# Visual Trace Complete-Run Playback Implementation Plan

Date: 2026-08-01
Status: Completed
Design: `docs/architecture/designs/trace/2026-08-01-visual-trace-complete-runs-design.md`

## Delivery strategy

Implement tests first in `feature/ai-visual-trace-complete-runs`. The shared
coordinator becomes the handoff policy used by headless and visual adapters;
standalone visual trace behavior remains unchanged. No inter-segment trace
state is written into gameplay. Integration follows the repository's
baseline/merge/push/cleanup workflow.

## Task 1: Catalog, launch validation, and special row policy

**Files:**

- `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- `src/main/java/com/openggf/trace/TraceRunManifest.java`
- `src/main/java/com/openggf/trace/DynamicArtTransfer.java` only for the minimal
  shared snake_case descriptor/request parser seam
- `src/main/java/com/openggf/trace/replay/runs/TraceRunSpecialStageRows.java`
- catalog and profile-row tests

**Tests first:**

- Resolve shared `_movies` first, then normalized `runDir/source_bk2`.
- Reject absolute and `..` local movie traversal.
- Keep a discovered run visible when launch validation finds BK2 bounds,
  profile/parser, row-count, or non-level segment-zero incompatibility.
- Prove S1 lag, S2 lag, and S3K no-lag admissions independently: gameplay,
  synthetic PLC phase, preserved VBlank, and hardware-row admission.
- Select a committed S2 local-BK2 run through the real catalog.
- Parse that run's nested schema-2 initial-ledger requests without losing
  source domain, addresses, VRAM destination, transfer identity, or provenance.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestTraceCatalogRunDiscovery,TestTraceRunLaunchValidation,TestTraceRunSpecialStageRows test
```

## Task 2: Shared structural coordinator and semantic boundaries

**Files:**

- `src/main/java/com/openggf/trace/replay/runs/TraceRunPlaybackCoordinator.java`
- `src/main/java/com/openggf/trace/replay/runs/RunBoundarySignal.java`
- `src/main/java/com/openggf/trace/replay/runs/DestinationAdmissionReceipt.java`
- `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- coordinator/transcript tests

**Tests first:**

- Arm the next target while the current segment becomes active.
- Latch transient bonus/special requests in-window; reject missing/late ones.
- Handle `stage_exit`, `level_advance`, `death_restart`, and transition-less
  level adjacency with distinct semantic signals.
- Match level generation/identity, bonus zone/type, and special-stage index;
  reject correct mode with wrong identity.
- Handle destination load inside source tail without changing the source input
  cursor, then commit the destination when the source range closes.
- Classify lag-only same-level continuation without a load.
- Emit admission receipts with explicit input clock and `rowsConsumed` 0/1;
  reject any other drift.
- Advance transition caps from step ordinal while the BK2 cursor is frozen.
- Require headless and visual adapters to produce identical action transcripts.
- Start transcript equivalence after adapter-owned initial bootstrap and prove
  coordinator actions cannot encode RNG/VBlank/V-int/gameplay-state writes.

**Implementation:**

- Add the engine-agnostic current/gap/ready/tail/complete/failed state machine.
- Extend `BoundaryProbe` with a pinned per-row delegate and semantic load-signal
  latching without putting gameplay owners into the coordinator.
- Keep `RunSegmentAdvancer` as the launcher's compatibility path until Task 5
  migrates its sole production consumer.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestTraceRunPlaybackCoordinator,TestTraceRunPlaybackTranscriptParity,TestTraceRunReplayWalkerControlFlow test
```

## Task 3: Shared boundary and runtime dynamic-art comparisons

**Files:**

- `src/main/java/com/openggf/trace/replay/runs/TraceRunBoundaryComparator.java`
- `src/main/java/com/openggf/trace/replay/runs/TraceRunDynamicArtGapComparator.java`
- `src/test/java/com/openggf/tests/trace/runs/AbstractRunChainTest.java`
- comparison tests

**Tests first:**

- Exact `ReturnAssertionMode` delegation: positional restore; checkpoint plus
  position; next act; S3K giant-ring position; rings; live emeralds only for
  organically reproduced interiors; otherwise recorded emerald progression.
  Every result is an ordinary `FrameComparison` without gameplay writes.
