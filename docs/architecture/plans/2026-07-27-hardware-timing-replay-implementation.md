# Hardware-Timing Replay Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a symptom-first, rewind-safe hardware timing service whose dedicated trace input can release only matching prepared S3K Kos module work at recorded service boundaries, allowing AIZ and HCZ title/ring timing to follow their ordinary ROM consumers.

**Architecture:** Production code submits ROM-backed work to one FIFO timing service and advances it through ROM-derived service points. Live play uses the production work-unit scheduler. Replay loads a separate `hardware_timing.jsonl` stream and replaces only final readiness admission after kind, ordinal, fingerprint, preparation, and boundary match; physics and aux data remain comparison-only.

**Tech Stack:** Java 21, JUnit 5, Jackson, Maven, C# 7.x/Mono native BizHawk recorder, Lua 5.4 reference recorder, existing rewind registry and trace replay infrastructure.

## Global Constraints

- Trace authority is limited to the dedicated timing port in the approved design; physics CSV and aux events remain comparison-only.
- A trace edge cannot submit, prepare, identify, or mutate a job; it can only release independently matching prepared work.
- No game-name, zone, route, trace, fixture, or frame carve-outs in shared runtime code.
- All runtime assets and submission fingerprints derive from the user-supplied ROM.
- Live timing uses ROM-derived deterministic work units and service points, never host wall-clock duration.
- Every gameplay row remains compared; no tolerance or transition-row suppression is permitted.
- LBZ is excluded from trace-work discovery and validation commands.
- Stage only task-owned files; never use `git add -A`, `git stash`, `--no-verify`, or trace-to-engine gameplay hydration.

## Per-task integration gate

Before every task commit, delegate an independent spec-compliance and code-quality review.
The reviewer reads the approved design, this plan, the staged diff, and test output. Address
every blocking finding and re-review until `APPROVE`; then run `git diff --check`, stage only
the task's listed files, and inspect `git diff --cached --name-only`. Let the repository hook
append the required trailers and fill every value truthfully. Architecture artifacts use
`Agent-Docs: n/a`; Task 3 stages both `AGENTS.md` and `CLAUDE.md` and uses
`Agent-Docs: updated`. Use the exact task trailer matrix below; all omitted mapped files
remain unstaged.

| Task | Changelog | Guide | Known-Discrepancies | S3K-Known-Discrepancies | Agent-Docs | Configuration-Docs | Skills |
|---|---|---|---|---|---|---|---|
| 1 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| 2 | n/a: internal trace container parser; no player-facing runtime change | n/a | n/a | n/a | n/a | n/a | n/a |
| 3 | n/a | n/a | n/a | n/a | updated | n/a | n/a |
| 4 | updated | n/a | n/a | n/a | n/a | n/a | n/a |
| 5 | n/a: test/replay authority adapter only; live gameplay remains production-owned | n/a | n/a | n/a | n/a | n/a | n/a |
| 6 | updated | n/a | n/a | n/a | n/a | n/a | n/a |
| 7 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| 8 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| 9 | updated | n/a | n/a | updated | n/a | n/a | n/a |
| 10 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |

---

### Task 1: Close the cross-game timing inventories

**Files:**
- Create: `docs/architecture/audits/2026-07-27-s1-hardware-timing-inventory.md`
- Create: `docs/architecture/audits/2026-07-27-s2-hardware-timing-inventory.md`
- Create: `docs/architecture/audits/2026-07-27-s3k-hardware-timing-inventory.md`
- Reference: `docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md`

**Interfaces:**
- Consumes: the five replay contracts defined by the approved design.
- Produces: one table per game with columns `ROM owner`, `service point`, `polled gate`, `main loop admitted while pending`, `gameplay consumer`, `existing replay symptom`, and `disposition`.

- [ ] **Step 1: Inventory S1 service queues and lag-only work**

Record every `RunPLC`/`QuickPLC`, decompression, fade, controller-sampling, special-stage, VInt-counter, VDP-wait, and Z80-bus path found with:

```bash
rg -n "RunPLC|QuickPLC|ProcessDMA|WaitForVint|VBlank|v_vblank_byte|NemDec|KosDec|EniDec|SaxDec|Z80" docs/s1disasm
```

Classify work as `LAG`, `PHASE`, `NATIVE_SERVICE_QUEUE`, `INITIAL_BASE`, or `DIAGNOSTIC_ONLY`. Cite the exact disassembly location for every non-diagnostic row.

- [ ] **Step 2: Inventory S2 service queues and special-stage phases**

Use:

```bash
rg -n "RunPLC|QuickPLC|ProcessDMA|WaitForVint|VintID_|Vint_Lag|NemDec|KosDec|EniDec|SaxDec|Z80" docs/s2disasm
```

Reconcile the result with `docs/architecture/research/trace/s2-special-stage-init-timeline.md`. Explicitly identify which ordinary gameplay routines poll a pending PLC while the main loop continues.

- [ ] **Step 3: Inventory S3K direct/module queues and hardware fences**

Use:

```bash
rg -n "Process_Kos_Queue|Process_Kos_Module_Queue|Kos_decomp_queue_count|Kos_modules_left|Wait_VSync|DMA|Plane|Refresh" docs/skdisasm/sonic3k.asm \
  | rg -vi "LBZ|Lava Reef"
```

Separate `KOS_DECOMPRESSION_QUEUE` from `KOS_MODULE_QUEUE`. List all non-LBZ gameplay
consumers, including AIZ intro and ICZ transition direct-queue gates. Do not inspect,
document, implement, or validate LBZ trace behavior.

- [ ] **Step 3a: Pin trace-schema and raw-boundary ownership**

In the S3K inventory, cite exact RAM owners for queue count, module count, and busy state;
define pending-to-complete transition detection; map BizHawk emulator frames to
`raw_frame`; and record visibility at `vint_service`, `pre_main_loop`, and `post_objects`.
Verify this against both standard and complete-run recorder loops before runtime or recorder
implementation.

- [ ] **Step 4: Verify no unjustified authoritative kind**

The conclusion tables must leave `KOS_MODULE_QUEUE` as the only authoritative version-1 kind. Any additional candidate is `NATIVE_SERVICE_QUEUE_PENDING_REVIEW`, with the ROM evidence needed for a later design.

