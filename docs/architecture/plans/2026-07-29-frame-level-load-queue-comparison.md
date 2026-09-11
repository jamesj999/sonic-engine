# Frame-Level Load-Queue Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make S1, S2, and S3K load-queue state an ordinary zero-tolerance per-frame trace comparison so queue timing becomes the first visible divergence.

**Architecture:** Game-owned queue services project immutable normalized snapshots through a read-only provider. Native recorders emit the equivalent ROM snapshots as optional capability-gated auxiliary events, and `TraceBinder` merges exact queue fields into the existing frame comparison without exposing trace data to gameplay.

**Tech Stack:** Java 21, JUnit 5, Jackson, C# native BizHawk recorder tests, Maven, .NET.

## Global Constraints

- Trace queue data is comparison-only and must never mutate gameplay or queue state.
- Existing fixtures without `load_queue_state_per_frame` remain compatible.
- Declared capability requires exactly the complete queue set on every stored
  physics row, including prefix and lag rows.
- Queue fields have zero tolerance and use stable game-independent comparison code.
- S1/S2 native service cadence is unchanged in every load-time simulation mode.
- Runtime asset identity and work counts come from the user-supplied ROM.

---

### Task 0: Isolated workspace and integration baseline

**Files:**
- Inspect only: main-workspace status and all existing uncommitted/untracked files.
- Create: repository-convention worktree on `feature/ai-frame-load-queue-comparison`
  from the current main-workspace `develop`.
- Record: exact baseline commands and failures in the Task 6 verification notes
  within this plan before final delivery.

**Interfaces:**
- Produces: isolated development worktree, verified JDK/ROM inputs, and an exact
  main-workspace baseline against which development and post-merge results are compared.

- [ ] Inspect `git status --short --branch`, preserve every unrelated main-workspace
  change, fetch `origin`, and fast-forward pull the checked-out `develop` branch without
  switching it or overwriting local work.
- [ ] Create the feature worktree and branch from the updated main-workspace HEAD using
  the repository worktree convention; transfer only these reviewed design/plan files
  into it and leave unrelated user changes in the main workspace.
- [ ] Verify `mvn -v` reports JDK 21.
- [ ] Discover `.gen` files, verify the three required SHA-1 values, and record the exact
  `sonic1.rom.path`, `sonic2.rom.path`, and `s3k.rom.path` inputs.
- [ ] Run the full Java suite on the updated main-workspace baseline with one Surefire
  fork and all three ROM properties; record test counts, exact failing classes/methods,
  first error text, exit status, and whether Maven exits without a resident fork.
- [ ] Run the full native recorder suite on the baseline with absolute
  `BIZHAWK_HOME`, `S1_ROM_PATH`, `S2_ROM_PATH`, and `S3K_ROM_PATH`; record pass/fail/skip
  counts and exact failures.

### Task 1: Normalized queue diagnostic model and native projections

**Files:**
- Create: `src/main/java/com/openggf/game/resources/QueueDiagnosticSnapshot.java`
- Create: `src/main/java/com/openggf/game/resources/QueueServiceObservation.java`
- Create: `src/main/java/com/openggf/game/resources/QueueDiagnosticsProvider.java`
- Modify: `src/main/java/com/openggf/game/resources/PlcLifecycleService.java`
- Modify: `src/main/java/com/openggf/level/resources/NemesisPlcServiceQueue.java`
- Modify: `src/main/java/com/openggf/game/sonic1/resources/Sonic1PlcService.java`
- Modify: `src/main/java/com/openggf/game/sonic2/resources/Sonic2PlcService.java`
- Modify: `src/main/java/com/openggf/game/resources/PlcFrameLifecycleCoordinator.java`
- Test: `src/test/java/com/openggf/game/resources/TestQueueDiagnosticSnapshot.java`
- Test: `src/test/java/com/openggf/game/sonic1/resources/TestSonic1PlcQueueDiagnostics.java`
- Test: `src/test/java/com/openggf/game/sonic2/resources/TestSonic2PlcQueueDiagnostics.java`

**Interfaces:**
- Produces: `QueueDiagnosticSnapshot`, `QueueServiceObservation`,
  `QueueDiagnosticsProvider.captureQueueDiagnostics()`.
- Consumes: existing immutable `NemesisPlcQueueSnapshot`.

