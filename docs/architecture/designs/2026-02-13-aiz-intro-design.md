# AIZ1 Intro Cinematic - Design Document

## Overview

Implements the full Angel Island Zone Act 1 intro cinematic for Sonic/Sonic&Tails mode, matching the original S3K ROM pixel-for-pixel. The intro plays when `S3K_SKIP_AIZ1_INTRO=false` and the player character is Sonic.

The sequence: Sonic arrives on a biplane as Super Sonic, the plane descends and decelerates, Super Sonic de-transforms, the player regains control and runs right across the beach. At a trigger point, Knuckles appears, the emeralds scatter from an explosion, Knuckles collects them while pacing, laughs, and walks offscreen. The title card appears and gameplay begins.

## Architecture

### Object Composition

The intro is driven by **3 independent objects**, following the ROM's architecture:

| Object | ROM Name | Routines | Purpose |
|--------|----------|----------|---------|
| `AizPlaneIntroInstance` | `Obj_AIZPlaneIntro` | 14 (0x00-0x1A) | Master orchestrator: plane flight, Super Sonic transform, position monitoring, emerald explosion |
| `CutsceneKnucklesAiz1Instance` | `CutsceneKnux_AIZ1` | 7 (0x00-0x0C) | Knuckles drops in, paces (collecting emeralds), laughs, walks off, spawns title card |
| `AizEmeraldScatterInstance` | `loc_67900` | 3 phases | 7 scatter objects: launch from player, gravity, floor collision, Knuckles proximity pickup |

### Spawn Chain