- [ ] **Step 5: Review and commit the audits**

Run:

```bash
git diff --check -- docs/architecture/audits
rg -n "TBD|TODO|FIXME|route-specific|frame-specific" docs/architecture/audits/2026-07-27-*-hardware-timing-inventory.md
```

Expected: no placeholders, no route/frame workaround, and every authoritative classification has a disassembly citation.

Commit only the three audit files with:

```text
docs(trace): inventory hardware timing symptoms
```

---

### Task 2: Parse and validate the dedicated timing stream

**Files:**
- Create: `src/main/java/com/openggf/game/timing/HardwareWorkKind.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareServiceBoundary.java`
- Create: `src/main/java/com/openggf/trace/timing/HardwareCompletionEdge.java`
- Create: `src/main/java/com/openggf/trace/timing/HardwareTimingSchedule.java`
- Create: `src/main/java/com/openggf/trace/timing/HardwareTimingStreamLoader.java`
- Modify: `src/main/java/com/openggf/trace/TraceMetadata.java`
- Modify: `src/main/java/com/openggf/trace/TraceData.java`
- Modify: `src/test/java/com/openggf/trace/TraceFixtures.java`
- Modify: direct `new TraceMetadata(...)` call sites reported by `rg -n 'new TraceMetadata' src/test/java`
- Test: `src/test/java/com/openggf/trace/timing/TestHardwareTimingStreamLoader.java`
- Test: `src/test/java/com/openggf/trace/TestTraceDataHardwareTiming.java`

**Interfaces:**
- Consumes: `metadata.hardware_timing_schema`, fixed file `hardware_timing.jsonl`.
- Produces:

```java
public enum HardwareWorkKind {
    KOS_MODULE_QUEUE
}

public enum HardwareServiceBoundary {
    VINT_SERVICE,
    PRE_MAIN_LOOP,
    POST_OBJECTS
}

public record HardwareCompletionEdge(
        int rawFrame,
        HardwareServiceBoundary boundary,
        HardwareWorkKind kind,
        long ordinal,
        String submissionFingerprint) {
}

public final class HardwareTimingSchedule {
    public static HardwareTimingSchedule empty();
    public List<HardwareCompletionEdge> edges();
    public List<HardwareCompletionEdge> edgesAt(
            int rawFrame, HardwareServiceBoundary boundary);
}
```

- [ ] **Step 1: Write schema RED tests**

Cover:

```java
@Test void legacyFixtureWithoutKeyOrFileLoadsEmptySchedule()
@Test void versionOneRequiresHardwareTimingFile()
@Test void fileWithoutMetadataKeyFails()
@Test void unknownSchemaFails()
@Test void malformedOrUnknownEventFails()
@Test void eventsMustUseCanonicalOrdering()
@Test void duplicateIdentityFails()
@Test void emptyVersionOneStreamIsValid()
@Test void schemaOneRequiresTraceSchemaSeven()
@Test void traceSchemaSevenRequiresKeyAndFile()
@Test void legacySchemaRejectsHardwareTimingSchema()
@Test void rawFrameMustBeWithinTraceFrameCount()
@Test void ordinalsAreMonotonicPerKind()
```

Construct temporary fixtures with a minimal `metadata.json`, `physics.csv`, and `hardware_timing.jsonl`. Assert failures include the filename and rejected field.

- [ ] **Step 2: Run the loader tests and observe RED**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestHardwareTimingStreamLoader,TestTraceDataHardwareTiming" test
```

Expected: compilation failures for the missing timing types and metadata field.

- [ ] **Step 3: Add the exact metadata and loader contract**

Append to `TraceMetadata`:

```java
@JsonProperty("hardware_timing_schema") Integer hardwareTimingSchema
```

Update every direct test constructor call with a final `null` argument so legacy
fixtures retain absent-key semantics; do not add an overload that could conceal the
wire-schema field.

Add:

```java
public boolean hasHardwareTimingStream() {
    return hardwareTimingSchema != null;
}

public int requiredHardwareTimingSchema() {
    if (hardwareTimingSchema == null) {
        return 0;
    }
    if (hardwareTimingSchema != 1) {
        throw new IllegalArgumentException(
                "Unsupported hardware_timing_schema: " + hardwareTimingSchema);
    }
    return hardwareTimingSchema;
}
```

`HardwareTimingStreamLoader` must parse UTF-8 JSONL strictly, reject unknown enum values and
fields, validate lowercase wire names, validate `sha256:` plus 64 lowercase hexadecimal
digits, enforce boundary order `VINT_SERVICE < PRE_MAIN_LOOP < POST_OBJECTS`, require
`trace_schema == 7` for schema 1, require the key and file for schema 7, reject schema 1 on
legacy schemas, bound `raw_frame` to `[0, trace_frame_count)`, and require monotonically
increasing ordinals per kind.

- [ ] **Step 4: Attach the schedule to `TraceData`**

Extend the package-private constructor and both load paths with
`HardwareTimingSchedule`. Add:

```java
public HardwareTimingSchedule hardwareTimingSchedule()
```

`TraceData.loadMetadataOnly` must enforce the same metadata/file consistency and load the timing stream because run-chain segments can still need timing input.

- [ ] **Step 5: Run GREEN and regression tests**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestHardwareTimingStreamLoader,TestTraceDataHardwareTiming,TestTraceReplayReportPolicy,TestTraceReplayStartPositionPolicy" test
```

Expected: all pass; legacy fixtures continue loading with an empty schedule.

- [ ] **Step 6: Commit**

Stage the exact files above and commit:

```text
feat(trace): parse dedicated hardware timing stream
```

---

### Task 3: Add policy and parser guards

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`
- Create: `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Reference: `docs/status/known-discrepancies.md`

**Interfaces:**
- Consumes: timing stream parser types.
- Produces: a source-level ratchet preventing physics/aux parsers from becoming timing
  authority; Task 5 extends it after the replay port exists.

- [ ] **Step 1: Write the failing guard**

The guard must scan `src/main/java` and assert:

