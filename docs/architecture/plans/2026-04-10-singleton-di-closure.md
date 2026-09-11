# Singleton DI Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the singleton migration on `next` by removing production `getInstance()` dependencies and enforcing explicit dependency injection through the engine composition root, `GameRuntime`, module-owned services, and `ObjectServices`.

**Architecture:** Add an explicit `EngineServices` root for process-level services, keep runtime-owned gameplay state inside `GameRuntime`, pass dependencies through constructors, and tighten guard tests before each migration bucket. The only temporary compatibility bridge allowed during migration is `EngineServices.fromLegacySingletonsForBootstrap()`; the end state is no production singleton access outside that bridge, and preferably none at all.

**Tech Stack:** Java 21, Maven, JUnit 5 / Jupiter only, existing OpenGGF service/provider architecture.

---

## Current Audit

Worktree: `C:\Users\farre\IdeaProjects\sonic-engine\.worktrees\next-singleton-di`

Branch: `next`

Baseline command:

```powershell
mvn -q -Dmse=off test
```

Clean-`next` baseline result: failed before this plan changed anything. Maven reported 2345 tests run, 4 failures, 3 errors, 253 skipped. Visible failures include:

```text
TestSpecialStageModuleConfig.sonic1ModuleConfiguresSixStagesAndEmeralds expected:<6> but was:<7>
TestSpecialStageModuleConfig.switchingBackToSonic2RestoresSevenStageConfig
TestSpecialStageModuleConfig.resetRestoresSonic2StageConfigThroughCompatibilityPath
TestMegaChopperBadnikInstance.captureKeepsPlayerMobileAndDrainsOneRingAfterSixtyFrames expected:<2> but was:<0>
TestS3kShieldPriorityParity.* services not available
```

The log also showed missing local ROM access under the fresh worktree path:

```text
C:\Users\farre\IdeaProjects\sonic-engine\.worktrees\next-singleton-di\Sonic The Hedgehog 2 (W) (REV01) [!].gen
```

Use this focused verification set for the DI migration until the unrelated full-suite baseline failures are handled:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestRuntimeSingletonGuard,com.openggf.game.TestGameModuleRegistryUsageGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.game.TestGameServicesNullableAccessors,com.openggf.game.TestGameRuntime,com.openggf.game.session.TestSessionManager,com.openggf.editor.TestEditorToggleIntegration" test
```

Production audit count:

```text
455 production .getInstance() occurrences in src/main/java
```

Largest buckets:

```text
135 GraphicsManager
 64 SonicConfigurationService
 46 AudioManager
 25 CrossGameFeatureProvider
 17 RomManager
 16 Engine
 14 LevelManager
 11 Sonic3kLevelEventManager
 11 Camera
  7 GameStateManager
```

Runtime-owned managers to remove from production singleton access:

```text
Camera, LevelManager, SpriteManager, GameStateManager, TimerManager,
FadeManager, CollisionSystem, TerrainCollisionManager, WaterSystem,
ParallaxManager
```

Process-level services to inject through the root:

```text
AudioManager, GraphicsManager, RomManager, SonicConfigurationService,
PerformanceProfiler, DebugOverlayManager, DebugRenderer,
PlaybackDebugManager, RomDetectionService, CrossGameFeatureProvider, Engine
```

Game/module services to make module-owned:

```text
Sonic1SwitchManager, Sonic1ConveyorState, Sonic1LevelEventManager,
Sonic1TitleScreenManager, Sonic1TitleCardManager, Sonic1LevelSelectManager,
Sonic1ZoneRegistry, Sonic2LevelEventManager, Sonic2SpecialStageManager,
Sonic2SpecialStageSpriteDebug, TitleScreenManager, TitleCardManager,
LevelSelectManager, Sonic2ZoneRegistry, Sonic3kLevelEventManager,
Sonic3kTitleCardManager, Sonic3kTitleScreenManager,
Sonic3kLevelSelectManager, Sonic3kSpecialStageManager, Sonic3kZoneRegistry
```

## Task 1: Add the DI Root

**Files:**
- Create: `src/main/java/com/openggf/game/EngineServices.java`
- Create: `src/test/java/com/openggf/game/TestEngineServices.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`
- Modify: `src/main/java/com/openggf/game/GameRuntime.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/TestEngineServices.java`:

```java
package com.openggf.game;

