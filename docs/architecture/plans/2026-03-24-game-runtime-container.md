# GameRuntime Container — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `GameRuntime` as an explicit container for all mutable gameplay state, removing singletons from 10 manager classes and replacing `GameContext`.

**Architecture:** `GameRuntime` owns Camera, LevelManager, SpriteManager, GameStateManager, TimerManager, FadeManager, CollisionSystem, TerrainCollisionManager, WaterSystem, ParallaxManager. `RuntimeManager` is the single static accessor. `GameServices` becomes a thin locator over `RuntimeManager.getCurrent()`. `DefaultObjectServices` backs against `GameRuntime` instead of singletons.

**Tech Stack:** Java 21, Maven, JUnit 5, LWJGL (OpenGL/GLFW)

**Spec:** `docs/architecture/designs/2026-03-24-game-runtime-architecture-design.md`

---

## File Map

### New Files
| File | Responsibility |
|------|---------------|
| `src/main/java/com/openggf/game/GameRuntime.java` | Container owning all mutable gameplay managers |
| `src/main/java/com/openggf/game/RuntimeManager.java` | Static holder for current `GameRuntime` |
| `src/test/java/com/openggf/game/TestGameRuntime.java` | Unit tests for runtime lifecycle |
| `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java` | Guard test: no getInstance() on removed singletons |

### Deleted Files
| File | Replacement |
|------|------------|
| `src/main/java/com/openggf/GameContext.java` | `GameRuntime` + `RuntimeManager` |
| `src/test/java/com/openggf/TestGameContextStaleGuard.java` | `TestGameRuntime` |
| `src/test/java/com/openggf/tests/TestGameContext.java` | `TestGameRuntime` |

### Major Rewrites
| File | Changes |
|------|---------|
| `src/main/java/com/openggf/level/LevelManager.java` | Constructor takes manager dependencies as params; remove 5 `final` field `getInstance()` calls |
| `src/main/java/com/openggf/level/objects/DefaultObjectServices.java` | Constructor takes `GameRuntime`; all methods read from runtime |
| `src/main/java/com/openggf/level/objects/ObjectManager.java` | `DefaultObjectServices` field initializer uses runtime instead of `LevelManager.getInstance()` |
| `src/main/java/com/openggf/GameLoop.java` | Hold `GameRuntime` reference; remove 7 cached singleton fields |
| `src/main/java/com/openggf/Engine.java` | Create/destroy runtime in lifecycle; remove cached singleton fields |
| `src/main/java/com/openggf/game/GameServices.java` | Runtime-owned methods delegate to `RuntimeManager.getCurrent()`; engine globals stay direct |
| `src/main/java/com/openggf/game/AbstractLevelInitProfile.java` | Teardown reads from `RuntimeManager.getCurrent()` or `GameServices` |
| `src/main/java/com/openggf/graphics/GraphicsManager.java` | FadeManager access via `RuntimeManager.getCurrent().getFadeManager()` instead of field |
| `src/main/java/com/openggf/level/rings/RingManager.java` | LostRingPool receives Camera as constructor param |
| `src/main/java/com/openggf/tests/HeadlessTestFixture.java` | Creates `GameRuntime` in setup; replaces `GameContext.forTesting()` |

### Singleton Removal (10 classes — remove `getInstance()`/`resetInstance()`/static instance field)
| File | Callers |
|------|---------|
| `src/main/java/com/openggf/camera/Camera.java` | 93 |
| `src/main/java/com/openggf/level/LevelManager.java` | 120 |
| `src/main/java/com/openggf/sprites/managers/SpriteManager.java` | 43 |
| `src/main/java/com/openggf/game/GameStateManager.java` | 16 |
| `src/main/java/com/openggf/timer/TimerManager.java` | 13 |
| `src/main/java/com/openggf/graphics/FadeManager.java` | 11 |
| `src/main/java/com/openggf/physics/CollisionSystem.java` | 13 |
| `src/main/java/com/openggf/physics/TerrainCollisionManager.java` | 5 |
| `src/main/java/com/openggf/level/WaterSystem.java` | 37 |
| `src/main/java/com/openggf/level/ParallaxManager.java` | 17 |

---

## Task Breakdown

The plan is structured so that the engine compiles and all tests pass after each task. Tasks 1-3 build the new infrastructure alongside existing singletons. Task 4 is the big bang: rewire everything and remove singletons. Tasks 5-7 handle ripple fixes, tests, and cleanup.