```text
TraceFrame, TraceEvent, TraceBinder, and aux parsing packages do not import
HardwareTimingService.

Only com.openggf.trace.timing may parse hardware_timing.jsonl.

No gameplay owner imports com.openggf.trace.timing parser types.
```

Also assert `AGENTS.md` and `CLAUDE.md` remain byte-identical and contain the dedicated timing exception language.

- [ ] **Step 2: Run RED**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestArchitecturalSourceGuard,TestHardwareTimingAuthorityGuard" test
```

Expected: failure until the parser package/API names are present and confined.

- [ ] **Step 3: Implement the source guard allowlist**

Use exact fully-qualified package prefixes, not substring exclusions. The parser allowlist is:

```text
com.openggf.trace.timing
```

No test fixture class is allowed to call a gameplay mutation API through reflection.
Keep `AGENTS.md` and `CLAUDE.md` byte-identical and preserve the approved timing exception;
stage them together.

- [ ] **Step 4: Run GREEN and commit**

Run the same Maven command. Expected: all pass.

Commit:

```text
test(trace): guard hardware timing authority boundary
```

---

### Task 4: Implement the production hardware timing FIFO

**Files:**
- Create: `src/main/java/com/openggf/game/timing/HardwareSubmissionFingerprint.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareWorkSubmission.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareWorkHandle.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareTimingJob.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareTimingService.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareTimingSnapshot.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareWorkPreparation.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareWorkPreparationSnapshot.java`
- Create: `src/main/java/com/openggf/game/timing/RecordedCompletionAuthority.java`
- Create: `src/main/java/com/openggf/game/timing/PendingRecordedSubmission.java`
- Create: `src/main/java/com/openggf/game/timing/HardwareTimingBoundaryObserver.java`
- Create: `src/main/java/com/openggf/game/timing/RomWorkBudgetScheduler.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/main/java/com/openggf/LevelFrameContext.java`
- Modify: `src/main/java/com/openggf/LevelFrameStep.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Modify: `CHANGELOG.md`
- Test: `src/test/java/com/openggf/game/timing/TestHardwareSubmissionFingerprint.java`
- Test: `src/test/java/com/openggf/game/timing/TestHardwareTimingService.java`
- Test: `src/test/java/com/openggf/game/timing/TestHardwareTimingRewind.java`

**Interfaces:**
- Consumes: canonical ROM-backed source/destination spans and a runtime-owned resumable
  preparation object.
- Produces:

```java
public record HardwareWorkSubmission(
        HardwareWorkKind kind,
        int romSourceAddress,
        int compressedLength,
        int destinationAddress,
        int destinationLength,
        String compressionVariant,
        int moduleCount,
        boolean exportableAcrossSegment,
        HardwareWorkPreparation preparation) {
}

public interface HardwareWorkPreparation {
    boolean stepOneWorkUnit();
    boolean isPrepared();
    byte[] preparedPayload();
    HardwareWorkPreparationSnapshot snapshot();
    void restore(HardwareWorkPreparationSnapshot snapshot);
}

public record HardwareWorkHandle(
        HardwareWorkKind kind,
        long ordinal,
        String submissionFingerprint) {
}

public final class HardwareTimingService
        implements RewindSnapshottable<HardwareTimingSnapshot> {
    public HardwareWorkHandle submit(HardwareWorkSubmission submission);
    public void service(HardwareServiceBoundary boundary);
    public boolean isPending(HardwareWorkHandle handle);
    public boolean isReady(HardwareWorkHandle handle);
    public byte[] claim(HardwareWorkHandle handle);
    public List<HardwareWorkHandle> pendingHandles();
}

public interface RecordedCompletionAuthority {
    void admitRecordedCompletion(
            HardwareServiceBoundary boundary,
            HardwareWorkKind kind,
            long ordinal,
            String submissionFingerprint);
    List<PendingRecordedSubmission> pendingSubmissions();
    void endRecordedAdmission();
}

public record PendingRecordedSubmission(
        HardwareWorkHandle handle,
        boolean exportableAcrossSegment) {
}

public interface HardwareTimingBoundaryObserver {
    void onBoundary(HardwareServiceBoundary boundary);
}

public enum HardwareReadinessAdmissionPolicy {
    LIVE,
    RECORDED
}
```

`GameplayModeContext` owns the observer, defaulting to a no-op.
`LevelFrameContext.from(GameplayModeContext)` carries that session-owned reference into the
static `LevelFrameStep`, which only invokes it immediately after servicing each production
boundary. No observer is static or cross-session, and `GameLoop` never imports replay code.
`claim` returns a defensive copy exactly once and fails unless the handle is ready.
`service` advances preparation through integer work units. Under `LIVE`, prepared FIFO
heads become ready immediately; under `RECORDED`, they remain prepared-but-held until the
authority admits a matching edge. `beginRecordedAdmission()` may be called only before the
first submission and returns the narrow capability; it remains active across structural
run-chain segments. `RecordedCompletionAuthority.endRecordedAdmission()` is final-run-only,
verifies no unexpected pending submission remains, and restores `LIVE`. Only that capability
implements `RecordedCompletionAuthority`; the trace adapter receives that capability, not
the service. The trace cannot provide or advance preparation.

- [ ] **Step 1: Write fingerprint and FIFO RED tests**

Tests must prove:

- the SHA-256 fingerprint changes for source start/length, destination start/length,
  compression variant, module count, and kind;
- the segment-exportability flag is engine-owned scheduling policy and excluded from the
  canonical ROM-work fingerprint;
- payload bytes are excluded from the canonical identity;
- ordinals are monotonic per kind;
- FIFO service never releases a later job first;
- `claim` before readiness and a second claim fail;
- host elapsed time has no effect;
- a defensive payload copy is returned.
- recorded admission cannot release a job until `preparation.isPrepared()` is true.

- [ ] **Step 2: Write rewind RED tests**

Create snapshots immediately before, on, and after completion. Restore each and assert the same service/claim behavior and ordinal allocation recur.

