# S3K FBZ Zone Analysis

## Summary

- **Zone:** Flying Battery Zone (FBZ)
- **Zone Index:** 0x04
- **Zone Set:** S3KL
- **Acts:** 1 and 2
- **Water:** No
- **Palette Cycling:** Minimal -- `AnPal_FBZ` toggles a magnetic polarity flag (`_unkF7C1`) every 256 frames; no color cycling
- **Animated Tiles:** Yes (5 AniPLC scripts per act, shared art sources, generic handler)
- **Character Branching:** Minimal -- Act 2 end boss event sets Camera_min_Y_pos differently for Tails ($40 vs $3C); Act 2 subboss uses EggRobo art for Knuckles instead of Robotnik art
- **Unique Mechanic:** Indoor/outdoor BG switching system with palette mutations and layout modifications

## Events

### Dynamic_Resize (FBZ1_Resize / FBZ2_Resize)

**Disassembly location:** `sonic3k.asm` line 39411-39413

Both `FBZ1_Resize` and `FBZ2_Resize` are `rts` stubs (shared with CNZ). FBZ does NOT use the standard `Dynamic_resize_routine` state machine. All event logic is handled entirely through `ScreenEvent` and `BackgroundEvent` routines.

**Confidence:** HIGH

### Act 1 Screen Events (FBZ1_ScreenEvent)

**Disassembly location:** `sonic3k.asm` line 108171

FBZ1 uses a layout modification system that swaps foreground tile regions when the player moves between "indoor" and "outdoor" areas of the airship. The system is driven by `Events_bg+$00` (layout mod index) and `Events_bg+$02` (indoor/outdoor state flag for FG).

**Death guard:** Returns immediately (`rts`) if `Player_1+routine >= 6` (dying).

**State machine (Events_bg+$00 -- layout mod dispatch):**

| Index | Value | Label | Region (X min, X max, Y min, Y max) | Description |
|-------|-------|-------|--------------------------------------|-------------|
| 0 | $00 | FBZ1SE_Normal | N/A | Scans all 6 regions for player position, activates matching layout mod |
| 1 | $04 | FBZ1SE_LayoutMod1 | $400-$F00, $880-$A80 | First indoor/outdoor swap area |
| 2 | $08 | FBZ1SE_LayoutMod2 | $880-$1100, $180-$300 | Second swap area |
| 3 | $0C | FBZ1SE_LayoutMod3 | $1400-$1B80, $900-$B00 | Third swap area |
| 4 | $10 | FBZ1SE_LayoutMod4 | $1A80-$2100, $80-$200 | Fourth swap area |
| 5 | $14 | FBZ1SE_LayoutMod5 | $2080-$2680, $100-$280 | Fifth swap area |
| 6 | $18 | FBZ1SE_LayoutMod6 | $0-$180, $580-$780 | Beginning of stage (near start) |

**Layout mod range table:** `FBZ1_LayoutModRange` at line 108656:
```
$0400, $0F00, $0880, $0A80   ; Region 1
$0880, $1100, $0180, $0300   ; Region 2
$1400, $1B80, $0900, $0B00   ; Region 3
$1A80, $2100, $0080, $0200   ; Region 4
$2080, $2680, $0100, $0280   ; Region 5
$0000, $0180, $0580, $0780   ; Region 6
```

**Layout mod behavior (common pattern):**
Each layout mod checks if the player has moved past a threshold:
- If `Events_bg+$02` is clear (indoors): checks for transition to outdoors
- If `Events_bg+$02` is set (outdoors): checks for transition back to indoors
- On transition: copies tile data from the appropriate BG row to the FG layout, then calls `Refresh_PlaneScreenDirect`

**ScreenInit (FBZ1_ScreenInit):** At line 108144
- If Player X >= $180: copies "indoors" tile data from BG to visible area
- If Player X < $180: sets `Events_bg+$00 = $18` (LayoutMod6 -- start area) and `Events_bg+$02 = $FF` (outdoors flag)

**Knuckles differences:** None in Act 1 screen events.

**Confidence:** HIGH

### Act 1 Background Events (FBZ1_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 108700

The background event manages the indoor/outdoor BG plane switching -- when the player transitions between indoor and outdoor sections, the BG plane must be redrawn with different content and palette.

**State machine (Events_routine_bg):**

| Stage | Value | Label | Action |
|-------|-------|-------|--------|
| 0 | $00 | FBZ1BGE_Normal | Main handler: checks for act transition via `Events_fg_5`, runs `FBZ1_CheckBGChange`, draws BG tiles |
| 1 | $04 | FBZ1BGE_ChangeTopDown | Draws new BG plane top-down during indoor/outdoor transition |
| 2 | $08 | FBZ1BGE_ChangeBottomUp | Draws new BG plane bottom-up during transition |
| 3 | $0C | FBZ1BGE_ChangeLeftRight | Draws new BG plane left-to-right during transition |
| 4 | $10 | FBZ1BGE_ChangeRightLeft | Draws new BG plane right-to-left during transition |

**Act 1 to Act 2 seamless transition** (in `FBZ1BGE_Normal`, line 108789):
When `Events_fg_5` is set (by Act 1 miniboss defeat via standard `Obj_EndSignControl`):
1. Loads PLC $1C (FBZ2 level art: `ArtNem_FBZMisc`, `ArtNem_FBZMisc2`, `ArtNem_FBZEggCapsule`)
2. Sets `Current_zone_and_act` = $0401 (FBZ Act 2)
3. Clears `Dynamic_resize_routine`, `Object_load_routine`, `Rings_manager_routine`, `Boss_flag`, `Respawn_table_keep`
4. Calls `Clear_Switches`, `Load_Level`, `LoadSolids`
5. Loads palette $13 (FBZ2 palette)
6. Offsets all objects and camera by -$2E00 X pixels
7. Resets tile offsets and runs `FBZ_Deform`

