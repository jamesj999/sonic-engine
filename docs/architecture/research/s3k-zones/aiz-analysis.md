# S3K AIZ Zone Analysis

## Summary

- **Zone:** Angel Island Zone (AIZ)
- **Zone Index:** 0x00
- **Zone Set:** S3KL
- **Acts:** 1 and 2 (with seamless transition from Act 1 fire sequence into Act 2)

## Current Engine Status (2026-08-08)

The principal AIZ-to-HCZ route is implemented and heavily exercised, but AIZ is
not feature-complete. Wave 1 (2026-08-08) implements the
`AizMinibossNapalmProjectile` routine through
`AIZMiniboss_FallingShot` (`loc_68C96`) and wires it through each existing
barrel child: native rise/pause/top-drop timing,
`ObjHitFloor_DoRoutine` floor snapping, post-movement `$98` touch publication,
PLC `$5A` ROM-backed `Map_AIZMiniboss` art, seven staggered `$97`
`BossExplosionHitbox` children, and rewind recreation. The committed Knuckles
AIZ2/AIZ3 trace rows contain the native `0x68C96` FallingShot and `0x68D88`
explosion-child object codes and are recorded as comparison evidence only;
trace rows never hydrate runtime state. The activation/slot/lifetime route is
still awaiting a captured Knuckles miniboss trace before this item can be
marked resolved. The AIZ2 end-boss splash child is now ported as
`AizEndBossWaterfallChild`: its subtype-0 emerge and subtype-2 re-submerge/drop
paths allocate in the native child slot, use ROM mapping/flip scripts, render
from the production ROM art provider, and participate in generic rewind
recreation. The owner tests cover the real `ObjectManager` slot/rewind path and
ROM mapping frames; an end-to-end AIZ2 boss trace was not rerun for this change.
- **Water:** Yes (both acts have water with underwater palette transitions and foreground wave deformation)
- **Palette Cycling:** Yes (2 channels Act 1 normal, 2 channels Act 1 fire, 3 channels Act 2)
- **Animated Tiles:** Yes (3 scripts Act 1, 5 scripts Act 2)
- **Character Branching:** Yes -- Knuckles has a completely separate resize state machine in Act 2 (different boss position, different arena boundaries, different water targets), and chunk adjustments in both acts

## Events

### Act 1 (AIZ1_Resize)

**Disassembly location:** `sonic3k.asm` line 38859

**State machine (eventRoutineFg):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Level start | Sets `AIZ1_palette_cycle_flag=1` (fire palette cycling). When Camera X >= $1000, clears flag and locks `Camera_min_X_pos` to current X. When Camera X >= $1308: loads PLC $0B (AIZ1 level art, Sonic/Tails only), loads palette $2A, resets Tails CPU routine, advances state. | Knuckles (Player_mode >= 2) skips PLC $0B load. Knuckles (Player_mode == 3) gets additional palette line 4 override from `word_4FAE4`. |
| 2 | $02 | Camera X >= $1400 | Locks `Camera_min_X_pos=$1308`. Queues AIZ1 main level 16x16 blocks (Kos) and 8x8 patterns (KosinskiM at VRAM tile $0BE). Sets `Events_fg_5` flag, resets anim counters. Sets star post at ($13A0, $41A). Loads PLC $08 (monitors). | Skipped if checkpoint already hit (`Last_star_post_hit != 0`) or Knuckles. |
| 4 | $04 | Camera X >= various | Uses `Resize_MaxYFromX` with `word_1C60A` table to set dynamic Y boundaries. Writes palette mutation `palette[2][15]` (line 3, offset $1E): $020E default, $0004 when Camera X >= $2B00. When Camera X >= $2D80: loads PLC $5A (AIZ1 miniboss art), locks Camera_min_X_pos=$2D80, advances. | Knuckles gets different palette line 3 color 15 from `word_4FAEA`. Also sets Camera_min_Y_pos=$02E0 when Camera X >= $2C00. |
| 6 | $06 | Camera X >= $2E00 (and Kos queue clear) | Queues AIZ1 flame art (KosinskiM at VRAM tile $500). Loads PLC $0C (AIZ2 level art). Advances state. | Art swap for Act 1 -> Act 2 fire transition. |
| 8 | $08 | Always | Locks `Camera_min_X_pos` to current Camera X. | Terminal state -- camera only moves forward. |

**Resize_MaxYFromX table (word_1C60A):**

