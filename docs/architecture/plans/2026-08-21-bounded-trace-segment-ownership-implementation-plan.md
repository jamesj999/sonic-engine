# Active Trace Segment Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace whole-run eager trace-payload retention with descriptor-only planning plus at most one closeable eager segment payload, reducing the warmed 67-segment retained graph by at least 75% without changing replay semantics or broadening trace authority.

**Architecture:** `TraceRunSegmentDescriptor` remains the whole-run representation, retains its existing `executionPolicy`, and gains only the already-materialised `levelLoopRowCount` coordinator scalar. `TraceRunReplayWalker.openActiveSegment(...)` creates a guarded `ActiveSegmentPayload` containing the existing eager ordinary payload or existing composite special-stage payload; production, headless, visual, and complete-audio drivers detach all aliases and close it before another segment opens. Comparator, bootstrap, row-policy, binder, and special-stage APIs remain unchanged.

**Tech Stack:** Java 21, JUnit 5/Jupiter, Maven Surefire, gzip/plain v5 trace fixtures, forced-GC retained-heap sampling.

**Spec:** `docs/architecture/designs/2026-08-21-bounded-trace-run-segment-ownership.md`

## Global Constraints

- Build and test with Maven running on JDK 21; verify with `mvn -v`.
- Keep v5 as the only trace contract. Do not change trace schemas, recorder formats, parser ordering, hardware-timing authority, RNG, execution-phase derivation, or gameplay behavior.
- Preserve `LiveTraceComparator`, `TraceReplaySessionBootstrap`, `TraceReplayBootstrap`, `TraceReplayRowPolicy`, `TraceBinder`, `LoadQueueComparisonProjection`, `TraceStructuralRowComparator`, and `TraceRunSpecialStageRowDriver` eager semantics.
- The descriptor retains `executionPolicy` and may add only `levelLoopRowCount`, the exact additional coordinator scalar already computed by the eager path.
- `ActiveSegmentPayload.trace()`, `specialStageRows()`, and `TraceRunReplayWalker.openActiveSegment(...)` are available only to `TraceSessionLauncher`, `AbstractRunChainTest`, `VisualRunReplayHarness`, and the exact non-relaying test FQCNs enumerated in Task 7; no package or naming-pattern allowance is valid.
- At every observed instant there are zero or one active payloads. Close and detach on normal boundary, terminal tail, launch failure, comparison failure, production failure, abort, user exit, and repeated teardown.
- Retained-heap gates are descriptor graph `<= 16,777,216` bytes, installed live ownership graph `<= 268,435,456` bytes, and reduction `>= 75%` from the warmed eager baseline `1,087,200,800` bytes.
- Store durable logs under `$AGENT_SCRATCH_ROOT/tasks/trace-active-segment-cursor-20260822T002616Z-260779-06974cb7/`.
- Use test-first RED/GREEN cycles and commit each independently reviewable task with all required repository trailers.

---

### Task 1: Extend descriptors with the exact coordinator scalars

**Files:**
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunSegmentDescriptor.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunPlaybackCoordinator.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunSegmentDescriptorPlanning.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunReplayWalkerControlFlow.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunPlaybackCoordinator.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunDescriptorPlanningPerformance.java`

**Interfaces:**
- Produces: `TraceRunSegmentDescriptor.levelLoopRowCount()` with range `0..rowCount`.
- Preserves: `TraceRunSegmentDescriptor.executionPolicy()` exactly as phase one computes it.
- Produces: `TraceRunPlaybackCoordinator.fromDescriptors(TraceRunManifest, TracePlaybackProfile, int, List<TraceRunSegmentDescriptor>)` while the eager constructor remains during migration; generic-list erasure forbids overloading the constructor.

- [x] **Step 1: Add failing scalar-parity and coordinator tests**

For synthetic level, presentation-bridge, and special-stage descriptors, assert constructor range checks and that the coordinator consumes descriptor values without loading a payload. On a synthetic run, compare each descriptor with the current eager reference:

```java
List<SegmentPlan> eager = TraceRunReplayWalker.plan(run, runDir);
List<TraceRunSegmentDescriptor> compact =
        TraceRunReplayWalker.planDescriptors(run, runDir);
for (int i = 0; i < eager.size(); i++) {
    assertEquals(eager.get(i).executionPolicy(),
            compact.get(i).executionPolicy());
    assertEquals(TraceRunReplayWalker.levelLoopRowCount(eager.get(i).trace()),
            compact.get(i).levelLoopRowCount());
}
```

Add `levelLoopRowCount:int` to the exact descriptor component whitelist; approve no other component.

- [x] **Step 2: Run focused tests and verify RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.tests.trace.runs.TestTraceRunSegmentDescriptorPlanning,com.openggf.tests.trace.runs.TestTraceRunReplayWalkerControlFlow,com.openggf.tests.trace.runs.TestTraceRunPlaybackCoordinator" test
```

