# S3K Bonus Stage Framework & Gumball Machine Implementation

**Date:** 2026-04-04
**Status:** Design
**Scope:** Shared bonus stage framework (all three stages) + Gumball Machine implementation

## Overview

Sonic 3&K has three bonus stages accessed via star post checkpoints: Gumball Machine, Pachinko (Glowing Spheres), and Slot Machine. This design covers the shared framework that supports all three, plus the concrete implementation of the Gumball Machine as the first stage.

The engine already has `BonusStageProvider`, `BonusStageType`, star post bonus star spawning, and ring-based stage selection — but the actual stage entry and gameplay are NYI.

## Intentional Discrepancy

**ROM behavior:** Bonus stages are loaded through the normal `Level()` routine. The zone ID changes to a bonus zone ($1300/$1400/$1500), the level loads, and the game loop runs identically to any other level. No separate game mode exists.

**Engine approach:** Bonus stages use a distinct `GameMode.BONUS_STAGE` that runs the same level rendering/physics/object pipeline as `LEVEL` mode, but with an explicit coordinator managing entry/exit lifecycle. This gives cleaner separation in `GameLoop` at the cost of some dispatch duplication.

**Rationale:** The engine's `GameLoop` dispatches behavior based on `GameMode`. Overloading `LEVEL` with conditional bonus stage logic would scatter bonus-specific checks across the codebase. A dedicated mode keeps the lifecycle explicit and contained.

## 1. BonusStageProvider Redesign

The current `BonusStageProvider` extends `MiniGameProvider` (with `update()`/`draw()`/`handleInput()`). This is wrong — bonus stages use the level pipeline, so the provider doesn't own rendering or frame updates. Redesign as a coordinator interface.

### New Interface

```java
public interface BonusStageProvider {
    boolean hasBonusStages();
    BonusStageType selectBonusStage(int ringCount);

    // Lifecycle — called by GameLoop during transitions
    void onEnter(BonusStageType type, BonusStageState savedState);
    void onExit();

    // Per-frame — called during BONUS_STAGE level loop
    void onFrameUpdate();
    boolean isStageComplete();

    // Results
    BonusStageRewards getRewards();

    // Zone/audio mapping
    int getZoneId(BonusStageType type);
    int getMusicId(BonusStageType type);
}
```

`BonusStageProvider` no longer extends `MiniGameProvider`. The `update()`/`draw()`/`handleInput()` methods are removed. `NoOpBonusStageProvider` is updated to match.

### BonusStageState Record

Captures all state that must be saved before entering a bonus stage and restored on exit:

```java
record BonusStageState(
    int savedZoneAndAct,
    int savedApparentZoneAndAct,
    int savedRingCount,
    int savedExtraLifeFlags,
    int savedLastStarPostHit,
    int savedStatusSecondary,
    // Level event state machine positions — must be restored so camera
    // locks aren't replayed from routine 0 (same issue as BigRingReturnState)
    int dynamicResizeRoutineFg,
    int dynamicResizeRoutineBg,
    // Player/camera position for checkpoint restoration
    int playerX,
    int playerY,
    int cameraX,
    int cameraY,
    byte topSolidBit,
    byte lrbSolidBit,
    int cameraMaxY
) {}
```

These fields mirror the ROM's `Saved_*` variables plus the `BigRingReturnState` pattern already used for S3K special stage returns.

### BonusStageRewards Record

Unchanged from current implementation:

```java
record BonusStageRewards(
    int rings, int lives,
    boolean shield, boolean fireShield,
    boolean lightningShield, boolean bubbleShield
) {}
```

## 2. GameMode & GameLoop Integration

### GameMode

Add `BONUS_STAGE` to the `GameMode` enum.

### GameLoop Entry Flow

Parallel to `enterSpecialStage()`:

1. Capture `BonusStageState` from current game state (zone, rings, lives, event routine counters, player position, camera, collision path)
2. Fade out music
3. Fade-to-white, then in callback:
   a. `provider.onEnter(type, savedState)`
   b. Set current zone to `provider.getZoneId(type)`
   c. Load level through normal `Sonic3kLevel` loading path
   d. Play bonus music via `provider.getMusicId(type)`
   e. `currentGameMode = GameMode.BONUS_STAGE`
   f. Fade-from-white to reveal

### GameLoop Step (BONUS_STAGE mode)

Runs the same level frame steps as `LEVEL` mode (sprites, objects, collision, camera, parallax, rendering). After the level steps:

- Call `provider.onFrameUpdate()`
- If `provider.isStageComplete()`, initiate exit transition

### GameLoop Exit Flow

1. Capture rewards via `provider.getRewards()`
2. Apply ring/life gains to the saved state
3. `provider.onExit()`
4. Fade-to-white, then in callback:
   a. Restore zone/act from `BonusStageState`
   b. Reload level via `loadCurrentLevel()`
   c. Restore event routine counters (fg and bg) on the level event manager
   d. Restore player position, camera, collision path from saved state
   e. `currentGameMode = GameMode.LEVEL`
   f. Fade-from-white to reveal

