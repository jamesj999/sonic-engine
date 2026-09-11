
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.


> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce test boilerplate from ~20 lines to ~5 by encapsulating singleton reset ordering in a GameContext holder and HeadlessTestFixture builder.

**Architecture:** GameContext bundles 8 critical-path managers with factory methods (`production()` wraps singletons, `forTesting()` resets in correct order). SharedLevel caches loaded level data for reuse across tests. HeadlessTestFixture provides a builder API that wires everything together. All existing tests remain unchanged -- both patterns coexist.

**Tech Stack:** Java 21, with historical JUnit 4 migration context in this document. Current and new tests must use JUnit 5 / Jupiter only.

---

### Task 1: Create GameContext

**Files:**
- Create: `src/main/java/com/openggf/GameContext.java`
- Test: `src/test/java/com/openggf/tests/TestGameContext.java`

**Step 1: Write the failing test**

Create `src/test/java/com/openggf/tests/TestGameContext.java`:

```java
package com.openggf.tests;

import com.openggf.GameContext;
import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.physics.CollisionSystem;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestGameContext {

    @Test
    public void productionWrapsExistingSingletons() {
        GameContext ctx = GameContext.production();
        assertSame(Camera.getInstance(), ctx.camera());
        assertSame(LevelManager.getInstance(), ctx.levelManager());
        assertSame(SpriteManager.getInstance(), ctx.spriteManager());
        assertSame(CollisionSystem.getInstance(), ctx.collisionSystem());
    }

    @Test
    public void forTestingResetsCamera() {
        Camera.getInstance().setFrozen(true);
        GameContext ctx = GameContext.forTesting();
        assertFalse("Camera should not be frozen after forTesting()", ctx.camera().isFrozen());
    }

    @Test
    public void forTestingResetsSpriteManager() {
        // Add a sprite, then reset
        SpriteManager.getInstance().resetState();
        GameContext ctx = GameContext.forTesting();
        assertNotNull(ctx.spriteManager());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestGameContext -pl .`
Expected: FAIL - `GameContext` class does not exist

**Step 3: Write GameContext implementation**

Create `src/main/java/com/openggf/GameContext.java`:

```java
package com.openggf;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.data.GameModuleRegistry;
import com.openggf.data.GameServices;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic3k.AizPlaneIntroInstance;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.managers.TimerManager;

/**
 * Bundles critical-path managers for dependency passing.
 * Use {@link #production()} in game code, {@link #forTesting()} in tests.
 */
public class GameContext {
    private final Camera camera;
    private final LevelManager levelManager;
    private final SpriteManager spriteManager;
    private final CollisionSystem collisionSystem;
    private final GraphicsManager graphicsManager;
    private final GameServices.GameStateAccessor gameStateAccessor;
    private final TimerManager timerManager;
    private final WaterSystem waterSystem;

    private GameContext(Camera camera, LevelManager levelManager,
                        SpriteManager spriteManager, CollisionSystem collisionSystem,
                        GraphicsManager graphicsManager,
                        GameServices.GameStateAccessor gameStateAccessor,
                        TimerManager timerManager, WaterSystem waterSystem) {
        this.camera = camera;
        this.levelManager = levelManager;
        this.spriteManager = spriteManager;
        this.collisionSystem = collisionSystem;
        this.graphicsManager = graphicsManager;
        this.gameStateAccessor = gameStateAccessor;
        this.timerManager = timerManager;
        this.waterSystem = waterSystem;
    }

    /** Wraps existing singletons. No state change. */
    public static GameContext production() {
        return new GameContext(
            Camera.getInstance(),
            LevelManager.getInstance(),
            SpriteManager.getInstance(),
            CollisionSystem.getInstance(),
            GraphicsManager.getInstance(),
            null, // GameStateAccessor TBD based on actual API
            TimerManager.getInstance(),
            WaterSystem.getInstance()
        );
    }

    /**
     * Resets all critical singletons in correct order and returns a fresh context.
     * Encapsulates the ordering from TestEnvironment.resetAll().
     */
    public static GameContext forTesting() {
        // Phase 1: Game module
        GameModuleRegistry.reset();

        // Phase 2: Audio
        AudioManager.getInstance().resetState();

        // Phase 3: Level subsystems
        Sonic2LevelEventManager.getInstance().resetState();
        ParallaxManager.getInstance().resetState();
        LevelManager.getInstance().resetState();

        // Phase 4: Sprites
        SpriteManager.getInstance().resetState();

        // Phase 5: Physics
        CollisionSystem.resetInstance();

        // Phase 6: Camera & graphics
        Camera.getInstance().resetState();
        GraphicsManager.getInstance().resetState();
        FadeManager.resetInstance();

        // Phase 7: Game state & timers
        GameServices.gameState().resetSession();
        TimerManager.getInstance().resetState();
        WaterSystem.getInstance().reset();

        // Phase 8: Static fixups
        GroundSensor.setLevelManager(LevelManager.getInstance());
        AizPlaneIntroInstance.setSidekickSuppressed(false);

        return production();
    }

    public Camera camera() { return camera; }
    public LevelManager levelManager() { return levelManager; }
    public SpriteManager spriteManager() { return spriteManager; }
    public CollisionSystem collisionSystem() { return collisionSystem; }
    public GraphicsManager graphicsManager() { return graphicsManager; }
    public TimerManager timerManager() { return timerManager; }
    public WaterSystem waterSystem() { return waterSystem; }
}
```