Expected: compilation failures for the missing descriptor component and descriptor-backed coordinator factory.

- [x] **Step 3: Implement the minimal descriptor extension**

Add `int levelLoopRowCount` immediately before `executionPolicy` and validate:

```java
if (levelLoopRowCount < 0 || levelLoopRowCount > rowCount) {
    throw new IllegalArgumentException(
            "levelLoopRowCount must be within the segment row range");
}
```

In `planDescriptors`, compute it from the already-loaded validation payload before releasing that local. For metadata-only special-stage payloads the existing result is zero. Have `fromDescriptors` copy only:

```java
this.executionPolicies = descriptors.stream()
        .map(TraceRunSegmentDescriptor::executionPolicy).toList();
this.levelLoopRows = descriptors.stream()
        .map(TraceRunSegmentDescriptor::levelLoopRowCount).toList();
```

Do not add a public descriptor helper for either scalar.
Keep the eager `List<SegmentPlan>` constructor compiling until Task 5 migrates its last caller; share initialization through a private constructor that takes non-generic scalar lists or another erasure-safe internal shape.

- [x] **Step 4: Run focused tests and descriptor benchmark GREEN**

Run Step 2, then:

```bash
mvn -Ptrace-replay -Dmse=off \
  -Dopenggf.trace.segmentDescriptorBenchmark=true \
  "-Dtest=com.openggf.tests.trace.runs.TestTraceRunDescriptorPlanningPerformance" test
```

Require zero failures/errors and descriptor retained bytes `<= 16,777,216`.

- [x] **Step 5: Commit Task 1**

Stage only the listed files and commit as `refactor(traces): retain coordinator scalars in descriptors` with policy trailers.

---

### Task 2: Add the guarded active-payload lease and factory

**Files:**
- Create: `src/main/java/com/openggf/trace/replay/runs/ActiveSegmentPayload.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/main/java/com/openggf/trace/TraceFiles.java`
- Create: `src/test/java/com/openggf/trace/replay/runs/TestActiveSegmentPayload.java`
- Create: `src/test/java/com/openggf/trace/TestTraceReaderLifecycle.java`

**Interfaces:**
- Produces: `public static ActiveSegmentPayload TraceRunReplayWalker.openActiveSegment(TraceRunSegmentDescriptor descriptor, int segmentIndex) throws IOException`.
- Produces: public final lease methods `descriptor()`, `trace()`, `specialStageRows()`, `isClosed()`, and idempotent `close()`; constructor/mutable fields remain non-public.
- Produces: package-private `TraceFiles.ReaderLifecycleEvent { OPENED, CLOSED }`, `ReaderLifecycleObserver.onEvent(ReaderLifecycleEvent, Path)`, and `AutoCloseable observeReadersForTest(ReaderLifecycleObserver)`; observation is thread-confined and defaults to no-op.

- [x] **Step 1: Write failing lease tests**

Cover ordinary, special-stage composite, construction failure, post-close access, and repeated close:

```java
ActiveSegmentPayload payload =
        TraceRunReplayWalker.openActiveSegment(descriptor, 0);
assertSame(descriptor, payload.descriptor());
assertNotNull(payload.trace());
assertNull(payload.specialStageRows());
payload.close();
assertTrue(payload.isClosed());
assertThrows(IllegalStateException.class, payload::trace);
assertThrows(IllegalStateException.class, payload::specialStageRows);
assertThrows(IllegalStateException.class, payload::descriptor);
payload.close();
```

For special stages require non-null metadata-only `TraceData` and game-owned rows with matching row count, metadata, timing schedule, optional S2 pass-binder shape, and spill-normalised rows equal to the eager reference.
Treat S2 pass-binder presence as parity with the eager reference rather than universally non-empty; add a separate positive fixture containing `run_objects_end` that proves the binder and spill-normalised rows are retained when present.

- [x] **Step 2: Write failing reader-balance tests**

Install `TraceFiles.ReaderLifecycleObserver` around actual plain/gzip loads. Count every successful open and close, including a special-stage composite whose second parser fails. Require `opened == closed`; restore the thread-confined observer in `finally`.

- [x] **Step 3: Run suites and verify RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.trace.replay.runs.TestActiveSegmentPayload,com.openggf.trace.TestTraceReaderLifecycle" test
```

Expected: compilation failure because the lease, facade, and observer do not exist.

- [x] **Step 4: Implement the lease and failure-atomic facade**

Use nullable mutable payload fields only so close breaks reachability:

```java
public final class ActiveSegmentPayload implements AutoCloseable {
    private final TraceRunSegmentDescriptor descriptor;
    private TraceData trace;
    private TraceRunSpecialStageRows specialStageRows;
    private boolean closed;

