# Session-owned SMPS physical-device design

Date: 2026-09-01
Status: proposed for user review
Scope: cross-game SMPS runtime ownership required by the sound-driver parity roadmap

## Decision

OpenGGF will model one Mega Drive audio device per audio presentation session. One
session-owned object will own the YM2612, PSG, physical write observer, physical snapshot,
render cadence, device initialization, and global driver commands. Music, SFX, temporary
music overrides, donor programs, and snapshot recreation will not create additional chip
pairs.

The migration will introduce a session-owned physical device and restricted write port while
the old presentation graph still uses separate private drivers. Those old drivers never
share the new device. The atomic presentation cutover binds one persistent logical
`SmpsDriver` to one session device, which is the final invariant: one session-owned driver
state machine over one physical device. This seam lets preparation, snapshot contracts, and
compatibility adapters move incrementally without creating an intermediate shared device
with several independent arbitration owners.

The rejected S3K stop-all branches `c0f125201` and `b2a7e2eca` are evidence only. They must
not be merged or cherry-picked wholesale. Their exact S3K write sequence and source-owned
command findings may be reimplemented at the new session boundary.

## Why this change is required

The shipped games have one sound driver and one YM2612/PSG pair. OpenGGF currently binds
logical and physical ownership together:

- `SmpsDriver` extends `VirtualSynthesizer`, so every driver owns chips, renders them, and
  snapshots them.
- The presentation factory constructs a fresh driver for music, standalone SFX, saved
  music overrides, donor music, and restored snapshots.
- `SmpsCompositeVoice` renders each driver's chips, and the presentation mixer sums the
  resulting streams.
- Hidden music overrides freeze an entire old driver and chip pair and later restore that
  physical state.
- `SmpsDriverSnapshot` embeds a physical synthesizer snapshot in every logical driver
  snapshot.

That model cannot express a global ROM command faithfully. A stop-all request may be
emitted by several drivers, retained by a hidden driver until much later, or use a donor
game's write sequence. It also makes driver construction observable as repeated generic
202-write chip initialization before an S3K-specific policy can be installed. Restore and
rollback can restore or silence the same conceptual hardware several times.

The same mismatch was already identified by the complete-run parity research: S1 saves
driver RAM for temporary music while one physical YM2612/PSG remains continuously live.
Freezing and restoring a whole chip is not a source-accurate substitute.

## Goals

1. Own exactly one YM2612 and one PSG per presentation session.
2. Initialize that physical device exactly once per session.
3. Render the physical device exactly once per forward outer-audio presentation.
4. Route every accepted logical SMPS write through one restricted session-owned port.
5. Snapshot and restore physical state exactly once, independently of the number of
   logical programs or overrides.
6. Make logical construction, snapshot preparation, discard, and teardown produce zero
   chip writes.
7. Keep device policy owned by the base game session. Donor content may supply program and
   sequencer behavior but cannot replace host hardware policy.
8. Preserve current S1 and S2 output while migration is in progress, including the exact
   S1 music and SFX oracle matches.
9. Support the shipped S3K device initialization and global-stop sequences: 85 writes at
   session initialization and 84 writes for `zStopAllSound`.
10. Preserve deterministic rewind, command rollback, diagnostic observation, and
    presentation thread ownership.

## Non-goals

- This design does not change the Nuked-OPN2 or PSG cores.
- It does not merge analog PCM or final mixed PCM into the driver-transaction parity
  contract.
- It does not enable a trace to hydrate or schedule runtime audio behavior.
- It does not use game names, zones, routes, trace frames, or known fixtures in shared
  runtime decisions.
- It does not require the dormant `AbstractSmpsAudioBackend` to become the authoritative
  output path. That backend receives a compatibility adapter until it is retired or
  migrated separately.
- It does not claim S2 or S3K frontier movement without authenticated reference evidence.
- It does not attempt to land the final one-driver architecture in a single unreviewable
  commit.

## Ownership model

### `SmpsPhysicalDevice`

`SmpsPhysicalDevice` is the sole physical audio owner in a presentation session. It owns:

- one `Ym2612Chip` and one `PsgChip` through composition;
- output sample rate, FM/PSG balance, mute and solo state, DAC interpolation, and PSG noise
  configuration;
