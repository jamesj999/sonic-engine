# S2 Special Stage Trace Capture & Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A BizHawk lua recorder that captures per-frame ROM truth inside the Sonic 2 special stage, plus headless JUnit and visual test-mode replay that steps the engine's S2 special stage deterministically against it, ending in a faithful divergence report.

**Architecture:** Parallel trace profile (`trace_profile: "s2_special_stage"`) reusing the S2 level-select pipeline *shape* — recorder lua → csv/jsonl/metadata trio → Java parser → replay harness → report — with SS-specific schema and a new purpose-built replay driver. Replay is trace-paced at logic-frame granularity: one engine step per non-lag ROM frame; lag rows consume input without stepping; the flat lag compensator is set to 0.

**Tech Stack:** Java 21, JUnit 5 (Jupiter ONLY), BizHawk 2.11 lua, PowerShell driver scripts, Maven.

**Spec:** `docs/architecture/designs/2026-07-09-s2-special-stage-trace-design.md` — read it before starting any task. It is the authority on scope and invariants.

## Global Constraints

- JUnit 5 / Jupiter only. No `org.junit.*` (JUnit 4) imports, rules, or runners.
- Comparison-only invariant: committed code never hydrates engine state from trace fields. Only BK2 pad inputs and frame pacing may drive the engine.
- ROM-only runtime assets: never load asset bytes from `docs/`. (`docs/s2disasm/` is for research only.)
- Work on branch `feature/ai-s2-ss-trace` off `develop`.
- Every commit gets the auto-appended trailer block; fill it honestly. `feat`/`fix` commits touching `src/main/` must set `Changelog: updated` and stage `CHANGELOG.md` (or justify `n/a: <reason>`).
- Shared repo: never `git add -A` / `git add .`. Stage exact paths only. Never stash.
- PowerShell: quote Maven `-D` properties, e.g. `mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageTeamSetupTest" test`.
- ROM-dependent tests: guard with `Assumptions.assumeTrue(Files.exists(Path.of("s2.gen")), "s2.gen ROM required")`.
- The Edit/Write tools write LF; check `git diff` for spurious whole-file CRLF diffs on existing files (CHANGELOG.md is CRLF).
- Keep green: existing trace suites, `TestNoDirectMapMutationsInGameplay`, `TestObjectServicesMigrationGuard`, rewind coverage guards.

## Verified ROM RAM Address Map (68k RAM offsets for BizHawk `mainmemory.*`)

Derived from `docs/s2disasm/s2.constants.asm` phase blocks; validated by the
`SS_unk_DB4D` label matching its computed address `$DB4D` and the
`$FFFFF73B-$FFFFF73E` / `$FFFFF736` comments. Absolute address = `0xFF0000 + offset`.

| Symbol | Offset | Size |
|---|---|---|
| `Game_Mode` | `0xF600` | u8 (`0x10` = special stage) |
| `SSTrack_anim` | `0xDB08` | u8 |
| `SpecialStage_CurrentSegment` | `0xDB0A` | u8 |
| `SSTrack_anim_frame` | `0xDB0B` | u8 |
| `SSTrack_drawing_index` | `0xDB0D` | u8 |
| `SSTrack_Orientation` | `0xDB0E` | u8 |
| `SS_New_Speed_Factor` | `0xDB12` | u16be (high word of the long is the index word) |
| `SS_Cur_Speed_Factor` | `0xDB16` | u16be (e.g. `0x000C` = 12) |
| `SSTrack_duration_timer` | `0xDB1F` | u8 |
| `SS_player_anim_frame_timer` | `0xDB21` | u8 |
| `SS_Ctrl_Record_Buf` | `0xDB62` | 16 words (`0xDB62..0xDB81`) |
| `SS_Check_Rings_flag` | `0xDB86` | u8 |
| `SS_Ring_Requirement` | `0xDB8C` | u16be |
| `SS_CurrentLevelLayout` | `0xDB8E` | u32be |
| `SS_Perfect_rings_left` | `0xDB9A` | u16be |
| `SS_NoRingsTogoLifetime` | `0xDBA2` | u16be |
| `SS_RingsToGoBCD` | `0xDBA4` | u16be (BCD) |
| `SS_HideRingsToGo` | `0xDBA6` | u8 |
| `SS_TriggerRingsToGo` | `0xDBA7` | u8 |
| `Tails_control_counter` | `0xF702` | u16be |
| `SS_Swap_Positions_Flag` | `0xF742` | u8 |
| Sonic object (MainCharacter) | `0xB000` | 0x40-byte slot |
| Tails object (Sidekick) | `0xB040` | 0x40-byte slot |

