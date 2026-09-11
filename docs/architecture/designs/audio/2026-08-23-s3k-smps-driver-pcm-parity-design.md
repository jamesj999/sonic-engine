# Sonic 3&K SMPS Driver and PCM Parity Design

## Status and decision

Build a bounded, source-authenticated Sonic 3&K SMPS parity subsystem around
the existing runtime driver. It will inventory every reachable retail music
and SFX stream path, compare exact chip-write behavior, compare native-rate
chip output, and finally compare the mixed presentation signal. It will then
close proved driver gaps in small, reviewable slices.

This is an accuracy program, not a second emulator. The authority is the shipped
locked-on S&K build with `SonicDriverVer = 4`, `fix_sndbugs = 0`,
`FixMusicAndSFXDataBugs = 0`, and `FixBugs = 0`, plus the authenticated ROM,
checked-out disassembly, pinned Genesis Plus GX core, and reviewed chip
implementations. Those four build-condition identities are mandatory manifest
fields and source-parser inputs; a Sonic 3-only driver branch cannot be accepted
as locked-on evidence. Trace and PCM captures remain comparison evidence; they
never select gameplay behavior or hydrate runtime state.

OpenGGF also deliberately exposes `0x100+` compatibility music loaded from the
standalone Sonic 3 driver tables embedded in the combined ROM. Those entries are
not silently treated as version 4. They form a second explicit dialect,
`STANDALONE_S3_V3_COMPAT`, authenticated against the embedded version-3 driver
and its own build-condition tuple. The reported first slice is
`LOCKED_ON_S3K_V4`; Phase 3 inventories both runtime-exposed dialects. Evidence
from one dialect cannot satisfy the other.

The first implementation slice covers the three current audible reports:

- Collapse must retain its source-authenticated staggered FM/PSG modulation and
  tail instead of ending dry or leaking one stereo side;
- Spindash Release must reproduce its full source path and audible release;
- the four note-fill tones in Invincibility must stop at the shipped boundaries
  rather than fading softly.

No work from this program is merged into `develop` until its automated gates
are green and the corresponding human listening cases improve. Passing a PCM
metric does not waive a source or chip-write mismatch.

## Requirements

### Goals

1. Produce a finite, regenerable inventory of every reachable S3K retail SMPS
   stream construct and every driver-global audio behavior.
2. Classify each reachable behavior as exact, partially modeled, missing, or
   intentionally unavailable, with a source citation and an executable proof.
3. Detect wrong stream interpretation, modulation/envelope timing, note fill,
   takeover/restore, priority, jingle, tempo, pause, fade, DAC, PSG, and mixing
   behavior before it reaches a listening build.
4. Add deterministic parity gates at three boundaries:
   chip writes, native-rate chip PCM, and final presentation PCM.
5. Correct proved runtime gaps through typed driver/profile ownership, without
   sound-ID, zone, movie, frame, or trace carve-outs.
6. Preserve snapshot, rewind, rollback, observer, and atomic publication
   semantics for every new piece of persistent or pending audio state.
7. Keep Sonic 1 and Sonic 2 behavior unchanged unless separately proved by
   their own source and native evidence.

### Non-goals

- complete Z80 emulation inside the Java playback runtime;
- complete-game bit-identical PCM as the initial completion criterion;
- runtime ingestion of BK2, trace, native write, or PCM evidence;
- tuning constants to one recorded movie, waveform, or listening report;
- one monolithic rewrite of the existing shared SMPS architecture;
- enabling unproved S1/S2 behavior by symmetry;
- treating a WAV golden file as sufficient evidence of driver correctness.

### Constraints

- Runtime music, SFX, voice, sample, and table bytes come from the authenticated
  user ROM. Research artifacts may describe bytes but cannot supply them.
- All source decisions name a dialect and use that dialect's exact authenticated
  build-condition tuple; the first slice uses the locked-on tuple above.
