# S3K MGZ Zone Analysis

## Summary

- **Zone:** Marble Garden Zone (MGZ)
- **Zone Index:** 0x02
- **Zone Set:** S3KL
- **Acts:** 1 and 2
- **Water:** No
- **Palette Cycling:** No (`AnPal_None` for both acts)
- **Animated Tiles:** Yes (`AniPLC_MGZ`, plus custom gating wrapper `AnimateTiles_MGZ`)
- **Character Branching:** Yes, Act 2 has separate BG move paths for Sonic/Tails and Knuckles

## Events

### Act 1 (`MGZ1_Resize`)

**Disassembly location:** `sonic3k.asm` line 39331

`MGZ1_Resize` falls through to `MGZ2_Resize` without an `rts`. The disassembly notes this as a **bug** (`FixBugs` guard): MGZ1 uses a dynamic resize routine meant for MGZ2, which can cause the Act 2 boss to spawn out of bounds in Act 1.

### Act 2 (`MGZ2_Resize`)

**Disassembly location:** `sonic3k.asm` line 39339

Three-state routine driven by `Dynamic_resize_routine`:

| State | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 | Camera Y in `$600-$700` and Camera X >= `$3A00` | Lock camera to Y=`$6A0`, max X=`$3C80`, advance | End boss arena entry |
| 2 | Camera X >= `$3C80` | Lock min X=`$3C80`, spawn `Obj_MGZEndBoss` at `($3D20, $668)`, advance | Boss spawn |
| 2 | Camera X < `$3A00` (retreat) | Reset Y bounds to `$1000`, max X to `$6000`, revert to state 0 | Player retreated, unlock |
| 4 | always | `rts` (no-op) | Boss is active, no further resize logic |

### Act 1 Screen Events (`MGZ1_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` line 106252

Simple handler:

| Step | Action | Notes |
|------|--------|-------|
| 1 | Add `Screen_shake_offset` to `Camera_Y_pos_copy` | Earthquake visual shake |
| 2 | Call `Do_ShakeSound` | Play rumble SFX every 16 frames during continuous shake |
| 3 | `DrawTilesAsYouMove` | Normal FG tile drawing |

No routine index; single path every frame.

### Act 1 Background Events (`MGZ1_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` line 106269

Two-state routine via `Events_routine_bg`:

| State | Label | Trigger | Action | Notes |
|-------|-------|---------|--------|-------|
| 0 | `MGZ1BGE_Normal` | `Events_fg_5 != 0` | Queue Act 2 chunks (`MGZ2_128x128_Secondary_Kos`), blocks (`MGZ2_16x16_Secondary_Kos`), patterns (`MGZ2_8x8_Secondary_KosM` to tile `$252`), load PLC `$14`, advance to state 4 | Seamless act transition prep |
| 0 | `MGZ1BGE_Normal` | otherwise | Run `MGZ1_Deform`, apply deformation, `ShakeScreen_Setup` | Steady-state parallax |
| 4 | `MGZ1BGE_Transition` | `Kos_modules_left != 0` | Wait for Kos queue to finish, still run deform | Stall until assets loaded |
| 4 | `MGZ1BGE_Transition` | Kos queue empty | Set `Current_zone_and_act=$201`, clear managers, call `Load_Level`/`LoadSolids`/`LoadPalette_Immediate($F)`, offset players/camera by `(-$2E00, -$600)`, reset tile offsets, clear event state | **Seamless Act 1 -> Act 2 transition** |

**Palette mutations:**
- `LoadPalette_Immediate($F)` is called during Act 1->2 transition (palette ID `$F` = MGZ palette).

### Act 2 Screen Events (`MGZ2_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` line 106385

Three-state routine via `Events_routine_fg`:

| State | Label | Trigger | Action | Notes |
|-------|-------|---------|--------|-------|
| 0 | `MGZ2SE_Normal` | `Events_fg_4 == 0` | Run `MGZ2_QuakeEvent`, `MGZ2_ChunkEvent`, `DrawTilesAsYouMove` | Normal gameplay with earthquake monitoring |
| 0 | `MGZ2SE_Normal` | `Events_fg_4 != 0` | Set continuous shake, disable shake sound flag, advance to state 4 | Collapse trigger from boss/event |
| 4 | `MGZ2SE_Collapse` | `Screen_shake_flag >= 0` (not continuous) | `MGZ2_LevelCollapse`, `DrawTilesAsYouMove` | Normal draw during early collapse |
| 4 | `MGZ2SE_Collapse` | `Screen_shake_flag < 0` (continuous) | `MGZ2_LevelCollapse`, `DrawTilesVDeform` with `MGZ2_FGVScrollArray` | Per-column FG VScroll during collapse |
| 8 | `MGZ2SE_MoveBG` | each frame | Accelerate `Events_bg+$08` up to `$50000`, apply to `Events_bg+$0C` as boss BG X scroll offset | Post-collapse boss BG scrolling |

**S3K vs S3 differences:**
- S3K `MGZ2_ScreenInit` additionally clears `Events_fg_0` and `Events_fg_2` (S3 does not).
- S3K `MGZ2_ScreenEvent` adds an `Events_fg_0` check that forces `Screen_shake_flag = $FF` (continuous) when set. S3 lacks this and relies solely on event-spawned shake.

### Act 2 Earthquake System (`MGZ2_QuakeEvent`)

**Disassembly location:** `sonic3k.asm` line 106579

Seven-state routine via `Events_bg+$10`, with three earthquake trigger regions and three continuation states:

**Trigger check (`MGZ2_QuakeEventCheck`):**

| Event | Player X Range | Player Y Range | Camera Lock | Notes |
|-------|----------------|----------------|-------------|-------|
| 1 | `$780-$7C0` | `$580-$600` | Max Y=`$5A0`, Max X=`$7E0` | First quake, camera locked rightward |
| 2 | `$31C0-$3200` | `$1C0-$280` | Max Y=`$1E0`, Min X=`$2F60` | Second quake, camera locked leftward |
| 3 | `$3440-$3480` | `$680-$700` | Max Y=`$6A0`, Min X=`$32C0` | Third quake, camera locked leftward |

Each event is one-shot: flags `Events_bg+$12/+$13/+$14` track which have already fired.

**Per-event flow:**
1. Wait for camera to reach locked boundary
2. Lock screen, set earthquake flag, spawn `Obj_MGZ2DrillingRobotnik` at fixed coordinates
3. Enable continuous screen shake (`Screen_shake_flag = $FF`, `Events_fg_0 = $FF`)
4. Advance to continuation state which waits for player to pass a threshold, then resets bounds

**Drilling Robotnik spawn coordinates:**

| Event | X | Y | Facing |
|-------|---|---|--------|
| 1 | `$8E0` | `$690` | Right (default) |
| 2 | `$2FA0` | `$2D0` | Left (bit 0 set) |
| 3 | `$3300` | `$790` | Left (bit 0 set) |

### Act 2 Chunk Events (`MGZ2_ChunkEvent`)

**Disassembly location:** `sonic3k.asm` line 106791

Five-state routine via `Events_bg+$04`. Performs layout tile replacement during earthquakes using `MGZ2_QuakeChunks` data ($1080 bytes). Three trigger regions in `MGZ2_ChunkEventArray`:

| Event | Player X Range | Player Y Range | Redraw Origin |
|-------|----------------|----------------|---------------|
| 1 | `$F68-$F78` | `$500-$580` | `($F00, $500)` |
| 2 | `$3680-$3700` | `$2F0-$380` | `($3700, $280)` |
| 3 | `$3000-$3080` | `$770-$800` | `($3080, $700)` |

Event 1 additionally requires `Screen_shake_flag < 0` (continuous shaking active).

Chunk replacement happens once every 7 frames, modifying two 128x128 chunks at addresses `$FF5880` and `$FF7500`. Screen rows are redrawn using `MGZ2_ScreenRedrawArray` (23 pairs of Y-offset + row-count). After 46 steps (`$5C`), shaking stops and screen events are cleared.

### Act 2 Level Collapse (`MGZ2_LevelCollapse`)

**Disassembly location:** `sonic3k.asm` line 106450

Triggered when `Events_fg_4` is set (by Drilling Robotnik boss defeat):