- [ ] **Step 3: Run RED**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestHardwareSubmissionFingerprint,TestHardwareTimingService,TestHardwareTimingRewind" test
```

- [ ] **Step 4: Implement canonical fingerprinting**

Encode the canonical tuple as fixed-width big-endian integers plus UTF-8 enum/variant names, then SHA-256 it. Return `sha256:` followed by exactly 64 characters matching `[0-9a-f]`.

Do not serialize Java object identity, file paths, zone names, or trace data.

- [ ] **Step 5: Implement FIFO and ROM-budget scheduler**

`RomWorkBudgetScheduler` consumes integer work units only at configured service boundaries.
It calls `stepOneWorkUnit()` on the head preparation object and never accepts payload bytes
or preparation progress from replay. Task 6 supplies the KosM implementation and derives
budgets from decoder/module service semantics, without guessing a title-card countdown.

- [ ] **Step 6: Install session ownership**

Construct exactly one `HardwareTimingService` with `GameplayModeContext`, expose:

```java
public HardwareTimingService hardwareTiming()
```

Register its rewind adapter inside `GameplayModeContext.attachGameplayManagers()` after the
`RewindRegistry` is created, and deregister it during context teardown.

- [ ] **Step 6a: Establish canonical production boundary dispatch**

Add the boundary sequencer to `LevelFrameStep`. Both `GameLoop` and
`RecordingFrameDriver` delegate live scheduler service to it. VBlank-only paths emit only
`VINT_SERVICE`; setup/advance-only paths emit only boundaries genuinely traversed; and
`POST_OBJECTS` occurs exactly after the object scan. Task 5 attaches a replay observer to
this same sequencer and must not add a second/ad-hoc dispatch path.

- [ ] **Step 7: Run GREEN and commit**

Run the three focused tests plus:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestArchUnitRules" test
```

Commit:

```text
feat(runtime): add rewind-safe hardware timing FIFO
```

---

### Task 5: Implement the bounded replay authority port

**Files:**
- Create: `src/main/java/com/openggf/trace/timing/HardwareTimingReplayPort.java`
- Create: `src/main/java/com/openggf/trace/timing/HardwareTimingReplaySnapshot.java`
- Create: `src/main/java/com/openggf/trace/timing/TraceHardwareTimingBoundaryObserver.java`
- Modify: `src/main/java/com/openggf/game/timing/HardwareTimingService.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplayFixture.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/tools/TraceCaptureTool.java`
- Modify: `src/test/java/com/openggf/tests/trace/TraceReplayFrameClosureDriver.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceReplayReferenceClosureGuard.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestS3kAizPrefixClosureContract.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestRunner.java`
- Test: `src/test/java/com/openggf/trace/timing/TestHardwareTimingReplayPort.java`
- Test: `src/test/java/com/openggf/tools/TestRecordingFrameDriverHardwareTiming.java`

**Interfaces:**
- Consumes: `HardwareTimingSchedule`, engine FIFO state, raw frame and service boundary.
- Produces:

```java
public final class HardwareTimingReplayPort
        implements RewindSnapshottable<HardwareTimingReplaySnapshot> {
    public void install(HardwareTimingSchedule schedule);
    public void beginRawFrame(int rawFrame);
    public void apply(HardwareServiceBoundary boundary);
    public void handoffTo(HardwareTimingSchedule nextSchedule);
    public void verifyRunComplete();
}
```

The port constructor accepts `RecordedCompletionAuthority`, never
`HardwareTimingService`. It translates the wire edge to production enums and invokes the
narrow capability, which validates prepared state, head-of-kind FIFO ordering, kind,
ordinal, fingerprint, and current boundary before changing readiness. The port alone
validates `raw_frame` against its schedule cursor. Production runtime packages never import
trace-domain records or enums.

- [ ] **Step 1: Write adversarial RED tests**

Cover:

```java
@Test void matchingPreparedHeadReleasesAtBoundary()
@Test void earlyPreparedJobIsHeldUntilEdge()
@Test void edgeCannotPrepareAJob()
@Test void wrongKindOrdinalFingerprintOrBoundaryFails()
@Test void duplicateAndReorderedEdgesFail()
@Test void nonExportablePendingSubmissionFailsAtSegmentEnd()
@Test void exportablePendingSubmissionRequiresMatchingNextSegmentEdge()
@Test void validExportPreservesOrdinalAndPreparationAcrossHandoff()
@Test void unconsumedEdgeFailsAtSegmentEnd()
@Test void rewindRestoresEdgeCursorAndConsumedLedger()
```

Assert exception messages print expected and engine identities.