- Raw native traces and PCM stay below `$AGENT_SCRATCH_ROOT/tasks` in a task
  created with `agent-scratch new`; neither `/tmp` nor a repository build folder
  is a retained capture store. Only compact, bounded, regenerable manifests and
  calculation artifacts are committed.
- Every capture has two clean A/B runs, exact provenance hashes, fixed caps,
  explicit overflow/fault terminals, and byte-identical canonical output.
- JDK 21 and all three authenticated ROMs are required for the final regression
  comparison.
- New shared runtime behavior must be selected through the existing typed
  profile/configuration boundaries, never a game-name check.

### Acceptance criteria

The program is complete when:

1. every statically reachable S3K retail stream path has one classification and
   no decoder state, branch, call, loop, or command is silently unclassified;
2. every driver-global behavior in the finite service inventory has an explicit
   classification and verification owner;
3. every distinct reachable behavior family has a source-derived test plus at
   least one native execution case when the behavior is dynamically reachable;
4. the three reported first-slice cases pass their declared exact write and
   same-core replay gates, structural PCM/onset/tail gates, snapshot/rollback
   gates, and the human listen checklist;
5. no test that passes on the updated integration baseline newly fails in the
   feature branch or merged result;
6. all deliberate remaining differences are recorded precisely rather than
   hidden by an aggregate score.

Complete-game bit-identical PCM is a later tier. It becomes achievable only
after clocks, initial chip phase, native chip dialect, DAC production, PSG
noise, resampling, and presentation configuration are identical. This design
creates the evidence and boundaries required to pursue that tier without
claiming it prematurely.

### Assumptions and risks

- The locked-on retail ROM and local disassembly remain byte/source consistent.
- The existing headless GPGX observer can be extended with diagnostic-only chip
  taps without widening the production trace authority.
- Some observable differences will be chip-dialect or presentation differences
  after the driver is exact. The layered gates prevent those from being blamed
  on stream interpretation.
- Static SMPS control flow can contain loops whose iteration count is data
  dependent. The inventory therefore uses explicit bounded symbolic state and
  fails rather than treating an unbounded exploration as complete.
- PCM comparisons can be misleading when clock phase, chip dialect, or resampler
  phase differs. Phase is an explicit matrix dimension, never an alignment
  chosen after seeing results. Cross-core amplitude error is diagnostic unless
  equality or a mathematically conservative finite-state bound is proved.

## Exploration synthesis

### Existing engine and source findings

OpenGGF already has one shared SMPS runtime, game-owned configuration/profile
selection, atomic YM publication, snapshots, rewind, and diagnostic observer
boundaries. The correct course is to extend those owners, not create a parallel
S3K player.

The current defects demonstrate why command-presence tests are insufficient:

- Collapse is four coordinated tracks. Three staggered FM tracks use modulation;
  PSG3 changes noise/modulation while five tied notes increase attenuation. A
  correct early FM key-off fixed the gross duration but did not prove the
  modulated tail or PSG waveform.
- Spindash Release exercises a different short-lived release path and remains a
  useful independent SFX lifecycle case.
- Invincibility uses repeated `smpsNoteFill $05` on several FM tracks. Its four
  sharply terminated tones directly exercise cadence and note-fill ordering,
  not merely voice values.

Earlier audits also identified driver-global risk areas: carry-based tempo
service, speed-shoes extra services, SFX-before-music ordering, first-admission
silencing, completion restore, modulation-envelope interpretation, fades,
jingle/speed ownership, StopAll, and SEGA PCM exclusivity. These belong in the
global inventory even when the first correction slice does not modify them.

### PCM-oracle exploration

The current tools already expose useful boundaries:

- `ChipWriteObserver`, `YmWriteTimeline`, and the headless GPGX audio observer
  can establish canonical ordered YM/PSG/DAC writes and timing;
- `Ym2612Chip.renderOneSample` supplies an OpenGGF native YM boundary;
- `AudioPresentationProducer`, `AudioPresentationMixer`, and bounded playback
  traces supply the final presentation boundary.

