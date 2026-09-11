# Visual Trace and Headless Replay Parity Implementation Plan

## Status

Implemented. Where an established test owner already exercised the same production boundary, coverage was consolidated there instead of adding a duplicate class with the provisional filename below. The matching validation report maps the executed focused and full-suite evidence.

**Goal:** Make master-title visual trace replay execute and diagnose the same
recorded row contract as the headless replay harness, including S1/S2 PLC and
player-DPLC publication and S3K direct/module Kosinski timing.

**Design:**
[`2026-08-01-visual-trace-headless-parity-design.md`](../../designs/trace/2026-08-01-visual-trace-headless-parity-design.md)

**Runtime:** Java 21, JUnit 5, Mockito, Maven.

## Global constraints

- Trace physics, queue, and dynamic-art data remain comparison-only.
- `HardwareTimingReplayPort` may only release matching prepared,
  production-submitted work at a recorded service boundary.
- No game-name, zone, route, frame-number, or trace-name carve-out enters
  shared gameplay code.
- S1/S2 PLC and DPLC expected values never schedule or populate work.
- Each stored physical row owns at most one production lifecycle closure.
- Preserve unrelated changes in the main workspace.
- Use JDK 21 and compare full-suite results against the updated integration
  baseline before merging.

## Task 1: Freeze the shared replay-row policy

**Files:**

- Create: `src/main/java/com/openggf/trace/replay/TraceReplayRowPolicy.java`
- Create: `src/test/java/com/openggf/trace/replay/TestTraceReplayRowPolicy.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestS3kAizPrefixClosureContract.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceExecutionModel.java`

**Interfaces:**

- Produces one immutable policy for trace index, execution phase, represented
  raw row, validation row, relative applied BK2 row, VBlank closure,
  publishing lifecycle, held-sidekick action, and post-row playable-prefix
  action.
- Consumes only existing structural predicates in `TraceReplayBootstrap`.

- [x] Add failing table tests for S1/S2 full/VBlank rows and S3K
  `ADVANCE_ONLY`, `VBLANK_ONLY`, `PLAYABLE_ANIMATION_ONLY`, ordinary full, and
  held-sidekick full rows.
- [x] Prove previous BK2 input is selected only for S3K prefix gameplay-running
  phases; validation always remains the current row.
- [x] Prove every suppressed stored row requests zero or one closure and no
  policy contains expected queue/dynamic-art values.
- [x] Implement the immutable policy/factory and rerun the focused tests.

## Task 2: Prepare and apply visual input before admission

**Files:**

- Modify: `src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/test/java/com/openggf/TestPlaybackAdvanceOnlyInputBridge.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunReplayWalkerControlFlow.java`
- Modify: `src/test/java/com/openggf/trace/live/LiveTraceComparatorTest.java`
- Create: `src/test/java/com/openggf/TestGameLoopTraceInputAdmission.java`

**Interfaces:**

- `PlaybackFrameObserver` gains default pure row preparation and applied-input
  offset hooks.
- `PlaybackDebugManager` gains idempotent prepare, read-only input peek, and a
  cached skip decision for the prepared current cursor.
- `BoundaryProbe` forwards the new hooks to its delegate.

- [x] Add failing tests for current/previous held masks, action and Start edge
  history, cursor invariance, out-of-range failure, peek mask conversion, and
  no-observer compatibility.
- [x] Add a failing run-probe test proving a delegated `-1` offset survives the
  wrapper and detached gaps use zero.
- [x] Add a failing GameLoop test proving playback Start reaches ROM admission
  without toggling the user-facing audio/HUD pause.
- [x] Implement pure policy preparation before Start/pause evaluation,
  idempotent input synchronization, represented activation, and cached skip.
- [x] Move the skip query before generic timers and make suppressed rows leave
  `TimerManager` held.
- [x] Implement `LiveFixture.peekRecordingInputAt()` through the playback peek
  seam and rerun the focused tests.

## Task 3: Share suppressed-row production closure

**Files:**

- Create: `src/main/java/com/openggf/trace/replay/TraceSuppressedRowClosure.java`
- Modify: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Create: `src/test/java/com/openggf/trace/replay/TestTraceSuppressedRowClosure.java`
- Modify: `src/test/java/com/openggf/tools/TestRecordingFrameDriverDynamicArt.java`
- Modify: `src/test/java/com/openggf/tools/TestRecordingFrameDriverHardwareTiming.java`
- Modify: `src/test/java/com/openggf/TestGameLoopTraceRunPostIteration.java`

**Interfaces:**

- One trace-value-free helper owns held-counter title-card scan or fallback
  `LAG` service, pending in-level title-card dispatch, level-event VBlank-only
  state, retained fixed slots/control lock, and one object VBlank increment.