| Max Y | Until Camera X |
|-------|----------------|
| $0390 | $1650 |
| $03B0 | $1B00 |
| $0430 | $2000 |
| $04C0 | $2B00 |
| $03B0 | $2D80 |
| $02E0 | $FFFF |

**Palette mutations:**
- Camera X >= $2B00: `Normal_palette_line_3+$1E` = $0004 (palette line 2/index 3, color 15 -- darkens red to near-black inside hollow tree)
- Camera X >= $2D80: `Normal_palette_line_3+$1E` = $0C02 (Sonic/Tails) or value from `word_4FAEA` (Knuckles) -- restores exterior color
- Stage 0 transition at Camera X >= $1308: `palette $2A` loaded (immediate), plus Knuckles-specific palette line 4 overrides

**Knuckles differences:** Knuckles skips PLC $0B load (stage 0) and AIZ1 main art queue (stage 2). Gets different palette colors in stages 0 and 4. Chunk adjustments applied at level init via `Adjust_AIZ1Chunks` / `Adjust_AIZ1Chunks2` (modifies block data for Knuckles-specific paths).

**Confidence:** HIGH

### Act 2 (AIZ2_Resize)

**Disassembly location:** `sonic3k.asm` line 39012

**State machine (eventRoutineFg) -- Sonic/Tails path (stages 0-$10):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 0 | $00 | Level start | If Knuckles: jumps to $12 (Knuckles path). Otherwise advances to $02. | Character routing. |
| 2 | $02 | Camera X >= $2E0 | Sets Camera_max_Y_pos=$590. If `Apparent_zone_and_act == 1` (seamless from miniboss defeat): sets Camera_min_X_pos=$F50 and advances extra stage. | Distinguishes fresh load vs seamless transition. |
| 4 | $04 | Camera X >= $F50 | Locks Camera_min_X_pos=$F50. Spawns `Obj_AIZMiniboss` at ($11F0, $289). Camera_max_Y_pos drops to $2B8 when Camera X >= $ED0. | Act 2 miniboss arena (Sonic path). |
| 6 | $06 | Camera X >= $1500 | Sets Camera_max_Y_pos=$630. | Opens up level height post-miniboss. |
| 8 | $08 | Camera X >= $3C00 (and Kos queue clear) | Queues battleship 16x16 blocks (Kos) and 8x8 patterns (KosinskiM at tile $1FC + ArtTile_AIZ2Bombership). Loads palette $30. Sets `Events_fg_5` flag. | Battleship art and palette loaded for end sequence. |
| A | $0A | Camera X >= $3F00 | Sets Camera_min_Y_pos=$15A. | Camera lock for battleship approach. |
| C | $0C | Camera X >= $4000 | Sets Camera_max_Y_pos=$15A. | Full vertical lock. |
| E | $0E | Camera X >= $4160 | Sets `Events_fg_4` flag. | Triggers battleship sequence in ScreenEvent. |
| 10 | $10 | -- | Terminal rts. | End state. |

**State machine (eventRoutineFg) -- Knuckles path (stages $12-$1C):**

| Stage | Offset | Trigger | Action | Notes |
|-------|--------|---------|--------|-------|
| 12 | $12 | Camera X >= $2E0 | Sets Camera_max_Y_pos=$590. If seamless transition: sets Camera_min_X_pos=$1040, advances extra. | Knuckles arena is further right. |
| 14 | $14 | Camera X >= $1040 | Locks Camera_min_X_pos=$1040. Spawns `Obj_AIZMiniboss` at ($11D0, $420). Sets Target_water_level=$F80. Camera_max_Y_pos drops to $450 when Camera X >= $E80. | Knuckles fights the miniboss lower and further right. Water raised. |
| 16 | $16 | Camera X >= $11A0 | Sets Camera_target_max_Y_pos=$820. | Opens height post-miniboss. |
| 18 | $18 | Camera X >= $3B80 (and Kos queue clear) | Queues battleship art (same as Sonic). Loads palette $30. Locks Camera_min_X_pos=$3B80, sets Camera_target_max_Y_pos=$5DA. Sets `Events_fg_5`. | Knuckles battleship art load triggers slightly earlier. |
| 1A | $1A | Camera X >= $3F80 | Locks Camera_min_X_pos=$3F80. | Knuckles camera lock. |
| 1C | $1C | -- | Terminal rts. | End state. |

