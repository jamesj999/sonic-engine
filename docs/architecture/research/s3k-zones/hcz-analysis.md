# S3K HCZ Zone Analysis

## Summary

- **Zone:** Hydrocity Zone (HCZ)
- **Zone Index:** 0x01
- **Zone Set:** S3KL
- **Acts:** 1 and 2
- **Water:** Yes (both acts, with dynamic water height tables and waterline deformation)
- **Palette Cycling:** Yes (1 channel, Act 1 only; Act 2 is rts)
- **Animated Tiles:** Yes (2 AniPLC scripts per act + complex custom AnimateTiles routines for waterline/BG deformation)
- **Character Branching:** Yes -- Knuckles has different end boss arena bounds and different dynamic water heights in Act 2

## Events

### Act 1 (HCZ1_Resize)

**Disassembly location:** `sonic3k.asm` line 39244

**State machine (eventRoutineFg):**

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 | Camera X < $360 AND Camera Y >= $3E0 | Write palette mutation to Normal_palette_line_4+$10: $680, $240, $220 (3 colors); advance to stage 2 | Underwater palette correction. Note: ROM has a bug writing $B80 instead of $680, `FixBugs` corrects it |
| 2 | Camera X >= $360 AND Camera Y < $3E0 | Revert to stage 0, write palette restore: $CEE, $ACE, $08A | Player moved back above water threshold |
| 2 | Camera Y >= $500 AND Camera X >= $900 | Write palette restore: $CEE, $ACE, $08A; advance to stage 4 | Player progressed past the initial underwater area |
| 4 | (none) | rts -- idle | Terminal state |

**State machine (eventRoutineBg):** Used for the Act 1 to Act 2 seamless transition.

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 (HCZ1BGE_Normal) | Events_fg_5 set (by HCZ2_Resize stage 0) | Queue Kos decompression of HCZ2 secondary blocks/chunks/art; Load PLC $10 and $11 (HCZ2 PLCs); set Events_bg+$16; advance to stage 4 | Seamless act transition prep |
| 0 (normal) | (each frame) | Run HCZ1_Deform, Draw_TileRow, ApplyDeformation | Normal BG scrolling |
| 4 (HCZ1BGE_DoTransition) | Kos_modules_left == 0 | Change zone to $101 (HCZ2), clear Dynamic_resize_routine/Object_load_routine/Rings_manager_routine/Boss_flag, Load_Level (HCZ2 layout), LoadSolids, CheckLevelForWater, set water to $6A0, LoadPalette_Immediate #$D (HCZ2 palette), offset all objects/camera by -$3600 X | Full seamless transition to Act 2 |
| 4 (waiting) | Kos_modules_left != 0 | Run HCZ1_Deform, draw BG | Wait for Kos queue to clear |

**Palette mutations:**
- Stage 0: Writes `$0680, $0240, $0220` to palette line 4, colors 8-10 (Normal_palette_line_4+$10) when underwater area entered
- Stage 2 (revert): Writes `$0CEE, $0ACE, $008A` to same location when player moves back above water or progresses past

**Knuckles differences:** None in Act 1 events -- same state machine for all characters.

### Act 2 (HCZ2_Resize)

**Disassembly location:** `sonic3k.asm` line 39306

**State machine (eventRoutineFg):**

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 | Camera X >= $C00 | Set Events_fg_5 (triggers BG transition from wall-move to normal); advance to stage 2 | Signals end of the wall-chase section |
| 2 | (none) | rts -- idle | Terminal state |

**State machine (eventRoutineBg):** Complex, manages the collapsing-wall chase sequence and BG transitions.

