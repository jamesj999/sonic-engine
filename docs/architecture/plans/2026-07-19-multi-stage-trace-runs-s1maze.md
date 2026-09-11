# Multi-Stage Trace Runs — S1 Maze (Sonic 1 Special Stage) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the Sonic 1 special-stage (rotating maze) trace pipeline: engine comparison accessors (spec addition #2), the `s1_special_stage` schema, the S1 complete-run recorder's stage-detour state machine + maze row writer + run-manifest emission, a headless standalone replay harness with a skip-if-missing comparator test, and the #6-S1 headless standalone-loadability verification.

**Architecture:** Mirrors the shipped blue-spheres (S3K SS) plan exactly, S1-flavored: a pure-read `captureComparisonState()` seam on `Sonic1SpecialStageManager`, profile-keyed `Sonic1SpecialStageTraceData`/`Frame` in `com.openggf.game.sonic1.specialstage`, the v6.31 detour machinery from `s3k_complete_run_recorder.lua` ported into `s1_complete_run_recorder.lua`, and a single-player VBlank-paced harness modeled on `S3kSpecialStageReplayHarness`. The chained-run walker, non-consuming coordinator peeks, and `TraceRunManifest` foundation already exist on develop and are consumed, not modified.

**Tech Stack:** Java 21, JUnit 5/Jupiter only, BizHawk Lua, existing trace framework (`TraceMetadata`, `TraceData` helpers, `DivergenceReport`, `Bk2MovieLoader`, `RecordedInputSnapshots`, `SpecialStageInputMapper`).

## Global Constraints

- **Comparison-only invariant:** trace data is read-only diagnostic input; engine state must NEVER be hydrated/synced from the trace in committed test code (`trace-replay-bug-fixing` skill).
- **No zone/route/frame carve-outs.** Model ROM state, never trace identity.
- **ROM-only runtime assets:** never load asset bytes from `docs/` disassembly trees.
- JUnit 5 / Jupiter only. No JUnit 4 imports/rules/runners.
- **ArchUnit naming rule (learned in slots plan fix sl5):** game-specific trace data classes must live in the game package (`com.openggf.game.sonic1.specialstage`), NOT `com.openggf.trace`. `TraceData.loadAuxEvents` / `resolveTraceFile` / `openTraceReader` are already `public` for this reason.
- **Guard baselines are never weakened.** If a guard fires, fix the code or register with an explicit documented justification in the guard file itself.
- **Lua local budget:** `s1_complete_run_recorder.lua` has ~117 top-level `local` statements (~130 declared names) against Lua's 200-local main-chunk limit — headroom exists but shrinks with every addition. ALL new recorder state/constants/functions added by this plan are **globals**, matching the S3K recorder's v6.30/v6.31 convention.
- **Commit policy:** every non-merge commit carries the 7-trailer block (`Changelog`/`Guide`/`Known-Discrepancies`/`S3K-Known-Discrepancies`/`Agent-Docs`/`Configuration-Docs`/`Skills`, each `updated` or `n/a`). A `feat`/`fix` commit touching `src/main/` must set `Changelog: updated` and stage `CHANGELOG.md` (CRLF file — edit carefully, verify `git diff --stat` shows only the added lines). Stage exact paths only; NEVER `git add -A`. Never `git stash`. End commits with the session's `Co-Authored-By` / `Claude-Session` lines.
- **Test hygiene:** prefer `TestEnvironment.resetPerTest()` over hand-rolled singleton teardown (slots fix sl5); every test asserts something real.
- **VERIFY-ON-FIRST-CAPTURE:** the S1 SS RAM map below is derived from `docs/s1disasm/sonic.lst` + `_Variables.asm` and has NOT yet been validated against a live capture. The recorder must print self-check summaries so the first real recording validates the map before the schema is treated as frozen (spec Component 1 requirement).

## Verified S1 RAM map (BizHawk `mainmemory` = low 16 bits of $FFxxxx)

| Symbol | Address | Size | Meaning |
|---|---|---|---|
| `v_gamemode` | `0xF600` | b | `$0C`=GM_Level, `$10`=GM_Special (`sonic.asm:446`) |
| Player slot base | `0xD000` | — | `v_objspace` slot 0 (Obj09 in SS) |
| `obX` | `0xD008` | u32 | 16.16 X (high word = pixel) |
| `obY` | `0xD00C` | u32 | 16.16 Y |
| `obVelX` | `0xD010` | w | X velocity |
| `obVelY` | `0xD012` | w | Y velocity |
| `obInertia` | `0xD014` | w | ground speed |
| `obStatus` | `0xD022` | b | bit0=facing left, bit1=airborne |
| `v_ssangle` | `0xF780` | w | maze rotation angle (`sonic.lst:1783`) |
| `v_ssrotate` | `0xF782` | w | rotation speed (`sonic.lst:1785`; exit ramps to `$1800`) |
| `v_ssbganim` | `0xF7A0` | w | SS BG animation state (`sonic.lst:1803`) |
| `v_rings` | `0xFE20` | w | ring counter (Obj09 ring pickup increments it, `sonic.lst:2012`) |
| `v_emeralds` | `0xFE57` | b | chaos emerald count (`sonic.lst:2074`) |
| `v_lastspecial` | `0xFE16` | b | special stage number 0–5 (`sonic.lst:1992`; read by GM_Special loader at `$1BE34`) |