**Knuckles differences:** Entirely separate state machine. Miniboss spawns at different position ($11D0, $420 vs $11F0, $289). Arena boundaries differ. Water level is changed ($F80 target). Battleship art loads at $3B80 instead of $3C00. No battleship interactive sequence (Knuckles path ends with just camera lock, no `Events_fg_4` trigger).

**Confidence:** HIGH

## Boss Sequences

### Act 1 Miniboss (also used in Act 2)

- **Object:** `Obj_AIZMiniboss` at line 137222
- **Cutscene:** `Obj_AIZMinibossCutscene` at line 136734 (pre-fight cutscene)
- **Spawn trigger:** Act 1: Camera X >= $2D80 (PLC loaded), then Arena used via miniboss cutscene flow. Act 2: Camera X >= $F50 (Sonic) or $1040 (Knuckles)
- **PLC load:** PLC $5A (loaded at AIZ1_Resize stage 4 / line 38972-38973) -- contains ArtNem_AIZMiniboss, ArtNem_AIZMinibossSmall, ArtNem_AIZBossFire, ArtNem_BossExplosion
- **Arena boundaries (Sonic):** Left=$F50, Max Y=$2B8 (narrowed when Camera X >= $ED0)
- **Arena boundaries (Knuckles):** Left=$1040, Max Y=$450 (narrowed when Camera X >= $E80)
- **Defeat behavior:** Triggers fire transition sequence leading to Act 2
- **After-boss PLC:** `PLC_AfterMiniboss_AIZ` (line 176606) loaded after defeat -- restores monitors, AIZ2 art, swing vine, background tree, bubbles, button, cork floor
- **Confidence:** HIGH

### Act 2 Boss (Eggman Battleship + Small Boss)

- **Battleship object:** `Obj_AIZBattleship` at line 105257, creates `Obj_AIZBattleshipMain` and `Obj_AIZ2BossSmall`
- **Small boss:** `Obj_AIZ2BossSmall` at line 105572
- **Ship bomb:** `Obj_AIZShipBomb` at line 105362
- **End boss:** `Obj_AIZEndBoss` at line 137997 (separate boss fight after battleship)
- **Spawn trigger:** Battleship spawned via `Events_fg_4` flag (set at Camera X >= $4160) in AIZ2_ScreenEvent
- **PLC load:** Battleship art loaded at AIZ2_Resize stages $08/$18 (Kos + KosinskiM). End boss: PLC $6B (ArtNem_RobotnikShip, ArtNem_BossExplosion)
- **Ship loop system:** `AIZ2_DoShipLoop` (line 105200) -- wraps camera and player by $200 when Camera X exceeds `Events_bg+$02` to create infinite scrolling runway
- **Defeat behavior:** Post-defeat triggers Act 2 results screen
- **Confidence:** HIGH

## Parallax

### Act 1 Intro (AIZ1_IntroDeform)

**Disassembly location:** `Lockon S3/Screen Events.asm` line 515

**Description:** During the intro sequence (before Camera X reaches $1300), a special intro deform is used. The camera X is halved, and a gradient interpolation creates parallax bands that scroll independently based on distance from a $580 threshold.

**Bands:** 37 bands of 4px each (from `AIZ1_IntroDeformArray`), all at constant 4px height. A separate intro draw array (`AIZ1_IntroDrawArray`) uses $3E0 height for the first band then 10 bands of $10 each.

**Confidence:** MEDIUM (complex interpolation logic with clamping at $580)

### Act 1 Normal (AIZ1_Deform)

**Disassembly location:** `Lockon S3/Screen Events.asm` line 576

**Description:** Multi-layer parallax with Y at half camera speed. X scrolling uses a delta from Camera X minus $1300, accumulated through incremental additions to build up 7 speed layers for the background mountains/canopy.

**Bands (from AIZ1_DeformArray):**

| Band | Height (px) | Description |
|------|-------------|-------------|
| 0 | $D0 (208) | Sky |
| 1 | $20 (32) | Far mountains |
| 2 | $30 (48) | Mid mountains |
| 3 | $30 (48) | Near mountains |
| 4 | $10 (16) | Canopy layer 1 |
| 5 | $10 (16) | Canopy layer 2 |
| 6 | $10 (16) | Canopy layer 3 |
| 7 | Per-line ($800D) | Per-line scroll start |
| 8 | $0F (15) | Water surface line group 1 |
| 9 | $06 (6) | Water surface line group 2 |
| 10 | $0E (14) | Water surface line group 3 |
| 11 | $50 (80) | Deep water |
| 12 | $20 (32) | Bottom |