- [ ] Write tests proving the four literal cross-language golden fingerprint
  vectors, defensive list copies, canonical idle state, S1/S2 active/waiting
  normalization, ROM-derived totals for immutable waiting descriptors, prepared
  S1/S2 total sentinel `-1`, and rejection of non-empty version-1 service
  observations.
- [ ] Run `mvn -Dmse=off -Dtest=TestQueueDiagnosticSnapshot,TestSonic1PlcQueueDiagnostics,TestSonic2PlcQueueDiagnostics test` and confirm compilation/test failure before implementation.
- [ ] Implement the immutable model, SHA-256 versioned descriptor encoding,
  provider interface, and kernel projection without guessing end-frame-invisible
  service phases or budgets. S1/S2 services and
  `PlcFrameLifecycleCoordinator` must not latch diagnostic phase/budget state.
- [ ] Run the focused tests and existing `TestSonic1PlcService,TestSonic2PlcService,TestPlcFrameLifecycleCoordinator`.
- [ ] Commit only Task 1 files with project trailers.

### Task 2: S3K direct and module queue projections

**Files:**
- Modify: `src/main/java/com/openggf/game/RuntimeArtCoordinator.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosDecompressionQueue.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosModuleQueue.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/resources/S3kRuntimeArtCoordinator.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Test: `src/test/java/com/openggf/game/sonic3k/resources/TestS3kQueueDiagnostics.java`
- Test: `src/test/java/com/openggf/game/session/TestGameplayModeQueueDiagnostics.java`

**Interfaces:**
- Consumes: `QueueDiagnosticSnapshot` and `QueueDiagnosticsProvider` from Task 1.
- Produces: `GameplayModeContext.captureQueueDiagnostics()` composing PLC and runtime-art providers without concrete casts.

- [ ] Write failing tests for direct physical membership excluding
  ready-but-unclaimed jobs, module parent/child non-duplication, module remaining
  count, prepared module source/destination/total masking to `-1`, normalized
  byte-address destinations and totals in immutable waiting fingerprints,
  waiting-parent order, empty reserved observations, stable order, and session
  aggregation. Cover the cross-boundary case where KosM submits a direct child
  at `POST_OBJECTS`: direct remains unprepared until `PRE_MAIN_LOOP`. Prove
  KosM prepared state uses a nonzero low-seven-bit module count, while bit 7
  only reports a child in progress.
- [ ] Run `mvn -Dmse=off -Dtest=TestS3kQueueDiagnostics,TestGameplayModeQueueDiagnostics test` and confirm failure.
- [ ] Add read-only projection methods without changing submission, preparation, retirement, or rewind behavior.
- [ ] Run focused tests plus `TestS3kKosDecompressionQueue,TestS3kKosModuleQueue,TestS3kKosTimingRewindIntegration`.
- [ ] Commit only Task 2 files with project trailers.

### Task 3: Trace schema, parser, metadata, and comparator

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceEvent.java`
- Modify: `src/main/java/com/openggf/trace/TraceData.java`
- Modify: `src/main/java/com/openggf/trace/TraceMetadata.java`
- Modify: `src/main/java/com/openggf/trace/TraceBinder.java`
- Modify: `src/main/java/com/openggf/trace/TraceEventFormatter.java`
- Modify: `src/main/java/com/openggf/trace/DivergenceReport.java`
- Test: `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`
- Test: `src/test/java/com/openggf/trace/TestLoadQueueComparison.java`
- Test: `src/test/java/com/openggf/tests/trace/TestDivergenceReport.java`

**Interfaces:**
- Consumes: `QueueDiagnosticSnapshot`.
- Produces: `TraceEvent.LoadQueueState`,
  `TraceData.validateAdvertisedLoadQueueStates(...)`,
  `TraceData.loadQueueStatesForFrame(int)`, and
  `TraceBinder.compareLoadQueues(int, List<LoadQueueState>, List<QueueDiagnosticSnapshot>)`.

- [ ] Write failing parser tests for valid events, malformed fields, duplicate kinds,
  per-game expected-kind completeness, events outside the physics-row domain,
  first/last stored frames, prefix and lag rows, segment-local
  numbering, legacy omission, and rejection of non-empty version-1
  `service_observations` before replay. Missing queue events on non-compared
  prefix/lag rows must fail completeness validation.
