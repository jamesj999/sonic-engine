# Two-Tier Service Architecture: ObjectServices Expansion

**Date:** 2026-03-22
**Status:** Design
**Scope:** Expand ObjectServices interface, migrate ~250 object files, establish two-tier service pattern

## Problem

Objects currently access services through three inconsistent patterns:
- `GameServices.xxx()` — 308 files (static facade wrapping singletons)
- `Xxx.getInstance()` — 234 files (direct singleton access)
- `services().xxx()` — 2 files (injected, context-aware)

This creates two problems:
1. **Inconsistency** — three patterns for the same thing, no clear rule on which to use
2. **Editor blocker** — a planned level editor needs objects to work in both game mode and editor play-test mode, requiring swappable service contexts

## Solution: Two-Tier Service Architecture

Split services into two tiers based on whether they're context-specific or global:

**Tier 1 — ObjectServices (context-specific, injected):**
Services whose implementation differs between game mode and editor mode. Objects access these via `services().xxx()`.

**Tier 2 — GameServices (global, static):**
Services that are process-wide and identical in all contexts. Objects access these via `GameServices.xxx()`.

### Tier Assignment (Data-Driven)

Measured by counting object file usage across `level/objects/` and `game/sonic*/objects/`:

| Service | Object Files | Tier | Rationale |
|---------|-------------|------|-----------|
| Level state | 118 | ObjectServices | Editor has its own level |
| Camera | 98 | ObjectServices | Editor has its own viewport |
| Game state | 50 | ObjectServices | Editor doesn't track score/lives |
| Audio | 39 | ObjectServices | Editor may want silent/preview |
| Sidekicks | 37 calls | ObjectServices | Editor provides test sidekicks |
| GraphicsManager | 0 | GameServices | Shared rendering pipeline |
| Config | 4 | GameServices | Global settings |
| ROM | 8 | GameServices | Same ROM in all contexts |

## Expanded ObjectServices Interface

```java
public interface ObjectServices {
    // --- Object management (existing) ---
    ObjectManager objectManager();
    ObjectRenderManager renderManager();

    // --- Level state (existing) ---
    LevelState levelGamestate();
    RespawnState checkpointState();
    Level currentLevel();
    int romZoneId();
    int currentAct();

    // --- Audio (existing narrow helpers) ---
    void playSfx(int soundId);
    void playMusic(int musicId);
    void fadeOutMusic();

    // --- Gameplay (existing) ---
    void spawnLostRings(PlayableEntity player, int frameCounter);

    // --- NEW: Context-specific managers ---
    Camera camera();
    GameStateManager gameState();

    // --- NEW: Player/sidekick access ---
    List<PlayableEntity> sidekicks();
}
```

### New Methods: 3

| Method | Return Type | Replaces |
|--------|------------|----------|
| `camera()` | `Camera` | `GameServices.camera()` in 98 object files |
| `gameState()` | `GameStateManager` | `GameServices.gameState()` in 50 object files |
| `sidekicks()` | `List<PlayableEntity>` | `SpriteManager.getInstance().getSidekicks()` in 26 object files (37 call sites) |

### Why Not Also Added

| Service | Reason |
|---------|--------|
| `GraphicsManager` | 0 object calls; purely rendering infrastructure |
| `SonicConfigurationService` | 4 object calls; all `getBoolean()` for debug flags |
| `TimerManager` | 0 object calls; game-loop-level scheduler |
| `FadeManager` | 0 object calls; screen transition control |
| `WaterSystem` | 0 object calls; accessed indirectly via player/level |
| `level()` (full LevelManager) | Transition methods (`requestZoneAndAct`, `requestSpecialStageEntry`) are game-mode-only; the level-state subset is already on ObjectServices |

## DefaultObjectServices Updates

```java
public class DefaultObjectServices implements ObjectServices {
    // ... existing delegation methods unchanged ...

    @Override
    public Camera camera() {
        return Camera.getInstance();
    }

    @Override
    public GameStateManager gameState() {
        return GameStateManager.getInstance();
    }

    @Override
    public List<PlayableEntity> sidekicks() {
        return List.copyOf(SpriteManager.getInstance().getSidekicks());
    }
}
```

The `sidekicks()` method returns an unmodifiable copy. The cast from `List<AbstractPlayableSprite>` to `List<PlayableEntity>` is safe because `AbstractPlayableSprite implements PlayableEntity`.

## Migration Rules

### Context-specific: Migrate to services()