Per-player object offsets (S2 SST + `ss_*` aliases, `s2.constants.asm:138-153`):
`id +0x00` (u8; Sonic=0x09, Tails=0x10, 0=absent), `anim_frame +0x1B`, `anim +0x1C`,
`status +0x22`, `routine +0x24`, `routine_secondary +0x25`, `angle +0x26`,
`ss_x +0x2A` (u16be), `ss_x_sub +0x2C` (u16be), `ss_y +0x2E` (u16be),
`ss_y_sub +0x30` (u16be), `flip_timer +0x33`, `ss_z +0x34` (u16be),
`hurt_timer +0x36`, `slide_timer +0x37`, `rings_hundreds/tens/units +0x3C/0x3D/0x3E` (BCD bytes).

## SS physics.csv schema (`ss_csv_version` 1)

Header (single line, exact):

```
frame,input,input_p2,lag,speed_factor,track_anim,track_anim_frame,track_drawing_index,track_orientation,track_duration_timer,current_segment,player_anim_frame_timer,rings_togo_bcd,check_rings_flag,tails_control_counter,swap_positions_flag,sonic_present,sonic_ss_x,sonic_ss_x_sub,sonic_ss_y,sonic_ss_y_sub,sonic_ss_z,sonic_angle,sonic_routine,sonic_routine_secondary,sonic_status,sonic_anim,sonic_anim_frame,sonic_rings_bcd,sonic_hurt_timer,sonic_slide_timer,sonic_flip_timer,tails_present,tails_ss_x,tails_ss_x_sub,tails_ss_y,tails_ss_y_sub,tails_ss_z,tails_angle,tails_routine,tails_routine_secondary,tails_status,tails_anim,tails_anim_frame,tails_rings_bcd,tails_hurt_timer,tails_slide_timer,tails_flip_timer
```

All values lowercase hex without `0x` prefix except `frame` (decimal) and `lag`
(0/1). `rings_bcd` is the three BCD bytes packed `hundreds<<16 | tens<<8 | units`.
`input`/`input_p2` use the engine input-mask convention of the existing recorder
(`UP=0x01 DOWN=0x02 LEFT=0x04 RIGHT=0x08 JUMP=0x10 START=0x80`).

---

### Task 1: Align `setupPlayers()` with the standard team model

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java:919-962`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageTeamSetupTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `ActiveGameplayTeamResolver.resolveMainCharacterCode(SonicConfigurationService)`, `ActiveGameplayTeamResolver.resolveSidekicks(SonicConfigurationService)` (both exist, `game/session/ActiveGameplayTeamResolver.java`).
- Produces: `setupPlayers()` spawns the Sonic+Tails team when `MAIN_CHARACTER_CODE="sonic"` and `SIDEKICK_CHARACTER_CODE="tails"` (the standard two-key config). Later tasks (5, 7) rely on this.

- [ ] **Step 1: Create branch**

```bash
git checkout develop && git pull && git checkout -b feature/ai-s2-ss-trace
```

- [ ] **Step 2: Write the failing test**

Look at `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManagerTest.java` first and mirror its ROM-free construction + config setup pattern (it uses the package-private manager constructor with injected services; this test lives in the same package so it can use it too). Test body:

```java
@Test
void standardTwoKeyTeamConfigSpawnsSonicAndTails() throws Exception {
    // configure MAIN_CHARACTER_CODE="sonic", SIDEKICK_CHARACTER_CODE="tails"
    // (via the same SonicConfigurationService setup ManagerTest uses; @TempDir per repo convention)
    manager.setupPlayersForTest(); // add a package-private seam if setupPlayers is not reachable; see Step 3
    assertNotNull(manager.getSonicPlayer(), "Sonic should spawn as team leader");
    assertNotNull(manager.getTailsPlayer(), "Tails should spawn as team sidekick");
    assertEquals(2, manager.getPlayers().size());
}

@Test
void soloTailsConfigStillSpawnsTailsAlone() throws Exception {
    // MAIN_CHARACTER_CODE="tails", no sidekicks
    manager.setupPlayersForTest();
    assertNull(manager.getSonicPlayer());
    assertNotNull(manager.getTailsPlayer());
    assertEquals(1, manager.getPlayers().size());
}