- the physical chip-write observer and one stable physical identity;
- physical render scratch buffers and one render operation per forward presentation;
- direct physical snapshots and bounded physical mutation snapshots; and
- owner-thread enforcement for every write, render, capture, restore, rollback, and global
  command.

It does not own music/SFX sequencer lists, logical channel locks, priorities, saved music,
continuous-SFX state, or game-specific program data.

The physical device exists even when there are no logical SMPS voices. This is required for
chip tails, deterministic rewind, and consumption of global commands when no song is
active.

### `SmpsPhysicalPort`

Logical drivers receive a restricted `SmpsPhysicalPort`, not the device itself. A port is a
short-lived capability issued by the session for one authorized activation, admission, or
service epoch; it is not stored by a driver between epochs. The port exposes only the
physical mutations required by admitted driver behavior:

```java
public interface SmpsPhysicalPort {
    DriverIdentity owner();
    long epoch();
    void writeFm(int port, int register, int value);
    void writePsg(int value);
    void setInstrument(int channelId, byte[] voice);
    void playDac(int note);
    void stopDac();
    void selectDac(SmpsDacSelection selection);
    void forceSilenceFmChannel(int channelId);

    AdmissionToken captureAdmissionState(int fmMask, int psgMask);
    void restoreAdmissionState(AdmissionToken token);

    interface AdmissionToken { }
}
```

The exact names may follow surrounding code, but the boundary is fixed:

- the port never renders;
- it never exposes whole-device construction or replacement;
- it never exposes an observer setter or device policy setter;
- it validates the presentation owner thread before mutation; and
- admission rollback restores only the bounded state captured before an SFX admission and
  generates no observer writes.

Every call also validates that the capability belongs to the current session, current
logical owner, and still-open epoch. Hidden, replaced, outgoing, or discarded logical state
therefore fails before mutation even on the correct thread. Admission tokens are bound to
the device identity and epoch, are single-use, and reject stale, cross-session, or
cross-owner restoration. The raw port never escapes a session callback.

The port's DAC selection uses a stable ROM-backed source descriptor plus resolved live DAC
data. It never accepts an anonymous byte array whose dependency generation cannot be
recovered during rewind.

Logical source/service identity is carried as diagnostic context into port calls. That
context labels the origin of a write but cannot select the physical device or its policy.

### `SmpsDriverSession`

`SmpsDriverSession` is the presentation-session coordinator. It owns:

- one `SmpsPhysicalDevice` and its restricted write port;
- one immutable host `SmpsPhysicalPolicy` selected by the base `GameAudioProfile`;
- the retained global command/mailbox state for E2/FE-style commands;
- the one-time initialization state;
- physical command rollback and the session physical snapshot;
- deterministic ordering of logical driver services; and
- the stable physical diagnostic identity.

It is created with the presentation factory/registry/producer in `AudioManager`, and it is
closed with the producer. Donor registration and logical voice creation cannot reconstruct
or replace it.

The session provides separate operations for logical service and physical rendering:

```java
public final class SmpsDriverSession {
    public void initialize();
    public void activateMusic(SmpsDacSelection dac, SmpsMusicActivation activation);
    public SmpsServiceOutcome serviceForward();
    public int render(short[] destination, int stereoFrames);
    public SmpsDriverSessionSnapshot captureSnapshot();
    public PreparedRestore prepareRestore(
            SmpsDriverSessionSnapshot snapshot,
            DacDependencyResolver dependencies);
    public void commitRestore(PreparedRestore restore);
}
```

The session constructs its sole logical driver once and never exposes a presentation
`newLogicalDriver` factory. Legacy pre-cutover construction belongs only to compatibility
adapters. `serviceForward` uses coordinator-owned state; callers cannot reorder work or
include hidden state. It consumes pending global commands, commits an eligible pending
activation, then services the live logical driver in the source-owned order defined below.
Global physical stop is private to this retained-command path. `render` is called afterward.
Silent and reverse presentations neither service nor render the device; reverse PCM
continues to come from presentation history.

`SmpsServiceOutcome` reports `ORDINARY` or `GLOBAL_STOP_CONSUMED`; it does not mutate the
presentation registry. `AudioPresentationProducer` owns a narrow composite transaction over
the session and registry. It captures both participants, calls `serviceForward`, asks the
registry to clear sample/raw/presentation state without writes on `GLOBAL_STOP_CONSUMED`,
then commits both and publishes buffered diagnostics. Any pre-commit failure rolls both
participants back. The session and registry therefore remain dependency-independent while a
global stop is atomic across physical, logical, sample, and raw state.