import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class TestEngineServices {
    @Test
    void defaultRootCollectsExistingProcessServicesAtCompositionBoundary() {
        EngineServices services = EngineServices.fromLegacySingletonsForBootstrap();

        assertSame(SonicConfigurationService.getInstance(), services.configuration());
        assertSame(GraphicsManager.getInstance(), services.graphics());
        assertSame(AudioManager.getInstance(), services.audio());
        assertSame(RomManager.getInstance(), services.roms());
        assertSame(PerformanceProfiler.getInstance(), services.profiler());
        assertSame(DebugOverlayManager.getInstance(), services.debugOverlay());
        assertSame(PlaybackDebugManager.getInstance(), services.playbackDebug());
    }
}
```

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestEngineServices" test
```

Expected: compilation fails because `EngineServices` does not exist.

- [ ] **Step 2: Add `EngineServices`**

Create `src/main/java/com/openggf/game/EngineServices.java`:

```java
package com.openggf.game;

import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.graphics.GraphicsManager;
import java.util.Objects;

public final class EngineServices {
    private final SonicConfigurationService configuration;
    private final GraphicsManager graphics;
    private final AudioManager audio;
    private final RomManager roms;
    private final PerformanceProfiler profiler;
    private final DebugOverlayManager debugOverlay;
    private final PlaybackDebugManager playbackDebug;
    private final RomDetectionService romDetection;
    private final CrossGameFeatureProvider crossGameFeatures;

    public EngineServices(SonicConfigurationService configuration, GraphicsManager graphics,
                          AudioManager audio, RomManager roms, PerformanceProfiler profiler,
                          DebugOverlayManager debugOverlay, PlaybackDebugManager playbackDebug,
                          RomDetectionService romDetection, CrossGameFeatureProvider crossGameFeatures) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.graphics = Objects.requireNonNull(graphics, "graphics");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.roms = Objects.requireNonNull(roms, "roms");
        this.profiler = Objects.requireNonNull(profiler, "profiler");
        this.debugOverlay = Objects.requireNonNull(debugOverlay, "debugOverlay");
        this.playbackDebug = Objects.requireNonNull(playbackDebug, "playbackDebug");
        this.romDetection = Objects.requireNonNull(romDetection, "romDetection");
        this.crossGameFeatures = Objects.requireNonNull(crossGameFeatures, "crossGameFeatures");
    }

    public static EngineServices fromLegacySingletonsForBootstrap() {
        return new EngineServices(SonicConfigurationService.getInstance(), GraphicsManager.getInstance(),
                AudioManager.getInstance(), RomManager.getInstance(), PerformanceProfiler.getInstance(),
                DebugOverlayManager.getInstance(), PlaybackDebugManager.getInstance(),
                RomDetectionService.getInstance(), CrossGameFeatureProvider.getInstance());
    }

    public SonicConfigurationService configuration() { return configuration; }
    public GraphicsManager graphics() { return graphics; }
    public AudioManager audio() { return audio; }
    public RomManager roms() { return roms; }
    public PerformanceProfiler profiler() { return profiler; }
    public DebugOverlayManager debugOverlay() { return debugOverlay; }
    public PlaybackDebugManager playbackDebug() { return playbackDebug; }
    public RomDetectionService romDetection() { return romDetection; }
    public CrossGameFeatureProvider crossGameFeatures() { return crossGameFeatures; }
}
```

- [ ] **Step 3: Wire the root through composition**

In `GameRuntime`, add:

```java
private final EngineServices engineServices;
public EngineServices getEngineServices() { return engineServices; }
```

Add `EngineServices engineServices` to the constructor and assign it with `Objects.requireNonNull(engineServices, "engineServices")`.

In `RuntimeManager`, add:

```java
private static EngineServices engineServices = EngineServices.fromLegacySingletonsForBootstrap();

public static synchronized void configureEngineServices(EngineServices services) {
    engineServices = java.util.Objects.requireNonNull(services, "services");
}
```

Pass `engineServices` into the `GameRuntime` constructor.

In `Engine` and `GameLoop`, move field initializers that call `getInstance()` into constructors and assign them from `EngineServices`:

```java
this.configService = engineServices.configuration();
this.graphicsManager = engineServices.graphics();
this.profiler = engineServices.profiler();
this.playbackDebugManager = engineServices.playbackDebug();
```

- [ ] **Step 4: Verify and commit**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestEngineServices,com.openggf.game.session.TestSessionManager,com.openggf.TestGameLoop" test
git add src/main/java/com/openggf/game/EngineServices.java src/test/java/com/openggf/game/TestEngineServices.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/game/RuntimeManager.java src/main/java/com/openggf/game/GameRuntime.java
git commit -m "feat: add explicit engine service root"
```

## Task 2: Remove Runtime-Owned Singleton Fallbacks

**Files:**
- Modify: `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/main/java/com/openggf/physics/GroundSensor.java`
- Modify: `src/main/java/com/openggf/util/LazyMappingHolder.java`

- [ ] **Step 1: Tighten the guard first**

In `TestRuntimeSingletonGuard`, replace `ALLOWED_FILES` with:

```java
private static final Set<String> ALLOWED_FILES = Set.of(
        "RuntimeManager.java",
        "GameServices.java"
);
```

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestRuntimeSingletonGuard" test
```

