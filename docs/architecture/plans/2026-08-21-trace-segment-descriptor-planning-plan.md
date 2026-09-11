# Trace Segment Descriptor Planning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the run ownership boundary by scanning and validating one trace segment at a time into compact descriptors, without changing the existing replay path.

**Architecture:** Add a `TraceRunSegmentDescriptor` that retains only metadata, row/timing/lag summaries, opening state, terminal dynamic-art descriptors, and execution policy. A new descriptor planner loads one eager segment, performs the existing schema and run-ledger validation, extracts the descriptor, and releases the payload before loading the next. Catalog validation uses descriptors; actual run launch continues using the established eager `SegmentPlan` path in this phase.

**Tech Stack:** Java 21, JUnit 5/Jupiter, Maven Surefire, existing v5 trace parsers.

**Spec:** `docs/architecture/designs/2026-08-21-bounded-trace-run-segment-ownership.md`

## Global Constraints

- Trace payloads remain comparison-only and cannot hydrate or choose gameplay state.
- V5 remains the sole live trace contract; do not change fixture formats, schema fields, or hardware-timing authority.
- Preserve the existing eager replay path and every replay result in this phase.
- Descriptor planning must perform the same manifest, schema, row-count, profile, dynamic-art, and hardware-timing validation as eager planning.
- The returned descriptor graph must not retain `TraceData`, `TraceFrame` collections beyond the single opening row, auxiliary event collections, special-stage row payloads, readers, streams, or mapped buffers.
- Planning may eagerly parse one segment at a time; it must release that segment before loading the next.
- Keep hardware-timing schedules and compact raw-frame/lag mappings because their measured footprint is negligible and whole-run consumers need them.
- Build and test with JDK 21 and JUnit Jupiter.
- Use test-first RED/GREEN cycles for every production behavior.

---

### Task 1: Add immutable segment descriptors and sequential planning

**Files:**
- Create: `src/main/java/com/openggf/trace/replay/runs/TraceRunSegmentDescriptor.java`
- Modify: `src/main/java/com/openggf/trace/TraceRunManifest.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Test: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunSegmentDescriptorPlanning.java`

**Interfaces:**
- Produces: `TraceRunSegmentDescriptor`, a payload-independent immutable summary.
- Produces: `TraceRunReplayWalker.planDescriptors(TraceRunManifest, Path)`.
- Produces: `TraceRunManifest.DynamicArtRunValidator.accept(int, TraceData)` and `finish()` for run-wide incremental lifecycle validation.

- [ ] **Step 1: Write failing descriptor-shape and parity tests**

Use `TraceV5RunFixture.writeS3kBonusRun` and assert descriptor count, segment directory, metadata/profile, declared row count, raw-frame mapping, lag bits, hardware schedule, opening frame, terminal dynamic-art ledger, boundary pairing, and execution policy match the eager plans. Catch payload retention structurally:

```java
@Test
void descriptorPlanContainsNoEagerPayloadOwner(@TempDir Path root) throws Exception {
    Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
    TraceRunManifest run = TraceRunManifest.load(runDir.resolve("run_manifest.json"));

    var descriptors = TraceRunReplayWalker.planDescriptors(run, runDir);

    assertEquals(run.segments().size(), descriptors.size());
    assertTrue(Arrays.stream(TraceRunSegmentDescriptor.class.getRecordComponents())
            .noneMatch(component -> component.getType() == TraceData.class));
    assertTrue(descriptors.stream().noneMatch(descriptor ->
            Arrays.stream(descriptor.getClass().getDeclaredFields())
                    .anyMatch(field -> TraceData.class.isAssignableFrom(field.getType()))));
}
```

Add malformed segment, row-count mismatch, profile mismatch, and non-contiguous special-stage row tests; require diagnostics to name the segment index/profile exactly as eager planning does.

