# AIZ1 Real Miniboss, Signpost & Defeat Flow

## Overview

Implement the complete end-of-AIZ-Act-1 experience: the real miniboss fight (Obj_AIZMiniboss, ID 0x91) with its Knuckles-only napalm attack, the S3K signpost (Obj_EndSign) with bump-from-below and hidden monitor reveal, the reusable boss defeat-to-signpost flow (Obj_EndSignControl), and a stubbed results screen.

### Three AIZ Boss Encounters (Context)

| # | Object | ID | Location | Post-defeat |
|---|--------|----|----------|-------------|
| a | `Obj_AIZMinibossCutscene` | 0x90 | Mid-AIZ1 (X=0x3020) | Flies away, triggers fire transition |
| b | `Obj_AIZMiniboss` | 0x91 | End-of-AIZ1 (dynamically spawned by AIZ2 resize) | **Signpost** via `Obj_EndSignControl` |
| c | `Obj_AIZEndBoss` | 0x92 | End-of-AIZ2 (layout-placed) | Egg capsule |

This spec covers (b) — the real miniboss. (a) is already implemented as `AizMinibossCutsceneInstance`. (c) is out of scope.

## 0. New Game State Flags

Two new flags are needed, matching ROM variables that don't yet exist in the codebase. Add to `GameStateManager` (via `GameServices.gameState()`):

- **`endOfLevelActive`** (ROM: `_unkFAA8`) — `boolean`, initially `false`. Set `true` by defeat flow when end-of-level sequence begins. Cleared by results screen when tally/transition is done. Polled by defeat flow Phase 3 and by `Obj_EndSignControlAwaitStart` equivalent.
- **`endOfLevelFlag`** (ROM: `End_of_level_flag`) — `boolean`, initially `false`. Set `true` by results screen to trigger act transition. Polled by defeat flow Phase 4. Reset on level load.

Both flags are reset to `false` in `GameStateManager.initLevel()`.

## 0b. Known Limitation: PlayerCharacter Hardcoded

`Sonic3kLevelEventManager.getPlayerCharacter()` currently returns `SONIC_AND_TAILS` always. The Knuckles napalm path and Knuckles-specific signpost animation are correctly gated but won't trigger until character selection is implemented. This is acceptable — the code paths exist and are testable via unit tests with mocked character.

## 1. Knuckles Napalm Attack (AizMinibossInstance Extension)

### ROM Reference

At `loc_68ADE` (line 137291 in sonic3k.asm), after the swing phase callback:
- Sonic: bit 1 of `$38` stays clear — napalm children remain idle
- Knuckles (`character_id == 2`): bit 1 is set, activating napalm children

### New Classes

**`AizMinibossNapalmController`** (extends `AbstractBossChild`)
- One per boss, polls parent's custom flag bit 1 (`$38`) to activate
- On activation: delay based on subtype, then spawn visual fire child + napalm projectile
- Checks parent defeated status to self-delete

**`AizMinibossNapalmProjectile`** (extends `AbstractObjectInstance`, implements `TouchResponseProvider`)
- Lifecycle:
  1. Launch upward: `y_vel = -0x400`, play `Sonic3kSfx.PROJECTILE` (0x4D)
  2. After delay: reposition at `Camera_Y - 0x20` (top of screen), set `y_vel` downward
  3. Drop toward player: play `Sonic3kSfx.MISSILE_THROW` (0x51)
  4. Floor collision: play `Sonic3kSfx.MISSILE_EXPLODE` (0x4E), spawn 7 explosion children
- Has collision flags (hurts player) — implements `TouchResponseProvider` directly since it's independent of the boss parent
- `shield_reaction` bit 4 set (fire shield interaction)
- Self-deletes after 0x9F frames

### Integration

- In `AizMinibossInstance`, after swing phase callback, check `PlayerCharacter == KNUCKLES`
- If Knuckles: set custom flag bit 1 on `$38`
- Spawn napalm controller children during init (they stay idle until bit 1 activates them)
- Camera trigger threshold: 0x10C0 for Knuckles vs 0x10E0 for Sonic

