# S3K End-to-End Trace Fixture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the authoritative Sonic 3&K AIZ intro-to-HCZ BK2 trace fixture, the elastic-window replay harness support it needs, and the default-suite replay test that stays green without faking ROM-length decompression stalls.

**Architecture:** Keep `physics.csv` on the existing schema-v3 path and add the new S3K semantics entirely through `aux_state.jsonl` checkpoint and `zone_act_state` events. On replay, keep the current strict binder for normal frames, but switch the S3K path to a two-cursor elastic-window controller: one cursor keeps BK2/input and execution-model advancement aligned with the recorded trace stream, while a separate strict-comparison cursor re-anchors at the recorded exit checkpoint frame.

**Tech Stack:** BizHawk 2.11 Lua recorder scripts, Java 21, JUnit 5, existing trace-replay test harness (`TraceData`, `TraceBinder`, `AbstractTraceReplayTest`, `DivergenceReport`), S3K disassembly under `docs/skdisasm/`.

---

## File Map

| Path | Action | Responsibility |
| --- | --- | --- |
| `docs/architecture/research/2026-04-21-s3k-trace-addresses.md` | Create or replace | Freeze the RAM-backed checkpoint signals and the engine mirrors used by recorder/replay detection. |
| `docs/architecture/designs/2026-04-21-s3k-end-to-end-trace-fixture-design.md` | Already updated | Final source of truth for the clarified elastic-window rules. |
| `tools/bizhawk/s3k_trace_recorder.lua` | Modify | Add the end-to-end profile, checkpoint emission, `zone_act_state`, deterministic same-frame ordering, and metadata needed for the real fixture. |
| `tools/bizhawk/record_s3k_trace.bat` | Modify if needed | Keep the S3K launcher aligned with the recorder profile and output directory shape. |
| `tools/bizhawk/README.md` | Modify | Document the end-to-end S3K recorder mode and the authoritative fixture import flow. |
| `docs/guide/contributing/trace-replay.md` | Modify | Contributor-facing instructions for recording/regenerating the S3K fixture. |
| `src/test/java/com/openggf/tests/trace/TraceEvent.java` | Modify | Add typed `checkpoint` and `zone_act_state` aux events. |
| `src/test/java/com/openggf/tests/trace/TraceData.java` | Modify | Add helpers for finding the latest checkpoint / zone-act event at or before a frame. |
| `src/test/java/com/openggf/tests/trace/TraceEventFormatter.java` | Modify | Add compact summaries for new checkpoint/state events. |
| `src/test/java/com/openggf/tests/trace/DivergenceReport.java` | Modify | Surface latest checkpoint and latest `zone_act_state` in summary/json/context. |
| `src/test/java/com/openggf/tests/trace/TraceBinder.java` | Modify | Add a report-construction path that preserves the current comparison contract while attaching trace aux context. |
| `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` | Modify | Replace the single-index strict replay loop with the two-cursor elastic-window loop for S3K. |
| `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java` | Modify | Parser tests for `checkpoint` and `zone_act_state`. |
| `src/test/java/com/openggf/tests/trace/TestTraceEventFormatting.java` | Modify | Summary-format tests for new aux event types. |
| `src/test/java/com/openggf/tests/trace/TestDivergenceReport.java` | Modify | Report-context tests for checkpoint/state lookups. |
| `src/test/java/com/openggf/tests/trace/s3k/S3kReplayCheckpointDetector.java` | Create | Pure replay-side detector keyed from engine state, not trace aux data. |
| `src/test/java/com/openggf/tests/trace/s3k/S3kElasticWindowController.java` | Create | Two-cursor controller for strict replay vs elastic windows. |
| `src/test/java/com/openggf/tests/trace/s3k/TestS3kReplayCheckpointDetector.java` | Create | Unit tests for required/optional checkpoint detection and sticky ordering. |
| `src/test/java/com/openggf/tests/trace/s3k/TestS3kElasticWindowController.java` | Create | Unit tests for entry, exit, drift budget, and re-anchor behavior. |
| `src/test/java/com/openggf/tests/trace/s3k/TestS3kAizTraceReplay.java` | Create | Authoritative default-suite replay test against the real fixture. |
| `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/` | Create | Authoritative BK2 + `metadata.json` + `physics.csv` + `aux_state.jsonl`. |
| `CHANGELOG.md` | Modify | Record the S3K end-to-end trace fixture landing. |

## Task 1: Freeze Recorder And Replay Signal Sources

**Files:**
- Create or replace: `docs/architecture/research/2026-04-21-s3k-trace-addresses.md`
- Modify if duplicated: `docs/architecture/research/2026-04-20-s3k-trace-addresses.md`

- [ ] **Step 1: Verify the checkpoint signals already identified in the research notes**

Run:

```powershell
rg -n "Apparent_act|Events_fg_5|_unkFAA8|Current_zone_and_act|move_lock|Ctrl_1_locked" `
  docs/architecture/research/2026-04-21-s3k-trace-addresses.md `
  docs/skdisasm/sonic3k.constants.asm `
  docs/skdisasm/sonic3k.asm `
  src/main/java/com/openggf/level/LevelManager.java `
  src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java `
  src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java `
  src/main/java/com/openggf/game/sonic3k/objects/Aiz2BossEndSequenceState.java `
  src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java `
  src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java
```

Expected: all six symbols appear in the research note and at least one matching disassembly or engine location. Done means each checkpoint row in Step 2 has a concrete recorder predicate, a concrete BizHawk offset, and a named engine mirror.

- [ ] **Step 2: Write the frozen checkpoint table**

Create the research note with this exact table, replacing the older partial checkpoint section:

```md
## Required Checkpoints

