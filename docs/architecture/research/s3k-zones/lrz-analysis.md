# S3K LRZ Zone Analysis

## Summary

- **Zone:** Lava Reef Zone (LRZ)
- **Zone Index:** 0x09 (LRZ1 = $0900, LRZ2 = $0901); Boss act = $1600 (LRZ3)
- **Zone Set:** SKL (zones 7-13)
- **Acts:** 1, 2, and Boss (LRZ3 -- transitions to Hidden Palace Zone for Knuckles)
- **Water:** No (DynamicWaterHeight_Null2 for both acts; no CheckLevelForWater match)
- **Palette Cycling:** Yes -- 3 channels for Act 1, 3 channels for Act 2 (2 shared, 1 act-specific each); separate 2-channel routine for LRZ3 boss act with gating flag
- **Animated Tiles:** Yes -- custom AnimateTiles routines per act (BG position-dependent split-DMA) + AniPLC scripts per act; LRZ3 shares channel 0 only
- **Character Branching:** Yes -- Knuckles gets modified BG in Act 1 (different chunk byte at init), LRZ2 cutscene boulder sequence, LRZ3 Knuckles deletes Death Egg BG object
- **Seamless Transition:** Yes -- Act 1 to Act 2 transition with layout reload, camera offset -$2C00 X, rock placement reset
- **Screen Shake:** Yes (both acts and LRZ3 call ShakeScreen_Setup)
- **Special VInt Routines:** Yes (LRZ3 boss act uses $04/$0C for cell-based VScroll on/off during boss arena)
- **Layout Modification:** Yes (Act 1 has 3 dynamic BG layout regions via sub_56DCA; LRZ3 has chunk writes via Events_bg+$0C)
- **VScroll Deformation:** Yes (LRZ3 boss arena uses cell-based VScroll with 20 columns of $10 pixels)
- **Rock Sprite System:** Yes -- custom Draw_LRZ_Special_Rock_Sprites renders foreground rock decorations from binary placement data, unique to LRZ
- **Auto-scroll:** Yes (LRZ3 boss act uses Special_events_routine $14 for forced-scroll boss sequence with 7-stage state machine)
- **FG Deformation (heat shimmer):** Yes (LRZ3 boss act uses AIZ2_SOZ1_LRZ3_FGDeformDelta sine table for per-scanline FG/BG HScroll shimmer)

## Current Engine Status (2026-08-08)

`Sonic3kLevelEventManager` now models the native
`SpawnLevelMainSprites` falling introduction for LRZ1 when the player is not
Knuckles. The owning ROM path compares `$0900` at
`sonic3k.asm:8161-8165`; `Player_mode == 3` (Knuckles) skips `loc_68A6`, while
other LRZ1 modes fall through to the animation `$1B` / `Status_InAir` writes at
`sonic3k.asm:8172-8178`. The engine mirrors that gate after player spawn and
`TestS3kLrzFallingIntroBootstrap` covers Sonic + Tails, Tails alone, Knuckles,
and LRZ2. The `lrz_completerun` payload is present, but a direct replay attempt
currently stops before gameplay while compiling its final v5 hardware-timing
row (`unsupported-held-row-POST`, trace rows 72/78 continuing through
`raw_frame=38746`; first unsupported row `raw_frame=38719`); no route result is claimed
until that fixture-level blocker is resolved.

The same ROM routine also reaches `loc_68A6` for `$1600` (the LRZ boss slot),
not for SSZ; that boss slot is outside the standard LRZ1/LRZ2 bootstrap handled
by this remediation.

## Events

### Act 1 (Dynamic_Resize)

**Disassembly location:** `sonic3k.asm` line 38823 (LevelResizeArray entry)

LRZ1 maps to `No_Resize` in the LevelResizeArray. There is no Dynamic_Resize routine for Act 1.

### Act 2 (Dynamic_Resize)

**Disassembly location:** `sonic3k.asm` line 38824 (LevelResizeArray entry)

LRZ2 also maps to `No_Resize`. There is no Dynamic_Resize routine for Act 2.

### Act 1 Screen Events (LRZ1_ScreenInit / LRZ1_ScreenEvent)

**Disassembly location:** `sonic3k.asm` line 115189 (init), line 115194 (event)

**LRZ1_ScreenInit:**
1. Calls `Reset_TileOffsetPositionActual`
2. Calls `Refresh_PlaneFull`
3. No zone-specific initialization (minimal init)

**LRZ1_ScreenEvent:**
1. Adds `Screen_shake_offset` to `Camera_Y_pos_copy`
2. Checks `Events_bg+$0C`:
   - If positive: writes chunk byte $9C at layout offset `$40(a3)` position `$A`, clears flag, refreshes screen
   - If negative: writes chunk bytes $44/$00/$4A at `$38(a3)+$1D` and $3E/$00/$4B at `$3C(a3)+$1D`, clears flag, refreshes screen
   - If zero: normal operation via `DrawTilesAsYouMove`

**Purpose:** The `Events_bg+$0C` mechanism handles dynamic layout chunk replacement triggered by external objects (likely collapsing terrain or doors). The positive/negative value determines which set of chunks to write.

**Confidence:** HIGH -- simple screen event with conditional chunk writes.

### Act 2 Screen Events (LRZ2_ScreenInit / LRZ2_ScreenEvent)

**Disassembly location:** `sonic3k.asm` line 115647 (init), line 115652 (event)

**LRZ2_ScreenInit:**
1. Calls `Reset_TileOffsetPositionActual`
2. Calls `Refresh_PlaneFull`
3. No zone-specific initialization (minimal init, same as Act 1)

**LRZ2_ScreenEvent:**
1. Adds `Screen_shake_offset` to `Camera_Y_pos_copy`
2. Calls `DrawTilesAsYouMove`
3. No state machine, no conditional logic

**Confidence:** HIGH -- trivially simple screen event.

### Act 1 Background Events (LRZ1_BackgroundInit / LRZ1_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 115226 (init), line 115254 (event)

**LRZ1_BackgroundInit:**
1. Copies Camera_X_pos_BG_copy value from `(a3)` to 25 longword entries at `HScroll_table+$1C` through `HScroll_table+$7C` (fills deform entries with base value)
2. **Knuckles branch:** If `Player_mode == 3`, modifies chunk at BG layout position `4(a3)` -- writes $F6 at byte offset 4 (removes a visual element from BG for Knuckles)
3. Calls `LRZ1_Deform` to compute initial parallax state
4. Calls `Reset_TileOffsetPositionEff`
5. Clears `HScroll_table` (longword) and sets `HScroll_table+$006` to d2
6. Clears `HScroll_table+$008` (longword)
7. Draws BG plane using `LRZ1_BGDrawArray` via `Refresh_PlaneTileDeform`
8. Applies `LRZ1_BGDeformArray` via `ApplyDeformation`

**LRZ1_BackgroundEvent (Events_routine_bg):**

| Stage | Offset | Label | Action | Notes |
|-------|--------|-------|--------|-------|
| 0 | $00 | loc_56BD2 | Check `Events_fg_5`: if set, queue LRZ2 secondary art (128x128, 16x16, 8x8 KosM), load PLC $30, advance to stage 12. Otherwise: run sub_56DCA (layout region check), LRZ1_Deform, Draw_BG, apply deformation, ShakeScreen_Setup | Normal scrolling + seamless transition trigger |
| 4 | $04 | loc_56C6E | Run sub_56DCA, sub_56DAC, DrawBGAsYouMove, PlainDeformation, ShakeScreen_Setup | Locked BG scrolling for lava dome interior regions |
| 8 | $08 | loc_56C88 | Run LRZ1_Deform, Draw_PlaneVertBottomUpComplex. When complete: clear Events_bg+$02, reset Events_routine_bg to 0, continue normal drawing | BG refresh (returning from locked region) |
| 12 | $0C | loc_56CAA | **Seamless transition to Act 2.** Wait for Kos_modules_left == 0, then: set zone to $901, clear routines, Load_Level, LoadSolids, offset all by -$2C00 X, reset tile positions, clear Events_routine_bg | Full seamless transition |

**Post-transition drawing (loc_56D16):**
Runs every frame during and after transition: LRZ1_Deform, Draw_BG with LRZ1_BGDrawArray, ApplyDeformation with LRZ1_BGDeformArray, ShakeScreen_Setup.

**Seamless Transition Details (Stage 12):**
- Sets `Current_zone_and_act = $901` (LRZ Act 2)
- Clears: `Dynamic_resize_routine`, `Object_load_routine`, `Rings_manager_routine`, `Boss_flag`, `Respawn_table_keep`, switches, `LRZ_rocks_routine`
- Calls `Load_Level` and `LoadSolids`
- X offset: -$2C00 applied to Player 1, Player 2, all objects, Camera X, Camera X copy, Camera min X, Camera max X
- Calls `Reset_TileOffsetPositionActual`
- Resets `Events_routine_bg` to 0

