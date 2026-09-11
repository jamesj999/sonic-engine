# Sonic 1 YM Write-Timing Profile Design

## Decision

Implement a source-program timing profile for Sonic 1's audited FM5 SFX
first-voice/attack path. The profile extends the existing bounded
YM write timeline; it does not emulate the 68000, branch on a sound ID, consume
trace data at runtime, reset the chip to conceal the symptom, or enable Sonic 2
timing by symmetry.

The first delivery remains on `bugfix/ai-s1-dac-pause-resume` with the already
committed S1 pause/DAC correction. It is not merged into `develop` until native
and Java verification are green and both audible defects pass a human listen
test.

## Reported defect and root cause

The remaining report is that a music instrument can appear to leak into the
first audible instant of an S1 FM SFX. The earlier constructor-time leak is not
the cause: SFX construction is chip-pure, ownership is established before the
first write, and `REGISTER_SEQUENCE` takeover correctly avoids a synthetic
channel reset.

The live defect is the next timing boundary. OpenGGF publishes the complete S1
`SetVoice` upload, key-off, frequency, and key-on before rendering another
internal YM sample. The previous music note's envelope and operator feedback
history therefore survive directly into the newly keyed SFX. The shipped 68K
driver executes busy-polled YM writes over real time, allowing that state to
evolve before key-on.

The retained corrected native audit is decisive:

- all 38 authenticated `SndB5_Ring` FM5 groups preserve the exact 3,624-byte
  pre-group YM context;
- 18 isolated groups span 66,836-69,167 master cycles from the first voice
  write to key-on; 20 overlap groups span 66,577-69,426;
- exact-state atomic versus source-timed replay changes an isolated group's
  key-on operator attenuation from `[1,1,311,313]` to
  `[89,73,391,378]`, a maximum difference of 88;
- every source group has zero DMA/fault/overflow markers and the source-timed
  replay equals the live native key-on state.

This proves that the missing inter-write time is material. It does not claim
that every perceived onset artifact is the same effect; the human gate covers
the reported effect as well as the authenticated ring reference.

## Scope

### Included

- S1 normal and special SFX using the audited FM5 hardware role whose first
  stream path has the authenticated `SetVoice -> optional Pan -> Note` shape;
- first `SetVoice` upload, optional stream panning, and first-note
  key-off/frequency/key-on as one continuous source program;
- dynamic 68K busy-poll timing at the existing master-cycle timeline boundary;
- exact snapshot, rewind, rollback, observer, and hybrid-rendering behavior;
- native isolated and overlapping ring verification plus a second ROM-backed
  S1 FM5 SFX with a different algorithm/carrier mask;
- an explicit S2/S3K control ruling.

### Excluded

- complete 68K or Z80 emulation;
- absolute VInt/service-entry-to-first-write phase;
- S1 FM1-FM4/FM6 timing without a retained native owner join;
- S1 FM5 paths with a coordination flag, call, return, jump, or loop between
  `SetVoice` and the first note other than the authenticated optional pan. They
  remain immediate until that exact source path has its own checked ledger;
- SFX completion key-off/music restoration timing. The current behavior remains
  ordered and unchanged until a separate A/B native capture authenticates the
  retiring SFX owner, the unique overridden music owner, its `VoiceIndex`, and
  the complete `cfStopTrack`/`SetVoice` instruction path;
- PSG, DAC, gain, panning, or mixing changes;
- a forced key-off/reset before voice upload;
- runtime trace playback or movie/sound/zone selection;
- enabling the separately proven S2 timing defect in this delivery.

The hardware-role boundary is deliberate. It matches the retained native owner
join and the existing S3K first slice. Profile selection is from driver dialect,
FM port/channel, operator/carrier layout, and path kind—not the effect ID.

## Existing architecture retained

