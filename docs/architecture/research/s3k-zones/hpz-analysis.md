# S3K HPZ Zone Analysis

## Summary

- **Zone:** Hidden Palace Zone (HPZ)
- **Zone Index:** 0x0A (standard), but loaded as **zone 0x17 act 1** (`Current_zone_and_act = $1701`)
- **Zone Set:** SKL
- **Acts:** 1 only (single act); plus a **Special Stage Arena** sub-level at zone 0x18 act 1 (`$1801`)
- **Water:** No
- **Palette Cycling:** Yes (generic `AnPal_HPZ` glow on palette line 4; the Master Emerald object also writes the same line-4 colors during the altar sequence)
- **Animated Tiles:** Yes (4 AniPLC scripts -- waterfall/gem sparkle animations)
- **Character Branching:** Yes -- Knuckles has a restricted right boundary ($AA0) vs. Sonic/Tails who have a left boundary restriction ($AA0 min X when Y < $480). Knuckles also has a unique cutscene (`CutsceneKnux_HPZ`)
- **Unique Mechanic:** HPZ is a sub-level accessed via Super Emerald ring entry; it is NOT a standard 2-act zone. The Obj_HPZPaletteControl object performs a camera-position-triggered palette swap (Intro palette -> Main palette at Camera X >= $460). Background switches parallax origin based on player X ($EC0 threshold).

## Zone Loading Architecture

HPZ is **not** a standard zone in the level select order. It is a sub-level that shares zone slot 0x17 with LRZ Boss:

| Zone Slot | Act 0 | Act 1 |
|-----------|-------|-------|
| 0x17 | LRZ Boss (LRZ3) | **HPZ** (main) |
| 0x18 | DEZ Boss (DEZ3) | **HPZS** (Special Stage Arena) |

**Entry path:** Player enters HPZ via the Special Stage ring (`SSEntryFlash_GoSS` at line 128383). When the player has all 7 Chaos Emeralds and is in an S&K level (or the subtype is negative), the code sets `Special_bonus_entry_flag = 2` and `Current_zone_and_act = $1701`.

**HPZ-specific loading:** HPZ has a custom loading sequence (at line 63125) that:
1. Loads `Pal_HPZIntro` palette (intro colors at $CCC) fading to `Pal_HPZ` target palette
2. Copies `Layout_HPZ` directly to level layout RAM
3. Decompresses chunks and blocks via `Kos_Decomp` (not standard LevelResourcePlan)
4. Queues primary and secondary art via `Queue_Kos_Module`
5. Loads PLC $48 (teleporter art)
6. Sets camera to initial position ($15A0, $240)
7. Loads objects and adjusts camera X based on `Current_special_stage_2`

**Music:** `mus_LRZ2` (LRZ Act 2 music) for both HPZ and HPZS.

**Level boundaries:**
- HPZ: X=[0, $1880], Y=[0, $B20]
- HPZS: X=[$1500, $1640], Y=[$320, $320]

**Confidence:** HIGH

## Events

### Dynamic_Resize

**Disassembly location:** `sonic3k.asm` line 38808 (LevelResizeArray)

HPZ uses **`No_Resize`** -- there is no Dynamic_Resize state machine. All event logic is handled through `ScreenEvent` and `BackgroundEvent` routines.

**Confidence:** HIGH

### HPZ_ScreenInit (line 119988)

Handles initial camera boundary setup with character branching:

| Condition | Action |
|-----------|--------|
| `Player_mode == 3` (Knuckles) | Set `Camera_max_X_pos = $AA0` (right boundary) |
| `Player_1.y_pos < $480` (Sonic/Tails, upper area) | Set `Camera_min_X_pos = $AA0` (left boundary) |
| Otherwise | No boundary adjustment |

Then calls `Reset_TileOffsetPositionActual` and `Refresh_PlaneFull`.

**Confidence:** HIGH

### HPZ_ScreenEvent (line 120005)

Runs every frame. Handles:

1. **Palette control object spawning:** On first call (when `Events_bg+$00 == 0` and `Palette_fade_timer == 0`), spawns `Obj_HPZPaletteControl` and sets `Events_bg+$00` flag to prevent re-spawning.

2. **Screen shake:** Adds `Screen_shake_offset` to `Camera_Y_pos_copy` every frame.

3. **FG tile refresh:** If `Events_fg_4` is set by the HPZ Knuckles cutscene collapse beat, writes chunk ID `$61` to layout positions at offsets `$30` and `$31` from the FG layout pointer, then calls `Refresh_PlaneScreenDirect` to redraw the screen. Clears `Events_fg_4` after processing.