1. Clears 3 chunks on 3 lines at Y=`$700`, X=`$3C80` to create empty space
2. Sets up 10 per-column VScroll regions in `HScroll_table+$100` area
3. Spawns `Obj_MGZ2LevelCollapseSolid` pairs (20 total) as invisible solid platforms the player can stand on during collapse
4. Activates `Special_V_int_routine = 4` for custom VScroll VBlank handling
5. Each column has a staggered delay from `MGZ2_CollapseScrollDelay`: `$A, $10, 2, 8, $E, 6, 0, $C, $12, 4`
6. Columns accelerate at `$500` per frame, capped at VScroll `$2E0`
7. Plays `sfx_BigRumble` every 16 frames
8. When all columns reach max scroll, clears remaining chunks, stops shaking, sets `Special_V_int_routine = $C`, advances to `MGZ2SE_MoveBG`

### Act 2 Background Events (`MGZ2_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` line 107029

Three-state routine via `Events_routine_bg`:

| State | Label | Trigger | Action | Notes |
|-------|-------|---------|--------|-------|
| 0 | `MGZ2BGE_GoRefresh` | entry | Clear bottom BG, clear HScroll, advance | One-shot init |
| 4 | `MGZ2BGE_Normal` | BG event triggered | Deform, refresh, go to refresh state | BG event changes require full BG redraw |
| 4 | `MGZ2BGE_Normal` | no trigger | Deform, draw BG, apply deformation, FGVScroll, shake setup, check BG collision release | Steady state |
| 8 | `MGZ2BGE_Refresh` | refresh loop | Bottom-up plane redraw using `MGZ2_BGDrawArray`, revert to state 4 when done | Transition refresh |

**BG Draw Array:** `$200, $7FFF` (single 512-pixel tall band).

**Background collision:** `Background_collision_flag` is toggled by `MGZ2_BGEventTrigger` depending on player position. When active, `Go_CheckPlayerRelease` runs to handle player interaction with the BG plane.

### Act 2 BG Event Triggers (`MGZ2_BGEventTrigger`)

**Disassembly location:** `sonic3k.asm` line 107117

Four-state routine via `Events_bg+$00`, managing the "background rises up" mechanic:

| State | Description | Transition Conditions |
|-------|-------------|----------------------|
| 0 | Normal | Player Y `$80-$180` and X >= `$3800`: spawn `Obj_MGZ2BGMoveKnux`, go to 4. Player Y `$800-$900` and X >= `$34C0`: spawn `Obj_MGZ2BGMoveSonic`, go to 8. |
| 4 | Knuckles BG move | BG collision ON. If Y < `$80` or Y >= `$180` or X >= `$3800`: return to 0. If Y < `$100` and X >= `$3C00`: BG collision OFF. |
| 8 | Sonic BG move | BG collision ON. If Y < `$800` and X >= `$3900`: turn off cloud movement, go to 12. If Y >= `$900` or X >= `$34C0`: return to 0. |
| 12 | After BG move | BG collision OFF. If Y >= `$800` and X >= `$3A40`: go back to 8. |

**BG Move Objects:**
- `Obj_MGZ2BGMoveKnux`: Y threshold `$400`, X threshold `$38A0`, target BG offset `$220`, max X `$6000`
- `Obj_MGZ2BGMoveSonic`: Y threshold `$A80`, X threshold `$36D0`, target BG offset `$1D0`, max X `$6000`

Both objects lock `Camera_min_X_pos` when triggered, accelerate `Events_bg+$02` (BG Y offset), move both players' Y positions, and play `sfx_Crash` + set timed shake (`$E` frames) on completion.

## Boss Sequences

### Act 1 Miniboss: Tunnelbot

