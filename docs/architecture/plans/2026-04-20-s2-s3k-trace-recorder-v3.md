# Sonic 2 and Sonic 3&K Trace Recorder v3 Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Sonic 2 and Sonic 3&K onto the schema v3 trace-replay contract already landed for Sonic 1, so that future S2 and S3K BizHawk captures emit `gameplay_frame_counter`, `vblank_counter`, and `lag_counter` columns driven by real ROM counters rather than the legacy heuristic.

**Architecture:** The Java-side v3 schema, backward-compatible parser, and execution-model phase derivation already shipped under `docs/architecture/plans/2026-04-19-trace-lag-model.md` (Tasks 2–5). This plan covers the deferred Tasks 6a (S2 recorder) and 6b (S3K recorder) only. It adds two new BizHawk Lua recorders, updates the launcher batch / docs, and updates the `Trace Replay Recorder Coverage` known-discrepancy entry to reflect that recorder migration is complete while real BK2-derived S2/S3K fixture coverage remains follow-up work.

**Tech Stack:** BizHawk 2.11 EmuHawk Lua scripting, existing Java 21 / JUnit 5 trace-replay harness (`TraceData`, `TraceFrame`, `TraceMetadata`, `TraceExecutionModel`), Sonic 2 and Sonic 3&K disassemblies under `docs/s2disasm/` and `docs/skdisasm/`.

**Reference:**
- `tools/bizhawk/s1_trace_recorder.lua` — canonical v3 recorder (Sonic 1)
- `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md` — cross-game execution matrix and counter addresses (S1/S2/S3K already resolved)
- `docs/architecture/plans/2026-04-19-trace-lag-model.md` — parent plan; Tasks 6a/6b stubs
- `docs/guide/contributing/trace-replay.md` — contributor-facing documentation
- `docs/status/known-discrepancies.md` entry #10 — `Trace Replay Recorder Coverage`

**Out of scope:**
- Do not touch the Java-side parser, metadata, or execution model — v3 is already accepted for all games.
- Do not regenerate existing Sonic 1 trace fixtures.
- Do not add a synthetic S2/S3K lag counter where the ROM does not expose one. For S2, write `lag_counter = 0` as a diagnostic placeholder; do not derive one from `Vint_runcount - Level_frame_counter`.
- Do not modify replay tests to force-run against new fixtures until the recorders are validated end-to-end.
- Do not remove the legacy heuristic fallback in `TraceData` until a real v3 fixture from S2 and one from S3K have parsed and replayed successfully.

---

## Pre-Resolved Counter Addresses

The counter matrix is already frozen in `docs/architecture/research/2026-04-19-trace-lag-model-matrix.md`. Recorder implementations must use these exact addresses and no others:

| Game | `gameplay_frame_counter` | `vblank_counter` | `lag_counter` |
|---|---:|---:|---|
| S1 | `0xFE04` (`v_framecount`) | `0xFE0E` (`v_vbla_word`) | n/a — emit `0` |
| S2 | `0xFE04` (`Level_frame_counter`) | `0xFE0E` (`Vint_runcount+2`) | n/a — emit `0` |
| S3K | `0xFE08` (`Level_frame_counter`) | `0xFE12` (`V_int_run_count+2`) | `0xF628` (`Lag_frame_count`) |

Any task that reports a different address for any of the values above must stop and update the research matrix first. Counter addresses are the part of this plan with the tightest ROM coupling, and the matrix is the single source of truth.

## Scope and Constraints

- Keep the v3 CSV schema byte-for-byte identical across S1/S2/S3K. The Java parser does not branch by game for the physics columns; any divergence must be fixed in the recorder, not the parser.
- Reuse the S1 recorder's output-file layout (`physics.csv`, `aux_state.jsonl`, `metadata.json`) so `TraceData.load(Path)` needs no per-game switching.
- Do not rewrite the S1 recorder as part of this work. If a shared helper is genuinely needed, the first step is to factor it out of S1 and add tests — but the default here is to duplicate rather than prematurely abstract three Lua files.
- Each recorder must start recording only once the game is in gameplay mode with controls unlocked, matching S1 behavior. This avoids "dead frame" leading rows where BK2 input is present but physics has not run yet.
- Each recorder must honor `--chromeless --lua` headless execution with auto-exit on movie end.

## File Map

| Path | Action | Responsibility |
|---|---|---|
| `tools/bizhawk/s2_trace_recorder.lua` | Create | Sonic 2 v3 recorder. |
| `tools/bizhawk/s3k_trace_recorder.lua` | Create | Sonic 3&K v3 recorder. |
| `tools/bizhawk/record_s2_trace.bat` | Create | Launch helper for S2 recorder (mirror of `record_trace.bat`). |
| `tools/bizhawk/record_s3k_trace.bat` | Create | Launch helper for S3K recorder. |
| `tools/bizhawk/README.md` | Modify | Add S2 and S3K recorder usage, keep canonical trace-replay guide pointer. |
| `docs/guide/contributing/trace-replay.md` | Modify | Document that S2 and S3K now emit v3 traces; update the recorder walkthrough. |
| `docs/status/known-discrepancies.md` | Modify | Update entry #10 `Trace Replay Recorder Coverage` to reflect completed recorder migration and deferred BK2-derived S2/S3K fixtures. |
| `docs/architecture/research/2026-04-20-s2-trace-addresses.md` | Create | Per-game S2 RAM addresses used by the recorder beyond the counter matrix (player base, game mode, zone/act, camera, input, SST layout, status bits). |
| `docs/architecture/research/2026-04-20-s3k-trace-addresses.md` | Create | Same, for Sonic 3&K. |
| `src/test/resources/traces/synthetic/s2_execution_v3_2frames/` | Create | Hand-authored synthetic v3 trace with `game: s2` — exercises the S2 metadata path. Mirror layout of the existing `execution_v3_2frames/` fixture (3 files: `physics.csv`, `metadata.json`, `aux_state.jsonl`). |
| `src/test/resources/traces/synthetic/s3k_execution_v3_2frames/` | Create | Same shape, with `game: s3k` and a non-zero `lag_counter` value on the second frame to exercise the S3K lag-counter path. |
| `src/test/java/com/openggf/tests/trace/TestS2SyntheticV3Fixture.java` | Create | JUnit 5 smoke test: loads the S2 synthetic fixture, asserts `traceSchema == 3`, `game == "s2"`, counter monotonicity, `lag_counter == 0` on every frame. |
| `src/test/java/com/openggf/tests/trace/TestS3kSyntheticV3Fixture.java` | Create | JUnit 5 smoke test: loads the S3K synthetic fixture, asserts `traceSchema == 3`, `game == "s3k"`, counter monotonicity, at least one frame with `lag_counter > 0`, and that `TraceExecutionModel.forGame("s3k").phaseFor(prev, curr)` returns `VBLANK_ONLY` for lag frames. |