`SmpsDriver` continues to own one global service cursor and atomic write/
logical-observer journal. `VirtualSynthesizer` continues to own the bounded
`YmWriteTimeline`, and `Ym2612Chip` continues to drain committed writes at the
pre-internal-sample boundary. Existing generation barriers, snapshot identity,
adoption, capacity reservation, and chip-observer semantics remain unchanged.

The new profile changes only how one bounded source program derives each next
YM due cycle from checked source costs and the existing discrete-YM BUSY rule
before journal commit.

## Source-program timing representation

### Fixed segments and continuous source programs

Preserve `YmServiceTimingProfile.Segment` and its fixed `long[]` convenience for
the reviewed S3K profile. Add an immutable source program beside it:

```java
record SourceProgram(
        ProgramKind kind,
        ProgramVariant variant,
        List<ProgramWrite> writes,
        List<ProgramSection> sections) { }

record ProgramVariant(
        int port,
        int carrierMask,
        FirstPathShape shape) { }

record ProgramWrite(
        SegmentKind section,
        int expectedPort,
        int expectedRegister,
        OptionalInt expectedFixedValue,
        int fixedCyclesBeforeFirstStatusRead,
        int statusReadCycles,
        int takenBusyLoopCycles,
        int cyclesAfterReadyStatusToDataWrite,
        SourcePath sourcePath) { }

record ProgramSection(SegmentKind kind, int firstWrite, int writeCount) { }
```

`ProgramKind.S1_FM5_FIRST_VOICE_ATTACK` contains the entire authenticated path,
not one independently normalized program per Java helper. It has 30 writes
without a stream pan and 31 with one: 26 voice-upload writes, optional B5 pan,
one key-off, and A5/A1/key-on. Only write zero of the whole program is the
relative zero anchor. Section boundaries label source ownership and validation;
they do not reset timing or busy state. All arrays/lists are defensively copied,
bounded, and immutable.

The canonical checked program lives at
`docs/architecture/research/audio/s1-fm5-ym-busy-write-program-v1.json`.
Runtime production code does not read this file. Tests require the hard-coded
typed profile to equal it and bind every source row to the existing canonical
PC/opcode/source ledger and map.

### Pure source-cost/BUSY resolver

A package-private pure `YmSourceProgramResolver` advances one row of the whole
program from an explicit immutable continuation:

```java
ResolvedWrite resolveNext(
        SourceProgram program,
        ProgramState state,
        SegmentKind actualSection,
        int actualRegister,
        int actualValue,
        long serviceCursorMasterCycle,
        long renderedYmFrontierMasterCycle);
```

Fixed S3K timing never uses this resolver. The S1 artifact proves the
reviewed GPGX discrete-YM BUSY contract:

- one 68K cycle is seven Mega Drive master cycles;
- the discrete YM clock ratio is master/42;
- a data write sets BUSY through `ceil(write/42)+32` YM clocks;
- `WriteFMI` polls A04000 at `$7272E`, while `WriteFMII` polls A04000 at
  `$72764`; its later A04002 read at `$7277C` is not a BUSY-ready decision;
- the source artifact retains every taken `btst`/branch interval separately;
- the diagnostic core increments a cumulative refresh-delay counter only at
  the two GPGX sites that actually add the 14-master-cycle 68K refresh stall.
  Consecutive source-row cost is `captured delta - cumulative refresh delta`.
  After that subtraction every repeated BUSY loop is exactly 259 master cycles.

The audit also proves a limit: 68K refresh insertion changes captured normalized
30/31-write paths by up to 2,590 master cycles. OpenGGF has no 68K execution or
refresh phase from which to reproduce those stalls. Adding a partial 68K CPU
solely for this SFX would be disproportionate. Runtime therefore excludes only
the explicitly measured refresh stalls and executes the checked no-refresh
source costs against the real service cursor's YM-clock residue. Captured final
write gaps and attenuation are comparison evidence only; no captured relative
write vector is a runtime constant.