    ActiveSegmentPayload(TraceRunSegmentDescriptor descriptor,
            TraceData trace, TraceRunSpecialStageRows specialStageRows) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.specialStageRows = specialStageRows;
        boolean special = "special_stage".equals(
                descriptor.segment().kind());
        if (special != (specialStageRows != null)) {
            throw new IllegalArgumentException(
                    "special-stage payload shape does not match descriptor");
        }
    }

    public TraceRunSegmentDescriptor descriptor() {
        requireOpen(); return descriptor;
    }
    public TraceData trace() { requireOpen(); return trace; }
    public TraceRunSpecialStageRows specialStageRows() {
        requireOpen(); return specialStageRows;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        specialStageRows = null;
        trace = null;
    }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("segment payload is closed");
    }
}
```

Move current `loadSegmentPayload` logic behind `openActiveSegment`; construct the lease only after both special-stage components succeed. Retain exact parsing order and diagnostics. Wrap readers from `TraceFiles.openReader` so the observer sees one `OPENED` and one `CLOSED`; production no-observer state retains no observer/path.

- [x] **Step 5: Run Task 2 suites GREEN**

Run Step 3; require balanced reader events, correct ordinary/composite shapes, post-close guards, and idempotent close.

- [x] **Step 6: Commit Task 2**

Commit as `feat(traces): lease one active run segment payload`, staging Task 2 files and `CHANGELOG.md` if required by the hook.

---

### Task 3: Add a descriptor-only run launch path

**Files:**
- Modify: `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/test/java/com/openggf/trace/catalog/TestTraceRunLaunchValidation.java`
- Modify: `src/test/java/com/openggf/trace/catalog/TestTraceCatalogRunDiscovery.java`
- Modify: `src/test/java/com/openggf/trace/catalog/TestTraceCatalogDescriptorOwnership.java`
- Create: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunPlanningOwnership.java`

**Interfaces:**
- Produces in parallel: `TraceCatalog.PreparedDescriptorRunLaunch(Bk2Movie movie, List<TraceRunSegmentDescriptor> segments)` and `prepareDescriptorRunLaunch(TraceEntry)`.
- Preserves temporarily: eager `PreparedRunLaunch`, `prepareRunLaunch`, `RunSegmentPlanner`, and `RunPlannerPair.segmentPlanner` so every intermediate commit compiles; Task 7 removes them after the final caller migrates.
- Retains `TraceRunReplayWalker.plan(...)` only as benchmark/reference until Task 7.
- Preserves validation ordering, diagnostics, profiles, row ranges, dynamic-art validation, and BK2 parsing.

- [x] **Step 1: Write failing descriptor-only launch tests**

Inject a descriptor planner returning sentinel descriptors and an eager loader that throws if called. Assert `prepareDescriptorRunLaunch` returns the descriptors, validates profile/row/range from them, and opens no payload. Add a transitive assertion that `PreparedDescriptorRunLaunch` reaches no `TraceData`, `TraceRunSpecialStageRows`, `TraceEvent`, `Reader`, `InputStream`, or mapped buffer. Also assert the legacy eager result remains available and unchanged during this migration task.

- [x] **Step 2: Run tests and verify RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.trace.catalog.TestTraceRunLaunchValidation,com.openggf.trace.catalog.TestTraceCatalogDescriptorOwnership,com.openggf.tests.trace.runs.TestTraceRunPlanningOwnership" test
```

Expected: the parallel descriptor preparation API does not exist.

- [x] **Step 3: Add descriptor preparation**

Add the parallel descriptor result/method and validate metadata and row counts from each descriptor, returning an immutable descriptor list. Keep the eager result and planner pair compiling for visual/headless consumers not migrated until Tasks 5-6. No migrated production path may call the eager method.

- [x] **Step 4: Run catalog/planning suites GREEN**

Run Step 2, then `mvn -Dmse=off "-Dtest=com.openggf.trace.catalog.*" test`.

- [x] **Step 5: Commit Task 3**

Commit as `perf(traces): prepare run launch from descriptors` with policy trailers.

---

### Task 4: Migrate production run ownership and cleanup

**Files:**
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/RunSegmentAdvancer.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunPlaybackCoordinator.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherFailureCleanup.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherProductionFailureCleanup.java`
- Modify: `src/test/java/com/openggf/TestLevelIterationAdmissionController.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionSpecialStageTerminalExit.java`
- Modify: `src/test/java/com/openggf/TestVisualTraceRunTerminalTail.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunHardwareTimingCoordinator.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/VisualRunReplayHarness.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestS2CompleteEmeraldVisualRun.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestCompleteRunAudioReplayCadence.java`
- Create: `src/test/java/com/openggf/TestTraceSessionLauncherActivePayloadLifecycle.java`
- Create: `src/test/java/com/openggf/tests/trace/runs/TestVisualRunActivePayloadLifecycle.java`