Changes to `src/test/**` are limited to two new synthetic fixtures (one per game) plus minimal parser-smoke tests (see Tasks 3 and 6). The Java parser itself, `TraceExecutionModel`, and `AbstractTraceReplayTest` are not modified — the new tests piggyback on existing public API. The fixtures lock in per-game `metadata.json` acceptance paths (`game: s2`, `game: s3k`) that no current test covers.

## Risk Register

- **Status bit divergence.** S1 `OFF_STATUS = 0x22` and its bit assignments do not automatically port to S2/S3K. The S2 player object extends to `$40` with a different `status_secondary` byte, and S3K adds elemental shield and Super-form bits. The recorder must read the game-appropriate status byte and emit the same semantic meaning (`air`, `rolling`, `ground_mode`) in the CSV; do not blindly copy S1 bit masks.
- **Player base divergence.** S1 uses `PLAYER_BASE = 0xD000` (SST slot 0). S2 uses `MainCharacter = $FFB000` with the SST starting there. S3K uses `$FFB000` as well but with a different SST layout and additional `Player2/Player3` slots for Tails/Knuckles partners.
- **Input latch divergence.** S1 exposes `v_jpadhold1` at `0xF604`. S2 uses `Ctrl_1_held`, S3K uses `Ctrl_1_held` at different RAM addresses. The recorder must write the same `input_mask` byte semantics as S1 across all games.
- **Zone/act name mapping.** The S1 `ZONE_NAMES` table is game-specific. Each recorder needs its own table derived from its constants file. Replay tests key fixtures by zone name, so consistency across runs matters.
- **Game-mode sentinel.** S1 uses `GAMEMODE_LEVEL = 0x0C`. S2 and S3K use different constants (`IDs_Level`, `GM_Level`). Each recorder must detect "in-level" correctly or it will capture title-screen / results-screen frames.
- **S3K: S&K-side addresses only.** The locked-on Sonic 3 & Knuckles ROM contains two halves (S&K `< 0x200000`, S3 `>= 0x200000`) with many shared symbols defined in both `docs/skdisasm/sonic3k.asm` and `docs/skdisasm/s3.asm`. The engine's S3KL runtime and every S3K address in `Sonic3kConstants.java` is S&K-side only. Task 4's research must cite `docs/skdisasm/sonic3k.asm` / `docs/skdisasm/sonic3k.constants.asm`; do not substitute an `s3.asm` address even when the label exists in both files. See `CLAUDE.md` "Sonic 3&K Bring-up Notes" and the `s3k-disasm-guide` skill for details.

Each risk is addressed by an explicit step in Task 1 or Task 4 below.

---

## Task 1: Resolve Remaining Sonic 2 Addresses

**Files:**
- Create: `docs/architecture/research/2026-04-20-s2-trace-addresses.md`

- [ ] **Step 1: Write the per-game address matrix**

Document every RAM address the S2 recorder needs beyond the counter matrix. At minimum:

```md
| Purpose | Label | Address | Evidence |
|---|---|---:|---|
| game mode | `Game_Mode` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| in-level game-mode value | `IDs_Level` / `id_Level` | `0x0C` | `docs/s2disasm/s2.asm:<line>` |
| player SST base | `MainCharacter` | `<fill>` | `docs/s2disasm/s2.constants.asm:1101` |
| SST slot size | `object_size` | `0x40` | `docs/s2disasm/s2.constants.asm:<line>` |
| total SST slots | see `Object_RAM_End - Object_RAM` | `<fill>` | `docs/s2disasm/s2.constants.asm:1096-1145` |
| camera X | `Camera_X_pos` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| camera Y | `Camera_Y_pos` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| current zone | `Current_zone` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| current act | `Current_act` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| ring count | `Ring_count` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| held input P1 | `Ctrl_1_held` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| held-logical input P1 | `Ctrl_1_logical` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` |
| player status primary | offset `status` within SST slot | `<fill>` | `docs/s2disasm/_inc/Object RAM offsets.asm:<line>` |
| player status secondary | offset `status_secondary` | `<fill>` | `docs/s2disasm/_inc/Object RAM offsets.asm:<line>` |
| player routine | offset `routine` | `<fill>` | `docs/s2disasm/_inc/Object RAM offsets.asm:<line>` |
| control-lock timer offset | offset `move_lock` (or equivalent) within SST slot | `<fill>` | `docs/s2disasm/_inc/Object RAM offsets.asm:<line>` (zeroed when player regains control; used to gate recording start) |
| stand-on-object | offset `interact` or `top_solid_bit` (whichever tracks rider) | `<fill>` | `docs/s2disasm/_inc/Object RAM offsets.asm:<line>` |
| player routine — hurt | routine value | `<fill>` | `docs/s2disasm/_incObj/01 Sonic.asm:<line>` or `_incObj/02 Tails.asm` (replaces S1 `0x04`) |
| player routine — death | routine value | `<fill>` | `docs/s2disasm/_incObj/01 Sonic.asm:<line>` (replaces S1 `0x06`) |
| first dynamic SST slot | `Dynamic_object_RAM` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` (slot index, NOT absolute address — compute as `(Dynamic_object_RAM - Object_RAM) / object_size`) |
| last SST slot (exclusive) | `Object_RAM_End` | `<fill>` | `docs/s2disasm/s2.constants.asm:<line>` (slot index) |
```

- [ ] **Step 2: Resolve the S2 status bit mapping**

Add a subsection naming each status bit used by the CSV (`air`, `rolling`, facing-left, on-object, underwater, pushing, roll-jump) with bit values and evidence:

```md
| Semantic | Bit | Evidence |
|---|---:|---|
| facing_left | 0x01 | `docs/s2disasm/_inc/Object RAM offsets.asm:<line>` |
| in_air | 0x02 | `...` |
| rolling | 0x04 | `...` |
| on_object | 0x08 | `...` |
| roll_jump | 0x10 | `...` |
| pushing | 0x20 | `...` |
| underwater | 0x40 | `...` |
```

Any S2-specific bits that do not exist in S1 (e.g. secondary status shields in S3K) must be marked explicitly and excluded from the v3 CSV — the CSV only carries the S1-compatible semantic set.

- [ ] **Step 3: Resolve the S2 zone/act name table**

Produce a table matching the `ZONE_NAMES` Lua table with S2 zone IDs. Use the canonical short names already used by `src/test/resources/traces/` (`ehz`, `cpz`, `arz`, ...).

- [ ] **Step 4: No code changes in this task**

This task is a research gate. Do not touch Lua or Java until the research doc has all three tables filled in.

- [ ] **Step 5: Commit**

```bash
git add docs/architecture/research/2026-04-20-s2-trace-addresses.md
git commit -m "docs: resolve sonic 2 trace recorder ram addresses"
```

## Task 2: Build the Sonic 2 Recorder

**Files:**
- Create: `tools/bizhawk/s2_trace_recorder.lua`

- [ ] **Step 1: Copy and re-parameterize `s1_trace_recorder.lua`**

Start from a verbatim copy of `tools/bizhawk/s1_trace_recorder.lua`. The S1 recorder has S1-specific logic embedded in the body as well as in the constants block; a mechanical find-and-replace of only the constants is insufficient. Update **all** of the following sites:

**a. Header comment block** (lines 1–24): change "Sonic 1" → "Sonic 2" and the version banner to `v3.0-s2`.

**b. `Constants` section** (lines 43–106): replace the S1 RAM addresses, player offsets, and object-table constants with S2 equivalents (see Step 1 template below and the Task 1 research doc).

**c. S1 player-routine comment table** (lines 75–86): replace the `0 = Sonic_Main`/`2 = Sonic_Control`/`4 = Sonic_Hurt`/`6 = Sonic_Death`/`8 = Sonic_ResetLevel` block with the S2 routine map from Task 1's research doc. S2 uses different numeric values (hurt and death are shifted — exact values are a Task 1 deliverable).

**d. Hurt/death routine-change handling**: the S1 recorder hard-codes S1's routine values inside the routine-change event handler. Specifically, line 437 has the comment `-- S1: hurt=0x04, death=0x06. S2: hurt=0x08, death=0x0A.` and lines 403–432 branch on those literals. Replace the S1 constants with the S2 values from the Task 1 research doc. Drop the now-inaccurate "S2: hurt=0x08…" comment or rewrite it with the S3K values so future S3K work has a hint.