`ProgramState` stores `nextWrite`, `lastDueMasterCycle`, and the virtual
`busyUntilMasterCycle`. Row zero is due at the service cursor and sets
`busyUntil = (ceil(due / 42) + 32) * 42`. For every later row the resolver:

1. starts at `lastDue + fixedCyclesBeforeFirstStatusRead`;
2. adds the 259-cycle checked loop while `statusCycle < busyUntil`;
3. publishes at `statusCycle + cyclesAfterReadyStatusToDataWrite`; and
4. recomputes `busyUntil` from that data-write cycle.

All 42 possible service-cursor residues modulo the YM clock are test inputs.
`statusReadCycles` remains checked source decomposition evidence; the readiness
decision occurs at the status-read instruction boundary already represented by
`statusCycle`.

Resolution is incremental within one transaction-owned program state. The
side-effect-free classifier preselects `VOICE_NOTE` or `VOICE_PAN_NOTE`; the
voice-upload section starts that program, and later sections must match the
selected path exactly. Both variants continue from one program-relative cursor.
The classifier does not consume live stream state, and ordinary live-command
rollback protects any mismatch between its local view and actual interpretation.

Row zero is always published at the transaction's service cursor. This is the
explicit relative-only boundary: service-entry-to-first-write time and any
pre-existing live busy interval are excluded. If an older pending entry has the
same due cycle, its lower source ordinal drains first, then row zero drains at
that same frontier. Only rows 1..N advance through the checked source/BUSY
calculation. An acceptance test pins
this exact pending-tail case: prior tail due `C`, row zero due `C`, row one due
strictly after `C`, with source ordinals tail < row zero < row one.

Each later step returns its due cycle and next ordinal. The cursor remains
continuous across voice-upload, optional pan, key-off, frequency, and key-on
sections. The resolver never mutates the live chip, reads gameplay state, or
retains a sequencer reference. The actual timeline drain remains the authority
for chip mutation and callbacks.

The resolver is checked-arithmetic and fail-closed: negative cycles, overflow,
an empty program, count mismatch, or a due-cycle regression
poisons the entire service transaction before publication.

### Bounded timing discrepancy

Normalized native groups vary by 2,590 master cycles because each data write
reaches the driver under a different 68K refresh phase. The production model
does not claim cycle-exact refresh parity. Acceptance instead requires the
source-cost model to preserve exact write order and to resolve deterministically
for every YM-clock residue. Acceptance evaluates the full 42-residue × every
retained 3,624-byte native-state matrix. For each residue independently, the
summed L1 key-on attenuation error over all states must be no larger than the
atomic sum over those same states; at least one residue and the full matrix total
must be strictly better. Every residue aggregate and per-state result, including
neutral or worse individual states, is reported without filtering. This bounded
refresh omission is recorded rather than hidden behind a fitted vector or a
broader CPU model.

## S1 profile and interpreter mapping

Add `Sonic1YmServiceTimingProfile.PROFILE` and install it in
`Sonic1SmpsSequencerConfig.CONFIG` with typed ownership
`TimingOwnership.EXCLUSIVE_SFX_FM5`. `SmpsDriver` returns this profile only for
the one SFX sequencer that owns the FM5 lock, has the first-path state armed,
and has an authenticated first-path shape;
other SFX and music sequencers remain unprofiled. Managed music remains
immediate before the S1 music-then-SFX service; if prior timed SFX writes are
still pending it becomes an ordered sibling fence and cannot overtake them. No
game-name test is added to shared code.