Expected: failures list remaining runtime-owned singleton usage.

- [ ] **Step 2: Replace runtime fallbacks**

Use strict runtime or service access:

```java
return RuntimeManager.getCurrent().getCamera();
return RuntimeManager.getCurrent().getGameState();
this(sprite, GameServices.collision(), GameServices.gameState());
return GameServices.camera();
return GameServices.level();
return GameServices.timers();
return GameServices.gameState();
return GameServices.collision();
return GameServices.audio();
return GameServices.water();
```

In `LevelManager`, replace the no-arg constructor with:

```java
@Deprecated(forRemoval = true)
protected LevelManager() {
    throw new IllegalStateException("LevelManager requires explicit runtime dependencies");
}
```

In `GroundSensor`:

```java
if (cachedLevelManager == null) cachedLevelManager = GameServices.level();
```

In `LazyMappingHolder`:

```java
LevelManager manager = GameServices.level();
```

- [ ] **Step 3: Verify and commit**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestRuntimeSingletonGuard,com.openggf.game.TestGameServicesNullableAccessors,com.openggf.game.TestGameRuntime,com.openggf.game.session.TestSessionManager,com.openggf.tests.physics.CollisionSystemTest,com.openggf.sprites.managers.TestPlayableSpriteMovement" test
git add src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java src/main/java/com/openggf/level/LevelManager.java src/main/java/com/openggf/sprites/managers/SpriteManager.java src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java src/main/java/com/openggf/physics/GroundSensor.java src/main/java/com/openggf/util/LazyMappingHolder.java
git commit -m "refactor: remove runtime singleton fallbacks"
```

## Task 3: Finish ObjectServices and Remove Object Exceptions

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/BootstrapObjectServices.java`
- Modify: object files under `src/main/java/com/openggf/game/sonic1/objects`
- Modify: object files under `src/main/java/com/openggf/game/sonic2/objects`
- Modify: object files under `src/main/java/com/openggf/game/sonic3k/objects`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`

- [ ] **Step 1: Tighten the object guard**

In `TestObjectServicesMigrationGuard`, replace `PERMANENT_EXCEPTIONS` with only registries:

```java
private static final Set<String> PERMANENT_EXCEPTIONS = Set.of(
        "com.openggf.game.sonic1.objects.Sonic1ObjectRegistry",
        "com.openggf.game.sonic2.objects.Sonic2ObjectRegistry",
        "com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry"
);
```

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.level.objects.TestObjectServicesMigrationGuard" test
```

Expected: failures identify the AIZ intro helpers and special-stage results object as remaining bypasses.

- [ ] **Step 2: Expand `ObjectServices` with process services**

Add:

```java
SonicConfigurationService configuration();
DebugOverlayManager debugOverlay();
RomManager romManager();
CrossGameFeatureProvider crossGameFeatures();
EngineServices engineServices();
```

Back them in `DefaultObjectServices` from `runtime.getEngineServices()` or `EngineServices.fromLegacySingletonsForBootstrap()` in the legacy constructor.

- [ ] **Step 3: Replace object lookups**

Use these exact replacement shapes in object code:

```java
services().configuration().getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
GraphicsManager graphicsManager = services().graphicsManager();
Rom rom = services().rom();
Sonic2SpecialStageManager manager = services().gameService(Sonic2SpecialStageManager.class);
```

