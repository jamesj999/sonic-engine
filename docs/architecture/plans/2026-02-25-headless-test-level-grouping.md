
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.


> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce headless test suite time by grouping tests per level, loading expensive level data once per group instead of once per test.

**Architecture:** JUnit 4 `@ClassRule` loads ROM + level once per test class. New `TestEnvironment.resetPerTest()` cheaply resets per-test state (sprite, camera, game state) without touching level data. Tests that previously lived in separate classes for the same zone+act merge into single classes.

**Tech Stack:** Historical JUnit 4 migration context only. Current and new tests must use JUnit 5 / Jupiter only, with the modern extension-based fixture path.

**Design doc:** `docs/architecture/designs/2026-02-25-headless-test-level-grouping-design.md`

---

## Scope Note: S3K AIZ1 Intro Group Deferred

The S3K AIZ1 intro tests (`TestS3kAizIntroStateTimeline`, `TestS3kAizIntroCoverage`, `TestS3kAizIntroVisibleDiagnostics`, `TestAizIntroEmeraldCollection`) are **excluded** from this plan because:
1. Each test steps through the intro cutscene which mutates level chunk state
2. Tests have incompatible setup patterns (some inline, some standard `@Before`)
3. Level must be reloaded per-test regardless, so grouping saves 0 loads

These can be organized later as a separate effort if desired.

---

### Task 1: Add `TestEnvironment.resetPerTest()`

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestEnvironment.java`

**Step 1: Add the `resetPerTest()` method**

Add below the existing `resetAll()` method:

```java
/**
 * Lightweight per-test reset. Clears sprite, camera, physics, and game state
 * without touching the loaded level data or game module. Use this in @Before
 * when level data is shared across tests via @BeforeClass/@ClassRule.
 *
 * <p>Does NOT reset: GameModuleRegistry, AudioManager, LevelManager,
 * GraphicsManager, GroundSensor.setLevelManager().
 */
public static void resetPerTest() {
    // Level event state (routine counters, boss flags)
    Sonic2LevelEventManager.getInstance().resetState();
    ParallaxManager.getInstance().resetState();

    // Sprites (clear all registered sprites)
    SpriteManager.getInstance().resetState();

    // Physics
    CollisionSystem.resetInstance();

    // Camera (position, frozen flag, scroll state)
    Camera.getInstance().resetState();

    // Fade state
    FadeManager.resetInstance();

    // Game state (score, lives, emeralds) and timers
    GameServices.gameState().resetSession();
    TimerManager.getInstance().resetState();
    WaterSystem.getInstance().reset();
}
```

**Step 2: Run existing tests to verify no regression**

Run: `mvn test -pl . 2>&1 | tail -5`
Expected: All tests pass (resetPerTest is additive — no existing code calls it yet).

**Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/TestEnvironment.java
git commit -m "test: add TestEnvironment.resetPerTest() for shared-level test groups"
```

---

### Task 2: Update `RequiresRomRule` to support `@ClassRule`

**Files:**
- Modify: `src/test/java/com/openggf/tests/rules/RequiresRomRule.java`

**Context:** Currently `RequiresRomRule` implements `TestRule` which works for `@Rule` (instance). To support `@ClassRule` (static), it also needs to implement `TestRule` with class-level `Description` handling. JUnit 4 `@ClassRule` passes a `Description` for the class (not individual methods), so `description.getTestClass()` works the same way. The key change: when used as `@ClassRule`, `resetAll()` runs once for the whole class, not per-test.

**Step 1: Make `RequiresRomRule` support both `@Rule` and `@ClassRule`**

The existing code already works as `@ClassRule` because:
- `description.getTestClass()` returns the test class for both `@Rule` and `@ClassRule` descriptions
- `TestEnvironment.resetAll()` is idempotent

However, when used as `@ClassRule`, we want `resetAll()` to run once (at class level), and individual tests should use `resetPerTest()` in `@Before`. The rule doesn't need code changes — it already works. The distinction is purely in how test classes use it:

- **Old pattern:** `@Rule public RequiresRomRule romRule = ...` — calls `resetAll()` per test
- **New pattern:** `@ClassRule public static RequiresRomRule romRule = ...` — calls `resetAll()` once per class

**Step 2: Verify `@ClassRule` works by adding a minimal test**

Create a quick smoke test to verify the pattern:

```java
// In any existing test, temporarily add:
@ClassRule public static RequiresRomRule classRomRule = new RequiresRomRule();
```

Run one existing test to confirm it works, then revert.

**Step 3: Commit**

No code change needed for `RequiresRomRule` itself — the JUnit 4 API already supports this. Commit is deferred to Task 3 where we create the first grouped class.