@Test
void soloSonicConfigSpawnsSonicAlone() throws Exception {
    // MAIN_CHARACTER_CODE="sonic", no sidekicks
    manager.setupPlayersForTest();
    assertNotNull(manager.getSonicPlayer());
    assertNull(manager.getTailsPlayer());
    assertEquals(1, manager.getPlayers().size());
}
```

If `setupPlayers()` cannot run without the renderer (`renderer.setPlayers(players)` at line 961), guard that call with a null check rather than stubbing a renderer.

- [ ] **Step 3: Run to verify the team test fails**

```
mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageTeamSetupTest" test
```
Expected: `standardTwoKeyTeamConfigSpawnsSonicAndTails` FAILS (solo Sonic spawned — the `"sonic_and_tails"` literal case is unreachable).

- [ ] **Step 4: Implement the alignment**

Replace the switch in `setupPlayers()` (keep the existing player-construction bodies):

```java
String characterCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(configuration());
if (characterCode == null) {
    characterCode = "sonic";
}
characterCode = characterCode.toLowerCase();
boolean tailsSidekick = ActiveGameplayTeamResolver.resolveSidekicks(configuration())
        .stream().map(String::toLowerCase).anyMatch("tails"::equals);

if ("tails".equals(characterCode)) {
    // Tails alone (unchanged body)
} else if (tailsSidekick) {
    // Sonic + Tails team (unchanged body from the old "sonic_and_tails" case)
} else {
    // Sonic alone (unchanged default body; also covers "knuckles" etc. as before)
}
```

Delete the now-dead `"sonic_and_tails"` literal case. Do not change behavior for solo `"tails"` or the default arm.

- [ ] **Step 5: Run the test class, then the SS test neighborhood**

```
mvn "-Dtest=com.openggf.game.sonic2.specialstage.*Test" test
```
Expected: all PASS.

- [ ] **Step 6: Update CHANGELOG.md and commit**

Add a CHANGELOG entry (watch CRLF). Commit:

```bash
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageTeamSetupTest.java CHANGELOG.md
git commit -m "fix(s2ss): resolve special-stage team from standard two-key config"
```
Trailers: `Changelog: updated`, rest `n/a`.

---

### Task 2: Public comparison snapshot on `Sonic2SpecialStageManager`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimator.java`
- Create: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageComparisonState.java`
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageComparisonStateTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: existing getters — manager: `getTrackAnimator()`, `getSonicPlayer()`, `getTailsPlayer()`, `getRingsCollected()`, `isFinished()`; animator: `getSpeedFactor()`, `getCurrentSegmentIndex()`, `getCurrentFrameInSegment()`, `getCurrentTrackFrameIndex()`; player: `getSSXPos()`, `getSSYPos()`, `getSSZPos()`, `getAngle()`, `getRoutine()` (returns `RoutineState`), `isHurt()`, `getAnim()`, `getAnimFrame()`.
- Produces (Task 5 consumes exactly this):

```java
public record Sonic2SpecialStageComparisonState(
        int speedFactor,
        int currentSegmentIndex,
        int trackAnimFrame,        // animator.getCurrentFrameInSegment()
        int drawingIndex,          // manager drawingIndex field
        int trackFrameDelayCounter,// NEW animator getter (counts up 0..duration-1)
        int combinedRings,         // manager.getRingsCollected()
        int tailsControlCounter,
        boolean finished,
        PlayerState sonic,         // null if absent
        PlayerState tails) {       // null if absent
    public record PlayerState(int ssX, int ssY, int ssZ, int angle,
                              String routine, int routineSecondary,
                              int anim, int animFrame) {}
}
```
plus `public Sonic2SpecialStageComparisonState captureComparisonState()` on the manager and `public int getFrameDelayCounter()` on the animator. `routine` = `player.getRoutine().name()`; `routineSecondary` = `player.isHurt() ? 2 : 0`.

- [ ] **Step 1: Write the failing test** — construct the manager ROM-free (same pattern as Task 1's test), call `captureComparisonState()`, assert non-null, `finished()==false`, `speedFactor()==12` (default), and player sub-records match `getSonicPlayer()` presence.
- [ ] **Step 2: Run — expect FAIL** (record/method missing): `mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageComparisonStateTest" test`
- [ ] **Step 3: Implement** the record, the animator getter (`return frameDelayCounter;`), and `captureComparisonState()` assembling from existing getters/fields. Read-only; no new mutators.
- [ ] **Step 4: Run — expect PASS.** Also run `mvn "-Dtest=com.openggf.game.sonic2.specialstage.*Test" test`.
- [ ] **Step 5: Commit** (`feat(s2ss): comparison-state accessor for trace replay`; `Changelog: updated`).

