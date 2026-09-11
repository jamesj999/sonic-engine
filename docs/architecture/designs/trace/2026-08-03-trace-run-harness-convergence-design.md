# Trace-run harness convergence

Date: 2026-08-03

## Problem

The visual trace launcher and `AbstractRunChainTest` share the manifest parser,
segment planner, playback coordinator, row policy, hardware-timing port, and
comparators, but they still assemble those parts with separate mutable row
state. That remaining adapter duplication has produced two misleading classes
of failure.

First, `s1-sonic-complete-withemeralds` is selectable from the visual catalog
but has no dedicated headless chain lane. Replaying that manifest through the
existing headless chain body reproduces the visual GHZ1 mismatch at trace frame
3596 (`player_animation_id`, expected `$1C`, actual `$00`). This is therefore a
shared runtime defect hidden by missing route coverage, not a renderer-only
defect. The prior green GHZ runs exercise different, non-emerald fixtures.

Second, a visual special-stage segment advances `runSpecialLocalRow` in the
special-stage input hook while its `DynamicArtSegmentComparison` advances in a
separate post-production hook. The reported closure failure, “expected 3728
rows but compared 0,” proves only that an untouched comparator was verified; it
does not by itself prove that special-stage production consumed input rows.
This split ownership is nevertheless a structural risk. A launcher regression
must first establish whether input advanced while comparison did not, or
whether an earlier boundary/cleanup path verified the comparator before the
first SS iteration. The headless chain has the same concepts again as a local
`int[] traceRow` and a separately owned comparison accumulator. Sharing value
types does not prevent either adapter from advancing only half of a represented
row or from closing an owner that never admitted a row.

The headless fixture and live fixture also duplicate the implementation of
playable-only terminal production slices even though both already expose the
same `GameplayModeContext`. That is another unnecessary behavior seam.

## Source of truth

A represented trace row is atomic. Its BK2 input and lag policy are selected
before production; any recorded hardware readiness is admitted at the same
boundary; production publishes the engine-created PLC/dynamic-art snapshot;
only then may comparison and the represented-row cursor advance. Recorded
values remain comparison-only except for the existing hardware-timing delay
contract.

For the S1 giant-ring route, Object 7C writes `id_Null` (`$1C`) to the player
animation at flash frame 3 and deletes the player SST at flash frame 8
(`docs/s1disasm/_incObj/4B, 7C Giant Ring and Flash.asm`). The retained engine
sprite must continue to expose the deleted SST's last recorded animation byte
for comparison; creating or updating `v_endcard` must not revive normal player
animation. The frame-3596 fix must be derived from that native object state,
not from a route or trace-frame exception.

## Design

### One represented special-stage row owner

Add `TraceRunSpecialStageRowDriver` under `trace.replay.runs`. It owns:

- the immutable `TraceRunSpecialStageRows` policy;
- the segment's `DynamicArtSegmentComparison`;
- one represented cursor;
- at most one admitted, unpublished row.

Its state machine is `READY -> ADMITTED -> READY`, with `COMPLETE` represented
by `cursor == rowCount` and no pending admission. `admitCurrentRow(before)`
returns a record containing the row index and existing
`SpecialStageRowAdmission`, and captures the immutable pre-production
publication evidence: delivery serial and target comparison generation.
`publishAdmittedRow(after)` owns the shared atomic-publication check. For an
advertised row, `after` must have a newer delivery serial, be published in the
admitted generation, and name the admitted row. For an unadvertised row, the
driver still commits structural progress without comparing dynamic-art fields.
It then compares that exact row, advances the cursor once, and returns the
optional `FrameComparison` for the adapter's common report sink. Duplicate
admission, publication without admission, out-of-order publication, early
verification, and extra rows fail immediately. Special-local destination
admission requires `rowsConsumed == 0`; unlike a level title-card fall-through,
no special-stage production row may execute before the coordinator admits its
local input owner. A one-row receipt is rejected rather than silently advancing
an advertised comparison without row-zero evidence.

The driver always advances its comparison accumulator, even when a fixture did
not advertise per-frame dynamic art; advertising controls which fields are
compared, not whether the structural row commits. It has no access to a movie,
input handler, game services, gameplay objects, or mutable gameplay values.