---

### Task 3: Create `TestS2Ehz1Headless` (S2 EHZ1 group)

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS2Ehz1Headless.java`
- Delete after verification: `TestHeadlessWallCollision.java`, `TestHeadlessStaticObjectPushStability.java`, `TestTailsCpuController.java`, `TestSignpostWalkOff.java`

This is the highest-value S2 group (4 classes → 1, 24 tests, 3 level loads saved).

**Step 1: Create the grouped test class skeleton**

```java
package com.openggf.tests;

// Union of all imports from the 4 source classes
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.playable.TailsCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.RequiresRomRule;
import com.openggf.tests.rules.SonicGame;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Grouped headless tests for Sonic 2 EHZ Act 1.
 * Level data loaded once; sprite/camera/game state reset per test.
 *
 * <p>Merged from: TestHeadlessWallCollision, TestHeadlessStaticObjectPushStability,
 * TestTailsCpuController, TestSignpostWalkOff.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Ehz1Headless {

    @ClassRule public static RequiresRomRule romRule = new RequiresRomRule();

    private static final int ZONE_EHZ = 0;
    private static final int ACT_1 = 0;
    private static String mainCharCode;

    @BeforeClass
    public static void loadLevel() throws Exception {
        GraphicsManager.getInstance().initHeadless();
        SonicConfigurationService cs = SonicConfigurationService.getInstance();
        mainCharCode = cs.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        LevelManager.getInstance().loadZoneAndAct(ZONE_EHZ, ACT_1);
        GroundSensor.setLevelManager(LevelManager.getInstance());
    }

    // Per-test state (reset fresh each test)
    private Sonic sprite;
    private HeadlessTestRunner testRunner;

    @Before
    public void setUp() {
        TestEnvironment.resetPerTest();
        sprite = new Sonic(mainCharCode, (short) 0, (short) 0);
        SpriteManager.getInstance().addSprite(sprite);
        Camera camera = Camera.getInstance();
        camera.setFocusedSprite(sprite);
        camera.setFrozen(false);
        camera.updatePosition(true);
        testRunner = new HeadlessTestRunner(sprite);
    }

    // ========== From TestHeadlessWallCollision ==========
    // Move testGroundCollisionAndWalking() verbatim.
    // Set sprite position: sprite.setX((short) 100); sprite.setY((short) 624);
    // at the start of the test method (was previously in setUp constructor args).

    // ========== From TestHeadlessStaticObjectPushStability ==========
    // Move constants: TESTBED_X, TESTBED_FLOOR_Y, etc.
    // Move inner class: StaticSolidObject
    // Move tests: testNoJitterWhenPushingStaticObjectToRight/Left()
    // Move helpers: assertNoPushJitter(), directionName()

    // ========== From TestTailsCpuController ==========
    // This test needs Tails + TailsCpuController in addition to Sonic.
    // Tests that need Tails should create it at the start of the test method
    // or use a helper method: createTailsController().
    // Move all 20+ test methods and helpers verbatim.

    // ========== From TestSignpostWalkOff ==========
    // Move constants: START_X=0x29A0, START_Y=0x02A0, MAX_FRAMES=600
    // Move test: testSignpostWalkOffAndResultsScreen()
}
```

**Step 2: Move test methods verbatim from each source class**

For each source class, copy all `@Test` methods, helper methods, constants, and inner classes into the new file. Key adaptations:

- **TestHeadlessWallCollision:** Sprite was created at `(100, 624)` in setUp. In merged class, sprite starts at `(0, 0)`. Add `sprite.setX((short) 100); sprite.setY((short) 624);` at the start of `testGroundCollisionAndWalking()`.

- **TestHeadlessStaticObjectPushStability:** Same adaptation for TESTBED positions. Copy `StaticSolidObject` inner class.

- **TestTailsCpuController:** This needs both Sonic and Tails. Add a helper:
  ```java
  private Tails tails;
  private TailsCpuController controller;
  private HeadlessTestRunner sonicRunner;

  private void createTailsController() {
      tails = new Tails("tails", (short) 60, (short) 624);
      tails.setCpuControlled(true);
      controller = new TailsCpuController(tails);
      tails.setCpuController(controller);
      SpriteManager.getInstance().addSprite(tails);
      sonicRunner = new HeadlessTestRunner(sonic);
  }
  ```
  Each Tails test method calls `createTailsController()` first. The `sonic` field is the same as `sprite`. Add: `private Sonic sonic;` alias set in `setUp()`: `sonic = sprite;`.

- **TestSignpostWalkOff:** Reposition sprite at start of test.

**Step 3: Run the new grouped test**

Run: `mvn test -Dtest=TestS2Ehz1Headless`
Expected: All 24 tests pass.

**Step 4: Delete old source files**

Delete: `TestHeadlessWallCollision.java`, `TestHeadlessStaticObjectPushStability.java`, `TestTailsCpuController.java`, `TestSignpostWalkOff.java`

**Step 5: Run full suite to verify**

Run: `mvn test`
Expected: Same pass count (old classes gone, new class covers them).

**Step 6: Commit**

```bash
git add -A src/test/java/com/openggf/tests/TestS2Ehz1Headless.java
git add -A src/test/java/com/openggf/tests/TestHeadlessWallCollision.java
git add -A src/test/java/com/openggf/tests/TestHeadlessStaticObjectPushStability.java
git add -A src/test/java/com/openggf/tests/TestTailsCpuController.java
git add -A src/test/java/com/openggf/tests/TestSignpostWalkOff.java
git commit -m "test: group S2 EHZ1 headless tests — 4 classes merged, 3 level loads eliminated"
```

---

### Task 4: Create `TestS1Ghz1Headless` (S1 GHZ1 group)

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS1Ghz1Headless.java`
- Delete: `TestSonic1GhzSlopeTopDiagnostic.java`, `TestSonic1GhzTunnel.java`, `TestHeadlessSonic1PushStability.java`, `TestHeadlessSonic1EdgeBalance.java`, `TestHeadlessSonic1ObjectCollision.java`
- Move into new file: `src/test/java/com/openggf/game/sonic1/objects/badniks/TestCrabmeatSpawnPosition.java`

