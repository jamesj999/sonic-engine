# S3K PSG Service-Write Timing Design

## Status and decision

**Rejected after diagnostic implementation; do not implement this design.**

The full-prelude capture proved that the largest apparent intra-service delay
is a moving 68K `stopZ80`/`startZ80` bus hold owned by VBlank, VDP, and DMA work,
not an SMPS routine constant. A smaller implementation that preserved only the
source-derived relative PSG write gaps was then measured against the native
component PCM and did not improve Collapse. The experimental runtime was
removed rather than fitting an absolute delay from one capture.

The decisive direct-chip comparison showed that the existing positive-edge PSG
core carries Collapse's five native bursts through frame 121 at the native
amplitude. A subsequent final-speaker capture separated the remaining issue:
raw native PSG becomes silent on frame 122, but GPGX's band-limited output
decays over four more output frames. BizHawk configures `hq_psg=1`; OpenGGF's
SMPS runtime had left its otherwise compatible `PsgChip` in standalone fast
mode. All SMPS drivers therefore select the existing HQ path. This changes the
generic chip presentation, not Sound_59's lifetime, and adds no synthetic
reverb. The comparison evidence is retained in
`docs/architecture/research/audio/s3k-collapse-final-pcm-hq-psg-v1.json`.

The one-time paired-default migration in `ConfigMigrationService` remains a
separate correction for old generated `config.yaml` files that persisted
`dacInterpolate: true` and `psgNoiseShiftEveryToggle: true`. Listening rejected
that migration as the complete explanation for Collapse's ending.

The rest of this document is retained as a rejected design record. It describes
what exact absolute PSG placement would require if a future PCM-parity goal
justifies reproducing the external VInt bus-arbitration owner. It is not needed
for the band-limited renderer correction above.

The scheduler fixes one systemic mismatch: OpenGGF currently publishes all PSG
writes at the beginning of a host driver service, while the retail driver issues
them at different points inside the VInt. Collapse and Dash are acceptance cases,
not runtime selectors. Timing is selected from the S3K dialect, physical PSG
slot, and the semantic branch already taken by the interpreter.

This design amends, but does not replace,
`2026-08-23-s3k-smps-driver-pcm-parity-design.md`. Its authenticated dialect,
trace-is-comparison-only rule, inventory, and listening gate remain normative.

## Why sub-frame placement is necessary

The current runtime already matches Collapse's effective PSG register sequence
and semantic lifetime. It does not match the resulting waveform:

- native writes occur tens of thousands of Mega Drive master cycles after the
  VInt boundary;
- OpenGGF applies the complete service journal before rendering the first sample
  of that VInt;
- the native terminal frame contains 107 non-zero PSG samples before the mute,
  with RMS `44.695811`;
- the aligned OpenGGF frame is completely silent;
- delaying the mute by a whole VInt instead makes the effect too long.

The SN76489 tone counters and noise LFSR run continuously. Moving a frequency,
noise, or attenuation write changes which oscillator/LFSR state is heard. A
within-VInt write position is therefore required; a synthetic reverb, fade, or
sound-specific duration change cannot reproduce the native result.

This does **not** require general instruction timing. The first slice only needs
the finite SFX PSG paths that already produce writes in the high-level runtime.

## Source authority

The slice is authenticated to locked-on S&K:

```text
ROM SHA-1: CFBF98C36C776677290A872547AC47C53D2761D6
SonicDriverVer = 4
fix_sndbugs = 0
FixMusicAndSFXDataBugs = 0
FixBugs = 0
```

The checked-out source owns behavior and timing costs:

- `zUpdateSFXTracks` fixed-slot scan: `Z80 Sound Driver.asm:727-759`;
- PSG timer/note/modulation/envelope service: `4058-4147`;
- rest and channel silence: `4205-4247`;
- `cfStopTrack` and `zStopPSGTrack`: `3423-3529`;
- interrupt handler and entry into SFX service: source-cited by the checked
  calculation artifact.

Decoded native instruction/write captures validate the calculations. Captured
write gaps and PCM are comparison oracles only and never become production
constants. Every retained capture is A/B reproducible, bounded, source-owner
joined, and zero fault/overflow. Any audited group used for absolute timing must
also report zero DMA stalls.

## Goals

1. Place covered S3K SFX PSG writes at source-derived positions within their
   driver service.
2. Preserve exact PSG write value, physical-slot order, equal-cycle order, and
   already-committed write lifetime.
