# Audit: dead and partially dead trace-comparison fields

**Date:** 2026-08-06
**Commit audited:** `b57cd1e57` (develop)
**Scope:** every field the trace comparison chain can compare, on every lane that
produces a `FrameComparison`.

## Why this exists

Ring count was silently never compared on either lane. `EngineDiagnostics` passed a
hardcoded `rings = -1`, `TraceBinder` skips the field when the engine value is negative,
so every row passed the ring check. Fixed on the run path (`9e7590efa`) and the per-act
path (`11d9b67de`); turning it on exposed 21 real divergences that had been invisible
since the lanes were written.

The same agent noted in passing that `routine`, `statusByte`, `xSub` and `ySub` are also
still `-1` on the per-act lane. This audit enumerates the whole class before any further
behavioural change, so that each switch-on has an attributable delta.

**A comparator that silently compares nothing is the most dangerous thing against the
"headless traces pass for all games" goal, because it makes the suite report success it
has not earned.**

## The axis that decides what happens to each field

Every dead field falls into exactly one of two categories, and they call for completely
different work:

- **Dead by sentinel** — the ROM column exists, the engine value is computed, and a
  producer throws it away by hardcoding `-1` at the call site. `TraceBinder` then skips
  the comparison because it reads a negative as "not captured". **Fix cost: one call-site
  parameter.** This is the ring-count class. Every field in this category should be
  switched on, one at a time, with an individual measured delta.