| Checkpoint | Recorder-side signal | BizHawk offset(s) | Replay-side engine mirror | Evidence |
| --- | --- | --- | --- | --- |
| `intro_begin` | emit on recorded frame `0` unconditionally | n/a | replay bootstrap frame `0` | spec anchor |
| `gameplay_start` | `Level_started_flag != 0 && Game_mode == $0C && Player_1 + move_lock == 0 && Ctrl_1_locked == 0` | `$F711`, `$F600`, `$B432`, `$F7CA` | `GameServices.level().getCurrentZone() == 0 && fixture.sprite().getMoveLockTimer() == 0 && !fixture.sprite().isControlLocked()` | `2026-04-21-s3k-trace-addresses.md`, `AbstractPlayableSprite#getMoveLockTimer`, `isControlLocked` |
| `aiz1_fire_transition_begin` | rising edge of `Events_fg_5 != 0` while `Current_zone_and_act == $0000` | `$EEC6`, `$FE14` | `captureS3kProbe(replayFrame, fixture.sprite()).fireTransitionActive()` backed by `GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager && manager.isFireTransitionActive()` | `sonic3k.asm:104613-104639`, `Sonic3kLevelEventManager#isFireTransitionActive`, `Sonic3kAIZEvents#isFireTransitionActive` |
| `aiz2_reload_resume` | `Current_zone_and_act == $0001 && Apparent_act == $00` | `$FE14`, `$EE51` | `GameServices.level().getCurrentZone() == 0 && GameServices.level().getCurrentAct() == 1 && GameServices.level().getApparentAct() == 0` | `sonic3k.asm:104722-104770`, `LevelManager#getApparentAct` |
| `aiz2_main_gameplay` | `Current_zone_and_act == $0001 && Player_1 + move_lock == 0 && Ctrl_1_locked == 0` | `$FE14`, `$B432`, `$F7CA` | `GameServices.level().getCurrentZone() == 0 && GameServices.level().getCurrentAct() == 1 && fixture.sprite().getMoveLockTimer() == 0 && !fixture.sprite().isControlLocked()` | disasm + `AbstractPlayableSprite` mirrors |
| `hcz_handoff_begin` | falling edge of `_unkFAA8` from non-zero to `0` during AIZ2 post-boss flow | `$FAA8` | `Aiz2BossEndSequenceState.isCutsceneOverrideObjectsActive() && !hasResultsScreen && fixture.sprite().isControlLocked()` | `sonic3k.asm:138248-138277`, `sonic3k.asm:62702-62712`, `Aiz2BossEndSequenceState` |
| `hcz_handoff_complete` | `Current_zone_and_act == $0100 && Player_1 + move_lock == 0 && Ctrl_1_locked == 0` | `$FE14`, `$B432`, `$F7CA` | `GameServices.level().getCurrentZone() == 1 && GameServices.level().getCurrentAct() == 0 && fixture.sprite().getMoveLockTimer() == 0 && !fixture.sprite().isControlLocked()` | `sonic3k.asm:180637-180643`, engine zone/act mirrors |
```

- [ ] **Step 3: Freeze the same-frame ordering and optional-checkpoint policy in the note**

Add this section verbatim to the research note so recorder and replay implementation use the same contract:

```md
## Emission And Detection Order

Within a single frame, recorder emission order is:

1. `zone_act_state`
2. `checkpoint`
3. existing object / routine / mode diagnostics in fixed detector order

Optional checkpoints (`aiz2_signpost_begin`, `aiz2_results_begin`) are diagnostics-only.
They may appear in recorder output and replay diagnostics, but they never drive elastic-window entry, exit, or pass/fail decisions.
```

- [ ] **Step 4: Reconcile the research file path with the spec**

Run:

```powershell
rg -n "2026-04-21-s3k-trace-addresses|2026-04-20-s3k-trace-addresses" docs/architecture/designs docs/architecture/plans docs/architecture/research
```

Expected: all forward references point at `docs/architecture/research/2026-04-21-s3k-trace-addresses.md`. If the `2026-04-20` file is only an interim draft, move its confirmed content into the `2026-04-21` note and remove or stop referencing the stale path.

- [ ] **Step 5: Commit the research freeze**

Run:

```bash
git add docs/architecture/research/2026-04-21-s3k-trace-addresses.md docs/architecture/designs/2026-04-21-s3k-end-to-end-trace-fixture-design.md
git commit -m "docs: freeze s3k trace checkpoint signals"
```

Expected: one documentation-only commit that locks the signal contract before code changes.

## Task 2: Add Typed Checkpoint And Zone/Act Aux Events

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/TraceEvent.java`
- Modify: `src/test/java/com/openggf/tests/trace/TraceData.java`
- Modify: `src/test/java/com/openggf/tests/trace/TraceEventFormatter.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceEventFormatting.java`

- [ ] **Step 1: Write failing parser and formatter tests first**

Add these tests:

```java
@Test
void parsesCheckpointEvent() {
    ObjectMapper mapper = new ObjectMapper();
    TraceEvent event = TraceEvent.parseJsonLine(
        """
        {"frame":1200,"event":"checkpoint","name":"aiz2_main_gameplay","actual_zone_id":0,"actual_act":1,"apparent_act":0,"game_mode":12,"notes":"resume strict replay"}
        """.trim(),
        mapper);

    assertInstanceOf(TraceEvent.Checkpoint.class, event);
    TraceEvent.Checkpoint checkpoint = (TraceEvent.Checkpoint) event;
    assertEquals("aiz2_main_gameplay", checkpoint.name());
    assertEquals(0, checkpoint.actualZoneId());
    assertEquals(1, checkpoint.actualAct());
    assertEquals(0, checkpoint.apparentAct());
    assertEquals(12, checkpoint.gameMode());
    assertEquals("resume strict replay", checkpoint.notes());
}

@Test
void latestCheckpointLookupReturnsNearestEarlierCheckpoint() throws IOException {
    Path dir = Files.createTempDirectory("s3k-trace");
    Files.writeString(dir.resolve("metadata.json"), """
        {
          "game": "s3k",
          "zone": "aiz",
          "zone_id": 0,
          "act": 1,
          "bk2_frame_offset": 0,
          "trace_frame_count": 3,
          "start_x": "0x0080",
          "start_y": "0x03A0",
          "recording_date": "2026-04-21",
          "lua_script_version": "3.1-s3k",
          "trace_schema": 3,
          "csv_version": 4,
          "rom_checksum": "test"
        }
        """);
    Files.writeString(dir.resolve("physics.csv"), """
        frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode,x_sub,y_sub,routine,camera_x,camera_y,rings,status_byte,gameplay_frame_counter,stand_on_obj,vblank_counter,lag_counter
        0000,0000,0080,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0001,00,0001,0000
        0001,0000,0080,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0002,00,0002,0000
        0002,0000,0080,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0003,00,0003,0000
        """);
    Files.writeString(dir.resolve("aux_state.jsonl"), """
        {"frame":0,"event":"checkpoint","name":"intro_begin","actual_zone_id":null,"actual_act":null,"apparent_act":null,"game_mode":12}
        {"frame":1,"event":"zone_act_state","actual_zone_id":0,"actual_act":0,"apparent_act":0,"game_mode":12}
        {"frame":2,"event":"checkpoint","name":"gameplay_start","actual_zone_id":0,"actual_act":0,"apparent_act":0,"game_mode":12}
        """);

    TraceData data = TraceData.load(dir);
    TraceEvent.Checkpoint checkpoint = data.latestCheckpointAtOrBefore(2);
    TraceEvent.ZoneActState state = data.latestZoneActStateAtOrBefore(2);

    assertEquals("gameplay_start", checkpoint.name());
    assertEquals(0, state.actualZoneId());
    assertEquals(0, state.actualAct());
    assertEquals(0, state.apparentAct());
}
```

Run:

```bash
mvn -Dtest=TestTraceDataParsing,TestTraceEventFormatting test
```

Expected: FAIL because `TraceEvent` does not yet have typed `Checkpoint` or `ZoneActState` records.

- [ ] **Step 2: Add the new typed aux events to `TraceEvent`**

Implement records and parse branches along these lines:

```java
record Checkpoint(
        int frame,
        String name,
        Integer actualZoneId,
        Integer actualAct,
        Integer apparentAct,
        Integer gameMode,
        String notes) implements TraceEvent {}

record ZoneActState(
        int frame,
        Integer actualZoneId,
        Integer actualAct,
        Integer apparentAct,
        Integer gameMode) implements TraceEvent {}
```

Use nullable `Integer` payloads so `intro_begin` can preserve `null` values before gameplay state is observable.