- [x] Add failing transcript tests for fallback `LAG` and S3K
  `LEVEL_TITLE_CARD` boundary order
  `VINT_SERVICE -> POST_OBJECTS -> PRE_MAIN_LOOP`.
- [x] Prove the helper executes exactly one closure for one stored row,
  advances pending/event/fixed/control/counter state in headless order, and
  never consumes expected trace content.
- [x] Extract the headless implementation into the helper.
- [x] Route live forward and visual rewind suppressed rows through the helper.
- [x] Defer the generic live held-counter overlay update so the provider runs
  exactly once inside the scan; retain normal non-trace overlay behaviour.
- [x] Rerun focused closure, PLC lifecycle, and hardware-timing tests.

## Task 4: Unify forward and rewind applied input

**Files:**

- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherAdvanceOnlyRewind.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRewindPresentation.java`

**Interfaces:**

- `VisualTraceRewindStepper` consumes `TraceReplayRowPolicy` and a shared BK2
  applied-row projection rather than deriving current/previous input itself.

- [x] Extend the existing rewind owners to cover applied-row selection,
  input-only latching, held action edges, held-versus-pressed Start, and
  lifecycle phase ownership.
- [x] Replace rewind's direct current-row publication with the shared policy.
- [x] Preserve rewind raw-row timing latch and exactly-once sidekick hold.
- [x] Rerun rewind reference-closure and hardware timing rewind tests.

## Task 5: Align live comparison expectations and bootstrap diagnostics

**Files:**

- Create: `src/main/java/com/openggf/trace/replay/TraceReplayEngineSnapshot.java`
- Modify: `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplayDriver.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/trace/live/LiveTraceComparatorTest.java`
- Modify: `src/test/java/com/openggf/trace/live/TestLiveTraceComparatorObserver.java`

**Interfaces:**

- Shared read-only frame-zero engine snapshot capture.
- Live comparison selects normalized gameplay diagnostics by game family but
  keeps queue/dynamic-art lookup on the represented raw row.

- [x] Add failing live tests for S1/S2 split-row visual/ring diagnostics and
  S3K camera-only split-row normalization.
- [x] Add failing tests for BK2/trace input misalignment error count, first
  pause, and mismatch ring entry without gameplay mutation.
- [x] Add failing bootstrap tests proving error/warning counts, frame-zero HUD
  entries, separate bootstrap report retention, and no cursor advance.
- [x] Move headless frame-zero capture into the shared production utility and
  use it from `TraceReplayDriver`.
- [x] Implement live normalization, input validation, and bootstrap ingestion.
- [x] Rerun binder, bootstrap, comparator, and invariant guard tests.

## Task 6: Compare dynamic art after production publication

**Files:**

- Modify: `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/test/java/com/openggf/trace/live/TestLiveTraceComparatorObserver.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Modify: `src/test/java/com/openggf/TestGameLoopTraceRunPostIteration.java`

**Interfaces:**

- Comparator queues one pending expected dynamic-art row and optional post-row
  playable-prefix action.
- Launcher retains the iteration-owning comparator plus immutable pre-finish
  diagnostics baseline, then drains after coordinator `finish()`.

- [x] Add a failing normal-row test proving queue comparison occurs after
  service/preparation while dynamic-art comparison cannot pull before outer
  lifecycle finish.
- [x] Add a failing `PLAYABLE_ANIMATION_ONLY` test proving prefix/DPLC
  submission occurs after current-row publication and feeds the next or
  terminal closure.
- [x] Add failing `ADVANCE_ONLY` coverage proving no new delivery serial is
  required and the unchanged snapshot is compared exactly once.
- [x] Add failing segment-rebind coverage proving the old comparator drains its
  own row before the new comparator becomes the post-finish consumer.
- [x] Defer run gap entry, dynamic-art segment close/open, comparator/HUD/camera
  rebind, playback reseek, and special-stage comparison re-arm when
  `runAdvanceTickIfActive()` produces a boundary event inside a production
  iteration. Drain the old row after `finish()` first, then apply the pending
  boundary action in deterministic order; prove no old-generation publication
  can be observed through the new segment.
- [x] Implement freshness/generation/row validation for publishing rows and
  unchanged-snapshot validation for input-only rows.
- [x] Preserve the existing native terminal forwarding iteration and
  special-stage post-production path.
- [x] Rerun dynamic-art, run-chain, PLC lifecycle, and comparison-only guards.

## Task 7: Make incomplete visual sessions abort safely

**Files:**

- Modify: `src/main/java/com/openggf/trace/replay/TraceReplayFixture.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java` only
  if an idempotent hook-detach seam is not already sufficient.
