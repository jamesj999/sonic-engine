# ROM-Driven Initialization Profiles Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the fragile 8-phase manual singleton reset in `GameContext.forTesting()` and `TestEnvironment.resetPerTest()` with game-specific initialization profiles that each game module provides.

**Architecture:** Each `GameModule` implementation (S1, S2, S3K) provides a `LevelInitProfile` declaring ordered teardown and per-test reset steps as `InitStep` lists. `GameContext.forTesting()` and `TestEnvironment.resetPerTest()` execute these profiles instead of hardcoded sequences. This fixes the known limitation where only S2's level event manager is reset. Level load profiles (the 44/57/65-step ROM sequences from the design doc) are deferred to a follow-up plan.

**Tech Stack:** Java 21 (records), JUnit 5, Maven

**Design doc:** `docs/architecture/designs/2026-02-27-rom-driven-init-profiles-design.md`

---

### Task 1: Create Core Abstractions

**Files:**
- Create: `src/main/java/com/openggf/game/InitStep.java`
- Create: `src/main/java/com/openggf/game/StaticFixup.java`
- Create: `src/main/java/com/openggf/game/LevelInitProfile.java`
- Create: `src/test/java/com/openggf/game/TestInitStep.java`

**Step 1: Write the failing test**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TestInitStep {

    @Test
    void initStepExecutesAction() {
        AtomicBoolean ran = new AtomicBoolean(false);
        var step = new InitStep("TestStep", "test.asm:1", () -> ran.set(true));
        step.execute();
        assertTrue(ran.get());
    }

    @Test
    void initStepFieldsAccessible() {
        var step = new InitStep("LoadTiles", "s2.asm:4934", () -> {});
        assertEquals("LoadTiles", step.name());
        assertEquals("s2.asm:4934", step.romRoutine());
        assertNotNull(step.action());
    }

    @Test
    void staticFixupAppliesAction() {
        AtomicBoolean applied = new AtomicBoolean(false);
        var fixup = new StaticFixup("WireGroundSensor",
            "Static ref goes stale after reset",
            () -> applied.set(true));
        fixup.apply();
        assertTrue(applied.get());
    }

    @Test
    void stepsExecuteInDeclaredOrder() {
        var order = new AtomicInteger(0);
        var steps = List.of(
            new InitStep("First", "test:1", () -> assertEquals(0, order.getAndIncrement())),
            new InitStep("Second", "test:2", () -> assertEquals(1, order.getAndIncrement())),
            new InitStep("Third", "test:3", () -> assertEquals(2, order.getAndIncrement()))
        );
        for (var step : steps) {
            step.execute();
        }
        assertEquals(3, order.get());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.TestInitStep -pl .`
Expected: FAIL — compilation error, `InitStep` class not found.

**Step 3: Write minimal implementation**

`src/main/java/com/openggf/game/InitStep.java`:
```java
package com.openggf.game;

/**
 * A single step in a level initialization or teardown sequence.
 *
 * @param name        short identifier, e.g. "LoadZoneTiles"
 * @param romRoutine  disassembly reference, e.g. "s2.asm:Level, line 4934"
 * @param action      the operation to execute
 */
public record InitStep(
    String name,
    String romRoutine,
    Runnable action
) {
    public void execute() {
        action.run();
    }
}
```

`src/main/java/com/openggf/game/StaticFixup.java`:
```java
package com.openggf.game;

/**
 * A post-teardown static field fixup (e.g. re-wiring GroundSensor).
 *
 * @param name   short identifier
 * @param reason why this fixup is needed
 * @param action the fixup operation
 */
public record StaticFixup(
    String name,
    String reason,
    Runnable action
) {
    public void apply() {
        action.run();
    }
}
```

`src/main/java/com/openggf/game/LevelInitProfile.java`:
```java
package com.openggf.game;

import java.util.List;

/**
 * Declares the ordered initialization and teardown sequences for a game.
 * <p>
 * Each game module provides its own profile. The engine executes steps in
 * declared order — no topological sorting, no dependency resolution.
 * The disassembly IS the dependency graph.
 */
public interface LevelInitProfile {

    /** Ordered steps for entering a level (title card through control unlock).
     *  Deferred — returns empty list until production level loading is wired. */
    List<InitStep> levelLoadSteps();

    /** Ordered steps for tearing down all singletons before the next level load.
     *  Replaces GameContext.forTesting() phases 2-8. */
    List<InitStep> levelTeardownSteps();

    /** Subset of teardown safe for per-test reset (preserves level data).
     *  Replaces TestEnvironment.resetPerTest(). */
    List<InitStep> perTestResetSteps();

    /** Static field fixups applied after full teardown (e.g. GroundSensor wiring).
     *  Only used by levelTeardownSteps() path, not per-test reset. */
    List<StaticFixup> postTeardownFixups();
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.TestInitStep`
Expected: PASS (4 tests).

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/InitStep.java \
       src/main/java/com/openggf/game/StaticFixup.java \
       src/main/java/com/openggf/game/LevelInitProfile.java \
       src/test/java/com/openggf/game/TestInitStep.java
git commit -m "feat: add InitStep, StaticFixup, and LevelInitProfile core abstractions"
```

---

### Task 2: Implement Sonic2LevelInitProfile

Maps the current `GameContext.forTesting()` phases 2-8 (12 operations) and `TestEnvironment.resetPerTest()` (9 S2-relevant operations) to explicit `InitStep` lists.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java`
- Create: `src/test/java/com/openggf/game/sonic2/TestSonic2LevelInitProfile.java`
- Reference: `src/main/java/com/openggf/GameContext.java:82-115`
- Reference: `src/test/java/com/openggf/tests/TestEnvironment.java:49-71`

**Step 1: Write the failing test**

These tests verify the STRUCTURE of the profile (step names, count, ordering) without executing steps. No ROM or OpenGL required.

```java
package com.openggf.game.sonic2;

import com.openggf.game.InitStep;
import com.openggf.game.StaticFixup;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestSonic2LevelInitProfile {

    private final Sonic2LevelInitProfile profile = new Sonic2LevelInitProfile();

    @Test
    void teardownStepsMatchGameContextPhases2Through8() {
        List<InitStep> steps = profile.levelTeardownSteps();

        // GameContext.forTesting() phases 2-8 have 12 operations
        assertEquals(12, steps.size());

        // Verify ordering matches GameContext phases
        assertEquals("ResetAudio", steps.get(0).name());
        assertEquals("ResetS2LevelEvents", steps.get(1).name());
        assertEquals("ResetParallax", steps.get(2).name());
        assertEquals("ResetLevelManager", steps.get(3).name());
        assertEquals("ResetSprites", steps.get(4).name());
        assertEquals("ResetCollision", steps.get(5).name());
        assertEquals("ResetCamera", steps.get(6).name());
        assertEquals("ResetGraphics", steps.get(7).name());
        assertEquals("ResetFade", steps.get(8).name());
        assertEquals("ResetGameState", steps.get(9).name());
        assertEquals("ResetTimers", steps.get(10).name());
        assertEquals("ResetWater", steps.get(11).name());
    }

    @Test
    void perTestResetOmitsAudioLevelManagerAndGraphics() {
        List<InitStep> steps = profile.perTestResetSteps();

        // Per-test reset: 9 operations (no audio, no level manager, no graphics)
        assertEquals(9, steps.size());

        assertEquals("ResetS2LevelEvents", steps.get(0).name());
        assertEquals("ResetParallax", steps.get(1).name());
        assertEquals("ClearSprites", steps.get(2).name());
        assertEquals("ResetCollision", steps.get(3).name());
        assertEquals("ResetCamera", steps.get(4).name());
        assertEquals("ResetFade", steps.get(5).name());
        assertEquals("ResetGameState", steps.get(6).name());
        assertEquals("ResetTimers", steps.get(7).name());
        assertEquals("ResetWater", steps.get(8).name());
    }

    @Test
    void postTeardownFixupsContainGroundSensorWiring() {
        List<StaticFixup> fixups = profile.postTeardownFixups();

        assertEquals(1, fixups.size());
        assertEquals("WireGroundSensor", fixups.get(0).name());
    }

    @Test
    void levelLoadStepsEmptyForNow() {
        assertTrue(profile.levelLoadSteps().isEmpty());
    }

    @Test
    void teardownStepsAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
            () -> profile.levelTeardownSteps().add(null));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.sonic2.TestSonic2LevelInitProfile`
Expected: FAIL — `Sonic2LevelInitProfile` not found.

**Step 3: Write minimal implementation**

```java
package com.openggf.game.sonic2;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.InitStep;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.StaticFixup;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;

import java.util.List;

/**
 * Sonic 2 level initialization profile.
 * <p>
 * Teardown steps transcribe {@code GameContext.forTesting()} phases 2-8.
 * Per-test reset steps transcribe {@code TestEnvironment.resetPerTest()}
 * (excluding the S3K-specific {@code AizPlaneIntroInstance} reset).
 */
public class Sonic2LevelInitProfile implements LevelInitProfile {

    @Override
    public List<InitStep> levelLoadSteps() {
        // Deferred to follow-up plan (design doc Phase 3)
        return List.of();
    }

    @Override
    public List<InitStep> levelTeardownSteps() {
        return List.of(
            // Phase 2: Audio
            new InitStep("ResetAudio",
                "Engine: clear SMPS loader cache",
                () -> AudioManager.getInstance().resetState()),
            // Phase 3: Level subsystems
            new InitStep("ResetS2LevelEvents",
                "Engine: clear S2 level event state",
                () -> Sonic2LevelEventManager.getInstance().resetState()),
            new InitStep("ResetParallax",
                "Engine: clear parallax state",
                () -> ParallaxManager.getInstance().resetState()),
            new InitStep("ResetLevelManager",
                "Engine: clear level manager state",
                () -> LevelManager.getInstance().resetState()),
            // Phase 4: Sprites
            new InitStep("ResetSprites",
                "Engine: full sprite manager reset",
                () -> SpriteManager.getInstance().resetState()),
            // Phase 5: Physics
            new InitStep("ResetCollision",
                "Engine: destroy and recreate collision system",
                CollisionSystem::resetInstance),
            // Phase 6: Camera and graphics
            new InitStep("ResetCamera",
                "Engine: clear camera state",
                () -> Camera.getInstance().resetState()),
            new InitStep("ResetGraphics",
                "Engine: clear graphics manager state",
                () -> GraphicsManager.getInstance().resetState()),
            new InitStep("ResetFade",
                "Engine: destroy and recreate fade manager",
                FadeManager::resetInstance),
            // Phase 7: Game state and timers
            new InitStep("ResetGameState",
                "Engine: clear score, lives, emeralds",
                () -> GameServices.gameState().resetSession()),
            new InitStep("ResetTimers",
                "Engine: clear timer manager state",
                () -> TimerManager.getInstance().resetState()),
            new InitStep("ResetWater",
                "Engine: clear water system state",
                () -> WaterSystem.getInstance().reset())
        );
    }

    @Override
    public List<InitStep> perTestResetSteps() {
        return List.of(
            new InitStep("ResetS2LevelEvents",
                "Engine: clear S2 level event state",
                () -> Sonic2LevelEventManager.getInstance().resetState()),
            new InitStep("ResetParallax",
                "Engine: clear parallax state",
                () -> ParallaxManager.getInstance().resetState()),
            new InitStep("ClearSprites",
                "Engine: clear sprite list (lighter than full reset)",
                () -> SpriteManager.getInstance().clearAllSprites()),
            new InitStep("ResetCollision",
                "Engine: destroy and recreate collision system",
                CollisionSystem::resetInstance),
            new InitStep("ResetCamera",
                "Engine: clear camera state",
                () -> Camera.getInstance().resetState()),
            new InitStep("ResetFade",
                "Engine: destroy and recreate fade manager",
                FadeManager::resetInstance),
            new InitStep("ResetGameState",
                "Engine: clear score, lives, emeralds",
                () -> GameServices.gameState().resetSession()),
            new InitStep("ResetTimers",
                "Engine: clear timer manager state",
                () -> TimerManager.getInstance().resetState()),
            new InitStep("ResetWater",
                "Engine: clear water system state",
                () -> WaterSystem.getInstance().reset())
        );
    }

    @Override
    public List<StaticFixup> postTeardownFixups() {
        return List.of(
            new StaticFixup("WireGroundSensor",
                "GroundSensor uses static LevelManager ref that goes stale after resetState()",
                () -> GroundSensor.setLevelManager(LevelManager.getInstance()))
        );
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.sonic2.TestSonic2LevelInitProfile`
Expected: PASS (5 tests).

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java \
       src/test/java/com/openggf/game/sonic2/TestSonic2LevelInitProfile.java
git commit -m "feat: implement Sonic2LevelInitProfile (teardown + per-test reset)"
```

---

### Task 3: Implement Sonic1LevelInitProfile

Same structure as S2 but with `Sonic1LevelEventManager` instead of `Sonic2LevelEventManager`.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java`
- Create: `src/test/java/com/openggf/game/sonic1/TestSonic1LevelInitProfile.java`

**Step 1: Verify Sonic1LevelEventManager has resetState()**

Read `AbstractLevelEventManager` (in `com.openggf.game`) and confirm `resetState()` is defined there or on `Sonic1LevelEventManager` directly. If it's only on `Sonic2LevelEventManager`, add it to the abstract class first.

Run: Search for `resetState` in `AbstractLevelEventManager.java` and `Sonic1LevelEventManager.java`.

**Step 2: Write the failing test**

```java
package com.openggf.game.sonic1;

import com.openggf.game.InitStep;
import com.openggf.game.StaticFixup;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestSonic1LevelInitProfile {

    private final Sonic1LevelInitProfile profile = new Sonic1LevelInitProfile();

    @Test
    void teardownHas12Steps() {
        List<InitStep> steps = profile.levelTeardownSteps();
        assertEquals(12, steps.size());
        // S1 uses Sonic1LevelEventManager instead of S2's
        assertEquals("ResetS1LevelEvents", steps.get(1).name());
    }

    @Test
    void perTestResetHas9Steps() {
        List<InitStep> steps = profile.perTestResetSteps();
        assertEquals(9, steps.size());
        assertEquals("ResetS1LevelEvents", steps.get(0).name());
    }

    @Test
    void postTeardownFixupsContainGroundSensorOnly() {
        List<StaticFixup> fixups = profile.postTeardownFixups();
        assertEquals(1, fixups.size());
        assertEquals("WireGroundSensor", fixups.get(0).name());
    }

    @Test
    void levelLoadStepsEmptyForNow() {
        assertTrue(profile.levelLoadSteps().isEmpty());
    }
}
```

**Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.sonic1.TestSonic1LevelInitProfile`
Expected: FAIL — class not found.

**Step 4: Write minimal implementation**

Same structure as `Sonic2LevelInitProfile` but:
- Replace `Sonic2LevelEventManager` with `Sonic1LevelEventManager`
- Step name: `"ResetS1LevelEvents"` instead of `"ResetS2LevelEvents"`
- No `AizPlaneIntroInstance` fixup (S3K-only)

```java
package com.openggf.game.sonic1;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.InitStep;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.StaticFixup;
import com.openggf.game.sonic1.events.Sonic1LevelEventManager;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;

import java.util.List;

/**
 * Sonic 1 level initialization profile.
 * <p>
 * Structurally identical to S2 profile but resets
 * {@link Sonic1LevelEventManager} instead of S2's.
 */
public class Sonic1LevelInitProfile implements LevelInitProfile {

    @Override
    public List<InitStep> levelLoadSteps() {
        return List.of();
    }

    @Override
    public List<InitStep> levelTeardownSteps() {
        return List.of(
            new InitStep("ResetAudio", "Engine: clear SMPS loader cache",
                () -> AudioManager.getInstance().resetState()),
            new InitStep("ResetS1LevelEvents", "Engine: clear S1 level event state",
                () -> Sonic1LevelEventManager.getInstance().resetState()),
            new InitStep("ResetParallax", "Engine: clear parallax state",
                () -> ParallaxManager.getInstance().resetState()),
            new InitStep("ResetLevelManager", "Engine: clear level manager state",
                () -> LevelManager.getInstance().resetState()),
            new InitStep("ResetSprites", "Engine: full sprite manager reset",
                () -> SpriteManager.getInstance().resetState()),
            new InitStep("ResetCollision", "Engine: destroy and recreate collision system",
                CollisionSystem::resetInstance),
            new InitStep("ResetCamera", "Engine: clear camera state",
                () -> Camera.getInstance().resetState()),
            new InitStep("ResetGraphics", "Engine: clear graphics manager state",
                () -> GraphicsManager.getInstance().resetState()),
            new InitStep("ResetFade", "Engine: destroy and recreate fade manager",
                FadeManager::resetInstance),
            new InitStep("ResetGameState", "Engine: clear score, lives, emeralds",
                () -> GameServices.gameState().resetSession()),
            new InitStep("ResetTimers", "Engine: clear timer manager state",
                () -> TimerManager.getInstance().resetState()),
            new InitStep("ResetWater", "Engine: clear water system state",
                () -> WaterSystem.getInstance().reset())
        );
    }

    @Override
    public List<InitStep> perTestResetSteps() {
        return List.of(
            new InitStep("ResetS1LevelEvents", "Engine: clear S1 level event state",
                () -> Sonic1LevelEventManager.getInstance().resetState()),
            new InitStep("ResetParallax", "Engine: clear parallax state",
                () -> ParallaxManager.getInstance().resetState()),
            new InitStep("ClearSprites", "Engine: clear sprite list",
                () -> SpriteManager.getInstance().clearAllSprites()),
            new InitStep("ResetCollision", "Engine: destroy and recreate collision system",
                CollisionSystem::resetInstance),
            new InitStep("ResetCamera", "Engine: clear camera state",
                () -> Camera.getInstance().resetState()),
            new InitStep("ResetFade", "Engine: destroy and recreate fade manager",
                FadeManager::resetInstance),
            new InitStep("ResetGameState", "Engine: clear score, lives, emeralds",
                () -> GameServices.gameState().resetSession()),
            new InitStep("ResetTimers", "Engine: clear timer manager state",
                () -> TimerManager.getInstance().resetState()),
            new InitStep("ResetWater", "Engine: clear water system state",
                () -> WaterSystem.getInstance().reset())
        );
    }

    @Override
    public List<StaticFixup> postTeardownFixups() {
        return List.of(
            new StaticFixup("WireGroundSensor",
                "GroundSensor uses static LevelManager ref that goes stale after resetState()",
                () -> GroundSensor.setLevelManager(LevelManager.getInstance()))
        );
    }
}
```

**Important:** Verify the import path for `Sonic1LevelEventManager`. From `Sonic1GameModule`, it's accessed via `getLevelEventProvider()` which returns `Sonic1LevelEventManager.getInstance()`. The class is in `com.openggf.game.sonic1.events`. Adjust the import if the package differs.

**Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.sonic1.TestSonic1LevelInitProfile`
Expected: PASS (4 tests).

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java \
       src/test/java/com/openggf/game/sonic1/TestSonic1LevelInitProfile.java
git commit -m "feat: implement Sonic1LevelInitProfile (teardown + per-test reset)"
```

---

### Task 4: Implement Sonic3kLevelInitProfile

S3K profile adds `AizPlaneIntroInstance.setSidekickSuppressed(false)` to per-test reset and post-teardown fixups. Uses `Sonic3kLevelEventManager`.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java`

**Step 1: Verify Sonic3kLevelEventManager has resetState()**

Same verification as Task 3 Step 1 — search for `resetState` on `Sonic3kLevelEventManager`. It should inherit from `AbstractLevelEventManager`.

**Step 2: Write the failing test**

```java
package com.openggf.game.sonic3k;

import com.openggf.game.InitStep;
import com.openggf.game.StaticFixup;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestSonic3kLevelInitProfile {

    private final Sonic3kLevelInitProfile profile = new Sonic3kLevelInitProfile();

    @Test
    void teardownHas12Steps() {
        List<InitStep> steps = profile.levelTeardownSteps();
        assertEquals(12, steps.size());
        assertEquals("ResetS3kLevelEvents", steps.get(1).name());
    }

    @Test
    void perTestResetHas10StepsIncludingAizFixup() {
        List<InitStep> steps = profile.perTestResetSteps();
        // S3K has 10: same 9 as S2 + AizPlaneIntroInstance reset
        assertEquals(10, steps.size());
        assertEquals("ResetS3kLevelEvents", steps.get(0).name());
        assertEquals("ResetAizSidekickSuppression", steps.get(1).name());
    }

    @Test
    void postTeardownFixupsContainGroundSensorAndAiz() {
        List<StaticFixup> fixups = profile.postTeardownFixups();
        // S3K has 2 fixups: GroundSensor + AizPlaneIntroInstance
        assertEquals(2, fixups.size());
        assertEquals("WireGroundSensor", fixups.get(0).name());
        assertEquals("ResetAizSidekickSuppression", fixups.get(1).name());
    }

    @Test
    void levelLoadStepsEmptyForNow() {
        assertTrue(profile.levelLoadSteps().isEmpty());
    }
}
```

**Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kLevelInitProfile`
Expected: FAIL — class not found.

**Step 4: Write minimal implementation**

```java
package com.openggf.game.sonic3k;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.InitStep;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.StaticFixup;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;

import java.util.List;

/**
 * Sonic 3&K level initialization profile.
 * <p>
 * Adds S3K-specific resets: {@link Sonic3kLevelEventManager} and
 * {@link AizPlaneIntroInstance#setSidekickSuppressed(boolean)}.
 */
public class Sonic3kLevelInitProfile implements LevelInitProfile {

    @Override
    public List<InitStep> levelLoadSteps() {
        return List.of();
    }

    @Override
    public List<InitStep> levelTeardownSteps() {
        return List.of(
            new InitStep("ResetAudio", "Engine: clear SMPS loader cache",
                () -> AudioManager.getInstance().resetState()),
            new InitStep("ResetS3kLevelEvents", "Engine: clear S3K level event state",
                () -> Sonic3kLevelEventManager.getInstance().resetState()),
            new InitStep("ResetParallax", "Engine: clear parallax state",
                () -> ParallaxManager.getInstance().resetState()),
            new InitStep("ResetLevelManager", "Engine: clear level manager state",
                () -> LevelManager.getInstance().resetState()),
            new InitStep("ResetSprites", "Engine: full sprite manager reset",
                () -> SpriteManager.getInstance().resetState()),
            new InitStep("ResetCollision", "Engine: destroy and recreate collision system",
                CollisionSystem::resetInstance),
            new InitStep("ResetCamera", "Engine: clear camera state",
                () -> Camera.getInstance().resetState()),
            new InitStep("ResetGraphics", "Engine: clear graphics manager state",
                () -> GraphicsManager.getInstance().resetState()),
            new InitStep("ResetFade", "Engine: destroy and recreate fade manager",
                FadeManager::resetInstance),
            new InitStep("ResetGameState", "Engine: clear score, lives, emeralds",
                () -> GameServices.gameState().resetSession()),
            new InitStep("ResetTimers", "Engine: clear timer manager state",
                () -> TimerManager.getInstance().resetState()),
            new InitStep("ResetWater", "Engine: clear water system state",
                () -> WaterSystem.getInstance().reset())
        );
    }

    @Override
    public List<InitStep> perTestResetSteps() {
        return List.of(
            new InitStep("ResetS3kLevelEvents", "Engine: clear S3K level event state",
                () -> Sonic3kLevelEventManager.getInstance().resetState()),
            new InitStep("ResetAizSidekickSuppression",
                "Engine: clear AizPlaneIntroInstance sidekick flag",
                () -> AizPlaneIntroInstance.setSidekickSuppressed(false)),
            new InitStep("ResetParallax", "Engine: clear parallax state",
                () -> ParallaxManager.getInstance().resetState()),
            new InitStep("ClearSprites", "Engine: clear sprite list",
                () -> SpriteManager.getInstance().clearAllSprites()),
            new InitStep("ResetCollision", "Engine: destroy and recreate collision system",
                CollisionSystem::resetInstance),
            new InitStep("ResetCamera", "Engine: clear camera state",
                () -> Camera.getInstance().resetState()),
            new InitStep("ResetFade", "Engine: destroy and recreate fade manager",
                FadeManager::resetInstance),
            new InitStep("ResetGameState", "Engine: clear score, lives, emeralds",
                () -> GameServices.gameState().resetSession()),
            new InitStep("ResetTimers", "Engine: clear timer manager state",
                () -> TimerManager.getInstance().resetState()),
            new InitStep("ResetWater", "Engine: clear water system state",
                () -> WaterSystem.getInstance().reset())
        );
    }

    @Override
    public List<StaticFixup> postTeardownFixups() {
        return List.of(
            new StaticFixup("WireGroundSensor",
                "GroundSensor uses static LevelManager ref that goes stale after resetState()",
                () -> GroundSensor.setLevelManager(LevelManager.getInstance())),
            new StaticFixup("ResetAizSidekickSuppression",
                "AizPlaneIntroInstance flag persists across tests",
                () -> AizPlaneIntroInstance.setSidekickSuppressed(false))
        );
    }
}
```

**Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kLevelInitProfile`
Expected: PASS (4 tests).

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java \
       src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java
git commit -m "feat: implement Sonic3kLevelInitProfile (teardown + per-test reset)"
```

---

### Task 5: Wire getLevelInitProfile() into GameModule and All Modules

Add the interface method and implement in all three game modules.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Create: `src/test/java/com/openggf/game/TestGameModuleProfiles.java`

**Step 1: Write the failing test**

```java
package com.openggf.game;

import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.Sonic1LevelInitProfile;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2LevelInitProfile;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kLevelInitProfile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestGameModuleProfiles {

    @Test
    void sonic2ModuleReturnsSonic2Profile() {
        GameModule module = new Sonic2GameModule();
        assertInstanceOf(Sonic2LevelInitProfile.class, module.getLevelInitProfile());
    }

    @Test
    void sonic1ModuleReturnsSonic1Profile() {
        GameModule module = new Sonic1GameModule();
        assertInstanceOf(Sonic1LevelInitProfile.class, module.getLevelInitProfile());
    }

    @Test
    void sonic3kModuleReturnsSonic3kProfile() {
        GameModule module = new Sonic3kGameModule();
        assertInstanceOf(Sonic3kLevelInitProfile.class, module.getLevelInitProfile());
    }

    @Test
    void profilesAreNotNull() {
        assertNotNull(new Sonic1GameModule().getLevelInitProfile());
        assertNotNull(new Sonic2GameModule().getLevelInitProfile());
        assertNotNull(new Sonic3kGameModule().getLevelInitProfile());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.TestGameModuleProfiles`
Expected: FAIL — `getLevelInitProfile()` method not found on `GameModule`.

**Step 3: Add method to GameModule interface**

In `src/main/java/com/openggf/game/GameModule.java`, add to the interface:

```java
/** Returns the ROM-derived level initialization profile for this game. */
LevelInitProfile getLevelInitProfile();
```

**Step 4: Implement in all three modules**

In `Sonic2GameModule.java`, add:
```java
@Override
public LevelInitProfile getLevelInitProfile() {
    return new Sonic2LevelInitProfile();
}
```

In `Sonic1GameModule.java`, add:
```java
@Override
public LevelInitProfile getLevelInitProfile() {
    return new Sonic1LevelInitProfile();
}
```

In `Sonic3kGameModule.java`, add:
```java
@Override
public LevelInitProfile getLevelInitProfile() {
    return new Sonic3kLevelInitProfile();
}
```

**Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.TestGameModuleProfiles`
Expected: PASS (4 tests).

**Step 6: Run all profile tests to confirm nothing broke**

Run: `mvn test -Dtest="com.openggf.game.TestInitStep,com.openggf.game.sonic2.TestSonic2LevelInitProfile,com.openggf.game.sonic1.TestSonic1LevelInitProfile,com.openggf.game.sonic3k.TestSonic3kLevelInitProfile,com.openggf.game.TestGameModuleProfiles"`
Expected: PASS (all 21 tests).

**Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/GameModule.java \
       src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java \
       src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java \
       src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java \
       src/test/java/com/openggf/game/TestGameModuleProfiles.java
git commit -m "feat: add getLevelInitProfile() to GameModule, wire all 3 modules"
```

---

### Task 6: Replace GameContext.forTesting() with Profile-Driven Teardown

This is the critical swap. The 8-phase hardcoded sequence becomes profile-driven.

**Files:**
- Modify: `src/main/java/com/openggf/GameContext.java:82-115`

**Step 1: Read the current implementation**

Read `src/main/java/com/openggf/GameContext.java` in full. Understand the 8-phase sequence at lines 82-115.

**Step 2: Replace forTesting() implementation**

Replace the entire `forTesting()` method body with:

```java
public static GameContext forTesting() {
    // CRITICAL: Capture the current game's profile BEFORE resetting the module.
    // After reset(), the module reverts to Sonic2GameModule (the default).
    // We need the PREVIOUS game's teardown to clean up its own state.
    LevelInitProfile profile = GameModuleRegistry.getCurrent().getLevelInitProfile();

    // Phase 0: Reset game module (shared across all games)
    GameModuleRegistry.reset();

    // Execute game-specific teardown steps (replaces phases 2-8)
    for (InitStep step : profile.levelTeardownSteps()) {
        step.execute();
    }

    // Apply static fixups (replaces Phase 8)
    for (StaticFixup fixup : profile.postTeardownFixups()) {
        fixup.apply();
    }

    return production();
}
```

**Step 3: Update imports in GameContext.java**

Add imports:
```java
import com.openggf.game.InitStep;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.StaticFixup;
```

Remove imports no longer needed by `forTesting()` (but keep any still used elsewhere):
- Keep `GameModuleRegistry` (still used)
- Check which of the singleton imports are only used by `forTesting()` — if `production()` still references them, keep them. Otherwise remove.

**Important:** The old `forTesting()` imports `Sonic2LevelEventManager`, `AizPlaneIntroInstance`, `FadeManager`, `ParallaxManager`, `CollisionSystem`, `GroundSensor`. If none of these are used in `production()` or the constructor, they can be removed. `production()` uses `Camera`, `LevelManager`, `SpriteManager`, `CollisionSystem`, `GraphicsManager`, `TimerManager`, `WaterSystem` — keep those.

**Step 4: Update the Javadoc**

Replace the existing `forTesting()` Javadoc with:

```java
/**
 * Resets all critical singletons using the current game's
 * {@link LevelInitProfile}, then returns a fresh production context.
 * <p>
 * The profile is captured from the CURRENT game module before
 * {@link GameModuleRegistry#reset()} is called, ensuring each game's
 * teardown cleans up its own state (S1 resets S1 event manager,
 * S3K resets AizPlaneIntroInstance, etc.).
 * <p>
 * <b>Warning:</b> Callers should not hold references to singleton instances
 * across a {@code forTesting()} call. {@link CollisionSystem} and
 * {@link FadeManager} are destroyed and recreated (via {@code resetInstance()}),
 * so any previously captured references become stale.
 */
```

**Step 5: Run ALL existing tests**

Run: `mvn test`
Expected: ALL tests pass. This is a behavior-preserving refactoring — the profile executes the same operations in the same order as the old hardcoded code.

If any test fails: the profile step ordering doesn't match the old sequence. Compare the failing test's setup against the profile steps.

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/GameContext.java
git commit -m "refactor: replace GameContext.forTesting() 8-phase with profile-driven teardown"
```

---

### Task 7: Replace TestEnvironment.resetPerTest() with Profile-Driven Per-Test Reset

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestEnvironment.java:49-71`

**Step 1: Read the current implementation**

Read `src/test/java/com/openggf/tests/TestEnvironment.java` in full. Understand the 10-operation sequence at lines 49-71.

**Step 2: Replace resetPerTest() implementation**

Replace the method body with:

```java
public static void resetPerTest() {
    LevelInitProfile profile = GameModuleRegistry.getCurrent().getLevelInitProfile();
    for (InitStep step : profile.perTestResetSteps()) {
        step.execute();
    }
}
```

**Step 3: Update imports**

Add:
```java
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.InitStep;
import com.openggf.game.LevelInitProfile;
```

Remove imports that are no longer referenced in the file body (depends on whether `resetAll()` or other methods still use them).

**Step 4: Run ALL existing tests**

Run: `mvn test`
Expected: ALL tests pass.

**Key behavioral change:** Previously, `resetPerTest()` always reset `Sonic2LevelEventManager` and `AizPlaneIntroInstance` regardless of which game was active. Now:
- S2 tests: reset `Sonic2LevelEventManager` only (same as before for S2)
- S1 tests: reset `Sonic1LevelEventManager` (improvement — was not reset before)
- S3K tests: reset `Sonic3kLevelEventManager` + `AizPlaneIntroInstance` (improvement — event manager was not reset before)

If an S1 or S3K test fails: the game's level event manager may need `resetState()` added. Check `AbstractLevelEventManager` for the method. If missing, add it to the abstract class (it should reset `eventRoutineFg`, `eventRoutineBg`, and zone/act tracking fields).

**Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/TestEnvironment.java
git commit -m "refactor: replace TestEnvironment.resetPerTest() with profile-driven per-test reset"
```

---

### Task 8: Full Validation Sweep

**Step 1: Run all tests**

Run: `mvn test 2>&1 | tail -20`
Expected: All tests pass.

**Step 2: Run S3K tests specifically (if ROM available)**

Run: `mvn test -Ds3k.rom.path="Sonic and Knuckles & Sonic 3 (W) [!].gen" 2>&1 | tail -20`
Expected: S3K tests pass (especially `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`).

**Step 3: Run S1 tests specifically (if ROM available)**

Run: `mvn test -Dtest="*Sonic1*" 2>&1 | tail -10`
Expected: S1 tests pass.

**Step 4: Verify no regressions in key test classes**

Run: `mvn test -Dtest="TestHeadlessWallCollision,TestCollisionLogic,TestPhysicsProfile,TestSpindashGating,TestCollisionModel" 2>&1 | tail -10`
Expected: All physics/collision tests pass.

**Step 5: Final commit if any fixups were needed**

```bash
git add -u
git commit -m "fix: address test regressions from profile-driven init"
```

Only create this commit if Step 1-4 required fixes. Otherwise, skip.

---

## Summary

| Task | What it does | Files created/modified |
|------|--------------|-----------------------|
| 1 | Core abstractions | 3 created, 1 test |
| 2 | S2 teardown + per-test profile | 1 created, 1 test |
| 3 | S1 teardown + per-test profile | 1 created, 1 test |
| 4 | S3K teardown + per-test profile | 1 created, 1 test |
| 5 | Wire into GameModule + all modules | 4 modified, 1 test |
| 6 | Replace GameContext.forTesting() | 1 modified |
| 7 | Replace TestEnvironment.resetPerTest() | 1 modified |
| 8 | Full validation | 0 (fixups only if needed) |

**Total:** 6 new files, 5 modified files, 5 test files.

## Follow-Up Work (Not In This Plan)

- **Level load profiles:** Populate `levelLoadSteps()` with the 44/57/65-step ROM sequences from the design doc. Requires refactoring level constructors to extract individual operations.
- **Production level loading:** Replace `Sonic1.loadLevel()`, `Sonic2.loadLevel()`, `Sonic3k.loadLevel()` with profile step execution (design doc Phase 3).
- **Implementation gaps:** Implement missing engine methods identified in the design doc (PLC queue, S3K water, zone-specific objects).