- [ ] **Step 3: Add `TraceData` lookup helpers for replay diagnostics**

Add helpers that scan backward by frame:

```java
public TraceEvent.Checkpoint latestCheckpointAtOrBefore(int frame) {
    List<Integer> frames = new ArrayList<>(eventsByFrame.keySet());
    frames.sort(Comparator.reverseOrder());
    for (int candidateFrame : frames) {
        if (candidateFrame > frame) {
            continue;
        }
        for (TraceEvent event : eventsByFrame.getOrDefault(candidateFrame, Collections.emptyList())) {
            if (event instanceof TraceEvent.Checkpoint checkpoint) {
                return checkpoint;
            }
        }
    }
    return null;
}

public TraceEvent.ZoneActState latestZoneActStateAtOrBefore(int frame) {
    List<Integer> frames = new ArrayList<>(eventsByFrame.keySet());
    frames.sort(Comparator.reverseOrder());
    for (int candidateFrame : frames) {
        if (candidateFrame > frame) {
            continue;
        }
        for (TraceEvent event : eventsByFrame.getOrDefault(candidateFrame, Collections.emptyList())) {
            if (event instanceof TraceEvent.ZoneActState state) {
                return state;
            }
        }
    }
    return null;
}
```

Keep the existing `eventsByFrame` structure; do not build a second aux index yet.

- [ ] **Step 4: Add compact formatter output for the new event types**

Add summary strings that remain short enough for context windows:

```java
private static String nullableInt(Integer value) {
    return value == null ? "null" : Integer.toString(value);
}

case TraceEvent.Checkpoint checkpoint ->
        String.format("cp %s z=%s a=%s ap=%s gm=%s",
                checkpoint.name(),
                nullableInt(checkpoint.actualZoneId()),
                nullableInt(checkpoint.actualAct()),
                nullableInt(checkpoint.apparentAct()),
                nullableInt(checkpoint.gameMode()));
case TraceEvent.ZoneActState state ->
        String.format("zoneact z=%s a=%s ap=%s gm=%s",
                nullableInt(state.actualZoneId()),
                nullableInt(state.actualAct()),
                nullableInt(state.apparentAct()),
                nullableInt(state.gameMode()));
```

- [ ] **Step 5: Run the parser/formatter tests again**

Run:

```bash
mvn -Dtest=TestTraceDataParsing,TestTraceEventFormatting test
```

Expected: PASS. New tests confirm typed parsing, null-safe payload handling, and readable summaries.

- [ ] **Step 6: Commit the aux-event model**

Run:

```bash
git add src/test/java/com/openggf/tests/trace/TraceEvent.java src/test/java/com/openggf/tests/trace/TraceData.java src/test/java/com/openggf/tests/trace/TraceEventFormatter.java src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java src/test/java/com/openggf/tests/trace/TestTraceEventFormatting.java
git commit -m "test: add s3k checkpoint aux event types"
```

## Task 3: Build A Pure Replay-Side Checkpoint Detector And Elastic Controller

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s3k/S3kReplayCheckpointDetector.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/S3kElasticWindowController.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kReplayCheckpointDetector.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kElasticWindowController.java`

- [ ] **Step 1: Write failing unit tests for the detector contract**

Add tests that prove:

```java
@Test
void introBeginEmitsAtReplayFrameZero() {
    S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

    TraceEvent.Checkpoint hit = detector.observe(new S3kCheckpointProbe(
            0, null, null, null, 12, 0, false, false, false, false, false, false));

    assertNotNull(hit);
    assertEquals("intro_begin", hit.name());
    assertNull(hit.actualZoneId());
}

@Test
void requiredCheckpointsEmitOnceInFixedOrder() {
    S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

    assertEquals("intro_begin", detector.observe(new S3kCheckpointProbe(
            0, null, null, null, 12, 0, false, false, false, false, false, false)).name());
    assertEquals("gameplay_start", detector.observe(new S3kCheckpointProbe(
            10, 0, 0, 0, 12, 0, false, false, false, false, false, true)).name());
    assertNull(detector.observe(new S3kCheckpointProbe(
            11, 0, 0, 0, 12, 0, false, false, false, false, false, true)));
    assertEquals("aiz1_fire_transition_begin", detector.observe(new S3kCheckpointProbe(
            200, 0, 0, 0, 12, 5, true, true, false, false, false, true)).name());
}

@Test
void optionalCheckpointsAreReportedButNotRequired() {
    S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
    detector.observe(new S3kCheckpointProbe(0, null, null, null, 12, 0, false, false, false, false, false, false));
    detector.observe(new S3kCheckpointProbe(10, 0, 0, 0, 12, 0, false, false, false, false, false, true));

    TraceEvent.Checkpoint hit = detector.observe(new S3kCheckpointProbe(
            900, 0, 1, 0, 12, 0, false, false, false, true, false, true));

    assertNotNull(hit);
    assertEquals("aiz2_signpost_begin", hit.name());
    assertFalse(detector.requiredCheckpointNamesReached().contains("aiz2_signpost_begin"));
}

@Test
void hczHandoffCompleteRequiresZoneActAndMoveLockClear() {
    S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
    detector.observe(new S3kCheckpointProbe(0, null, null, null, 12, 0, false, false, false, false, false, false));
    detector.observe(new S3kCheckpointProbe(10, 0, 0, 0, 12, 0, false, false, false, false, false, true));
    detector.observe(new S3kCheckpointProbe(200, 0, 0, 0, 12, 5, true, true, false, false, false, true));
    detector.observe(new S3kCheckpointProbe(260, 0, 1, 0, 12, 10, true, false, false, false, false, true));
    detector.observe(new S3kCheckpointProbe(320, 0, 1, 0, 12, 0, false, false, false, false, false, true));
    detector.observe(new S3kCheckpointProbe(1600, 0, 1, 1, 12, 1, true, false, true, false, false, true));

    assertNull(detector.observe(new S3kCheckpointProbe(
            1700, 1, 0, 0, 12, 5, false, false, true, false, false, true)));
    TraceEvent.Checkpoint hit = detector.observe(new S3kCheckpointProbe(
            1701, 1, 0, 0, 12, 0, false, false, true, false, false, true));

    assertNotNull(hit);
    assertEquals("hcz_handoff_complete", hit.name());
}
```

The tests should drive the detector with a pure probe record rather than a live engine object, so the logic is deterministic and cheap to run.

- [ ] **Step 2: Define the probe and checkpoint-hit model**

Use a small immutable probe instead of reading engine singletons in the detector:

```java
record S3kCheckpointProbe(
        int replayFrame,
        Integer actualZoneId,
        Integer actualAct,
        Integer apparentAct,
        Integer gameMode,
        int moveLock,
        boolean ctrlLocked,
        boolean fireTransitionActive,
        boolean hczTransitionActive,
        boolean signpostActive,
        boolean resultsActive,
        boolean levelStarted) {}