- [ ] Write failing comparator tests for exact match and each normalized mismatch, including missing/extra queues.
- [ ] Run the three focused test classes and confirm failure.
- [ ] Implement strict event parsing, metadata capability access, indexed frame lookup, comparison field merging, and compact context formatting.
- [ ] Pin S1/S2 prepared-head normalization to source/destination/total `-1` and
  compare busy, prepared, remaining work, immutable waiting fingerprints, and
  empty reserved observations; compare the three masked identity fields exactly as
  sentinel `-1`.
- [ ] Pin prepared S3K KosM source/destination/total to exact `-1` while
  comparing busy, prepared, remaining modules, waiting-parent fingerprints,
  and empty reserved observations.
- [ ] Run focused tests and `TestHardwareTimingAuthorityGuard,TestS1S2PlcComparisonOnlyGuard`.
- [ ] Commit only Task 3 files with project trailers.

### Task 4: Replay-loop integration and isolation guards

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/trace/TestS1S2PlcComparisonOnlyGuard.java`
- Modify: `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`
- Test: `src/test/java/com/openggf/tests/trace/TestLoadQueueReplayComparison.java`

**Interfaces:**
- Consumes: `GameplayModeContext.captureQueueDiagnostics()` and `TraceBinder.compareLoadQueues`.
- Produces: queue comparisons in both general and S3K replay loops at the ordinary comparison frame.

- [ ] Write failing replay tests proving comparison occurs at
  `END_OF_LOGICAL_FRAME` after S1/S2 preparation and S3K POST_OBJECTS retirement,
  preserves empty reserved observations, skips legacy fixtures, handles lag rows
  through the existing phase predicate, and reports the first queue mismatch.
- [ ] Extend source guards to reject trace imports in queue owners and calls to mutation verbs from queue comparison paths.
- [ ] Run the focused replay and guard tests and confirm failure.
- [ ] Add one shared replay helper invoked from both loop variants; do not duplicate game-specific comparison logic.
- [ ] Run the focused tests plus representative S1, S2, and S3K replay tests with discovered ROM paths.
- [ ] Commit only Task 4 files and required trace-frontier documentation with project trailers.

### Task 5: Native recorder emission and schema publication

**Files:**
- Create: `tools/bizhawk-headless/src/Recording/LoadQueueStateEvent.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S1AuxEventEngine.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S2AuxEventEngine.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KAuxEventEngine.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S1TraceMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S2TraceMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KTraceMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S1CompleteRunMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KCompleteRunMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S1Ram.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S2Ram.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KRam.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`
- Test: `tools/bizhawk-headless/tests/LoadQueueStateEventTests.cs`
- Test: `tools/bizhawk-headless/tests/S1AuxEventEngineTests.cs`
- Test: `tools/bizhawk-headless/tests/S2AuxEventEngineTests.cs`
- Test: `tools/bizhawk-headless/tests/S3KAuxEventEngineTests.cs`
- Test: `tools/bizhawk-headless/tests/S1CompleteRunMetadataWriterTests.cs`
- Test: `tools/bizhawk-headless/tests/S3KCompleteRunProfileTests.cs`
- Test: `tools/bizhawk-headless/tests/S1RunCaptureRunnerTests.cs`
- Test: `tools/bizhawk-headless/tests/S2RunCaptureRunnerTests.cs`
- Test: `tools/bizhawk-headless/tests/S3KCompleteRunSegmenterTests.cs`

**Interfaces:**
- Produces: canonical `load_queue_state` JSON matching `TraceEvent.LoadQueueState`.
- Consumes: stable frame-boundary RAM reads and reviewed per-game queue addresses.

- [ ] Use C# 7.x syntax only. Hand-add `LoadQueueStateEvent.cs` to both non-SDK
  `.csproj` files, hand-add `LoadQueueStateEventTests.cs` to the tests project, and
  register its class in `TestMain.BuildRegistry()` so the custom runner executes it.
- [ ] Write C# tests for canonical JSON, the same four literal fingerprint golden
  vectors used by Java tests, S1/S2 complete PLC snapshots, queue-local S3K
  direct/module empty reserved observations and metadata capability
  declaration. `LoadQueueStateEvent` must reject rather than serialize a
  non-empty version-1 observation array.
- [ ] Add S1/S2 same-frame submit-and-arm and mid-job-start recorder cases proving
  prepared source/destination/total remain `-1` and no identity latch or cursor
  reconstruction is attempted.
- [ ] Add an already-processing S3K KosM case proving mutable active
  source/destination/total remain `-1`, remaining and waiting-parent
  fingerprints are preserved, and no cursor identity reconstruction occurs.
- [ ] Cover standard, S1 complete-run, S2 delegated run, and independently formatted
  S3K complete-run metadata. Preserve intentional S3K special-stage capability absence
  where no queue comparison domain exists.
- [ ] Run `tools/bizhawk-headless/test.sh --filter LoadQueueState --jobs 1` and the
  focused metadata/run filters; confirm the new tests fail through the Mono/xbuild
  custom runner before implementation.
- [ ] Implement shared formatting/fingerprinting and per-game RAM projections without enabling execute hooks.
- [ ] Prove each enabled standard/run recorder emits exactly the complete
  per-game queue-kind set for every emitted physics row, including prefix and
  lag rows, and that no recorder/coordinator retains a stale phase/budget latch.
- [ ] Run the full suite with absolute
  `BIZHAWK_HOME`, `S1_ROM_PATH`, `S2_ROM_PATH`, and `S3K_ROM_PATH` via
  `tools/bizhawk-headless/test.sh`; record pass/fail/skip counts and verify legacy
  fixture-byte tests remain unchanged unless their synthetic metadata explicitly opts in.
- [ ] Update recorder behavior documentation under `tools/bizhawk-headless/docs/`.
- [ ] Commit only Task 5 files with project trailers.

### Task 6: End-to-end verification and documentation

**Files:**
- Modify: `CONFIGURATION.md`
- Modify: `docs/architecture/designs/2026-07-29-profiled-load-time-simulation.md`
- Modify: `docs/status/trace-frontier-log.md` only if a trace frontier or replay outcome changes.
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `.agents/skills/plc-system/SKILL.md`
- Modify: `.claude/skills/plc-system/SKILL.md`
- Modify: `.agents/skills/trace-replay-bug-fixing/SKILL.md`
- Modify: `.claude/skills/trace-replay-bug-fixing/SKILL.md`
- Modify: `AGENTS.md` and `CLAUDE.md` together only if the diagnostic rule belongs in general project guidance.

**Interfaces:**
- Consumes: completed Tasks 1-5.
- Produces: clarified configuration semantics and final verification evidence.

- [ ] Document that native S1/S2 PLC cadence is always active and `loadTimeSimulation` controls only profile-gated readiness work.
- [ ] Amend the profiled timing design's S1/S2 follow-up to specify comparison diagnostics rather than an added admission gate.
- [ ] Add the required `README.md` release/change-log summary and include it in the
  feature branch before merging into `develop`.
- [ ] Update both mirrored copies of the PLC and trace-replay skills to require checking the earliest frame-level queue mismatch before editing a downstream consumer; verify each pair is byte-identical.
- [ ] If general guidance changes, stage byte-identical `AGENTS.md` and `CLAUDE.md` changes together; otherwise leave both untouched.
- [ ] Run all focused Java queue/parser/comparator/guard tests.
- [ ] Run the full C# recorder suite in the feature worktree with absolute harness/ROM
  paths and compare exact pass/fail/skip results with Task 0.
- [ ] Run `mvn -Dmse=off -Dsurefire.forkCount=1 test` on JDK 21 with all three
  discovered ROM properties in the feature worktree and compare every failure/error
  with Task 0; no new or worsened result may proceed.
- [ ] Review `git diff --check`, source guards, generated reports, and untracked files; stage no user-authored unrelated changes.
- [ ] Commit Task 6 documentation with project trailers and verify the feature
  worktree has no unknown, user-authored, or unmerged change.
- [ ] Fetch and fast-forward pull the main-workspace `develop` branch again. Before
  merging, rerun and record the full Java and native-recorder suites on this refreshed,
  unmerged integration baseline.
- [ ] Reconcile the feature worktree with the refreshed integration baseline without
  switching the main workspace, then run the same full suites and focused queue tests
  there. Compare against the refreshed pre-merge baseline and resolve every new or
  worsened result attributable to the feature.
- [ ] Merge the verified feature branch into the main-workspace checked-out `develop`
  branch and reconcile conflicts carefully.
- [ ] Run the full Java and native-recorder suites on merged `develop`; compare exact
  failures with the refreshed pre-merge baseline (and report movement from Task 0);
  confirm no baseline pass regressed or baseline failure worsened due to this feature.
- [ ] Push only `develop`. Verify the feature worktree is fully merged and contains no
  preservable work, remove it, delete the fully merged local feature branch, and prune
  stale worktree metadata.
