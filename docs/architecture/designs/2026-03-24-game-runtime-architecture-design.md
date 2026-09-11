# GameRuntime Architecture — Design Spec

**Date:** 2026-03-24
**Status:** Draft
**Goal:** Introduce an explicit `GameRuntime` container for all mutable gameplay state, enabling a future in-game level editor.

## Context

The engine currently uses 47+ singletons for gameplay state (Camera, LevelManager, ObjectManager, etc.). An in-game level editor needs to mutate or replace live level state without restarting. Global singletons make this fragile because code can hold stale references across level reloads, editor mode transitions, and live rebuilds.

The `ObjectServices` migration (completed 2026-03-24) moved ~230 object classes from direct singleton access to an injectable interface. This spec builds on that foundation by introducing the runtime container that `ObjectServices` should have been backed by from the start.

**Prior art:** `GameContext` already exists as a plain holder bundling critical-path managers with a `production()` factory and `forTesting()` reset sequence. `GameRuntime` replaces `GameContext` entirely — it takes over the holder role, the testing reset role, and adds lifecycle ownership. `GameContext` is deleted.

## High-Level Roadmap

| Phase | Scope | Unlocks |
|-------|-------|---------|
| **1. GameRuntime Container** | Explicit runtime object, singleton removal, rewiring | Clean state ownership |
| **2. Finish ObjectServices Migration** | Clear 47 KNOWN_UNMIGRATED entries | All object code injectable |
| **3. Level Data Abstraction** | Mutable `LevelData` interface behind LevelManager | Level state can be mutated |
| **4. Editor Mode Integration** | Pause, overlay, mutate, rebuild, resume | Usable level editor |
| **5. Save/Load + Polish** | Standalone level file format, editor UX | Persistent edited levels |

This spec covers **Phase 1** in detail.

## Design — Phase 1: GameRuntime Container

### GameRuntime Ownership

`GameRuntime` is a plain Java class (not a singleton) holding all mutable gameplay state:

```
GameRuntime
  Camera camera
  LevelManager levelManager
  SpriteManager spriteManager
  GameStateManager gameState
  TimerManager timers
  FadeManager fadeManager
  CollisionSystem collisionSystem
  TerrainCollisionManager terrainCollisionManager
  WaterSystem waterSystem
  ParallaxManager parallaxManager
```

Note: `ObjectManager` and `RingManager` are owned by `LevelManager` (created during level load), not directly by `GameRuntime`. They are accessed via `runtime.getLevelManager().getObjectManager()` and exposed through `ObjectServices` as `services().objectManager()` / `services().ringManager()`.

**Not owned by GameRuntime** (engine globals — stay as singletons):
- `GraphicsManager` — OpenGL context, shaders, pattern atlas (tied to window)
- `AudioManager` — audio device backend (accessed from audio thread — must never be runtime-scoped)
- `SonicConfigurationService` — configuration
- `RomManager` — ROM data (immutable once loaded)
- `PerformanceProfiler`, `DebugOverlayManager`, `DebugRenderer` — debug tools
- `GameModuleRegistry` — game module selection

**GameServices.audio()** stays as a direct `AudioManager.getInstance()` call since `AudioManager` remains an engine global. It does NOT route through `RuntimeManager`. Same for `GameServices.rom()` and `GameServices.debugOverlay()`.

### FadeManager Ownership Boundary

`FadeManager` is runtime-owned, but `GraphicsManager` (engine global) holds a reference to it for shader binding. Resolution: `GraphicsManager` does NOT hold `FadeManager` as a field. Instead, it accesses it via `RuntimeManager.getCurrent().getFadeManager()` when needed, or receives it as a method parameter. This prevents stale references across runtime swaps.

### Construction Order

`GameRuntime` constructor creates managers in dependency order:

```
1. Camera                    (no dependencies)
2. TimerManager              (no dependencies)
3. GameStateManager          (no dependencies)
4. FadeManager               (no dependencies)
5. WaterSystem               (no dependencies)
6. ParallaxManager           (no dependencies)
7. TerrainCollisionManager   (no dependencies)
8. CollisionSystem           (depends on TerrainCollisionManager)
9. SpriteManager             (no dependencies)
10. LevelManager             (depends on Camera, SpriteManager, ParallaxManager,
                              CollisionSystem — passed as constructor params)
```

`LevelManager` currently field-captures `SpriteManager`, `Camera`, `ParallaxManager`, and `CollisionSystem` via `getInstance()`. These become constructor parameters. `LevelManager` is the most invasive rewrite — it is a **major rewrite target**, not just a singleton removal.