- **Object:** `Obj_MGZMiniboss` / `Obj_Tunnelbot` at line `184811`
- **Spawn trigger:** dormant until `Check_CameraInRange` sees the camera inside Y `$D20-$EC0` and X `$2B80-$3080` (`word_8859C`)
- **Arena setup:** saves the current camera bounds, hard-clamps `Camera_max_X_pos` to `$2E00`, and raises `Camera_target_max_Y_pos` to `$E10`; `Boss_flag` is set before the fight starts
- **PLC / palette ownership:** `Load_PLC_Raw(PLC_MGZMiniboss)` loads `ArtTile_MGZMiniboss` plus `ArtTile_MGZMiniBossDebris`; there is no dedicated palette swap, so the fight stays on the main MGZ palette and uses the shared hit helper to flash palette line 2 during damage
- **Child objects:** creates the initial two-piece support set via `ChildObjDat_88B2C`, then uses the shared `sub_88A62` helper for the hit flash and defeat explosion flow
- **Defeat behavior:** `sub_88A62` clears `Screen_shake_flag`, spawns `Child6_CreateBossExplosion`, and drops into `BossDefeated_StopTimer`; the boss then sets `Events_fg_5`, which is the real handoff that starts the Act 1 -> Act 2 seamless reload in `MGZ1_BackgroundEvent`
- **Implementation note:** the visible fight uses shared boss-hit logic, but the act transition is owned by the level event system, not by the miniboss object itself
- **Confidence:** HIGH

### Act 2 Repeated Drilling Robotnik Encounters

- **Object:** `Obj_MGZ2DrillingRobotnik` at line `142384`
- **Spawn trigger:** `MGZ2_QuakeEvent1/2/3` spawn the same object at three fixed points once the camera reaches each locked arena window; the event routines only place the object and start the shake, they do not own the art
- **Encounter list:**
  - Event 1: spawn at `($8E0, $690)` facing right
  - Event 2: spawn at `($2FA0, $2D0)` facing left
  - Event 3: spawn at `($3300, $790)` facing left
- **Shared setup:** sets `Boss_flag = 1`, plays `cmd_FadeOut`, queues `ArtKosM_MGZEndBoss` at `ArtTile_MGZEndBoss`, queues `ArtKosM_MGZEndBossDebris` at `ArtTile_MGZEndBossDebris`, loads PLC `$6D`, and loads `Pal_MGZEndBoss` to palette line 1
- **Music handoff:** after a 120-frame wait, `Obj_MGZ2DrillingRobotnikGo` switches to `mus_EndBoss`
- **Child objects:** the encounter builds its visible drill body from the shared `Child1_MakeRoboShip3` path plus the internal drill/segment child lists under `ObjDat_MGZDrillBoss`
- **Defeat behavior:** `MGZ2_SpecialCheckHit` drives the hit counter; when the boss is beaten, the object sets `Events_fg_4`, sets `Disable_death_plane`, plays `sfx_BossHitFloor`, and spawns `Obj_MGZ2_BossTransition`
- **Ownership note:** the same art/palette bundle is reused for all three drill encounters and the later final boss, so the first spawn preloads the resources for the whole MGZ endgame
- **Confidence:** HIGH

### Act 2 Boss Transition

- **Object:** `Obj_MGZ2_BossTransition` at line `30200`
- **Spawn trigger:** created only by `Obj_MGZ2DrillingRobotnik` when `Events_fg_4` is set
- **Role:** invisible logic-only helper that keeps the player inside the collapse corridor while the level hands off from the drill encounter to the boss-collapse sequence
- **Non-Knuckles path:** if `Player_mode != 2`, the object anchors itself at `Camera_X + $40` and `Camera_Y + $100`, respawns/refreshes `Player_2` as `Obj_Tails` when needed, sets `Tails_CPU_routine = $12`, and clears `Flying_carrying_Sonic_flag`
- **Timer behavior:** holds a `$168`-frame countdown before letting the carry-up sequence progress; while active, it zeroes Sonic's velocities and clamps him to the helper object's Y when he would drift below it
- **Knuckles path:** `Player_mode == 2` skips the Tails carry logic and only uses the helper as a camera/player clamp
- **Ownership:** no art load, no PLC, no palette mutation; this object is pure event orchestration and should stay separate from the visible boss renderer
- **Confidence:** HIGH

### Act 2 Final Boss: `Obj_MGZEndBoss`

