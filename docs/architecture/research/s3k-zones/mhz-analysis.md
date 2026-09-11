# S3K MHZ Zone Analysis

## Summary

- **Zone:** Mushroom Hill Zone (MHZ)
- **Zone Index:** 0x07
- **Zone Set:** SKL
- **Acts:** 1 and 2
- **Water:** No
- **Palette Cycling:** No (`AnPal_None` for both acts)
- **Animated Tiles:** Yes (2 custom BG DMA handlers + 4 AniPLC scripts)
- **Character Branching:** Yes -- Knuckles has alternate start positions in Act 1, different boss scrolling parameters in Act 2, and a cutscene trigger in MHZ1 (peering over ledge)
- **Unique Mechanics:** Seasonal color change (green/spring -> autumn/gold -> winter/gold palette swap driven by trigger regions), Act 1->2 seamless transition with layout reload, Act 2 end boss forced-scroll arena with level repeat loop, falling leaves/pollen spawner behavior changes with season flag

## Level Boundaries

| Act | Min X | Max X | Min Y | Max Y | Notes |
|-----|-------|-------|-------|-------|-------|
| 1 | $0000 | $4298 | $0000 | $0AA0 | S3K locked-on with Sonic/Tails starts at min X $00C0 |
| 2 | $0098 | $3C90 | $0620 | $09A0 | Initial; dynamically modified during gameplay |

## Events

### Act 1 (`MHZ1_ScreenInit` / `MHZ1_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` lines 112164-112234

#### `MHZ1_ScreenInit` (line 112164)

1. Clears `Events_bg+$16` and `_unkF7C1` (season flag = green/spring)
2. Calls `sub_54B80` (dynamic min-X boundary helper)
3. **SK-alone bootstrap:** If `SK_alone_flag` is set:
   - If Knuckles (`Player_mode == 3`) and first star post: sets Camera X to $680
   - If no star post: sets Sonic/Tails to position ($718, $62D), camera to ($680, $5CD)
4. S3&K locked-on Sonic/Tails: additional min-X adjustment to $00C0 (in the `DeformBgLayer` pre-resize code, line 38216)
5. `Reset_TileOffsetPositionActual` + `Refresh_PlaneFull`

#### `MHZ1_ScreenEvent` (line 112204)

This is NOT a state-machine -- it runs unconditionally each frame. Logic:

| Condition | Action | Notes |
|-----------|--------|-------|
| `Events_bg+$00 != 0` | Skip to `DrawTilesAsYouMove` | Boss is active, no boundary updates |
| `Events_bg+$16 != 0` | Skip to `DrawTilesAsYouMove` | Act transition in progress |
| Always | Call `sub_54B80` (min-X helper) | Adjusts Camera_min_X_pos |
| Player_1 X >= $4100 | Set Camera_max_Y = $710 | Lower area unlocked |
| Player_1 X < $4100 | Set Camera_max_Y = $AA0 | Full height accessible |
| Camera Y >= $710 AND Camera X >= $4298 | Lock Y-min to $710, set `Special_events_routine = 8`, set `Events_bg+$00`, spawn `Obj_MHZMiniboss` | **Miniboss arena lock** |
| End | `DrawTilesAsYouMove` | Normal tile drawing |

#### `sub_54B80` -- Dynamic Min-X Helper (line 112239)

| Condition | Camera_min_X_pos |
|-----------|-----------------|
| SK-alone flag set | $680 |
| Player Y >= $580 | 0 |
| Player Y < $580 | $C0 |

#### `MHZ1_Resize` -- Foreground Events

**No foreground resize routine** -- MHZ uses `No_Resize` in the `LevelResizeArray`. All event logic is in `ScreenEvent` and `BackgroundEvent`.

### Act 1 (`MHZ1_BackgroundInit` / `MHZ1_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` lines 112259-112312

#### `MHZ1_BackgroundInit` (line 112259)

1. Stores `Camera_X_pos_copy` into `Events_fg_0` and `Events_fg_1` (BG tracking accumulators)
2. Calls `MHZ_Deform` (parallax calculation)
3. `Reset_TileOffsetPositionEff` + `Refresh_PlaneFull` + `PlainDeformation`

#### `MHZ1_BackgroundEvent` (line 112269)

**State machine (`Events_fg_5` flag):**

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| Normal (Events_fg_5 == 0) | Every frame | `Adjust_BGDuringLoop` with d2=$100 d3=$200, call `MHZ_Deform`, `Draw_TileRow`, `PlainDeformation` | Standard parallax scrolling |
| Act transition (Events_fg_5 != 0) | Set by miniboss defeat | Full act transition sequence: loads PLC $28, changes zone to $701, reloads level/solids, offsets player/camera by -$4200, clears event state | **Seamless Act 1 -> Act 2 transition** |

**Act 1 -> Act 2 Transition Detail** (line 112269-112312):

