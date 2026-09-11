# Trace Lag Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current lag-frame heuristic in trace replay with an explicit per-game execution model that distinguishes VBlank-only progress from full level-loop progress.

**Architecture:** Keep the first implementation pass narrow. The replay harness should stop inferring lag from unchanged physics and instead derive per-frame execution phase from ROM counters recorded in the trace. Backward compatibility matters: old v2.2 traces must still parse, and existing replay tests must keep running while the new schema is introduced.

**Tech Stack:** Java 17, JUnit 5, existing `HeadlessTestRunner` / `HeadlessTestFixture` / `LevelFrameStep` replay harness, BizHawk Lua trace recorders, Sonic 1 / Sonic 2 / Sonic 3K disassembly docs under `docs/`.

**Branch status (2026-04-20):** Tasks up through the shared Java replay-model migration and Sonic 1 recorder upgrade are landed on `feature/ai-performance-parity-optimization`. Tasks `6a` and `6b` remain deferred: the branch does not yet ship `tools/bizhawk/s2_trace_recorder.lua` or `tools/bizhawk/s3k_trace_recorder.lua`, so Sonic 2 and Sonic 3K traces still replay through the legacy heuristic path until those recorders are added.

---

## Scope and Constraints

- Do not add a fake “partial gameplay tick” stub. If the runner gains a new method, it must have real semantics and tests.
- Do not duplicate `vFrameCount` with a second field. Rename the existing concept to `gameplayFrameCounter`.
- Do not add `main_loop_ran` as a persisted CSV field. It is derivable from counter deltas and only adds redundancy.
- Do not break existing v2.2 trace fixtures. Parser compatibility is required before any old-path removal.
- All replay callers must migrate together:
  - `AbstractTraceReplayTest`
  - `AbstractCreditsDemoTraceReplayTest`
  - `TestS1Mz1LostRingCollectionOrderRegression`
  - `DebugS1Ghz1RingParity`
  - `DebugS1Mz1RingParity`

## File Map

| Path | Action | Responsibility |
|---|---|---|
| `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md` | Create | Cross-game lag matrix and counter/address reference. |
| `src/test/java/com/openggf/tests/trace/TraceFrame.java` | Modify | Rename `vFrameCount` to `gameplayFrameCounter`, add `vblankCounter` and `lagCounter`, keep parser compatibility. |
| `src/test/java/com/openggf/tests/trace/TraceData.java` | Modify | Derive execution phase from recorded counters; remove heuristic call sites. |
| `src/test/java/com/openggf/tests/trace/TraceMetadata.java` | Modify | Add `trace_schema`; keep schema metadata minimal and actually used. |
| `src/test/java/com/openggf/tests/trace/TraceExecutionPhase.java` | Create | Enum for replay phase selection. |
| `src/test/java/com/openggf/tests/trace/TraceExecutionModel.java` | Create | Game-aware phase derivation from trace counters. |
| `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java` | Modify | Verify v2.2 fallback and v3 parsing. |
| `src/test/java/com/openggf/tests/trace/TestTraceExecutionModel.java` | Create | Lock in FULL vs VBLANK_ONLY rules across games. |
| `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` | Modify | Replace `isLagFrame()` branching with execution-phase branching. |
| `src/test/java/com/openggf/tests/trace/AbstractCreditsDemoTraceReplayTest.java` | Modify | Same migration as the main replay path. |
| `src/test/java/com/openggf/tests/trace/s1/TestS1Mz1LostRingCollectionOrderRegression.java` | Modify | Use the execution model instead of open-coded heuristic checks. |
| `src/test/java/com/openggf/tests/trace/DebugS1Ghz1RingParity.java` | Modify | Log derived execution phase and counters. |
| `src/test/java/com/openggf/tests/trace/DebugS1Mz1RingParity.java` | Modify | Log derived execution phase and counters. |
| `tools/bizhawk/s1_trace_recorder.lua` | Modify | Emit v3 execution counters for S1. |
| `tools/bizhawk/s2_trace_recorder.lua` | Create | Emit v3 execution counters for S2 after address research is complete. |
| `tools/bizhawk/s3k_trace_recorder.lua` | Create | Emit v3 execution counters for S3K after address research is complete. |
| `tools/bizhawk/README.md` | Modify | Document the v3 trace schema and lag model. |
| `docs/guide/contributing/trace-replay.md` | Modify | Replace heuristic-lag documentation with counter-driven replay semantics. |