Missing pieces are a retained diagnostic native stereo YM tap, a native PSG tap
or standalone pinned PSG harness, an explicit DAC production tap, authenticated
initial chip state, and compact manifest tooling. Native chip streams must stay
separate unless a common master-cycle representation is proved; silently
resampling one to the other would erase the evidence being measured.

### Rejected alternatives

#### Duplicate Z80 sound-driver emulation

Running the original driver or a new Z80 interpreter inside normal playback
would duplicate state ownership, complicate rewind, and turn a bounded fidelity
effort into a second emulator. The headless core remains a diagnostic oracle.

#### WAV-golden-only testing

A final waveform can match while register ownership or driver state is wrong,
and it can differ solely because of a resampler phase. WAV comparison is useful
only after exact write and native-chip boundaries are independently checked.

#### Fixing only the three named sounds

Sound-ID branches would conceal shared interpreter and scheduler gaps. The
three reports are acceptance fixtures for general source-owned behavior families.

## Architecture decision

Add four cooperating, bounded components:

1. `S3kSmpsReachabilityInventory` statically decodes authenticated retail SMPS
   streams into a canonical finite behavior inventory.
2. `S3kDriverServiceInventory` records the finite non-stream/global driver
   behavior set and its source/runtime/evidence classification.
3. `S3kAudioParityManifest` stores compact write, chip-PCM, and final-PCM oracle
   cases with exact provenance and coverage links.
4. Existing runtime profiles and handlers receive only the smallest corrections
   proved by an inventory gap and a failing parity case.

Inventory and parity tooling live outside gameplay packages. Runtime code does
not read their JSON, recognize their case IDs, or depend on diagnostic classes.
Tests mechanically bind production typed rules to the canonical artifacts.

## Feature design

### Authenticated retail reachability inventory

The inventory builder opens the locked-on ROM through the existing verified ROM
authority and starts from every production music, normal-SFX, special-SFX, and
continuous-SFX table entry. Each work item is immutable and contains:

```text
source space and bank
program counter
track dialect/type
call stack
bounded loop-counter state
coordination-flag state needed to select a branch
voice/sample/table authority key
abstract shared-driver memory projection
external-event class and queue/continuous-SFX projection
bounded active-stream overlay produced by COPY_MEM
```

The decoder follows notes, durations, calls, returns, loops, conditional jumps,
and every S3K coordination flag. Its finite external-event alphabet contains
music/SFX queue admission, continuous-SFX update/stop, ring-speaker toggle,
pause/fade/jingle/system commands, and service entry. It explicitly models the
bank window, overlapping saved-song/SFX RAM, queue state, continuous-SFX state,
`FF 01` nested sound invocation, and the bounded stream overlay written by
`FF 03`/`COPY_MEM`. Unknown shared bytes are conservative finite abstract values;
the decoder explores every branch they can select instead of assuming zero.

Conditional paths are both explored unless a ROM constant proves one
impossible. A state is deduplicated by its complete semantic tuple, including
shared-memory projection and active overlay, not only its PC. The tooling rejects
malformed input or an unrepresentable state, but it does not reject shipped bugs
merely because they are unsafe. The builder rejects:

- an unknown command or operand shape;
- a target outside the authenticated source region;
- an impossible tooling stack underflow or state outside the declared abstract
  domain;
- arithmetic overflow;
- a loop or state count beyond a declared cap;
- a reachable path without a terminal, proven cycle, or bounded loop summary.

Proven cycles are represented as graph strongly connected components with their
entry/exit edges and state-changing commands. They are not unrolled forever.
Caps are fixed in the artifact schema and tested at N and N-1 boundaries.

Source-defined unsafe behavior has a separate classification. A retail-reachable
unchecked return-stack overflow, FM3 special-mode overwrite, active-stream copy,
or envelope over-read is modeled with `source_behavior = SHIPPED_BUG` and its
exact `fix_sndbugs = 0` effect. If its preconditions are impossible for the
authenticated retail tables, a mechanical proof sets status `UNREACHABLE`.
Neither case is hidden as an unsupported decoder path.

