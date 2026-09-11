# Visual Special-Stage Admission Observation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:test-driven-development` and execute this plan task-by-task.

**Goal:** Prevent the first visual S1 special-stage tick from closing its newly
admitted run segment with zero compared rows.

**Architecture:** Keep strict row-driver closure and the shared coordinator
unchanged. Repair the visual adapter by invalidating a
`RunPlaybackObservation` when `beforeAdmission` changes coordinator segment
ownership, then recapturing engine state before exhaustion or closure checks.

**Tech stack:** Java 21, JUnit 5, Maven Surefire, Mockito for the active special
stage provider seam already used by project tests.

## Global constraints

- Trace data remains comparison-only; no gameplay state hydration.
- No game, zone, route, frame-number, or fixture-name carve-outs.
- The row driver must continue rejecting genuine early closure.
- Preserve same-iteration level-load production-owner behavior.
- Work in `.worktrees/visual-ss-production-handoff` and do not modify the
  user's dirty main-workspace files.

---

### Task 1: Reproduce stale source exhaustion at real visual admission seam

**Files:**

- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`

**Interfaces:**

- Consumes: `TraceSessionLauncher.runAdvanceTickIfActive(GameMode, int)`,
  `TraceRunPlaybackCoordinator`, `TraceRunFailureStatus`, and the real
  `src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds` manifest.
- Produces: regression
  `specialStageAdmissionRecapturesExhaustionBeforeDestinationClosure()`.

- [ ] Add a test that constructs a real `TraceSessionLauncher`, coordinator,
  boundary probe, active special-stage provider, and exhausted source
  comparator. Move the coordinator to `TRANSITION_GAP`, then call
  `runAdvanceTickIfActive(GameMode.SPECIAL_STAGE, 4976)`.
- [ ] Clear `TraceRunFailureStatus` in test setup/teardown. During the red
  phase, use a temporary assertion to confirm the launcher records
  `dynamic-art segment expected 3728 rows but compared 0` rather than
  expecting an exception to escape; retain the failing Maven output as the
  red evidence, then replace that temporary assertion in the permanent test
  with `TraceRunFailureStatus.current().isEmpty()`.
- [ ] Assert that segment 1 is admitted, `runSpecialRowDriver` remains present
  at cursor 0, no `CloseSegment(1)` or `FailRun` action exists, and the
  coordinator remains in `CURRENT_SEGMENT` for segment 1.
- [ ] Continue through `prepareHardwareTimingForAdmission`, one
  `SPECIAL_STAGE` PLC logical iteration, and `afterProductionIteration`.
  Assert row 0 publishes through the admitted destination driver, advancing
  its cursor and comparison count to 1.
- [ ] Run:
  `mvn -q -Dmse=off -Dtest=com.openggf.TestTraceSessionLauncherRunBranch#specialStageAdmissionRecapturesExhaustionBeforeDestinationClosure test`.
  Expected before the fix: FAIL because `closeRunSegment(1)` invokes
  `verifyComplete()` and reports `dynamic-art segment expected 3728 rows but
  compared 0`.

### Task 2: Recapture observations across admission ownership changes

**Files:**

- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`

**Interfaces:**

- Consumes: `runCoordinator.currentSegmentIndex()` and
  `captureRunObservation(GameMode, int, boolean)`.
- Produces: an observation whose exhaustion and identity belong to the current
  coordinator segment.

- [ ] In `runCoordinatorTick`, record the segment index immediately after
  capturing `currentObservation` and before `beforeAdmission`.
- [ ] After applying admission actions, compare the coordinator's current
  segment index with the recorded owner. If it changed, replace
  `currentObservation` with a fresh capture before building
  `productionObservation` or evaluating `currentSegmentExhausted`.
- [ ] Do not alter `withProductionOwner`, coordinator policy, row-driver
  verification, hardware-timing admission, or dynamic-art publication.
- [ ] Rerun the single regression. Expected: PASS.
- [ ] Run the complete launcher branch class. Expected: all selected tests
  pass.

### Task 3: Verify parity and document the moved visual frontier

**Files:**

- Modify: `CHANGELOG.md`
- Modify: `docs/status/trace-frontier-log.md`

- [ ] Run the focused parity selection:
  `mvn -q -Dmse=off -Dsonic1.rom.path=s1.gen
  -Dtest=com.openggf.TestTraceSessionLauncherRunBranch,com.openggf.trace.replay.runs.TestTraceRunSpecialStageRowDriver,com.openggf.TestSpecialStageVisualTraceSession,com.openggf.TestSpecialStageHardwareTimingLifecycle,com.openggf.tests.trace.runs.TestS1CompleteEmeraldRunPrefix,com.openggf.tests.TestArchitecturalSourceGuard test`.
- [ ] Run the exact emerald diagnostic command through the real run-chain
  regression and confirm the first special-stage segment admits and publishes
  row 0 rather than recording the 3,728-versus-zero failure. Record any later
  independent frontier honestly.
- [ ] Update changelog and frontier log with the stale-observation root cause,
  exact commands, pass/fail totals, and next frontier.
- [ ] Run `git diff --check` and inspect the final diff.
- [ ] Commit with required documentation trailers, merge into updated
  `develop`, rerun focused verification, push `develop`, and remove the clean
  worktree/branch.