| Stage | Trigger | Action | Notes |
|-------|---------|--------|-------|
| 0 (HCZ2BGE_WallMoveInit) | Level start (BackgroundInit branches here when Camera X < $C00 and Camera Y >= $500) | Allocate Obj_HCZ2Wall, advance to stage 4 | Creates the animated wall object |
| 4 (HCZ2BGE_WallMove) | Events_fg_5 set | Clear Screen_shake_flag if constant; set Draw_delayed values; advance to stage 8 | Wall chase ended, begin normal BG transition |
| 4 (normal) | Each frame | Run HCZ2_WallMove (wall chase logic); DrawBGAsYouMove; PlainDeformation; ShakeScreen_Setup; enable Background_collision_flag when player is within X=$3F0-$C10 and Y=$600-$840 | Wall is chasing the player |
| 8 (HCZ2BGE_NormalTransition) | Draw_PlaneVertSingleBottomUp completes | Run HCZ2_Deform; Reset_TileOffsetPositionEff; set up bottom-up refresh; advance to stage 12 | Transition to normal parallax |
| 12 (HCZ2BGE_NormalRefresh) | Draw_PlaneVertBottomUp completes | Advance to stage 16 | BG fully refreshed |
| 16 (HCZ2BGE_Normal) | Each frame | Run HCZ2_Deform; Draw_TileRow; ApplyDeformation; ShakeScreen_Setup | Normal scrolling with screen shake support |

**Palette mutations:** None in Act 2 event routines.

**Knuckles differences:** None in Act 2 event routines directly, but the end boss uses different arena bounds (see Boss Sequences).

## Boss Sequences

### Act 1 Miniboss

- **Object:** `Obj_HCZMiniboss` at line 139224 (`0x99`)
- **Spawn trigger:** `Check_CameraInRange` with bounds Y=$300-$400, X=$3500-$3700
- **PLC load:** PLC ID $5B loaded at spawn time
- **Arena boundaries:** Initially Camera_min_Y_pos=$300; then when Camera Y >= $638, locks Y min/max to $638; when Camera X >= $3680, locks X min/max to $3680
- **Music:** Fades current music, then plays `mus_Miniboss`; loads `Pal_HCZMiniboss` / `Pal_HCZMinibossWater` into palette line 1
- **State flow:** init -> trigger wait -> fade wait -> descend -> wait -> rise -> dive -> strafe -> ascend -> pre-vortex drift -> vortex -> cooldown -> slow rise -> defeated
- **Attack logic:** `ATTACK_PATTERNS` alternates `{0x40,1}`, `{0x100,1}`, `{0x40,0}`, `{0x40,1}`, `{0x100,0}`, `{0x100,1}`, `{0x40,0}`, `{0x100,0}`. The rocket orbit uses the same folded 128-entry lookup for both X and Y, then slows through 4 -> 2 -> 1 -> 0 on wind-down and 1 -> 2 -> 4 on wind-up.
- **Defeat behavior:** Creates the ship, rocket, and engine helper tree, then enters a vortex phase that captures the player and sidekicks, spawns 0x1E bubble children, and ends in a signpost-style clear handoff.
- **Confidence:** HIGH -- the state machine, trigger window, and helper roles are concrete. The only narrow gap is frame-level verification of a few child spawn timings.

### Act 2 End Boss

- **Object:** `Obj_HCZEndBoss` at line 140778 (`0x9A`)
- **Spawn trigger:** `Check_CameraInRange` with character-dependent bounds:
  - **Sonic/Tails:** Y=$438-$838, X=$3F00-$4100 (tight Y, tighter X at $4000-$4050)
  - **Knuckles:** Y=$000-$3B8, X=$44E0-$4640 (wider arena at $4540-$4590)
- **PLC load:** PLC ID $6C loaded at spawn time
- **Arena boundaries:** Set by `Check_CameraInRange` data (two 4-word entries per character: first = camera trigger region, second = locked arena bounds)
- **Music:** `mus_EndBoss`; loads `Pal_HCZEndBoss` palette to line 1; clears Normal_palette+$1E
- **Routine table:** `off_6AF24` uses routine bytes 0, 2, 4, 6, 8, A, C. The shared `loc_6AF74` path is the generic wait/move step; `loc_6AFB6` is the swing/hover step; `loc_6B064` is the late-stage oscillation and turn-around path.
- **State flow:** setup -> generic wait/move -> swing/hover -> character-dependent retreat/reopen -> post-vortex oscillation -> final escape
- **Child tree:** Spawns the Robotnik ship child and two arm children up front, then branches into propeller/flame/explosion helpers as the attack and escape sequence advances. Knuckles uses the alternate branch at `loc_6AFF2` / `loc_6B138`, including the alternate Egg Capsule spawn at $4760,$360 instead of $4250,$7E0.
- **Confidence:** MEDIUM -- the top-level state table and character split are exact, but the frame-level choreography of the helper children is still distributed across several secondary routines.