---

### Task 1: Create GameRuntime and RuntimeManager

**Files:**
- Create: `src/main/java/com/openggf/game/GameRuntime.java`
- Create: `src/main/java/com/openggf/game/RuntimeManager.java`
- Create: `src/test/java/com/openggf/game/TestGameRuntime.java`

**Purpose:** Build the container and static holder. At this stage, `GameRuntime` wraps existing singletons via `getInstance()` — no singletons are removed yet. This lets us validate the shape before the big rewire.

- [ ] **Step 1: Write GameRuntime**

`GameRuntime` has a private constructor and a static `createFromSingletons()` factory that captures all 10 managers from their current `getInstance()` methods. Getters for each. A `destroy()` method that calls `resetState()` in reverse order.

```java
package com.openggf.game;

public final class GameRuntime {
    private final Camera camera;
    private final LevelManager levelManager;
    private final SpriteManager spriteManager;
    private final GameStateManager gameState;
    private final TimerManager timers;
    private final FadeManager fadeManager;
    private final CollisionSystem collisionSystem;
    private final TerrainCollisionManager terrainCollisionManager;
    private final WaterSystem waterSystem;
    private final ParallaxManager parallaxManager;

    // Package-private constructor — only RuntimeManager creates these
    GameRuntime(Camera camera, LevelManager levelManager, ...) { ... }

    // Factory: capture from existing singletons (transitional)
    static GameRuntime createFromSingletons() { ... }

    // Getters
    public Camera getCamera() { return camera; }
    public LevelManager getLevelManager() { return levelManager; }
    // ... all 10

    // ObjectManager/RingManager accessed via LevelManager
    public ObjectManager getObjectManager() { return levelManager.getObjectManager(); }
    public RingManager getRingManager() { return levelManager.getRingManager(); }

    // Teardown in reverse construction order
    public void destroy() { ... }
}
```

- [ ] **Step 2: Write RuntimeManager**

```java
package com.openggf.game;

public final class RuntimeManager {
    private static GameRuntime current;
    private RuntimeManager() {}

    public static GameRuntime getCurrent() { return current; }

    public static synchronized void setCurrent(GameRuntime runtime) {
        current = runtime;
    }

    public static GameRuntime createGameplay() {
        GameRuntime runtime = GameRuntime.createFromSingletons();
        setCurrent(runtime);
        return runtime;
    }

    public static void destroyCurrent() {
        if (current != null) {
            current.destroy();
            current = null;
        }
    }
}
```

- [ ] **Step 3: Write unit tests**

```java
// TestGameRuntime.java
@Test void createFromSingletons_allManagersNonNull()
@Test void destroy_callsResetOnAllManagers()
@Test void runtimeManager_createAndDestroy_lifecycle()
@Test void runtimeManager_getCurrent_returnsNullBeforeCreate()
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestGameRuntime -q`
Expected: All pass

- [ ] **Step 5: Commit**

```
feat: add GameRuntime container and RuntimeManager

GameRuntime wraps all 10 mutable gameplay managers.
RuntimeManager provides static access to the current runtime.
Transitional: createFromSingletons() captures existing singletons.
```

---

### Task 2: Rewire GameServices and DefaultObjectServices

**Files:**
- Modify: `src/main/java/com/openggf/game/GameServices.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` (line 46 — field initializer)

**Purpose:** Route `GameServices` through `RuntimeManager` and back `DefaultObjectServices` against `GameRuntime`. Both changes are compatible with singletons still existing — `RuntimeManager.getCurrent()` returns a runtime that wraps the same singleton instances.

- [ ] **Step 1: Rewire GameServices**

Runtime-owned methods delegate to `RuntimeManager.getCurrent()`. Engine globals stay direct:

```java
// Runtime-owned
public static Camera camera() { return RuntimeManager.getCurrent().getCamera(); }
public static LevelManager level() { return RuntimeManager.getCurrent().getLevelManager(); }
public static GameStateManager gameState() { return RuntimeManager.getCurrent().getGameState(); }
public static TimerManager timers() { return RuntimeManager.getCurrent().getTimers(); }
public static FadeManager fade() { return RuntimeManager.getCurrent().getFadeManager(); }

// Engine globals — stay direct
public static AudioManager audio() { return AudioManager.getInstance(); }
public static RomManager rom() { return RomManager.getInstance(); }
public static DebugOverlayManager debugOverlay() { return DebugOverlayManager.getInstance(); }
```