4. **Normal tile drawing:** Calls `DrawTilesAsYouMove` when no FG refresh is needed.

**Confidence:** HIGH

### HPZ_BackgroundInit (line 120033)

Initializes the background plane:

1. Copies camera X to BG camera tracking
2. **Knuckles BG layout:** If `Player_mode == 3`, modifies two bytes in the BG layout row pointer table (sets `$8E` and `$8F` chunk IDs) -- Knuckles has a slightly different background
3. **BG mode selection:** Based on player X position:
   - X < $EC0: calls `sub_5A32C` (left-area BG origin: d2=$348, d3=0)
   - X >= $EC0: calls `sub_5A334` (right-area BG origin: d2=$E00, d3=$700), sets `Events_routine_bg = 8` (state 2, right-side mode)
4. Initializes HScroll_table entries and draws initial BG plane

**Confidence:** HIGH

### HPZ_BackgroundEvent (line 120067)

**State machine (Events_routine_bg):**

| Stage | Offset | Trigger | Action |
|-------|--------|---------|--------|
| 0 | $00 | Player X >= $EC0 | Switch to right-area BG (sub_5A334), begin row-by-row BG redraw. Advance to stage 1. If X < $EC0, stay in left-area mode with normal BG draw. |
| 1 | $04 | Row draw in progress | Draw BG plane bottom-up using right-area parallax. When complete, advance to stage 2. |
| 2 | $08 | Player X < $EC0 | Switch back to left-area BG (sub_5A32C), begin row-by-row BG redraw. Advance to stage 3. If X >= $EC0, stay in right-area mode with normal BG draw. |
| 3 | $0C | Row draw in progress | Draw BG plane bottom-up using left-area parallax. When complete, reset to stage 0. |

All states call `ShakeScreen_Setup` to process screen shake effects.

**BG transition mechanism:** When the player crosses the X=$EC0 boundary, the BG plane must be completely redrawn because the parallax origin shifts significantly. This is done via `Draw_PlaneVertBottomUpComplex` which draws one row per frame, creating a smooth vertical wipe transition.

**Confidence:** HIGH

### HPZS (Special Stage Arena) Events

**HPZS_ScreenInit (line 120801):**
- If player has Chaos Emeralds (`Chaos_emerald_count != 0`), saves `Current_special_stage` to `HPZ_current_special_stage` with bit 7 set
- Clears FG and BG scroll offsets from the layout pointer table
- Calls `Reset_TileOffsetPositionActual` and `Refresh_PlaneFull`

**HPZS_ScreenEvent (line 120818):** Minimal -- just adds screen shake offset and calls `DrawTilesAsYouMove`.

**HPZS_BackgroundInit (line 120824):** Calls `sub_5A334` (right-area BG), initializes HScroll and draws BG.

**HPZS_BackgroundEvent (line 120840):** Simple -- calls `sub_5A334`, draws BG, applies deformation, calls `ShakeScreen_Setup`. No state machine.

**Confidence:** HIGH

## Boss Sequences

HPZ has **no boss**. It is an emerald shrine zone where the player accesses Special Stages via emerald teleporters.

However, HPZ hosts the **Knuckles cutscene** (`CutsceneKnux_HPZ` at line 131259) where Knuckles is encountered at the Master Emerald altar. This is a staged object sequence, not a single animation:

- The spawn object clones itself into reserved slot 45 and stores that pointer in `_unkFAA4`.
- When the camera reaches the altar region, `loc_63D1A` runs `Check_CameraInRange` / `sub_85D6A`, allocates the cutscene helper, and starts `mus_Knuckles` through `boss_saved_mus`.
- The sprite is converted into the cutscene Knuckles setup: `Map_Knuckles`, `ArtTile_CutsceneKnux`, cutscene collision bounds, and `Pal_CutsceneKnux` on palette line 2.
- DPLC selection is explicit and state-driven through `$44(a0)`: normal Knuckles art, `DPLCPtr_HPZKnucklesGrab`, and `DPLCPtr_SSZKnucklesTired` are all selected from the same state machine.
- The later altar-collapse beat is handled by `loc_64964`, where the sprite sets `Events_fg_4`, triggers screen shake, and spawns the child object chain that follows the floor impact.

That makes the cutscene implementation-ready: the camera-gated setup, art swaps, palette load, and FG refresh trigger are all concrete ROM behaviors.