### `SmpsPhysicalPolicy`

`SmpsPhysicalPolicy` is immutable game-owned physical-driver behavior. It is selected once
from the base game profile and installed on `SmpsDriverSession`:

```java
public interface SmpsPhysicalPolicy {
    SmpsWriteProgram boot();
    SmpsWriteProgram stopAll();
    SmpsWriteProgram activateMusic(SmpsMusicActivation activation);
}
```

The policy returns immutable ordered write programs. This makes boot, global stop, and the
currently constructor-owned DAC-enable action explicit, reviewable, and testable without a
live chip. `SmpsPhysicalDevice.apply(program)` is the only executor. Music activation is a
separate operation because constructing a sequencer must become physically pure.

The default policy preserves the existing S1/S2 initialization and stop behavior until
their exact source-closed sequences are implemented. The S3K policy emits the shipped
`fix_sndbugs=0` sequences:

- initialization: the 84-write `zStopAllSound` program followed by the source-owned extra
  YM part-I `$2B = 0`, for 85 total writes; and
- global stop: exactly 84 writes in channel order 6, 0, 1, 2, 4, 5, followed by PSG
  `$9F,$BF,$DF,$FF`, YM part-I `$2B=0`, and YM part-I `$27=0`.

The 68k-owned `PSGInitValues` burst observed at S3K boot tick 3 is outside this Z80 physical
policy and remains a separate frontier. The policy is sourced from the disassembly, not
inferred from a trace's first observed row.

Shared code consumes only the policy interface. It does not branch on game identity.

Physical policy and logical transition policy have different owners. Every incoming music
activation carries a typed `SmpsLogicalTransitionPolicy` captured with its ROM-backed program
entry. It describes the source game's song-load and override save/restore semantics,
including which existing SFX/locks survive and which first-service reassertion writes occur.
The incoming music program owns this decision: base music uses the base program policy;
donor music uses the donor program policy. Existing donor or base SFX do not choose the
transition, and the host session still exclusively owns physical boot/global-stop policy.
Shared code executes the typed transition without inspecting a game name.

### Logical `SmpsDriver`

The final logical driver no longer extends `VirtualSynthesizer` and does not implement a
physical render owner. It owns:

- sequencers and their service order;
- music/SFX admission, priority, channel ownership, and override RAM;
- PAL cadence and continuous-SFX state;
- logical rollback journals and logical snapshots; and
- logical service/admission/contention diagnostics.

It forwards accepted physical mutations to `SmpsPhysicalPort`.

During migration, existing constructors and direct tool APIs remain available through a
legacy standalone adapter that privately owns a session/device. That compatibility path
must not be used by authoritative presentation code, and a structural guard enforces the
boundary.

### `SmpsSequencerHost`

`SmpsSequencer` currently casts its `Synthesizer` back to `SmpsDriver` for service callbacks,
inactive-track reconciliation, continuous-SFX state, and the S1 special-voice bug. A narrow
logical host interface replaces those casts before physical sharing begins:

```java
public interface SmpsSequencerHost {
    void onServiceBegin(SmpsSequencer sequencer);
    void onServiceEnd(SmpsSequencer sequencer);
    void reconcileInactiveTrack(SmpsSequencer sequencer, SmpsSequencer.Track track);
    ContinuousSfxState continuousSfxState();
    byte[] s1SpecialSfxVoiceForBug(int voiceId);
}
```

The concrete methods may be split into smaller ports where existing responsibilities make
that clearer. The architectural requirement is that sequencing depends on logical host
semantics and a physical write port separately; it cannot recover a concrete driver by
casting the write target.

## Pure construction and explicit activation

Logical object construction and snapshot materialization must be chip-write-free.
Currently, sequencer/music construction binds DAC data and writes YM `$2B = $80`. Those
effects move to an explicit activation operation.

Factory flow becomes:

1. parse and validate ROM-backed program data;
2. construct detached sequencer/program state against a non-writing preparation context;
3. prepare an immutable logical memento and activation describing the program transition and
   required physical actions;
4. publish the pending memento and activation transactionally without replacing the
   persistent driver; and
5. let the next eligible forward session service either cancel or commit that activation.

