# Sonic 1 GHZ1 Gameplay Audio Timeline Design

## Requirements

### Goal

Use the existing `sonic1-complete-withemeralds.bk2` gameplay recording to
identify when music and sound effects play during GHZ1 and compare the natural
ROM and OpenGGF timelines. The MVP must expose both music/SFX channel
contention and SFX/SFX contention.

### Acceptance criteria

- The reference capture uses Sonic 1 World REV01, BizHawk 2.11, Genesis Plus
  GX, and the exact committed complete-game BK2.
- GHZ1 is selected from the committed run manifest: BK2 offset `860`, trace
  frame count `4115`, giving the half-open trace-row interval `[860, 4975)`.
  The following movie row is an intentional gap and the special-stage mode
  transition occurs at frame `4976`.
- The reference output identifies each raw music or SFX queue request at its
  movie frame, then identifies the later driver admission separately with the
  same ordinal, resolved sound ID, and acquired hardware roles.
- The output identifies music-track ownership before, during, and after SFX
  overrides, plus replacement between overlapping SFX.
- OpenGGF runs the committed GHZ1 replay normally and reports commands it
  naturally produces. Reference data never injects sound IDs or other gameplay
  values into the engine.
- Comparison reports the first mismatch in request timing/identity, admission
  timing/resolved identity, per-role arbitration, channel ownership, or restoration.
- The existing sound-test chip-write parity remains available for follow-up
  diagnosis; gameplay chip-write equality is not part of this MVP.
- Outputs are deterministic, strictly validated, bounded in memory, and
  published atomically beneath `target/audio-parity/`.

### Non-goals

- Do not cover later acts in the MVP.
- Do not create new BK2 recordings.
- Do not replace or weaken the existing GHZ sound-test music parity contract.
- Do not make trace data an audio authority or production runtime input.
- Do not merge or push before human listening and gameplay review.

### Risks and conservative choices

- A movie frame can contain more than one ROM driver update. Semantic equality
  is anchored to the absolute BK2 frame; the reference additionally carries a
  monotonically increasing diagnostic audio-tick ordinal.
- Queue writes and queue consumption are different events. Capturing both is
  required to distinguish gameplay scheduling from driver priority behavior.
- The ROM has three queue slots and a global SFX priority while OpenGGF admits
  commands directly and arbitrates per hardware role. Queue slot and global
  priority remain ROM diagnostics, but the raw queue request is semantic and
  distinct from admission. Cross-producer equality first compares raw request
  ID/order at submission time, then resolved ID, requested roles, acquired
  roles, displaced owner, and final owner at admission time. Owners carry the
  admitted class, resolved sound ID, and original request ordinal.
- SFX ownership is not equivalent to audible output. The MVP compares logical
  requests, decisions, and ownership; detailed chip writes remain a follow-up
  layer after the causal timeline is trustworthy.

## Exploration synthesis

The committed run manifest at
`src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/run_manifest.json`
defines GHZ1 at BK2 frame `860` for `4115` trace frames. Its segment metadata
names the same committed BK2, so no new fixture or fitted boundary is needed.
The BK2 has SHA-256
`f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b`.
The trusted Java launcher validates the binaries actually loaded from the
canonical BizHawk home: `BizHawk.Emulation.Cores.dll` SHA-256
`0144e6e236be68ce126eb771dcb5a9ae7c153a083fa0333f345ac37b4a60acf7`
and `gpgx.wbx.zst` SHA-256
`c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12`,
in addition to the pinned `EmuHawk.exe`.

On the ROM side, `QueueSound1` (`$00138E`), `QueueSound2` (`$001394`), and
`QueueSound3` (`$00139A`) write the three bytes at sound RAM offsets
`$0A..$0C`. `CycleSoundQueue` (`$071F02`) chooses a queued request, and
`PlaySoundID` (`$071F4C`) applies the priority rules. Accepted dispatch enters
the BGM path at `$071FD2`, normal SFX at `$0721C6`, or special SFX at
`$07230C`. The
existing parity probe already verifies those opcode sites, brackets complete
`UpdateMusic` invocations at `$071B4C/$071C4C`, captures ordered YM2612/PSG
writes, and reads the global priority plus music-track override bits. The
gameplay observer should reuse those proven primitives without inheriting the
sound-test-only `$81` epoch or recurrence assertions.