### Regression Safety

- `AizMinibossCutsceneInstance` (0x90) is a completely separate class — unaffected
- For Sonic path in `AizMinibossInstance`: bit 1 never set, napalm children remain idle
- Existing flame sweep + swing attacks are unchanged

## 2. Screen Lock & Dynamic Spawn (AIZ2 Resize)

### ROM Reference

`AIZ2_SonicResize2` (line 39076-39080): spawns `Obj_AIZMiniboss` when camera reaches threshold.
`AIZ2_KnuxResize2` (line 39187-39191): same for Knuckles at different position.

### Spawn Parameters

| Character | Camera Trigger | Boss X | Boss Y |
|-----------|---------------|--------|--------|
| Sonic | >= 0x0F50 | 0x11F0 | 0x0289 |
| Knuckles | >= 0x1040 | 0x11D0 | 0x0420 |

### Screen Lock

When camera reaches trigger threshold:
- `Camera.minX = Camera.maxX = triggerX` (matching `loc_68A6E` pattern)
- `Boss_flag = true` via `setBossFlag(true)`
- Fade to `mus_Miniboss`

### Implementation

New resize phase in `Sonic3kAIZEvents` — after fire transition completes and `Current_zone_and_act` has flipped to AIZ2, a new phase watches camera X and spawns the boss dynamically via `ObjectManager`.

### Post-defeat Unlock

Gradually increase `Camera.minX` and `Camera.maxX` by 2/frame until reaching signpost area. Clear `Boss_flag`.

## 3. S3K Signpost (Obj_EndSign)

### ROM Reference

`Obj_EndSign` at sonic3k.asm line 176110 (also s3.asm line 100771). 5-routine state machine.

### ROM Addresses

| Asset | Address | Size | Notes |
|-------|---------|------|-------|
| `ArtUnc_EndSigns` | 0x0DCC76 | 3328 bytes | Uncompressed, 104 tiles |
| `ArtNem_SignpostStub` | 0x0DD976 | 26 bytes (compressed) | Nemesis, 2 tiles |
| `Map_EndSigns` | 0x083B9E | 94 bytes | 7 frames |
| `DPLC_EndSigns` | 0x083B6C | 50 bytes | 7 entries |
| `Map_SignpostStub` | 0x083BFC | 10 bytes | 1 frame |

### VRAM Tile Destinations

- `ArtTile_EndSigns = 0x04AC`
- `ArtTile_SignpostStub = 0x069E`
- `ArtTile_Monitors = 0x04C4`

### New Class: `S3kSignpostInstance`

**State Machine (5 routines):**

**INIT:**
- Load attributes: mappings=`Map_EndSigns`, art_tile=0x04AC, priority=0x300, dimensions 0x18x0x10
- Match player's high-priority render bit
- Store own address in shared `Signpost_addr` reference (so hidden monitors can find it)
- Set collision radii: x_radius=0x18, y_radius=0x1E
- Y position = `Camera_Y - 0x20` (above screen top)
- Select animation: `AniRaw_EndSigns2` for Knuckles, `AniRaw_EndSigns1` otherwise
- Play `sfx_Signpost`
- Create `S3kSignpostStubChild` at offset (0, 0x18)

**FALLING:**
- Every 4 frames: spawn `S3kSignpostSparkleChild`
- Check player bump from below (see Bump Mechanic below)
- Apply gravity: `y_vel += 0x0C` each frame
- Move via `MoveSprite2`
- Check wall bounce (see Wall Bounce below)
- Animate spin via `Animate_Raw`
- Cannot land until `y_pos >= Camera_Y + 0x50` AND `y_vel > 0`
- On floor contact (`ObjCheckFloorDist` returns negative):
  - Snap to floor
  - Set `landed` flag (bit 0 of `$38`)
  - Set 0x40 frame post-landing timer