## Cross-Game Lag Contract

### Sonic 1

- `v_framecount` advances only in `Level_MainLoop` after `WaitForVBla`, in `docs/s1disasm/sonic.asm:3198-3226`.
- `v_vbla_count` advances every VBlank in `docs/s1disasm/sonic.asm:615-643`.
- Lag VBlank uses `VBla_00`, which still performs interrupt-side work and then falls through to music update in `docs/s1disasm/sonic.asm:662-700` and `docs/s1disasm/sonic.asm:637-643`.

### Sonic 2

- `Level_frame_counter` advances only in `Level_MainLoop`, in `docs/s2disasm/s2.asm:5084-5108`.
- The lag V-int path is `Vint_Lag`, which still performs interrupt-side palette/H-int work and sound-driver input, in `docs/s2disasm/s2.asm:529-583`.
- `Level_frame_counter` is in `docs/s2disasm/s2.constants.asm:1665`, immediately after `$FFFFFE02`, so its address is `$FFFFFE04`.
- `Vint_runcount` is in `docs/s2disasm/s2.constants.asm:1672`, so its address is `$FFFFFE0A`.

### Sonic 3 / Sonic 3K

- `Level_frame_counter` advances only in `LevelLoop`, in `docs/skdisasm/s3.asm:5966-5989` and `docs/skdisasm/sonic3k.asm:7884-7911`.
- `Lag_frame_count` increments in `VInt_0_Main`, in `docs/skdisasm/s3.asm:737-755` and `docs/skdisasm/sonic3k.asm:566-581`.
- `Do_Updates` resets `Lag_frame_count` during a normal frame, in `docs/skdisasm/s3.asm:940-949` and `docs/skdisasm/sonic3k.asm:784-793`.
- `sonic3k.constants.asm` gives symbol order for `Lag_frame_count`, `Level_frame_counter`, and `V_int_run_count` in `docs/skdisasm/sonic3k.constants.asm:555` and `docs/skdisasm/sonic3k.constants.asm:782-790`, but the plan intentionally treats exact S3K hex addresses as a research gate before recorder implementation. Do not guess them in code.

## Schema Direction

- Rename `TraceFrame.vFrameCount` to `TraceFrame.gameplayFrameCounter`.
- Add `TraceFrame.vblankCounter`.
- Add `TraceFrame.lagCounter`.
- Use `trace_schema = 3`.
- Keep support for:
  - v2.2 traces without the new columns
  - v3 traces with explicit execution counters

Replay phase derives from counter deltas:

- `FULL_LEVEL_FRAME` when `gameplayFrameCounter` changed from previous frame
- `VBLANK_ONLY` when `vblankCounter` changed but `gameplayFrameCounter` did not

`lagCounter` is diagnostic only. It must not drive phase selection.

### Task 1: Research Gate and Address Matrix

**Files:**
- Create: `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md`

- [ ] **Step 1: Write the cross-game subsystem matrix**

Create `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md` with three sections: `Sonic 1`, `Sonic 2`, `Sonic 3K`.

For each section include a table with these columns:

```md
| Subsystem | Full level frame | VBlank-only lag frame | Evidence |
|---|---|---|---|
| gameplay frame counter | yes | no | `docs/...` |
| VBlank counter | yes | yes | `docs/...` |
| object execution | yes | no | `docs/...` |
| camera / deform / screen events | yes | no | `docs/...` |
| palette / H-int setup in interrupt | mode-dependent | yes | `docs/...` |
| music / sound driver input | yes | yes | `docs/...` |
```

- [ ] **Step 2: Resolve and record counter addresses**

In the same research doc, add an address table:

```md
| Game | Gameplay counter label | Gameplay counter address | VBlank counter label | VBlank counter address | Lag counter label | Lag counter address |
|---|---|---:|---|---:|---|---:|
| S1 | `v_framecount` | `0xFE04` | `v_vbla_word` | `0xFE0E` | n/a | n/a |
| S2 | `Level_frame_counter` | `0xFE04` | `Vint_runcount+2` | `0xFE0E` | n/a | n/a |
| S3K | `Level_frame_counter` | `<fill from research>` | `V_int_run_count+2` | `<fill from research>` | `Lag_frame_count` | `<fill from research>` |
```

For S3K, do not proceed until exact addresses are resolved from the RAM table or verified in BizHawk memory tools. Write the resolved hex into the doc before starting recorder work.

- [ ] **Step 3: No code changes in this task**

This task is a hard gate. Do not touch Java or Lua until the matrix and address table are complete.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/research/2026-04-19-trace-lag-model-matrix.md
git commit -m "docs: capture cross-game trace lag matrix and counter addresses"
```

### Task 2: Introduce the v3 Trace Schema With Backward Compatibility

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/TraceFrame.java`
- Modify: `src/test/java/com/openggf/tests/trace/TraceMetadata.java`
- Modify: `src/test/java/com/openggf/tests/trace/TraceData.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`
- Create: `src/test/java/com/openggf/tests/trace/TestTraceExecutionModel.java`
- Create: `src/test/java/com/openggf/tests/trace/TraceExecutionPhase.java`
- Create: `src/test/java/com/openggf/tests/trace/TraceExecutionModel.java`

- [ ] **Step 1: Write the failing parser and execution-model tests**

Add to `TestTraceDataParsing.java`:

```java
@Test
public void testV22TraceStillParses() throws IOException {
    TraceData data = TraceData.load(SYNTHETIC_3FRAMES);
    TraceFrame frame0 = data.getFrame(0);
    assertEquals(-1, frame0.vblankCounter());
    assertEquals(-1, frame0.lagCounter());
}
```

Create `TestTraceExecutionModel.java`:

```java
package com.openggf.tests.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestTraceExecutionModel {

    @Test
    void sonic1CounterDelta_fullLevelFrame() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0121, 0x3457, 0);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void sonic1VblankDeltaWithoutGameplayDelta_vblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0121, 0x3456, 0);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void sonic3kUsesGameplayCounterNotLagCounter() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x2000, 0x0100, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x2001, 0x0100, 4);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s3k").phaseFor(previous, current));
    }

    @Test
    void firstFrameDefaultsToFullLevelFrame() {
        TraceFrame current = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 0);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s1").phaseFor(null, current));
    }

    @Test
    void unsupportedGameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TraceExecutionModel.forGame("bad"));
    }
}
```

- [ ] **Step 2: Implement the schema changes**

Modify `TraceFrame`:

- Rename `vFrameCount` to `gameplayFrameCounter`.
- Add `vblankCounter`.
- Add `lagCounter`.
- Add a test helper used by the new unit test:

```java
public static TraceFrame executionTestFrame(int frame, int vblankCounter,
        int gameplayFrameCounter, int lagCounter) {
    return new TraceFrame(frame, 0, (short) 0, (short) 0, (short) 0, (short) 0,
            (short) 0, (byte) 0, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, gameplayFrameCounter, -1,
            vblankCounter, lagCounter);
}
```

Update `TraceMetadata` to add only:

```java
@JsonProperty("trace_schema") Integer traceSchema
```

Update `TraceData` and `TraceFrame.parseCsvRow(...)` so:

- v2.2 remains accepted and defaults `vblankCounter = -1`, `lagCounter = -1`
- v3 accepts:

```text
frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode,x_sub,y_sub,routine,camera_x,camera_y,rings,status_byte,gameplay_frame_counter,stand_on_obj,vblank_counter,lag_counter
```

- [ ] **Step 3: Implement the execution model**

Create `TraceExecutionPhase.java`:

```java
package com.openggf.tests.trace;

public enum TraceExecutionPhase {
    FULL_LEVEL_FRAME,
    VBLANK_ONLY
}
```

Create `TraceExecutionModel.java`:

```java
package com.openggf.tests.trace;

public interface TraceExecutionModel {
    TraceExecutionPhase phaseFor(TraceFrame previous, TraceFrame current);

    static TraceExecutionModel forGame(String game) {
        return switch (game) {
            case "s1", "s2", "s3", "s3k" -> (previous, current) -> {
                if (previous == null) {
                    return TraceExecutionPhase.FULL_LEVEL_FRAME;
                }
                return current.gameplayFrameCounter() != previous.gameplayFrameCounter()
                        ? TraceExecutionPhase.FULL_LEVEL_FRAME
                        : TraceExecutionPhase.VBLANK_ONLY;
            };
            default -> throw new IllegalArgumentException("Unsupported game: " + game);
        };
    }
}
```

- [ ] **Step 4: Run the schema tests**

Run: `mvn -q "-Dtest=TestTraceDataParsing,TestTraceExecutionModel" test`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/TraceFrame.java src/test/java/com/openggf/tests/trace/TraceMetadata.java src/test/java/com/openggf/tests/trace/TraceData.java src/test/java/com/openggf/tests/trace/TraceExecutionPhase.java src/test/java/com/openggf/tests/trace/TraceExecutionModel.java src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java src/test/java/com/openggf/tests/trace/TestTraceExecutionModel.java
git commit -m "test: add v3 trace schema and explicit execution model"
```

### Task 3: Migrate Replay Callers Off the Heuristic

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractCreditsDemoTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/s1/TestS1Mz1LostRingCollectionOrderRegression.java`
- Modify: `src/test/java/com/openggf/tests/trace/DebugS1Ghz1RingParity.java`
- Modify: `src/test/java/com/openggf/tests/trace/DebugS1Mz1RingParity.java`

- [ ] **Step 1: Reuse `skipFrameFromRecording()` instead of adding a duplicate API**

Do not add `stepVblankOnlyFrameFromRecording()` or a stubbed lag hook. The current method already does the required first-pass behavior:

- consume the input stream
- do not execute `LevelFrameStep`

If a clearer name is needed, rename `skipFrameFromRecording()` in a follow-up task only after all callers migrate together.

- [ ] **Step 2: Update `AbstractTraceReplayTest`**

Replace:

```java
if (trace.isLagFrame(i)) {
    bk2Input = fixture.skipFrameFromRecording();
} else {
    bk2Input = fixture.stepFrameFromRecording();
}
```

with:

```java
TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
TraceExecutionPhase phase = TraceExecutionModel.forGame(meta.game()).phaseFor(previous, expected);

if (phase == TraceExecutionPhase.VBLANK_ONLY) {
    bk2Input = fixture.skipFrameFromRecording();
} else {
    bk2Input = fixture.stepFrameFromRecording();
}
```

- [ ] **Step 3: Update `AbstractCreditsDemoTraceReplayTest`**

Replace:

```java
if (trace.isLagFrame(i)) {
    var sprite = fixture.sprite();
    EngineDiagnostics engineDiag = captureEngineDiagnostics(sprite);
    binder.compareFrame(expected,
        sprite.getCentreX(), sprite.getCentreY(),
        sprite.getXSpeed(), sprite.getYSpeed(), sprite.getGSpeed(),
        sprite.getAngle(),
        sprite.getAir(), sprite.getRolling(),
        sprite.getGroundMode().ordinal(),
        engineDiag);
    continue;
}
```

with:

```java
TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
TraceExecutionPhase phase = TraceExecutionModel.forGame("s1").phaseFor(previous, expected);

if (phase == TraceExecutionPhase.VBLANK_ONLY) {
    var sprite = fixture.sprite();
    EngineDiagnostics engineDiag = captureEngineDiagnostics(sprite);
    binder.compareFrame(expected,
        sprite.getCentreX(), sprite.getCentreY(),
        sprite.getXSpeed(), sprite.getYSpeed(), sprite.getGSpeed(),
        sprite.getAngle(),
        sprite.getAir(), sprite.getRolling(),
        sprite.getGroundMode().ordinal(),
        engineDiag);
    continue;
}
```