3. Keep SMPS parsing, requests, priorities, ownership, modulation, envelopes,
   snapshots, rewind, and modding in their existing owners.
4. Cover a finite ROM-backed grammar with explicit covered/unavailable results.
5. Leave YM timing, S1, S2, and standalone-S3 behavior unchanged.
6. Make unsupported retail or modded paths retain current immediate behavior
   rather than guessing.

## Non-goals

- full or partial production Z80 emulation;
- a unified YM/PSG CPU scheduler;
- retiming music PSG or any YM write in this slice;
- runtime use of diagnostic traces, disassembly files, sound IDs, zones, movies,
  frame numbers, or captured amplitudes;
- complete-game bit-identical PCM;
- synthetic reverb, gain, filter, duration, or release-tail compensation.

## Architecture

### 1. One unpublished SFX PSG journal per VInt service

The existing outer `SmpsDriver` VInt batch transaction gains one private bounded
SFX PSG journal shared by every SFX sequencer serviced at that boundary. If any
active SFX path is unavailable, the complete SFX PSG journal for that VInt uses
the existing immediate publication path; a covered sequencer cannot be timed
around an unclassified sibling. A journal entry records:

```text
physicalSlot: PSG1 | PSG2 | PSG3
semanticOperation:
  TONE_LOW | TONE_HIGH | NOISE_MODE | ATTENUATION | SILENCE
value: unsigned byte
sourceDescriptor
sourceTrackIdentity
serviceOrdinal
writeOrdinal
```

The sequencer records the PSG writes it already intends to publish. Each active
FM or PSG track also contributes a bounded `SlotTimingSummary` containing the
small semantic decisions needed to calculate the path it actually took:
inactive/active slot, timer sustain/expiry, rest/note/tie, reached
coordination-command class, modulation branch, envelope branch, terminal
branch, and hardware-write count. It does not predict the stream with a second
interpreter and does not duplicate YM write values.

The journal is capped at 4,096 semantic events and 4,096 PSG writes per service.
Overflow poisons the unpublished transaction. Exact values remain attached to
the writes; timing cannot authorize a different register value.

The current engine may update different SFX sequencers in insertion order. The
timing resolver projects their active PSG channel claims onto the native fixed
slots PSG1, PSG2, and PSG3. Duplicate physical owners are rejected by the
existing contention transaction. The resolver orders only the unpublished
hardware writes; it does not replay or reorder logical SMPS state mutations.
Tests prove that reordering is safe for the covered grammar because those tracks
share no mutable stream or channel state after contention has committed.

### 2. Bounded source-cost resolver

`S3kPsgServiceTimingProfile` consumes the completed journal and returns either:

```text
COVERED(List<ResolvedPsgWrite>)
UNAVAILABLE(reason)
```

Each resolved write contains its due master cycle, dense source ordinal, value,
physical slot, source descriptor, and calculation-row identity. The resolver
uses a generated, checked source-cost table for only these routines:

- interrupt entry through `zUpdateSFXTracks`;
- inactive FM/PSG slot scan costs;
- reached earlier active-slot service summaries needed to locate PSG1-3;
- the covered PSG timer/note/modulation/envelope branches;
- PSG output, rest, silence, and stop paths;
- return from the covered PSG service.

Earlier FM slots contribute time summaries only. Their YM writes and existing
YM source-timing implementation are untouched. A summary is selected from live
semantic branch facts and is accepted only when its checked executed-row slice
accounts for every branch and write. An unrecognized earlier-slot path makes the
whole SFX PSG journal unavailable; it is not approximated from a neighboring
path.

The source-cost artifact stores decoded PC/opcode, branch outcome, Z80 T-states,
GPGX's explicit average three-T-state bank-window wait where applicable,
semantic event, and disassembly citation. Independent tests regenerate every
sum and reject row deletion, reorder, wrong opcode, wrong branch, wrong event,
wrong value predicate, orphan row, overflow, or unknown selector.

Write-value predicates are semantic rather than fixture-specific. For example,
an attenuation operation accepts the actual low nibble selected by the live
envelope; it does not hard-code Collapse's value. Constant source writes such as
terminal silence remain exact constants.

### 3. Service anchor and bounded accuracy

The existing S3K driver-service boundary is the VInt anchor. At that boundary,
the driver reads the PSG chip's rendered master-cycle frontier; this is the
absolute `serviceAnchorMasterCycle` for every entry in the journal. Hybrid
rendering must already have drained audio exactly to that boundary. The checked
source program includes interrupt-entry cost through the first SFX slot and
uses the actual active-slot/path facts for the remainder of the service.