Prepared activation can be discarded without writes. A failed activation captures the
physical device once and the affected logical journals, then restores both directly so the
same queued command can retry from byte-identical state.

Command application never commits physical activation. At `serviceForward`, a retained
global stop has first priority. If present, the session applies it once, cancels every
pending activation, clears logical/presentation state without cleanup writes, and ends that
service boundary. Otherwise the session commits the pending activation and then services
the live logical state. A replacement cannot therefore write immediately before an older
FE/E2 stop.

## Service and render order

The authoritative cadence remains `AudioPresentationProducer`:

1. Apply pending presentation commands transactionally.
2. On `FORWARD`, begin the single SMPS session service boundary.
3. Consume one retained global command, if present.
4. If no global stop consumed the boundary, commit one eligible pending activation.
5. Service the one live logical driver in its ROM-defined music/SFX order.
6. Render the physical SMPS device for exactly the required source-frame span.
7. Mix sample/raw voices and publish the final packet.

`SILENT` applies commands but does not service or consume retained driver commands. Reverse
presentations use stored PCM and do not service or render live SMPS state.

Fast-forward changes the requested source-frame span, not logical service count. One outer
presentation performs one logical service, asks the device to render the complete
`sourceFramesNeeded` span, and publishes one output packet. The device may chunk its internal
render buffer, but chunking creates no extra service boundary and advances the physical
clock by exactly that span. SMPS, sample, and raw sources are resampled against the same
outer packet interval at integral and fractional forward rates.

Hidden overrides are not active logical owners and cannot service or write. The final
presentation session owns one persistent logical `SmpsDriver` from session creation. SFX
without music are admitted into that driver; starting music mutates the same logical owner.
Temporary music overrides are ROM-style logical RAM save/restore within it, not driver
replacement. Thus one channel-lock table and one continuous-SFX state own every shared-device
write. The atomic presentation cutover is not accepted while a standalone or override
logical driver remains capable of servicing the shared device.

## Global commands

### FE: stop SEGA PCM, retain driver stop

For S3K's shipped path:

1. Applying FE stops raw SEGA PCM immediately.
2. It records one session-global retained stop request.
3. Submission emits zero driver writes.
4. Silent/reverse presentation, logical replacement, override push/restore, and snapshot
   preparation do not consume the request.
5. The next forward SMPS session service applies the host policy's stop sequence exactly
   once, clears every logical music/SFX/override/standalone state silently, clears the
   retained request, and removes sample/raw presentation state as required by the command.
6. This works when no logical SMPS voice exists because the physical device is session-owned.

### E2/E3/E4

S3K command IDs follow the shipped table:

- E2 is global `zStopAllSound` and uses the same retained session stop path;
- E3 is PSG mute and remains an explicit parity gap until implemented from source;
- E4 stops SFX only; and
- speed shoes are not E2/E3 commands. S3K monitor pickup and expiry set the semantic speed
  multiplier to 8 and 1 respectively, matching direct `zTempoSpeedup` writes.

`AudioManager` records each logical command once. Timeline recording is the sole mirror into
the presentation resolver; command-specific methods cannot submit the same presentation
mutation a second time.

## Music overrides, donor content, and standalone SFX

### Temporary music overrides

Override stack entries store the ROM-defined saved logical RAM region and ROM-backed
dependency descriptors inside the one persistent driver. They never own a chip, driver, or
physical snapshot. Push and restore keep the same physical and logical identities. Resume
uses an explicit game-owned logical transition operation derived from the game's save/restore
routine; it emits exactly the source-required first-service writes and no generic refresh.
Those writes occur during the next real logical service, never during construction or
snapshot materialization. S1 and S2 oracle tests cover the exact first service and next PCM
after override restoration before cutover is accepted.

### Donor content

Donor programs retain donor-owned data, sequencer configuration, coordination handlers, DAC
dependencies, admission rules, and logical transition policy. The base session retains
physical policy, sample rate, chip configuration, physical observer identity, and global
command behavior.

Therefore:

- S3K host plus S1/S2 donor content still emits the S3K 84-write stop sequence;
- S1/S2 host plus S3K donor content retains the host's default sequence; and
- donor replacement or restoration cannot reconstruct, retune, or re-identify the device.

### Standalone SFX

