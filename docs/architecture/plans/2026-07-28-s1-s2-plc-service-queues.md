# Sonic 1 and Sonic 2 PLC Service Queues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove whether structural interrupt/lag state can reproduce S1/S2 PLC readiness, then—only if that evidence gate passes—add ROM-derived logical queues so gameplay consumers observe the original FIFO timing without trace hydration.

**Architecture:** Diagnostic ROM captures and a standalone structural predictor first test the provisional native-service classification, including lag, handler selection, HBlank deferral, preparation bubbles, and the retail interrupt race. If it passes, a shared `NemesisPlcServiceQueue` owns game-neutral FIFO/progress mechanics while game façades own parsing, lifecycle call sites, and three/six/nine-pattern budgets; if it fails, implementation stops for an amended authority design.

**Tech Stack:** Java 21, JUnit 5, Maven, existing `PlcParser`, ROM loaders, game module service graph, level lifecycle, trace replay, and rewind framework.

## Global Constraints

- Implement after the S3K direct Kos decompression queue worktree is integrated, or rebase the implementation worktree onto its integrated commit.
- Task 1 is a hard evidence gate. Tasks 2-9 must not begin until its independent review concludes `NATIVE_MODEL_APPROVED`.
- If Task 1 concludes `NATIVE_MODEL_REJECTED`, amend both design and plan, repeat their review loops, and do not infer permission to add trace authority.
- Do not change S3K Kos module or direct Kos decompression semantics in this scope.
- Runtime PLC data and pattern counts come only from the user-supplied ROM.
- Trace physics and auxiliary data remain comparison-only; do not add `PLC_QUEUE` to `HardwareWorkKind` or `hardware_timing.jsonl`.
- Shared runtime code contains no game-name, game-ID, zone, route, fixture, or frame carve-out.
- Objects use injected services and never call `getInstance()`.
- Preserve ROM call order: VBlank service, admitted loop, queue preparation, then consumer observation at its actual owner.
- Keep rendering eager; renderer/cache readiness is never gameplay readiness.
- Every queue and façade state field is rewind-covered.
- Use JDK 21 as reported by `mvn -v`.
- Tests are JUnit 5 only.
- Do not use `--no-verify`.

---

### Task 1: Prove or reject native deterministic PLC service

**Files:**
- Modify: `docs/architecture/audits/2026-07-27-s1-hardware-timing-inventory.md`
- Modify: `docs/architecture/audits/2026-07-27-s2-hardware-timing-inventory.md`
- Create: `docs/architecture/research/trace/2026-07-28-s1-s2-plc-readiness-evidence.md`
- Create: `tools/bizhawk/diagnostics/s1_plc_timing_probe.lua`
- Create: `tools/bizhawk/diagnostics/s2_plc_timing_probe.lua`
- Create: `tools/bizhawk/diagnostics/s1_plc_timing_probe.env.sh`
- Create: `tools/bizhawk/diagnostics/s2_plc_timing_probe.env.sh`
- Create: `tools/bizhawk/diagnostics/plc_timing_probe_contract_test.lua`
- Create: `src/main/java/com/openggf/tools/PlcTimingEvidenceTool.java`
- Create: `src/test/java/com/openggf/tools/TestPlcTimingEvidenceTool.java`
- Create: `docs/architecture/research/trace/assets/s1-s2-plc-evidence-vectors.json.gz`
- Reference: `docs/s1disasm/sonic.asm`
- Reference: `docs/s2disasm/s2.asm`
- Reference: `docs/architecture/designs/2026-07-28-s1-s2-plc-service-queues.md`

**Interfaces:**
- Consumes: S1/S2 ROM PLC RAM, selected interrupt handler, raw lag/admission state, and diagnostic-only capture events.
- Produces: rerunnable compact evidence plus one reviewed disposition:
  `NATIVE_MODEL_APPROVED`, `NATIVE_MODEL_REJECTED`, or `EVIDENCE_INCOMPLETE`.

- [ ] **Step 1: Verify the integration baseline**

Run:

```bash
git status --short --branch
git log -1 --oneline
git log --all --oneline --grep='Kos decompression queue' -5
mvn -v
```

Expected: the implementation worktree is based on the integrated direct Kos
decompression commit, the main workspace branch is unchanged, and Maven reports
Java 21.

- [ ] **Step 2: Pin RAM fields and structural boundaries**

Run:

```bash
rg -n "NewPLC|AddPLC|ClearPLC|RunPLC|ProcessPLC_[39]Tiles|QuickPLC" \
  docs/s1disasm/sonic.asm docs/s1disasm/_incObj
```

For every production caller, record PLC ID, append/replace/clear operation,
containing lifecycle, `RunPLC` point, selected interrupt handler, service
budget, HBlank deferral, and consumer. Pin addresses for queue head,
destination, patterns left, per-frame patterns left, VInt routine, HInt
deferral flag, game mode, and gameplay-frame counter. Resolve queue capacity
and retail overflow behavior.

- [ ] **Step 3: Add isolated diagnostic probes**

Run:

```bash
rg -n "LoadPLC|LoadPLC2|ClearPLC|RunPLC_RAM|ProcessDPLC2?|Plc_Buffer" \
  docs/s2disasm/s2.asm docs/s2disasm/*.asm
```

The native recorder host does not expose execute hooks, so do not modify the
canonical native recorder or its schema. Create isolated Lua diagnostic probes
using `event.onmemoryexecute` at the verified PLC submission, preparation,
service, pop, and consumer-poll addresses. Use `event.onframeend` to attach raw
frame, RAM state, selected handler, lag classification, and HBlank deferral.

Emit compact scratch-only events:

```text
plc_submission
plc_prepare_begin
plc_prepare_end
plc_service
plc_pop
plc_empty
plc_consumer_observation
plc_frame_state
plc_vint_state
plc_hblank_state
```

Each record contains raw frame, game mode, selected interrupt handler, lag
classification, HBlank deferral flag, queue head source/destination, patterns
left before/after, and queue-slot count. It contains no engine mutation
payload. The probes write to an explicitly supplied temporary output path and
refuse to overwrite it. They are diagnostic tools, not fixture-publication
authorities.

- [ ] **Step 4: Smoke-test probe derivation**

Run short captures and require preparation begin/end around one `RunPLC`,
service events only at the hooked service routine, a pop at the queue-shift
routine, and consumer observations only at their verified hook addresses.

Run:

```bash
plc_probe_dir=$(mktemp -d)
export OGGF_PLC_PROBE_OUTPUT="$plc_probe_dir/s1.jsonl"
source tools/bizhawk/diagnostics/s1_plc_timing_probe.env.sh
tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/diagnostics/s1_plc_timing_probe.lua \
  src/test/resources/traces/s1/_movies/s1-complete-run.bk2 s1.gen
export OGGF_PLC_PROBE_OUTPUT="$plc_probe_dir/s2.jsonl"
source tools/bizhawk/diagnostics/s2_plc_timing_probe.env.sh
tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/diagnostics/s2_plc_timing_probe.lua \
  src/test/resources/traces/s2/arz2/s2-lvl-select-ARZ.bk2 s2.gen
```

Expected: each output is nonempty, ordered by raw frame and within-frame hook
sequence, and contains no event inferred solely from a frame-to-frame RAM
delta.

- [ ] **Step 5: Capture varied-history ROM evidence**

Discover ROMs and existing movies. Capture at least:

```text
S1: level title card, Final Zone submission through boss release,
    varied level results, special-stage results; Game Over if an authentic
    corpus route exists
S2: level title card, ARZ submission through boss initialization,
    varied level results, special-stage results; Game Over if an authentic
    corpus route exists
```

Run one identical-input pair as a recorder-stability smoke test. For the model
gate, capture materially distinct execution instances that reach equivalent
consumers after different queue histories. Individual-level movies and
different lifecycle instances inside a multi-level/complete-run movie both
qualify when their submission, service, lag, or HBlank histories differ.
Keep raw outputs under a temporary directory; do not install or commit them as
fixtures.