**BG change system (FBZ1_CheckBGChange):** At line 108922
Dispatches to 6 background change routines corresponding to the 6 layout mod regions. Each checks player position against thresholds and triggers indoor/outdoor BG transitions:

| Handler | Indoor->Outdoor trigger | Outdoor->Indoor trigger |
|---------|------------------------|------------------------|
| BGChange1 | Player Y >= $9C0 (left) or >= $900 (right of $B00 X) | Reverse thresholds |
| BGChange2 | Player Y <= $2C0 (left) or <= $240 (right of $C80 X) | Reverse thresholds |
| BGChange3 | Player Y >= $9C0 (left) or >= $940 (right of $1880 X) | Reverse thresholds |
| BGChange4 | Complex: Y >= $1C0 (mid-height area), or X >= $1B00 (left-right special case) | Reverse thresholds |
| BGChange5 | Player Y <= $240 | Player Y >= $240 |
| BGChange6 | Player Y >= $640 | Player Y <= $640 |

**BG change mechanics:**
- Going outdoors: `Events_bg+$04` set, loads `Pal_FBZBGOutdoors` to palette line 4 colors 2-9, sets `Events_routine_bg` to TopDown($04) or BottomUp($08)
- Going indoors: `Events_bg+$04` cleared, loads `Pal_FBZBGIndoors` to palette line 4 colors 2-9, sets `Events_routine_bg` to TopDown or BottomUp
- BGChange4 special case: uses LeftRight($0C) / RightLeft($10) for horizontal BG transitions instead of vertical

**Palette mutations on BG change:**
- Going outdoors: writes `Pal_FBZBGOutdoors` (16 bytes) to `Normal_palette_line_4+$04` (palette line 4, colors 2-9)
- Going indoors: writes `Pal_FBZBGIndoors` (16 bytes) to same location
- BGChange4 left-right special case: loads palette inline at line 109120-109131

**BackgroundInit (FBZ1_BackgroundInit):** At line 108665
1. Allocates `Obj_FBZOutdoorBGMotion` (BG bobbing object)
2. If Player X < $180: sets `Events_bg+$04` = $FF (outdoors), loads `Pal_FBZBGOutdoors` palette, applies outdoor BG deformation
3. If Player X >= $180: applies indoor BG deformation (default)

**Confidence:** HIGH

### Act 2 Screen Events (FBZ2_ScreenEvent)

**Disassembly location:** `sonic3k.asm` line 109324

**Critical:** FBZ2 screen event applies screen shake offset to camera Y every frame:
```
move.w (Screen_shake_offset).w,d0
add.w  d0,(Camera_Y_pos_copy).w
```

**State machine (Events_routine_fg):**

| Stage | Value | Label | Trigger | Action |
|-------|-------|-------|---------|--------|
| 0 | $00 | FBZ2SE_Normal | Player in range | One layout mod region ($D80-$1300, $A00-$B80); advances to BossEvent when Camera X >= $2B30 |
| 1 | $04 | FBZ2SE_BossEvent | Boss event active | Sets d7 = VRAM_Plane_B_Name_Table (draws FG tiles on BG plane), calls DrawTilesAsYouMove |
| 2 | $08 | FBZ2SE_BossEventRefresh | Plane refresh | Draws vertical bottom-up from X=$2D00 on plane B, advances to End when complete |
| 3 | $0C | FBZ2SE_BossEventEnd | Done | `rts` -- terminal state |

**Boss event setup** (triggered at Camera X >= $2B30, line 109374):
1. Calls `SetUp_FBZ2BossEvent` (spawns boss control, pillar, and cloud objects)
2. Clears `_unkEE98` and `_unkEE9C` (BG camera offsets)
3. Advances `Events_routine_fg` by 4

**SetUp_FBZ2BossEvent** (line 109455):
1. Allocates `Obj_FBZEndBossEventControl` (solid platform that carries player through transition)
2. Allocates `Obj_FBZBossPillar` (decorative pillars)
3. Resets 5 cloud address slots at `FBZ_cloud_addr` RAM
4. Creates 10 `Obj_FBZCloud` objects, stores their addresses in cloud address array
5. Loads `Pal_FBZBGOutdoors` to palette line 4 (both Normal and Target), sets color 1 to $EEE (white)

**Layout mod region (Act 2):** `FBZ2_LayoutModRange` at line 109498:
```
$0D80, $1300, $0A00, $0B80   ; Single indoor/outdoor region
```

**ScreenInit (FBZ2_ScreenInit):** At line 109300
- If Camera X >= $2C40: sets `Events_routine_fg = 4` (boss event mode), `Camera_min_X_pos = $2C40`, calls `SetUp_FBZ2BossEvent`, queues `ArtKosM_FBZCloud` and `ArtKosM_FBZBossPillar` KosinskiM art, sets `Screen_shake_flag`
- Otherwise: normal init

**Knuckles differences:** None in Act 2 screen events (the boss event control object has a Tails check, see Boss Sequences).

**Confidence:** HIGH

### Act 2 Background Events (FBZ2_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 109539

**State machine (Events_routine_bg):**