### Destruction Contract

`GameRuntime.destroy()` tears down in reverse construction order:
```
1. LevelManager.resetState()          — clears level data, objects, rings
2. SpriteManager.resetState()         — clears sprite list
3. CollisionSystem.resetState()       — clears collision state
4. TerrainCollisionManager.resetState()
5. ParallaxManager.resetZoneState()   — clears scroll offsets
6. WaterSystem.reset()                — clears water state
7. FadeManager.cancel()               — cancels active fade
8. GameStateManager.resetState()      — resets score, lives, etc.
9. TimerManager.resetState()          — clears all timers
10. Camera.resetState()               — resets position, bounds
```

This replaces `GameContext.forTesting()` and `AbstractLevelInitProfile.levelTeardownSteps()` for the runtime-level reset. Per-level teardown (between acts/zones) stays in `LevelInitProfile`.

### Replacing GameContext

`GameContext` is deleted. Its responsibilities transfer to:
- `GameContext.production()` → `RuntimeManager.getCurrent()` (returns `GameRuntime`)
- `GameContext.forTesting()` → `RuntimeManager.destroyCurrent()` + `RuntimeManager.createGameplay()`
- Generation counter / stale detection → not needed; runtime references are explicit, not captured-and-checked

All tests using `GameContext.forTesting()` switch to `RuntimeManager.destroyCurrent(); RuntimeManager.createGameplay();` (or a `TestRuntime.reset()` helper).

### RuntimeManager

The one true singleton for gameplay state:

```java
public final class RuntimeManager {
    private static GameRuntime current;

    public static GameRuntime getCurrent() { return current; }
    public static void setCurrent(GameRuntime runtime) { ... }
    public static GameRuntime createGameplay() { ... }
    public static void destroyCurrent() { ... }
}
```

**Lifecycle:**
- `Engine.initializeGame()` calls `RuntimeManager.createGameplay()` which constructs a `GameRuntime`, wires up all managers, sets it as current
- `GameLoop` receives the runtime from `RuntimeManager.getCurrent()`
- Level transitions: `LevelManager.loadZoneAndAct()` rebuilds level-scoped state within the existing runtime
- `Engine.cleanup()` calls `RuntimeManager.destroyCurrent()`
- Future editor: mutates the current runtime while paused, or creates a fresh runtime for hard resets

### Rewiring

**GameServices becomes a thin locator:**
```java
// Runtime-owned — route through RuntimeManager
public static Camera camera() {
    return RuntimeManager.getCurrent().getCamera();
}
public static LevelManager level() {
    return RuntimeManager.getCurrent().getLevelManager();
}

// Engine globals — stay as direct singleton calls
public static AudioManager audio() {
    return AudioManager.getInstance();
}
public static RomManager rom() {
    return RomManager.getInstance();
}
```

**DefaultObjectServices backs against GameRuntime:**
```java
public DefaultObjectServices(GameRuntime runtime) {
    this.runtime = runtime;
}
public Camera camera() { return runtime.getCamera(); }
public ObjectManager objectManager() { return runtime.getObjectManager(); }
```

`ObjectManager` constructs `DefaultObjectServices` during level load. It receives the `GameRuntime` reference from `LevelManager`, which receives it at construction.

**GameLoop and Engine** hold a `GameRuntime` reference instead of caching singletons.

### Physics Layer: GroundSensor and ObjectTerrainUtils

`GroundSensor` has a static `cachedLevelManager` field set via `setLevelManager()`. `ObjectTerrainUtils` calls `LevelManager.getInstance()` directly at 3 call sites. Both are on the physics hot path.

Resolution: `GroundSensor.setLevelManager()` is called during `LevelManager` construction (already happens today). `ObjectTerrainUtils` switches to `GameServices.level()` (which routes through RuntimeManager). No performance concern — `GameServices.level()` is a static field read, same cost as `getInstance()`.

### Field Captures in LevelManager and RingManager

`LevelManager` field-captures `SpriteManager`, `Camera`, `ParallaxManager` as `final` fields via `getInstance()`. Resolution: these become constructor parameters.

`RingManager.LostRingPool` captures `Camera` in a `final` field via `Camera.getInstance()`. Resolution: receives `Camera` from the runtime via `LevelManager`'s constructor-injected reference.

### AbstractLevelInitProfile Teardown

`AbstractLevelInitProfile.levelTeardownSteps()` calls `getInstance()` on every runtime-owned manager. Resolution: the teardown steps receive a `GameRuntime` parameter (or read from `RuntimeManager.getCurrent()`), calling `runtime.getCamera().resetState()` etc. All three game-specific `LevelInitProfile` subclasses (S1, S2, S3K) are updated.