```

Return typed hits that reuse the same payload shape as the aux `Checkpoint` record.

- [ ] **Step 3: Implement sticky checkpoint detection in fixed evaluation order**

Implement the detector with ordered predicate checks, not hash iteration:

```java
private static final List<String> REQUIRED_ORDER = List.of(
        "intro_begin",
        "gameplay_start",
        "aiz1_fire_transition_begin",
        "aiz2_reload_resume",
        "aiz2_main_gameplay",
        "hcz_handoff_begin",
        "hcz_handoff_complete");

private final Set<String> emitted = new LinkedHashSet<>();

TraceEvent.Checkpoint observe(S3kCheckpointProbe probe) {
    if (!emitted.contains("intro_begin") && probe.replayFrame() == 0) {
        return emit(probe, "intro_begin");
    }
    if (!emitted.contains("gameplay_start")
            && probe.levelStarted()
            && isLevelGameplay(probe)
            && probe.moveLock() == 0
            && !probe.ctrlLocked()) {
        return emit(probe, "gameplay_start");
    }
    if (!emitted.contains("aiz1_fire_transition_begin")
            && probe.actualZoneId() != null
            && probe.actualZoneId() == 0
            && probe.actualAct() != null
            && probe.actualAct() == 0
            && probe.fireTransitionActive()) {
        return emit(probe, "aiz1_fire_transition_begin");
    }
    if (!emitted.contains("aiz2_reload_resume")
            && probe.actualZoneId() != null
            && probe.actualZoneId() == 0
            && probe.actualAct() != null
            && probe.actualAct() == 1
            && probe.apparentAct() != null
            && probe.apparentAct() == 0) {
        return emit(probe, "aiz2_reload_resume");
    }
    if (!emitted.contains("aiz2_main_gameplay")
            && probe.actualZoneId() != null
            && probe.actualZoneId() == 0
            && probe.actualAct() != null
            && probe.actualAct() == 1
            && probe.moveLock() == 0
            && !probe.ctrlLocked()) {
        return emit(probe, "aiz2_main_gameplay");
    }
    if (!emitted.contains("hcz_handoff_begin")
            && probe.hczTransitionActive()) {
        return emit(probe, "hcz_handoff_begin");
    }
    if (!emitted.contains("hcz_handoff_complete")
            && probe.actualZoneId() != null
            && probe.actualZoneId() == 1
            && probe.actualAct() != null
            && probe.actualAct() == 0
            && probe.moveLock() == 0
            && !probe.ctrlLocked()) {
        return emit(probe, "hcz_handoff_complete");
    }
    if (!emitted.contains("aiz2_signpost_begin")
            && probe.signpostActive()) {
        return emit(probe, "aiz2_signpost_begin");
    }
    if (!emitted.contains("aiz2_results_begin")
            && probe.resultsActive()) {
        return emit(probe, "aiz2_results_begin");
    }
    return null;
}

private static boolean isLevelGameplay(S3kCheckpointProbe probe) {
    return probe.gameMode() != null && probe.gameMode() == 0x0C;
}

private TraceEvent.Checkpoint emit(S3kCheckpointProbe probe, String name) {
    emitted.add(name);
    return new TraceEvent.Checkpoint(
            probe.replayFrame(),
            name,
            probe.actualZoneId(),
            probe.actualAct(),
            probe.apparentAct(),
            probe.gameMode(),
            null);
}

Set<String> requiredCheckpointNamesReached() {
    Set<String> required = new LinkedHashSet<>();
    for (String checkpointName : REQUIRED_ORDER) {
        if (emitted.contains(checkpointName)) {
            required.add(checkpointName);
        }
    }
    return required;
}
```

- [ ] **Step 4: Write failing controller tests for elastic-window behavior**

Add controller tests that prove:

```java
@Test
void windowEntryKeepsStrictValidationOnEntryFrame() {
    S3kElasticWindowController controller = new S3kElasticWindowController(Map.of(
            "intro_begin", 0,
            "gameplay_start", 12,
            "aiz1_fire_transition_begin", 200,
            "aiz2_main_gameplay", 260));

    controller.onEntryFrameValidated(new TraceEvent.Checkpoint(200, "aiz1_fire_transition_begin", 0, 0, 0, 12, null));

    assertFalse(controller.isStrictComparisonEnabled());
    assertEquals(200, controller.strictTraceIndex());
    assertEquals(200, controller.driveTraceIndex());
}

@Test
void windowExitReanchorsStrictComparisonAtRecordedExitPlusOne() {
    S3kElasticWindowController controller = new S3kElasticWindowController(Map.of(
            "aiz1_fire_transition_begin", 200,
            "aiz2_main_gameplay", 260));

    controller.onEntryFrameValidated(new TraceEvent.Checkpoint(200, "aiz1_fire_transition_begin", 0, 0, 0, 12, null));
    for (int i = 0; i < 17; i++) {
        controller.onEngineTick();
        controller.advanceDriveCursor();
    }

    controller.onEngineCheckpoint(new TraceEvent.Checkpoint(217, "aiz2_main_gameplay", 0, 1, 0, 12, null));

    assertTrue(controller.isStrictComparisonEnabled());
    assertEquals(261, controller.strictTraceIndex());
    assertEquals(261, controller.driveTraceIndex());
}

@Test
void driftBudgetFailureTriggersStructuralDivergence() {
    S3kElasticWindowController controller = new S3kElasticWindowController(Map.of(
            "intro_begin", 0,
            "gameplay_start", 10));

    controller.onEntryFrameValidated(new TraceEvent.Checkpoint(0, "intro_begin", null, null, null, 12, null));
    for (int i = 0; i <= 190; i++) {
        controller.onEngineTick();
    }

    IllegalStateException ex = assertThrows(IllegalStateException.class, controller::assertWithinDriftBudget);
    assertTrue(ex.getMessage().contains("intro_begin"));
}

@Test
void optionalCheckpointDoesNotOpenOrCloseAWindow() {
    S3kElasticWindowController controller = new S3kElasticWindowController(Map.of(
            "aiz1_fire_transition_begin", 200,
            "aiz2_main_gameplay", 260));

    controller.onEntryFrameValidated(new TraceEvent.Checkpoint(200, "aiz1_fire_transition_begin", 0, 0, 0, 12, null));
    controller.onEngineCheckpoint(new TraceEvent.Checkpoint(215, "aiz2_signpost_begin", 0, 1, 0, 12, null));

    assertFalse(controller.isStrictComparisonEnabled());
    assertEquals(200, controller.strictTraceIndex());
}
```

- [ ] **Step 5: Implement the two-cursor controller**

Use separate drive and strict cursors:

```java
record ReplayCursorState(
        int driveTraceIndex,
        int strictTraceIndex,
        boolean strictComparisonEnabled) {}

private static final Map<String, String> ELASTIC_WINDOWS = Map.of(
        "intro_begin", "gameplay_start",
        "aiz1_fire_transition_begin", "aiz2_main_gameplay");

private final Map<String, Integer> traceCheckpointFrames;
private int driveTraceIndex;
private int strictTraceIndex;
private String openEntryName;
private String expectedExitName;
private int entryTraceFrame;
private int exitTraceFrame;
private int engineTicksInsideWindow;
private int maxEngineSpan;