**Important:** The `GameServices.GameStateAccessor` type is a placeholder. Check the actual
return type of `GameServices.gameState()` and adjust. If it returns `GameStateManager`, use that
directly. The constructor parameter can be omitted if game state is always accessed via
`GameServices.gameState()`.

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestGameContext -pl .`
Expected: PASS (all 3 tests)

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/GameContext.java src/test/java/com/openggf/tests/TestGameContext.java
git commit -m "feat: add GameContext holder with production() and forTesting() factories"
```

---

### Task 2: Create SharedLevel

**Files:**
- Create: `src/test/java/com/openggf/tests/SharedLevel.java`
- Test: `src/test/java/com/openggf/tests/TestSharedLevel.java`

**Context:** SharedLevel encapsulates the `@BeforeClass` level loading pattern. It needs
headless graphics init, a temporary sprite for camera focus, and level loading. The `dispose()`
method cleans up. Tests require a ROM.

**Step 1: Write the failing test**

Create `src/test/java/com/openggf/tests/TestSharedLevel.java`:

```java
package com.openggf.tests;

import com.openggf.data.SonicGame;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

@RequiresRom(SonicGame.SONIC_2)
public class TestSharedLevel {
    @ClassRule
    public static RequiresRomRule romRule = new RequiresRomRule();

    private static SharedLevel sharedLevel;

    @BeforeClass
    public static void setUp() {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2,
            Sonic2Constants.ZONE_EHZ, 0);
    }

    @AfterClass
    public static void tearDown() {
        sharedLevel.dispose();
    }

    @Test
    public void levelIsLoaded() {
        assertNotNull("Level should be loaded", sharedLevel.level());
    }

    @Test
    public void levelHasValidBounds() {
        assertTrue("Max X should be positive", sharedLevel.level().getMaxX() > 0);
    }

    @Test
    public void gameAndZoneAreStored() {
        assertEquals(SonicGame.SONIC_2, sharedLevel.game());
        assertEquals(Sonic2Constants.ZONE_EHZ, sharedLevel.zone());
        assertEquals(0, sharedLevel.act());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSharedLevel -pl .`
Expected: FAIL - `SharedLevel` class does not exist

**Step 3: Write SharedLevel implementation**

Create `src/test/java/com/openggf/tests/SharedLevel.java`:

```java
package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.data.SonicGame;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;

/**
 * Encapsulates loaded level data for reuse across tests in the same class.
 * Use in {@code @BeforeClass} to load once, then pass to
 * {@link HeadlessTestFixture.Builder#withSharedLevel(SharedLevel)}.
 * Call {@link #dispose()} in {@code @AfterClass}.
 */
public class SharedLevel {
    private final Level level;
    private final SonicGame game;
    private final int zone;
    private final int act;

    private SharedLevel(Level level, SonicGame game, int zone, int act) {
        this.level = level;
        this.game = game;
        this.zone = zone;
        this.act = act;
    }

    /**
     * Loads a level for shared use across tests.
     * Initializes headless graphics, creates a temporary sprite for
     * camera focus, and loads the specified zone/act.
     */
    public static SharedLevel load(SonicGame game, int zone, int act) {
        GraphicsManager.getInstance().initHeadless();

        // LevelManager needs a focused sprite during loading
        Sonic temp = new Sonic((byte) 0, (short) 0, (short) 0);
        SpriteManager.getInstance().addSprite(temp);
        Camera camera = Camera.getInstance();
        camera.setFocusedSprite(temp);
        camera.setFrozen(false);

        LevelManager.getInstance().loadZoneAndAct(zone, act);
        GroundSensor.setLevelManager(LevelManager.getInstance());

        Level loaded = LevelManager.getInstance().getCurrentLevel();

        // Set camera bounds from level
        if (loaded != null) {
            camera.setMinX((short) loaded.getMinX());
            camera.setMaxX((short) loaded.getMaxX());
            camera.setMinY((short) loaded.getMinY());
            camera.setMaxY((short) loaded.getMaxY());
        }

        return new SharedLevel(loaded, game, zone, act);
    }

    public void dispose() {
        // Clean up: reset LevelManager and related state
        LevelManager.getInstance().resetState();
        SpriteManager.getInstance().resetState();
        Camera.getInstance().resetState();
    }

    public Level level() { return level; }
    public SonicGame game() { return game; }
    public int zone() { return zone; }
    public int act() { return act; }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestSharedLevel -pl .`
Expected: PASS (all 3 tests). Requires Sonic 2 ROM in working directory.

**Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/SharedLevel.java src/test/java/com/openggf/tests/TestSharedLevel.java
git commit -m "feat: add SharedLevel for reusable level loading in tests"
```

---

### Task 3: Create HeadlessTestFixture

**Files:**
- Create: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`
- Test: `src/test/java/com/openggf/tests/TestHeadlessTestFixture.java`

**Context:** This is the main payoff. The fixture builder replaces ~20 lines of per-test
boilerplate. It handles GameContext creation, sprite setup, camera wiring, GroundSensor,
and HeadlessTestRunner creation.

**Step 1: Write the failing test**

Create `src/test/java/com/openggf/tests/TestHeadlessTestFixture.java`:

```java
package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.data.SonicGame;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

@RequiresRom(SonicGame.SONIC_2)
public class TestHeadlessTestFixture {
    @ClassRule
    public static RequiresRomRule romRule = new RequiresRomRule();

    private static SharedLevel sharedLevel;

    @BeforeClass
    public static void loadLevel() {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2,
            Sonic2Constants.ZONE_EHZ, 0);
    }

    @AfterClass
    public static void cleanup() {
        sharedLevel.dispose();
    }

    @Test
    public void fixtureCreatesSprite() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withSharedLevel(sharedLevel)
            .startPosition((short) 0x100, (short) 0x300)
            .build();

        assertNotNull("Sprite should be created", fixture.sprite());
        assertEquals(0x100, fixture.sprite().getX());
        assertEquals(0x300, fixture.sprite().getY());
    }

    @Test
    public void fixtureSetsUpCamera() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withSharedLevel(sharedLevel)
            .startPosition((short) 0x100, (short) 0x300)
            .build();

        Camera camera = fixture.camera();
        assertNotNull("Camera should be available", camera);
        assertFalse("Camera should not be frozen", camera.isFrozen());
    }

    @Test
    public void fixtureCanStepFrames() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withSharedLevel(sharedLevel)
            .startPosition((short) 0x100, (short) 0x300)
            .build();

        short initialX = fixture.sprite().getX();
        // Hold right for 10 frames
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, true, false);
        }
        assertTrue("Sonic should have moved right",
            fixture.sprite().getX() > initialX);
    }

    @Test
    public void twoFixturesDoNotInterfere() {
        HeadlessTestFixture fixture1 = HeadlessTestFixture.builder()
            .withSharedLevel(sharedLevel)
            .startPosition((short) 0x100, (short) 0x300)
            .build();

        fixture1.stepIdleFrames(5);
        short pos1 = fixture1.sprite().getX();

        HeadlessTestFixture fixture2 = HeadlessTestFixture.builder()
            .withSharedLevel(sharedLevel)
            .startPosition((short) 0x200, (short) 0x300)
            .build();

        // fixture2 starts fresh -- different position
        assertEquals(0x200, fixture2.sprite().getX());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestHeadlessTestFixture -pl .`
