# Singleton Coupling Reduction: GameServices Expansion + ObjectServices

**Date:** 2026-03-21
**Status:** Draft
**Branch:** feature/ai-common-utility-refactors

## Problem

The engine has 44 singletons with ~1,650 `getInstance()` calls. The worst coupling is in the object/badnik layer: 400+ object files call `LevelManager.getInstance()` (~600 refs) and `AudioManager.getInstance()` (~380 refs) to reach sub-managers they could receive at construction time. This creates:

- Fragile test setups requiring manual singleton reset sequences
- Dependency inversion violations (game-agnostic objects importing game-specific singletons)
- Hidden dependencies that resist refactoring

## Solution Overview

Two complementary changes targeting different layers:

1. **GameServices facade expansion** — static accessors for the remaining high-traffic singletons (AudioManager, Camera, LevelManager, FadeManager). For non-factory code: event handlers, scroll handlers, title screens, GameLoop.
2. **ObjectServices interface** — injectable service handle for objects created by ObjectManager. Replaces direct singleton access in AbstractObjectInstance, AbstractBadnikInstance, AbstractS3kBadnikInstance, and AbstractBossInstance.

## Strategy 1: GameServices Facade Expansion

### Current State

```java
public final class GameServices {
    public static GameStateManager gameState() { return GameStateManager.getInstance(); }
    public static TimerManager timers()        { return TimerManager.getInstance(); }
    public static RomManager rom()             { return RomManager.getInstance(); }
    public static DebugOverlayManager debugOverlay() { return DebugOverlayManager.getInstance(); }
}
```

### Change

Add four accessors:

```java
public static AudioManager audio()    { return AudioManager.getInstance(); }
public static Camera camera()         { return Camera.getInstance(); }
public static LevelManager level()    { return LevelManager.getInstance(); }
public static FadeManager fade()      { return FadeManager.getInstance(); }
```

### Migration

Incremental. Both `AudioManager.getInstance()` and `GameServices.audio()` work simultaneously. Files migrate as they are touched for other work, or in bulk batches per package.

### Scope

Event handlers, scroll handlers, title screens, level event managers, GameLoop, Engine — any code that legitimately needs global manager access and is not created by ObjectManager.

## Strategy 2: ObjectServices Interface

### Interface

Located in `com.openggf.level.objects`:

```java
public interface ObjectServices {
    // Object management
    ObjectManager objectManager();
    ObjectRenderManager renderManager();

    // Level state
    LevelState levelGamestate();
    RespawnState checkpointState();
    Level currentLevel();
    int romZoneId();
    int currentAct();

    // Audio
    void playSfx(int soundId);
    void playMusic(int musicId);
    void fadeOutMusic();

    // Gameplay
    void spawnLostRings(AbstractPlayableSprite player, int frameCounter);
}
```

This surface was derived from grep analysis of all `LevelManager.getInstance()` and `levelManager.` field access patterns across object, badnik, and boss files.

### Default Implementation

Located in `com.openggf.level.objects`:

```java
public class DefaultObjectServices implements ObjectServices {
    @Override
    public ObjectManager objectManager() {
        return LevelManager.getInstance().getObjectManager();
    }

    @Override
    public ObjectRenderManager renderManager() {
        return LevelManager.getInstance().getObjectRenderManager();
    }

    @Override
    public LevelState levelGamestate() {
        return LevelManager.getInstance().getLevelGamestate();
    }

    @Override
    public RespawnState checkpointState() {
        return LevelManager.getInstance().getCheckpointState();
    }

    @Override
    public Level currentLevel() {
        return LevelManager.getInstance().getCurrentLevel();
    }

    @Override
    public int romZoneId() {
        return LevelManager.getInstance().getRomZoneId();
    }

    @Override
    public int currentAct() {
        return LevelManager.getInstance().getCurrentAct();
    }

    @Override
    public void playSfx(int soundId) {
        AudioManager.getInstance().playSfx(soundId);
    }

    @Override
    public void playMusic(int musicId) {
        AudioManager.getInstance().playMusic(musicId);
    }

    @Override
    public void fadeOutMusic() {
        AudioManager.getInstance().fadeOutMusic();
    }

    @Override
    public void spawnLostRings(AbstractPlayableSprite player, int frameCounter) {
        LevelManager.getInstance().spawnLostRings(player, frameCounter);
    }
}
```

### Injection Point

`ObjectManager.syncActiveSpawns()` is the single creation point for all objects (line 548). After `registry.create(spawn)` returns the instance, ObjectManager calls `setServices()`:

```java
ObjectInstance instance = registry.create(spawn);
if (instance instanceof AbstractObjectInstance aoi) {
    aoi.setServices(objectServices);
}
```