**Interfaces:**
- `TraceSessionLauncher.runSegments` becomes `List<TraceRunSegmentDescriptor>`.
- `RunSegmentAdvancer` becomes descriptor-backed because it consumes only segment topology.
- Produces an erasure-safe run constructor `TraceSessionLauncher(TraceEntry, Bk2Movie, List<TraceRunSegmentDescriptor>, ActiveSegmentPayload, TraceReplaySessionBootstrap.ConfigSnapshot)`; migrate/remove the four-argument eager run constructor rather than reusing its erased `List` slot.
- Adds nullable `ActiveSegmentPayload activeRunPayload` and package-private `ActiveSegmentFactory` test seam with `open(TraceRunSegmentDescriptor, int) throws IOException` plus a default `close(ActiveSegmentPayload)` that delegates to the lease's no-throw close.
- Adds distinctly named descriptor helpers `hasDescriptorHardwareTimingStream` and `descriptorHardwareTimingSegments`; retain eager helpers until the headless consumer migrates.

- [x] **Step 1: Write failing lifecycle transcript tests**

Use an injected factory that calls the real facade, records each opened lease,
and asserts every preceding lease is closed before returning the next. Cover
initial launch, two-segment handoff, gap, terminal tail, open failure, assertion,
production exception, cleanup failure, abort, user exit, and repeated teardown. The real lease close is deliberately idempotent and no-throw; exercise suppression by injecting a factory `close` implementation that closes first and then throws. Reconstruct the
normal transcript from factory calls and observed `isClosed()` transitions:

```text
open 0
close 0
open 1
close 1
```

Assert maximum active count one and zero during gap/terminal tail.
For each visual/audio entry point, inject one failure before the reflective session constructor accepts the lease and one failure after ownership transfer but before replay bootstrap completes. Require the local owner to close before transfer, and session teardown to detach aliases and close after transfer.

- [x] **Step 2: Write failing alias-release tests**

Hold weak references to source `TraceData`, special rows, comparator/driver, and aux graph. Advance/fail, clear only test locals, force GC with bounded retries, and require each source reference clears while destination remains usable. Attach boundary probe, playback observer, HUD/camera model, fixture, dynamic-art comparison, and S2 pass binder.

- [x] **Step 3: Run launcher lifecycle tests RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.TestTraceSessionLauncherActivePayloadLifecycle,com.openggf.TestTraceSessionLauncherFailureCleanup,com.openggf.TestTraceSessionLauncherProductionFailureCleanup,com.openggf.TestTraceSessionLauncherRunBranch,com.openggf.TestLevelIterationAdmissionController,com.openggf.TestTraceSessionSpecialStageTerminalExit,com.openggf.TestVisualTraceRunTerminalTail,com.openggf.tests.trace.runs.TestVisualRunActivePayloadLifecycle" test
```

- [x] **Step 4: Implement production ownership**

Call `prepareDescriptorRunLaunch`, open segment zero in `launchRun` before `prepareConfiguration`, and pass descriptors and lease through the five-argument session constructor. Existing eager consumers receive `activeRunPayload.trace()` or `.specialStageRows()` unchanged. Convert all package-level tests that directly construct run sessions to this constructor, using null only for deliberately empty/non-driving lifecycle states.

As a compile- and runtime-safe transition, have both visual harness entry points call `prepareDescriptorRunLaunch`, open segment zero, and pass the descriptors plus lease to `newRunSession`, which reflectively invokes the new five-argument constructor. Give each entry point an outer `try/finally` with explicit ownership transfer: the harness-local owner closes on configuration, fixture creation, callback, or reflective-construction failure before the session accepts the lease; after successful construction, the session is the sole owner and every activation, `finishRunLaunch`, replay, abort, observer, or teardown exit invokes session detachment/close. Never let both local and session owners remain armed. Convert `frameView` to descriptors in this same task so the harness retains no parallel eager list: derive the local row from the BK2 offset, the lag bit from `descriptor.laggedRows()`, and the physical frame only from `rawFrames()` for display/reporting. This avoids an intermediate whole-run payload graph as well as the erased-constructor cast.

At source close, preserve verification order, then detach all aliases and close in `finally`. Clear comparator/structural/special driver, pass binder, dynamic-art comparison, boundary delegate, HUD/camera suppliers, fixture observers, and launcher aliases. Suppress cleanup exceptions onto the primary failure.

During destination admission: enter hardware timing, open destination, run return-boundary/adopted-row comparison, attach consumers. Attachment failure detaches and closes before `failRun`. Construct the coordinator through `fromDescriptors`; no new consumer reads coordinator scalars. Use the distinctly named descriptor timing helpers, leaving eager helpers only for not-yet-migrated headless code.

- [x] **Step 5: Run launcher/run-control suites GREEN**

Run Step 3 plus:

```bash
mvn -Dmse=off "-Dtest=com.openggf.tests.trace.runs.TestTraceRunPlaybackCoordinator,com.openggf.tests.trace.runs.TestTraceRunFrameDriver,com.openggf.tests.trace.runs.TestTraceRunHardwareTimingCoordinator,com.openggf.tests.trace.runs.TestCompleteRunAudioReplayCadence,com.openggf.trace.live.*,com.openggf.trace.replay.*" test
mvn -Ptrace-replay -Dmse=off \
  "-Dsonic2.rom.path=$TRACE_S2_ROM_PATH" \
  "-Dtest=com.openggf.tests.trace.runs.TestS2CompleteEmeraldVisualRun" test