**LANDED:**
- Continue spin animation for 0x40 frames
- If `landed` flag cleared (by hidden monitor): bounce back up (`y_vel = -0x200`), return to FALLING with 0x20 frame cooldown
- When timer expires:
  - Show character face frame: `{0, 0, 1, 2}[Player_mode]` where `PlayerCharacter` ordinal is 0-indexed (SONIC_AND_TAILS=0/Sonic, SONIC_ALONE=1/Sonic, TAILS_ALONE=2/Tails, KNUCKLES=3/Knuckles)
  - Set `resultsReady` flag
  - Lock P2 controls
  - Zero velocity

**RESULTS:**
- Wait for Player 1 to be on ground
- Spawn `S3kLevelResultsInstance` (stub)

**AFTER:**
- Range check / cleanup / delete

### Spin Animation (ROM-accurate)

`AniRaw_EndSigns1` (non-Knuckles): `speed=1, frames: 0,4,5,6, 1,4,5,6, 3,4,5,6, $FC (loop)`

Decoded: Sonic → spin → edge → spin_flip → Tails → spin → edge → spin_flip → Eggman → spin → edge → spin_flip → loop

`AniRaw_EndSigns2` (Knuckles): `speed=1, frames: 1,4,5,6, 2,4,5,6, 3,4,5,6, $FC (loop)`

Decoded: Tails → spin → edge → spin_flip → Knuckles → spin → edge → spin_flip → Eggman → spin → edge → spin_flip → loop

### Mapping Frame Definitions (Map_EndSigns)

| Frame | Content | Pieces |
|-------|---------|--------|
| 0 | Sonic face | 2 pieces, 4x3 each at x=-24 and x=0 |
| 1 | Tails face | 2 pieces, same layout |
| 2 | Knuckles face | 2 pieces, same layout |
| 3 | Eggman face | 2 pieces, second has h-flip |
| 4 | Full sign spin | 1 piece, 4x4 at x=-16 |
| 5 | Thin edge | 1 piece, 1x2 at x=-4 |
| 6 | Full sign reverse | 1 piece, 4x4 with h-flip at x=-16 |

### Bump From Below Mechanic

`EndSign_CheckPlayerHit` (line 176342):
- 0x20-frame cooldown between bumps
- Range check: `{-0x20, 0x40, -0x18, 0x30}` relative to signpost (32px wide, 48px tall)
- Checks both Player 1 and Player 2 independently
- Player must be **jumping** (animation == 2) AND **moving upward** (`y_vel < 0`)
- On bump:
  - `x_vel = (signpost_x - player_x) * 16` (if centered: 8)
  - `y_vel = -0x200`
  - Play `sfx_Signpost`
  - +100 points with score popup (`Obj_EnemyScore`)
  - 0x20 frame cooldown

### Wall Bounce

- Moving right: bounce off `Camera_X + 0x128` (296px = screen width 320 - 24px margin) and right wall collision
- Moving left: bounce off `Camera_X + 0x18` (24px left margin) and left wall collision
- On collision: negate `x_vel`
- Note: These are VDP screen-relative offsets. The engine uses direct screen coordinates (no VDP+128 adjustment needed).

### Child Objects

**`S3kSignpostStubChild`** — wooden post below sign
- Single frame from `Map_SignpostStub`, art_tile=0x069E
- Position offset (0, 0x18) from parent
- Pure visual, no collision

**`S3kSignpostSparkleChild`** — ring-art sparkles
- Reuses ring mappings and `ArtTile_Ring`
- Animation: `AniRaw_SignpostSparkle` = `{1, 4,5,6,7, 0xFC}`
- Spawned every 4 frames during FALLING state
- Self-destroys when animation complete

## 4. Hidden Monitor (Obj_HiddenMonitor)

### ROM Reference

`Obj_HiddenMonitor` at sonic3k.asm line 176030. Object ID **0x80** in both zone sets (S3KL and SKL). Note: 0xC5 is `LBZMinibossBoxKnux` in S3KL — do NOT register hidden monitor there.