- [ ] **Step 2: Run RED**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestHardwareTimingReplayPort,TestRecordingFrameDriverHardwareTiming" test
```

- [ ] **Step 3: Implement the port without gameplay dependencies**

Constructor dependencies are limited to:

```java
HardwareTimingReplayPort(RecordedCompletionAuthority authority)
```

The port must not import or accept `LevelManager`, ring, title-card, object, event, or playable types.

- [ ] **Step 4: Integrate exact frame boundaries**

`RecordingFrameDriver` calls
`TraceHardwareTimingBoundaryObserver.beginRawFrame(rawFrame)` immediately before delegating
the row to `LevelFrameStep`; the observer immediately delegates that call to
`HardwareTimingReplayPort.beginRawFrame(rawFrame)`. At each production dispatch point, `LevelFrameStep` calls only
`HardwareTimingBoundaryObserver.onBoundary(boundary)`.
`TraceHardwareTimingBoundaryObserver.onBoundary()` is the only class that invokes
`HardwareTimingReplayPort.apply(boundary)`; the port reads its private snapshotted latch.

only at the production boundaries represented by the current `TraceExecutionPhase`. `VBLANK_ONLY` calls only VInt service. `ADVANCE_ONLY` and setup-only rows use the same production-owned boundary map and never manufacture an object scan.

Add `installHardwareTimingReplay(...)`, `verifyHardwareTimingSegmentEdges()`, and
`closeHardwareTimingReplayRun()` to `TraceReplayFixture`. `HeadlessTestFixture` implements them through its
`GameplayModeContext` and `HeadlessTestRunner`; `TraceCaptureTool.CaptureFixture` implements
them through its context and driver; and the visual `TraceSessionLauncher` adapter does the
same. Add an explicit `HardwareReadinessAdmissionPolicy` constructor/factory argument to
`GameplayModeContext`. `HeadlessTestFixture.Builder`, `TraceCaptureTool`'s
`HeadlessGameBoot`, and `TraceSessionLauncher` select `RECORDED` before context construction
and before level loading whenever metadata has a timing stream; ordinary gameplay selects
`LIVE`. Context construction begins recorded admission before any submission.
`TraceReplaySessionBootstrap.applyBootstrap()` creates and registers the port after
`attachGameplayManagers()` and installs `TraceHardwareTimingBoundaryObserver`; it does not
change admission policy.
`AbstractTraceReplayTest` reaches this via `HeadlessTestFixture`; `TraceCaptureTool` and
visual replay reach it through their fixture adapters.

Add `beginTraceRow(int traceIndex, int rawFrame)` to `TraceReplayFixture` and
`RecordingFrameDriver`. `AbstractTraceReplayTest` and `TraceCaptureTool` call it from their
outer trace-row loop before any setup-only retry, skip, or gameplay drive. The driver passes
the supplied `rawFrame`—never BK2 index, trace index, or gameplay counter—to
`TraceHardwareTimingBoundaryObserver`; repeated work for one row preserves the same latch.
`HardwareTimingReplaySnapshot` owns the raw-frame latch, so the already-registered port
restores it; the observer has no rewind state. Tests cover nonzero replay
starts, setup-only retry, lag/VBlank rows, run-chain segment starts, and rewind.

Recorded admission spans the entire structural run. Ordinals never reset at a segment
handoff. A submission is exportable only when its production owner set
`exportableAcrossSegment=true`, every current-segment edge is consumed, and the next
segment schedule contains a later completion edge with the same kind, ordinal, and
fingerprint. `TraceReplayFrameClosureDriver` calls `handoffTo(nextSchedule)`, which rejects
every non-exportable pending job and every export without that exact future edge; it does
not end recorded admission for a valid export.
After the final row, `AbstractTraceReplayTest`, `TraceCaptureTool`, and
`TraceSessionLauncher` call `verifyRunComplete()`, which checks edges and unexpected
pending submissions, calls `RecordedCompletionAuthority.endRecordedAdmission()`,
deregisters the port, and removes the
observer in `finally`. Ordinary fixture/context teardown invokes this idempotent final
close. Update direct closure callers in `TestTraceReplayReferenceClosureGuard` and
`TestS3kAizPrefixClosureContract` for the new callbacks/signatures.
`HardwareTimingReplaySnapshot` captures edge cursor and consumed identities; the service
snapshot independently captures pending FIFO order, preparation/decoder snapshots, prepared
outputs, readiness/claim state, and next ordinals.

- [ ] **Step 4a: Extend the architecture authority guard**

Now that the port exists, add the Task 3 assertions that only
`TraceHardwareTimingBoundaryObserver` invokes its boundary method; physics/aux parsers and gameplay owners
cannot import it; and its package cannot import rings, title cards, objects, events,
playables, or `LevelManager`.

- [ ] **Step 5: Run GREEN, guards, and commit**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestHardwareTimingReplayPort,TestRecordingFrameDriverHardwareTiming,TestHardwareTimingAuthorityGuard,TestTraceReplayInvariantGuard,TestTraceReplayStartPositionPolicy" test
```

Commit:

```text
feat(trace): gate prepared hardware completions from timing input
```

---

### Task 6: Port the S3K Kos module queue and resumable work state

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosModuleQueue.java`
- Create: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosModuleDescriptor.java`
- Create: `src/main/java/com/openggf/game/sonic3k/resources/S3kKosModuleSnapshot.java`
- Create: `src/main/java/com/openggf/tools/ResumableKosinskiDecoder.java`
- Create: `src/main/java/com/openggf/tools/DecoderStepResult.java`
- Create: `src/main/java/com/openggf/tools/DecoderSnapshot.java`
- Modify: `src/main/java/com/openggf/tools/KosinskiReader.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: every non-LBZ AIZ/HCZ module-queue owner listed by the Task 1 call-site ledger;
  at minimum inspect and either migrate or prove out-of-session:
  `Sonic3kObjectArtProvider`, `AizIntroArtLoader`, `AizMinibossInstance`,
  `Aiz2BossEndSequenceController`, `Aiz2EndEggCapsuleInstance`,
  `Sonic3kAIZEvents`, `Sonic3kHCZEvents`, `HczEndBossEggCapsuleInstance`,
  `S3kResultsScreenObjectInstance`, and `S3kSignpostInstance`
- Modify: `CHANGELOG.md`
- Test: `src/test/java/com/openggf/tools/TestResumableKosinskiDecoder.java`
- Test: `src/test/java/com/openggf/game/sonic3k/resources/TestS3kKosModuleQueue.java`
- Test: `src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kTitleCardKosQueue.java`

**Interfaces:**
- Consumes: user-ROM source address, module header/count, destination pattern address, S3K pre/post-VSync service points.
- Produces:

```java
public final class ResumableKosinskiDecoder {
    public DecoderStepResult step(int descriptorBudget);
    public boolean complete();
    public byte[] output();
    public DecoderSnapshot snapshot();
    public void restore(DecoderSnapshot snapshot);
}

public final class S3kKosModuleQueue {
    public HardwareWorkHandle queue(
            Rom rom, int sourceAddress, int destinationAddress);
    public void prepareQueuedModuleBeforeVSync();
    public void processModuleQueueAfterObjects();
    public boolean modulesLeft();
}
```

- [ ] **Step 1: Write decoder parity RED tests**

For title/results KosM addresses already used by `Sonic3kTitleCardManager` and `Sonic3kObjectArt`, compare final output byte-for-byte with `KosinskiReader.decompressModuled`. Interrupt and restore after every descriptor boundary and assert identical output.

- [ ] **Step 2: Write queue-order RED tests**

First enumerate every non-LBZ `Queue_Kos_Module` call preceding or overlapping the AIZ/HCZ
capture windows from the Task 1 audit. Cover the full ordered sequence—not only the three
results and four title-card archives—so ordinals cannot drift. Assert FIFO order, canonical
source/destination spans, fingerprints, bookmark restoration, module counts, and that
`modulesLeft()` stays true until final readiness.

- [ ] **Step 3: Run RED**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestResumableKosinskiDecoder,TestS3kKosModuleQueue,TestSonic3kTitleCardKosQueue" test
```

