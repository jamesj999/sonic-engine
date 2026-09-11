# S3K SSZ Zone Analysis

## Summary

- **Zone:** Sky Sanctuary Zone (SSZ)
- **Zone Index:** 0x0A (Current_zone_and_act = $A00 / $A01)
- **Zone Set:** SKL (S&K zones 7-13)
- **Acts:** 2 acts with completely different art sets and gameplay
  - **Act 1 ($A00):** Sonic/Tails only (Knuckles denied in level select). Tall vertically-wrapping zone with three boss fights (GHZ boss recreation, MTZ boss recreation, Mecha Sonic). Features crumbling tower ascent, Death Egg launch sequence, and transition to the ending/Doomsday Zone.
  - **Act 2 ($A01):** Knuckles only (Sonic/Tails denied in level select). Flat arena zone for Mecha Sonic + Super Mecha Sonic boss fight.
- **Water:** No
- **Palette Cycling:** Act 1: No (AnPal_None). Act 2: Yes -- sub_5928C (water cycle, 3 colors, palette line 4) and sub_592EE (emerald glow, 2 colors, palette line 4), both used conditionally during the ending sequence.
- **Animated Tiles:** Yes (6 scripts shared for both acts via AniPLC_SSZ)
- **Character Branching:** Extreme -- Acts are entirely character-segregated. Act 1 = Sonic/Tails, Act 2 = Knuckles. Different level art, layout, objects, bosses, and event systems.
- **Special Features:** Vertical wrapping (Act 1, Camera_min_Y_pos = -$100), screen shake, Death Egg launch cutscene, teleporter beam intro, roaming cloud sprites, multi-layer parallax clouds, collapsing tower sequence, custom block/chunk/pattern hot-swap mid-level.

## Current Engine Status (2026-08-08)

The earlier claim that SSZ has a native `SpawnLevelMainSprites` falling
introduction was stale. In the owning S&K routine, `loc_68A6` is reached by the
LRZ1 `$0900` comparison and the LRZ boss `$1600` comparison
(`docs/skdisasm/sonic3k.asm:8161-8178`); SSZ's `$0A00/$0A01` values are listed
separately as SSZ acts (`sonic3k.asm:10154-10157`) and are not compared there.
The S3 standalone routine likewise has no SSZ branch
(`docs/skdisasm/s3.asm:6175-6260`). Existing SSZ event and teleporter behavior
therefore does not need a falling-introduction override. The negative SSZ gate
is covered by `TestS3kLrzFallingIntroBootstrap` so a future change cannot
silently reintroduce the stale attribution.

## Level Boundaries

From `LevelSizes` (sonic3k.asm line 38112-38113):

| Act | X Start | X End | Y Start | Y End | Notes |
|-----|---------|-------|---------|-------|-------|
| 1 (Sonic) | $0000 | $19A0 | -$100 | $1000 | Y-wraps; min_Y=-$100 enables vertical wrapping |
| 2 (Knuckles) | $0000 | $6000 | $0000 | $0400 | Flat arena, nominal $6000 X (actual playable area much smaller) |

**Screen wrap values:** Both acts get Screen_X_wrap_value = $FFFF, Screen_Y_wrap_value = $FFFF (set at Get_LevelSizeStart, line 38087-38088). The Y-wrapping is activated by Camera_min_Y_pos = -$100 (SSZ1 only).

## Level Resources

From `LevelLoadBlock` (sonic3k.asm line 199448-199449):

| Act | Palette | Patterns (Pri) | Patterns (Sec) | Blocks (Pri) | Blocks (Sec) | Chunks (Pri) | Chunks (Sec) |
|-----|---------|----------------|----------------|--------------|--------------|--------------|--------------|
| 1 | $32 | ArtKosM_SSZ1_Primary | ArtKosM_SSZ1_Secondary | SSZ1_16x16_Primary_Kos | SSZ1_16x16_Secondary_Kos | SSZ1_128x128_Primary_Kos | SSZ1_128x128_Secondary_Kos |
| 2 | $34 | ArtKosM_SSZ2 | ArtKosM_SSZ2 | SSZ2_16x16_Kos | SSZ2_16x16_Kos | SSZ2_128x128_Kos | SSZ2_128x128_Kos |

**Act 1 Custom resources:** SSZ1 has a third set of "Custom" blocks/chunks/patterns (SSZ1_16x16_Custom_Kos, ArtKosM_SSZ1_Custom, SSZ1_128x128_Custom_Kos) hot-loaded during the Death Egg launch sequence (ScreenEvent stage $04, line 115976-115987).

**Collision indices:** $1E (act 1), $1F (act 2)

**Palettes:**
- Act 1: Pal_SSZ1 (line 200651)
- Act 2: Pal_SSZ2 (line 200654)
- Boss-specific: Pal_SSZDeathEgg (line 116375), Pal_SSZMTZOrbs (line 164152), Pal_SSZGHZMisc (line 167560)
- Ending: Pal_Ending1 (line 200657), Pal_Ending2 (line 200660), Knuckles SSZ End palette (line 135416)

## PLCs (Pattern Load Cues)

**Level PLC:** PLC_32_33_34_35 (both acts, line 199804-199807)
- `plreq ArtTile_SSZMisc, ArtNem_SSZMisc`
- `plreq ArtTile_SSZCutsceneButton, ArtNem_GrayButton`

**Enemy PLC:** PLCKosM_SSZ (line 64424-64426)
- `plreq ArtTile_EggRoboBadnik, ArtKosM_EggRoboBadnik`