There is no standalone logical owner in the final presentation model. The persistent driver
admits SFX even when no music sequencer is active. Starting music follows the incoming
program's source-owned song-load transition on that same driver: S1 music preserves the SFX
state its ROM save/load path preserves; S2 and S3K music clear the states their song-load
routines clear. In mixed base/donor cases, the incoming music program's captured
`SmpsLogicalTransitionPolicy` decides. Shared code never branches on a game name. Creating
music never initializes or replaces the physical device.

## Snapshot, rewind, and rollback

### Snapshot shape

`SmpsDriverSnapshot` becomes logical-only. It contains sequencers, descriptors, locks,
latches, PAL cadence, priority, continuous-SFX state, and other logical fields. It does not
contain `VirtualSynthesizer.Snapshot` or any physical mutation state.

The presentation/session snapshot contains exactly one `SmpsDriverSessionSnapshot`:

```java
public record SmpsDriverSessionSnapshot(
        boolean initialized,
        PendingGlobalCommand pendingGlobalCommand,
        SmpsSessionProfileFingerprint profile,
        SmpsSourceDescriptor selectedDacSource,
        SmpsPhysicalDevice.Snapshot physical) { }
```

The physical snapshot is present even when the logical voice inventory is empty.
`SmpsSessionProfileFingerprint` identifies the immutable base profile generation, physical
policy, output settings, and ROM dependency generation. Changing the base profile is a hard
presentation-session boundary: close the old session, clear incompatible presentation and
rewind history, build a new session, and initialize it once. Restore preparation rejects a
profile-fingerprint mismatch before mutation. Donor program replacement does not change the
base-session fingerprint.

### Restore transaction

Restore is prepare-then-commit without replacing the persistent driver object:

1. Resolve dependencies and materialize an immutable logical memento against a non-writing
   preparation context.
2. Capture no new physical device and emit no writes during preparation.
3. On dependency failure, discard the prepared memento without writes; live registry,
   physical state, pending commands, history, and reverse cursor remain unchanged.
4. At commit, restore the memento into the existing persistent `SmpsDriver` instance without
   calling `voice.stop()` or any physical silence operation.
5. Select the resolved live DAC dependency, then restore the one physical snapshot directly.
   Direct restoration emits zero chip observer events.
6. Reapply registry-authoritative mute/solo controls.
7. Install pending global-command state atomically with the physical and logical snapshots.

Held rewind selection does not repeatedly restore the live device. The selected physical
snapshot is restored exactly once when reverse playback releases into the chosen state.

An empty logical inventory can still restore and render an envelope/DAC tail because the
physical device has independent lifetime.

### Command rollback

A live command transaction captures:

- the session physical mutation state once; and
- a logical mutation journal for each affected logical driver.

Command, dependency, activation, or driver failure restores logical journals first, then the
session's live DAC reference and physical state once, without producing rollback writes.
Global commands never capture or restore the same physical device once per voice. The port's
bounded admission token remains available for channel-scoped SFX admission because
owner-thread serialization prevents another physical mutation from interposing.

## Diagnostics and thread ownership

Logical and physical identities become explicit:

- service, admission, contention, and logical lifecycle events identify the logical driver
  and sequencer;
- every resolved YM/PSG write identifies one stable session physical device plus its logical
  source context; and
- physical identity does not change across music replacement, override, donor playback,
  standalone SFX, or rewind restoration.

Observers remain diagnostic only and are excluded from snapshots. Physical events are
buffered during the session transaction and published only after physical and logical state
commit. Observer callbacks cannot change acceptance or runtime behavior: an observer
exception is quarantined and reported through the diagnostic error sink, but it does not
roll back committed audio state, escape the owner loop, or cause an event prefix to be
replayed. Command, dependency, or driver failures before commit discard buffered events and
roll back from byte-identical state.

The presentation producer thread is the sole device owner. Device write, render, physical
capture/restore, mutation capture/rollback, policy initialization, and global stop all
fail before mutation when invoked off-owner-thread. Detached logical preparation is allowed
off the device only because it cannot write.

## Migration plan boundary

The implementation is divided into independently reviewable, test-first slices. No slice
may claim the final single-device invariant before the corresponding structural and runtime
tests pass.

### Slice 1: replace inheritance with behavior-preserving composition

- Make each existing `SmpsDriver` privately delegate to a `VirtualSynthesizer` rather than
  extend it.
- Preserve existing constructors, `AudioStream` behavior, test override seams, and output.
- Convert inherited calls to explicit delegation.
- Do not share devices or change render cadence in this slice.