- [ ] **Step 4: Implement resumable decoding from the existing reader**

Extract descriptor parsing and copy operations from `KosinskiReader` without changing its public synchronous results. `ResumableKosinskiDecoder.step` advances only the explicit descriptor budget; no wall-clock checks or threads are permitted.

- [ ] **Step 5: Implement S3K service ordering**

Match the ROM order cited by the design:

```text
Process_Kos_Queue          before Wait_VSync
Process_Sprites            main object scan
Process_Kos_Module_Queue   after the object scan
```

`prepareQueuedModuleBeforeVSync()` is the internal Kos decoder phase for the current module
job; it does not own or expose the separately polled global direct
`KOS_DECOMPRESSION_QUEUE`, which remains outside version 1. The module queue submits
`HardwareWorkSubmission` with exact compressed source span,
destination span, module count, and a `HardwareWorkPreparation` backed by the resumable
decoder. Do not implement the separate direct queue in this task.

- [ ] **Step 5a: Prove complete structural-session submission coverage**

For each Task 1 call-site ledger entry, either migrate the owner to `S3kKosModuleQueue` or
prove with a focused test that it occurs before recorded-admission session creation or after
session closure. The reviewed ledger, file list, and tests are a hard gate: no merely
enumerated in-session submission may remain synchronous, because it would shift ordinals.

- [ ] **Step 6: Replace synchronous title/results readiness**

`Sonic3kTitleCardManager` and `Sonic3kObjectArt` queue ROM work and retain handles. Their existing consumers poll queue/handle readiness. Remove fixed readiness/countdown ownership only where the ROM polls `Kos_modules_left`; retain animation timers that are independently ROM-defined.

- [ ] **Step 7: Run GREEN and compatibility tests**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestResumableKosinskiDecoder,TestS3kKosModuleQueue,TestSonic3kTitleCardKosQueue,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestRewindCoverageGuard" test
```

- [ ] **Step 8: Commit**

Commit:

```text
feat(s3k): model resumable Kos module readiness
```

---

### Task 7: Add native and Lua timing-stream recording

**Files:**
- Modify: `tools/bizhawk/s3k_trace_recorder.lua`
- Modify: `tools/bizhawk/s3k_complete_run_recorder.lua`
- Create: `tools/bizhawk-headless/src/Recording/HardwareTimingEventEngine.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KTraceCaptureRunner.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KCompleteRunCaptureRunner.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KTraceMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/src/Recording/S3KCompleteRunMetadataWriter.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`
- Create: `tools/bizhawk-headless/tests/HardwareTimingEventEngineTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KTraceDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KCompleteRunDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S3KRunModeDifferentialTests.cs`

**Interfaces:**
- Consumes: polled S3K Kos module queue/busy RAM state and canonical submission fields.
- Produces: byte-identical `hardware_timing.jsonl`, metadata `hardware_timing_schema: 1`, `trace_schema: 7`, versions `6.37-s3k` and `6.37-s3k-completerun`. Version 6.35 corrects immediate Kosinski descriptor refill and excludes synchronous initial-load completions from external authority while retaining their ordinals. Version 6.36 introduced held-counter VInt admission. Version 6.37 distinguishes genuine lag/loading rows from the ROM's `loc_62CC` held-counter title-card loop by arming only from its fixed SST parent and retaining the loop through its raw title/Nemesis exit predicates.

- [ ] **Step 1: Write C# RED tests**

Register tests proving:

- pending-to-complete emits exactly one event;
- repeated zero emits nothing;
- a second lifecycle increments ordinal;
- fingerprint canonicalization matches Java golden vectors;
- ordering and LF termination are exact;
- routes without eligible work publish a zero-byte stream;
- metadata/file publication is atomic and no-replace safe.
- `S3KTraceDifferential native capture matches canonical AIZ timing stream`;
- `S3KCompleteRunDifferential native capture matches canonical AIZ timing stream`;
- `S3KCompleteRunDifferential native capture matches canonical HCZ timing stream`.

- [ ] **Step 2: Run RED**

Run:

```bash
tools/bizhawk-headless/test.sh --filter HardwareTiming
```

- [ ] **Step 2a: Capture the pre-edit Lua performance baseline**

Before modifying either recorder, run:

```bash
mkdir -p target/hardware-timing-perf/baseline
env OGGF_WORKDIR="$PWD/target/hardware-timing-perf/baseline" \
  OGGF_TRACE_OUTPUT_DIR="$PWD/target/hardware-timing-perf/baseline/output" \
  OGGF_S3K_TRACE_PROFILE=aiz_end_to_end OGGF_TRACE_STOP_FRAME=10000 \
  /usr/bin/time -p -o target/hardware-timing-perf/baseline.time \
  tools/bizhawk/run_bizhawk_lua.sh tools/bizhawk/s3k_trace_recorder.lua \
  src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2 s3k.gen