**e. Hardcoded raw-input read**: line 546 reads `mainmemory.read_u8(0xF604) -- v_jpadhold1`. This bypasses `ADDR_CTRL1` and must be changed to `mainmemory.read_u8(ADDR_CTRL1)`. (This is also a latent bug in the S1 recorder — the constant exists but isn't used at this one site. Since the S1 recorder stays canonical, do not fix it in-tree during this plan; just route around it in the S2 copy.)

**f. `ZONE_NAMES` table**: swap for the S2 version from Task 1 Step 3.

**g. Banner message** (line 652): `"S1 Trace Recorder v3.0 loaded. Waiting for level gameplay (Game_Mode=0x0C, controls unlocked)..."` → `"S2 Trace Recorder v3.0 loaded. Waiting for level gameplay (Game_Mode=<S2 value>, controls unlocked)..."`.

**h. `write_metadata` game and lua_script_version fields** — see Step 4.

Do **not** reorganize the main loop, the aux-event format, the file I/O path, the `HEADLESS` / `client.speedmode` / `client.invisibleemulation` sequence, or the column ordering in `write_physics_csv`. Those are the shared contract.

Set constants to match the Task 1 research doc:

```lua
-- Sonic 2 REV01 68K RAM addresses
local ADDR_GAME_MODE       = <from research>
local ADDR_CTRL1           = <from research>   -- Ctrl_1_held
local ADDR_CTRL1_DUP       = <from research>   -- Ctrl_1_logical
local ADDR_RING_COUNT      = <from research>
local ADDR_CAMERA_X        = <from research>
local ADDR_CAMERA_Y        = <from research>
local ADDR_ZONE            = <from research>
local ADDR_ACT             = <from research>
local PLAYER_BASE          = <from research>   -- MainCharacter
local OBJ_TABLE_START      = <from research>   -- = PLAYER_BASE for S2 (SST begins at MainCharacter)
local OBJ_SLOT_SIZE        = 0x40
local OBJ_TOTAL_SLOTS      = <from research>
local OBJ_DYNAMIC_START    = <from research>   -- slot index where Dynamic_object_RAM begins
local OBJ_DYNAMIC_COUNT    = <from research>   -- number of dynamic slots (from research doc)
local OFF_CTRL_LOCK        = <from research>   -- player SST offset for move_lock / control-lock timer (word)
-- S2 player routine values (replace S1 0x04/0x06)
local ROUTINE_HURT         = <from research>
local ROUTINE_DEATH        = <from research>
-- Schema v3 counters
local ADDR_FRAMECOUNT      = 0xFE04  -- Level_frame_counter (S2)
local ADDR_VBLA_WORD       = 0xFE0E  -- Vint_runcount+2     (S2, low word of longword at 0xFE0C)
-- In-level game-mode sentinel (replaces S1's GAMEMODE_LEVEL = 0x0C)
local GAMEMODE_LEVEL       = <from research>   -- IDs_Level / id_Level
```

Rewrite every body-logic site that used S1's `0x04` / `0x06` / `0x0C` literals to reference `ROUTINE_HURT`, `ROUTINE_DEATH`, and `GAMEMODE_LEVEL` respectively. The "controls unlocked" gate at the current line 460 of the S1 recorder (`game_mode == GAMEMODE_LEVEL and ctrl_lock_timer == 0`) stays, but `OFF_CTRL_LOCK` must be resolved from research — do not assume S1's `0x3E` carries over.

- [ ] **Step 2: Replace status bit masks with S2 values**

Update the `STATUS_*` locals to use the bits resolved in Task 1 Step 2. If the S2 "on object" state lives in `status_secondary` rather than the primary status byte, add a second status read and derive the bit from there, but still emit the same semantic `ground_mode` / `air` / `rolling` flags in the CSV so the shared parser does not need to know it was S2.

- [ ] **Step 3: Replace the zone name table**

Swap `ZONE_NAMES` for the S2 version from Task 1 Step 3.

- [ ] **Step 4: Update metadata emission**

In `write_metadata`, set:

```lua
meta_file:write('  "game": "s2",\n')
...
meta_file:write('  "lua_script_version": "3.0-s2",\n')
meta_file:write('  "trace_schema": 3,\n')
```

Keep `csv_version = 4` to match the S1 recorder — the CSV layout is identical by design.

- [ ] **Step 5: Lag counter placeholder**

Sonic 2 does not expose a per-frame lag counter. Leave:

```lua
local lag_counter = 0
```

Do not synthesize a counter from `Vint_runcount - Level_frame_counter`; the matrix explicitly records S2 as not having a lag counter.

- [ ] **Step 6: Chromeless launch contract**

Keep the existing `HEADLESS` / `client.speedmode(6400)` / `client.invisibleemulation(true)` sequence and the `while true do ... emu.frameadvance() end` main loop untouched. Only the per-game values change.

- [ ] **Step 7: Verify script loads**

Open BizHawk 2.11, load the Sonic 2 REV01 ROM, load the new `s2_trace_recorder.lua` in Lua Console. Expected console output: `"S2 Trace Recorder v3.0 loaded. Waiting for level gameplay..."` (or equivalent). The script should not error on load.

No commit yet — keep this paired with Task 3 validation.

## Task 3: Validate the Sonic 2 Recorder End-to-End

**Files:**
- Create: `tools/bizhawk/record_s2_trace.bat`

- [ ] **Step 1: Create the S2 launcher batch**

Copy `tools/bizhawk/record_trace.bat` to `tools/bizhawk/record_s2_trace.bat`. The existing launcher has S1-specific user-facing text scattered through it — not just the header. Update **every** of the following sites:

- **Line 2** (header REM): `"Record a BizHawk trace for any Sonic 1 zone/act."` → `"Record a BizHawk trace for any Sonic 2 zone/act."`
- **Line 5** (usage example command name): `record_trace.bat` → `record_s2_trace.bat`
- **Line 6** (usage example ROM path): `"Sonic The Hedgehog (W) (REV01) [!].gen"` → `"Sonic The Hedgehog 2 (W) (REV01) [!].gen"` and BK2 example → an S2 movie (e.g. `"Movies\s2-ehz1.bk2"`)
- **Line 14** (HEADLESS_VISIBLE pointer): `s1_trace_recorder.lua` → `s2_trace_recorder.lua`
- **Line 21** (LUA_SCRIPT): `s1_trace_recorder.lua` → `s2_trace_recorder.lua`
- **Line 28** (rom_path help text): `"Path to Sonic 1 REV01 ROM"` → `"Path to Sonic 2 REV01 ROM"`
- **Line 43** (banner): `"=== BizHawk Trace Recorder ==="` → `"=== BizHawk Sonic 2 Trace Recorder ==="` (so multi-game CI logs identify which recorder ran)

The `BIZHAWK_EXE` default path, `--chromeless --lua --movie` invocation, and positional argument layout stay identical.

- [ ] **Step 2: Run a short recording**

Record a short EHZ1 or similar run using any available S2 BK2. The script should:

- produce `tools/bizhawk/trace_output/physics.csv` with the same header as S1
- produce `tools/bizhawk/trace_output/metadata.json` with `"game": "s2"` and `"trace_schema": 3`
- produce `tools/bizhawk/trace_output/aux_state.jsonl` with routine/mode-change events

If any of these fail, stop and fix the recorder before continuing.

- [ ] **Step 3: Ad-hoc round-trip through `TraceData`**

Point the existing S2-capable side of the parser at the captured output (either by copying it into a temporary `src/test/resources/traces/s2/<zone>/` directory or by running a small ad-hoc parser test). Verify interactively before writing the committed fixture:

- `TraceMetadata.traceSchema()` returns `3`
- `TraceFrame.gameplayFrameCounter()` is non-zero and strictly non-decreasing
- `TraceFrame.vblankCounter()` is non-zero and strictly non-decreasing
- `TraceFrame.lagCounter()` is `0` for every row
- `TraceExecutionModel.forGame("s2").phaseFor(previous, current)` returns `FULL_LEVEL_FRAME` for most frames, with occasional `VBLANK_ONLY` during genuine lag

If any assertion fails, fix the recorder before continuing. This step is diagnostic only — nothing is committed until Step 4.

- [ ] **Step 4: Author the committed synthetic fixture + JUnit 5 smoke test**

Create a minimal hand-authored synthetic fixture at `src/test/resources/traces/synthetic/s2_execution_v3_2frames/` following the structure of `src/test/resources/traces/synthetic/execution_v3_2frames/`:

- `metadata.json` — `"trace_schema": 3`, `"game": "s2"`, `"lua_script_version": "3.0-s2"`, `"csv_version": 4`
- `physics.csv` — 22-column v3 header, 2 data rows, both with `lag_counter = 0`, `gameplay_frame_counter` and `vblank_counter` both advancing by 1
- `aux_state.jsonl` — a single `routine_change` event to exercise the aux path

Create `src/test/java/com/openggf/tests/trace/TestS2SyntheticV3Fixture.java`:

```java
@Test
void parsesS2MetadataAndMonotonicCounters() {
    TraceData data = TraceData.load(Path.of("src/test/resources/traces/synthetic/s2_execution_v3_2frames"));
    assertEquals(3, data.metadata().traceSchema());
    assertEquals("s2", data.metadata().game());
    TraceFrame f0 = data.getFrame(0);
    TraceFrame f1 = data.getFrame(1);
    assertTrue(f1.gameplayFrameCounter() > f0.gameplayFrameCounter());
    assertTrue(f1.vblankCounter() > f0.vblankCounter());
    assertEquals(0, f0.lagCounter());
    assertEquals(0, f1.lagCounter());
}
```

Match the exact method signatures used by the existing `TestTraceDataParsing` class — do not invent new API surface. Run `mvn -Dmse=relaxed "-Dtest=TestS2SyntheticV3Fixture" test` and confirm green.

- [ ] **Step 5: Commit the S2 recorder, launcher, fixture, and test**

```bash
git add tools/bizhawk/s2_trace_recorder.lua tools/bizhawk/record_s2_trace.bat \
        src/test/resources/traces/synthetic/s2_execution_v3_2frames \
        src/test/java/com/openggf/tests/trace/TestS2SyntheticV3Fixture.java
git commit -m "feat: add sonic 2 v3 trace recorder"
```

## Task 4: Resolve Remaining Sonic 3&K Addresses

**Files:**
- Create: `docs/architecture/research/2026-04-20-s3k-trace-addresses.md`

- [ ] **Step 1: Write the per-game address matrix**

Same layout as Task 1 Step 1, but for S3K:

```md
| Purpose | Label | Address | Evidence |
|---|---|---:|---|
| game mode | `Game_Mode` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:<line>` |
| in-level game-mode value | `GM_Level` or `IDs_Level` | `<fill>` | `docs/skdisasm/sonic3k.asm:<line>` |
| active character selector | `Player_mode` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:892` (word: 0=Sonic+Tails, 1=Sonic alone, 2=Tails alone, 3=Knuckles alone) |
| player 1 SST base | `Player_1` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:304` (main character in 1P mode) |
| player 2 SST base (partner) | `Player_2` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:305` (Tails in a Sonic+Tails game) |
| SST slot size | `object_size` | `0x4A` | `docs/skdisasm/sonic3k.constants.asm:303` (note: `$4A`, NOT S1/S2's `0x40`) |
| total SST slots | from `Object_RAM` block | `110` | `docs/skdisasm/sonic3k.constants.asm:303` |
| camera X | `Camera_X_pos` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:<line>` |
| camera Y | `Camera_Y_pos` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:<line>` |
| current zone | `Current_zone` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:<line>` |
| current act | `Current_act` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:<line>` |
| ring count | `Ring_count` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:<line>` |
| held input P1 | `Ctrl_1_held` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:<line>` |
| player status primary | offset `status` | `<fill>` | `docs/skdisasm/_inc/Object RAM offsets.asm:<line>` |
| player status secondary | offset `status_secondary` | `<fill>` | `...` |
| player status tertiary (shields) | offset `<shield-tracking byte>` | `<fill>` | `...` |
| player routine | offset `routine` | `<fill>` | `...` |
| control-lock timer offset | offset `move_lock` (or equivalent) | `<fill>` | `...` (zeroed when player regains control; used to gate recording start) |
| stand-on-object | rider-tracking offset | `<fill>` | `...` |
| player routine — hurt | routine value | `<fill>` | `docs/skdisasm/_incObj/<Sonic|Tails|Knuckles>.asm:<line>` (replaces S1 `0x04`) |
| player routine — death | routine value | `<fill>` | `docs/skdisasm/_incObj/<Sonic|Tails|Knuckles>.asm:<line>` (replaces S1 `0x06`) |
| first dynamic SST slot | `Dynamic_object_RAM` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:307` (slot index = `(Dynamic_object_RAM - Object_RAM) / object_size`; confirm denominator is `0x4A`, not `0x40`) |
| last SST slot (exclusive) | `Dynamic_object_RAM_end` | `<fill>` | `docs/skdisasm/sonic3k.constants.asm:308` (slot index; note this is the end of the dynamic block — the broader `Object_RAM` region runs further) |
| insta-shield / double-jump routine values (if any) | per-character | `<fill>` | `docs/skdisasm/_incObj/<Sonic>.asm:<line>` (S3K-only; emit as aux event only — do not leak into v3 CSV) |
```

The counter addresses (`Level_frame_counter = 0xFE08`, `V_int_run_count+2 = 0xFE12`, `Lag_frame_count = 0xF628`) are pre-resolved — do not re-derive them.

Note the two structural divergences from S1/S2 embedded in this table:
- **S3K `object_size` is `$4A`, not `$40`.** S3K extends the SST slot by 10 bytes relative to S2's `$40`. Recorder slot-dump loops must stride by `$4A` or they will read garbage after the first slot. Confirmed from `docs/skdisasm/sonic3k.constants.asm:303` (`Object_RAM` comment states "$4A bytes per object, 110 objects").
- **S3K has two always-present player SST slots.** `Player_1` holds the active character; `Player_2` holds Tails in a Sonic+Tails game and is otherwise inert. The recorder only needs to capture `Player_1` physics for the v3 CSV, but Task 5 Step 4 must explicitly read `Player_mode` and route accordingly (see next step).

- [ ] **Step 1a: Document the `Player_mode` routing policy**

Add a subsection named `Player_mode routing` that specifies how the recorder picks which SST slot to treat as "the player":

```md
| Player_mode | Value | Trace source | Notes |
|---|---:|---|---|
| Sonic + Tails | 0 | `Player_1` (Sonic) | Tails runs in `Player_2`; ignored for v3 CSV |
| Sonic alone | 1 | `Player_1` (Sonic) | `Player_2` slot is inert |
| Tails alone | 2 | `Player_1` (Tails) | `Player_2` slot is inert |
| Knuckles alone | 3 | `Player_1` (Knuckles) | `Player_2` slot is inert |
```

Rule: **Always read from `Player_1` for the v3 physics row.** Do not dereference `Player_2` for the CSV. If a future extension wants per-character metadata, emit `Player_mode` as an aux event (e.g. `player_mode_set { mode: 0 }`) at recording start and on change, but never branch physics-column capture on `Player_mode`. This keeps the CSV schema byte-for-byte identical across games and characters.

- [ ] **Step 2: Resolve the S3K status bit mapping**

Produce the same semantic → bit table as Task 1 Step 2. Mark S3K-only bits (elemental shields, Super form, insta-shield in progress) as "not in v3 CSV" so the recorder does not leak them into the shared schema. They can still be captured as diagnostic aux events if useful for debugging, but they do not get their own physics.csv columns.

- [ ] **Step 3: Resolve the S3K zone/act name table**

Use the short names already used by `game.sonic3k` code (`aiz`, `hcz`, `mgz`, `cnz`, `fbz`, `icz`, `lbz`, `mhz`, `soz`, `lrz`, `ssz`, `ddz`). S3K has the SKL/S3KL zone-set split; the recorder only needs the level-short-name mapping — do not bake the zone-set split into the recorder.

- [ ] **Step 4: Document the lag-counter reset semantics**

Add a short subsection explaining that `Lag_frame_count` at `0xF628` is zeroed by `Do_Updates` during normal frames and incremented by `VInt_0_Main` during lag VBlanks. The recorder should treat it as a diagnostic counter that will read `0` on non-lag frames and a small positive integer on lag frames. Replay must still derive phase from `gameplay_frame_counter` / `vblank_counter` deltas, not from `Lag_frame_count` (the execution model is already locked in this way).

- [ ] **Step 5: No code changes in this task**

Research gate, mirror of Task 1 Step 4.

- [ ] **Step 6: Commit**

```bash
git add docs/architecture/research/2026-04-20-s3k-trace-addresses.md
git commit -m "docs: resolve sonic 3k trace recorder ram addresses"
```

## Task 5: Build the Sonic 3&K Recorder

**Files:**
- Create: `tools/bizhawk/s3k_trace_recorder.lua`

- [ ] **Step 1: Copy and re-parameterize**

Start from a verbatim copy of `tools/bizhawk/s1_trace_recorder.lua` (not the S2 recorder — start from the canonical S1 reference to avoid compounding any S2-specific drift). The same body-logic sites enumerated in Task 2 Step 1 (a–h) must be edited for S3K. In addition to those:

- **Object-slot stride**: `OBJ_SLOT_SIZE` must be set to `0x4A` (not S1/S2's `0x40`). Confirm every use of `OBJ_SLOT_SIZE` in the S1 source uses the constant rather than a literal `0x40` before assuming the stride is parameterized. If any literal `0x40` is found inside an SST address computation (e.g. `OBJ_TABLE_START + slot * 0x40`), rewrite it to multiply by `OBJ_SLOT_SIZE`.
- **Player selection**: wrap the `PLAYER_BASE` physics read in a helper that reads from `Player_1`'s absolute address. Follow the routing policy from Task 4 Step 1a — always read `Player_1`, never branch on `Player_mode`. If aux events want to record the active-character identity, emit `player_mode_set { mode: <value> }` once at the first frame where `Player_mode` is read and on every change.
- **Hurt/death routine values**: use the S3K routine map from Task 4's research doc. S3K may add insta-shield-related routine states that do not exist in S1 or S2. Mark any such state as "S3K-only aux event" per the Task 4 Step 2 instruction; do not emit it into the v3 CSV.
- **Banner message**: `"S3K Trace Recorder v3.0 loaded. Waiting for level gameplay (Game_Mode=<S3K value>, controls unlocked)..."`.

Do **not** reorganize the main loop, the aux-event format, the file I/O path, the `HEADLESS` / `client.speedmode` / `client.invisibleemulation` sequence, or the column ordering in `write_physics_csv`.

- [ ] **Step 2: Use the pre-resolved S3K counter addresses**

```lua
-- Sonic 3&K counters (matrix-resolved; do not change)
local ADDR_FRAMECOUNT       = 0xFE08   -- Level_frame_counter (S3K)
local ADDR_VBLA_WORD        = 0xFE12   -- V_int_run_count+2    (S3K)
local ADDR_LAG_FRAME_COUNT  = 0xF628   -- Lag_frame_count      (S3K)
local ADDR_PLAYER_MODE      = <from research>  -- Player_mode (word: 0=S+T, 1=S, 2=T, 3=K)
-- Per-character routine values (see Task 4 Step 1 research)
local ROUTINE_HURT          = <from research>
local ROUTINE_DEATH         = <from research>
-- Control-lock and SST extents (see Task 4 Step 1 research)
local OFF_CTRL_LOCK         = <from research>   -- player SST offset for move_lock timer
local OBJ_SLOT_SIZE         = 0x4A              -- S3K: $4A, NOT $40
local OBJ_TOTAL_SLOTS       = 110
local OBJ_DYNAMIC_START     = <from research>   -- slot index for Dynamic_object_RAM
local OBJ_DYNAMIC_COUNT     = <from research>   -- slot count (Dynamic_object_RAM_end - Dynamic_object_RAM) / 0x4A
-- In-level game-mode sentinel
local GAMEMODE_LEVEL        = <from research>   -- GM_Level
```

- [ ] **Step 3: Populate `lag_counter`**

```lua
local lag_counter = mainmemory.read_u16_be(ADDR_LAG_FRAME_COUNT)
```

Do not gate `lag_counter` behind `VBLANK_ONLY` — always emit the current value. Replay treats it as diagnostic.

- [ ] **Step 4: Adjust status bits and player base**

Swap in the S3K values from Task 4 Steps 1–2. If the S3K "on object" bit lives in `status_secondary`, read that byte too; emit the same semantic flags into the CSV.

- [ ] **Step 5: Update metadata emission**

```lua
meta_file:write('  "game": "s3k",\n')
...
meta_file:write('  "lua_script_version": "3.0-s3k",\n')
meta_file:write('  "trace_schema": 3,\n')
```

- [ ] **Step 6: Verify script loads**

Open BizHawk with a combined Sonic 3 & Knuckles locked-on ROM image. Load the recorder. It should print its banner and wait for `GM_Level` with controls unlocked.

No commit yet — paired with Task 6 validation.

## Task 6: Validate the Sonic 3&K Recorder End-to-End

**Files:**
- Create: `tools/bizhawk/record_s3k_trace.bat`

- [ ] **Step 1: Create the S3K launcher batch**

Copy `tools/bizhawk/record_trace.bat` to `tools/bizhawk/record_s3k_trace.bat`. Apply the same full set of edits enumerated in Task 3 Step 1 (header REM, usage example command name, usage example ROM path, HEADLESS_VISIBLE pointer, `LUA_SCRIPT`, rom_path help text, banner) with these S3K values:

- ROM example: `"Sonic and Knuckles & Sonic 3 (W) [!].gen"` (use the locked-on combined image, per CLAUDE.md)
- BK2 example: an S3K movie such as `"Movies\s3k-aiz1.bk2"`
- `LUA_SCRIPT` → `%~dp0s3k_trace_recorder.lua`
- Banner: `"=== BizHawk Sonic 3&K Trace Recorder ==="`
- rom_path help text: `"Path to Sonic 3 & Knuckles locked-on ROM"`

- [ ] **Step 2: Run a short recording**

Record a short AIZ1 or HCZ1 run. Verify the same file triad as Task 3 Step 2.

- [ ] **Step 3: Ad-hoc round-trip through `TraceData`**

Verify interactively before writing the committed fixture:

- `TraceMetadata.traceSchema() == 3`
- `TraceMetadata.game().equals("s3k")`
- `TraceFrame.gameplayFrameCounter()` is non-zero and strictly non-decreasing
- `TraceFrame.vblankCounter()` is non-zero and strictly non-decreasing
- `TraceFrame.lagCounter()` is `0` on every non-lag frame and positive only when the gameplay counter did not advance
- `TraceExecutionModel.forGame("s3k").phaseFor(previous, current)` returns the expected phase using the `gameplay_frame_counter` delta rule, not `lag_counter`

If any assertion fails, fix the recorder before continuing. This step is diagnostic only — nothing is committed until Step 4.

- [ ] **Step 4: Author the committed synthetic fixture + JUnit 5 smoke test**

Create `src/test/resources/traces/synthetic/s3k_execution_v3_2frames/` following the same shape as the S2 fixture, with two key differences:

- `metadata.json` — `"game": "s3k"`, `"lua_script_version": "3.0-s3k"`
- `physics.csv` — the second data row must hold `gameplay_frame_counter` steady (lag frame), advance `vblank_counter` by 1, and set `lag_counter = 1`. This is the only per-game behavioral difference that needs explicit regression coverage: the S3K lag-counter path.

Create `src/test/java/com/openggf/tests/trace/TestS3kSyntheticV3Fixture.java`:

```java
@Test
void parsesS3kMetadataAndExercisesLagCounter() {
    TraceData data = TraceData.load(Path.of("src/test/resources/traces/synthetic/s3k_execution_v3_2frames"));
    assertEquals(3, data.metadata().traceSchema());
    assertEquals("s3k", data.metadata().game());
    TraceFrame f0 = data.getFrame(0);
    TraceFrame f1 = data.getFrame(1);
    assertEquals(f0.gameplayFrameCounter(), f1.gameplayFrameCounter(), "frame 1 is a lag frame");
    assertTrue(f1.vblankCounter() > f0.vblankCounter());
    assertEquals(0, f0.lagCounter());
    assertTrue(f1.lagCounter() > 0);
    TraceExecutionPhase phase = TraceExecutionModel.forGame("s3k").phaseFor(f0, f1);
    assertEquals(TraceExecutionPhase.VBLANK_ONLY, phase);
}
```

Run `mvn -Dmse=relaxed "-Dtest=TestS3kSyntheticV3Fixture" test` and confirm green.

- [ ] **Step 5: Commit the S3K recorder, launcher, fixture, and test**

```bash
git add tools/bizhawk/s3k_trace_recorder.lua tools/bizhawk/record_s3k_trace.bat \
        src/test/resources/traces/synthetic/s3k_execution_v3_2frames \
        src/test/java/com/openggf/tests/trace/TestS3kSyntheticV3Fixture.java
git commit -m "feat: add sonic 3k v3 trace recorder"
```

## Task 7: Documentation and Known-Discrepancy Closeout

**Files:**
- Modify: `tools/bizhawk/README.md`
- Modify: `docs/guide/contributing/trace-replay.md`
- Modify: `docs/status/known-discrepancies.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update `tools/bizhawk/README.md`**

Add new bullets next to the S1 recorder mention:

```md
- `s2_trace_recorder.lua` captures Sonic 2 ROM-side trace data using schema v3
- `s3k_trace_recorder.lua` captures Sonic 3&K ROM-side trace data using schema v3
- `record_s2_trace.bat` / `record_s3k_trace.bat` launch the corresponding headless recorders
```

Keep the pointer to `docs/guide/contributing/trace-replay.md` as the canonical doc.

- [ ] **Step 2: Update `docs/guide/contributing/trace-replay.md`**

Replace any "S1-only" qualifier in the recorder walkthrough with a per-game table mirroring the counter matrix. Spell out for each game:

- recorder script path
- launcher batch path
- expected `metadata.json["game"]` value
- counter addresses (reproduce the pre-resolved matrix for convenience)
- whether `lag_counter` is meaningful (S3K) or a placeholder zero (S1, S2)

- [ ] **Step 3: Update `KNOWN_DISCREPANCIES.md` entry #10 (do NOT mark fully resolved)**

Entry 10 `Trace Replay Recorder Coverage` has a two-part removal condition: **(1)** S2 and S3K recorders emit schema v3, and **(2)** the remaining checked-in fixtures are regenerated with authoritative execution counters. This plan delivers (1) plus committed synthetic v3 fixtures per game, but does **not** deliver (2) — real BK2-derived fixtures are still optional (Task 8). Closing the entry now would misrepresent the CI-backed state.

Instead, rewrite the entry in place to reflect the partial-completion state:

- Update the `Current Implementation` section: replace "only the Sonic 1 BizHawk recorder emits schema v3. Sonic 2 and Sonic 3K traces remain on the legacy fixture format…" with "Sonic 1, Sonic 2, and Sonic 3&K BizHawk recorders all emit schema v3. Committed synthetic v3 fixtures exercise the per-game metadata acceptance paths. The historical S1 BK2-derived fixtures under `src/test/resources/traces/s1/` are already v3-native; no S2 or S3K BK2-derived fixtures are yet checked in, so the legacy-heuristic fallback in `TraceExecutionModel` remains reachable when older fixtures are loaded."
- Update the `Rationale` section: delete bullet 2 ("S2/S3K recorder work is not finished") as it is no longer accurate. Replace with a bullet noting that BK2-derived S2/S3K fixtures are deferred to Task 8 of this plan, tracked as follow-up work.
- Tighten the `Removal Condition`: "Remove this entry once at least one real BK2-derived S2 fixture and one real BK2-derived S3K fixture are checked in, a replay test parses them as schema v3, and the `deriveLegacyHeuristic()` branch in `TraceExecutionModel` is deleted."

Keep the entry in the table of contents. Do not delete it.

- [ ] **Step 4: Update `CHANGELOG.md`**

In the `Unreleased → Performance, Parity, and Trace Replay` section, amend the existing bullet that currently says "The BizHawk v3 recorder upgrade is currently landed for Sonic 1; Sonic 2 and Sonic 3K recorder migration remains deferred." Replace with:

```md
- Trace replay supports schema v3 execution counters
  (`gameplay_frame_counter`, `vblank_counter`, `lag_counter`). Sonic 1,
  Sonic 2, and Sonic 3&K BizHawk recorders all emit the v3 schema, and
  synthetic v3 fixtures cover the per-game parser contract. Real
  BK2-derived S2 / S3K fixtures are deferred follow-up work; until they
  land, the legacy-heuristic fallback in `TraceExecutionModel` remains
  reachable for any pre-v3 fixture.
```

The wording must match the tightened `KNOWN_DISCREPANCIES.md` entry from Step 3. If the two documents disagree about the state of BK2-derived fixtures or the heuristic fallback, the known-discrepancy entry is authoritative.

- [ ] **Step 5: Commit**

```bash
git add tools/bizhawk/README.md docs/guide/contributing/trace-replay.md docs/status/known-discrepancies.md CHANGELOG.md
git commit -m "docs: document v3 recorder coverage for s1 s2 and s3k"
```

## Task 8: Optional — Land Real BK2-Derived Fixtures for Full Replay Parity

**Files:**
- Modify: `src/test/resources/traces/s2/**` (create if new captures are added)
- Modify: `src/test/resources/traces/s3k/**` (create if new captures are added)

The synthetic metadata/counter fixtures from Tasks 3 Step 4 and 6 Step 4 already land with the recorders and lock in the per-game parser contract. This task is about adding real BK2-derived gameplay traces for physics parity regression — a strictly larger scope.

- [ ] **Step 1: Decide whether to land a real BK2-derived fixture**

Optional: a 300–600-frame real capture per game (e.g. first 300 frames of EHZ1 for S2, first 300 frames of AIZ1 for S3K) plus a matching replay test that parses the fixture and asserts schema v3 + monotonic counters + basic per-frame physics parity. Do not wire it into `AbstractTraceReplayTest` yet — that would couple the fixture to the full S1 replay contract before per-game parity is established.

- [ ] **Step 2: If landing real fixtures, revisit the heuristic-fallback removal**

Once at least one real v3 fixture exists for every game the replay harness is expected to cover, the one-shot "falling back to legacy heuristic" notice in `TraceData` and the heuristic branch inside `TraceExecutionModel` (`deriveLegacyHeuristic()` + the `current.vblankCounter() < 0` guard) can be removed. This is explicitly a follow-up, not a gate on landing the recorders.

- [ ] **Step 3: Commit (only if real fixtures were added)**

```bash
git add src/test/resources/traces/s2 src/test/resources/traces/s3k \
        src/test/java/com/openggf/tests/trace/s2 src/test/java/com/openggf/tests/trace/s3k
git commit -m "test: add real bk2-derived s2 and s3k v3 trace fixtures"
```

## Validation Matrix

After each recorder lands, the following must all pass on the host machine with a local ROM + BK2:

```powershell
# Sanity: existing S1 replay suite still green (no regressions in shared Java code).
mvn -Dmse=relaxed "-Dtest=TestTraceDataParsing,TestTraceExecutionModel" test
mvn -Dmse=relaxed "-Dtest=com.openggf.tests.trace.s1.*TraceReplay*" test

# New per-game synthetic-fixture smoke tests (committed in Tasks 3 Step 4 and 6 Step 4).
mvn -Dmse=relaxed "-Dtest=TestS2SyntheticV3Fixture,TestS3kSyntheticV3Fixture" test

# Recorder sanity: S2 physics.csv parses with schema v3 and passes the ad-hoc counter check from Task 3 Step 3.
# Recorder sanity: S3K physics.csv parses with schema v3 and passes the lag_counter check from Task 6 Step 3.
```

Everything above `Task 7 Step 5` must ship green before the known-discrepancy entry is updated to the post-recorder, pre-real-fixture state described in Task 7 Step 3.

## Self-Review

- Spec coverage:
  - Counter addresses pre-resolved and locked to the matrix (avoids re-litigating Task 1 of the parent plan).
  - S2 and S3K recorders handled as sibling tasks, each with its own address research gate.
  - Explicit rule against synthesizing an S2 lag counter.
  - Docs and known-discrepancies closeout are explicit task steps, not afterthoughts.
- Placeholder scan:
  - No `TODO`, `TBD`, or "handle appropriately" placeholders remain.
  - Every per-game address that isn't in the matrix is routed through a research-doc `<fill>` placeholder that is explicitly a gate.
- Type consistency:
  - The CSV schema stays identical across S1/S2/S3K — the Java parser is not touched.
  - `metadata.json` game codes emitted by the new recorders are `s2` and `s3k`, both already accepted by `TraceExecutionModel.forGame(String)`. (The model also recognizes `s1` and a standalone `s3` alias; neither recorder in this plan emits `s3`, and no current BizHawk workflow targets a Sonic 3 standalone ROM.)
- Risk register explicitly names the four drift hazards (status bits, player base, input latch, game-mode sentinel) and routes each into a concrete research step.

## Execution Handoff

Plan saved to `docs/architecture/plans/2026-04-20-s2-s3k-trace-recorder-v3.md`. Two execution options:

**1. Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration. Especially useful because Tasks 1/4 are research-heavy and benefit from a fresh context for the disassembly reading.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints (one natural checkpoint after S2 lands, another after S3K lands).

Which approach?