| Before | After |
|--------|-------|
| `GameServices.camera().xxx()` | `services().camera().xxx()` |
| `GameServices.gameState().xxx()` | `services().gameState().xxx()` |
| `GameServices.audio().playSfx(x)` | `services().playSfx(x)` |
| `GameServices.audio().playMusic(x)` | `services().playMusic(x)` |
| `GameServices.audio().fadeOutMusic()` | `services().fadeOutMusic()` |
| `GameServices.level().getObjectManager()` | `services().objectManager()` |
| `GameServices.level().getObjectRenderManager()` | `services().renderManager()` |
| `GameServices.level().getRomZoneId()` | `services().romZoneId()` |
| `GameServices.level().getCurrentAct()` | `services().currentAct()` |
| `GameServices.level().getCurrentLevel()` | `services().currentLevel()` |
| `GameServices.level().getCheckpointState()` | `services().checkpointState()` |
| `GameServices.level().getLevelGamestate()` | `services().levelGamestate()` |
| `SpriteManager.getInstance().getSidekicks()` | `services().sidekicks()` |

### Global: Keep on GameServices (not migrated)

| Pattern | Reason |
|---------|--------|
| `GameServices.rom()` | Same ROM in all contexts |
| `GameServices.level().requestZoneAndAct()` | Game-mode transition |
| `GameServices.level().requestSpecialStageEntry()` | Game-mode transition |
| `GameServices.level().requestCreditsTransition()` | Game-mode transition |
| `GameServices.level().advanceToNextLevel()` | Game-mode transition |
| `GameServices.level().invalidateForegroundTilemap()` | Rendering control |
| `GameServices.level().updatePalette()` | Rendering control |
| `SonicConfigurationService.getInstance()` | Global config |

### Edge Cases

**Constructor-time access:** `services()` throws before ObjectManager injection. Objects needing services during construction use `GameServices` directly (documented in `AbstractObjectInstance.services()` javadoc). This is a small number of cases and is correct — the editor will initialize GameServices with appropriate defaults before constructing objects.

**Boss transition methods:** Bosses calling `GameServices.level().requestZoneAndAct()` etc. stay on GameServices. These are inherently game-mode operations. In editor mode, bosses wouldn't trigger level transitions.

**`GameServices.level()` remaining calls:** ~15 object files use `GameServices.level()` for game-mode-only operations (transitions, palette, tilemap invalidation). These are NOT migrated — they represent genuine game-mode coupling that the editor will either stub out or not invoke.

## Migration Scope

| Category | Files | Approach |
|----------|-------|----------|
| S2 objects | ~121 | Mechanical find-and-replace |
| S1 objects | ~82 | Mechanical find-and-replace |
| S3K objects | ~40 | Mechanical find-and-replace |
| level/objects (game-agnostic) | ~30 | Mechanical find-and-replace |
| Total | ~250+ | Single commit, all at once |

### Files NOT Migrated (Non-Object Code)

GameLoop, LevelManager, SpriteManager, event handlers, and other non-object code continue using GameServices. They are not in the object hierarchy and don't receive ObjectServices injection.

## Future: EditorObjectServices

When the level editor is implemented, a new `EditorObjectServices` provides editor-specific state:

```java
public class EditorObjectServices implements ObjectServices {
    private final EditorLevelContext level;
    private final EditorViewport camera;
    private final EditorGameState gameState;
    // ...

    @Override public Camera camera() { return camera; }
    @Override public GameStateManager gameState() { return gameState; }
    @Override public List<PlayableEntity> sidekicks() { return List.of(); }
    // ...
}
```

Objects work unchanged in editor context — they call `services().camera().getX()` and get the editor viewport instead of the game camera.

## Verification

```bash
# All tests pass
mvn test

# Zero GameServices.camera()/gameState()/audio() in object files
grep -rl "GameServices\.camera()\|GameServices\.gameState()\|GameServices\.audio()" \
  --include="*.java" src/main/java/com/openggf/game/sonic*/objects/ \
  src/main/java/com/openggf/level/objects/ | wc -l
# Expected: 0 (or small number for game-mode transition code)

# Zero SpriteManager.getInstance() in object files
grep -rl "SpriteManager\.getInstance()" --include="*.java" \
  src/main/java/com/openggf/game/sonic*/objects/ \
  src/main/java/com/openggf/level/objects/ | wc -l
# Expected: 0

# services() adoption
grep -rl "services()\." --include="*.java" src/main/java | wc -l
# Expected: ~250 (up from 2)
```

## Key Files

| File | Role |
|------|------|
| `src/main/java/com/openggf/level/objects/ObjectServices.java` | Interface to expand |
| `src/main/java/com/openggf/level/objects/DefaultObjectServices.java` | Production impl to update |
| `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java` | Base class with services() accessor |
| `src/main/java/com/openggf/game/GameServices.java` | Global facade (unchanged) |
| `src/main/java/com/openggf/level/objects/ObjectManager.java` | Injects services into objects |