```

- [ ] **Step 3: Implement the native event engine**

Use C# 7.x only. Hand-add the new `.cs` file to both project files. Encode the exact
Task 1-audited `Kos_modules_left`, `Kos_decomp_queue_count`, queue-source, queue-destination,
and module-header RAM addresses as named `S3KRam` constants with disassembly citations.
At each audited boundary, poll a tuple of pending bit/count plus canonical submission
fields; emit once only on a nonzero/busy to zero/not-busy transition for the same active
submission. Reset transition state only after observing a later eligible submission.
Do not use broad memory-write/execute hooks.

- [ ] **Step 4: Implement the Lua reference path**

Reuse the canonical recorder template behavior: call `client.speedmode(6400)`,
`client.invisibleemulation(true)`, and disable sound before the frame loop; preserve the
`HEADLESS_VISIBLE` opt-out; arm any optional diagnostic hook only after the semantic
zone/act gate and unregister it on stage exit. The timing stream is frame-polled and uses no
hook. Reuse the shared `C` table/module pattern so neither recorder exceeds Lua's 200-local
limit. Stop at movie end or the existing bounded safety limit and always close streams and
exit BizHawk.

- [ ] **Step 5: Extend publication and compression manifests**

Publish `hardware_timing.jsonl` alongside metadata/physics/aux. It remains uncompressed because it is sparse and schema-v1 byte parity is defined over the plain UTF-8 file.

- [ ] **Step 6: Run exact native/Lua differential and performance gates**

Run:

```bash
luac -p tools/bizhawk/s3k_trace_recorder.lua
luac -p tools/bizhawk/s3k_complete_run_recorder.lua
tools/bizhawk-headless/test.sh --filter "HardwareTiming"
tools/bizhawk-headless/test.sh --filter "canonical AIZ timing stream"
tools/bizhawk-headless/test.sh --filter "canonical HCZ timing stream"
mkdir -p target/hardware-timing-perf/candidate
env OGGF_WORKDIR="$PWD/target/hardware-timing-perf/candidate" \
  OGGF_TRACE_OUTPUT_DIR="$PWD/target/hardware-timing-perf/candidate/output" \
  OGGF_S3K_TRACE_PROFILE=aiz_end_to_end OGGF_TRACE_STOP_FRAME=10000 \
  /usr/bin/time -p -o target/hardware-timing-perf/candidate.time \
  tools/bizhawk/run_bizhawk_lua.sh tools/bizhawk/s3k_trace_recorder.lua \
  src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2 s3k.gen
```

The baseline from Step 2a and candidate must run on the same host. Both use the canonical
fast launcher and stop inside AIZ, so neither
enters or inspects LBZ. Compare the `real` values and fail the gate for a regression greater
than 5%. The native AIZ and HCZ filters are literal and must not select any other case.
Expected:

```text
metadata versions differ only by the exact approved bumps
physics.csv byte-identical to prior capture
aux_state.jsonl byte-identical to prior capture
hardware_timing.jsonl native == Lua byte-for-byte
```

- [ ] **Step 7: Commit**

Commit:

```text
feat(trace-recorder): capture hardware completion timing
```

---

### Task 8: Publish version-1 AIZ and HCZ timing fixtures

**Files:**
- Modify: `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/metadata.json`
- Create: `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/hardware_timing.jsonl`
- Modify: `src/test/resources/traces/s3k/aiz_completerun/metadata.json`
- Create: `src/test/resources/traces/s3k/aiz_completerun/hardware_timing.jsonl`
- Modify: `src/test/resources/traces/s3k/hcz_completerun/metadata.json`
- Create: `src/test/resources/traces/s3k/hcz_completerun/hardware_timing.jsonl`
- Test: `src/test/java/com/openggf/trace/timing/TestCommittedHardwareTimingFixtures.java`

**Interfaces:**
- Consumes: output from the Task 7 native recorder, whose correctness was
  established before publication by the ROM/disassembly-backed completion
  contract, behavioral/unit and cross-language vector tests, bounded real
  captures, range/inventory validation, and independent implementation review.
- Produces: immutable version-1 timing fixtures; physics and aux payloads remain unchanged.

- [x] **Step 1: Obtain explicit fixture-publication approval**

Before replacing committed metadata or adding streams, present SHA-256 and byte-length deltas for metadata, physics, aux, and timing files. Proceed only after explicit approval.

On 2026-07-27 the user authorized the native-authority policy and subsequently
approved the exact candidate-byte table for the three metadata and timing
streams. The Task 7 candidates were eligible for that review because Task 7
established their recorder through reviewed native implementation, real capture
validation, the ROM/disassembly completion contract, behavioral/unit tests,
cross-language fingerprint vectors, synthetic results-mode `$48` behavior, and
emitted-frame range and event-inventory checks. Lua parity was additional
corroboration before the final results-mode correction, not authority for
publication.

- [x] **Step 2: Write the fixture RED**

Assert each version-1 fixture has:

```text
trace_schema == 7
hardware_timing_schema == 1
expected recorder version
canonical stream ordering
none of these three standalone fixtures is owned by a run manifest
```

Also assert physics and aux SHA-256 hashes equal the pre-publication fixtures.
Record and pin the native candidate's timing digests, byte lengths, event counts,
canonical ordering, ranges, and semantic inventory as literals.
The test must not invoke the native recorder or derive expected values
dynamically from a capture invocation.

- [x] **Step 3: Stage recorder output exactly**

Copy only the already approved, gated native metadata and timing stream
byte-for-byte. Never edit an event or timestamp by hand. Do not add uncompressed
`physics*.csv` or `aux_state*.jsonl`.

- [x] **Step 4: Run fixture and compression guards**

Run:

```bash
mvn -q -Dmse=relaxed "-Dtest=TestCommittedHardwareTimingFixtures,TestTraceFixtureCompressionGuard,TestTraceReplayReferenceClosureGuard" test
```

- [ ] **Step 5: Commit**

Commit:

```text
test(trace): publish S3K hardware timing fixtures
```

---

### Task 9: Prove AIZ/HCZ behavior and rewind

**Files:**
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kHardwareTimingReplay.java`
- Test: `src/test/java/com/openggf/game/sonic3k/resources/TestS3kKosTimingRewindIntegration.java`
- Modify: `docs/status/trace-frontier-log.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md` to remove or rewrite the superseded fixed-count
  readiness discrepancy

**Interfaces:**
- Consumes: production Kos queue, timing stream, strict trace comparator.
- Produces: verified AIZ/HCZ completion timing through ordinary title/results consumers.

- [ ] **Step 1: Add behavioral timing tests**

Assert:

- engine submission fingerprint/ordinal matches the fixture edge;
- AIZ and HCZ release at their independently recorded boundaries;
- the title/results consumer, not the replay port, clears rings;
- deleting, duplicating, reordering, shifting, or fingerprint-corrupting an edge fails structurally or by strict gameplay comparison as specified;
- no row is skipped from comparison.

- [ ] **Step 2: Add live scheduler tests**

Drive the production scheduler without a replay schedule and assert its completion boundaries match the recorded AIZ/HCZ service windows. This prevents trace green from masking live timing drift.

- [ ] **Step 3: Add rewind epoch tests**

Capture immediately before, on, and after completion in live and recorded modes. Restore and verify identical FIFO, decoder, title readiness, edge cursor, pattern publication, and ring outcome.