---

### Task 3: SS trace parsing (`SpecialStageTraceFrame` / `SpecialStageTraceData`) + `special_stage_index` metadata

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceMetadata.java` (add `@JsonProperty("special_stage_index") Integer specialStageIndex` component; record is `@JsonIgnoreProperties(ignoreUnknown=true)` so old traces are unaffected)
- Create: `src/main/java/com/openggf/trace/SpecialStageTraceFrame.java`
- Create: `src/main/java/com/openggf/trace/SpecialStageTraceData.java`
- Test: `src/test/java/com/openggf/trace/SpecialStageTraceFrameTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Produces:

```java
public record SpecialStageTraceFrame(
        int frame, int input, int inputP2, boolean lag,
        int speedFactor, int trackAnim, int trackAnimFrame, int trackDrawingIndex,
        int trackOrientation, int trackDurationTimer, int currentSegment,
        int playerAnimFrameTimer, int ringsToGoBcd, int checkRingsFlag,
        int tailsControlCounter, int swapPositionsFlag,
        CharacterState sonic, CharacterState tails) {
    public record CharacterState(boolean present, int ssX, int ssXSub, int ssY,
            int ssYSub, int ssZ, int angle, int routine, int routineSecondary,
            int status, int anim, int animFrame, int ringsBcd,
            int hurtTimer, int slideTimer, int flipTimer) {
        public int ringsBinary() { // hundreds<<16|tens<<8|units bytes -> binary
            return ((ringsBcd >> 16) & 0xFF) * 100
                 + ((ringsBcd >> 8) & 0xFF) * 10
                 + (ringsBcd & 0xFF);
        }
    }
    public static SpecialStageTraceFrame parseCsvRow(String row);
}
```
Note on `ringsBinary()`: each byte is a single BCD digit pair per the ROM layout
(`hundreds`, `tens`, `units` are separate bytes holding 0-9 values directly per
`s2.asm:70771-70789`'s `abcd` usage — verify with the first real trace in Task 4
and simplify to `h*100 + t*10 + u` if the bytes hold plain 0-9).

```java
public final class SpecialStageTraceData {
    public static SpecialStageTraceData load(Path traceDirectory) throws IOException;
    public TraceMetadata metadata();
    public int frameCount();
    public SpecialStageTraceFrame getFrame(int i);
    public List<TraceEvent> getEventsForFrame(int i);      // reuse TraceEvent + aux jsonl parsing from TraceData
    public OptionalInt stageFinishedFrame();               // first frame with a "stage_finished" aux event
}
```
`load` mirrors `TraceData.load` (`TraceData.java:57`): `metadata.json` +
`physics.csv(.gz)` + optional `aux_state.jsonl(.gz)`; reuse `TraceData`'s
gzip/`resolveTraceFile` helpers (extract them to package-visible statics if private).
It must reject a directory whose `metadata.trace_profile` is not
`"s2_special_stage"` with an `IllegalArgumentException` naming the profile.

- [ ] **Step 1: Write failing tests** — hand-built header+row string round-trips through `parseCsvRow` (assert every field, hex parsing, lag 0/1); `load` on a `@TempDir` synthetic trace dir (write 3-row csv + minimal metadata json with `trace_profile`/`special_stage_index` + a raw checkpoint, logical `stage_finished`, and later `results_started`); wrong-profile rejection; `stageFinishedFrame()` and `resultsStartedFrame()` return their distinct boundaries.
- [ ] **Step 2: Run — expect FAIL** (classes missing).
- [ ] **Step 3: Implement.** Column order must match the schema header verbatim (index constants, not `split` guesswork — use one `String[] parts = row.split(",", -1)` with named index constants).
- [ ] **Step 4: Run — expect PASS**: `mvn "-Dtest=com.openggf.trace.SpecialStageTraceFrameTest" test`
- [ ] **Step 5: Commit** (`feat(trace): S2 special-stage trace parsing`; `Changelog: updated`).