- Headless and visual adapters invoke the same boundary helper.
- Schema-2 runtime gap journal comparison covers edge ordinals, before/after
  outstanding ledger fingerprints, forwarded/completed edges, and destination
  ownership.
- Schema 1 enforces structural close/gap/open without requiring absent payload.
- Special-stage dynamic-art mismatch is attributed to segment/local row and
  honours pause-on-first-error with a frozen shared cursor.

**Implementation:**

- Extract current nested headless assertions/probes into production
  comparison-only helpers.
- Migrate the real `AbstractRunChainTest` driver—not a simulated adapter—to
  `TraceRunPlaybackCoordinator` transitions, admission receipts,
  `TraceRunSpecialStageRows` policy, boundary helper, runtime gap helper, and
  terminal-tail policy. Keep adapter-owned legacy bootstrap/alignment outside
  the coordinator transcript and retain no visual trace-state hydration seam.
- Run transcript parity against the real headless adapter and the visual
  adapter harness, not only pure coordinator fakes.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestTraceRunBoundaryComparator,TestTraceRunDynamicArtGapComparator,TestTraceRunHeadlessCoordinatorAdapter,TestTraceRunPlaybackTranscriptParity,TestTraceRunReplayWalkerControlFlow test
```

## Task 4: Guaranteed GameLoop and level-load seams

**Files:**

- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/LevelIterationAdmissionController.java`
- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java`
- seam tests

**Tests first:**

- Bonus title-card release applies immediate destination actions after deferred
  setup and before `syncPlaybackInputBridge`/fallthrough production.
- Both level-load paths report generation, typed identity, and
  `DEATH_RESTART`/`LEVEL_ADVANCE`/ordinary cause before first destination tick.
- A matching load during source tail is remembered without seeking; a wrong
  reload cannot consume the pending target.
- An outer step `finally` observes every mode/early return exactly once and
  increments an independent step ordinal.
- Escape delegates to an active run from every phase.

**Implementation:**

- Add narrow callbacks at existing production seams; keep manifests and trace
  values out of `GameLoop`/`LevelManager`.
- Make pending playback rebind target-aware and cancellable.
- Preserve source-compatible overloads for standalone/headless consumers.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestGameLoop,TestGameLoopTraceRunAdmission,TestGameLoopTraceRunPostIteration,TestPlaybackTargetAwareLevelLoad test
```

## Task 5: Atomic launcher integration and row-zero ownership

**Files:**

- `src/main/java/com/openggf/TraceSessionLauncher.java`
- launcher run/cleanup tests

**Tests first:**

- Install one stable run observer and pin the source delegate through its
  prepared row publication.
- Commit source close, timing handoff, dynamic-art open, comparator creation,
  playback seek, and HUD/camera rebind in the specified order.
- Prove source comparator never receives destination row zero.
- Exercise level, already-loaded level, bonus entry, special entry, and return
  receipts at row 0; exercise the documented produced-row fallback at row 1.
- Attribute the first destination mismatch to the destination.
- Failure injection at source close, timing handoff, dynamic-art open,
  comparator creation, and commit rollback leaves no partial owner.

**Implementation:**

- Replace `RunSegmentAdvancer` with coordinator action translation.
- Keep immediate-admission and post-publication actions as separate sealed
  types; apply batches atomically/idempotently.
- Remove `RunSegmentAdvancer` only after all launcher consumers migrate and its
  focused regression coverage has equivalent coordinator tests.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestTraceSessionLauncherRunBranch,TestTraceSessionLauncherRunAdmission,TestTraceSessionLauncherProductionFailureCleanup test
```

## Task 6: Special-stage local runner

**Files:**

- run-specific special input/lifecycle methods in `TraceSessionLauncher`
- special-stage GameLoop tests

**Tests first:**

- S1/S2/S3K input uses `offset + localRow` and the preceding physical BK2 row
  for press edges.
- Apply each profile admission policy exactly: S1 versus S2 lag/VBlank and S3K
  every-row gameplay.
- Admit hardware timing and advertised DPLC comparison on the same local row.
- Clear overrides in `finally` on success, row load failure, production failure,
  mode exit, Escape, and repeated abort.
- Row exhaustion before mode exit enters native transition steps with no stale
  input; mode exit before row exhaustion fails visibly.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestSpecialStageVisualTraceSession,TestTraceSessionLauncherRunSpecialRows,TestSpecialStageHardwareTimingLifecycle test
```