Expected: FAIL - `HeadlessTestFixture` class does not exist

**Step 3: Write HeadlessTestFixture implementation**

Create `src/test/java/com/openggf/tests/HeadlessTestFixture.java`:

```java
package com.openggf.tests;

import com.openggf.GameContext;
import com.openggf.camera.Camera;
import com.openggf.data.SonicGame;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;

/**
 * Builder-pattern test setup that encapsulates HeadlessTestRunner boilerplate.
 * <pre>
 * HeadlessTestFixture fixture = HeadlessTestFixture.builder()
 *     .withSharedLevel(sharedLevel)
 *     .startPosition((short) 0x100, (short) 0x300)
 *     .build();
 * fixture.stepFrame(false, false, false, true, false);
 * assertEquals(expected, fixture.sprite().getX());
 * </pre>
 */
public class HeadlessTestFixture {
    private final GameContext context;
    private final HeadlessTestRunner runner;
    private final AbstractPlayableSprite sprite;

    private HeadlessTestFixture(GameContext context, HeadlessTestRunner runner,
                                 AbstractPlayableSprite sprite) {
        this.context = context;
        this.runner = runner;
        this.sprite = sprite;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SharedLevel sharedLevel;
        private int zone;
        private int act;
        private short startX;
        private short startY;
        private boolean initLevelEvents = false;

        public Builder withSharedLevel(SharedLevel level) {
            this.sharedLevel = level;
            return this;
        }

        public Builder zone(int zone, int act) {
            this.zone = zone;
            this.act = act;
            return this;
        }

        public Builder startPosition(short x, short y) {
            this.startX = x;
            this.startY = y;
            return this;
        }

        /**
         * Enable level event initialization (needed for zone events like
         * HTZ earthquake, boss arenas, dynamic boundaries).
         */
        public Builder withLevelEvents() {
            this.initLevelEvents = true;
            return this;
        }

        public HeadlessTestFixture build() {
            // Reset transient state (preserves loaded level data)
            TestEnvironment.resetPerTest();

            // Create sprite at start position
            Sonic sprite = new Sonic((byte) 0, startX, startY);
            SpriteManager.getInstance().addSprite(sprite);

            // Wire camera
            Camera camera = Camera.getInstance();
            camera.setFocusedSprite(sprite);
            camera.setFrozen(false);

            // Restore camera bounds from level
            Level level = sharedLevel != null
                ? sharedLevel.level()
                : LevelManager.getInstance().getCurrentLevel();
            if (level != null) {
                camera.setMinX((short) level.getMinX());
                camera.setMaxX((short) level.getMaxX());
                camera.setMinY((short) level.getMinY());
                camera.setMaxY((short) level.getMaxY());
            }

            // Wire GroundSensor
            GroundSensor.setLevelManager(LevelManager.getInstance());

            // Snap camera to sprite position
            camera.updatePosition(true);

            // Initialize level events if requested
            if (initLevelEvents && sharedLevel != null) {
                // Get the level event provider and init for this zone/act
                var eventProvider = com.openggf.data.GameModuleRegistry
                    .getCurrent().getLevelEventProvider();
                if (eventProvider != null) {
                    eventProvider.getManager().initLevel(
                        sharedLevel.zone(), sharedLevel.act());
                }
            }

            // Create context and runner
            GameContext ctx = GameContext.production();
            HeadlessTestRunner runner = new HeadlessTestRunner(sprite);

            return new HeadlessTestFixture(ctx, runner, sprite);
        }
    }

    // --- Convenience delegation ---

    public void stepFrame(boolean up, boolean down,
                          boolean left, boolean right, boolean jump) {
        runner.stepFrame(up, down, left, right, jump);
    }

    public void stepIdleFrames(int count) {
        runner.stepIdleFrames(count);
    }

    // --- Accessors ---

    public AbstractPlayableSprite sprite() { return sprite; }
    public Camera camera() { return context.camera(); }
    public GameContext context() { return context; }
    public HeadlessTestRunner runner() { return runner; }
    public int frameCount() { return runner.getFrameCounter(); }
}
```