## Parallax

### Act 1 (HCZ1_Deform)

**Disassembly location:** `sonic3k.asm` line 105796

This routine is the waterline parallax generator, not a simple banded scroll. It splits the HScroll table into three regions and uses `Events_bg+$10` as the signed waterline displacement signal.

**Core values:**
- `Camera_Y_pos_copy - $610` is the equilibrium delta.
- `Camera_Y_pos_BG_copy = ((cameraY - $610) / 4) + $190`.
- `Events_bg+$10 = ((cameraY - $610) / 4) - (cameraY - $610)`.

**HScroll layout:**
- `HScroll_table+$00-$18` and `$19A` are mirrored 7-band cave parallax values.
- The top/bottom cave bands use `cameraX / 4` as the base and subtract `cameraX / 32` per speed step.
- `HScroll_table+$01A-$19A` is the 96-word middle gradient section. If `d2 <= -$60`, it is filled forward from `$01A`; otherwise it is filled backward from `$19A`.
- When `-$60 < d2 < $60`, the middle band is remapped through `HCZ_WaterlineScroll_Data` to create the visible water surface ripple.
- The binary waterline table is 9312 bytes, i.e. 97 positions x 96-byte lookup rows.

**BG height array:** `HCZ1_BGDeformArray` (defined in `Lockon S3/Screen Events.asm` line 972):
```
$40, 8, 8, 5, 5, 6, $F0, 6, 5, 5, 8, 8, $30, $80C0, $7FFF
```
(Used by ApplyDeformation for the standard parallax bands outside the custom waterline logic)

**Vertical scroll:** BG Y = `(Camera_Y_copy - $610) / 4 + $190`

**Auto-scroll accumulators:** None (no persistent per-frame accumulation)

**Confidence:** MEDIUM -- the control flow, table split, and band math are exact. The only unresolved piece is the exact byte content of `HCZ_WaterlineScroll_Data`, not the routine structure.

### Act 1 Background Events (HCZ1_BackgroundEvent)

**Disassembly location:** `sonic3k.asm` line 105702

Two states:
- **Normal (stage 0):** Runs HCZ1_Deform, draws BG tile rows, applies deformation array. When `Events_fg_5` is set, queues HCZ2 art/blocks/chunks via Kos, loads PLCs $10/$11, and transitions to stage 4.
- **Transition (stage 4):** Waits for Kos queue to clear, then performs seamless Act 1->2 transition (loads HCZ2 layout, sets water to $6A0, offsets camera/objects by -$3600 X).

### Act 2 (HCZ2_Deform)

**Disassembly location:** `sonic3k.asm` line 106179

**Core mechanic:** Simpler than Act 1. BG Y = `(Camera_Y_copy - Screen_shake_offset) / 4 + Screen_shake_offset`. BG X = `Camera_X_copy / 2`, then further divided by 8 for slower bands.

**Band layout:** Uses scatter-fill via `HCZ2_BGDeformIndex` (defined in `Lockon S3/Screen Events.asm` line 977):
```
Band 0 (4 entries): HScroll offsets $A, $14, $1E, $2C  -- fastest
Band 1 (3 entries): $C, $16, $20
Band 2 (6 entries): $0, $8, $E, $18, $22, $2A          -- medium
Band 3 (4 entries): $2, $10, $1A, $24
Band 4 (2 entries): $12, $1C
Band 5 (2 entries): $6, $28
Band 6 (2 entries): $4, $26                              -- slowest
```

Each band is written at successively slower speeds (each subtracting `BG_X / 8` per step). The index table maps non-contiguous HScroll_table entries to create an interleaved parallax effect.

After writing bands, computes three difference values stored in Events_bg+$10/12/14 which drive the AnimateTiles_HCZ2 animated tile channels.

**BG height array:** `HCZ2_BGDeformArray`:
```
8, 8, $90, $10, 8, $30, $18, 8, 8, $A8, $30, $18, 8, 8, $B0, $10, 8, $7FFF
```

**Vertical scroll:** BG Y = `(Camera_Y_copy - shake_offset) / 4 + shake_offset`