```

Before the ROM-backed command, discover `.gen` files from the project root as required by `AGENTS.md`, identify the Sonic 2 World REV01 image, verify SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`, and set the task-local `TRACE_S2_ROM_PATH` to that verified absolute path. Do not assume a filename, copy, rename, or symlink a ROM.

Document known base-equivalent launcher errors only if each reproduces unchanged in an isolated one-method fork; no new error is acceptable.

- [x] **Step 6: Commit Task 4**

Commit as `perf(traces): own one active production run payload`, including `CHANGELOG.md` and required release summary.

---

### Task 5: Migrate the headless chain driver

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/runs/AbstractRunChainTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestS2EhzHalfpipeRoundTripChain.java`
- Create: `src/test/java/com/openggf/tests/trace/runs/TestHeadlessRunActivePayloadLifecycle.java`

**Interfaces:**
- Headless setup uses `List<TraceRunSegmentDescriptor>` and one lease per driven segment.
- `HeadlessRunCoordinatorAdapter` owns coordinator results; `sourceComparatorExhausted` no longer calls `levelLoopRowCount` directly.

- [x] **Step 1: Write failing headless lifecycle tests**

Drive ordinary -> special -> bridge -> return. Assert open/close order, zero payload in gaps, adopted row parity, S2 pass parity, and source weak-reference collection. Exercise a failing comparison and failing destination open to prove the per-boundary `finally` clears every alias before propagating the primary failure.

- [x] **Step 2: Run tests RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.tests.trace.runs.TestHeadlessRunActivePayloadLifecycle,com.openggf.tests.trace.runs.TestTraceRunReplayWalkerControlFlow" test
```

- [x] **Step 3: Convert `AbstractRunChainTest` by ownership**

Use descriptors for all topology/history variables, including replacing retained `SegmentPlan` values and `uncomparedInteriorSourceLevel`. Use `descriptor.openingFrame()` plus metadata for return-boundary comparison, and the active destination lease only for the adopted row. The current lease is the only payload source:

```java
try (ActiveSegmentPayload active =
        TraceRunReplayWalker.openActiveSegment(descriptor, segmentIndex)) {
    driveActiveSegment(descriptor, active, coordinator);
}
```

Do not capture `TraceData` beyond the block or store it in lists/report fields. Route direct `levelLoopRowCount` logic through the coordinator adapter. In a per-boundary `finally`, clear the probe delegate, `productionComparator`, active/structural comparator, special driver/pass binder, dynamic-art comparison, slot probe, fixture aliases, and current lease before the next open. Mark the eager coordinator constructor and eager timing helpers eligible for removal, but defer removal to Task 7's whole-repository caller proof.

- [x] **Step 4: Run headless chain suites GREEN**

Find subclasses with `rg -l "extends AbstractRunChainTest" src/test/java | sort`, pass their class names to Maven `-Dtest=` under `-Ptrace-replay`, and preserve exact frontiers. No baseline-passing trace may regress.

- [x] **Step 5: Commit Task 5**

Commit as `test(traces): bound headless run payload ownership`.

---