**Important implementation notes:**
- `build()` calls `TestEnvironment.resetPerTest()` to reset transient state while preserving the shared level
- The `withLevelEvents()` flag controls whether zone event managers are initialized (needed for HTZ earthquake tests but not for simple collision tests)
- Check the actual API for `LevelEventProvider.getManager().initLevel()` -- it may be `getInstance().initLevel()` on the game-specific event manager. Verify against `TestS2Htz1Headless.java` line ~107 which calls `Sonic2LevelEventManager.getInstance().initLevel()`

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestHeadlessTestFixture -pl .`
Expected: PASS (all 4 tests). Requires Sonic 2 ROM.

**Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/HeadlessTestFixture.java src/test/java/com/openggf/tests/TestHeadlessTestFixture.java
git commit -m "feat: add HeadlessTestFixture builder for test setup"
```

---

### Task 4: Verify All Existing Tests Pass

**Files:**
- No changes -- verification only

**Step 1: Run full test suite**

Run: `mvn test -pl .`
Expected: ALL existing tests pass. The new classes don't affect any existing code.

**Step 2: If any test fails, investigate**

The new classes should have zero impact on existing tests since they only add new files.
If failures occur, they are pre-existing or environmental -- do not modify existing tests.

**Step 3: Commit (tag milestone)**

```bash
git commit --allow-empty -m "chore: verify all tests pass after GameContext/Fixture addition"
```

---

### Task 5: Convert TestHeadlessCNZ1LiftWallStick (Simple Example)

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestHeadlessCNZ1LiftWallStick.java`

**Context:** This is the simplest conversion target -- single test, basic setup, no level events.
Convert it to use the fixture pattern as a proof-of-concept.

**Step 1: Read the existing test file**

Read `src/test/java/com/openggf/tests/TestHeadlessCNZ1LiftWallStick.java` fully.
Note the exact setup pattern used (fields, @BeforeClass, @Before, test body).

**Step 2: Convert to fixture pattern**

Replace the boilerplate with SharedLevel + HeadlessTestFixture. The conversion should:
- Replace `@BeforeClass` body with `SharedLevel.load()`
- Add `@AfterClass` with `sharedLevel.dispose()`
- Replace `@Before` body with `HeadlessTestFixture.builder()...build()`
- Replace `testRunner.stepFrame()` with `fixture.stepFrame()`
- Replace direct `sprite` field access with `fixture.sprite()`
- Replace `Camera.getInstance()` calls with `fixture.camera()`

The test logic (assertions, frame stepping, position setup) must remain identical.

**Step 3: Run the converted test**

Run: `mvn test -Dtest=TestHeadlessCNZ1LiftWallStick -pl .`
Expected: PASS -- same behavior, less boilerplate

**Step 4: Run full suite to verify no regressions**

Run: `mvn test -pl .`
Expected: ALL tests pass

**Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/TestHeadlessCNZ1LiftWallStick.java
git commit -m "refactor: convert TestHeadlessCNZ1LiftWallStick to HeadlessTestFixture"
```

---

