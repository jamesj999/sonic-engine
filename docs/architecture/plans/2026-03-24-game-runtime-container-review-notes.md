# GameRuntime Container — Plan Review Notes

These notes supplement the implementation plan at `2026-03-24-game-runtime-container.md`.
They document issues found during review and their resolutions. The implementing agent
MUST read these before executing.

## Critical Resolutions

### 1. LevelManager Constructor — No Circular Dependency

The plan says `LevelManager(GameRuntime runtime)` — this creates a chicken-and-egg problem
since `GameRuntime` holds `LevelManager` but `LevelManager` needs the runtime to exist.

**Resolution:** `LevelManager` takes individual manager peers as constructor params, NOT a
`GameRuntime` reference:

```java
public LevelManager(Camera camera, SpriteManager spriteManager,
                    ParallaxManager parallaxManager, CollisionSystem collisionSystem) {
    this.camera = camera;
    this.spriteManager = spriteManager;
    this.parallaxManager = parallaxManager;
    // graphicsManager, configService, overlayManager, profiler stay as engine globals
}
```

`GameRuntime` constructs the independent managers first (Camera, SpriteManager, etc.),
then constructs LevelManager passing them. No circular dependency.

### 2. DefaultObjectServices — All 11 Runtime-Backed Methods

The plan shows only 2 example methods. ALL of these must be rewritten to use the runtime:

```java
public DefaultObjectServices(GameRuntime runtime) {
    this.runtime = runtime;
}

// Level state (was: lm().getXxx())
public ObjectManager objectManager()       { return runtime.getLevelManager().getObjectManager(); }
public ObjectRenderManager renderManager() { return runtime.getLevelManager().getObjectRenderManager(); }
public LevelState levelGamestate()         { return runtime.getLevelManager().getLevelGamestate(); }
public RespawnState checkpointState()      { return runtime.getLevelManager().getCheckpointState(); }
public Level currentLevel()                { return runtime.getLevelManager().getCurrentLevel(); }
public int romZoneId()                     { return runtime.getLevelManager().getRomZoneId(); }
public int currentAct()                    { return runtime.getLevelManager().getCurrentAct(); }
public int featureZoneId()                 { return runtime.getLevelManager().getFeatureZoneId(); }
public int featureActId()                  { return runtime.getLevelManager().getFeatureActId(); }
public ZoneFeatureProvider zoneFeatureProvider() { return runtime.getLevelManager().getZoneFeatureProvider(); }
public RingManager ringManager()           { return runtime.getLevelManager().getRingManager(); }
public boolean areAllRingsCollected()      { return runtime.getLevelManager().areAllRingsCollected(); }

// Direct from runtime (was: Foo.getInstance())
public Camera camera()                     { return runtime.getCamera(); }
public GameStateManager gameState()        { return runtime.getGameState(); }
public SpriteManager spriteManager()       { return runtime.getSpriteManager(); }
public GraphicsManager graphicsManager()   { return GraphicsManager.getInstance(); }  // engine global
public FadeManager fadeManager()           { return runtime.getFadeManager(); }
public WaterSystem waterSystem()           { return runtime.getWaterSystem(); }
public ParallaxManager parallaxManager()   { return runtime.getParallaxManager(); }
public AudioManager audioManager()         { return AudioManager.getInstance(); }  // engine global

// Audio convenience (was: AudioManager.getInstance().playSfx())
public void playSfx(int soundId)           { AudioManager.getInstance().playSfx(soundId); }
public void playSfx(GameSound sound)       { AudioManager.getInstance().playSfx(sound); }
public void playMusic(int musicId)         { AudioManager.getInstance().playMusic(musicId); }
public void fadeOutMusic()                 { AudioManager.getInstance().fadeOutMusic(); }

// ROM (engine global)
public Rom rom() throws IOException        { return RomManager.getInstance().getRom(); }
public RomByteReader romReader() throws IOException { return RomByteReader.fromRom(rom()); }

// Level actions (was: GameServices.level().xxx())
public void advanceToNextLevel()           { runtime.getLevelManager().advanceToNextLevel(); }
public void requestCreditsTransition()     { runtime.getLevelManager().requestCreditsTransition(); }
public void requestSpecialStageEntry()     { runtime.getLevelManager().requestSpecialStageEntry(); }
public void invalidateForegroundTilemap()  { runtime.getLevelManager().invalidateForegroundTilemap(); }
public void updatePalette(int i, byte[] d) { runtime.getLevelManager().updatePalette(i, d); }

// Sidekicks
public List<PlayableEntity> sidekicks()    { return List.copyOf(runtime.getSpriteManager().getSidekicks()); }

// LostRings
public void spawnLostRings(PlayableEntity p, int fc) { ... same as today but via runtime ... }
```