---

### Task 4: Recorder lua + recording workflow + record the MVP trace

**Files:**
- Create: `tools/bizhawk/s2_ss_trace_recorder.lua`
- Modify: `tools/bizhawk/record_s2_level_select_traces.ps1` (routes table `Profile` column, SS validation branch, `input_p2` normalizer awareness)
- Create (artifacts): `src/test/resources/traces/s2/special_stage/{metadata.json, physics.csv.gz, aux_state.jsonl.gz, s2-lvl-select-special-stage.bk2}`

**Interfaces:**
- Consumes: `docs/BizHawk-2.11-win-x64/Movies/s2-lvl-select-special-stage.bk2`, `s2.gen`, the RAM address map and CSV schema from this plan's header.
- Produces: the trace artifact trio Task 5/6 load. Metadata must contain: `game:"s2"`, `trace_profile:"s2_special_stage"`, `special_stage_index:0`, `ss_csv_version:1`, `characters:["sonic","tails"]`, `main_character:"sonic"`, `sidekicks:["tails"]`, `bk2_frame_offset`, `trace_frame_count`, `source_bk2:"s2-lvl-select-special-stage.bk2"`, `lua_script_version`.

- [ ] **Step 1: Write the recorder lua.** Derive from `tools/bizhawk/s2_trace_recorder.lua` (v9.10): keep its skeleton — `while true do ... on_frame_end(); emu.frameadvance() end` main loop (level recorder L1322-1360), `open_files`/`write_metadata` structure (L462/L478), `bk2_input_mask` movie-input derivation (L319-342, extend for P2: `movie.getinput(frame_index, 2)` same button mapping), output-dir handling, flush cadence. Replace the capture core:
  - Start condition: `mainmemory.read_u8(0xF600) == 0x10`; on first detection set `bk2_frame_offset = emu.framecount()` and start recording from that frame as trace frame 0. Hard-fail (`error(...)`) if not reached within 5000 frames.
  - Stop condition: `Game_Mode ~= 0x10` after recording started, or movie end, or FRAME_CAP.
  - Per-frame row: read every column from the RAM address map above. `lag` = `emu.islagged() and 1 or 0` (note: the level recorder does NOT do this — its `lag_counter` is a placeholder; this recorder must call `emu.islagged()` for real).
  - Aux jsonl events: `stage_finished` on the final `SS_Check_Rings_flag` 0→nonzero transition, keyed to the owning logical non-lag observation with the raw row retained as `observed_frame`; `checkpoint` remains on that raw transition; `results_started` records the later first object-slot id `0x6F` (`ObjID_SSResults`) without redefining the finish boundary; `message_state` whenever any of `SS_HideRingsToGo`/`SS_TriggerRingsToGo`/`SS_NoRingsTogoLifetime` changes (dump all three + frame); frame -1 `state_snapshot` with `SS_Ring_Requirement`, `SS_CurrentLevelLayout`, initial `SS_Cur_Speed_Factor`, `SS_Perfect_rings_left`.
  - Metadata: fields listed in Interfaces above; omit zone/act entirely.