| Stage | Value | Label | Action |
|-------|-------|-------|--------|
| 0 | $00 | FBZ2BGE_Init | Copies BG line pointers to mirror lines ($70,$74,$78,$7C), advances to $04 |
| 1 | $04 | FBZ2BGE_Normal | Checks if boss event started (Events_routine_fg != 0); if so, enters cloud deform and sets Events_routine_bg=$10. Otherwise runs FBZ2_CheckBGChange + normal BG drawing with ShakeScreen_Setup |
| 2 | $08 | FBZ2BGE_ChangeTopDown | Draws new BG plane top-down during indoor/outdoor BG transition |
| 3 | $0C | FBZ2BGE_ChangeBottomUp | Draws new BG plane bottom-up during BG transition |
| 4 | $10 | FBZ2BGE_BossEvent | Cloud deform mode: draws BG on plane A (VRAM_Plane_A_Name_Table), applies flipped deformation, screen shake, swaps V-scroll values (BG copy -> V_scroll_value, camera copy -> V_scroll_value_BG). Processes boss load event (`Events_bg+$06`) for final position adjustment |

**Boss load position adjustment** (line 109651):
When `Events_bg+$06` is set (boss loaded):
- Reads and clears `_unkEE9C` (Y offset) and `_unkEE98` (X offset)
- Adjusts player positions if airborne
- Adjusts camera bounds
- Clears a 4x20 block area of FG layout at Y=$580, X=$2D00 (erases copied FG from the layout mod)

**FBZ2_CheckBGChange** (line 109762): Single region check:
- Indoor->Outdoor: Player Y >= $A40; sets `Events_bg+$04`, loads outdoor palette, TopDown transition
- Outdoor->Indoor: Player Y <= $A40; clears `Events_bg+$04`, loads indoor palette, BottomUp transition

**BackgroundInit (FBZ2_BackgroundInit):** At line 109502
1. Allocates `Obj_FBZOutdoorBGMotion`
2. If Camera X >= $2C40: sets `Events_routine_bg = $10` (boss event), `Screen_shake_flag = $FF` (constant shake), draws cloud deform on plane A with flipped deformation, special V-scroll setup
3. Otherwise: normal indoor deform init

**Confidence:** HIGH

## Boss Sequences

### Act 1 Miniboss (Obj_FBZMiniboss)

- **Object:** `Obj_FBZMiniboss` at line 146761
- **Spawn trigger:** `Check_CameraInRange` with bounds Y=$240-$600, X=$2D20-$2F20 (initial); locks at Y=$540, X=$2E20 (arena)
- **PLC load:** `PLC_BossExplosion` (raw), plus `ArtKosM_FBZMiniboss` queued via KosinskiM
- **Palette:** `Pal_FBZMiniboss` loaded to palette line 1
- **Arena boundaries:** `Camera_target_max_Y_pos = $540`, `Camera_min_X_pos = $2E20`, `Camera_max_X_pos = $2EA0` ($2E20 + $80)
- **HP:** 6 hits (`collision_property = 6`)
- **Boss flag:** Sets `Boss_flag = 1` on init
- **Music:** Fades out, then plays `mus_Miniboss`
- **Defeat behavior:** Creates `Obj_EndSignControl` + `Obj_Song_Fade_ToLevelMusic`, which eventually sets `Events_fg_5` to trigger act transition
- **Confidence:** MEDIUM -- entry and arena setup clear; internal attack patterns (sub_6F786, sub_6F994) need deeper investigation

### Act 2 Subboss (Obj_FBZ2Subboss)

- **Object:** `Obj_FBZ2Subboss` at line 148028
- **Spawn trigger:** `Check_CameraInRange` with bounds Y=$560-$660, X=$2900-$2C00 (initial); locks at Y=$5E0, X=$2900 (arena)
- **PLC load:** `PLC_FBZ2Subboss_SonicTails` (Robotnik art) or `PLC_FBZ2Subboss_Knuckles` (EggRobo art) based on `Player_1+character_id`
- **Palette:** `Pal_FBZ2Subboss` loaded to palette line 1
- **Arena boundaries:** `Camera_min_X_pos = $2900`, `Camera_target_max_Y_pos = $5E0`
- **HP:** 127 hits (`collision_property = $7F` -- effectively invincible, uses hit counter at $39 = 6 real hits)
- **Boss flag:** Sets `Boss_flag = 1` on init
- **Music:** Fades to `mus_Miniboss`
- **Defeat behavior:** Loads boss pillar/cloud art (`PLCKosM_FBZ2Subboss`), clears `Boss_flag`, loads `PLC_Monitors`
- **Character difference:** Knuckles gets `ArtNem_EggRoboStand` / `ArtNem_EggRoboRun` instead of `ArtNem_FBZRobotnikStand` / `ArtNem_FBZRobotnikRun`
- **Confidence:** MEDIUM -- arena setup and character branching clear; multi-phase attack loop (routines 0-8) needs deeper reading

### Act 2 End Boss (Obj_FBZEndBoss)

- **Object:** `Obj_FBZEndBoss` at line 148693
- **Spawn trigger:** Created by `Obj_FBZEndBossEventControl` at line 109877 when `Events_routine_fg == $0C` (boss event plane refresh complete)
- **PLC load:** PLC $6F loaded at boss spawn (contents: `ArtNem_FBZEndBoss`, `ArtNem_FBZRobotnikHead`, `ArtNem_FBZEndBossFlame`, `ArtNem_RobotnikShip`, `ArtNem_BossExplosion`, `ArtNem_EggCapsule`)
- **Palette:** `Pal_FBZEndBoss` loaded to palette line 1
- **Arena boundaries:** Set by `Obj_FBZEndBossEventControl`: `Camera_max_X_pos = $32B8`, `Camera_min_Y_pos = $3C` (Sonic/Tails) or $40 (Tails alone)
- **HP:** 8 hits (`collision_property = 8`)
- **Boss flag:** Sets `Boss_flag = 1`
- **Music:** Fades out current, transitions to `mus_EndBoss`
- **Screen shake:** Cleared (`Screen_shake_flag = 0`) when boss loads
- **Defeat behavior:** `Obj_EndSignControl` called, palette rotation script started for transition; creates `Obj_FBZRobotnikHead` and `Obj_FBZRobotnikShip` children
- **Confidence:** MEDIUM -- spawn sequence and arena well-documented; the boss's multi-phase attack pattern (5 routines) needs deeper investigation

