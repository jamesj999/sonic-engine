# Testability Improvement: GameContext + HeadlessTestFixture

> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.

**Date:** 2026-02-27
**Status:** Design
**Focus:** Incremental testability improvement via GameContext holder and test fixture builder

## Problem

The engine has 42 singleton classes. Tests must reset 8-10 of them in a specific order before
each test run. This ordering is fragile, manually maintained, and repeated across 80+ test
classes. Key pain points:

1. **Fragile reset ordering** -- `TestEnvironment.resetAll()` / `resetPerTest()` enforce a
   specific sequence. Missing a step or misordering causes subtle cross-test state leakage.
2. **Static GroundSensor dependency** -- `GroundSensor.setLevelManager()` is a static setter
   that must be called after level loading and re-called after resets.
3. **Shared mutable state** -- Tests using `@BeforeClass` level loading share LevelManager
   state, creating invisible coupling between tests in the same class.
4. **Boilerplate** -- ~20 lines of setup code repeated per test class: create sprite, configure
   camera, load level, wire sensors, update position.

## Design

### GameContext

A plain holder object bundling the critical-path managers. No framework, no magic.

```java
package com.openggf;

public class GameContext {
    private final Camera camera;
    private final LevelManager levelManager;
    private final SpriteManager spriteManager;
    private final CollisionSystem collisionSystem;
    private final GraphicsManager graphicsManager;
    private final GameStateManager gameStateManager;
    private final TimerManager timerManager;
    private final WaterSystem waterSystem;

    // Private constructor -- use factory methods
    private GameContext(Camera camera, LevelManager levelManager,
                        SpriteManager spriteManager, CollisionSystem collisionSystem,
                        GraphicsManager graphicsManager, GameStateManager gameStateManager,
                        TimerManager timerManager, WaterSystem waterSystem) {
        this.camera = camera;
        this.levelManager = levelManager;
        this.spriteManager = spriteManager;
        this.collisionSystem = collisionSystem;
        this.graphicsManager = graphicsManager;
        this.gameStateManager = gameStateManager;
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
            GameStateManager.getInstance(),
            TimerManager.getInstance(),
            WaterSystem.getInstance()
        );
    }

    /**
     * Creates a fresh, isolated context for testing.
     * Resets all critical singletons in correct order.
     * Wires GroundSensor to the fresh LevelManager.
     */
    public static GameContext forTesting() {
        // Phase 1: Game module
        GameModuleRegistry.reset();

        // Phase 2: Audio (reset ROM-specific cache)
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

        // Phase 8: Wire GroundSensor
        GroundSensor.setLevelManager(LevelManager.getInstance());

        return production();
    }

    // Getters
    public Camera camera() { return camera; }
    public LevelManager levelManager() { return levelManager; }
    public SpriteManager spriteManager() { return spriteManager; }
    public CollisionSystem collisionSystem() { return collisionSystem; }
    public GraphicsManager graphicsManager() { return graphicsManager; }
    public GameStateManager gameStateManager() { return gameStateManager; }
    public TimerManager timerManager() { return timerManager; }
    public WaterSystem waterSystem() { return waterSystem; }
}
```

