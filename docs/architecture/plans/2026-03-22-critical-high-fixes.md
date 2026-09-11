# Critical & High Severity Architectural Fixes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all Critical (3) and High (10) severity issues plus 2 Medium items (M-6, M-10) from the comprehensive architectural review.

**Architecture:** Fixes are grouped into independent tasks by affected subsystem. Each task produces a compilable codebase. No new features — only fixes to layering violations, stale references, silent failures, and game-specific leaks in game-agnostic code.

**Tech Stack:** Java 21, Maven

---

### Task 1: Fix AbstractBadnikInstance per-frame allocation (C-1)

`getSpawn()` allocates a new `ObjectSpawn` on every call. Since `ObjectInstance.getX()`/`getY()` delegate to `getSpawn()`, and `ObjectManager` calls these multiple times per frame per object, this generates hundreds of short-lived allocations per frame with 30+ badniks on screen. The parent `AbstractObjectInstance` already has `updateDynamicSpawn(x, y)` which lazily caches. All other base classes use it correctly.

**Note:** `AbstractS3kBadnikInstance` also overrides `getSpawn()` with `buildSpawnAt()` — that is a separate allocation issue not addressed here (S3K badniks use different position tracking via `getBodyAnchorX/Y`).

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java:54-60,121-131`

- [ ] **Step 1: Add updateDynamicSpawn call at end of update()**

In `AbstractBadnikInstance.java`, modify the `update()` method to call `updateDynamicSpawn()` after movement completes:

```java
@Override
public final void update(int frameCounter, PlayableEntity player) {
    if (isDestroyed()) {
        return;
    }
    updateMovement(frameCounter, player);
    updateAnimation(frameCounter);
    updateDynamicSpawn(currentX, currentY);
}
```

- [ ] **Step 2: Remove the getSpawn() override**

Delete the entire `getSpawn()` override at lines 121-131. The parent `AbstractObjectInstance.getSpawn()` will now return the cached `dynamicSpawn` (set by `updateDynamicSpawn`), or the original `spawn` before the first update. Keep the `getX()` and `getY()` overrides (lines 138-146) — they return `currentX`/`currentY` directly, which is correct and avoids even the cached spawn lookup.

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java
git commit -m "$(cat <<'EOF'
fix: eliminate per-frame ObjectSpawn allocation in AbstractBadnikInstance

getSpawn() was allocating a new ObjectSpawn on every call. Since
ObjectManager calls getSpawn() multiple times per frame per object for
touch response detection, this generated hundreds of short-lived
allocations per frame. Use the parent's updateDynamicSpawn() cache
instead, matching the pattern used by all other base classes.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Fix SidekickCpuController and TailsRespawnStrategy S2 animation leak (C-3)

`SidekickCpuController` and `TailsRespawnStrategy` are in game-agnostic `sprites.playable` but import `Sonic2AnimationIds`. When S3K is active with a different animation table, these S2-specific IDs index wrong animations. The `CanonicalAnimation` enum already has `FLY` and `DUCK` entries, and `DonorCapabilities.resolveNativeId()` exists for exactly this purpose. The simplest fix is to resolve the IDs from the active `GameModule`'s animation table at construction time.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java` (add method)
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java:4,21,254,287,397`
- Modify: `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java:3,14`

- [ ] **Step 1: Add resolveAnimationId to GameModule**

Add a default method to `GameModule.java` that resolves a `CanonicalAnimation` to the game's native ID:

```java
/**
 * Resolves a canonical animation to this game's native animation ID.
 * Used by game-agnostic code (sidekick controller) to avoid hardcoding
 * game-specific animation IDs.
 *
 * @param canonical the cross-game animation identifier
 * @return the native animation ID, or -1 if not supported
 */