On the engine side, `AudioManager` records resolved `PlayMusic` and `PlaySfx`
commands in an `AudioCommandTimeline` after `beginFrame`. A separate
disabled-by-default observer at the numeric `playMusic`/`playSfx` entry records
the raw caller request before ring alternation (`$B5` remains `$B5` even when
the presentation command resolves to `$CE`). The live
SMPS presentation snapshot identifies sequencers and FM/PSG lock owners, but a
final snapshot cannot reconstruct two same-frame arbitration steps. A
disabled-by-default observer at `SmpsDriver` sequencer-admission and per-role
arbitration boundaries emits ordered immutable events carrying challenger and
displaced owner identity. It observes the existing decision and is never
consulted by it. The capture therefore needs observation seams, not a second
command source. Its
GHZ1 runner extends `VisualRunReplayHarness` with the production outer-frame
audio boundary: `GameLoop.presentOuterFrame(false, false)` followed by
`GameServices.audio().update()` exactly once. It must not advance an SMPS
driver directly.

The alternative of replaying sidecar sound IDs directly into OpenGGF was
rejected for the authoritative path. It would bypass gameplay scheduling and
violate the repository rule that trace data is comparison-only. A separate
tool-only SMPS command reproducer may be added later for diagnosis, but its
result cannot satisfy this design's end-to-end acceptance criteria.

## Architecture decision

### Ownership and boundaries

1. A dedicated BizHawk gameplay-audio probe owns ROM observation. It validates
   identities and GHZ1 bounds, observes queue writes and consumption, brackets
   sound-driver ticks, and publishes a compact JSONL timeline. ROM-only queue
   and global-priority details are marked diagnostic.
2. A tooling-only Java schema owns strict parsing and canonical serialization
   of both producers' timeline records.
3. A GHZ1 replay observer receives raw OpenGGF caller requests at
   `AudioManager`, then correlates normal command/presentation admission and
   contention events after frame execution. It never submits a command obtained
   from reference data.
4. A comparator owns alignment and first-mismatch reporting. It compares
   semantic events before consulting diagnostic bus writes.

Production gameplay code must not import the parity tooling package. Any new
observation seam belongs at an existing audio boundary, is disabled by
default, is omitted from snapshots, and cannot alter ordering or state.

### Timeline lifecycle

The reference producer observes before movie frame `860` because GHZ music
`$81` is queued during level/title-card setup. Pre-arm timing is not comparable
to OpenGGF, whose headless run launch bypasses the master title screen. The
cross-producer stream therefore begins with a frame-860 baseline stating that
music `$81` is active; the reference retains the preceding queue and dispatch
only as diagnostic provenance. Comparable event rows use exactly the half-open
interval `[860, 4975)`. Audio ticks remain diagnostic and are numbered from
zero at the first complete retained `UpdateMusic` invocation. The semantic
capture stops before movie frame `4975`; the gap and frame-4976 special-stage
transition are outside the MVP.

The OpenGGF producer runs the committed GHZ1 segment through
`VisualRunReplayHarness.replay(..., stopAfterSegmentBody(0))`. This bypasses
only the master title screen while retaining production run launch, level
bootstrap, and title-card behavior. Its segment frame `0` maps to BK2 frame
`860`. It emits the same absolute movie-frame coordinate and assigns
audio-tick ordinals at the normal presentation advance boundary. Fast-forward
must be disabled and asserted disabled. Normal diagnostic presentations run
during bootstrap/title card; their count is separate. The frame-860 baseline
is sampled after segment admission and before processing row 860. Lag rows
still consume one BK2 row and one outer presentation; gameplay counters are
diagnostic and never substitute for the BK2 coordinate. After the baseline,
the producer emits exactly one semantic frame and performs exactly one
semantic-row presentation for each of the 4,115 BK2 rows.

### Record contract

The first JSONL record is metadata containing schema version, capture kind,
ROM and BK2 digests, emulator/producer identity, GHZ1 bounds, and field
inventories. Subsequent records are one of:

- `baseline`: frame `860`, active music ID, and identity-bearing fixed hardware ownership before
  the first comparable gameplay row;
- `frame`: BK2 frame, ordered raw requests (`request_ordinal`, `sound_class`,
  `raw_sound_id`) at caller/queue submission, and separate ordered admissions
  carrying the same ordinal, resolved `sound_id`, `requested_roles`, per-role
  arbitration (`acquired`, identity-bearing `displaced_owner`, `final_owner`),
  and the final fixed ownership vector after the frame's last driver/presentation update;