**Animal PLC:** PLC_Animals_SSZ (line 180957-180960)
- `plreq ArtTile_Animals1, ArtNem_Rabbit`
- `plreq ArtTile_Animals2, ArtNem_Chicken`

**Boss art:** Loaded dynamically by boss objects:
- GHZ Boss: PLC $7B + ArtKosM_SSZGHZMisc (line 162592-162595)
- MTZ Boss: PLC $7B + ArtKosM_SSZMTZOrbs (line 163032-163036)
- Mecha Sonic: ObjSlot_MechaSonic + ArtKosM_MechaSonicExtra (line 164194-164227)
- Death Egg sequence: ArtKosM_SSZ1_Custom + ArtKosM_SSZSpiralRamp (line 115982-115987)

## Events (Screen Events / Background Events)

### S3 Resize Table

SSZ is an S&K zone; the S3 `LevelResizeArray` (line 38808) maps SSZ slots to `No_Resize` (line 38823-38824). All event handling is via the S&K ScreenEvent/BackgroundEvent system.

### Act 1 Screen Init (SSZ1_ScreenInit)

**Disassembly location:** sonic3k.asm line 115846

**Initialization sequence:**
1. If no checkpoint: Spawns `Obj_57C1E` (teleporter beam controller) at X=$100 with subtype $6C. Sets `Events_bg+$05` flag.
2. Forces Camera_max_X_pos = $200, Camera_max_Y_pos = $BC0.
3. Forces camera to ($60, $F49) and locks scroll (`Scroll_lock = true`).
4. Clears `_unkEE98` and `_unkEE9C` (cloud offset accumulators).
5. Clears 5 words at HScroll_table+$1F6 (cloud sprite position cache).
6. Allocates 5 roaming cloud sprite objects from `word_58758` data table -- each gets position, velocity, and mapping frame.
7. Calls `sub_5758A` (cloud sprite position update), `Reset_TileOffsetPositionActual`, `Refresh_PlaneFull`.

### Act 1 Screen Event (SSZ1_ScreenEvent)

**Disassembly location:** sonic3k.asm line 115899

**Pre-dispatch:** Adds `Screen_shake_offset` to `Camera_Y_pos_copy` (screen shake applied to FG). Dispatches on `Events_routine_fg`.

