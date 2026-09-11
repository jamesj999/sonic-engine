# S3K DEZ Zone Analysis

## Summary

- **Zone:** Death Egg Zone (DEZ)
- **Zone Index:** 0x0A (acts 1 and 2), 0x17 (act 3 / Final Boss)
- **Zone Set:** SKL (zones 7-13: MHZ-DDZ)
- **Acts:** 1, 2, and 3 (Final Boss arena, zone $17 act 0)
- **Water:** No
- **Palette Cycling:** Yes (1 DEZ1-only channel + 2 shared DEZ1/DEZ2 channels = 3 channels total)
- **Animated Tiles:** Yes (8 AniPLC scripts, shared between Act 1 and Act 2)
- **Character Branching:** No -- Sonic/Tails and Knuckles share the same start positions, event routines, and layout. Knuckles does not reach Act 3 (goes to Mecha Sonic boss in Sky Sanctuary instead).
- **Parallax:** Minimal -- both acts use `PlainDeformation` (flat BG scroll with no multi-layer deformation). Act 3 has a custom deform with `ApplyDeformation2` and `ShakeScreen_Setup`.
- **Seamless Transition:** Yes -- Act 1 transitions seamlessly to Act 2 via `DEZ1_BackgroundEvent`.
- **Unique Mechanics:** Reverse gravity (`Reverse_gravity_flag`), light tunnel transport, gravity switching objects, teleporters, hover machines, conveyor belts/pads, energy bridges, bumper walls, gravity puzzles.

## Events

### Event System Architecture

DEZ uses the SK-era Screen/Background Event system (NOT the S3 `Dynamic_resize_routine` state machine). Events are dispatched through the `ScreenInit`/`ScreenEvent`/`BackgroundInit`/`BackgroundEvent` function pointer table at `sonic3k.asm` lines 102298-102301 (Acts 1-2) and 102346-102348 (Act 3).

### Act 1 (DEZ1_ScreenEvent / DEZ1_BackgroundEvent)

#### DEZ1_ScreenInit (line 118618)

Simple initialization:
1. Calls `Reset_TileOffsetPositionActual`
2. Calls `Refresh_PlaneFull`

No special state setup.

#### DEZ1_ScreenEvent (line 118623)

**Not a state machine** -- single conditional check each frame:

| Condition | Action |
|-----------|--------|
| `Events_fg_4 != 0` | Clears `Events_fg_4`. Reads chunk address from `$14(a3)` (level layout pointer), writes `$BD` to byte offset `$6E` of that chunk. Calls `Refresh_PlaneScreenDirect` to update visuals. |
| Otherwise | Calls `DrawTilesAsYouMove` (standard tile drawing). |

This is a one-shot chunk modification triggered by an object setting `Events_fg_4`. The modification writes chunk ID `$BD` at a specific layout offset, likely opening a path or revealing a passage.

**Confidence:** HIGH

#### DEZ1_BackgroundInit (line 118636)

1. Clears `Camera_X_pos_BG_copy` and `Camera_Y_pos_BG_copy` to 0
2. Calls `Reset_TileOffsetPositionEff`
3. Calls `Refresh_PlaneFull`
4. Calls `PlainDeformation`

Sets `Events_routine_bg` to 0 (implicit, starts at first state).

#### DEZ1_BackgroundEvent (line 118644)