The concrete first-slice reservation is the existing **4,096-entry YM timeline
capacity**, once for that unique FM5 owner. This intentionally reserves the
whole bounded queue rather than claiming an unproved smaller number: S1
coordination flags can execute repeatedly before a note, and `cfChangeFMVolume`
may add up to four `SendVoiceTL` writes per occurrence. The exclusive FM5-lock
predicate proves at most one owner is charged; identity de-duplication covers an
owner also present in pending removals. The active profile forces a sample
boundary and complete timeline drain before the next S1 driver VInt, and S1 has
no PAL repeat. Thus all FM writes from the one music-then-SFX/removal horizon,
including unprofiled sibling tracks and unchanged completion cleanup, either
fit the already allocated 4,096 entries and commit atomically or fail before
publication. Tests pin: exactly one charged owner, identity de-duplication for
the same owner in active/pending collections, rejection of a second distinct
timed FM5 owner,
an empty timeline at the profiled service boundary, N=4,096 success, and
N-1=4,095 rollback with no logical/chip callback or state mutation.

A ROM-backed control-flow scanner still reports the actual maximum and every
hardware-writing coord-flag path for documentation and future tightening, but
its result is not used to weaken the safety reservation in this delivery.

### Authenticated runtime shape selection

Before `loadVoice` emits the first hardware write, a side-effect-free bounded
classifier reads from the track's current ROM-backed `ProgramView` position
(after the EF voice parameter). It may consume only a local cursor and returns
one of:

- `VOICE_NOTE`: the next semantic unit is a note/duration;
- `VOICE_PAN_NOTE`: exactly one E0 pan+parameter precedes that note/duration;
- `UNSUPPORTED`: every other command, malformed span, call, return, jump, loop,
  repeated pan, second SetVoice, volume change, or end-of-stream.

The classifier never mutates `Track`, loop counters, return stack, duration,
or the live stream cursor. Only the first two results arm the source program;
`UNSUPPORTED` uses the existing immediate path with no partial journal. The
actual interpreter must later consume exactly the classified bytes and section
sequence; a byte/position mismatch aborts and rolls back. Classification is by
typed bytecode shape and FM5 ownership, never by SFX ID, zone, movie, or trace.

Driver-service capacity preselection is a distinct bounded lookahead because
retail Break Item executes one non-writing `F0 ModSet` before `EF SetVoice`.
Starting at the untouched track cursor it may skip exactly that fixed-width
five-byte prefix, then requires a valid EF voice and applies the same post-EF
shape/carrier validation above. Once live interpretation reaches EF, the
already-classified shape keeps the transaction active through voice upload.
No other pre-EF command is skipped; an unsupported path remains immediate and
does not reserve or append to the timed queue.

Tests enumerate every retail S1 SFX track, record which shapes are eligible,
prove Ring's 30/31 native variants are included, and poison every unsupported
control-flow class. A second ROM SFX is an acceptance vector only if this same
source-authenticated shape classifier selects it; otherwise the delivery makes
no timing claim for it.

The existing `FmVoiceWriteProfile.S1_68K` dialect owns the mapping:

- no `SFX_ADMISSION_PREP` or `SFX_MAX_RELEASE` segment is opened;
- `FM_VOICE_UPLOAD` covers the exact S1 order: B0, five parameter groups in
  stored operator order 1/3/2/4, adjusted carrier TL, then B4;
- `TRACK_PAN_WRITE` covers a stream-owned `smpsPan` data write between voice
  upload and the first note. It is optional per stream but, when present, owns
  its full preceding and following source gap; the audited ring's second B5
  write must not collapse onto the voice upload;
- `KEY_OFF` covers the source note-off;
- `FREQUENCY_AND_KEY_ON` covers A4, A0, and 28/Fx;
- all four sections consume one active
  `S1_FM5_FIRST_VOICE_ATTACK` program. Opening a section out of order, closing
  it with unconsumed rows, publishing a mismatched port/register or a mismatched
  fixed semantic value, or ending the
  service before key-on poisons the unpublished transaction.

