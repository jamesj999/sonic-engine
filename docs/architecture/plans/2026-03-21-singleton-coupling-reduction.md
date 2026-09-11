# Singleton Coupling Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce singleton coupling in the object layer by expanding the GameServices facade and introducing an injectable ObjectServices interface.

**Architecture:** Two complementary changes — GameServices gets 4 new static accessors for non-object code, while ObjectServices provides an injectable service handle that ObjectManager injects into all objects post-construction. Three base class hierarchies (AbstractBadnikInstance, AbstractS3kBadnikInstance, AbstractBossInstance) drop their stored LevelManager fields in favor of the injected services.

**Tech Stack:** Java 21, JUnit 5, Maven

**Spec:** `docs/architecture/designs/2026-03-21-singleton-coupling-reduction-design.md`

**Baseline:** 1893 tests passing, 8 pre-existing failures, 36 skipped

---

### Task 1: Expand GameServices Facade

**Files:**
- Modify: `src/main/java/com/openggf/game/GameServices.java`

- [ ] **Step 1: Add four new accessors to GameServices**

Add imports and methods after the existing `debugOverlay()` method:

```java
import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.graphics.FadeManager;
import com.openggf.level.LevelManager;

// Add after debugOverlay():
public static AudioManager audio() {
    return AudioManager.getInstance();
}

public static Camera camera() {
    return Camera.getInstance();
}

public static LevelManager level() {
    return LevelManager.getInstance();
}

public static FadeManager fade() {
    return FadeManager.getInstance();
}
```

- [ ] **Step 2: Run tests to verify no regressions**

Run: `mvn test -q`
Expected: Same baseline (1893 passed, 8 failed, 36 skipped)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameServices.java
git commit -m "refactor: expand GameServices facade with audio/camera/level/fade accessors"
```

---

### Task 2: Create ObjectServices Interface and DefaultObjectServices

**Files:**
- Create: `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Create: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`

- [ ] **Step 1: Create the ObjectServices interface**

```java
package com.openggf.level.objects;