The canonical inventory records, for every behavior family:

- stable family key and source citation;
- reachable track types and source entries;
- operand/value domains and control-flow shapes;
- runtime handler/config/profile owner;
- status: `EXACT`, `PARTIAL`, `MISSING`, or `UNREACHABLE`;
- orthogonal `source_behavior`: `NORMAL` or `SHIPPED_BUG`;
- orthogonal `timing_status`: `EXACT`, `PARTIAL`, or `UNAVAILABLE`;
- source-derived reason and test/evidence IDs;
- dynamic execution count from retained captures when available.

Static reachability and dynamic coverage are separate fields. A zero capture
count never makes a statically reachable behavior unreachable.

### Driver-global service inventory

Stream decoding cannot observe behaviors selected outside bytecode. A second
finite inventory covers:

- VInt service order and carry-based tempo behavior;
- speed-shoes extra music services and counter phase;
- SFX admission, channel claims, priority, override, and restore;
- normal/special/continuous SFX lifecycles;
- alternating ring-speaker state, pan selection, and queue ownership;
- pause/unpause, fade in/out, and StopAll;
- 1-up/invincibility/other jingle push and restoration;
- music change and speed ownership across level/special-stage transitions;
- DAC service, sample playback, and FM6/DAC ownership;
- SEGA PCM exclusion and direct-DAC path;
- PAL full-driver repeat behavior.

Each row names the shipped source branch, its semantic inputs, the production
owner, snapshot state, evidence boundary, and status. The inventory is exhaustive
against a checked source-label list so adding or omitting a known service family
fails a guard.

S3K decoding is strict at runtime as well as in tooling. An S3K-specific handler
may delegate only the explicitly inventoried dialect-neutral commands. An
unknown S3K coordination flag must fail the service transaction; it cannot fall
through to an S2/default semantic. A production guard binds every reachable
inventory command to exactly one permitted handler path.

### Parity layer 1: chip-write contract

Schema `openggf.s3k-audio-write-parity.v1` records canonical groups with:

- ROM, BK2, complete GPGX source bundle, diagnostic patch, native build recipe,
  artifact lock, managed BizHawk assemblies, compiler/toolchain, sync settings,
  region, output rate, and runtime-configuration hashes;
- OpenGGF source commit, source-tree identity, dirty status, reproducible JAR or
  test-artifact hash, JDK/Maven identity, and runtime-configuration hash;
- the exact dialect key and that dialect's authenticated source-condition tuple;
- semantic service/track owner, source PC, group ordinal, and source citation;
- master-cycle timestamp and stable source ordinal;
- YM port/register/value, raw PSG byte plus effective latch/register/value, or
  explicit DAC sample/value event;
- terminal counts, ordered digest, overflow/fault/DMA markers.

The global coordinate is Mega Drive master cycles since authenticated core
reset. Every row also records the containing VInt ordinal, service-entry master
cycle, and driver-relative delta. Clock conversion is fixed by that dialect's
authenticated clock/profile manifest. There is no case-selected alignment or
shift. If
OpenGGF does not yet model the absolute service-entry phase, the row has
`timing_status = PARTIAL`; exact order/value and relative source costs may still
be proved, but the behavior status cannot be `EXACT`.

Native ownership is a typed diagnostic transaction, not a register fingerprint.
At service entry the compiled observer records ROM identity, Z80 PC, service
kind/ordinal, active track base/type/channel, bank, source pointer, and owner
generation. Each subsequent source-instruction and chip-write row carries that
transaction ID and a dense per-transaction ordinal until an explicit terminal.
The compiler validates each PC/driver-state predicate against the disassembly
and ROM. A missing, overlapping, or interrupted join poisons the whole group.

Exact ordered equality is required in the global coordinate. No register/value
or timing mismatch can be waived by PCM similarity.

### Parity layer 2: native-rate chip PCM contract

Schema `openggf.s3k-chip-pcm-parity.v1` records these explicit boundaries:

- `YM2612_MIX_STEREO`: signed left/right output immediately after one pinned
  YM internal sample is generated, including its DAC/FM6 contribution, before
  output resampling, presentation gain, or the PSG mix;
- `PSG_STEREO_NATIVE`: signed left/right chip contribution after tone/noise,
  attenuation, and configured chip panning, but before output resampling or the
  presentation mix;
- `DAC_LATCH_MONO`: the held signed DAC code immediately after S3K DPCM decode
  and YM DAC-register update, before optional interpolation, gain, filtering,
  FM6 panning, or FM synthesis;
- sample rate/clock ratio, phase residue, reset/state digest, and channel layout;
- source write-group digest and exact window anchors;
- signed sample format, frame count, ordered PCM digest, peak/RMS/energy, onset,
  and tail indices;
- per-channel or mono sample blocks only when within the compact fixture cap.

These taps do not exist generally today and are Phase-1 deliverables before the
corresponding PCM gate can be claimed. The native patch emits each sample at the
named core boundary. OpenGGF adds package-private diagnostic taps at the
equivalent production step; PSG is stereo rather than an ambiguous mono sum. A
standalone PSG harness is acceptable for core conformance only and is not called
an integrated GPGX tap until clock, reset, panning, and write scheduling are
proved identical.

The manifest's clock epoch is reset master cycle zero. YM internal sample `n`
is anchored by the reviewed `1008`-master-cycle GPGX interval and its explicit
first-sample phase. PSG and DAC retain their own integer clock/frontier fields;
conversion to master cycles uses checked rational arithmetic and never resamples
the stored native stream. Reset digest includes all envelope/operator phase,
feedback, LFO, timer, DAC latch/interpolator/filter, PSG latch/counter/LFSR,
and pending-write state required by the tap.

Exact PCM acceptance is deliberately same-core/state:

1. replay the authenticated native driver write group and initial state through
   the pinned native core;
2. replay OpenGGF's canonical write group from the same global coordinates and
   initial state through that same pinned native core; and
3. require byte-identical tap output and terminals.

This proves the audible consequence of driver differences without conflating a
Java chip-port difference. Native self-replay and OpenGGF self-replay are also
exact. Direct GPGX-versus-Java chip PCM remains diagnostic until the
implementations become byte-identical or a mathematically conservative bound is
proved over a fully enumerated finite state domain. A representative synthetic
suite may discover chip differences, but it cannot certify a retail amplitude
bound.

### Parity layer 3: final presentation PCM contract

Schema `openggf.s3k-final-pcm-parity.v1` binds the exact chip-manifest digests to:

- output sample rate, resampler implementation/configuration, mixer gains,
  stereo layout, and presentation reset/state identity;
- left/right sample count and ordered digest;
- per-channel onset, tail, peak, RMS/energy, and stereo-balance metrics;
- maximum absolute error, mean absolute error, and RMS error for diagnostics.

Internal OpenGGF replay and presentation transparency from a fixed chip-component
input are exact. Onset is the first non-zero sample after the authenticated
service anchor; tail is the last non-zero sample before a source-proved silence
window. If a selected core has a non-zero noise floor, the threshold and silence
window come from its pinned numeric representation/configuration before a retail
case runs. Indices are per channel and exact. No lag or favorable-offset search
is permitted for an acceptance gate.

Cross-core final output error/energy metrics are diagnostic, not parity gates,
unless the same-core/state rule above applies or a conservative finite-domain
proof is added. A diagnostic report may include a fixed anchor-relative lag, but
it cannot turn a RED onset/tail/write result GREEN.

This layer detects the current audible classes directly: Collapse tail duration,
modulated/wobbly energy, stereo asymmetry, Spindash release shape, and the sharp
Invincibility note-fill cutoffs. It never substitutes for the first two layers.

### Capture, storage, and regeneration

