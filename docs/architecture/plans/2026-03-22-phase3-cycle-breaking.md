# Phase 3: Cycle Breaking — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Break the `sprites.playable ↔ level.objects` circular dependency by introducing `PlayableEntity` and `PowerUpSpawner` interfaces in the `game` package.

**Architecture:** Extract enums to `game` package, define interfaces, migrate `level/objects/` (33 files) to use `PlayableEntity` instead of `AbstractPlayableSprite`, and inject `PowerUpSpawner` to break the reverse direction.

**Tech Stack:** Java 21, Maven

**Spec:** `docs/architecture/designs/2026-03-22-architectural-refactoring-design.md` (Phase 3)

---

### Task 1: Move GroundMode and ShieldType to game Package

Move these enums from `sprites.playable` to `game` so that `PlayableEntity` can reference them without creating a dependency on `sprites`.

**Files:**
- Move: `src/main/java/com/openggf/sprites/playable/GroundMode.java` → `src/main/java/com/openggf/game/GroundMode.java`
- Move: `src/main/java/com/openggf/sprites/playable/ShieldType.java` → `src/main/java/com/openggf/game/ShieldType.java`

- [ ] **Step 1: Move GroundMode.java**

Change package declaration from `com.openggf.sprites.playable` to `com.openggf.game`. Then update ALL imports across the codebase:

```bash
grep -rn "import com.openggf.sprites.playable.GroundMode" src/ --include="*.java" -l
```

Replace `import com.openggf.sprites.playable.GroundMode` with `import com.openggf.game.GroundMode` in every file.

- [ ] **Step 2: Move ShieldType.java**

Same pattern — change package, update all imports:

```bash
grep -rn "import com.openggf.sprites.playable.ShieldType" src/ --include="*.java" -l
```

- [ ] **Step 3: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass (pure refactoring move)

- [ ] **Step 4: Commit**

```bash
git add -u
git add src/main/java/com/openggf/game/GroundMode.java \
        src/main/java/com/openggf/game/ShieldType.java
git commit -m "refactor: move GroundMode and ShieldType from sprites.playable to game package"
```

---

### Task 2: Extract DamageCause from AbstractPlayableSprite

`DamageCause` is currently a nested enum inside `AbstractPlayableSprite` (line ~266). Extract it to a top-level class in `game` package.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Create: `src/main/java/com/openggf/game/DamageCause.java`

- [ ] **Step 1: Read AbstractPlayableSprite.java and find the DamageCause enum**

Find the exact definition and all its values.

- [ ] **Step 2: Create top-level DamageCause.java in game package**

Copy the enum definition, change package to `com.openggf.game`.

- [ ] **Step 3: Remove the inner enum from AbstractPlayableSprite**

Delete the enum definition from inside the class. Add `import com.openggf.game.DamageCause;` to AbstractPlayableSprite.

- [ ] **Step 4: Update all references**

```bash
grep -rn "AbstractPlayableSprite.DamageCause" src/ --include="*.java" -l
grep -rn "DamageCause" src/ --include="*.java" -l
```

Replace `AbstractPlayableSprite.DamageCause` with `DamageCause` and add `import com.openggf.game.DamageCause;` where needed.

- [ ] **Step 5: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add -u
git add src/main/java/com/openggf/game/DamageCause.java
git commit -m "refactor: extract DamageCause from AbstractPlayableSprite to game package"
```

---

### Task 3: Create PlayableEntity Interface

Define the interface based on the verified coupling surface (51 distinct methods called on `player` from `level/objects/`).

**Files:**
- Create: `src/main/java/com/openggf/game/PlayableEntity.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` (add `implements PlayableEntity`)

- [ ] **Step 1: Create PlayableEntity.java**

The interface must include ALL methods called on `player` from `level/objects/`. The verified list (51 methods):

```java
package com.openggf.game;

import com.openggf.physics.Direction;

/**
 * Interface for the player entity as seen by the object/collision system.
 * <p>
 * Breaks the circular dependency between {@code level.objects} and
 * {@code sprites.playable} by providing an abstraction that ObjectManager's
 * SolidContacts and TouchResponses can depend on.
 * <p>
 * Methods match the coupling surface of AbstractPlayableSprite as
 * called from level/objects/ (33 files, 51 methods).
 */
public interface PlayableEntity {
    // Position
    short getCentreX();
    short getCentreY();
    void setCentreX(short x);
    short getX();
    short getY();
    void setY(short y);
    void shiftX(int deltaX);
    void move(short xSpeed, short ySpeed);

    // Dimensions
    int getHeight();
    int getYRadius();
    int getXRadius();
    int getRollHeightAdjustment();

    // Physics
    short getXSpeed();
    short getYSpeed();
    void setXSpeed(short xSpeed);
    void setYSpeed(short ySpeed);
    short getGSpeed();
    void setGSpeed(short gSpeed);
    boolean getAir();
    void setAir(boolean air);
    byte getAngle();
    void setAngle(byte angle);