- [ ] **Step 2: Rewire DefaultObjectServices**

Change constructor from `LevelManager` to `GameRuntime`. All methods read from runtime:

```java
public DefaultObjectServices(GameRuntime runtime) {
    this.runtime = runtime;
}

public Camera camera() { return runtime.getCamera(); }
public ObjectManager objectManager() { return runtime.getLevelManager().getObjectManager(); }
public LevelState levelGamestate() { return runtime.getLevelManager().getLevelGamestate(); }
// ... all methods
```

Keep the no-arg constructor for test contexts (reads from `RuntimeManager.getCurrent()`).

- [ ] **Step 3: Update ObjectManager field initializer**

Change line 46 from:
```java
private final ObjectServices objectServices = new DefaultObjectServices(LevelManager.getInstance());
```
To:
```java
private final ObjectServices objectServices = new DefaultObjectServices(RuntimeManager.getCurrent());
```

- [ ] **Step 4: Wire RuntimeManager in Engine.initializeGame()**

Add `RuntimeManager.createGameplay()` call at the start of `initializeGame()` (before any manager access), and `RuntimeManager.destroyCurrent()` in `cleanup()`. This ensures `RuntimeManager.getCurrent()` is non-null when `GameServices` methods are called.

- [ ] **Step 5: Run full test suite**

Run: `mvn test -q`
Expected: All 1894+ tests pass (singletons still exist, runtime wraps them)

- [ ] **Step 6: Commit**

```
refactor: route GameServices and DefaultObjectServices through GameRuntime

GameServices runtime-owned methods delegate to RuntimeManager.getCurrent().
DefaultObjectServices backed by GameRuntime instead of LevelManager.
Engine creates/destroys runtime in lifecycle methods.
Singletons still exist — behavioral equivalence maintained.
```

---

### Task 3: Rewire GameLoop and Engine

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`

**Purpose:** Replace cached singleton fields with a `GameRuntime` reference. This is still compatible with existing singletons.

- [ ] **Step 1: Rewire GameLoop**

Replace the 7 cached singleton fields:
```java
// Old:
private final SpriteManager spriteManager = SpriteManager.getInstance();
private final Camera camera = Camera.getInstance();
private final TimerManager timerManager = GameServices.timers();
private final LevelManager levelManager = LevelManager.getInstance();
// ... etc

// New:
private GameRuntime runtime;  // set after RuntimeManager.createGameplay()

// In all methods, replace:
//   spriteManager.xxx() → runtime.getSpriteManager().xxx()
//   camera.xxx() → runtime.getCamera().xxx()
//   levelManager.xxx() → runtime.getLevelManager().xxx()
```

GameLoop receives the runtime via a `setRuntime(GameRuntime)` method called from Engine after `RuntimeManager.createGameplay()`.

- [ ] **Step 2: Rewire Engine**

Replace cached singleton fields the same way. Engine holds a `GameRuntime` reference set during `initializeGame()`.

- [ ] **Step 3: Run full test suite**

Run: `mvn test -q`
Expected: All pass

- [ ] **Step 4: Commit**

```
refactor: GameLoop and Engine use GameRuntime instead of cached singletons
```

---

### Task 4: Rewire LevelManager and Fix Dependency Chains

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (constructor + 5 field captures)
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java` (LostRingPool Camera capture)
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java` (FadeManager access)
- Modify: `src/main/java/com/openggf/game/AbstractLevelInitProfile.java` (teardown)
- Modify: S1/S2/S3K `LevelInitProfile` subclasses if needed
- Modify: `src/main/java/com/openggf/physics/GroundSensor.java` (setLevelManager stays)
- Modify: `src/main/java/com/openggf/physics/ObjectTerrainUtils.java` (3 call sites)

**Purpose:** Fix the internal dependency chains so managers receive their peers from the runtime instead of `getInstance()`.

- [ ] **Step 1: LevelManager constructor takes dependencies**

Change the 5 `final` field initializers from `getInstance()` to constructor parameters:

```java
// Old:
private final SpriteManager spriteManager = SpriteManager.getInstance();
private final ParallaxManager parallaxManager = ParallaxManager.getInstance();
private final Camera camera = Camera.getInstance();

// New:
private final SpriteManager spriteManager;
private final ParallaxManager parallaxManager;
private final Camera camera;