**Confidence:** MEDIUM -- scatter-fill index pattern is well understood, but the interaction with AnimateTiles_HCZ2 (which reads Events_bg+$10/12/14) adds complexity.

### Act 2 Wall Chase (HCZ2_WallMove)

**Disassembly location:** `sonic3k.asm` line 106129

During the collapsing wall section (BackgroundEvent stages 0-4):
- BG Y = Camera_Y_copy - $500
- BG X = Camera_X_copy - $200 + wall_movement_offset
- Wall starts moving when player X >= $680 (constant screen shake begins)
- Wall speed: $E000 subpixels/frame normally, $14000 if player X >= $A88
- When wall has moved $600 pixels: switches from constant to timed shake ($E frames), plays `sfx_Crash`
- Plays `sfx_Rumble2` every 16th frame during movement
- **Background collision** enabled only when player is within X=$3F0-$C10, Y=$600-$840

**Confidence:** HIGH -- well-commented in disassembly, straightforward state machine.

### Act 2 Screen Shake

**Disassembly location:** `sonic3k.asm` line 105989 (HCZ2_ScreenEvent)

HCZ2_ScreenEvent adds `Screen_shake_offset` to `Camera_Y_pos_copy` before calling `DrawTilesAsYouMove`, and `ShakeScreen_Setup` (line 104183) is called during HCZ2 BackgroundEvent. ShakeScreen supports two modes:
- **Timed** (positive flag): Decrements counter, reads offset from ScreenShakeArray
- **Constant** (negative flag): Uses `Level_frame_counter & $3F` as index into ScreenShakeArray2

## Animated Tiles

### Script: Act 1 Water Surface (AniPLC_HCZ1, channel 0)

- **Disassembly location:** `sonic3k.asm` line 55646
- **Declaration:** `zoneanimdecl -1, ArtUnc_AniHCZ1_0, $30C, 3, $24`
- **Destination VRAM tile:** $30C
- **Frame count:** 3
- **Frame duration:** Variable (0=2 frames, $24=1 frame, $48=2 frames)
- **Tiles per frame:** $24 / $20 = ~1.125 tiles (36 bytes per frame = 1.125 patterns)
- **Confidence:** HIGH

### Script: Act 1/2 Shared Water Animation (AniPLC_HCZ, channel 1)

- **Disassembly location:** `sonic3k.asm` line 55652 (Act 1), 55679 (Act 2)
- **Declaration:** `zoneanimdecl -1, ArtUnc_AniHCZ__1, $115, $10, 6`
- **Destination VRAM tile:** $115
- **Frame count:** 16 ($10)
- **Frame duration:** Variable ping-pong pattern (4,3,2,1,0,1,2,3,4,3,2,1,0,1,2,3)
- **Tiles per frame:** 6 bytes per frame (< 1 pattern; likely 6 bytes = partial tile update)
- **Notes:** Shared between Act 1 and Act 2 (same art label `ArtUnc_AniHCZ__1`)
- **Confidence:** HIGH

### Script: Act 2 Water Surface (AniPLC_HCZ2, channel 0)

- **Disassembly location:** `sonic3k.asm` line 55672
- **Declaration:** `zoneanimdecl 3, ArtUnc_AniHCZ2_0, $25E, 4, $15`
- **Destination VRAM tile:** $25E
- **Frame count:** 4
- **Frame duration:** 3 frames each (constant)
- **Tiles per frame:** $15 / $20 = ~0.66 patterns (21 bytes per frame)
- **Confidence:** HIGH

### Custom: Act 1 Waterline Tile Swapping (AnimateTiles_HCZ1)