This creates a real extraction boundary without changing behavior.

### Slice 2: remove the sequencer reverse dependency and constructor writes

- Introduce `SmpsSequencerHost` and eliminate casts from `SmpsSequencer` to `SmpsDriver`.
- Split logical preparation from physical activation. A private compatibility activation
  coordinator applies the same writes to each legacy driver's private device, preserving
  behavior until the presentation session exists.
- Prove logical construction, recreation, and discard produce zero chip writes.

### Slice 3: define split logical and physical snapshot contracts

- Introduce the logical-only and session-physical snapshot types without cutting the
  presentation graph over yet.
- Retain a temporary combined compatibility wrapper in the old presentation graph and the
  private standalone adapter until the atomic cutover consumes the split contracts.
- Add preparation-purity tests before presentation restore changes ownership.

### Slice 4: introduce the session/device composition root

- Create `SmpsPhysicalDevice`, `SmpsPhysicalPort`, `SmpsDriverSession`, and
  `SmpsPhysicalPolicy` at `AudioManager` presentation construction.
- Keep the old presentation drivers and their private composed devices authoritative through
  this slice while the new session remains inert; do not expose a multi-device lease
  abstraction through the new session.
- Inject the one session through the presentation factory, registry, and producer, but keep
  the old graph authoritative until the atomic cutover slice.
- Ensure composing the not-yet-authoritative session emits no writes.

### Slice 5: cut presentation atomically to one physical device

- Bind every active presentation logical state to the sole session device and one shared
  persistent arbitration owner.
- Move presentation restore and command rollback to the single session snapshot boundary.
- Stop rendering through `SmpsCompositeVoice`; service logical state through the session and
  render one physical packet afterward.
- Store overrides as logical RAM save areas inside the persistent driver and route no-music
  SFX into that same driver.
- Move observer buffering, output configuration, mute/solo, DAC selection, physical command
  transaction ownership, and base-profile fingerprint validation into the session in this
  same cutover. None may remain per-driver when shared hardware becomes authoritative.
- Keep a standalone direct-read adapter for tools until their migration is complete.

### Slice 6: enforce and simplify the new ownership

- Add structural guards forbidding physical synth ownership or rendering from logical
  drivers and voices.
- Remove the old presentation combined-snapshot wrapper and compatibility activation path.
- Preserve the one-driver standalone session adapter only for supported direct tools.

### Slice 7: reintroduce source-owned S3K command parity

- Add the exact S3K policy at the session boundary.
- Implement FE/E2/E4 and direct speed multiplier semantics.
- Delete the parity helper's duplicate stop emitter and use the production policy.
- Keep E3 explicitly unresolved until its PSG-mute behavior is implemented and validated.

### Slice 8: retire compatibility paths

- Migrate direct tools and, where still needed, the dormant backend to the standalone session
  adapter.
- Remove combined physical/logical snapshots and the private legacy devices superseded by
  standalone session adapters.
- Preserve public compatibility only where a current supported caller still requires it.

## Acceptance tests

The implementation plan must include at least these executable contracts:

1. One physical and one persistent logical identity survive music, override, donor,
   no-music SFX, and rewind transitions.
2. One session initialization occurs. S3K emits the exact ordered 85 `(chip, port, register,
   value)` tuples, beginning at YM part II `$82=$FF` and ending at YM part I `$2B=$00`, with
   no preceding generic 202-write initialization.
3. S3K global stop emits the exact ordered 84 tuples; S1/S2 compatibility stop remains
   exactly 202 writes until replaced by separately source-closed policies.
4. Constructing or recreating logical state emits zero initialization or activation writes.
5. Multiple logical operations result in one physical render and one PCM packet.
6. Integral and fractional fast-forward perform one logical service, advance the physical
   device by exactly `sourceFramesNeeded`, and publish one consistently resampled packet.
7. Empty logical state can render and rewind a physical envelope/DAC tail.
8. Silent and reverse modes neither service nor render the live device.
9. Presentation snapshots contain one physical snapshot and a profile fingerprint; logical
   snapshots contain no physical state.
10. Prepare, commit, discard, and failed restore emit zero observed chip writes and remain
    atomic; stale-profile restore fails before mutation.
11. Restoring a pending FE/E2 snapshot emits zero writes during restore and exactly one stop
    sequence at the next forward service.