public LevelManager(GameRuntime runtime) {
    this.spriteManager = runtime.getSpriteManager();
    this.parallaxManager = runtime.getParallaxManager();
    this.camera = runtime.getCamera();
    this.graphicsManager = GraphicsManager.getInstance();  // stays engine global
    this.configService = SonicConfigurationService.getInstance();  // stays engine global
    this.overlayManager = GameServices.debugOverlay();  // stays engine global
    this.profiler = PerformanceProfiler.getInstance();  // stays engine global
}
```

Update `GameRuntime.createFromSingletons()` to construct LevelManager with the runtime reference. This requires a two-phase init: create the independent managers first, then construct LevelManager passing them.

- [ ] **Step 2: Fix RingManager.LostRingPool Camera capture**

Change from field-initializer singleton capture to constructor parameter:

```java
// Old (RingManager inner class LostRingPool):
private final Camera camera = Camera.getInstance();

// New:
private final Camera camera;
LostRingPool(..., Camera camera) {
    this.camera = camera;
    ...
}
```

Pass Camera from LevelManager (which has it from the runtime).

- [ ] **Step 3: Fix GraphicsManager FadeManager access**

Remove the `fadeManager` field. Access via `RuntimeManager.getCurrent().getFadeManager()`:

```java
// Old:
private FadeManager fadeManager;  // field
this.fadeManager = FadeManager.getInstance();  // in init()

// New:
public FadeManager getFadeManager() {
    return RuntimeManager.getCurrent().getFadeManager();
}
```

Update `UiRenderPipeline` setup to not cache FadeManager either — read from runtime each frame.

- [ ] **Step 4: Fix AbstractLevelInitProfile teardown**

Replace direct `getInstance()` calls with `GameServices` delegates (which now route through RuntimeManager):

```java
// Old:
ParallaxManager.getInstance().resetState()
SpriteManager.getInstance().resetState()
CollisionSystem.getInstance().resetState()

// New:
GameServices.level().getParallaxManager()... // No — wrong abstraction
// Actually: read from RuntimeManager
RuntimeManager.getCurrent().getParallaxManager().resetState()
RuntimeManager.getCurrent().getSpriteManager().resetState()
RuntimeManager.getCurrent().getCollisionSystem().resetState()
```

Or simpler: use `GameServices` for everything since it now routes through RuntimeManager anyway:
```java
GameServices.camera().resetState()
GameServices.level().resetState()
// ... add missing methods to GameServices for the ones not yet there
```

- [ ] **Step 5: Fix ObjectTerrainUtils (3 call sites)**

```java
// Old:
LevelManager.getInstance()

// New:
GameServices.level()
```

- [ ] **Step 6: Run full test suite**

Run: `mvn test -q`
Expected: All pass

- [ ] **Step 7: Commit**

```
refactor: LevelManager takes runtime dependencies, fix FadeManager/RingManager/terrain chains
```

---

### Task 5: Remove Singletons

**Files:**
- Modify: 10 manager classes (remove `getInstance()`, `resetInstance()`, `private static instance`)
- Modify: All remaining callers (~80-100 files)

**Purpose:** The big bang. Every `Foo.getInstance()` call for the 10 removed singletons has been replaced by either `services()`, `GameServices.foo()`, or direct runtime access in Tasks 1-4. Now remove the static infrastructure.

- [ ] **Step 1: Remove singleton pattern from all 10 classes**

For each of: Camera, LevelManager, SpriteManager, GameStateManager, TimerManager, FadeManager, CollisionSystem, TerrainCollisionManager, WaterSystem, ParallaxManager:

1. Delete `private static Foo instance;`
2. Delete `public static synchronized Foo getInstance() { ... }`
3. Delete `public static void resetInstance() { ... }` if present
4. Make constructor `public` (was private/package-private for singleton)
5. Remove `synchronized` from any methods that only needed it for singleton init

- [ ] **Step 2: Fix remaining callers (dispatch 3 parallel agents)**

For each remaining `Foo.getInstance()` call in non-object code:
- If in a class that holds a runtime reference → `runtime.getFoo()`
- If in infrastructure code → `GameServices.foo()`
- If in test code → `RuntimeManager.getCurrent().getFoo()` or test fixture accessor

Split across 3 agents: production code, test infrastructure, individual test files.

- [ ] **Step 3: Compile check**

Run: `mvn compile test-compile -q`
Expected: Zero errors

- [ ] **Step 4: Run full test suite**

Run: `mvn test -q`
Expected: All 1894+ tests pass

- [ ] **Step 5: Commit**

```
refactor: remove singletons from 10 manager classes