- **Disassembly location:** `sonic3k.asm` line 53969
- **Description:** Uses `Events_bg+$10` as a cached signed waterline offset. When the value changes, it loads either `ArtUnc_AniHCZ1_WaterlineBelow` or `ArtUnc_AniHCZ1_WaterlineAbove` into `Chunk_table+$7C00`, one row at a time, using the same 96-byte-per-position `HCZ_WaterlineScroll_Data` window that `HCZ1_Deform` consults. The matching fix pass then restores whichever cave background strip was overwritten by the opposite waterline state.
- **Gating:** Disabled when `Events_bg+$16` is set (boss flag / act transition). `Anim_Counters+4` caches the last waterline state so DMA only fires on transitions.
- **VRAM destinations:** Tiles $2DC/$2E8 for the below-waterline state, $2F4/$300 for the above-waterline state. The fix routines queue two DMA uploads each.
- **State detail:** `d1 == 0` seeds the lower-bg repair path, `d1 < 0` selects the below-waterline art path, and `d1 > 0` selects the above-waterline art path.
- **Confidence:** MEDIUM -- the DMA flow and state transitions are exact. The remaining gap is visual verification of the swapped chunk art, not the branching logic.

### Custom: Act 2 Multi-Channel BG Animation (AnimateTiles_HCZ2)

- **Disassembly location:** `sonic3k.asm` line 54158
- **Description:** Four split-DMA channels, each keyed off a cached `Anim_Counters` byte and driven by the HCZ2 deform offsets:
  - **Channel 0:** `Events_bg+$12 & $1F`, source `ArtUnc_AniHCZ2_SmallBGLine`, VRAM $2D2. The phase is broken by `word_27AB8`.
  - **Channel 1:** `Events_bg+$12 & $1F`, source `ArtUnc_AniHCZ2_2`, VRAM $2D6. The phase is broken by `word_27B24`.
  - **Channel 2:** `Events_bg+$14 & $1F`, source `ArtUnc_AniHCZ2_3`, VRAM $2DE. The phase is broken by `word_27B90`.
  - **Channel 3:** `Events_bg+$14 & $3F`, source `ArtUnc_AniHCZ2_4`, VRAM $2EE. The phase is inverted with `neg.w` before indexing, so this strip scrolls in the opposite direction.
- **Notes:** Channels 0/1 share the same source phase but use different packing tables; channels 2/3 do the same at a wider cadence. Every channel performs at most two DMA transfers per update.
- **Confidence:** HIGH -- the channel masks, split tables, and VRAM destinations are all concrete.

## Palette Cycling

### Channel 0: Act 1 Water Shimmer (AnPal_HCZ1)

- **Disassembly location:** `sonic3k.asm` line 3287
- **Counter:** `Palette_cycle_counter0`
- **Step:** 8 bytes (4 colors per tick)
- **Limit:** $20 (4 frames in cycle)
- **Timer:** 7 game frames between ticks (`Palette_cycle_counter1`)
- **Table:** `AnPal_PalHCZ1` at line 4087
- **Data:** four 8-byte frames that rotate the same blue quartet through the sequence `EC8/EC0/EA0/E80` in all four phase offsets.
- **Destination:** Palette line 3, colors 3-6 (`Normal_palette_line_3+$06`); the ROM also mirrors the same four colors into `Water_palette_line_3+$06`.
- **Conditional:** No (always active in Act 1)
- **Confidence:** HIGH

### Act 2 (AnPal_HCZ2)

- **Disassembly location:** `sonic3k.asm` line 3315
- **Content:** `rts` -- Act 2 has NO palette cycling
- **Confidence:** HIGH

### Engine Implementation Status

HCZ palette cycling is implemented in `Sonic3kPaletteCycler` for `zoneIndex == 1`, act 0 only. The engine loads `HczCycle`, which mirrors the Act 1 water shimmer and the camera-triggered cave lighting mutation. Act 2 remains an explicit no-op because `AnPal_HCZ2` is `rts`. The remaining parity gap is the underwater mirror write to `Water_palette_line_3+$06/$0A`, which the cycler still marks as `TODO`.

## Notable Objects