### Boss Event Control (Obj_FBZEndBossEventControl)

- **Object:** `Obj_FBZEndBossEventControl` at line 109820
- **Purpose:** Acts as a solid platform carrying the player during the outdoor transition sequence before the Act 2 end boss
- **Behavior sequence:**
  1. Sets `Camera_max_X_pos = $32B8`, `Camera_min_Y_pos` based on character ($3C default, $40 for Tails alone)
  2. Waits until Player X >= $2E80, then starts moving BG via `_unkEE98` (X offset, increments by $7800/frame) and `_unkEE9C` (Y offset, increments by $A000/frame)
  3. Sets `Background_collision_flag = $FF` (BG collision enabled during transition)
  4. When Y offset reaches $5D0 and X offset reaches $45C: clears `Background_collision_flag`, stops moving
  5. Waits for camera to reach min Y, locks camera max Y
  6. When camera fully locked: starts `Draw_delayed` row refresh, advances `Events_routine_fg`
  7. When `Events_routine_fg == $0C`: spawns `Obj_FBZEndBoss`, clears `Screen_shake_flag`, sets `Events_bg+$06` (triggers position adjustment in BG event)
  8. Final state: solid platform at ($31C0, $690) adjusted by offsets, using `SolidObjectTop` with width=$4C0, height=$11
- **Confidence:** HIGH

## Parallax

### FBZ_Deform (shared routine)

**Disassembly location:** `sonic3k.asm` line 108854

FBZ uses a single `FBZ_Deform` routine for both acts, with indoor and outdoor modes selected by `Events_bg+$04`.

#### Indoor Mode (Events_bg+$04 == 0)

**BG Y calculation:**
```
BG_Y = Camera_Y / 2 - Camera_Y / 64
     = Camera_Y * (1/2 - 1/64)
     = Camera_Y * 31/64
```
This gives a slightly-less-than-half-speed vertical parallax.

**BG X calculation:**
Base X = `Camera_X << 16 >> 4` = Camera_X * 4096 (fixed-point, only integer part used)
Each band adds `base_increment = base_X` to the running accumulator.

**Scatter-fill index table:** `FBZ_InBGDeformIndex` at line 109270

The indoor deform uses a non-sequential scatter-fill pattern. The index table maps 9 speed groups to specific HScroll_table entries (scanline pairs). Format: `count-1, entry0, entry1, ..., entryN`.

| Group | Count | Speed level | HScroll entries (byte offsets) |
|-------|-------|-------------|-------------------------------|
| 0 | 1 | Slowest | $0C |
| 1 | 2 | +1 increment | $0A, $16 |
| 2 | 11 | +2 increments | $08, $14, $18, $1C, $20, $24, $28, $2C, $30, $34, $38 |
| 3 | 3 | +3 increments | $06, $12, $3E |
| 4 | 1 | +4 increments | $46 |
| 5 | 8 | +5 increments | $04, $10, $1E, $26, $2E, $36, $3A, $40 |
| 6 | 4 | +6 increments | $0E, $22, $32, $3C |
| 7 | 2 | +7 increments | $00, $44 |
| 8 | 4 | +8 increments (fastest) | $02, $1A, $2A, $42 |

**Indoor deform array:** `FBZ_InBGDeformArray` at line 109224
Heights in pixels for each BG band (word entries, $7FFF = fill remaining):

| Band | Height (px) | Description |
|------|-------------|-------------|
| 0 | $80 (128) | Top area |
| 1 | $40 (64) | |
| 2 | $20 (32) | |
| 3 | $08 (8) | |
| 4 | $08 (8) | |
| 5 | $08 (8) | |
| 6 | $08 (8) | |
| 7 | $28 (40) | |
| 8 | $30 (48) | |
| 9 | $08 (8) | |
| 10 | $04 (4) | |
| 11 | $04 (4) | |
| 12 | $B8 (184) | Large central band |
| 13 | $30 (48) | |
| 14 | $10 (16) | |
| 15 | $10 (16) | |
| 16 | $30 (48) | |
| 17 | $28 (40) | |
| 18 | $18 (24) | |
| 19 | $10 (16) | |
| 20 | $30 (48) | |
| 21 | $30 (48) | |
| 22 | $10 (16) | |
| 23 | $10 (16) | |
| 24 | $30 (48) | |
| 25 | $28 (40) | |
| 26 | $18 (24) | |
| 27 | $10 (16) | |
| 28 | $B0 (176) | |
| 29 | $30 (48) | |
| 30 | $28 (40) | |
| 31 | $40 (64) | |
| 32 | $18 (24) | |
| 33 | $30 (48) | |
| term | $7FFF | Fill remaining |

Total: 34 bands before terminator.

**Confidence:** HIGH

#### Outdoor Mode (Events_bg+$04 != 0)

**BG Y calculation:**
```
BG_Y = $16 + Events_bg+$08   (bobbing offset from Obj_FBZOutdoorBGMotion)
```
Fixed base of $16 (22 px) plus oscillating offset from the `Gradual_SwingOffset` object.