### Task 6: Convert TestS2Htz1Headless (Complex Example with Level Events)

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS2Htz1Headless.java`

**Context:** This is a more complex conversion. It has 5+ test methods, uses
`Sonic2LevelEventManager.getInstance().initLevel()` in `@Before`, and tests earthquake
behavior that depends on level event state. This validates the `withLevelEvents()` builder flag.

**Step 1: Read the existing test file**

Read `src/test/java/com/openggf/tests/TestS2Htz1Headless.java` fully.
Note:
- The `@BeforeClass` loads HTZ act 0
- The `@Before` calls `TestEnvironment.resetPerTest()` then re-inits level events
- Tests access `Sonic2LevelEventManager.getInstance()` and `ParallaxManager.getInstance()` directly

**Step 2: Convert to fixture pattern**

- Replace `@BeforeClass` with `SharedLevel.load(SonicGame.SONIC_2, ZONE_HTZ, 0)`
- Add `@AfterClass` with `sharedLevel.dispose()`
- Replace `@Before` with fixture builder using `.withLevelEvents()`
- Tests that access `Sonic2LevelEventManager` or `ParallaxManager` directly should continue to
  do so -- the fixture doesn't hide these, it just handles setup
- Keep all test assertions and logic identical

**Step 3: Run the converted test**

Run: `mvn test -Dtest=TestS2Htz1Headless -pl .`
Expected: PASS -- all 5+ tests pass with same behavior

**Step 4: Run full suite**

Run: `mvn test -pl .`
Expected: ALL tests pass

**Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/TestS2Htz1Headless.java
git commit -m "refactor: convert TestS2Htz1Headless to HeadlessTestFixture"
```

---

### Task 7: Update TestEnvironment to Delegate to GameContext

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestEnvironment.java`
- Modify: `src/test/java/com/openggf/tests/TestGameContext.java` (add delegation test)

**Context:** Now that GameContext.forTesting() encapsulates the reset logic, TestEnvironment
should delegate to it to avoid duplication. This ensures the ordering is defined in one place.

**Step 1: Write the test**

Add to `TestGameContext.java`:

```java
@Test
public void forTestingMatchesResetAllBehavior() {
    // Dirty some state
    Camera.getInstance().setFrozen(true);
    // forTesting should clean it
    GameContext.forTesting();
    assertFalse(Camera.getInstance().isFrozen());
}
```

**Step 2: Run to verify it passes (should already pass)**

Run: `mvn test -Dtest=TestGameContext -pl .`
Expected: PASS

**Step 3: Update TestEnvironment.resetAll() to delegate**

In `TestEnvironment.java`, replace the body of `resetAll()` with:

```java
public static void resetAll() {
    GameContext.forTesting();
}
```

Keep `resetPerTest()` as-is for now -- it has a different (smaller) reset scope that
doesn't include GameModule, AudioManager, LevelManager, or GraphicsManager resets.

**Step 4: Run full test suite**

Run: `mvn test -pl .`
Expected: ALL tests pass -- behavior is identical since GameContext.forTesting()
implements the same reset sequence.

**Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/TestEnvironment.java src/test/java/com/openggf/tests/TestGameContext.java
git commit -m "refactor: TestEnvironment.resetAll() delegates to GameContext.forTesting()"
```

---

### Task 8: Final Regression Check and Documentation

**Files:**
- No code changes

**Step 1: Run full test suite one final time**

Run: `mvn test -pl .`
Expected: ALL tests pass

**Step 2: Verify converted tests work**

Run: `mvn test -Dtest=TestGameContext,TestSharedLevel,TestHeadlessTestFixture,TestHeadlessCNZ1LiftWallStick,TestS2Htz1Headless -pl .`
Expected: ALL pass

**Step 3: Review and commit**

No code changes needed. If all green, Phase 1 is complete.

```bash
git log --oneline -8
```

Should show commits for: GameContext, SharedLevel, HeadlessTestFixture, verification,
CNZ conversion, HTZ conversion, TestEnvironment delegation, and this final check.