- [ ] **Step 4: Run focused trace tests**

Discover the existing convenience link and verify the locked-on ROM before use:

```bash
S3K_ROM_PATH="$PWD/s3k.gen"
test -f "$S3K_ROM_PATH"
test "$(sha1sum "$S3K_ROM_PATH" | cut -d' ' -f1)" = \
  "cfbf98c36c776677290a872547ac47c53d2761d6"
```

Then construct the command literally without LBZ:

```bash
mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=false \
  "-Ds3k.rom.path=$S3K_ROM_PATH" \
  "-Dtest=TestS3kAizTraceReplay#replayMatchesTrace,TestS3kAizCompleteRunTraceReplay#replayMatchesTrace,TestS3kHczCompleteRunTraceReplay#replayMatchesTrace,TestS3kHardwareTimingReplay,TestS3kKosTimingRewindIntegration" \
  test
```

Expected: the title/ring timing frontiers are removed through ordinary consumer behavior; any later unrelated frontier is recorded exactly.

- [ ] **Step 5: Run required S3K guards**

Run:

```bash
mvn -q -Dmse=relaxed \
  "-Ds3k.rom.path=$S3K_ROM_PATH" \
  "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestHardwareTimingAuthorityGuard" \
  test
```

- [ ] **Step 6: Update documentation and commit**

Record commands, composed commit, pass/fail, errors, and first frame/field in `docs/status/trace-frontier-log.md`. Update the S3K discrepancy ledger only to remove superseded fixed-count/Kos readiness claims.

Commit:

```text
fix(trace): align S3K title readiness to hardware timing
```

---

### Task 10: Complete non-LBZ compatibility validation

**Files:**
- Create: `docs/architecture/validation/2026-07-27-hardware-timing-replay-validation.md`
- Modify: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: composed implementation branch.
- Produces: final compatibility report and authoritative non-LBZ frontier inventory.

- [ ] **Step 1: Build an explicit allowlist**

Enumerate concrete `*TraceReplay` classes, remove abstract/guard classes, and remove every LBZ class before constructing Maven arguments:

```bash
mkdir -p target/trace-validation
rg -l '(^|[[:space:]])(public[[:space:]]+)?(final[[:space:]]+)?class Test.*TraceReplay' src/test/java --glob '*.java' \
  | rg -v '/Abstract|Guard|Lbz|LBZ' \
  | sed -E 's#src/test/java/##; s#/#.#g; s#\.java$##' \
  | sort > target/trace-validation/non-lbz-classes.txt
if rg -i 'lbz' target/trace-validation/non-lbz-classes.txt; then
  exit 1
fi
```

Copy the resulting literal class list into the validation report before running it. Apply
the same literal no-LBZ list to every native recorder/run-mode gate; never use an
unrestricted `--game s3k` or wildcard differential.

- [ ] **Step 2: Run all S1 and S2 replay classes**

Verify the existing convenience links and use separate forks:

```bash
S1_ROM_PATH="$PWD/s1.gen"
S2_ROM_PATH="$PWD/s2.gen"
test "$(sha1sum "$S1_ROM_PATH" | cut -d' ' -f1)" = \
  "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b"
test "$(sha1sum "$S2_ROM_PATH" | cut -d' ' -f1)" = \
  "8bca5dcef1af3e00098666fd892dc1c2a76333f9"
mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=false \
  "-Ds1.rom.path=$S1_ROM_PATH" \
  "-Ds2.rom.path=$S2_ROM_PATH" \
  "-Dtest=$(rg 'com\.openggf\.tests\.trace\.(s1|s2)\.' target/trace-validation/non-lbz-classes.txt | paste -sd,)" test
```

Expected: no regression from the existing green inventory.

- [ ] **Step 3: Run the explicit non-LBZ S3K allowlist**

Run:

```bash
S3K_ROM_PATH="$PWD/s3k.gen"
test "$(sha1sum "$S3K_ROM_PATH" | cut -d' ' -f1)" = \
  "cfbf98c36c776677290a872547ac47c53d2761d6"
mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=false \
  "-Ds3k.rom.path=$S3K_ROM_PATH" \
  "-Dtest=$(rg 'com\.openggf\.tests\.trace\.s3k\.' target/trace-validation/non-lbz-classes.txt | paste -sd,)" test
```

Classify each result by its own Surefire report and trace JSON, not Maven Silent Extension aggregate totals.

- [ ] **Step 4: Run architecture, fixture, rewind, and recorder gates**

Run:

```bash
mvn -q -Dmse=relaxed \
  "-Dtest=TestArchitecturalSourceGuard,TestHardwareTimingAuthorityGuard,TestTraceFixtureCompressionGuard,TestTraceReplayReferenceClosureGuard,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard" \
  test
tools/bizhawk-headless/test.sh --filter "HardwareTiming"
tools/bizhawk-headless/test.sh --filter "canonical AIZ timing stream"
tools/bizhawk-headless/test.sh --filter "canonical HCZ timing stream"
```

The three native filters are literal non-LBZ gates. Record any environmental native
extraction or Mockito attach failure separately and rerun it in isolation when required.

- [ ] **Step 5: Write validation evidence**

The report must include:

- final commit and ROM hashes;
- exact commands and allowlists;
- S1/S2 green counts;
- every non-LBZ S3K frontier;
- AIZ/HCZ before/after completion edges;
- live scheduler conformance;
- rewind results;
- fixture hashes and recorder parity; and
- explicit confirmation that LBZ was not inspected, run, changed, or documented as trace work.

- [ ] **Step 6: Commit**

Commit:

```text
docs(trace): validate hardware timing replay contract
```

---

## Plan completion gate

Implementation is complete only when:

- every task commit received independent spec-compliance and code-quality review;
- all Critical and Important findings were fixed and re-reviewed;
- the dedicated timing stream is the sole recurring trace-to-engine authority;
- AIZ/HCZ timing advances without comparison suppression or gameplay hydration;
- live scheduling remains ROM-derived and independently tested;
- rewind and recorder parity are green;
- S1/S2 remain green;
- the final explicit non-LBZ S3K sweep is documented; and
- no unreviewed implementation commit is merged or pushed.