- **Object:** `Obj_MGZEndBoss` at line `142715`
- **Spawn trigger:** `MGZ2_Resize` state 2 spawns it at `($3D20, $668)` once the camera reaches the locked end-arena threshold at X `$3C80`
- **Setup:** uses `ObjDat_MGZDrillBoss`, sets `Boss_flag = 1`, `collision_property = 8`, `y_radius = $1C`, and `angle = $C`, then plays `cmd_FadeOut` and waits 120 frames before switching to `mus_EndBoss`
- **PLC / palette ownership:** reuses the same art bundle as the drill encounters (`ArtKosM_MGZEndBoss` + `ArtKosM_MGZEndBossDebris`, PLC `$6D`, `Pal_MGZEndBoss` on palette line 1)
- **Child objects:** spawns `Child1_MakeRoboShip3` plus the drill body child set in `ChildObjDat_6D7C0`; later phases swap in additional child arrays for the drill, debris, and scaled-art flight section
- **State flow:** the routine table is 17 entries deep, with repeated swing/drill states, hit-driven collapse beats, and a later horizontal escape section that switches to the scaled art object data (`ObjDat3_6D7B4` / `ArtTile_MGZEndBossScaled`)
- **Defeat behavior:** the ground-hit branch sets `Events_fg_4`, sets `Disable_death_plane`, plays `sfx_BossHitFloor`, and spawns `Obj_MGZ2_BossTransition`; the post-battle cleanup then clears `Boss_flag`, spawns the Egg Capsule, restores control/music, and rolls into the CNZ fade object that copies `Pal_MGZFadeCNZ` into palette line 4 before calling `StartNewLevel($300)`
- **Implementation note:** this is the point where the boss fight stops being a pure object battle and becomes level-end orchestration. The visible boss renderer, the invisible carry helper, and the zone transition object are three separate responsibilities
- **Confidence:** HIGH

### Act 2 Final Boss: `Obj_MGZEndBossKnux`

- **Object:** `Obj_MGZEndBossKnux` at line `142993`
- **Spawn trigger:** gated by `Check_CameraInRange(word_6C688)` with a much narrower camera window than the Sonic/Tails path
- **Setup differences:** stores `boss_saved_mus = mus_Miniboss` instead of `mus_EndBoss`, then reuses the same art/palette bundle and PLC `$6D`
- **State flow:** shorter 8-state variant that skips the long Sonic/Tails handoff path and uses the same `loc_6D61E` hit helper for damage and defeat processing
- **Transition behavior:** does not use the Tails-carry helper; it short-circuits into the end-of-zone transition path directly after the fight resolves
- **Confidence:** HIGH on assets and trigger, MEDIUM on the exact mid-fight motion sequence

## Parallax

### Act 1 Deform (`MGZ1_Deform`)

**Disassembly location:** `s3.asm` line 72161

**Key formulas:**
- BG Y = `Screen_shake_offset` (no camera-relative Y scrolling; BG stays at top)
- BG X base = `Camera_X_pos_copy / 4`, with `1/16` gradient reduction per band
- Writes 14 horizontal scroll values into `HScroll_table[0..13]`
- First 9 values: X decreases by gradient stepping backward from `HScroll_table+$01C`
- Next 5 values: additional parallax with cumulative velocity `$500` per band
- Final two values are swapped (adjacent bands exchanged for visual effect)

**Deformation array (`MGZ1_BGDeformArray`):**
```
$10, 4, 4, 8, 8, 8, $D, $13, 8, 8, 8, 8, $18, $7FFF
```
13 bands of pixel heights before the terminator. Total BG height: `$10+4+4+8+8+8+$D+$13+8+8+8+8+$18 = $A8` (168 pixels).

**Confidence:** HIGH

### Act 2 Deform (`MGZ2_BGDeform`)

**Disassembly location:** `s3.asm` line 72971

Four-state routine via `Events_bg+$00`, matching the BG event trigger states:

**State 0 (Normal):**
- BG Y = `(cameraY - Screen_shake_offset) * 3/16 + Screen_shake_offset` (approximately 3/16ths parallax ratio)
- BG X = 0 (no horizontal offset)
- Additional band deformation via `MGZ2_BGDeformIndex` (15 entries, interleaved order) and `MGZ2_BGDeformOffset` (23 signed pixel offsets)
- Cloud auto-scroll: `HScroll_table+$038 += $800` per frame (unless `Events_bg+$0E` disables it)