**BG X calculation:**
Base = Camera_X * 4096 (fixed-point, same as indoor)
Each of 9 bands accumulates `base_increment = base / 2` plus a cloud drift `d2` from `HScroll_table+$1FC`:
```
HScroll_table+$1FC += $0E00 per frame   (cloud auto-scroll accumulator)
```

**Outdoor deform index table:** `FBZ_OutBGDeformIndex` at line 109282
9 entries (word offsets into HScroll_table): $0E, $02, $0A, $06, $0C, $04, $08, $00, $10

**Outdoor deform array:** `FBZ_OutBGDeformArray` at line 109260

| Band | Height (px) |
|------|-------------|
| 0 | $30 (48) |
| 1 | $20 (32) |
| 2 | $30 (48) |
| 3 | $10 (16) |
| 4 | $10 (16) |
| 5 | $10 (16) |
| 6 | $10 (16) |
| 7 | $10 (16) |
| term | $7FFF |

Total: 8 bands before terminator.

**Auto-scroll:** Cloud drift at $0E00 per frame in `HScroll_table+$1FC`.

**Obj_FBZOutdoorBGMotion** (line 109217): Uses `Gradual_SwingOffset` with amplitude $2800 and step $80 to create a gentle vertical bobbing motion. Result stored in `Events_bg+$08`.

**Confidence:** HIGH

### Act 2 Boss Event Cloud Deform (FBZ2_CloudDeform)

**Disassembly location:** `sonic3k.asm` line 109696

Special deform routine used during the Act 2 end boss sequence. Replaces normal FBZ_Deform when `Events_routine_bg == $10`.

**BG position calculation:**
```
BG_Y = (Camera_Y - $300 + _unkEE9C - Screen_shake_offset) / 2 + Screen_shake_offset + Events_bg+$08
BG_X = Camera_X - $2600 - _unkEE98
```

**Cloud scroll:** Uses `FBZ2_CloudDeformIndex` at line 110022 (10 entries: $04, $0E, $08, $10, $02, $0C, $06, $12, $0A, $00) with cloud auto-scroll accumulator:
```
HScroll_table+$1FC += $8000 per frame   (faster than normal outdoor mode)
cloud_drift = HScroll_table+$1FC >> 3
```

**Cloud object positioning:** After computing HScroll values, iterates over 10 cloud objects in `FBZ_cloud_addr` array. Each cloud's screen position is calculated from:
- Y: `(obj.$30 + BG_Y_offset) & $FF + $74`
- X: `(-hscroll + obj.$2E) & $1FF + $54`

**Cloud position data:** `FBZCloud_PositionFrameData` at line 110033 (10 entries of X, Y, frame):
| Cloud | X | Y | Frame |
|-------|-------|------|-------|
| 0 | $1E0 | $EC | 1 |
| 1 | $144 | $C8 | 2 |
| 2 | $60 | $B4 | 3 |
| 3 | $C4 | $A0 | 2 |
| 4 | $140 | $84 | 1 |
| 5 | $1A0 | $6C | 3 |
| 6 | $F0 | $54 | 1 |
| 7 | $160 | $3C | 3 |
| 8 | $7C | $28 | 2 |
| 9 | $20 | $0C | 1 |

**Plane swap:** During boss event, BG draws on Plane A (VRAM_Plane_A_Name_Table) and FG on Plane B. V-scroll is inverted: `V_scroll_value = Camera_Y_pos_BG_copy`, `V_scroll_value_BG = Camera_Y_pos_copy`.

**Confidence:** HIGH

### Palette Data Files

- **`Levels/FBZ/Palettes/FBZ BG Indoors.bin`** -- 16 bytes (8 colors, written to palette line 4, colors 2-9)
- **`Levels/FBZ/Palettes/FBZ BG Outdoors.bin`** -- 16 bytes (8 colors, same destination)

## Animated Tiles

FBZ uses the generic `AnimateTiles_DoAniPLC` handler (no custom `Animated_Tiles_FBZ` routine). All animation is driven purely by AniPLC script data.

### AniPLC_FBZ1 (Act 1)

**Disassembly location:** `sonic3k.asm` line 55807

5 scripts:

#### Script 0: Large panel animation
- **Source art:** `ArtUnc_AniFBZ__0` (8192 bytes = 256 tiles total)
- **Destination VRAM tile:** $210
- **Frame count:** 2
- **Frame duration:** $3F (63 frames)
- **Tiles per frame:** $20 (32 tiles = 1024 bytes)
- **Frame offsets:** 0, 0 (same frame repeated -- effectively static in Act 1)
- **Notes:** Act 1 only uses 2 frames of the 8-frame art; likely a slower version of the Act 2 animation
- **Confidence:** HIGH

#### Script 1: Small mechanism animation A
- **Source art:** `ArtUnc_AniFBZ__1` (768 bytes = 24 tiles)
- **Destination VRAM tile:** $230
- **Frame count:** 6
- **Frame duration:** 7 (8 frames)
- **Tiles per frame:** 8 tiles (256 bytes)
- **Frame offsets:** 0, 8, $10, 0, 8, $10 (3 unique frames, looped twice)
- **Confidence:** HIGH

#### Script 2: Piston/cylinder animation
- **Source art:** `ArtUnc_AniFBZ__2` (4096 bytes = 128 tiles)
- **Destination VRAM tile:** $238
- **Frame count:** 8
- **Frame duration:** 1 (2 frames -- fast)
- **Tiles per frame:** $10 (16 tiles = 512 bytes)
- **Frame offsets:** 0, $10, $20, $30, $40, $50, $60, $70
- **Confidence:** HIGH