Camera, LevelManager, SpriteManager, GameStateManager, TimerManager,
FadeManager, CollisionSystem, TerrainCollisionManager, WaterSystem,
ParallaxManager no longer have getInstance(). All access routes through
GameRuntime (via services(), GameServices, or direct runtime reference).
```

---

### Task 6: Delete GameContext and Update Test Infrastructure

**Files:**
- Delete: `src/main/java/com/openggf/GameContext.java`
- Delete: `src/test/java/com/openggf/TestGameContextStaleGuard.java`
- Delete: `src/test/java/com/openggf/tests/TestGameContext.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`
- Modify: `src/test/java/com/openggf/tests/TestEnvironment.java`
- Modify: All test files using `GameContext.forTesting()` (~18 files)
- Modify: `src/main/java/com/openggf/game/LevelInitProfile.java` (if it references GameContext)
- Create: `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`

**Purpose:** Remove the old context, update test infrastructure to use `RuntimeManager`.

- [ ] **Step 1: Update HeadlessTestFixture**

Replace `GameContext.forTesting()` with `RuntimeManager.destroyCurrent(); RuntimeManager.createGameplay();`

- [ ] **Step 2: Update all GameContext.forTesting() callers**

Replace in all ~18 test files. Pattern:
```java
// Old:
GameContext ctx = GameContext.forTesting();

// New:
RuntimeManager.destroyCurrent();
GameRuntime runtime = RuntimeManager.createGameplay();
```

If the test used `ctx.camera()` etc., replace with `runtime.getCamera()` or `GameServices.camera()`.

- [ ] **Step 3: Delete GameContext and its tests**

Remove all 3 files. Fix any import errors.

- [ ] **Step 4: Add singleton guard test**

`TestRuntimeSingletonGuard` scans bytecode of ALL source files (not just objects) for `getInstance()` calls to the 10 removed singletons. Fails if found. This prevents regression.

- [ ] **Step 5: Run full test suite**

Run: `mvn test -q`
Expected: All pass

- [ ] **Step 6: Commit**

```
refactor: delete GameContext, update test infrastructure to RuntimeManager

GameContext replaced by GameRuntime + RuntimeManager.
HeadlessTestFixture and 18 test files updated.
TestRuntimeSingletonGuard prevents singleton regression.
```

---

### Task 7: Documentation and Final Verification

**Files:**
- Modify: `CLAUDE.md` (update architecture section)
- Modify: `docs/architecture/singleton-lifecycle.md` (if it exists — update or delete)
- Update: Memory files

- [ ] **Step 1: Update CLAUDE.md**

Add `GameRuntime` / `RuntimeManager` to the Architecture section. Update the "Core Managers" section to note they're runtime-owned, not singletons. Update `GameServices` description.

- [ ] **Step 2: Final full test suite run**

Run: `mvn test -q`
Expected: All 1894+ tests pass, zero `getInstance()` for removed singletons

- [ ] **Step 3: Verify singleton removal is complete**

Run: `grep -rn "getInstance()" src/main/java --include="*.java" | grep -E "Camera|LevelManager|SpriteManager|GameStateManager|TimerManager|FadeManager|CollisionSystem|TerrainCollisionManager|WaterSystem|ParallaxManager" | grep -v "//"`
Expected: Zero matches

- [ ] **Step 4: Commit**

```
docs: update architecture docs for GameRuntime model
```

---

## Execution Notes

**Total estimated scope:** ~120-150 files across 7 tasks.

**Risk mitigation:** Tasks 1-3 are additive — they build new infrastructure without removing anything. The engine works identically at each step because `GameRuntime.createFromSingletons()` wraps the same instances. Task 4 fixes internal dependency chains. Task 5 is the actual singleton removal — the point of no return. Tasks 6-7 are cleanup.

**Parallelism:** Task 5 Step 2 (fix remaining callers) can be split across 3 agents: production code, test infrastructure, individual tests. Task 6 Step 2 (GameContext callers) is mechanical and can be done by a single agent.

**Key invariant:** At no point should object code (`services()` callers) need to change. The entire migration happens underneath the `ObjectServices` interface that was built in the prior session.
