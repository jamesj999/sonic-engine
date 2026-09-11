# Visual S1 replay-clock and end-act PLC parity implementation plan

Date: 2026-08-03

Design: `docs/architecture/designs/trace/2026-08-03-visual-s1-end-act-plc-parity-design.md`

## 1. Lock the prepared replay-clock invariant with failing tests

Files:

- `src/test/java/com/openggf/trace/replay/TestTraceReplaySessionBootstrapClockParity.java`
- `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`

Add a focused JUnit 5 test around a loaded S1 fixture which gives the object
manager an arbitrary title-card-era VBlank count, invokes prepared bootstrap,
and asserts that the pre-row value is `trace.initialVblankCounter() - 1`.
Cover an initial trace value of zero and a nonzero value near low-word wrap.
Add a companion assertion that ordinary bootstrap ends at the identical
pre-row value. Run the test first and record that prepared bootstrap fails.

Implement `alignObjectVblankCounterForReplayStart(TraceData)` as a single named
operation. Call it at the end of both ordinary and prepared bootstrap, after
ordinary bootstrap's synthetic object prelude and before replay-start state is
returned. Do not alter level frame counters, V-int phase offsets, title-card
objects, PLC state, or dynamic-art state.

Run:

`mvn -Dtest=com.openggf.trace.replay.TestTraceReplaySessionBootstrapClockParity test`

## 2. Lock the ROM fixed `v_endcard` contract with failing tests

Files:

- `src/test/java/com/openggf/game/sonic1/TestSonic1PlcProducerCoverage.java`
- `src/test/java/com/openggf/game/sonic1/objects/TestSonic1GiantRingObjectInstance.java`
- `src/test/java/com/openggf/game/sonic1/objects/TestSonic1FixedEndCardSlot.java`

Add tests proving:

- fixed end-card claim uses absolute slot 23 and succeeds with every allocatable
  S1 dynamic slot occupied;
- only a live `Sonic1ResultsScreenObjectInstance` in slot 23 is idempotent; any
  other live occupant fails closed;
- constructor/claim happens before cue 16, an active decoder leaves the card
  uncommitted and the producer pending, and a later retry submits cue 16 once;
- an uncommitted card cannot advance merely because an unrelated queue drains;
- signpost observation of the retained giant-ring deleted-SST representation
  submits exact cue 16, commits one card, and marks it for special stage;
- Object 7C completion no longer creates a card or plays act-clear music, while
  it can still mark an already-committed card for special-stage routing.
- capture/destroy/restore/update preserves absolute slot 23, restores exactly
  one rewind identity/registration outside `SlotAllocator`, and resumes through
  the S1 pre-dynamic fixed pass without a duplicate card or cue.

Run these tests before implementation and preserve their expected failures.

## 3. Implement fixed results-card ownership and the signpost handoff

Files:

- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- `src/main/java/com/openggf/level/objects/FixedRuntimeObjectInstance.java`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1FixedEndCardSlot.java`
- `src/main/java/com/openggf/game/sonic1/events/Sonic1LevelEventManager.java`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1ResultsScreenObjectInstance.java`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1SignpostObjectInstance.java`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1EggPrisonObjectInstance.java`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1RingFlashObjectInstance.java`

Reuse the object manager's existing explicit-slot registration path rather than
growing its already-guarded orchestration surface. The S1 helper validates that
slot 23 is empty, constructs the results card through
`ObjectConstructionContext`, then registers it with
`ObjectLifetimeOps.addDynamicAtReservedSlot(objects, card, 23)`, keeping the direct
lifecycle mutation inside the approved lifecycle owner. The delegated operation
uses `ObjectManager.addDynamicObjectAtSlot`. `SlotAllocator.reserveOrMarkUsed` is already
a no-op outside the allocatable dynamic window, so the card receives its normal
rewind identity and render registration without consuming pool capacity. The
`FixedRuntimeObjectInstance` marker lets the existing dynamic fallback exclude
only a marked object below `firstDynamicSlot`; restore derives the same owner
from captured type and slot without adding mutable manager state. Constructor
failures propagate before registration, and any live slot occupant fails closed.

Add the S1-owned slot-23 helper with:

`ClaimResult claim(ObjectServices services, ResultsData data)`

with the exact value types:

`record ResultsData(int elapsedSeconds, int ringCount, int actNumber, boolean specialStageAfter) {}`

`record ClaimResult(ClaimState state, Sonic1ResultsScreenObjectInstance card) {}`

`ClaimResult` exposes `requireCard()`, which returns the nonnull card for every
valid state and throws for `INVALID_OCCUPANT`. States are
`NEW_UNCOMMITTED`, `EXISTING_UNCOMMITTED`, `EXISTING_COMMITTED`, and
`INVALID_OCCUPANT`. Only a live `Sonic1ResultsScreenObjectInstance` at slot 23
is idempotent. `INVALID_OCCUPANT` contains no card and the caller throws/fails
closed before touching cue 16. The two uncommitted states require cue retry;
the committed state requires neither queue mutation nor duplicate music/card
creation. The helper updates/removes the fixed card from the S1 pre-dynamic
fixed-object pass. The results card gains a rewind-captured
`resultsPlcCommitted` state; routine zero cannot release before it is true.

Refactor signpost and prison `GotThroughAct` paths to claim/validate the fixed
card before queue mutation. If cue 16 replacement is rejected, leave both the
card uncommitted and the producer routine pending. After successful replacement,
mark the card committed and only then commit the producer completion state and
music. Existing committed-card observation remains idempotent.

Remove direct card/music creation from ring flash. Make signpost walk-off detect
the specific retained deleted-SST representation (`f_bigring`, hidden player,
native bit-7 control with movement suppressed and CPU disallowed), bypass its
ordinary airborne/position checks, and use its existing `GotThroughAct` path.
Initialize a newly claimed card's special-stage route from the live big-ring
flag.

Run:

`mvn -Dtest=com.openggf.game.sonic1.TestSonic1PlcProducerCoverage,com.openggf.game.sonic1.objects.TestSonic1GiantRingObjectInstance,com.openggf.game.sonic1.objects.TestSonic1FixedEndCardSlot test`

Then run rewind guards relevant to new fixed ownership:

`mvn -Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageGuard,com.openggf.game.rewind.coverage.TestStaticStateRewindCoverageGuard test`

## 4. Share whole-run VBlank pacing with the visual launcher

Files:

- `src/main/java/com/openggf/trace/replay/runs/TraceRunVblankClock.java`
- `src/main/java/com/openggf/TraceSessionLauncher.java`
- `src/test/java/com/openggf/trace/replay/runs/TestTraceRunVblankClock.java`
- `src/test/java/com/openggf/trace/replay/runs/TestTraceRunVblankClockAuthorityGuard.java`
- `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`

Add focused failing tests proving that the run clock:

- projects a source level's final VBlank from the production cursor rather than
  reading a destination trace frame;
- calculates the legacy non-emerald GHZ1→GHZ2 and GHZ2→GHZ3 budgets as 230 and
  229 ticks from manifest/BK2 row distances and the S1 six-row profile;
- applies the uncompared-special-stage return budget from the preserved source
  level anchor; and
- produces no target for a disabled game profile;
- drives the production launcher's destination-admission clock seam and proves
  that the 230/229 level targets and the uncompared-interior return target are
  installed on the loaded destination `ObjectManager`; and
- structurally prevents public clock inputs or source dependencies on
  `SegmentPlan`, `TraceData`, `TraceFrame`, comparators, auxiliary state,
  dynamic-art journals, or hardware-timing schedules. The clock's public inputs
  are limited to individual manifest segments, the trace playback profile, and
  observed production BK2/VBlank integer counters.
- opens visual dynamic-art comparison at the same `rowsConsumed` cursor as its
  destination comparator for both zero and one already-produced row, preventing
  the production publisher from lagging the comparator by one row.

Implement `TraceRunVblankClock` as run-scoped, value-free timing state. Capture
each level source tail when the production coordinator closes that segment.
At destination admission, initialize the new object manager from the source
tail plus the same `TraceRunReplayWalker` budget used by the headless chain.
Do not read `TraceFrame.initialVblankCounter` at a segment boundary and do not
add a game, zone, route, or frame carve-out.

Retain the headless chain adapter's existing policy and focused standalone
`TestS1Ghz3CompleteRunTraceReplay` regression. Do not add a passing claim for
the emerald chain: its second S1 special-stage segment independently fails the
standalone comparator at frame 2162 (`ss_rotate`), and end-to-end emerald
coverage remains follow-up work.

Run:

`mvn -Dtest=com.openggf.trace.replay.runs.TestTraceRunVblankClock,com.openggf.trace.replay.runs.TestTraceRunVblankClockAuthorityGuard,com.openggf.TestTraceSessionLauncherRunBranch test`

then:

`mvn -Dsonic1.rom.path="$S1_ROM" -Dtest=com.openggf.tests.trace.s1.TestS1Ghz1CompleteRunTraceReplay,com.openggf.tests.trace.s1.TestS1Ghz3CompleteRunTraceReplay test`

## 5. Regression verification and project records

Files:

- `docs/status/trace-frontier-log.md` (update unconditionally when either
  reported visual/continuous frontier moves)
- `CHANGELOG.md`
- `README.md` during integration into `develop`

Use JDK 21 (`mvn -v` must report 21) and set `S1_ROM`, `S2_ROM`, and `S3K_ROM`
to the discovered, hash-verified ROM paths.

Run the S1 replay sweep:

`mvn -Dsonic1.rom.path="$S1_ROM" -Dtest='com.openggf.tests.trace.s1.*TraceReplay' test`

Run all existing chain lanes:

`mvn -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" -Dtest='com.openggf.tests.trace.runs.Test*Chain' test`

Run the full suite:

`mvn -Dsonic1.rom.path="$S1_ROM" -Dsonic2.rom.path="$S2_ROM" -Ds3k.rom.path="$S3K_ROM" test`

Record exact commands, pass/fail/error counts, and first divergence in the
trace frontier log whenever the frontier moves or a passing trace regresses.

## 6. Scoped commits, review, and integration

After the green scoped checkpoints, stage only task-owned files in one
production `fix` commit: prepared/bootstrap parity, fixed end-card ownership,
shared visual/headless run-clock pacing, focused tests, the reviewed design and
plan, `CHANGELOG.md`, and the updated frontier log. The commit sets
`Changelog: updated` and every other required trailer names its actual staged
obligation. Before the integration merge, stage
the required `README.md` release/change-log summary in the main-workspace
integration commit so the non-master-to-`develop` policy is satisfied.

Before completion, request a code review and resolve all valid issues. Then
follow repository integration policy: fetch and fast-forward the main-workspace
branch without overwriting user changes, record its full-suite baseline, run
the same suite plus focused tests in this worktree, merge into the unchanged
main-workspace branch with the required README release note, compare the merged
suite to baseline, push the main branch, and remove the clean merged worktree
and local implementation branch.