#### Script 3: Small mechanism animation B
- **Source art:** `ArtUnc_AniFBZ__3` (512 bytes = 16 tiles)
- **Destination VRAM tile:** $200
- **Frame count:** 2
- **Frame duration:** 7 (8 frames)
- **Tiles per frame:** 8 tiles (256 bytes)
- **Frame offsets:** 0, 8
- **Confidence:** HIGH

#### Script 4: Small mechanism animation C
- **Source art:** `ArtUnc_AniFBZ__4` (768 bytes = 24 tiles)
- **Destination VRAM tile:** $208
- **Frame count:** 6
- **Frame duration:** 7 (8 frames)
- **Tiles per frame:** 8 tiles (256 bytes)
- **Frame offsets:** 0, 8, $10, 0, 8, $10 (3 unique frames, looped twice)
- **Confidence:** HIGH

### AniPLC_FBZ2 (Act 2)

**Disassembly location:** `sonic3k.asm` line 55844

5 scripts (same art sources, same VRAM destinations, some different frame counts/durations):

#### Script 0: Large panel animation (expanded)
- **Source art:** `ArtUnc_AniFBZ__0` (8192 bytes)
- **Destination VRAM tile:** $210
- **Frame count:** 8 (full animation in Act 2)
- **Frame duration:** 1 (2 frames -- fast)
- **Tiles per frame:** $20 (32 tiles)
- **Frame offsets:** 0, $20, $40, $60, $80, $A0, $C0, $E0
- **Notes:** Uses all 8 frames of the art, unlike Act 1's static 2-frame version
- **Confidence:** HIGH

#### Scripts 1-4: Identical to Act 1
Same parameters as Act 1 scripts 1-4.

### VRAM Tile Destinations Summary

| VRAM Tile Range | Script | Art Source |
|-----------------|--------|------------|
| $200-$207 | Script 3 | `ArtUnc_AniFBZ__3` |
| $208-$20F | Script 4 | `ArtUnc_AniFBZ__4` |
| $210-$22F | Script 0 | `ArtUnc_AniFBZ__0` |
| $230-$237 | Script 1 | `ArtUnc_AniFBZ__1` |
| $238-$247 | Script 2 | `ArtUnc_AniFBZ__2` |

## Palette Cycling

### AnPal_FBZ (both acts)

**Disassembly location:** `sonic3k.asm` line 3370

```asm
AnPal_FBZ:
    tst.b  (Level_frame_counter+1).w
    bne.s  locret_244C
    bchg   #0,(_unkF7C1).w
locret_244C:
    rts
```

**This is NOT traditional palette cycling.** FBZ's `AnPal` routine toggles bit 0 of RAM address `_unkF7C1` every 256 frames (when `Level_frame_counter` low byte == 0). This acts as a **magnetic polarity flag** used by `Obj_FBZMagneticSpikeBall` and `Obj_FBZMagneticPlatform` objects.

**Effect on objects:**
- `_unkF7C1 == 0` (polarity A): Magnetic spike balls fall (gravity), magnetic platforms in default state
- `_unkF7C1 != 0` (polarity B): Magnetic spike balls rise (anti-gravity), magnetic platforms in activated state

**No palette colors are written.** The name "AnPal" is inherited from the dispatch table structure, but this routine performs no palette animation.

**Note:** In `s3.asm` (Sonic 3 standalone), `AnPal_FBZ` is a bare `rts` -- the magnetic toggle was added in the S&K combined ROM.

**Confidence:** HIGH

## Notable Objects

| Object | Label | Description | Zone-Specific | Notes |
|--------|-------|-------------|---------------|-------|
| Wire Cage | `Obj_FBZWireCage` (77580) | Hanging cage transport | Yes | Multiple subtypes |
| Wire Cage Stationary | `Obj_FBZWireCageStationary` (77861) | Fixed cage | Yes | |
| Floating Platform | `Obj_FBZFloatingPlatform` (78149) | Moving platform on rail | Yes | |
| Chain Link | `Obj_FBZChainLink` (78307) | Chain mechanism | Yes | |
| Magnetic Spike Ball | `Obj_FBZMagneticSpikeBall` (78797) | Polarity-switching hazard | Yes | Uses `_unkF7C1` flag |
| Magnetic Platform | `Obj_FBZMagneticPlatform` (78921) | Polarity-switching platform | Yes | Uses `_unkF7C1` flag |
| Snake Platform | `Obj_FBZSnakePlatform` (79076) | Segmented moving platform | Yes | Spawns child segments |
| Bent Pipe | `Obj_FBZBentPipe` (79242) | Tube transport | Yes | |
| Rotating Platform | `Obj_FBZRotatingPlatform` (79271) | Spinning platform | Yes | |
| DEZ Player Launcher | `Obj_FBZDEZPlayerLauncher` (79389) | Spring launcher | Yes | Shared with DEZ |
| Disappearing Platform | `Obj_FBZDisappearingPlatform` (79515) | Timed vanishing platform | Yes | Has animation data |
| Screw Door | `Obj_FBZScrewDoor` (79605) | Rotating door barrier | Yes | Has animation data |
| Spinning Pole | `Obj_FBZSpinningPole` (79731) | Grabable spinning pole | Yes | Unused mapping file |
| Propeller | `Obj_FBZPropeller` (79926) | Wind-generating fan | Yes | |
| Piston | `Obj_FBZPiston` (79957) | Crushing piston hazard | Yes | |
| Platform Blocks | `Obj_FBZPlatformBlocks` (80033) | Breakable blocks | Yes | |
| Missile Launcher | `Obj_FBZMissileLauncher` (80105) | Fires missiles | Yes | |
| Wall Missile | `Obj_FBZWallMissile` (80349) | Horizontal missile from wall | Yes | |
| Mine | `Obj_FBZMine` (80435) | Explosive mine | Yes | |
| Elevator | `Obj_FBZElevator` (80502) | Vertical transport | Yes | |
| Trap Spring | `Obj_FBZTrapSpring` (80562) | Spring trap with animation | Yes | |
| Flamethrower | `Obj_FBZFlamethrower` (80654) | Fire-breathing hazard | Yes | |
| Spider Crane | `Obj_FBZSpiderCrane` (80935) | Grabable crane | Yes | |
| Magnetic Pendulum | `Obj_FBZMagneticPendulum` (81106) | Swinging pendulum | Yes | |
| Exit Door | `Obj_FBZExitDoor` (149227) | End boss exit | Yes | |
| Exit Hall | `Obj_FBZExitHall` (182234) | End-of-zone hallway | Yes | |
| Egg Capsule | `Obj_FBZEggPrison` (187030) | Zone-specific capsule | Yes | |
| Spring Plunger | `Obj_FBZSpringPlunger` (187089) | Miniboss-related spring | Yes | |
| Outdoor BG Motion | `Obj_FBZOutdoorBGMotion` (109217) | BG bobbing oscillation | Yes | Persistent object |
| Boss Event Control | `Obj_FBZEndBossEventControl` (109820) | Act 2 boss transition | Yes | Solid platform |
| Boss Pillar | `Obj_FBZBossPillar` (109907) | Act 2 decorative pillars | Yes | |
| Cloud | `Obj_FBZCloud` (109999) | Act 2 boss BG clouds | Yes | 10 instances |