The visual launcher uses the driver cursor everywhere it currently reads
`runSpecialLocalRow`. Admission occurs in
`prepareHardwareTimingForAdmission`; input application consumes the already
admitted row; the special-stage update clears input and applies the existing
preserved-VBlank policy but does not advance the row; post-production validates
and publishes the after snapshot through the driver before draining any action
that can close, verify, or replace the segment owner. The all-mode coordinator
therefore observes the committed cursor after the host production wrapper
finishes. Headless passes the before snapshot at admission and the after
snapshot after its engine step through the identical validation path; it no
longer has weaker publication semantics than visual replay.

The headless chain replaces its local row array and independent comparison with
the same driver. Its per-frame callback admits, applies hardware timing, drives
the engine, publishes the resulting immutable snapshot, and only then tests
completion. This is the executable parity proof: both adapters use the same
state transition and cannot independently reinterpret row advancement.

### Shared fixture production slices

Move the default implementations of `advancePlayableAnimationsOnly` and
`advancePlayableFixedSlotsOnly` into `TraceReplayFixture`, expressed solely in
terms of `gameplayMode().getSpriteManager()`. The playable prefix calls
`advancePlayableSlotPrefix()`, retaining its follower-history write as well as
animation dispatch. Both the live and headless fixtures inherit the defaults.
Fixture-specific implementations are removed unless a fixture has a genuinely
different external I/O responsibility. This preserves the existing native
slot ordering while removing duplicated behavior.

### Route discovery and execution coverage

Add a catalog/plan parity guard which independently enumerates every eligible
on-disk `<game>/runs/*/run_manifest.json`, then asserts each manifest produces
exactly one master-title catalog entry and prepares through the same
`TraceRunReplayWalker` path available to headless tests. This catches silent
catalog omission as well as parser divergence. A visual-only manifest, an
on-disk run missing from the UI, duplicate catalog entry, or a headless-only
parser path is a test failure.

Add a dedicated S1 emerald chain regression lane rather than reusing the
short-maze class through an external property. Extract an adapter-neutral stop
condition from the whole-run chain body, expressed as a target segment and
committed represented row. The ordinary chain lanes retain an end-of-run stop;
the emerald lane initially stops only after the reported GHZ1 boundary and
first special-stage handoff have committed through the shared row driver. The
test reports the first real frontier rather than permitting a visual route to
remain wholly untested. Its executable prefix is explicit, advanced with the
trace frontier, is not an expected-failure/disabled test, and does not waive
mismatches within the covered prefix.

### S1 deleted-player animation

Add a focused native-state regression around the ring-flash/signpost/end-card
ordering and a chain regression at frame 3596. Diagnose which production owner
clears the retained sprite's forced/null animation. `AbstractPlayableSprite`
exposes an explicit, rewind-captured `nativeSlotPresent` lifecycle bit because
the engine retains that structural object after the ROM clears its SST. The S1
ring-flash owner clears the bit at the exact native word-clear operation, and
shared animation dispatch consults only that semantic. Visibility and object
control do not imply deletion: a cross-game regression proves an S3K sprite in
the same flag combination still dispatches normally. No trace, frame number,
zone, run id, or visual-session flag participates in the decision.

## Failure and UI behavior

The visual launcher's existing error presentation remains the terminal owner
for user-visible failures. Shared row-driver exceptions include segment-local
row state so the display can report “admitted row N was not published” rather
than only a terminal count. No exception returns silently to the menu.

## Verification

- Unit tests prove the shared row driver's atomic state machine and shared
  delivery-serial/generation/frame validation, including a 3,728-row advertised
  segment and omitted-publication rejection.
- Launcher tests drive the real coordinator's special-local destination
  receipt and prove BK2 input, hardware timing, atomic publication, comparison,
  cursor advancement, and exactly-once closure use one admitted row owner.
- Headless adapter tests prove the same driver handles input, lag, hardware
  timing, dynamic art, and completion.
- Fixture tests prove live and headless terminal animation/fixed-slot slices
  dispatch through the shared defaults.
- Catalog parity tests prove every eligible on-disk run appears exactly once in
  the UI and uses a headless-loadable plan.
- The dedicated emerald lane reproduces frame 3596 before the S1 fix and passes
  through the first level-to-special-stage handoff afterward, recording any
  later independent frontier.
- Existing standalone S1 traces, short S1 chain, S2/S3K chains, launcher tests,
  PLC guards, and the full suite introduce no regression.