The first `FM_VOICE_UPLOAD` call opens the program and resolves its first
section. Later helpers call an
internal `enterYmProgramSection(source, kind)`; `writeFm` atomically consumes
and resolves the next row only when its program state, section, and expected
port/register match. Fixed semantic channel values are also checked: FM5 key-off
must be `$05` and key-on must be `$F5`. Voice bytes, carrier-adjusted TL, pan,
and frequency data remain ROM/interpreter-owned variable values and are proven
by end-to-end ordered-write tests rather than falsely copied from Ring into the
generic timing program.
An ordinary write while a program is active is not assigned the current cursor:
it must be the represented optional pan row or the transaction fails. This
keeps the Java helper boundaries without treating them as timing anchors.

S1 carrier branches are derived from algorithm output operators using the
existing S1 stored-operator mapping. S3K continues to use stored TL bit 7. A
typed helper selected by `FmVoiceWriteProfile`, not a game name, calculates the
variant and prevents the two dialects from sharing the wrong carrier rule.

The existing private FM5 admission flags become dialect-neutral names while
retaining their exact snapshot bits and live-command rollback behavior. They
are armed only for an SFX FM5 track under a non-empty profile, consumed once by
the first successful voice/note path, and restored exactly across rewind and
failed admission. A first-path state machine also remains armed between
`smpsSetvoice`, the classified optional `smpsPan`, and the first note. Any
unclassified coordination/control-flow transition in that window poisons the
transaction instead of silently publishing it at the wrong cycle.

Ordinary subsequent notes, voice changes, and completion restoration remain
globally ordered but untimed unless represented by an audited segment. Any
later write cannot overtake committed timed work because all FM writes share
the service cursor.

## Completion boundary

This slice does not time completion. Current completion remains one ordered
transaction: the retiring SFX key-off followed by the existing deterministic
music restoration behavior. It cannot overtake the first-attack program because
the latter drains roughly 1.25-1.30 ms after admission, while completion occurs
only after the SFX stream's later duration boundary.

Before a later completion-timing slice may start, its diagnostic capture must
prove exactly one overridden music FM5 owner, record its `VoiceIndex`, retain
the complete key-off-to-restored-B4 register/value/cycle stream and decoded
instruction ledger, poison a wrong or duplicate owner, and reproduce A/B. That
future design must parameterize the 25-byte-per-index `SetVoice` pointer loop.
The present 909-row first-attack ledger is explicitly not authority for
`cfStopTrack`.

`YmServiceTimingProfile` therefore exposes an exact capability query,
`supports(SegmentKind, Variant)`. `releaseLocks` opens
`COMPLETION_RESTORE` only when that query is true; otherwise it executes the
existing immediate ordered cleanup. S1 returns false, S3K returns true for its
reviewed completion variants, and `none()` returns false. Tests prove S1 cleanup
does not request a missing segment and that S3K still emits its existing timed
completion vector byte-for-byte.

## Source and native authority

The canonical program is generated from:

- `s1.sounddriver.asm:348-456` (`FMUpdateTrack` and
  `FinishTrackUpdate`);
- lines 1713-1769 (`WriteFMIorII`, `WriteFMI`, `WriteFMII`);
- lines 2313-2375 (`cfSetVoice`/`SetVoice`);
- the checked 909-row representative instruction ledger and canonical source
  map already retained in `docs/architecture/research/audio/`.

A deterministic generator identifies, per inter-data-write gap, the fixed
prefix/suffix and the exact repeatable busy-poll loop. Its native instruction
rows carry the cumulative refresh delay observed at the exact GPGX refresh-add
sites; the generator subtracts only the counter delta and rejects regression,
non-14-cycle increments, any inconsistent remaining loop cost, or any ledger
row not consumed exactly once. The Java test independently parses the source
program, validates its PC/opcode/cycle/source references against the ledger,
and re-derives every no-refresh source cost from those rows.

The runtime profile is accepted only if its typed source costs equal the checked
artifact, its resolver is correct for every service-cursor residue modulo 42,
and its exact ROM-produced register/value stream remains ordered. The retained
oracle evaluates the onset metric but remains comparison-only; captured final
cycle gaps never enter runtime production data.

