# SMPS YM2612 Write Timeline Design

## Decision

Add a bounded, source-timed YM2612 write timeline to the existing SMPS driver.
The first slice models only the locked-on S3K FM paths needed to fix the Blue
Sphere completed-then-replayed defect. It does not emulate the Z80, read traces
at runtime, or branch on a sound, zone, movie, or game name.

The change remains on `feature/ai-smps-playback-verification`. It is not merged
into `develop` until automated native parity is green and the reported sound
passes a human listen test.

## Problem

OpenGGF currently publishes every YM write produced by one SMPS service before
rendering the next output sample. The shipped Z80 driver consumes instruction
time between maximum-release writes, voice fields, frequency, and key-on. The
MAME YM2612 core advances its envelope while that instruction stream runs.

The difference is most audible after the previous SFX has finished and music
has reclaimed FM5. Rapid retriggers start from a different envelope history and
mostly conceal it. This matches the reported pattern: consecutive Blue Sphere
pickups are acceptable, while the first pickup after a turn or comparable idle
gap starts incorrectly.

The existing completed-replay test compares two OpenGGF runs. It proves
determinism but cannot detect a timing error shared by both runs.

## Corrected root-cause evidence

The first diagnostic capture was invalid: it emitted the write event before
Genesis Plus GX called `fm_update(cycles)`, so the event ordinal named samples
from the pre-write state. Those results are discarded.

The corrected diagnostic wrapper calls `fm_write_impl` first, then records both
the explicit Genesis master-cycle timestamp and the first internal-sample
ordinal which can observe the write. The corrected capture uses the locked-on
S3K ROM, reviewed complete-emeralds BK2, BizHawk 2.11, and the diagnostic GPGX
MAME YM2612 path. Its compact write table has SHA-256
`33cef3472ad2c9c0d0d50e27f6ae574b51e02755420cd9c542b0443996013f99`;
the captured FM5 stream has SHA-256
`4277bc5f29fa086013b49f006fd887b9795ebfbb17e8288de4c50005bb97e6d8`.

At representative isolated pickup 7, the exact source-ordered writes are:

- maximum-release writes begin at master cycle 77,760;
- the final instrument write is at 185,085;
- key-off is at 193,140;
- frequency writes are at 223,770 and 226,470;
- key-on is at 229,350.

The first maximum-release write and key-on are 151,590 master cycles, or 150.39
MAME YM samples, apart. Replaying the exact writes and corrected ordinals through
OpenGGF's YM core gives FM5 RMS 5,571.46 versus native 5,565.97 over the bounded
onset window. This does not claim bit-identical chip output; it isolates the
large fresh-start defect from register values and driver routing.

Collapsing only those native writes to one VInt changes key-on attenuation from
`[760, 976, 881, 1023]` to `[132, 384, 273, 1023]`. The aligned OpenGGF driver
run reports `[130, 385, 274, 1023]`. Register order and values are unchanged.
This confirms that lost inter-write time, rather than gain or panning, owns the
reported attenuation pattern.

The retained evidence path described below replaces the throwaway spike before
delivery.

## Scope boundaries

This slice changes only FM write timing for audited S3K source paths. It does
not include:

- complete sound-CPU emulation;
- a complete-run audio subsystem expansion;
- runtime playback of trace-derived values or timing;
- PSG, DAC, mixing, panning, gain, or envelope normalization;
- gameplay pickup timing or special-stage routing;
- automatic S1/S2 adoption.

## Clock and publication model

### Authoritative unit

All scheduled time is an integer count of Mega Drive master cycles. For the
locked-on Z80 path:

- one Z80 T-state is 15 master cycles;
- the GPGX MAME YM2612 sample period is `42 * 24 = 1008` master cycles.

No floating-point time or output-sample ordinal is stored in the profile.
Output resampling happens after the internal YM stream and therefore cannot
move a write between internal samples.

`Ym2612Chip` exposes a package-private scheduled-write boundary at its internal
renderer. Its master-cycle frontier advances by 1008 for each `renderOneSample`.
To match GPGX `fm_update(cycles)`, the chip first renders old state until its
frontier is greater than or equal to a write's due cycle, then applies that
write before the next internal sample. All writes due at the same cycle retain
their source ordinal.

The first profiled write in one OpenGGF driver service is anchored to the current
YM frontier. This first slice is deliberately a **relative inter-write timing
model**, not a complete sound-CPU phase model. Corrected native captures place
the first maximum-release write 76,230–82,125 master cycles after the frame
boundary in the four isolated examples; that variation includes interrupt,
DAC-loop, and work-before-this-track phase which OpenGGF does not yet model.

Native validation therefore normalizes each group to its first maximum-release
write and compares exact relative master-cycle deltas. This design makes no
claim that the absolute first-write cycle, native key-on attenuation, or an
absolute PCM digest can match exactly. Acceptance instead requires a consistent
improvement across multiple starting envelope states and the human gate. A
future complete sound-CPU phase model may add the service-entry delay without
changing the timeline or profile representation.

### One global service cursor