void onEntryFrameValidated(TraceEvent.Checkpoint checkpoint) {
    String exitName = ELASTIC_WINDOWS.get(checkpoint.name());
    if (exitName == null) {
        return;
    }
    openEntryName = checkpoint.name();
    expectedExitName = exitName;
    entryTraceFrame = traceCheckpointFrames.get(checkpoint.name());
    exitTraceFrame = traceCheckpointFrames.get(exitName);
    engineTicksInsideWindow = 0;
    maxEngineSpan = (exitTraceFrame - entryTraceFrame) + Math.max(180, exitTraceFrame - entryTraceFrame);
    driveTraceIndex = entryTraceFrame;
    strictTraceIndex = entryTraceFrame;
}

void onEngineTick() {
    if (openEntryName != null) {
        engineTicksInsideWindow++;
    }
}

void assertWithinDriftBudget() {
    if (openEntryName != null && engineTicksInsideWindow > maxEngineSpan) {
        throw new IllegalStateException("Elastic window drift budget exhausted for " + openEntryName);
    }
}

void advanceDriveCursor() {
    driveTraceIndex++;
    if (openEntryName == null) {
        strictTraceIndex = driveTraceIndex;
    }
}

void onEngineCheckpoint(TraceEvent.Checkpoint checkpoint) {
    if (openEntryName == null) {
        return;
    }
    if (checkpoint.name().equals(expectedExitName)) {
        strictTraceIndex = exitTraceFrame + 1;
        driveTraceIndex = exitTraceFrame + 1;
        openEntryName = null;
        expectedExitName = null;
        return;
    }
    if (ELASTIC_WINDOWS.containsKey(checkpoint.name())) {
        throw new IllegalStateException(
                "Out-of-order checkpoint " + checkpoint.name() + " while waiting for " + expectedExitName);
    }
}

boolean isStrictComparisonEnabled() {
    return openEntryName == null;
}

int driveTraceIndex() {
    return driveTraceIndex;
}

int strictTraceIndex() {
    return strictTraceIndex;
}
```

- [ ] **Step 6: Run the detector/controller unit tests**

Run:

```bash
mvn -Dtest=TestS3kReplayCheckpointDetector,TestS3kElasticWindowController test
```

Expected: PASS. This gives a fast, deterministic proof that the elastic-window rules work before touching the end-to-end harness.

- [ ] **Step 7: Commit the detector/controller layer**

Run:

```bash
git add src/test/java/com/openggf/tests/trace/s3k/S3kReplayCheckpointDetector.java src/test/java/com/openggf/tests/trace/s3k/S3kElasticWindowController.java src/test/java/com/openggf/tests/trace/s3k/TestS3kReplayCheckpointDetector.java src/test/java/com/openggf/tests/trace/s3k/TestS3kElasticWindowController.java
git commit -m "test: add s3k elastic replay controller"
```

## Task 4: Integrate Elastic Replay And Aux-Aware Divergence Reporting

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/TraceBinder.java`
- Modify: `src/test/java/com/openggf/tests/trace/DivergenceReport.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestDivergenceReport.java`

- [ ] **Step 1: Write failing report-context tests**

Add tests like:

```java
private TraceData loadAuxContextTrace() throws IOException {
    Path dir = Files.createTempDirectory("divergence-report");
    Files.writeString(dir.resolve("metadata.json"), """
        {
          "game": "s3k",
          "zone": "aiz",
          "zone_id": 0,
          "act": 1,
          "bk2_frame_offset": 0,
          "trace_frame_count": 6,
          "start_x": "0x0080",
          "start_y": "0x03A0",
          "recording_date": "2026-04-21",
          "lua_script_version": "3.1-s3k",
          "trace_schema": 3,
          "csv_version": 4,
          "rom_checksum": "test"
        }
        """);
    Files.writeString(dir.resolve("physics.csv"), """
        frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode,x_sub,y_sub,routine,camera_x,camera_y,rings,status_byte,gameplay_frame_counter,stand_on_obj,vblank_counter,lag_counter
        0000,0000,0080,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0001,00,0001,0000
        0001,0000,0081,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0002,00,0002,0000
        0002,0000,0082,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0003,00,0003,0000
        0003,0000,0083,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0004,00,0004,0000
        0004,0000,0084,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0005,00,0005,0000
        0005,0000,0085,03A0,0000,0000,0000,00,0,0,0,0000,0000,02,0000,0000,0000,00,0006,00,0006,0000
        """);
    Files.writeString(dir.resolve("aux_state.jsonl"), """
        {"frame":0,"event":"checkpoint","name":"intro_begin","actual_zone_id":null,"actual_act":null,"apparent_act":null,"game_mode":12}
        {"frame":1,"event":"zone_act_state","actual_zone_id":0,"actual_act":0,"apparent_act":0,"game_mode":12}
        {"frame":2,"event":"checkpoint","name":"gameplay_start","actual_zone_id":0,"actual_act":0,"apparent_act":0,"game_mode":12}
        """);
    return TraceData.load(dir);
}

private FrameComparison makeComparison(int frame, String field, Severity severity, String expected, String actual) {
    Map<String, FieldComparison> fields = new LinkedHashMap<>();
    fields.put(field, new FieldComparison(field, expected, actual, severity,
            severity == Severity.MATCH ? 0 : 1));
    return new FrameComparison(frame, fields);
}

@Test
void summaryIncludesLatestCheckpointBeforeFirstError() throws IOException {
    TraceData trace = loadAuxContextTrace();
    FrameComparison frame = makeComparison(5, "air", Severity.ERROR, "0", "1");
    DivergenceReport report = new DivergenceReport(List.of(frame), trace);

    assertTrue(report.toSummary().contains("gameplay_start"));
}

@Test
void jsonIncludesLatestZoneActStateBeforeFirstError() throws IOException {
    TraceData trace = loadAuxContextTrace();
    FrameComparison frame = makeComparison(5, "air", Severity.ERROR, "0", "1");
    DivergenceReport report = new DivergenceReport(List.of(frame), trace);

    assertTrue(report.toJson().contains("\"latest_zone_act_state_before_first_error\""));
}

@Test
void contextWindowIncludesCheckpointAndZoneActLines() throws IOException {
    TraceData trace = loadAuxContextTrace();
    FrameComparison frame = makeComparison(5, "air", Severity.ERROR, "0", "1");
    DivergenceReport report = new DivergenceReport(List.of(frame), trace);

    String context = report.getContextWindow(5, 1);
    assertTrue(context.contains("checkpoint: gameplay_start"));
    assertTrue(context.contains("zone_act_state: z=0 a=0 ap=0 gm=12"));
}
```

Run:

```bash
mvn -Dtest=TestDivergenceReport test
```

Expected: FAIL because `DivergenceReport` has no access to aux-event context yet.

- [ ] **Step 2: Add a report-construction path that carries `TraceData`**

Keep the existing constructor for old tests, and add an overload:

```java
public DivergenceReport(List<FrameComparison> comparisons, TraceData trace) {
    this.allComparisons = List.copyOf(comparisons);
    this.trace = trace;
    List<DivergenceGroup> allGroups = buildGroups(comparisons);
    this.errors = allGroups.stream()
            .filter(g -> g.severity() == Severity.ERROR)
            .toList();
    this.warnings = allGroups.stream()
            .filter(g -> g.severity() == Severity.WARNING)
            .toList();
}
```