- **Dead by absence** — the recorder never reads the ROM address, so there is no column
  and nothing to compare against. **Fix cost: recorder change (Lua *and* C# native), a
  schema bump, and a full fixture recapture.** No amount of plumbing makes these live.

Every table below states which category a field is in.

## The lanes

`TraceBinder.compareFrame(...)` is the single comparison engine. What it actually
compares depends entirely on what the caller puts in the `EngineDiagnostics` record it is
handed. There are four distinct producers.

| Lane | Producer | `EngineDiagnostics` construction | Exercised by |
|---|---|---|---|
| **A1** per-act, S1/S2 | `AbstractTraceReplayTest.replayMatchesTrace()` line 459-470 | captures a full record at line 440, then **re-wraps** it through `EngineDiagnostics.formattedWithCameraAnimationAndRings` | `tests/trace/s1/*TraceReplay`, `tests/trace/s2/*TraceReplay` |
| **A2** per-act, S3K | `AbstractTraceReplayTest.replayS3kTrace()` lines 623, 770 | passes the **full** `engineDiag` record | `tests/trace/s3k/*TraceReplay` |
| **A3** S1 credits demos | `AbstractCreditsDemoTraceReplayTest` lines 173, 216 | passes the **full** record built at line 394 | `tests/trace/s1/TestS1Credits0*TraceReplay` |
| **B** whole-run / visual | `LiveTraceComparator.compareFrame` line 268-285 | `EngineDiagnostics.formattedWithCameraAnimationSubpixelAndRings` | `tests/trace/runs/*`, `TraceReplayDriver`, `TraceSessionLauncher` |
| **C** presentation rows | `TraceStructuralRowComparator` | none — no playable state at all | run-chain presentation segments |

Lane C compares no playable state **by design** ("Presentation rows deliberately have no
playable-state comparison"), so it is out of scope below.

The key asymmetry: **A2 and A3 pass the full diagnostics record; A1 and B throw most of
it away at the call site.** One lane being live does not imply the other.

## What the recorder actually captures

The v5 `physics.csv` row is a fixed 42-column schema. Verified against
`src/test/resources/traces/s1/ghz1_completerun/physics.csv.gz` and
`TraceFrame.parseV5Row`:

```
frame, input, camera_x, camera_y, rings, gameplay_frame_counter, vblank_counter,
lag_counter,
player_present, player_x, player_y, player_x_speed, player_y_speed, player_g_speed,
player_angle, player_air, player_rolling, player_ground_mode, player_x_sub,
player_y_sub, player_routine, player_status_byte, player_stand_on_obj,
player_animation_id, player_mapping_frame,
sidekick_present, ... (the same 17 fields)
```

`aux_state.jsonl` adds the event families in `TraceEvent`. A key scan over
`s1/ghz1_completerun/aux_state.jsonl.gz` found these JSON keys and no others of interest:
`stand_on_obj`, `stand_obj_slot/type/routine/x/y`, `fwd_ctr`, `bwd_ctr`, `fwd_ptr`,
`bwd_ptr`, `underwater`, `status_byte`, `routine`, `anim_id`, `control_locked`,
`pushing`, `on_object`, `roll_jumping`, `x_radius`, `y_radius`, `vfc`, `lagcount`,
`limitbtm1/2`, `lookshift`, `opl_screen`, `bgscrollvert`, and the load-queue /
dynamic-art fields.

## Score and lives — the explicit question

**Both are dead by absence, on all three games, on every lane.** Not a sentinel, not a
tolerance mode, not a skipped branch: the recorders never read the addresses, so no column
exists and there is nothing to compare against.

### Per game

The three games share an identical RAM layout for this block. Anchoring on `Ring_count`,
which every recorder already reads at `$FFFFFE20`:

| | S1 (`_Variables.asm:356,376`) | S2 (`s2.constants.asm:1677,1702`) | S3K (`sonic3k.constants.asm:794,811`) |
|---|---|---|---|
| lives address | `v_lives` `$FFFFFE12` (byte) | `Life_count` `$FFFFFE12` (byte) | `Life_count` `$FFFFFE12` (byte) |
| score address | `v_score` `$FFFFFE26` (long) | `Score` `$FFFFFE26` (long) | `Score` `$FFFFFE26` (long) |
| Lua recorder reads either? | **No** — only `ADDR_RING_COUNT = 0xFE20` | **No** | **No** |
| C# native recorder reads either? | **No** — `S1TraceCsvWriter.cs:51` reads `S1Ram.RingCount` only | **No** — `S2TraceCsvWriter.cs:122` | **No** — `S3KTraceCsvWriter.cs:147` |
| column exists? | **No** | **No** | **No** |
| engine accessor | `GameStateManager.getScore()` / `.getLives()` | same | same |

The engine accessors are reliable and shared: `score` and `lives` are session-scoped
fields on `GameStateManager` (`:22-23`), deliberately **not** reset by `resetForLevel()`
(`:211-213`), mutated only through `addScore()`, `addLife()` and `loseLife()`, and already
captured in the rewind snapshot (`:774-775`). There is no per-game divergence in how the
engine holds them, so a single comparison field works for all three games.

One S1-only subtlety for whoever writes the recorder change: S1 has **two** lives
variables — `v_lives` (`$FE12`, the real count) and `v_lifecount` (`$FE1B`, the BCD value
the HUD displays). The engine's `getLives()` models the real count, so the recorder must
read `$FE12`. S3K additionally has `Life_count_P2` (`$FE...`, competition-mode leftover);
main-game single-player uses `Life_count`.

The only lives-adjacent artefact already in the tree is a *historical bug*: the S3K
recorder's `ADDR_VBLA_WORD` used to point at `Life_count` (`$FE12`) instead of
`V_int_run_count` (`$FE0E`), so the `vblank_counter` column was accidentally recording the
lives counter — "constant except on 1UPs". Fixed in `v6.32-s3k`
(`tools/bizhawk/s3k_trace_recorder.lua:46-51`, `s3k_complete_run_recorder.lua:295`,
`S3KTraceCsvWriter.cs:19`). Captures made before that carry the wrong column.

### Consequence for the death-handling finding

The death-handling audit's finding — that the engine's lives write lands 60 frames later
than the ROM's (`Sonic_HandleDeath`, `docs/s1disasm/_incObj/01 Sonic.asm:2013-2020,
2042-2045`) — is **uncompared**, not compared-and-wrong and not compared-and-lucky. No
trace lane could ever have caught it, and none will until a recorder change lands. It is
invisible rather than tolerated.

### Game over must be derived, not recorded

Game over is **not** a field to capture. Its condition is lives reaching zero, so a
recorded game-over flag would be redundant state that then has to be kept consistent with
the lives column. Once `lives` is captured, game over is derivable on both sides. Do not
add a column for it.

### What capturing score and lives would actually cost

Scoped, **not started** — this wants its own plan and its own sequencing decision.

| Item | Detail |
|---|---|
| Lua recorders | 5 files: `s1_trace_recorder.lua`, `s1_complete_run_recorder.lua`, `s2_trace_recorder.lua`, `s3k_trace_recorder.lua`, `s3k_complete_run_recorder.lua`. Two `mainmemory.read_u8(0xFE12)` / `read_u32_be(0xFE26)` calls and two header/row entries each. The addresses are identical across all three games and adjacent to one already being read. |
| C# native recorders | 3 files: `S1TraceCsvWriter.cs`, `S2TraceCsvWriter.cs`, `S3KTraceCsvWriter.cs`, plus a constant each in `S1Ram` / `S2Ram` / `S3KRam`. |
| Java parse | `TraceFrame`: `V5_COLUMNS` 42 → 44, two record components, `parseV5Row` offsets for the primary/sidekick blocks shift by 2. |
| Comparison | `TraceBinder`: two `compareNumeric` calls. `EngineDiagnostics`: two record components plus forwarding on each of the four producers. |
| Schema | Row width is fixed per game+profile in v5, so this is a **schema bump**. Sanctioned as minor with **no backwards compatibility, no shims, no migration** — the project is pre-release. |
| Fixtures | Full recapture. Explicitly *not* a cost to minimise. |
| Sequencing | There is already a queued schema-shape change for the recorded level-load span (`docs/architecture/designs/2026-08-06-recorded-level-load-span-segment.md` §7.1-7.2) that re-records all seven run fixtures. Score/lives should **ride along with that recapture** rather than forcing a second one. |

### The one lane that does compare score

`UserRecordingVerifier` / `DesyncLiteSnapshotter`
(`src/main/java/com/openggf/game/recording/`) compares `timerFrames`, `timerSeconds`,
`timerMinutes`, `ringCount` and `score`. **That lane is engine-versus-its-own-recording,
not engine-versus-ROM**, so it can only catch a replay determinism break, never a
ROM-accuracy divergence. It does not close this gap, and it does not compare lives at all.

## The dead-field table

Fields are named as they appear in comparison output (`FieldComparison.name`).
"Dead" = the comparison never executes. "Partially dead" = it executes on some lanes only.

### Class 1 — DEAD BY SENTINEL (cheap: one call-site parameter each)

These are the direct siblings of the ring-count bug: the engine value is captured, then
discarded by a factory that hardcodes `-1`, and `TraceBinder` skips on the negative.
**These are the cheap, high-value switch-ons.**

| Field | Dead on | Live on | Why dead | Recorded? |
|---|---|---|---|---|
| `routine` | **A1 (S1/S2 per-act), B (run/visual)** | A2, A3 | `formattedWithCameraAnimationAndRings` (A1) and `formattedWithCameraAnimationSubpixelAndRings` (B) both hardcode `routine = -1`; `TraceBinder:226` requires `engineDiag.routine() >= 0`. A1 *computes* the real value at `AbstractTraceReplayTest:1021` and then throws it away one line later. | Yes — `player_routine` col 20. |
| `status_byte` | **A1, B** | A2, A3 | Same two factories hardcode `statusByte = -1`; `TraceBinder:231` requires `>= 0`. A1 computes it at line 1040 and discards it. | Yes — `player_status_byte` col 21. |
| `x_sub`, `y_sub` | **A1** | A2, A3, B | `formattedWithCameraAnimationAndRings` hardcodes `xSub = ySub = -1`; `TraceBinder:190` requires both `>= 0`. B was fixed when the subpixel factory was added; A1 was not. A1 computes both at lines 1060-1061 and discards them. | Yes — `player_x_sub` / `player_y_sub` cols 18-19. |

The sidekick equivalents (`sidekick_routine`, `sidekick_status_byte`, `sidekick_x_sub`,
`sidekick_y_sub`) are **live on both lanes** — they travel in `TraceCharacterState`, not
`EngineDiagnostics`, and both lanes pass a real `captureFirstSidekickState()`. So the
per-act lane currently checks Tails' routine but not Sonic's.

### Class 2 — DEAD BY MISSING COMPARATOR (medium: recorded on both sides, but nobody wrote the comparison)

Not dead by absence — the ROM column and the engine value both exist. Not dead by sentinel
either — no `-1` is involved. `TraceBinder` simply has no code that emits these fields.

| Field | Dead on | Why | Recorded? | Engine side |
|---|---|---|---|---|
| `stand_on_obj` (primary) | **all lanes** | `TraceBinder` never emits it. `expected.standOnObj()` is read only inside sidekick status heuristics (`TraceBinder:1100`). `EngineDiagnostics.standOnSlot` / `standOnType` are captured on A1/A2/A3 and only *formatted* into the context string. | Yes — `player_stand_on_obj` col 22, plus `stand_obj_slot/type/routine/x/y` in aux. | Yes — `ObjectManager.getRidingObject(...).getSlotIndex()`. |
| `sidekick_stand_on_obj` | **all lanes** | `appendCharacterComparisons` compares 16 of the 17 `TraceCharacterState` fields and omits `standOnObj`. | Yes — col 39. | Yes — `TraceCharacterState.java:76`. |
| riding / standing snapshot | **all lanes** | `EngineDiagnostics.ridingObject` and `standingSnapshot` are engine tri-states with no comparison; their ROM counterpart is `stand_on_obj`. Captured only on A1/A2 (both `-1` on A3 and B). | Counterpart yes. | Yes. |
| placement cursors (`cursorIdx`, `leftCursorIdx`, `fwdCtr`, `bwdCtr`) | **all lanes** | Engine values are captured into `EngineDiagnostics` and formatted; the ROM side arrives as `TraceEvent.VObjState` bytes and is only rendered by `TraceEventFormatter:347`. No comparator. | Yes — `v_objstate` aux event. | Yes — `ObjectManager.getPlacementCursorState()`. |

`stand_on_obj` is the largest genuinely-comparable gap after class 1: it is the ROM's own
answer to "which object is the player riding", it is recorded for both characters on every
fixture, and platform/ride divergence is a recurring frontier cause.

### Class 3 — NOT DEAD, by design (recorded, consumed as replay input rather than compared)

These are **not** defects; they drive the execution model under the trace-execution
contract. Recorded here so nobody re-discovers them as "dead".

| Field | Status |
|---|---|
| `gameplay_frame_counter` | Drives `TraceReplaySessionBootstrap.setFrameCounter(...)` and phase selection in `TraceReplayBootstrap`. Never compared. Comparing it would be partly circular on seeded frames but *not* thereafter — a genuine latent check. |
| `vblank_counter` | Drives lag/phase detection (`TraceReplayBootstrap:780`). Never compared. |
| `lag_counter` | Drives lag/phase detection. Never compared. |
| `player_ground_mode` (col 17) | **Deliberately unused.** `TraceBinder:217-224` derives `ground_mode` from `angle` on *both* sides because the ROM has no stored ground-mode variable and the engine's stored value is stale while airborne. Documented in-line; correct as-is. |
| `input` | Compared, but as a hard `fail()` in A1/A2 and as an `input_alignment` field in B — not via the normal field path. Live. |

### Class 4 — DEAD BY MISSING COMPARATOR, aux events

Recorded, parsed into `TraceEvent`, and rendered into the ROM diagnostics string for
human cross-reference, but never compared for pass/fail:

`CameraBoundary`, `LagState`, `VOscillate`, `OscillationState`, `InteractState`,
`ModeChange`, `RoutineChange`, `CollisionEvent`, `AirCountdownState`, `RngCall`,
`SonicRecordPos`, `AizBoundaryState`, `AizShipLoop`, `CageState` / `CageExecution`,
`CnzCylinderState` / `CnzCylinderExecution`, `S1Obj64State`, `VelocityWrite`,
`PositionWrite`.

Several of these are explicitly documented as diagnostic-only (`AirCountdownState`:
"replay code must never hydrate engine state from it"; `RngCall`: "comparison-only
context"). Diagnostic-only is a legitimate design point — the note here is that "recorded"
must not be mistaken for "checked".

### Class 5 — DEAD BY GATE (a default, an overload choice, or absent fixture metadata)

| Field | Gate | Effect |
|---|---|---|
| `obj_*` (`obj_sNN_type/x/y`, `obj_extra_*`) | `AbstractTraceReplayTest.compareObjectNearEvents()` returns **`false` by default** | Object-slot comparison runs in exactly **4** of the per-act classes (`TestS1Lz2/Sbz2/Sbz3CompleteRunTraceReplay`, `TestS2Arz2LevelSelectTraceReplay`). Never on the S3K per-act branch — the call site at line 475 is inside the non-S3K `else`. Never on lane B. |
| `sidekick cpu_*` (`cpu_routine`, `cpu_control_counter`, `cpu_respawn_counter`, `cpu_interact`, `cpu_target_x/y`, `cpu_ctrl2_held/pressed`, `cpu_jumping`, `cpu_follow_ring`) | Lane B calls the `compareFrame` overload that passes `expectedSidekickCpu = null`; `appendSidekickCpuComparisons` returns immediately on null | **Entire Tails-CPU comparison family is dead on lane B.** Live on A1/A2. The data is recorded (`cpu_state` aux events). |
| bootstrap frame-0 divergences | `TraceBinder.compareBootstrapFrame0` returns `List.of()` unless `metadata.aux_schema_extras` contains `native_prelude_bootstrap` | **19 of 72** fixtures advertise it. The other 53 get no frame-0 bootstrap check. Data-availability gate, not a code defect — but the coverage number is worth knowing. |
| `rings` | `ToleranceConfig.ringCountMode() != DISABLED` | `DEFAULT` is `FORCE_ERROR`, so live. Callers may opt out via `withRingCountMode(DISABLED)` — check per-test overrides before trusting a green ring check. |
| `camera_x` / `camera_y` | both sides `>= 0` | Live on all four lanes. |
| `player_animation_id` / `player_mapping_frame` / `player_animation_present` | `expected.animationId() >= 0 && expected.mappingFrame() >= 0` | Live on all four lanes (all four factories forward animation). |

### Class 6 — DEAD BY ABSENCE (expensive: recorder + schema bump + recapture)

Nothing here is switchable by plumbing. Every entry needs a recorder read added in 5 Lua
recorders and 3 C# CSV writers, new columns, and a fixture recapture. Addresses below are
identical across S1, S2 and S3K unless noted, and all sit within ±20 bytes of the
`Ring_count` word the recorders already read.

| Field | ROM source | Engine source | Notes |
|---|---|---|---|
| **score** | `$FFFFFE26` (long), all three games | `GameStateManager.getScore()` | See the score/lives section. Wanted; scoped, not started. |
| **lives** | `$FFFFFE12` (byte), all three games — S1 `v_lives`, not `v_lifecount` `$FE1B` | `GameStateManager.getLives()` | See the score/lives section. The 60-frame death-path lives-write divergence is invisible to every trace lane. |
| **game over** | — | — | **Do not capture.** Derive it from lives reaching zero on both sides; a recorded flag is redundant state that then needs keeping consistent. |
| **timer** minutes/seconds/frames | `Timer` `$FFFFFE22` long (`+1` min, `+2` sec, `+3` frame) | `LevelState.getTimerFrames()` | Compared only in the engine-vs-engine desync-lite lane, never against ROM. Adjacent to `Ring_count`; would be near-free to add alongside score. |
| air / drowning countdown | player `+$2C` | player air timer | S3K records `owner_air_left` inside `AirCountdownState` for the *object*, diagnostic-only; no player column. S1 also has `v_air` at `$FFFFFE14`. |
| invincibility status | S1 `v_invinc` `$FFFFFE2D` | sprite invincibility state | Not recorded. |
| speed-shoe status | S1 `v_shoes` `$FFFFFE2E` | `sprite.hasSpeedShoes()` (formatted into the diag string only) | Not recorded. |
| shield status | S1 `v_shield` `$FFFFFE2C` | sprite shield state | Not recorded. |
| underwater | — | `sprite.isInWater()` | **Already covered** once class-1 `status_byte` is switched on: bit `0x40` of the recorded `player_status_byte` is the underwater flag. |
| player `x_radius` / `y_radius` | aux `object_near` records radii for *objects* only | sprite radii | No player-radius column. |

## Summary — what is worth switching on, cheapest first

**Dead by sentinel — do these now, one commit each with its own measured delta:**

1. **`routine` on A1 and B** — one call-site parameter each, data on both sides, highest
   diagnostic value (separates normal / hurt / death control paths).
2. **`status_byte` on A1 and B** — same shape; also brings underwater, pushing, rolling-jump
   and facing into comparison, closing the "underwater" gap without a recorder change.
3. **`x_sub` / `y_sub` on A1** — same shape; catches sub-pixel drift a whole frame before it
   becomes a position error.

**Dead by missing comparator — moderate, own commits:**

4. **`stand_on_obj`** — needs new comparator code in `TraceBinder` plus forwarding
   `standOnSlot`, but the data is recorded on every fixture for both characters.
5. **`sidekick cpu_*` on lane B** — needs the run lane to look up
   `trace.cpuStateForFrame(...)` and capture the engine CPU state; moderate plumbing.

**Dead by absence — own plan, own sequencing, not started here:**

6. **score / lives**, and **timer** as a near-free rider. Blocked on 5 Lua recorders,
   3 C# CSV writers, a schema bump and a corpus recapture. Should ride the recapture
   already queued by the recorded level-load span design rather than forcing a second one.
   Game over is derived from lives, never recorded.

Each must land as its own commit with its own measured delta. Batching several would make
the delta unattributable, which is exactly the failure mode that let the ring hole survive.