ObjectManager holds one `DefaultObjectServices` instance, created in its constructor or set via a setter.

`ObjectManager.addDynamicObject()` must also call `setServices()` on the new object. This is currently a simple list-add — it needs a one-line addition to inject services on dynamically spawned children.

### AbstractObjectInstance Changes

```java
// New field and accessor
private ObjectServices services;

public void setServices(ObjectServices services) {
    this.services = services;
}

protected ObjectServices services() {
    return services;
}
```

The existing static helper methods (`spawnDynamicObject()`, `isPlayerRiding()`, `getRenderer()`, `getRenderManager()`) remain as fallbacks during migration but are candidates for deprecation once objects use `services()`.

### AbstractBadnikInstance Changes

Remove the `protected final LevelManager levelManager` field and the constructor parameter that accepts it. The two existing constructors:

```java
// Before (4-arg)
protected AbstractBadnikInstance(ObjectSpawn spawn, LevelManager levelManager,
        String name, DestructionConfig destructionConfig)

// Before (3-arg, backwards compat)
protected AbstractBadnikInstance(ObjectSpawn spawn, LevelManager levelManager, String name)
```

Become:

```java
// After (3-arg)
protected AbstractBadnikInstance(ObjectSpawn spawn, String name,
        DestructionConfig destructionConfig)

// After (2-arg)
protected AbstractBadnikInstance(ObjectSpawn spawn, String name)
```

Subclasses that currently pass `LevelManager.getInstance()` to super stop doing so. Code that accessed `levelManager.getObjectManager()` migrates to `services().objectManager()`.

### AbstractS3kBadnikInstance Changes

Same pattern as AbstractBadnikInstance. This is a separate class hierarchy (not a subclass of AbstractBadnikInstance) in `com.openggf.game.sonic3k.objects.badniks` that also holds `protected final LevelManager levelManager`. Remove the field and constructor parameter. Subclasses (Bloominator, Rhinobot, MonkeyDude, CaterkillerJrHead) update accordingly.

### AbstractBossInstance Changes

`AbstractBossInstance` in `com.openggf.level.objects.boss` also holds `protected final LevelManager levelManager`. Same migration: remove field, remove constructor parameter, subclasses (~15 across S1/S2/S3K) switch to `services()`.

Note: Some boss classes call level-mutation methods (`invalidateForegroundTilemap()`, `updatePalette()`, `requestZoneAndAct()`) that go beyond ObjectServices' scope. These remain on `GameServices.level()` during Phase 3 migration.

### DestructionEffects Cascade

`DestructionEffects.destroyBadnik()` currently accepts `LevelManager`. Changing it to accept `ObjectServices` cascades to:

1. `PointsFactory` interface signature (`LevelManager` param → `ObjectServices`)
2. All `PointsFactory` lambda implementations (`Sonic2BadnikConfig`, `Sonic1DestructionConfig`)
3. `AbstractPointsObjectInstance` constructor (`LevelManager` param → receives services via `setServices()`)
4. `PointsObjectInstance` and `Sonic1PointsObjectInstance` constructors
5. `AnimalObjectInstance` constructor
6. `BreakableBlockObjectInstance` and `SmashableGroundObjectInstance` — these directly instantiate `PointsObjectInstance` (not via `PointsFactory`), so they are affected by the constructor change in item 3, not by item 1

All of these are scoped to Phase 2.

### Factory Signature

`ObjectFactory` remains unchanged:

```java
ObjectInstance create(ObjectSpawn spawn, ObjectRegistry registry);
```

Services are injected post-construction by ObjectManager, not passed through the factory. This avoids changing every factory lambda across all three game registries.

### Test Usage

Tests can create a stub implementation:

```java
ObjectServices stubServices = new ObjectServices() {
    @Override public ObjectManager objectManager() { return mockOm; }
    @Override public ObjectRenderManager renderManager() { return null; }
    @Override public LevelState levelGamestate() { return mockState; }
    @Override public RespawnState checkpointState() { return null; }
    @Override public Level currentLevel() { return null; }
    @Override public int romZoneId() { return 0; }
    @Override public int currentAct() { return 0; }
    @Override public void playSfx(int soundId) { /* no-op */ }
    @Override public void playMusic(int musicId) { /* no-op */ }
    @Override public void fadeOutMusic() { /* no-op */ }
    @Override public void spawnLostRings(AbstractPlayableSprite p, int f) { /* no-op */ }
};
object.setServices(stubServices);
```

No singleton reset required. Objects become testable in isolation.

## Migration Strategy

Both patterns coexist during migration. No big-bang change required.

### Phase 1: Infrastructure (non-breaking)
1. Add four accessors to `GameServices`
2. Create `ObjectServices` interface and `DefaultObjectServices`
3. Add `services` field + setter + accessor to `AbstractObjectInstance`
4. Wire `ObjectManager` to inject services after object creation (both `syncActiveSpawns` and `addDynamicObject`)