### 3. AbstractLevelInitProfile Teardown Strategy

Use `GameServices` for all teardown calls (it routes through RuntimeManager after Task 2):

```java
// Replace ALL getInstance() calls in levelTeardownSteps() with:
GameServices.camera().resetState();
GameServices.level().resetState();
GameServices.gameState().resetState();
GameServices.fade().resetState();
GameServices.timers().resetState();
// For managers not yet in GameServices, add them:
RuntimeManager.getCurrent().getParallaxManager().resetState();
RuntimeManager.getCurrent().getSpriteManager().resetState();
RuntimeManager.getCurrent().getCollisionSystem().resetState();
RuntimeManager.getCurrent().getWaterSystem().reset();
```

**Also update `perTestResetSteps()`** — same pattern, same issue.

### 4. FadeManager / GraphicsManager Wiring

Do NOT remove `fadeManager` field from GraphicsManager entirely. Instead:
- `GraphicsManager.init()` still calls `setFadeShader()` on the FadeManager
- But it gets FadeManager from `RuntimeManager.getCurrent().getFadeManager()` instead of `FadeManager.getInstance()`
- `UiRenderPipeline.setFadeManager()` still receives it during init
- On runtime swap (future editor): `GraphicsManager` must re-wire `setFadeShader()` on the new FadeManager. Add a `GraphicsManager.onRuntimeChanged(GameRuntime)` method for this.

### 5. Master Title Screen Path

`Engine` creates the window and GameLoop BEFORE `initializeGame()` when master title
screen is enabled. GameLoop must handle `runtime == null` gracefully during this phase:

```java
// In GameLoop methods that access runtime:
if (runtime == null) return; // master title screen mode, no gameplay
```

Or: create a minimal "empty" runtime during `Engine()` construction that gets replaced
when `initializeGame()` runs.

## Untracked Production Files

These files call `getInstance()` on removed singletons and are NOT in the plan's
"Major Rewrites" or "explicit ripple" lists. They must be fixed in Task 5:

### Sprites/Physics (high-impact, hot path):
- `PlayableSpriteMovement.java` — 13 calls (Camera×7, LevelManager×4, CollisionSystem×1 field)
- `AbstractPlayableSprite.java` — 5 calls (LevelManager×3, Camera×2)
- `DrowningController.java` — 2 calls (LevelManager×2)
- `SidekickCpuController.java` — Camera calls
- `KnucklesRespawnStrategy.java` — Camera calls
- `SonicRespawnStrategy.java` — Camera calls
- `SuperStateController.java` — GameStateManager calls
- `SpindashCameraTimer.java` — Camera calls

### Level internals:
- `ObjectManager.java` — 3 additional calls (Camera×2, LevelManager×1) beyond the field initializer
- `SpriteManager.java` — lazy `LevelManager` field capture
- `GroundSensor.java` — fallback `LevelManager.getInstance()` (change to `GameServices.level()`)
- `ObjectTerrainUtils.java` — 3 calls (change to `GameServices.level()`)

### Game module code:
- `GameModuleRegistry.java` — `GameStateManager.getInstance()`
- Various scroll handlers, event managers, zone feature providers

### Strategy for all untracked files:
Replace `Foo.getInstance()` with `GameServices.foo()` (which routes through RuntimeManager).
This is the safest mechanical replacement — no constructor changes needed.

## Test Infrastructure

`TestEnvironment.resetAll()` calls `GameContext.forTesting()` and is used transitively by
`SingletonResetExtension` and `RequiresRomRule`. Update `TestEnvironment.resetAll()` itself —
the 11+ transitive callers will then work automatically.

## File Count

The plan estimates 120-150 files. Based on the review, the actual count is closer to
**150-180 files** when counting all test files that use `GameContext` or call `getInstance()`
on removed singletons. Tasks 1-4 are well-scoped. Task 5 is the variable — the "dispatch
3 agents" approach is correct but needs a concrete file list generated by grepping for
the 10 removed singletons after Tasks 1-4 are complete.