When `Events_fg_5` is set (by the miniboss defeat):
1. Loads PLC $28
2. Sets `Current_zone_and_act = $701` (MHZ Act 2)
3. Clears: `Dynamic_resize_routine`, `Object_load_routine`, `Rings_manager_routine`, `Boss_flag`, `Respawn_table_keep`
4. Calls `Clear_Switches`, `Load_Level`, `LoadSolids`
5. Offsets all objects and camera by -$4200 X
6. Resets `Events_fg_0/1` accumulators to new camera position
7. Clears `Special_events_routine`, `Events_bg+$00`

### Act 2 (`MHZ2_ScreenInit` / `MHZ2_ScreenEvent`)

**Disassembly location:** `sonic3k.asm` lines 112406-112600

#### `MHZ2_ScreenInit` (line 112406)

1. Sets `Events_routine_fg = 4` (starts at stage index 1 in the bra.w table)
2. Clears `Events_bg+$16`
3. Sets `_unkF7C1` = $FF (autumn mode by default)
4. **Palette selection based on player X position:**

| Condition | Palette Loaded | Season Flag |
|-----------|---------------|-------------|
| Player X >= $2940 | `Pal_MHZ2Gold` | `_unkF7C1` = $FF (autumn) |
| $9C0 <= Player X < $2940 | `Pal_MHZ2+$20` (autumn standard) | `Events_bg+$04` = $FF, `_unkF7C1` = $FF |
| Player X < $9C0 AND Player Y >= $600 | `Pal_MHZ1+$20` (green/spring) | `_unkF7C1` = 0, `Events_bg+$04` = 0 |

5. Copies selected palette to both `Normal_palette_line_3` and `Target_palette_line_3` (64 bytes = full line 3)
6. `Reset_TileOffsetPositionActual` + `Refresh_PlaneFull`

#### `MHZ2_ScreenEvent` (line 112437)

Adds `Screen_shake_offset` to `Camera_X_pos_copy` each frame (for boss fight shake).

**State machine (`Events_routine_fg`):**

| Stage | Index | Label | Trigger | Action | Notes |
|-------|-------|-------|---------|--------|-------|
| 0 | 0 | `loc_54DB0` | `End_of_level_flag` set | Advance to stage 1 | Initial idle state |
| 1 | 4 | `loc_54DBE` | Various camera thresholds | Dynamic boundary management, season palette checks | **Main gameplay state** |
| 2 | 8 | `loc_54E86` | Events_fg_4 set (ship arriving) | Draw boss BG using `MHZ2_BGDrawArray1` | Ship background setup |
| 3 | $C | `loc_54E9C` | Drawing complete | Load ship art, set up H-Int, setup propeller art via `Queue_Kos_Module`, spawn ship objects, advance | **End boss arena entry** |
| 4 | $10 | `loc_54F64` | Boss active | Draw boss area using BGDrawArrays 2 and 3, manage V-scroll | Boss fight rendering |

#### Act 2 Stage 1 Boundary Logic (line 112476, `loc_54DDC`)

Complex dynamic boundary system tracking player position across MHZ2:

| Condition | Camera_min_Y | Camera_min_X | Camera_max_Y |
|-----------|-------------|-------------|-------------|
| Camera X >= $3C90 | $280 (if Camera Y >= $280) | $3C90 | $280 |
| Camera X < $380 | $620 | $98 | $9A0 |
| $380 <= X < $3600 | 0 | depends on player Y: $98 or $380 | $9A0 |
| X >= $3600 | $1A8 | unchanged | $280 or $9A0 (complex) |

Also calls `sub_55008` which handles the **seasonal palette transition regions** (see below).

#### Act 2 Season Transition System (`sub_55008`, line 112675)

This subroutine checks player position against 5 rectangular trigger regions. When the player enters/exits a region, the palette swaps between green, autumn, and gold.

**Trigger regions** (from `word_5513E` at line 112814):

| Region | X-min | X-max | Y-min | Y-max | Trigger Value |
|--------|-------|-------|-------|-------|---------------|
| 0 | $420 | $4A0 | $640 | $6C0 | Check Y against $680 |
| 1 | $980 | $A00 | $7C0 | $800 | Check X against $9C0 |
| 2 | $2900 | $2980 | $280 | $300 | Check X against $2940 |
| 3 | $2B00 | $2B80 | $540 | $580 | Check X against $2B40 |
| 4 | $2800 | $2980 | $7C0 | $840 | Check Y against $800 |

**Palette outcomes:**

| Outcome | Flags | Palette | Description |
|---------|-------|---------|-------------|
| Enter autumn (loc_55098) | `Events_bg+$04` = $FF, `_unkF7C1` = $FF | `Pal_MHZ2+$20` | Autumn colors |
| Exit to green (loc_550A8) | `Events_bg+$04` = 0, `_unkF7C1` = 0 | `Pal_MHZ1+$20` | Green/spring colors |
| Exit to gold (loc_550B8) | `Events_bg+$04` = 0, `_unkF7C1` = $FF | `Pal_MHZ2Gold` | Gold/winter colors |