**States 4/8 (BG Move events):**
- BG Y = `cameraY - threshold + Events_bg+$02` (threshold is `$1E0` for Knuckles, `$8F0` for Sonic)
- BG X = `cameraX - X_threshold` (threshold is `$3580` for Knuckles, `$3200` for Sonic)
- Direct 1:1 BG X/Y tracking during the "BG rises up" sequence

**State 12 (After BG Move):**
- BG Y = `(cameraY - $500) * 3/16` (fixed offset, same 3/16 ratio)
- BG X = 0

**Boss override:** When `Events_routine_fg == 8` (boss phase), BG X uses `Events_bg+$0C` instead of camera X.

**Deformation array (`MGZ2_BGDeformArray`):**
```
$10, $10, $10, $10, $10, $18, 8, $10, 8, 8, $10, 8, 8, 8, 5, $2B, $C, 6, 6, 8, 8, $18, $D8, $7FFF
```
23 bands. Total BG height: `$10*5+$18+8+$10+8+8+$10+8+8+8+5+$2B+$C+6+6+8+8+$18+$D8 = $200` (512 pixels).

**Deform index array (`MGZ2_BGDeformIndex`):**
```
$1C, $18, $1A, $C, 6, $14, 2, $10, $16, $12, $A, 0, 8, 4, $E
```
15 entries: byte offsets into `HScroll_table+$008`, defining non-sequential write order for parallax bands (creates the interleaved layer effect).

**Deform offset array (`MGZ2_BGDeformOffset`):**
```
-5, -8, 9, $A, 2, -$C, 3, $10, -1, $D, -$F, 6, -$B, -4, $E, -8, $10, 8, 0, -8, $10, 8, 0
```
23 signed word offsets added to the computed scroll positions, creating static parallax displacement per band.

**BG Draw Array (`MGZ2_BGDrawArray`):** `$200, $7FFF` (single 512px band).

**FG VScroll Array (`MGZ2_FGVScrollArray`):** `$3CA0, $20, $20, $20, $20, $20, $20, $20, $20, $7FFF` - used during level collapse for per-column vertical scroll. First entry `$3CA0` is the X start position; subsequent `$20` entries are column widths (32 pixels each, 8 columns).

**Confidence:** HIGH

## Animated Tiles

### Custom Wrapper (`AnimateTiles_MGZ`)

**Disassembly location:** `sonic3k.asm` line 54362

```asm
AnimateTiles_MGZ:
    tst.b  (Boss_flag).w
    beq.w  AnimateTiles_DoAniPLC
    rts
```

Gates tile animation on `Boss_flag`: when a boss is active, animated tiles are **disabled** (returns immediately). Otherwise, falls through to the standard `AnimateTiles_DoAniPLC` processor.

Both acts use the same handler.

### `AniPLC_MGZ`

**Disassembly location:** `sonic3k.asm` line 55699

| Script | Art Source | Destination Tile | Frame Count | Frame Size (bytes) | Duration | Notes |
|--------|-----------|------------------|-------------|--------------------|---------|----|
| 0 | `ArtUnc_AniMGZ__0` | `$222` | 6 | `$30` tiles = `$600` bytes | 9 frames | Waterfall/lava flow (9216 bytes total = 6 frames x $600) |
| 1 | `ArtUnc_AniMGZ__1` | `$252` | 4 | 1 tile = `$20` bytes | variable | Small blinking/shimmer (96 bytes total), uses duration-per-frame format |

**Script 0 details:**
- Duration: 9 frames between each frame
- Frame offsets: `$00, $30, $60, $90, $C0, $F0` (tile offsets from art base)
- Total art size: `$2400` bytes (9216 bytes, confirmed by file size)

**Script 1 details:**
- Uses the `zoneanimdecl -1` format (variable duration per frame)
- Frame/duration pairs: frame 0 for 7 ticks, frame 1 for 14 ticks, frame 2 for 7 ticks, frame 1 for 14 ticks
- Total art size: `$60` bytes (96 bytes, 4 frames of 1 tile)