**Auto-scroll accumulators:** `HScroll_table+$03C` incremented by $2000 each frame (cloud/canopy auto-scroll effect for bands 1-6 interleaved with camera-based scroll).

**Water deformation:** `AIZ1_ApplyDeformWater` (line 639) splits rendering at the water level boundary. Above water uses `AIZ1_DeformArray`, below water uses `AIZ1_WaterFGDeformDelta` / `AIZ1_WaterBGDeformDelta` sine tables indexed by `Level_frame_counter` for underwater waviness.

**Vertical scroll:** Simple -- Camera Y / 2 for BG.

**Confidence:** MEDIUM (speed computation involves chained shifts/adds that are non-trivial to map to exact divisors)

### Act 2 (AIZ2_Deform)

**Disassembly location:** `Lockon S3/Screen Events.asm` line 744

**Description:** BG Y scroll uses `(Camera_Y - Screen_shake_offset) / 2 + Screen_shake_offset`. BG X scroll uses `Events_fg_1 / 2` as base, with incremental accumulation via `AIZ2_BGDeformMake` scatter-fill table that distributes 7 speed tiers across 25 HScroll entries.

**Bands (from AIZ2_BGDeformArray):**

| Band | Height (px) | Description |
|------|-------------|-------------|
| 0 | $10 (16) | Far background 1 |
| 1 | $20 (32) | Far background 2 |
| 2 | $38 (56) | Mountain layer 1 |
| 3 | $58 (88) | Mountain layer 2 |
| 4 | $28 (40) | Canopy 1 |
| 5 | $40 (64) | Canopy 2 |
| 6 | $38 (56) | Canopy 3 |
| 7 | $18 (24) | Near canopy 1 |
| 8 | $18 (24) | Near canopy 2 |
| 9 | $90 (144) | Large middle section |
| 10 | $48 (72) | Lower background |
| 11 | $10 (16) | Bottom 1 |
| (repeats) | | Second cycle of same pattern |

**Scatter-fill system (AIZ2_BGDeformMake):** Maps 7 speed tiers to non-contiguous HScroll entries at `HScroll_table+$1C0`:
- Tier 0 (2 entries): $12, $2A
- Tier 1 (4 entries): $10, $14, $28, $2C
- Tier 2 (4 entries): $0E, $16, $26, $2E
- Tier 3 (5 entries): $00, $0C, $18, $24, $30
- Tier 4 (4 entries): $02, $0A, $1A, $22
- Tier 5 (4 entries): $04, $08, $1C, $20
- Tier 6 (2 entries): $06, $1E

**Water deformation:** `AIZ2_ApplyDeform` (line 789) handles foreground and background water wave deformation using `AIZ2_SOZ1_LRZ3_FGDeformDelta` (shared sine table), `AIZ1_WaterFGDeformDelta`, and `AIZ1_WaterBGDeformDelta`. Splits at water level for both FG and BG planes.

**Battleship override:** When `Events_bg+$04` is set (battleship sequence), the top 64 scanlines of FG scroll are overwritten with the secondary camera X position (`_unkEE98`) for the ship layer.

**Vertical scroll (battleship):** `AIZTrans_WavyFlame` (line 702) provides per-column VScroll during the fire transition using `AIZ_FlameVScroll` sine data (16-byte table). Used for flame waviness effect.

**Confidence:** MEDIUM (scatter-fill and water split are well understood; exact speed ratios require careful fixed-point analysis)

## Animated Tiles

### AniPLC_AIZ1 (3 scripts)

**Disassembly location:** `sonic3k.asm` line 55570

#### Script 0: Waterfall splash (primary)
- **Source art:** `ArtUnc_AniAIZ1_0`
- **Destination VRAM tile:** $2E6
- **Frame count:** 9
- **Tiles per frame:** $0C (12)
- **Frame durations:** $3C/$4F, $30/5, $18/5, $0C/5, 0/$4F, $0C/3, $18/3, $24/1, $30/1 (variable per frame)
- **Trigger:** Always active (trigger byte = -1)
- **Confidence:** HIGH

#### Script 1: Waterfall splash (secondary)
- **Source art:** `ArtUnc_AniAIZ1_0` (same source, different dest)
- **Destination VRAM tile:** $2F2
- **Frame count:** 8
- **Tiles per frame:** $0C (12)
- **Frame durations:** $18/5, $24/5, $30/5, $3C/$27, 0/5, $0C/5, $18/5, $24/5
- **Trigger:** Always active (trigger byte = -1)
- **Confidence:** HIGH