    // Ground mode
    boolean getRolling();
    void setRolling(boolean rolling);
    boolean getSpindash();
    GroundMode getGroundMode();
    void setGroundMode(GroundMode mode);

    // Object interaction
    boolean isObjectControlled();
    void setOnObject(boolean onObject);
    void setPushing(boolean pushing);
    boolean getPinballMode();
    boolean isCpuControlled();

    // Collision path
    void setTopSolidBit(int bit);
    void setLrbSolidBit(int bit);
    void setLayer(byte layer);
    boolean isHighPriority();
    void setHighPriority(boolean highPriority);

    // Vulnerability
    boolean getDead();
    boolean isDebugMode();
    boolean getInvulnerable();
    boolean hasShield();
    ShieldType getShieldType();
    int getInvincibleFrames();
    int getDoubleJumpFlag();
    boolean isSuperSonic();

    // Damage/hitbox
    boolean getCrouching();
    int getRingCount();
    Direction getDirection();
    boolean applyHurt(int sourceX);
    boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings);
    boolean applyCrushDeath();

    // Scoring
    int incrementBadnikChain();

    // Feature set
    PhysicsFeatureSet getPhysicsFeatureSet();
}
```

IMPORTANT: Before finalizing, verify the exact method signatures by reading AbstractPlayableSprite. The spec's interface is a projection — actual return types and parameter types must match. Pay special attention to:
- `move()` — may take `int` not `short`
- `setAngle()` — may take `short` not `byte`
- `setTopSolidBit()`/`setLrbSolidBit()` — may take `byte` not `int`
- `applyHurt()` return type — verify boolean vs void
- `getCentreX()`/`getCentreY()` — may return `int` not `short`

- [ ] **Step 2: Add `implements PlayableEntity` to AbstractPlayableSprite**

This should compile immediately since all methods already exist on the class. If the compiler reports signature mismatches, adjust the interface to match the actual signatures.

- [ ] **Step 3: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass (adding an interface doesn't change behavior)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/PlayableEntity.java \
        src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java
git commit -m "feat: create PlayableEntity interface, AbstractPlayableSprite implements it"
```

---

### Task 4: Migrate level/objects/ to PlayableEntity

Change all 33 files in `level/objects/` that import `AbstractPlayableSprite` to use `PlayableEntity` instead. This is the core cycle-breaking change.

**Files:**
- Modify: All 33 files listed in Part 2 of the coupling analysis
- Key files: `ObjectManager.java`, `AbstractObjectInstance.java`, `AbstractBadnikInstance.java`, `ObjectInstance.java` (interface), `ObjectServices.java`, `SolidObjectProvider.java`, `TouchResponseProvider.java`, etc.

- [ ] **Step 1: Identify method parameter changes needed**

In each file, find methods that take `AbstractPlayableSprite` as a parameter and change to `PlayableEntity`. Key patterns:
- `update(int frameCounter, AbstractPlayableSprite player)` → `update(int frameCounter, PlayableEntity player)`
- `onTouch(AbstractPlayableSprite player)` → `onTouch(PlayableEntity player)`
- `applySolid(AbstractPlayableSprite player, ...)` → `applySolid(PlayableEntity player, ...)`

- [ ] **Step 2: Update ObjectManager inner classes**

`ObjectManager.SolidContacts` and `ObjectManager.TouchResponses` are the main consumers. Change all `AbstractPlayableSprite player` parameters to `PlayableEntity player`.

- [ ] **Step 3: Update interfaces and base classes**

- `ObjectInstance.java` — if it defines methods with `AbstractPlayableSprite`, change to `PlayableEntity`
- `SolidObjectProvider.java`, `TouchResponseProvider.java`, `TouchResponseListener.java`, `TouchResponseAttackable.java`, `SolidObjectListener.java` — update interface method signatures
- `AbstractObjectInstance.java`, `AbstractBadnikInstance.java` — update method signatures

- [ ] **Step 4: Update remaining level/objects/ files**

For each of the 33 files:
1. Replace `import com.openggf.sprites.playable.AbstractPlayableSprite;` with `import com.openggf.game.PlayableEntity;`
2. Change parameter types from `AbstractPlayableSprite` to `PlayableEntity`
3. If a file needs methods NOT on `PlayableEntity` (e.g., `getSpindashDustController()`), see Task 5

- [ ] **Step 5: Handle getSpindashDustController() in SkidDustObjectInstance and SplashObjectInstance**

These two files call `player.getSpindashDustController()` which returns `SpindashDustController` from `sprites.managers`. Options:
1. Add `getSpindashDustController()` to PlayableEntity (introduces `game → sprites.managers` dep — acceptable for now)
2. Cast `player` to `AbstractPlayableSprite` in these two specific methods only (escape hatch with comment)
3. Pass the dust controller via the object's services injection

Choose based on what's cleanest. If adding to PlayableEntity creates import issues, use option 2 with a TODO comment for future cleanup.

- [ ] **Step 6: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass

- [ ] **Step 7: Verify no level/objects/ files import AbstractPlayableSprite**

Run: `grep -rn "import.*AbstractPlayableSprite" src/main/java/com/openggf/level/objects/ --include="*.java"`
Expected: Zero matches (or only the SpindashDustController escape hatch files if option 2 was chosen)

- [ ] **Step 8: Commit**

```bash
git add -u
git commit -m "refactor: migrate level/objects/ from AbstractPlayableSprite to PlayableEntity (33 files)"
```

---

### Task 5: Create PowerUpSpawner Interface and DefaultPowerUpSpawner

Break the reverse direction: `sprites.playable → level.objects`.

**Files:**
- Create: `src/main/java/com/openggf/game/PowerUpSpawner.java`
- Create: `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (inject spawner during player init)

- [ ] **Step 1: Read AbstractPlayableSprite shield/invincibility code**

Find all instantiation points (from analysis):
- Line ~610-613: `new FireShieldObjectInstance(this)`, `new LightningShieldObjectInstance(this)`, etc.
- Line ~685: `new InvincibilityStarsObjectInstance(this)`
- Line ~1972: `new InstaShieldObjectInstance(this)`
- Line ~2855: `new SplashObjectInstance(...)`
- Line ~2863: `new Sonic1SplashObjectInstance(...)`

Also find how objects are added: `LevelManager.getInstance().getObjectManager().addDynamicObject(...)`

- [ ] **Step 2: Create PowerUpSpawner interface**

```java
package com.openggf.game;

/**
 * Spawns power-up objects (shields, invincibility stars, splash effects)
 * without coupling the player sprite to concrete object implementations.
 */
public interface PowerUpSpawner {
    void spawnShield(PlayableEntity owner, ShieldType type);
    void spawnInvincibilityStars(PlayableEntity owner);
    void spawnInstaShield(PlayableEntity owner);
    void spawnSplash(PlayableEntity owner, int x, int y, boolean isSonic1);
    void removeShield(PlayableEntity owner);
    void removeInvincibilityStars(PlayableEntity owner);
}
```

Note: Review the actual AbstractPlayableSprite code to see if additional methods are needed (e.g., `removeInstaShield`). Adapt the interface to match actual usage.

- [ ] **Step 3: Create DefaultPowerUpSpawner**

In `com.openggf.level.objects`, implement the interface by creating the concrete object instances and adding them via ObjectManager.

- [ ] **Step 4: Add PowerUpSpawner to AbstractPlayableSprite**

Add a `private PowerUpSpawner powerUpSpawner;` field and `setPowerUpSpawner(PowerUpSpawner)` setter. Replace all direct object instantiation with spawner calls.

- [ ] **Step 5: Inject PowerUpSpawner in LevelManager**

In the player initialization code (near `initPlayerSpriteArt()` or similar), inject the spawner:

```java
sprite.setPowerUpSpawner(new DefaultPowerUpSpawner(objectManager));
```

- [ ] **Step 6: Remove level imports from AbstractPlayableSprite**

After migrating to PowerUpSpawner, these imports should no longer be needed:
- `import com.openggf.level.objects.ShieldObjectInstance;`
- `import com.openggf.level.objects.InvincibilityStarsObjectInstance;`
- `import com.openggf.level.objects.SplashObjectInstance;`
- `import com.openggf.level.objects.InstaShieldObjectInstance;`
- `import com.openggf.level.objects.FireShieldObjectInstance;`
- `import com.openggf.level.objects.LightningShieldObjectInstance;`
- `import com.openggf.level.objects.BubbleShieldObjectInstance;`
- `import com.openggf.game.sonic1.objects.Sonic1SplashObjectInstance;`
- `import com.openggf.level.LevelManager;` (for object spawning — verify if still needed for other reasons)

- [ ] **Step 7: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git add -u
git add src/main/java/com/openggf/game/PowerUpSpawner.java \
        src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java
git commit -m "feat: create PowerUpSpawner interface, break sprites.playable → level.objects dependency"
```

---

### Task 6: Final Verification

- [ ] **Step 1: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass

- [ ] **Step 2: Verify no level/objects/ files import AbstractPlayableSprite**

Run: `grep -rn "import.*sprites.playable" src/main/java/com/openggf/level/objects/ --include="*.java"`
Expected: Zero matches (or minimal escape hatches with TODO comments)

- [ ] **Step 3: Verify AbstractPlayableSprite has no level.objects imports**

Run: `grep -rn "import.*level.objects" src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
Expected: Zero matches

- [ ] **Step 4: Verify PlayableEntity has no sprites or level imports**

Run: `grep -rn "import.*sprites\|import.*level" src/main/java/com/openggf/game/PlayableEntity.java`
Expected: Zero matches (only `game` and `physics` imports)

- [ ] **Step 5: Commit (if fixups needed)**

```bash
git add -u
git commit -m "refactor: complete Phase 3 cycle breaking — level/objects decoupled from sprites.playable"
```