Each palette copy writes 64 bytes ($40) to `Normal_palette_line_3`.

### Act 2 (`MHZ2_BackgroundInit` / `MHZ2_BackgroundEvent`)

**Disassembly location:** `sonic3k.asm` lines 112824-113108

#### `MHZ2_BackgroundInit` (line 112824)

1. Stores `Camera_X_pos_copy` into `Events_fg_0` and `Events_fg_1`
2. If player X >= $3700 AND player Y < $500: sets `Events_routine_bg = 8` and calls `sub_554B8` (boss area deform)
3. Otherwise: calls `MHZ_Deform` (standard parallax)
4. `Reset_TileOffsetPositionEff` + `Refresh_PlaneFull` + `PlainDeformation`

#### `MHZ2_BackgroundEvent` (line 112846)

First calls `Adjust_BGDuringLoop` with d2=$100 d3=$200.

**State machine (`Events_routine_bg`):**

| Stage | Index | Label | Trigger | Action | Notes |
|-------|-------|-------|---------|--------|-------|
| 0 | 0 | `loc_551EE` | Player X >= $3700 AND Player Y < $500 | Switch to boss deform (`sub_554B8`), begin vertical transition drawing, advance | Transition to boss area BG |
| 0 (else) | 0 | `loc_5521E` | Normal gameplay | `MHZ_Deform` + `Draw_TileRow` + `PlainDeformation` | Standard parallax |
| 1 | 4 | `loc_55236` | Drawing complete | `Draw_PlaneVertSingleBottomUp`, advance when done | Boss BG transition draw |
| 2 | 8 | `loc_55250` | Player Y >= $500 (exit boss area) | Reload standard deform, begin reverse transition, advance to stage 3 | Return to normal BG |
| 2 (alt) | 8 | `loc_5528A` | Player Y <= $420 AND not airborne | **Load custom layout**: `MHZ_Custom_Layout`, `MHZ_128x128_Custom_Kos`, `MHZ_16x16_Custom_Kos`, `ArtKosM_MHZ_Custom` to tile $222. Advance by +8 (to stage 4) | **Dynamic tilemap change for end boss arena** |
| 2 (else) | 8 | `loc_552E0` | Player Y > $420 OR airborne | Boss deform + draw row + `PlainDeformation` | Stay in boss BG mode |
| 3 | $C | `loc_552F8` | Standard deform draw | `MHZ_Deform` + `Draw_PlaneVertSingleBottomUp`, reset `Events_routine_bg = 0` | Return to normal mode |
| 4 | $10 | `loc_55312` | Camera X >= $3F00 | **End boss arena setup**: lock scroll, set `Special_events_routine = $C`, load `ArtKosM_MHZEndBossPillar`, spawn pillar/spike objects, configure Knuckles scroll data | End boss arena entry |
| 4 (else) | $10 | `loc_55312` first check | `Events_bg+$00` already set | Continue arena column drawing | Arena already triggered |
| 5 | $14 | `loc_553B6` | Column drawing complete | `Draw_PlaneVertTopDown`, spawn end boss arena objects (`loc_556F8` pillar, `loc_55732` tall support, `loc_5577C` spike hazards x6), advance | Arena object creation |
| 6 | $18 | `loc_55424` | `Events_fg_5 < 0` (boss defeated) | Begin cleanup draw, advance | Post-boss transition |
| 7 | $1C | `loc_5543A` | Cleanup drawing done | Clear `Events_bg+$01`, advance | Transition back |
| 8 | $20 | `loc_5545A` | Second cleanup phase done | Clear `Events_bg+$00`, reset `Events_routine_fg = 0`, advance | Full arena teardown |
| 9 | $24 | `loc_55486` | Always (all post-arena states) | `sub_554B8` deform + `Draw_TileRow` + `sub_5550C` + `ShakeScreen_Setup` | Boss area standard rendering with screen shake |

#### End Boss Forced Scroll (Act 2)

The end boss arena uses a **forced scroll loop** mechanism (`loc_5560C`, line 113276):

1. Camera advances 4px/frame (5px for Knuckles) rightward
2. When camera X reaches $4280: wrap by subtracting $200 from all positions
3. Player is constrained between camera+$18 and camera+$A8 (pushed forward if too slow)
4. Track counter `Events_bg+$08` increments each wrap, used to index the obstacle pattern table (`word_558E8`)
5. When `_unkFAA9` is set (boss signals end), sets `Events_bg+$0A` to trigger spike deletion and arena teardown

**Scroll lock data table** (`word_558E8`, line 113537): 16 bytes for Sonic/Tails path, 16 bytes for Knuckles path. Each byte encodes the obstacle column configuration for that loop iteration.

## Boss Sequences

### Act 1 Miniboss (Hey Ho / Woodcutter)