- `writes`: optional ordered decoded YM2612/PSG events for that audio tick.

The GHZ1 MVP requires `baseline` and `frame`. A frame-local representation is
the common cadence boundary: ROM audio-tick count/order, queue slot, selected
sound ID, and global priority transition remain validated diagnostics but are
excluded from semantic equality. Reference chip
writes may be captured immediately by reusing the proven callback/manifest
source selection, but OpenGGF write attachment and write equality are deferred
unless they can be added through the existing disabled-by-default observer
without changing presentation ownership or cadence.

Numbers are unsigned normalized integers. Records have exact allowed fields,
duplicate keys are rejected, ordinals are monotonic, and event arrays preserve
source order. Diagnostic raw callback data is validated but excluded from
semantic equality.

### Comparison order

The comparator validates both complete inputs before comparison, then checks:

1. capture identity, frame-860 baseline, and GHZ1 bounds;
2. each frame's raw request order, class, and ID;
3. each frame's admission order, ordinal, class, resolved ID, and requested roles;
4. per-role acquired/displaced/final ownership;
5. final ownership vector and music restoration.

There is no event realignment after the first missing, extra, reordered, or
value-different record. Reports contain at most eight preceding and eight
following semantic records from each side.

### Failure and publication behavior

Identity mismatch, malformed JSON, an incomplete driver invocation, a semantic
frame outside `[860,4975)`, a missing or duplicate frame, non-monotonic
diagnostic ordinals, missing terminal metadata, OpenGGF fast-forward, an
OpenGGF row-to-presentation cardinality other than one, or a changed input
between validation and comparison is a capture failure. Producers write to a
sibling temporary file, validate it, and publish with an atomic create-new
operation. BizHawk's `OGGF_OUT` is a fresh staging path only; the trusted Java
boundary strictly validates and atomically create-new publishes it. A failed
BizHawk process enters a separate trusted discard command and can never invoke
publication, even if it happened to leave a complete staging stream. A failed
run preserves prior outputs and deletes its temporary file.

## Feature behavior and acceptance tests

- A synthetic Lua test proves queue-write, later consumption, equal-priority
  replacement, lower-priority rejection, per-role arbitration, and
  music-channel restoration.
- Java schema tests reject unknown, duplicate, type-invalid, out-of-range, and
  non-monotonic data at every nesting level.
- Engine observation tests prove that raw `AudioManager` requests are visible
  before resolution, absent from rewind snapshots, and behaviorally inert.
  Ring coverage proves raw `$B5` with resolved `$CE`. A two-request,
  same-frame overlap proves ordered arbitration is observed rather than
  reconstructed from only the final snapshot.
- Cadence tests prove 4,115 contiguous semantic frames and exactly 4,115
  OpenGGF semantic-row presentations, separately count bootstrap/title-card
  diagnostic presentations, sample the baseline before row 860, reject
  fast-forward, retain lag rows, and accept then fold multiple complete ROM
  `UpdateMusic` ticks within one BK2 frame in source order.
- A static authority guard proves production packages cannot import timeline
  tooling, schema, or sidecar readers. A runner test proves the OpenGGF capture
  receives only the committed BK2/run manifest and has no reference-timeline
  path, bytes, loader, or callback.
- A synthetic comparator test distinguishes raw-request timing/ID from
  admission timing/resolved ID, requested-role, per-role acquisition/displacement, ownership, and
  restoration mismatches while proving ROM-only diagnostics do not gate.
- A real BizHawk GHZ1 discovery run must prove both contention classes: an SFX
  acquires a music-owned role and later restores music, and a new SFX arrives
  while a prior SFX is active and is replaced, rejected, or coexists according
  to its role overlap. Absence of either class fails MVP acceptance rather than
  being filled with a synthetic or fitted event.
- Two reference captures and two OpenGGF captures must be byte-identical within
  each producer before any parity conclusion is reported.

## Rollback and extension

All new runtime hooks are disabled-by-default observers, so rollback removes
the tooling and seams without changing saved state or gameplay behavior. Once
GHZ1 is useful, later acts can reuse the manifest-selected interval contract;
no schema field is keyed to GHZ by name or fitted event frames.