### New Class: `S3kHiddenMonitorInstance`

**Init:**
- Load attributes: `Map_Monitor` mappings, `collision_flags = 0x46`
- Back up subtype (monitor contents type)

**Main loop (every frame):**
1. Is `Signpost_addr` set? (a signpost exists)
2. Is the object at that address still an `S3kSignpostInstance`?
3. Has `landed` flag (bit 0 of `$38`) been set on the signpost?
4. If not yet landed: continue waiting
5. If landed, check range: `{-0x0E, 0x1C, -0x80, 0xC0}` relative to hidden monitor position

**In range:**
- Play `sfx_BubbleAttack` (0x44)
- Clear signpost's `landed` flag (causes signpost to bounce back up)
- Transform self into normal `MonitorInstance` at routine 2 (break state)
- Set `y_vel = -0x500` (pop upward)
- Set `$3C = 4` (monitor subtype/contents)

**Out of range:**
- Play `sfx_GroundSlide` (0x7E)
- Become inert (transition to on-screen test / self-destruct)

### Registration

Register in `Sonic3kObjectRegistry`:
- 0x80 (both zone sets) -> `S3kHiddenMonitorInstance` (currently named "HiddenMonitor" in both S3KL and SKL name tables — add factory)

## 5. Boss Defeat Flow (S3kBossDefeatSignpostFlow)

### ROM Reference

`Obj_EndSignControl` at sonic3k.asm line 180372. Reused by all S3K minibosses that end with a signpost.

### New Class: `S3kBossDefeatSignpostFlow` (extends `AbstractObjectInstance`)

Spawned dynamically via `ObjectManager.addDynamicObject()`, matching the ROM pattern where `Obj_EndSignControl` is a standalone object. Gets `update()` calls from the normal object update loop.

Constructor accepts:
- `zoneCleanupCallback: Runnable` — zone-specific palette/art restoration
- Reference to the boss instance (for position context)

**Phase 1: WAIT_FADE** (119 frames = 0x77)
- Set `GameServices.gameState().setEndOfLevelActive(true)` (ROM: `_unkFAA8`)
- Boss explosions continue (handled by boss instance separately — boss runs its own defeat timer)
- Music fading in progress
- Timer counts down from 0x77

Note: The existing `AizMinibossInstance.DEFEAT_TIME = 0x90` (144 frames) is longer than this 119-frame wait. The defeat flow's Phase 1 timer and the boss's explosion timer run concurrently. The signpost spawns after 119 frames even while explosions may still be running for 25 more frames. This matches the ROM behavior — `Wait_FadeToLevelMusic` timer (0x77) runs independently from the boss's explosion animation.

**Phase 2: SPAWN_SIGNPOST**
- Clear `Boss_flag`
- Spawn `S3kSignpostInstance`
- Load `PLC_EndSignStuff` (ArtNem_SignpostStub + ArtNem_Monitors)
- Call `zoneCleanupCallback` — for AIZ1: restore palette line 2 from `Pal_AIZ` (0x0A8B7C)

**Phase 3: AWAIT_RESULTS**
- Poll `GameServices.gameState().isEndOfLevelActive()` until false (cleared by results screen stub)
- Restore player control

**Phase 4: AWAIT_ACT_TRANSITION**
- Poll `GameServices.gameState().isEndOfLevelFlag()` until true
- Call `Change_Act2Sizes` (update level boundaries for act 2)
- Clean up and delete self

### Integration with AizMinibossInstance

- `AizMinibossInstance.onDefeatStarted()` spawns `S3kBossDefeatSignpostFlow` as a dynamic object
- The existing defeat timer + explosion logic in `updateDefeated()` continues running — it handles the visual explosions
- The defeat flow object runs concurrently, handling the signpost spawn timing
- The current `setDestroyed(true)` at the end of `updateDefeated()` should be replaced with a no-op (the boss stays alive but invisible while the defeat flow runs, and is cleaned up when the flow completes)
- AIZ1 zone cleanup lambda: restore palette line 2 from `Pal_AIZ`