If a lifecycle is absent from existing movies, inspect the complete-run movie
first and extract the relevant playback interval without changing its input.
If no existing movie reaches it, record a diagnostic BK2/save-state route using
the repository's BizHawk recording runbook and keep it as scratch. Do not
manufacture duplicate inputs solely to satisfy a per-consumer count. Record
consumer coverage by instance, movie, submission history, lag/HBlank exposure,
and readiness latency. A unique consumer may be single-instance covered; a
common consumer family without any varied-history comparison leaves the result
`EVIDENCE_INCOMPLETE` and Tasks 2-9 blocked.

- [ ] **Step 6: Write the standalone structural predictor and analyzer**

The test-local predictor consumes ROM-derived entries plus only:

```java
record StructuralRow(
        int rawFrame,
        int gameMode,
        int interruptHandler,
        boolean lag,
        boolean hblankDeferred,
        List<Submission> submissions,
        boolean runPlcCalled,
        List<ConsumerPoll> consumerPolls,
        int withinFrameOrder,
        StructuralPhase phase) {}

record ConsumerPoll(
        String consumerId,
        int withinFrameOrder) {}
```

It must not consume captured `patterns_left`, service, pop, empty, or consumer
events as inputs. Its output is:

```java
record PredictedEdge(
        int rawFrame,
        EdgeKind kind,
        int sourceAddress,
        int remainingPatterns) {}
```

`ConsumerPoll` supplies only structural execution identity/order; the captured
busy/empty result remains oracle-only. Compare predicted preparation, service,
pop, empty, and poll results against diagnostic output.

Build an ordered structural/action timeline from `plc_frame_state`,
`plc_vint_state`, and `plc_hblank_state`, keyed by
`(raw_frame, within_frame_order)`. A passive frame-end row never creates a
service opportunity. A VInt row creates the selected-handler segment; a
reviewed HBlank row may defer and complete that same open segment. Service,
pop, empty, and consumer oracle events must never create, select, or
reclassify structural segments.

Implement and run:

```bash
mvn exec:java \
  "-Dexec.mainClass=com.openggf.tools.PlcTimingEvidenceTool" \
  "-Dexec.args=--game s1 --rom s1.gen --probe /tmp/s1-plc.jsonl --out /tmp/s1-vector.json"
```

The tool validates raw probe ordering, derives structural rows and observed
edge oracles, runs the predictor, prints the first mismatch, and writes a
compact vector. Run it for every distinct capture. Require deterministic
serialization for the identical-input smoke pair, but compare distinct runs by
predictor match and diversity rather than byte equality. Merge the reviewed
vectors, gzip them reproducibly, and stage
`docs/architecture/research/trace/assets/s1-s2-plc-evidence-vectors.json.gz`.

Unit tests load that committed vector and mutate one handler, lag row, HBlank
deferral, consumer order, preparation call, or budget. Each mutation must
produce a mismatch, making the evidence gate rerunnable without committing raw
capture payloads.

- [ ] **Step 7: Resolve the retail preparation race**

Use preparation begin/end events to determine whether any covered route accepts
an interrupt while `PatternsLeft` is published but decoder state is incomplete.
Record one of:

```text
NOT_OBSERVED_ON_COVERED_ROUTES
OBSERVED_AND_CYCLE_MODEL_REQUIRED
OBSERVED_WITH_UNPREDICTABLE_HEALTHY_COMPLETION
```

Do not silently model the retail ROM as the disassembly's `FixBugs` variant.

- [ ] **Step 8: Decide the gate**

Write the evidence report with exact commands, ROM hashes, recorder commit,
routes, repeated-output hashes, first mismatch if any, race disposition, and
one conclusion:

```text
NATIVE_MODEL_APPROVED
NATIVE_MODEL_REJECTED
EVIDENCE_INCOMPLETE
```

Approval requires zero unexplained predicted-edge mismatch across all captured
lifecycles, varied-history coverage for each available common consumer family,
and authentic coverage for available unique consumers. An unavailable unique
consumer does not block approval when disassembly review proves it introduces
no queue mechanism outside the covered submission, service, and readiness
contracts. Delegate independent review. If
rejected, amend and re-review the design and plan before continuing. If
incomplete, record the missing acquisition dependency and keep Tasks 2-9
blocked. If approved, Tasks 2-9 may proceed.

- [ ] **Step 9: Commit diagnostic tooling and evidence**

Stage only Task 1 files. Commit:

```text
test(plc): validate S1 and S2 service determinism
```

Use `Changelog: n/a: diagnostic recorder and architecture evidence only`.

---

### Task 2: Implement the shared logical queue kernel

**Files:**
- Create: `src/main/java/com/openggf/level/resources/NemesisPlcPatternCounts.java`
- Create: `src/main/java/com/openggf/level/resources/NemesisPlcServiceQueue.java`
- Create: `src/main/java/com/openggf/game/rewind/snapshot/NemesisPlcQueueSnapshot.java`
- Test: `src/test/java/com/openggf/level/resources/TestNemesisPlcPatternCounts.java`
- Test: `src/test/java/com/openggf/level/resources/TestNemesisPlcServiceQueue.java`
- Create: `src/test/java/com/openggf/level/resources/TestNemesisPlcRomVectors.java`

**Interfaces:**
- Consumes: `Rom`, `PlcParser.PlcDefinition`, `PlcParser.PlcEntry`, `Pattern.PATTERN_SIZE_IN_ROM`.
- Produces:

```java
public final class NemesisPlcPatternCounts {
    public static List<Integer> derive(Rom rom, PlcDefinition definition)
            throws IOException;
}

public final class NemesisPlcServiceQueue {
    public void replaceQueued(
            PlcDefinition definition, List<Integer> patternCounts);
    public void append(PlcDefinition definition, List<Integer> patternCounts);
    public void clearQueued();
    public void prepareHead();
    public void servicePatterns(int patternBudget);
    public boolean isBusy();
    public int queuedEntryCount();
    public NemesisPlcQueueSnapshot capture();
    public void restore(NemesisPlcQueueSnapshot snapshot);
}

public record NemesisPlcQueueSnapshot(
        Entry activeEntry,
        List<Entry> queuedEntries) {
    public record Entry(
            int sourceAddress,
            int destinationTile,
            int totalPatterns,
            int remainingPatterns) {}
}
```

- [ ] **Step 1: Write queue RED tests**

Proceed only when the Task 1 evidence report says
`NATIVE_MODEL_APPROVED` and its independent review has no blocking findings.

Add named tests:

```java
@Test void appendPreservesFifoAndDuplicateEntries()
@Test void replaceQueuedRequiresIdleDecoder()
@Test void clearQueuedRequiresIdleDecoder()
@Test void prepareHeadConsumesNoPatterns()
@Test void serviceRequiresPreparedHead()
@Test void serviceHonorsThreeSixAndNinePatternBudgets()
@Test void completingEntryLeavesNextHeadUnprepared()
@Test void busyCoversPreparedAndUnpreparedEntries()
@Test void invalidCountsDoNotMutateQueue()
@Test void snapshotRoundTripsPartialHeadAndPendingTail()
@Test void restoreRejectsImpossibleProgress()
```

Use synthetic definitions and exact remaining-count assertions. In
`invalidCountsDoNotMutateQueue`, cover cardinality mismatch, zero/negative
counts, and a remaining count greater than total during restore. Task 1 must
prove the idle precondition; if it does not, stop and amend the design instead
of implementing this kernel.

- [ ] **Step 2: Write pattern-count RED tests**

Use a test ROM containing one known Nemesis stream and assert:

```java
assertEquals(
        raw.length / Pattern.PATTERN_SIZE_IN_ROM,
        NemesisPlcPatternCounts.derive(rom, definition).getFirst());
```

Also assert that a decompressed byte length not divisible by
`Pattern.PATTERN_SIZE_IN_ROM` fails before returning counts.