Every covered resolved write must be at or after that anchor and strictly before
the next driver-service anchor already owned by the sequencer's sample-phase
accumulator. A source path whose writes can cross that boundary is unavailable
in this slice. This prevents a later service from being committed ahead of
unresolved work from its predecessor without replacing the existing regional
cadence model.

The first slice deliberately does not model the complete DAC playback-loop
phase. Native captures quantify the resulting entry-phase variation. Coverage
is accepted only when:

- the source-derived write positions fall within the predeclared native window
  for every retained group;
- no tested initial DAC phase regresses the component-PCM error versus immediate
  publication;
- the terminal partial-frame sample count is within the predeclared bounded
  tolerance; and
- no per-phase aggregate is selected after observing the result.

The tolerance and phase set are fixed in the research artifact before runtime
code is changed. If the bounded model cannot improve every retained phase, the
family remains unavailable and a separate DAC-phase design is required. No
captured mean or best-fitting offset may enter production.

This boundary is intentional scope control: it gives the PSG the timing
resolution required by the audible bug without creating a general Z80 phase
machine.

### 4. PSG write timeline and exact chip application

Add a private fixed-capacity `PsgWriteTimeline` beside the existing YM timeline.
Its 4,096-entry snapshot-safe record contains:

```text
dueMasterCycle
ordinal
value
driverGeneration
serviceOrdinal
sourceDescriptor
physicalSlot
semanticOperation
```

Commit is atomic and validates nondecreasing due cycle, dense ordinal, current
generation, source identity, and byte range. Snapshots preserve pending entries,
capacity, next ordinal, and generation. Hard reset, synthesizer replacement,
and full silence use the existing generation barriers. Ordinary SFX completion
does not erase committed entries.

`PsgChip` applies writes with the same clock rule as the pinned GPGX PSG core:
advance PSG state to the requested master cycle, round its internal clock up to
the 240-master-cycle PSG boundary, mutate the register/latch, then continue
rendering from the preserved counters, LFSR, deltas, and blip-resampler state.
Writes are not rounded to a host output sample. Equal-cycle entries retain
source ordinal.

The PSG timeline is chip-private because YM and PSG state are independent. The
resolved service journal retains one global source ordinal for audit. Existing
`ChipWriteObserver` callbacks remain drain-bound and guarantee per-chip order;
they do not claim callback arrival order across separate chip renderers. A
package-private timed diagnostic tap includes due cycle and source ordinal, so
trace tests can merge YM and PSG records by `(dueMasterCycle, sourceOrdinal)`
without changing the public observer contract.

Untimed PSG writes remain immediate when no pending timed predecessor exists.
After a timed SFX PSG journal commits, later same-VInt unprofiled PSG writes are
fenced after its final due cycle so music cannot overtake SFX. Those later writes
remain timing-partial and are not used for exact component-PCM claims.

### 5. Transactions, capacity, snapshots, and rendering

The outer driver batch preflights the aggregate number of PSG entries before any
sequencer, chip, lock, observer, phase, or ordinal mutation. N succeeds; N-1
fails with exact deep rollback. YM capacity accounting remains unchanged and is
preflighted independently.

Logical service/contention observers are staged until the whole batch commits.
PSG chip callbacks fire only when a committed entry actually mutates the chip.
A generation barrier discards pending entries without callbacks.

Service-end snapshots contain the current PSG state plus the self-contained
pending timeline. They do not apply future writes to a shadow chip early. A
snapshot after partial drain captures the advanced oscillator/LFSR/blip state
and only the remaining entries. Restore, live rollback, presentation replacement,
and `adoptActiveSfxFrom` preserve or generation-remap that exact state.

Both sample-accurate and hybrid rendering fence at the earliest pending PSG
write or driver-service boundary. `PsgChip` performs exact internal-cycle
segmentation, so output buffer partitioning cannot move a write. Whole-buffer
and arbitrarily chunked rendering must produce byte-identical PCM and deep-equal
chip/timeline snapshots.

## Coverage boundary

The first slice covers the common locked-on SFX PSG grammar reached by the ROM:

- inactive and active fixed-slot scan through PSG1-3;
- timer sustain and expiry;
- note, rest, tie, and reached coordination commands before a PSG write;
- reached modulation preparation/update branches;
- reached PSG volume-envelope branches;
- tone frequency, noise mode, and attenuation writes;
- rest, silence, `cfStopTrack`, and `zStopPSGTrack` termination.

A ROM-backed static census walks every S3K SFX stream and classifies its reachable
PSG service shapes as covered or unavailable. The census is grammar-based and
contains no sound-specific runtime table. Collapse and Dash must be covered.
At least one unrelated PSG noise effect and one PSG tone effect are positive
controls. Unsupported effects remain immediate and must be byte-identical to the
pre-feature runtime in write order, values, and semantic state.

The slice does not claim exact music-PSG timing or exact PCM when an unavailable
music PSG path overlaps a covered SFX. It does preserve SFX-before-music hardware
order. Existing S3K YM timing, Blue Sphere, Ring Loss, Spike Hit, Spindash,
music restore, S1, and S2 remain regression controls.

## Evidence and acceptance

### RED gates

Before production changes, retain package-confined tests showing:

- Collapse's native terminal frame has 107 non-zero PSG samples while the
  aligned engine frame has zero;
- selected attack and repeat frames differ despite equal effective PSG state;
- an immediate terminal mute fails the partial-frame oracle;
- a whole-VInt delayed mute fails duration and tail bounds.

### Source and native proof

- capture fresh A/B instruction, PSG-write, and component-PCM streams with the
  pinned headless GPGX core;
- generate the bounded source-cost artifact from decoded executed rows;
- regenerate it independently and compare byte-for-byte;
- prove calculated write positions against every retained covered group without
  copying captured deltas into production;
- retain exact ROM/movie/core/patch/tool hashes and zero fault/overflow/DMA;
- run the predeclared initial-phase matrix and report every phase, not only an
  aggregate winner.

### Runtime proof

- timeline order, equal-cycle order, capacity N/N-1, overflow, stale generation,
  reset-before-due, and ordinary-completion retention;
- snapshot before drain, partial drain, restore, live rollback, observer failure,
  retry-once, and replacement/adoption;
- exact GPGX clock-boundary application and buffer-partition equality;
- Collapse and Dash effective writes, component PCM, repeat texture, and terminal
  partial frame;
- ROM-wide census stability and unsupported-path byte identity;
- no semantic lifecycle, request, priority, or channel-ownership change;
- S3K YM plus S1/S2 regression controls.

### Listening proof

The handoff build repeats:

- Collapse in isolation and over music, including its complete tail;
- Spindash Release;
- Blue Sphere and ring collection;
- Invincibility melody note fills;
- one unrelated PSG-noise SFX and one PSG-tone SFX.

No merge or push occurs until the user confirms a positive improvement.

## Failure handling

- Malformed timing data, ambiguous slot ownership, event/write mismatch,
  arithmetic overflow, capacity failure, or invalid snapshot aborts the
  unpublished transaction and restores its exact mutation token.
- An unclaimed path uses current immediate timing and reports `UNAVAILABLE`.
- A path claimed covered but unresolved is a test/diagnostic failure, never a
  silent fallback.
- Native disagreement blocks that grammar family; it never authorizes tuning a
  delay until the waveform looks closer.

## Rejected alternatives

### Collapse-specific tail, fade, or reverb

The native texture is generated by the continuously running PSG and correctly
timed source writes. Presentation processing would hide the cause and change
other playback contexts.

### Whole-VInt delay

Immediate publication cuts the sound off too early; a whole-VInt delay keeps it
alive too long. Neither can reproduce a partially audible terminal VInt.

### Unified chip or complete Z80 scheduler

YM timing is already separately owned and does not need replacement to fix this
PSG defect. Full CPU scheduling would duplicate production driver state and
expand the slice far beyond the audible bug. If the bounded phase matrix or
ROM-wide census shows that most paths require unmodeled CPU state, this design
must stop and be reconsidered rather than grow into an accidental emulator.

## Completion boundary

This slice is complete when the bounded PSG grammar is source-derived, the
ROM-wide census has no unclassified path, Collapse/Dash component gates improve
for every predeclared phase, existing YM and cross-game controls remain green,
the full-suite red identity ledger introduces no attributable regression, and
the listening gate confirms that Collapse's texture and ending are a positive
improvement.

It does not claim complete S3K PCM parity. Remaining unavailable timing families
stay explicit and are prioritized only when they cause a demonstrated audible or
route-impacting defect.