**State machine (Events_routine_bg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | `Events_fg_5 != 0` | Clears `Events_fg_5`. Queues DEZ2 secondary 16x16 blocks (Kos, dest `Block_table+$15E0`). Queues DEZ2 secondary 8x8 patterns (KosinskiM at VRAM tile $292). Loads PLC $38 (DEZ2 misc art: `ArtNem_DEZMisc` at `ArtTile_DEZMisc`, `ArtNem_DEZ2Extra` at `ArtTile_DEZ2Extra`). Advances state. | Prepares art resources for Act 2 seamless transition. |
| 1 | $04 | `Kos_modules_left == 0` (art loading complete) | Sets `Current_zone_and_act = $B01` (DEZ Act 2). Clears `Dynamic_resize_routine`, `Object_load_routine`, `Rings_manager_routine`, `Boss_flag`, `Respawn_table_keep`. Calls `Clear_Switches`, `Load_Level`, `LoadSolids`. Copies `Pal_DEZ2+$20` palette data (64 bytes) to `Normal_palette_line_3` (palette lines 2-3). Offsets player and camera positions: X -= $3600, Y -= (-$400) = Y += $400. Calls `Offset_ObjectsDuringTransition`. Adjusts camera bounds accordingly. Resets `Events_routine_bg` to 0. | Full seamless level transition to Act 2. |
| Both | -- | Always | Calls `PlainDeformation` at end of every frame. | Flat BG scroll. |

**Position offset during transition:**
- X offset: subtract $3600 from all player/camera/bounds X positions
- Y offset: subtract -$400 (i.e., add $400) from all player/camera/bounds Y positions

After transition, `Camera_max_Y_pos` is copied to `Camera_target_max_Y_pos`, and tile offsets are reset.

**Confidence:** HIGH

### Act 2 (DEZ2_ScreenEvent / DEZ2_BackgroundEvent)

#### DEZ2_ScreenInit (line 118719)

1. Sets `Events_routine_fg = 4` (starts at stage 1 in the screen event index)
2. Calls `Reset_TileOffsetPositionActual`
3. Calls `Refresh_PlaneFull`

Starting at stage 1 (offset 4) means the first chunk modification opportunity is `loc_594DA` (stage 1).

#### DEZ2_ScreenEvent (line 118725)

**State machine (Events_routine_fg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | `Events_fg_4 != 0` | Clears `Events_fg_4`. Reads chunk address from `$38(a3)`. Writes 3 consecutive chunk IDs at offset +1: `$D7`, `$DC`, `$D7`. Advances state (+4). Calls `Refresh_PlaneScreenDirect`. | Modifies a 3-chunk sequence in the level layout -- likely opening/closing a door or platform section. |
| 1 | $04 | `Events_fg_4 != 0` | Clears `Events_fg_4`. Reads chunk address from `$18(a3)`. Writes chunk ID `$BC` at byte offset `$6B` of that chunk. Advances state (+4). Calls `Refresh_PlaneScreenDirect`. | Second one-shot chunk modification. |
| 2 | $08 | Always | Calls `DrawTilesAsYouMove`. | Terminal state -- normal tile drawing. |

**Note:** `DEZ2_ScreenInit` sets `Events_routine_fg = 4`, which means stage 0 (offset $00) is only reachable during a seamless transition from Act 1 where `Events_routine_fg` starts at 0.

**Confidence:** HIGH

#### DEZ2_BackgroundInit (line 118765)

1. Sets `Events_routine_bg = 8` (starts at stage 2 / terminal idle state)
2. Clears `Camera_X_pos_BG_copy` and `Camera_Y_pos_BG_copy` to 0
3. Calls `Reset_TileOffsetPositionEff`, `Refresh_PlaneFull`, `PlainDeformation`

#### DEZ2_BackgroundEvent (line 118774)

**State machine (Events_routine_bg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Always | Clears BG camera copies to 0. Calls `Reset_TileOffsetPositionEff`. Sets up delayed plane drawing: `Draw_delayed_position = d0 + $E0` (masked), `Draw_delayed_rowcount = $F`. Advances state. | Only reached during seamless transition from Act 1 (init sets stage to 2). Sets up BG plane reconstruction. |
| 1 | $04 | `Draw_PlaneVertBottomUp` returns negative (drawing complete) | Advances state. | Bottom-up plane drawing in progress. |
| 2 | $08 | Always | Calls `PlainDeformation`. | Terminal state -- flat BG scroll. |

**Confidence:** HIGH

### Act 3 / Final Boss (DEZ3_ScreenEvent / DEZ3_BackgroundEvent)

DEZ3 is the Sonic/Tails final boss arena (zone $17 act 0). It is reached when `Obj_DEZEndBoss` defeat triggers `StartNewLevel` with `d0 = $1700`. Ring count and timer are preserved via `Act3_ring_count` / `Act3_timer`.

#### DEZ3_ScreenInit (line 120240)

Complex initialization:
1. Clears `Events_bg+$10` and sets `Events_bg+$12 = $FF`
2. Calls `sub_5A79E` (laser art DMA update based on `Events_bg+$10`)
3. Copies initial camera X to multiple scroll registers (`$10(a3)`, `$12(a3)`, `$7C(a3)`, `$7E(a3)`)
4. Clears BG plane layout: zeros out `$1000 - offset` bytes of `Level_layout_main` BG region
5. Spawns 3 objects:
   - `Obj_5A7C8` (via `AllocateObject`) -- arena platform/wall object
   - `Obj_5A8E6` (via `CreateNewSprite4`) -- arena visual element
   - `Obj_DEZ3_Boss` (via `CreateNewSprite4`) -- Final Boss object, positioned at ($3C0, $F8). Boss position stored in `Events_bg+$02` / `Events_bg+$04`.
6. Sets `Events_bg+$00 = $6C0` (arena boundary/state variable)
7. Locks scrolling: `Scroll_lock = $FF`
8. Sets camera X to $80, stores in `Events_bg+$16`
9. Calls `sub_5A508` (scroll position setup), `Reset_TileOffsetPositionActual`
10. Sets up HScroll values (clears entries 0-1, copies entry 2->3 with alignment)
11. Draws BG using `DEZ3_BGDrawArray` via `Refresh_PlaneTileDeform`

**DEZ3_BGDrawArray:** `$E0, $7FFF` -- single band of 224 pixels (full screen height), then terminator.

#### DEZ3_ScreenEvent (line 120300)

**State machine (Events_routine_fg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Always | Transfers `Act3_ring_count` to `Ring_count` if non-zero (with HUD update flag). Transfers `Act3_timer` to `Timer` if non-zero (with HUD update flag). Advances state. | Restores preserved rings/timer from DEZ2. |
| 1 | $04 | Always | Calls `sub_5A508` (scroll position update). Draws BG using `DEZ3_BGDrawArray` via `Draw_BGNoVert`. Checks `Events_bg+$06` for pending tile row draws (draws 2 rows at offset if flagged). | Main loop -- handles arena scrolling and dynamic tile updates. |

**sub_5A508 (line 120358):** Updates camera Y copy to `$20 + Screen_shake_offset`. Copies `Camera_X_pos_copy` to `HScroll_table+$00A`, and its `$1FF`-masked value to `HScroll_table+$004`. This provides the arena's horizontal scroll with a wrap-around at $200 for the circular arena effect.

**Confidence:** HIGH

#### DEZ3_BackgroundInit (line 120374)

1. Uses `Level_layout_main` (FG layout) as the plane source with `VRAM_Plane_A_Name_Table` -- note: DEZ3 renders FG through the background event, swapping the normal plane assignment.
2. Calls `sub_5A76C` (camera-relative BG position computation)
3. Calls `Reset_TileOffsetPositionEff`, `Refresh_PlaneFull`
4. Falls through to `loc_5A734` (deformation and VScroll setup)

#### DEZ3_BackgroundEvent (line 120383)

**State machine (Events_routine_bg) -- 9 stages:**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | `Events_fg_5 != 0` | Clears `Events_fg_5`. Modifies chunk at layout offset `8(a3)`: sets byte $0E to $1A. Refreshes plane area ($15 = 21 rows). Advances state. | Arena layout modification -- likely opening/expanding the boss area. |
| 1 | $04 | `Screen_shake_flag != 0` | Spawns `Obj_5A922` with params ($13, $2D0). Sets `Events_bg+$16 = $2C0`. Advances state. | Screen shake triggered by boss -- spawns debris/effect object. |
| 2 | $08 | `Events_bg+$00 != $6C0` | Calls `Reset_TileOffsetPositionEff`. Sets up delayed draw at row $F0 with $F rows. Records `_unkEE8E` to `Events_bg+$0C`. Advances state. | Arena boundary changed from initial $6C0 -- triggers BG redraw. |
| 3 | $0C | `Draw_PlaneVertBottomUp` complete (negative return) | Clears `Events_bg+$0C`. Advances state. | BG plane reconstruction in progress. |
| 4 | $10 | `Events_bg+$00 != $2C0` | Sets up delayed draw at row $F0 with $F rows. Spawns `Obj_5A94C` with position based on camera X (aligned to $20). Sets `Events_bg+$16` = aligned camera X. Advances state. When `Events_bg+$00 == $2C0`, jumps to stage 7 (sets up final redraw). | Arena shrinking to $2C0. Spawns additional object during arena change. |
| 5 | $14 | `Events_bg+$00 != 0` AND `Events_fg_5 != 0` | Clears `Events_fg_5`. Dispatches via `Events_bg+$0A` sub-index (4 states cycling through chunk modifications). Writes chunk modification bytes to layout. Draws tile rows across the arena area. | Progressive arena chunk modifications during boss fight. Sub-index cycles through: ($603, $903, $603, $807) -- pairs of chunk IDs for two layout planes at offsets `8(a3)` and `$C(a3)`. |
| 5 | $14 | `Events_bg+$00 == 0` (via `loc_5A6FE`) | Final redraw setup at row $F0 with $F rows. Records `_unkEE8E` to `Events_bg+$0C`. Advances state. | Arena fully collapsed. |
| 6 | $18 | `Draw_PlaneVertBottomUp` complete | Clears `Events_bg+$0C`. Advances state. | Final BG plane reconstruction. |
| 7 | $1C | `Draw_PlaneVertBottomUp` complete | Clears `Events_bg+$0C`. Advances state (to stage 8). | Second pass of final BG reconstruction. |
| 8 | $20 | Always | Calls `DrawBGAsYouMove`. Falls through to deformation. | Terminal state. |

**All stages** end with the deformation block at `loc_5A734`:
- Uses `DEZ3_BGDrawArray` with `ApplyDeformation2`
- Sets `V_scroll_value = Camera_Y_pos_BG_copy` (Plane B VScroll)
- Sets `V_scroll_value_BG = Camera_Y_pos_copy` (Plane A VScroll)
- Calls `ShakeScreen_Setup` for screen shake support
- Pops return address (`addq.w #4,sp`) to skip the normal deformation return

**sub_5A76C (line 120586):** Camera-relative BG position computation:
- Records `Camera_X_pos_BG_copy` to `_unkEE8E`
- If `Events_bg+$00 == 0`: BG X = 0
- Otherwise: BG X = `Camera_X_pos_copy - Events_bg+$02 + Events_bg+$00` (boss-relative offset + arena size)
- BG Y = `Camera_Y_pos_copy - Events_bg+$04 + $180`

**sub_5A79E (line 120612):** Laser art DMA update:
- Reads `Events_bg+$10` (laser frame index)
- If changed from `Events_bg+$12` (previous frame), queues DMA transfer:
  - Source: `ArtUnc_DEZFBLaser + (frame+2)*4` (uncompressed art, 256 bytes total -- "Final Boss Laser.bin")
  - Destination: VRAM tile $208
  - Size: $40 bytes (2 tiles)
- This provides animated laser beam art for the final boss

**Confidence:** HIGH (complex multi-stage arena management, but logic flow is clear)

## Boss Sequences

### Act 1 Miniboss (`Obj_DEZMiniboss`)

- **Object:** `Obj_DEZMiniboss` at line 167659
- **Spawn trigger:** Object-based (placed in object layout, uses `Check_CameraInRange` with bounds from `word_7DDA4`)
- **Music:** Changes to `mus_Miniboss`
- **PLC load:** PLC $7B (loads `ArtNem_RobotnikShip` and `ArtNem_BossExplosion` via `PLC_78_79_7A_7B`)
- **Art load:** `ArtKosM_DEZMinibossMisc` queued to VRAM at `ArtTile_DEZMiniboss` ($0400)
- **Palette:** `Pal_DEZMiniboss1` loaded to palette line 1. `Pal_DEZMiniboss2` loaded later (at line 168278)
- **Object data:** Uses `ObjDat_DEZMiniboss` (mappings: `Map_DEZMiniboss`, art tile: `ArtTile_DEZMiniboss` with palette 1, priority)
- **Defeat behavior:** Boss flag cleared, music restored to `mus_DEZ2` (line 169744). Camera max X unlocked via `Obj_IncLevEndXGradual` at X=$3620. When player passes camera right edge, triggers `StartNewLevel` with `d0 = $1700` (DEZ3 Final Boss zone).
- **Ring/timer preservation:** `Act3_flag` set, `Ring_count` -> `Act3_ring_count`, `Timer` -> `Act3_timer`
- **Confidence:** HIGH

### Act 2 Boss (`Obj_DEZEndBoss`)

- **Object:** `Obj_DEZEndBoss` at line 169543
- **Spawn trigger:** Object-based (`Check_CameraInRange` with bounds at `word_7F0BE`: Y range $198-$498, X range $33E0-$3480)
- **Arena bounds:** Y range $218-$288, X range $3400-$34E0 (from `word_7F0C6`)
- **Music:** Changes to `mus_EndBoss`
- **PLC load:** PLC $76 (loads `ArtNem_FBZRobotnikStand`, `ArtNem_FBZRobotnikRun`, `ArtNem_BossExplosion` via `PLC_72_73_74_75_76`)
- **Art load:** `ArtKosM_DEZEndBoss` queued to VRAM at `ArtTile_DEZEndBoss` ($038A)
- **Palette:** `Pal_DEZEndBoss` loaded to palette line 1
- **Hit points:** 8 (`collision_property = 8`)
- **Movement:** Oscillating Y velocity ($100 initial), plays `sfx_WaveHover` every 64 frames
- **6-phase routine:** Init, approach, attack pattern 1, retreat, attack pattern 2, retreat (via `off_7F102` at line 169594)
- **Defeat behavior:** Sets `Events_fg_5`, starts act clear sequence
- **Confidence:** HIGH

### Act 3 Final Boss (`Obj_DEZ3_Boss`)

- **Object:** `Obj_DEZ3_Boss` at line 170909
- **Spawn:** Created by `DEZ3_ScreenInit` at position ($3C0, $F8)
- **PLC load:** `PLC_DEZ3_Boss` (line 172639) -- loads `ArtNem_RobotnikShip` to `ArtTile_RobotnikShip`
- **Art loads:**
  - `ArtKosM_DEZFinalBossMisc` to `ArtTile_DEZFinalBossMisc` ($038F)
  - `ArtKosM_DEZFinalBossDebris` to `ArtTile_DEZFinalBossDebris` ($0100) -- later phase
  - Boss crane art to `ArtTile_DEZBossCrane` ($049D) -- later phase
- **Fireball child:** `Obj_DEZ3_Boss_Fireball` (line 171996) spawned during attack phases
- **12-phase routine:** Complex multi-phase boss fight (`DEZ3_Boss_Index` at line 170918, 12 entries covering the full Robotnik mech fight sequence)
- **Dynamic art:** Laser beam art updated per-frame via `sub_5A79E` (DMA from `ArtUnc_DEZFBLaser` to VRAM tile $208)
- **Arena management:** Boss drives the background event state machine through `Events_fg_5`, `Screen_shake_flag`, and `Events_bg+$00` modifications. Arena boundary (`Events_bg+$00`) shrinks from $6C0 through $2C0 to 0 as the boss fight progresses.
- **Defeat behavior:** Transitions to next zone (DDZ / Doomsday)
- **Confidence:** HIGH

## Parallax

### Act 1 and Act 2 (PlainDeformation)

**Disassembly location:** `sonic3k.asm` line 103593

DEZ Acts 1 and 2 use `PlainDeformation` -- the simplest possible deformation routine:
- Fills the entire `H_scroll_buffer` ($380 bytes = 224 scanlines x 4 bytes per line) with a single value
- High word: `-Camera_X_pos_copy` (FG scroll)
- Low word: `-Camera_X_pos_BG_copy` (BG scroll)
- No per-line variation, no multi-layer parallax, no auto-scroll accumulators

The BG plane scrolls at the same rate as the camera position, with BG camera position controlled entirely by the Background Event code (which in normal gameplay just leaves it at 0,0 -- effectively static BG).

**Confidence:** HIGH

### Act 3 / Final Boss (Custom Deform)

**Disassembly location:** `sonic3k.asm` line 120567 (`loc_5A734`)

DEZ3 uses a more complex deformation:
1. Uses `DEZ3_BGDrawArray` ($E0, $7FFF) -- single 224-pixel band
2. Writes to `HScroll_table+$008` via `ApplyDeformation2` (d1=$DF = 223 lines)
3. `sub_5A508` updates HScroll entries:
   - `HScroll_table+$00A` = `Camera_X_pos_copy` (arena X scroll)
   - `HScroll_table+$004` = `Camera_X_pos_copy & $1FF` (wrapped BG X scroll)
4. Camera Y is fixed at `$20 + Screen_shake_offset`
5. VScroll: Plane B = `Camera_Y_pos_BG_copy`, Plane A = `Camera_Y_pos_copy`
6. `ShakeScreen_Setup` provides screen shake during boss attacks

**BG camera computation (`sub_5A76C`):**
- BG X tracks the boss position relative to arena size: `Camera_X - Boss_X + Arena_Size`
- BG Y = `Camera_Y - Boss_Y + $180`
- When arena size (`Events_bg+$00`) reaches 0, BG X is forced to 0

**Confidence:** HIGH

## Animated Tiles

### AniPLC_DEZ (8 scripts, shared for Act 1 and Act 2)

**Disassembly location:** `sonic3k.asm` line 56074

Both acts use `AnimateTiles_DoAniPLC` as the animation driver and the same `AniPLC_DEZ` script table. The `zoneanimdecl` format is: `trigger_byte, art_address, vram_tile, num_frames, tiles_per_frame`.

#### Script 0: Electrical arc A
- **Source art:** `ArtUnc_AniDEZ__0` (128 bytes = 2 frames x 2 tiles x 32 bytes/tile)
- **Destination VRAM tile:** $0E4
- **Frame count:** 2
- **Tiles per frame:** 2
- **Trigger:** `Level_trigger_array[0]` (trigger byte = 0)
- **Frame durations:** 0, 2 (2-frame cycle)
- **Confidence:** HIGH

#### Script 1: Large electrical effect
- **Source art:** `ArtUnc_AniDEZ__1` (2880 bytes = 6 frames x 30 tiles x 32 bytes/tile... actually 6 frames x $1E=30 tiles)
- **Destination VRAM tile:** $1F4
- **Frame count:** 6
- **Tiles per frame:** $1E (30)
- **Trigger:** `Level_trigger_array[1]` (trigger byte = 1)
- **Frame durations:** 0, $1E, $3C, 0, $1E, $3C (offsets into art data, not timing -- 6 frames)
- **Note:** Art file is 2880 bytes = 30 tiles x 32 bytes x 3 unique frames (durations cycle through 3 offsets twice)
- **Confidence:** HIGH

#### Script 2: Electrical panel animation
- **Source art:** `ArtUnc_AniDEZ__2` (1024 bytes = 8 frames x 4 tiles x 32 bytes/tile)
- **Destination VRAM tile:** $0EC
- **Frame count:** 8
- **Tiles per frame:** 4
- **Trigger:** `Level_trigger_array[3]` (trigger byte = 3)
- **Frame durations:** 0, 4, 8, $C, $10, $14, $18, $1C (sequential 4-tile offsets)
- **Confidence:** HIGH

#### Script 3: Flashing lights (always active)
- **Source art:** `ArtUnc_AniDEZ__3` (576 bytes)
- **Destination VRAM tile:** $05F
- **Frame count:** 4
- **Tiles per frame:** 6
- **Trigger:** Always active (trigger byte = -1)
- **Frame durations:** {0, 9}, {6, 4}, {$C, 9}, {6, 4} -- paired values (offset, duration). Durations: 9, 4, 9, 4 frames.
- **Note:** This script uses variable per-frame durations (duration bytes interleaved with frame offsets)
- **Confidence:** HIGH

#### Script 4: Small machinery animation A
- **Source art:** `ArtUnc_AniDEZ__4` (192 bytes = 3 unique frames x 2 tiles x 32 bytes/tile)
- **Destination VRAM tile:** $04B
- **Frame count:** 4
- **Tiles per frame:** 2
- **Trigger:** `Level_trigger_array[4]` (trigger byte = 4)
- **Frame durations:** 0, 2, 4, 2 (4-frame cycle, frame 3 repeats frame 1)
- **Confidence:** HIGH

#### Script 5: Small machinery animation B
- **Source art:** `ArtUnc_AniDEZ__5` (288 bytes = 3 unique frames x 3 tiles x 32 bytes/tile)
- **Destination VRAM tile:** $06B
- **Frame count:** 6
- **Tiles per frame:** 3
- **Trigger:** `Level_trigger_array[4]` (trigger byte = 4, shared with script 4)
- **Frame durations:** 0, 3, 6, 0, 3, 6 (6-frame cycle, repeats 3-frame pattern twice)
- **Confidence:** HIGH

#### Script 6: Background machinery glow
- **Source art:** `ArtUnc_AniDEZ__6` (512 bytes = 2 frames x 8 tiles x 32 bytes/tile)
- **Destination VRAM tile:** $028
- **Frame count:** 2
- **Tiles per frame:** 8
- **Trigger:** `Level_trigger_array[1]` (trigger byte = 1, shared with script 1)
- **Frame durations:** 0, 8 (2-frame cycle)
- **Confidence:** HIGH

#### Script 7: Large scrolling conveyor/machinery
- **Source art:** `ArtUnc_AniDEZ__7` (1600 bytes)
- **Destination VRAM tile:** $26D
- **Frame count:** $84 (132 -- but this is likely wrong, as the actual number of frames is limited by art data)
- **Tiles per frame:** 5
- **Trigger:** `Level_trigger_array[0]` (trigger byte = 0, shared with script 0)
- **Frame durations:** 0, $2D, 0, $2D, 0, $2D, 0, $2D (8 entries cycling through 2 art offsets)
- **Note:** Art size is 1600 bytes = 10 unique tile sets x 5 tiles x 32 bytes. The $84 frame count with wraparound and the 8-entry duration table creates a long cyclic animation. With 10 unique 5-tile frames at 32 bytes each, actual unique frames = 1600/(5*32) = 10.
- **Confidence:** MEDIUM (the $84 frame count vs. art data size needs verification -- may use modular indexing)

## Palette Cycling

### Act 1 (AnPal_DEZ1) -- 3 channels

**Disassembly location:** `sonic3k.asm` line 3661

AnPal_DEZ1 is the entry point for Act 1 palette cycling. It runs `AnPal_DEZ1`-specific channel first, then falls through to `AnPal_DEZ2` (which is shared with Act 2).

#### Channel 0: DEZ1-specific color cycling (energy conduit)

- **Counter:** `Palette_cycle_counters+$04`
- **Step:** 8 bytes (4 colors per tick)
- **Limit:** $30 (6 frames, wraps to 0)
- **Timer:** `Palette_cycle_counters+$0A`, period = 16 frames (reset to $F)
- **Table:** `AnPal_PalDEZ1` at line 4444

  | Frame | Color 0 | Color 1 | Color 2 | Color 3 |
  |-------|---------|---------|---------|---------|
  | 0 | $8AC | $68A | $468 | $246 |
  | 1 | $246 | $8AC | $68A | $468 |
  | 2 | $468 | $246 | $8AC | $68A |
  | 3 | $68A | $468 | $246 | $8AC |
  | 4 | $468 | $246 | $8AC | $68A |
  | 5 | $246 | $8AC | $68A | $468 |

- **Destination:** `Normal_palette_line_4+$18` = Palette line 3 (index 4), colors 12-15
- **Pattern:** Rotating 4-color palette (blue/purple gradient) -- colors rotate position each frame, creating a flowing energy effect
- **Conditional:** Only runs in Act 1 (AnPal_DEZ1 is only called for Act 1)
- **Confidence:** HIGH

#### Channel 1: Shared blinking light (fast)

- **Counter:** `Palette_cycle_counter0`
- **Step:** 4 bytes (2 colors per tick)
- **Limit:** $30 (12 frames, wraps to 0)
- **Timer:** `Palette_cycle_counter1`, period = 5 frames (reset to 4)
- **Table:** `AnPal_PalDEZ12_1` at line 4424

  | Frame | Color 0 | Color 1 |
  |-------|---------|---------|
  | 0 | $0E0 | $000 |
  | 1 | $000 | $E0E |
  | 2 | $E00 | $000 |
  | 3 | $000 | $0EE |
  | 4 | $00E | $000 |
  | 5 | $000 | $EE0 |
  | 6 | $EE0 | $000 |
  | 7 | $000 | $00E |
  | 8 | $0EE | $000 |
  | 9 | $000 | $E00 |
  | 10 | $E0E | $000 |
  | 11 | $000 | $0E0 |

- **Destination:** `Normal_palette_line_3+$1A` = Palette line 2 (index 3), colors 13-14
- **Pattern:** Alternating bright color flashes against black -- creates rapid blinking indicator lights. Each frame shows one bright color and one black, cycling through all primary and secondary colors.
- **Shared:** Runs for both DEZ1 and DEZ2 (falls through from AnPal_DEZ1 to AnPal_DEZ2)
- **Confidence:** HIGH

#### Channel 2: Shared panel glow (slow)

- **Counter:** `Palette_cycle_counters+$02`
- **Step:** $A (10 bytes = 5 colors per tick)
- **Limit:** $28 (4 frames, wraps to 0)
- **Timer:** `Palette_cycle_counters+$08`, period = 20 frames (reset to $13)
- **Table:** `AnPal_PalDEZ12_2` at line 4438

  | Frame | Color 0 | Color 1 | Color 2 | Color 3 | Color 4 |
  |-------|---------|---------|---------|---------|---------|
  | 0 | $08E | $06C | $04A | $028 | $6AE |
  | 1 | $28C | $26A | $04A | $226 | $4AC |
  | 2 | $48A | $468 | $248 | $424 | $4AA |
  | 3 | $28C | $26A | $04A | $226 | $4AC |

- **Destination:** `Normal_palette_line_3+$10` = Palette line 2 (index 3), colors 8-12 (5 colors written via move.l + move.l + move.w = 10 bytes)
- **Pattern:** Slow-cycling purple/blue gradient -- 4-frame cycle with frame 3 matching frame 1 (ping-pong)
- **Shared:** Runs for both DEZ1 and DEZ2
- **Confidence:** HIGH

### Act 2 (AnPal_DEZ2) -- 2 channels

**Disassembly location:** `sonic3k.asm` line 3676

AnPal_DEZ2 is called directly for Act 2, and is also the fall-through target from AnPal_DEZ1 for Act 1. It contains channels 1 and 2 described above.

- **Channel 1:** Shared blinking light (fast) -- same as Act 1 channel 1
- **Channel 2:** Shared panel glow (slow) -- same as Act 1 channel 2

Act 2 does NOT have the Act 1-specific energy conduit cycling (channel 0).

**Confidence:** HIGH

## Notable Objects

| Object | Name | Description | Notes |
|--------|------|-------------|-------|
| `Obj_DEZFloatingPlatform` | Floating Platform | Moving platform | Line 51189. Uses `ArtTile_DEZ2Extra+$8` |
| `Obj_DEZTiltingBridge` | Tilting Bridge | Bridge that tilts as player walks | Line 92694. Uses `ArtTile_DEZMisc`. Creates child segments. |
| `Obj_DEZHangCarrier` | Hang Carrier | Moving carrier player hangs from | Line 92839. Uses `ArtTile_DEZMisc+$10` |
| `Obj_DEZTorpedoLauncher` | Torpedo Launcher | Fires torpedo projectiles | Line 93006. Uses `ArtTile_DEZMisc+$26` |
| `Obj_DEZLiftPad` | Lift Pad | Elevator pad platform | Line 93086. Uses `ArtTile_DEZMisc2+$6` |
| `Obj_DEZStaircase` | Staircase | Dynamic staircase segments | Line 93317. Uses `ArtTile_DEZMisc+$133` with TiltingBridge mappings |
| `Obj_DEZConveyorBelt` | Conveyor Belt | Moving conveyor surface | Line 93510 |
| `Obj_DEZLightning` | Lightning | Electrical hazard | Line 93562. Uses `ArtTile_DEZMisc+$2C` with custom animations |
| `Obj_DEZConveyorPad` | Conveyor Pad | Conveyor boost pad | Line 93614. Uses `ArtTile_DEZMisc+$BB` |
| `Obj_DEZEnergyBridge` | Energy Bridge | Energy-based bridge platform | Line 93904 |
| `Obj_DEZEnergyBridgeCurved` | Curved Energy Bridge | Curved variant of energy bridge | Line 93987 |
| `Obj_DEZRetractingSpring` | Retracting Spring | Spring that retracts after use | Line 94093. Uses `ArtTile_DEZ2Extra` |
| `Obj_DEZTunnelLauncher` | Light Tunnel Launcher | Entry point for light tunnel transport | Line 94187. Uses `ArtTile_DEZMisc+$38` |
| `Obj_DEZTunnelControl` | Light Tunnel Control | Controls player movement through light tunnels | Line 94366. Complex waypoint-based path following with circle/sine movement modes. |
| `Obj_DEZTransRingSpawner` | Transporter Ring Spawner | Spawns rings along tunnel path | Line 94717 |
| `Obj_DEZTransRing` | Transporter Ring | Visual ring in tunnel | Line 94778. Uses `ArtTile_DEZMisc+$38` |
| `Obj_DEZGravitySwitch` | Gravity Switch | Toggles reverse gravity | Line 94795. Uses `ArtTile_DEZMisc+$143`. Sets/clears `Reverse_gravity_flag` based on player crossing and `render_flags` bit 0. |
| `Obj_DEZTeleporter` | Teleporter | Teleports player to new position | Line 94908 |
| `Obj_DEZGravityTube` | Gravity Tube | Tube transport with gravity control | Line 95165 |
| `Obj_DEZGravitySwap` | Gravity Swap | Directional gravity reversal trigger | Line 95467. Triggers on player X crossing, sets `Reverse_gravity_flag` based on render flags. |
| `Obj_DEZGravityHub` | Gravity Hub | Central gravity routing mechanism | Line 95540 |
| `Obj_DEZHoverMachine` | Hover Machine | Rideable hover vehicle | Line 95694. Uses `ArtTile_DEZMisc2+$11` |
| `Obj_DEZGravityRoom` | Gravity Room | Room with controlled gravity changes | Line 95809 |
| `Obj_DEZBumperWall` | Bumper Wall | Bouncing wall obstacle | Line 95953. Uses `ArtTile_DEZMisc2+$31` |
| `Obj_DEZGravityPuzzle` | Gravity Puzzle | Gravity-based puzzle mechanism | Line 96082. Uses `ArtTile_DEZMisc2+$31` |
| `Obj_FBZDEZPlayerLauncher` | Player Launcher | Tube launcher (shared FBZ/DEZ) | Line 79389. Uses `ArtTile_DEZMisc2` |
| `Map_HCZCNZDEZDoor` | Door | Sliding door (shared HCZ/CNZ/DEZ) | Line 66163. Uses `ArtTile_DEZMisc+$1E` |
| `Obj_DEZMiniboss` | Act 1 Miniboss | DEZ Act 1 boss | Line 167659 |
| `Obj_DEZEndBoss` | Act 2 Boss | DEZ Act 2 end boss | Line 169543 |
| `Obj_DEZ3_Boss` | Final Boss | Robotnik giant mech (Act 3) | Line 170909. 12-phase fight. |
| `Obj_DEZ3_Boss_Fireball` | Final Boss Fireball | Projectile from final boss | Line 171996 |

**Badnik PLCs (PLCKosM_DEZ):** Spikebonker (`ArtTile_Spikebonker`), Chainspike (`ArtTile_Chainspike`). Both acts share the same badnik art. (Line 64428)

**Still sprites:** DEZ has 3 still sprite types in the StillSprites table:
- ID $30: DEZ Horizontal Beam Platform Shooter (`ArtTile_DEZMisc+$B2`, palette 1, size 16x12)
- ID $31: DEZ Vertical Beam Platform Shooter (`ArtTile_DEZMisc+$B2`, palette 1, size 12x8)
- ID $32: DEZ Light Tunnel Post (`ArtTile_DEZMisc+$38`, palette 1, size 16x36)

## Cross-Cutting Concerns

- **Water:** Not present. DEZ is entirely dry. No water level, no waterline deformation, no underwater palette.

- **Reverse Gravity:** DEZ is the primary zone using the `Reverse_gravity_flag` system. Multiple objects (`Obj_DEZGravitySwitch`, `Obj_DEZGravitySwap`, `Obj_DEZGravityRoom`) toggle this flag. When set, the physics engine inverts gravity for the player. This flag is checked extensively throughout the player movement code (~20+ references in `sonic3k.asm`). Gravity is restored to normal (flag cleared) when the player crosses a gravity switch in the opposite direction.

- **Light Tunnel Transport:** The `Obj_DEZTunnelLauncher` / `Obj_DEZTunnelControl` system provides a unique transport mechanic:
  - Launcher captures the player, spawns a `Obj_DEZTunnelControl` child
  - Control object follows waypoint paths from `DEZTunnelPaths` data
  - Movement modes: Normal (straight-line waypoints), CircleLarge, CircleSmall, SineDown, SineUp
  - Scale factors and wait timers vary per mode (`DEZTunnelControl_ScaleFactors`, `DEZTunnelControl_WaitTimers`)
  - `Obj_DEZTransRingSpawner` creates visual ring effects along the path

- **Seamless Act Transition (Act 1 -> Act 2):**
  - Triggered via `Events_fg_5` signal (set by an object, likely the miniboss or an end-of-act trigger)
  - Stage 0: Queues DEZ2 art (16x16 blocks via Kos, 8x8 patterns via KosinskiM, PLC $38 for Nemesis art)
  - Stage 1: Waits for art loading completion, then performs full level reload: changes `Current_zone_and_act` to $B01, clears state variables, calls `Load_Level`/`LoadSolids`, copies DEZ2 palette, offsets all positions (X-=$3600, Y+=$400), reconstructs BG plane
  - This is simpler than the AIZ act transition (no fire sequence, no progressive tile reveals)

- **DEZ2 -> DEZ3 (Final Boss) Transition:**
  - After `Obj_DEZEndBoss` defeat: saves ring count and timer to `Act3_ring_count`/`Act3_timer`
  - Calls `StartNewLevel` with `d0 = $1700` (zone $17 act 0)
  - DEZ3_ScreenEvent stage 0 restores the saved rings/timer to the HUD
  - `Act3_flag` is set to signal that this is a continuation (preserving state)

- **DEZ3 Arena System:**
  - The final boss arena is dynamically managed through `Events_bg+$00` (arena size)
  - Arena shrinks during the fight: $6C0 -> $2C0 -> 0
  - Each shrink triggers BG plane reconstruction and chunk modifications
  - Camera X wraps at $1FF (`HScroll_table+$004 = Camera_X & $1FF`) creating a circular arena effect
  - Screen shake via `ShakeScreen_Setup` during boss attacks
  - Laser beam art dynamically DMA'd per frame from `ArtUnc_DEZFBLaser` to VRAM tile $208

- **Screen Events (Chunk Modifications):**
  - DEZ1: Single chunk modification (writes $BD at offset $6E in layout) triggered by `Events_fg_4`
  - DEZ2: Two-stage chunk modification (stage 0: writes $D7/$DC/$D7 at 3 consecutive layout positions; stage 1: writes $BC at offset $6B). Both triggered by `Events_fg_4`.
  - DEZ3: Multiple progressive chunk modifications during boss fight, cycling through chunk ID pairs via `Events_bg+$0A` sub-index

- **Palette Loading:**
  - Act 1: Uses palette ID $20 (from `levartptrs` -- `Pal_DEZ1`, 96 bytes = 3 palette lines)
  - Act 2: Uses palette ID $21 (`Pal_DEZ2`, 96 bytes = 3 palette lines)
  - Seamless transition copies `Pal_DEZ2+$20` (64 bytes) to `Normal_palette_line_3` (overwriting palette lines 2-3)
  - Act 3: Uses palette ID $40 (separate DEZ3 palette)
  - Miniboss: `Pal_DEZMiniboss1` and `Pal_DEZMiniboss2` (32 bytes each, palette line 1)
  - End boss: `Pal_DEZEndBoss` (32 bytes, palette line 1)

- **PLC Loading:**
  - Act 1 level PLC: PLC $36 (`ArtNem_DEZMisc` at `ArtTile_DEZMisc`, `ArtNem_DEZMiniboss` at `ArtTile_DEZMisc2`)
  - Act 2 level PLC: PLC $38 (`ArtNem_DEZMisc` at `ArtTile_DEZMisc`, `ArtNem_DEZ2Extra` at `ArtTile_DEZ2Extra`)
  - Act 2 transition: PLC $38 loaded during seamless transition from Act 1
  - Act 3 level PLC: PLC $4C (`ArtNem_FBZRobotnikRun` at `ArtTile_DEZRobotnikRun`)
  - Enemy art: `PLCKosM_DEZ` (Spikebonker, Chainspike)
  - Miniboss: PLC $7B (RobotnikShip, BossExplosion) + `ArtKosM_DEZMinibossMisc` (KosinskiM)
  - End boss: PLC $76 (FBZRobotnikStand, FBZRobotnikRun, BossExplosion) + `ArtKosM_DEZEndBoss` (KosinskiM)
  - Final boss: `PLC_DEZ3_Boss` (RobotnikShip) + `ArtKosM_DEZFinalBossMisc` + `ArtKosM_DEZFinalBossDebris` (KosinskiM)

- **Level Boundaries:**
  - Act 1: X range 0-$6000, Y range 0-$B20
  - Act 2: X range 0-$6000, Y range 0-$F10
  - Act 3: X range 0-$6000, Y range $20-$20 (fixed single-screen arena)

- **Start Locations:**
  - Act 1: Sonic ($0030, $09AC), Knuckles ($0030, $09AC) -- identical
  - Act 2: Sonic ($0140, $03AC), Knuckles ($0140, $03AC) -- identical
  - Act 3 Boss: Sonic ($0060, $0070)

- **Character Branching:** Minimal for DEZ compared to other zones. Sonic/Tails and Knuckles share the same start positions and level layouts. The primary character difference is at the game progression level: Knuckles' story does not include DEZ3 (the Final Boss), proceeding instead to Mecha Sonic in Sky Sanctuary.

- **No chunk adjustments:** Unlike AIZ, DEZ has no `Adjust_*Chunks` routines.

- **No dynamic water height:** No `DynamicWaterHeight_DEZ*` routines exist.

- **Shared objects:** Door object shared with HCZ/CNZ (`Map_HCZCNZDEZDoor`). Player launcher shared with FBZ (`Obj_FBZDEZPlayerLauncher`).

## Implementation Notes

### Priority Order
1. **Parallax (Deform)** -- Trivial for Acts 1/2 (`PlainDeformation` is the default). Act 3 requires custom deform with arena-relative BG positioning.
2. **Palette Cycling (AnPal)** -- 3 channels with well-defined counter/step/limit patterns. Critical for visual correctness: blinking lights and energy conduit effects.
3. **Animated Tiles (AniPLC)** -- 8 scripts with trigger conditions. Standard `AnimateTiles_DoAniPLC` driver.
4. **Events (Screen/Background)** -- Act 1/2 events are simple chunk modifications and seamless transition. Act 3 events are complex multi-stage arena management.
5. **Act Transitions** -- DEZ1->DEZ2 seamless transition is simpler than AIZ's fire transition but still requires art loading, level reload, and position offsetting. DEZ2->DEZ3 is a full level change via `StartNewLevel`.

### Dependencies
- Seamless Act 1->2 transition requires Kos/KosinskiM queuing and completion detection
- DEZ2 ScreenEvent stage 0 is only reached during seamless transition (init sets stage to 1)
- DEZ3 arena management depends on boss object driving `Events_fg_5`, `Screen_shake_flag`, and `Events_bg+$00`
- AniPLC scripts 0, 2, 4, 5 depend on `Level_trigger_array` entries being set by trigger objects in the level
- Palette cycling channel 0 (energy conduit) is Act 1 only -- channel selection is implicit from the zone dispatch table

### Known Risks
- **DEZ3 arena management:** The circular arena with shrinking boundaries, dynamic chunk modifications, and boss-driven state transitions is the most complex feature. The camera X wrapping at $1FF and the BG position computation relative to boss position requires careful implementation. Confidence: MEDIUM.
- **Light tunnel system:** The waypoint-based player transport with multiple movement modes (normal, circle, sine) is complex but self-contained in the object code. Confidence: MEDIUM (requires accurate waypoint path parsing).
- **Reverse gravity:** The `Reverse_gravity_flag` affects player physics engine-wide (~20+ call sites). Implementation requires physics system support, not just zone-level code. Confidence: MEDIUM.
- **Seamless transition art loading:** The Kos/KosinskiM queue completion check (`Kos_modules_left == 0`) gates the level reload. If art loading is incomplete, the transition stalls. Confidence: HIGH (pattern is well-established from other zones).