#### Script 2: Water surface ripple
- **Source art:** `ArtUnc_AniAIZ1_1`
- **Destination VRAM tile:** $2FE
- **Frame count:** 8
- **Tiles per frame:** $06 (6)
- **Frame durations:** 0/7, 6/3, $C/3, $12/3, $18/7, $12/3, $C/3, 6/3
- **Trigger:** Always active (trigger byte = -1)
- **Confidence:** HIGH

### AniPLC_AIZ2 (5 scripts)

**Disassembly location:** `sonic3k.asm` line 55604

#### Script 0: Lava/fire flow
- **Source art:** `ArtUnc_AniAIZ2_0`
- **Destination VRAM tile:** $0B3
- **Frame count:** 4
- **Tiles per frame:** $17 (23)
- **Trigger:** Trigger byte = 3 (conditional on `Level_trigger_array+3`)
- **Confidence:** HIGH

#### Script 1: Waterfall splash (primary, same as Act 1 script 0 pattern)
- **Source art:** `ArtUnc_AniAIZ2_1`
- **Destination VRAM tile:** $0CA
- **Frame count:** 9
- **Tiles per frame:** $0C (12)
- **Trigger:** Always active (trigger byte = -1)
- **Confidence:** HIGH

#### Script 2: Waterfall splash (secondary, same as Act 1 script 1 pattern)
- **Source art:** `ArtUnc_AniAIZ2_1`
- **Destination VRAM tile:** $0D6
- **Frame count:** 8
- **Tiles per frame:** $0C (12)
- **Trigger:** Always active (trigger byte = -1)
- **Confidence:** HIGH

#### Script 3: Small animated element
- **Source art:** `ArtUnc_AniAIZ2_2`
- **Destination VRAM tile:** $0E2
- **Frame count:** 4
- **Tiles per frame:** $04 (4)
- **Trigger:** Trigger byte = 3 (conditional)
- **Confidence:** HIGH

#### Script 4: Large animated element (torch/fire glow?)
- **Source art:** `ArtUnc_AniAIZ2_3`
- **Destination VRAM tile:** $0E6
- **Frame count:** 4
- **Tiles per frame:** $18 (24)
- **Trigger:** Trigger byte = 3 (conditional)
- **Confidence:** HIGH

## Palette Cycling

### Act 1 -- Normal Mode (AIZ1_palette_cycle_flag == 0)

**Disassembly location:** `sonic3k.asm` line 3173

**Shared timer:** `Palette_cycle_counter1`, period = 8 frames (reset to 7)

#### Channel 0: Waterfall shimmer

- **Counter:** `Palette_cycle_counter0`
- **Step:** 8 bytes (4 colors per tick)
- **Limit:** $20 (masked with $18 -- 4 frames cycling: offsets 0, 8, $10, $18)
- **Timer:** Shared (8 frames)
- **Table:** `AnPal_PalAIZ1_1` at line 3953 (16 words = 4 frames x 4 colors)
- **Destination:** `Normal_palette_line_3+$16` = Palette line 2 (index 3), colors 11-14
- **Conditional:** Only when `AIZ1_palette_cycle_flag == 0` (normal mode, not intro/fire)
- **Confidence:** HIGH

#### Channel 1: Secondary water

- **Counter:** `Palette_cycle_counters+$02`
- **Step:** 6 bytes (3 colors per tick)
- **Limit:** $30 (8 frames)
- **Timer:** Shared (8 frames)
- **Table:** `AnPal_PalAIZ1_2` at line 3995 (24 words = 8 frames x 3 colors)
- **Destination:** `Normal_palette_line_4+$18` = Palette line 3 (index 4), colors 12-14
- **Conditional:** Gated by `Palette_cycle_counters+$00 == 0` (not active at level start)
- **Confidence:** HIGH

### Act 1 -- Fire/Intro Mode (AIZ1_palette_cycle_flag != 0)

**Disassembly location:** `sonic3k.asm` line 3202

**Shared timer:** `Palette_cycle_counter1`, period = 10 frames (reset to 9)

#### Channel 2: Fire colors