- Modify: `src/main/java/com/openggf/tools/TraceReplayDrive.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`
- Modify: `src/test/java/com/openggf/TestSpecialStageHardwareTimingLifecycle.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/AbstractRunChainTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunHardwareTimingCoordinator.java`
- Create: `src/test/java/com/openggf/TestTraceSessionLauncherFailureCleanup.java`
- Create: `src/test/java/com/openggf/TestTraceSessionLauncherProductionFailureCleanup.java`
- Modify: `src/test/java/com/openggf/TestGameLoopTraceRunPostIteration.java`
- Modify: `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`

**Interfaces:**

- Fixture abort detaches observer, rewind registration, and close hook without
  strict edge/pending verification; immediate context destruction resets the
  teardown-only authority state.
- Successful completion remains verify-and-close.

- [x] Add failing partial-install tests for no port, port only, and fully
  installed observer/rewind/hook states.
- [x] Update every `TraceReplayFixture` implementation with an explicit,
  idempotent abort implementation; production/tool/headless fixtures detach
  their real observer/hook/registration, while test probes record the abort so
  an accidental strict close is detectable.
- [x] Add failing cleanup tests for ordinary bootstrap, special-stage launch,
  run transition, Esc, production-body, and post-finish comparison failures.
- [x] Prove the primary failure retains cleanup failures as suppressed,
  playback/HUD/rewind/config are restored, and no recorded admission survives.
- [x] Add an explicit `runProductionIterationIfActive` `Error` test: cleanup is
  best-effort, cleanup failures are suppressed on the original `Error`, and
  the original `Error` is rethrown after the context has reset.
- [x] In the dedicated production-wrapper test owner, cover body failure after
  coordinator entry, post-finish comparison failure, both failures together,
  `finish()` ordering, RuntimeException containment, and fatal `Error`
  cleanup/rethrow.
- [x] Implement idempotent abort and the post-finish runtime failure state.
- [x] Make incomplete Esc exit abort/destroy/return immediately with no
  gameplay-owned fade; preserve fade only after strict successful close.
- [x] Rerun timing authority, session lifecycle, and master-title launch tests.

## Task 8: Add orchestration contract coverage

**Files:**

- Create: `src/test/java/com/openggf/trace/replay/TestTraceReplayRowPolicy.java`
- Create: `src/test/java/com/openggf/trace/replay/TestTraceSuppressedRowClosure.java`
- Create: `src/test/java/com/openggf/debug/playback/TestPlaybackDebugManagerPreparedInput.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherAdvanceOnlyRewind.java`
- Modify: `src/test/java/com/openggf/TestSpecialStageHardwareTimingLifecycle.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Modify: `src/test/java/com/openggf/tools/TestRecordingFrameDriverHardwareTiming.java`
- Modify: `src/test/java/com/openggf/trace/live/TestLiveTraceComparatorObserver.java`

**Interfaces:**

- Deterministic event transcripts compare headless and visual orchestration
  order without using trace data to drive production work.

- [x] Pin the immutable row projection and shared suppressed-closure transcript,
  including S1/S2 `LAG` and S3K held-title boundary ordering.
- [x] Pin preparation before ROM admission, physical queue comparison before
  finish, and player-DPLC comparison after finish.
- [x] Cover VBlank-starved `ADVANCE_ONLY` carry, playable-prefix comparison,
  named-run comparator rebind/handoff, and bonus-stage timer suppression.
- [x] Exercise the public master-title launch callback plus live fixture abort,
  timing-port detach, config restoration, and return-to-title behavior.
- [x] Keep the existing headless PLC lifecycle, recording-driver hardware
  timing, and run-walker suites in the focused parity batch.
- [x] Run all focused visual/headless parity tests green.

## Task 9: Documentation and verification

**Files:**

- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Create:
  `docs/architecture/validation/trace/2026-08-01-visual-trace-headless-parity-validation.md`

- [x] Document visual/headless parity, PLC/DPLC/Kos timing treatment, and the
  safe incomplete-session exit behaviour.
- [x] Run formatting/policy checks and the complete Maven suite on JDK 21.
- [x] Stage the reviewed design, implementation plan, validation report, and
  every other task artifact required by the documentation policy.
- [x] Fetch and fast-forward the potentially dirty main workspace's checked-out integration
  branch without overwriting user changes.
- [x] Record the exact full-suite result on the updated integration baseline.
- [x] Reconcile upstream, then rerun the full suite and focused parity tests in
  the worktree.
- [x] Update the validation report and obtain an
  independent end-to-end review with no blocking issues.
- [x] Commit with required policy trailers, merge into the main-workspace
  branch without switching it, rerun the full suite against the recorded
  baseline, and prepare the verified clean feature branch for push and
  worktree cleanup.