default int resolveAnimationId(CanonicalAnimation canonical) {
    DonorCapabilities donor = getDonorCapabilities();
    return donor != null ? donor.resolveNativeId(canonical) : -1;
}
```

Add `import com.openggf.game.CanonicalAnimation;` if not already present (it's in the same package, so may not be needed).

- [ ] **Step 2: Update SidekickCpuController**

1. Remove `import com.openggf.game.sonic2.constants.Sonic2AnimationIds;`
2. Add:
   ```java
   import com.openggf.game.CanonicalAnimation;
   import com.openggf.game.GameModuleRegistry;
   ```
3. Replace the `FLY_ANIM_ID` static field (line 21) with instance fields:
   ```java
   private final int flyAnimId;
   private final int duckAnimId;
   ```
4. In the constructor, resolve from the active game module:
   ```java
   this.flyAnimId = GameModuleRegistry.getCurrent().resolveAnimationId(CanonicalAnimation.FLY);
   this.duckAnimId = GameModuleRegistry.getCurrent().resolveAnimationId(CanonicalAnimation.DUCK);
   ```
5. Replace ALL `FLY_ANIM_ID` usages with `flyAnimId` (lines 21, 397) and ALL `Sonic2AnimationIds.DUCK.id()` usages with `duckAnimId` (lines 254, 287).

- [ ] **Step 3: Update TailsRespawnStrategy**

1. Remove `import com.openggf.game.sonic2.constants.Sonic2AnimationIds;`
2. Add:
   ```java
   import com.openggf.game.CanonicalAnimation;
   import com.openggf.game.GameModuleRegistry;
   ```
3. Replace the static `FLY_ANIM_ID` field (line 14) with an instance field:
   ```java
   private final int flyAnimId;
   ```
4. In the constructor, resolve:
   ```java
   this.flyAnimId = GameModuleRegistry.getCurrent().resolveAnimationId(CanonicalAnimation.FLY);
   ```
5. Replace all `FLY_ANIM_ID` references with `flyAnimId`.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/GameModule.java \
       src/main/java/com/openggf/sprites/playable/SidekickCpuController.java \
       src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java
git commit -m "$(cat <<'EOF'
fix: remove Sonic2AnimationIds import from game-agnostic sidekick code

SidekickCpuController and TailsRespawnStrategy hardcoded S2 animation
IDs (FLY, DUCK) which index wrong animations when S3K is active. Resolve
IDs at construction time via GameModule.resolveAnimationId() using the
existing CanonicalAnimation/DonorCapabilities infrastructure.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Fix sidekick suppression S2 zone hardcoding (H-1)

`SpriteManager.isCpuSidekickSuppressed()` checks `Sonic2ZoneConstants` directly in game-agnostic code. S3K zones that should suppress sidekicks are never suppressed.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java` (add default method)
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java` (override)
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java:18,637-645`

- [ ] **Step 1: Add isSidekickSuppressedForZone to GameModule**

```java
/**
 * Returns whether sidekick characters should be suppressed in the given zone.
 * Some zones (e.g., S2 Sky Chase, Wing Fortress, Death Egg) have no sidekick
 * during gameplay.
 *
 * @param zoneId the current zone index
 * @return true if sidekicks should be hidden and inactive
 */
default boolean isSidekickSuppressedForZone(int zoneId) {
    return false;
}
```

- [ ] **Step 2: Override in Sonic2GameModule**

```java
@Override
public boolean isSidekickSuppressedForZone(int zoneId) {
    return zoneId == Sonic2ZoneConstants.ZONE_SCZ
        || zoneId == Sonic2ZoneConstants.ZONE_WFZ
        || zoneId == Sonic2ZoneConstants.ZONE_DEZ;
}
```

(S3K has no permanently-suppressed zones; the default `false` is correct. AIZ intro suppression is transient state handled separately.)

- [ ] **Step 3: Update SpriteManager.isCpuSidekickSuppressed()**

Replace the method body:

```java
private boolean isCpuSidekickSuppressed() {
    LevelManager lm = getLevelManager();
    if (lm == null) return false;
    if (GameModuleRegistry.getCurrent().isSidekickSuppressedForZone(lm.getCurrentZone())) return true;
    if (AizPlaneIntroInstance.isSidekickSuppressed()) return true;
    return false;
}
```

Remove `import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;` from `SpriteManager.java`.
Add `import com.openggf.game.GameModuleRegistry;` if not already present.