1. `Sonic3kAIZEvents.init()` spawns `AizPlaneIntroInstance` (see Discrepancy #1)
2. At player X >= 0x918, intro object spawns `CutsceneKnucklesAiz1Instance`
3. At player X >= 0x13D0, intro object spawns 7x `AizEmeraldScatterInstance`, then deletes itself
4. When Knuckles walks offscreen, it unlocks controls, spawns title card, and sets `Level_started_flag`

### What the Level Event Handler Owns

`Sonic3kAIZEvents` (new, extends `Sonic3kZoneEvents`):
- Spawns the intro object during `init()` when bootstrap is not GAMEPLAY_AFTER_INTRO
- Post-intro AIZ gameplay events (dynamic boundaries, boss arena) - future work

## AizPlaneIntroInstance - 14-Routine State Machine

### Phase A: Plane Flight (Routines 0-5)

| Routine | ROM | Behavior |
|---------|-----|----------|
| 0 (Init) | `loc_674AC` | Set position (0x60, 0x30). Use Sonic mappings (frame 0xBA). Set `Events_fg_1 = 0xE918`. Lock player: `object_control = 0x53`, `mapping_frame = 0`. Spawn plane child + emerald glow children. Set `$2E = 0x40`, `$40 = 8`. |
| 1 (Wait) | `loc_67514` | `Obj_Wait` pattern: decrement `$2E` timer, call `$34` callback when expired. |
| 2 (Descend) | `loc_67536` | Each frame: `y_vel -= 0x18`. Call `MoveSprite2`. When `y_vel <= 0`, advance. |
| 3 (Swing) | `loc_67560` | Call `Swing_UpAndDown` (oscillate Y velocity). `MoveSprite2`. Swing callback triggers advance. |
| 4 (H-Decel) | `loc_67594` | Each frame: `x_vel -= 0x40`. `MoveSprite2`. Check `y_pos >= 0x130`. When met, advance. |
| 5 (H-Stop) | `loc_675C0` | Continue horizontal deceleration to ~0. Set timer. |

### Phase B: Super Sonic Transform (Routines 6-10)

| Routine | ROM | Behavior |
|---------|-----|----------|
| 6 (Timer + Waves) | `loc_675D6`/`loc_67614` | Set x_pos=0x40, wave spawn callback (`loc_675FA`) fires every 5 frames when x_pos >= 0x80. Main timer `$3A = 0x3F` counts down. Calls `loc_67996` for Super Sonic mapping/palette init. When `$3A` expires, advance. |
| 7 (Walk Right) | `loc_67624` | `sub_679B8` (Super palette cycle). `Obj_Wait` continues wave spawning. `x_pos += 4` each frame. When `x_pos >= 0x200`, advance. Set `$3A = 0x1F`. |
| 8 (Flash) | `loc_6764E` | Super palette cycle. `Obj_Wait` + waves. Countdown `$3A`. When expired: advance, set bit 2 in `$38` (flip left), `$40 = 0xC`. |
| 9 (Walk Left) | `loc_67674` | Super palette cycle. `Obj_Wait` + waves. `x_pos -= 4`. When `x_pos <= 0x120`, advance. Set timer. |
| 10 (Pause) | `loc_676AC` | Super palette cycle. `Obj_Wait` + waves. Countdown timer. When expired, advance. Release player control. |

### Phase C: Position Monitoring (Routines 11-13)

| Routine | ROM | Behavior |
|---------|-----|----------|
| 11 (X >= 0x918) | `loc_676C6` | Super palette cycle. `Obj_Wait` + waves. When `player.x >= 0x918`: spawn `CutsceneKnucklesAiz1Instance`, advance. |
| 12 (X >= 0x1240) | `loc_676E8` | Super palette cycle. When `player.x >= 0x1240`: `y_pos -= 0x20`, advance. No Obj_Wait, so waves stop. |
| 13 (X >= 0x13D0) | `loc_67704` | Super palette cycle. When `player.x >= 0x13D0`: disable Super Sonic, throw player into air (`y_vel=-0x400, x_vel=-0x200`), lock input, load emerald palette to line 4, spawn 7 emerald scatter objects. **Intro object deletes itself.** |

### sub_679B8 - Super Sonic Palette Cycling (Intro-Specific)

Called each frame during routines 7-13. NOT the same as the `SuperStateController` cycling - this runs on the intro object:
- Set `Super_Sonic_Knux_flag = 1`
- Alternate between mapping frames 0x21/0x22 on V-blank parity
- Every 6 frames: advance `Palette_frame` by 6 (word offsets into `PalCycle_SuperSonic`)
- Cycling range: 0x24-0x36, wraps from >0x36 back to 0x24
- Writes 3 Mega Drive colors (6 bytes) to palette line 0, colors 2-4

### sub_67A08 - Screen Scroll Helper

Called during early routines. Uses `$40` value:
- If `Events_fg_1 < 0`: add `$40` to `Events_fg_1` (scrolls screen left during approach)
- If `Events_fg_1 >= 0`: add `$40` to `Player_1.x_pos` (moves Sonic right after scroll completes)

### Wave Spawning

The `Obj_Wait` callback (`loc_675FA`) fires every 5 frames from routine 6 through routine 11:
- Resets timer `$2E = 5`
- If `x_pos >= 0x80`, spawns one `AizIntroWaveChild`
- Wave children use `Map_AIZIntroWaves`, scroll left by parent's `$40` value, self-delete when X < 0x60

## CutsceneKnucklesAiz1Instance - 7 Routines

Spawned at routine 11. Uses `ObjSlot_CutsceneKnux` attributes (art tile `ArtTile_CutsceneKnux`, palette line 1, priority 0x180). Has DPLC.

| Routine | ROM | Behavior |
|---------|-----|----------|
| 0 (Init) | `loc_61DBE` | Position at (0x1400, 0x440). `mapping_frame = 8`, `y_radius = 0x13`. Load Knuckles palette via `sub_65DD6`. Spawn rock child (`loc_61F60`). |
| 1 (Wait Trigger) | `loc_61DF4` | Poll parent's `status bit 7`. When set: advance, set visible, `y_vel = -0x600`, `x_vel = 0x80`, load `Pal_CutsceneKnux` to palette line 1. |
| 2 (Fall) | `loc_61E24` | Animate jump/fall frames. `MoveSprite` with gravity. On floor collision: snap Y, `mapping_frame = 0x16`, timer `$2E = 0x7F`. |
| 3 (Stand) | `loc_61E64` | `Obj_Wait` countdown 0x7F frames. When expired: flip facing, set walk anim, `x_vel = -0x600`, timer `$2E = 0x29`. |
| 4 (Pace L/R) | `loc_61E96` | Animate + `MoveSprite2` + `Obj_Wait`. After 0x29 frames left: reverse `x_vel`, flip, timer 0x29. After 0x29 frames right: advance. **Knuckles collects emeralds during pacing** (emerald scatter objects detect proximity). Then: `mapping_frame = 0x16`, timer `$2E = 0x3F`, load laugh anim (frame 0x1C via `loc_62056`). |
| 5 (Laugh) | `loc_61EE0` | Animate laugh + `Obj_Wait` 0x3F frames. When expired: set walk anim, `x_vel = 0x600`. Spawn `Obj_Song_Fade_ToLevelMusic` (fade to AIZ music). |
| 6 (Exit Right) | `loc_61F10` | Animate + `MoveSprite2` until offscreen. When offscreen: clear `Palette_cycle_counters`, unlock `Ctrl_1_locked`, call cleanup, spawn title card, set `Level_started_flag = 0x91`, delete self. **Gameplay begins.** |

### Rock Child (`loc_61F60`)

Spawned at Knuckles init. Waits invisibly until Knuckles' `status bit 7` is set. Then calls `BreakObjectToPieces` to shatter into fragments (visual effect of Knuckles punching through).

## AizEmeraldScatterInstance - 3 Phases

7 instances spawned at the explosion (player X >= 0x13D0). Each has a unique subtype (0-6).

| Phase | Behavior |
|-------|----------|
| Init (`loc_67900`) | `mapping_frame = subtype >> 1` (7 colors). Position at Player_1 X/Y. `Set_IndexedVelocity` with index 0x40 for scatter directions. `y_radius = 4`. |
| Falling (`loc_67938`) | `MoveSprite` with gravity. `ObjCheckFloorDist`. When floor hit (`d1 < 0`): snap Y, transition to grounded. |
| Grounded (`loc_6795C`) | Each frame: check Knuckles' X (via `_unkFAA4`). If within 8px and Knuckles' velocity direction matches `subtype bit 1`, delete self (Knuckles "picks up" the emerald). Otherwise draw. |

## Player Control Flow

| Phase | controlLocked | objectControlled | Super |
|-------|--------------|-----------------|-------|
| Plane flight (R0-R5) | true | true (0x53) | visual only (intro renders) |
| Transform (R6-R10) | true | true | palette cycling |
| Player released (R10 end) | false | false | true |
| Running right (R11-R13) | false | false | true |
| Explosion (R13) | true | false | false (disabled) |
| Knuckles sequence | true | false | false |
| Knuckles exits (R6) | false | false | false |

## Art and Palette Requirements

### Art Assets

| Asset | ROM Label | Compression | Destination |
|-------|-----------|-------------|-------------|
| Plane sprite | `ArtKosM_AIZIntroPlane` | KosinskiM | `ArtTile_AIZIntroPlane` |
| Emerald sprites | `ArtKosM_AIZIntroEmeralds` | KosinskiM | `ArtTile_AIZIntroEmeralds` |
| Wave sprites | `ArtTile_AIZIntroSprites` | Shared with intro tiles | Already loaded |
| Knuckles cutscene | Knuckles art + DPLC | TBD | `ArtTile_CutsceneKnux` |
| Super Sonic | Player art | Already loaded | Player tile base |

### Sprite Mappings

| Mapping | Used By |
|---------|---------|
| `Map_AIZIntroPlane` | Plane child |
| `Map_AIZIntroWaves` | Wave children |
| `Map_SuperSonic` | Intro object (frames 0x21, 0x22, 0xBA) |
| `Map_CutsceneKnux` | Knuckles + DPLC |

### Palettes

| Palette | Destination | Trigger |
|---------|-------------|---------|
| `PalCycle_SuperSonic` (60 bytes, 10 frames x 3 words) | Line 0, colors 2-4 | Cycling every 6 frames, range 0x24-0x36 |
| `Pal_CutsceneKnux` | Line 1 | Knuckles activation |
| `Pal_AIZIntroEmeralds` (32 bytes) | Line 4 | Explosion trigger |

### Art Loading Approach

Load assets directly in each object's init method using existing KosinskiM decompression. Find ROM addresses via RomOffsetFinder. Write decompressed tiles to pattern table at specific indices. Parse mappings from ROM into engine sprite structures. Targeted, not a general pipeline.

## Child Objects

### Plane Child (`loc_6777A`)

- Uses `Map_AIZIntroPlane`, `ArtTile_AIZIntroPlane`
- Init: `Swing_Setup1` for oscillation parameters
- Loads `ArtKosM_AIZIntroPlane` and `ArtKosM_AIZIntroEmeralds` via `Queue_Kos_Module`
- Spawns 2 emerald glow children (`ChildObjDat_67A62`)
- Update: `Swing_UpAndDown` oscillation, eventually spirals away offscreen

### Emerald Glow Children (`loc_67824`, `loc_67862`)

- 2 children spawned by the plane child
- Visual effect: glowing emeralds on the plane
- Follow parent position with offsets

### Wave Children (`loc_678A0`)

- Uses `Map_AIZIntroWaves`, `ArtTile_AIZIntroSprites`
- Spawned every 5 frames from routines 6-11
- Scroll left by parent's `$40` value each frame
- Animate via `byte_67A9B` animation script
- Self-delete when X < 0x60

## Prerequisites / New Infrastructure

### SwingMotion Helper

Port of `Swing_UpAndDown`. Stateless utility:
- Input: acceleration, current velocity, max velocity, direction flag
- Output: updated velocity, updated direction flag
- Oscillates: accelerate in current direction until max, then reverse direction

### S3K Super Sonic Palette Cycling

Fill in `Sonic3kSuperStateController`:
- Find `PalCycle_SuperSonic` ROM address
- Timer: 6 frames between advances
- Range: word offsets 0x24-0x36, wrap at >0x36 to 0x24
- Write 3 MD colors (6 bytes) to palette line 0, colors 2-4

### Set_IndexedVelocity Equivalent

Velocity lookup table for emerald scatter directions. 7 entries of X/Y velocity pairs, indexed by subtype.

## Implementation Order

1. **S3K Zone Event Framework** - `Sonic3kZoneEvents`, `Sonic3kAIZEvents`, wire dispatch in `Sonic3kLevelEventManager`
2. **Utility Infrastructure** - `SwingMotion`, `ObjWaitTimer` pattern, velocity table
3. **S3K Super Sonic Palette Cycling** - Fill `Sonic3kSuperStateController`, find ROM palette address
4. **Art Loading** - Find ROM addresses, parse mappings, load palettes
5. **Core Intro Object** - `AizPlaneIntroInstance` + plane/wave/glow children
6. **Emerald Scatter** - `AizEmeraldScatterInstance`
7. **Knuckles Cutscene** - `CutsceneKnucklesAiz1Instance` + rock child
8. **Integration** - Wire into level loading, write S3K_KNOWN_DISCREPANCIES.md, test

## New Files (~15)

| File | Package |
|------|---------|
| `Sonic3kZoneEvents.java` | `game.sonic3k.events` |
| `Sonic3kAIZEvents.java` | `game.sonic3k.events` |
| `AizPlaneIntroInstance.java` | `game.sonic3k.objects` |
| `AizIntroPlaneChild.java` | `game.sonic3k.objects` |
| `AizIntroWaveChild.java` | `game.sonic3k.objects` |
| `AizIntroEmeraldGlowChild.java` | `game.sonic3k.objects` |
| `AizEmeraldScatterInstance.java` | `game.sonic3k.objects` |
| `CutsceneKnucklesAiz1Instance.java` | `game.sonic3k.objects` |
| `CutsceneKnucklesRockChild.java` | `game.sonic3k.objects` |
| `SwingMotion.java` | `physics` or `util` |
| `S3K_KNOWN_DISCREPANCIES.md` | `docs/` |

## Modified Files

- `Sonic3kLevelEventManager.java` - Wire zone dispatch
- `Sonic3kSuperStateController.java` - Palette cycling implementation
- `Sonic3kBootstrapResolver.java` - Add FULL_INTRO mode
- `Sonic3kLoadBootstrap.java` - New enum value
- `Sonic3k.java` - Art preloading hook

## Discrepancies (to log in S3K_KNOWN_DISCREPANCIES.md)

1. **Intro object spawned by level event init** - ROM uses `SpawnLevelMainSprites`; we use `Sonic3kAIZEvents.init()`. Object exists from frame 1 either way.
2. **Obj_Wait reimplemented as explicit timer + callback fields** - Same timing, different internal structure.
3. **Art loaded immediately rather than via `Queue_Kos_Module`** - ROM queues for V-blank DMA; we decompress at init. Art present before first draw.
4. **Knuckles DPLC simplified to pre-loaded frames** - ROM transfers patterns per-frame; we pre-load all frames at init. Uses more pattern memory, visually identical.