- [ ] **Step 4: Update the narrow regression and debug tests**

In `TestS1Mz1LostRingCollectionOrderRegression`, replace the branch:

```java
if (trace.isLagFrame(i)) {
    fixture.skipFrameFromRecording();
} else {
    fixture.stepFrameFromRecording();
}
```

with:

```java
TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
TraceExecutionPhase phase = TraceExecutionModel.forGame(meta.game()).phaseFor(previous, expected);
if (phase == TraceExecutionPhase.VBLANK_ONLY) {
    fixture.skipFrameFromRecording();
} else {
    fixture.stepFrameFromRecording();
}
```

In both debug tests, add:

```java
TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
TraceExecutionPhase phase = TraceExecutionModel.forGame(meta.game()).phaseFor(previous, expected);
System.out.printf("phase=%s gameplay=%04X vblank=%04X lag=%04X%n",
        phase,
        expected.gameplayFrameCounter(),
        expected.vblankCounter(),
        expected.lagCounter());
```

This snippet assumes `meta` is the visible `TraceMetadata` instance in scope. In the credits-demo replay there is no `meta`, so use the literal `"s1"` as shown in Step 3.

- [ ] **Step 5: Remove the heuristic**

Delete `TraceData.isLagFrame(...)` after all five call sites are migrated. Do not leave both models alive.

- [ ] **Step 6: Run the replay-focused tests**

Run: `mvn -q "-Dtest=TestTraceExecutionModel,TestS1Mz1LostRingCollectionOrderRegression,DebugS1Ghz1RingParity,DebugS1Mz1RingParity" test`

Expected:

- `BUILD SUCCESS` for unit tests
- debug traces log `phase=... gameplay=... vblank=... lag=...`

- [ ] **Step 7: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java src/test/java/com/openggf/tests/trace/AbstractCreditsDemoTraceReplayTest.java src/test/java/com/openggf/tests/trace/s1/TestS1Mz1LostRingCollectionOrderRegression.java src/test/java/com/openggf/tests/trace/DebugS1Ghz1RingParity.java src/test/java/com/openggf/tests/trace/DebugS1Mz1RingParity.java src/test/java/com/openggf/tests/trace/TraceData.java
git commit -m "test: migrate replay callers to counter-driven lag model"
```

### Task 4: Preserve Existing Behavior With Old Fixtures Before Re-Recording

**Files:**
- None

- [ ] **Step 1: Run an explicit compatibility checkpoint**

Task 2 already adds the parser coverage for the synthetic v2.2 fixture. The purpose of this task is the suite-level migration checkpoint: run the existing Sonic 1 replay tests against the current checked-in traces before re-recording anything.

Run:

```bash
mvn -q "-Dtest=TestTraceDataParsing,*S1*TraceReplay*" test
```

Expected:

- existing checked-in traces still parse
- replay suite still runs using the new counter model with fallback defaults

### Task 5: Update the Sonic 1 Recorder and Docs to v3

**Files:**
- Modify: `tools/bizhawk/s1_trace_recorder.lua`
- Modify: `tools/bizhawk/README.md`
- Modify: `docs/guide/contributing/trace-replay.md`

- [ ] **Step 1: Update the S1 CSV schema**

Change the header in `tools/bizhawk/s1_trace_recorder.lua` to:

```lua
physics_file:write("frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode,"
    .. "x_sub,y_sub,routine,camera_x,camera_y,rings,status_byte,gameplay_frame_counter,stand_on_obj,"
    .. "vblank_counter,lag_counter\n")
```

Use:

```lua
local ADDR_FRAMECOUNT = 0xFE04
local ADDR_VBLA_WORD = 0xFE0E
```

Populate:

```lua
local gameplay_frame_counter = mainmemory.read_u16_be(ADDR_FRAMECOUNT)
local vblank_counter = mainmemory.read_u16_be(ADDR_VBLA_WORD)
local lag_counter = 0
```

Write `trace_schema = 3` to `metadata.json`.

- [ ] **Step 2: Update docs, replacing the heuristic wording**

In both docs files, replace any explanation that says lag is inferred from unchanged physics with:

```md
Replay phase is derived from recorded ROM counters:

- `gameplay_frame_counter` changes only when the level main loop completed
- `vblank_counter` changes on every VBlank
- `lag_counter` is diagnostic where the ROM exposes it
```

- [ ] **Step 3: Re-record and validate S1**

Run the S1 recorder, copy the trace output into the checked-in S1 trace fixtures, then run:

```bash
mvn -q "-Dtest=TestTraceDataParsing,*S1*TraceReplay*" test
```

Expected:

- parser accepts v3 traces
- Sonic 1 replay tests still run end-to-end

- [ ] **Step 4: Commit**

```bash
git add tools/bizhawk/s1_trace_recorder.lua tools/bizhawk/README.md docs/guide/contributing/trace-replay.md
git commit -m "feat: upgrade sonic 1 trace recorder to v3 execution counters"
```

### Task 6a: Add Sonic 2 Recorder Support

**Files:**
- Create: `tools/bizhawk/s2_trace_recorder.lua`
- Modify: `tools/bizhawk/README.md`

- [ ] **Step 1: Copy the S1 recorder structure**

Create `s2_trace_recorder.lua` using the same v3 column order as the S1 recorder.

- [ ] **Step 2: Use the resolved S2 addresses**

For S2 use:

- `Level_frame_counter = 0xFE04`
- `Vint_runcount+2 = 0xFE0E` (low word of the `Vint_runcount` longword at `0xFE0C–0xFE0F`; see `docs/s2disasm/s2.constants.asm:1663-1672`)

- [ ] **Step 3: Populate the diagnostic lag counter**

For S2:

```lua
local lag_counter = 0
```

For S3K:

```lua
- [ ] **Step 4: Document the new recorder**

Add a usage example for `s2_trace_recorder.lua` to `tools/bizhawk/README.md`.

- [ ] **Step 5: Commit**

```bash
git add tools/bizhawk/s2_trace_recorder.lua tools/bizhawk/README.md
git commit -m "feat: add sonic 2 v3 trace recorder"
```

### Task 6b: Add Sonic 3K Recorder Support After Address Research

**Files:**
- Create: `tools/bizhawk/s3k_trace_recorder.lua`
- Modify: `tools/bizhawk/README.md`

- [ ] **Step 1: Copy the S1 recorder structure**

Create `s3k_trace_recorder.lua` using the same v3 column order as the S1 recorder.

- [ ] **Step 2: Use the researched S3K addresses**

Copy the exact S3K hex values from `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md`.

Do not hardcode guessed addresses.

- [ ] **Step 3: Populate the diagnostic lag counter**

For S3K:

```lua
local lag_counter = mainmemory.read_u16_be(ADDR_LAG_FRAME_COUNT)
```

- [ ] **Step 4: Document the new recorder**

Add a usage example for `s3k_trace_recorder.lua` to `tools/bizhawk/README.md`.

- [ ] **Step 5: Commit**

```bash
git add tools/bizhawk/s3k_trace_recorder.lua tools/bizhawk/README.md
git commit -m "feat: add sonic 3k v3 trace recorder"
```

## Self-Review

- Spec coverage:
  - Cross-game lag behavior is documented first: Task 1.
  - Field duplication is removed by renaming `vFrameCount`: Task 2.
  - All replay callers, including credits demos, migrate together: Task 3.
  - Old fixtures remain valid: Task 4.
  - Recorder work is split into S1 now, S2 independently, and S3K after address research: Tasks 5, 6a, and 6b.
- Placeholder scan:
  - The previous empty runner stub is gone.
  - The previous S2/S3K Lua address placeholders are replaced by a gated research doc requirement.
  - Every replay-caller migration now has a concrete before/after code block.
- Type consistency:
  - The trace fields are `gameplayFrameCounter`, `vblankCounter`, and `lagCounter`.
  - The phase API is `TraceExecutionPhase` plus `TraceExecutionModel`.

## Execution Handoff

Plan complete and saved to `docs/architecture/plans/2026-04-19-trace-lag-model.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