**Confidence:** HIGH

## Parallax

### HPZ Background Deformation

**Disassembly location:** `sonic3k.asm` line 120234-120237

**BGDrawArray:** Single band of $200 (512px) for tilemap drawing.

**BGDeformArray (band heights):**

| Band | Height (px) | HScroll Source | Description |
|------|-------------|---------------|-------------|
| 0 | $198 (408) | HScroll_table+$008 | Main background area (large static region) |
| 1 | 8 | HScroll_table+$00A | Thin parallax strip |
| 2 | 4 | HScroll_table+$00C | Narrow parallax strip |
| 3 | 4 | HScroll_table+$00E | Narrow parallax strip |
| 4 | 8 | HScroll_table+$010 | Thin parallax strip |
| 5 | 8 | HScroll_table+$012 | Thin parallax strip |
| 6 | $10 (16) | HScroll_table+$014 | Medium parallax strip |
| 7 | 8 | HScroll_table+$016 | Thin parallax strip |
| 8 | $30 (48) | HScroll_table+$018 | Bottom area |

Total height: 408+8+4+4+8+8+16+8+48 = 512 pixels.

**Parallax Speed Calculation (sub_5A32C / sub_5A334):**

Both subroutines use the same math with different origin offsets:

| Mode | X Origin (d2) | Y Origin (d3) | When Active |
|------|--------------|--------------|-------------|
| Left area | $348 | $0 | Player X < $EC0 |
| Right area | $E00 | $700 | Player X >= $EC0 |

**Y scroll formula:** `BG_Y = (CamY - shake + d3) * 3/16 + shake`
- The `3/16` ratio is computed as: `asr.l #4` (divide by 16), then `d0+d0+d1 = 3*d0`

**X scroll formula:** `BG_X = (CamX - d2) * 3/16`
- Same 3/16 ratio applied to X

**Scatter-fill bands:** After computing the base BG_X scroll, the routine fills HScroll_table entries $00C through $01C with progressively offset values. The loop computes: `base - base*3/16 - (base*3/64)*i` for 9 iterations, creating subtle parallax differences in the lower bands.

**Auto-scroll accumulators:** None.

**Vertical scroll:** Single BG Y value (not per-column).

**Confidence:** HIGH

## Animated Tiles

### AniPLC Scripts (AniPLC_HPZ at line 56322)

HPZ uses the generic `AnimateTiles_DoAniPLC` handler (no custom wrapper routine). Four independent animation scripts run simultaneously:

### Script 0: Waterfall/Gem effect A

- **Declaration:** `zoneanimdecl 2, ArtUnc_AniHPZ__0, $2D0, 8, 3`
- **Art source:** `Levels/HPZ/Animated Tiles/0.bin`
- **Destination VRAM tile:** $2D0
- **Frame count:** 8
- **Frame duration:** 2 game frames
- **Tiles per frame:** 3 (each frame = 3 tiles * 32 bytes = 96 bytes)
- **Frame offsets:** 0, 3, 6, 9, $C, $F, $12, $15 (stride 3 tiles)
- **Confidence:** HIGH

### Script 1: Waterfall/Gem effect B

- **Declaration:** `zoneanimdecl 3, ArtUnc_AniHPZ__1, $2D3, 6, 2`
- **Art source:** `Levels/HPZ/Animated Tiles/1.bin`
- **Destination VRAM tile:** $2D3
- **Frame count:** 6
- **Frame duration:** 3 game frames
- **Tiles per frame:** 2 (each frame = 2 tiles * 32 bytes = 64 bytes)
- **Frame offsets:** 0, 2, 4, 6, 8, $A (stride 2 tiles)
- **Confidence:** HIGH

### Script 2: Waterfall/Gem effect C

- **Declaration:** `zoneanimdecl 2, ArtUnc_AniHPZ__2, $2D5, 8, 4`
- **Art source:** `Levels/HPZ/Animated Tiles/2.bin`
- **Destination VRAM tile:** $2D5
- **Frame count:** 8
- **Frame duration:** 2 game frames
- **Tiles per frame:** 4 (each frame = 4 tiles * 32 bytes = 128 bytes)
- **Frame offsets:** 0, 4, 8, $C, $10, $14, $18, $1C (stride 4 tiles)
- **Confidence:** HIGH

### Script 3: Waterfall/Gem effect D