Put the new `buildReport(TraceData)` method on `TraceBinder`, not `DivergenceReport`, because `allComparisons` is a `TraceBinder` field today:

```java
// TraceBinder.java
public DivergenceReport buildReport(TraceData trace) {
    return new DivergenceReport(allComparisons, trace);
}

// DivergenceReport.java
private final TraceData trace;

public DivergenceReport(List<FrameComparison> comparisons, TraceData trace) {
    this.allComparisons = List.copyOf(comparisons);
    this.trace = trace;
    List<DivergenceGroup> allGroups = buildGroups(comparisons);
    this.errors = allGroups.stream().filter(g -> g.severity() == Severity.ERROR).toList();
    this.warnings = allGroups.stream().filter(g -> g.severity() == Severity.WARNING).toList();
}
```

This preserves the current `TraceBinder.compareFrame(...)` contract while letting report rendering scan `TraceData` for the latest checkpoint and `zone_act_state`.

- [ ] **Step 3: Refactor the replay loop to use drive and strict cursors**

Replace the current `for (int i = 0; i < trace.frameCount(); i++)` loop with a `while` loop:

```java
int driveTraceIndex = 0;
TraceFrame previousDriveFrame = null;
S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
S3kElasticWindowController controller = new S3kElasticWindowController(loadCheckpointFrames(trace));

while (driveTraceIndex < trace.frameCount()) {
    TraceFrame driveFrame = trace.getFrame(driveTraceIndex);
    TraceExecutionPhase phase = TraceExecutionModel.forGame(meta.game()).phaseFor(previousDriveFrame, driveFrame);
    int bk2Input = phase == TraceExecutionPhase.VBLANK_ONLY
            ? fixture.skipFrameFromRecording()
            : fixture.stepFrameFromRecording();

    if (!binder.validateInput(driveFrame, bk2Input)) {
        fail(String.format(
                "Input alignment error at trace frame %d: BK2 input=0x%04X, trace input=0x%04X",
                driveTraceIndex, bk2Input, driveFrame.input()));
    }

    S3kCheckpointProbe probe = captureS3kProbe(driveFrame.frame(), fixture.sprite());
    TraceEvent.Checkpoint engineCheckpoint = detector.observe(probe);
    controller.onEngineTick();
    controller.assertWithinDriftBudget();

    if (controller.isStrictComparisonEnabled()) {
        int strictTraceIndex = controller.strictTraceIndex();
        TraceFrame expected = trace.getFrame(strictTraceIndex);
        EngineDiagnostics engineDiag = captureEngineDiagnostics(fixture.sprite());
        String romDiag = combineDiagnostics(
                expected.hasExtendedData() ? expected.formatDiagnostics() : "",
                TraceEventFormatter.summariseFrameEvents(trace.getEventsForFrame(strictTraceIndex)));

        binder.compareFrame(expected,
                fixture.sprite().getCentreX(), fixture.sprite().getCentreY(),
                fixture.sprite().getXSpeed(), fixture.sprite().getYSpeed(), fixture.sprite().getGSpeed(),
                fixture.sprite().getAngle(),
                fixture.sprite().getAir(), fixture.sprite().getRolling(),
                fixture.sprite().getGroundMode().ordinal(), romDiag, engineDiag);

        TraceEvent.Checkpoint traceCheckpoint = trace.latestCheckpointAtOrBefore(strictTraceIndex);
        if (traceCheckpoint != null && traceCheckpoint.frame() == strictTraceIndex) {
            controller.onEntryFrameValidated(traceCheckpoint);
        }

        if (firstSubDivFrame < 0 && expected.xSub() > 0) {
            firstSubDivFrame = updateFirstSubpixelDivergence(firstSubDivFrame, expected, fixture.sprite());
        }
    }

    if (engineCheckpoint != null) {
        controller.onEngineCheckpoint(engineCheckpoint);
    }

    controller.advanceDriveCursor();
    driveTraceIndex = controller.driveTraceIndex();
    previousDriveFrame = driveFrame;
}
```

Add this helper to `AbstractTraceReplayTest` so the replay loop has concrete probe capture code instead of reaching into singletons inline:

```java
private S3kCheckpointProbe captureS3kProbe(int replayFrame, AbstractPlayableSprite sprite) {
    boolean resultsActive = GameServices.level().getObjectManager().getActiveObjects().stream()
            .anyMatch(S3kResultsScreenObjectInstance.class::isInstance);
    boolean signpostActive = S3kSignpostInstance.getActiveSignpost() != null;
    boolean fireTransitionActive = GameServices.module().getLevelEventProvider() instanceof Sonic3kLevelEventManager manager
            && manager.isFireTransitionActive();
    boolean hczTransitionActive = Aiz2BossEndSequenceState.isCutsceneOverrideObjectsActive() && !resultsActive;

    return new S3kCheckpointProbe(
            replayFrame,
            GameServices.level().getCurrentZone(),
            GameServices.level().getCurrentAct(),
            GameServices.level().getApparentAct(),
            0x0C,
            sprite.getMoveLockTimer(),
            sprite.isControlLocked(),
            fireTransitionActive,
            hczTransitionActive,
            signpostActive,
            resultsActive,
            GameServices.level().getCurrentZone() == 0 || GameServices.level().getCurrentZone() == 1);
}
```

Important: inside elastic windows, continue validating BK2 input against the drive cursor. Suspend `TraceBinder.compareFrame(...)` and the `firstSubDivFrame` subpixel tracker together so neither creates false strict-frame diagnostics during elastic spans.

- [ ] **Step 4: Wire the S3K detector/controller only for S3K traces**

In `AbstractTraceReplayTest`, gate the new path on `meta.game().equals("s3k")`. Keep S1/S2 on the current strict path so this change does not risk existing replay coverage.

- [ ] **Step 5: Re-run harness and report tests**

Run:

```bash
mvn -Dtest=TestDivergenceReport,TestS3kReplayCheckpointDetector,TestS3kElasticWindowController,TestTraceDataParsing,TestTraceEventFormatting test
```

Expected: PASS. S3K elastic logic is now integrated without breaking the existing parser/report unit coverage.

- [ ] **Step 6: Commit the harness integration**

Run:

```bash
git add src/test/java/com/openggf/tests/trace/TraceBinder.java src/test/java/com/openggf/tests/trace/DivergenceReport.java src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java src/test/java/com/openggf/tests/trace/TestDivergenceReport.java
git commit -m "test: integrate s3k elastic replay windows"
```

## Task 5: Finish The S3K Recorder And Import The Authoritative Fixture

**Files:**
- Modify: `tools/bizhawk/s3k_trace_recorder.lua`
- Modify: `tools/bizhawk/record_s3k_trace.bat`
- Modify: `tools/bizhawk/README.md`
- Modify: `docs/guide/contributing/trace-replay.md`
- Create: `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`

- [ ] **Step 1: Add an explicit end-to-end recorder profile**