import com.openggf.game.LevelState;
import com.openggf.game.RespawnState;
import com.openggf.level.Level;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Injectable service handle for game objects. Provides access to level sub-managers
 * and audio without requiring singleton lookups.
 * <p>
 * Injected by {@link ObjectManager} after object construction via
 * {@link AbstractObjectInstance#setServices(ObjectServices)}.
 */
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

- [ ] **Step 2: Create DefaultObjectServices**

```java
package com.openggf.level.objects;

import com.openggf.audio.AudioManager;
import com.openggf.game.LevelState;
import com.openggf.game.RespawnState;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Production implementation of {@link ObjectServices} backed by existing singletons.
 * A single instance is held by {@link ObjectManager} and shared across all objects.
 */
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

- [ ] **Step 3: Verify it compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectServices.java \
    src/main/java/com/openggf/level/objects/DefaultObjectServices.java
git commit -m "refactor: add ObjectServices interface and DefaultObjectServices implementation"
```

---

### Task 3: Add Services Field to AbstractObjectInstance and Wire ObjectManager

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`

- [ ] **Step 1: Add services field, setter, and accessor to AbstractObjectInstance**

After the `dynamicSpawn` field (line 23), add:
```java
private ObjectServices services;
```

After the `getName()` method (around line 60), add:
```java
/**
 * Sets the injectable services handle. Called by ObjectManager after construction.
 */
public void setServices(ObjectServices services) {
    this.services = services;
}

/**
 * Returns the injectable services handle, or null if not yet injected
 * (e.g. during construction or in legacy code paths).
 */
protected ObjectServices services() {
    return services;
}
```

- [ ] **Step 2: Add ObjectServices field to ObjectManager**

After the existing field declarations (around line 42), add:
```java
private final ObjectServices objectServices = new DefaultObjectServices();
```

- [ ] **Step 3: Inject services in syncActiveSpawns()**

In `syncActiveSpawns()`, after `registry.create(spawn)` (line 548) and before `activeObjects.put(spawn, instance)` (line 550), add:
```java
if (instance instanceof AbstractObjectInstance aoi) {
    aoi.setServices(objectServices);
}
```

- [ ] **Step 4: Inject services in addDynamicObject()**

In `addDynamicObject()` (line 405), add at the start of the method before the `if (updating)` check:
```java
if (object instanceof AbstractObjectInstance aoi && aoi.services() == null) {
    aoi.setServices(objectServices);
}
```

- [ ] **Step 5: Run tests**

Run: `mvn test -q`
Expected: Same baseline

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/objects/AbstractObjectInstance.java \
    src/main/java/com/openggf/level/objects/ObjectManager.java
git commit -m "refactor: wire ObjectManager to inject ObjectServices into all objects"
```

---

### Task 4: Migrate All Base Classes, DestructionEffects Cascade, and All Subclasses

This is an atomic change — the DestructionEffects signature change, base class constructor changes, and all subclass updates must compile together. No intermediate compilable state exists between these changes.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/DestructionEffects.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractPointsObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/PointsObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1PointsObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/AnimalObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/badniks/Sonic2BadnikConfig.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/badniks/Sonic1DestructionConfig.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/badniks/AbstractS3kBadnikInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic1/objects/bosses/AbstractS1EggmanBossInstance.java`
- Modify: All 12 S1 badnik files in `game/sonic1/objects/badniks/`
- Modify: All 24 S2 badnik files in `game/sonic2/objects/badniks/`
- Modify: All 4 S3K badnik files in `game/sonic3k/objects/badniks/`
- Modify: All S1 boss files (~7 in `game/sonic1/objects/bosses/`)
- Modify: All S2 boss files (~10 in `game/sonic2/objects/bosses/`)
- Modify: All S3K boss files (2 in `game/sonic3k/objects/`)
- Modify: `Sonic1ObjectRegistry.java`, `Sonic2ObjectRegistry.java`, `Sonic3kObjectRegistry.java`
- Modify: Direct callers of changed constructors (see Step 7)

- [ ] **Step 1: Update DestructionEffects**

In `DestructionEffects.java`:

Change `PointsFactory` interface from `LevelManager levelManager` to `ObjectServices services`.

Change `destroyBadnik()` signature from `LevelManager levelManager` to `ObjectServices services`.

Update all internal references:
- `levelManager.getObjectManager()` → `services.objectManager()`
- `levelManager.getObjectRenderManager()` → `services.renderManager()`
- `new AnimalObjectInstance(animalSpawn, levelManager)` → `new AnimalObjectInstance(animalSpawn, services)`
- `config.pointsFactory().create(pointsSpawn, levelManager, ...)` → `config.pointsFactory().create(pointsSpawn, services, ...)`
- `AudioManager.getInstance().playSfx(...)` → `services.playSfx(...)`

- [ ] **Step 2: Update AbstractPointsObjectInstance, PointsObjectInstance, Sonic1PointsObjectInstance**

Change constructors from `LevelManager levelManager` to `ObjectServices services`.
Update `levelManager.getObjectRenderManager().getPointsRenderer()` → `services.renderManager().getPointsRenderer()`.
Remove `import com.openggf.level.LevelManager` from the concrete classes.

- [ ] **Step 3: Update AnimalObjectInstance**

Change constructor from `LevelManager levelManager` to `ObjectServices services`.
Update `levelManager.getObjectRenderManager().getAnimalRenderer()` → `services.renderManager().getAnimalRenderer()`.
If `levelManager` is stored as a field, replace with `services` or remove if only used in constructor.

- [ ] **Step 4: Update Sonic2BadnikConfig and Sonic1DestructionConfig lambdas**

Change parameter names in PointsFactory lambdas from `lm` to `svc`:
- `Sonic2BadnikConfig`: `(spawn, svc, pts) -> new PointsObjectInstance(spawn, svc, pts)`
- `Sonic1DestructionConfig`: `(spawn, svc, pts) -> new Sonic1PointsObjectInstance(spawn, svc, pts)`

- [ ] **Step 5: Update AbstractBadnikInstance**

Remove `protected final LevelManager levelManager;` field (line 26).
Remove `import com.openggf.level.LevelManager;`.

Change 4-arg constructor `(ObjectSpawn spawn, LevelManager levelManager, String name, DestructionConfig destructionConfig)` → `(ObjectSpawn spawn, String name, DestructionConfig destructionConfig)`.

Change 3-arg constructor `(ObjectSpawn spawn, LevelManager levelManager, String name)` → `(ObjectSpawn spawn, String name)`.

In `destroyBadnik()`, change `levelManager` → `services()`.

- [ ] **Step 6: Update AbstractS3kBadnikInstance**

Remove `protected final LevelManager levelManager;` field.
Remove `LevelManager levelManager` from constructor.
Change `defeat()`: `levelManager` → `services()` in `DestructionEffects.destroyBadnik()` call.
Change any `levelManager.getObjectRenderManager()` → `services().renderManager()`.

- [ ] **Step 7: Update AbstractBossInstance and AbstractS1EggmanBossInstance**

Remove `protected final LevelManager levelManager;` field from `AbstractBossInstance`.
Remove `LevelManager levelManager` from constructor.
Replace `levelManager.getObjectManager()` → `services().objectManager()`.
Replace `levelManager.getObjectRenderManager()` → `services().renderManager()`.
Replace `levelManager.getCurrentLevel()` → `services().currentLevel()`.
For level-mutation calls not in ObjectServices, use `GameServices.level()`.

Update `AbstractS1EggmanBossInstance` to remove the `LevelManager` passthrough parameter.

- [ ] **Step 8: Update all 12 S1 badnik subclasses**

Each S1 badnik changes from `super(spawn, levelManager, "Name")` to `super(spawn, "Name")`. Remove `LevelManager levelManager` constructor parameter. Replace any `levelManager.` references with `services().`.

Files: `Sonic1MotobugBadnikInstance`, `Sonic1CaterkillerBadnikInstance`, `Sonic1YadrinBadnikInstance`, `Sonic1RollerBadnikInstance`, `Sonic1OrbinautBadnikInstance`, `Sonic1NewtronBadnikInstance`, `Sonic1JawsBadnikInstance`, `Sonic1CrabmeatBadnikInstance`, `Sonic1ChopperBadnikInstance`, `Sonic1BuzzBomberBadnikInstance`, `Sonic1BurrobotBadnikInstance`, `Sonic1BatbrainBadnikInstance`

**Special case — Sonic1CaterkillerBadnikInstance:** This passes the inherited `levelManager` to `Sonic1CaterkillerBodyInstance`'s constructor. Change this to `GameServices.level()` or update `Sonic1CaterkillerBodyInstance` to accept `ObjectServices`.

- [ ] **Step 9: Update all 24 S2 badnik subclasses**

Each S2 badnik changes from `super(spawn, levelManager, "Name", Sonic2BadnikConfig.DESTRUCTION)` to `super(spawn, "Name", Sonic2BadnikConfig.DESTRUCTION)`. Remove `LevelManager levelManager` constructor parameter. Replace any `levelManager.` references with `services().`.

**Special cases requiring extra attention:**
- `TurtloidBadnikInstance` — 8+ `levelManager` references including direct `AnimalObjectInstance` and `PointsObjectInstance` construction. Change those to use `services()`.
- `RexonHeadObjectInstance` — if it extends AbstractBadnikInstance, same pattern. If it has its own `levelManager` field, update that too.

Files: `TurtloidBadnikInstance`, `SolBadnikInstance`, `OctusBadnikInstance`, `AquisBadnikInstance`, `AsteronBadnikInstance`, `WhispBadnikInstance`, `SpinyOnWallBadnikInstance`, `SpinyBadnikInstance`, `SpikerBadnikInstance`, `SlicerBadnikInstance`, `ShellcrackerBadnikInstance`, `RexonBadnikInstance`, `NebulaBadnikInstance`, `MasherBadnikInstance`, `GrounderBadnikInstance`, `GrabberBadnikInstance`, `FlasherBadnikInstance`, `CrawltonBadnikInstance`, `CrawlBadnikInstance`, `CoconutsBadnikInstance`, `CluckerBadnikInstance`, `ChopChopBadnikInstance`, `BuzzerBadnikInstance`, `BalkiryBadnikInstance`

- [ ] **Step 10: Update all 4 S3K badnik subclasses**

Remove `LevelManager` from constructors. Replace `levelManager.` with `services().`.

Files: `RhinobotBadnikInstance`, `BloominatorBadnikInstance`, `MonkeyDudeBadnikInstance`, `CaterkillerJrHeadInstance`

- [ ] **Step 11: Update all boss subclasses**

Remove `LevelManager` from constructors. Replace `levelManager.` with `services().` or `GameServices.level()` for level-mutation ops.

S1 bosses (~7 files): `AbstractS1EggmanBossInstance`, `Sonic1GHZBossInstance`, `Sonic1LZBossInstance`, `Sonic1MZBossInstance`, `Sonic1SLZBossInstance`, `Sonic1SYZBossInstance`, `Sonic1FZBossInstance` + helper classes (`GHZBossWreckingBall`, `SYZBossSpike`, `Sonic1SLZBossSpikeball`, `Sonic1ScrapEggmanInstance`, etc.)
S2 bosses (10 files): `Sonic2EHZBossInstance`, `Sonic2HTZBossInstance`, `Sonic2CPZBossInstance`, `Sonic2ARZBossInstance`, `Sonic2MCZBossInstance`, `Sonic2CNZBossInstance`, `Sonic2MTZBossInstance`, `Sonic2WFZBossInstance`, `Sonic2DeathEggRobotInstance`, `Sonic2MechaSonicInstance`
S3K bosses (2 files): `AizMinibossInstance`, `AizMinibossCutsceneInstance`

- [ ] **Step 12: Update factory registrations in all three ObjectRegistries**

Remove `LevelManager.getInstance()` from all badnik and boss factory lambdas in:
- `Sonic1ObjectRegistry.java`
- `Sonic2ObjectRegistry.java`
- `Sonic3kObjectRegistry.java`

- [ ] **Step 13: Update remaining direct callers of changed constructors**

These files directly construct `PointsObjectInstance`, `Sonic1PointsObjectInstance`, or `AnimalObjectInstance` with `LevelManager` — they must use `services()` instead:

- `BreakableBlockObjectInstance.java` — `new PointsObjectInstance(spawn, services(), pts)`
- `SmashableGroundObjectInstance.java` — same
- `BonusBlockObjectInstance.java` — same
- `BumperObjectInstance.java` — same
- `PointPokeyObjectInstance.java` — same
- `Sonic1BumperObjectInstance.java` — `new Sonic1PointsObjectInstance(spawn, services(), pts)`
- `Sonic1SmashBlockObjectInstance.java` — same

Also update `Sonic1BallHogBadnikInstance.java` — this extends `AbstractObjectInstance` (not `AbstractBadnikInstance`) but has its own `private final LevelManager levelManager` field and calls `DestructionEffects.destroyBadnik()`. Change it to use `services()`.

- [ ] **Step 14: Verify it compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 15: Run tests**

Run: `mvn test -q`
Expected: Same baseline

- [ ] **Step 16: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ \
    src/main/java/com/openggf/game/sonic1/objects/ \
    src/main/java/com/openggf/game/sonic2/objects/ \
    src/main/java/com/openggf/game/sonic3k/objects/ \
    src/main/java/com/openggf/game/GameServices.java
git commit -m "refactor: remove LevelManager from all badnik/boss base classes and subclasses

Migrate DestructionEffects, PointsFactory, AbstractPointsObjectInstance,
AnimalObjectInstance, AbstractBadnikInstance, AbstractS3kBadnikInstance,
AbstractBossInstance, and all ~60 subclasses from stored LevelManager
field to injectable ObjectServices."
```

---

### Task 5: Update Tests

**Files:**
- Modify: Any test files that construct badniks/bosses with `LevelManager` parameter

- [ ] **Step 1: Find all affected test files**

```bash
grep -rn "LevelManager" src/test/ --include="*.java" | grep -i "badnik\|boss\|destruction\|points\|animal"
```

Known affected files include:
- `src/test/java/com/openggf/game/sonic3k/objects/TestAizMinibossBarrelShotChild.java`
- Any test constructing badnik/boss instances with `LevelManager.getInstance()`

For each, either:
- Remove the `LevelManager` parameter from constructor calls
- Add `instance.setServices(new DefaultObjectServices())` after construction
- Or create a stub ObjectServices for isolated unit tests

- [ ] **Step 2: Run tests**

Run: `mvn test -q`
Expected: Same baseline

- [ ] **Step 3: Commit**

```bash
git add src/test/
git commit -m "test: update badnik/boss tests for ObjectServices migration"
```

---

### Task 6: Final Verification

- [ ] **Step 1: Full test suite**

Run: `mvn test -q`
Expected: Same baseline (1893 passed, 8 failed, 36 skipped)

- [ ] **Step 2: Verify no LevelManager references remain in base classes**

```bash
grep -n "LevelManager" src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java
grep -n "LevelManager" src/main/java/com/openggf/game/sonic3k/objects/badniks/AbstractS3kBadnikInstance.java
grep -n "LevelManager" src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java
```

Expected: No matches for any of these files.

- [ ] **Step 3: Count remaining LevelManager.getInstance() calls in object files**

```bash
grep -rn "LevelManager.getInstance()" src/main/java/com/openggf/game/*/objects/ --include="*.java" | wc -l
```

This number should be significantly lower than before. Remaining calls are in non-badnik, non-boss objects that migrate incrementally in a future Phase 3.