- **Object:** `Obj_MHZMiniboss` at line 155584
- **Spawn trigger:** Camera Y >= $710 AND Camera X >= $4298 (in `MHZ1_ScreenEvent`)
- **PLC load:** None explicit in screen event; uses zone PLC art
- **Arena boundaries:** Camera_min_Y = $710, Camera X locked at max
- **Hit points:** 6 (`collision_property`)
- **Associated objects:** `Obj_MHZMinibossTree` (line 156348) -- tree that the miniboss chops
- **Defeat behavior:** Sets `Events_fg_5` flag, which triggers Act 1 -> Act 2 seamless transition in `MHZ1_BackgroundEvent`
- **Palettes:** `Pal_MHZMiniboss` loaded at line 155660
- **Confidence:** HIGH

### Act 2 End Boss (Eggman's Ship / Satellite Dish)

- **Object:** `Obj_MHZEndBoss` at line 156888
- **Spawn trigger:** `Check_CameraInRange` against `word_769F4` thresholds (spawned during the forced scroll section)
- **PLC load:** PLC $7B loaded at spawn; `ArtKosM_MHZEndBoss` queued to `ArtTile_MHZEndBoss`
- **Arena boundaries:** Forced scroll arena with pillar hazards
- **Hit points:** 9 (`collision_property`)
- **Arena setup:** `ArtKosM_MHZEndBossPillar` loaded to `ArtTile_MHZEndBossPillar` at BG event stage 4; `ArtKosM_MHZShipPropeller` loaded to `ArtTile_MHZShipPropeller` at screen event stage 3
- **Defeat behavior:** Triggers `Events_fg_5 = -1` (signed), sets `_unkFAA9`, spawns explosion effects, fades to `mus_EndBoss`, loads `Pal_MHZ1+$20` (green palette) back as target palette
- **Screen shake:** Yes, via `ShakeScreen_Setup` in the BG event loop
- **Palettes:** `Pal_MHZEndBoss` loaded to line 2 at spawn; `Pal_MHZ2Ship` loaded to line 2 at arena entry
- **H-Interrupt:** `HInt6` configured during arena setup for split-screen rendering (boss ship area vs. ground)
- **Confidence:** HIGH

## Parallax

### Shared Deform (`MHZ_Deform`, line 112317)

Both acts share the same core deform function. It computes BG scroll positions from the camera:

**Vertical BG position:**
```
Camera_Y_pos_BG_copy = (Camera_Y_pos_copy * 5/32) + $76
```
Calculation: `asr.l #3` (divide by 8), then `asr.l #2` (divide by 4 of that = 1/32 of original), add both = 5/32.

**Horizontal BG position (3 bands):**
```
Events_fg_1 = smoothed camera X (via Adjust_BGDuringLoop accumulator)
Band 0: Camera_X_pos_BG_copy = Events_fg_1 * 3/8
Band 1: Events_bg+$10 = Band 0 - (Events_fg_1 * 3/32)
Band 2: Events_bg+$12 = Band 1 - (Events_fg_1 * 3/32)
```
Calculation: `asr.l #1` (1/2), then `asr.l #2` (1/4 of that = 1/8), subtract from first = 3/8. Each subsequent band subtracts another 3/32.

**Rendering:** Uses `PlainDeformation` -- a simple non-per-line BG scroll with a single BG X position for the entire screen.

### Act 1 Parallax

**Disassembly location:** `MHZ1_BackgroundEvent` at line 112300

- Uses `Adjust_BGDuringLoop` with delta limits d2=$100, d3=$200 (handles camera wrapping)
- Calls `MHZ_Deform` for position calculation
- `Draw_TileRow` for vertical BG update with d6=$20 (32-pixel rows)
- `PlainDeformation` for final HScroll table fill

**Bands:**

| Band | Description | Speed Factor | Per-line? |
|------|-------------|-------------|-----------|
| 0 (main) | Far background layer | Camera X * 3/8 | No |
| 1 | Mid background | Band 0 - Camera X * 3/32 | No |
| 2 | Near background | Band 1 - Camera X * 3/32 | No |

**Vertical:** Single value: `Camera_Y * 5/32 + $76`

**Confidence:** HIGH

### Act 2 Parallax (Normal Mode)

Same as Act 1 when `Events_routine_bg == 0`.

### Act 2 Boss Area Deform (`sub_554B8`, line 113113)

Used when in the end boss forced-scroll section (Events_routine_bg >= 8):

**Vertical BG position:**
```
Camera_Y_pos_BG_copy = ((Camera_Y_pos_copy - $280) * 5/32) + $180
```
Same formula as `MHZ_Deform` but with Y offset of $280 subtracted and base of $180.

**Horizontal BG position:**
Same band formula as `MHZ_Deform` but subtracts `Screen_shake_offset` from the accumulator input before calculating (the shake is applied to camera copy separately).