- **Counter:** `Palette_cycle_counter0`
- **Step:** 8 bytes (4 colors per tick)
- **Limit:** $50 (10 frames)
- **Timer:** Shared (10 frames)
- **Table:** `AnPal_PalAIZ1_3` at line 3959 (40 words = 10 frames x 4 colors)
- **Destination:** `Normal_palette_line_4+$04` = Palette line 3 (index 4), colors 2-5
- **Conditional:** Only when `AIZ1_palette_cycle_flag != 0`
- **Confidence:** HIGH

#### Channel 3: Fire secondary

- **Counter:** `Palette_cycle_counters+$02`
- **Step:** 6 bytes (3 colors per tick)
- **Limit:** $3C (10 frames)
- **Timer:** Shared (10 frames)
- **Table:** `AnPal_PalAIZ1_4` at line 3977 (30 words = 10 frames x 3 colors... actually 48 words / 3 = 16 entries, but limit $3C / 6 = 10)
- **Destination:** `Normal_palette_line_4+$1A` = Palette line 3 (index 4), colors 13-15
- **Conditional:** Only when `AIZ1_palette_cycle_flag != 0`
- **Confidence:** HIGH

**Mode switching:** `AIZ1_palette_cycle_flag` is set to 1 at level start (AIZ1_Resize stage 0, line 38874) and cleared to 0 when Camera X >= $1000 (line 38877). This switches from fire mode to normal mode permanently.

### Act 2 (AnPal_AIZ2)

**Disassembly location:** `sonic3k.asm` line 3231

#### Channel 0: Water cycling

- **Counter:** `Palette_cycle_counter0`
- **Step:** 8 bytes (4 colors per tick)
- **Limit:** $20 (masked with $18 -- 4 frames)
- **Timer:** `Palette_cycle_counter1`, period = 6 frames (reset to 5)
- **Table:** `AnPal_PalAIZ2_1` at line 4005 (16 words = 4 frames x 4 colors)
- **Destination:** `Normal_palette_line_4+$18` = Palette line 3 (index 4), colors 12-15
- **Confidence:** HIGH

#### Channel 1: Water trickle (camera-X dependent table swap)

- **Counter:** `Palette_cycle_counters+$02`
- **Step:** 6 bytes (3 colors per tick)
- **Limit:** $30 (8 frames)
- **Timer:** Shared with Channel 0 (6 frames)
- **Table:** `AnPal_PalAIZ2_2` at line 4011 when Camera X < $3800; `AnPal_PalAIZ2_3` at line 4021 when Camera X >= $3800
- **Destination:** Three scattered writes:
  - `Normal_palette_line_3+$08` = Palette line 2 (index 3), color 4
  - `Normal_palette_line_3+$10` = Palette line 2 (index 3), color 8
  - `Normal_palette_line_4+$16` = Palette line 3 (index 4), color 11
- **Additional:** Hardcodes `Normal_palette_line_3+$1C` = $0A0E unless Camera X < $1C0 (then uses table value)
- **Conditional:** Always active; table swaps at Camera X $3800 boundary
- **Confidence:** HIGH

#### Channel 2: Torch/fire glow

- **Counter:** `Palette_cycle_counters+$04`
- **Step:** 2 bytes (1 color per tick)
- **Limit:** $34 (26 frames)
- **Timer:** `Palette_cycle_counters+$08`, period = 2 frames (reset to 1)
- **Table:** `AnPal_PalAIZ2_4` at line 4031 when Camera X < $3800; `AnPal_PalAIZ2_5` at line 4059 when Camera X >= $3800
- **Destination:** `Normal_palette_line_4+$02` = Palette line 3 (index 4), color 1
- **Conditional:** Always active; table swaps at Camera X $3800 boundary. This is the critical "green fire fix" -- without it, fire sprites show vegetation green instead of fire colors.
- **Confidence:** HIGH

## Notable Objects