### Phase 2: Base class migration (breaking for constructors)
1. Remove `LevelManager` constructor parameter from `AbstractBadnikInstance` (36 subclasses)
2. Remove `LevelManager` constructor parameter from `AbstractS3kBadnikInstance` (4 subclasses)
3. Remove `LevelManager` constructor parameter from `AbstractBossInstance` (~15 subclasses)
4. Update `DestructionEffects.destroyBadnik()` and its cascade: `PointsFactory`, `AbstractPointsObjectInstance`, `AnimalObjectInstance`, `Sonic2BadnikConfig`, `Sonic1DestructionConfig`, `BreakableBlockObjectInstance`, `SmashableGroundObjectInstance`
5. Update all factory registrations in `Sonic1ObjectRegistry`, `Sonic2ObjectRegistry`, `Sonic3kObjectRegistry` to stop passing `LevelManager.getInstance()`

### Phase 3: Incremental object migration (non-breaking, file-by-file)
- Migrate individual object files from `LevelManager.getInstance().getXxx()` to `services().xxx()`
- Migrate `AudioManager.getInstance().playSfx(x)` to `services().playSfx(x)` in objects
- Boss classes using level-mutation methods (`invalidateForegroundTilemap()`, `requestZoneAndAct()`) migrate those calls to `GameServices.level()` instead
- Each file is independent — can be done alongside other work

### Phase 4: Incremental non-object migration (non-breaking, file-by-file)
- Migrate event handlers, scroll handlers, etc. from `AudioManager.getInstance()` to `GameServices.audio()`
- Migrate `LevelManager.getInstance()` to `GameServices.level()` in non-object code

## Out of Scope

The following singleton accesses from object files are **not** covered by ObjectServices and remain on direct singleton or `GameServices` access:

- `Camera.getInstance()` (~151 refs from objects) — objects use camera position for projectile targeting, screen-relative checks beyond the static `CameraBounds`. Potential future `ObjectServices` addition.
- `SpriteManager.getInstance()` (~42 refs from objects) — objects accessing player list for multi-character interactions. Potential future addition.
- `GraphicsManager.getInstance()` (~34 refs from objects) — direct GL rendering calls. Potential future addition.
- Object registry factory lambdas that call `LevelManager.getInstance().getRomZoneId()` for zone-set resolution (~4 refs) — these are in factory scope, not object scope.

## Files Modified

### New files
- `src/main/java/com/openggf/level/objects/ObjectServices.java`
- `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`

### Modified files (Phase 1)
- `GameServices.java` — add 4 accessors
- `AbstractObjectInstance.java` — add services field/setter/accessor
- `ObjectManager.java` — inject services after object creation

### Modified files (Phase 2)
- `AbstractBadnikInstance.java` — remove LevelManager field and constructor param
- `AbstractS3kBadnikInstance.java` — remove LevelManager field and constructor param
- `AbstractBossInstance.java` — remove LevelManager field and constructor param
- `AbstractS1EggmanBossInstance.java` — intermediate S1 boss base class, passes LevelManager through to super
- `DestructionEffects.java` — change LevelManager param to ObjectServices across cascade
- `AbstractPointsObjectInstance.java`, `PointsObjectInstance.java`, `Sonic1PointsObjectInstance.java` — remove LevelManager constructor params
- `AnimalObjectInstance.java` — remove LevelManager constructor param
- `Sonic2BadnikConfig.java`, `Sonic1DestructionConfig.java` — update PointsFactory lambdas
- `BreakableBlockObjectInstance.java`, `SmashableGroundObjectInstance.java` — update PointsFactory usage
- ~55 badnik/boss subclass files — update constructor calls
- `Sonic1ObjectRegistry.java`, `Sonic2ObjectRegistry.java`, `Sonic3kObjectRegistry.java` — update factory registrations

### Modified files (Phase 3-4, incremental)
- ~400 object files, migrated individually
- ~50 non-object files for GameServices facade migration

## What This Does NOT Change

- No singleton is removed. DefaultObjectServices delegates to the same singletons.
- No DI framework introduced.
- ObjectFactory signature unchanged.
- Engine.java, GameLoop.java, LevelManager.java internals unchanged.
- GameContext.forTesting() continues to work as-is.

## Success Criteria

- All existing tests pass with no changes to test logic
- New objects can be tested without singleton resets by passing stub ObjectServices
- AbstractBadnikInstance, AbstractS3kBadnikInstance, and AbstractBossInstance no longer hold LevelManager references
- Object files migrated in Phase 3 have zero `LevelManager.getInstance()` calls (except for level-mutation operations documented in Out of Scope)