### Badnik PLCs (PLCKosM_FBZ)

**Disassembly location:** `sonic3k.asm` line 64381

| Art Tile | KosinskiM Art | Name |
|----------|--------------|------|
| ArtTile_Blaster | ArtKosM_Blaster | Blaster badnik |
| ArtTile_Technosqueek | ArtKosM_Technosqueek | Technosqueek badnik |
| ArtTile_FBZButton | ArtKosM_FBZButton | FBZ button object |

## Cross-Cutting Concerns

- **Water:** Not present. No `Water_flag` references in FBZ routines.
- **Screen shake:** Present in Act 2 boss event. `Screen_shake_flag` set to $FF (constant shake) during boss transition sequence. FBZ2_ScreenEvent applies `Screen_shake_offset` to `Camera_Y_pos_copy` every frame. Cleared when end boss loads.
- **Act transition:** Seamless scroll from Act 1 to Act 2. Triggered by `Events_fg_5` (set by standard miniboss defeat handler). Offsets all positions by -$2E00 X, loads FBZ2 layout and palette.
- **Character paths:** Minimal differences. Act 2 subboss loads EggRobo art for Knuckles. Act 2 boss event sets different Camera_min_Y_pos for Tails alone ($40 vs $3C). No major route differences.
- **Dynamic tilemap:** Extensive. The indoor/outdoor layout modification system in both acts dynamically swaps foreground tile regions based on player position. 6 regions in Act 1, 1 region in Act 2.
- **PLC loading:**
  - Act 1 init: PLC $1A/$1B (FBZ1 level art: `ArtNem_FBZMisc`, `ArtNem_FBZOutdoors`, `ArtNem_FBZEggCapsule`)
  - Act transition: PLC $1C (FBZ2 level art: `ArtNem_FBZMisc`, `ArtNem_FBZMisc2`, `ArtNem_FBZEggCapsule`)
  - Miniboss: `PLC_BossExplosion` + `ArtKosM_FBZMiniboss` (queued)
  - Subboss: `PLC_FBZ2Subboss_SonicTails` or `PLC_FBZ2Subboss_Knuckles` (character-dependent), then `PLCKosM_FBZ2Subboss` on defeat (cloud + pillar art)
  - End boss: PLC $6F (`ArtNem_FBZEndBoss`, `ArtNem_FBZRobotnikHead`, `ArtNem_FBZEndBossFlame`, `ArtNem_RobotnikShip`, `ArtNem_BossExplosion`, `ArtNem_EggCapsule`)
  - Boss event setup: `ArtKosM_FBZCloud` and `ArtKosM_FBZBossPillar` queued via KosinskiM
- **Unique mechanics:**
  - **Indoor/outdoor system:** The primary FBZ mechanic. Player position triggers layout modifications that swap foreground tiles and background plane content, with accompanying palette mutations. State tracked via `Events_bg+$00` (layout mod index), `Events_bg+$02` (FG indoor/outdoor), `Events_bg+$04` (BG indoor/outdoor).
  - **Magnetic polarity:** `_unkF7C1` flag toggled every 256 frames by `AnPal_FBZ`. Controls magnetic spike balls (gravity vs anti-gravity) and magnetic platforms.
  - **Boss event plane swap:** During Act 2 end boss, FG is drawn on Plane B and BG on Plane A, with V-scroll values inverted.
  - **Background collision:** `Background_collision_flag` set during boss transition -- enables collision detection against BG plane tiles while the boss event control platform carries the player.

## Dependency Map

### Events -> Animated Tiles
- No direct dependencies. AniPLC scripts run independently of event state.
- No VRAM conflicts: AniPLC targets tiles $200-$247; event PLCs load to different tile ranges (boss art, misc art).

### Events -> Palette Cycling
- No dependency. `AnPal_FBZ` toggles `_unkF7C1` independently of event state; events only write BG palette (line 4, colors 2-9).

