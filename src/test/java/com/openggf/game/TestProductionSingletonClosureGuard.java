package com.openggf.game;

import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TestProductionSingletonClosureGuard {

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

    private static final List<String> FORBIDDEN_PROCESS_SINGLETONS = List.of(
            "GraphicsManager.getInstance(",
            "AudioManager.getInstance(",
            "RomManager.getInstance(",
            "SonicConfigurationService.getInstance(",
            "PerformanceProfiler.getInstance(",
            "DebugOverlayManager.getInstance(",
            "DebugObjectArtViewer.getInstance(",
            "DebugRenderer.getInstance(",
            "DebugRenderer.current(",
            "MemoryStats.getInstance(",
            "PlaybackDebugManager.getInstance(",
            "RenderOrderRecorder.getInstance(",
            "RomDetectionService.getInstance(",
            "Sonic1TitleCardManager.getInstance(",
            "Sonic1TitleScreenManager.getInstance(",
            "Sonic1LevelSelectManager.getInstance(",
            "TitleCardManager.getInstance(",
            "TitleScreenManager.getInstance(",
            "LevelSelectManager.getInstance(",
            "Sonic3kTitleScreenManager.getInstance(",
            "Sonic3kLevelSelectManager.getInstance(",
            "CrossGameFeatureProvider.getInstance(",
            "Engine.getInstance(",
            "Engine.current("
    );
    private static final Set<String> NON_SINGLETON_GET_INSTANCE_OWNERS = Set.of(
            "java.security.MessageDigest"
    );
    private static final Pattern RAW_GET_INSTANCE_PATTERN =
            Pattern.compile("\\.\\s*getInstance\\s*\\(");

    private static final String ENGINE_SERVICES_BOOTSTRAP_EXCEPTION =
            "com/openggf/game/session/EngineContext.java";
    private static final String LEGACY_BOOTSTRAP_BRIDGE = "EngineContext.fromLegacySingletonsForBootstrap(";
    private static final String ENGINE_SERVICES_LOCATOR = "RuntimeManager.getEngineServices(";
    private static final String ENGINE_SERVICES_LOCATOR_ALIAS = "RuntimeManager.currentEngineServices(";
    private static final String RUNTIME_CURRENT_LOCATOR = "RuntimeManager.getCurrent(";
    private static final String RUNTIME_ACTIVE_LOCATOR = "RuntimeManager.getActiveRuntime(";
    private static final String RUNTIME_CREATE_GAMEPLAY = "RuntimeManager.createGameplay(";
    private static final String GAME_SERVICES_RUNTIME_OR_NULL = "GameServices.runtimeOrNull(";
    private static final List<String> RUNTIME_CURRENT_LOCATOR_ALLOWLIST = List.of();
    private static final List<String> RUNTIME_ACTIVE_LOCATOR_ALLOWLIST = List.of();
    private static final List<String> RUNTIME_CREATE_GAMEPLAY_ALLOWLIST = List.of();
    private static final List<String> GAME_SERVICES_RUNTIME_OR_NULL_ALLOWLIST = List.of();
    private static final String GAME_RUNTIME_REFERENCE = "GameRuntime";
    private static final String NEW_GAME_RUNTIME = "new GameRuntime(";
    private static final List<String> GAME_RUNTIME_COMPATIBILITY_SURFACE = List.of();
    private static final String EDITOR_RENDERER_PACKAGE = "com/openggf/editor/render/";
    private static final String ABSTRACT_LEVEL_INIT_PROFILE =
            "com/openggf/game/AbstractLevelInitProfile.java";
    private static final List<String> BOOTSTRAP_ADJACENT_FILES = List.of(
            "com/openggf/game/CrossGameFeatureProvider.java",
            "com/openggf/game/GameModuleRegistry.java"
    );
    private static final String MASTER_TITLE_SCREEN =
            "com/openggf/game/MasterTitleScreen.java";
    private static final String SONIC2_MENU_PACKAGE =
            "com/openggf/game/sonic2/menu/";
    private static final String SONIC2_TITLESCREEN_PACKAGE =
            "com/openggf/game/sonic2/titlescreen/";
    private static final String SONIC2_TITLECARD_PACKAGE =
            "com/openggf/game/sonic2/titlecard/";
    private static final String SONIC2_LEVELSELECT_PACKAGE =
            "com/openggf/game/sonic2/levelselect/";
    private static final String SONIC2_CREDITS_PACKAGE =
            "com/openggf/game/sonic2/credits/";
    private static final String SONIC2_SPECIAL_STAGE_PACKAGE =
            "com/openggf/game/sonic2/specialstage/";
    private static final String SONIC2_SPECIAL_STAGE_DEBUG_FILE =
            "com/openggf/game/sonic2/debug/Sonic2SpecialStageSpriteDebug.java";
    private static final List<String> SONIC2_CORE_FILES = List.of(
            "com/openggf/game/sonic2/Sonic2LevelEventManager.java",
            "com/openggf/game/sonic2/Sonic2Level.java",
            "com/openggf/game/sonic2/Sonic2GameModule.java",
            "com/openggf/game/sonic2/DynamicHtz.java",
            "com/openggf/game/sonic2/OilSurfaceManager.java",
            "com/openggf/game/sonic2/Sonic2ObjectArtProvider.java",
            "com/openggf/game/sonic2/Sonic2PatternAnimator.java",
            "com/openggf/game/sonic2/Sonic2PaletteCycler.java",
            "com/openggf/game/sonic2/Sonic2ZoneFeatureProvider.java",
            "com/openggf/game/sonic2/Sonic2SuperStateController.java"
    );
    private static final String SONIC3K_LEVELSELECT_PACKAGE =
            "com/openggf/game/sonic3k/levelselect/";
    private static final String SONIC3K_TITLESCREEN_PACKAGE =
            "com/openggf/game/sonic3k/titlescreen/";
    private static final String SONIC3K_TITLECARD_PACKAGE =
            "com/openggf/game/sonic3k/titlecard/";
    private static final String SONIC3K_SPECIAL_STAGE_PACKAGE =
            "com/openggf/game/sonic3k/specialstage/";
    private static final String SONIC3K_FEATURE_PACKAGE =
            "com/openggf/game/sonic3k/features/";
    private static final List<String> SONIC3K_CORE_FILES = List.of(
            "com/openggf/game/sonic3k/Sonic3kBootstrapResolver.java",
            "com/openggf/game/sonic3k/Sonic3k.java",
            "com/openggf/game/sonic3k/Sonic3kLevel.java",
            "com/openggf/game/sonic3k/Sonic3kGameModule.java",
            "com/openggf/game/sonic3k/events/Sonic3kZoneEvents.java",
            "com/openggf/game/sonic3k/events/Sonic3kHCZEvents.java",
            "com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java",
            "com/openggf/game/sonic3k/Sonic3kLevelEventManager.java",
            "com/openggf/game/sonic3k/bonusstage/slots/S3kSlotMachinePanelAnimator.java",
            "com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java",
            "com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java",
            "com/openggf/game/sonic3k/Sonic3kPatternAnimator.java",
            "com/openggf/game/sonic3k/Sonic3kPaletteCycler.java",
            "com/openggf/game/sonic3k/Sonic3kPlcLoader.java",
            "com/openggf/game/sonic3k/Sonic3kSuperStateController.java",
            "com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java"
    );
    private static final String SONIC1_LEVELSELECT_PACKAGE =
            "com/openggf/game/sonic1/levelselect/";
    private static final String SONIC1_TITLESCREEN_PACKAGE =
            "com/openggf/game/sonic1/titlescreen/";
    private static final String SONIC1_CREDITS_PACKAGE =
            "com/openggf/game/sonic1/credits/";
    private static final String SONIC1_TITLECARD_PACKAGE =
            "com/openggf/game/sonic1/titlecard/";
    private static final String SONIC1_SPECIAL_STAGE_PACKAGE =
            "com/openggf/game/sonic1/specialstage/";
    private static final List<String> SONIC1_REMAINING_RUNTIME_FILES = List.of(
            "com/openggf/game/sonic1/Sonic1PatternAnimator.java",
            "com/openggf/game/sonic1/Sonic1PaletteCycler.java",
            "com/openggf/game/sonic1/Sonic1Level.java",
            "com/openggf/game/sonic1/Sonic1GameModule.java"
    );
    private static final List<String> LEVEL_AND_OBJECT_CORE_FILES = List.of(
            "com/openggf/level/LevelManager.java",
            "com/openggf/level/AbstractLevel.java",
            "com/openggf/level/rings/RingManager.java",
            "com/openggf/level/objects/AbstractObjectInstance.java",
            "com/openggf/level/objects/DefaultObjectServices.java",
            "com/openggf/level/objects/ObjectManager.java",
            "com/openggf/level/objects/ExplosionObjectInstance.java",
            "com/openggf/level/objects/boss/AbstractBossInstance.java",
            "com/openggf/timer/timers/SpeedShoesTimer.java"
    );
    private static final List<String> GRAPHICS_INFRASTRUCTURE_FILES = List.of(
            "com/openggf/graphics/BatchedPatternRenderer.java",
            "com/openggf/graphics/InstancedPatternRenderer.java",
            "com/openggf/graphics/PatternRenderCommand.java",
            "com/openggf/graphics/GLCommand.java",
            "com/openggf/level/render/PatternSpriteRenderer.java",
            "com/openggf/level/render/BackgroundRenderer.java",
            "com/openggf/sprites/render/PlayerSpriteRenderer.java",
            "com/openggf/graphics/GraphicsManager.java"
    );
    private static final List<String> AUDIO_INFRASTRUCTURE_FILES = List.of(
            "com/openggf/audio/LWJGLAudioBackend.java",
            "com/openggf/audio/smps/SmpsSequencer.java"
    );
    private static final List<String> DEBUG_INFRASTRUCTURE_FILES = List.of(
            "com/openggf/debug/DebugRenderer.java",
            "com/openggf/debug/DebugObjectArtViewer.java",
            "com/openggf/debug/DebugOverlayManager.java",
            "com/openggf/debug/PerformancePanelRenderer.java",
            "com/openggf/debug/playback/PlaybackDebugManager.java"
    );
    private static final List<String> SPRITE_RUNTIME_FILES = List.of(
            "com/openggf/sprites/managers/SpriteManager.java",
            "com/openggf/sprites/playable/AbstractPlayableSprite.java",
            "com/openggf/sprites/playable/SuperStateController.java"
    );
    private static final List<String> COMPOSITION_ROOT_FILES = List.of(
            "com/openggf/Engine.java",
            "com/openggf/GameLoop.java",
            "com/openggf/game/GameServices.java",
            "com/openggf/camera/Camera.java",
            "com/openggf/data/RomManager.java"
    );
    private static final List<String> LEGACY_BOOTSTRAP_BRIDGE_ALLOWLIST = List.of(
            "com/openggf/Engine.java",
            "com/openggf/game/session/EngineContext.java",
            "com/openggf/game/session/EngineServices.java",
            // Headless composition roots for the trace-driven CLI tools:
            // configure EngineServices from the legacy singletons exactly like
            // Engine does.
            "com/openggf/tools/HeadlessGameBoot.java",
            "com/openggf/tools/TraceCaptureTool.java",
            "com/openggf/tools/TraceBenchmarkTool.java"
    );

    @Test
    public void productionCodeDoesNotUseClosedGameSpecificSingletons() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> scanFile(srcMain, path, violations));

        if (!violations.isEmpty()) {
            fail("Found closed game-specific singleton access in production code:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void productionCodeDoesNotUseForbiddenProcessSingletonsOutsideEngineServices() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !ENGINE_SERVICES_BOOTSTRAP_EXCEPTION.equals(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, FORBIDDEN_PROCESS_SINGLETONS));

        if (!violations.isEmpty()) {
            fail("Found forbidden process singleton access in production code:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void productionCodeOnlyUsesRawGetInstanceAtEngineServicesBootstrapBridge() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !ENGINE_SERVICES_BOOTSTRAP_EXCEPTION.equals(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanRawGetInstanceCalls(srcMain, path, violations));

        if (!violations.isEmpty()) {
            fail("Found raw .getInstance() usage outside EngineContext bootstrap bridge:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void productionCodeDoesNotUseLegacyBootstrapBridgeOutsideAllowlist() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !LEGACY_BOOTSTRAP_BRIDGE_ALLOWLIST.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(LEGACY_BOOTSTRAP_BRIDGE)));

        if (!violations.isEmpty()) {
            fail("Found legacy bootstrap bridge usage outside the allowlist:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void runtimeFacadeLocatorsDoNotSpreadBeyondCurrentMigrationSurface() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !RUNTIME_CURRENT_LOCATOR_ALLOWLIST.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(RUNTIME_CURRENT_LOCATOR)));

        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !RUNTIME_ACTIVE_LOCATOR_ALLOWLIST.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(RUNTIME_ACTIVE_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager runtime-facade locator usage outside the migration allowlist:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void runtimeFacadeCreationDoesNotSpreadBeyondCurrentEngineAdapter() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !RUNTIME_CREATE_GAMEPLAY_ALLOWLIST.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(RUNTIME_CREATE_GAMEPLAY)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager.createGameplay() usage after facade removal:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void gameRuntimeReferencesDoNotReturnToProductionCode() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !GAME_RUNTIME_COMPATIBILITY_SURFACE.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(GAME_RUNTIME_REFERENCE, NEW_GAME_RUNTIME)));

        if (!violations.isEmpty()) {
            fail("Found GameRuntime reference after facade removal:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void gameServicesRuntimeOrNullDoesNotSpreadBeyondCurrentMigrationSurface() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !GAME_SERVICES_RUNTIME_OR_NULL_ALLOWLIST.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(GAME_SERVICES_RUNTIME_OR_NULL)));

        if (!violations.isEmpty()) {
            fail("Found GameServices.runtimeOrNull() usage outside the migration allowlist:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void runtimeManagerEngineServicesAccessDoesNotSpreadBeyondAdapter() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !"com/openggf/game/RuntimeManager.java".equals(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(ENGINE_SERVICES_LOCATOR, ENGINE_SERVICES_LOCATOR_ALIAS)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services access outside the compatibility adapter:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void editorRendererCodeDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(EDITOR_RENDERER_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in editor renderer code:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void abstractLevelInitProfileDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> ABSTRACT_LEVEL_INIT_PROFILE.equals(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in AbstractLevelInitProfile:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void bootstrapAdjacentHelpersDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> BOOTSTRAP_ADJACENT_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in bootstrap-adjacent helpers:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void compositionRootsDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> COMPOSITION_ROOT_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in composition-root files:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void masterTitleScreenDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> MASTER_TITLE_SCREEN.equals(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in MasterTitleScreen:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic2MenuPackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC2_MENU_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 2 menu package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic2LevelSelectPackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC2_LEVELSELECT_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 2 level-select package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic2TitlePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    String rel = srcMain.relativize(path).toString().replace('\\', '/');
                    return rel.startsWith(SONIC2_TITLESCREEN_PACKAGE)
                            || rel.startsWith(SONIC2_TITLECARD_PACKAGE);
                })
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 2 title packages:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic2CreditsPackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC2_CREDITS_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 2 credits package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic2SpecialStagePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    String rel = srcMain.relativize(path).toString().replace('\\', '/');
                    return rel.startsWith(SONIC2_SPECIAL_STAGE_PACKAGE) || SONIC2_SPECIAL_STAGE_DEBUG_FILE.equals(rel);
                })
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(ENGINE_SERVICES_LOCATOR, RUNTIME_CURRENT_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 2 special-stage package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic2CorePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> SONIC2_CORE_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 2 core packages:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic3kLevelSelectPackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC3K_LEVELSELECT_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 3K level-select package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic3kTitlePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    String rel = srcMain.relativize(path).toString().replace('\\', '/');
                    return rel.startsWith(SONIC3K_TITLESCREEN_PACKAGE) || rel.startsWith(SONIC3K_TITLECARD_PACKAGE);
                })
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 3K title packages:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic3kSpecialStagePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/')
                        .startsWith(SONIC3K_SPECIAL_STAGE_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 3K special-stage package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic3kFeaturePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/')
                        .startsWith(SONIC3K_FEATURE_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 3K feature package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic3kCorePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> SONIC3K_CORE_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(ENGINE_SERVICES_LOCATOR, ENGINE_SERVICES_LOCATOR_ALIAS, RUNTIME_CURRENT_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 3K core packages:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic1LevelSelectPackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC1_LEVELSELECT_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 1 level-select package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic1TitleScreenPackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC1_TITLESCREEN_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 1 title-screen package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic1CreditsPackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC1_CREDITS_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 1 credits package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic1TitleCardPackageDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> srcMain.relativize(path).toString().replace('\\', '/').startsWith(SONIC1_TITLECARD_PACKAGE))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in Sonic 1 title-card package:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void sonic1RemainingRuntimePackagesDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    String rel = srcMain.relativize(path).toString().replace('\\', '/');
                    return rel.startsWith(SONIC1_SPECIAL_STAGE_PACKAGE) || SONIC1_REMAINING_RUNTIME_FILES.contains(rel);
                })
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in remaining Sonic 1 runtime packages:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void levelAndObjectCoreDoNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> LEVEL_AND_OBJECT_CORE_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(ENGINE_SERVICES_LOCATOR, ENGINE_SERVICES_LOCATOR_ALIAS, RUNTIME_CURRENT_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in level/object core runtime:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void graphicsInfrastructureDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> GRAPHICS_INFRASTRUCTURE_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations, List.of(ENGINE_SERVICES_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in graphics infrastructure:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void audioInfrastructureDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> AUDIO_INFRASTRUCTURE_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(ENGINE_SERVICES_LOCATOR, ENGINE_SERVICES_LOCATOR_ALIAS)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in audio infrastructure:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void debugInfrastructureDoesNotUseRuntimeManagerEngineServicesLocator() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> DEBUG_INFRASTRUCTURE_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(ENGINE_SERVICES_LOCATOR, ENGINE_SERVICES_LOCATOR_ALIAS)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager engine-services locator usage in debug infrastructure:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void spriteRuntimeInfrastructureDoesNotUseRuntimeLocators() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> SPRITE_RUNTIME_FILES.contains(
                        srcMain.relativize(path).toString().replace('\\', '/')))
                .forEach(path -> scanFile(srcMain, path, violations,
                        List.of(ENGINE_SERVICES_LOCATOR, ENGINE_SERVICES_LOCATOR_ALIAS, RUNTIME_CURRENT_LOCATOR)));

        if (!violations.isEmpty()) {
            fail("Found RuntimeManager locator usage in sprite runtime infrastructure:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    public void detectsForbiddenProcessSingletonSplitAcrossLines() {
        List<String> violations = scanSourceText("sample/Split.java", """
                package sample;

                class Split {
                    void render() {
                        GraphicsManager
                                .getInstance();
                    }
                }
                """, FORBIDDEN_PROCESS_SINGLETONS);

        assertEquals(List.of("sample/Split.java:5 - GraphicsManager.getInstance("), violations);
    }

    @Test
    public void detectsLegacyBootstrapBridgeSplitAcrossLines() {
        List<String> violations = scanSourceText("sample/Bootstrap.java", """
                package sample;

                class Bootstrap {
                    void wire() {
                        EngineContext
                                .fromLegacySingletonsForBootstrap();
                    }
                }
                """, List.of(LEGACY_BOOTSTRAP_BRIDGE));

        assertEquals(List.of("sample/Bootstrap.java:5 - " + LEGACY_BOOTSTRAP_BRIDGE), violations);
    }

    @Test
    public void ignoresForbiddenPatternsInsideComments() {
        List<String> violations = scanSourceText("sample/Comments.java", """
                package sample;

                class Comments {
                    // Engine.getInstance();
                    /* DebugRenderer.current(); */
                    String s = "GraphicsManager.getInstance(";
                }
                """, FORBIDDEN_PROCESS_SINGLETONS);

        assertTrue(violations.isEmpty());
    }

    @Test
    public void detectsAliasSingletonAccessPatterns() {
        List<String> violations = scanSourceText("sample/Alias.java", """
                package sample;

                class Alias {
                    void use() {
                        Engine.current();
                        DebugRenderer
                                .current();
                    }
                }
                """, FORBIDDEN_PROCESS_SINGLETONS);

        assertEquals(2, violations.size());
        assertTrue(violations.contains("sample/Alias.java:5 - Engine.current("));
        assertTrue(violations.contains("sample/Alias.java:6 - DebugRenderer.current("));
    }

    @Test
    public void rawGetInstanceGuardIgnoresOnlyImportedJdkFactoryAcrossWhitespace(@TempDir Path tempDir)
            throws IOException {
        Path source = tempDir.resolve("ImportedFactory.java");
        Files.writeString(source, """
                package sample;

                import java.security.MessageDigest;

                class ImportedFactory {
                    void hash() throws Exception {
                        MessageDigest.getInstance("SHA-256");
                        MessageDigest
                                .
                                getInstance
                                ("SHA-256");
                    }
                }
                """);

        List<String> violations = new ArrayList<>();
        scanRawGetInstanceCalls(tempDir, source, violations);

        assertTrue(violations.isEmpty());
    }

    @Test
    public void rawGetInstanceGuardStillDetectsProjectAndLookalikeReceiversAcrossWhitespace(@TempDir Path tempDir)
            throws IOException {
        Path source = tempDir.resolve("ProjectFactories.java");
        Files.writeString(source, """
                package sample;

                class ProjectFactories {
                    void use() {
                        ProjectRegistry
                                .
                                getInstance
                                ();
                        MessageDigest.getInstance("custom");
                    }
                }

                class MessageDigest {
                    static MessageDigest getInstance(String algorithm) {
                        return new MessageDigest();
                    }
                }
                """);

        List<String> violations = new ArrayList<>();
        scanRawGetInstanceCalls(tempDir, source, violations);

        assertEquals(List.of(
                "ProjectFactories.java:5 - .getInstance(",
                "ProjectFactories.java:9 - .getInstance("), violations);
    }

    @Test
    public void rawGetInstanceGuardIgnoresCommentAndStringDecoys(@TempDir Path tempDir)
            throws IOException {
        Path source = tempDir.resolve("Comments.java");
        Files.writeString(source, """
                package sample;

                class Comments {
                    // ProjectRegistry.getInstance();
                    /* MessageDigest.getInstance("SHA-256"); */
                    String example = "ProjectRegistry.getInstance()";
                }
                """);

        List<String> violations = new ArrayList<>();
        scanRawGetInstanceCalls(tempDir, source, violations);

        assertTrue(violations.isEmpty());
    }

    private static void scanFile(Path srcMain, Path file, List<String> violations) {
        scanFile(srcMain, file, violations, FORBIDDEN_SINGLETONS);
    }

    private static void scanFile(Path srcMain, Path file, List<String> violations, List<String> forbiddenSingletons) {
        try {
            String relative = srcMain.relativize(file).toString().replace('\\', '/');
            String source = Files.readString(file);
            violations.addAll(scanSourceText(relative, source, forbiddenSingletons));
        } catch (IOException ignored) {
        }
    }

    private static void scanRawGetInstanceCalls(Path srcMain, Path file, List<String> violations) {
        try {
            String relative = srcMain.relativize(file).toString().replace('\\', '/');
            String source = stripComments(Files.readString(file));
            Matcher matcher = RAW_GET_INSTANCE_PATTERN.matcher(source);
            while (matcher.find()) {
                Receiver receiver = receiverBefore(source, matcher.start());
                if (!isNonSingletonGetInstanceOwner(source, receiver.name())) {
                    violations.add(relative + ":" + lineNumberForOffset(source, receiver.start())
                            + " - .getInstance(");
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isNonSingletonGetInstanceOwner(String source, String receiver) {
        if (NON_SINGLETON_GET_INSTANCE_OWNERS.contains(receiver)) {
            return true;
        }
        if (receiver.indexOf('.') >= 0) {
            return false;
        }
        return NON_SINGLETON_GET_INSTANCE_OWNERS.stream()
                .filter(owner -> owner.endsWith("." + receiver))
                .anyMatch(owner -> hasExactImport(source, owner));
    }

    private static boolean hasExactImport(String source, String owner) {
        String ownerPattern = Pattern.quote(owner).replace(".", "\\E\\s*\\.\\s*\\Q");
        Pattern importPattern = Pattern.compile(
                "(?m)^\\s*import\\s+" + ownerPattern + "\\s*;");
        return importPattern.matcher(source).find();
    }

    private static Receiver receiverBefore(String source, int dotOffset) {
        int cursor = skipWhitespaceBackward(source, dotOffset - 1);
        int receiverEnd = cursor + 1;
        int receiverStart = scanIdentifierBackward(source, cursor);
        if (receiverStart == receiverEnd) {
            return new Receiver("", dotOffset);
        }
        cursor = receiverStart - 1;

        while (true) {
            int separator = skipWhitespaceBackward(source, cursor);
            if (separator < 0 || source.charAt(separator) != '.') {
                break;
            }
            int precedingIdentifierEnd = skipWhitespaceBackward(source, separator - 1);
            int precedingIdentifierStart = scanIdentifierBackward(source, precedingIdentifierEnd);
            if (precedingIdentifierStart == precedingIdentifierEnd + 1) {
                break;
            }
            receiverStart = precedingIdentifierStart;
            cursor = receiverStart - 1;
        }

        String receiver = source.substring(receiverStart, receiverEnd).replaceAll("\\s+", "");
        return new Receiver(receiver, receiverStart);
    }

    private static int skipWhitespaceBackward(String source, int cursor) {
        while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    private static int scanIdentifierBackward(String source, int identifierEnd) {
        int cursor = identifierEnd;
        while (cursor >= 0 && Character.isJavaIdentifierPart(source.charAt(cursor))) {
            cursor--;
        }
        return cursor + 1;
    }

    private record Receiver(String name, int start) {
    }

    static List<String> scanSourceText(String relative, String source, List<String> forbiddenSingletons) {
        String stripped = stripComments(source);
        List<String> violations = new ArrayList<>();
        for (String forbidden : forbiddenSingletons) {
            Matcher matcher = compileForbiddenPattern(forbidden).matcher(stripped);
            while (matcher.find()) {
                violations.add(relative + ":" + lineNumberForOffset(stripped, matcher.start()) + " - " + forbidden);
            }
        }
        return violations;
    }

    static String stripComments(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (current == '\n') {
                    inLineComment = false;
                    stripped.append('\n');
                } else if (current == '\r') {
                    stripped.append('\r');
                } else {
                    stripped.append(' ');
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    stripped.append("  ");
                    i++;
                    inBlockComment = false;
                } else if (current == '\n' || current == '\r') {
                    stripped.append(current);
                } else {
                    stripped.append(' ');
                }
                continue;
            }

            if (inString) {
                if (current == '\n' || current == '\r') {
                    stripped.append(current);
                } else if (current == '"') {
                    stripped.append(current);
                } else {
                    stripped.append(' ');
                }
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (inChar) {
                if (current == '\n' || current == '\r') {
                    stripped.append(current);
                } else if (current == '\'') {
                    stripped.append(current);
                } else {
                    stripped.append(' ');
                }
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (current == '/' && next == '/') {
                stripped.append("  ");
                i++;
                inLineComment = true;
                continue;
            }

            if (current == '/' && next == '*') {
                stripped.append("  ");
                i++;
                inBlockComment = true;
                continue;
            }

            if (current == '"') {
                inString = true;
                stripped.append(current);
                continue;
            }

            if (current == '\'') {
                inChar = true;
                stripped.append(current);
                continue;
            }

            stripped.append(current);
        }
        return stripped.toString();
    }

    private static Pattern compileForbiddenPattern(String forbidden) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < forbidden.length(); i++) {
            char current = forbidden.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }
            if (Character.isJavaIdentifierPart(current)) {
                regex.append(Pattern.quote(String.valueOf(current)));
            } else {
                regex.append("\\s*")
                        .append(Pattern.quote(String.valueOf(current)))
                        .append("\\s*");
            }
        }
        return Pattern.compile(regex.toString(), Pattern.MULTILINE);
    }

    private static int lineNumberForOffset(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Path findSourceRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path srcMain = cwd.resolve("src/main/java");
        if (Files.isDirectory(srcMain)) {
            return srcMain;
        }
        Path parent = cwd.getParent();
        if (parent == null) {
            return null;
        }
        srcMain = parent.resolve("src/main/java");
        return Files.isDirectory(srcMain) ? srcMain : null;
    }
}