- **Declaration:** `zoneanimdecl 3, ArtUnc_AniHPZ__3, $2D9, 6, 3`
- **Art source:** `Levels/HPZ/Animated Tiles/3.bin`
- **Destination VRAM tile:** $2D9
- **Frame count:** 6
- **Frame duration:** 3 game frames
- **Tiles per frame:** 3 (each frame = 3 tiles * 32 bytes = 96 bytes)
- **Frame offsets:** 0, 3, 6, 9, $C, $F (stride 3 tiles)
- **Confidence:** HIGH

### VRAM Tile Layout

| VRAM Range | Script | Tiles |
|------------|--------|-------|
| $2D0-$2D2 | Script 0 | 3 tiles |
| $2D3-$2D4 | Script 1 | 2 tiles |
| $2D5-$2D8 | Script 2 | 4 tiles |
| $2D9-$2DB | Script 3 | 3 tiles |

Total: 12 animated tiles covering VRAM $2D0-$2DB.

## Palette Cycling

### Channel 0: Emerald Glow

- **Disassembly location:** `sonic3k.asm` line 3934
- **Counter:** `Palette_cycle_counter0` (step counter), `Palette_cycle_counter1` (timer)
- **Guard:** `Palette_cycle_counters+$00` -- if nonzero, cycling is suppressed (used during super/hyper palette fade)
- **Step:** 4 bytes (2 colors per tick)
- **Limit:** $28 (40 bytes = 10 frames in cycle, wraps to 0)
- **Timer:** 7 game frames between ticks (`move.w #7,(Palette_cycle_counter1).w`)
- **Table:** `AnPal_PalHPZ` at line 4593
- **Destination:** `Normal_palette_line_4+$2` (palette line 4, colors 1-2)
- **Conditional:** Suppressed when `Palette_cycle_counters+$00 != 0` (super palette transition active)
- **Confidence:** HIGH

**Palette data (AnPal_PalHPZ):**

| Frame | Color 1 | Color 2 | Description |
|-------|---------|---------|-------------|
| 0 | $00E | $00A | Dark green-blue |
| 1 | $00E | $00A | Same |
| 2 | $00E | $00A | Same |
| 3 | $00E | $00A | Same (4 frames hold) |
| 4 | $00C | $008 | Slightly darker |
| 5 | $00A | $006 | Dimming |
| 6 | $008 | $004 | Dark |
| 7 | $008 | $004 | Dark (2 frames hold) |
| 8 | $00A | $006 | Brightening |
| 9 | $00C | $008 | Return to mid |

Cycle period: 10 steps * 8 frames = 80 frames total (1.33 seconds at 60fps).

The cycling creates a slow pulsing glow effect on the emerald/gem tiles -- steady bright, then dim, then bright again.

## Notable Objects

| Object | Label | Description | Zone-Specific? | Notes |
|--------|-------|-------------|----------------|-------|
| Palette Control | `Obj_HPZPaletteControl` (line 133498) | Camera-triggered palette swap between Intro and Main palettes | Yes | Spawned by ScreenEvent; persists for zone lifetime |
| Master Emerald | `Obj_HPZMasterEmerald` (line 197478) | Large emerald at altar; runs palette rotation script when all emeralds collected | Yes | Writes to palette line 4 colors 1-2; shares the same bytes as `AnPal_HPZ` |
| Super Emerald | `Obj_HPZSuperEmerald` (line 197545) | 7 emerald pedestals around altar; subtype = emerald index (0-6) | Yes | Color based on collection state; gray/colored/super states |
| SS Entry Control | `Obj_HPZSSEntryControl` (line 197737) | Manages emerald room setup -- loads art, seeds the altar palette target, spawns Master Emerald, teleporter, and Super Emeralds | Yes | Queue-loads art for small emeralds and teleporter; initializes `Target_palette_line_4+$2` before the Master Emerald object starts updating |
| Teleporter | `Obj_SSZHPZTeleporter` (line 90924) | Shared between SSZ and HPZ; enters Special Stage | No (shared) | Uses `ArtTile_HPZTeleporter` in HPZ, `ArtTile_SSZTeleporter` in SSZ |
| Collapsing Bridge | `Map_HPZCollapsingBridge` (line 45029) | Platform that collapses when stood on | Shared with LRZ | Detects zone ($16 or $09) for mapping/art selection |

## Cross-Cutting Concerns