Native capture uses the headless BizHawk harness and pinned patched GPGX source.
Each native and OpenGGF capture command creates a durable task with
`agent-scratch new`, records its
`$AGENT_SCRATCH_ROOT/tasks/...` path and retention date, verifies available
capacity, authenticates every provenance item named by the write schema, and
produces independent clean A and B outputs. The native artifact lock/build
recipe is updated and
independently rebuilt whenever the diagnostic source or compiled table changes.
Publication requires:

- byte-identical canonical A/B manifests;
- byte-identical OpenGGF A/B write and PCM manifests from clean processes, in
  addition to the native A/B pair;
- gzip/container integrity for any compressed scratch stream;
- zero overflow/fault and the case-specific DMA ruling;
- deterministic terminal counts/digests;
- a tracked regeneration command and expected hashes.

Raw PCM and full occurrence streams remain scratch artifacts with a documented
retention date. Committed fixtures contain only bounded manifests, compact sample
windows needed by executable tests, and hashes/provenance sufficient to detect a
stale or substituted source.

### Runtime correction boundary

Every runtime correction follows the same transaction:

1. an inventory row is `PARTIAL` or `MISSING`;
2. a native/source case and focused Java test are observed RED;
3. the source path is derived from the shipped disassembly and, for synthesis,
   the pinned chip implementation;
4. the smallest existing configuration/profile/handler owner is changed;
5. the runtime owns every source-derived timing segment needed by the behavior;
   a case cannot be `EXACT` by inheriting the existing FM5-first-attack-only S3K
   profile for unaudited FM/PSG/modulation/note-fill work;
6. exact write parity turns GREEN before PCM is evaluated;
7. same-core PCM replay and exact structural onset/tail gates turn GREEN without
   relaxing another phase/case; cross-core diagnostics are recorded separately;
8. snapshot, rewind, rollback, observer, capacity, and cross-game controls pass;
9. the inventory row and validation report are updated atomically.

New runtime state is immutable or defensively copied and must appear in the
relevant snapshot and live-command rollback token. Multi-track services publish
logical state, writes, and staged observers atomically. A capacity or source-
shape failure leaves no prefix, callback, lock, counter, or chip mutation.

### First correction slice

#### Collapse

The case covers all three staggered FM tracks and PSG3 from admission through
their individual stop paths and the final audible tail. Required proofs include:

- source-derived timing ownership for every audited FM/PSG write and service
  cadence in this path, extending the existing FM5-first-attack-only profile
  rather than treating it as whole-service authority;
- exact per-track modulation setup and update cadence;
- exact FM key-off/override restoration order with no duplicate terminal keyoff;
- exact PSG noise/latch/volume sequence and effective register state;
- native-rate left/right YM, PSG, and final-mix windows through silence;
- tail duration, channel energy, modulation periodicity, and stereo balance.

#### Spindash Release

The case covers the complete authenticated stream, voice/frequency/key-on,
modulation or coordination flags, stop path, and restored owner. It must not
inherit Collapse-specific timing or classification. Its complete source-owned
timing path is independently derived before exact write/PCM status is available.

#### Invincibility note fill

The case covers the four reported FM tones and the surrounding scheduler state.
It proves shipped `smpsNoteFill $05` timing at exact service boundaries, sharp
key-off/write order, and the native/final PCM tail index for each tone. Tests span
normal tempo, relevant extra-service cadence, snapshot/restore, and a presentation
chunk boundary so a renderer cannot soften the stop by batching.

### Cross-game controls

S1 and S2 keep their existing configuration/profile identities and write timing.
Every shared change runs:

- none-profile/immediate-path controls;
- S1 and S2 cadence, takeover, pause, and snapshot selectors;
- exact tests proving no S3K inventory/oracle class is referenced by runtime
  packages or selected by a game/zone/sound name;
- the final all-three-ROM baseline/candidate identity comparison.

If the inventory exposes an analogous S1/S2 defect, it becomes a separately
designed source-owned slice rather than an automatic extension of this one.

## Delivery phases

### Phase 1: minimum first-slice foundation