No results screen. No title card on entry or exit. The ROM fades out and returns directly.

### LevelTransitionCoordinator

New methods:
- `requestBonusStageEntry(BonusStageType type)` — called by bonus star child on player touch
- `consumeBonusStageRequest()` — returns `BonusStageType` or `null`, consumed in `GameLoop.step()` alongside the existing special stage request check

### Engine.java

`BONUS_STAGE` mode handled identically to `LEVEL` mode in the display/draw dispatch. The level pipeline renders normally.

## 3. AbstractBonusStageCoordinator

Shared base class in `com.openggf.game` for all three bonus stages.

### Entry (`onEnter`)

1. Store `BonusStageState` snapshot
2. Disable death plane
3. Disable HUD timer
4. Restore saved ring count and extra life flags into the active game state (ROM: rings persist into the bonus stage from the checkpoint)

### Per-frame (`onFrameUpdate`)

Checks a `stageComplete` flag. The exit trigger object sets this flag when the player reaches it. Default implementation does no other per-frame work. Subclasses (Slots) can override.

### Exit (`onExit`)

1. Persist current ring count back to saved state
2. Persist extra life flags
3. Re-enable death plane and HUD timer
4. Return saved state for `GameLoop` to restore

### Object Communication

The exit trigger object signals completion by calling `coordinator.requestExit()`. The coordinator is accessible via `GameServices.bonusStage()` (new accessor, registered on entry, returns `NoOpBonusStageProvider` when not in a bonus stage). This follows the same pattern as `LevelTransitionCoordinator` — object sets a flag, `GameLoop` consumes it next frame.

### Sonic3kBonusStageCoordinator

Concrete subclass providing:
- Zone ID mapping: `GUMBALL` -> 0x1300, `PACHINKO` -> 0x1400, `SLOTS` -> 0x1500
- Music ID mapping: `GUMBALL` -> 0x1E, `PACHINKO` -> 0x1F, `SLOTS` -> 0x20
- S3K ring selection formula (already implemented in star post, moved here)
- Implements `BonusStageProvider` interface

Methods are non-final to allow Slots to override `onEnter()` for deform lock and player sprite swap.

## 4. Gumball Machine Objects

All in `com.openggf.game.sonic3k.objects`.

### GumballMachineObjectInstance

Parent machine object (ROM: `Obj_GumballMachine`). Object placement data places one instance.

**Init:**
- Disable death plane (`st Disable_death_plane`)
- Seed RNG from frame counter
- Copy ring/extra life counts to working state
- Clear gumball slot tracking RAM (0x24 bytes)
- Spawn 7 children via `spawnChild()`

**State machine:**

| State | Trigger | Behavior |
|-------|---------|----------|
| Idle | Player in range (-36/+72 x, -8/+16 y) | Play `sfx_GumballTab` (0xD2), determine flip, -> Spin |
| Spin | Animation complete | Animate frames `[3,5,6,7,$14,5,$F4,$7F,5,5,$FC]`, -> Triggered |
| Triggered | Immediate | Signal dispenser to eject, set status flags, -> Post-trigger |
| Post-trigger | Activation flag cleared | Wait, -> Idle |

**Children (7 total):**

| Child | Offset | Behavior |
|-------|--------|----------|
| Dispenser | (0, 0) | Solid platform. When triggered, spawns 16 ejection effects then deletes self |
| Ball container display | (0, +0x24) | Visual, animates |
| Exit trigger | (0, +0x2A0) | Detects player in range, calls `coordinator.requestExit()` |
| Platform left | (-0x38, -0x2C) | Solid platform via `SolidObjectFull` |
| Platform center | (0, -0x2C) | Solid platform |
| Platform right | (+0x38, -0x2C) | Solid platform |
| Platform extra | (0, -0x28) | Solid platform |

Children can be inner classes of `GumballMachineObjectInstance` or lightweight standalone classes.

### GumballTriangleBumperObjectInstance

Placed via object layout data (ROM: `Obj_GumballTriangleBumper`).

**Collision physics:**
- X velocity: +0x300 or -0x300 (determined by h-flip render flag)
- Y velocity: -0x600 (always upward)
- Sets player airborne, clears riding/on-object flags, sets facing direction
- 0x0F frame cooldown timer
- Plays `sfx_Spring`
- Collision box: 4px wide, 16px tall
- Clears corresponding gumball slot in tracking RAM

Follows the same fixed-velocity pattern as `CNZBumperManager`.

### GumballItemObjectInstance

Ejected gumball items (ROM: `Obj_GumballItem`). Shared with future Pachinko implementation.

**Physics:**
- Gravity: -4 per frame Y velocity deceleration
- Movement via `SubpixelMotion.moveSprite2()`
- 16x16 collision hitbox, flags 0xD7