- **Water:** Not present.
- **Screen shake:** `ShakeScreen_Setup` is called every frame from BackgroundEvent. HPZ supports both timed screen shake (positive `Screen_shake_flag`) and constant screen shake (negative flag, e.g., during cutscenes). The screen shake offset is applied to `Camera_Y_pos_copy` in ScreenEvent and to the BG Y calculation in the parallax routines.
- **Act transition:** HPZ is a single-act zone. The level ends when the player enters a teleporter to go to a Special Stage, or completes the Special Stage Arena. After the Special Stage results screen, the game returns to the previously saved zone/act (`Special_stage_zone_and_act`).
- **Character paths:** Knuckles has `Camera_max_X_pos = $AA0` (restricted from going right beyond the altar area). Sonic/Tails have `Camera_min_X_pos = $AA0` when starting in the upper area (Y < $480). The `CutsceneKnux_HPZ` cutscene (line 131259) loads Knuckles cutscene palette and sets up the encounter. Knuckles' BackgroundInit modifies BG layout bytes.
- **Dynamic tilemap:** `Events_fg_4` is produced by the HPZ Knuckles cutscene at `loc_64964` during the collapse/floor-impact beat. `HPZ_ScreenEvent` consumes the flag on the next frame, writes chunk `$61` to the FG layout at offsets `$30` and `$31`, and calls `Refresh_PlaneScreenDirect` immediately. This is a one-shot scene refresh, not a general object-driven tile streamer.
- **PLC loading:** PLC $48 loaded during level init (contains `ArtKosM_Teleporter` at `ArtTile_HPZTeleporter`). `Obj_HPZSSEntryControl` dynamically queues additional art: `ArtKosM_HPZSmallEmeralds` and `ArtKosM_Teleporter`.
- **Unique mechanics:** HPZ is the Super Emerald hub zone. The Emerald collection state drives most object behavior -- which emeralds are displayed, whether the Master Emerald glow animates, and which teleporter state is shown. The `HPZ_special_stage_completed` and `HPZ_current_special_stage` flags track progression through the Special Stage gauntlet.

## Dependency Map

### Events -> Palette Cycling
- **`Palette_cycle_counters+$00` guard:** When super palette transition is active (nonzero), `AnPal_HPZ` cycling is suppressed. This flag is set by `SuperHyper_PalCycle` (line 4608) during super/hyper transformations.
- **`Palette_fade_timer`:** HPZ_ScreenEvent delays spawning `Obj_HPZPaletteControl` until `Palette_fade_timer == 0`, ensuring the intro palette fade-in completes before the camera-triggered palette swap can activate.

### Events -> Parallax
- **Screen shake:** ScreenEvent adds `Screen_shake_offset` to `Camera_Y_pos_copy`. BackgroundEvent calls `ShakeScreen_Setup` to update the offset. The BG Y calculation in `sub_5A32C/sub_5A334` subtracts the shake offset before computing parallax, then re-adds it, creating correct visual shake.
- **BG mode transition:** BackgroundEvent tracks player X position and triggers a full BG redraw when crossing the $EC0 boundary. This changes the parallax origin from (d2=$348, d3=0) to (d2=$E00, d3=$700), creating a seamless background shift.

### Obj_HPZPaletteControl -> Palette (Mutation)
- **Camera-triggered swap:** When Camera_X_pos crosses $460, `Obj_HPZPaletteControl` (line 133498) copies either `Pal_HPZIntro` or `Pal_HPZ` (96 bytes, $60 = 48 colors) to `Normal_palette_line_2`. This is a one-time mutation per direction crossing, not cycling.
- **Palette table:** Uses `HPZPaletteControl_PalIndex` (line 133520): index 0 = `Pal_HPZIntro`, index 4 = `Pal_HPZ`.

### Obj_HPZMasterEmerald -> Palette Cycling (SHARED WRITES)
- **Palette line 4 colors 1-2:** The Master Emerald object writes to `Normal_palette_line_4+$2` from its object update:
  - If not all emeralds are collected (`$26(a0) == 0`), it writes the static `$6A00660` color pair.
  - If all emeralds are collected and `_unkFAC1 == 0`, it calls `Run_PalRotationScript2` with `off_914CE` data into the same target bytes.
- **AnPal_HPZ also targets `Normal_palette_line_4+$2`** -- the generic HPZ palette cycle writes the same 4 bytes on its own timer.
- **Runtime ownership:** Treat these as two separate writers, not one system with exclusive ownership. `Obj_HPZMasterEmerald` is the altar-specific writer; `AnPal_HPZ` is the zone-global palette animation pass. The shared bytes are therefore controlled by update ordering and by the `Palette_cycle_counters+$00` guard that suppresses `AnPal_HPZ` during super/hyper palette transitions.