- add strict schema/parsers and deterministic A/B capture for only the write and
  tap kinds exercised by Collapse, Spindash Release, and Invincibility;
- add native/OpenGGF YM and PSG taps plus the final-output tap needed by those
  cases; DAC schema validation lands, but a DAC native tap may wait for the first
  DAC case and cannot be reported available before then;
- seed the reachability and global inventories with the complete roots and
  behavior families needed by the three cases, including exact unknown-command
  failure, without claiming full-retail closure;
- capture the cases and publish a first-slice gap report without changing
  playback.

### Phase 2: reported first slice

- capture and close Collapse;
- capture and close Spindash Release;
- capture and close Invincibility note fill;
- package an exact clean-HEAD listening build.

### Phase 3: exhaustive retail inventory and stream interpreter families

Expand the same bounded inventory to every authenticated table root and the full
shared-memory/external-event model. Publish the complete initial classification,
then close remaining reachable command, call/loop, modulation,
modulation-envelope, note-fill, PSG envelope/noise, voice, and DAC-stream
families in descending audible/route impact.

### Phase 4: driver-global behavior

Close scheduler, tempo/speed, priority, takeover/restore, continuous SFX,
pause/fade, jingle, music transition, StopAll, SEGA PCM, and PAL-repeat gaps.

### Phase 5: synthesis and presentation

After write parity is exact, close proved chip-dialect, DAC production, PSG noise,
resampling, gain, and mixing differences. Cross-core differences remain
diagnostic until independently source/core-derived corrections make exact replay
possible; they are never tuned to retail PCM. Configuration changes require
their own default-migration and cross-game review.

### Phase 6: retail closure and listening

Regenerate the complete inventories/manifests, require zero unclassified rows,
run all-three-ROM regression comparison, package exact HEAD, and perform a
representative listening matrix before integration.

## Verification strategy

Every implementation task starts with a strict failing test. Required gate
families are:

- decoder state/cap/unknown-command poison tests;
- deterministic inventory regeneration and complete table-entry census;
- source-map/citation and production-handler bijection;
- native A/B provenance and manifest parser poison tests;
- exact write ordering/timing/owner tests;
- chip-state/phase identity, same-core PCM digest, exact onset/tail/stereo, and
  diagnostic cross-core metric tests;
- final mixer/presentation tests including chunk-boundary equivalence;
- transaction N/N-1, rollback, observer, snapshot, rewind, and adoption tests;
- S1/S2 controls and architecture confinement guards;
- identical-command full-suite baseline/candidate red-identity comparison.

The final human matrix includes, at minimum:

- Collapse from silence, over music, repeated rapidly, and after its prior
  instance has fully completed, listening to each stereo side and the tail;
- Spindash Release from several charge lengths and after another FM SFX;
- all four Invincibility melody cutoffs at normal speed and around a presentation
  buffer boundary;
- special-stage transition with speed shoes, rings, Blue Spheres, pause/unpause,
  and focus loss/regain as regression controls.

## Failure and rollback policy

A malformed inventory, stale authority hash, unsupported source shape, capacity
overflow, timing regression, same-core PCM mismatch, or structural onset/tail
failure blocks publication. It does not fall back to guessed behavior or
silently mark the row exact. A cross-core diagnostic difference blocks only a
claim about that chip/presentation layer unless source evidence identifies a
runtime defect; it cannot be relabeled as a passing bound.

Runtime corrections are delivered as small commits whose typed profiles can be
disabled by reverting the owning commit. Diagnostic tooling is inert in normal
playback. No fixture migration alters runtime ROM assets. Existing snapshots
remain readable only when their explicit audio schema version is compatible;
otherwise restore fails clearly instead of fabricating missing state.

## Integration policy

Work remains on the isolated feature worktree. Before integration, update the
main-workspace branch from its remote, record the exact full-suite baseline, run
the same suite and focused gates on the feature branch, merge into the branch
checked out in the main workspace, rerun the comparison, and push only that
integration branch. The listening gate may block integration even when every
automated gate is green.