### Act 2 Boss Ship Rendering (`sub_5550C`, line 113153)

When boss arena is active (Events_routine_fg == $10):
- Fills entire `H_scroll_buffer` (64 scanline pairs) with negated ship BG X position
- Overrides standard BG rendering with the ship parallax

When `Events_bg+$00` is set but not in ship mode:
- Fills first $18 (24) entries of `H_scroll_buffer` with `Camera_X_pos_BG_copy`

When `Events_bg+$01` is set (pillar visible):
- Complex per-line deformation for pillar rotation effect
- Uses `muls.w #$5600` scaling factor
- Fills 48 scanlines of `H_scroll_buffer+2` with graduated offsets
- Updates spike object positions based on the scroll offsets

**Confidence:** MEDIUM -- the pillar rotation math is complex

## Animated Tiles

### Custom BG Scrolling Tiles (`AnimateTiles_MHZ`, line 54815)

MHZ uses a **custom** `AnimateTiles_MHZ` routine (NOT the generic AniPLC processor) for its first two animations, then falls through to the AniPLC scripts for the remaining four.

#### BG Layer 1 -- Distant Background Scroll (line 54815)

- **Source art:** `ArtUnc_AniMHZ__BG` (`Levels/MHZ/Animated Tiles/BG 1.bin`)
- **Destination VRAM tile:** $1B8
- **Mechanism:** Tracks `Events_bg+$12 - Camera_X_pos_BG_copy`, masked to 5 bits (0-31). When the low-5-bit value changes, performs a split DMA based on column alignment.
- **Frame source logic:** 8 horizontal positions within the art, split into two DMA transfers. Column index = (offset & $18) >> 1, with sizes from `word_28114` table:
  - Column 0: $80 + $00 bytes
  - Column 1: $60 + $20 bytes
  - Column 2: $40 + $40 bytes
  - Column 3: $20 + $60 bytes
- **Effective behavior:** Smooth scrolling background parallax layer using tile art rotation
- **Confidence:** HIGH

#### BG Layer 2 -- Mid Background Scroll (line 54866)

- **Source art:** `ArtUnc_AniMHZ__BG2` (`Levels/MHZ/Animated Tiles/BG 2.bin`)
- **Destination VRAM tile:** $1D5
- **Mechanism:** Same as BG Layer 1 but tracks `Events_bg+$10 - Camera_X_pos_BG_copy`, masked to 6 bits (0-63). Uses `word_28198` size table (8 entries with larger ranges).
- **Column sizes** from `word_28198`:
  - Column 0: $200 + $000 bytes
  - Column 1: $1C0 + $040 bytes
  - Column 2: $180 + $080 bytes
  - Column 3: $140 + $0C0 bytes
  - Column 4: $100 + $100 bytes
  - Column 5: $0C0 + $140 bytes
  - Column 6: $080 + $180 bytes
  - Column 7: $040 + $1C0 bytes
- **Confidence:** HIGH

#### Global Frame Counter

After the custom BG handlers, the routine increments `Anim_Counters+$F` by 2 each frame, wrapping at $58 (44 frames * 2 = 88 byte offset cycle). This counter is used by the AniPLC scripts below.

### AniPLC Script 0: Flower Animation

- **Disassembly location:** `sonic3k.asm` line 55944
- **Source art:** `ArtUnc_AniMHZ__0` (`Levels/MHZ/Animated Tiles/0.bin`)
- **Destination VRAM tile:** $025
- **Frame count:** 20 ($14)
- **Tiles per frame:** 4
- **Duration:** Per-frame (global = -1)
- **Frame data:** 20 entries with per-frame tile IDs and durations:
  - Frames progress through tiles $00, $04, $08, $0C, $10, $14 with varying durations ($18, $00, $00, $00, $00, $00, $31...)
  - Bloom/unfurl cycle with long pauses at start and end
- **Confidence:** HIGH

### AniPLC Script 1: Leaf/Plant Rustle

- **Disassembly location:** `sonic3k.asm` line 55966
- **Source art:** `ArtUnc_AniMHZ__1` (`Levels/MHZ/Animated Tiles/1.bin`)
- **Destination VRAM tile:** $019
- **Frame count:** 12 ($0C)
- **Tiles per frame:** 4
- **Duration:** Per-frame (global = -1)
- **Frame data:** 12 entries:
  - Tiles: $00, $04, $00, $04, $00, $04, $00, $04, $08, $0C, $10, $14
  - Durations: $1D, $00, $00, $00, $00, $00, $00, $01, $1D, $04, $04, $04
  - Pattern: gentle sway (repeat tiles 0-4) then quick rustle (tiles 8-14)
- **Confidence:** HIGH

### AniPLC Script 2: Water/Stream Surface A