**Design decisions:**
- Immutable after construction
- `production()` is a zero-cost wrapper -- no state change, no allocation beyond the holder
- `forTesting()` encapsulates the reset ordering currently in `TestEnvironment`
- No AudioManager in the holder (it's reset but rarely accessed by test assertions)

### SharedLevel

Encapsulates loaded level data for reuse across tests in the same class.

```java
package com.openggf.tests;

public class SharedLevel {
    private final Level level;
    private final SonicGame game;
    private final int zone;
    private final int act;

    public static SharedLevel load(SonicGame game, int zone, int act) {
        // Init headless graphics if needed
        GraphicsManager.getInstance().initHeadless();

        // Create temp sprite for level loading (LevelManager needs a focused sprite)
        Sonic temp = new Sonic((byte) 0, (short) 0, (short) 0);
        SpriteManager.getInstance().addSprite(temp);
        Camera.getInstance().setFocusedSprite(temp);
        Camera.getInstance().setFrozen(false);

        // Load level
        LevelManager.getInstance().loadZoneAndAct(zone, act);
        GroundSensor.setLevelManager(LevelManager.getInstance());

        return new SharedLevel(
            LevelManager.getInstance().getCurrentLevel(), game, zone, act
        );
    }

    public void dispose() {
        // Release resources, clear cached data
    }

    // Getters
    public Level level() { return level; }
    public SonicGame game() { return game; }
    public int zone() { return zone; }
    public int act() { return act; }
}
```

### HeadlessTestFixture

Builder-pattern test setup that encapsulates all boilerplate.

```java
package com.openggf.tests;

public class HeadlessTestFixture {
    private final GameContext context;
    private final HeadlessTestRunner runner;
    private final AbstractPlayableSprite sprite;

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private SonicGame game = SonicGame.SONIC_2;
        private SharedLevel sharedLevel;
        private int zone, act;
        private short startX, startY;

        public Builder game(SonicGame game) { this.game = game; return this; }
        public Builder zone(int zone, int act) {
            this.zone = zone; this.act = act; return this;
        }
        public Builder withSharedLevel(SharedLevel level) {
            this.sharedLevel = level; return this;
        }
        public Builder startPosition(short x, short y) {
            this.startX = x; this.startY = y; return this;
        }

        public HeadlessTestFixture build() {
            GameContext ctx;

            if (sharedLevel != null) {
                // Shared level: reset transient state only
                ctx = GameContext.forTesting();
                // Re-wire to shared level data
                // LevelManager keeps the loaded level from @BeforeClass
            } else {
                // Fresh level: full reset + load
                ctx = GameContext.forTesting();
                ctx.graphicsManager().initHeadless();
                ctx.levelManager().loadZoneAndAct(zone, act);
            }

            // Create sprite
            Sonic sprite = new Sonic((byte) 0, startX, startY);
            ctx.spriteManager().addSprite(sprite);

            // Wire camera
            Camera camera = ctx.camera();
            camera.setFocusedSprite(sprite);
            camera.setFrozen(false);

            // Set camera bounds from level
            Level level = sharedLevel != null
                ? sharedLevel.level()
                : ctx.levelManager().getCurrentLevel();
            if (level != null) {
                camera.setMinX((short) level.getMinX());
                camera.setMaxX((short) level.getMaxX());
                camera.setMinY((short) level.getMinY());
                camera.setMaxY((short) level.getMaxY());
            }

            // Wire GroundSensor
            GroundSensor.setLevelManager(ctx.levelManager());

            // Snap camera
            camera.updatePosition(true);

            // Create runner
            HeadlessTestRunner runner = new HeadlessTestRunner(sprite);

            return new HeadlessTestFixture(ctx, runner, sprite);
        }
    }

    // Convenience delegation
    public void stepFrame(boolean up, boolean down,
                          boolean left, boolean right, boolean jump) {
        runner.stepFrame(up, down, left, right, jump);
    }

    public void stepIdleFrames(int count) {
        runner.stepIdleFrames(count);
    }

    public AbstractPlayableSprite sprite() { return sprite; }
    public Camera camera() { return context.camera(); }
    public GameContext context() { return context; }
    public HeadlessTestRunner runner() { return runner; }
}
```

### GroundSensor Fix

In Phase 1, `GroundSensor.setLevelManager()` remains as-is but is called exclusively by
`GameContext.forTesting()` and `HeadlessTestFixture.build()`. Tests no longer call it directly.

Future (Phase 4): Add a `GroundSensor(LevelManager lm)` constructor so sensors created within
a GameContext don't rely on the static field at all.

### Test Usage: Before and After

**Before (current):**
```java
@RequiresRom(SonicGame.SONIC_2)
public class TestHtzEarthquake {
    @ClassRule public static RequiresRomRule romRule = new RequiresRomRule();

    @BeforeClass
    public static void loadLevel() throws Exception {
        GraphicsManager.getInstance().initHeadless();
        Sonic temp = new Sonic((byte) 0, (short) 0, (short) 0);
        SpriteManager.getInstance().addSprite(temp);
        Camera camera = Camera.getInstance();
        camera.setFocusedSprite(temp);
        camera.setFrozen(false);
        LevelManager.getInstance().loadZoneAndAct(ZONE_HTZ, 0);
        GroundSensor.setLevelManager(LevelManager.getInstance());
    }

    @Before
    public void setUp() {
        TestEnvironment.resetPerTest();
        sprite = new Sonic((byte) 0, (short) 0, (short) 0);
        SpriteManager.getInstance().addSprite(sprite);
        Camera camera = Camera.getInstance();
        camera.setFocusedSprite(sprite);
        camera.setFrozen(false);
        Level level = LevelManager.getInstance().getCurrentLevel();
        if (level != null) {
            camera.setMinX((short) level.getMinX());
            camera.setMaxX((short) level.getMaxX());
            camera.setMinY((short) level.getMinY());
            camera.setMaxY((short) level.getMaxY());
        }
        camera.updatePosition(true);
        testRunner = new HeadlessTestRunner(sprite);
    }

    @Test
    public void testEarthquakeTriggers() {
        sprite.setX((short) 0x2A00);
        sprite.setY((short) 0x300);
        Camera.getInstance().updatePosition(true);
        for (int i = 0; i < 60; i++) {
            testRunner.stepFrame(false, false, true, false, false);
        }
        // assertions...
    }
}
```

**After:**
```java
@RequiresRom(SonicGame.SONIC_2)
public class TestHtzEarthquake {
    @ClassRule public static RequiresRomRule romRule = new RequiresRomRule();
    private static SharedLevel sharedLevel;
    private HeadlessTestFixture fixture;

    @BeforeClass
    public static void loadLevel() {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_HTZ, 0);
    }

    @AfterClass
    public static void cleanup() {
        sharedLevel.dispose();
    }

    @Before
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
            .withSharedLevel(sharedLevel)
            .startPosition((short) 0x2A00, (short) 0x300)
            .build();
    }

    @Test
    public void testEarthquakeTriggers() {
        for (int i = 0; i < 60; i++) {
            fixture.stepFrame(false, false, true, false, false);
        }
        // assertions via fixture.sprite(), fixture.camera()
    }
}
```

~20 lines of boilerplate reduced to ~5.

## Migration Strategy

### Phase 1: Foundation

- Create `GameContext` with `production()` and `forTesting()` factories
- Create `SharedLevel` with `load()` and `dispose()`
- Create `HeadlessTestFixture` with builder API
- Fix GroundSensor: ensure `forTesting()` and `build()` always wire it correctly
- Modify `HeadlessTestRunner` to accept optional `GameContext` (backward-compatible)
- Write 2-3 NEW tests using the fixture to validate the pattern
- Existing tests unchanged and passing

### Phase 2: Convert Critical-Path Tests

Convert the most fragile/most-touched test classes:
- `TestHeadlessWallCollision`
- `TestS2Htz1Headless` (HTZ earthquake)
- `TestDEZDeathEggRobot` (boss tests)
- `TestCNZObjectBugs`

Old-style tests continue working alongside new-style.

### Phase 3: Gradual Migration

- Convert remaining tests as they're touched for feature work
- No big-bang rewrite -- each test converts when modified
- `TestEnvironment.resetAll()` / `resetPerTest()` remain available indefinitely

### Phase 4: Production Migration (Future, Optional)

- Production classes optionally accept `GameContext` via constructor
- `getInstance()` delegates to a module-scoped context
- GroundSensor gets instance-level `LevelManager` field
- Long-term path toward full DI with no pressure or deadline

## Constraints

- **Zero breaking changes** -- both old and new test patterns coexist
- **No DI framework** -- manual constructor injection only
- **No production code changes in Phase 1** -- only new classes + test infrastructure
- **Existing 240+ tests must remain green** at every phase boundary

## Files to Create

| File | Package | Purpose |
|------|---------|---------|
| `GameContext.java` | `com.openggf` | Manager holder with factory methods |
| `SharedLevel.java` | `com.openggf.tests` | Reusable loaded level data |
| `HeadlessTestFixture.java` | `com.openggf.tests` | Builder-pattern test setup |

## Files to Modify

| File | Change |
|------|--------|
| `HeadlessTestRunner.java` | Add optional `GameContext` constructor parameter |
| Per-test classes (Phase 2+) | Convert to fixture pattern as touched |

## Success Criteria

1. New test classes can be written with <5 lines of setup
2. No test needs to call `resetInstance()` or `setLevelManager()` directly
3. All existing tests pass without modification
4. Cross-test state leakage eliminated for fixture-based tests