**Confidence:** HIGH -- clear state machine with well-understood transition.

### Act 1 Layout Region System (sub_56DCA)

**Disassembly location:** `sonic3k.asm` line 115452

Checks Player 1 position against 3 rectangular regions. When the player enters or exits a region, transitions the BG to/from a locked parallax mode.

**Region table (word_56F88):**

| Region | X Min | X Max | Y Min | Y Max | Direction Check | Exit Threshold |
|--------|-------|-------|-------|-------|-----------------|----------------|
| 0 | $1AC0 | $1B40 | $840 | $8C0 | X >= threshold to enter | X to exit |
| 1 | $1B00 | $2240 | $2340 | $840 | X < threshold to enter | X to exit |
| 2 | $880 | $22C0 | $20C0 | $2180 | Y >= threshold to enter | Y to exit |

Extra data after: `$740, $800, $7A0` (exit thresholds for each region).

**Region entry (loc_56E40):**
1. Sets `Events_bg+$00` flag
2. Allocates `Obj_56EA0` (lava platform object)
3. Calls `sub_56DAC` (sets BG position to locked parallax)
4. Resets tile offset positions
5. Advances `Events_routine_bg` by 4 (to locked BG mode)
6. Jumps to locked BG drawing (loc_56C76)

**Region exit (loc_56E66):**
1. Clears `Events_bg+$00` flag
2. Saves current BG position to `Events_bg+$02/$04`
3. Calls `LRZ1_Deform`
4. Resets tile positions
5. Sets up bottom-up BG refresh (delayed draw)
6. Advances `Events_routine_bg` by 4 (to refresh mode)

**Locked BG Position (sub_56DAC):**
```
BG_X = Camera_X_copy - $1500
BG_Y = Camera_Y_copy - $788 + _unkEE9C
```
This positions the BG to show a specific large lava dome/cave interior when the player is in these regions.

**Confidence:** MEDIUM -- the 3-region system is clear, but the exact visual purpose of each region requires testing.

### Lava Platform Object (Obj_56EA0)

**Disassembly location:** `sonic3k.asm` line 115563

Allocated when entering a BG layout region. Acts as a solid platform that oscillates vertically.

- **Init:** height_pixels = $80, x_pos = $1E80, status bit 7 set, speed = -$4000, clears _unkEE9C
- **Main loop:** Checks `Events_bg+$00`; deletes if cleared (player exited region)
- **Movement:** Oscillates via acceleration/deceleration pattern:
  - Adds velocity to position, checks bounds
  - When reaching bottom: reverses with velocity $C000
  - When reaching top: reverses with velocity -$C000
  - Acceleration: +/- $100 per frame
- **Result:** `_unkEE9C` = negated swap of position (used by sub_56DAC for BG Y offset)
- **Solid interaction:** Width $280, height $80, Y-clearance $6C; uses `SolidObjectTop`
- **Lava damage:** Players without Fire Shield standing on this take damage via `sub_24280` (HurtCharacter with upward knockback)
- **P2 interaction:** Player 2 also checked for damage (but no fire shield check for P2)

**Confidence:** HIGH -- well-understood oscillating lava platform with heat damage.

### Act 2 Background Events (LRZ2_BackgroundInit / LRZ2_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 115658 (init), line 115675 (event)

**LRZ2_BackgroundInit:**
1. Allocates `loc_5711E` object (Death Egg BG sprite), stores address at `Events_bg+$06`
2. Sets `Events_routine_bg = 8` (Normal state)
3. Calls `sub_57082` (LRZ2_Deform equivalent)
4. Resets tile offset positions
5. Refreshes full BG plane
6. Applies `LRZ2_BGDeformArray` via ApplyDeformation

**LRZ2_BackgroundEvent (Events_routine_bg):**

| Stage | Offset | Label | Action | Notes |
|-------|--------|-------|--------|-------|
| 0 | $00 | loc_5700C | Transition from Act 1: allocate Death Egg BG object, run sub_57082, set up bottom-up BG refresh ($E0 row offset, 15 rows), advance to stage 4 | Only when arriving via seamless transition |
| 4 | $04 | loc_57040 | Run sub_57082, draw BG rows bottom-up until complete, then advance to stage 8 | BG refresh during transition |
| 8 | $08 | loc_57058 | Normal: run sub_57082, Draw_TileRow, ApplyDeformation with LRZ2_BGDeformArray, ShakeScreen_Setup | Standard scrolling |

**Confidence:** HIGH -- straightforward 3-state BG handler.

### Death Egg BG Object (loc_5711E)

**Disassembly location:** `sonic3k.asm` line 115806

A background sprite object that renders the Death Egg in LRZ2's BG layer.