### Thread Safety Constraint

**The audio thread must never access runtime-owned state.** `AudioManager` stays as an engine global singleton specifically because `SmpsSequencer` runs on the audio callback thread. This constraint is permanent and applies to all future phases. If audio needs gameplay state (e.g., zone-specific music), it must receive it via an explicit handoff (method parameter or message), not by reading from the runtime.

### Singleton Removal

These 10 classes lose `getInstance()` / `resetInstance()`:
- `Camera`, `LevelManager`, `SpriteManager`, `GameStateManager`
- `TimerManager`, `FadeManager`, `CollisionSystem`, `TerrainCollisionManager`
- `WaterSystem`, `ParallaxManager`

Code that previously called `Foo.getInstance()` routes through:
- `services()` — object code (already migrated)
- `GameServices.foo()` — non-object code (rewired to RuntimeManager)
- Direct runtime reference — GameLoop, Engine

### Editor Mode Interaction (Phase 4 Preview)

**Enter editor:** Gameplay pauses. Camera switches to free-fly. Editor overlay activates. GameRuntime stays live — all state preserved, just frozen. Editor mutates the runtime's state directly.

**Exit editor:** Editor overlay deactivates. Affected subsystems rebuild (tilemap, collision, objects). Camera snaps to player. Gameplay resumes.

No runtime swap — same instance throughout. Swap only for hard resets (reload from ROM or load saved file).

### Testing Strategy

- **GameRuntime unit tests:** construction, cleanup, create-destroy-create lifecycle
- **HeadlessTestRunner:** creates a `GameRuntime` in setup, sets via `RuntimeManager`
- **Existing tests:** replace `GameContext.forTesting()` with `RuntimeManager` reset; replace `Foo.getInstance()` with `GameServices.foo()` or direct runtime access
- **Guard tests:** existing `TestObjectServicesMigrationGuard` stays; add guard for removed singletons in non-object code
- **TestRuntime helper:** minimal `GameRuntime` for unit tests that don't need a full level

### File Changes

**New files (2):**
- `GameRuntime.java`
- `RuntimeManager.java`

**Deleted files (1):**
- `GameContext.java` (replaced by GameRuntime)

**Major rewrites (5):**
- `DefaultObjectServices.java` — backs against GameRuntime
- `GameLoop.java` — receives runtime instead of caching singletons
- `Engine.java` — creates/destroys runtime in lifecycle methods
- `GameServices.java` — delegates runtime-owned to RuntimeManager, keeps engine globals direct
- `LevelManager.java` — constructor takes manager dependencies as params, removes field-init singletons

**Singleton removal (10 classes):**
Remove `getInstance()` / `resetInstance()` / `private static instance` from Camera, LevelManager, SpriteManager, GameStateManager, TimerManager, FadeManager, CollisionSystem, TerrainCollisionManager, WaterSystem, ParallaxManager.

**Significant ripple fixes (explicitly tracked):**
- `AbstractLevelInitProfile` + S1/S2/S3K subclasses — teardown uses runtime
- `GroundSensor` — `setLevelManager()` stays but source changes
- `ObjectTerrainUtils` — 3 call sites switch to `GameServices.level()`
- `RingManager.LostRingPool` — Camera received from LevelManager
- `GraphicsManager` — FadeManager access via RuntimeManager, not field
- `ObjectManager` — DefaultObjectServices constructed with GameRuntime
- `HeadlessTestRunner`, `HeadlessTestFixture` — runtime-aware setup
- All `GameContext.forTesting()` callers → RuntimeManager pattern

**General ripple fixes (~80-100 files):**
Non-object code using removed singletons, game-specific event managers, scroll handlers, title screens, level select, test setup methods.

**Not touched:**
- 230+ migrated object files (use `services()`)
- GraphicsManager, AudioManager, RomManager, ConfigService (stay as singletons)
- GameModule system, rendering pipeline, SMPS audio driver

**Estimated total:** ~120-150 files in one commit.

### Success Criteria

1. All 1894+ tests pass
2. Zero `getInstance()` calls remain for the 10 removed singletons
3. `GameContext` deleted — no references remain
4. `GameRuntime` can be constructed, used, destroyed, and reconstructed without state leaks
5. Engine behavior is identical — pixel-for-pixel rendering unchanged
6. `ObjectServices` backed by runtime, not singletons
7. Audio thread does not access any runtime-owned state