`SmpsDriver` owns one monotonic master-cycle cursor per driver service, not one
cursor per Java method or sequencer. The cursor begins at the greater of the YM
render frontier and the last pending due cycle. Every subsequent YM write in
that service is queued through the same cursor, including writes from an
unaudited operation. An unaudited operation contributes no invented delay, but
it cannot overtake an earlier delayed write.

This preserves shipped global order such as S3K SFX completion and music restore
before same-VInt music service. A timing scope never resets the cursor. In
particular, `cfSetVoice` ends after instrument upload; key-off, frequency, and
key-on are separate source segments composed on the same cursor.

## Source-owned timing profile

`SmpsSequencerConfig` gains an immutable `YmServiceTimingProfile`. It contains
typed, branch-specific timing segments, not sound IDs, register matching, game
names, or trace coordinates. The initial S3K segments are:

- SFX admission and maximum-release preparation;
- FM instrument upload for the shipped `fix_sndbugs = 0` path;
- key-off, frequency, and key-on;
- SFX completion and restored music-instrument upload.

The selected segment variant is determined from semantic state already owned by
the interpreter: FM port/channel, operator count and order, carrier mask,
instrument layout, and whether the source branch uploads SSG-EG data. A segment
contains the expected write count and the master-cycle advance before each
write. Scheduling fails atomically if the actual source operation produces a
different count or order.

The constants come from a checked calculation artifact. Each row records:

- disassembly label and exact `fix_sndbugs = 0` branch;
- executed opcode path, including taken/not-taken branches, calls, returns, and
  Z80 RAM/YM I/O accesses;
- Z80 T-state subtotal and multiplication by 15;
- every banked 68k-ROM voice-byte access and the GPGX/hardware wait-state rule;
- branch inputs and the resulting inter-write master-cycle delta.

The artifact explicitly includes `zWriteFMIorII`, `zWriteFMI`, `zWriteFMII`,
`zSetMaxRelRate`, `zFMOperatorWriteLoop`, `zSendFMInstrument`,
`zSendFMInstrData`, frequency preparation, `zKeyOnOff`, completion, and restore.
Corrected native CPU-cycle timestamps validate every calculated delta but never
select or tune a constant. A mismatch blocks implementation and is resolved
against source before changing the profile.

For banked voice reads, the artifact includes GPGX
`z80_request_68k_bus_access()`: its documented **average approximation** adds
three Z80 T-states per uncontended 68k-bus access. This is the selected GPGX
parity dialect, not a claim that every physical-console access has an exact
three-T-state wait. VDP DMA may additionally stall access until `dma_endCycles`.

Repeatable capture deltas alone do not prove DMA was absent. The retained
diagnostic therefore records a DMA-stall marker/count for every banked access in
the bounded group. The no-DMA profile is accepted only if the independently
calculated uncontended total equals 151,590 master cycles **and** all four
isolated captured groups report zero DMA stalls. Otherwise this slice remains
RED. The bounded profile does not claim exact relative timing during simultaneous
68k-bus VDP DMA. Adding that later requires a typed VDP-bus timing input; it must
not be inferred from a sound, zone, frame, or trace.

The profile is absent for a driver until that driver's source paths are audited.
Absent timing keeps current immediate behavior and cannot inherit S3K timing.

## Timeline ownership and rendering

`VirtualSynthesizer` owns a bounded `YmWriteTimeline` because it is the object
that renders the YM internal stream. `SmpsDriver` owns the source clock which
assigns due cycles. A pending entry is self-contained:

`(dueMasterCycle, sourceOrdinal, port, register, value, driverGeneration,
serviceOrdinal, sourceDescriptor, timingSegment)`.

It never retains a live sequencer reference. Arbitration and channel ownership
are decided when the driver service stages the write; draining only mutates the
chip. The diagnostic descriptor identifies the immutable ROM-backed source and
track ordinal, so a completed sequencer may disappear while its committed writes
remain valid.

Once timing is enabled, every FM write passes through this timeline. Direct
external hardware barriers use explicit policies:

- hard reset, synth replacement, and full silence increment the driver
  generation and discard all older pending entries before applying reset writes;
- `stopAllSfx` cancels only the unpublished journal of an in-flight SFX service,
  then stages the shipped stop sequence; it does not erase any atomically
  committed timeline entry;
- ordinary SFX completion and removal retain every self-contained committed
  write, whether it belongs to music or SFX;
- `adoptActiveSfxFrom` copies pending entries and remaps them to the target driver
  generation using source descriptors, never object identity;
- pause, fade, and one-up operations use the same service cursor and do not gain
  a separate timing shortcut.

Hybrid rendering calls the same internal-sample drain hook as sample-accurate
rendering, so a chunk cannot skip a due write.

## Transactions, capacity, and rewind

One driver service stages its complete write journal and its **logical observer
journal** before publishing either. Contention, service, and timing-scheduling
callbacks are appended as immutable notifications and become externally visible
only after profile/count/order validation and queue commit succeed. Rollback
therefore has no logical callback to retract.