12. A retained FE/E2 outranks and cancels pending activation, including across music
    replacement, override push/pop, no-music SFX, and snapshot restoration.
13. FE stops raw PCM immediately, emits zero driver writes on submission, and emits one S3K
    84-write stop at the next forward service even with no logical program active.
14. E2 records one logical/presentation command and consumes one global stop.
15. Global stop clears active music, override save areas, SMPS SFX, sample, and raw state;
    subsequent frames produce no stale writes or latent old-program burst.
16. E4 removes all SFX ownership while preserving music logical state and continuous physical
    music output.
17. S3K speed-shoes pickup and expiry set multiplier 8 and 1 and never route through E2/E3.
18. Host session policy wins for every base/donor game pairing, and donor replacement never
    rebuilds, retunes, or re-identifies the device.
19. Starting music after no-music SFX retains the one driver/device and follows the incoming
    music program's source-owned song-load preservation or clearing semantics. A complete
    base/donor matrix proves base music after donor SFX and donor music after base SFX for all
    three source policies.
20. Override pop keeps both identities and produces the exact source-owned first-service
    writes and next PCM; S1/S2 oracle assertions cover that boundary.
21. Command, dependency, activation, and driver failures roll physical/logical state and the
    command retry position back exactly once from byte-identical state.
22. Buffered diagnostics publish only after commit; observer exceptions are quarantined,
    never change audio acceptance/state, and never cause a prefix to replay.
23. Physical entry points reject off-owner-thread calls before mutation.
24. Hidden, outgoing, stale-epoch, cross-owner, and cross-session port or admission-token
    calls fail before mutation.
25. Logical service identities may vary, but every chip write reports the same session
    physical identity and the authorized logical context.
26. Replacing the base profile closes the old session, clears incompatible rewind history,
    and initializes one new fingerprinted session; donor changes do not.
27. SFX admission channel-bounded rollback remains byte-exact on the shared device.
28. Physical controls and output settings apply once per session, never once per logical
    program.
29. Structural guards forbid `SmpsDriver` inheritance from `VirtualSynthesizer`, physical
    snapshot fields in logical snapshots, physical rendering from logical voices, durable
    physical-port storage, and presentation construction of private chip pairs after the
    corresponding migration slices land.

Every slice also runs the relevant driver, presentation, rewind, donor, diagnostic,
allocation, and architecture tests. The S1 GHZ 14,690-tick music match and 1,967-tick/8-
dispatch SFX match are hard regression gates. The known S2 first divergence must not move
earlier. Ordinary and fresh-JVM guard suites remain release gates.

## Rejected alternatives

### One complete session-owned driver in one change

This is the correct final shape, but the current `SmpsDriver` also owns sequencers, locks,
cadence, continuous-SFX state, rollback, chips, rendering, and diagnostics. Moving override,
standalone, donor, snapshot, and tool behavior atomically would be too large to review
safely. The selected physical-device seam converges to this invariant incrementally.

### Session-global stop helper with private per-voice chips

This would make the immediate S3K 84-write test pass while ordinary playback, donor policy,
rewind, and initialization still model several physical devices. It creates two competing
definitions of physical truth and is rejected.

### Arbitrarily elect one existing voice as the global emitter

This fails when no voice exists, when the selected voice belongs to a donor game, when
hidden overrides retain pending state, and when restore calls `stop()` on outgoing voices.
It was implemented experimentally and rejected by review.

## Evidence and branch handling

The sound-driver roadmap branch at design time is
`feature/ai-sound-driver-roadmap-completion` at `e80688882`. It has:

- the S2 admission ownership and PSG3 write policies integrated and independently reviewed;
- the unbound S3K Java v2 experiment removed from production bytecode;
- ordinary suite result 16,083 tests, zero failures/errors, 34 skips; and
- fresh-JVM guard result 587 tests, zero failures/errors/skips.

The S3K experimental branch is preserved as review evidence. Selective semantics may be
reimplemented, but its registry emitter election, per-voice policy ownership, pending-state
placement, duplicate E2 submission, and restore-time stop behavior are explicitly rejected.

The architecture migration will use a fresh isolated branch from the current roadmap branch.
It will not switch the main workspace, weaken the S1 gates, activate unauthenticated
producers, update the frontier log without real movement, or push implementation scaffolding.
