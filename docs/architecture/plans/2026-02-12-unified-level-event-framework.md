# Unified Level Event Framework

## Context

Sonic 1, 2, and 3K all use the same fundamental pattern for dynamic level events: a per-zone state machine driven by camera position triggers. However, the three games currently have separate, unrelated implementations with duplicated patterns. This design unifies them under a shared abstract base class while preserving game-specific behavior.

## Current State

| Game | Implementation | Size | Pattern |
|------|---------------|------|---------|
| S1 | `Sonic1LevelEventManager` + per-zone classes | ~600 lines | Per-zone handler classes extending `Sonic1ZoneEvents` |
| S2 | `LevelEventManager` (monolithic) | ~1400 lines | Single class, private methods per zone |
| S3K | `Sonic3kLevelEventManager` (stub) | ~60 lines | Bootstrap only, no zone events |

All three implement `LevelEventProvider` (`initLevel`, `update`) but share no code.

## Design

### Approach: Shared Abstract Base Class

An `AbstractLevelEventManager` provides state machine mechanics and convenience methods. Game-specific subclasses contain per-zone logic. Zone handlers stay game-specific but share common utilities.

The ROM logic is imperative (check position, set boundary, advance routine) so component-based or declarative approaches were rejected as fighting the architecture.

### AbstractLevelEventManager

**Fields:**
- `currentZone`, `currentAct` (int)
- `eventRoutineFg`, `eventRoutineBg` (int) - dual counters; S1/S2 only use fg
- `frameCounter` (int) - incremented each `update()`
- `timerFrames` (int) - general countdown, decremented each frame when >0
- `bossActive` (boolean) - S3K Boss_flag, also used by S2
- `eventDataFg` (short[]) - per-zone temp storage, sized by subclass
- `eventDataBg` (byte[]) - per-zone temp storage, sized by subclass
- `camera` (Camera) - cached reference

**Concrete `initLevel(zone, act)`:**
1. Store zone/act
2. Zero both routine counters, frameCounter, timerFrames
3. Clear bossActive
4. Zero eventDataFg/eventDataBg arrays
5. Call `onInitLevel(zone, act)` subclass hook

**Concrete `update()`:**
1. Increment frameCounter
2. Decrement timerFrames if >0
3. Call `onUpdate()` subclass hook

**Abstract methods:**
- `int getRoutineStride()` - 2 (S1/S2) or 4 (S3K)
- `int getEventDataFgSize()` - 0 (S1), ~6 (S2/S3K)
- `int getEventDataBgSize()` - 0 (S1/S2), 24 (S3K)
- `void onInitLevel(int zone, int act)` - game-specific init
- `void onUpdate()` - game-specific per-frame dispatch

**Convenience methods (concrete):**

| Category | Methods |
|----------|---------|
| Routine management | `advanceFgRoutine()`, `advanceBgRoutine()`, `revertFgRoutine()`, `setFgRoutine(int)` |
| Camera boundaries | `lockCameraX(min, max)`, `lockCameraY(min, max)`, `setBottomBoundaryTarget(y)`, `setTopBoundaryTarget(y)` |
| Camera control | `freezeCamera()`, `unfreezeCamera()`, `preventBacktracking()`, `preventAdvancing()` |
| Timing | `startTimer(int frames)`, `isTimerExpired()` |
| Player control | `lockPlayerInput()`, `unlockPlayerInput()`, `setForcedInput(mask)`, `clearForcedInput()`, `forcePlayerRight()` |
| Audio | `fadeMusic()`, `playMusic(int id)`, `playSfx(int id)` |
| Object spawning | `spawnObject(ObjectInstance)` |
| Zone transition | `transitionToZone(int zone, int act)` |
| Player query | `getPlayerCharacter()` returning `PlayerCharacter` enum |

### PlayerCharacter Enum

```java
enum PlayerCharacter {
    SONIC_AND_TAILS,  // Player_mode 0
    SONIC_ALONE,      // Player_mode 1
    TAILS_ALONE,      // Player_mode 2
    KNUCKLES          // Player_mode 3
}
```

Lives in `game/` package (game-agnostic). S1/S2 always return SONIC_AND_TAILS. S3K queries actual player mode.

### Game-Specific Subclasses

**Sonic1LevelEventManager:**
- Stride 2, no temp storage, always SONIC_AND_TAILS
- Dispatches to existing per-zone handler classes
- Zone handlers gain base class helpers via manager reference