| Object ID | Name | Description | Zone-Specific? | Notes |
|-----------|------|-------------|----------------|-------|
| 0x36 | Obj_HCZBreakableBar | Breakable barrier/bar | Yes | `Sonic3kObjectIds.HCZ_BREAKABLE_BAR` |
| direct | Obj_HCZWaveSplash | Water surface wave splash effect | No table ID | Spawned directly into the `Wave_Splash` RAM slot at load time |
| 0x40 | Obj_HCZBlock | Pushable/intractable block | Yes | `Sonic3kObjectIds.HCZ_BLOCK` |
| 0x67 | Obj_HCZSnakeBlocks | Snake block platform path | Yes | `Sonic3kObjectIds.HCZ_SNAKE_BLOCKS` |
| 0x37 | Obj_HCZWaterRush | Water rush/current object | Yes | `Sonic3kObjectIds.HCZ_WATER_RUSH` |
| 0x3B | Obj_HCZWaterWall | Water wall barrier | Yes | `Sonic3kObjectIds.HCZ_WATER_WALL` |
| 0x38 | Obj_HCZCGZFan | Fan (shared with CGZ) | Shared | `Sonic3kObjectIds.HCZ_CGZ_FAN` |
| 0x39 | Obj_HCZLargeFan | Large fan | Yes | `Sonic3kObjectIds.HCZ_LARGE_FAN` |
| 0x3A | Obj_HCZHandLauncher | Hand launcher mechanism | Yes | `Sonic3kObjectIds.HCZ_HAND_LAUNCHER` |
| 0x3E | Obj_HCZConveyorBelt | Conveyor belt | Yes | `Sonic3kObjectIds.HCZ_CONVEYOR_BELT` |
| 0x3F | Obj_HCZConveryorSpike | Conveyor belt with spikes | Yes | `Sonic3kObjectIds.HCZ_CONVEYOR_SPIKE` (disasm label typo preserved) |
| 0x68 | Obj_HCZSpinningColumn | Spinning column/pole | Yes | `Sonic3kObjectIds.HCZ_SPINNING_COLUMN` |
| 0x6D | Obj_HCZWaterSplash | Water skim splash effect | Yes | `Sonic3kObjectRegistry` maps 0x6D to `HCZWaterSplash` |
| 0x69 | Obj_HCZTwistingLoop | Twisting tube/loop transport | Yes | `Sonic3kObjectIds.HCZ_TWISTING_LOOP` |
| direct | Obj_HCZ2Wall | Collapsing wall (Act 2 chase) | No table ID | Spawned directly by the HCZ2 background event |
| 0x99 | Obj_HCZMiniboss | Act 1 miniboss (underwater) | Yes | `Sonic3kObjectIds.HCZ_MINIBOSS` |
| 0x9A | Obj_HCZEndBoss | Act 2 end boss | Yes | `Sonic3kObjectIds.HCZ_END_BOSS` |

**Note:** Normal HCZ objects are mapped directly in `Sonic3kObjectRegistry.getPrimaryName()` and `Sonic3kObjectProfile`. `Obj_HCZWaveSplash` and `Obj_HCZ2Wall` are event/direct-spawn objects, so they do not come from the zone object pointer table.

## Cross-Cutting Concerns

- **Water:** Present in both acts. HCZ is one of the primary water zones. Water level is dynamic:
  - Act 1: `DynamicWaterHeight_HCZ1` uses a 4-entry table -- water at $500 until X>$900, $680 until X>$2A00, $680 until X>$3500, then $6A0. High bit set on first entry ($8500) forces immediate Mean_water_level write.
  - Act 2: `DynamicWaterHeight_HCZ2` -- Sonic/Tails: water at $700 until X>$3E00, then $7E0. Knuckles: water at $700 until X>$4100, then $360 (high bit set = immediate). Gated by `_unkFAA2` flag.
  - HInt handler is set to `HInt2` specifically for HCZ (zone == 1 check at line 9789).
  - Water palette transition data: `WaterTransition_AIZ1` is loaded as default (line 9797).
  - Act 1->2 transition sets water to $6A0.