**Art files:**
- `Levels/MGZ/Animated Tiles/MGZ Animated 0.bin` (9216 bytes)
- `Levels/MGZ/Animated Tiles/MGZ Animated 1.bin` (96 bytes)

**Confidence:** HIGH

## Palette Cycling

MGZ has **no palette cycling**. Both `MGZ1` and `MGZ2` map to `AnPal_None` (`rts`) in the `OffsAnPal` dispatch table (lines 3123-3124).

**Palette data:**
- `Pal_MGZ` (`Levels/MGZ/Palettes/Main.bin`, 96 bytes = 3 palette lines): loaded to `Normal_palette_line_2` for both acts
- `Pal_MGZEndBoss` (`Levels/MGZ/Palettes/End Boss.bin`, 32 bytes): loaded to palette line 1 during boss encounters
- `Pal_MGZFadeCNZ` (`Levels/MGZ/Palettes/Fade to CNZ.bin`, 512 bytes): 16-step palette fade from MGZ to CNZ colors, applied to palette line 4 during zone transition

## Notable Objects

| Object ID (S3KL) | Name | Description | Zone-Specific? | Notes |
|-------------------|------|-------------|----------------|-------|
| `$20` (`32`) | `MGZLBZSmashingPillar` | Smashing pillar (shared MGZ/LBZ) | Shared | Also ID `$52` (`82`) |
| `$50` (`80`) | `MGZTwistingLoop` | Twisting loop path handler | Yes | |
| `$51` (`81`) | `FloatingPlatform` | Floating platform | Shared | Uses `Map_MGZFloatingPlatform` in MGZ |
| `$53` (`83`) | `MGZSwingingPlatform` | Swinging platform with chain | Yes | Spawns chain child |
| `$55` (`85`) | `MGZHeadTrigger` | Arrow-head trigger that activates on proximity | Yes | Animated via `Ani_MGZHeadTrigger` |
| `$56` (`86`) | `MGZMovingSpikePlatform` | Platform with spikes that moves | Yes | |
| `$57` (`87`) | `MGZTriggerPlatform` | Platform triggered by head trigger | Yes | |
| `$58` (`88`) | `MGZSwingingSpikeBall` | Swinging spike ball on chain | Yes | Spawns chain segments as children |
| `$59` (`89`) | `MGZDashTrigger` | Speed boost arrow trigger | Yes | Uses `ArtTile_MGZMisc1` |
| `$5A` (`90`) | `MGZPulley` | Pulley rope mechanism | Yes | |
| `$5B` (`91`) | `MGZTopPlatform` | Top-mounted platform | Yes | |
| `$5C` (`92`) | `MGZTopLauncher` | Top launcher (catapult) | Yes | Spawns `Obj_MGZTopPlatform` children |
| `$0D` (`13`) | `BreakableWall` | Breakable wall (shared) | Shared | Uses `Map_MGZBreakableWall` in MGZ |
| `$0F` (`15`) | `CollapsingBridge` | Collapsing bridge (shared) | Shared | Uses `Map_MGZCollapsingBridge` in MGZ |
| `$07` | `Spring` | Spring (shared) | Shared | Diagonal variant uses `ArtTile_MGZMHZDiagonalSpring` |
| `$2F` (`47`) | `StillSprite` | Direction signposts | Shared | Uses `ArtTile_MGZSigns`, subtypes `$B-$E` |
| `$34` (`52`) | `StarPost` | Checkpoint | Shared | Standard |

**Badniks (via PLCs):**

| Badnik | PLC Entry | Acts | Art Tile |
|--------|-----------|------|----------|
| Spiker | `PLCKosM_MGZ1`, `PLCKosM_MGZ2` | Both | `$0530` |
| Mantis | `PLCKosM_MGZ2` | Act 2 only | `$054F` |
| Tunnelbot (Miniboss) | `PLCKosM_MGZ1` | Act 1 only | `$054F` (`ArtTile_MGZMiniboss`) |

**Also present in debug list:** `Obj_BubblesBadnik` (shared badnik).

## Cross-Cutting Concerns