**State machine (Events_routine_fg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Normal play | If `End_of_level_flag` not set: calls sub_575EA (dynamic boundary manager), sub_5758A (cloud sprite update), DrawTilesAsYouMove. If `End_of_level_flag` set: spawns `Obj_57E96` (Death Egg launch controller) with timer $1E, sets up crumbling platform data at HScroll_table+$E0, calls sub_5750C (crumbling platform manager), restores player control then immediately locks it, sets Scroll_lock, Screen_shake_flag (constant), Special_V_int_routine=4, advances to stage $04. | The End_of_level_flag transition is triggered by Mecha Sonic's defeat. |
| 4 | $04 | Crumbling sequence | Checks Events_fg_4: if zero, runs sub_5750C (crumbling platform physics). Uses word_577B2 (FG VScroll deform array) and DrawTilesVDeform. When Events_fg_4 becomes nonzero: hot-loads SSZ1_128x128_Custom_Kos (chunks at Chunk_table+$180), SSZ1_16x16_Custom_Kos (blocks at Block_table+$B8), ArtKosM_SSZ1_Custom (patterns at tile $073), and ArtKosM_SSZSpiralRamp (patterns at ArtTile_SSZSpiralRamp). Patches chunk data bytes to replace terrain with Death Egg structure. Loads Pal_SSZDeathEgg into palette line 2. Calls sub_574DC (Death Egg BG position calc). Sets `_unkEE98` flag. Spawns collapse debris objects (loc_58234, loc_58360, loc_582AC, loc_581F2) at staggered Y positions around ($1A38, $5A8). Sets Special_V_int_routine=12, advances to stage $08. | This is the dramatic Death Egg reveal and crumbling tower sequence. |
| 8 | $08 | Death Egg visible | Runs sub_574DC (calculates Death Egg BG camera from main camera). Draws tile columns and rows for the Death Egg background plane using _unkEEEA/_unkEEEE as position trackers. | Terminal state -- the Death Egg BG scrolls independently as the tower crumbles. |

### Act 1 Dynamic Boundaries (sub_575EA)

**Disassembly location:** sonic3k.asm line 115193

**Called from:** SSZ1_ScreenEvent stage 0 (normal play)

**Boss arena detection and boundary management:**

1. **Final arena check (line 115196):** If Camera_X_pos >= $19A0 AND Player Y < $680: lock Camera_min_X_pos = $19A0, Camera_min_Y_pos = Camera_target_max_Y_pos = $5C0. Set `Events_bg+$06` flag. This is the Mecha Sonic final arena at the top of the zone.

2. **Normal scrolling (line 115208):** If `Events_bg+$05` is set (teleporter intro not yet done), skip all boundary logic.

3. **Y boundary tables (line 115219):**
   - `word_5778A` -- Camera_min_Y_pos lookup (X thresholds, ascending):
     | Until Player X | Min Y |
     |---------------|-------|
     | $0EE0 | $0D00 |
     | $11E0 | $0CC0 |
     | $1340 | $0B20 |
     | $7FFF | $0000 |
   - `word_5779A` -- Camera_max_Y_pos lookup (X thresholds, ascending):
     | Until Player X | Max Y |
     |---------------|-------|
     | $0640 | $0C60 |
     | $0880 | $0CA0 |
     | $1200 | $0C60 |
     | $1380 | $0A80 |
     | $13C0 | $0660 |
     | $7FFF | $03E0 |

4. **MTZ Boss trigger (line 116262):** When Player Y is in range $440-$880 and `Events_bg+$00` is not negative and not already triggered:
   - If `Events_bg+$01` not yet set: lock Camera_min_X_pos=$160, Camera_max_X_pos=$19A0. When Player Y >= $7C0 and Camera X == $160 and player on ground: lock Camera_max_X_pos=$160, Camera_min/target_max_Y_pos=$7C0. Set `Events_bg+$01`.
   - When Camera Y reaches $7C0: spawn `Obj_SSZGHZBoss` (GHZ recreation boss). Set `Events_bg+$05`, Events_bg+$00 = $7F00.

5. **GHZ Boss trigger (line 116298):** When Player Y < $440 and `Events_bg+$02` is not negative:
   - If `Events_bg+$03` not yet set: lock Camera_max_X_pos=$1660. When Player Y >= $420 and Camera X == $1660 and player on ground: lock Camera_min_X_pos=$1660, Camera_min/target_max_Y_pos=$380. Set `Events_bg+$03`.
   - When Camera Y reaches $380: spawn `Obj_SSZMTZBoss` (MTZ recreation boss). Set `Events_bg+$05`, Events_bg+$02 = $7F00.

6. **Reset (line 116337):** When Player Y >= $880 or `Events_bg+$00/02` is negative: clear Camera_min_X_pos=0, Camera_max_X_pos=$19A0.

**Confidence:** HIGH

### Act 1 Crumbling Platform Manager (sub_5750C)

**Disassembly location:** sonic3k.asm line 116094

Manages 10 crumbling platforms stored in HScroll_table+$80. Each platform has:
- Position (4 bytes at HScroll_table+$80)
- Timer (2 bytes at HScroll_table+$E0)
- Velocity (4 bytes at HScroll_table+$100)
- Offset (4 bytes at HScroll_table+$140)

Platforms accelerate downward ($800 per frame added to velocity), accumulate offset, and stop at Y=$580. When all platforms have stopped (counter reaches 0), sets `Events_fg_4+1` and deletes the platform tracking object.

Also tracks the player object's position relative to platforms for ride detection, adjusting the player's Y screen position by the platform displacement.

### Act 1 Cloud Sprite Update (sub_5758A)

**Disassembly location:** sonic3k.asm line 116148

Updates 5 cloud sprite positions stored at HScroll_table+$1F6. For each sprite:
- Calculates effective Y = Camera_Y_pos * 1.25 + _unkEE9C * 0.625 + shake_offset
- Calculates effective X = Camera_X_pos * 1.25
- Positions sprites at (sprite.$3A - effectiveX) & $1FF + $50, (sprite.$38 - effectiveY) & $FF + $70

These are foreground decorative cloud objects that parallax relative to the camera.

### Act 2 Screen Init (SSZ2_ScreenInit)

**Disassembly location:** sonic3k.asm line 117719

**Initialization sequence:**
1. Sets `Palette_cycle_counters+$00` flag (enables palette cycling gate).
2. Spawns `Obj_57C1E` (teleporter beam) at X=$A0 with subtype $44.
3. Forces camera to (0, $649) and locks scroll.
4. Spawns `loc_59078` object (ending sequence camera controller) with $30=1.
5. Calls `Reset_TileOffsetPositionActual` and `Refresh_PlaneFull`.

### Act 2 Screen Event (SSZ2_ScreenEvent)

**Disassembly location:** sonic3k.asm line 117744

**Pre-dispatch:** Adds `Screen_shake_offset` to `Camera_Y_pos_copy`. Dispatches on `Events_routine_fg`.

**State machine (Events_routine_fg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Level start | Waits until Camera_min_Y_pos == Camera_max_Y_pos (camera settled). Adds 4 to Special_V_int_routine, advances to $04. | Initial sync wait. |
| 4 | $04 | Events_fg_4 set | Patches level layout bytes at the FG row pointed to by $24(a3) and $28(a3) -- writes tile IDs $17/$18/$19 to create the arena floor. Sets `Ending_running_flag`. Calls Refresh_PlaneFullDirect. Advances to $08. | Creates the boss arena platform dynamically. |
| 8 | $08 | Events_fg_4 set (positive) | Fills VRAM tiles $7F0-$800 with solid $66666666 pattern. Spawns `loc_591D6` (floor collapse visual). Sets Draw_delayed_position=$1F0 with 15 rows. Clears palette cycle gate. Advances to $0C. | Creates solid floor tiles for Death Egg sequence. |
| C | $0C | Auto | Draws plane vertically (bottom-up) using Draw_PlaneVertBottomUp with d1=$200, d2=$100. When complete: clears Camera Y to 0, clears Camera X, advances to $10. | Scrolls new floor into view. |
| 10 | $10 | Post-boss sequence | Calls sub_5B18E (ending sequence manager). Branches based on emerald count: If >= 7 Super Emeralds OR >= 7 Chaos Emeralds: runs sub_592EE (emerald palette cycle), waits for _unkFAAE. Target Y is $14C0 (SK alone) or $1660 (S3K). When Camera Y reaches target with Events_routine_bg == 0: loads Pal_Ending2 into palette line 2, adjusts BG camera, sets Events_routine_bg += 4. If < 7 Chaos Emeralds: waits for Events_fg_4, loads Pal_Ending1, sets Draw_delayed at $7F0, advances to $14. | Determines good/bad ending path. $14C0/$1660 heights lead to Doomsday zone (good ending) or credits (bad ending). |
| 14 | $14 | Bad ending | Draws plane vertically (bottom-up) with d1=0, d2=$700. When complete: sets Camera Y = $720, advances to $18. | Scrolls to ending scene. |
| 18 | $18 | Normal draw | Calls sub_5928C (water palette cycle). Standard tile row drawing. | Terminal state for bad ending. |

**Ending sequence manager (sub_5B18E, line 121468):** 4-phase state machine at `_unkFA86`:
- Phase 0: Clears camera, _unkFAAE. Spawns Obj_Song_Fade_Transition (Knuckles credits music if Knuckles). Calls sub_5B514 (ending scene setup).
- Phase 2: Loads ending art, palette. Handles camera drift and scroll.
- Phase 4: Manages Doomsday transition (if good ending) or credits scroll.
- Phase 6: Terminal rts.

### Act 2 Camera Controller Object (loc_59078)

**Disassembly location:** sonic3k.asm line 118362

3-phase object controlling SSZ2's vertical camera motion:

| Phase | Behavior |
|-------|----------|
| 0 (loc_5908E) | Waits for Special_V_int_routine. Adds $11B per frame to `_unkEE9C`. Uses Gradual_SwingOffset (amplitude $2000, step $4A) on `Events_bg+$00` for oscillating column deformation. When swing reaches zero twice (two full oscillations with Events_fg_4 negative): clears Events_fg_4, sets Special_V_int_routine=12. Advances. |
| 4 (loc_590E4) | **Good ending (>=7 emeralds):** Accelerates `$3C` up to $8000. Subtracts accumulated velocity from _unkEE9C. Camera Y drifts upward toward $318+_unkEE9C offset of $D0. When Camera Y reaches $2A0: sets Events_fg_4+1, deletes self. **Bad ending (<7 emeralds):** Same acceleration. Adds accumulated velocity to _unkEE9C (opposite direction). Camera Y drifts downward. When Camera Y reaches $600: sets Events_fg_4+1, advances. |
| 8 (loc_59194) | Waits for Events_routine_fg >= $18. Continues adding velocity to Camera_Y_pos. When Camera Y crosses $4000: forces Y = $5C0. Self-deletes when Y > $4000 or special conditions met. |

### Act 1 Background Init (SSZ1_BackgroundInit)

**Disassembly location:** sonic3k.asm line 116380

1. Clears `Events_bg+$10` (sky mode flag).
2. Spawns main cloud oscillator object (`loc_57B6A`) which controls `_unkEE9C` via Gradual_SwingOffset (amplitude $8000, step $100).
3. Spawns 10 solid cloud platform objects from `word_5853E` data table. Each gets render_flags, height ($80), x_pos, y_vel, width ($2E), and slope data pointer ($30). Uses `SolidObjectTopSloped2` for collision. Cloud Y positions are offset by `_unkEE9C` (oscillation).
4. **Sky mode selection based on Camera_Y_pos:**
   - If Camera Y (wrapped) < $800 or >= $F00: "sky visible" mode -- calls sub_579F0 (sky-relative BG), PlainDeformation.
   - If Camera Y (wrapped) in $800-$F00: "clouds only" mode -- sets Events_routine_bg = 8, calls sub_57A60 (cloud parallax), uses SSZ1_BGDeformArray and ApplyDeformation.

### Act 1 Background Event (SSZ1_BackgroundEvent)

**Disassembly location:** sonic3k.asm line 116427

**State machine (Events_routine_bg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Sky visible | Monitors Camera_Y_pos (wrapped). When enters $800-$F00 range: saves BG position to Events_bg+$0C/0E, calls sub_57A60, draws delayed rows (15 rows from bottom), advances to $04. Otherwise: calls sub_579F0 + DrawBGAsYouMove + PlainDeformation + ShakeScreen_Setup. If `_unkEE98` set (Death Egg mode): writes VRAM updates from _unkEEFA, writes _unkEEEE to V_scroll_value, bypasses normal VScroll. Otherwise: uses word_577B2 (FG VScroll array) via Apply_FGVScroll. | Handles dual-mode sky/cloud BG. Death Egg mode uses independent V-scroll. |
| 4 | $04 | Transitioning to clouds | Calls sub_57A60 (cloud parallax). Draws plane vertically bottom-up at X=$1C00. When complete: clears Events_bg+$0C, advances to $08. | Gradual BG transition. |
| 8 | $08 | Clouds only | Monitors Camera_Y_pos. When exits $800-$F00 range: calls sub_579F0, draws delayed rows, advances to $0C. Otherwise: calls sub_57A60 + Draw_TileRow + PlainDeformation/ApplyDeformation (SSZ1_BGDeformArray). | Cloud parallax mode with deformation. |
| C | $0C | Transitioning to sky | Calls sub_579F0. Draws plane vertically (single bottom-up). When complete: resets Events_routine_bg=0, falls through to sky mode. | Gradual BG transition back. |

### Act 1 Sky BG Position (sub_579F0)

**Disassembly location:** sonic3k.asm line 116563

Two modes controlled by `Events_bg+$10`:

**Mode 0 (sky far, Camera_X_pos < $1800):**
- Camera_Y_pos_BG = Camera_Y_pos + $160 + _unkEE9C
- Camera_X_pos_BG = Camera_X_pos + $28

**Mode 1 (near Death Egg, Camera_X_pos >= $1800):**
- Camera_Y_pos_BG = Camera_Y_pos + $180
- Camera_X_pos_BG = Camera_X_pos (1:1 mapping)

Transition between modes triggers when Camera_X_pos crosses $1800, with immediate BG position recalculation and Camera_X_pos_BG_rounded snap.

### Act 1 Cloud Parallax (sub_57A60)

**Disassembly location:** sonic3k.asm line 116610

Calculates multi-layer cloud parallax for the $800-$F00 Y range:

1. **BG Y position:** Camera_Y_pos + (_unkEE9C / 2), wrapped to Screen_Y_wrap_value, subtract $800, divide by 2, add $A0.
2. **Auto-scroll accumulator:** HScroll_table[0] incremented by $500 per frame (constant cloud drift).
3. **Parallax layers:** Base X from Camera_X_pos (shifted right 5, fractional), multiplied by progressive depth factors:
   - Layer base: d0 = (Camera_X_pos << 8) >> 5 / 2 + accumulator
   - 5 depth levels generated by repeatedly adding d1 (= base + accumulator):
     - Shallowest (d0 * 1): HScroll entries at offsets +0, +6, +$A, +$14
     - d0 + d1: offsets +6/+$A, +8/+$E
     - d0 + 2*d1: offsets +4/+$C/+$12/+$16, +$3A
     - d0 + 3*d1: offsets +2/+$10/+$18, +$38
     - d0 + 4*d1: offsets +$1A/+$36
     - d0 + 5*d1: offsets +$1C/+$34
     - d0 + 6*d1: offsets +$1E/+$32
     - d0 + 7*d1: offsets +$20/+$30
     - d0 + 8*d1: offsets +$22/+$2E
     - d0 + 10*d1: offsets +$24/+$2C
     - d0 + 12.5*d1: offsets +$26/+$2A
     - d0 + 14.5*d1: offset +$28

   The pattern is symmetric -- cloud strips share scroll values in a mirrored arrangement, creating a perspective effect with deeper clouds moving faster.

### Act 2 Background Init (SSZ2_BackgroundInit)

**Disassembly location:** sonic3k.asm line 117974

1. Clears `_unkEE9C` (4 bytes).
2. Calls sub_58D3E (BG position/parallax setup).
3. Calls Reset_TileOffsetPositionEff + Refresh_PlaneFull.
4. Calls sub_58FBC (deformation setup).

### Act 2 Background Event (SSZ2_BackgroundEvent)

**Disassembly location:** sonic3k.asm line 117983

**State machine (Events_routine_bg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Normal | sub_58D3E (BG parallax calc) + Draw_TileRow + sub_58FBC (deformation) + ShakeScreen_Setup. | Standard scrolling with deformation. |
| 4 | $04 | Post-ending transition | Same as stage 0 but enters `loc_58D44` path in sub_58D3E: auto-scrolling BG with $8000/frame accumulator, 8-layer reverse parallax at HScroll_table+$18 (each layer shifted right by d1). Uses SSZ2_BGDeformArray. | Ending sequence BG auto-scroll. |

### Act 2 BG Parallax (sub_58D3E)

**Disassembly location:** sonic3k.asm line 118006

**Three modes based on Events_routine_fg:**

**Mode 1 (Events_routine_fg < $0C, normal play):**
- Auto-scroll: HScroll_table[0] += $1000 per frame (cloud drift).
- Multi-layer cloud parallax similar to Act 1 sub_57A60, with base from Camera_X_pos >> 5.
- Symmetric layer assignment across ~30 HScroll entries.
- For Events_routine_fg <= 4: fills HScroll entries $2A-$38 with Camera_X_pos (no parallax for lower screen).
- For Events_routine_fg > 4: shift propagation -- entries cascade downward (scroll history creates ripple effect).

**Mode 2 (Events_routine_fg >= $0C and < $18, transition):**
- Returns immediately (PlainDeformation used instead).

**Mode 3 (Events_routine_fg >= $18, ending fly-away):**
- Auto-scroll: HScroll_table[0] -= $10000 per frame (rapid reverse scroll).
- 32 entries filled with progressive parallax, 4 entries per depth step.
- 6 negated entries overlaid for asymmetric cloud pattern.

**VScroll column deformation (within sub_58FBC, line 118278):**
- Two wave deformation regions at HScroll_table+$D0 (64 entries, amplitude from Events_bg+$00) and HScroll_table+$160 (8 entries, amplitude from Events_bg+$00 >> 3).
- Direction-aware: sign of Events_bg+$00 determines which side of center the wave expands from.
- VScroll columns at HScroll_table+$170: 20 entries read from HScroll_table+$140 with BG Y offset.

## Deformation Arrays

### SSZ1_BGDeformArray (line 117575)
Used for cloud-mode BG (Y range $800-$F00):
```
$1D0, $10, $8, $18, $10, $10, $8, $28, $10, $8, $8, $28, $8, $20, $8, $8,
$8, $10, $18, $20, $40, $20, $18, $10, $8, $8, $8, $20, $8, $7FFF
```
29 bands totaling $1D0+$10+$8+... = 976 pixels. Symmetric mountain-shaped profile (narrow bands at top/bottom, wide $40 band in center).

### word_577B2 (FG VScroll deform, line 117358)
Used for FG vertical scroll during sky mode:
```
$19C0, $20, $20, $20, $20, $20, $20, $20, $20, $7FFF
```
Large initial $19C0 band (the main level area), then 8 narrow $20 bands.

### word_58C80 (SSZ2 FG deform, line 117911)
Used for Act 2 FG ApplyFGDeformation:
```
$380, $10, $10, $18, $10, $8, $10, $10, $8, $38, $10, $10, $28, $8, $20,
$8, $8, $8, $8, $8, $18, $20, $30, $8, $8, $10, $8, $28, $10, $10, $18,
$10, $8, $10, $10, $8, $7FFF
```
36 bands starting with large $380 band. Used during normal play.

### SSZ2_DeformArray (line 117949)
Used for Act 2 FG during ending fly-away:
```
$800, $24, $4, $14, $4, $1C, $4, $20, $8080, $7FFF
```
9 bands. The $8080 entry is a special marker (bit 15 set = line-scroll mode for remaining screen).

### SSZ2_BGDeformArray (line 117960)
Used for Act 2 BG (post-ending):
```
$120, $8, $8, $4, $4, $8, $8, $18, $10, $10, $7FFF
```
10 bands totaling $120+$8+... = $1A4 pixels.

## Animated Tiles (AniPLC_SSZ)

**Disassembly location:** sonic3k.asm line 56035

**Dispatch:** Act 1 uses `AnimateTiles_DoAniPLC` (generic AniPLC processor). Act 2 uses `AnimateTiles_NULL` (no custom animate function, AniPLC only).

| Script | Speed | Art Source | VRAM Dest | Frames | Tile Size | Description |
|--------|-------|------------|-----------|--------|-----------|-------------|
| 0 | 7 | ArtUnc_AniSSZ__0 | $1F3 | 4 | $24 tiles | Large animated element (cloud/waterfall), 36 tiles per frame |
| 1 | 7 | ArtUnc_AniSSZ__1 | $217 | 4 | $08 tiles | Medium animated element, 8 tiles per frame |
| 2 | 7 | ArtUnc_AniSSZ__2 | $21F | 3 | $08 tiles | Medium animated element, 8 tiles per frame (4th frame entry unused) |
| 3 | 2 | ArtUnc_AniSSZ__3 | $1D9 | 4 | $09 tiles | Fast animated element, 9 tiles per frame |
| 4 | 2 | ArtUnc_AniSSZ__4 | $1E2 | 4 | $04 tiles | Fast small animated element, 4 tiles per frame |
| 5 | 2 | ArtUnc_AniSSZ__5 | $1E6 | 4 | $0D tiles | Fast medium animated element, 13 tiles per frame |

**Frame offsets (all sequential):**
- Scripts 0-2: offsets 0, $24/$8/$8, $48/$10/$10, $6C/$18/$18 (stride = tile size)
- Scripts 3-5: offsets 0, $9/$4/$D, $12/$8/$1A, $1B/$C/$27

**Art data files:** Located at `Levels/SSZ/Animated Tiles/0.bin` through `5.bin`.

## Palette Cycling

### Act 1: AnPal_None

No palette cycling for Act 1 (indices 20-21 in OffsAnPal = AnPal_None).

### Act 2 Conditional Palette Cycling

SSZ2 has two palette mutation subroutines called from the screen event, not from the standard AnPal system:

**sub_5928C (Water Cycle) -- line 118551:**
- **Gate:** `Palette_cycle_counters+$00` must be zero (cleared in SSZ2_ScreenEvent stage $08).
- **Counter:** Palette_cycle_counter1 counts down from 7 (every 8 frames).
- **Cycle:** Palette_cycle_counter0 advances by 6, wrapping at $30 (8 steps).
- **Target:** `Normal_palette_line_4+$1A` (4 bytes) and `Normal_palette_line_4+$1E` (2 bytes) -- palette line 4, colors 13-15.
- **Data:** Pal_EndingWater (line 118573), 6 bytes per step, 8 steps = 48 bytes.
- **Used in:** SSZ2_ScreenEvent stage $18 (bad ending terminal state).

**sub_592EE (Emerald Glow) -- line 118580:**
- **Gate:** `Palette_cycle_counters+$00` must be zero.
- **Counter:** Palette_cycle_counter1 counts down from 3 (every 4 frames).
- **Cycle:** Palette_cycle_counter0 advances by 4, wrapping at $38 (14 steps).
- **Target:** `Normal_palette_line_4+$16` (4 bytes) -- palette line 4, colors 11-12.
- **Data:** word_5931A (line 118601), 4 bytes per step, 14 steps = 56 bytes.
- **Used in:** SSZ2_ScreenEvent stage $10 (good ending path, with emeralds).

Both routines are also used by the Ending_ScreenEvent (line 121268): sub_592EE during normal play, sub_5928C when Events_routine_bg is nonzero.

## Boss Sequences

### Act 1: GHZ Boss Recreation (Obj_SSZGHZBoss)

- **Object:** `Obj_SSZGHZBoss` at line 162571
- **Spawn trigger:** sub_575EA detects Player Y >= $7C0 at Camera X == $160 (ground level, left side)
- **Arena:** Camera locked to X=$160, Y=$7C0
- **Art:** PLC $7B + ArtKosM_SSZGHZMisc (loaded at spawn)
- **Palette:** Pal_SSZGHZMisc loaded into line 2 + target line 2
- **Music:** Fades out, then plays mus_EndBoss
- **Notes:** Recreation of Sonic 1 Green Hill Zone boss (wrecking ball). Uses Sonic 2-derived Robotnik ship code (line 164015 comment: "This routine comes from Sonic 2").
- **Confidence:** HIGH

### Act 1: MTZ Boss Recreation (Obj_SSZMTZBoss)

- **Object:** `Obj_SSZMTZBoss` at line 163018
- **Spawn trigger:** sub_575EA detects Player Y >= $420 at Camera X == $1660 (upper level, right side)
- **Arena:** Camera locked to X=$1660, Y=$380
- **Art:** PLC $7B + ArtKosM_SSZMTZOrbs (loaded at spawn)
- **Palette:** Pal_SSZMTZOrbs loaded via PalLoad_Line1 + target line 2 backup
- **Music:** Fades out, then plays mus_EndBoss
- **Boss variables:** Uses dedicated RAM: SSZ_MTZ_boss_X_pos, SSZ_MTZ_boss_Y_pos, SSZ_MTZ_boss_X_vel, SSZ_MTZ_boss_Y_vel, SSZ_MTZ_boss_laser_timer
- **Notes:** Recreation of Sonic 2 Metropolis Zone boss (floating orbs/laser).
- **Confidence:** HIGH

### Act 1: Mecha Sonic (Obj_SSZEndBoss)

- **Object:** `Obj_SSZEndBoss` at line 164157
- **Spawn trigger:** HPZ teleporter exit (Obj_SSZHPZTeleporter, line 91421)
- **Arena:** Camera_min_X_pos=$19A0, Camera_min/target_max_Y_pos=$5C0 (sub_575EA final arena)
- **Art:** ObjSlot_MechaSonic + ArtKosM_MechaSonicExtra (DPLC-driven)
- **Health:** 8 hits (collision_property = 8)
- **State machine:** 21 states (SSZEndBoss_Index, line 164169). Multi-phase fight including dash attacks, jumping patterns, and defeat sequence.
- **Defeat:** Transitions to Obj_SSZ2_Boss (Super Mecha Sonic phase, line 164947) which has 36 states.
- **Act 1 only behavior:** When Current_act == 0: routine starts at state 4 (line 164205, sets starting position differently).
- **Notes:** Uses Map_MechaSonic and DPLCPtr_MechaSonic for sprite rendering. The SSZ2_Boss phase is Super Mecha Sonic using the Master Emerald.
- **Confidence:** HIGH

### Act 2: Mecha Sonic / Super Mecha Sonic (Obj_SSZ2_Boss)

- **Object:** `Obj_SSZ2_Boss` at line 165047
- **Context:** Knuckles' boss fight. Direct continuation from Obj_SSZEndBoss defeat.
- **Health:** 8 hits
- **State machine:** 36 states (SSZ2_Boss_Index, line 165059). Extended multi-phase fight.
- **Art:** Same MechaSonic DPLC system.
- **Defeat:** Triggers ending sequence (music fade, palette transition, sub_5B18E ending manager).
- **Confidence:** HIGH

## Zone Objects

### Object ID Mapping (from SonLVL INI)

**Act 1 (Sonic/Tails) objects:**

| ID | Object | Notes |
|----|--------|-------|
| $74 | Retracting Spring (Obj_SSZRetractingSpring) | Spring that retracts into wall |
| $75 | Swinging Carrier (Obj_SSZSwingingCarrier) | Pendulum platform |
| $76 | Rotating Platform (Obj_SSZRotatingPlatform) | Orbiting platform |
| $77 | Cutscene Bridge (Obj_SSZCutsceneBridge) | Used in cutscene sequences |
| $7A | Elevator Bar (Obj_SSZElevatorBar) | Vertical moving platform |
| $7B | Collapsing Bridge Diagonal (Obj_SSZCollapsingBridgeDiagonal) | Diagonal collapsing bridge |
| $7C | Collapsing Bridge (Obj_SSZCollapsingBridge) | Standard collapsing bridge |
| $7D | Bouncy Cloud (Obj_SSZBouncyCloud) | Cloud that bounces player upward |
| $7E | Collapsing Column (Obj_SSZCollapsingColumn) | Vertical collapsing structure |
| $7F | Floating Platform (Obj_SSZFloatingPlatform) | Static/moving floating platform |
| $A0 | EggRobo (Obj_EggRobo) | Enemy badnik unique to SSZ |
| $AF | Cutscene Button (Obj_SSZCutsceneButton) | Interactive button for cutscenes |

**Act 2 (Knuckles) objects:**

| ID | Object | Notes |
|----|--------|-------|
| $00 | Ring | Standard ring |
| $B2 | Egg Mobile (Obj_SSZ2_Boss related) | Eggman's ship in Knuckles ending |

**Shared objects (spawned by events):**
- Teleporter beam (Obj_57C1E / Obj_TeleporterBeamExpand)
- Roaming clouds (loc_57BB2 / Map_SSZRoamingClouds)
- Cloud platforms (loc_57B8E / SolidObjectTopSloped2)
- Crumbling debris (loc_58234, loc_58360, loc_582AC, loc_581F2)
- Spiral ramp pieces (Map_SSZSpiralRampPieces, ArtKosM_SSZSpiralRamp)
- Death Egg launch objects
- Mecha Sonic head (Obj_MechaSonicHead)

## Cross-Cutting Concerns

### Character Segregation

SSZ is unique in S3K: acts are completely character-segregated, not just branched.
- **Act 1 ($A00):** Level select denies Knuckles entry (line 10179). Different level art set (Primary/Secondary/Custom).
- **Act 2 ($A01):** Level select denies Sonic/Tails entry (line 10198). Uses entirely different art set (SSZ2 resources).

### Vertical Wrapping (Act 1)

SSZ1 uses vertical wrapping (Camera_min_Y_pos = -$100, Y range -$100 to $1000). The zone is a tall tower structure where the player ascends, and the Y-wrapping allows the background to tile seamlessly. Objects and the camera respect Screen_Y_wrap_value ($FFFF) for position masking.

### Screen Shake

Both acts use screen shake:
- **Act 1:** `Screen_shake_flag` set to -1 (constant shake) during the crumbling tower sequence (line 115954). `Screen_shake_offset` added to `Camera_Y_pos_copy` in the screen event pre-dispatch (line 115900-115901).
- **Act 2:** `ShakeScreen_Setup` called in BG event (line 118001). Column deformation from Events_bg+$00 creates oscillating visual effect.

### Teleporter Beam Intro

Both acts start with a teleporter beam sequence (Obj_57C1E):
- **Act 1:** Player appears at Y=$F49 (near bottom of wrapped zone), teleporter at X=$100. Player is locked (object_control = 3), rolls upward with y_vel = -1. On Act 1, also spawns Obj_57E34 (Tails follower) if Player_mode == 0 (Sonic & Tails).
- **Act 2:** Player appears at Y=$649, teleporter at X=$A0. Subtype $44 indicates Act 2 behavior. Art_tile bit 7 set (high priority) for Act 2.

### Death Egg Launch Sequence (Act 1)

Triggered by End_of_level_flag (after Mecha Sonic defeated):
1. **Tower crumble:** 10 crumbling platforms with gravity physics (sub_5750C).
2. **Art hot-swap:** Custom blocks, chunks, and patterns queued via Kosinski/KosinskiM (replacing terrain with Death Egg structure).
3. **Palette swap:** Pal_SSZDeathEgg loaded into palette line 2.
4. **Independent BG:** Death Egg background tracks at 1/4 vertical rate of camera, with gradual vertical offset when Camera_Y_pos reaches $110 (sub_574DC, line 116076-116087).
5. **Spiral ramp pieces:** Debris objects spawned at staggered positions around the crumbling area.

### Ending Branching (Act 2)

After defeating Super Mecha Sonic:
- **>= 7 Chaos Emeralds OR >= 7 Super Emeralds:** Good ending path. Camera rises to $14C0 (S&K alone) or $1660 (S3K). Loads Pal_Ending2. Leads to Doomsday Zone or good ending credits.
- **< 7 Chaos Emeralds:** Bad ending path. Camera descends. Loads Pal_Ending1. Leads to bad ending credits with water palette cycling.

### Music

- **Level music:** SSZ theme (Snd_SSZ, line 201185)
- **Boss music:** cmd_FadeOut -> mus_EndBoss (for all three Act 1 bosses and Act 2 boss)
- **Ending music:** mus_CreditsK (Knuckles credits, triggered from sub_5B18E)
- **Sound effects:** sfx_BigRumble played every 16 frames during crumbling sequence (line 117463)

### Unique Art Tiles

| Constant | Value | Usage |
|----------|-------|-------|
| ArtTile_SSZMisc | $02D4 | Main SSZ object graphics (clouds, bridges, platforms) |
| ArtTile_SSZSpiralRamp | $0348 | Spiral ramp debris pieces |
| ArtTile_SSZMasterEmerald | $01EE | Master Emerald (Act 2 boss arena) |
| ArtTile_SSZMTZOrbs | $041F | MTZ boss recreation orbs |
| ArtTile_Ending_SSZKnuckles | $0310 | Knuckles ending cutscene sprites |
| ArtTile_SSZMisc+$3C | -- | Roaming cloud sprites (palette 3) |
| ArtTile_SSZMisc+$88 | -- | Teleporter beam sprites (palette 3) |
| ArtTile_SSZMisc+$20 | -- | Collapsing column/debris (palette 2) |

## Implementation Priority

1. **Parallax/Scrolling (HIGH):** SSZ has the most complex parallax system in the game. Act 1 has dual-mode BG (sky vs cloud), multi-layer parallax with symmetric depth scaling, oscillating cloud platforms, and independent Death Egg BG. Act 2 has multi-layer cloud parallax with column deformation. Both acts share the characteristic SSZ cloud aesthetic.

2. **Events (HIGH):** Act 1 has 3 boss arenas with dynamic boundary management, Death Egg launch sequence with art hot-swap, crumbling platform physics, and teleporter intro. Act 2 has arena construction, ending sequence branching, and camera controller object.

3. **Animated Tiles (MEDIUM):** 6 standard AniPLC scripts, no custom AnimateTiles handler (uses generic DoAniPLC for Act 1, NULL for Act 2).

4. **Palette Cycling (LOW):** No standard palette cycling. Two conditional palette mutation routines used only during the ending sequence (Act 2 only, and shared with the Ending zone).

5. **Objects (MEDIUM):** 12 unique object types for Act 1, minimal objects for Act 2. Many SSZ objects are zone-specific (bouncy clouds, swinging carriers, collapsing structures).

6. **Bosses (HIGH):** Four boss objects total (GHZ recreation, MTZ recreation, Mecha Sonic, Super Mecha Sonic). All use DPLC-driven sprites and have extensive state machines.