### Events -> Parallax
- **`Events_bg+$04`** (indoor/outdoor flag): Events BG change routines set/clear this flag -> `FBZ_Deform` checks it to select indoor vs outdoor deformation mode
- **`Events_bg+$08`** (bobbing offset): Written by `Obj_FBZOutdoorBGMotion` -> `FBZ_Deform` outdoor mode reads it for BG Y position
- **Screen shake:** Events set `Screen_shake_flag` during Act 2 boss transition -> `ShakeScreen_Setup` generates `Screen_shake_offset` -> FBZ2_ScreenEvent applies offset to Camera_Y_pos_copy, FBZ2_CloudDeform accounts for shake in BG position
- **Boss event mode:** When `Events_routine_fg != 0`, BG event switches to cloud deform mode (replaces normal FBZ_Deform)
- **`_unkEE98` / `_unkEE9C`** (BG camera offsets): Boss event control increments these -> Cloud deform reads them for BG position calculation

### AnPal -> Objects
- **`_unkF7C1`** (magnetic polarity): `AnPal_FBZ` toggles this flag -> `Obj_FBZMagneticSpikeBall` and `Obj_FBZMagneticPlatform` read it to determine gravity direction and active state

### VRAM Ownership Table

| VRAM Tile Range | Owner | Condition | Notes |
|-----------------|-------|-----------|-------|
| $200-$247 | AniPLC scripts 0-4 | Always active | Animated level tiles |
| ArtTile_FBZMisc | PLC $1A/$1B or $1C | Level load | Zone-specific objects |
| ArtTile_FBZOutdoors | PLC $1A/$1B | Act 1 only | Outdoor decoration art |
| ArtTile_FBZMisc2 | PLC $1C/$1D | Act 2 only | Act 2-specific misc art |
| ArtTile_FBZMiniboss | KosinskiM queue | Miniboss spawn | Miniboss art |
| ArtTile_FBZ2Subboss | PLC_FBZ2Subboss | Subboss spawn | Subboss art (character-specific) |
| ArtTile_FBZCloud | KosinskiM queue | Boss event setup | Cloud sprite art |
| ArtTile_FBZBossPillar | KosinskiM queue | Boss event setup | Pillar sprite art |
| ArtTile_FBZEndBoss | PLC $6F | End boss spawn | End boss art |

### Palette Ownership Table

| Palette Entry | Owner | Condition | Notes |
|---------------|-------|-----------|-------|
| Line 4, colors 2-9 | Events (BG change) | Indoor/outdoor transition | `Pal_FBZBGIndoors` or `Pal_FBZBGOutdoors` (16 bytes) |
| Line 4, color 1 | Events (boss setup) | Boss event | Set to $EEE (white) |
| Line 1 (full) | Boss objects | Boss active | `Pal_FBZMiniboss`, `Pal_FBZ2Subboss`, or `Pal_FBZEndBoss` |

No palette conflicts -- events write line 4 (BG colors) and boss writes line 1 (boss colors). `AnPal_FBZ` writes NO palette entries.

## Implementation Notes

### Priority Order
1. **Parallax (FBZ_Deform)** -- Foundation for all visual rendering. Indoor/outdoor mode selection via `Events_bg+$04` is the critical branch. Implement both indoor scatter-fill deformation and outdoor cloud auto-scroll.
2. **Animated Tiles (AniPLC)** -- Straightforward generic handler with 5 scripts. No custom routine needed -- just register AniPLC script data.
3. **Indoor/Outdoor BG switching (BackgroundEvent)** -- The palette mutations and BG plane refresh during transitions. Depends on parallax being implemented first.
4. **Layout Modifications (ScreenEvent)** -- FG tile swapping for indoor/outdoor regions. Complex but self-contained. Requires understanding of layout row addressing.
5. **Act 1-to-2 Transition** -- Seamless scroll transition. Standard pattern but FBZ-specific offset ($2E00 X).
6. **Magnetic Polarity System** -- Simple flag toggle in AnPal + object behavior. Low priority unless implementing magnetic objects.
7. **Act 2 Boss Event** -- Complex plane swap, cloud deform, screen shake, BG collision. High complexity, implement last.

### Dependencies
- Parallax requires `Obj_FBZOutdoorBGMotion` to be registered for outdoor BG bobbing
- BG switching requires parallax to be implemented (calls `FBZ_Deform` during transitions)
- Act transition requires PLC $1C to be registered
- Boss event requires cloud art (`ArtKosM_FBZCloud`) and pillar art (`ArtKosM_FBZBossPillar`) to be loadable
- Magnetic objects require `AnPal_FBZ` polarity toggle to be implemented

### Known Risks
- **Layout modification complexity (MEDIUM risk):** The FG tile swapping in ScreenEvent uses direct layout row addressing with computed offsets from `a3` (layout pointers). Implementing this requires understanding the engine's layout memory structure. Each of the 6 Act 1 regions has unique source/destination offsets and tile copy dimensions.
- **Boss event plane swap (MEDIUM risk):** The Act 2 boss reverses which plane draws FG vs BG. This requires special handling in the engine's tile drawing pipeline -- `d7 = VRAM_Plane_B_Name_Table` for FG, and `VRAM_Plane_A_Name_Table` for BG, with inverted V-scroll values.
- **Indoor deform scatter-fill (LOW risk):** The `FBZ_InBGDeformIndex` table uses a non-sequential scatter-fill pattern that maps speed groups to specific HScroll_table entries. This is a known pattern (similar to other zones) but requires careful index-to-scanline mapping.
- **BG collision during boss transition (LOW risk):** `Background_collision_flag` enables collision against BG plane tiles. This is a rarely-used engine feature that may need verification.