- **Screen shake:** Central to MGZ's identity. Both acts apply `Screen_shake_offset` to `Camera_Y_pos_copy` in their screen events. `ShakeScreen_Setup` drives the offset: timed shakes decay via `ScreenShakeArray`, continuous shakes (`Screen_shake_flag < 0`) cycle via `ScreenShakeArray2`. `Do_ShakeSound` plays `sfx_Rumble2` every 16 frames during continuous shake.
- **Act transition:** Act 1 -> Act 2 is a **seamless transition** implemented in `MGZ1_BackgroundEvent`, not via a fade/reload. Art/blocks/chunks are queued via Kos, then the level is reloaded with world coordinates offset by `(-$2E00, -$600)`.
- **Zone transition:** MGZ2 -> CNZ1 is handled by `Obj_MGZEndBoss` via `Pal_MGZFadeCNZ` (16-step palette fade on line 4) followed by `StartNewLevel($300)`.
- **Boss flag gating:** `AnimateTiles_MGZ` disables tile animation when `Boss_flag` is set. This prevents waterfall animation during boss encounters.
- **Background collision:** Act 2 uses `Background_collision_flag` to enable player collision with the BG plane during the "BG rises up" sequences. `Go_CheckPlayerRelease` handles the collision release check.
- **Dynamic layout modification:** Act 2 earthquake chunk events modify level layout at runtime using `MGZ2_QuakeChunks` data, with progressive chunk replacement every 7 frames.
- **Level collapse VScroll:** The Act 2 collapse uses a custom `Special_V_int_routine` (values `4` and `$C`) for per-column vertical scrolling, with solid invisible platforms (`Obj_MGZ2LevelCollapseSolid`) providing footing during the collapse.
- **Character paths:** Knuckles and Sonic/Tails have separate BG move sequences in Act 2 (different Y/X thresholds and target BG offsets). The `Obj_MGZ2_BossTransition` has explicit Knuckles-only logic (skip Tails carry).
- **Bug note:** `MGZ1_Resize` falls through to `MGZ2_Resize` without `rts` (guarded by `FixBugs`). In vanilla ROM, the end boss can erroneously spawn in Act 1.
- **PLC loading:** `PLCKosM_MGZ1` loads Spiker + Miniboss + MinibossDebris, while `Obj_MGZMiniboss` separately raw-loads `PLC_MGZMiniboss` for the spire/boss art. `PLCKosM_MGZ2` loads Spiker + Mantis. PLC `$14` is loaded during act transition. PLC `$6D` is loaded by both Drilling Robotnik and End Boss objects.

## Implementation Notes

### Priority Order
1. Events (screen + background) for both acts, because MGZ orchestration is event-centric with earthquake/collapse/BG-move logic
2. Act 1 parallax (`MGZ1_Deform`) -- simple, single-path deform
3. Act 2 parallax (`MGZ2_BGDeform`) -- complex, four-state deform with BG move and boss override
4. Animated tiles (`AniPLC_MGZ` + boss flag gating)
5. Act 1 -> Act 2 seamless transition
6. Act 2 earthquake and level collapse systems
7. Boss/object surface in a follow-up phase

### Dependencies
- `MGZ2_QuakeEvent` and `MGZ2_ChunkEvent` must share `Events_bg` state with the screen event handler.
- `MGZ2_BGDeform` must read `Events_bg+$00/+$02/+$0C/+$0E` set by `MGZ2_BGEventTrigger` and the BG move objects.
- The level collapse requires `Special_V_int_routine` support for per-column VScroll.
- `AnimateTiles_MGZ` depends on `Boss_flag` from the boss spawn system.
- Act 2 BG collision needs `Background_collision_flag` and `Go_CheckPlayerRelease` support.

### Known Risks
- The level collapse system uses custom VBlank routines (`Special_V_int_routine` 4 and 12) and per-column FG VScroll, which may need engine-level support.
- The earthquake chunk replacement modifies level layout at runtime, requiring `MutableLevel` support.
- The BGMove objects directly modify player Y positions (`sub.w d1, Player_1+y_pos`), which must coordinate with physics.
- Three Drilling Robotnik encounters + End Boss + Boss Transition = complex multi-boss orchestration.
- The S3K-specific additions to `MGZ2_ScreenEvent` (continuous shake forcing) may need to be gated by S3K vs S3 detection if standalone S3 is ever supported.