- [ ] **Step 3: Run RED**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=TestNemesisPlcPatternCounts,TestNemesisPlcServiceQueue" test
```

Expected: compilation failure for the missing production types.

- [ ] **Step 4: Implement count derivation**

For every entry, call `PlcParser.decompressEntryRaw(rom, entry)`, validate the
length, and return an immutable count list. Synchronize ROM channel access
through the existing parser/decompressor boundary; do not introduce a second
decoder.

- [ ] **Step 5: Implement queue mutation and service**

Use a separate private active progress entry plus an `ArrayDeque` of queued
descriptors. Validate an entire submission before mutating either.
`servicePatterns` rejects nonpositive budgets and returns without effect when
there is no active entry. When the active entry reaches zero, clear it; do not
spend leftover budget on or implicitly prepare the next descriptor.

- [ ] **Step 6: Implement immutable rewind snapshots**

Capture the optional active entry separately from immutable queued descriptor
records in FIFO order. Restore validates all entries before replacing live
state.

- [ ] **Step 7: Run GREEN**

Run:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=TestNemesisPlcPatternCounts,TestNemesisPlcServiceQueue,TestNemesisPlcRomVectors" test
```

Expected: all tests pass.

- [ ] **Step 8: Commit the kernel**

Commit:

```text
feat(plc): add deterministic Nemesis service queue
```

Stage `CHANGELOG.md` and add a concise entry describing logical S1/S2 PLC
readiness; use `Changelog: updated`.

---

### Task 3: Add game-owned S1 and S2 PLC services

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/resources/Sonic1PlcService.java`
- Create: `src/main/java/com/openggf/game/sonic2/resources/Sonic2PlcService.java`
- Create: `src/main/java/com/openggf/game/resources/PlcVBlankService.java`
- Modify: S1 game module/service registration file found by `rg -n "registerGameService|GameService" src/main/java/com/openggf/game/sonic1`
- Modify: S2 game module/service registration file found by `rg -n "registerGameService|GameService" src/main/java/com/openggf/game/sonic2`
- Test: `src/test/java/com/openggf/game/sonic1/resources/TestSonic1PlcService.java`
- Test: `src/test/java/com/openggf/game/sonic2/resources/TestSonic2PlcService.java`

**Interfaces:**
- Consumes: `NemesisPlcServiceQueue`, each game's ROM and PLC table address.
- Produces:

```java
public interface PlcVBlankService {
    void serviceLevelVBlank();
}

public final class Sonic1PlcService implements PlcVBlankService {
    public void replaceQueued(int plcId);
    public void append(int plcId);
    public void clearQueued();
    public void prepare();
    public void serviceLevelVBlank();       // three patterns
    public void serviceFastVBlank();        // nine patterns
    public boolean isBusy();
}

public final class Sonic2PlcService implements PlcVBlankService {
    public void replaceQueued(int plcId);
    public void append(int plcId);
    public void clearQueued();
    public void prepare();
    public void serviceLevelVBlank();       // three patterns
    public void serviceNormalVBlank();      // six patterns
    public boolean isBusy();
}
```

Methods that parse/decompress ROM data either declare `IOException` or translate
it once at the existing game resource boundary with game/PLC context.

- [ ] **Step 1: Write S1 façade RED tests**

Test that S1 parses from `Sonic1Constants.ART_LOAD_CUES_ADDR`, maps append,
queued replacement, and queued clear distinctly, exposes whole-buffer busy,
and advances exactly three or nine patterns. Assert `prepare()` is required
between entries and clear/replace rejects a non-idle decoder.

- [ ] **Step 2: Write S2 façade RED tests**

Test the same behavior against `Sonic2Constants.ART_LOAD_CUES_ADDR`, using
three- and six-pattern budgets. Add a capacity test using the exact behavior
pinned in Task 1.

- [ ] **Step 3: Run RED**

Run:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=TestSonic1PlcService,TestSonic2PlcService" test
```

Expected: compilation failure for the services and lifecycle interface.

- [ ] **Step 4: Implement the façades**

Parse with `PlcParser.parse`, derive counts with
`NemesisPlcPatternCounts.derive`, validate queue capacity according to Task 1,
then delegate to the kernel. Do not register render art from these low-level
methods; Task 5 composes logical submission with the existing art provider.

- [ ] **Step 5: Register session-owned services**

Use each game module's existing service graph. Do not add static mutable
singletons. Add reset/close behavior through the established session lifecycle.

- [ ] **Step 6: Run GREEN and ownership guards**

Run:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=TestSonic1PlcService,TestSonic2PlcService,TestStaticStateRewindCoverageGuard" test
```

Expected: all tests pass.

- [ ] **Step 7: Commit the game services**

Commit:

```text
feat(plc): add S1 and S2 queue services
```

Use `Changelog: updated` and stage the Task 2 changelog entry if the kernel and
façades land in separate commits with one shared player-facing entry.

---

### Task 4: Integrate exact VBlank preparation and service ordering

Commit `37beb532d` established the ordinary-level service seam and lag-row
exclusion, but it is only a partial Task 4 result. It does not select the
title/title-card/fade/results/credits/special/pause budgets or call
`RunPLC` / `RunPLC_RAM` at the phase-owned preparation points. Complete the
task through the semantic adapter below; do not stack another independent
ordinary-level hook on top of the partial seam.

**Files:**
- Create: `src/main/java/com/openggf/game/resources/PlcLifecyclePhase.java`
- Create: `src/main/java/com/openggf/game/resources/PlcLifecycleService.java`
- Create: `src/main/java/com/openggf/game/resources/PlcFrameLifecycleCoordinator.java`
- Create: `src/main/java/com/openggf/game/resources/NativeFadeLifecycle.java`
- Create: `src/main/java/com/openggf/game/resources/NativeFadeLifecycleAware.java`
- Remove or fold into the new port: `src/main/java/com/openggf/game/resources/PlcVBlankService.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/LevelFrameStep.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/game/EndingProvider.java`
- Modify: `src/main/java/com/openggf/game/SpecialStageEntryPresentationController.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- Modify: `src/main/java/com/openggf/game/mode/MenuScreenModeController.java` only if a callback seam is needed to keep title/level-select service around its provider update
- Modify: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindStepper.java`
- Modify: `src/main/java/com/openggf/game/rewind/SpecialStageStepper.java`
- Modify: `src/main/java/com/openggf/game/sonic1/resources/Sonic1PlcService.java`
- Modify: `src/main/java/com/openggf/game/sonic2/resources/Sonic2PlcService.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic1/credits/Sonic1CreditsManager.java`
- Modify: `src/main/java/com/openggf/game/sonic1/credits/Sonic1EndingProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1ResultsScreenObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/ResultsScreenObjectInstance.java`
- Audit/unmarked: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java`
- Audit/unmarked: `src/main/java/com/openggf/game/sonic1/objects/Sonic1EndingSonicObjectInstance.java`
- Test: `src/test/java/com/openggf/TestPlcVBlankOrdering.java`
- Test: `src/test/java/com/openggf/game/resources/TestPlcFrameLifecycleCoordinator.java`
- Test: `src/test/java/com/openggf/TestPlcLifecycleDriverParity.java`
- Test: `src/test/java/com/openggf/game/resources/TestNativePlcFadeOwnerCoverage.java`
- Test: `src/test/java/com/openggf/game/resources/TestPlcObjectOwnedFadeLifecycle.java`
- Test: `src/test/java/com/openggf/game/resources/TestPlcProviderOwnedFadeLifecycle.java`
- Test: `src/test/java/com/openggf/game/sonic1/resources/TestSonic1PlcLifecycle.java`
- Test: `src/test/java/com/openggf/game/sonic2/resources/TestSonic2PlcLifecycle.java`

**Interfaces:**
- Consumes: `PlcLifecyclePhase`, the active module's optional
  `PlcLifecycleService`, and the existing S1/S2 queue façades.
- Produces:

```java
public enum PlcLifecyclePhase {
    LAG,
    TITLE_SCREEN,
    LEVEL_SELECT,
    LEVEL_TITLE_CARD,
    ORDINARY_LEVEL,
    PALETTE_FADE,
    SPECIAL_STAGE,
    SPECIAL_STAGE_RESULTS,
    TWO_PLAYER_RESULTS,
    CREDITS_TEXT,
    CREDITS_DEMO,
    CREDITS_DEMO_FADE,
    ENDING,
    POST_CREDITS,
    NORMAL_PAUSE,
    SPECIAL_STAGE_PAUSE
}

public interface PlcLifecycleService {
    void serviceVBlank(PlcLifecyclePhase phase);
    boolean hasPreparationBoundary(PlcLifecyclePhase phase);
    void prepareAfterLoop(PlcLifecyclePhase phase);
}
```

`PlcVBlankService` must not remain as a second independently invoked service
port. Either remove it or fold it into the semantic port and migrate every
caller in the same task. S1/S2 expose exactly one adapter instance through
their module service graph; S3K exposes none.

`PlcFrameLifecycleCoordinator` owns one single-use frame token per represented
VBlank. Its concrete contract is the one in the reviewed design:
`latchBeforeFadeUpdate()`, an explicit native-blocking-fade marker, token
`claim(phase)`, `prepareAfterLoop(phase)`, and `finish()`.

It implements the game-neutral `NativeFadeLifecycle` marker port and is stored
on `GameplayModeContext`. Objects receive that port only through
`ObjectServices` / `DefaultObjectServices`. Provider fade code receives it
through `NativeFadeLifecycleAware`, bound by `GameLoop` before provider
initialization; `Sonic1EndingProvider` passes it into
`Sonic1CreditsManager`. Do not add a `GameServices` accessor, call
`SessionManager` from a provider, use `getInstance()`, or branch on game name.

- [ ] **Step 1: Write ordering RED tests**

Use a recording service to assert the ordinary level loop:

```java
assertEquals(
        List.of("vblank-service", "events", "objects", "prepare"),
        observedCalls);
```

Add a table-driven façade test for the reviewed phase matrix:

```text
S1:
  9 = title, level-select, title-card, fade, special-results,
      credits-text, ending, post-credits
  3 = ordinary-level, credits-demo, credits-demo-fade, normal-pause
  0 = lag, special-stage, special-stage-pause

S2:
  6 = title, level-select, title-card, fade, two-player-results
  3 = ordinary-level, special-stage, special-results, normal-pause
  0 = lag, credits/ending/post-credits, special-stage-pause
```

Pin preparation independently from service:

```text
S1 prepare = title, level-select, title-card, ordinary-level, fade,
             special-results, credits-text, credits-demo
S2 prepare = title, title-card, ordinary-level, fade, special-stage,
             special-results, two-player-results
```

S2's implemented level-select loop deliberately has no `RunPLC_RAM`; S1
credits-demo-fade, ending/post-credits, and both pause loops deliberately have
no preparation. `CREDITS_DEMO_FADE` is not applicable to S2.
Assert every omitted phase is a no-op rather than relying only on positive
cases.

Add lifecycle-order cases for:

- lag/VBlank-only rows: no PLC service and no preparation;
- native normal pause: three-pattern service and no preparation;
- special-stage pause: no service and no preparation;
- ordinary level: service before events/objects, preparation after the full
  producer/consumer scan;
- locked title card: title-card budget before provider/object work and
  preparation afterward, without the nested ordinary-level budget;
- palette fade: exactly one fast/normal service and one preparation when the
  transition explicitly owns a native fade loop;
- native-fade completion: latch the fade token, let `FadeManager.update()`
  invoke a callback that changes mode, then prove the outgoing fade receives
  exactly one service/preparation pair and the incoming phase cannot claim
  until the next token;
- cosmetic overlay: advance the fade presentation without a native marker and
  prove the admitted underlying phase remains the sole PLC owner;
- multi-step presentation: execute two logical iterations inside one host
  update and prove two separately latched tokens/fade advances rather than one
  host-frame-scoped owner;
- title and level select;
- S1 credits text/demo and ending/post-credits;
- S1 credits demo slow fade: all 60 `CREDITS_DEMO_FADE` iterations service
  three and perform zero preparations, while ordinary `CREDITS_DEMO` still
  services three and prepares;
- S1/S2 special stage and special-stage results;
- S2 two-player results if and only if the engine has a real owner for that
  lifecycle; otherwise record it as dormant instead of manufacturing a mode;
- a completed-entry boundary proving the successor is first serviced on a
  later prepare/service pair; and
- a test-only object scan in which an earlier callback invokes append, clear,
  or replace directly on a recording/pre-seeded façade and a later callback
  observes `isBusy()`, plus the reversed order. This pins synchronous slot
  visibility without routing any real producer or rendering art; Task 5 repeats
  it through production producer owners; and
- an S3K module with no PLC adapter, proving the existing
  `VINT_SERVICE -> PRE_MAIN_LOOP -> POST_OBJECTS` callbacks occur once and in
  their prior order.

Run paired live/headless cases for ordinary level, recorded lag, native pause,
S2 locked title card, special stage, and special-stage results. Each pair must
produce the same token claim, service, and preparation sequence.

Add production-owner completion cases:

- drive either results object through fade completion and assert its
  level/mode callback plus callback-started reveal cannot claim the outgoing
  `PALETTE_FADE` token; the reveal owns the next token;
- drive a bound S1 credits/post-credits transition and an S2 ending/credits
  transition through completion and assert the new provider phase first owns
  the next token; and
- repeat one object-owned and one provider-owned case through the headless
  logical-iteration driver without `Engine.display()`, comparing its
  claim/service/preparation events with the live driver.

`TestNativePlcFadeOwnerCoverage` pins the design's audited inventory and fails
when an S1/S2 production fade call lacks a marked/unmarked classification.
In particular it pins S1 `SS_FinLoop`, credits `Level_FadeDemo`, and the ending
emerald flash as unmarked concurrent work, and pins `Level_FadeDemo` to
`CREDITS_DEMO_FADE` rather than ordinary `CREDITS_DEMO`.

Do not add real queue submissions to title/results/fade production code in
these tests. Pre-seed the façade queue or use a recording adapter. Task 5 owns
producer/render composition and same-scan append/clear/replace tests.

- [ ] **Step 2: Run RED**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=TestPlcVBlankOrdering,TestPlcFrameLifecycleCoordinator,TestPlcLifecycleDriverParity,TestSonic1PlcLifecycle,TestSonic2PlcLifecycle" test
```

Expected: failures because the ordinary-only port cannot express the phase
matrix or preparation boundary.

- [ ] **Step 3: Replace the partial port with the semantic adapter**

Add `PlcLifecyclePhase` and `PlcLifecycleService`. Make each S1/S2 façade map
the table above to its existing `serviceLevelVBlank`,
`serviceFastVBlank` / `serviceNormalVBlank`, and `prepare` methods. Keep those
low-level methods package-private or otherwise prevent alternate lifecycle
callers from bypassing the semantic table.

Expose the same façade instance through the new interface. Remove the old
ordinary-only lookup after migrating its callers. No shared class may inspect
`GameId`, a game name, a zone, a trace, or a frame number.

- [ ] **Step 4: Implement the pre-fade lifecycle coordinator**

Create the session-owned `PlcFrameLifecycleCoordinator` and single-use
`PlcLifecycleFrame` contract from the design. A native transition call site
must call `beginNativeBlockingFade()` immediately before starting its
`FadeManager` operation and wrap even a no-op completion. Cosmetic overlays do
not acquire the marker.

For every logical iteration:

```text
frame = latchBeforeFadeUpdate()
if native fade:
    service PALETTE_FADE
advance FadeManager once
if native fade:
    prepare PALETTE_FADE