Refactor the recorder start gating so the AIZ end-to-end fixture can begin at BK2 frame 0 instead of waiting for gameplay unlock:

```lua
local TRACE_PROFILE = os.getenv("OGGF_S3K_TRACE_PROFILE") or "gameplay_unlock"

local function should_start_recording(game_mode)
    if TRACE_PROFILE == "aiz_end_to_end" then
        return emu.framecount() == 0
    end
    local ctrl_lock_timer = mainmemory.read_u16_be(PLAYER_BASE + OFF_CTRL_LOCK)
    local ctrl_locked = mainmemory.read_u8(ADDR_CTRL1_LOCKED)
    return game_mode == GAMEMODE_LEVEL and ctrl_lock_timer == 0 and ctrl_locked == 0
end
```

Update `record_s3k_trace.bat` to expose the profile without editing Lua:

```bat
set "OGGF_S3K_TRACE_PROFILE=aiz_end_to_end"
"%BIZHAWK_EXE%" --chromeless --lua "%LUA_SCRIPT%" --movie "%BK2_PATH%" "%ROM_PATH%"
```

Normal act-level recordings keep working by launching BizHawk without that environment variable or by setting `OGGF_S3K_TRACE_PROFILE=gameplay_unlock`.

- [ ] **Step 2: Add deterministic `zone_act_state` and checkpoint emission**

Add recorder helpers that always emit in the same within-frame order:

```lua
local emitted_checkpoints = {}
local last_zone_act_state_key = nil
local previous_handoff_flag = nil
local ADDR_APPARENT_ACT = 0xEE51
local ADDR_EVENTS_FG_5 = 0xEEC6
local ADDR_UNKFAA8 = 0xFAA8
local ADDR_LEVEL_STARTED_FLAG = 0xF711

local function json_quote(value)
    return '"' .. tostring(value)
        :gsub("\\", "\\\\")
        :gsub('"', '\\"') .. '"'
end

local function json_int_or_null(value)
    if value == nil then
        return "null"
    end
    return tostring(value)
end

local function emit_zone_act_state(frame, actual_zone_id, actual_act, apparent_act, game_mode)
    local key = table.concat({
        tostring(actual_zone_id),
        tostring(actual_act),
        tostring(apparent_act),
        tostring(game_mode)
    }, "|")
    if key == last_zone_act_state_key then
        return
    end
    last_zone_act_state_key = key
    write_aux(string.format(
        '{"frame":%d,"event":"zone_act_state","actual_zone_id":%s,"actual_act":%s,"apparent_act":%s,"game_mode":%s}',
        frame,
        json_int_or_null(actual_zone_id),
        json_int_or_null(actual_act),
        json_int_or_null(apparent_act),
        json_int_or_null(game_mode)))
end

local function emit_checkpoint_once(frame, name, actual_zone_id, actual_act, apparent_act, game_mode, notes)
    if emitted_checkpoints[name] then
        return false
    end
    emitted_checkpoints[name] = true
    local notes_field = notes ~= nil and (',"notes":' .. json_quote(notes)) or ""
    write_aux(string.format(
        '{"frame":%d,"event":"checkpoint","name":"%s","actual_zone_id":%s,"actual_act":%s,"apparent_act":%s,"game_mode":%s%s}',
        frame,
        name,
        json_int_or_null(actual_zone_id),
        json_int_or_null(actual_act),
        json_int_or_null(apparent_act),
        json_int_or_null(game_mode),
        notes_field))
    return true
end

local function emit_s3k_semantic_events(frame)
    local actual_zone_id = started and mainmemory.read_u8(ADDR_ZONE) or nil
    local actual_act = started and mainmemory.read_u8(ADDR_ACT) or nil
    local apparent_act = started and mainmemory.read_u8(ADDR_APPARENT_ACT) or nil
    local game_mode = mainmemory.read_u8(ADDR_GAME_MODE)
    local move_lock = started and mainmemory.read_u16_be(PLAYER_BASE + OFF_CTRL_LOCK) or nil
    local ctrl_locked = mainmemory.read_u8(ADDR_CTRL1_LOCKED)
    local events_fg_5 = started and mainmemory.read_u16_be(ADDR_EVENTS_FG_5) or 0
    local handoff_flag = started and mainmemory.read_u8(ADDR_UNKFAA8) or 0
    local level_started = mainmemory.read_u8(ADDR_LEVEL_STARTED_FLAG)

    emit_zone_act_state(frame, actual_zone_id, actual_act, apparent_act, game_mode)

    if frame == 0 then
        emit_checkpoint_once(frame, "intro_begin", actual_zone_id, actual_act, apparent_act, game_mode, nil)
    end
    if level_started ~= 0 and game_mode == GAMEMODE_LEVEL and move_lock == 0 and ctrl_locked == 0 then
        emit_checkpoint_once(frame, "gameplay_start", actual_zone_id, actual_act, apparent_act, game_mode, nil)
    end
    if actual_zone_id == 0 and actual_act == 0 and events_fg_5 ~= 0 then
        emit_checkpoint_once(frame, "aiz1_fire_transition_begin", actual_zone_id, actual_act, apparent_act, game_mode, nil)
    end
    if actual_zone_id == 0 and actual_act == 1 and apparent_act == 0 then
        emit_checkpoint_once(frame, "aiz2_reload_resume", actual_zone_id, actual_act, apparent_act, game_mode, nil)
    end
    if actual_zone_id == 0 and actual_act == 1 and move_lock == 0 and ctrl_locked == 0 then
        emit_checkpoint_once(frame, "aiz2_main_gameplay", actual_zone_id, actual_act, apparent_act, game_mode, nil)
    end
    if previous_handoff_flag ~= nil and previous_handoff_flag ~= 0 and handoff_flag == 0 then
        emit_checkpoint_once(frame, "hcz_handoff_begin", actual_zone_id, actual_act, apparent_act, game_mode, nil)
    end
    if actual_zone_id == 1 and actual_act == 0 and move_lock == 0 and ctrl_locked == 0 then
        emit_checkpoint_once(frame, "hcz_handoff_complete", actual_zone_id, actual_act, apparent_act, game_mode, nil)
    end
    previous_handoff_flag = handoff_flag
end
```

Enforce within-frame ordering by call order, not a deferred buffer: invoke `emit_s3k_semantic_events(trace_frame)` before `emit_player_mode_event()`, `check_mode_changes(...)`, `write_state_snapshot()`, and `scan_objects(...)`. This preserves `zone_act_state -> checkpoint -> existing diagnostics` on every frame.

- [ ] **Step 3: Record the authoritative trace**

Run:

```powershell
tools\bizhawk\record_s3k_trace.bat `
  "Sonic and Knuckles & Sonic 3 (W) [!].gen" `
  "src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3k-aiz1-aiz2-sonictails.bk2"
```

Expected: `tools/bizhawk/trace_output/metadata.json`, `physics.csv`, and `aux_state.jsonl` are produced for the full intro-to-HCZ run.

- [ ] **Step 4: Import the generated files into the fixture directory**

Run:

```powershell
New-Item -ItemType Directory -Force src/test/resources/traces/s3k/aiz1_to_hcz_fullrun | Out-Null
Copy-Item tools/bizhawk/trace_output/metadata.json      src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/
Copy-Item tools/bizhawk/trace_output/physics.csv        src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/
Copy-Item tools/bizhawk/trace_output/aux_state.jsonl    src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/
```

Then verify the fixture shape:

```powershell
Get-ChildItem src/test/resources/traces/s3k/aiz1_to_hcz_fullrun
```

Expected: exactly four authoritative files in the directory: the BK2, `metadata.json`, `physics.csv`, and `aux_state.jsonl`.

- [ ] **Step 5: Freeze the exact fixture metadata**

The recorder should emit this metadata shape for the real fixture:

The `trace_frame_count`, `start_x`, and `start_y` values below are placeholders in this example. Replace them with the recorded values after capture.

```json
{
  "game": "s3k",
  "zone": "aiz",
  "zone_id": 0,
  "act": 1,
  "bk2_frame_offset": 0,
  "trace_frame_count": 0,
  "start_x": "0x0000",
  "start_y": "0x0000",
  "recording_date": "2026-04-21",
  "lua_script_version": "3.1-s3k",
  "trace_schema": 3,
  "csv_version": 4,
  "bizhawk_version": "2.11",
  "genesis_core": "Genplus-gx",
  "rom_checksum": "C5B1C655C19F462ADE0AC4E17A844D10",
  "notes": "AIZ intro through HCZ handoff end-to-end fixture"
}
```

Only `trace_frame_count`, `start_x`, `start_y`, and `recording_date` should vary after the capture. `TraceMetadata` already uses `@JsonIgnoreProperties(ignoreUnknown = true)`, so adding `bizhawk_version` and `genesis_core` does not require Java parser changes.

- [ ] **Step 6: Add a recorder-output sanity gate before committing the fixture**

Run:

```powershell
$events = Get-Content tools/bizhawk/trace_output/aux_state.jsonl | ForEach-Object { $_ | ConvertFrom-Json }
$required = 'intro_begin','gameplay_start','aiz1_fire_transition_begin','aiz2_reload_resume','aiz2_main_gameplay','hcz_handoff_begin','hcz_handoff_complete'
$frames = $events | Select-Object -ExpandProperty frame

if (($events | Where-Object event -eq 'checkpoint' | Where-Object name -eq 'intro_begin').Count -ne 1) { throw 'intro_begin count != 1' }
if ((($frames | Sort-Object) -join ',') -ne ($frames -join ',')) { throw 'frame order regressed' }
foreach ($name in $required) {
  if (($events | Where-Object event -eq 'checkpoint' | Where-Object name -eq $name).Count -ne 1) {
    throw "required checkpoint missing or duplicated: $name"
  }
}
for ($i = 1; $i -lt $events.Count; $i++) {
  if ($events[$i].frame -eq $events[$i-1].frame -and $events[$i].event -eq 'zone_act_state' -and $events[$i-1].event -eq 'checkpoint') {
    throw "zone_act_state emitted after checkpoint on frame $($events[$i].frame)"
  }
}
```

Expected: no output and no exception. This is the fixture-level check that the real recorder obeys the same ordering and checkpoint-count contract the unit tests expect.

- [ ] **Step 7: Update recorder documentation**

Add a short guide section showing:

```md
1. Keep the locked-on ROM at the repo root.
2. Run `tools/bizhawk/record_s3k_trace.bat <rom> <bk2>`.
3. Copy `tools/bizhawk/trace_output/{metadata.json,physics.csv,aux_state.jsonl}` into `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`.
4. Run the fixture sanity script against `aux_state.jsonl`.
5. Re-run `TestS3kAizTraceReplay`.
```

- [ ] **Step 8: Commit the recorder and fixture import**

Run:

```bash
git add tools/bizhawk/s3k_trace_recorder.lua tools/bizhawk/record_s3k_trace.bat tools/bizhawk/README.md docs/guide/contributing/trace-replay.md src/test/resources/traces/s3k/aiz1_to_hcz_fullrun
git commit -m "test: record s3k aiz to hcz replay fixture"
```

## Task 6: Add The Real Replay Test, Verify It, And Gate Parity Work

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kAizTraceReplay.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add the authoritative replay test class**

Create:

```java
package com.openggf.tests.trace.s3k;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAizTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 0; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun");
    }
}
```

- [ ] **Step 2: Run the focused replay stack**

Run:

```bash
mvn -Dtest=TestTraceDataParsing,TestTraceEventFormatting,TestDivergenceReport,TestS3kReplayCheckpointDetector,TestS3kElasticWindowController,TestS3kAizTraceReplay -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" -Dmse=off test
```

Expected:

- unit tests are green
- `TestS3kAizTraceReplay` either passes or produces `target/trace-reports/s3k_aiz1_report.json` and a matching context file

- [ ] **Step 3: If the replay is red, stop and use the report as the parity gate**

Use the first-error report, do not guess. This is the explicit scope boundary for this plan: if the replay is still red after Tasks 1-5, stop here, preserve the report, and write a separate engine-parity plan rather than pretending this plan already achieved the spec's final merge gate.

Run:

```powershell
Get-Content target/trace-reports/s3k_aiz1_report.json
Get-Content target/trace-reports/s3k_aiz1_context.txt
```

Expected: the report names the latest checkpoint and latest `zone_act_state` before the first divergence. Use that checkpoint boundary to decide which subsystem needs the follow-on engine-parity plan.

- [ ] **Step 4: Update the changelog only once the replay test is green**

Add one entry summarizing:

```md
- Added the authoritative Sonic 3&K `aiz1_to_hcz_fullrun` trace fixture and default-suite replay test with checkpoint-aware elastic windows for the intro and fire-transition spans.
```

- [ ] **Step 5: Run the full default-suite gate**

Run:

```bash
mvn test -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" -Dmse=off
```

Expected: green default suite with `TestS3kAizTraceReplay` discovered normally. No `@Disabled`, no Surefire exclusion, no manual-only path.

- [ ] **Step 6: Commit the test landing**

Run:

```bash
git add src/test/java/com/openggf/tests/trace/s3k/TestS3kAizTraceReplay.java CHANGELOG.md
git commit -m "test: add s3k end-to-end trace replay"
```

## Self-Review Checklist

- Spec coverage:
  - Research freeze: Task 1
  - Recorder completion: Task 5
  - Replay-side checkpoint detector and elastic windows: Tasks 3 and 4
  - Checkpoint and `zone_act_state` parsing/reporting: Tasks 2 and 4
  - Fixture recording/import: Task 5
  - Default-suite replay test and verification through structural validity: Task 6
  - Engine parity after a red real-fixture replay is intentionally deferred to a follow-on plan
- Placeholder scan:
  - No `TBD`, `TODO`, or “handle later” steps remain.
  - The one deliberate stop condition is parity work after the first real replay failure; that work must be driven by the concrete divergence report, not guessed in advance.
- Type consistency:
  - Use `TraceEvent.Checkpoint` / `TraceEvent.ZoneActState` everywhere for typed aux events.
  - Use the two-cursor controller terms `driveTraceIndex` and `strictTraceIndex` consistently.