Chip-write, channel-sample, and key-on observers are different: they remain at
the chip boundary and fire only when a committed timeline entry actually drains
and mutates `Ym2612Chip`. Key-on attenuation is read from chip state at that
moment. An entry discarded by a generation barrier such as hard reset, synth
replacement, or full silence before its due cycle emits no chip callback.

Capacity is derived from the maximum aggregate pending
writes across the scheduling horizon, including all music and SFX tracks,
completion/restore work, and one PAL repeated service before render drain. It is
not sized to the largest individual operation.

Preflight failure restores the write journal, observer journal, service cursor,
driver generation, sequencer state, locks, and synth state. Capacity N succeeds;
N-1 fails without a partial queue, callback, or chip mutation.

Tests require zero phantom or duplicate logical observer events after count
mismatch, capacity failure, command rollback, snapshot restore, and retry.
Chip callbacks must equal actual drain order. Reset/replacement-before-due must
discard the write with no chip callback; ordinary SFX completion/stop preserves
it, and restore-and-drain emits it exactly once.

The timeline entries, rendered master-cycle frontier, source cursor, ordinals,
and driver generation are included in both `VirtualSynthesizer.Snapshot` and
`SmpsDriverSnapshot`. `LiveCommandMutationToken` captures the same state so an
admission rollback cannot leave delayed writes. Restore reproduces the exact
pending sequence and resampler phase.

## Retained native validation path

The corrected diagnostic must be reproducible after the spike is removed:

1. Track a diagnostic-only GPGX patch under the audio research tooling. It emits
   FM address/data, explicit master-cycle timestamp, and post-`fm_update` internal
   ordinal, plus a bank-access DMA-stall marker/count, without changing the
   production observer ABI.
2. Track a small capture command which verifies the pinned GPGX source, ROM SHA-1,
   BK2 SHA-256, patch SHA-256, and resulting core SHA-256.
3. Track a compact JSON oracle containing only the relevant source-bounded FM5
   write groups, cycle deltas, key-on attenuation, onset-window summary, hashes,
   and provenance. Do not commit raw PCM or the full movie trace.
4. Make regeneration produce a new file and byte-compare before publication.

Source-derived timing tests consume only the calculation artifact. Native parity
tests consume the compact oracle. Runtime code consumes neither.

## S1 and S2 ruling

S1 and S2 share the immediate-write limitation, so both receive a source and
native audit. They do not automatically receive S3K timing.

Each audit covers admission, voice upload, key-on, completion/restore, an isolated
replay after completion, and an overlapping retrigger. S1 uses its 68k timing and
cannot reuse Z80 constants. S2 uses its own Z80 routines and branch paths. A game
gets a separate typed profile only when its source calculation and corrected
native cycle capture prove a material difference. This delivery records the
audit but does not broaden implementation merely for symmetry.

## Verification

### RED

- The corrected compact native oracle is regenerated before writing behavioral
  expectations.
- A source-calculation test proves every S3K inter-write delta independently of
  the oracle.
- The current atomic engine fails the isolated completed-then-idle sequence on
  exact write deltas and key-on attenuation while the overlapping control stays
  classified separately.

### GREEN

- Corrected native and engine register/value order match.
- Every audited inter-write master-cycle delta matches exactly after normalizing
  to the first maximum-release write.
- From four isolated captured FM5 pre-group states, four synthetic envelope
  phases, and overlapping controls, replaying the source-timed production group
  improves the onset error relative to the atomic baseline without claiming an
  exact absolute native phase. The acceptance metric and threshold are fixed
  from the corrected pre-implementation captures, then left unchanged.
- A second S3K FM SFX proves operation-based behavior.
- Same-cycle write order, internal-sample drain, linear and blip resampling, and
  hybrid chunks are equivalent.
- Aggregate capacity N succeeds; N-1 rolls back queue, cursor, locks, the staged
  observer journal, sequencers, and chip state.
- Count/order/capacity failure emits no logical observer event; retry emits each
  committed logical event exactly once in source order. Chip-write/key-on events
  fire exactly once at actual drain; reset/replacement-before-due fires none,
  while ordinary SFX completion/stop does not cancel committed entries.
- Snapshot/restore during an upload is byte-identical.
- reset, replacement, stop-all, pause, fade, one-up, completion, and
  `adoptActiveSfxFrom` cannot leak or reorder pending writes.
- Existing S3K contention, modulation, cadence, PAL, ring, and special-stage
  tests remain green.
- Focused S1/S2 controls remain unchanged unless their independent audit justifies
  a separate profile.
- The three-ROM audio suite and full-suite baseline comparison introduce no
  attributable regression.

### Human gate

The listen test covers first pickup, rapid repeats, first pickup after completion,
pickup after a turn/idle gap, pickup after another FM5 SFX, rings, and special-stage
music tempo. Automated parity is necessary but does not replace this gate.

## Rejected alternatives

- Sound-ID delay, forced attenuation, gain, or special-stage carve-out: fitted
  behavior rather than source timing.
- One global fixed delay: wrong across different routines and games.
- Runtime trace playback: violates comparison-only trace policy.
- Full sound-CPU emulation: disproportionate to this S3K release defect.