This is the highest-value S1 group (6 classes → 1, 25 tests, 5 level loads saved).

**Step 1: Create the grouped class**

Same `@ClassRule` + `@BeforeClass` + `@Before` pattern as Task 3, but with:
- `@RequiresRom(SonicGame.SONIC_1)`
- `loadZoneAndAct(0, 0)` for GHZ1

**Step 2: Move test methods from all 6 source classes**

Key adaptations:

- **TestSonic1GhzSlopeTopDiagnostic:** Move all 9+ test methods, `runNudgeSequence`, `resetAtTarget`, `resetAtTopLeft`, `printVerticalScanState`, `printTileState`. Constants: `TARGET_X=1900`, `TARGET_Y=857`.

- **TestSonic1GhzTunnel:** Move `testTunnelTraversal()` and `findFirstTunnelTile()`. Needs `Sonic1Level` cast: `s1Level = (Sonic1Level) LevelManager.getInstance().getLevel();` — do this inside the test method.

- **TestHeadlessSonic1PushStability:** Move test methods, `assertNoPushJitter()`, `directionName()`, and `StaticSolidObject` inner class. Rename inner class to `PushTestSolidObject` to avoid conflict with EdgeBalance's copy.

- **TestHeadlessSonic1EdgeBalance:** Move tests, helpers, and `StaticSolidObject`. Rename to `EdgeBalanceSolidObject`.

- **TestHeadlessSonic1ObjectCollision:** Move tests and `StaticSolidObject`. Rename to `CollisionTestSolidObject`.

- **TestCrabmeatSpawnPosition:** Move from `com.openggf.game.sonic1.objects.badniks` package into this class. Move all 4 tests and `findCrabmeats()`, `countCrabmeatsAtSpawnX()`. This test repositions sprite to `(0x0800, 0x0350)` at test start.

**Step 3: Run, verify, delete old files, commit**

Same pattern as Task 3. Commit message: `"test: group S1 GHZ1 headless tests — 6 classes merged, 5 level loads eliminated"`

---