**Subtypes and rewards:**

| Subtype | Behavior |
|---------|----------|
| 0 | Normal gumball — special handler |
| 1 | Small bumper — sound effect only |
| 2 | Ring item — awards 20 rings |
| 3 | Bonus item — ring reward from position-based table |
| 4 | Ring reward — collision flag 0xD7 |

**Reward table** (indexed by `y_position & 0xF`):
```
80, 50, 40, 35, 35, 30, 30, 20, 20, 10, 10, 10, 10, 5, 5, 5
```

**Random type selection:** RNG value → `byte_612E0` lookup table. If parent has bit 7 set, forces subtype 3.

## 5. Level Data & Art Loading

The gumball stage loads through the normal `Sonic3kLevel` path as zone 19.

### Zone Registration

`Sonic3kZoneRegistry` gets an entry for zone 19 (Gumball) mapping to layout, collision, and art addresses.

### Level Data

| Resource | Source | Size |
|----------|--------|------|
| Layout | `Levels/Gumball/Layout/1.bin` | 184 bytes |
| Collision | `Levels/Gumball/Collision/1.bin` | 3.0 KB (Noninterleaved flag) |
| Object placement | `Levels/Gumball/Object Pos/1.bin` | 192 bytes |
| Ring placement | `Levels/Gumball/Ring Pos/1.bin` | 6 bytes |
| Start position | `Levels/Gumball/Start Location/Sonic/1.bin` | 4 bytes (X/Y word pair) |

### Art

| Resource | Label | Format |
|----------|-------|--------|
| Object sprites | `ArtNem_BonusStage` | Nemesis compressed, ~2.8 KB |
| Sprite mappings | `Map_GumballBonus` | Standard S3K mapping format |
| Tile art | `Gumball_8x8_KosM` | KosinskiM compressed |
| Block mappings | `Gumball_16x16_Kos` | Kosinski compressed |
| Chunk mappings | `Gumball_128x128_Kos` | Kosinski compressed |

Art tile base: `make_art_tile(ArtTile_BonusStage, 1, 1)`.

### Palette

`Pal_Gumball_Special` — 96 bytes, loaded as zone palette via normal `LevelLoadBlock` path.

### Animated Tiles

`AnimateTiles_Gumball` — counter-based vertical scroll animation. Register in `Sonic3kPatternAnimator` via the `AniPLC` infrastructure.

### ROM Addresses

All addresses must be verified via `RomOffsetFinder` before implementation. Disassembly labels provide the names; confirmed offsets go into `Sonic3kConstants`.

## 6. File Changes

### New Files

| File | Purpose |
|------|---------|
| `game/BonusStageState.java` | Record — saved zone/player/event state snapshot |
| `game/AbstractBonusStageCoordinator.java` | Shared entry/exit/ring persistence lifecycle |
| `game/sonic3k/Sonic3kBonusStageCoordinator.java` | S3K zone/music mapping, `BonusStageProvider` impl |
| `game/sonic3k/objects/GumballMachineObjectInstance.java` | Parent machine + child objects |
| `game/sonic3k/objects/GumballTriangleBumperObjectInstance.java` | Triangle bumper fixed-velocity bounce |
| `game/sonic3k/objects/GumballItemObjectInstance.java` | Ejected gumball items (Pachinko-reusable) |

### Modified Files

| File | Change |
|------|--------|
| `game/BonusStageProvider.java` | Drop `MiniGameProvider`, redesign as coordinator interface |
| `game/NoOpBonusStageProvider.java` | Update to match new interface |
| `game/GameMode.java` | Add `BONUS_STAGE` |
| `GameLoop.java` | `BONUS_STAGE` mode in step/draw, entry/exit transitions |
| `Engine.java` | Handle `BONUS_STAGE` in display/draw dispatch (same as `LEVEL`) |
| `level/LevelTransitionCoordinator.java` | `requestBonusStageEntry(type)` / `consumeBonusStageRequest()` |
| `game/sonic3k/Sonic3kGameModule.java` | Override `getBonusStageProvider()` |
| `game/sonic3k/Sonic3kZoneRegistry.java` | Register zone 19 (Gumball) |
| `game/sonic3k/Sonic3kObjectRegistry.java` | Register gumball object factories |
| `game/sonic3k/Sonic3kConstants.java` | ROM addresses for gumball art/mappings/layout |
| `game/sonic3k/objects/Sonic3kStarPostBonusStarChild.java` | Replace NYI log with `requestBonusStageEntry()` |
| `docs/status/known-discrepancies.md` | Document bonus stage `GameMode` discrepancy |

### Not In Scope

- Pachinko or Slots implementation
- Bonus stage results screen (ROM has none)
- Title card on bonus stage entry/exit (ROM shows none)
- Headless test infrastructure for bonus stages