### Task 6: Harden visual and complete-audio lease lifecycles

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/runs/VisualRunReplayHarness.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestVisualRunActivePayloadLifecycle.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestCompleteRunAudioReplayCadence.java`

**Interfaces:**
- Preserves Task 4's descriptor-only `frameView`, which opens no payload.
- Visual and complete-audio replay share one failure-safe active-lease lifecycle.
- Adds only inside the test harness a package-private `VisualPayloadCloser.close(ActiveSegmentPayload)` injection seam whose normal default delegates to the lease's idempotent no-throw close; it is not an acquisition or relay path.

- [x] **Step 1: Write a failing cleanup-suppression test, then add lifecycle characterization**

First inject a closer that invokes the real close and then throws while a distinct primary observer/replay failure is active. Assert the primary failure remains primary, the cleanup failure is suppressed exactly once, and lease/payload aliases are collectible; this test must fail because the injection seam is absent. Then cover active first/last row, gap, handoff, adopted row, tail, visual/audio failure, abort, repeated teardown, and normal audio completion. Preserve the Task 4 proof that frame view leaves the factory transcript empty and uses `descriptor.laggedRows().get(localRow)`.

- [x] **Step 2: Run tests RED**

```bash
mvn -Dmse=off "-Dtest=com.openggf.tests.trace.runs.TestVisualRunActivePayloadLifecycle,com.openggf.tests.trace.runs.TestCompleteRunAudioReplayCadence" test
```

- [x] **Step 3: Implement failure-safe visual/audio cleanup**

Keep the descriptor preparation/session/frame-view path established in Task 4. Add the closer seam only to make cleanup failure observable, and route both entrypoint `finally` blocks through it after clearing HUD/camera/observer aliases. Complete-audio replay remains owned inside `VisualRunReplayHarness`; update its cadence regression rather than inventing a second audio lease owner. Suppress injected cleanup failures onto the primary replay failure and throw cleanup alone only when no primary exists. Add the remaining assertion, observer, audio, abort, reachability, and repeated-teardown cases without adding another acquisition path.

- [x] **Step 4: Run visual/audio/special-stage suites GREEN**

```bash
mvn -Ptrace-replay -Dmse=off "-Dtest=com.openggf.tests.trace.runs.*Visual*,com.openggf.tools.audio.completerun.*,com.openggf.trace.replay.runs.TestTraceRunSpecialStageRows,com.openggf.trace.replay.runs.TestTraceRunSpecialStageRowDriver" test
```

- [x] **Step 5: Commit Task 6**

Commit as `perf(traces): bound visual and audio run payloads`.

---

### Task 7: Enforce authority, reachability, memory, and resources

**Files:**
- Modify: `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunPlaybackCoordinator.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/test/java/com/openggf/trace/TestTraceReaderLifecycle.java`
- Modify: `src/test/java/com/openggf/trace/catalog/TestTraceRunLaunchValidation.java`
- Modify: `src/test/java/com/openggf/trace/catalog/TestTraceCatalogRunDiscovery.java`
- Modify: `src/test/java/com/openggf/trace/catalog/TestTraceCatalogDescriptorOwnership.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunPlaybackCoordinator.java`
- Create: `src/test/java/com/openggf/tests/trace/runs/TestActiveSegmentPayloadAuthorityGuard.java`
- Create: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunActivePayloadOwnership.java`
- Create: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunActivePayloadPerformance.java`
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunDescriptorPlanningPerformance.java`

**Interfaces:**
- Exact accessor/facade allowlist: `com.openggf.TraceSessionLauncher`, `com.openggf.tests.trace.runs.AbstractRunChainTest`, `com.openggf.tests.trace.runs.VisualRunReplayHarness`, `com.openggf.trace.replay.runs.TestActiveSegmentPayload`, `com.openggf.trace.TestTraceReaderLifecycle`, `com.openggf.TestTraceSessionLauncherActivePayloadLifecycle`, `com.openggf.tests.trace.runs.TestHeadlessRunActivePayloadLifecycle`, `com.openggf.tests.trace.runs.TestVisualRunActivePayloadLifecycle`, `com.openggf.tests.trace.runs.TestTraceRunActivePayloadOwnership`, `com.openggf.tests.trace.runs.TestTraceRunActivePayloadPerformance`, and `com.openggf.tests.trace.runs.TestActiveSegmentPayloadAuthorityGuard`. No naming pattern or package-wide allowance is permitted.
- Benchmark property: `openggf.trace.activePayloadBenchmark=true` under `-Ptrace-replay`.

- [x] **Step 1: Write failing public-surface/caller guard**

Require final lease, non-public constructor, exact public methods `descriptor`, `trace`, `specialStageRows`, `isClosed`, `close`, and one public walker open facade. Use the existing ArchUnit dependency to inspect compiled production and test bytecode and reject every direct call or method reference targeting `trace`, `specialStageRows`, or the walker facade outside the exact FQCN allowlist above. Lock the exact public API and constructor visibility with reflection. Do not derive test permission from a class-name pattern or package.

Add a separate source scan for reflective and method-handle acquisition (`Class.forName`, class literals, `getMethod`, `getDeclaredMethod`, `MethodHandles.Lookup.findVirtual/findStatic`, accessor-name literals, concatenated/constructed string variants) and fail with file/line. Unit-test the guard rules against nested synthetic mutation classes/source snippets that contain an unauthorized direct call, method reference, reflective call, method-handle call, and constructed accessor string; assert each is rejected without checking in a real unauthorized caller that would permanently fail the suite.

Also reject relay APIs: none of the allowlisted callers may add a public/protected method or field that exposes `ActiveSegmentPayload`, its raw `TraceData`, or `TraceRunSpecialStageRows`, and no helper class may acquire on an allowlisted caller's behalf. Dedicated tests may inspect/use the lease but must not become production acquisition paths.

- [x] **Step 2: Write failing installed-consumer reachability proof**

Attach real ordinary comparator and special driver/pass binder, then detach/close. Keep the complete installed ownership roots reachable—session/launcher, playback observer, boundary delegate, HUD/camera suppliers, fixture, comparator/structural comparator, special driver/pass binder, dynamic-art comparison, slot probe, descriptor list, and active lease—through sampling. After normal and injected-failure teardown, keep all non-payload session roots alive, force GC, and require prior `TraceData`, special rows, aux events, comparator/driver/binder, and lease graphs to clear. Mutation-control by intentionally retaining a comparator and proving the reference remains until removed.