- **Disassembly location:** `sonic3k.asm` line 55980
- **Source art:** `ArtUnc_AniMHZ__2` (`Levels/MHZ/Animated Tiles/2.bin`)
- **Destination VRAM tile:** $05D
- **Frame count:** 8
- **Tiles per frame:** 8
- **Duration:** Per-frame (global = -1)
- **Frame data:** 8 entries with tile IDs $00, $08, $10, $08, $00, $18, $20, $18 and durations 4, 4, 6, 4, 4, 4, 6, 4
- **Confidence:** HIGH

### AniPLC Script 3: Water/Stream Surface B

- **Disassembly location:** `sonic3k.asm` line 55990
- **Source art:** `ArtUnc_AniMHZ__3` (`Levels/MHZ/Animated Tiles/3.bin`)
- **Destination VRAM tile:** $01D
- **Frame count:** 8
- **Tiles per frame:** 8
- **Duration:** Per-frame (global = -1)
- **Frame data:** 8 entries with tile IDs $10, $08, $00, $18, $20, $18, $00, $08 and durations 6, 4, 4, 4, 6, 4, 4, 4
- **Note:** Same art/pattern as Script 2 but offset by 2 frames (phase-shifted water animation)
- **Confidence:** HIGH

## Palette Cycling

**MHZ has NO palette cycling.** Both acts map to `AnPal_None` (rts) in the `OffsAnPal` dispatch table (lines 3133-3134).

The seasonal color changes are **palette mutations** driven by the event system (camera position trigger regions), not timer-driven cycling.

## Notable Objects