run/skip mode logic with the same token
finish token
```

The completion wrapper ends the marker before invoking the mode-changing
callback, but cannot change the already-latched token. Assert that the incoming
mode cannot claim it and begins on the next represented VBlank.

Migrate every marked owner named in the design audit. Objects acquire the port
only from `services().nativeFadeLifecycle()`; ending providers use their bound
port; `SpecialStageEntryPresentationController` receives it from `GameLoop`.
Leave S1 `SS_FinLoop`, `Level_FadeDemo`, and ending emerald flash unmarked.
Their underlying phases are `SPECIAL_STAGE`, `CREDITS_DEMO_FADE`, and
`ENDING`, respectively. Add a neutral optional semantic-phase override to
`EndingProvider`; `Sonic1EndingProvider` returns `CREDITS_DEMO_FADE` exactly
while `Sonic1CreditsManager` reports `DEMO_FADING_OUT`. The shared lifecycle
owner consults the override before mapping `EndingPhase`; it must not inspect
game identity or provider class.

Move active-session logical fade advancement out of the unconditional
`Engine.display()` UI update and into the one-logical-iteration driver.
`Engine` keeps the UI fade updater only when no gameplay coordinator owns the
session fade. User-recording fast-forward/pumped steps each create a fresh
token and advance the fade once. Headless drivers invoke the same coordinator
with the session `FadeManager.update` callback; no OpenGL code owns PLC policy.

- [ ] **Step 5: Replace `LevelFrameStep` with the canonical phase-aware API**

Implement only these production entry shapes:

```java
execute(context, frame, phase, level, camera, spriteUpdate, wrapper)
executeWithPause(context, frame, activePhase, pausePhase,
                 level, camera, spriteUpdate, startEdge, wrapper)
executeHardwareTimedObjectScan(context, frame, phase, objectScan)
serviceVBlankOnly(context, frame, phase)
```

Remove the no-phase production overloads and migrate direct callers.
`execute` claims before phase-owned work and prepares immediately after the
object/event producer-consumer scan and existing `POST_OBJECTS` boundary,
before any transition-request early return. Setup-only returns do not claim.
Do not prepare from a generic `finally`.

`executeWithPause` applies the Start edge once. A still-paused frame calls
`serviceVBlankOnly(..., pausePhase)` and never prepares; an unpause press
delegates to `execute(..., activePhase)` on that row. Ordinary level uses
`ORDINARY_LEVEL` / `NORMAL_PAUSE`; represented special-stage pause uses
`SPECIAL_STAGE` / `SPECIAL_STAGE_PAUSE`.

Restrict `serviceVBlankOnly` to:

```text
native level pause                    -> NORMAL_PAUSE
recorded level lag / held admission   -> LAG
recorded special-stage lag            -> LAG
native special-stage pause            -> SPECIAL_STAGE_PAUSE
```

S3K-only setup/bonus held rows use a hardware-boundary-only helper because
they represent no S1/S2 PLC handler.

- [ ] **Step 6: Migrate every live, trace, recording, and rewind caller**

Wire the adapter at the smallest existing lifecycle owner:

- `LevelFrameStep` owns `ORDINARY_LEVEL` service before events/objects and
  preparation after their complete scan. Its VBlank-only entry point must
  accept `LAG`, `NORMAL_PAUSE`, or `SPECIAL_STAGE_PAUSE` explicitly; it must
  not infer all VBlank-only rows are lag.
- `GameLoop.updateTitleCardMode` owns one `LEVEL_TITLE_CARD` pair around the
  provider/native title-card scan. The S2 branch that delegates its body to
  `LevelFrameStep` must not also invoke `ORDINARY_LEVEL`.
- title and level-select owners wrap their provider update with their semantic
  phase. A phase with no reviewed preparation remains service-only.
- special-stage and special-results owners service before their object/results
  update and prepare after it. A skipped trace lag row selects `LAG`, never the
  nominal special-stage phase.
- credits text/demo, ending, and post-credits owners use their explicit phases.
  Preserve the table's service-without-prepare and no-service cases.
- native normal pause selects `NORMAL_PAUSE`; unpause resumes the ordinary
  level phase on the unpause press row, matching `GameStateManager`'s existing
  admission contract.

Audit and migrate `RecordingFrameDriver`,
`TraceSessionLauncher.VisualTraceRewindStepper`, `LiveRewindStepper`,
`SpecialStageStepper`, and every `executeWithPause`,
`serviceVBlankOnly`, and `executeHardwareTimedObjectScan` call. A caller that
cannot represent an S1/S2 PLC VBlank must use the named hardware-only helper,
not an implicit ordinary phase.

The S2 locked-title-card path passes its one token and
`LEVEL_TITLE_CARD` directly into phase-aware `LevelFrameStep.execute`. It must
not wrap that call in an external title-card service and must not call an
ordinary overload. The S1 minimal title-card scan and S3K provider scan pass
their token and semantic title-card phase to
`executeHardwareTimedObjectScan`.

Call `prepareAfterLoop` only at the represented call site. Do not prepare at
frame start, in a generic `finally`, after lag/pause, or from
`HardwareServiceBoundary`.

- [ ] **Step 7: Protect S3K timing and Task 5 ownership**

The semantic PLC call must be adjacent to, not embedded in, the S3K hardware
timing dispatcher. It must not invoke `HardwareTimingService.service`,
`RuntimeArtCoordinator.afterTimingService`, or either S3K Kos queue. Keep the
existing hardware-boundary tests byte-for-byte in intent and add a no-adapter
case if needed.

Task 4 production code calls only `serviceVBlank` and
`prepareAfterLoop`. It must not call `append`, `replaceQueued`, `clearQueued`,
`PlcParser`, an eager art provider, or renderer registration. Same-object-scan
visibility is tested here only through test-owned callbacks; Task 5 owns every
real producer/render composition path.

- [ ] **Step 8: Run GREEN**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=TestPlcVBlankOrdering,TestPlcFrameLifecycleCoordinator,TestPlcLifecycleDriverParity,TestSonic1PlcLifecycle,TestSonic2PlcLifecycle,TestNativePlcFadeOwnerCoverage,TestPlcObjectOwnedFadeLifecycle,TestPlcProviderOwnedFadeLifecycle,TestLevelFrameHardwareTimingBoundaries" test
```

Expected: all lifecycle tests pass, including the existing S3K hardware timing
boundary test. Also run:

```bash
mvn -Dmse=off \
  "-Dtest=TestLevelIterationHardwareTimingAdmissionOrder,TestS3kKosDecompressionQueueLifecycle" test
```

- [ ] **Step 9: Commit the Task 4 completion**

`37beb532d` already used the originally planned commit subject for the partial
ordinary-level seam. Commit the reviewed completion as:

```text
fix(plc): complete phase-owned PLC lifecycle
```

Use `Changelog: updated`.

---

### Task 5: Compose every implemented producer with eager rendering

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1ObjectArtProvider.java`
- Modify: `GameLoop`, S1/S2 title-screen managers, S1/S2 level-init profiles,
  and title-card managers at their audited title, setup, and title-card-retirement
  producers
- Modify: `src/main/java/com/openggf/game/sonic1/credits/Sonic1CreditsManager.java`
  at the audited credits-text next-demo prequeue owners
- Modify: S1 producer owners listed as `Route` in
  [`2026-07-29-s1-s2-plc-producer-call-site-audit.md`](../audits/2026-07-29-s1-s2-plc-producer-call-site-audit.md)
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2ZoneEvents.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`, the S1/S2 special-stage
  providers, and `Sonic2SpecialStageIntro` at the audited special-stage routes
- Modify: all S2 event and boss producer owners listed as `Route` in
  [`2026-07-29-s1-s2-plc-producer-call-site-audit.md`](../audits/2026-07-29-s1-s2-plc-producer-call-site-audit.md)
- Test: `src/test/java/com/openggf/game/sonic1/resources/TestSonic1PlcProducerCoverage.java`
- Test: `src/test/java/com/openggf/game/sonic2/resources/TestSonic2PlcProducerCoverage.java`
- Test: `src/test/java/com/openggf/game/sonic2/TestSonic2RuntimePlcRendererRefresh.java`

**Interfaces:**
- Consumes: each game PLC service plus existing eager art loaders/providers.
- Produces: one game-owned request path per ROM PLC operation that performs validated logical submission and eager rendering without conflating readiness.

- [ ] **Step 1: Write producer-coverage RED tests**

Create table-driven tests from the audited Task 5 routing table. The table is
exhaustive for all disassembly producers with represented Java owners; for each
`Route` producer,
invoke its threshold or lifecycle action and assert:

```java
assertEquals(expectedPlcId, recordingPlcService.lastSubmission().plcId());
assertEquals(expectedOperation, recordingPlcService.lastSubmission().operation());
assertTrue(rendererOrSheetIsAvailable());
```

For S1 credits, drive `Sonic1CreditsManager.initialize()` and every
`onReturnToText()` entry. Assert the same ordered `clear/optional-primary/Main2`
transaction before the first `CREDITS_TEXT` service row. The final text-only
credit is not a no-op: reproduce the ROM's `EndDemo_Levels[8]` overread into
the following `EndDemo_LampVar` bytes (`0x0101`) and assert its selected
primary (if nonzero) plus `Main2`, despite no later demo being scheduled. Do
not use `GameLoop.loadEndingDemoZone()` as the test trigger: that load occurs
after the native prequeue boundary.

Include repeated submission after the sheet is cached and assert that the
logical submission count increments while renderer allocation remains stable.

- [ ] **Step 2: Run RED**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=TestSonic1PlcProducerCoverage,TestSonic2PlcProducerCoverage,TestSonic2RuntimePlcRendererRefresh" test
```

Expected: failures for omitted logical submissions, including implemented S2
events whose comments currently say art is handled elsewhere.

- [ ] **Step 3: Add transactional game-owned request methods**

For each game, introduce an internal request method that first parses and
derives all logical work and materializes the eager render payload without
publishing it. Then commit queue mutation plus a non-throwing prepared renderer
registration; if publication can fail, roll back before exposing either
effect. Preserve existing renderer deduplication, but never use it to skip
logical submission. Invoke this method synchronously at the exact producer
call site inside an object/event scan.

- [ ] **Step 4: Route all implemented producers**

Replace comments or direct art-only calls with the reviewed operation and PLC
ID. The audit, not an inferred lifecycle, is the exhaustiveness boundary: route
every row marked `Route`, including both submissions from each
`LoadPLC_AnimalExplosion` helper, title-screen `replace(0)` before the first
`TITLE_SCREEN` service row, S1 credits-text `clear/primary/Main2` before the
first `CREDITS_TEXT` service row on every text page (including its final
`EndDemo_Levels[8]` overread, never deferred to the later demo level load), and
the S2 one-player `Player_mode != 2` no-life submission branch; leave every
excluded producer family
documented until it has a concrete engine owner. Do not add zone checks to
shared code.

- [ ] **Step 5: Run GREEN**

Run:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=TestSonic1PlcProducerCoverage,TestSonic2PlcProducerCoverage,TestSonic2RuntimePlcRendererRefresh,TestSonic2PlcParser" test
```

Expected: all tests pass.

- [ ] **Step 6: Commit producer routing**

Commit:

```text
fix(plc): route runtime S1 and S2 PLC requests
```

Use `Changelog: updated`.

#### Task 5 amendment: transactional publication and represented S2 player-life mode

**Files:**
- Modify: `Sonic2ObjectArtProvider`, `Sonic2PlcService`, `Sonic2ZoneEvents`, and
  `Sonic2PlcRequests`; `Sonic2LevelInitProfile`; `Sonic2GameModule`
- Create: `Sonic2PlayerArtModeAuthority`, `Sonic2RuntimePlcPublisher`
- Test: `TestSonic2RuntimePlcRendererRefresh` and the S1/S2 producer coverage tests

1. Add a provider preflight object that parses a PLC and builds all absent sheets
   without registering them. Its commit only installs already-built sheets and
   cannot perform ROM I/O.
2. Add a prepared **ordered batch** to the logical queue. It parses and
   capacity-checks every requested PLC against one snapshot, then commits all
   prepared entries without further parsing. The publisher performs renderer
   batch preflight, logical batch preflight, logical batch commit, non-throwing
   renderer commit, then exactly one renderer-cache refresh. A preflight error
   leaves both states unchanged and does not refresh.
3. Route event and object owners through that publisher, including cache-hit
   calls. Assert with real queue snapshots that every audit row submits exactly
   the documented replace/append sequence and that invalid/capacity-rejected
   work does not change either queue or renderer registration.
4. Keep the S2 initial setup on the representable one-player contract: default
   no-life path, Tails-alone PLC `9`. `Sonic2PlayerArtModeAuthority` is injected
   by `Sonic2GameModule` into `Sonic2LevelInitProfile`; its default maps
   `{twoPlayer=false, playerMode=PlayerCharacter, alternateGraphics=false}` to
   `OptionalInt.empty()` for Sonic/Sonic+Tails and `OptionalInt.of(9)` for
   Tails-alone. Do not synthesize or claim coverage for `6`/`7`/`8` until a
   session-owned source supplies those inputs. Test the default contract and a
   rejected two-item batch with zero queue/renderer/refresh effects.
5. Treat a capacity-rejected event request as deferred publication bookkeeping,
   not as a new DLE state. Retry it before the next game-owned DLE call without
   returning early: on failure, retain the request and execute the current DLE
   routine exactly once; on success, clear the request and execute the current
   DLE routine exactly once. Because the original one-shot producer advanced
   its routine before publication, the retry must not repeat the producer.
   Apply this contract to every S1 and S2 event owner. Add focused S1 and S2
   tests for both retry failure and retry success, asserting DLE progression,
   pending-request state, and exactly one successful queue/render publication.

---

### Task 6: Migrate gameplay consumers and retire the FZ counter

**Files:**
- Delete: `src/main/java/com/openggf/game/sonic1/events/Sonic1FzPlcTimingQueue.java`
- Delete: `src/test/java/com/openggf/game/sonic1/events/TestSonic1FzPlcTimingQueue.java`
- Modify: `src/main/java/com/openggf/game/sonic1/events/Sonic1SBZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FZBossInstance.java`
- Modify: implemented S1 result/Game Over/special-stage consumer owners
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2ARZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2ARZBossInstance.java`
- Modify: implemented S2 result/Game Over/special-stage/2P consumer owners
- Test: `src/test/java/com/openggf/game/sonic1/events/TestSonic1FinalZonePlcIntegration.java`
- Test: `src/test/java/com/openggf/game/sonic2/objects/bosses/TestSonic2ArzBossPlcReadiness.java`
- Test: per-consumer lifecycle tests identified by `rg -n "Results|GameOver|SpecialStageResults" src/test/java/com/openggf/game/sonic1 src/test/java/com/openggf/game/sonic2`

**Interfaces:**
- Consumes: injected `Sonic1PlcService.isBusy()` and `Sonic2PlcService.isBusy()`.
- Produces: ROM consumers that advance only from the shared queue's natural readiness.

- [ ] **Step 1: Write S1 Final Zone RED tests**

Assert:

```java
@Test void bossWaitsForWholeQueueAndAdvancesRngEveryBusyFrame()
@Test void queueCompletionAndCameraThresholdReleaseOnTheSameRomVisibleFrame()
@Test void unrelatedEarlierEntryExtendsTheBossWait()
@Test void existingFzRomVectorMatchesGeneralQueueDrain()
```

Use a recording/in-memory PLC service, not a hard-coded frame counter.

- [ ] **Step 2: Write S2 ARZ RED tests**

Assert:

```java
@Test void arzBossInitializationReturnsWhileAnyPlcEntryIsPending()
@Test void unrelatedEarlierEntryBlocksArzBossArtReadiness()
@Test void completionBeforeObjectScanAllowsInitializationThatFrame()
@Test void playerAndSidekickPositionBranchRunsOnlyAfterReadiness()
```

- [ ] **Step 3: Write remaining consumer RED tests**

For every implemented consumer in the Task 1 audit, assert its initial routine
does not advance while busy and advances at the first ROM-visible empty frame.

- [ ] **Step 4: Run RED**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=TestSonic1FinalZonePlcIntegration,TestSonic2ArzBossPlcReadiness,*Results*,*GameOver*" test
```

Expected: the general queue is not yet consumed and ARZ initializes early.

- [ ] **Step 5: Inject queue readiness into consumers**

