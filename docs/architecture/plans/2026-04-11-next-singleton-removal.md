# Next Singleton Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the `next` branch singleton migration by removing the remaining production `RuntimeManager.getEngineServices()` service-locator usages, then narrowing bootstrap wiring so engine services are explicit at composition roots.

**Architecture:** The runtime-owned gameplay singleton migration is already done. The remaining loophole is the process-global engine-services locator. Complete it in dependency order: first isolate composition roots and shared infrastructure, then sweep game-specific packages by bounded cluster, then remove the bootstrap fallback and tighten guards to an explicit allowlist.

**Tech Stack:** Java, Maven, JUnit 5 / Jupiter only, existing `EngineServices`, `RuntimeManager`, `GameServices`, `ObjectServices`, and module/provider architecture

---

### Task 1: Rebaseline the Remaining Surface and Guard Strategy

**Files:**
- Modify: `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`
- Verify: `docs/architecture/plans/2026-04-11-next-singleton-removal.md`

- [ ] **Step 1: Refresh the remaining production locator inventory**

Run:
```bash
rg -n "RuntimeManager\\.getEngineServices\\(" src/main/java --no-heading
```

Expected: remaining matches are concentrated in:
- `src/main/java/com/openggf/Engine.java`
- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/graphics/*`
- `src/main/java/com/openggf/level/*`
- `src/main/java/com/openggf/game/sonic1/*`
- `src/main/java/com/openggf/game/sonic2/*`
- `src/main/java/com/openggf/game/sonic3k/*`

- [ ] **Step 2: Keep the current focused package guards and add new guards only as each slice is taken**

Add new package/file guard methods in:
```java
@Test
public void sonic1SpecialStagePackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
    // same scan pattern as the existing package guards
}
```

Apply the same pattern later for:
- `com/openggf/game/sonic2/titlescreen/`
- `com/openggf/game/sonic2/titlecard/`
- `com/openggf/game/sonic2/credits/`
- `com/openggf/game/sonic3k/titlescreen/`
- `com/openggf/game/sonic3k/titlecard/`
- `com/openggf/game/sonic3k/features/`
- `com/openggf/game/sonic3k/specialstage/`

- [ ] **Step 3: Re-run the existing shared guard bundle before any new slice**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard,com.openggf.game.TestRuntimeSingletonGuard,com.openggf.game.TestEngineServices,com.openggf.game.TestGameRuntime,com.openggf.game.TestGameServicesNullableAccessors,com.openggf.game.TestCrossGameFeatureProviderRefactor,com.openggf.game.TestGameModuleRegistryUsageGuard,com.openggf.game.sonic2.TestSonic2LevelInitProfile,com.openggf.editor.TestEditorToggleIntegration,com.openggf.editor.TestEditorRenderingSmoke" test
```

Expected: PASS

- [ ] **Step 4: Commit the current already-finished slices before moving into deeper core infrastructure**

Run:
```bash
git add src/main/java/com/openggf/Engine.java
git add src/main/java/com/openggf/editor/render
git add src/main/java/com/openggf/game/AbstractLevelInitProfile.java
git add src/main/java/com/openggf/game/CrossGameFeatureProvider.java
git add src/main/java/com/openggf/game/GameModuleRegistry.java
git add src/main/java/com/openggf/game/GameServices.java
git add src/main/java/com/openggf/game/MasterTitleScreen.java
git add src/main/java/com/openggf/game/sonic1
git add src/main/java/com/openggf/game/sonic2/menu
git add src/main/java/com/openggf/game/sonic2/levelselect
git add src/main/java/com/openggf/game/sonic3k/levelselect
git add src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java
git add src/test/java/com/openggf/game/TestGameRuntime.java
git add src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java
git add docs/architecture/plans/2026-04-11-next-singleton-removal.md
git commit -m "refactor: close title and editor engine-services locator slices"
```

Expected: clean checkpoint before core changes

### Task 2: Remove Locator Usage from Composition Roots and Shared Facades

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/game/GameServices.java`
- Modify: `src/main/java/com/openggf/camera/Camera.java`
- Modify: `src/main/java/com/openggf/data/RomManager.java`
- Verify: `src/test/java/com/openggf/game/TestEngineServices.java`
- Verify: `src/test/java/com/openggf/game/TestGameRuntime.java`

- [ ] **Step 1: Write a failing guard for the composition-root files**

Add file-specific guard methods for:
```java
private static final List<String> COMPOSITION_ROOT_FILES = List.of(
        "com/openggf/Engine.java",
        "com/openggf/GameLoop.java",
        "com/openggf/game/GameServices.java",
        "com/openggf/camera/Camera.java",
        "com/openggf/data/RomManager.java"
);
```

and scan them for `ENGINE_SERVICES_LOCATOR`.

- [ ] **Step 2: Run the new composition-root guard and verify RED**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#compositionRootsDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on the five files listed above

- [ ] **Step 3: Refactor constructors and static accessors so they accept `EngineServices` or use already-available dependencies**

Implement the narrowest constructor rewiring:
```java
public Engine() {
    this(EngineServices.fromLegacySingletonsForBootstrap());
}

public GameLoop() {
    this(inputHandler, RuntimeManager.currentEngineServices());
}

public static RomManager rom() {
    return RuntimeManager.currentEngineServices().roms();
}
```

Target end state:
- `Engine` and `GameLoop` keep bootstrap-only entry points
- `GameServices` becomes the supported static facade
- `Camera` and `RomManager` stop calling `RuntimeManager.getEngineServices()` internally

- [ ] **Step 4: Run the focused composition-root guard and runtime tests**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#compositionRootsDoNotUseRuntimeManagerEngineServicesLocator,com.openggf.game.TestEngineServices,com.openggf.game.TestGameRuntime" test
```

Expected: PASS

- [ ] **Step 5: Commit**

Run:
```bash
git add src/main/java/com/openggf/Engine.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/game/GameServices.java src/main/java/com/openggf/camera/Camera.java src/main/java/com/openggf/data/RomManager.java src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java src/test/java/com/openggf/game/TestEngineServices.java src/test/java/com/openggf/game/TestGameRuntime.java
git commit -m "refactor: isolate engine-services access at composition roots"
```

### Task 3: Remove Locator Usage from Shared Rendering and Utility Infrastructure

**Files:**
- Modify: `src/main/java/com/openggf/graphics/BatchedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/InstancedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/PatternRenderCommand.java`
- Modify: `src/main/java/com/openggf/graphics/GLCommand.java`
- Modify: `src/main/java/com/openggf/level/render/PatternSpriteRenderer.java`
- Modify: `src/main/java/com/openggf/level/render/BackgroundRenderer.java`
- Modify: `src/main/java/com/openggf/sprites/render/PlayerSpriteRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Verify: `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java`

- [ ] **Step 1: Add a failing package guard for shared render infrastructure**

Guard packages:
- `com/openggf/graphics/`
- `com/openggf/level/render/`
- file: `com/openggf/sprites/render/PlayerSpriteRenderer.java`

- [ ] **Step 2: Run the focused render-infra guard and verify RED**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#graphicsInfrastructureDoesNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on the render utility constructors and helpers

- [ ] **Step 3: Replace default constructors that pull graphics/config from `RuntimeManager` with explicit delegating overloads**

Use the existing explicit constructors as the canonical path:
```java
public BatchedPatternRenderer(GraphicsManager graphicsManager, SonicConfigurationService configService) { ... }
public BatchedPatternRenderer() {
    this(GameServices.graphics(), GameServices.configuration());
}
```

For helpers such as `GLCommand` and `PatternRenderCommand`, push graphics/config lookup to the caller:
```java
public void draw(GraphicsManager gm, SonicConfigurationService config) { ... }
```

- [ ] **Step 4: Verify rendering smoke coverage after the constructor/API rewiring**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#graphicsInfrastructureDoesNotUseRuntimeManagerEngineServicesLocator,com.openggf.editor.TestEditorRenderingSmoke" test
```

Expected: PASS

- [ ] **Step 5: Commit**

Run:
```bash
git add src/main/java/com/openggf/graphics src/main/java/com/openggf/level/render src/main/java/com/openggf/sprites/render/PlayerSpriteRenderer.java src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java
git commit -m "refactor: remove engine-services locator from shared render infrastructure"
```

### Task 4: Remove Locator Usage from Level and Object Core Runtime

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/level/AbstractLevel.java`
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/main/java/com/openggf/level/objects/ExplosionObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java`
- Modify: `src/main/java/com/openggf/timer/timers/SpeedShoesTimer.java`
- Verify: `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`

- [ ] **Step 1: Add failing guards for `com/openggf/level/` and object core files**

Scan:
- `com/openggf/level/`
- `com/openggf/level/rings/`
- `com/openggf/level/objects/`
- `com/openggf/timer/timers/SpeedShoesTimer.java`

- [ ] **Step 2: Run the focused level/object guard and verify RED**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#levelAndObjectCoreDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on `LevelManager`, `AbstractObjectInstance`, `ObjectManager`, `RingManager`, and related helper classes

- [ ] **Step 3: Convert core classes to use already-owned runtime/services objects instead of re-reading engine services**

Use patterns already present in the codebase:
```java
private final GraphicsManager graphicsManager;
private final SonicConfigurationService configService;

public LevelManager(EngineServices engineServices) {
    this.graphicsManager = engineServices.graphics();
    this.configService = engineServices.configuration();
}
```

And for object code:
```java
protected final SonicConfigurationService config() {
    return services().engineServices().configuration();
}
```

Do not add new static facades for object/runtime core.

- [ ] **Step 4: Run the focused guard plus runtime guard coverage**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#levelAndObjectCoreDoNotUseRuntimeManagerEngineServicesLocator,com.openggf.game.TestRuntimeSingletonGuard" test
```

Expected: PASS

- [ ] **Step 5: Commit**

Run:
```bash
git add src/main/java/com/openggf/level src/main/java/com/openggf/timer/timers/SpeedShoesTimer.java src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java
git commit -m "refactor: remove engine-services locator from level and object core"
```

### Task 5: Finish the Remaining Sonic 1 Surface

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageResultsScreen.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1PatternAnimator.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1PaletteCycler.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1Level.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`

- [ ] **Step 1: Add a failing Sonic 1 remaining-surface guard**

Guard:
- `com/openggf/game/sonic1/specialstage/`
- files `Sonic1PatternAnimator.java`, `Sonic1PaletteCycler.java`, `Sonic1Level.java`, `Sonic1GameModule.java`

- [ ] **Step 2: Run the focused Sonic 1 guard and verify RED**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic1RemainingRuntimePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on the six files listed above

- [ ] **Step 3: Remove locator access by pushing graphics/config/cross-game dependencies through the manager/module seams already present**

Use patterns like:
```java
this.graphicsManager = GameServices.graphics();
this.crossGameFeatures = GameServices.crossGameFeatures();
```

or explicit method parameters for results-screen drawing:
```java
resultsScreen.draw(GameServices.graphics());
```

- [ ] **Step 4: Run the focused Sonic 1 guard**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic1RemainingRuntimePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: PASS

- [ ] **Step 5: Commit**

Run:
```bash
git add src/main/java/com/openggf/game/sonic1 src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java
git commit -m "refactor: close remaining sonic1 engine-services locator usage"
```

### Task 6: Finish the Remaining Sonic 2 Surface

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/titlescreen/TitleScreenManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/titlescreen/TitleScreenDataLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic2/titlecard/TitleCardManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2LogoFlashManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingArt.java`
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2CreditsTextRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2SuperStateController.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2PatternAnimator.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2PaletteCycler.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2Level.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/DynamicHtz.java`
- Modify: `src/main/java/com/openggf/game/sonic2/OilSurfaceManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/debug/Sonic2SpecialStageSpriteDebug.java`

- [ ] **Step 1: Split Sonic 2 into four guardable clusters**

Clusters:
- titlescreen/titlecard
- credits
- specialstage/debug
- core level/zone/module/palette/pattern

- [ ] **Step 2: Take the titlescreen/titlecard cluster first**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic2TitlePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on `TitleScreenManager`, `TitleScreenDataLoader`, `TitleCardManager`

- [ ] **Step 3: Then take the credits cluster**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic2CreditsPackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on the four credits classes

- [ ] **Step 4: Then take the special-stage/debug cluster**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic2SpecialStagePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on `Sonic2SpecialStageManager` and `Sonic2SpecialStageSpriteDebug`

- [ ] **Step 5: Finish the Sonic 2 core cluster and commit**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic2CorePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
git add src/main/java/com/openggf/game/sonic2 src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java
git commit -m "refactor: close remaining sonic2 engine-services locator usage"
```

Expected: focused guard passes before commit

### Task 7: Finish the Remaining Sonic 3K Surface

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/titlescreen/Sonic3kTitleScreenDataLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/S3kSpecialStageResultsScreen.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/features/AizTransitionRenderFeature.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/features/AizFireCurtainRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/features/AizBattleshipRenderFeature.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kZoneEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kHCZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3k.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevel.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kSuperStateController.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kBootstrapResolver.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotMachinePanelAnimator.java`

- [ ] **Step 1: Split Sonic 3K into five bounded clusters**

Clusters:
- titlescreen/titlecard
- specialstage/results
- AIZ render features
- events/zone feature/bootstrap
- core level/plc/pattern/palette/module/super-state/bonus-stage

- [ ] **Step 2: Take titlescreen/titlecard first**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic3kTitlePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on `Sonic3kTitleScreenManager`, `Sonic3kTitleScreenDataLoader`, `Sonic3kTitleCardManager`

- [ ] **Step 3: Take special-stage and feature clusters second**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic3kSpecialStagePackagesDoNotUseRuntimeManagerEngineServicesLocator,com.openggf.game.TestProductionSingletonClosureGuard#sonic3kFeaturePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on the special-stage and AIZ feature files

- [ ] **Step 4: Finish the core Sonic 3K cluster last**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard#sonic3kCorePackagesDoNotUseRuntimeManagerEngineServicesLocator" test
```

Expected: FAIL on `Sonic3k.java`, `Sonic3kLevel.java`, `Sonic3kPlcLoader`, `Sonic3kObjectArtProvider`, `Sonic3kGameModule`, and related files

- [ ] **Step 5: Commit**

Run:
```bash
git add src/main/java/com/openggf/game/sonic3k src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java
git commit -m "refactor: close remaining sonic3k engine-services locator usage"
```

### Task 8: Remove Bootstrap Fallback and Replace It with Explicit Engine-Service Wiring

**Files:**
- Modify: `src/main/java/com/openggf/game/EngineServices.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/test/java/com/openggf/game/TestEngineServices.java`
- Modify: `src/test/java/com/openggf/game/TestGameRuntime.java`
- Modify: `src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java`

- [ ] **Step 1: Add a failing test that demonstrates bootstrap still relies on the legacy fallback**

Add a test like:
```java
@Test
public void runtimeManagerRequiresExplicitEngineServicesOnceBootstrapBridgeIsRemoved() {
    RuntimeManager.resetForTest();
    assertThrows(IllegalStateException.class, RuntimeManager::getEngineServices);
}
```

- [ ] **Step 2: Run the new bootstrap test and verify RED**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestEngineServices#runtimeManagerRequiresExplicitEngineServicesOnceBootstrapBridgeIsRemoved" test
```

Expected: FAIL because bootstrap still materializes legacy singleton services

- [ ] **Step 3: Narrow or delete `EngineServices.fromLegacySingletonsForBootstrap()` and force explicit wiring**

Target end state:
```java
public static void installEngineServices(EngineServices engineServices) {
    RuntimeManager.setEngineServices(engineServices);
}
```

Only `Engine` should assemble the bootstrap `EngineServices`.

- [ ] **Step 4: Run runtime/engine-service tests**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestEngineServices,com.openggf.game.TestGameRuntime,com.openggf.game.TestRuntimeSingletonGuard" test
```

Expected: PASS

- [ ] **Step 5: Commit**

Run:
```bash
git add src/main/java/com/openggf/game/EngineServices.java src/main/java/com/openggf/game/RuntimeManager.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/GameLoop.java src/test/java/com/openggf/game/TestEngineServices.java src/test/java/com/openggf/game/TestGameRuntime.java src/test/java/com/openggf/game/TestRuntimeSingletonGuard.java
git commit -m "refactor: remove legacy engine-services bootstrap fallback"
```

### Task 9: Tighten the Global Guard and Run Final Verification

**Files:**
- Modify: `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`
- Verify: all modified files from Tasks 1-8

- [ ] **Step 1: Replace package-by-package temporary guard expansion with a final allowlist**

Allow only:
```java
private static final List<String> LEGACY_ENGINE_SERVICES_ALLOWLIST = List.of(
        "com/openggf/game/RuntimeManager.java"
);
```

If `RuntimeManager` no longer needs the locator string at all, make the allowlist empty.

- [ ] **Step 2: Run the focused production guard**

Run:
```bash
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionSingletonClosureGuard" test
```

Expected: PASS

- [ ] **Step 3: Run the full suite**

Run:
```bash
mvn -q -Dmse=off test
```

Expected: PASS

- [ ] **Step 4: Review branch state and final diff**

Run:
```bash
git status --short --branch
git diff --stat origin/next...HEAD
```

Expected: only intended DI / guard / test changes

- [ ] **Step 5: Final commit**

Run:
```bash
git add docs/architecture/plans/2026-04-11-next-singleton-removal.md src/main/java src/test/java
git commit -m "refactor: close remaining engine-services locator loophole"
```