- **Screen shake:** Present in Act 2 during the wall chase section. Two modes: constant shake (wall moving) and timed shake (wall stopped). ShakeScreen_Setup called in HCZ2 BackgroundEvent.
- **Act transition:** HCZ1 ends with a **seamless transition** to HCZ2. No boss in Act 1 triggers the transition; instead, when Camera X >= $C00 in HCZ2_Resize stage 0, it sets Events_fg_5 which triggers the BG event to queue HCZ2 art loading and perform the layout swap. Camera/object positions are offset by -$3600 X. This is one of the most complex act transitions in S3K.
- **Character paths:** Knuckles has different dynamic water heights in Act 2 (different table at `word_6EC2`). The end boss uses completely different arena bounds for Knuckles ($44E0-$4640 X vs $3F00-$4100 X for Sonic/Tails), indicating a separate boss arena location.
- **Dynamic tilemap:** The Act 1->2 transition loads entirely new level layout, blocks, chunks, and patterns via Kos queue. `AnimateTiles_HCZ1` rewrites the waterline chunk strip in `Chunk_table+$7C00` and repairs the affected cave strip with the matching fix DMA uploads.
- **PLC loading:**
  - Act transition: PLC $10 and $11 loaded during HCZ1BGE_Normal (line 105729-105730)
  - Miniboss: PLC $5B loaded at spawn (line 139231)
  - End boss: PLC $6C loaded at spawn (line 140791)
- **Unique mechanics:**
  - **Water tube transport** (Obj_HCZTwistingLoop): Players ride through transparent water tubes
  - **Collapsing wall chase** (Act 2): Timed chase sequence with screen shake, background collision switching, and speed-dependent wall velocity
  - **Spinning columns** (Obj_HCZSpinningColumn): Player grabs and spins around vertical poles
  - **Hand launchers** (Obj_HCZHandLauncher): Mechanical hands that launch the player
  - **Water rush/currents** (Obj_HCZWaterRush): Water flow that pushes the player
  - **Conveyor belts** with spike variants
  - **Background tile collision** in Act 2 wall-chase area (Background_collision_flag toggles based on player position)

## Implementation Notes

### Priority Order
1. **Palette cycling (AnPal_HCZ1)** -- Simple, 1 channel, already has engine infrastructure from AIZ. Quick win for visual accuracy in Act 1.
2. **Events (HCZ1_Resize + HCZ2_Resize)** -- Relatively simple state machines (3 stages Act 1, 2 stages Act 2). The palette mutations in Act 1 stage 0/2 are critical for correct underwater colors.
3. **Parallax -- Act 2 normal mode (HCZ2_Deform)** -- Scatter-fill index pattern is well-established. Implement this before the more complex Act 1 deform.
4. **AniPLC scripts** -- Standard zoneanimdecl entries for both acts. Should work with existing Sonic3kPatternAnimator.
5. **Events background (HCZ1_BackgroundEvent)** -- The seamless act transition is complex but architecturally important.
6. **Parallax -- Act 1 (HCZ1_Deform)** -- The waterline deformation is the most complex parallax routine in S3K. Defer until Act 2 is working.
7. **Custom AnimateTiles** -- The waterline tile swapping (Act 1) and multi-channel BG animation (Act 2) are tightly coupled to the deform routines. Implement alongside parallax.
8. **Boss implementations** -- Depend on PLC art loading infrastructure and arena event logic.

### Dependencies
- Palette cycling requires `AnPal_PalHCZ1` ROM address to be added to `Sonic3kConstants.java`
- Events require the `AbstractLevelEventManager` dual-routine framework (already exists)
- Act transition (HCZ1BGE_DoTransition) requires Kos queue support and seamless level reload capability
- Act 2 wall chase requires screen shake infrastructure (`ShakeScreen_Setup`)
- AnimateTiles_HCZ1/HCZ2 require DMA queue simulation and the corresponding uncompressed art ROM addresses
- End boss Knuckles path requires `character_id` checking in boss spawn code

### Known Risks
- **Residual gap: HCZ1_Deform data dump** -- The control flow is pinned down, but the exact byte contents of `HCZ_WaterlineScroll_Data` still need visual verification against the ROM.
- **Residual gap: AnimateTiles_HCZ1 visual parity** -- The DMA sequencing is concrete, but the swapped chunk art and fix passes should still be screenshot-checked in-engine.
- **Seamless act transition** -- The HCZ1->HCZ2 seamless transition is one of the most complex in S3K (layout reload, camera offset, object offset, Kos queue dependency). May need engine-level support for mid-gameplay level reloads.
- **Background collision flag** -- The Act 2 wall chase enables BG collision only in a specific player position range. The engine's collision system may need to support toggling BG collision dynamically.
- **Palette cycling parity** -- Act 1 HCZ palette cycling is present in the engine, but the underwater mirror write remains unimplemented; Act 2 is intentionally `rts`.