### AfterBoss_Cleanup Zone Table (for future zones)

| Zone | Cleanup Action |
|------|---------------|
| AIZ1 | Restore palette line 2 from `Pal_AIZ` |
| AIZ2 | Load AIZ fire palette + PLCs |
| HCZ | Nothing |
| MGZ | Load monitors + spikes/springs PLCs |
| ICZ2 | Load ICZ2 palette |
| MHZ | Load MHZ2 palette |
| Others | Nothing |

## 6. Results Screen (Stub)

### New Class: `S3kLevelResultsInstance`

Minimal stub:
- Spawned by signpost when player lands on ground
- Waits ~60 frames
- Sets `GameServices.gameState().setEndOfLevelActive(false)` (signals defeat flow that results are done)
- Sets `GameServices.gameState().setEndOfLevelFlag(true)` (triggers act transition)
- No art, no tally, no bonus calculation — future work

## 7. PLC & Art Loading

### PLC_EndSignStuff

| Entry | Asset | VRAM Tile |
|-------|-------|-----------|
| 1 | `ArtNem_SignpostStub` (0x0DD976) | 0x069E |
| 2 | `ArtNem_Monitors` (0x190F4A) | 0x04C4 |

Register in `Sonic3kPlcArtRegistry`.

### Signpost Art (DPLC-based)

The signpost uses uncompressed art (`ArtUnc_EndSigns` at 0x0DCC76, 3328 bytes) with per-frame DPLC (`DPLC_EndSigns` at 0x083B6C). Each frame only loads the tiles it needs via `Perform_DPLC`.

## 8. Constants & Registry Changes

### Sonic3kConstants.java (new entries)

```java
// Signpost
public static final int ART_UNC_END_SIGNS_ADDR = 0x0DCC76;
public static final int ART_UNC_END_SIGNS_SIZE = 3328;
public static final int ART_NEM_SIGNPOST_STUB_ADDR = 0x0DD976;
public static final int MAP_END_SIGNS_ADDR = 0x083B9E;
public static final int DPLC_END_SIGNS_ADDR = 0x083B6C;
public static final int MAP_SIGNPOST_STUB_ADDR = 0x083BFC;
public static final int ART_TILE_END_SIGNS = 0x04AC;
public static final int ART_TILE_SIGNPOST_STUB = 0x069E;

// AIZ palette (for AfterBoss_Cleanup)
public static final int PAL_AIZ_ADDR = 0x0A8B7C;
public static final int PAL_AIZ_SIZE = 96;
```

### Sonic3kObjectIds.java

```java
public static final int HIDDEN_MONITOR = 0x80; // Both zone sets
```

Also add:
```java
// Monitors art (for PLC_EndSignStuff)
public static final int ART_NEM_MONITORS_ADDR = 0x190F4A;
```

### Sonic3kObjectRegistry.java

- Register 0x80 (both zone sets) -> `S3kHiddenMonitorInstance` factory