- [x] **Step 3: Write opt-in memory/resource benchmark**

Warm/release planners before both arms. Measure both arm orders or fresh forks, use isolated lexical scopes for each arm, clear locals between them, and call `Reference.reachabilityFence` on every root after the sample so JIT liveness cannot bias the result. Keep the fixed warmed eager baseline `1,087,200,800` bytes as the denominator; do not replace it with a same-run noisy measurement. Keep descriptors plus the complete installed-consumer roots enumerated in Step 2 reachable while sampling all 67 S3K segments and representative S1/S2 special stages. Print:

```text
TRACE_ACTIVE_PAYLOAD_BENCH eager_retained_bytes=... descriptor_retained_bytes=... max_installed_bytes=... reduction_percent=... max_segment=...
```

Assert descriptor `<= 16,777,216`, installed `<= 268,435,456`, and `100 * (1 - max_installed_bytes / 1_087_200_800.0) >= 75`. Keep deterministic 100-cycle plain/gzip/ordinary/S1/S2/S3K balance assertions in package-peer `com.openggf.trace.TestTraceReaderLifecycle`, where the package-private observer is accessible. The performance test runs that suite as a separate acceptance command and uses `/proc/self/fd` only as a Linux smoke check, never as the deterministic oracle.

- [x] **Step 4: Remove the transitional eager launch path**

After `rg` and ArchUnit prove no production/headless/visual/audio caller remains, remove `PreparedRunLaunch`, `prepareRunLaunch`, `RunSegmentPlanner`, `RunPlannerPair.segmentPlanner`, the eager timing helpers, and any non-benchmark use of `TraceRunReplayWalker.plan(...)`. Update catalog discovery/validation tests to the descriptor result. Convert every remaining four-argument eager-coordinator call in `TestTraceRunPlaybackCoordinator` to `fromDescriptors` (or an explicit three-argument no-plan case) before removing that constructor. Retain `plan(...)` only if the eager benchmark/reference still requires it, and guard that no runtime or replay harness calls it.

- [x] **Step 5: Run guard/ownership RED, then fix only enforcement gaps**

```bash
mvn -Dmse=off "-Dtest=com.openggf.tests.trace.runs.TestActiveSegmentPayloadAuthorityGuard,com.openggf.tests.trace.runs.TestTraceRunActivePayloadOwnership" test
```

Do not relax allowlists or reachability assertions; remove leaked aliases or excess API.

- [x] **Step 6: Run benchmark and reader lifecycle GREEN twice and preserve logs**

```bash
mvn -Ptrace-replay -Dmse=off \
  -Dopenggf.trace.activePayloadBenchmark=true \
  "-Dtest=com.openggf.tests.trace.runs.TestTraceRunActivePayloadPerformance" test
mvn -Dmse=off "-Dtest=com.openggf.trace.TestTraceReaderLifecycle" test
```

Repeat in a fresh Maven fork and require both passes meet every cap.

Whole-branch review correction: the installed benchmark root must be a real
prepared launch graph, not the JUnit instance standing in for a session. The
final corrected graph also replaces the synthetic fixture consumer and
parallel consumer roots with the exact headless harness/`LiveEngineFixture`
and the consumers installed in the launcher's and harness's actual fields.
Both fresh final forks retained actual catalog entries, parsed BK2 movies,
complete descriptor sets, a `TraceSessionLauncher`, the exact fixture/harness,
and all installed ordinary/special consumers. They printed respectively:

```text
TRACE_ACTIVE_PAYLOAD_BENCH eager_retained_bytes=1087200800 descriptor_retained_bytes=9253296 max_installed_bytes=170550952 reduction_percent=84.31 max_segment=s3k-59-soz_2
TRACE_ACTIVE_PAYLOAD_BENCH eager_retained_bytes=1087200800 descriptor_retained_bytes=9252768 max_installed_bytes=170910128 reduction_percent=84.28 max_segment=s3k-59-soz_2
```

- [x] **Step 7: Commit Task 7**

Commit as `test(traces): enforce active payload ownership`.

---