### Task 5: Create `TestS2Arz1Headless` (S2 ARZ1 group)

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS2Arz1Headless.java`
- Delete: `TestArzRunRight.java`, `TestArzSpringLoop.java`, `TestArzDebug.java`

3 classes → 1, 5 tests, 2 level loads saved.

**Step 1: Create grouped class**

`@ClassRule` pattern with `loadZoneAndAct(2, 0)`. Common start position: `START_X=2468`, `START_Y=841`.

ARZ tests have a special setup step: `objectManager.reset(camera.getX())` after level load. Add this to `@BeforeClass`:

```java
@BeforeClass
public static void loadLevel() throws Exception {
    GraphicsManager.getInstance().initHeadless();
    // ... standard setup ...
    LevelManager.getInstance().loadZoneAndAct(2, 0);
    GroundSensor.setLevelManager(LevelManager.getInstance());
    // ARZ tests need object manager reset with camera position
    Camera.getInstance().updatePosition(true);
    var om = LevelManager.getInstance().getObjectManager();
    if (om != null) om.reset(Camera.getInstance().getX());
}
```

**Step 2: Move test methods verbatim**

- From `TestArzRunRight`: `dumpPlaneSwitchers()`, `dumpCollisionDataAtWall()`, `testRunRightOnPrimaryPath()`, `debugSpringLoopHoldingRight()` plus helper methods.
- From `TestArzSpringLoop`: `testSpringLaunchAndLoopTraversal()`, `logState()`.
- From `TestArzDebug`: `debugRun()`.

Each test repositions sprite to `(START_X, START_Y)` at method start.

**Step 3: Run, verify, delete, commit**

Commit message: `"test: group S2 ARZ1 headless tests — 3 classes merged, 2 level loads eliminated"`

---

### Task 6: Create `TestS2Cnz1Headless` (S2 CNZ1 group)

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS2Cnz1Headless.java`
- Delete: `TestCNZCeilingStateExit.java`, `TestCNZFlipperLaunch.java`, `TestCNZForcedSpinTunnel.java`

3 classes → 1, 3 tests, 2 level loads saved.

**Step 1: Create grouped class**

`@ClassRule` pattern with `loadZoneAndAct(3, 0)`.

**Step 2: Move test methods**

Each test starts at different positions and has different constants — these become method-local or grouped by source:
- From `TestCNZCeilingStateExit`: Constants `START_X=3621, START_Y=1820, CHARGE_FRAMES=130`, etc. The large multi-phase test method moves verbatim.
- From `TestCNZFlipperLaunch`: Constants `START_X=612, START_Y=857`, test method.
- From `TestCNZForcedSpinTunnel`: Constants `START_X=7787, START_Y=921`, test method.

**Step 3: Run, verify, delete, commit**

Commit message: `"test: group S2 CNZ1 headless tests — 3 classes merged, 2 level loads eliminated"`

---

### Task 7: Create `TestS2Htz1Headless` (S2 HTZ1 group)

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS2Htz1Headless.java`
- Delete: `TestHtzDropOnFloor.java`, `TestHTZInvisibleWallBug.java`

2 classes → 1, 6 tests, 1 level load saved.

**Step 1: Create grouped class**

`@ClassRule` pattern with `loadZoneAndAct(4, 0)`.

Note: `TestHtzDropOnFloor` previously used reflection to reset `Sonic2LevelEventManager`. This is now handled by `TestEnvironment.resetPerTest()` which calls `Sonic2LevelEventManager.getInstance().resetState()`. The reflection hack is no longer needed.

**Step 2: Move test methods**

- From `TestHtzDropOnFloor`: `sonicDetachesFromLavaPlatformAtFloor()`, `sonicTakesDamageFromLavaSubtype4()`. Remove the `resetLevelEventManager()` reflection helper — `resetPerTest()` handles this.
- From `TestHTZInvisibleWallBug`: `testDiagnoseCollisionAtBugLocation()`, `testWalkThroughBugLocation()`, `testEarthquakeOffsetSync()`, `testWalkThroughEarthquakeZoneNoStall()` plus helpers `analyzeChunkAt()`, `findSolidGroundAt()`, `diagnoseWallSensorAt()`.

**Step 3: Run, verify, delete, commit**

Commit message: `"test: group S2 HTZ1 headless tests — 2 classes merged, 1 level load eliminated"`

---

### Task 8: Create `TestS3kAiz1SkipHeadless` (S3K AIZ1 intro-skip group)

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS3kAiz1SkipHeadless.java`
- Delete: `TestS3kAiz1SpawnStability.java`, `TestS3kAizHollowLogTraversal.java`

2 classes → 1, 7 tests, 1 level load saved.

**Step 1: Create grouped class**

```java
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1SkipHeadless {

    @ClassRule public static RequiresRomRule romRule = new RequiresRomRule();

    private static Object oldSkipIntros;

    @BeforeClass
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        GraphicsManager.getInstance().initHeadless();
        LevelManager.getInstance().loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(LevelManager.getInstance());
    }

    @AfterClass
    public static void restoreConfig() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
    }

    // ... standard @Before with resetPerTest() ...
}
```

**Step 2: Move test methods**

- From `TestS3kAiz1SpawnStability`: `aiz1IntroSkipSpawnHasCollisionAndNoImmediatePitDeath()` and `hasPrimaryCollisionBelow()`. Remove per-class `@Before`/`@After` config management (now in `@BeforeClass`/`@AfterClass`).