| Object ID | Name | Description | Zone-Specific? | Notes |
|-----------|------|-------------|----------------|-------|
| -- | `Obj_MHZ_Pollen_Spawner` | Spawns pollen/leaves particles. Max 16 concurrent. Behavior changes with `_unkF7C1` flag (pollen vs. falling leaves) | Yes | Loaded into `Dynamic_object_RAM+object_size` at level start (line 7792) |
| -- | `Obj_MHZ_Pollen` | Individual pollen/leaf particle | Yes | Uses different mappings based on `_unkF7C1` |
| -- | `Obj_MHZMushroomCap` | Bouncy mushroom caps (dark-spotted = palette 2, light-spotted = palette 2) | Yes | SFX: `sfx_MushroomBounce` |
| -- | `Obj_MHZMushroomPlatform` | Mushroom platform (rides player, animated) | Yes | |
| -- | `Obj_MHZMushroomParachute` | Parachute mushroom (floats down slowly with player) | Yes | Art at `ArtTile_MHZMisc+$86` |
| -- | `Obj_MHZMushroomCatapult` | Catapult mushroom (launches player) | Yes | SFX: `sfx_MushroomBounce` |
| -- | `Obj_MHZPulleyLift` | Vine pulley lift mechanism | Yes | Art at `ArtTile_MHZMisc+$DD` |
| -- | `Obj_MHZSwingVine` | Vine swing (shared mapping with AIZ: `Map_AIZMHZRideVine`) | Shared AIZ/MHZ | Art at `ArtTile_MHZMisc+$10E` |
| -- | `Obj_MHZTwistedVine` | Twisted vine (running path) | Yes | |
| -- | `Obj_MHZCurledVine` | Curled vine obstacle | Yes | Art at `ArtTile_MHZMisc+$C` |
| -- | `Obj_MHZStickyVine` | Sticky vine (grabs player) | Yes | Art at `ArtTile_MHZMisc+$C3` |
| -- | `Obj_MHZSwingBarHorizontal` | Horizontal swing bar | Yes | Art at `ArtTile_MHZMisc+$AC` |
| -- | `Obj_MHZSwingBarVertical` | Vertical swing bar | Yes | Art at `ArtTile_MHZMisc+$AC` |
| -- | `Obj_MHZBreakableWall` | Breakable wall | Yes | Art at `ArtTile_MHZMisc+$4`, palette 2 |
| -- | `Obj_MHZ1CutsceneKnuckles` | Knuckles intro cutscene controller (peering + button press) | Yes | Only for Sonic/Tails; loads `ArtTile_MHZKnuxPeer` art |
| -- | `Obj_MHZ1CutsceneButton` | Knuckles button/switch in Act 1 cutscene | Yes | Loads `ArtTile_MHZKnuxSwitch` art |
| -- | `Obj_MHZMiniboss` | Act 1 miniboss (Hey Ho / Woodcutter) | Yes | 24-state routine, 6 HP |
| -- | `Obj_MHZMinibossTree` | Tree target for miniboss axe | Yes | |
| -- | `Obj_MHZEndBoss` | Act 2 end boss (Eggman's satellite dish ship) | Yes | 8-state routine, 9 HP, uses Check_CameraInRange |

### Badniks (from PLCKosM entries)

| Badnik | Art Key | Acts |
|--------|---------|------|
| Madmole | `ArtKosM_Madmole` | 1, 2 |
| Mushmeanie | `ArtKosM_Mushmeanie` | 1, 2 |
| Dragonfly | `ArtKosM_Dragonfly` | 1, 2 |
| Cluckoid (Arrow only) | `ArtKosM_CluckoidArrow` at `ArtTile_Cluckoid+$22` | 2 only |

## Cross-Cutting Concerns

- **Water:** Not present
- **Screen shake:** Present in Act 2 end boss. `Screen_shake_offset` added to `Camera_X_pos_copy` in `MHZ2_ScreenEvent`. `ShakeScreen_Setup` called in `MHZ2_BackgroundEvent` stage 9 (loc_55486). Ship approach uses periodic `sfx_LargeShip` SFX every 16 frames.
- **Act transition:** Act 1 ends with miniboss defeat. Seamless transition: `MHZ1_BackgroundEvent` reloads level data as Act 2, offsets positions by -$4200 X. No fade/title card.
- **Character paths:** Knuckles has:
  - Different Act 1 start position (SK-alone only, from star post 0)
  - `Obj_MHZ1CutsceneKnuckles` cutscene (only runs for Sonic/Tails players)
  - Different forced scroll speeds in Act 2 end boss (+5px vs +4px)
  - Different obstacle patterns in forced scroll (second table at word_558E8+$10)
- **Dynamic tilemap:** Yes. Act 2 BG event stage 2 (`loc_5528A`) loads `MHZ_Custom_Layout` (Layout/3.bin), custom 128x128 chunks to Chunk_table+$2280, custom 16x16 blocks to Block_table+$B28, and custom art to VRAM tile $222. This creates the end boss arena foreground.
- **PLC loading:**
  - PLC $28: loaded during Act 1->2 transition (MHZ2 zone art)
  - PLC $7B: loaded at end boss spawn (boss art)
  - `ArtKosM_MHZEndBossPillar` -> `ArtTile_MHZEndBossPillar` at BG event stage 4
  - `ArtKosM_MHZShipPropeller` -> `ArtTile_MHZShipPropeller` at screen event stage 3
  - `ArtKosM_MHZEndBoss` -> `ArtTile_MHZEndBoss` at boss spawn
  - `ArtKosM_MHZ_Custom` -> VRAM tile $222 at BG event custom layout load
- **Unique mechanics:**
  - **Seasonal palette system:** `_unkF7C1` flag controls visual appearance. When 0 = green/spring (pollen particles, spring colors). When $FF = autumn/winter (falling leaves, gold/brown colors). Affects: pollen spawner behavior, object animation frames (e.g., vine rustle frame cycling), palette line 3.
  - **Level repeat/loop (Act 2):** `loc_54CB0` implements a horizontal level wrap for the forced scroll section. When camera X >= $4400, wraps back by $200. All objects with `render_flags` bit 2 set are adjusted.
  - **H-Interrupt split (Act 2 boss):** `HInt6` configured with counter $80 for boss ship rendering. Splits screen between ship background and ground level.

## Dependency Map

### Events -> Animated Tiles
- **`Events_bg+$10` and `Events_bg+$12`:** MHZ_Deform writes these parallax band X positions -> `AnimateTiles_MHZ` reads them to determine which column of BG art to DMA. The animated tiles directly depend on the deform calculation output.
- **`Camera_X_pos_BG_copy`:** Written by MHZ_Deform -> read by AnimateTiles_MHZ for offset calculation.
- **VRAM tile $222:** Events loads `ArtKosM_MHZ_Custom` to this address during Act 2 boss arena setup. AniPLC scripts do NOT target this range (safe).

### Events -> Palette (Mutations, not cycling)
- **`_unkF7C1` (season flag):** Written by `MHZ2_ScreenInit`, `sub_55008` (trigger regions), and end boss defeat routine -> read by pollen spawner, vine objects, and other objects for visual mode selection.
- **`Events_bg+$04`:** Written by `sub_55008` -> checked by trigger region logic to determine current season state and direction of transition.
- **Palette line 3:** Written by `MHZ2_ScreenInit` initial setup, `sub_55008` trigger regions, and end boss defeat. Sources: `Pal_MHZ1+$20` (green), `Pal_MHZ2+$20` (autumn), `Pal_MHZ2Gold` (gold).
- **Palette line 2:** Written by end boss arena entry (`Pal_MHZ2Ship`) and boss spawn (`Pal_MHZEndBoss`).

### Events -> Parallax
- **`Screen_shake_offset`:** Written by `ShakeScreen_Setup` -> applied to `Camera_X_pos_copy` in `MHZ2_ScreenEvent` and subtracted from BG accumulator input in `sub_554B8`.
- **`Events_routine_bg`:** Determines which deform function runs (standard `MHZ_Deform` vs. boss-area `sub_554B8`). BG event state transitions are triggered by player position thresholds.
- **`Events_fg_0` / `Events_fg_1`:** BG tracking accumulators, initialized from camera X at level start and updated by `Adjust_BGDuringLoop` each frame. Used as input to both deform functions.

### Animated Tiles -> Parallax
- The custom `AnimateTiles_MHZ` routine reads `Events_bg+$10` and `Events_bg+$12` (written by the deform function) to determine the visible column offset. This means the parallax deform must run BEFORE the animated tile DMA to have correct source offsets.

### VRAM Ownership Table

| VRAM Tile Range | Owner | Condition | Notes |
|-----------------|-------|-----------|-------|
| $019 (4 tiles) | AniPLC Script 1 | Always | Leaf/plant rustle |
| $01D (8 tiles) | AniPLC Script 3 | Always | Water surface B |
| $025 (4 tiles) | AniPLC Script 0 | Always | Flower bloom |
| $05D (8 tiles) | AniPLC Script 2 | Always | Water surface A |
| $1B8+ | AnimateTiles_MHZ BG1 | Always | Distant BG scroll (variable size DMA) |
| $1D5+ | AnimateTiles_MHZ BG2 | Always | Mid BG scroll (variable size DMA) |
| $222+ | Events custom art load | Act 2 boss arena entry | Custom level art. No conflict with above. |
| ArtTile_MHZEndBossPillar | Events Kos load | Act 2 BG stage 4 | Pillar/spike art |
| ArtTile_MHZShipPropeller | Events Kos load | Act 2 screen stage 3 | Propeller animation art |
| ArtTile_MHZEndBoss | Boss PLC $7B | Boss spawn | Boss sprite art |
| ArtTile_MHZMisc | Zone PLC | Level start | All zone object art (mushrooms, vines, etc.) |

### Palette Ownership Table

| Palette Entry | Owner | Condition | Notes |
|---------------|-------|-----------|-------|
| Line 3 (full, 32 colors) | Events mutation | Trigger-region driven | Green/Autumn/Gold seasonal swap. 3 source palettes. |
| Line 2 (full, 16 colors) | Events mutation | Boss arena entry | `Pal_MHZ2Ship` overwrites, then `Pal_MHZEndBoss` at boss spawn |
| Line 1 | Boss spawn | Boss active | `Pal_MHZEndBoss` via `PalLoad_Line1` |

## Implementation Notes

### Priority Order

1. **Parallax / MHZ_Deform** -- core scrolling needed for any visual correctness. Simple formula, shared between acts.
2. **AnimateTiles_MHZ (custom BG DMA)** -- the two BG scroll tile updates are tightly coupled to deform outputs. Must run after deform each frame.
3. **AniPLC scripts (4 foreground animations)** -- independent flower/water animations. Standard AniPLC processing.
4. **Act 1 ScreenEvent** -- miniboss arena trigger, dynamic boundaries.
5. **Act 1 BackgroundEvent** -- act transition (seamless reload).
6. **Act 2 ScreenEvent** -- forced scroll, boss arena state machine.
7. **Act 2 BackgroundEvent** -- boss area BG transitions, custom layout load, end boss arena setup.
8. **Season palette mutations** -- trigger regions in Act 2 screen event.
9. **Miniboss (Hey Ho)** -- standard boss with tree interaction.
10. **End Boss (Satellite Ship)** -- complex forced-scroll boss with H-interrupt split.

### Dependencies

- AnimateTiles_MHZ depends on MHZ_Deform having run first (reads `Events_bg+$10`, `Events_bg+$12`)
- Act 2 end boss arena requires custom layout load (MHZ_Custom_Layout) to have completed before arena objects spawn
- End boss H-interrupt setup requires `ArtKosM_MHZShipPropeller` to be decompressed before propeller objects render
- Season palette mutations in Act 2 depend on `sub_55008` trigger region checks (require player position tracking)
- Act 1->2 transition resets all event state -- implementation must handle the mid-frame zone reload cleanly

### Known Risks

- **Forced scroll loop (Act 2):** The $200-pixel wrap mechanism (`loc_5560C`) requires all active objects to have their positions adjusted simultaneously. If any object misses the adjustment, it will appear at the wrong position. The `sub_54CF4` helper iterates all dynamic objects -- engine equivalent needs to do the same.
- **H-Interrupt split (Act 2 boss):** The HInt6 handler splits rendering between boss ship background and ground. This is a hardware-level feature that needs careful emulation in the GPU renderer.
- **Custom layout load (Act 2):** Loading `MHZ_Custom_Layout` replaces the ENTIRE level layout header. This overwrites the layout pointers mid-gameplay. The engine's `MutableLevel` system would need to handle a full layout swap while maintaining object positions.
- **BG DMA in AnimateTiles_MHZ:** The custom handler uses `Add_To_DMA_Queue` with variable-size transfers based on column offset. Engine implementation needs to handle the two-part split DMA pattern (two consecutive transfers that together cover the full column width).
- **Season flag (`_unkF7C1`) scope:** This flag is checked by multiple objects (pollen spawner, vines, mushrooms) outside the event system. It must be stored in a zone-wide accessible location, not just within the event handler.