| Object ID | Name | Description | Zone-Specific? | Notes |
|-----------|------|-------------|----------------|-------|
| -- | `Obj_AIZPlaneIntro` | Intro plane sequence (Tails flies Sonic in) | Yes | Loaded at `Dynamic_object_RAM+object_size*2` during intro bootstrap |
| -- | `Obj_AIZ1Tree` | Act 1 foreground tree | Yes | Line 42271 |
| -- | `Obj_AIZ1ZiplinePeg` | Zipline sliding peg | Yes | Line 42283 |
| -- | `Obj_AIZHollowTree` | Hollow tree interior transition | Yes | Line 43601, spawns `Obj_AIZ1TreeRevealControl` |
| -- | `Obj_AIZLRZEMZRock` | Rock obstacle (shared with LRZ/EMZ) | Shared | Line 43838 |
| -- | `Obj_AIZRideVine` | Swinging ride vine | Yes (shared w/ MHZ) | Line 46098, spawns `Obj_AIZRideVineHandle` |
| -- | `Obj_AIZGiantRideVine` | Giant vine (Act 2) | Yes | Line 46749 |
| -- | `Obj_AIZDisappearingFloor` | Disappearing floor panels | Yes | Line 58320 |
| -- | `Obj_AIZFlippingBridge` | Bridge that flips when walked on | Yes | Line 58872 |
| -- | `Obj_AIZCollapsingLogBridge` | Log bridge that collapses | Yes | Line 59193 |
| -- | `Obj_AIZDrawBridge` | Drawbridge with fire | Yes | Line 59490 |
| -- | `Obj_AIZFallingLog` | Log that falls into water | Yes | Line 59887 |
| -- | `Obj_AIZSpikedLog` | Rotating spiked log | Yes | Line 60038 |
| -- | `Obj_AIZForegroundPlant` | Decorative foreground plant | Yes | Line 60430 |
| -- | `Obj_AIZMiniboss` | AIZ miniboss (fire-breathing machine) | Yes | Line 137222 |
| -- | `Obj_AIZMinibossCutscene` | Pre-fight cutscene for miniboss | Yes | Line 136734 |
| -- | `Obj_AIZMiniboss_Flame` | Flame projectile from miniboss | Yes | Line 137104 |
| -- | `Obj_AIZBattleship` | Act 2 bombing battleship | Yes | Line 105257 |
| -- | `Obj_AIZShipBomb` | Bombs dropped by battleship | Yes | Line 105362 |
| -- | `Obj_AIZ2BossSmall` | Small boss on battleship | Yes | Line 105572 |
| -- | `Obj_AIZ2BGTree` | Background tree during battleship | Yes | Line 105540 |
| -- | `Obj_AIZEndBoss` | Final Act 2 boss (Eggman) | Yes | Line 137997 |
| -- | `Obj_AIZTransitionFloor` | Solid floor during Act 1->2 transition | Yes | Line 104777 |
| -- | `Obj_AIZ1TreeRevealControl` | Controls progressive tree tile reveal | Yes | Line 104414 |

**Badnik PLCs (PLCKosM_AIZ):** MonkeyDude, Bloominator, CaterkillerJr (line 64343-64347). Shared for both acts.

## Cross-Cutting Concerns

- **Water:** Present in both acts. AIZ1 has static water level (`DynamicWaterHeight_AIZ1` is just `rts`). AIZ2 has dynamic water height changes (`DynamicWaterHeight_AIZ2` at line 8648): water target changes from $528 to $618 based on Camera X thresholds, with screen shake and `Level_trigger_array` flags. Knuckles path sets `Target_water_level=$F80` in Resize. Underwater palette transitions defined: `WaterTransition_AIZ1` (15 color pairs, line 9316), `WaterTransition_AIZ2` (18 color pairs, line 9335). Water palette data at `Pal_AIZ_Water` / `Pal_AIZ2_Water` (line 200573-200576).
- **Screen shake:** Present in Act 2 via `Screen_shake_flag` write (line 8678) during dynamic water height change (dam break at Camera X >= $2900, Knuckles excluded when `Camera_target_max_Y_pos == $820`). AIZ2_Deform incorporates `Screen_shake_offset` into BG Y (line 747). `ShakeScreen_Setup` called in AIZ2 BG event routines.
- **Act transition:** Act 1 ends with a seamless fire transition sequence. After miniboss defeat, `AIZ1_BackgroundEvent` progresses through: fire intro palette mutation -> `AIZ1_FireRise` (camera BG Y rises) -> art load for Act 2 (chunks, blocks, patterns via Kos/KosinskiM queues) -> `Obj_AIZTransitionFloor` spawned -> full level reload (`Load_Level`, `LoadSolids`, `CheckLevelForWater`, palette) -> player position offset by (-$2F00, -$80) -> boundaries set to Act 2 initial values. This is a "seamless" transition that never goes through a title card.
- **Character paths:** Knuckles has extensive differences: separate Resize state machines in Act 2, different miniboss position and arena, different water level targets, chunk adjustments in both acts via `Adjust_AIZ1Chunks2` / `Adjust_AIZ2Layout`, different palette overrides. Knuckles does NOT get the battleship interactive sequence (no `Events_fg_4` trigger in Knuckles path).
- **Dynamic tilemap:** Act 1 tree reveal system: `Obj_AIZ1TreeRevealControl` increments `Events_fg_4` to progressively reveal tree tiles via `AIZ1_ScreenEvent` chunk manipulation (`AIZ1SE_ChangeChunk1`-`4`). Uses `AIZ_TreeRevealArray` data. Act 2: battleship sequence changes FG plane via `AIZ2_ScreenEvent` states.
- **PLC loading:**
  - Stage 0 (Act 1): PLC $0B (AIZ1 level art -- swing vine, slide rope, misc, falling log, bubbles, floating platform)
  - Stage 2 (Act 1): PLC $08 (monitors)
  - Stage 4 (Act 1): PLC $5A (AIZ1 miniboss art -- miniboss, small parts, fire, explosion)
  - Stage 6 (Act 1): PLC $0C/$0D (AIZ2 art -- misc2, swing vine, BG tree, bubbles, button, cork floor)
  - Act 2 Stage 8/$18: Battleship art via Kos/KosinskiM queues (not PLC system)
  - After miniboss: `PLC_AfterMiniboss_AIZ` (monitors, AIZ2 misc, swing vine, BG tree, bubbles, button, cork floor)
  - Enemy art: `PLCKosM_AIZ` (MonkeyDude, Bloominator, CaterkillerJr)
  - End boss: PLC $6B (RobotnikShip, BossExplosion)