- [ ] **Step 2: Run the tests and verify RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.tests.trace.runs.TestTraceRunSegmentDescriptorPlanning" test
```

Expected: compilation failure because the descriptor and `planDescriptors` do not exist.

- [ ] **Step 3: Implement the immutable descriptor**

Create:

```java
public record TraceRunSegmentDescriptor(
        TraceRunManifest.Segment segment,
        Path segmentDirectory,
        TraceMetadata metadata,
        int rowCount,
        TraceFrame openingFrame,
        List<Integer> rawFrames,
        BitSet laggedRows,
        HardwareTimingSchedule hardwareTimingSchedule,
        List<DynamicArtTransfer.Descriptor> terminalDynamicArtLedger,
        TraceRunManifest.Transition entryBoundary,
        TraceRunManifest.Transition exitBoundary,
        TraceRunReplayWalker.SegmentExecutionPolicy executionPolicy) {
}
```

Validate non-negative row count; require `rawFrames.size() == rowCount`; require an opening frame exactly when ordinary level rows are parsed; defensively copy every collection, `Path`, and `BitSet`; override `laggedRows()` to return a clone. No field may reference `TraceData`, `TraceRunSpecialStageRows`, a reader, stream, channel, or mapped buffer.

- [ ] **Step 4: Extract incremental dynamic-art run validation**

Move the state from `validateDynamicArtRun(List<TraceData>)` into:

```java
public final class DynamicArtRunValidator {
    public void accept(int segmentIndex, TraceData trace);
    public void finish();
}
```

The validator owns one `DynamicArtTransfer.LifecycleIdentity`, gap index, and opening ledger. `accept` performs the same capability, advertised-capability, initial-ledger fingerprint, preceding-gap ledger match, segment lifecycle, and adjacent-gap checks as the existing list method. `finish` rejects unconsumed gap transitions. Retain `validateDynamicArtRun(List<TraceData>)` as a compatibility wrapper that calls `accept` in order and then `finish`.

- [ ] **Step 5: Implement sequential descriptor planning**

Validate the manifest and pair boundaries once. For each segment in manifest order:

1. Load its ordinary `TraceData`, or special-stage rows plus metadata-only trace, using the exact eager-plan parser path.
2. Feed the trace to the incremental dynamic-art validator.
3. Validate manifest/profile and row count immediately.
4. Extract raw frames, lag bits from recorded `lag_state`, hardware timing, opening frame, terminal ledger, and execution policy into a descriptor.
5. Drop the eager trace and special-stage row locals before the next loop iteration.

Call `finish()` only after the last segment. Wrap parser failures with the existing `Segment N parser failed for profile '...'` diagnostic.

- [ ] **Step 6: Run focused tests and verify GREEN**

```bash
mvn -Dmse=off "-Dtest=com.openggf.tests.trace.runs.TestTraceRunSegmentDescriptorPlanning,com.openggf.tests.trace.runs.TestTraceRunReplayWalkerControlFlow,com.openggf.tests.trace.TestTraceRunSyntheticFixture" test
```

Require zero failures/errors and no new warnings.

- [ ] **Step 7: Commit Task 1**

```bash
git add src/main/java/com/openggf/trace/replay/runs/TraceRunSegmentDescriptor.java \
  src/main/java/com/openggf/trace/TraceRunManifest.java \
  src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java \
  src/test/java/com/openggf/tests/trace/runs/TestTraceRunSegmentDescriptorPlanning.java
git commit -m "feat(traces): plan compact run segment descriptors"
```

### Task 2: Route catalog validation through descriptor planning

**Files:**
- Modify: `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- Modify: `src/test/java/com/openggf/trace/catalog/TestTraceRunLaunchValidation.java`
- Test: `src/test/java/com/openggf/trace/catalog/TestTraceCatalogDescriptorOwnership.java`

**Interfaces:**
- Consumes: `TraceRunReplayWalker.planDescriptors` and descriptor metadata/row count.
- Produces: descriptor-backed `validateRunLaunch`; preserves eager `prepareRunLaunch` for actual replay.

- [ ] **Step 1: Write failing catalog ownership tests**

Inject separate descriptor and eager planners. Assert `validateRunLaunch` calls the descriptor planner once and never calls the eager planner; assert `prepareRunLaunch` still calls the eager planner once. Preserve every existing diagnostic for movie bounds, segment-zero kind, profile mismatch, parser failure, and row-count mismatch.

- [ ] **Step 2: Run catalog tests and verify RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.trace.catalog.TestTraceCatalogDescriptorOwnership,com.openggf.trace.catalog.TestTraceRunLaunchValidation" test
```

Expected: compilation failure because no descriptor-planner seam exists.

- [ ] **Step 3: Split validation from eager launch preparation**

Add:

```java
@FunctionalInterface
interface RunDescriptorPlanner {
    List<TraceRunSegmentDescriptor> plan(TraceRunManifest manifest, Path runDir)
            throws IOException;
}
```

Make `validateRunLaunch` load the BK2 once, call the descriptor planner once, and validate movie bounds/profile/row count against descriptors. Keep `prepareRunLaunch` and its `PreparedRunLaunch` result unchanged so live/headless/visual/audio replay behavior cannot change in this phase.

- [ ] **Step 4: Run catalog suites and verify GREEN**

```bash
mvn -Dmse=off "-Dtest=com.openggf.trace.catalog.*" test
```

Require zero failures/errors.

- [ ] **Step 5: Commit Task 2**

```bash
git add src/main/java/com/openggf/trace/catalog/TraceCatalog.java \
  src/test/java/com/openggf/trace/catalog/TestTraceRunLaunchValidation.java \
  src/test/java/com/openggf/trace/catalog/TestTraceCatalogDescriptorOwnership.java