- **Knuckles check:** If `Player_mode == 3`, clears `Events_bg+$06` and deletes self (Knuckles doesn't see the Death Egg in BG)
- **Init:** height $40, width $50, priority $380, art tile = `ArtTile_LRZ2DeathEggBG` palette 3, mappings = `Map_LRZ2DeathEggBG`
- **Position tracking:** Updated by `sub_57082` -- X offset is `$678 - HScroll_table+$004`, clamped if < -$7E0 (set to 0); Y offset is `$C0 - Camera_Y_pos_BG_copy`
- **Art loading:** On first visible frame, queues `ArtKosM_LRZ2DeathEggBG` via `Queue_Kos_Module`
- **Draw:** Calls `Draw_Sprite` when visible (x_pos != 0)

**Confidence:** HIGH -- simple BG decoration sprite.

### LRZ3 Screen Events (LRZ3_ScreenInit / LRZ3_ScreenEvent)

**Disassembly location:** `sonic3k.asm` line 119305 (init), line 119344 (event)

**LRZ3_ScreenInit:**
1. Clears `Camera_max_X_pos`
2. **Post-boss camera lock:** If `Player_1 X >= $480`:
   - Loads `Pal_LRZBossFire` into `Target_palette_line_2` (48 words = 3 palette lines)
   - Writes $9C0 at Target_palette_line_4+$30 and $36C at +$34 (additional color entries)
   - Sets camera position: X = $920, Y = $2F0 (locked view)
   - Sets camera bounds: min/max X = $920, min/max Y = $2F0
   - Sets `Special_events_routine = $14` (auto-scroll handler)
   - Sets `Events_bg+$00 = $10`, `Events_bg+$02 = $2D` (auto-scroll state/delay)
   - Sets `Events_routine_fg = $0C` (skip to post-transition state)
3. Calls `Reset_TileOffsetPositionActual` and `Refresh_PlaneFull`

**LRZ3_ScreenEvent (Events_routine_fg):**

| Stage | Offset | Label | Action | Notes |
|-------|--------|-------|--------|-------|
| 0 | $00 | loc_59B1C | Restore ring count from Act3_ring_count and timer from Act3_timer (carried from Act 2), advance to stage 4 | Initial setup on entry from Act 2 |
| 4 | $04 | loc_59B46 | Force HUD life count update. Check Events_fg_4: if set, write chunk IDs ($16/$15/$16/$15/$16) at layout position `$24(a3)`, advance to stage 8, refresh screen. Otherwise: check if Camera Y reached max, lock Camera_min_Y to match | Pre-boss layout modification |
| 8 | $08 | loc_59B88 | Check Events_fg_4: if set, clear Camera X/Y subpixels, set Special_events_routine = $14 (restart auto-scroll), advance to stage 12 | Re-enable auto-scroll after boss transition |
| 12 | $0C | loc_59BA4 | **Layout chunk replacement system.** If Events_bg+$0C is set: extract row/column from it, write chunk $17 at computed layout position (2 adjacent chunks), refresh 5 tile rows. Then call DrawTilesAsYouMove | Standard operation + dynamic chunk writes |

**Chunk replacement system details:**
- `Events_bg+$0C` encodes position: high bits (shifted right 7) = column offset, `Events_bg+$0E` encodes row
- Row is decoded as `(value >> 5) & $FFFC` = index into layout row pointer table
- Writes chunk $17 at the target position and +1 (two adjacent chunks)
- Refreshes 5 tile rows of $10 pixels starting from the target Y, column $A offset $30

**Confidence:** MEDIUM-HIGH -- stage transitions clear, chunk replacement system understood.

### LRZ3 Background Events (LRZ3_BackgroundInit / LRZ3_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 119441 (init), line 119468 (event)

**LRZ3_BackgroundInit:**
1. Allocates `Obj_59FC4` (lava rising platform object for boss arena)
2. Fills `HScroll_table` with `$600050` (BG X = $60, FG X = $50) for 20 longword entries
3. Fills `HScroll_table+$100` with $30 for 48 longword entries (deformation amplitude data)
4. Copies camera BG value to `$7C(a3)`
5. Calls `sub_59D82` (LRZ3 BG position calculation)
6. Resets tile offsets, refreshes full BG plane
7. Calls `sub_59DDE` (heat shimmer deformation)

**LRZ3_BackgroundEvent (Events_routine_bg):**

| Stage | Offset | Label | Action | Notes |
|-------|--------|-------|--------|-------|
| 0 | $00 | loc_59C60 | Wait for Camera Y >= $500, then advance to stage 4. Otherwise: run sub_59D82, DrawBGAsYouMove, apply Events_bg+$04/$06 override if set, sub_59DDE | Pre-boss BG scrolling |
| 4 | $04 | loc_59C8C | When Camera Y >= $500 AND Camera X == $A00 AND Camera Y at max: lock camera (max X = $A00, min Y = Camera Y), spawn Obj_LRZEndBoss, set Special_V_int_routine = $04 (VScroll On), advance to stage 12. If Camera Y < $500: save BG pos, run sub_59D82, set up top-down BG refresh, advance to stage 8. Otherwise: run sub_59DA2, DrawBGAsYouMove, sub_59DDE | Boss spawn trigger + BG transition |
| 8 | $08 | loc_59D02 | Run sub_59D82, draw BG rows top-down until complete, then reset Events_bg+$04, reset to stage 0 | BG refresh returning from boss area |
| 12 | $0C | loc_59D24 | **Boss arena mode.** Check Events_fg_5 AND Events_bg+$0A: if both set, transition to stage 16 (clear VScroll). Otherwise: run sub_59DBC (boss arena BG calc), DrawTilesVDeform2 with word_5A106 (VScroll array), sub_59DDE (heat shimmer), copy VScroll buffer to VSRAM | Cell-based VScroll boss arena |
| 16 | $10 | loc_59D74 | Clear Events_fg_5, set Special_V_int_routine = $0C (VScroll Off), advance to stage 20. Run sub_59DA2, DrawBGAsYouMove, sub_59DDE | Post-boss BG restoration |

**Boss arena BG calculation (sub_59DBC):**
```
BG_X = Camera_X_copy - $700
BG_Y = Camera_Y_copy - $500
```
Then iterates 20 columns: reads amplitude byte from `HScroll_table+$113` (stride 8), subtracts $30 from it, adds BG_X, writes to `HScroll_table` (stride 4). This creates the per-column wobble effect for the lava arena BG.

**VScroll array (word_5A106):**
```
$310, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $10, $7FFF
```
Starting at X=$310, 19 columns of $10 pixels each (304 pixels total). Covers the full screen width for cell-based VScroll.

**Confidence:** MEDIUM -- boss arena VScroll and BG positioning understood, but lava platform interaction with BG columns requires careful implementation.

### LRZ3 Auto-Scroll System (Special_events_routine $14)

**Disassembly location:** `sonic3k.asm` line 119698

Activated by `Special_events_routine = $14`. This is a 7-stage forced-scroll state machine that moves the camera through the boss act level.

**Delay mechanism:** Uses `Events_bg+$02` as a frame delay counter. Decrements each frame; when zero, proceeds to auto-scroll logic.

**State machine (Events_bg+$00):**

| Stage | Offset | Camera Trigger | X Speed | Y Speed | Notes |
|-------|--------|----------------|---------|---------|-------|
| 0 | $00 | Camera X >= $410 | $20000 (right) | 0 | Scroll right |
| 4 | $04 | Camera Y <= $330 | $16A00 (right) | -$16A00 (up) | Diagonal up-right |
| 8 | $08 | Camera X >= $650 | $20000 (right) | 0 | Scroll right |
| 12 | $0C | Camera Y <= $2F0 | $16A00 (right) | -$16A00 (up) | Diagonal up-right |
| 16 | $10 | Camera X >= $910 | $20000 (right) | 0 | Scroll right |
| 20 | $14 | Camera Y >= $320 | $1D900 (right) | $C400 (down) | Diagonal down-right |
| 24 | $18 | Camera X >= $BBF AND Player X >= $C50 | $20000 (right) then 0 | 0 | Final approach; locks arena at X=$A00-$BC0, Y max=$560, disables scroll lock, clears Special_events_routine |

**Camera update (loc_59F3C):**
Each frame during auto-scroll:
1. Adds Y speed to `Camera_Y_pos`, copies to min/max/copy
2. Adds X speed to `Camera_X_pos`, copies to min/max/copy
3. For each player: if pushing against left edge (X <= Camera_X + $10), force position and set ground_vel to scroll speed; if exceeding right edge (X >= Camera_X + $120), cap position. If player has `Status_Push` set and is at left edge, kills character.

**Confidence:** HIGH -- auto-scroll state machine is clear and well-structured.

### LRZ3 Lava Rising Platform (Obj_59FC4)

**Disassembly location:** `sonic3k.asm` line 119846

The main solid platform object in the LRZ3 boss arena. Acts as a rising/falling lava surface that the player stands on.

- **Init:** height_pixels = $C0, y_pos = $640, status bit 7 set
- **Boss arena mode (Events_routine_bg == $C):**
  - Reads `Events_bg+$0A` (amplitude), adjusts by +1/-1 based on `Events_bg+$09` flag (direction), capped at $80
  - Computes slope amount from amplitude: `d0 = amplitude >> 8` (fractional), `d1 = amplitude >> 8 / 2`
  - Fills `HScroll_table+$110` with slope values (176 entries for lava surface deformation), creating an asymmetric slope:
    - If `Events_bg+$08` clear: fills forward from +$110, then 16 entries backward from +$110
    - If `Events_bg+$08` set: fills backward from +$1B0, then 16 entries forward from +$1B0
  - Uses `SolidObjectTopSloped2` with width=$A0, x_pos=$AA0
  - Computes `Events_bg+$14` (platform push speed): `amplitude >> 7 + amplitude >> 8`, negated if `Events_bg+$08` clear
  - Players standing on platform get pushed horizontally by `Events_bg+$14` velocity
  - Players without Fire Shield (or without invincibility) standing on platform take lava damage via `sub_24280`
- **Normal mode (Events_routine_bg != $C):**
  - Standard solid platform: width=$180, height=$40, clearance=$30, x_pos=$B80
  - Uses `SolidObjectTop`
  - Same push/damage logic applies

**Confidence:** MEDIUM -- the slope deformation math is complex but the platform behavior is understood.

## Boss Sequences

### Act 1 Miniboss (Obj_LRZMiniboss)

- **Object:** `Obj_LRZMiniboss` at `sonic3k.asm` line 159996
- **Spawn:** `Check_CameraInRange` with bounds Y=$610-$810, X=$2B00-$2D00
- **Arena boundaries:** Y=$710, X=$2C00 (locked at arena center)
- **Music:** `mus_Miniboss`
- **PLC load:** `ArtKosM_LRZMiniboss` -> `ArtTile_LRZMiniboss`; `PLC_BossExplosion`
- **Palettes:** `Pal_LRZMiniboss1` (initial), `Pal_LRZMiniboss2` (active), `Pal_LRZMiniboss3` (defeat)
- **Art:** `Map_LRZMiniboss`, palette line 1, priority 1
- **HP:** 6 hits (collision_property = 6)
- **Routine stages:** 11 stages (init, art load wait, timer wait, rise, swing+track, swing wait, attack, land, post-attack)
- **AI pattern:**
  - Rises from lava with -$400 Y velocity, sfx_BossHand
  - Tracks player X position every 16 frames (moves at +/-$200 X)
  - Uses `Swing_UpAndDown` for oscillating motion
  - After timer expires ($BF frames of swinging), transitions to attack phase
  - Sets collision_flags = $B5 during attack
- **Child objects:** `ChildObjDat_78D84` (12 children), `ChildObjDat_78D8A` (12 children)
- **Palette script (word_78EAA):** 13-frame palette fade sequence on palette line 3 colors 1-5, transitioning through warm colors ($0EE/$0AE/$06E) down to dark ($222/$222/$224)
- **Confidence:** MEDIUM -- attack pattern and child objects need deeper reading for full implementation

### LRZ3 Boss (Obj_LRZEndBoss)

- **Object:** `Obj_LRZEndBoss` at `sonic3k.asm` line 161563
- **Spawn:** Created by LRZ3_BackgroundEvent stage 4 when Camera reaches X=$A00, Y at max
- **Arena boundaries:** Camera locked by LRZ3_ScreenInit (X=$920, Y=$2F0) and boss event (max X=$A00)
- **Music:** `mus_EndBoss` (set via Current_music+1)
- **PLC load:** `ArtKosM_LRZEndBoss` -> `ArtTile_LRZEndBoss`; `ArtKosM_LRZ3PlatformDebris` -> `ArtTile_LRZ3PlatformDebris`; `PLC_BossExplosion`
- **Palette:** `Pal_LRZEndBoss` via PalLoad_Line1
- **Art:** `ObjDat_LRZEndBoss` = `Map_LRZEndBoss`, `ArtTile_LRZEndBoss` palette line 1
- **HP:** 14 hits (collision_property = $E)
- **Boss flag:** Sets `Boss_flag = 1` when `_unkFA88` bit 0 is set
- **Routine stages:** 6 stages:
  1. Init: set position X=$B10 Y=$640, angle=-$12, Y velocity=-$580, play sfx_BossMagma, spawn child objects
  2. Rise: `MoveSprite`, wait for Y velocity to become non-negative, then cap at Y=$600
  3. Attack phase 1: 3 attack cycles ($39 count), swing motion
  4. Wait phase
  5. Attack phase 2
  6. Defeat
- **Initial behavior:** Boss emerges from lava below, rises to Y=$600, then begins attack pattern
- **Child objects:** `ChildObjDat_7A18C`
- **Post-draw:** Calls `sub_79F58` (damage/collision check) and `Draw_And_Touch_Sprite`
- **Confidence:** MEDIUM -- emergence/initial attack clear, full multi-phase AI needs deeper reading

### LRZ3 Autoscroll Boss (Obj_LRZ3Autoscroll)

- **Object:** `Obj_LRZ3Autoscroll` at `sonic3k.asm` line 160901
- **Spawn:** During LRZ3 cutscene sequence after Death Egg flash
- **PLC load:** `ArtKosM_LRZ3DeathEggFlash` -> `ArtTile_LRZ3DeathEggFlash`; `ArtKosM_LRZ3PlatformDebris` -> `ArtTile_LRZ3PlatformDebris`
- **Setup sequence:**
  1. Wait 60 frames, lock controls
  2. Wait for `_unkFAB8` bit 0 (cutscene signal)
  3. Unlock controls, init as visible object: X=-$40, Y=$460, X_vel=$100, start swing
  4. Timer $11F frames of approach, playing sfx_RobotnikSiren continuously
  5. Set `_unkFAB8` bit 1, stop X movement, wait $BF frames
  6. Set Events_fg_4 (triggers chunk writes in screen event)
  7. Transition to main boss phase
- **Art:** `ObjDat3_795D2`
- **Child objects:** `ChildObjDat_7961A`
- **Confidence:** MEDIUM -- autoscroll entry sequence understood, main boss AI phase separate

### LRZ3 Cutscene Sequence

**Disassembly location:** `sonic3k.asm` line 161280 (Death Egg flash sequence)

The sequence before the LRZ3 boss fight:
1. Sets `Palette_cycle_counters+$00 = $80` (disables AnPal_LRZ3 channel B)
2. Plays sfx_SuperTransform
3. Copies Normal_palette to Target_palette (1st line only)
4. Loads `Pal_LRZBossFire` into Target_palette_line_2
5. Spawns flash object (palette fade at rate 3)
6. Loads `ArtKosM_LRZ3Autoscroll` art
7. When flash completes: sets `Palette_cycle_counters+$00 = 1` (enables AnPal_LRZ3 channel B)
8. Sets `_unkFAB8` bit 0 (signals Autoscroll boss to begin)
9. Sets `Events_fg_4` (triggers chunk write in screen event)
10. Spawns sfx_SuperEmerald
11. Spawns CollapsingBridge at X=$60, Y=$4D0

## Parallax

### Act 1 (LRZ1_Deform)

**Disassembly location:** `sonic3k.asm` line 115384

This is a multi-band BG parallax for the lava cavern background.

**BG Y calculation:**
```
BG_Y = (Camera_Y_copy - Screen_shake_offset) / 8 + Screen_shake_offset
```

**BG X calculation:**
```
base_X = Camera_X_copy (as 16:16 fixed) / 8
slower_X = base_X / 4
```

**Band structure:**

The deform routine fills HScroll_table entries in two passes:

**Pass 1 (rear bands, HScroll_table+$01A down to $004):** 8 bands, each slower than the next
```
band[0] = base_X                    -> HScroll_table+$004 (= Camera_X_pos_BG_copy)
band[1] = base_X + slower_X         -> HScroll_table+$006
band[2] = base_X + 2*slower_X       -> HScroll_table+$008
...
band[7] = base_X + 7*slower_X       -> HScroll_table+$01A
```
Written in reverse order (from $01A down to $004), each band offset by `slower_X` from the previous.

**Pass 2 (front bands, HScroll_table+$01C to $02A):** 5 bands with doubled speed increment
```
slower_X2 = slower_X * 2
band[8] = base_X + 8*slower_X + slower_X2     -> HScroll_table+$01C
band[9] = band[8] + slower_X2                  -> HScroll_table+$01E
...
band[12] = band[8] + 4*slower_X2               -> HScroll_table+$02A
```

**Additional writes:**
- `Events_bg+$10` = value from pass 1 subtraction chain
- `Events_bg+$12` = value from pass 1 subtraction chain (one step further)

**BGDrawArray:**
```
$B0, $100, $7FFF
```
Two draw regions: $B0 lines (176px) and $100 lines (256px).

**BGDeformArray:**
```
$40, $20, $10, $10, $10, $10, $10, $100, $10, $10, $10, $20, $7FFF
```
13 bands: 64px, 32px, 16px x5, 256px, 16px x3, 32px. Total = 64+32+80+256+48+32 = 512px.

**Confidence:** HIGH -- straightforward multi-speed parallax bands.

### Act 2 (sub_57082)

**Disassembly location:** `sonic3k.asm` line 115734

Nearly identical structure to LRZ1_Deform but with slightly different HScroll_table offsets.

**BG Y calculation:**
```
raw = Camera_Y_copy - Screen_shake_offset (as 16:16 fixed)
adjusted = raw / 8
result = adjusted - adjusted/4
BG_Y = result.hi + Screen_shake_offset
```
This gives approximately `(Camera_Y / 8) * 0.75` parallax rate.

**BG X calculation:**
Same as Act 1: base_X = Camera_X (fixed) / 8, slower_X = base_X / 4.

**Band structure:**
- **Pass 1 (rear bands, HScroll_table+$00E down to $000):** 8 bands, written from $00E to $000
- **Pass 2 (front bands, HScroll_table+$010 to $01E):** 5 bands with doubled speed
- Same band speed calculations as Act 1

**Death Egg BG Object positioning:**
After computing bands, if `Events_bg+$06` (Death Egg object pointer) is set:
```
obj.x_offset = $678 - HScroll_table+$004
if x_offset <= -$7E0: x_offset = 0
obj.y_offset = $C0 - Camera_Y_pos_BG_copy
```

**BGDeformArray:**
```
$20, $20, $20, $10, $10, $10, $10, $F0, $10, $10, $10, $20, $7FFF
```
13 bands: 32px x3, 16px x4, 240px, 16px x3, 32px. Total = 96+64+240+48+32 = 480px.

**Confidence:** HIGH -- same structure as Act 1 with minor offset differences.

### LRZ3 Boss Act (sub_59D82 / sub_59DA2 / sub_59DDE)

**Disassembly location:** `sonic3k.asm` line 119598 (sub_59D82), line 119615 (sub_59DA2), line 119652 (sub_59DDE)

**Standard BG position (sub_59D82):**
```
BG_Y = Camera_Y_copy / 16 + $10
BG_X = Camera_X_copy / 16
Events_bg+$12 = BG_X / 2
```

**Boss arena BG position (sub_59DA2):**
```
BG_X = Camera_X_copy - $700
BG_Y = Camera_Y_copy - $500
```
Direct offset (no parallax division) for the boss arena interior.

**Heat shimmer deformation (sub_59DDE):**

This is the signature LRZ visual effect -- per-scanline horizontal displacement creating a heat distortion.

- **Gating:** Only runs when `Events_routine_fg >= 8` (post-boss-setup) AND `End_of_level_flag` is clear AND `_unkFACD` is clear. Otherwise falls through to `PlainDeformation`.
- **Data source:** `AIZ2_SOZ1_LRZ3_FGDeformDelta` -- a 64-entry word table (32 unique values, repeated):
  ```
  0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0,
  0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, (repeated)
  ```
  Values are 0 or 1, creating a subtle per-line horizontal jitter.
- **FG shimmer:** Indexed by `(Camera_Y_pos_copy + Level_frame_counter * 2) & $3E`, adds delta to Camera_X_pos_copy (negated)
- **BG shimmer:** Indexed by `(Camera_Y_pos_BG_copy + Level_frame_counter) & $3E`, adds delta to Camera_X_pos_BG_copy (negated)
- **Writes:** 224 entries ($E0) to `H_scroll_buffer` -- alternating FG HScroll (with FG delta) and BG HScroll (with BG delta)
- **Animation:** The `Level_frame_counter` offset creates apparent upward motion of the shimmer at different speeds for FG and BG

**Confidence:** HIGH -- shared deformation data with AIZ2/SOZ1, well-understood heat haze effect.

## Animated Tiles

### Custom: Shared BG Tile Animation (AnimateTiles_LRZ1 / AnimateTiles_LRZ2 / AnimateTiles_LRZ3)

**Disassembly location:** `sonic3k.asm` line 55040 (LRZ1), line 55046 (LRZ2), line 55034 (LRZ3)

All three share the same core routine at `loc_282D0`. The only difference is the VRAM destination:

| Variant | VRAM Dest 1 (d4) | VRAM Dest 2 (d6) | Notes |
|---------|------------------|------------------|-------|
| LRZ1 | $320 | $344 | Act 1 tile addresses |
| LRZ2 | $320 | $344 | Act 2 tile addresses (same as Act 1) |
| LRZ3 | $170 | $194 | Boss act tile addresses (different base) |

**Channel 0 (BG position-dependent animation):**

Tracks `(Events_bg+$12 - Camera_X_pos_BG_copy - 1) / $30` as the animation state.

- Only updates when position changes (compares against `Anim_Counters+$01`)
- Computes art offset from position within a $30-pixel cycle:
  - `sub_frame = position & 7` -> offset = `sub_frame * $80 + sub_frame * $400` = `sub_frame * $480`
  - `split_group = position & $38` -> split point from `word_2834C` table
- **Split DMA table (word_2834C):**
  ```
  $240, $000, $1E0, $060, $180, $0C0, $120, $120, $0C0, $180, $060, $1E0
  ```
  (6 pairs: first DMA size, second DMA size)
- **Art source:** `ArtUnc_AniLRZ__BG` ("Levels/LRZ/Animated Tiles/BG 1.bin")
- Two DMA transfers per update: first at computed offset -> VRAM d4, second at base offset -> VRAM d4+size1
- Creates a scrolling lava/rock texture effect that changes with BG scroll position

**Channel 1 (second BG position-dependent animation):**

**Only runs for LRZ1 and LRZ2** (skipped for LRZ3 where `Current_zone == $16`).

Tracks `(Events_bg+$10 - Camera_X_pos_BG_copy) & $1F` as animation state.

- Same structure as Channel 0 but with different parameters:
  - `sub_frame = position & 7` -> offset = `sub_frame * $80 + sub_frame * $100 + sub_frame * $80` = `sub_frame * $180`
  - `split_group = position & $18`
- **Split DMA table (word_283D2):**
  ```
  $C0, $00, $90, $30, $60, $60, $30, $90
  ```
  (4 pairs)
- **Art source:** `ArtUnc_AniLRZ__BG2` ("Levels/LRZ/Animated Tiles/BG 2.bin")
- Two DMA transfers -> VRAM d6

**Channel 2 (standard AniPLC):**

After Channel 1 (or directly for LRZ3): advances Anim_Counters pointer by 2, then falls through to `loc_286E8` which is the standard `zoneanimdecl`-based animation runner.

**Confidence:** MEDIUM -- the position-dependent split-DMA is complex. Channel 0 and 1 use the same algorithm with different lookup tables and art sources. The visual effect is lava/rock textures that shift as the BG scrolls.

### Script: Act 1 (AniPLC_LRZ1)

**Disassembly location:** `sonic3k.asm` line 56002

Two channels:

**Channel 0:**
- **Declaration:** `zoneanimdecl 5, ArtUnc_AniLRZ1_0, $354, 4, 4`
- **Destination VRAM tile:** $354
- **Frame count:** 4
- **Frame duration:** 5 frames each (constant)
- **Tiles per frame:** 4 (4 x $20 = $80 bytes)
- **Frame offsets:** 0, 4, 8, $C
- **Art source:** "Levels/LRZ/Animated Tiles/Act1 0.bin"

**Channel 1:**
- **Declaration:** `zoneanimdecl 5, ArtUnc_AniLRZ1_1, $350, 4, 4`
- **Destination VRAM tile:** $350
- **Frame count:** 4
- **Frame duration:** 5 frames each (constant)
- **Tiles per frame:** 4 ($80 bytes)
- **Frame offsets:** 0, 4, 8, $C
- **Art source:** "Levels/LRZ/Animated Tiles/Act1 1.bin"

**Confidence:** HIGH

### Script: Act 2 (AniPLC_LRZ2)

**Disassembly location:** `sonic3k.asm` line 56017

Two channels:

**Channel 0:**
- **Declaration:** `zoneanimdecl -1, ArtUnc_AniLRZ2_0, $358, 3, 6`
- **Destination VRAM tile:** $358
- **Frame count:** 3
- **Frame duration:** Variable (per-frame durations in data)
- **Tiles per frame:** 6 ($C0 bytes)
- **Frame data (offset, duration pairs):**
  - Frame 0: offset 0, duration 2
  - Frame 1: offset 6, duration 2
  - Frame 2: offset $C, duration 1
- **Art source:** "Levels/LRZ/Animated Tiles/Act2 0.bin"

**Channel 1:**
- **Declaration:** `zoneanimdecl 1, ArtUnc_AniLRZ2_1, $350, 8, 8`
- **Destination VRAM tile:** $350
- **Frame count:** 8
- **Frame duration:** 1 frame each (very fast)
- **Tiles per frame:** 8 ($100 bytes)
- **Frame offsets:** 0, $38, $30, $28, $20, $18, $10, 8
- **Art source:** "Levels/LRZ/Animated Tiles/Act2 1.bin"
- **Note:** Frame offsets are NOT sequential -- they go 0, $38, $30... descending, creating a specific animation order

**Confidence:** HIGH

### Enemy Art PLC

**PLCKosM_LRZ** (shared both acts, `sonic3k.asm` line 64418):
- Fireworm Segments: `ArtKosM_FirewormSegments` -> `ArtTile_FirewormSegments`
- Iwamodoki: `ArtKosM_Iwamodoki` -> `ArtTile_Iwamodoki`
- Toxomister: `ArtKosM_Toxomister` -> `ArtTile_Toxomister`

### Level PLCs

**PLC_2E_2F** (LRZ1, `sonic3k.asm` line 199793):
- `ArtNem_LRZMisc` -> `ArtTile_LRZMisc`
- `ArtNem_LRZSpikes` -> `ArtTile_LRZ2Misc+$2B`
- `ArtNem_LRZBigSpike` -> `ArtTile_LRZBigSpike`

**PLC_30_31** (LRZ2, `sonic3k.asm` line 199799):
- `ArtNem_LRZ2Misc` -> `ArtTile_LRZ2Misc`
- `ArtNem_LRZ2Drum` -> `ArtTile_LRZ2Drum`

## Palette Cycling

### AnPal_LRZ1 (Act 1)

**Disassembly location:** `sonic3k.asm` line 3569

Three independent channels:

**Channel A (shared with Act 2): Lava glow cycle**
- **Counter:** `Palette_cycle_counter1` (delay), `Palette_cycle_counter0` (index)
- **Delay:** 15 frames between updates ($F)
- **Step:** 8 bytes per tick
- **Limit:** $80 (16 frames in cycle, wraps at index $80)
- **Target:** `Normal_palette_line_3+$02` (palette line 2, colors 1-4: 2 longwords = 4 colors)
- **Palette data:** `AnPal_PalLRZ12_1` (128 bytes = 16 frames x 8 bytes)

| Frame | Color 1 | Color 2 | Color 3 | Color 4 |
|-------|---------|---------|---------|---------|
| 0 | $00EE | $00AE | $006E | $000E |
| 1 | $00AE | $006E | $000E | $00EE |
| 2 | $006E | $000E | $00EE | $02CE |
| 3 | $002E | $08EE | $04EE | $006E |
| 4 | $0AEE | $06EE | $008E | $004E |
| 5 | $04EE | $006E | $002E | $08EE |
| 6 | $006E | $000E | $00EE | $02CE |
| 7 | $000E | $00EE | $02CE | $006E |
| 8 | $00EE | $00AE | $006E | $000E |
| 9 | $008E | $004E | $000C | $00CE |
| 10 | $002E | $000A | $00AC | $006E |
| 11 | $0008 | $008E | $004C | $000C |
| 12 | $00AC | $006E | $002E | $000A |
| 13 | $008E | $004E | $000C | $00CE |
| 14 | $006E | $000E | $00EE | $00AE |
| 15 | $000E | $00EE | $00AE | $006E |

This creates a flowing lava color cycle through green/cyan tones.

**Channel B (shared with Act 2): Secondary glow**
- **Counter:** `Palette_cycle_counters+$02` (index)
- **Step:** 4 bytes per tick
- **Limit:** $1C (7 frames in cycle)
- **Target:** `Normal_palette_line_4+$02` (palette line 3, colors 1-2: 1 longword = 2 colors)
- **Palette data:** `AnPal_PalLRZ12_2` (28 bytes = 7 frames x 4 bytes)

| Frame | Color 1 | Color 2 |
|-------|---------|---------|
| 0 | $0224 | $0224 |
| 1 | $0224 | $0424 |
| 2 | $0224 | $0426 |
| 3 | $0426 | $0224 |
| 4 | $0424 | $0224 |
| 5 | $0224 | $0224 |
| 6 | $0224 | $0224 |

(Followed by $0224/$0422, $0422/$0422 -- which may be accessed by Act 2's different limit)

**Channel C (Act 1 only): Lava surface shimmer**
- **Counter:** `Palette_cycle_counters+$08` (delay), `Palette_cycle_counters+$04` (index)
- **Delay:** 7 frames between updates
- **Step:** 2 bytes per tick
- **Limit:** $22 (17 frames in cycle)
- **Target:** `Normal_palette_line_3+$16` (palette line 2, color 11: 1 word)
- **Palette data:** `AnPal_PalLRZ1_3` (34 bytes = 17 frames x 2 bytes)

| Frames | Color 11 |
|--------|----------|
| 0-5 | $0624 |
| 6-7 | $0626 |
| 8-14 | $0826 |
| 15-16 | $0626 |

Subtle warm-tone pulsing on a single color.

### AnPal_LRZ2 (Act 2)

**Disassembly location:** `sonic3k.asm` line 3611

Three independent channels:

**Channel A (shared): Identical to Act 1 Channel A**
- Same logic, same data (`AnPal_PalLRZ12_1`), same target, same timing

**Channel B (shared): Identical to Act 1 Channel B**
- Same logic, same data (`AnPal_PalLRZ12_2`), same target, same timing

**Channel D (Act 2 only): Lava flow gradient**
- **Counter:** `Palette_cycle_counters+$08` (delay), `Palette_cycle_counters+$04` (index)
- **Delay:** 15 frames between updates ($F)
- **Step:** 8 bytes per tick
- **Limit:** $100 (32 frames in cycle)
- **Target:** `Normal_palette_line_4+$16` (palette line 3, colors 11-14: 2 longwords = 4 colors)
- **Palette data:** `AnPal_PalLRZ2_3` (256 bytes = 32 frames x 8 bytes)
- **Known bug (FixBugs):** The original code writes `(a0,d0.w)` to both `+$16` and `+$1A`, duplicating colors 11-12 into 13-14 instead of writing `4(a0,d0.w)` for the second pair. Fixed in Sonic Origins.

| Frame | Color 11 | Color 12 | Color 13 | Color 14 |
|-------|----------|----------|----------|----------|
| 0-2 | $0824 | $0C44 | $0E2A | $0EAE |
| 3 | $0824 | $0C44 | $0E48 | $0EAC |
| 4-6 | $0822 | $0C44 | $0E66 | $0EAA |
| 7 | $0842 | $0C64 | $0EA4 | $0EC6 |
| 8-10 | $0642 | $0A82 | $0CC2 | $0EE0 |
| 11 | $0642 | $0882 | $0AC2 | $0CE0 |
| 12-14 | $0642 | $0682 | $08C2 | $08E0 |
| 15 | $0642 | $0486 | $04C8 | $04E8 |
| 16 | $0442 | $0288 | $02CA | $02EC |
| 17 | $0442 | $0288 | $02CC | $02EE |
| 18 | $0442 | $0288 | $02CA | $02EC |
| 19 | $0642 | $0486 | $04C8 | $04E8 |
| 20 | $0642 | $0682 | $08C2 | $08E0 |
| 21 | $0642 | $0882 | $0AC2 | $0CE0 |
| 22 | $0642 | $0A82 | $0CC2 | $0EE0 |
| 23 | $0842 | $0C64 | $0EA4 | $0EC6 |
| 24 | $0822 | $0C44 | $0E66 | $0EAA |
| 25 | $0824 | $0C44 | $0E48 | $0EAC |
| 26 | $0824 | $0C44 | $0E2A | $0EAE |
| 27 | $0826 | $0C46 | $0E4A | $0E8E |
| 28-30 | $0624 | $0A48 | $0C6C | $0E6E |
| 31 | $0826 | $0C46 | $0E4A | $0E8E |

This creates a dramatic warm-to-cool-to-warm lava flow cycle transitioning through orange/red ($E2A/$EAE) down to blue/teal ($2CA/$2EC) and back.

### AnPal_LRZ3 (Boss Act)

**Disassembly location:** `sonic3k.asm` line 3897

Two conditional channels:

**Channel A (lava glow -- shared data, gated):**
- **Gate:** If `Palette_cycle_counters+$00` is negative ($80), entire routine returns immediately (disabled during Death Egg flash)
- **Counter/timing:** Same as Act 1/2 Channel A ($F delay, 8-byte step, $80 limit)
- **Data:** `AnPal_PalLRZ12_1` (same shared palette data)
- **Target:** `Normal_palette_line_3+$02` (same as Act 1/2)

**Channel B (boss fire glow -- gated by flag):**
- **Gate:** Only runs when `Palette_cycle_counters+$00` is non-zero AND non-negative (value = 1, set after cutscene flash)
- **Counter:** `Palette_cycle_counters+$08` (delay), `Palette_cycle_counters+$04` (index)
- **Delay:** 7 frames between updates
- **Step:** 4 bytes per tick
- **Limit:** $3C (15 frames in cycle)
- **Target:** `Normal_palette_line_4+$18` (palette line 3, colors 12-13: 1 longword = 2 colors)
- **Palette data:** `AnPal_PalLRZ3` (60 bytes = 15 frames x 4 bytes)

| Frame | Color 12 | Color 13 |
|-------|----------|----------|
| 0-2 | $0424 | $00AE |
| 3 | $0426 | $008E |
| 4 | $0428 | $006E |
| 5 | $042A | $004E |
| 6 | $042C | $002E |
| 7-9 | $042E | $000E |
| 10 | $042C | $002E |
| 11 | $042A | $004E |
| 12 | $0428 | $006E |
| 13 | $0426 | $008E |
| 14 | $0424 | $00AE |

Symmetric pulse from dark red ($424/$AE) through bright ($42E/$E) and back.

**Gating state transitions:**
1. Initially `Palette_cycle_counters+$00` = 0: Channel A runs, Channel B skipped
2. During Death Egg flash: set to $80 (negative): both channels disabled
3. After flash: set to 1 (positive non-zero): both channels run

### S3 Version Differences (s3.asm)

**AnPal_LRZ1 in S3:**
- Only 2 channels (A and B), no Channel C
- Channel A uses `AnPal_PalLRZ1_1` (same data as S&K's `AnPal_PalLRZ12_1`)
- Channel B uses `AnPal_PalLRZ1_2` (same data as S&K's `AnPal_PalLRZ12_2`)
- No act-specific channel

**AnPal_LRZ2 in S3:**
- Bare `rts` -- no palette cycling at all for Act 2 in Sonic 3 alone

## Water System

LRZ has **no water** in any act. The `DynamicWaterHeight_Index` maps LRZ (indices 14-15) to `DynamicWaterHeight_Null2`, and `CheckLevelForWater` does not include zone 9 in its water zone list.

## Rock Sprite System

### Draw_LRZ_Special_Rock_Sprites

**Disassembly location:** `sonic3k.asm` line 39551 (S&K), `s3.asm` line 32705 (S3)

LRZ has a unique foreground rock decoration system that renders sprites from binary placement data, independent of the normal object system.

**Architecture:**
- Called during the main game loop (not per-object)
- Uses `LRZ_rocks_routine` state variable (2 states: init, update)
- Maintains front/back window pointers: `LRZ_rocks_addr_front`, `LRZ_rocks_addr_back`

**Placement data format:** 6 bytes per entry:
- Word 0: Rock type index (multiplied by 8 to index into LRZ_Rock_SpriteData)
- Word 1: X position
- Word 2: Y position

**Data sources:**
- Act 1: `LRZ1_Rock_Placement` ("Levels/LRZ/Misc/Act 1 Rock Placement.bin") with 3-word zero header
- Act 2: `LRZ2_Rock_Placement` ("Levels/LRZ/Misc/Act 2 Rock Placement.bin") with 3-word zero header

**Sprite data (LRZ_Rock_SpriteData):**
- Binary data from "Levels/LRZ/Misc/Rock Sprite Attribute Data.bin" (S&K) or "Rock Sprite Attribute Data S3.bin" (S3)
- 8 bytes per rock type: Y offset (word), size/link (word), tile (word), X offset (word)
- Used to compose VDP sprite table entries directly

**Windowing:**
- State 0 (init): Scans forward through placement data to find front pointer (Camera_X - 8) and back pointer (Camera_X + $150)
- State 1 (update): Adjusts front/back pointers as camera scrolls, scanning forward and backward as needed

**Rendering (sub_1CB68):**
- Iterates from front to back pointer
- For each entry within screen bounds (Y within 240px of camera, X within 240+120 of camera):
  - Looks up rock type in LRZ_Rock_SpriteData
  - Computes screen-relative X/Y
  - Writes directly to sprite attribute table entries (via a6)
  - Decrements d7 (sprite count limit)

**Transition handling:**
- `LRZ_rocks_routine` is cleared during the seamless Act 1 -> Act 2 transition (line 115352)
- This forces re-initialization with Act 2's placement data

**Confidence:** HIGH -- unique to LRZ, well-understood windowed sprite rendering system.

## Cutscene Objects

### CutsceneKnux_LRZ2

**Disassembly location:** `sonic3k.asm` line 131053

5 routine stages. Triggers for Sonic/Tails only (skips for Knuckles via character_id check).

| Stage | Action |
|-------|--------|
| 0 | Init: set up ObjAttributesSlotted, use Knuckles mappings, face right, position at X=$3A38 Y=$EC, load cutscene palette |
| 2 | Wait for `_unkFAB8` bit 1 (signal from Autoscroll boss), then set animation to $DE, advance |
| 4 | Animate via `byte_66891` (raw multi-delay). On completion: set `_unkFAB8` bit 2, start new animation |
| 6 | Animate via `byte_668A7`. On completion: advance to terminal state |
| 8 | Terminal (rts) |

### Obj_LRZ2CutsceneKnuckles

**Disassembly location:** `sonic3k.asm` line 131115

Extended cutscene for Sonic's path in LRZ2. Skips for Knuckles. Includes:

1. Allocates companion object (`loc_863C0`)
2. Checks player range (`word_63B94`: -$10 to $10 X, -$240 to $240 Y)
3. When player enters range: locks Camera_min_X, spawns `Obj_CutsceneKnuckles` with subtype $24
4. Loads `ArtKosM_LRZKnuxBoulder` art
5. When Player X >= $39B0: locks controls, stops player
6. When player lands: sets `_unkFAB8` bit 0, enables scroll lock, sets player to object_control $81 with anim 7
7. Boulder sequence follows

**Confidence:** MEDIUM -- multi-stage cutscene with boulder interaction.

## Level Art Resources

### Act 1
- **Palette IDs:** $2E (FG), $2F (water -- actually just secondary palette), $1C (BG)
- **Primary tiles:** `ArtKosM_LRZ_Primary` (shared)
- **Secondary tiles:** `ArtKosM_LRZ1_Secondary`
- **Primary blocks:** `LRZ_16x16_Primary_Kos` (shared)
- **Secondary blocks:** `LRZ1_16x16_Secondary_Kos`
- **Primary chunks:** `LRZ_128x128_Primary_Kos` (shared)
- **Secondary chunks:** `LRZ1_128x128_Secondary_Kos`

### Act 2
- **Palette IDs:** $30 (FG), $30 (secondary -- same as FG), $1D (BG)
- **Primary tiles:** `ArtKosM_LRZ_Primary` (shared)
- **Secondary tiles:** `ArtKosM_LRZ2_Secondary`
- **Primary blocks:** `LRZ_16x16_Primary_Kos` (shared)
- **Secondary blocks:** `LRZ2_16x16_Secondary_Kos`
- **Primary chunks:** `LRZ_128x128_Primary_Kos` (shared)
- **Secondary chunks:** `LRZ2_128x128_Secondary_Kos`

### Boss Act (LRZ3)
- **Palette IDs:** $48 (FG and secondary), $3E (BG)
- **Primary tiles:** `ArtKosM_HPZ_Primary` (shared with HPZ)
- **Secondary tiles:** `ArtKosM_LRZ3_Secondary`
- **Primary blocks:** `HPZ_16x16_Primary_Kos` (shared with HPZ)
- **Secondary blocks:** `LRZ3_16x16_Secondary_Kos`
- **Primary chunks:** `HPZ_128x128_Primary_Kos` (shared with HPZ)
- **Secondary chunks:** `LRZ3_128x128_Secondary_Kos`
- **Boss fire palette:** `Pal_LRZBossFire` ("Levels/LRZ/Palettes/Boss Act Fire.bin") -- loaded into palette lines 2-4 for boss act

### Level Art Loaded During Transition (Act 1 -> Act 2)
- `LRZ2_128x128_Secondary_Kos` -> `Chunk_table+$180`
- `LRZ2_16x16_Secondary_Kos` -> `Block_table+$128`
- `ArtKosM_LRZ2_Secondary` -> VRAM $090
- PLC $30 (LRZ2 misc/drum art)

### Animated Tile Art Sources
- `ArtUnc_AniLRZ__BG` -- "Levels/LRZ/Animated Tiles/BG 1.bin" -- shared BG position-dependent (Channel 0)
- `ArtUnc_AniLRZ__BG2` -- "Levels/LRZ/Animated Tiles/BG 2.bin" -- shared BG position-dependent (Channel 1)
- `ArtUnc_AniLRZ1_0` -- "Levels/LRZ/Animated Tiles/Act1 0.bin" -- Act 1 AniPLC channel 0
- `ArtUnc_AniLRZ1_1` -- "Levels/LRZ/Animated Tiles/Act1 1.bin" -- Act 1 AniPLC channel 1
- `ArtUnc_AniLRZ2_0` -- "Levels/LRZ/Animated Tiles/Act2 0.bin" -- Act 2 AniPLC channel 0
- `ArtUnc_AniLRZ2_1` -- "Levels/LRZ/Animated Tiles/Act2 1.bin" -- Act 2 AniPLC channel 1

### Boss Art
- `ArtKosM_LRZMiniboss` -> `ArtTile_LRZMiniboss` (miniboss)
- `ArtKosM_LRZEndBoss` -> `ArtTile_LRZEndBoss` (final boss)
- `ArtKosM_LRZ3Autoscroll` -> `ArtTile_LRZ3Autoscroll` (autoscroll boss vehicle)
- `ArtKosM_LRZ3DeathEggFlash` -> `ArtTile_LRZ3DeathEggFlash` (flash effect)
- `ArtKosM_LRZ3PlatformDebris` -> `ArtTile_LRZ3PlatformDebris` (platform destruction debris)
- `ArtKosM_LRZKnuxBoulder` -> `ArtTile_LRZKnuxBoulder` (cutscene boulder)
- `ArtKosM_LRZ2DeathEggBG` -> `ArtTile_LRZ2DeathEggBG` (Act 2 BG Death Egg sprite)
- `ArtKosM_LRZRockCrusher` -> `ArtTile_LRZRockCrusher` (rock crusher object)

### Palettes
- `Pal_LRZMiniboss1` -- "Levels/LRZ/Palettes/Miniboss 1.bin" (initial palette)
- `Pal_LRZMiniboss2` -- "Levels/LRZ/Palettes/Miniboss 2.bin" (active palette)
- `Pal_LRZMiniboss3` -- "Levels/LRZ/Palettes/Miniboss 3.bin" (defeat palette)
- `Pal_LRZBossFire` -- "Levels/LRZ/Palettes/Boss Act Fire.bin" (boss act ambient)
- `Pal_LRZEndBoss` -- "Levels/LRZ/Palettes/End Boss.bin"
- `Pal_LRZRockCrusher` -- "Levels/LRZ/Palettes/Rock Crusher.bin"

### Binary Data Files
- "Levels/LRZ/Misc/Act 1 Rock Placement.bin" -- rock sprite placement for Act 1
- "Levels/LRZ/Misc/Act 2 Rock Placement.bin" -- rock sprite placement for Act 2
- "Levels/LRZ/Misc/Rock Sprite Attribute Data.bin" -- VDP sprite data per rock type (S&K)
- "Levels/LRZ/Misc/Rock Sprite Attribute Data S3.bin" -- VDP sprite data per rock type (S3)

## Level Boundaries

| Zone/Act | X Start | X End | Y Start | Y End |
|----------|---------|-------|---------|-------|
| LRZ1 ($0900) | $0000 | $2CC0 | $0000 | $0B20 |
| LRZ2 ($0901) | $0940 | $3EC0 | $0000 | $0B20 |
| LRZ3 Boss ($1600) | $0000 | $0EC0 | $0000 | $0430 |

## Zone Objects

### Unique to LRZ
| Object | Line | Description |
|--------|------|-------------|
| `Obj_LRZSolidMovingPlatforms` | 51007 | Moving solid platforms |
| `Obj_LRZCollapsingBridge` | 77379 | Breakable bridge sections |
| `Obj_LRZCorkscrew` | 87489 | Corkscrew loop handler |
| `Obj_LRZWallRide` | 87688 | Wall ride mechanic |
| `Obj_LRZSinkingRock` | 87893 | Rocks that sink in lava |
| `Obj_LRZFallingSpike` | 87941 | Ceiling spikes that fall |
| `Obj_LRZDoor` | 88010 | Doors |
| `Obj_LRZBigDoor` | 88065 | Large doors |
| `Obj_LRZFireballLauncher` | 88146 | Fireball projectile launcher |
| `Obj_LRZButtonHorizontal` | 88216 | Horizontal button switch |
| `Obj_LRZShootingTrigger` | 88274 | Trigger-activated shooter |
| `Obj_LRZDashElevator` | 88376 | Elevator activated by dash |
| `Obj_LRZSmashingSpikePlatform` | 88533 | Descending spike crusher |
| `Obj_LRZSwingingSpikeBall` | 88647 | Pendulum spike ball |
| `Obj_LRZLavaFall` | 88765 | Lava waterfall visual |
| `Obj_LRZSpikeBall` | 88833 | Static/moving spike ball |
| `Obj_LRZOrbitingSpikeBallHorizontal` | 89072 | Horizontally orbiting spike ball |
| `Obj_LRZOrbitingSpikeBallVertical` | 89144 | Vertically orbiting spike ball |
| `Obj_LRZFlameThrower` | 89222 | Flame jet hazard |
| `Obj_LRZSolidRock` | 89448 | Solid rock terrain |
| `Obj_LRZTurbineSprites` | 89606 | Turbine visual sprites |
| `Obj_LRZSpikeBallLauncher` | 89843 | Launches spike balls |
| `Obj_LRZChainedPlatforms` | 97134 | Chain-linked platform system |
| `Obj_LRZRockCrusher` | 196983 | Rock crushing machine (Act 2) |

## Implementation Priority

### Phase 1: Core Playability
1. **Palette cycling** (AnPal_LRZ1/LRZ2) -- 3 channels shared/act-specific, already implemented in engine
2. **Basic parallax Act 1** (LRZ1_Deform) -- multi-band BG parallax with speed divisions
3. **Basic parallax Act 2** (sub_57082) -- nearly identical to Act 1
4. **Rock Sprite System** (Draw_LRZ_Special_Rock_Sprites) -- unique to LRZ, required for visual fidelity

### Phase 2: Screen Events & Transitions
5. **LRZ1_ScreenEvent** -- screen shake + layout chunk replacement via Events_bg+$0C
6. **LRZ2_ScreenEvent** -- trivially simple (DrawTilesAsYouMove only)
7. **LRZ1_BackgroundEvent** -- 4-stage state machine with BG layout regions + seamless transition
8. **LRZ2_BackgroundEvent** -- 3-stage BG handler with Death Egg BG object
9. **Seamless Act 1->2 Transition** -- -$2C00 X offset, art loading, rock placement reset

### Phase 3: Animated Tiles
10. **AnimateTiles_LRZ1/LRZ2** -- custom BG position-dependent split-DMA (most complex)
11. **AniPLC_LRZ1** -- 2-channel standard animation
12. **AniPLC_LRZ2** -- 2-channel standard animation (one with variable duration)

### Phase 4: Boss Act (LRZ3)
13. **LRZ3_ScreenEvent** -- ring/timer restoration, layout chunks, auto-scroll re-enable
14. **LRZ3_BackgroundEvent** -- 5-stage state machine with VScroll boss arena
15. **LRZ3 auto-scroll** (Special_events_routine $14) -- 7-stage forced-scroll
16. **Heat shimmer deformation** (sub_59DDE) -- per-scanline FG/BG HScroll displacement
17. **Lava platform object** (Obj_59FC4) -- oscillating solid with slope deformation
18. **LRZ3 palette cycling** (AnPal_LRZ3) -- 2 gated channels
19. **Special VInt routines** ($04/$0C) -- cell-based VScroll on/off

### Phase 5: Boss Sequences
20. **Miniboss** (Obj_LRZMiniboss) -- emergence + swing + attack pattern
21. **LRZ3 cutscene sequence** -- Death Egg flash, palette transition, autoscroll start
22. **Autoscroll boss** (Obj_LRZ3Autoscroll) -- approach + attack
23. **End boss** (Obj_LRZEndBoss) -- lava emergence + multi-phase attack
24. **Knuckles cutscene** (CutsceneKnux_LRZ2 / Obj_LRZ2CutsceneKnuckles) -- boulder sequence

### Phase 6: Act 1 BG Layout Regions
25. **sub_56DCA** -- 3 rectangular regions that switch BG to locked parallax mode
26. **Obj_56EA0** -- oscillating lava platform with heat damage (spawned during region entry)
27. **sub_56DAC** -- locked BG position calculation for lava dome interiors

## Key Implementation Notes

1. **No Dynamic_Resize for LRZ.** Both acts map to `No_Resize` in the LevelResizeArray. All dynamic behavior is handled through the screen event and background event systems.

2. **No water system.** LRZ uses `DynamicWaterHeight_Null2` and is not in the `CheckLevelForWater` zone list. Lava damage is handled through object-level collision (sub_24280/HurtCharacter) rather than the water system.

3. **The seamless transition offsets by -$2C00 X** (not the same as LBZ's -$3A00 or HCZ's -$3600). The transition also resets `LRZ_rocks_routine` to force rock sprite re-initialization with Act 2 data.

4. **The Rock Sprite System is unique to LRZ.** It renders decorative rock sprites directly to the VDP sprite table from binary placement data, bypassing the normal object system. This requires a dedicated subsystem in the engine.

5. **LRZ3 (boss act) shares primary art with HPZ** (Hidden Palace Zone). The `levartptrs` entry uses `ArtKosM_HPZ_Primary` and `HPZ_16x16_Primary_Kos`/`HPZ_128x128_Primary_Kos` for primary resources, with LRZ3-specific secondary resources. This is because LRZ3 transitions to HPZ for Knuckles.

6. **Heat shimmer deformation shares data with AIZ2 and SOZ1** (`AIZ2_SOZ1_LRZ3_FGDeformDelta`). The 64-entry table of 0/1 values creates subtle per-scanline horizontal jitter, animated by Level_frame_counter at different rates for FG and BG.

7. **The LRZ3 boss arena uses cell-based VScroll** (Special_V_int_routine $04/$0C to toggle VDP register $8B between $8B07 and $8B03). This creates independently-scrolling vertical columns for the lava platform deformation effect.

8. **Act 1 BG layout regions create a parallax mode switch.** When the player enters one of 3 rectangular zones, the BG switches from normal parallax scrolling to a fixed-offset "locked" view showing a lava dome interior. This is accompanied by an oscillating lava platform object (Obj_56EA0) that damages players without Fire Shield.

9. **Knuckles differences are moderate:**
   - Act 1: BG init modifies a chunk byte (removes visual element)
   - Act 2: Full cutscene with boulder sequence (`Obj_LRZ2CutsceneKnuckles`, `ArtKosM_LRZKnuxBoulder`)
   - LRZ3: Death Egg BG object deletes itself for Knuckles (Player_mode == 3)

10. **The AnPal_LRZ2 Channel D bug** is a documented S3K bug (fixed in Origins): it duplicates the first 2 colors of each 4-color frame into the second pair, rather than reading the correct offset. The `FixBugs` conditional in the disassembly shows the fix.

11. **AnimateTiles_LRZ1/LRZ2 share the same core routine** but differ only in VRAM destination addresses. The position-dependent split-DMA creates scrolling lava/rock textures that track BG scroll position, requiring two separate DMA transfers per update with variable split points.

12. **The LRZ3 autoscroll system is a 7-stage forced-scroll** that alternates between horizontal and diagonal movement segments. Players are pushed if they fall behind the left edge and killed if they are pushing against a wall at the left edge. This is similar to the AIZ2 ship loop but with more complex path segments.