S1 has **no** `Special_bonus_entry_flag` / `Saved_X_pos` analogs: SS entry is discriminated purely by the `$0C -> $10` mode edge, written while GM_Level is still running — the giant-ring flash sets `f_bigring` (`docs/s1disasm/_incObj/4B, 7C Giant Ring and Flash.asm:123`), and the end-of-act Got-Through card (Obj3A) then writes `move.b #id_Special,(v_gamemode).w` after its act-results tally completes, gated on `f_bigring` (`docs/s1disasm/_incObj/3A Got Through Card.asm:201`, `Got_ChkSS`; by that point `v_zone_act` already holds the NEXT act — harmless, the recorder labels segments from arm-time `start_zone_id`/`start_act`). There is no `$8C` intermediate: the card's tally runs under `$0C` (recorded into the level segment) and the mode jumps straight to `$10`. The SS's own results run at SS **exit** (see behavior contract #6), and SS exit returns to the **next act**, not to a saved position.

## `s1_special_stage` CSV schema (14 columns)

```
frame,input,lag,x_pos,y_pos,vel_x,vel_y,inertia,status,ss_angle,ss_rotate,bg_anim,rings,emeralds
```

`frame` decimal; `lag` `0`/`1` via `emu.islagged()`; every other column lowercase hex without `0x` prefix (S2/S3K SS recorder convention). `x_pos`/`y_pos` are the full 32-bit 16.16 words. `input` is the BK2-derived P1 mask (single-player — no `input_p2` column, unlike S3K's 20-col schema).

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageComparisonState.java` | Create | Read-only per-frame comparison record (addition #2) |
| `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java` | Modify | Add `captureComparisonState()` (pure read) |
| `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java` | Modify | Add `getManager()` accessor |
| `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceFrame.java` | Create | 14-col CSV row record + parser |
| `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceData.java` | Create | Profile-gated trace-dir loader |
| `tools/bizhawk/s1_complete_run_recorder.lua` | Modify | Detour state machine + maze writer + manifest |
| `src/test/java/com/openggf/game/sonic1/specialstage/TestSonic1SpecialStageComparisonState.java` | Create | Reflective-seed unit test |
| `src/test/java/com/openggf/tests/trace/TestS1SpecialStageTraceParsing.java` | Create | Parser round-trip test |
| `src/test/java/com/openggf/tests/trace/s1/S1SpecialStageReplayHarness.java` | Create | Single-player VBlank-paced replay driver |
| `src/test/java/com/openggf/tests/trace/s1/AbstractS1SpecialStageTraceReplayTest.java` | Create | Comparator + report writer |
| `src/test/java/com/openggf/tests/trace/s1/TestS1SpecialStageTraceReplay.java` | Create | Skip-if-missing concrete test |
| `src/test/java/com/openggf/tests/TestS1SpecialStageHeadlessBoot.java` | Create | #6-S1 standalone-loadability verify |
| `tools/bizhawk/README.md`, `docs/status/trace-frontier-log.md`, spec | Modify | Recording procedure + log + recorder-host amendment |

**Scope notes (explicit, for reviewers):**
- The chained round-trip test (`TestS3kBonusRoundTripChain` analog) and visual standalone SS launch generalization (`TraceSessionLauncher.SPECIAL_STAGE_PROFILE` is still `s2_special_stage`-only) are **deferred follow-ups shared with the S3K blue-spheres work** — neither plan wired in-chain/visual SS-interior comparison; the walker (`TraceRunReplayWalker`) and visual run branch (`RunSegmentAdvancer` is already mode-driven and game-agnostic; `applyPerGameSpecialStageConfig` already handles `"s1"`) need no S1-specific change in this plan. Record the deferral in the frontier log entry (Task 6).
- The spec (line ~201) names `s1_trace_recorder.lua` as the recorder host. This plan hosts the state machine in **`s1_complete_run_recorder.lua`** instead, mirroring how the S3K side actually landed (the run machinery — segment dirs, finalize/re-arm, manifest — already exists there in embryo, and the round-trip bk2 is a multi-segment recording by nature). Task 6 amends the spec line.

---

### Task 1: Comparison seam (spec addition #2)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageComparisonState.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java` (add method next to `captureRewindSnapshot()`, ~line 1861)
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java`
- Test: `src/test/java/com/openggf/game/sonic1/specialstage/TestSonic1SpecialStageComparisonState.java`

**Interfaces:**
- Produces: `Sonic1SpecialStageComparisonState` (record, 15 components below), `Sonic1SpecialStageManager.captureComparisonState()`, `Sonic1SpecialStageProvider.getManager()`. Tasks 4/5 consume all three.

- [ ] **Step 1: Write the failing test.** Model on `TestSonic3kSpecialStageComparisonState` (read it first) and the reflective field get/set idiom in `TestSonic1SpecialStageRewindSnapshot`. Construct `new Sonic1SpecialStageManager()` (no ROM needed — pure field reflection, no `initialize()`), reflectively seed EVERY captured field with a distinct value, call `captureComparisonState()`, assert each record component mirrors its field. **Five booleans cannot be swap-proofed in one pass** — a swapped mapping between two fields is undetected unless their seeded values differ in some pass, so each boolean field needs a DISTINCT value-vector across passes; with 5 booleans and only 4 possible two-pass vectors, **three re-seeded capture passes are required** (the model test `TestSonic3kSpecialStageComparisonState` uses the same multi-pass idiom for its smaller boolean set — read its comment). Use these codewords (each row is one field's value in pass 1/2/3; all five rows distinct AND none constant — a constant row would let a stuck-at-that-value mapping bug pass): `sonicAirborne = T,T,F`; `sonicFacingLeft = F,T,T`; `emeraldCollected = T,F,T`; `exitTriggered = F,F,T`; `finished = T,F,F`. Re-seed all fields and re-assert the full record each pass (a loop over three seed-sets keeps this compact). Add a third test: two consecutive `captureComparisonState()` calls with no intervening mutation return equal records (pure-read check).

```java
package com.openggf.game.sonic1.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1SpecialStageComparisonState {

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Three passes with distinct per-field boolean codewords: a swapped
     * boolean mapping is undetectable in any pass where the two fields hold
     * equal values, so every field carries a distinct value-vector across
     * the passes (5 fields > 4 possible two-pass vectors => 3 passes).
     */
    @Test
    void captureMirrorsManagerFields() throws Exception {
        boolean[][] seeds = {
                // airborne, facingLeft, emerald, exitTriggered, finished
                {true, false, true, false, true},
                {true, true, false, false, false},
                {false, true, true, true, false},
        };
        for (boolean[] pass : seeds) {
            Sonic1SpecialStageManager manager = new Sonic1SpecialStageManager();
            set(manager, "sonicPosX", 0x12345678L);
            set(manager, "sonicPosY", 0x0ABCDEF0L);
            set(manager, "sonicVelX", 0x0123);
            set(manager, "sonicVelY", 0xFEDC);
            set(manager, "sonicInertia", 0x0456);
            set(manager, "sonicAirborne", pass[0]);
            set(manager, "sonicFacingLeft", pass[1]);
            set(manager, "ssAngle", 0x4000);
            set(manager, "ssRotate", 0x0080);
            set(manager, "bgAnimState", 6);
            set(manager, "ringsCollected", 23);
            set(manager, "emeraldCollected", pass[2]);
            set(manager, "exitTriggered", pass[3]);
            set(manager, "finished", pass[4]);
            set(manager, "currentStage", 3);

            Sonic1SpecialStageComparisonState s = manager.captureComparisonState();
            assertEquals(0x12345678L, s.sonicPosX());
            assertEquals(0x0ABCDEF0L, s.sonicPosY());
            assertEquals(0x0123, s.sonicVelX());
            assertEquals(0xFEDC, s.sonicVelY());
            assertEquals(0x0456, s.sonicInertia());
            assertEquals(pass[0], s.sonicAirborne());
            assertEquals(pass[1], s.sonicFacingLeft());
            assertEquals(0x4000, s.ssAngle());
            assertEquals(0x0080, s.ssRotate());
            assertEquals(6, s.bgAnimState());
            assertEquals(23, s.ringsCollected());
            assertEquals(pass[2], s.emeraldCollected());
            assertEquals(pass[3], s.exitTriggered());
            assertEquals(pass[4], s.finished());
            assertEquals(3, s.currentStage());
        }
    }

    @Test
    void captureIsPureRead() throws Exception {
        Sonic1SpecialStageManager manager = new Sonic1SpecialStageManager();
        set(manager, "ssAngle", 0x1234);
        assertEquals(manager.captureComparisonState(), manager.captureComparisonState());
    }
}
```

- [ ] **Step 2: Run it, expect compile failure** (`captureComparisonState` undefined): `mvn "-Dtest=com.openggf.game.sonic1.specialstage.TestSonic1SpecialStageComparisonState" test`

- [ ] **Step 3: Implement.** New record (javadoc modeled on `Sonic3kSpecialStageComparisonState` — cite addition #2, pure-read contract, single producer):

```java
package com.openggf.game.sonic1.specialstage;

/**
 * Read-only per-frame snapshot of {@link Sonic1SpecialStageManager} state used
 * by a trace replay harness to compare engine state against a recorded ROM
 * trace (multi-stage trace run spec addition #2).
 *
 * <p>Produced exclusively by {@link Sonic1SpecialStageManager#captureComparisonState()}.
 * No mutators, no caching — every field is a pure read of existing manager
 * state at the moment of the call. Modeled on
 * {@link com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageComparisonState}.
 *
 * <p>{@code sonicPosX}/{@code sonicPosY} keep the manager's full 16.16
 * fixed-point layout (top 16 bits = pixel), matching the trace's raw
 * {@code obX}/{@code obY} longword reads.
 */
public record Sonic1SpecialStageComparisonState(
        long sonicPosX,
        long sonicPosY,
        int sonicVelX,
        int sonicVelY,
        int sonicInertia,
        boolean sonicAirborne,
        boolean sonicFacingLeft,
        int ssAngle,
        int ssRotate,
        int bgAnimState,
        int ringsCollected,
        boolean emeraldCollected,
        boolean exitTriggered,
        boolean finished,
        int currentStage) {
}
```

Manager method (place directly after `captureRewindSnapshot`, reusing the same field list subset):

```java
    /**
     * Read-only comparison snapshot for trace replay (multi-stage trace run
     * spec addition #2). Pure read — no state mutation, no caching.
     */
    public Sonic1SpecialStageComparisonState captureComparisonState() {
        return new Sonic1SpecialStageComparisonState(
                sonicPosX, sonicPosY, sonicVelX, sonicVelY, sonicInertia,
                sonicAirborne, sonicFacingLeft, ssAngle, ssRotate, bgAnimState,
                ringsCollected, emeraldCollected, exitTriggered, finished,
                currentStage);
    }
```

Provider accessor (mirrors `Sonic3kSpecialStageProvider.getManager()`):

```java
    /** The backing manager, for trace-replay comparison snapshots. */
    public Sonic1SpecialStageManager getManager() {
        return manager;
    }
```

- [ ] **Step 4: Run test, expect PASS.** Also run the neighboring S1 SS suite to confirm no regression: `mvn "-Dtest=com.openggf.game.sonic1.specialstage.*" test` (note: `Sonic1SpecialStageManagerTest` and siblings need the S1 ROM; they skip cleanly without it).

- [ ] **Step 5: Commit** — `feat(s1): add special-stage comparison snapshot seam` staging exactly the four files + `CHANGELOG.md` (one line under Unreleased: S1 special-stage trace comparison accessors). Trailers: `Changelog: updated`, rest `n/a`.

---

### Task 2: `s1_special_stage` schema (trace frame + data loader)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceFrame.java`
- Create: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceData.java`
- Test: `src/test/java/com/openggf/tests/trace/TestS1SpecialStageTraceParsing.java`

**Interfaces:**
- Consumes: `TraceMetadata.load`, `TraceData.resolveTraceFile`/`openTraceReader`/`loadAuxEvents` (all public).
- Produces: `Sonic1SpecialStageTraceFrame(int frame, int input, boolean lag, long xPos, long yPos, int velX, int velY, int inertia, int status, int ssAngle, int ssRotate, int bgAnim, int rings, int emeralds)` with `parseCsvRow(String)`; `Sonic1SpecialStageTraceData.load(Path)` requiring `trace_profile == "s1_special_stage"`, exposing `metadata()`, `frames()`, `frameCount()`, `getFrame(int)`, `getEventsForFrame(int)`, `eventsByFrame()`. Task 4 consumes both.

- [ ] **Step 1: Write the failing parser test.** Model on `TestS3kSpecialStageTraceParsing` (read it first for the metadata-fixture idiom — it builds a temp trace dir with `metadata.json` + `physics.csv`). Cover: (a) round-trip of a hand-built row exercising every column including an `x_pos` above `0x7FFFFFFF` would exceed s16/s32 concerns — use `fffe8000` (u32 parse via `Long.parseLong(...,16)`), `lag=1`, hex letters both cases handled by lowercase emitter only (parse with radix 16 accepts either); (b) wrong column count throws `IllegalArgumentException`; (c) `Sonic1SpecialStageTraceData.load` rejects a metadata with `trace_profile` `"s3k_special_stage"` (`IllegalArgumentException` naming the expected profile); (d) load succeeds on a 2-row fixture and `frameCount()==2`, `getFrame(1)` values match.

Example row for (a):

```java
String row = "7,208,1,fffe8000,00478000,fe00,0123,0456,03,4000,ff80,6,17,1";
Sonic1SpecialStageTraceFrame f = Sonic1SpecialStageTraceFrame.parseCsvRow(row);
assertEquals(7, f.frame());
assertEquals(0x208, f.input());
assertTrue(f.lag());
assertEquals(0xfffe8000L, f.xPos());
assertEquals(0x00478000L, f.yPos());
assertEquals(0xfe00, f.velX());
assertEquals(0x0123, f.velY());
assertEquals(0x0456, f.inertia());
assertEquals(0x03, f.status());
assertEquals(0x4000, f.ssAngle());
assertEquals(0xff80, f.ssRotate());
assertEquals(6, f.bgAnim());
assertEquals(0x17, f.rings());
assertEquals(1, f.emeralds());
```

- [ ] **Step 2: Run, expect compile failure.** `mvn "-Dtest=com.openggf.tests.trace.TestS1SpecialStageTraceParsing" test`

- [ ] **Step 3: Implement both classes.** Transcribe `S3kSpecialStageTraceFrame` / `S3kSpecialStageTraceData` (read both) with: 14 `COL_*` constants matching the schema order above, `COLUMN_COUNT=14`, `frame` decimal, `lag` `!parts[COL_LAG].trim().equals("0")`, `xPos`/`yPos` via `Long.parseLong(parts[i].trim(), 16)`, all other columns `Integer.parseInt(..., 16)`; `REQUIRED_TRACE_PROFILE = "s1_special_stage"`. Javadoc documents the exact header string and the "frame decimal / lag 0-1 / rest lowercase hex" convention, citing this plan's schema section.

- [ ] **Step 4: Run test, expect PASS.**

- [ ] **Step 5: Commit** — `feat(s1): add s1_special_stage trace schema` (3 files + `CHANGELOG.md`, `Changelog: updated`).

---

### Task 3: Recorder — detour state machine, maze writer, manifest

**Files:**
- Modify: `tools/bizhawk/s1_complete_run_recorder.lua`

**Interfaces:**
- Consumes (read as port models, do NOT modify): `tools/bizhawk/s3k_complete_run_recorder.lua` — globals block (~L828–848), `write_ss_metadata` (L5061), `start_ss_segment` (L5097), `write_ss_row` (L5134), `finalize_ss_segment` (L5200), the `on_frame_end` detour state machine (L5288–5342), stage-exit transition push (L5386–5403), `write_run_manifest` (L1420); `src/main/java/com/openggf/trace/TraceRunManifest.java` (authoritative manifest field names/optionality — read `validate()` and the `Segment`/`Transition` parsing before writing the emitter); the synthetic fixture `src/test/resources/traces/synthetic/run_aiz_gumball_3seg/run_manifest.json` (concrete field-shape reference).
- Produces: run recordings under `trace_output/` with level segments (existing v7 CSV), `ss/` segments (14-col `s1_special_stage` CSV + metadata), and `run_manifest.json`. Consumed by Task 4's committed-trace layout and the (deferred) chain test.

**S1-specific behavior contract:**
1. A maze row is written ONLY while `game_mode == 0x10`. Level rows remain gated on `game_mode == GAMEMODE_LEVEL` exactly as today (the existing non-level branch at L919 already stops level rows).
2. The `$10` detour branch is checked BEFORE the existing `game_mode ~= GAMEMODE_LEVEL` finalize+re-arm branch, and is gated on `detour_active ~= "special_stage"` for entry (NEVER on `started` alone — the S3K comment at L5288–5294 explains the re-fire bug this prevents).
3. Entry transition (`giant_ring`) is pushed when the SS segment opens; exit transition (`stage_exit`) is pushed at the next level segment's arm when the previous `segments_done` entry has kind `special_stage`. S1 transition records carry only `mode_change_bk2_frame`, `rings_before`/`emeralds_before` (entry) and `rings_after`/`emeralds_after` (exit) — there is no `Special_bonus_entry_flag`/`Saved_X_pos` analog. Verified: `TraceRunManifest.Transition`'s boundary fields are all optional `Integer`s, so the reduced S1 field set validates as-is (`validate()` also enforces `to == from + 1`, `from >= 0`, `to < segments.size()` — the `started` outer gate below is what keeps `from` non-negative).
4. `run_manifest.json` is emitted at end-of-run finalize only when a detour occurred or `OGGF_TRACE_RUN_ID` is set (plain complete-run captures remain byte-stable). The end-of-run finalize is an explicit if/else — SS finalize when `detour_active`, else level finalize — per Step 5's restructure (mirrors s3k L5822–5846; the naive "call both" ordering corrupts a mid-`$10`-truncated SS segment because `started` is true during SS segments too).
5. All new state/functions are **globals**. Bump the metadata `lua_script_version` (currently `"3.14"`) to `"3.15"` in `write_metadata` and use the same version in the new `write_ss_metadata`.
6. S1 caveat to encode as a comment on the writer: S1's SS results tally may run under `$10`; those tail rows are recorded (rows are cheap, comparator is red-allowed MVP) — the green campaign decides where engine comparison stops.

- [ ] **Step 1: Add the globals block** (after the existing top-level constants, clearly commented as this plan's addition):

```lua
-- s1-maze plan run/detour state (globals: 200-local budget).
-- Mirrors s3k_complete_run_recorder.lua v6.30/v6.31.
segments_done = {}
transitions_done = {}
segment_dir_counts = {}
detour_active = nil               -- nil | "special_stage"
current_segment_dir_token = nil
current_ss_index = nil
ss_min_angle_seen = nil           -- self-check accumulators
ss_max_angle_seen = nil
ss_last_rotate = nil
run_id = os.getenv("OGGF_TRACE_RUN_ID") or nil
```

- [ ] **Step 2: Track level segments in `segments_done`.** In the level start path (L867–917), after `OUTPUT_DIR` is set, record `current_segment_dir_token = start_zone_name .. tostring(start_act + 1)`. Add a global `function append_level_segment_done(rows)` that appends `{dir = current_segment_dir_token, kind = "level", profile = "complete_run", zone_id = start_zone_id, act = start_act + 1, bk2_frame_offset = bk2_frame_offset, rows = rows}` — `act` is **1-based** and `profile` is the string `"complete_run"`, matching the S3K emitter (`finalize_segment`, s3k recorder ~L4957–4982) and the synthetic fixture's level segments exactly (the S1 level `metadata.json` emits no `trace_profile` today; the manifest entry is where the profile lives — note this in a comment). Call it from the non-level finalize (L919–931) immediately after `write_metadata()`, from the SS-entry inline finalize (Step 4), and from the restructured end-of-run finalize per Step 5. Guard against double-append (only append when `started` was true at entry to that branch — all three sites gate on `started` and reset it). **Placement (Lua upvalue capture — load-bearing):** define `append_level_segment_done` and ALL Step 3 functions **after `close_files` (~L505)** — i.e., after every `local` they reference (`physics_file` L366, `trace_frame`/`started`/`bk2_frame_offset` L346–349, `start_zone_id`/`start_act` L353/355, `close_files` L505, `bk2_input_mask` L394 for `write_ss_row`) — mirroring the S3K recorder's mid-file placement. A global `function` defined up near the constants would bind nil globals instead of those locals; the Step 6 parse gate would still pass, and it would explode only at the first live detour. Only the Step 1 state-globals block goes up top.

- [ ] **Step 3: Add the SS segment functions** (globals): `write_ss_metadata`, `start_ss_segment`, `write_ss_row`, `finalize_ss_segment`. Port shape from the S3K functions; S1 differences: dir token counting via `segment_dir_counts["ss"]` identical (**keep the S3K `ss`/`ss_2` token style exactly** — S1's level-zone namespace already owns `ss1`..`ss4` because zone id 7 is named `ss` in `ZONE_NAMES`, so the bare-`ss` token avoids collision; note `precreate_segment_dirs` (L278–315) does NOT pre-create the bare `ss/` dir, so the first detour's `ensure_segment_dir` will shell out once on Windows — add `"ss"` to the pre-create list to avoid the cmd-window flash); `current_ss_index = mainmemory.read_u8(0xFE16)` (`v_lastspecial`, 0–5 — **sampling-window caveat, document in a comment**: `SS_Load` (`docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:536-556`) reads `v_lastspecial`, immediately increments it mod 6, and if the selected stage's emerald is already collected it loops to the NEXT stage; the arm-time read precedes `SS_Load` only because GM_Special opens with a multi-frame fade, and after a first emerald has been collected the pre-`SS_Load` value can name a stage the skip loop rejects. The finalize self-check must therefore re-read `v_lastspecial` and print it: `(index+1) % 6` = healthy, anything else = the skip loop fired and `special_stage_index` is suspect — re-derive before committing the trace); header/writer per this plan's 14-col schema; metadata fields:

```lua
function write_ss_metadata()
    local meta_file = io.open(OUTPUT_DIR .. "metadata.json", "w")
    meta_file:write("{\n")
    meta_file:write('  "game": "s1",\n')
    meta_file:write('  "trace_profile": "s1_special_stage",\n')
    meta_file:write('  "special_stage_index": ' .. current_ss_index .. ',\n')
    meta_file:write('  "ss_csv_version": 1,\n')
    meta_file:write('  "characters": ["sonic"],\n')
    meta_file:write('  "main_character": "sonic",\n')
    meta_file:write('  "sidekicks": [],\n')
    meta_file:write('  "bk2_frame_offset": ' .. bk2_frame_offset .. ',\n')
    meta_file:write('  "trace_frame_count": ' .. trace_frame .. ',\n')
    meta_file:write('  "source_bk2": "s1-complete-run.bk2",\n')
    meta_file:write('  "lua_script_version": "3.15",\n')
    meta_file:write('  "recording_date": "' .. os.date("%Y-%m-%d") .. '",\n')
    if run_id ~= nil then
        meta_file:write('  "run_id": "' .. run_id .. '",\n')
    end
    meta_file:write('  "fresh_load": false,\n')
    meta_file:write('  "segment_index": ' .. #segments_done .. '\n')
    meta_file:write("}\n")
    meta_file:close()
end
```

(`source_bk2` mirrors the level writer's constant; if the recording procedure uses a dedicated round-trip bk2, the operator renames it to match or updates both — note this in the Task 6 README procedure instead of adding a recorder env var.)

`write_ss_row` (full body — RAM map is this plan's contract):

```lua
function write_ss_row()
    local raw_input = mainmemory.read_u8(0xF604)  -- v_jpadhold1 (fallback source)
    local input_mask = bk2_input_mask(raw_input, trace_frame)
    local lag = emu.islagged() and 1 or 0
    local x_pos = mainmemory.read_u32_be(PLAYER_BASE + 0x08)
    local y_pos = mainmemory.read_u32_be(PLAYER_BASE + 0x0C)
    local vel_x = mainmemory.read_u16_be(PLAYER_BASE + 0x10)
    local vel_y = mainmemory.read_u16_be(PLAYER_BASE + 0x12)
    local inertia = mainmemory.read_u16_be(PLAYER_BASE + 0x14)
    local status = mainmemory.read_u8(PLAYER_BASE + 0x22)
    local ss_angle = mainmemory.read_u16_be(0xF780)   -- v_ssangle
    local ss_rotate = mainmemory.read_u16_be(0xF782)  -- v_ssrotate
    local bg_anim = mainmemory.read_u16_be(0xF7A0)    -- v_ssbganim
    local rings = mainmemory.read_u16_be(0xFE20)      -- v_rings
    local emeralds = mainmemory.read_u8(0xFE57)       -- v_emeralds
    physics_file:write(string.format(
        "%d,%x,%d,%x,%x,%x,%x,%x,%x,%x,%x,%x,%x,%x\n",
        trace_frame, input_mask, lag, x_pos, y_pos, vel_x, vel_y, inertia,
        status, ss_angle, ss_rotate, bg_anim, rings, emeralds))
    if trace_frame % 60 == 0 then physics_file:flush() end
    if trace_frame % 300 == 0 then write_ss_metadata() end
    -- VERIFY-ON-FIRST-CAPTURE self-check accumulators.
    if ss_min_angle_seen == nil or ss_angle < ss_min_angle_seen then ss_min_angle_seen = ss_angle end
    if ss_max_angle_seen == nil or ss_angle > ss_max_angle_seen then ss_max_angle_seen = ss_angle end
    ss_last_rotate = ss_rotate
    if trace_frame == 0 or trace_frame % 300 == 0 then
        print(string.format(
            "S1 SS frame %d: x=0x%08X y=0x%08X angle=0x%04X rotate=0x%04X rings=%d emeralds=%d",
            trace_frame, x_pos, y_pos, ss_angle, ss_rotate, rings, emeralds))
    end
    trace_frame = trace_frame + 1
end
```

`finalize_ss_segment` is guarded `if not started then return end` (like s3k L5201–5203), then in order: `physics_file:flush()`, **`write_ss_metadata()`** (final `trace_frame_count` — without this rewrite the metadata is stale at the last 300-multiple, s3k L5204–5205), the self-check summary print (`angle range seen`, `final ss_rotate (exit ramp targets 0x1800)`, row count, and the `v_lastspecial` re-read from Step 3's caveat), `close_files()`, append `{dir = current_segment_dir_token, kind = "special_stage", profile = "s1_special_stage", special_stage_index = current_ss_index, zone_id = 0, act = 0, bk2_frame_offset = bk2_frame_offset, rows = trace_frame}` to `segments_done`, then reset shared recording state exactly like the level finalize (`started=false`, `trace_frame=0`, clear the ss accumulators and `current_ss_index`). NOTE: check `bk2_input_mask`'s implementation for hidden assumptions (it may index a preloaded BK2 input table by `bk2_frame_offset + trace_frame` — if so it works unchanged for SS segments because `start_ss_segment` re-bases `bk2_frame_offset`; if it assumes level-only state, adapt and document).

- [ ] **Step 4: Wire the detour state machine into `on_frame_end`.** Insert BEFORE the `game_mode ~= GAMEMODE_LEVEL` branch (L919), mirroring the S3K structure — the S1 recorder reads `game_mode` at the TOP of `on_frame_end` (L842), so the insertion point is after the stop/movie-end guard and BEFORE the `if not started` arm gate as well. SS entry ALWAYS occurs with a level segment armed: the Got-Through card writes `#id_Special` directly while GM_Level runs, gated on the flash-set `f_bigring` (`3A Got Through Card.asm:201`; flash sets the flag at `4B, 7C Giant Ring and Flash.asm:123`), so the edge is a direct `$0C -> $10` with `started == true` — the outer `started` gate never drops a legitimate detour (the inner `if started then` in the entry path below is redundant with the outer gate; keep it as defensive structure but comment it as such). Exact structure:

```lua
    -- ---- s1-maze stage-detour state machine (port of s3k v6.31) ----
    -- Outer gate mirrors s3k (L5295): `started` is true both when a level
    -- segment is armed at the $0C->$10 edge AND on every continuation frame
    -- (start_ss_segment sets it). Gating on detour_active alone for the
    -- entry branch prevents the re-finalize/re-open-on-every-$10-frame bug;
    -- requiring `started` prevents a bogus from_segment=-1 transition if a
    -- movie begins inside $10 with nothing armed (dedicated interior
    -- captures are not this recorder's job).
    if started and game_mode == 0x10 then  -- GM_Special
        if detour_active ~= "special_stage" then
            -- ENTRY: finalize any armed level segment first, then push the
            -- giant_ring transition with exact indices, then arm the SS
            -- segment ONCE.
            if started then
                if physics_file then physics_file:flush() end
                write_metadata()
                append_level_segment_done(trace_frame)
                close_files()
                started = false
                trace_frame = 0
            end
            transitions_done[#transitions_done + 1] = {
                from_segment = #segments_done - 1,
                to_segment = #segments_done,
                entry_kind = "giant_ring",
                mode_change_bk2_frame = emu.framecount(),
                rings_before = mainmemory.read_u16_be(0xFE20),
                emeralds_before = mainmemory.read_u8(0xFE57),
            }
            start_ss_segment()
            detour_active = "special_stage"
            print(string.format("S1 special-stage detour at bk2 frame %d.", emu.framecount()))
            return
        end
        write_ss_row()
        return
    end
    if detour_active == "special_stage" then
        finalize_ss_segment()
        detour_active = nil
        -- fall through: non-$10 frames (results/fade under $0C or otherwise)
        -- are manifest-only until the level arm gate below re-arms.
    end
```

The stage-exit transition push goes in the level arm path (inside the `if not started` branch, right before `open_files()`), gated on `#segments_done > 0 and segments_done[#segments_done].kind == "special_stage"`:

```lua
            if #segments_done > 0 and segments_done[#segments_done].kind == "special_stage" then
                transitions_done[#transitions_done + 1] = {
                    from_segment = #segments_done - 1,
                    to_segment = #segments_done,
                    entry_kind = "stage_exit",
                    mode_change_bk2_frame = emu.framecount(),
                    rings_after = mainmemory.read_u16_be(0xFE20),
                    emeralds_after = mainmemory.read_u8(0xFE57),
                }
            end
```

Also: the existing non-level finalize branch (L919) must now call `append_level_segment_done(trace_frame)` (Step 2) — and the SS-entry path above deliberately duplicates the finalize inline because entry can occur while `started` is true without passing through that branch (the `$0C -> $10` edge arrives as `game_mode == 0x10`, so L919's branch would otherwise ALSO fire on the same frame — the detour branch's early `return` prevents double-finalize; verify this ordering carefully and add a comment).

- [ ] **Step 5: Restructure the end-of-run finalize + port `write_run_manifest`.** The existing stop/movie-end branch (L856–864) runs the LEVEL finalize whenever `started` — but `started` is also true during an SS segment (`start_ss_segment` sets it), so a movie/stop ending mid-`$10` would run the level finalize against the SS segment: the level `write_metadata()` (which carries the previous level's zone/act and no `trace_profile`) would overwrite `ss/metadata.json` via the shared `OUTPUT_DIR`, a bogus `kind="level"` entry would be appended for the ss dir, and the subsequent `finalize_ss_segment()` would no-op on its `not started` guard. **Restructure L856–864 as an explicit if/else, mirroring the S3K end-of-run finalize (s3k recorder L5822–5846) literally:**

```lua
    if stop_reached or movie_done then
        if detour_active == "special_stage" then
            finalize_ss_segment()
            detour_active = nil
        elseif started then
            if physics_file then physics_file:flush() end
            write_metadata()
            append_level_segment_done(trace_frame)
            close_files()
            started = false
        end
        write_run_manifest()
        finished = true
        return
    end
```

**The FRAME_CAP backstop is a THIRD finalize site and must be restructured too:** the main-loop backstop at L1131–1141 ("even if every movie-end signal fails") contains its own inline level finalize and the loop's `if finished` block (L1146–1151) does not re-finalize — left untouched, a run ending via the backstop mid-`$10` corrupts `ss/metadata.json` exactly as described above, and even a non-detour backstop ending would never emit the manifest. Restructure it the way the S3K recorder does structurally (s3k L5816–5821 backstop only sets `finished = true`; ALL finalization funnels through the single end-of-run if/else at s3k L5822–5846). Concretely: extract the Step 5 lua block's contents (everything except the trailing `finished = true` / `return`) into a global `function finalize_run_end()` (defined after `close_files`, per the Step 2 placement rule); the L856–864 stop branch becomes `if stop_reached or movie_done then finalize_run_end(); finished = true; return end`; the L1131–1141 backstop's inline finalize is replaced by `finalize_run_end(); finished = true`. Every live termination path — stop-frame, movie-done, and backstop — then funnels through one finalize that handles both detour and level cases. The two mid-loop `finished = true` sites at L943/L950 (HEADLESS BK2-bound + mid-loop FINISHED checks) are **shadowed dead code since v3.6** — the top-of-function movie-end guard (L853–854) fires strictly earlier on both predicates and terminates via the funnel — so they are deliberately excluded; replace their `finished = true` with `finalize_run_end(); finished = true` anyway as belt-and-braces (cheap, and keeps the funnel claim unconditionally true).

`write_ss_metadata`'s `trace_frame_count` correctly labels the truncated SS segment. Then port `write_run_manifest` (global function) from the S3K recorder (L1420): same invariant warning loop (transition indices bounded by `#segments_done`), same gating (`#transitions_done == 0 and run_id == nil` -> skip), same field names as the S3K emitter/`TraceRunManifest` schema — note the manifest JSON key for per-segment frame counts is **`trace_frame_count`** (the Lua-side table field is `rows`; the emitter maps it), `"game": "s1"`, `"source_bk2": "s1-complete-run.bk2"`. **Do NOT transcribe the S3K emitter's globals literally** — it interpolates `SOURCE_BK2_NAME` / `S3K_ROM_CHECKSUM` / `LUA_SCRIPT_VERSION`, none of which exist in the S1 recorder (a literal port hits `string.format('%q', nil)` at end-of-run): inline `"3.15"` for the script version, the literal `"AFE05EEE"` (S1 World REV01 CRC32, per CLAUDE.md) for `rom_checksum`, and the `source_bk2` literal above. `TraceRunManifest.Transition` boundary fields (`saved_x_pos` etc.) are all optional `Integer`s — the S1 records' reduced field set validates as-is; `special_stage` segments hard-require `special_stage_index` (emitted). Read the S3K emitter and the synthetic fixture before writing; mirror them, translating only the game id, profile strings, and the inlined literals above.

- [ ] **Step 6: Parse gate.** Run: `docs/skdisasm/build_tools/lua/lua.exe -e "assert(loadfile('tools/bizhawk/s1_complete_run_recorder.lua'))"` from the repo root. Expected: no output, exit 0. (This catches syntax + the 200-local overflow.)

- [ ] **Step 7: Manifest compatibility test.** Extend nothing in Java — instead verify the emitter shape against the validator with a focused check: run `mvn "-Dtest=com.openggf.tests.trace.TestTraceRunManifest" test` (existing suite must stay green; it pins the schema the emitter targets). If the S1 emitter needed any field the validator rejects, fix the emitter, not the validator.

- [ ] **Step 8: Commit** — `feat(trace): s1 recorder stage-detour state machine + maze writer + run manifest` (lua file only; no `src/main` -> `Changelog: n/a: recorder tooling only, no engine change`).

---

### Task 4: Standalone replay harness + comparator test

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s1/S1SpecialStageReplayHarness.java`
- Create: `src/test/java/com/openggf/tests/trace/s1/AbstractS1SpecialStageTraceReplayTest.java`
- Create: `src/test/java/com/openggf/tests/trace/s1/TestS1SpecialStageTraceReplay.java`

**Interfaces:**
- Consumes: Task 1's `getManager()`/`captureComparisonState()`, Task 2's `Sonic1SpecialStageTraceData`/`Frame`; `Bk2MovieLoader`, `RecordedInputSnapshots.fromBk2`, `SpecialStageInputMapper`, `InputHandler.setLogicalOverride/clearLogicalOverride`, `RomTestUtils.ensureSonic1RomAvailable()`, `TestEnvironment.configureRomFixture(rom)`, `DivergenceReport`/`FrameComparison`/`FieldComparison`/`Severity`.
- Produces: report at `target/trace-reports/s1_special_stage_<index>_report.json`.

**Harness** — transcribe `S3kSpecialStageReplayHarness` (read it first) with these deltas, documented in the class javadoc:
- Package `com.openggf.tests.trace.s1`, class package-private.
- Provider `Sonic1SpecialStageProvider`; `initializeStage(int)` throws `IOException` (same one-arg surface).
- **Single-player:** drive only `provider.handleInput(mapped.p1Held(), mapped.p1Pressed())` — no `handlePlayer2Input` call (S1 maze is single-player; the interface default is a no-op but calling it would be dead code).
- Team config before `initializeStage`: `MAIN_CHARACTER_CODE="sonic"` only (S1 module has no sidekick in SS; do not set `SIDEKICK_CHARACTER_CODE`).
- Do NOT call `setLagCompensation` (S1's is a no-op scaffold, `Sonic1SpecialStageProvider.java:173-175`).
- `capture()` returns `provider.getManager().captureComparisonState()`; `isFinished()` delegates to provider.
- Identical BK2 indexing + press-edge rule text (previous PHYSICAL row, `fromBk2` synthesizes neutral prior on null) and identical `stepFrame` shape.

**Abstract test** — transcribe `AbstractS3kSpecialStageTraceReplayTest` with these deltas:
- `TRACE_DIRECTORY = Path.of("src","test","resources","traces","s1","special_stage")`; profile assertion `"s1_special_stage"`; report prefix `s1_special_stage_<index>`; boot uses `RomTestUtils.ensureSonic1RomAvailable()` + `assumeTrue`, `GraphicsManager` resetState/initHeadless, `Rom.open`, `TestEnvironment.configureRomFixture(rom)`, second `initHeadless()` (same double-init as the S3K test — `configureRomFixture` resets graphics).
- **Comparator field mapping (all Tier-1 ERROR; equality after masking both sides — sign-agnostic, the bs-plan signedWord lesson generalized):**
  - `x_pos` expected `tf.xPos() & 0xFFFFFFFFL` vs actual `state.sonicPosX() & 0xFFFFFFFFL` (compare as `Long.toString`); same for `y_pos`/`sonicPosY`.
  - `vel_x`/`vel_y`/`inertia`: `tf.velX() & 0xFFFF` vs `state.sonicVelX() & 0xFFFF` (etc.).
  - `status_facing_left`: `(tf.status() & 0x1) != 0` vs `state.sonicFacingLeft()`; `status_airborne`: `(tf.status() & 0x2) != 0` vs `state.sonicAirborne()`. Only bits 0–1 are modeled; javadoc notes the raw `status` column keeps the full byte for future bits.
  - `ss_angle`/`ss_rotate`/`bg_anim`: `& 0xFFFF` both sides (engine fields may be updated with signed arithmetic; ROM word equality is what matters).
  - `rings`: **delta-based** — expected `(tf.rings() - trace.getFrame(0).rings()) & 0xFFFF` vs actual `state.ringsCollected()`. Rationale (javadoc): ROM `v_rings` may carry a pre-SS value into the maze while the engine's `ringsCollected` starts at 0; the per-frame delta is the comparable quantity. (If the first capture shows `v_rings` actually starts at 0 in the maze, the baseline subtracts 0 and this is exact.)
  - `emeralds`: expected boolean `tf.emeralds() != trace.getFrame(0).emeralds()` vs actual `state.emeraldCollected()`.
- **Terminal exit check** replaces the S3K `finished_transition_frame` anchor (S1's segment simply ends at the `$10` mode exit; there is no in-segment completion marker like `fade_timer`): after the loop, append one `FrameComparison` at the last frame index with field `exit_state_at_end`, expected `"true"`, actual `String.valueOf(state.exitTriggered() || state.finished())` from the final captured state. Javadoc: the ROM leaves `$10` via the exit ramp (`v_ssrotate` -> `$1800`, `sonic.asm` `SS_MainLoop` exit) — by the recorded segment's final row the engine must have raised its exit sequence. MVP red-allowed.
- Keep the lag-row skip rule, `assertNoReleaseBlockingDivergences`, `writeReport`, `reportDir`, `specialStageIndex` helpers unchanged (S1 names).

**Concrete test:**

```java
package com.openggf.tests.trace.s1;

import java.nio.file.Path;

/**
 * Replays the committed S1 special-stage (maze) trace when one exists at
 * {@code src/test/resources/traces/s1/special_stage}; skips (assumption)
 * until the recording lands. See tools/bizhawk/README.md for the recording
 * procedure.
 */
class TestS1SpecialStageTraceReplay extends AbstractS1SpecialStageTraceReplayTest {
    @Override
    protected Path traceDirectory() {
        return TRACE_DIRECTORY;
    }
}
```

- [ ] **Step 1: Write harness + abstract + concrete test** per the deltas above (transcription from the S3K trio with the S1 mapping).
- [ ] **Step 2: Run** `mvn "-Dtest=com.openggf.tests.trace.s1.TestS1SpecialStageTraceReplay" test` — expected: SKIPPED via assumption (no committed trace yet) after compiling cleanly. NOTE (gate lesson): plain `mvn test` excludes `**/tests/trace/**`; this explicit `-Dtest` run is the only compile+execute check these classes get — do not skip it.
- [ ] **Step 3: Also run** `mvn "-Dtest=com.openggf.tests.trace.TestS1SpecialStageTraceParsing" test` again (guards against comparator/parser drift introduced while wiring).
- [ ] **Step 4: Commit** — `test(trace): s1 special-stage replay harness + skip-if-missing comparator` (3 test files; `Changelog: n/a: test-only change`).

---

### Task 5: #6-S1 headless standalone-loadability verify

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS1SpecialStageHeadlessBoot.java`

**Interfaces:**
- Consumes: `Sonic1SpecialStageProvider` (Task 1's `getManager()`), `RomTestUtils.ensureSonic1RomAvailable`, `TestEnvironment.configureRomFixture`.

Model on `TestS3kSpecialStageHeadlessBoot` (read it first). The S1 manager already boots headlessly in `Sonic1SpecialStageManagerTest` via `initHeadless()`; this test proves the **provider-path** boot the harness/GameLoop uses (spec addition #6-S1 — expected to confirm a no-op, i.e. no new init hook needed).

- [ ] **Step 1: Write the test.** **Mirror `TestS3kSpecialStageHeadlessBoot` literally** (read it first): it uses `@RequiresRom(SonicGame.SONIC_3K)` — no `assumeTrue`, no manual `Rom.open`/`configureRomFixture`, and no teardown; the S1 version uses `@RequiresRom(SonicGame.SONIC_1)` and whatever setup the S3K test actually performs, with `Sonic1SpecialStageProvider` substituted — **dropping the S3K-only content**: the `SIDEKICK_CHARACTER_CODE "tails"` config line (S1 maze is single-player, consistent with Task 4's delta), the `provider.handlePlayer2Input(0,0)` call, and the `Sonic3kSpecialStageRomOffsets.areOffsetsVerified()` / `getSpheresLeft() > 0` assertions (no S1 analogs; this test's own assertions below replace them). (`Sonic1SpecialStageManagerTest` is the second precedent: `@RequiresRom(SONIC_1)` + `initHeadless` + `initialize(0)` + repeated `update()` already work headlessly.) Body: `provider.initializeStage(0)`; assert `provider.isInitialized()`; capture frame-0 state; step 180 frames (`provider.handleInput(0,0); provider.update();`); assert the final `provider.getManager().captureComparisonState()` differs from the frame-0 capture — specifically `ssAngle` changed: `update()` adds `SS_INIT_ROTATION = 0x40` to `ssAngle` unconditionally, so this is guaranteed non-vacuous — and `provider.isFinished()` is false.
- [ ] **Step 2: Run** `mvn "-Dtest=com.openggf.tests.TestS1SpecialStageHeadlessBoot" test` — expected PASS with the S1 ROM present (pass `"-Ds1.rom.path=..."` if the runner needs it; discover the root-level `.gen` per CLAUDE.md, do not rename ROMs).
- [ ] **Step 3: Commit** — `test(s1): verify special-stage provider boots headlessly (spec addition #6-S1)` (`Changelog: n/a: test-only change`).

---

### Task 6: Docs, spec amendment, recording procedure

**Files:**
- Modify: `tools/bizhawk/README.md`
- Modify: `docs/status/trace-frontier-log.md`
- Modify: `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md`

- [ ] **Step 1: README recording procedure** (mirror the blue-spheres section's structure): "S1 maze round-trip (s1-ghz-maze-roundtrip)": record a BizHawk movie on the S1 World REV01 ROM — play GHZ1 collecting ≥50 rings, touch the giant ring past the signpost, complete (or fail out of) the maze, continue into GHZ2 until control is settled, stop the movie. Run `s1_complete_run_recorder.lua` against the bk2 (headless per the existing S1 recorder invocation) — the `$10` detour automatically produces `ghz1/` + `ss/` + `ghz2/` segments and `run_manifest.json`. Commit the run under `src/test/resources/traces/s1/runs/s1-ghz-maze-roundtrip/`, copy the `ss/` segment (with its `metadata.json`, `physics.csv`, and the source bk2) to `src/test/resources/traces/s1/special_stage/` to activate `TestS1SpecialStageTraceReplay`. The `source_bk2` filename must match the actual bk2 committed alongside: **mandate the rename** — commit the movie as `s1-complete-run.bk2` inside the trace dirs. (Editing `source_bk2` in the copied SS metadata instead would leave the run bundle internally inconsistent — the level segments' metadata hardcodes `"s1-complete-run.bk2"` at recorder L498.) Include the VERIFY-ON-FIRST-CAPTURE checklist: recorder self-check prints must show plausible angle range, final `ss_rotate` ramping toward `0x1800`, rings/emeralds behaving, and the finalize `v_lastspecial` re-read printing `(special_stage_index + 1) % 6` (anything else = the `SS_Load` emerald-skip loop fired and the recorded index is suspect); any surprise = re-derive the RAM map before committing the trace. Also note: record the round-trip on a **fresh save/no-emeralds state** — after a first emerald is collected, `v_lastspecial`'s pre-`SS_Load` value can name a stage the ROM's skip loop rejects, mislabeling the segment.
- [ ] **Step 2: Frontier log entry** (follow the blue-spheres entry format): pipeline landed, trace pending recording; record the deferred follow-ups explicitly — (a) in-chain/visual SS-interior comparison (shared with S3K blue spheres), (b) standalone visual SS launch still `s2_special_stage`-gated in `TraceSessionLauncher`/`TraceEntry`, (c) S1 SS results-tail rows recorded under `$10` may need a comparator stop rule in the green campaign.
- [ ] **Step 3: Spec amendment:** edit the `s1_trace_recorder.lua` line (~201) to name `s1_complete_run_recorder.lua` as the landed host, one-line parenthetical citing S3K parity (the run machinery lives in the complete-run recorders).
- [ ] **Step 4: Commit** — `docs(trace): s1 maze recording procedure + frontier entry + spec recorder-host amendment` (`Changelog: n/a: docs only`).

---

## Verification gate (after all tasks)

1. Explicit new-test sweep: the five `-Dtest` runs from Tasks 1–5 all green/skipped-as-designed.
2. Guard classes verified EXPLICITLY (aggregate diffs hide per-guard regressions — slots lesson): `mvn "-Dtest=com.openggf.tests.TestTraceReplayInvariantGuard" test`, `TestArchUnitRules`, `TestArchUnitTestRules`, `TestTraceRunManifest`, `TestSingletonLifecycleGuard`.
3. Full `mvn test` (detached `nohup mvn test > log &` + Monitor; sandbox off; remember surefire excludes `**/tests/trace/**` — trace classes are covered by step 1, not this run). Compare failures against the develop baseline (29F/6E pre-existing); NEW failures block.
4. Lua parse gate re-run on the final recorder.
