# Angel Island Zone Intro - Technical Documentation

This document describes how the AIZ Act 1 intro cutscene works in Sonic 3 & Knuckles, covering both the original ROM implementation (68000 assembly) and the Java reimplementation in this engine.

## Table of Contents

1. [Overview](#overview)
2. [ROM-Level Architecture](#rom-level-architecture)
   - [Level Loading & Init](#level-loading--init)
   - [Obj_AIZPlaneIntro State Machine](#obj_aizplaneintro-state-machine)
   - [Scroll Velocity & Camera System](#scroll-velocity--camera-system)
   - [Background Deformation (AIZ1_IntroDeform)](#background-deformation-aiz1_introdeform)
   - [Child Objects](#child-objects)
   - [Cutscene Knuckles](#cutscene-knuckles)
   - [Emerald Scatter](#emerald-scatter)
   - [Terrain Transition](#terrain-transition)
   - [Art & Palette Resources](#art--palette-resources)
3. [Java Reimplementation](#java-reimplementation)
   - [Bootstrap System](#bootstrap-system)
   - [AizPlaneIntroInstance (Master Object)](#aizplaneintroinstance-master-object)
   - [Scroll Handler (SwScrlAiz)](#scroll-handler-swscrlaiz)
   - [Child Object Hierarchy](#child-object-hierarchy)
   - [Art Loading Pipeline](#art-loading-pipeline)
   - [Terrain Swap System](#terrain-swap-system)
4. [Complete Timeline](#complete-timeline)
5. [Key Differences & Divergences](#key-differences--divergences)

---

## Overview

When starting a new game as Sonic in Sonic 3 & Knuckles, the first thing the player sees is a cinematic introduction to Angel Island Zone Act 1. This intro is unique in the game: it uses a custom level layout, custom scroll deformation, screen-space sprite rendering, and a multi-phase state machine that orchestrates the following narrative:

1. **Sonic arrives on a biplane** (the Tornado) as Super Sonic, flying from right to left across an ocean backdrop
2. **The plane descends**, bobs on the water, then Sonic/Tails lift off
3. **Super Sonic lands on the beach** and skids to a halt
4. **Super Sonic walks** right and left along the shore, showing off the Chaos Emeralds
5. **The camera scrolls** Sonic across the island, revealing the terrain
6. **Knuckles ambushes Sonic** — drops from above, punches the emeralds out of him, and collects them
7. **Knuckles leaves**, and the player gains control for normal Act 1 gameplay

The entire sequence runs within the normal level engine (not a separate game mode), using a special intro layout that transitions to the standard Act 1 layout mid-scroll.

---

## ROM-Level Architecture

### Level Loading & Init

The intro is gated by three conditions checked during `SpawnLevelMainSprites` (sonic3k.asm line 8111):

```asm
SpawnLevelMainSprites:
    ; ...
    cmpi.w  #0,(Current_zone_and_act).w     ; Zone 0, Act 0 (AIZ1)?
    bne.s   loc_6834
    cmpi.w  #2,(Player_mode).w              ; Not Knuckles mode?
    bhs.s   locret_6832
    move.l  #Obj_AIZPlaneIntro,(Dynamic_object_RAM+(object_size*2)).w
    clr.b   (Level_started_flag).w          ; Suppress HUD / camera lock
```

**Three conditions must ALL be true:**
1. Current zone/act = AIZ Act 1 (`Current_zone_and_act == 0x0000`)
2. Player mode < 2 (Sonic or Sonic+Tails, not Tails or Knuckles alone)
3. No checkpoint active (`Last_star_post_hit == 0`)

When active, `SpawnLevelMainSprites`:
- Writes `Obj_AIZPlaneIntro` into the third dynamic object slot
- Clears `Level_started_flag` to suppress HUD rendering and certain camera behaviors
- Loads PLC set 10 (intro-specific pattern load cues) instead of the normal character PLCs

The intro also uses a **separate level layout**. The ROM's `LevelLoadBlock` has a dedicated entry (index 26) for the AIZ1 intro, with different art and chunk pointers than standard AIZ1. The intro layout shows ocean and beach terrain with no foreground obstacles.

**Player start position** for the intro is hardcoded in the ROM:
```asm
; Level_FromSavedGame override:
move.w  #$40,(Player_1+x_pos).w    ; X = 0x40
move.w  #$420,(Player_1+y_pos).w   ; Y = 0x420
```

This places Sonic at the left edge of the intro layout, far below the visible area (hidden from camera).

### Obj_AIZPlaneIntro State Machine

The master intro object (`Obj_AIZPlaneIntro`, sonic3k.asm line 135464) uses **stride-2 routine dispatch** — the `routine` byte steps by 2 each transition (0x00, 0x02, 0x04, ..., 0x1A), indexing a word-sized branch table.

Each frame, the object:
1. Dispatches to the current routine handler
2. Calls `Sonic_Load_PLC` (dynamic pattern loading)
3. Calls `sub_67A08` (scroll velocity helper)
4. Calls `Draw_Sprite` (render the sprite)

#### Routine Table

| Routine | ROM Label | Duration | Description |
|---------|-----------|----------|-------------|
| 0x00 | `loc_674AC` | 1 frame | **Init**: Set position (0x60, 0x30), mapping_frame = 0xBA (Super Sonic standing), lock player (`object_control = $53`), set `Events_fg_1 = $E918` (-5864), timer = 0x40 |
| 0x02 | `loc_67514` | 64 frames | **Wait**: `Obj_Wait` countdown. On expiry: set x_vel=0x300, y_vel=0x600, spawn plane child via `CreateChild1_Normal` |
| 0x04 | `loc_67536` | Variable | **Descent**: `y_vel -= 0x18` each frame, `MoveSprite2`. When y_vel reaches 0: `Swing_Setup1` (maxVel=0xC0, accel=0x10, **scroll speed 8→16**) |
| 0x06 | `loc_67560` | 95 frames | **Swing+Wait**: `Swing_UpAndDown` + `MoveSprite2` + `Obj_Wait(0x5F)`. Scroll speed now 16 (from `Swing_Setup1`). On expiry: set x_vel=0x400, y_vel=-0x400, detach plane child |
| 0x08 | `loc_67594` | Variable | **Lift-off**: `x_vel -= 0x40`, `MoveSprite` (with gravity). When `y_pos >= 0x130`: land, zero y_vel |
| 0x0A | `loc_675C0` | Variable | **Ground decel**: `x_vel -= 0x40`, `MoveSprite2`. When `x_pos < 0x40`: set up Super Sonic visuals, signal plane child to walk left, set flash timer = 0x3F |
| 0x0C | `loc_67614` | 63 frames | **Super flash**: `$3A` countdown, palette cycling begins |
| 0x0E | `loc_67624` | Variable | **Walk right**: `x += 4/frame`, `sub_679B8` (palette anim), wave spawns. Until `x >= 0x200` |
| 0x10 | `loc_6764E` | 31 frames | **Wait**: `$3A` countdown, scroll speed increases to 0x0C |
| 0x12 | `loc_67674` | Variable | **Walk left**: `x -= 4/frame`. Until `x <= 0x120`. Scroll speed increases to 0x10 |
| 0x14 | `loc_676AC` | 31 frames | **Wait**: `$3A` countdown |
| 0x16 | `loc_676C6` | Variable | **Monitor Knux**: Wait until `Player_1.x_pos >= 0x918`. Spawn Knuckles via `CreateChild6_Simple` |
| 0x18 | `loc_676E8` | Variable | **Monitor approach**: Wait until `Player_1.x_pos >= 0x1240`. Adjust `y_pos -= 0x20` |
| 0x1A | `loc_67704` | Variable | **Explosion**: Wait until `Player_1.x_pos >= 0x13D0`. Release player (hurt state: y_vel=-0x400, x_vel=-0x200, anim=$1A), clear `Super_Sonic_Knux_flag`, load emerald palette, spawn 7 emerald scatter objects via `CreateChild6_Simple`, spawn emerald glow children, `Go_Delete_Sprite` |

#### Key ROM Fields

| Field | Offset | Purpose |
|-------|--------|---------|
| `routine` | `$24` | Routine index (stride-2) |
| `x_pos` | `$10` | Screen-space X (not world coordinates) |
| `y_pos` | `$14` | Screen-space Y |
| `x_vel` | `$18` | X velocity (subpixels, 0x100 = 1px/frame) |
| `y_vel` | `$1A` | Y velocity (subpixels) |
| `$2E` | — | Wait timer (Obj_Wait countdown) |
| `$34` | — | Callback pointer (Obj_Wait return address) |
| `$38` | — | Status bits (bit 2 = Super mode, bit 3 = plane detached) |
| `$3A` | — | Secondary timer (flash/wait countdowns) |
| `$40` | — | Scroll speed / swing accel (dual-purpose: starts at 8, `Swing_Setup1` sets to 16, later 12→16 for walk phases) |
| `mapping_frame` | `$22` | 0xBA = normal Sonic, 0x21/0x22 = Super Sonic (alternates per frame) |

### Scroll Velocity & Camera System

The intro uses a clever pseudo-scrolling mechanism (`sub_67A08`, sonic3k.asm line 135806):

```
Events_fg_1 starts at 0xE918 (signed: -5864)
Each frame: Events_fg_1 += scroll_speed ($40 field)
While Events_fg_1 < 0: BG parallax scrolls via introScrollOffset, player stays still
When Events_fg_1 >= 0: player.x_pos += scroll_speed, camera follows naturally
```

This creates the illusion of camera movement during the intro without actually moving the camera — the background scrolls via intro deformation while the player is stationary. Once the intro progresses far enough, the player starts physically moving rightward and the camera follows.

**Scroll speed progression:**

| Phase | Scroll Speed | Trigger | BG Rate |
|-------|-------------|---------|---------|
| Routines 0x00-0x04 | 8 px/frame | Init (`move.w #8,$40`) | 4 px/frame |
| Routines 0x06-0x0E | 16 px/frame | `Swing_Setup1` (`move.w #$10,$40`) | 8 px/frame |
| Routine 0x10-0x12 | 12 px/frame | Routine 0x10 exit (`move.w #$C,$40`) | 6 px/frame |
| Routine 0x14+ | 16 px/frame | Routine 0x12 exit (`move.w #$10,$40`) | 8 px/frame |

**Important:** `$40(a0)` is a **dual-purpose field**. `Swing_Setup1` writes `0x10` as the swing acceleration, but `sub_67A08` reads the same field as the scroll speed. This means the scroll speed doubles from 8→16 at the moment the descent ends and the swing begins — the ocean visibly speeds up. The later write of `0x0C` at routine 0x10 actually *decreases* speed briefly before restoring it to 0x10 at routine 0x12.

### Background Deformation (AIZ1_IntroDeform)

The intro uses `AIZ1_IntroDeform` (Screen Events.asm line 515) for BG parallax scrolling, distinct from the normal `AIZ1_Deform` used during gameplay.

**Intro deformation:**
- `Camera_Y_pos_BG_copy = Camera_Y_pos_copy` (1:1 vertical tracking)
- BG X scroll is derived from `Events_fg_1` (negative = still scrolling, positive = use camera X)
- The HScroll_table+$28 region stores 37 band values
- Each band scrolls at a slightly different rate, creating a parallax gradient from ocean to sky

**Deformation array** (`AIZ1_IntroDeformArray`):
```
$3E0, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, $7FFF
```
This means: the first 0x3E0 (992) scanlines are one band, then 36 bands of 4 scanlines each. The `$7FFF` terminates the array.

The `buildIntroBandValues()` function computes per-band horizontal offsets:
- If `source >> 1 >= 0x580`: all bands get the same offset (fully scrolled)
- Otherwise: band 0 = `source >> 1`, remaining bands interpolate from there toward 0x580 using `step = (source/2 - 0x580) << 16 >> 5`

When the intro transitions past camera X 0x1300, the deformation switches from `AIZ1_IntroDeform` to `AIZ1_Deform` (standard multi-layer parallax with water effects).

### Child Objects

The intro spawns several child objects:

#### Plane Child (loc_6777A)
- Uses `Map_AIZIntroPlane` / `ArtTile_AIZIntroPlane`
- Follows parent position with offset (-0x22, +0x2C) while attached
- Applies independent `Swing_UpAndDown` oscillation (accel=0x10, maxVel=0xC0)
- After parent routine 0x08: detaches, swings independently
- After parent routine 0x0A: walks left 4px/frame until x < 0x20, then `Go_Delete_Sprite`
- **ROM bug:** `height_pixels` is set incorrectly (`move.b #$20,width_pixels` instead of `height_pixels`). The `FixBugs` conditional corrects this.

#### Booster Flames (loc_67824, loc_67862)
Two booster flame children attached to the plane:
- Booster 1: offset (+0x38, +4), animation frames {2, 3, 4, 3, 2}
- Booster 2: offset (+0x18, +0x18), animation frames {5, 6}
- Both use `Animate_RawNoSST` + `Refresh_ChildPosition` + `Child_Draw_Sprite`

#### Wave Splashes (loc_678A0)
- Uses `Map_AIZIntroWaves` / `ArtTile_AIZIntroSprites`
- Spawned every 5 frames during routines 0x0E-0x16
- Each wave scrolls left by the parent's current scroll speed ($40 field)
- Animates through 6 frames (byte_67A9B)
- Self-deletes when x < 0x60

#### Emerald Glow (loc_67900)
Two glow children attached to the plane at offsets (-8, -12) and (+8, -12):
- Simple animated sprites showing emerald energy
- Follow the plane child position

### Cutscene Knuckles

`CutsceneKnux_AIZ1` (sonic3k.asm loc_61DBE) is spawned at routine 0x16 when `Player_1.x_pos >= 0x918`.

**Init:** Position = (0x1400, 0x440), mapping_frame = 8, spawns rock child object

**Phase flow:**

| Routine | Action |
|---------|--------|
| 0 | Init: spawn at (0x1400, 0x440), spawn rock child, fade music → Knuckles' theme |
| 2 | Wait for parent's status bit 7 (trigger signal from emerald explosion) |
| 4 | Fall: `MoveSprite` with gravity (0x38/frame), initial y_vel=-0x600, x_vel=0x80. Floor collision via `ObjCheckFloorDist` |
| 6 | Stand: countdown 0x7F (127) frames, then face left, set walk animation |
| 8 | Pace: walk left for 0x29 (41) frames at speed 0x600 subpixels, reverse, walk right for 0x29 frames. Collects grounded emeralds during this phase |
| 10 | Laugh: animate laugh for 0x3F (63) frames, then fade music → AIZ1 theme |
| 12 | Exit: walk right offscreen. When offscreen: unlock player controls, set `Level_started_flag = $91`, delete self |

**Rock child** (`loc_61F60`): Draws itself until parent is triggered, then increments mapping_frame (showing broken pieces) and calls `BreakObjectToPieces` to scatter fragment sprites with predetermined velocities.

### Emerald Scatter

Seven emerald objects are spawned at routine 0x1A with subtypes 0, 2, 4, 6, 8, 10, 12.

**Per-emerald velocity table (Set_IndexedVelocity):**

| Subtype | X Velocity | Y Velocity | Mapping Frame |
|---------|-----------|-----------|---------------|
| 0 | -0x0040 | -0x0700 | 0 |
| 2 | -0x0080 | -0x0700 | 1 |
| 4 | -0x0180 | -0x0700 | 2 |
| 6 | -0x0100 | -0x0700 | 3 |
| 8 | -0x0200 | -0x0700 | 4 |
| 10 | -0x0280 | -0x0700 | 5 |
| 12 | -0x0300 | -0x0700 | 6 |

All launch upward at the same speed but scatter leftward at different rates.

**Phases:**
1. **FALLING**: `MoveSprite` with gravity (0x38). Floor collision via `ObjCheckFloorDist`. Snap Y and transition on `d1 < 0`.
2. **GROUNDED**: Each frame, check proximity to Knuckles (within 8 pixels horizontally). Collection depends on Knuckles' velocity direction and `subtype bit 1`:
   - Bit 1 = 0: collected when Knuckles moves right
   - Bit 1 = 1: collected when Knuckles moves left

### Terrain Transition

At camera X = 0x1300, the `AIZ1_BackgroundEvent` routine detects the transition:

```asm
AIZ1BGE_Intro:
    tst.w   (Events_fg_5).w
    beq.s   loc_4FBA4
    tst.w   (Kos_decomp_queue_count).w    ; Wait for KosM decompression to finish
    bne.w   loc_4FBA4
    clr.w   (Events_fg_5).w
    jsr     (AIZ1_Deform).l               ; Switch to normal deformation
    ; ... set up normal BG drawing
    addq.w  #4,(Events_routine_bg).w      ; Advance BG routine
```

The terrain data is swapped mid-scroll:
- `Events_fg_5` is a flag set by the level event system when camera X crosses 0x1300-0x1400
- The intro's 8x8 tile patterns and 16x16 chunk data are overlaid with the standard AIZ1 data
- The Kosinski decompression queue is checked each frame until the swap data is fully decompressed
- Once complete, the BG deformation switches from `AIZ1_IntroDeform` to `AIZ1_Deform`

### Art & Palette Resources

| Resource | Format | ROM Label | VRAM Tile |
|----------|--------|-----------|-----------|
| Plane (Tornado) | KosinskiM | `ArtKosM_AIZIntroPlane` | `$0529` |
| Chaos Emeralds | KosinskiM | `ArtKosM_AIZIntroEmeralds` | `$05B1` |
| Intro sprites (waves) | Nemesis | `ArtNem_AIZIntroSprites` | `$03D1` |
| Cutscene Knuckles | Uncompressed | (address in constants) | Dynamically loaded via DPLC |
| Cork floor (rock) | Nemesis | `ArtNem_AIZCorkFloor` + `ArtNem_AIZCorkFloor2` | `$001C` |

**Mapping files:**
- `Map_AIZIntroPlane` — 12 frames (plane body, boosters, various states)
- `Map_AIZIntroEmeralds` — 7 frames (one per emerald color)
- `Map_AIZIntroWaves` — 6 frames (wave splash animation)
- `Map_CutsceneKnux` — Knuckles cutscene frames (with DPLC remapping)
- `Map_AIZCorkFloor` — 2 frames (intact rock, broken rock)

**Palettes:**
- `Pal_AIZIntro` — Intro-specific zone palette
- `Pal_AIZIntroEmeralds` — Emerald palette (loaded to line 4/index 3)
- `Pal_CutsceneKnux` — Knuckles palette (loaded to line 1)
- `PalCycle_SuperSonic` — Palette cycling data (10 entries x 6 bytes) for Super Sonic effect

**S3K mapping format** (differs from S2):
```
6 bytes per piece:
  byte 0:    yOffset (signed byte)
  byte 1:    size (width bits 3:2, height bits 1:0)
  bytes 2-3: tile word (priority|pal|vflip|hflip|tileIndex) — 16-bit BE
  bytes 4-5: xOffset (signed 16-bit BE)

vs S2 which has 8 bytes per piece (extra 2-byte 2P tile word)
```

---

## Java Reimplementation

### Bootstrap System

The Java reimplementation uses a **bootstrap resolver** pattern to determine whether the intro should play:

```
Sonic3kBootstrapResolver.resolve(zone, act)
  → Sonic3kLoadBootstrap { mode, introStartPosition }
```

**Mode values:**
- `NORMAL` — No intro for this zone/act
- `INTRO` — Play intro, use `introStartPosition` = {0x40, 0x420} for player spawn
- `SKIP_INTRO` — Zone has an intro but user has `S3K_SKIP_INTROS=true` or is playing as non-Sonic

**Config options** (in `config.json`):
- `S3K_SKIP_AIZ1_INTRO` / `S3K_SKIP_INTROS` — Boolean, skips all zone intros
- `MAIN_CHARACTER_CODE` — If not "sonic", intro is skipped

The bootstrap is computed during `Sonic3k.loadLevel()` and passed into the level event system and the level loader. When intro mode is active:
- The `LevelLoadBlock` index 26 (intro layout) is used instead of standard AIZ1
- The player is spawned at (0x40, 0x420)
- Level boundaries use `LEVEL_SIZES_AIZ1_INTRO_INDEX` (26)
- `Camera.setLevelStarted(false)` suppresses HUD/camera tracking

### AizPlaneIntroInstance (Master Object)

**File:** `src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizPlaneIntroInstance.java`

The master orchestrator is a single `AbstractObjectInstance` subclass that mirrors the ROM's stride-2 routine dispatch as a Java switch statement:

```java
switch (routine) {
    case 0  -> routine0Init(trackedPlayer);
    case 2  -> routine2Wait(trackedPlayer);
    case 4  -> routine4Descent(trackedPlayer);
    // ... through case 26
}
```

Each frame, after the routine handler, `scrollVelocity()` runs — the equivalent of `sub_67A08`:

```java
private void scrollVelocity(AbstractPlayableSprite player) {
    if (eventsFg1 < 0) {
        eventsFg1 += scrollSpeed;    // Still in pseudo-scroll phase
        introScrollOffset = eventsFg1;
        return;
    }
    introScrollOffset = 0;           // Gate reached: move player instead
    if (player != null) {
        player.setCentreX((short)(player.getCentreX() + scrollSpeed));
    }
}
```

**Static state** shared with the scroll handler:
- `introScrollOffset` — Current `Events_fg_1` value (drives BG parallax)
- `mainLevelPhaseActive` — Set when camera X >= 0x1400 (triggers terrain swap)
- `mainLevelTerrainSwapAttempted` — Prevents duplicate swap attempts

**Rendering:** The intro object renders as a `PlayerSpriteRenderer` in screen-space coordinates. The ROM uses `render_flags` without the on-screen bit, meaning positions are relative to screen origin (128,128 in VDP terms). The Java code converts this by adding `camera.getX() - 128` / `camera.getY() - 128` to the screen-space coordinates.

**Subpixel motion** is handled by `SubpixelMotion`, a utility class that implements ROM-accurate 16:8 fixed-point position updates:
- `moveSprite2()` — X and Y velocity applied, no gravity
- `moveSprite()` — Same, but adds gravity (0x38) to Y velocity each frame
- `moveX()` — X velocity only

### Scroll Handler (SwScrlAiz)

**File:** `src/main/java/uk/co/jamesj999/sonic/game/sonic3k/scroll/SwScrlAiz.java`

Implements `ZoneScrollHandler` for AIZ, with two modes:

**Intro mode** (when `!levelStarted && !mainLevelPhaseActive`):
- Reads `AizPlaneIntroInstance.getIntroScrollOffset()` for BG calculation
- Calls `buildIntroBandValues()` — port of ROM's HScroll_table+$28 calculation
- Calls `writeIntroScroll()` — applies `AIZ1_IntroDeformArray` segment heights to fill per-scanline scroll buffer
- Vertical BG scroll = `cameraY` (1:1 tracking)

**Normal mode** (post-intro):
- Simple half-speed horizontal/vertical parallax
- `bgScroll = fgScroll >> 1`, `vscrollFactorBG = cameraY >> 1`

### Child Object Hierarchy

```
AizPlaneIntroInstance (master state machine)
├── AizIntroPlaneChild (biplane sprite)
│   ├── AizIntroBoosterChild × 2 (flame animations)
│   └── AizIntroEmeraldGlowChild × 2 (emerald energy)
├── AizIntroWaveChild × N (water splashes, spawned periodically)
├── CutsceneKnucklesAiz1Instance (Knuckles cutscene)
│   ├── CutsceneKnucklesRockChild (breakable rock)
│   │   └── AizRockFragmentChild × 12 (scattered pieces)
│   └── SongFadeTransitionInstance (music fade/transition)
└── AizEmeraldScatterInstance × 7 (scattered emeralds)
```

Each child has its own `update()` / `appendRenderCommands()` cycle. Children reference their parent for state queries (e.g., `parent.isPlaneDetached()`, `parent.getScrollSpeed()`).

**Swing oscillation** is shared between the master and the plane child using `SwingMotion.update()`:
```java
SwingMotion.Result result = SwingMotion.update(
    SWING_ACCELERATION, swingVelocity, SWING_MAX_VELOCITY, swingDirectionDown);
swingVelocity = result.velocity();
swingDirectionDown = result.directionDown();
```

### Art Loading Pipeline

**File:** `src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizIntroArtLoader.java`

All intro art is loaded once at init and cached in static fields. The loader handles:

1. **Art decompression:** KosinskiM (plane, emeralds), Nemesis (waves, cork floor), uncompressed (Knuckles)
2. **Mapping parsing:** S3K format (6 bytes/piece) via `loadS3kMappingFrames()`
3. **DPLC remapping:** Knuckles uses dynamic pattern loading — VRAM-relative tile indices are remapped to absolute art array indices via `applyDplcRemap()`
4. **Palette application:** Knuckles palette → line 1, emerald palette → line 3, Super Sonic cycle → line 0 (colors 2-4)
5. **Renderer caching:** `PatternSpriteRenderer` objects are lazily created and GPU patterns uploaded on first render

**S3K DPLC format** (differs from player DPLC):
```
Object DPLC: startTile in upper 12 bits, (count-1) in lower 4 bits
Player DPLC: (count-1) in upper 4 bits, startTile in lower 12 bits
```

The `AizIntroPaletteCycler` handles Super Sonic's palette cycling effect:
- Reads 3 Mega Drive colors from `PalCycle_SuperSonic` data
- Writes them to palette line 0, colors 2-4
- Cycles through entries at 6-frame intervals
- Alternates mapping frame between 0x21 and 0x22 per frame (V-blank parity)

### Terrain Swap System

**File:** `src/main/java/uk/co/jamesj999/sonic/game/sonic3k/objects/AizIntroTerrainSwap.java`

When camera X reaches 0x1400, the intro terrain is replaced with standard AIZ1 terrain:

```java
static boolean applyMainLevelOverlays() {
    // Load overlay data from ROM (secondary art/blocks from LevelLoadBlock entry 26)
    OverlayData overlay = loadOverlayData();

    // Apply chunk overlay (16x16 blocks) at offset = size of primary chunks
    sonic3kLevel.applyChunkOverlay(overlay.mainLevelBlocks16x16(), overlay.chunkOverlayOffset());

    // Apply pattern overlay (8x8 tiles) at offset = size of primary patterns
    sonic3kLevel.applyPatternOverlay(overlay.mainLevelTiles8x8(), overlay.patternOverlayOffset());

    // Swap to pre-built tilemaps or invalidate all tilemaps for rebuild
    levelManager.swapToPrebuiltTilemaps() || levelManager.invalidateAllTilemaps();
}
```

**Optimization:** The overlay data is pre-decompressed during level load (`preloadOverlayData()`), and transition tilemaps are pre-computed (`precomputeTransitionTilemaps()`). This moves the expensive Kosinski decompression and tilemap rebuild from the transition frame to level load time.

The overlay data comes from the `LevelLoadBlock`:
- Entry 0 (standard AIZ1): provides the primary art/blocks base sizes
- Entry 26 (intro): provides the secondary art/blocks pointers → these are the "main level" overlays

---

## Complete Timeline

| Frame Range | Routine | What Happens |
|-------------|---------|-------------|
| 0 | 0x00 | Init: lock player, load art, set Events_fg_1 = -5864 |
| 1-64 | 0x02 | Wait 64 frames. BG scrolling begins (8px/frame into Events_fg_1) |
| 65 | 0x02→0x04 | Spawn plane child. Set velocity (0x300, 0x600). Plane descends from upper-right |
| 66-~90 | 0x04 | Descent: y_vel decreases by 0x18/frame (25 frames to reach 0). Scroll speed = 8 |
| ~91 | 0x04→0x06 | y_vel=0: `Swing_Setup1` writes 0x10 to $40 → **scroll speed 8→16**, ocean BG visibly speeds up |
| ~91-~186 | 0x06 | Swing+wait: plane bobs on water for 95 frames. Scroll speed = 16 |
| ~187 | 0x06→0x08 | Lift off: x_vel=0x400, y_vel=-0x400, plane detaches |
| ~187-~210 | 0x08 | Lift off arc: x_vel decreases by 0x40/frame, gravity pulls down. Lands at y=0x130 |
| ~211-~225 | 0x0A | Ground decel: slides left. Plane walks off-screen left |
| ~226 | 0x0A→0x0C | Super Sonic setup: palette cycling begins |
| ~226-~289 | 0x0C | Flash wait: 63 frames of palette cycling |
| ~290-~370 | 0x0E | Walk right to x=0x200, spawning waves every 5 frames |
| ~371-~402 | 0x10 | Wait 31 frames. Scroll speed → 12 |
| ~403-~440 | 0x12 | Walk left to x=0x120. Scroll speed → 16 |
| ~441-~472 | 0x14 | Wait 31 frames |
| ~473+ | 0x16 | Monitor: wait for player X >= 0x918 (depends on scroll speed) |
| Variable | 0x16→0x18 | Spawn Knuckles at (0x1400, 0x440) |
| Variable | 0x18 | Monitor: wait for player X >= 0x1240 |
| Variable | 0x1A | Monitor: wait for player X >= 0x13D0 |
| Variable | 0x1A | **EXPLOSION**: Release player (hurt state), spawn 7 emeralds, trigger Knuckles, delete self |
| +0 | Knux r2→r4 | Knuckles falls from above |
| +~20 | Knux r4→r6 | Knuckles lands, stands for 127 frames |
| +~147 | Knux r6→r8 | Knuckles paces left (41 frames), then right (41 frames), collecting emeralds |
| +~229 | Knux r8→r10 | Knuckles laughs for 63 frames, music fades to AIZ1 theme |
| +~292 | Knux r10→r12 | Knuckles walks right offscreen |
| +Variable | Knux r12 | Knuckles exits: unlock controls, Level_started_flag = 0x91, player gains control |

**Total estimated duration:** ~800-1000 frames (13-17 seconds), depending on scroll speed accumulation.

---

## Key Differences & Divergences

| Aspect | ROM | Java |
|--------|-----|------|
| **Coordinate space** | Screen-space (VDP +128 bias) | World-space with camera offset correction |
| **Object spawning** | `CreateChild1_Normal` / `CreateChild6_Simple` (hardcoded slots) | `spawnDynamicObject()` (dynamic list) |
| **Player lock** | `object_control = $53` | `setControlLocked(true)` + `setObjectControlled(true)` + `setHidden(true)` |
| **Palette cycling** | Direct VDP palette RAM writes | `GraphicsManager.cachePaletteTexture()` |
| **Terrain swap** | KosM decompression queue + `Events_fg_5` flag | Pre-decompressed + precomputed tilemaps for instant swap |
| **DPLC** | Runtime VRAM pattern loading per frame | Full art pre-loaded, tile indices remapped once at load time |
| **Music** | `PlaySound` / `Obj_Song_Fade_ToLevelMusic` | `SongFadeTransitionInstance` (spawned as independent timer object) |
| **Title card** | Spawned by Knuckles exit routine | Not yet implemented (TODO) |
| **2P mode gate** | `cmpi.w #2,(Player_mode).w / bhs.s skip` | `Sonic3kBootstrapResolver.resolve()` checks `MAIN_CHARACTER_CODE` config |
| **Bug: plane height_pixels** | `move.b #$20,width_pixels(a0)` (overwrites width instead of setting height) | Correct implementation (separate width/height fields) |