Use constructor/service injection consistent with surrounding objects.
Consumers only poll `isBusy()`; they do not service, clear queued descriptors,
prepare, or inspect queue entries.

- [ ] **Step 6: Remove the narrow FZ model**

Delete `Sonic1FzPlcTimingQueue`, its snapshot fields, and hard-coded tile
counts. Preserve the boss's per-wait-frame RNG increment and camera condition.
Update any stale comment claiming eager art makes the PLC always empty.

- [ ] **Step 7: Run GREEN**

Run:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=TestSonic1FinalZonePlcIntegration,TestSonic2ArzBossPlcReadiness,*Results*,*GameOver*,TestSonic1SBZEvents" test
```

Expected: all migrated consumers pass.

- [ ] **Step 8: Commit consumer migration**

Commit:

```text
fix(plc): gate S1 and S2 consumers on queue readiness
```

Use `Changelog: updated`.

---

### Task 7: Register complete rewind ownership

**Files:**
- Modify: rewind registration/adapter files located with `rg -n "PlcProgressSnapshot|RewindSnapshottable|register.*rewind" src/main/java`
- Modify: game service snapshots if Task 3's module graph requires them
- Test: `src/test/java/com/openggf/game/rewind/TestNemesisPlcQueueRewind.java`
- Test: `src/test/java/com/openggf/game/rewind/TestS1S2PlcSessionOwnership.java`
- Test: `src/test/java/com/openggf/tests/TestRewindCoverageGuard.java`
- Test: `src/test/java/com/openggf/tests/TestStaticStateRewindCoverageGuard.java`

**Interfaces:**
- Consumes: `NemesisPlcQueueSnapshot`, game service capture/restore.
- Produces: session-scoped rewind adapters restoring queue state without resubmission.

- [ ] **Step 1: Write rewind RED tests**

Cover rewind at:

```text
empty
unprepared head
partially serviced head
entry completion before next preparation
prepared second entry
after append
after successful queued replacement while idle
after successful queued clear while idle
after rejected queued replacement while active, with state unchanged
after rejected queued clear while active, with state unchanged
```

For each, capture, advance across readiness, restore, replay, and assert the
same busy sequence and consumer release frame.

- [ ] **Step 2: Write session-isolation RED test**

Create sequential S1 and S2 sessions, submit work in the first, close it, and
assert the second begins empty with no shared cache or ordinal state.

- [ ] **Step 3: Run RED**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=TestNemesisPlcQueueRewind,TestS1S2PlcSessionOwnership,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard" test
```

Expected: missing registration or coverage failure.

- [ ] **Step 4: Register capture and restore**

Restore the queue in place through the façade as the sole rewind registration
owner. Do not call parse, append, queued replacement, eager art registration,
or any trace API during restoration.

- [ ] **Step 5: Run GREEN**

Run the command from Step 3. Expected: all tests pass.

- [ ] **Step 6: Commit rewind support**

Commit:

```text
feat(rewind): capture S1 and S2 PLC progress
```

Use `Changelog: n/a: rewind coverage for the PLC runtime behavior already documented`.

---

### Task 8: Add trace-policy guards and route validation

**Files:**
- Modify: `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`
- Create: `src/test/java/com/openggf/trace/TestS1S2PlcComparisonOnlyGuard.java`
- Modify: relevant S1/S2 trace replay tests discovered with `rg -l "TraceReplay" src/test/java/com/openggf/tests/trace`
- Modify: `docs/status/trace-frontier-log.md` only if a measured frontier changes
- Modify: `docs/status/known-discrepancies.md` if an existing PLC discrepancy is resolved

**Interfaces:**
- Consumes: production PLC services and existing comparison-only trace policy.
- Produces: source guard preventing trace authority/hydration and measured trace evidence.

- [ ] **Step 1: Write the source guard**

Assert:

```text
Sonic1PlcService and Sonic2PlcService do not import com.openggf.trace.*
TraceFrame, TraceEvent, physics CSV, and aux parsers do not import either PLC service.
HardwareWorkKind does not contain PLC_QUEUE.
No replay/bootstrap class calls append, replaceQueued, clearQueued, prepare, servicePatterns,
serviceLevelVBlank, serviceFastVBlank, or serviceNormalVBlank on an S1/S2 PLC service.
```

- [ ] **Step 2: Run policy tests**

Run:

```bash
mvn -Dmse=off \
  "-Dtest=TestHardwareTimingAuthorityGuard,TestS1S2PlcComparisonOnlyGuard,TestHardwareTimingStreamLoader" test
```

Expected: all guards pass without adding a new timing kind or fixture stream.

- [ ] **Step 3: Run focused trace replays**

Discover actual class names and ROM files:

```bash
find . -maxdepth 1 -type f -name '*.gen' -print
rg -l "Final Zone|FZ|Aquatic Ruin|ARZ" src/test/java/com/openggf/tests/trace
```

Run every class printed by the discovery command individually with the correct
ROM property:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=FullyQualifiedClassNamePrintedByDiscovery" test
```

Expected: zero new divergences. If a frontier moves or a previously passing
trace changes, update `docs/status/trace-frontier-log.md` with command, commit,
pass/fail, error count, and first-error frame/field.

- [ ] **Step 4: Run S1/S2 trace sweeps**

List the repository's actual S1 and S2 `*TraceReplay` class set:

```bash
rg -l "class .*TraceReplay" \
  src/test/java/com/openggf/tests/trace/s1 \
  src/test/java/com/openggf/tests/trace/s2
```

Run each concrete non-abstract class printed by the command using:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=FullyQualifiedClassNamePrintedByDiscovery" test
```

Expected: no new failure relative to the integration baseline. Do not exclude
a class merely because it is slow.

- [ ] **Step 5: Commit guards and trace evidence**

Commit:

```text
test(trace): guard native S1 and S2 PLC timing
```

Use truthful documentation trailers based on whether the frontier or known
discrepancy files changed.

---

### Task 8A: Reproduce the native PLC boundary when level presentation is skipped

**Files:**
- Modify: `src/main/java/com/openggf/game/LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplayDriver.java`
- Create: focused skipped-presentation lifecycle tests under
  `src/test/java/com/openggf/game/`
- Create: a production/trace isolation guard under
  `src/test/java/com/openggf/trace/`

**Interfaces:**
- Consumes: the production level-transition decision to omit presentation,
  current ROM-backed level header, and session-owned PLC lifecycle service.
- Produces: the same logical PLC boundary native execution reaches before its
  first ordinary gameplay iteration, without any trace timing authority.

- [ ] **Step 1: Pin the native sequence and expected S1 queue snapshot**

Record the disassembly evidence:

```text
S1 Level_TtlCardLoop: title VBlank -> ExecuteObjects -> BuildSprites -> RunPLC,
                      repeat until v_plc_buffer is empty
S1 LevelDataLoad:     append header byte +4 as the second PLC
S1 Level_DelayLoop:   four level VBlanks, no RunPLC
S1 PalFadeIn_Alt:     22 x (palette VBlank -> fade update -> RunPLC)

S2 Level_TtlCard:     title VBlank -> RunObjects -> BuildSprites -> RunPLC_RAM,
                      repeat until Plc_Buffer is empty
S2 loadZoneBlockMaps: append level-header byte +4 after that locked loop
```

Use the discovered S1 ROM to assert that SBZ/FZ's secondary PLC is ID 15 with
pattern counts `16,41,31,15,15,20,49,4,48,12,8,16,14`. The expected
post-skip snapshot has descriptor seven active with 22 patterns remaining and
descriptors eight through thirteen queued. This is a ROM-derived oracle, not a
captured-state fixture.

- [ ] **Step 2: Write RED production lifecycle tests**

Test the game-owned level-init profiles through the normal PLC lifecycle:

1. S1 drains primary + `Main2` through `LEVEL_TITLE_CARD`.
2. S1 appends the current header's secondary PLC only after that drain.
3. S1 performs exactly 22 `PALETTE_FADE` service/prepare iterations and leaves
   the ROM-derived partial secondary snapshot, rather than draining it.
4. S2 drains its initial primary + `Std2` queue through `LEVEL_TITLE_CARD`,
   then appends the ROM header's secondary cue without draining it.