(Signpost is not in the object pointer table — it's dynamically spawned, no registration needed.)

## 9. File Summary

### New Files (8)

| File | Package | Purpose |
|------|---------|---------|
| `AizMinibossNapalmController.java` | `game.sonic3k.objects` | Knuckles napalm child controller |
| `AizMinibossNapalmProjectile.java` | `game.sonic3k.objects` | Fireball projectile |
| `S3kBossDefeatSignpostFlow.java` | `game.sonic3k.objects` | Reusable defeat-to-signpost sequence |
| `S3kSignpostInstance.java` | `game.sonic3k.objects` | Falling/spinning signpost |
| `S3kSignpostStubChild.java` | `game.sonic3k.objects` | Wooden post child |
| `S3kSignpostSparkleChild.java` | `game.sonic3k.objects` | Ring-art sparkle child |
| `S3kHiddenMonitorInstance.java` | `game.sonic3k.objects` | Hidden monitor (reveals on signpost land) |
| `S3kLevelResultsInstance.java` | `game.sonic3k.objects` | Stub results screen |

### Modified Files (6)

| File | Change |
|------|--------|
| `AizMinibossInstance.java` | Add Knuckles napalm gate + character-dependent trigger threshold, wire `S3kBossDefeatSignpostFlow` on defeat |
| `Sonic3kAIZEvents.java` | Add AIZ2 resize phase for dynamic boss spawn + screen lock |
| `Sonic3kObjectRegistry.java` | Register 0x80 -> `S3kHiddenMonitorInstance` |
| `GameStateManager.java` | Add `endOfLevelActive` and `endOfLevelFlag` boolean fields |
| `Sonic3kConstants.java` | Add signpost/palette ROM addresses |
| `Sonic3kObjectIds.java` | Add `HIDDEN_MONITOR` constant |
| `Sonic3kPlcArtRegistry.java` | Add `PLC_EndSignStuff` entries |

## 10. End-to-End Flow

```
Camera reaches 0x0F50 (Sonic) / 0x1040 (Knuckles)
  -> AIZ2 resize spawns AizMinibossInstance dynamically
  -> Screen locks (Camera.minX = Camera.maxX)
  -> Boss_flag = true, music fades to mus_Miniboss
  -> Player fights boss (6 hits, flame sweep; + napalm for Knuckles)
  -> Boss defeated
  -> S3kBossDefeatSignpostFlow starts
    -> Phase 1 (119 frames): explosions, music fade
    -> Phase 2: clear Boss_flag, spawn signpost, load PLCs, restore AIZ palette line 2
    -> Signpost falls from top of screen, spinning
      -> Hidden monitors check range on landing
        -> In range: sfx_BubbleAttack, transform to monitor, clear signpost landed flag
        -> Out of range: sfx_GroundSlide, become inert
      -> Signpost bounces if landed flag cleared by hidden monitor
      -> Player can bump signpost from below (+100 pts, sfx_Signpost)
      -> Signpost lands, spins 64 more frames, shows character face
    -> Phase 3: signpost spawns S3kLevelResultsInstance (stub)
      -> Stub waits 60 frames, clears endOfLevelActive, sets endOfLevelFlag
    -> Phase 4: act transition triggered
```

## 11. SFX Mapping

All SFX used in this spec mapped to `Sonic3kSfx` enum values:

| ROM Label | Enum | Hex ID | Used By |
|-----------|------|--------|---------|
| `sfx_Signpost` | `SIGNPOST` | 0xB8 | Signpost init, bump from below |
| `sfx_Projectile` | `PROJECTILE` | 0x4D | Napalm launch upward |
| `sfx_MissileThrow` | `MISSILE_THROW` | 0x51 | Napalm drop toward player |
| `sfx_MissileExplode` | `MISSILE_EXPLODE` | 0x4E | Napalm floor impact |
| `sfx_BubbleAttack` | `BUBBLE_ATTACK` | 0x44 | Hidden monitor reveal (in range) |
| `sfx_GroundSlide` | `GROUND_SLIDE` | 0x7E | Hidden monitor fail (out of range) |
| `sfx_BossHit` | `BOSS_HIT` | 0x6E | Boss hit (existing) |

## 12. Signpost DPLC Integration

The signpost art uses DPLC (Dynamic Pattern Load Cues) — per-frame tile loading from uncompressed art. The existing `Sonic3kPlcArtRegistry` has `StandaloneArtEntry` with a `dplcAddr` field. The signpost should use this system:

1. Register `ArtUnc_EndSigns` as a standalone art entry with `dplcAddr = 0x083B6C`
2. Each frame change triggers `Perform_DPLC`: read the DPLC entry for the current frame, DMA the required tiles from the uncompressed art source to VRAM at `ArtTile_EndSigns`
3. This is more ROM-accurate than loading all 104 tiles at once and avoids VRAM pressure