### Task 8: Establish the baseline, verify accuracy, document evidence, and integrate

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/architecture/designs/2026-08-21-bounded-trace-run-segment-ownership.md`
- Modify: `docs/architecture/audits/performance/2026-08-21-performance-investigation-report-audit.md`
- Modify: `docs/architecture/plans/2026-08-21-bounded-trace-segment-ownership-implementation-plan.md`
- Create: `docs/architecture/validation/trace/2026-08-22-active-segment-ownership-validation.md`

**Interfaces:**
- Produces exact before/after heap, frontier, resource, suite, branch, and commit evidence.
- Marks design implemented only after every gate passes.

- [x] **Step 1: Synchronize the main workspace and establish the exact baseline**

Inspect the main workspace for user changes without switching its branch. Fetch `origin`, fast-forward pull the checked-out `develop` only when doing so preserves all user changes, record the resulting commit, and run `mvn -Dmse=off test` there on JDK 21. Preserve the complete baseline log and exact failing method identities. If the main workspace cannot be safely fast-forwarded, stop integration work and report the unresolved state rather than using a stale or destructive baseline.

- [x] **Step 2: Run focused acceptance suites**

Run catalog/planning, lease/reader, coordinator/frame driver, launcher lifecycle, live/structural comparator, timing authority, S1/S2/S3K special stage, headless chain, visual, and complete-audio suites on JDK 21. Preserve complete logs in managed scratch.

- [x] **Step 3: Run recorded 67-segment oracle**

Require all 1,653 AIZ rows, first mismatch `camera_x` expected `0x1300` actual `0x1308`, same terminal segment-0 `giant_ring` failure, and identical unmatched timing completions/dynamic-art result.

- [x] **Step 4: Run fresh complete trace sweep**

Use the trace-replay profile with all discovered ROM properties. Record counts, failures/errors/skips, first-error frame/field, and base-equivalent errors. No baseline-passing trace may fail/starve.

- [x] **Step 5: Run the development full-suite comparison required by `AGENTS.md`**

Run the same `mvn -Dmse=off test` command in the development worktree and compare exact failing method identities with Step 1. A red baseline is acceptable; a new or worsened failure is not. Run the focused suites again in the development worktree after this full-suite command so both broad and relevant evidence are fresh.

- [x] **Step 6: Update documentation and self-review**

Record exact measurements, special-stage maxima, resource cycles, oracle, full-suite comparison, and authority debt. Mark completed plan boxes. Run `git diff --check` and `git status --short`.

- [x] **Step 7: Commit documentation**

Commit as `docs(traces): validate active segment ownership`, staging all listed artifacts with truthful `updated` trailers.

Steps 1-7 are evidenced by
[`2026-08-22-active-segment-ownership-validation.md`](../validation/trace/2026-08-22-active-segment-ownership-validation.md).
Independent review and integration remain controller-owned Steps 8 and 9.
Step 8 is complete; Step 9 remains pending below.

The authoritative Step 1/5 baseline was refreshed after upstream moved:
measured `develop` / `origin/develop` is `d473365ed`, and the feature suite
measurement point is reconciled commit `1a96fbdf1`. The fresh main/feature
trace sweeps share ten exact red identities and the completion audit accounts
for all baseline-green replay families. Task 8 initially exposed two real S1 visual
bridge regressions at `3a3c1fc5`; Task 4 fixes `25d4a41b7` and `c8cb56808`
closed them before the refreshed gates. Older `d9650fd7` measurements remain
historical only.

Later whole-branch authority review produced enforcement-only corrections
through `7b16dc94b`; the production ownership graph and the suite measurement
point above are unchanged. At that correction head the authority guard passes
35/35, authority plus ownership passes 38/38, and the combined reader gate
passes 44/44. Focused migration remains 179/179, the benchmark-root structural
test is 1/1, and policy/docs guards are 156/156.

- [x] **Step 8: Independent final code/spec review**

Review authority expansion, allowlist bypass, aliases, boundary ordering, special-stage parity, benchmark bias, and regression accounting. Fix material findings test-first and repeat until GREEN.

The final independent whole-branch review at `c811c9d2d` reported no Critical,
Important, or Minor findings. Its fresh authority/ownership/reader gate passed
44/44, all earlier ruled-in mutations remained diagnostic, `git diff --check`
was clean, and the worktree was clean. The spec and code-quality verdicts were
both GREEN.

- [x] **Step 9: Recheck upstream and integrate according to `AGENTS.md`**

Fetch `origin` again. If `origin/develop` moved after Step 1, fast-forward the main-workspace `develop`, rerun its exact baseline command, and replace the comparison baseline with those fresh method identities before merging. Reconcile feature conflicts without switching the main workspace, ensure the feature branch includes the required README release-summary update, merge into main-workspace `develop`, run the post-merge full suite and focused suites, and compare with the latest recorded baseline. Push only `develop`. After push succeeds, inspect every feature-worktree change, discard only identified workflow-generated outputs, preserve/report unknown or unmerged work, remove the worktree, verify the feature branch is fully merged, delete that local branch, and prune metadata. Do not claim completion if synchronization, comparison, merge, push, or required cleanup fails; preserve all integration logs and report pushed commits.

Synchronization, merge, and post-merge regression comparison completed at
merge commit `d3b62c23a`; no regression is attributable to the merge. The
final range policy exposed and test-first fixed a merge-parent parsing defect
at `f2b27262f`; the actual `ci-push` range then passed. `develop` was pushed,
the clean fully merged feature worktree and local branch were removed, and
worktree metadata was pruned. Step 9 is complete.