- [ ] **Step 4: Verify and commit**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.level.objects.TestObjectServicesExpansion,com.openggf.game.sonic2.objects.TestTornadoObjectInstance,com.openggf.tests.TestSonic1RingInstance,com.openggf.tests.TestS3kHczSnakeBlocksObject" test
git add src/main/java/com/openggf/level/objects src/main/java/com/openggf/game/sonic1/objects src/main/java/com/openggf/game/sonic2/objects src/main/java/com/openggf/game/sonic3k/objects src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java
git commit -m "refactor: finish object service injection"
```

## Task 4: Make Game-Specific Managers Module-Owned

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: game-specific callers from the audit
- Create or modify: `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`

- [ ] **Step 1: Add a guard for game-specific singleton access**

Create a source scanner that fails on:

```java
private static final List<String> FORBIDDEN_SINGLETONS = List.of(
        "Sonic1SwitchManager.getInstance(",
        "Sonic1ConveyorState.getInstance(",
        "Sonic1LevelEventManager.getInstance(",
        "Sonic2LevelEventManager.getInstance(",
        "Sonic2SpecialStageManager.getInstance(",
        "Sonic2SpecialStageSpriteDebug.getInstance(",
        "Sonic3kLevelEventManager.getInstance(",
        "Sonic3kTitleCardManager.getInstance(",
        "Sonic3kSpecialStageManager.getInstance(",
        "Sonic3kZoneRegistry.getInstance(",
        "Sonic2ZoneRegistry.getInstance(",
        "Sonic1ZoneRegistry.getInstance("
);
```

- [ ] **Step 2: Convert modules to own fields**

Use this pattern in each module:

```java
private final Sonic3kLevelEventManager levelEventManager = new Sonic3kLevelEventManager();
private final Sonic3kTitleCardManager titleCardManager = new Sonic3kTitleCardManager();
private final Sonic3kZoneRegistry zoneRegistry = new Sonic3kZoneRegistry();

@Override
public LevelEventProvider getLevelEventProvider() {
    return levelEventManager;
}

@Override
public ZoneRegistry getZoneRegistry() {
    return zoneRegistry;
}