- From `TestS3kAizHollowLogTraversal`: All 6 test methods and all helpers (`snapshotTreeColumns`, `applyDebugStartState`, `runUntilTreeRevealClears`, etc.). Constants: `START_X=11164, START_Y=951`, etc. Remove per-class config management.

**Important:** `TestS3kAizHollowLogTraversal` has `reloadAizAct1AndApplyDebugStart()` which calls `loadZoneAndAct(0, 0)` again. This is used in `hollowLogTraversal_visualRevealStableAcrossRepeatRuns()` to test re-entry. This is fine — it reloads within a single test method and the next test gets a fresh level via `resetPerTest()` + the shared @BeforeClass load. Actually wait — the @BeforeClass level load happens ONCE. If a test reloads the level, subsequent tests use the reloaded state. This should be OK since `resetPerTest()` resets sprite/camera state and the level data for zone 0 act 0 is the same regardless.

**Step 3: Run, verify, delete, commit**

Commit message: `"test: group S3K AIZ1 skip headless tests — 2 classes merged, 1 level load eliminated"`

---

### Task 9: Convert single-class tests to `@ClassRule` pattern

**Files to modify (5):**
- `src/test/java/com/openggf/tests/TestHeadlessMZ2PushBlockGap.java`
- `src/test/java/com/openggf/tests/TestS1SpikeDoubleHit.java`
- `src/test/java/com/openggf/tests/TestSbz1CreditsDemoBug.java`
- `src/test/java/com/openggf/tests/TestHtzSpringLoop.java`
- `src/test/java/com/openggf/tests/TestSczSpawnOnTornado.java`

These have only 1 class per zone so no merging needed, but converting to `@ClassRule` establishes a consistent pattern and prepares for future tests in the same zone.

**Step 1: For each file, apply this transformation:**

Before:
```java
@Rule public RequiresRomRule romRule = new RequiresRomRule();

@Before
public void setUp() throws Exception {
    GraphicsManager.getInstance().initHeadless();
    // create sprite ...
    // loadZoneAndAct(zone, act) ...
    // GroundSensor.setLevelManager(...) ...
    // camera.updatePosition(true) ...
    testRunner = new HeadlessTestRunner(sprite);
}
```

After:
```java
@ClassRule public static RequiresRomRule romRule = new RequiresRomRule();
private static String mainCharCode;

@BeforeClass
public static void loadLevel() throws Exception {
    GraphicsManager.getInstance().initHeadless();
    mainCharCode = SonicConfigurationService.getInstance()
            .getString(SonicConfiguration.MAIN_CHARACTER_CODE);
    LevelManager.getInstance().loadZoneAndAct(zone, act);
    GroundSensor.setLevelManager(LevelManager.getInstance());
}

@Before
public void setUp() {
    TestEnvironment.resetPerTest();
    sprite = new Sonic(mainCharCode, (short) 0, (short) 0);
    SpriteManager.getInstance().addSprite(sprite);
    Camera camera = Camera.getInstance();
    camera.setFocusedSprite(sprite);
    camera.setFrozen(false);
    camera.updatePosition(true);
    testRunner = new HeadlessTestRunner(sprite);
}
```

Sprite positioning (if not at 0,0) moves to the start of individual test methods.

**Step 2: Run each converted test individually**

Run: `mvn test -Dtest=TestHeadlessMZ2PushBlockGap,TestS1SpikeDoubleHit,TestSbz1CreditsDemoBug,TestHtzSpringLoop,TestSczSpawnOnTornado`
Expected: All pass.

**Step 3: Commit**

```bash
git commit -m "test: convert 5 single-class headless tests to @ClassRule pattern"
```

---

### Task 10: Full suite verification

**Step 1: Run the entire test suite**

Run: `mvn test`
Expected: Same number of passing tests as before the refactor. No new failures.

**Step 2: Verify test count**

Count `@Test` methods across old and new files to confirm no tests were lost.

**Step 3: Commit (if any fixups needed)**

---

## Summary

| Task | Group | Classes Merged | Level Loads Saved |
|------|-------|:--------------:|:-----------------:|
| 3 | S2 EHZ1 | 4 → 1 | 3 |
| 4 | S1 GHZ1 | 6 → 1 | 5 |
| 5 | S2 ARZ1 | 3 → 1 | 2 |
| 6 | S2 CNZ1 | 3 → 1 | 2 |
| 7 | S2 HTZ1 | 2 → 1 | 1 |
| 8 | S3K AIZ1 Skip | 2 → 1 | 1 |
| 9 | Single-class | 5 (pattern only) | 0 |
| **Total** | | **25 → 12** | **14** |

Plus 5 single-class conversions for consistency. S3K AIZ1 Intro (4 classes) deferred.