## Task 7: Terminal tail and persistent failures

**Files:**

- coordinator tail support
- launcher/picker status presentation
- terminal tests

**Tests first:**

- End ordinary playback/observer ownership before tail playback.
- Apply each physical tail row exactly once through LEVEL, title/results, and
  other non-level modes; clear override in `finally`.
- Prove a final LEVEL tail has no double input owner.
- Assert the expected final mode immediately at movie exhaustion; wrong mode
  fails on that step.
- Hold `TRACE FAILED` identity/cursor/step diagnostics after gameplay teardown
  until picker acknowledgement.
- Escape and repeated abort are clean from tail and failed states.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestVisualTraceRunTerminalTail,TestTraceSessionLauncherFailureCleanup,TestModeTracePickerRunFailureStatus test
```

## Task 8: PLC, DPLC, and Kosinski transfer proof

**Tests first:**

- S1 PLC readiness, lag closure, source final post-finish publication, gap, next
  schedule, and destination row zero.
- S2 DPLC readiness across skipped special rows, forwarded/completed transfer
  edges, and destination initial ledger fingerprint/generation.
- S3K module Kosinski plus schema-2 direct Kosinski readiness across a real
  transition gap, keeping module parents distinct from their direct children
  and distinguishing submitted/busy from prepared queue state.
- Gap work remains production-submitted; timing cannot create work or release a
  mismatched kind/ordinal/fingerprint/service boundary.
- Terminal post-finish forwarding closes before tail input begins.
- End-frame queue membership/fingerprints remain comparison-only; schema-1
  reserved `service_observations` stays empty and is not misread as no service.

**Verification:**

```text
mvn -q -Dmse=off -Dtest=TestTraceRunHardwareTimingCoordinator,TestTraceRunS1PlcTransfer,TestTraceRunS2DplcTransfer,TestTraceRunS3kKosinskiTransfer,TestHardwareTimingAuthorityGuard,TestS1S2PlcComparisonOnlyGuard test
```

## Task 9: Real launcher/GameLoop end-to-end traversal

**Tests first:**

- Engine-backed committed S1 round trip reaches its return segment/tail when
  its BK2 is available, otherwise a fixture-equivalent real launcher test plus
  an explicit missing-movie catalog diagnostic.
- Engine-backed committed local-BK2 S2 short round trip traverses special entry
  and return with real early-return hooks.
- Engine-backed representative S3K run traverses a bonus or special entry and
  return; any organic RNG/clock mismatch is a named diagnostic, never hydrated.
- Long committed manifest traverses shared coordinator policy and catches stale
  bindings, including `level_advance` and `death_restart`.
- Standalone visual level/special parity suite stays green.

**Verification:**

```text
mvn -q -Dmse=off -Dsonic1.rom.path=<s1> -Dsonic2.rom.path=<s2> -Ds3k.rom.path=<s3k> -Dtest=TestVisualTraceRunRoundTrips,TestTraceSessionLauncherRunBranch,TestSpecialStageVisualTraceSession test
```

## Task 10: Documentation, regression review, and integration

**Files:**

- `CHANGELOG.md`
- `README.md`
- `docs/architecture/validation/trace/2026-08-01-visual-trace-complete-runs-validation.md`
- trace frontier/status docs only if a trace result/frontier changes

**Checklist:**

- [x] Every design acceptance criterion has a focused test/result.
- [x] No inter-segment trace-derived gameplay write was introduced.
- [x] Independent implementation review reports no blocking issue.
- [x] Full JDK 21 suite is compared with a freshly updated `develop` baseline.
- [x] Required docs and policy trailers are committed.
- [x] Work is merged into the main-workspace `develop` without switching it,
      pushed, and the clean worktree/local feature branch are removed.