- **Unique mechanics:**
  - Intro plane ride sequence (`Obj_AIZPlaneIntro`)
  - Zipline sliding mechanic (`Obj_AIZ1ZiplinePeg`)
  - Vine swinging (`Obj_AIZRideVine`, `Obj_AIZGiantRideVine`)
  - Drawbridge with fire (`Obj_AIZDrawBridge`)
  - Spiked log rotation (`Obj_AIZSpikedLog`)
  - Flipping bridge (`Obj_AIZFlippingBridge`)
  - Collapsing log bridge (`Obj_AIZCollapsingLogBridge`)
  - Disappearing floor panels (`Obj_AIZDisappearingFloor`)
  - Battleship bombing run with screen loop (`AIZ2_DoShipLoop` wraps by $200)
  - Progressive tree tile reveal (unique visual effect using per-frame chunk swaps)

## Implementation Notes

### Priority Order
1. **Events (Resize)** -- Camera locks, boss spawns, and boundary management are foundational. Both acts' state machines must be implemented with Knuckles branching.
2. **Parallax (Deform)** -- Visual fidelity depends on correct multi-layer scrolling, especially the water deformation split.
3. **Palette Cycling (AnPal)** -- Critical for visual correctness: fire mode cycling, torch glow (green fire fix), waterfall shimmer.
4. **Animated Tiles (AniPLC)** -- Waterfall, water surface, and fire-gated tile animations.
5. **Act Transition** -- Seamless Act 1 -> Act 2 fire transition is the most complex background event sequence in the game.

### Dependencies
- Palette cycling requires `AIZ1_palette_cycle_flag` to be set/cleared by the Resize routine
- Act 2 channel 1 and 2 table swaps depend on Camera X tracking (Camera X >= $3800)
- Battleship sequence requires: art loaded (Resize stage 8), screen event triggered (Resize stage E -> Events_fg_4), background event in ship state
- Water deformation requires water level system to be active
- Tree reveal requires `Obj_AIZ1TreeRevealControl` + `AIZ1_ScreenEvent` chunk manipulation

### Known Risks
- **Act 1 -> Act 2 transition:** The seamless fire transition is extremely complex (6 background event stages, concurrent art loading, layout swapping, position offsetting). This is the highest-risk feature. Confidence: MEDIUM -- the broad structure is clear but the exact timing of art queue completion checks and VInt synchronization needs careful testing.
- **Battleship loop system:** The $200 camera wrap in `AIZ2_DoShipLoop` requires careful synchronization between camera, player positions, and tile drawing. HInt6 is used to switch VScroll mid-frame. Confidence: MEDIUM.
- **Scatter-fill parallax (Act 2):** The `AIZ2_BGDeformMake` non-contiguous HScroll fill is unusual and requires special handling vs. the standard sequential deform array approach. Confidence: MEDIUM.
- **Water deformation split:** Both acts split FG and BG deformation at the water level boundary, applying different sine tables above and below. This interacts with the water level system and must handle edge cases (fully above/below water). Confidence: MEDIUM.