Note: The `AizPlaneIntroInstance.isSidekickSuppressed()` reference remains as a transient state check. It's a static boolean accessor with no game-specific type leakage beyond the class name.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/GameModule.java \
       src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java \
       src/main/java/com/openggf/sprites/managers/SpriteManager.java
git commit -m "$(cat <<'EOF'
fix: move sidekick zone suppression from hardcoded S2 IDs to GameModule

SpriteManager.isCpuSidekickSuppressed() hardcoded Sonic2ZoneConstants,
meaning S3K zones that should suppress sidekicks were never suppressed.
Add GameModule.isSidekickSuppressedForZone() with per-game overrides.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Migrate AbstractObjectInstance.isPlayerRiding() to services() (H-2)

The non-static `isPlayerRiding()` method bypasses the injectable `services()` by calling `LevelManager.getInstance()` directly. The static methods (`spawnDynamicObject`, `getRenderManager`, `getRenderer`) must remain static for constructor/factory contexts, but `isPlayerRiding()` is only called from instance methods where `services()` is available.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java:170-179`

- [ ] **Step 1: Rewrite isPlayerRiding() to use services()**

Replace the method:

```java
/**
 * Returns true if any player is currently riding (standing on) this object.
 * Uses the injected services handle.
 */
protected boolean isPlayerRiding() {
    return services().objectManager().isAnyPlayerRiding(this);
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/objects/AbstractObjectInstance.java
git commit -m "$(cat <<'EOF'
refactor: migrate isPlayerRiding() from LevelManager.getInstance() to services()

The non-static isPlayerRiding() bypassed the injectable services() handle.
Migrate to services().objectManager().isAnyPlayerRiding(this).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Fix DefaultObjectServices.spawnLostRings() silent no-op (H-3)

The `instanceof` cast silently drops ring spawning for any non-`AbstractPlayableSprite` `PlayableEntity` implementation. Add logging so the failure is visible.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java:77-82`

- [ ] **Step 1: Add a LOG field and logging to the else branch**

Add at the top of the class (after class declaration):

```java
private static final java.util.logging.Logger LOG =
    java.util.logging.Logger.getLogger(DefaultObjectServices.class.getName());
```

Replace the `spawnLostRings` method:

```java
@Override
public void spawnLostRings(PlayableEntity player, int frameCounter) {
    if (player instanceof com.openggf.sprites.playable.AbstractPlayableSprite aps) {
        LevelManager.getInstance().spawnLostRings(aps, frameCounter);
    } else {
        LOG.warning("spawnLostRings: player is not AbstractPlayableSprite, rings not spawned");
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/objects/DefaultObjectServices.java
git commit -m "$(cat <<'EOF'
fix: log warning when spawnLostRings silently skips non-APS player

The instanceof cast in spawnLostRings silently no-ops for any future
PlayableEntity that isn't AbstractPlayableSprite. Add a warning log
so the failure is visible during development and testing.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Document camera()/gameState() governance rule (H-4)

Both `ObjectServices` and `GameServices` expose `camera()` and `gameState()`. Document which is canonical for object code.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java:40-43`
- Modify: `src/main/java/com/openggf/game/GameServices.java:16,36`

- [ ] **Step 1: Add Javadoc to ObjectServices.camera() and gameState()**

```java
/**
 * Returns the camera for position queries and bounds checks.
 * <p>
 * <b>Governance:</b> Object instance code (subclasses of {@link AbstractObjectInstance})
 * should use this method, not {@link com.openggf.game.GameServices#camera()}.
 * {@code GameServices.camera()} is for non-object code (HUD, level loading, etc.).
 */
Camera camera();

/**
 * Returns the game state manager for score, lives, and emerald tracking.
 * <p>
 * <b>Governance:</b> Object instance code should use this method, not
 * {@link com.openggf.game.GameServices#gameState()}.
 */
GameStateManager gameState();
```

- [ ] **Step 2: Add Javadoc to GameServices.camera() and gameState()**

```java
/**
 * Global camera accessor for non-object code (HUD, level loading, rendering).
 * Object instances should use {@code services().camera()} instead.
 */
public static Camera camera() {
    return Camera.getInstance();
}

/**
 * Global game state accessor for non-object code.
 * Object instances should use {@code services().gameState()} instead.
 */
public static GameStateManager gameState() {
    return GameStateManager.getInstance();
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectServices.java \
       src/main/java/com/openggf/game/GameServices.java
git commit -m "$(cat <<'EOF'
docs: add governance rules for dual camera()/gameState() accessors

Document that object instance code should use services().camera() and
services().gameState(), while GameServices equivalents are for non-object
code (HUD, level loading, rendering).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Fix CrossGameFeatureProvider hardcoded spindash table (H-5)

`buildHybridFeatureSet()` hardcodes the S2 spindash speed table instead of sourcing from the donor's `PhysicsFeatureSet`. The field is `String donorGameId` (line 53), and `GameId.fromCode()` exists for conversion.

**Files:**
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java:388-390`

- [ ] **Step 1: Source spindash table from donor feature set**

Replace lines 388-390:

```java
// Source spindash speed table from the donor game's physics feature set
// rather than hardcoding S2 values
PhysicsFeatureSet donorFeatureSet = resolveDonorFeatureSet();
short[] spindashSpeedTable = donorCapabilities.hasSpindash()
        ? donorFeatureSet.spindashSpeedTable()
        : null;
```

- [ ] **Step 2: Add resolveDonorFeatureSet() helper**

Add a private method that resolves the donor game's feature set. The field is `String donorGameId`, so use `GameId.fromCode()`:

```java
/**
 * Resolves the donor game's PhysicsFeatureSet for sourcing constants
 * (spindash speed table, etc.) without hardcoding game-specific values.
 */
private PhysicsFeatureSet resolveDonorFeatureSet() {
    return switch (GameId.fromCode(donorGameId)) {
        case S1 -> PhysicsFeatureSet.SONIC_1;
        case S2 -> PhysicsFeatureSet.SONIC_2;
        case S3K -> PhysicsFeatureSet.SONIC_3K;
    };
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/CrossGameFeatureProvider.java
git commit -m "$(cat <<'EOF'
fix: source spindash speed table from donor PhysicsFeatureSet

buildHybridFeatureSet() hardcoded the S2 spindash speed table literal
instead of sourcing it from the donor game's PhysicsFeatureSet. Future
donor games with different release speeds would get wrong values.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Fix BossPaletteFlasher stale GraphicsManager reference (H-6)

`BossPaletteFlasher` eagerly captures `GraphicsManager.getInstance()` at field init time. If the singleton is reset between tests, the cached reference goes stale.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java:301,360-364`

- [ ] **Step 1: Remove the cached field, fetch lazily**

Delete line 301 (`private final GraphicsManager graphicsManager = GraphicsManager.getInstance();`).

Replace `uploadPaletteToGpu` (lines 360-365):
```java
private void uploadPaletteToGpu(Palette palette) {
    GraphicsManager gm = GraphicsManager.getInstance();
    if (gm.isGlInitialized()) {
        int paletteIndex = getPaletteLineForFlash();
        gm.cachePaletteTexture(palette, paletteIndex);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java
git commit -m "$(cat <<'EOF'
fix: lazy-fetch GraphicsManager in BossPaletteFlasher

The eager field capture at construction time could hold a stale reference
after GraphicsManager.resetInstance() in headless tests. Fetch lazily in
uploadPaletteToGpu() instead.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Add CrossGameFeatureProvider to test teardown path (H-8)

`CrossGameFeatureProvider` is not reset by `GameContext.forTesting()` or any `LevelInitProfile` teardown, causing state leaks between tests.

**Files:**
- Modify: `src/main/java/com/openggf/game/AbstractLevelInitProfile.java:219-261,265-286`

- [ ] **Step 1: Add CrossGameFeatureProvider reset step to levelTeardownSteps()**

In `levelTeardownSteps()`, add a new `InitStep` as the **2nd element** in the `List.of()` call (after `ResetAudio`, before `levelEventTeardownStep()`):

```java
// Undoes cross-game feature donation state (donor ROM, SMPS loader, physics)
new InitStep("ResetCrossGameFeatures", "Undoes CrossGameFeatureProvider.initialize()",
    () -> CrossGameFeatureProvider.getInstance().resetState()),
```

`CrossGameFeatureProvider` is in the same `com.openggf.game` package — no import needed.

- [ ] **Step 2: Add same step to perTestResetSteps()**

In `perTestResetSteps()`, add the same step as the **2nd element** (after `perTestLeadStep()`, before `ResetParallax`):

```java
new InitStep("ResetCrossGameFeatures", "Undoes CrossGameFeatureProvider.initialize()",
    () -> CrossGameFeatureProvider.getInstance().resetState()),
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/AbstractLevelInitProfile.java
git commit -m "$(cat <<'EOF'
fix: add CrossGameFeatureProvider to test teardown path

CrossGameFeatureProvider was not reset by levelTeardownSteps() or
perTestResetSteps(), causing donor ROM/physics state to leak between
tests. Add resetState() step to both teardown paths.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Add TerrainCollisionManager reset to CollisionSystem.resetState() (H-9)

`CollisionSystem.resetState()` clears its own fields but doesn't reset `TerrainCollisionManager`, which holds a pooled `SensorResult` array.

**Files:**
- Modify: `src/main/java/com/openggf/physics/CollisionSystem.java:54-59`

- [ ] **Step 1: Add terrainCollisionManager reset**

Modify `resetState()`:

```java
public void resetState() {
    terrainCollisionManager.resetState();
    objectManager = null;
    trace = NoOpCollisionTrace.INSTANCE;
    unifiedPipelineEnabled = false;
    shadowModeEnabled = false;
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/physics/CollisionSystem.java
git commit -m "$(cat <<'EOF'
fix: reset TerrainCollisionManager in CollisionSystem.resetState()

CollisionSystem.resetState() cleared its own fields but did not
propagate to TerrainCollisionManager, which holds pooled SensorResult
slots that could carry over between tests.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Fix RomManager Javadoc referencing non-existent resetState() (H-10)

The `@Deprecated` annotation on `resetInstance()` says "prefer `resetState()`" but `RomManager` has no such method.

**Files:**
- Modify: `src/main/java/com/openggf/data/RomManager.java:173-177`

- [ ] **Step 1: Fix the Javadoc**

Replace lines 173-177:

```java
/**
 * @deprecated For test teardown, prefer calling {@link #close()} on the instance.
 * This method remains functional for ROM switching but should not
 * be used for inter-test cleanup.
 */
@Deprecated
public static synchronized void resetInstance() {
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/data/RomManager.java
git commit -m "$(cat <<'EOF'
docs: fix RomManager.resetInstance() Javadoc referencing non-existent resetState()

The @Deprecated annotation said 'prefer resetState()' but RomManager
has no such method. Corrected to reference close() instead.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Fix GameContext.forTesting() stale Javadoc (M-10)

The Javadoc at `GameContext.java:87-89` says CollisionSystem and FadeManager are "destroyed and recreated (via `resetInstance()`)" — but the actual teardown calls `resetState()`.

**Files:**
- Modify: `src/main/java/com/openggf/GameContext.java:86-89`

- [ ] **Step 1: Fix the Javadoc**

Replace lines 86-89:

```java
 * <b>Warning:</b> Callers should not hold references to singleton instances
 * across a {@code forTesting()} call. Singletons are reset in-place via
 * {@code resetState()}, but the generation counter invalidates any
 * previously issued {@code GameContext} instances.
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/GameContext.java
git commit -m "$(cat <<'EOF'
docs: fix GameContext.forTesting() Javadoc claiming resetInstance() usage

The comment said 'destroyed and recreated (via resetInstance())' but the
actual teardown uses resetState() for in-place reset. Corrected to match
the actual implementation.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Remove shadowed DEFEAT_TIMER_START constants in boss subclasses (M-6)

`DEFEAT_TIMER_START` is declared as `protected static final` in `AbstractBossInstance` (line 31) but re-declared as `private static final` with the identical value `0xB3` in `Sonic2EHZBossInstance` (line 68) and `Sonic2HTZBossInstance` (line 74). This shadowing means a future value correction in the base would not propagate. Similarly, `EXPLOSION_INTERVAL` is shadowed within `BossDefeatSequencer` inner class (line 391) of the same file.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2EHZBossInstance.java:68`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2HTZBossInstance.java:74`
- Modify: `src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java:391`

- [ ] **Step 1: Remove DEFEAT_TIMER_START from Sonic2EHZBossInstance**

Delete line 68: `private static final int DEFEAT_TIMER_START = 0xB3;`

The class already extends `AbstractBossInstance` and the `protected` base constant is accessible. Verify the usage at line 385 (`defeatTimer = DEFEAT_TIMER_START;`) still compiles.

- [ ] **Step 2: Remove DEFEAT_TIMER_START from Sonic2HTZBossInstance**

Delete line 74: `private static final int DEFEAT_TIMER_START = 0xB3;`

Verify usage at line 570 still compiles.

- [ ] **Step 3: Remove shadowed EXPLOSION_INTERVAL from BossDefeatSequencer**

In `AbstractBossInstance.java`, delete line 391: `private static final int EXPLOSION_INTERVAL = 8;`

The inner class `BossDefeatSequencer` can access the outer class's `protected static final EXPLOSION_INTERVAL` at line 39. Verify usage at line 432 still compiles.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2EHZBossInstance.java \
       src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2HTZBossInstance.java \
       src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java
git commit -m "$(cat <<'EOF'
fix: remove shadowed DEFEAT_TIMER_START and EXPLOSION_INTERVAL constants

EHZ and HTZ boss classes redeclared DEFEAT_TIMER_START (0xB3) as private,
hiding the identical protected constant in AbstractBossInstance. Similarly,
BossDefeatSequencer inner class shadowed EXPLOSION_INTERVAL. A future
value correction in the base class would not have propagated.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: Fix GameModule.getPhysicsProvider() S2 default import

`GameModule` imports `Sonic2PhysicsProvider` for its default `getPhysicsProvider()` return value — a game-specific import in a game-agnostic interface.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java:7,218-220`

- [ ] **Step 1: Remove the default and make it an abstract method**

Since all three game modules (`Sonic1GameModule`, `Sonic2GameModule`, `Sonic3kGameModule`) already override `getPhysicsProvider()`, the default is dead code. Replace lines 217-220:

```java
/**
 * Returns the physics provider for this game.
 * Provides per-character physics profiles, modifier rules (water/speed shoes),
 * and feature flags (spindash availability).
 *
 * @return the physics provider
 */
PhysicsProvider getPhysicsProvider();
```

Remove: `import com.openggf.game.sonic2.Sonic2PhysicsProvider;`

If compilation fails (meaning some module doesn't override it), add the override to that module instead.

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameModule.java
git commit -m "$(cat <<'EOF'
refactor: remove Sonic2PhysicsProvider default from GameModule interface

GameModule.getPhysicsProvider() defaulted to Sonic2PhysicsProvider,
leaking a game-specific import into the game-agnostic interface. All
three game modules already override this method, so the default was
dead code.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Final verification

- [ ] **Step 1: Run full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Verify no new game-specific imports in game-agnostic code**

Run: `grep -r "import com.openggf.game.sonic" src/main/java/com/openggf/level/objects/ --include="*.java"`
Expected: only `DefaultPowerUpSpawner.java` (documented bridge)

Run: `grep -r "import com.openggf.game.sonic2" src/main/java/com/openggf/sprites/ --include="*.java"`
Expected: no matches (SidekickCpuController and TailsRespawnStrategy cleaned up)

Run: `grep "Sonic2PhysicsProvider" src/main/java/com/openggf/game/GameModule.java`
Expected: no matches

Run: `grep "Sonic2ZoneConstants" src/main/java/com/openggf/sprites/managers/SpriteManager.java`
Expected: no matches