### VRAM Ownership Table

| VRAM Tile Range | Owner | Condition | Notes |
|-----------------|-------|-----------|-------|
| $000-$2CF | Level art | Always | Primary/secondary KosinskiM art |
| $2D0-$2DB | AniPLC scripts 0-3 | Always | 12 animated tiles |
| $52E+ | Teleporter | PLC $48 loaded | `ArtTile_HPZTeleporter` |
| $4AC+ | Small emeralds | Obj_HPZSSEntryControl loads | `ArtTile_HPZSmallEmeralds` |
| $488+ | Entry teleporter | Obj_HPZSSEntryControl loads | `ArtTile_HPZEntryTeleporter` |
| $3B5+ | Emerald misc | Level art | `ArtTile_HPZEmeraldMisc` |
| $477+ | Gray emerald | Level art | `ArtTile_HPZGrayEmerald` |

### Palette Ownership Table

| Palette Entry | Owner | Condition | Notes |
|---------------|-------|-----------|-------|
| Line 2 (48 colors) | Obj_HPZPaletteControl | Camera X threshold | One-shot swap between Intro/Main palettes |
| Line 2 ($C offset) | CutsceneKnux_HPZ | Knuckles cutscene | Writes $88 to single color |
| Line 3 (32 colors) | Level load | Init only | Loaded from Pal_HPZIntro, target from Pal_HPZ |
| Line 4, colors 1-2 | AnPal_HPZ | Timer-driven cycling | Zone-global emerald glow pulse; suppressed by `Palette_cycle_counters+$00` |
| Line 4, colors 1-2 | Obj_HPZMasterEmerald | When onscreen | Static write before collection; palette rotation script after collection; shares the same bytes as `AnPal_HPZ` |

## Implementation Notes

### Priority Order
1. **Parallax** -- The dual-mode BG system with X-threshold switching is the most visible feature. The 9-band deform array and 3/16 speed ratio define the zone's visual identity.
2. **Animated tiles** -- 4 straightforward AniPLC scripts with no custom wrapper. Generic `AnimateTiles_DoAniPLC` handler, no gating conditions.
3. **Palette cycling** -- Single AnPal channel with simple counter/step/limit pattern. Must handle the `Palette_cycle_counters+$00` suppression guard.
4. **Obj_HPZPaletteControl** -- Camera-triggered palette mutation (Intro -> Main palette swap). Spawned by ScreenEvent, not part of AnPal.
5. **Master Emerald palette interaction** -- Shared line 4 writer that overlaps with the generic `AnPal_HPZ` cycle; implement it as an altar-specific object update, not as part of the zone palette cycler.

### Dependencies
- Parallax implementation requires the dual-mode BG switching (sub_5A32C vs sub_5A334) to be implemented together -- they share the same HScroll_table entries.
- The `Obj_HPZPaletteControl` should be implemented as a zone object, not as part of the palette cycling system (it is a one-shot mutation, not timer-driven cycling).
- The Master Emerald's palette writes depend on emerald collection state (`Collected_emeralds_array`), which is part of the save game system.
- PLC $48 must be registered in the PLC table for teleporter art to load.

### Known Risks
- **BG transition visual glitch risk (MEDIUM):** The row-by-row BG redraw during X=$EC0 crossings uses `Draw_PlaneVertBottomUpComplex`, which draws one row per frame. If the engine's BG draw system doesn't support this incremental approach, the transition may appear as a sudden snap rather than a smooth wipe.
- **Palette overlap (LOW):** `AnPal_HPZ` and `Obj_HPZMasterEmerald` both target the same line 4 bytes. The ROM resolves this by running them as separate writers; if the engine's palette update ordering drifts from the ROM, the altar glow can change shape even though the target bytes are correct.
- **Sub-level zone encoding (LOW):** HPZ uses zone 0x17 act 1, not zone 0x0A. All table lookups (AnPal, AniPLC, Screen/BG events, music, level sizes) must use the $1701 encoding. The engine's zone registry must map HPZ to this sub-level slot correctly.
- **Cutscene state naming (LOW):** The key cutscene transitions are concrete, but several intermediate `loc_64xxxx` states are still flow-named rather than semantically named. The implementation hooks are known; the remaining work is a readability pass, not a behavior investigation.