@Override
public <T> T getGameService(Class<T> type) {
    if (type == Sonic3kLevelEventManager.class) return type.cast(levelEventManager);
    if (type == Sonic3kTitleCardManager.class) return type.cast(titleCardManager);
    return null;
}
```

Replace non-object calls with:

```java
Sonic3kLevelEventManager lem =
        (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
```

Replace object calls with:

```java
Sonic3kLevelEventManager lem = services().gameService(Sonic3kLevelEventManager.class);
```

- [ ] **Step 3: Remove game-specific singleton factories**

Delete `private static Type instance` and `public static synchronized Type getInstance()` from converted game-specific manager classes, keeping public constructors.

- [ ] **Step 4: Verify and commit**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.game.TestGameModuleRegistryUsageGuard,com.openggf.game.sonic3k.TestS3kSlotsPaletteCycling,com.openggf.tests.TestSpecialStageModuleConfig" test
git add src/main/java/com/openggf/game/sonic1 src/main/java/com/openggf/game/sonic2 src/main/java/com/openggf/game/sonic3k src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java
git commit -m "refactor: make game services module-owned"
```

`TestSpecialStageModuleConfig` is part of the clean-`next` failure set, so treat only changed failure modes as DI regressions.

## Task 5: Inject Render, Audio, Config, ROM, and Cross-Game Services

**Files:**
- Modify: `src/main/java/com/openggf/graphics/BatchedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/InstancedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/PatternRenderCommand.java`
- Modify: `src/main/java/com/openggf/graphics/GLCommand.java`
- Modify: `src/main/java/com/openggf/level/render/PatternSpriteRenderer.java`
- Modify: `src/main/java/com/openggf/level/render/BackgroundRenderer.java`
- Modify: `src/main/java/com/openggf/sprites/render/PlayerSpriteRenderer.java`
- Modify: `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`

- [ ] **Step 1: Add broad process singleton scanning**

Extend the production guard to fail on:

```java
private static final List<String> FORBIDDEN_PROCESS_SINGLETONS = List.of(
        "GraphicsManager.getInstance(",
        "AudioManager.getInstance(",
        "RomManager.getInstance(",
        "SonicConfigurationService.getInstance(",
        "PerformanceProfiler.getInstance(",
        "DebugOverlayManager.getInstance(",
        "DebugRenderer.getInstance(",
        "PlaybackDebugManager.getInstance(",
        "CrossGameFeatureProvider.getInstance(",
        "Engine.getInstance("
);
```

Allow only `src/main/java/com/openggf/game/EngineServices.java` during the temporary bootstrap bridge.

- [ ] **Step 2: Convert renderers to constructor injection**

Use this shape for `PatternSpriteRenderer` and mirror it in other renderers:

```java
private final GraphicsManager graphicsManager;

public PatternSpriteRenderer(ObjectSpriteSheet sheet, GraphicsManager graphicsManager) {
    this.sheet = sheet;
    this.graphicsManager = java.util.Objects.requireNonNull(graphicsManager, "graphicsManager");
}

public PatternSpriteRenderer(ObjectSpriteSheet sheet) {
    this(sheet, EngineServices.fromLegacySingletonsForBootstrap().graphics());
}
```

Replace local lookups with:

```java
GraphicsManager graphicsManager = this.graphicsManager;
```

- [ ] **Step 3: Convert audio/config users**

For `LWJGLAudioBackend`, use:

```java
private final SonicConfigurationService configService;

public LWJGLAudioBackend() {
    this(EngineServices.fromLegacySingletonsForBootstrap().configuration());
}

public LWJGLAudioBackend(SonicConfigurationService configService) {
    this.configService = java.util.Objects.requireNonNull(configService, "configService");
}
```

Replace `SonicConfigurationService.getInstance()` usage with `configService`. For `SmpsSequencer`, inject `AudioManager` or `AudioBackend` and replace `AudioManager.getInstance().getBackend().restoreMusic()` with `audioManager.getBackend().restoreMusic()`.

- [ ] **Step 4: Convert `CrossGameFeatureProvider` users**

Use:

```java
CrossGameFeatureProvider crossGame = engineServices.crossGameFeatures();
String donorId = crossGame.getDonorGameId();
```

- [ ] **Step 5: Verify and commit**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard" test
git add src/main/java/com/openggf src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java
git commit -m "refactor: inject process services into render and audio paths"
```

## Task 6: Delete Singleton Accessors or Restrict the Bootstrap Bridge

**Files:**
- Modify: runtime-owned manager classes
- Modify: process-service singleton classes where production no longer calls accessors
- Modify: tests that still call `getInstance()`

- [ ] **Step 1: Search remaining production calls**

```powershell
rg -n "\.getInstance\(" src/main/java
```

Expected: no output, or only `EngineServices.fromLegacySingletonsForBootstrap()`.

- [ ] **Step 2: Delete runtime-owned singleton state**

Delete `private static Type instance` and `public static synchronized Type getInstance()` from:

```text
src/main/java/com/openggf/camera/Camera.java
src/main/java/com/openggf/level/LevelManager.java
src/main/java/com/openggf/sprites/managers/SpriteManager.java
src/main/java/com/openggf/game/GameStateManager.java
src/main/java/com/openggf/timer/TimerManager.java
src/main/java/com/openggf/graphics/FadeManager.java
src/main/java/com/openggf/physics/CollisionSystem.java
src/main/java/com/openggf/physics/TerrainCollisionManager.java
src/main/java/com/openggf/level/WaterSystem.java
src/main/java/com/openggf/level/ParallaxManager.java
```

- [ ] **Step 3: Update tests to fixtures**

Replace test runtime access:

```java
GameRuntime runtime = RuntimeManager.createGameplay();
Camera camera = runtime.getCamera();
```

Replace graphics-only test setup:

```java
EngineServices.fromLegacySingletonsForBootstrap().graphics().initHeadless();
```

- [ ] **Step 4: Verify and commit**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.game.TestGameRuntime,com.openggf.game.session.TestSessionManager" test
git add src/main/java src/test/java
git commit -m "refactor: close singleton compatibility accessors"
```

## Task 7: Final Verification and Push

- [ ] **Step 1: Run final source scan**

```powershell
rg -n "\.getInstance\(" src/main/java
```

Expected: no output, or only the documented `EngineServices` bootstrap bridge if that bridge remains for this release line.

- [ ] **Step 2: Run focused migration verification**

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.game.TestGameModuleRegistryUsageGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.game.TestGameServicesNullableAccessors,com.openggf.game.TestGameRuntime,com.openggf.game.session.TestSessionManager,com.openggf.editor.TestEditorToggleIntegration" test
```

Expected: exit code 0.

- [ ] **Step 3: Run full suite and compare to baseline**

```powershell
mvn -q -Dmse=off test
```

Expected: no new DI-related failures beyond the baseline failure set recorded in this plan.

- [ ] **Step 4: Check formatting, commit, and push**

```powershell
git diff --check
git status --short --branch
git add src/main/java src/test/java
git commit -m "test: enforce full DI singleton closure"
git push origin next
```

Expected: whitespace check exits 0, status contains only intended files before commit, and push updates `origin/next`.

## Self-Review Notes

- The plan covers the known production `getInstance()` surface by bucket: runtime-owned managers, object services, game-specific managers, process services, and final accessor deletion.
- It uses guard-first tasks so migration loopholes close before each implementation phase.
- It records the current full-suite baseline failure set so implementation workers do not confuse pre-existing failures with DI regressions.
- The only acceptable production compatibility boundary during migration is `EngineServices.fromLegacySingletonsForBootstrap()`. The final guard either eliminates all production `getInstance()` calls or documents that single bootstrap method as the remaining bridge for this release line.