## Atomicity, capacity, and snapshots

Resolution occurs inside the existing service transaction before queue commit.
Capacity preflight uses the concrete one-owner 4,096-entry reservation above
and counts the whole driver-service horizon, including pending removal owners
and current immediate completion work. N succeeds; N-1 restores sequencer cursors/flags, locks,
service/write ordinals, timeline cursor/generation, PSG shadow state, and staged
logical observers with no chip callback.

Snapshots store only resolved committed entries and stable cursor/ordinal/
generation state. They do not serialize a half-resolved source program or an
unpublished journal. Snapshot/restore is tested after commit before the first
write, at every partial-drain boundary, and after completion restoration.

## Cross-game ruling

- S2 remains `YmServiceTimingProfile.none()` in this delivery. Its independent
  oracle proves a material 135,435-cycle FM5 group and 58-unit attenuation
  difference, but its Z80 bank waits and source dialect require the separate
  reviewed plan already tracked in the repository.
- S3K remains on `Sonic3kYmServiceTimingProfile.PROFILE`. Fixed timing vectors,
  SFX-first service order, and its existing music-write leakage guards must be
  unchanged.

Tests assert those exact profile identities so no cross-game fallback silently
enables or disables timing.

## Verification

### TDD RED

- The current S1 profile is `none()` and an exact ROM FM5 group collapses to one
  YM frontier.
- The retained isolated and overlap vectors vary with 68K refresh phase and
  cannot be valid runtime advance constants.
- Atomic exact-context replay differs from native key-on attenuation.
- Cross-helper voice/pan/key-off/frequency scopes currently lose native busy
  continuity.

### GREEN

- deterministic source-program generation is byte-identical twice;
- every row in each selected representative ledger is consumed exactly once or
  explicitly excluded as pre-first/post-terminal framing;
- the pure resolver re-derives every no-refresh source cost, exercises all 42
  YM-clock residues, and never reads a captured final write gap;
- the production profile equals the canonical checked program;
- a ROM-backed ring and every other classifier-eligible FM5 SFX schedule
  without a sound-ID branch; unsupported shapes remain byte-identical to the
  immediate control;
- exact-context production replay reports every retained isolated/overlap onset
  result and improves or equals the aggregate L1 error relative to atomic
  playback without selecting a captured phase;
- first attack, optional pan, ordinary note, retrigger, unchanged completion,
  suppression, and replacement retain source order;
- sample-accurate and hybrid modes produce identical PCM and driver snapshots;
- capacity N/N-1, observer exceptions, live-command rollback, rewind, save/load,
  partial drain, reset, pause, full silence, stop-all-SFX, and adoption are
  deterministic;
- the S1 pause/DAC regression remains green;
- S2 remains `none()`, S3K remains on its existing profile, and their focused
  ROM/cadence/timeline gates remain green;
- JDK 21 three-ROM focused audio gates and full-suite baseline comparison add
  no attributable failure.

### Human gate

Package the exact clean commit and test:

- the reported S1 effect against active music;
- ring and explosion from an idle FM5 owner;
- rapid overlapping repeats;
- first replay after completion/turn/idle;
- replay after a different FM5 SFX;
- pause/unpause and focus-loss/refocus DAC restoration;
- S2 and S3K controls for obvious onset regressions.

No merge or push occurs before a positive listen report.

## Rejected alternatives

- forced key-off, envelope reset, fade, or crossfade: changes shipped
  `FixBugs = 0` behavior and masks the timing owner;
- one observed delay vector without decoded source provenance and replay across
  retained native YM states: fixture fitting and wrong across busy phases;
- sound/zone/movie-specific timing: violates runtime rule placement and trace
  independence;
- runtime native-oracle playback: violates comparison-only evidence policy;
- complete 68K emulation: unnecessary for the bounded source program;
- enabling S2 from S1 constants: wrong CPU, bank, and driver dialect.