**Sonic2LevelEventManager (renamed from LevelEventManager):**
- Stride 2, 6 fg temp slots, always SONIC_AND_TAILS
- Keeps existing monolithic switch pattern (refactor to per-zone classes is a future task)
- Existing zone-specific state (HTZ oscillation, CNZ walls, boss refs) stays as-is

**Sonic3kLevelEventManager:**
- Stride 4, 6 fg + 24 bg temp slots, queries player character
- Dual FG/BG dispatch in `onUpdate()`
- Built from scratch with per-zone handler classes
- `bossActive` gates FG events during boss fights

### Zone Dispatch (Internal, Not Enforced by Base)

Each game chooses its own zone dispatch pattern. The base class does not enforce per-zone handler classes vs monolithic switch.

- S1: Per-zone classes (existing)
- S2: Monolithic switch (existing, future refactor task)
- S3K: Per-zone classes (new, built incrementally)

## Event Type Coverage

Every event type found across all three disassemblies maps to the framework:

| Event Type | Base Class API | Game-Specific |
|---|---|---|
| Camera boundary at X/Y threshold | `lockCameraX/Y()`, `setBottomBoundaryTarget()` | |
| Boundary reversion | `revertFgRoutine()` | |
| Boss arena lock | `freezeCamera()`, `lockCameraX/Y()` | |
| Boss spawn with delay | `startTimer()`, `spawnObject()` | |
| Boss defeat check | | Subclass stores boss ref |
| Post-boss movement lock | `preventBacktracking()`, `preventAdvancing()` | |
| Music fade + boss music | `fadeMusic()`, `playMusic()` | |
| SFX triggers | `playSfx()` | |
| Water level change | | Delegation to WaterSystem |
| Earthquake/lava oscillation | | Zone-specific state in subclass |
| Screen shake | | Delegation to ParallaxManager |
| Layout modification | | Zone-specific (CNZ walls, FBZ mods) |
| Input lock | `lockPlayerInput()`, `unlockPlayerInput()` | |
| Forced input | `setForcedInput()`, `forcePlayerRight()` | |
| Palette loading | | Game-specific (ROM addresses differ) |
| Object spawning | `spawnObject()` | |
| Dual FG/BG counters | Both in base, S1/S2 use fg only | |
| Per-zone temp storage | `eventDataFg[]`, `eventDataBg[]` | |
| Boss_flag global gate | `bossActive` | |
| Zone/act transition | `transitionToZone()` | |
| Player character branch | `getPlayerCharacter()` | S3K implements, S1/S2 hardcode |
| Looping levels | | S3K-exclusive (ICZ, SOZ2) |
| OOZ oil / CNZ bumpers | | Game-specific subsystems |

## File Structure

```
game/
  LevelEventProvider.java              (existing - unchanged)
  AbstractLevelEventManager.java       (NEW)
  PlayerCharacter.java                 (NEW)

game/sonic1/events/
  Sonic1LevelEventManager.java         (existing - extends AbstractLevelEventManager)
  Sonic1ZoneEvents.java                (existing - minor changes)
  Sonic1GHZEvents.java ... etc         (existing - no changes initially)

game/sonic2/
  Sonic2LevelEventManager.java         (RENAMED from LevelEventManager, extends base)

game/sonic3k/
  Sonic3kLevelEventManager.java        (existing stub - extends base)
```

## Migration Steps

1. **Create framework** - Add `PlayerCharacter.java` and `AbstractLevelEventManager.java`. Pure additions.
2. **Migrate S2** - Rename `LevelEventManager` to `Sonic2LevelEventManager`, extend base, replace duplicated fields with inherited ones, update all references.
3. **Migrate S1** - `Sonic1LevelEventManager` extends base, zone handlers get manager reference.
4. **Expand S3K** - Implement abstract methods, dual counter support, player character query.
5. **Documentation** - Update CLAUDE.md, agent skills, write design doc.

Each step independently testable. S1/S2 tests stay green throughout.

## Future Work (Separate Tasks)

- Refactor S2 from monolithic switch to per-zone handler classes
- S3K zone event implementations (per-zone, incremental)
- S3K cutscene object implementations (requires S3K object registry + art)
- Demo playback system (separate from level events)
- Character selection UI (populates PlayerCharacter for S3K)