5. A no-PLC game/profile is a no-op.
6. Repeating completion for the same consumed transition does not advance the
   queue twice.
7. A visible S2 release finishes the final locked-title preparation before
   publishing the header-secondary cue, leaving that cue queued and
   unprepared on publication.

Expected before implementation: lifecycle API/method missing or snapshots do
not match.

- [ ] **Step 3: Add the trace-isolation guard**

Assert that the production skipped-presentation API:

```text
imports no com.openggf.trace package;
accepts no TraceData, TraceFrame, metadata, fixture, BK2, route, or frame count;
is invoked from the production level-transition owner;
is not implemented in TraceReplaySessionBootstrap; and
leaves replay/bootstrap code unable to call append/replace/clear/prepare or
service methods on Sonic1PlcService/Sonic2PlcService.
```

The live trace driver may consume the production transition through
`LevelManager`; it must not contain a game-specific phase count or queue call.

- [ ] **Step 4: Implement the production-owned skip path**

Add a narrow `LevelInitProfile` operation for completing an initial locked
presentation's PLC lifecycle. Drive every represented iteration through
`PlcFrameLifecycleCoordinator` in service-before-prepare order. S1 reads the
secondary PLC from the active level header and owns the fixed 22-frame palette
sequence. S2 owns its queue-until-empty title-card sequence and then reads and
appends the active level header's secondary PLC. Do not add zone, route, trace,
or fixture branches.

`LevelManager.requestTitleCardIfNeeded` invokes the operation whenever a level
entry deliberately omits presentation (`showTitleCard=false`, headless, or a
production suppression provider). A live tool that has a pending title-card
request uses one `LevelManager` transition method to consume it and complete
the same skip. A visible `PostTitleCardDestination.LEVEL` release invokes the
same production boundary exactly once before its first ordinary gameplay
iteration. These transition methods are the idempotency boundary; replay code
does not call the profile or PLC services.

For a visible release, `GameLoop` finishes the already-claimed
`LEVEL_TITLE_CARD` preparation before calling the completion owner. Do not
append the header-secondary cue and then let the outgoing locked-title frame
prepare it.

- [ ] **Step 5: Run GREEN and regression traces**

Run the focused skipped-presentation lifecycle and isolation guards, then the
retry continuity suite:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Dtest=TestSonic1SkippedPresentationPlcLifecycle,TestSonic2SkippedPresentationPlcLifecycle,TestSkippedPresentationPlcTraceIsolationGuard,TestSonic1PlcRetryDleContinuity,TestSonic2RuntimePlcRendererRefresh" test
```

Then rerun all nine measured S1/S2 trace classes. Expected: no missing
preparation boundary and no new divergence relative to their pre-queue
baseline. Any remaining failure must be diagnosed independently and reflected
in this design/plan before changing the lifecycle contract again.

---

### Task 8B: Close source-truth lifecycle regressions exposed by queue waits

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/ResultsScreenObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1ResultsScreenObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1SignpostObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/resources/PlcFrameLifecycleCoordinator.java`
- Modify: focused S1/S2 results and lifecycle tests

- [ ] **Step 1: Write RED owner tests**

Assert that S2 normal results select a 180-frame post-tally wait below 1000
points and a 300-frame wait at or above 1000, matching `loc_141E6`. Assert
that two active S1 signpost owners share the fixed results-card slot and
publish the results replacement only once. Assert that a primary exception
thrown after an ordinary-level claim remains the thrown exception when
end-of-frame validation also detects missing preparation, with the validation
failure attached as suppressed context. For the S1 SBZ2 card, assert that the
movement scan which first reaches `got_finalX` returns, the next scan changes
to `Got_SBZ2_Boundary` without scrolling, and only the following scan adds two
to the right boundary.

- [ ] **Step 2: Implement the smallest owners**

Override the wait duration in the S2 results class using its accumulated
`totalBonus`. Before the S1 signpost publishes or allocates a results card,
query the active object manager for the existing live S1 results-card owner
and complete the duplicate signpost without a second submission. Keep this
logic in S1 code; do not add a game check to the shared object manager.

Make `PlcFrameLifecycleCoordinator.runLogicalIteration` preserve an exception
from fade/mode work. Its `finally` validation still runs; if validation also
fails, attach that failure to the primary exception rather than replacing it.
In the S1 results card, mark a move-out element complete only when a later
scan begins at `got_finalX`; reaching that coordinate by movement is not yet
the native equal-position dispatch.

- [ ] **Step 3: Run focused tests and affected traces**

Run the focused result-wait, singleton, lifecycle-coordinator, retry, and
skipped-presentation tests. Then rerun S1 SYZ2, S1 SBZ2, S1 FZ, and the six
measured S2 end-of-act traces. Expected: SYZ no duplicate fade failure, FZ
remains green, S2 traces no longer transition 120 frames too early, and no new
frontier regression.

---

### Task 9: Full regression, documentation closure, and delivery

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `README.md` release/change-log section for merge into `develop`
- Modify: `docs/status/known-discrepancies.md`
- Modify: `docs/status/trace-frontier-log.md` when required by Task 8 results
- Reference: `docs/architecture/designs/2026-07-28-s1-s2-plc-service-queues.md`
- Reference: this plan

**Interfaces:**
- Consumes: all prior tasks.
- Produces: reviewed documentation, baseline comparison, merged `develop`, pushed integration commit, and cleaned implementation worktree.

- [ ] **Step 1: Close documentation**

Update the changelog and known-discrepancy ledger to state:

- S1/S2 PLC readiness is ROM-derived production state;
- rendering remains eager;
- S1 Final Zone uses the general S1 queue;
- S2 ARZ and implemented lifecycle consumers poll the general S2 queue; and
- traces provide no PLC completion authority.

Update `README.md`'s release/change-log section as required for the feature
branch merge into `develop`.

- [ ] **Step 2: Run focused verification**

Run all focused tests from Tasks 2-8 in one Maven invocation with both
discovered ROM paths. Expected: all pass.

- [ ] **Step 3: Record the updated integration baseline**

In the main workspace, preserve uncommitted user changes, then:

```bash
git fetch origin
git pull --ff-only
mvn -Dmse=off \
  "-Dsonic1.rom.path=s1.gen" \
  "-Dsonic2.rom.path=s2.gen" \
  "-Ds3k.rom.path=s3k.gen" test
```

Record the exact baseline failures, errors, and command. A red baseline is
acceptable; new failures are not.

- [ ] **Step 4: Run the full suite in the implementation worktree**

Run the same full-suite command with all three ROM properties. Compare every
failure/error with Step 3, then rerun all focused tests. Expected: no new or
worsened failure attributable to this branch.

- [ ] **Step 5: Review and commit documentation**

Delegate design/spec compliance and code-quality review. Resolve every valid
issue and repeat review until no blocking issue remains. Run:

```bash
git diff --check
git status --short
git diff --cached --name-only
```

Commit:

```text
docs(plc): record S1 and S2 queue parity
```

Use truthful trailers for every staged mapped document.

- [ ] **Step 6: Merge into the main workspace**

Merge the completed implementation branch directly into the branch checked
out in the main workspace without switching that workspace. Reconcile upstream
changes and preserve all unrelated user modifications.

- [ ] **Step 7: Run post-merge regression comparison**

Run the same full suite and focused suite on merged `develop`. Confirm no test
that passed on the baseline now fails and no baseline failure worsened due to
the delivered work.

- [ ] **Step 8: Push and clean up**

Push only the main-workspace branch. Verify the implementation worktree has no
unknown, user-authored, uncommitted, or unmerged changes; discard only
classified generated outputs. Remove the worktree, verify the implementation
branch is fully merged, delete that local branch, and prune worktree metadata.

- [ ] **Step 9: Report exact delivery state**

Report the design/implementation result, significant challenges, upstream
conflicts, every test command/outcome, pushed branch/commits, and completed
worktree/branch cleanup. Do not claim completion if fetch, pull, comparison,
merge, push, or cleanup failed.