- [ ] **Step 2: Wire the workflow script.** In `record_s2_level_select_traces.ps1`: add optional `Profile` property to route objects (default `"level"`); add route `[pscustomobject]@{ Route = "special_stage"; Bk2 = "s2-lvl-select-special-stage.bk2"; Profile = "s2_special_stage" }`; when `Profile -eq "s2_special_stage"` invoke `s2_ss_trace_recorder.lua` instead of the level recorder and run `Assert-SsMetadata` (new function: checks `trace_profile`, `special_stage_index`, `source_bk2`, `bk2_frame_offset -gt 0`, `trace_frame_count -gt 0` and that the gzipped csv row count equals `trace_frame_count` — total frames INCLUDING the uncompared results tail) instead of `Assert-Metadata`/`Assert-ZoneActCoverage`. Keep the gzip/copy logic (L412-419) unchanged.
- [ ] **Step 3: Record.** Run the ps1 for the `special_stage` route (see `tools/bizhawk/record_s2_trace.bat` / `run_bizhawk_hidden.ps1` conventions). Verify outputs: metadata fields, csv header exactly matches the schema line, `lag` column contains BOTH 0s and 1s (S2 SS lags heavily — an all-zero lag column means `emu.islagged()` isn't wired), exactly one checkpoint-owned logical `stage_finished` exists before the later `results_started`, per-player `ss_x/ss_y` values move over time, and `speed_factor` starts at `c` (hex 12) and increases after checkpoints.
- [ ] **Step 4: Spot-verify BCD rings.** Find a frame range where a ring is collected (rings_bcd changes); confirm byte semantics (plain 0-9 per byte vs packed BCD) and correct Task 3's `ringsBinary()` + its test if needed.
- [ ] **Step 5: Commit** recorder + ps1 + artifacts (exact paths; artifacts are gzipped). `feat(trace): S2 special-stage recorder and MVP trace` — `Changelog: updated` (or `n/a: tooling+resources only` if nothing under src/main changed in this task — it doesn't; use the justified n/a).

---

### Task 5: Headless replay harness, comparator, and the (red-allowed) trace test

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s2/S2SpecialStageReplayHarness.java`
- Create: `src/test/java/com/openggf/tests/trace/s2/AbstractS2SpecialStageTraceReplayTest.java`
- Create: `src/test/java/com/openggf/tests/trace/s2/TestS2SpecialStageTraceReplay.java`
- Create: `src/test/java/com/openggf/tests/trace/s2/S2SpecialStageReplayDeterminismTest.java`
- Modify: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: `SpecialStageTraceData` (Task 3), `Sonic2SpecialStageComparisonState`/`captureComparisonState()` (Task 2), team config fix (Task 1), trace artifacts (Task 4). Existing: `Sonic2SpecialStageProvider` (`initializeStage(int)`, `handleInput(int,int)`, `handlePlayer2Input(int,int)`, `update()`, `isFinished()`, `setLagCompensation(double)`, `getManager()`), `SpecialStageInputMapper.map(LogicalInputSnapshot)` → `MappedInput(p1Held,p1Pressed,p2Held,p2Logical)`, `RecordedInputSnapshots.fromBk2(current, previous)`, `Bk2MovieLoader`, `TraceReplaySessionBootstrap.prepareConfiguration` two-key team pattern, `DivergenceReport` (read `AbstractTraceReplayTest.java` compareFrame/binder usage and mirror its report construction — same JSON shape, `target/trace-reports` output).
- Produces: report at `target/trace-reports/s2_special_stage_<special_stage_index>_report.json` (+ `_context.txt` on errors).

- [ ] **Step 1: Bootstrap research (30 min cap).** Find how existing ROM-backed S2 SS tests boot the provider: `grep -rn "initializeStage\|new Sonic2SpecialStageProvider" src/test/`. Mirror the closest full-boot pattern (rewind/renderer determinism tests). Record the pattern in the harness Javadoc.
- [ ] **Step 2: Write the harness** (drives everything; modeled on `SpecialStageStepper.step()` at `src/main/java/com/openggf/game/rewind/SpecialStageStepper.java:27` but purpose-built, and owning the finish boundary — see spec):

```java
final class S2SpecialStageReplayHarness {
    // ctor: (Path bk2, int bk2FrameOffset, int specialStageIndex) ->
    //   prepareConfiguration two-key team, boot services, provider.initializeStage(index),
    //   provider.setLagCompensation(0), load BK2 rows via Bk2MovieLoader
    /** Steps one SS logic frame using BK2 row (bk2FrameOffset + traceFrame). */
    void stepFrame(int traceFrame) {
        Bk2FrameInput current = rowAt(traceFrame);
        Bk2FrameInput previous = traceFrame == 0 ? current : rowAt(traceFrame - 1); // previous PHYSICAL row (spec press-edge rule)
        inputHandler.setLogicalOverride(RecordedInputSnapshots.fromBk2(current, previous));
        try {
            var mapped = SpecialStageInputMapper.map(inputHandler.logical());
            provider.handleInput(mapped.p1Held(), mapped.p1Pressed());
            provider.handlePlayer2Input(mapped.p2Held(), mapped.p2Logical());
            provider.update();
        } finally {
            inputHandler.clearLogicalOverride();
        }
    }
    /** Lag row: consume nothing engine-side; the row simply isn't stepped. */
    Sonic2SpecialStageComparisonState capture() { return provider.getManager().captureComparisonState(); }
    boolean isFinished() { return provider.isFinished(); }
}
```

- [ ] **Step 3: Write the abstract test.** Loop `for (int f = 0; f < compareEnd; f++)`: if `trace.getFrame(f).lag()` → skip (no engine step; the press-edge rule is honored because `stepFrame` always diffs against the previous *physical* row). Else `harness.stepFrame(f)` then compare `harness.capture()` against the frame: Tier-1 errors — per-player `present`, `ssX`, `ssY`, `ssZ`, `angle`, routine (via an explicit `Map<Integer, String>` from ROM routine byte to engine `RoutineState` name, plus trace `routine_secondary == 2` ↔ engine hurt flag; seed the map from the disasm's SS player routine table and correct it from the first real report — never compare raw bytes), combined rings (`sonic.ringsBinary()+tails.ringsBinary()` vs `combinedRings`), `speed_factor`, `current_segment`, `track_anim_frame`. Tier-2 warnings — per-player rings, `rings_togo_bcd`, `tails_control_counter`, `track_drawing_index`, `track_duration_timer` (map: ROM counts down from duration, engine `frameDelayCounter` counts up; compare `duration - romTimer == engineCounter` once duration is known from `speed_factor`), `swap_positions_flag`, hurt/slide timers. NOT compared: `player_anim_frame_timer`. `compareEnd = trace.stageFinishedFrame().orElse(trace.frameCount())`; also assert engine `isFinished()` becomes true exactly at `stageFinishedFrame` (Tier-1). Write the report (mirror `AbstractTraceReplayTest.writeReport`, `AbstractTraceReplayTest.java:1244`, prefix `"s2_special_stage_" + meta.specialStageIndex()`). The test asserts the *pipeline* (trace loads, harness steps to compareEnd without exceptions, report written); divergences do NOT fail the MVP test — expose a `assertNoReleaseBlockingDivergences()` hook left disabled with a comment pointing at the frontier log, so the ratchet is one line when fixes land.
- [ ] **Step 4: Concrete test** `TestS2SpecialStageTraceReplay` — `traceDirectory() = src/test/resources/traces/s2/special_stage`, `assumeTrue(s2.gen exists)`.
- [ ] **Step 5: Determinism test** — run the replay loop twice in one JVM (fresh harness each, `@FullReset`/`SingletonResetExtension` per repo convention), assert the two reports' JSON strings are identical.
- [ ] **Step 6: Run**: `mvn "-Dtest=com.openggf.tests.trace.s2.TestS2SpecialStageTraceReplay,com.openggf.tests.trace.s2.S2SpecialStageReplayDeterminismTest" test` — expect PASS (pipeline), report generated. Read the report; record first-divergence frame/field.
- [ ] **Step 7: Frontier log + commit.** Add a `docs/status/trace-frontier-log.md` entry: command, commit, pass/fail, error count, first-error frame/field. Commit (`feat(trace): S2 special-stage headless trace replay` — `Changelog: n/a: test-tree + docs only` if src/main untouched).

---

### Task 6: TraceCatalog profile support (visual-mode discovery)

**Files:**
- Modify: `src/main/java/com/openggf/trace/catalog/TraceCatalog.java` (tryLoad, `TraceCatalog.java:74`)
- Modify: `src/main/java/com/openggf/trace/catalog/TraceEntry.java`
- Test: `src/test/java/com/openggf/trace/catalog/TraceCatalogSpecialStageTest.java`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `TraceMetadata.traceProfile()`, `specialStageIndex()` (Task 3), trace dir layout (Task 4).
- Produces: `TraceEntry` gains `String displayLabel()` — for level traces the existing zone/act-derived text (extract current picker formatting into it), for `"s2_special_stage"` profile: `"S2 SPECIAL STAGE " + (specialStageIndex + 1)`. `TraceCatalog.scan` includes SS dirs (validation: metadata + physics + bk2 present, game id valid; zone/act default 0 accepted, not treated as invalid).

- [ ] **Step 1: Failing test** — `@TempDir` catalog root with one synthetic SS trace dir (reuse Task 3's synthetic-fixture helper; add a tiny valid `.bk2`-named file + `source_bk2` metadata); assert `scan` returns it, `displayLabel()` correct, and a second synthetic *level* trace still scans with unchanged label.
- [ ] **Step 2: Run — FAIL.** `mvn "-Dtest=com.openggf.trace.catalog.TraceCatalogSpecialStageTest" test`
- [ ] **Step 3: Implement.** Keep `tryLoad`'s existing checks; only branch labeling on `meta.traceProfile()`. Update `TestModeTracePicker`'s row rendering to use `displayLabel()` (mechanical swap).
- [ ] **Step 4: Run new test + existing catalog tests — PASS.**
- [ ] **Step 5: Commit** (`feat(trace): catalog support for special-stage traces`; `Changelog: updated`).

---

### Task 7: Visual test mode — live SS trace session

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java` (`updateSpecialStageMode()` at 1049-1114; `doEnterSpecialStage` visibility at 1995)
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Test: `src/test/java/com/openggf/GameLoopSpecialStageSkipGateTest.java` (or extend the existing `TestGameLoopSpecialStageRewindBoundary` neighborhood)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: catalog entry (Task 6), `SpecialStageTraceData` (Task 3), harness pacing rules (Task 5).
- Produces: selecting an SS trace in the test-mode picker plays it live at authentic (lag-paced) speed.

- [ ] **Step 1: Skip-gate test first.** Mirror the LEVEL-mode gate: assert that when the active trace session marks the current frame as a lag row, `ssProvider.update()` is NOT invoked that frame (use the existing fake-provider pattern from `TestGameLoopSpecialStageRewindBoundary.java` / `TestSpecialStageStepperReplay`'s `provider` fake). Run — FAIL.
- [ ] **Step 2: Implement the gate.** In `updateSpecialStageMode()` before `updateSpecialStageInput()`/`ssProvider.update()` (`GameLoop.java:1102-1103`):

```java
boolean skipSsTick = TraceSessionLauncher.active() != null
        && TraceSessionLauncher.active().shouldSkipCurrentSpecialStageTick();
if (!skipSsTick) {
    updateSpecialStageInput();
    ssProvider.update();
}
```
(No VBlank-counter analog exists in SS mode — nothing else to advance on a skipped row; the trace cursor advances inside the launcher.) Loosen `doEnterSpecialStage` from `private` to package-private; update the reflection-based caller in `TestGameLoopSpecialStageRewindBoundary` to call it directly.

- [ ] **Step 3: SS branch in `TraceSessionLauncher`.** On launch of an entry whose `metadata.traceProfile()` is `"s2_special_stage"`: skip zone/act load; enter SS mode via `doEnterSpecialStage(meta.specialStageIndex())`-equivalent path; hold the loaded `SpecialStageTraceData` + BK2 rows; per engine frame advance the trace cursor, expose `shouldSkipCurrentSpecialStageTick()` (true when current row `lag`), and feed the row's input through the same `setLogicalOverride(RecordedInputSnapshots.fromBk2(current, previousPhysical))` pattern as the headless harness; `setLagCompensation(0)` for the session and end the session at `stageFinishedFrame`. Follow the launcher's existing level-session lifecycle (start/stop/cleanup) — this is a parallel branch, do not thread SS state through the level driver classes.
- [ ] **Step 4: Run skip-gate test — PASS.** Full SS + GameLoop test neighborhood green.
- [ ] **Step 5: Manual visual verification.** `config.yaml`: `debug.testMode.enabled: true`, catalogDir default. Launch the jar, pick `S2 SPECIAL STAGE 1`, confirm: stage enters, players move per recording, perceived speed varies (lag pacing visible), session ends at results boundary. Note result in commit body.
- [ ] **Step 6: Commit** (`feat(trace): live special-stage trace sessions in test mode`; `Changelog: updated`).

---

### Task 8: Sweep, docs, merge readiness

**Files:**
- Modify: `docs/status/trace-frontier-log.md` (final status), `README.md` (staged at merge time per policy)

- [ ] **Step 1: Full regression sweep**: `mvn test` (expect: pre-existing failures only — compare against a develop baseline run; the new SS trace test passes as pipeline-proof).
- [ ] **Step 2: Trace suite spot-check**: run 2-3 existing S2 level-select trace classes to confirm zero interference.
- [ ] **Step 3: Verify commit-gate compliance** (`git config core.hooksPath` → `.githooks`), then merge to develop with a `README.md` release-log entry staged in the merge commit, per Branch Documentation Policy. Do not use `--no-verify`.

## Follow-up (separate plan, out of scope here)

The divergence-fix loop (control lock, Tails CPU semantics, speed factor/lag model, text/ring timing, per-player rings, swap-flag reconciliation) — worklist = the Task 5 report + frontier log entry, per the spec's phase-3 pointer.
