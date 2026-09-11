# Trace Framework Reference

A deep, end-to-end reference for the OpenGGF trace-replay framework. The live
recording and publication path is the native BizHawk headless harness and the
single v5 contract documented in
[`trace-v5-publication.md`](trace-v5-publication.md). The Lua and stable-retro
sections below are retained as historical implementation background and
diagnostic reference; they are not current publication instructions.

For a shorter contributor-facing guide, see [`trace-replay.md`](trace-replay.md). This document
is the long-form companion that documents every layer.

---

## Table of Contents

1. [What the Trace Framework Is For](#1-what-the-trace-framework-is-for)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Part I — Recording: BizHawk Lua](#3-part-i--recording-bizhawk-lua)
   - 3.1 [Script inventory](#31-script-inventory)
   - 3.2 [`s1_trace_recorder.lua`](#32-s1_trace_recorderlua)
   - 3.3 [`s2_trace_recorder.lua`](#33-s2_trace_recorderlua)
   - 3.4 [`s3k_trace_recorder.lua`](#34-s3k_trace_recorderlua)
   - 3.5 [`s1_credits_trace_recorder.lua` (removed)](#35-s1_credits_trace_recorderlua-removed)
   - 3.6 [Diagnostic/debug Lua scripts](#36-diagnosticdebug-lua-scripts)
   - 3.7 [Batch launchers](#37-batch-launchers)
4. [Part II — Recording: stable-retro Python](#4-part-ii--recording-stable-retro-python)
   - 4.1 [`trace_core.py` API](#41-trace_corepy-api)
   - 4.2 [`GenesisRAM` byte-swap handling](#42-genesisram-byte-swap-handling)
   - 4.3 [`s1_trace_recorder.py`](#43-s1_trace_recorderpy)
   - 4.4 [`s1_credits_trace_recorder.py`](#44-s1_credits_trace_recorderpy)
   - 4.5 [Platform setup (WSL / macOS / Linux)](#45-platform-setup-wsl--macos--linux)
5. [Part III — Trace File Formats](#5-part-iii--trace-file-formats)
   - 5.1 [`metadata.json`](#51-metadatajson)
   - 5.2 [`physics.csv` — all schema versions](#52-physicscsv--all-schema-versions)
   - 5.3 [`aux_state.jsonl` — event catalogue](#53-aux_statejsonl--event-catalogue)
   - 5.4 [`.bk2` BizHawk movie](#54-bk2-bizhawk-movie)
6. [Part IV — Java Replay Harness](#6-part-iv--java-replay-harness)
   - 6.1 [`AbstractTraceReplayTest`](#61-abstracttracereplaytest)
   - 6.2 [`AbstractCreditsDemoTraceReplayTest`](#62-abstractcreditsdemotracereplaytest)
   - 6.3 [Concrete test classes](#63-concrete-test-classes)
   - 6.4 [Trace data models](#64-trace-data-models)
   - 6.5 [Comparison — `TraceBinder` / `ToleranceConfig`](#65-comparison--tracebinder--toleranceconfig)
   - 6.6 [`DivergenceReport` and the JSON/context outputs](#66-divergencereport-and-the-jsoncontext-outputs)
   - 6.7 [Execution phase model (lag-frame detection)](#67-execution-phase-model-lag-frame-detection)
   - 6.8 [S3K elastic windows & required checkpoints](#68-s3k-elastic-windows--required-checkpoints)
   - 6.9 [`EngineDiagnostics` and context windows](#69-enginediagnostics-and-context-windows)
7. [Part V — Runtime Collision Tracing](#7-part-v--runtime-collision-tracing)
8. [Part VI — Test Resource Layout](#8-part-vi--test-resource-layout)
9. [Part VII — CI Pipeline](#9-part-vii--ci-pipeline)
10. [Part VIII — Agent-Driven Iterative Improvement Loop](#10-part-viii--agent-driven-iterative-improvement-loop)
    - 10.1 [Why the loop exists](#101-why-the-loop-exists)
    - 10.2 [Inputs available to the agent](#102-inputs-available-to-the-agent)
    - 10.3 [The skills that drive iteration](#103-the-skills-that-drive-iteration)
    - 10.4 [The `/loop` and subagent-driven patterns](#104-the-loop-and-subagent-driven-patterns)
    - 10.5 [Plan/research/spec artefacts](#105-planresearchspec-artefacts)
    - 10.6 [Case study — MZ1: 229 → 147 → 57 errors](#106-case-study--mz1-229--147--57-errors)
    - 10.7 [Case study — S3K AIZ intro parity gate](#107-case-study--s3k-aiz-intro-parity-gate)
    - 10.8 [Branch policy feedback into the loop](#108-branch-policy-feedback-into-the-loop)
11. [Part IX — Appendices](#11-part-ix--appendices)
    - 11.1 [RAM address reference per game](#111-ram-address-reference-per-game)
    - 11.2 [Schema version history](#112-schema-version-history)
    - 11.3 [S1/S2/S3K zone name tables](#113-s1s2s3k-zone-name-tables)
    - 11.4 [Related source files](#114-related-source-files)

---

## 1. What the Trace Framework Is For

OpenGGF reimplements the physics, collision, object timing, and scroll behaviour of Sonic 1, 2
and 3&K at pixel-level parity. The only way to prove that parity empirically is to feed the
engine the same inputs that were fed to the original ROM, running in BizHawk, and compare
their state frame by frame.

The trace framework is the plumbing that does that:

- **Record** — run the real ROM through the native BizHawk headless harness
  with a deterministic input stream, and snapshot player/camera/object state every frame.
- **Replay** — drive the engine with the same input stream and step it frame by frame,
  honouring the same ROM-internal pacing (gameplay frames vs VBlank-only lag frames).
- **Compare** — match the engine's per-frame state against the recorded ROM state, with
  configurable tolerances, and emit a structured divergence report.
- **Iterate** — feed the divergence report back to humans or to agents (via the `/loop`
  skill and/or subagent-driven execution) to diagnose and fix the first non-cascading error,
  and re-run.

Trace replay tests are described by [`trace-replay.md`](trace-replay.md) as "the highest-signal
tests in the repo for physics, object timing, spawn timing, and collision parity work."

---

## 2. High-Level Architecture

```
                ┌──────────────────────────────────────────┐
                │       ROM (Sonic 1 / 2 / 3&K)            │
                │   native BizHawk headless harness        │
                └──────────────────────────────────────────┘
                                   │
                                   │  per-frame RAM reads
                                   ▼
                ┌──────────────────────────────────────────┐
                │  Recorder (Lua or Python)                │
                │   - tools/tracechaser/bizhawk/*.lua                  │
                │   - tools/tracechaser/retro/*.py                     │
                └──────────────────────────────────────────┘
                                   │ writes 3 files
                                   ▼
                ┌──────────────────────────────────────────┐
                │  Trace fixture                           │
                │   metadata.json  physics.csv  aux_state  │
                │   .bk2           (BizHawk movie)         │
                └──────────────────────────────────────────┘
                                   │ consumed by
                                   ▼
                ┌──────────────────────────────────────────┐
                │  AbstractTraceReplayTest (JUnit 5)       │
                │   - HeadlessTestFixture                  │
                │   - TraceBinder                          │
                │   - TraceExecutionModel                  │
                │   - S3kElasticWindowController           │
                └──────────────────────────────────────────┘
                                   │ emits
                                   ▼
                ┌──────────────────────────────────────────┐
                │  worktree: target/trace-reports         │
                │   <game>_<zone><act>_report.json         │
                │   <game>_<zone><act>_context.txt         │
                └──────────────────────────────────────────┘
                                   │ read by
                                   ▼
                ┌──────────────────────────────────────────┐
                │  Agent iteration loop                    │
                │   - `/loop` skill                        │
                │   - subagent-driven-development skill    │
                │   - trace-replay / bizhawk-headless      │
                │   - plan/research/spec docs              │
                └──────────────────────────────────────────┘
```

Every piece of this pipeline is in-tree. The ROM is not; it is gitignored and users must supply
their own legally-obtained copy.

---

## 3. Part I — Recording: BizHawk Lua

BizHawk (`tools/tracechaser/.dependencies/BizHawk-2.11-win-x64/EmuHawk.exe`, not checked in) runs the ROM with a
deterministic BK2 movie. A companion Lua script is loaded with `--lua` which, via
`event.onframeend`, snapshots RAM into `physics.csv` and emits diagnostic events into
`aux_state.jsonl`. The script is the authoritative source of truth for the fixture format.

### 3.1 Script inventory

`tools/tracechaser/bizhawk/` contains **15 Lua files** and **4 batch launchers**:

| Category | File | Purpose |
|---|---|---|
| Main recorder | `s1_trace_recorder.lua` | S1 REV01 per-frame physics |
| Main recorder | `s2_trace_recorder.lua` | S2 REV01 per-frame physics, Sonic + Tails |
| Main recorder | `s3k_trace_recorder.lua` | S3&K per-frame physics, zone_act_state + checkpoints |
| Credits recorder | `s1_credits_trace_recorder.lua` (removed; see `tools/tracechaser/retro/s1_credits_trace_recorder.py`) | Recorded the 8 ROM-owned credits demos |
| Launcher | `record_trace.bat` | Wraps BizHawk for S1 recording |
| Launcher | `record_s2_trace.bat` | Wraps BizHawk for S2 recording with PowerShell stdout bridge |
| Launcher | `record_s3k_trace.bat` | Wraps BizHawk for S3K recording, exposes `aiz_end_to_end` profile |
| Launcher | `record_s1_credits_traces.bat` | Wraps BizHawk for credits demos |
| Debug | `debug_s2_endact_inputs.lua` | Dump S2 end-of-act input handling |
| Debug | `debug_s2_tails_despawn.lua` | Log Tails despawn/render flags near frame 5490–5520 |
| Debug | `s3k_domain_probe.lua` | Probe multiple BizHawk memory domains to verify the right one |
| Debug | `s3k_handoff_diag.lua` | Dump AIZ→HCZ handoff state and raw hex windows |
| Debug | `s3k_player_base_compare.lua` | Compare candidate player object bases (0xB000 vs 0xB400) |
| Debug | `s3k_player_search.lua` | Heuristic scan over all RAM to locate the player struct |
| Debug | `s3k_slot_scan.lua` | Dump all OST slots at specific target frames |
| Debug | `hook_solid_classify.lua` | M68K execute hook at 0x101D0 to capture distance regs |
| Debug | `hook_speedtopos.lua` | M68K execute hook at `ObjectMoveAndFall`/`SpeedToPos` |
| Debug | `trace_y_instructions.lua` | Frame-boundary Y snapshot and delta |
| Debug | `trace_y_poll.lua` | `event.onmemorywrite` on each byte of Sonic Y |
| Debug | `watch_y_write.lua` | Memory write hook with M68K PC capture |

### 3.2 `s1_trace_recorder.lua`

**File:** `tools/tracechaser/bizhawk/s1_trace_recorder.lua` (~680 lines)

#### Frame detection

Recording starts when the ROM transitions into `game_mode == 0x0C` (GAMEMODE_LEVEL) **and**
`ctrl_lock_timer == 0` (player input unlocked). On the frame recording is armed, the script
returns without writing; the next `on_frame_end()` writes trace frame `0x0000`. Recording
stops when `game_mode` transitions away from `0x0C`.

#### CSV v4 schema (22 columns)

```
frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode,
x_sub,y_sub,routine,camera_x,camera_y,rings,status_byte,
gameplay_frame_counter,stand_on_obj,vblank_counter,lag_counter
```

- All fields hexadecimal except `air`, `rolling`, `ground_mode` (decimal 0/1/0–3).
- `x_speed`, `y_speed`, `g_speed` are signed 16-bit stored as unsigned hex (`uhex`).
- `status_byte` bit layout: `0x01`=facing left, `0x02`=in air, `0x04`=rolling,
  `0x08`=on object, `0x10`=roll-jumping, `0x20`=pushing, `0x40`=underwater.
- `ground_mode`: 0=floor, 1=right-wall, 2=ceiling, 3=left-wall (quadrant of `angle`).
- `lag_counter` is always `0` for S1 (ROM has no exposed lag counter).

#### aux_state.jsonl event types

| Event | Fields (key ones) | Emitted |
|---|---|---|
| `object_appeared` | `slot`, `object_type`, `x`, `y` | When an SST slot becomes non-empty |
| `object_removed` | `slot`, `object_type` | When an SST slot is cleared |
| `object_near` | `slot`, `type`, `x`, `y`, `routine`, `status` | Every frame, per-object within 160px of Sonic |
| `slot_dump` | `slots`: array of `[slot, type_hex]` | When **any** object appeared that frame; full occupancy of slots 32–127 |
| `state_snapshot` | `control_locked`, `anim_id`, `status_byte`, `routine`, `y_radius`, `x_radius`, `on_object`, `pushing`, `underwater`, `roll_jumping` | Every 60 frames, or on air / rolling / on_object / control_locked transitions |
| `mode_change` | `field` (air/rolling/on_object/control_locked), `from`, `to` | Whenever one of those bits flips |
| `routine_change` | `from`, `to`, `sonic_x`, `sonic_y`, `x_vel`, `y_vel`, `inertia`, `status`, `stand_on_obj`, and optional `stand_obj_*` | Whenever `obRoutine` changes. S1 routines: 0=init, 2=control, 4=hurt, 6=death, 8=reset |
| `cursor_state` | `opl_screen`, `fwd_ptr`, `bwd_ptr`, `fwd_ctr`, `bwd_ctr`, `dir` | When ObjPosLoad cursor advances |

All events carry `frame` (the trace frame number) and `vfc` (`gameplay_frame_counter`).

#### Key RAM addresses (all relative to `$FF0000`)

| Name | Addr | Size | Notes |
|---|---|---|---|
| `ADDR_GAME_MODE` | `0xF600` | byte | `0x0C` = GAMEMODE_LEVEL |
| `ADDR_ZONE` / `ADDR_ACT` | `0xFE10` / `0xFE11` | byte | |
| `ADDR_RING_COUNT` | `0xFE20` | word BE, BCD | |
| `ADDR_FRAMECOUNT` | **`0xFE04`** | word BE | `v_framecount`; advances only on Level_MainLoop completion. **Not** `0xFE02` (common trap — see MZ1 case study) |
| `ADDR_VBLA_WORD` | `0xFE0E` | word BE | Advances every VBlank |
| `ADDR_CAMERA_X` / `ADDR_CAMERA_Y` | `0xF700` / `0xF704` | long, read hi word | |
| `PLAYER_BASE` | `0xD000` | | Sonic SST slot 0 |
| `OFF_X_POS` / `OFF_Y_POS` / `OFF_X_SUB` / `OFF_Y_SUB` | `0x08` / `0x0C` / `0x0A` / `0x0E` | word BE | 16:8 fixed-point (pixel + subpixel) |
| `OFF_X_VEL` / `OFF_Y_VEL` / `OFF_INERTIA` | `0x10` / `0x12` / `0x14` | signed word BE | |
| `OFF_ROUTINE` / `OFF_STATUS` / `OFF_ANGLE` | `0x24` / `0x22` / `0x26` | byte | |
| `OFF_STAND_ON_OBJ` | `0x3D` | byte | SST slot index (not RAM ptr) |
| `OFF_CTRL_LOCK` | `0x3E` | word BE | Control lock timer |
| `ADDR_OPL_SCREEN` | `0xF76E` | word BE | ObjPosLoad last processed chunk |
| `ADDR_OPL_DATA_FWD/BWD` | `0xF770` / `0xF774` | long BE | OPL cursor pointers |

#### metadata.json

```json
{
  "game": "s1",
  "zone": "ghz", "zone_id": 0, "act": 1,
  "bk2_frame_offset": 840,
  "trace_frame_count": 3905,
  "start_x": "0x0050", "start_y": "0x03B0",
  "recording_date": "2026-04-19",
  "lua_script_version": "3.0",
  "trace_schema": 3,
  "csv_version": 4,
  "rom_checksum": "",
  "notes": ""
}
```

### 3.3 `s2_trace_recorder.lua`

**File:** `tools/tracechaser/bizhawk/s2_trace_recorder.lua` (~947 lines) — the most complex recorder.

#### What changes vs S1

- **Two characters:** Sonic at `$FFB000` and Tails at `$FFB040` (slot 1 of the SST). Every
  physics field appears twice, once under a `sonic_` prefix and once under `tails_`.
- **`OBJ_DYNAMIC_START = 16`** (S2) vs S1's 32.
- **Control lock offset:** S2's `OFF_CTRL_LOCK = 0x2E` (word), *different from S1's `0x3E`*.
- **Pre-trace snapshots** (frame `-1`): the recorder emits a block of `object_state_snapshot`
  events with the raw 64-byte dump of every non-empty SST slot (plus semantic word aliases)
  before trace frame 0. The Java harness uses these to hydrate the engine's object manager so
  that routine/timer state matches the ROM at trace start.
- **Tails CPU snapshot** (frame `-1`): `cpu_state_snapshot` carries `control_counter`,
  `respawn_counter`, `cpu_routine`, `target_x/y`, `interact_id`, `jumping`.
- **Player history snapshot** (frame `-1`): 64-entry ring buffer of Sonic position/input/
  status captured from RAM at `$FFE400/E500` for pre-trace position history hydration.

#### CSV v6 schema (38 columns)

```
frame,input,camera_x,camera_y,rings,gameplay_frame_counter,vblank_counter,lag_counter,
sonic_present,sonic_x,sonic_y,sonic_x_speed,sonic_y_speed,sonic_g_speed,
sonic_angle,sonic_air,sonic_rolling,sonic_ground_mode,
sonic_x_sub,sonic_y_sub,sonic_routine,sonic_status_byte,sonic_stand_on_obj,
tails_present,tails_x,tails_y,tails_x_speed,tails_y_speed,tails_g_speed,
tails_angle,tails_air,tails_rolling,tails_ground_mode,
tails_x_sub,tails_y_sub,tails_routine,tails_status_byte,tails_stand_on_obj
```

#### aux_state.jsonl additions for S2

- `state_snapshot` / `routine_change` / `mode_change` / `object_near` carry an additional
  `character` field (`"sonic"` or `"tails"`).
- `state_snapshot` also carries `raw_input` / `raw_input_mask` / `logical_input` /
  `logical_input_mask` so that input-handling divergences (especially around Tails CPU) are
  debuggable.
- Pre-trace (frame = `-1`) events: `object_state_snapshot`, `player_history_snapshot`,
  `cpu_state_snapshot`.

### 3.4 `s3k_trace_recorder.lua`

**File:** `tools/tracechaser/bizhawk/s3k_trace_recorder.lua` (~745 lines).

#### Critical S3K differences

- **32-bit position format:** Unlike S1/S2's separate `$08/$0A` (x pixel / x subpixel), S3K
  packs position as `$10.L` where high word = pixel and low word = subpixel. Same applies to
  Y at `$14.L`. The recorder reads them as separate words.
- **Velocity offsets shift:** `OFF_X_VEL = 0x18`, `OFF_Y_VEL = 0x1A`, `OFF_INERTIA = 0x1C`.
- **`OFF_ROUTINE = 0x05`** (not `0x24`).
- **`OFF_STAND_ON_OBJ = 0x42` as a word** that holds a RAM pointer to the stood-on object,
  not an SST slot index. The recorder converts it to a slot via the helper
  `interact_addr_to_slot()` (subtracts OST base `0xB400`, divides by slot size `0x4A`).
- **OST geometry differs:** `OBJ_TABLE_START = 0xB000`, `OBJ_SLOT_SIZE = 0x4A` (74 bytes,
  not 64), `OBJ_TOTAL_SLOTS = 110`, `OBJ_DYNAMIC_START = 3`, `OBJ_DYNAMIC_COUNT = 90`.
- **Per-game counters:** `ADDR_FRAMECOUNT = 0xFE08`, `ADDR_VBLA_WORD = 0xFE12`,
  `ADDR_LAG_FRAME_COUNT = 0xF628`. S3K is the only game that records a real `lag_counter`.
- **`ADDR_APPARENT_ACT = 0xEE4F`:** differs from `ADDR_ACT = 0xFE11` during AIZ2 reload
  (act=1 but apparent=0 while the Act 1→2 seamless handoff is in progress).
- **`ADDR_PLAYER_MODE = 0xFF08`:** Sonic/Tails/Knuckles character selection.
- **`ADDR_EVENTS_FG_5 = 0xEEC6`:** used by the AIZ1 fire-transition checkpoint detector.
- **`ADDR_CTRL1_LOCKED = 0xF7CA`:** different "control locked" concept from S1/S2's
  `OFF_CTRL_LOCK`. Both are checked.
- **Object `type`** is written as `0x%08X` — it's a 32-bit code-pointer (the ROM address of
  the object's main routine), not an 8-bit ID.

#### Trace profile switch — `OGGF_S3K_TRACE_PROFILE`

```
TRACE_PROFILE = os.getenv("OGGF_S3K_TRACE_PROFILE") or "gameplay_unlock"
```

**`gameplay_unlock` (default):** Start when `game_mode == 0x0C` **and** `ctrl_lock == 0`
**and** `ctrl_locked == 0`. Returns once-and-skips like S1/S2.

**`aiz_end_to_end`:** Start when the BK2 movie is loaded and either `game_mode == 0x0C` or
player position is non-zero. Records through the entire intro and into gameplay. Used for
the authoritative AIZ1→AIZ2→HCZ fixture at
`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`.

In `aiz_end_to_end` mode the recorder emits:

- `zone_act_state` edge-triggered on any change of
  `(actual_zone_id, actual_act, apparent_act, game_mode)`.
- Named `checkpoint` events when the game crosses well-defined milestones:
  - `intro_begin` — trace frame 0
  - `gameplay_start` — `level_started != 0 && game_mode == 0x0C && move_lock == 0 && ctrl_locked == 0`
  - `aiz1_fire_transition_begin` — zone=0, act=0, `events_fg_5 != 0`
  - `aiz2_reload_resume` — zone=0, act=1, `apparent_act == 0`
  - `aiz2_main_gameplay` — zone=0, act=1, unlocked
  - `hcz_handoff_complete` — zone=1, act=0, unlocked

A PowerShell sanity gate in [`trace-replay.md`](trace-replay.md) (section "Recording The S3K
End-To-End Fixture") verifies the checkpoint set is complete and ordered before the fixture
is committed.

### 3.5 `s1_credits_trace_recorder.lua` (removed)

This Lua recorder and its `record_s1_credits_traces.bat` launcher no longer exist in the
tree; the surviving credits recorder is the stable-retro
`tools/tracechaser/retro/s1_credits_trace_recorder.py` (section 4.4), which writes the same layout
under `trace_output/credits_demos/`. The description below is kept because the trace
schema it produced is still what the credits fixtures carry.

Recorded the eight ROM-owned credits demos. The inputs are not in a BK2 — they are embedded
in the ROM at `DemoEndData` (see `Sonic1CreditsDemoData`) and replayed by the stock
`MoveSonicInDemo` routine. The recorder forces `GM_Credits` from the title screen, waits for
each demo to become active (`demo_flag == 0x8001` and `credits_num` matches), and writes a
separate trace directory per demo under `trace_output/credits_demos/` (gitignored).

Each demo trace carries the S1 v4 schema plus these extra metadata fields:

```json
"trace_type": "credits_demo",
"input_source": "rom_ending_demo",
"credits_demo_index": 0,
"credits_demo_slug": "ghz1_credits_demo_1",
"emu_frame_start": 317
```

A `manifest.json` in `credits_demos/` enumerates all eight sub-directories.

### 3.6 Diagnostic/debug Lua scripts

These are **not** part of the replay pipeline — they are point probes used by humans or
agents while diagnosing specific divergences. They drop text files into `trace_output/`
rather than structured CSVs.

| Script | Purpose |
|---|---|
| `debug_s2_endact_inputs.lua` | Frame-range dump of S2 end-of-act input handling (frames 5036–5108) |
| `debug_s2_tails_despawn.lua` | Tails render/despawn flags over frames 5490–5520 |
| `s3k_domain_probe.lua` | Probe mainmemory / M68K BUS / System Bus domains to find the correct S3K RAM window |
| `s3k_handoff_diag.lua` | Last 360 frames of AIZ→HCZ with raw hex windows at `0xFE00-0xFE30` and `0xEE50-0xEEA0` |
| `s3k_player_base_compare.lua` | Compare candidate bases `0xB000` vs `0xB400` at target frames |
| `s3k_player_search.lua` | Heuristic scan of every 16-bit address looking for a player-shaped struct |
| `s3k_slot_scan.lua` | Dump all occupied OST slots at given target frames |
| `hook_solid_classify.lua` | `event.onmemoryexecute` at `0x101D0` (SolidObject classification) — captures `d1`/`d5` (`|distY|`, `|distX|`) to explain side-vs-top contact decisions |
| `hook_speedtopos.lua` | Execute hooks at `0x0DC66` / `0x0DC6A` / `0x0DC92` — traces Y position updates inside `ObjectMoveAndFall` / `SpeedToPos` |
| `trace_y_instructions.lua` | Detect Y change across one frame |
| `trace_y_poll.lua` | `event.onmemorywrite` on each byte of Sonic Y |
| `watch_y_write.lua` | Memory write hook on Y with M68K PC capture |

Agents use these when a divergence is traced to a specific ROM routine and the question is
"what were the registers when the ROM made that decision?" — something the `physics.csv`
stream alone cannot answer.

### 3.7 Batch launchers

All launchers resolve `BIZHAWK_EXE` from env or default to
`C:\Users\farre\IdeaProjects\sonic-engine\docs\BizHawk-2.11-win-x64\EmuHawk.exe`, then run
BizHawk with `--chromeless --lua <script> --movie <bk2> <rom>`.

**`record_trace.bat`:** Direct shell invocation — simplest form.

**`record_s2_trace.bat`:** Same, but wrapped in a PowerShell bridge
(`System.Diagnostics.ProcessStartInfo` with `RedirectStandardOutput`/`StandardError`) so that
Lua `print` output streams back to the caller, since BizHawk chromeless mode does not print
to stdout by default.

**`record_s3k_trace.bat`:** Extends the PowerShell bridge with a preamble that opens the
BK2 as a ZIP, counts the lines in `Input Log.txt`, and sets
`OGGF_BK2_FRAME_COUNT` and `OGGF_S3K_TRACE_PROFILE` before launching. This lets the Lua
script know how long the movie is and which profile to activate (`gameplay_unlock` vs
`aiz_end_to_end`).


---

## 4. Part II — Recording: stable-retro Python

stable-retro is a Python library that embeds the same Genesis Plus GX core as BizHawk but is
fully headless and runs on macOS / Linux / WSL. It produces byte-identical output to the
BizHawk Lua recorder. S1 has full Python support; S2 and S3K are planned.

`tools/tracechaser/retro/` contents:

| File | Lines | Role |
|---|---|---|
| `trace_core.py` | ~732 | Shared infrastructure |
| `s1_trace_recorder.py` | ~229 | Main S1 recorder (BK2 replay or savestate boot) |
| `s1_credits_trace_recorder.py` | ~584 | ROM-driven credits demo recorder |
| `debug_credits.py` | ~43 | One-off inspector |
| `requirements.txt` | 3 | `stable-retro>=0.9.9`, `numpy>=1.21` |

### 4.1 `trace_core.py` API

**Classes**

- `GenesisRAM(ram_array=None)` — wrapper over the 64 KB work RAM numpy array. Provides
  `u8(addr)`, `s8(addr)`, `u16(addr)`, `s16(addr)`, `u32(addr)` that transparently handle
  byte-swapping (see 4.2).
- `BizhawkBK2(path)` — parses a BizHawk `.bk2` (ZIP containing `Input Log.txt`) and exposes
  per-frame input as a numpy action array compatible with stable-retro's 12-button Genesis
  button order. Handles BizHawk's LogKey format (`#P1 Up|P1 Down|...` with `#` marking
  group boundaries) and maps buttons:
  ```python
  _BK2_TO_RETRO = {
      'up': 4, 'down': 5, 'left': 6, 'right': 7,
      'a': 1, 'b': 0, 'c': 8, 'start': 3,
  }
  ```
- `TraceRecorder(output_dir)` — owns the CSV/JSONL writers and per-frame state machine.
  Mirrors the BizHawk script's recording logic 1:1:
  - `record_frame(mem)` — read RAM, write one physics row, emit any aux events
  - `_check_mode_changes()` — emit `mode_change` events
  - `_emit_routine_change()` — emit `routine_change` events
  - `_write_state_snapshot()` — periodic or transition-driven `state_snapshot`
  - `_scan_objects()` — produce `object_appeared`, `object_removed`, `object_near`,
    `slot_dump`
  - `_check_opl()` — ObjPosLoad cursor tracking

**Functions**

- `rom_joypad_to_mask(raw)` — convert ROM joypad byte to engine input bitmask
- `angle_to_ground_mode(angle)` — same quadrant mapping as the Lua script
- `write_metadata(path, **fields)` — writes `metadata.json` preserving field order
- `write_credits_manifest(path, entries)` — writes the credits `manifest.json`

**Constants** (exported for both recorders)

All the `ADDR_*`, `OFF_*`, `STATUS_*`, `OBJ_*`, `INPUT_*`, `GAMEMODE_*` constants used by the
S1 Lua recorder, plus the LZ lamppost state field names and the `ZONE_NAMES` dict.

### 4.2 `GenesisRAM` byte-swap handling

stable-retro's `genesis_plus_gx` core exposes 68K work RAM **with bytes swapped within each
16-bit word** (x86 little-endian layout of a big-endian system). So a word `$0050` at 68K
address `$FFD008` appears in the numpy array as:

```
ram[0xD008] = 0x50   # low byte
ram[0xD009] = 0x00   # high byte
```

The fix is to XOR the address with 1 for byte reads:

```python
def u8(self, addr):
    return int(self._ram[addr ^ 1])
```

For word reads the bytes are read raw because the swap is symmetric once you read both:

```python
def u16(self, addr):
    return int(self._ram[addr]) | (int(self._ram[addr + 1]) << 8)

def u32(self, addr):
    hi = int(self._ram[addr])     | (int(self._ram[addr + 1]) << 8)
    lo = int(self._ram[addr + 2]) | (int(self._ram[addr + 3]) << 8)
    return (hi << 16) | lo
```

Anyone reading stable-retro RAM directly (bypassing `GenesisRAM`) must remember the XOR 1
rule for byte accesses.

### 4.3 `s1_trace_recorder.py`

Three input-source modes, controlled by CLI flags:

```bash
# Native stable-retro BK2
python tools/tracechaser/retro/s1_trace_recorder.py --movie path.bk2 --output-dir tools/tracechaser/retro/trace_output/

# Auto-parse a BizHawk BK2
python tools/tracechaser/retro/s1_trace_recorder.py --bizhawk-bk2 path.bk2 --output-dir ...

# Boot from a savestate (GreenHillZone.Act1 etc.) — used for interactive or unit-test recordings
python tools/tracechaser/retro/s1_trace_recorder.py --state GreenHillZone.Act1 --output-dir ...
```

The main loop looks like:

```python
if movie:
    if not movie.step(): break
    action = np.array([movie.get_key(i, 0) for i in range(NUM_BUTTONS)], dtype=np.int8)
elif bk2_input:
    action = bk2_input[emu_frame] if not started else bk2_input[bizhawk_gameplay_start + recorder.trace_frame]
else:
    action = np.zeros(NUM_BUTTONS, dtype=np.int8)

env.step(action)
mem.update(env.get_ram())
recorder.record_frame(mem)
```

`render_mode=None` is set unconditionally on every `stable_retro.make()` call so the
recorder never opens a window.

### 4.4 `s1_credits_trace_recorder.py`

The credits recorder has three `--force-mode` strategies for kicking the ROM into
`GAMEMODE_CREDITS` without a movie:

1. **`redirect_level` (default):** Wait for title, inject START, wait for `GAMEMODE_LEVEL`,
   then RAM-patch `demo_flag=0`, `credits_num=0`, `game_mode=0x1C`. Safest because the ROM
   has finished its level-load pipeline by the time credits takes over.
2. **`direct`:** From the title screen, directly set `demo_flag=0x8001`, `credits_num=idx+1`,
   `zone_word=DEMO_ZONE_ACT_WORDS[idx]`, `lives=3`, `rings=0`, etc. plus LZ3-specific lamp
   state when `idx == 3`. Skips the level-load detour.
3. **`none`:** Sit on the title screen and wait for the game to naturally enter credits.

The RAM patches are routed through stable-retro's `env.data.set_value(name, value)`, and the
necessary variables are installed by `install_trace_data()` writing a `trace_data.json`
alongside the Sonic 1 game integration directory:

```python
_TRACE_VARS = {
    "game_mode":     (0xF600, "|u1"),
    "demo_flag":     (0xFFF2, ">u2"),
    "demo_num":      (0xFFF4, ">u2"),
    "credits_num":   (0xFFF6, ">u2"),
    "zone_word":     (0xFE10, ">u2"),
    "lives":         (0xFE12, "|u1"),
    "rings":         (0xFE20, ">u2"),
    "time_long":     (0xFE22, ">u4"),
    "score_long":    (0xFE26, ">u4"),
    "water_routine": (0xF64D, "|u1"),
    "water_state":   (0xF64E, "|u1"),
    # ... plus 15 LZ-lamp vars ...
}
```

The LZ3 (`idx == 3`) patch restores a lamp room state that the credits sequence normally
inherits from a prior level:

```python
last_lamp=1, lamp_x=0x0A00, lamp_y=0x062C, lamp_rings=13,
lamp_limit_btm=0x0800, lamp_scr_x=0x0957, lamp_scr_y=0x05CC,
lamp_bg1_x=0x04AB, lamp_bg1_y=0x03A6, lamp_water_pos=0x0308,
lamp_water_rout=1, lamp_water_stat=1, ...
```

### 4.5 Platform setup (WSL / macOS / Linux)

stable-retro requires a compiled C extension. Wheels exist for macOS and Linux but **not
Windows** — Windows users use WSL. The `s1-retro-trace` skill documents the setup
thoroughly:

```bash
# Windows: create a persistent venv under Ubuntu-24.04 WSL
wsl -d Ubuntu-24.04 -- bash -c 'python3 -m venv ~/retro-env'
wsl -d Ubuntu-24.04 -- bash -c 'source ~/retro-env/bin/activate && pip install stable-retro numpy'

# Copy ROM to WSL-local path (avoid slow /mnt/ I/O)
wsl -d Ubuntu-24.04 -- bash -c 'mkdir -p /tmp/roms && cp "/mnt/c/.../s1.gen" /tmp/roms/'

# Import ROM
wsl -d Ubuntu-24.04 -- bash -c 'cd /home && source ~/retro-env/bin/activate && PYTHONPATH="" python3 -m stable_retro.import /tmp/roms'
```

Critical: the project root contains `stable-retro-0.9.9/stable_retro/` which shadows the
installed package, so **always `cd /home`** and **always set `PYTHONPATH=""`** before running
retro scripts inside WSL. All subsequent `python3` commands must be wrapped in
`wsl -d Ubuntu-24.04 -- bash -c 'cd /home && source ~/retro-env/bin/activate && PYTHONPATH="" python3 -u ...'`.

ROM SHA-1 must be `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b` (Sonic 1 REV01 World).

---

## 5. Part III — Trace File Formats

Every production recorder produces the same v5 envelope and payload set (plus
the BK2 movie for BK2-owned recordings).

### 5.1 `metadata.json`

Minimum required fields across all games:

| Field | Type | Meaning |
|---|---|---|
| `game` | string | `"s1"`, `"s2"`, or `"s3k"` |
| `zone` | string | short zone name (`ghz`, `mz`, `ehz`, `aiz`, …) |
| `zone_id` | int | raw ROM zone byte |
| `act` | int | 1-indexed act number |
| `bk2_frame_offset` | int | emu frame at which recording began (= BK2 frame where the movie crosses into gameplay) |
| `trace_frame_count` | int | number of rows in physics.csv |
| `start_x`, `start_y` | `"0x….."` | Sonic's starting coordinates |
| `recording_date` | string | `YYYY-MM-DD` |
| `recorder` | string | opaque producer identity; canonical fixtures use `native-bizhawk-headless` |
| `recorder_version` | string | opaque native implementation version, currently `3.0` |
| `trace_schema` | int | the sole data contract selector; exactly `5` |
| `rom_checksum` | string | optional |
| `notes` | string | optional |

S2-specific additions: `characters`, `main_character: "sonic"`, and `sidekicks`. Normal Sonic
and Tails routes record `characters: ["sonic","tails"]` and `sidekicks: ["tails"]`; routes where
the ROM suppresses Tails, such as SCZ and WFZ, record `characters: ["sonic"]` and `sidekicks: []`.

S3K-specific additions (only in `aiz_end_to_end` profile):
`bizhawk_version: "2.11"`, `genesis_core: "Genplus-gx"`, and a descriptive `notes`.

Credits-demo additions: `trace_type: "credits_demo"`, `input_source: "rom_ending_demo"`,
`credits_demo_index`, `credits_demo_slug`, `emu_frame_start`.

Optional `pre_trace_oscillation_frames` field, consumed by
`AbstractTraceReplayTest` to pre-advance the engine's oscillation manager (see 6.1).

### 5.2 `physics.csv` — v5

Ordinary level traces have one fixed 42-column symmetric
primary-character/sidekick row, or 43 columns when the recording carries the
trailing `life_count` column (see below). There is no other width autodetection
and no `csv_version`. Dedicated special-stage readers are selected by `game` plus
`trace_profile`: S1 uses 14 columns, S2 uses 48, and S3K uses 20. All other
widths are rejected.

**Life counter (`life_count`, trailing 43rd column).** ROM `Life_count`
(`$FFFFFE12` in Sonic 1, 2, and 3&K alike). Recorded per-frame so a death or a
1UP is attributable to the exact frame it happened on. Recordings made before
the column existed omit it and `TraceFrame.lives()` reports
`TraceFrame.LIVES_ABSENT`; that is the only path on which no life comparison
runs, and it is keyed on the recording, never on the engine. When the column is
present `TraceBinder` emits three fields: `lives_present` (ERROR if the engine
snapshot carries no life count, so the comparison cannot silently vanish),
`lives` (the level — a missed death stays divergent from the transition on), and
`lives_delta` (the per-frame change — the field that names the exact frame a
life was gained or lost). The recorded value is comparison-only and is never
hydrated into engine state.

**Compared when both sides record them:** `x_sub`, `y_sub`, `routine`, `status_byte`,
`rings`, `camera_x`, and `camera_y`. These fields are strict frontier fields when the
trace row has extended data and `EngineDiagnostics` carries the matching engine snapshot.

Execution counters and aux-only context remain diagnostic unless a named
comparator contract says otherwise; queue/dynamic-art evidence is strict and
comparison-only.

**Always compared:** `x`, `y`, `x_speed`, `y_speed`, `g_speed`, `angle`, `air`, `rolling`,
`ground_mode` (derived from angle), plus the sidekick's copy of those fields in S2/S3K
sidekick-capable schemas.

### 5.3 `aux_state.jsonl` — event catalogue

One JSON object per line, compact (no whitespace). Every event carries `frame` and `event`
keys; most also carry `vfc` (`gameplay_frame_counter`).

Canonical union of event types across all three games:

| `event` | Games | Frame | Purpose |
|---|---|---|---|
| `object_appeared` | all | ≥0 | SST slot went non-empty |
| `object_removed` | all | ≥0 | SST slot cleared |
| `object_near` | all | ≥0 | Active object within 160 px of player |
| `slot_dump` | all | ≥0 | Full dynamic-slot occupancy, emitted any frame an object appeared |
| `state_snapshot` | all | ≥0 | Full player snapshot every 60 frames or on transitions |
| `mode_change` | all | ≥0 | `air`/`rolling`/`on_object`/`control_locked` transition |
| `routine_change` | all | ≥0 | Player `obRoutine` change with full position/velocity context |
| `cursor_state` | S1, S2 | ≥0 | ObjPosLoad cursor movement (S3K has no OPL) |
| `object_state_snapshot` | S2 | -1 | Raw 64-byte dump of every non-empty SST slot at trace start |
| `player_history_snapshot` | S2 | -1 | 64-entry ring buffer of Sonic history |
| `cpu_state_snapshot` | S2 | -1 | Tails AI counters |
| `zone_act_state` | S3K | ≥0 | Edge-triggered on `(zone,act,apparent_act,game_mode)` change |
| `checkpoint` | S3K (aiz_end_to_end) | ≥0 | `intro_begin`, `gameplay_start`, `aiz1_fire_transition_begin`, `aiz2_reload_resume`, `aiz2_main_gameplay`, `hcz_handoff_complete` |
| `player_mode_set` | S3K | ≥0 | Sonic/Tails/Knuckles switch |

S1 routines (for `routine_change`): `0`=init, `2`=control, `4`=hurt, `6`=death, `8`=reset.
**Not** the same as S2/S3K.

### 5.4 `.bk2` BizHawk movie

A zipped bundle of text files containing:
- `Input Log.txt` — one line per frame, `|` delimited, with characters per button
- `Header.txt` — emulator, core, ROM hash, log key
- `Comments.txt`, `Subtitles.txt`, etc. (unused here)

The trace replay reads only `Input Log.txt` (and only the P1 button group, identified via
the LogKey).

---

## 6. Part IV — Java Replay Harness

All replay code lives under `src/test/java/com/openggf/tests/trace/`. The collision-tracing
runtime hooks live in `src/main/java/com/openggf/physics/`.

### 6.1 `AbstractTraceReplayTest`

**File:** `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`

The base JUnit-5 test class. Subclasses override `game()`, `zone()`, `act()`,
`traceDirectory()`, and optionally `tolerances()` / `reportOutputDir()` /
`overridePreTraceOscFrames()`.

`replayMatchesTrace()` walks through these steps:

1. **Gate on fixture presence.** If any of `metadata.json`, `physics.csv`, or the `.bk2`
   is missing, the test is *skipped* via `Assumptions.assumeTrue`, not failed. This is how
   CI stays green in environments without ROMs / movies.
2. **Load `TraceData`.** Parses all three files; exposes `List<TraceFrame>` and
   `Map<Integer, List<TraceEvent>> eventsByFrame`.
3. **Load level.** `SharedLevel.load(game, zone, act)` constructs the engine level from the
   ROM.
4. **Build `HeadlessTestFixture`.** Configures BK2 movie playback starting at
   `meta.bk2FrameOffset()`; seeds Sonic's start position from `meta.start_x/y` (treated as
   centre coordinates).
5. **Initialise VBlank counter.** `ObjectManager.initVblaCounter(trace.initialVblankCounter() - 1)`
   aligns the engine's VBlank clock with the ROM's at trace frame 0.
6. **Pre-oscillation hydration.** For `preTraceOscFrames` iterations, advance
   `OscillationManager.update()` with *negative* frame numbers (keeps the engine's timeline
   clean while letting its internal oscillation phase catch up to ROM oscillation at trace
   frame 0). This is critical for MZ1 (`OscillationManager` drives the MovingBlock
   platforms — see 10.6).
7. **Pre-trace object snapshot hydration.** If the fixture has S2-style frame `-1` events,
   `TraceObjectSnapshotBinder` replays them onto the engine's `ObjectManager` so that every
   ROM slot's routine/timer state exists before frame 0.
8. **Frame-by-frame loop.** For each `i` in `[startTraceIndex, frameCount)`:
   - Ask `TraceExecutionModel.forGame(meta.game).phaseFor(previous, expected)` whether this
     frame is `FULL_FRAME` or `VBLANK_ONLY` (see 6.7).
   - `fixture.stepFrameFromRecording()` for FULL_FRAME, else
     `fixture.skipFrameFromRecording()`.
   - Capture `EngineDiagnostics` from the sprite.
   - `TraceBinder.compareFrame(...)` produces one `FrameComparison`.
   - For S3K, `S3kReplayCheckpointDetector.observe(probe)` may emit a checkpoint, fed to
     `S3kElasticWindowController` which decides strict vs elastic comparison and may advance
     the drive cursor asymmetrically.
9. **Build `DivergenceReport`.** S3K passes its `TraceData` in so the report can enrich
   itself with checkpoint metadata.
10. **Write report.** Always writes `<manifest.trace_reports>/<game>_<zone><act>_report.json`.
    If there are errors, also writes `<game>_<zone><act>_context.txt` — a side-by-side
    context window around the first error, sized `radius=10` frames.
11. **Fail the test** if `report.hasErrors()`. Print `report.toSummary()` and the context
    window to `System.err`.

### 6.2 `AbstractCreditsDemoTraceReplayTest`

Specialises the base test for S1 credits demos:

- **No `.bk2`.** Input is read from the ROM's `DemoEndData` table via
  `Sonic1CreditsDemoData.DEMO_DATA_ADDR[idx]` / `DEMO_DATA_SIZE[idx]`, wrapped in a
  `DemoInputPlayer` that implements the ROM's demo-input protocol.
- **Demo-specific initial state.** When `idx == 3` (LZ3), `setupLzDemoState(fixture)`
  restores the lamp state (ring count, camera, water level/routine).
- **Frame limit = `min(trace.frameCount, DEMO_TIMER[idx])`.**
- **REV01 demo bug emulation.** `MoveSonicInDemo` has a bug that zeroes `d2` before reading
  the held-state, causing the jump button to press every frame while the stream says "held"
  — the test forces `setForcedJumpPress(jump)` each frame to replicate.

### 6.3 Concrete test classes

**Sonic 1 full-run:**
- `TestS1Ghz1TraceReplay` — GHZ Act 1
- `TestS1Mz1TraceReplay` — MZ Act 1

**Sonic 1 credits demos (8):**
- `TestS1Credits00Ghz1TraceReplay` through `TestS1Credits07Ghz1bTraceReplay`

**Sonic 2 full-run & regressions:**
- `TestS2Ehz1TraceReplay`
- `TestS2Ehz1BuzzerSpawnRegression`
- `TestS2Ehz1BridgeTailsStandingRegression`
- `TestS2Ehz1MonitorBreakRegression`
- `DebugS2Ehz1SpiralProbe`, `DebugS2Ehz1TailsReplayWindowProbe` (diagnostic probes)

**Sonic 3K full-run & supporting:**
- `TestS3kAizTraceReplay` — AIZ1 → AIZ2 → HCZ1 end-to-end
- `TestS3kAizReplayBootstrap` — bootstrap hydration unit test
- `TestS3kReplayCheckpointDetector` / `TestS3kElasticWindowController` /
  `TestS3kRequiredCheckpointGuard` — unit tests for the S3K-specific pieces
- `DebugS3kAizReplayWindowProbe` (diagnostic)

**Synthetic / unit:**
- `TestS2SyntheticV3Fixture`, `TestS3kSyntheticV3Fixture` — exercise schema parsing without
  needing a real ROM

### 6.4 Trace data models

- **`TraceData`** — owns `List<TraceFrame>` and a `Map<Integer,List<TraceEvent>>` of aux
  events, plus `preTraceObjectSnapshots()`, `initialVblankCounter()`.
- **`TraceFrame`** — record with hex-parsed fields; `parseCsvRow(line, schema)` dispatches
  on column count (11/18/19/20/22/37/38). Holds `hasExtendedData()`, `stateEquals()` (used
  for physics-only equality in lag detection), `formatDiagnostics()`.
- **`TraceMetadata`** — parsed `metadata.json`.
- **`TraceEvent`** — sealed interface; subtypes include `Checkpoint`, `ZoneActState`,
  `ObjectStateSnapshot`, `PlayerHistorySnapshot`, `CpuStateSnapshot`, `ObjectAppeared`,
  `ObjectRemoved`, `ObjectNear`, `ModeChange`, `RoutineChange`, `StateSnapshot` (fallback
  for unknown event types).
- **`TraceCharacterState`** — per-character physics state for S2 v6's Tails block.
- **`RomObjectSnapshot`** — the raw-bytes-plus-semantic-aliases structure used by
  `object_state_snapshot`.

### 6.5 Comparison — `TraceBinder` / `ToleranceConfig`

`TraceBinder.compareFrame()` compares nine physics fields per character, plus
shared trace-frame fields when both ROM and engine recorded them:

| Field | Rule | Default tolerance |
|---|---|---|
| `x`, `y` | absolute diff | warn ≥1, error ≥1 |
| `x_speed`, `y_speed`, `g_speed` | absolute diff; **sign change is ERROR** | warn ≥1, error ≥1 |
| `angle` | circular-distance diff | warn ≥1, error ≥1 |
| `air`, `rolling` | exact match; any mismatch = ERROR | — |
| `ground_mode` | exact match of `deriveGroundMode(angle) & 0xFF` | — |
| `x_sub`, `y_sub` | exact match when both sides recorded subpixel diagnostics | warn ≥1, error ≥1 |
| `routine`, `status_byte` | exact match when both sides recorded character diagnostics | — |
| `rings` | exact match when both sides recorded; mode-gated | `RingCountMode.FORCE_ERROR` |
| `camera_x`, `camera_y` | absolute diff (both sides masked `& 0xFFFF`); only fires when ROM trace and `EngineDiagnostics` both recorded camera coords | warn ≥1, error ≥1 |

`ToleranceConfig.DEFAULT` is fully strict — any one-pixel/one-unit divergence is an ERROR:

```java
ToleranceConfig(positionWarn=1, positionError=1,
                speedWarn=1, speedError=1, speedSignChangeIsError=true,
                angleWarn=1, angleError=1,
                cameraWarn=1, cameraError=1,
                RingCountMode.FORCE_ERROR)
```

Tests can override `tolerances()` for known parity gaps — e.g. some S3K trace tests opt
into `withRingCountMode(WARN_ONLY)` while ring loss/donation parity is being chased, and
`withCameraTolerances(warn, error)` exists for the same reason on the camera fields.
The default is to fail loudly on any divergence.

`Severity`: `MATCH` / `WARNING` / `ERROR`. `FieldComparison` stores the pair of formatted
strings plus the severity and delta. `FrameComparison` aggregates all nine (or
nine + sidekick nine) comparisons for one frame and knows whether it has any error or any
divergence.

**Input validation.** Every frame, `binder.validateInput(expected, bk2Input)` asserts that
the BK2 movie's input matches the trace CSV's `input` column. A mismatch means the movie
has been re-recorded but the CSV was not regenerated — the test fails loudly rather than
quietly producing meaningless divergences.

### 6.6 `DivergenceReport` and the JSON/context outputs

`DivergenceReport` groups consecutive diverging `FrameComparison`s by field into
`DivergenceGroup`s (`field`, `severity`, `startFrame`, `endFrame`, `expectedAtStart`,
`actualAtStart`, `cascading`). The first group on each field is non-cascading; subsequent
groups on the same field within the same error run are flagged `cascading=true` so humans
can ignore them.

**JSON output** (`<manifest.trace_reports>/<game>_<zone><act>_report.json`):

```json
{
  "error_count": 3,
  "warning_count": 5,
  "total_frames": 3905,
  "summary": "3 errors, 5 warnings. First error: frame 512 -- x_speed mismatch ...",
  "latest_checkpoint": {"frame": 500, "name": "gameplay_start", ...},
  "errors": [
    {"field": "x_speed", "severity": "ERROR", "start_frame": 512, "end_frame": 520,
     "expected_at_start": "0x0200", "actual_at_start": "0x01F8", "cascading": false},
    ...
  ],
  "warnings": [...]
}
```

**Context window** (`<manifest.trace_reports>/<game>_<zone><act>_context.txt`), written only if
there are errors. Table of the `radius=10` frames on either side of the first error,
showing every divergent field plus the one-line `romDiag` and `engineDiag` strings so the
developer can see ROM state vs engine state at the moment the divergence opened.

Example text shape:

```
Frame | Exp x     | Act x     | Exp y_speed | Act y_speed
510   | 0x0200    | 0x0200    | 0x0000      | 0x0000
511   | 0x0202    | 0x0200    | 0x0010      | 0x000E   * ERROR y_speed
512   | 0x0204    | 0x0202    | 0x0020      | 0x001C   * ERROR y_speed
rom: sub=(0x0000,0x0000) rtn=02 cam=(0x0300,0x0200) rings=10 status=00
eng: rtn=02 onSlot=-1 rings=10 st=00 cam=0300
```

### 6.7 Execution phase model (lag-frame detection)

The ROM's VBlank interrupt can fire without a Level_MainLoop completing — e.g. when the game
is paused, lagging, or in a title-card/zone-transition pause. During those frames, physics
is unchanged but one full animation frame is still displayed.

`TraceExecutionModel.forGame(game).phaseFor(previous, expected)` returns one of:

- `FULL_FRAME` — `gameplay_frame_counter` advanced: run full engine tick
- `VBLANK_ONLY` — `gameplay_frame_counter` did **not** advance but `vblank_counter` did:
  skip the engine's physics tick, still consume the BK2 input

For pre-v3 fixtures that lack these counters the model falls back to a `bk2_frame_offset`
heuristic (documented in the guide) and emits a one-shot warning so the user knows the
fixture should be regenerated.

Synthetic fixtures for this path are built in-test (see `TestSpecialStageHardwareTimingLifecycle`
and `TestPlaybackAdvanceOnlyInputBridge`) rather than committed under
`src/test/resources/traces/`, so they need no ROM.

### 6.8 S3K elastic windows & required checkpoints

S3K's AIZ1 intro runs through a fire-bridge cutscene and a seamless AIZ1→AIZ2 transition
that introduces small amounts of per-frame latency we cannot match pixel-perfectly. The
solution is the **elastic window** pattern:

- `S3kReplayCheckpointDetector` emits `TraceEvent.Checkpoint` events as the engine reaches
  each of the six required milestones.
- `S3kElasticWindowController` holds a `Map<String,String>` of window entry→exit
  checkpoints — currently `"intro_begin" → "gameplay_start"` and
  `"aiz1_fire_transition_begin" → "aiz2_main_gameplay"`.
- Inside a window, the drive cursor is allowed to drift by up to
  `max(180, exitTrace - entryTrace)` engine ticks relative to the trace index. Strict
  field-by-field comparison is disabled (`isStrictComparisonEnabled() == false`).
- At each checkpoint boundary, `S3kRequiredCheckpointGuard.validate()` asserts that all
  earlier required checkpoints have been emitted in order. A missing or reordered
  checkpoint fails the test immediately.

The six required checkpoints: `intro_begin`, `gameplay_start`,
`aiz1_fire_transition_begin`, `aiz2_reload_resume`, `aiz2_main_gameplay`,
`hcz_handoff_complete`.

This is the design documented in
`docs/architecture/designs/2026-04-21-s3k-aiz-intro-parity-gate-design.md`.

### 6.9 `EngineDiagnostics` and context windows

`EngineDiagnostics` is a record snapshot captured every frame but **never compared**. It
holds:

- `routine`, `standOnSlot`, `standOnType`
- `rings`, `statusByte`
- `cameraX`, `cursorIdx`, `leftCursorIdx`, `fwdCtr`, `bwdCtr` (ObjPosLoad placement)
- `solidEvent` — one-line summary of touch/solid resolution at this frame
- `xSub`, `ySub`

Formatted by `EngineDiagnostics.format()` / `formattedOnly()` into the `eng:` line that
appears in the context window. Supplementary formatters
(`EngineNearbyObjectFormatter`, `TouchResponseDebugHitFormatter`,
`TraceEventFormatter`) produce additional context lines such as nearby-object listings
and recent aux events around the error.

---

## 7. Part V — Runtime Collision Tracing

Independent of file-based trace replay, the engine has a *runtime* collision-tracing hook
that records per-frame collision events in memory. This is used by collision-heavy tests
that want to assert "Sonic's ground sensor returned angle X, and the left-wall sensor
contacted solid Y at (a, b)" without parsing CSV.

Files in `src/main/java/com/openggf/physics/`:

- `CollisionTrace` — interface with `onTerrainProbesStart/Result/Complete`,
  `onSolidContactsStart/Candidate/Resolved/Complete`,
  `onSolidCheckpointStart/Result`, `onPostAdjustment`, `getEvents()`, `clear()`.
- `CollisionEvent` — one event record (terrain probe or solid contact) with all relevant
  fields.
- `SensorResult` — the result of a `GroundSensor` probe: angle, distance, success flag.
- `SolidContact` — contact payload for solid-object resolution.
- `RecordingCollisionTrace` — stores events in an `ArrayList<CollisionEvent>` for later
  inspection.
- `NoOpCollisionTrace` — the default zero-cost implementation used when tracing is disabled.

`CollisionSystem` holds a `CollisionTrace trace` field and forwards every probe/contact to
it. Tests that want trace output set `trace = new RecordingCollisionTrace()` before driving
a frame, then read `trace.getEvents()` after.

This is orthogonal to the CSV trace pipeline — it's a finer-grained lens for collision-only
regressions, not a recording of full gameplay.

---

## 8. Part VI — Test Resource Layout

```
src/test/resources/traces/
├── s1/
│   ├── ghz1_fullrun/           metadata.json  physics.csv  aux_state.jsonl  ghz1_fullrun.bk2
│   ├── mz1_fullrun/            metadata.json  physics.csv  aux_state.jsonl  mz1.bk2
│   ├── credits_00_ghz1/        metadata.json  physics.csv  aux_state.jsonl
│   ├── credits_01_mz2/         ...
│   ├── credits_02_syz3/
│   ├── credits_03_lz3/
│   ├── credits_04_slz3/
│   ├── credits_05_sbz1/
│   ├── credits_06_sbz2/        ← currently 0 errors / 0 warnings
│   └── credits_07_ghz1b/
├── s2/
│   └── ehz1_fullrun/           metadata.json  physics.csv  aux_state.jsonl  s2-ehz1.bk2
└── s3k/
    └── aiz1_to_hcz_fullrun/    metadata.json  physics.csv  aux_state.jsonl  s3-aiz1-2-sonictails.bk2
synthetic/
├── basic_3frames/                       — tiny shape tests
├── execution_v3_2frames/                — TraceExecutionModel unit fixture (S1)
├── s2_execution_v3_2frames/             — S2 counters, lag_counter=0
└── s3k_execution_v3_2frames/            — S3K counters, real lag_counter increments on frame 2
```

Fixture sizes:

| Trace | `physics.csv` | `aux_state.jsonl` | BK2 |
|---|---|---|---|
| S1 GHZ1 | ~370 KB, 3 905 frames | ~3.5 MB | 2.9 KB |
| S1 MZ1 | ~750 KB, 7 936 frames | ~8 MB | — |
| S2 EHZ1 | ~580 KB, 5 853 frames | ~2 MB | 3.9 KB |
| S3K AIZ→HCZ | ~2 MB, 20 913 frames | ~18 MB | 9.1 KB |

`aux_state.jsonl` dominates disk use — S3K AIZ→HCZ is 209 K+ events because it captures
every nearby-object position every frame through a 20-minute run.

---

## 9. Part VII — CI Pipeline

**`.github/workflows/ci.yml`** runs on every PR into `develop`. Two jobs:

1. **`policy`** — validates the branch trailer/file policy. On the PR, checks that every
   new non-merge commit carries the required trailers (`Changelog`, `Guide`, `Known-Discrepancies`,
   `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`) with `updated`
   or `n/a`, and cross-checks trailer ↔ file staging (e.g. `Changelog: updated` requires
   `CHANGELOG.md` to be in the diff). Runs via `bash .githooks/validate-policy.sh ci-pr ...`.
2. **`test`** — sets up Java 21 (Temurin) with Maven cache, then runs
   `mvn test -B` and reads `target/surefire-reports` and
   `target/trace-reports`.

Trace replay tests run in the `test` job but **skip gracefully** (`Assumptions.assumeTrue`)
whenever their `.bk2` or ROM is unavailable — so CI passes without committing ROMs. What CI
does exercise:

- Synthetic fixture parsing (schema v3 fidelity)
- The S3K checkpoint detector / elastic window / required-checkpoint guard unit tests
- `TestExecutionModel` unit tests against the synthetic fixtures
- Every `TestS1*`, `TestS2*`, `TestS3k*` test's *construction and skip logic* — any
  refactor that breaks the abstract base class will fail here even without a ROM

There is no dedicated CI job for recording — that's a human/agent-driven activity on
workstations with BizHawk installed.

---

## 10. Part VIII — Agent-Driven Iterative Improvement Loop

This is the crucial feedback loop that turns the trace framework into an *improvement
engine*. Agents run it over long sessions — the user describes this as "work long hours to
systematically diagnose and improve accuracy."

### 10.1 Why the loop exists

The engine has thousands of small physics edge-cases that only manifest at specific
frame/context combinations. A human cannot enumerate them. But a trace fixture *does* —
every frame where the engine diverges from the ROM is one such edge case. If you can:

1. Find the first non-cascading error,
2. Hypothesise a root cause,
3. Implement a fix,
4. Re-run the fixture,
5. Compare the new error count / first-error frame to the baseline,

and repeat, the error count monotonically decreases as each root cause is found. Agents
excel at this because steps 1, 4 and 5 are mechanical (parse JSON, count errors, diff
frames) and steps 2, 3 are LLM-friendly (read disassembly, read engine, edit Java).

### 10.2 Inputs available to the agent

For any given trace divergence, the agent has:

- **`<manifest.trace_reports>/<game>_<zone><act>_report.json`** — machine-readable error list.
- **`<manifest.trace_reports>/<game>_<zone><act>_context.txt`** — human-readable first-error
  window with side-by-side ROM vs engine diagnostics.
- **`src/test/resources/traces/<game>/<name>/physics.csv`** — every ROM-state row.
- **`src/test/resources/traces/<game>/<name>/aux_state.jsonl`** — every ROM event.
- **`docs/s1disasm/` / `docs/s2disasm/` / `docs/skdisasm/`** — the original ROM source (not
  checked in but available locally).
- **`RomOffsetFinder` tool** (`com.openggf.tools.disasm.RomOffsetFinder`) — symbol → offset
  resolution with ROM verification.
- **Engine code under `src/main/java/com/openggf/`** — the thing being fixed.
- **The engine's own collision trace** (`RecordingCollisionTrace`) for fine-grained
  collision divergences.
- **Diagnostic Lua scripts** (`hook_solid_classify.lua`, `hook_speedtopos.lua`, etc.) when
  the divergence narrows to a single ROM instruction.
- **Prior case studies** — the frontier history in `docs/status/trace-frontier-log.md`
  and the write-ups under `docs/architecture/research/` and `docs/architecture/audits/`.

### 10.3 The skills that drive iteration

Skills in `.claude/skills/` that are directly about the trace loop:

- **`s1-trace-replay`** — "Record a Sonic 1 BizHawk trace, copy it to the test resources,
  run the trace replay tests, and interpret the divergence results." Supports
  `mz1` / `ghz1` / `all` / `test-only` / `interpret` modes.
- **`bizhawk-headless-trace`** — canonical native capture and publication,
  including ROM-owned S1 credits-demo recording.

Supporting skills that a trace-driven agent routinely calls:

- **`s1disasm-guide` / `s2disasm-guide` / `s3k-disasm-guide`** — how to navigate the
  disassemblies to verify a ROM routine.
- **`s1-implement-object` / `s2-implement-object` / `s3k-implement-object`** — used when the
  fix is "this object's AI was wrong."
- **`superpowers:systematic-debugging`** — the meta-debug flow.
- **`superpowers:test-driven-development`** — if the fix needs a narrower unit test first.
- **`superpowers:verification-before-completion`** — mandatory before claiming the fix
  works.
- **`superpowers:receiving-code-review`** / **`requesting-code-review`** — for review loops.
- **`superpowers:subagent-driven-development`** — the pattern that makes long iterations
  feasible by dispatching independent sub-tasks in parallel.
- **`superpowers:using-git-worktrees`** — isolate a long-running iteration in a worktree
  without polluting the main checkout.

### 10.4 The `/loop` and subagent-driven patterns

Two mechanisms move the iteration forward:

**`/loop` skill** — runs a prompt or slash command on a recurring schedule, either at a
fixed interval (`/loop 5m /foo`) or self-paced (`/loop /foo` with no interval), suitable for
**"check the fixture, note the error count, if it went down commit, if it went up revert,
work on the first non-cascading error"** kind of cycles. In self-paced mode the agent uses
`ScheduleWakeup(delaySeconds, prompt, reason)` to plan its own cadence — typically 270 s
(stays in Anthropic prompt cache) or 1 200+ s (amortises cache misses).

**`superpowers:subagent-driven-development`** — when a plan has multiple independent tasks,
the orchestrating agent dispatches sub-agents (via the Agent tool) in parallel, each getting
a self-contained briefing. In the trace context, typical sub-agent roles are:

- "Read the first error in the context file and identify the ROM routine implicated."
- "Find the corresponding engine code and propose a minimal fix."
- "Run `mvn test -Dtest=TestS1Mz1TraceReplay`, inspect
  `target/trace-reports`, and report the new error count."
- "Record a fresh trace in BizHawk and update the fixture."

Results come back as short reports; the orchestrator decides what to do next.

### 10.5 Plan/research/spec artefacts

Longer, structured work lives under `docs/architecture/`:

- **`architecture/plans/`** — step-by-step execution plans (`executing-plans` skill format). Current
  trace-related plans:
  - `2026-04-20-s2-s3k-trace-recorder-v3.md` — the plan this branch implements
  - `2026-04-21-s3k-aiz-intro-parity-gate.md` — aligning elastic-window replay with the
    approved parity gate
  - `2026-04-21-s3k-end-to-end-trace-fixture.md` — roadmap for the real AIZ→HCZ fixture
- **`architecture/research/`** — investigation dumps.
  - `2026-04-20-s2-trace-addresses.md` — S2 RAM addresses
  - `2026-04-20-s3k-trace-addresses.md` / `2026-04-21-s3k-trace-addresses.md` — S3K RAM
    addresses, S&K-side only
- **`architecture/designs/`** — concrete design contracts.
  - `2026-04-21-s3k-aiz-intro-parity-gate-design.md` — the six-checkpoint gate contract

Plans are written once (`superpowers:writing-plans`), executed repeatedly
(`superpowers:executing-plans` or `subagent-driven-development`), and referenced by commits
via the `Changelog`/`Guide`/`Agent-Docs` trailers required by the branch policy.

### 10.6 Case study — MZ1: 229 → 147 → 57 errors

Reconstructed from the MZ1 trace-progress notes of the time:

- **Baseline:** 229 errors on first run of `TestS1Mz1TraceReplay`.
- **Session 1 fixes (229 → 147):** subpixel position fidelity, sensor alignment, solid
  object landing width.
- **Session 2 fixes (147 → 57):** seven concrete fixes to solid-object interactions —
  boundaries, path exclusivity, sticky buffer, `MvSonicOnPtfm2` offset for MovingBlock /
  Platform / CollapsingFloor, on-screen X-only guard for touch responses, first-frame skip
  for touch responses, pre-update of collision flags for correct touch response timing.
- **Root cause of remaining 57 errors:** a single 1-pixel X difference at trace frame 6100
  where Sonic leaves a MovingBlock. It cascades to a lava-ball touch at frame 6638, which
  cascades to the remaining 55 errors. Classic "first non-cascading error" lesson.
- **BizHawk-side confirmation:** used an `onmemoryexecute` hook at ROM `0x0081DC`
  (`sub.w D2,8(A1)` inside `MvSonic2`) to confirm that the ROM's `MvSonic2` carries **both**
  X and Y deltas on every frame, *including the jump-off frame*, using D2 (saved platform X
  before `MBlock_Move`). The engine did apply the X carry in `clearRidingObject()` but at
  the specific frame, the computed delta was 0 because the platform's oscillation hadn't
  moved it that tick.
- **Gotcha discovered:** the S1 recorder's `ADDR_FRAMECOUNT` had been set to `0xFE02` —
  wrong. Correct address is `0xFE04`, verified from the ROM `addq.w #1` instruction at
  `0x003AEC`. Once fixed, the trace's `gameplay_frame_counter` column aligned correctly
  with the ROM's `v_framecount` and the cursor-tracking events started matching.

This case study is the canonical example of:
1. Divergence cascade analysis (57 errors → 1 root cause).
2. Using BizHawk execute hooks as a ROM microscope once the first-error frame is known.
3. How a bug in the recorder itself (wrong `ADDR_FRAMECOUNT`) produces hard-to-diagnose
   divergences downstream.

### 10.7 Case study — S3K AIZ intro parity gate

Even more structural than MZ1 — here the issue is not "fix a physics bug" but "the fixture
itself cannot match pixel-perfectly through the intro." Solution:

1. Write a spec
   (`docs/architecture/designs/2026-04-21-s3k-aiz-intro-parity-gate-design.md`) defining the
   six-checkpoint contract.
2. Implement detectors (`S3kReplayCheckpointDetector`) + controller
   (`S3kElasticWindowController`) + guard (`S3kRequiredCheckpointGuard`) in the test
   harness.
3. Update the recorder (`s3k_trace_recorder.lua`'s `aiz_end_to_end` profile) to emit the
   same six checkpoints into `aux_state.jsonl`.
4. Add a fixture sanity gate (PowerShell block in [`trace-replay.md`](trace-replay.md)) that
   verifies no checkpoints are missing, duplicated, or out of order *before* the fixture is
   committed.
5. Enable strict pixel comparison only outside the elastic windows; allow up to
   ±180 engine ticks inside each window.

Once all of that is in place, the S3K fixture can catch genuine divergences in AIZ2 main
gameplay and the HCZ handoff without failing on the fundamentally-unavoidable intro drift.

### 10.8 Branch policy feedback into the loop

The repo's trailer policy (see `CLAUDE.md` §"Branch Documentation Policy") closes the loop:
every trace-related commit must stage:

- `CHANGELOG.md` — what changed, visible in the trace section of the changelog
- `docs/status/known-discrepancies.md` / `docs/S3K_KNOWN_DISCREPANCIES.md` — intentional deviations
- `docs/guide/*` — keep the guide truthful
- `AGENTS.md` + `CLAUDE.md` — keep agent guidance truthful
- `CONFIGURATION.md` — if a new env var was added (`OGGF_S3K_TRACE_PROFILE`,
  `BIZHAWK_EXE`, etc.)

This means every iteration that touches the trace framework necessarily updates the
documentation the next iteration reads. The loop is literally self-documenting.

---

## 11. Part IX — Appendices

### 11.1 RAM address reference per game

All offsets are from `$FF0000`. See the recorders for authoritative values.

**Sonic 1 (REV01):**

| Name | Addr | Size | Notes |
|---|---|---|---|
| `game_mode` | `0xF600` | byte | `0x0C` = level |
| `v_framecount` | `0xFE04` | word BE | Not `0xFE02` |
| `v_vblank` | `0xFE0E` | word BE | |
| `zone / act` | `0xFE10 / 0xFE11` | byte | |
| `rings` (BCD word) | `0xFE20` | word BE | |
| `camera_x / camera_y` | `0xF700 / 0xF704` | long, hi word | |
| `player_base` | `0xD000` | SST slot 0 | |
| player offsets | `0x08..0x3E` | | see s1 recorder |
| `stand_on_obj` | `+0x3D` | byte | SST slot |
| `opl_screen / opl_fwd / opl_bwd` | `0xF76E / 0xF770 / 0xF774` | | |

**Sonic 2 (REV01):**

| Name | Addr | Size | Notes |
|---|---|---|---|
| `game_mode` | `0xF600` | byte | |
| `v_framecount` | `0xFE04` | word BE | Level_frame_counter |
| `v_vblank` | `0xFE0E` | word BE | Vint_runcount+2 |
| `player_base (sonic)` | `0xB000` | | |
| `sidekick_base (tails)` | `0xB040` | | SST slot 1 |
| `tails_control_counter` | `0xF702` | word BE | |
| `tails_respawn_counter` | `0xF704` | word BE | |
| `tails_cpu_routine` | `0xF708` | word BE | |
| `tails_cpu_target_x / y` | `0xF70A / 0xF70C` | word BE | |
| `tails_interact_id` | `0xF70E` | byte | |
| `tails_cpu_jumping` | `0xF70F` | byte | |
| `sonic_stat_record_buf` | `0xE400` | byte buf | Pre-trace history |
| `sonic_pos_record_buf` | `0xE500` | byte buf | |
| `sonic_pos_record_index` | `0xEED2` | word BE | |
| `OFF_CTRL_LOCK` | `+0x2E` | word BE | Differs from S1 (`0x3E`) |

**Sonic 3&K (locked-on):**

| Name | Addr | Size | Notes |
|---|---|---|---|
| `game_mode` | `0xF600` | byte | |
| `v_framecount` | `0xFE08` | word BE | Not `0xFE04` |
| `v_vblank` | `0xFE12` | word BE | V_int_run_count+2 |
| `lag_frame_count` | `0xF628` | word BE | Only S3K has this |
| `zone / act` | `0xFE10 / 0xFE11` | byte | |
| `apparent_act` | `0xEE4F` | byte | Display act during AIZ2 reload |
| `player_mode` | `0xFF08` | word BE | Sonic/Tails/Knuckles |
| `events_fg_5` | `0xEEC6` | word BE | AIZ1 fire transition flag |
| `level_started_flag` | `0xF711` | byte | |
| `ctrl1_locked` | `0xF7CA` | byte | |
| `camera_x / camera_y` | `0xEE78 / 0xEE7C` | word BE | |
| `player_base` | `0xB000` | | Slot 0 |
| `ost_base` | `0xB400` | | Dynamic slot start |
| `x_pos` (32-bit) | `+0x10 / +0x12` | word.word | hi=pixel, lo=subpixel |
| `y_pos` (32-bit) | `+0x14 / +0x16` | | |
| `x_vel / y_vel / inertia` | `+0x18 / +0x1A / +0x1C` | signed word BE | |
| `routine` | `+0x05` | byte | |
| `status` | `+0x2A` | byte | |
| `angle` | `+0x26` | byte | |
| `stand_on_obj` | `+0x42` | word BE | **RAM ptr**, convert via `(addr-0xB400)/0x4A` |
| `ctrl_lock` | `+0x32` | word BE | |

### 11.2 Pre-v5 schema history

The table below is predecessor history only. Current code accepts exactly
`trace_schema: 5` and no separate CSV or recorder-derived schema selector.

| Schema | CSV cols | Key additions |
|---|---|---|
| v1 | 11 | Baseline: frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode |
| v2 | 18 | + x_sub, y_sub, routine, camera_x, camera_y, rings, status_byte |
| v2.1 | 19 | + gameplay_frame_counter (originally `v_framecount`) |
| v2.2 | 20 | + stand_on_obj |
| v3 (S1/S3K) | 22 | + vblank_counter, lag_counter |
| v4 (S2) | — | Per-slot `object_state_snapshot` aux events at frame -1 |
| v5 (S2) | 37 | First-sidekick state appended to each row (deprecated) |
| v6 (S2) | 38 | Explicit named `sonic_*` / `tails_*` blocks with shared header |
| v7 (S2) | 38 | + pre-trace `cpu_state_snapshot` for Tails AI |
| v8 (S2) | 38 | Character-scoped aux events and per-character nearby-object scans |

None of these predecessor versions is a current compatibility target.

### 11.3 S1/S2/S3K zone name tables

**S1 REV01** (`ZONE_NAMES` in `s1_trace_recorder.lua`):

```
0 ghz   1 lz    2 mz    3 slz   4 syz   5 sbz   6 endz  7 ss
```

**S2 REV01**:

```
0x00 ehz  0x02 wz  0x04-05 mtz  0x06 wfz  0x07 htz  0x08 hpz
0x0A ooz  0x0B mcz 0x0C cnz     0x0D cpz  0x0E dez  0x0F arz  0x10 scz
```

**S3K**:

```
0x00 aiz  0x01 hcz  0x02 mgz  0x03 cnz  0x04 fbz  0x05 icz  0x06 lbz
0x07 mhz  0x08 soz  0x09 lrz  0x0A ssz  0x0B dez  0x0C ddz  0x0D hpz
```

### 11.4 Related source files

**Current recorder:**

- `tools/tracechaser/bizhawk-headless/run.sh`
- `tools/tracechaser/bizhawk-headless/src/Recording/`

**Historical/diagnostic recorder references:**

- `tools/tracechaser/bizhawk/s1_trace_recorder.lua`
- `tools/tracechaser/bizhawk/s2_trace_recorder.lua`
- `tools/tracechaser/bizhawk/s3k_trace_recorder.lua`
- `tools/tracechaser/bizhawk/record_trace.bat`, `record_s2_trace.bat`, `record_s3k_trace.bat`
- `tools/tracechaser/retro/trace_core.py`
- `tools/tracechaser/retro/s1_trace_recorder.py`
- `tools/tracechaser/retro/s1_credits_trace_recorder.py`
- `tools/tracechaser/retro/requirements.txt`

**Java harness** (`src/test/java/com/openggf/tests/trace/`):

- `AbstractTraceReplayTest.java`
- `AbstractCreditsDemoTraceReplayTest.java`
- `TraceData.java`, `TraceFrame.java`, `TraceMetadata.java`, `TraceEvent.java`,
  `TraceCharacterState.java`, `RomObjectSnapshot.java`
- `TraceBinder.java`, `ToleranceConfig.java`, `Severity.java`, `FieldComparison.java`,
  `FrameComparison.java`
- `DivergenceReport.java`, `DivergenceGroup.java`
- `EngineDiagnostics.java`, `EngineNearbyObjectFormatter.java`,
  `TouchResponseDebugHitFormatter.java`, `TraceEventFormatter.java`
- `TraceExecutionModel.java`, `TraceExecutionPhase.java`
- `TraceReplayBootstrap.java`, `TraceObjectSnapshotBinder.java`
- `s3k/S3kReplayCheckpointDetector.java`, `s3k/S3kCheckpointProbe.java`,
  `s3k/S3kRequiredCheckpointGuard.java`
- `Sonic1CreditsDemoData.java` (under `src/main/java/com/openggf/game/sonic1/credits/`)

**Runtime collision trace** (`src/main/java/com/openggf/physics/`):

- `CollisionTrace.java`, `CollisionEvent.java`, `SensorResult.java`, `SolidContact.java`,
  `RecordingCollisionTrace.java`, `NoOpCollisionTrace.java`
- `CollisionSystem.java` (holds the trace handle)

**Docs:**

- `docs/guide/contributing/trace-replay.md` — user-facing guide
- `docs/guide/contributing/trace-framework-reference.md` — this document
- `docs/architecture/plans/2026-04-20-s2-s3k-trace-recorder-v3.md`
- `docs/architecture/plans/2026-04-21-s3k-aiz-intro-parity-gate.md`
- `docs/architecture/plans/2026-04-21-s3k-end-to-end-trace-fixture.md`
- `docs/architecture/research/2026-04-20-s2-trace-addresses.md`
- `docs/architecture/research/2026-04-20-s3k-trace-addresses.md`
- `docs/architecture/research/2026-04-21-s3k-trace-addresses.md`
- `docs/architecture/designs/2026-04-21-s3k-aiz-intro-parity-gate-design.md`

**Skills:**

- `.claude/skills/s1-trace-replay/SKILL.md`
- `.claude/skills/trace-replay-bug-fixing/SKILL.md`
- `.claude/skills/bizhawk-headless-trace/SKILL.md`