git commit -m "perf(traces): validate run catalogs from compact descriptors"
```

### Task 3: Measure the boundary and publish the phase-one result

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunDescriptorPlanningPerformance.java`
- Modify: `docs/architecture/audits/performance/2026-08-21-performance-investigation-report-audit.md`
- Modify: `docs/architecture/designs/2026-08-21-bounded-trace-run-segment-ownership.md`
- Modify: `docs/architecture/plans/2026-08-21-trace-segment-descriptor-planning-plan.md`
- Modify: `CHANGELOG.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: eager and descriptor planners.
- Produces: repeatable retained-heap evidence and a clear decision gate for the later active-cursor migration.

- [x] **Step 1: Write the opt-in measurement/structural test**

Under `-Dopenggf.trace.segmentDescriptorBenchmark=true`, locate the measured 67-segment run through the trace catalog, run eager and descriptor planning in separate forced-GC measurement phases, and print:

```text
TRACE_SEGMENT_DESCRIPTOR_BENCH segments=<n> eager_retained_bytes=<n> descriptor_retained_bytes=<n> reduction_bytes=<n> reduction_percent=<n> descriptor_raw_frames=<n>
```

The ordinary test assertions are host-stable: segment counts and row counts
match, the descriptor record components match the exact approved
payload-independent raw/generic API, and descriptor retained bytes are below
eager retained bytes. Warm both planners across the whole run, release and
force-GC both warmup graphs, then measure each arm against its own forced-GC
baseline. Report exact heap numbers without setting a brittle fixed-byte
threshold.

- [x] **Step 2: Run focused measurement and functional suites**

```bash
mvn -Ptrace-replay -Dmse=off "-Dopenggf.trace.segmentDescriptorBenchmark=true" \
  "-Dtest=com.openggf.tests.trace.runs.TestTraceRunDescriptorPlanningPerformance" test
mvn -Dmse=off \
  "-Dtest=com.openggf.tests.trace.runs.TestTraceRunSegmentDescriptorPlanning,com.openggf.trace.catalog.TestTraceCatalogDescriptorOwnership,com.openggf.trace.catalog.TestTraceCatalogRunDiscovery,com.openggf.trace.catalog.TestTraceRunLaunchValidation,com.openggf.trace.catalog.TraceCatalogHangTest,com.openggf.trace.catalog.TraceCatalogSpecialStageTest,com.openggf.trace.catalog.TraceCatalogTest" test
```

Preserve logs in managed task scratch storage.

The trace-replay profile is required because the measured eager graph exceeds
the shared one-GiB Surefire heap. The wildcard catalog selector did not select
those classes, so the recorded functional run names all six catalog classes
explicitly: 49/49 tests passed.

- [x] **Step 3: Run trace authority and representative replay controls**

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.trace.timing.TestHardwareTimingAuthorityGuard,com.openggf.tests.trace.runs.TestTraceRunReplayWalkerControlFlow,com.openggf.TestTraceSessionLauncherRunBranch,com.openggf.tools.audio.completerun.TestCompleteRunAudioTrace" test
```

The target is zero failures/errors. The recorded run produced 148 passes and
three errors in `TestTraceSessionLauncherRunBranch`; all three reproduced
individually in fresh one-test forks, while that test and
`TraceSessionLauncher` are byte-identical to base `c046e0298`. Under the
project baseline-comparison rule, these are pre-existing control failures, not
descriptor-planning regressions. Because actual replay still uses eager
`SegmentPlan`, the phase changes no replay observation by construction.

- [x] **Step 4: Update design, audit, and release documentation**

Record measured eager/descriptor retained heap and percent reduction. Mark descriptor planning implemented while leaving active-segment streaming explicitly future work. State that catalog validation benefits now but actual replay memory is unchanged in this phase.

- [x] **Step 5: Commit Task 3**

```bash
git add src/test/java/com/openggf/tests/trace/runs/TestTraceRunDescriptorPlanningPerformance.java \
  docs/architecture/audits/performance/2026-08-21-performance-investigation-report-audit.md \
  docs/architecture/designs/2026-08-21-bounded-trace-run-segment-ownership.md \
  docs/architecture/plans/2026-08-21-trace-segment-descriptor-planning-plan.md \
  CHANGELOG.md README.md
git commit -m "perf(traces): validate compact segment planning"
```

### Task 4: Review, integrate, and clean up

**Files:**
- Review every file changed by Tasks 1-3.

**Interfaces:**
- Consumes: phase-one implementation and measurement evidence.
- Produces: pushed `develop`, no new regression, and no leftover task worktree/branch.

- [ ] **Step 1: Run independent task and whole-branch review**

Review specifically for trace-to-gameplay authority expansion, descriptor payload reachability, loss of validation parity, mutable collection escape, and accidental eager replay-path changes. Correct every material finding test-first.

- [ ] **Step 2: Establish the updated integration baseline**

Fetch/reconcile `origin/develop` without switching the main workspace branch. Run the complete JDK 21 three-ROM suite on the updated integration baseline and preserve normalized failing-method identities.

- [ ] **Step 3: Merge and verify**

Merge the feature branch into main `develop`, preserving concurrent upstream work and the required README summary. Repeat focused descriptor/catalog/authority tests and the complete suite; require zero new normalized failing methods attributable to the branch.

- [ ] **Step 4: Push and clean up**

Push only `develop`. Verify the feature commit is merged, inspect and remove the clean task worktree, delete the merged local feature branch, and prune worktree metadata.
