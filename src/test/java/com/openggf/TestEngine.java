package com.openggf;

import com.openggf.game.GameId;
import com.openggf.game.GameModule;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameMode;
import com.openggf.game.GameStateManager;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.audio.AudioManager;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.dataselect.DataSelectAction;
import com.openggf.game.dataselect.DataSelectActionType;
import com.openggf.game.save.SaveManager;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageCacheManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.dataselect.S2DataSelectImageCacheManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.graphics.GraphicsManager;
import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.game.RomDetectionService;
import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;

class TestEngine {
    @TempDir
    Path tempDir;

    @Test
    void titleAudioBackendReinstallHonorsAudioEnabledLifecycle() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, false);
        AudioManager audio = mock(AudioManager.class);
        Engine engine = new Engine(new EngineContext(
                config,
                new GraphicsManager(),
                audio,
                mock(RomManager.class),
                mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class),
                mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class)));
        clearInvocations(audio);

        invokePrivateMethod(engine, "ensureAudioBackend", new Class<?>[]{});
        verify(audio, never()).setBackend(any());

        config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, true);
        invokePrivateMethod(engine, "ensureAudioBackend", new Class<?>[]{});
        verify(audio).setBackend(any());

        clearInvocations(audio);
        invokePrivateMethod(engine, "resetForGameplayFromMasterTitle", new Class<?>[]{});
        invokePrivateMethod(engine, "initializeGlobalGameplayServices", new Class<?>[]{});
        verify(audio).resetState();
        verify(audio).ensurePresentationSink();

        clearInvocations(audio);
        invokePrivateMethod(engine, "resetForGameplayFromMasterTitle", new Class<?>[]{});
        invokePrivateMethod(engine, "showStartupRomError", new Class<?>[]{String.class},
                "title reconstruction test");
        verify(audio).ensurePresentationSink();
    }

    @AfterEach
    void tearDown() {
        // Constructing an Engine publishes it into a process-global static.
        // Surefire reuses forks, so leaving it set lets an unrelated later test
        // reach Engine.currentGameLoop() and drive a GL shader compile with no
        // context, which aborts the JVM rather than failing a test.
        Engine.clearGlobalInstance();
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void visualTraceReplayActivationKeepsPreparedRenderManagers() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext presentation = SessionManager.getCurrentGameplayMode();
        Engine engine = new Engine(EngineServices.current());

        GameplayModeContext replay = presentation;
        replay.activateRecordedHardwareAdmission();

        assertSame(presentation, replay);
        assertTrue(presentation.isGameplayRuntimeReady());
        assertSame(replay, SessionManager.getCurrentGameplayMode());
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                replay.hardwareTiming().admissionPolicy());
    }

    @Test
    void restoreS3kSaveProgressPreservesExplicitConvertedStateWithoutSuperEmeralds() {
        GameplayModeContext gameplayMode = mock(GameplayModeContext.class);
        GameStateManager gameState = new GameStateManager();
        when(gameplayMode.getGameStateManager()).thenReturn(gameState);

        Engine.restoreGameplayModeFromDataSelectPayload(gameplayMode, Map.of(
                "lives", 3,
                "continues", 0,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(),
                "emeraldsConverted", true));

        assertTrue(gameState.isEmeraldsConverted());
        assertTrue(gameState.getCollectedSuperEmeraldIndices().isEmpty());
    }

    /** docs/skdisasm/sonic3k.asm:16997-17012: zero lives on load costs a continue and gives three lives. */
    @Test
    void restoreSlotAfterGameOverSpendsAContinueAndRestoresThreeLives() {
        GameplayModeContext gameplayMode = mock(GameplayModeContext.class);
        GameStateManager gameState = new GameStateManager();
        when(gameplayMode.getGameStateManager()).thenReturn(gameState);

        Engine.restoreGameplayModeFromDataSelectPayload(gameplayMode, Map.of(
                "lives", 0, "continues", 2, "chaosEmeralds", List.of(), "superEmeralds", List.of()));
        assertEquals(3, gameState.getLives());
        assertEquals(1, gameState.getContinues());

        Engine.restoreGameplayModeFromDataSelectPayload(gameplayMode, Map.of(
                "lives", 0, "continues", 0, "chaosEmeralds", List.of(), "superEmeralds", List.of()));
        assertEquals(3, gameState.getLives());
        assertEquals(0, gameState.getContinues(), "subq.b then bcc/clr.b clamps at zero");

        Engine.restoreGameplayModeFromDataSelectPayload(gameplayMode, Map.of(
                "lives", 2, "continues", 0, "chaosEmeralds", List.of(), "superEmeralds", List.of()));
        assertEquals(3, gameState.getLives(), "fewer than three lives with no continues is topped up");

        Engine.restoreGameplayModeFromDataSelectPayload(gameplayMode, Map.of(
                "lives", 2, "continues", 1, "chaosEmeralds", List.of(), "superEmeralds", List.of()));
        assertEquals(2, gameState.getLives(), "lives below three with a continue in hand are kept");
        assertEquals(1, gameState.getContinues());
    }

    @Test
    void drawMasterTitleScreenDoesNotRequireGameplayCamera() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        Engine engine = new Engine();
        MasterTitleScreen masterTitleScreen = mock(MasterTitleScreen.class);

        setPrivateField(engine, "masterTitleScreen", masterTitleScreen);
        engine.getGameLoop().setGameMode(GameMode.MASTER_TITLE_SCREEN);

        assertDoesNotThrow(engine::draw);
        verify(masterTitleScreen).setProjectionMatrix(engine.getProjectionMatrixBuffer());
        verify(masterTitleScreen).draw();
    }

    @Test
    void renderThreadTasksRunWhenPumped() throws Exception {
        GraphicsManager graphics = new GraphicsManager();
        CompletableFuture<Integer> future = graphics.submitRenderThreadTask(() -> 42);

        assertFalse(future.isDone());

        graphics.runPendingRenderThreadTasks();

        assertTrue(future.isDone());
        assertEquals(42, future.join());
    }

    @Test
    void resetState_discardsPendingRenderThreadTasks() throws Exception {
        GraphicsManager graphics = new GraphicsManager();
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<Integer> future = graphics.submitRenderThreadTask(() -> runs.incrementAndGet());

        graphics.resetState();
        graphics.runPendingRenderThreadTasks();

        assertEquals(0, runs.get());
        assertTrue(future.isCancelled());
    }

    @Test
    void cleanup_discardsPendingRenderThreadTasks() throws Exception {
        GraphicsManager graphics = new GraphicsManager();
        graphics.initHeadless();
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<Integer> future = graphics.submitRenderThreadTask(() -> runs.incrementAndGet());

        graphics.cleanup();
        graphics.runPendingRenderThreadTasks();

        assertEquals(0, runs.get());
        assertTrue(future.isCancelled());
    }

    @Test
    void computeIntegerScaleUpperBound_ignoresUnavailableVideoMode() {
        assertNull(Engine.computeIntegerScaleUpperBound(320, 224, null));
        assertNull(Engine.computeIntegerScaleUpperBound(0, 224, 1920, 1080));
        assertEquals(4, Engine.computeIntegerScaleUpperBound(320, 224, 1920, 1080));
        assertEquals(1, Engine.computeIntegerScaleUpperBound(320, 224, 160, 112));
    }

    @Test
    void refreshLaunchSessionCachedConfig_updatesDebugViewCacheFromSessionOverride() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
        Engine engine = newTestEngine(config, new GraphicsManager());

        assertFalse((boolean) getPrivateField(engine, "debugViewEnabled"));

        config.setSessionOverride(SonicConfiguration.DEBUG_VIEW_ENABLED, true);
        engine.refreshLaunchSessionCachedConfig();

        assertTrue((boolean) getPrivateField(engine, "debugViewEnabled"));

        config.clearSessionOverrides();
        engine.refreshLaunchSessionCachedConfig();

        assertFalse((boolean) getPrivateField(engine, "debugViewEnabled"));
    }

    @Test
    void readResolvedDisplayDimensionsForLaunch_readsNativeGlobalDimensions() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.resolveDisplayAspect();
        Engine engine = newTestEngine(config, new GraphicsManager());

        Engine.ResolvedDisplayDimensions resolved = engine.readResolvedDisplayDimensionsForLaunch();

        assertEquals(320, resolved.pixelWidth());
        assertEquals(224, resolved.pixelHeight());
        assertEquals(640, resolved.windowWidth());
        assertEquals(448, resolved.windowHeight());
    }

    @Test
    void readResolvedDisplayDimensionsForLaunch_readsPinnedWide169SessionDimensions() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        config.resolveDisplayAspect();
        Engine engine = newTestEngine(config, new GraphicsManager());

        Engine.ResolvedDisplayDimensions resolved = engine.readResolvedDisplayDimensionsForLaunch();

        assertEquals(400, resolved.pixelWidth());
        assertEquals(224, resolved.pixelHeight());
        assertEquals(800, resolved.windowWidth());
        assertEquals(448, resolved.windowHeight());
    }

    @Test
    void readResolvedDisplayDimensionsForLaunch_forcesPinnedUltra219ToNativeInTestMode() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        config.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "ULTRA_21_9");
        config.resolveDisplayAspect();
        Engine engine = newTestEngine(config, new GraphicsManager());

        Engine.ResolvedDisplayDimensions resolved = engine.readResolvedDisplayDimensionsForLaunch();

        assertEquals(320, resolved.pixelWidth());
    }

    @Test
    void performanceProfilerEligibilityFollowsPerformanceToggleInNativeImages() {
        assertTrue(Engine.shouldEnablePerformanceProfiler(true, false));
        assertFalse(Engine.shouldEnablePerformanceProfiler(false, false));
        assertTrue(Engine.shouldEnablePerformanceProfiler(true, true));
    }

    @Test
    void performanceOverlayCanRenderInSpecialStagesWithoutDebugOverlayGate() {
        assertTrue(Engine.shouldRenderPerformanceOverlay(GameMode.SPECIAL_STAGE, true, false));
        assertTrue(Engine.shouldRenderPerformanceOverlay(GameMode.LEVEL, true, false));
        assertFalse(Engine.shouldRenderPerformanceOverlay(GameMode.SPECIAL_STAGE, false, false));
        assertFalse(Engine.shouldRenderPerformanceOverlay(GameMode.SPECIAL_STAGE, true, true));
    }

    @Test
    void applyResolvedDisplayDimensionsPreservesCurrentWindowDuringModeTransitions() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        GraphicsManager graphics = new GraphicsManager();
        TilemapGpuRenderer tilemapRenderer = new TilemapGpuRenderer(320);
        setPrivateField(graphics, "tilemapGpuRenderer", tilemapRenderer);
        Engine engine = newTestEngine(config, graphics);
        setPrivateField(engine, "windowWidth", 640);
        setPrivateField(engine, "windowHeight", 448);

        config.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        config.resolveDisplayAspect();
        engine.applyResolvedDisplayDimensions();

        assertEquals(400.0, (double) getPrivateField(engine, "realWidth"));
        assertEquals(224.0, (double) getPrivateField(engine, "realHeight"));
        assertEquals(400.0, (double) getPrivateField(engine, "projectionWidth"));
        assertEquals(640, getPrivateField(engine, "windowWidth"));
        assertEquals(448, getPrivateField(engine, "windowHeight"));
        assertEquals(400, graphics.getProjectionWidth());
        assertEquals(25, tilemapRenderer.getVScrollColumnCapacity());
    }

    @Test
    void resolveFramebufferDimensionsAfterWindowResizePrefersActualFramebufferPixels() {
        Engine.FramebufferDimensions dimensions =
                Engine.resolveFramebufferDimensionsAfterWindowResize(800, 448, 1600, 896);

        assertEquals(1600, dimensions.width());
        assertEquals(896, dimensions.height());
    }

    @Test
    void resolveFramebufferDimensionsAfterWindowResizeFallsBackWhenFramebufferUnavailable() {
        Engine.FramebufferDimensions dimensions =
                Engine.resolveFramebufferDimensionsAfterWindowResize(800, 448, 0, 0);

        assertEquals(800, dimensions.width());
        assertEquals(448, dimensions.height());
    }

    @Test
    void failedMasterTitleLaunchRollsBackHostLaunchCachesAfterSessionOverridesClear() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.setConfigValue(SonicConfiguration.SCREEN_WIDTH, 640);
        config.setConfigValue(SonicConfiguration.SCREEN_HEIGHT, 448);
        config.setConfigValue(SonicConfiguration.LAUNCH_S2_DEBUG_TOOLS, true);
        config.setConfigValue(SonicConfiguration.LAUNCH_S2_ASPECT, "WIDE_16_9");
        config.resolveDisplayAspect();

        GraphicsManager graphics = new GraphicsManager();
        TilemapGpuRenderer tilemapRenderer = new TilemapGpuRenderer(320);
        setPrivateField(graphics, "tilemapGpuRenderer", tilemapRenderer);
        RomManager romManager = mock(RomManager.class);
        Rom rom = mock(Rom.class);
        when(romManager.getRom()).thenReturn(rom);
        RomDetectionService romDetection = mock(RomDetectionService.class);
        when(romDetection.detectAndCreateModule(rom)).thenReturn(Optional.empty());
        Engine engine = new Engine(new EngineContext(
                config,
                graphics,
                mock(AudioManager.class),
                romManager,
                mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class),
                romDetection,
                mock(CrossGameFeatureProvider.class)));

        invokePrivateMethod(engine.getGameLoop(), "doExitMasterTitleScreen",
                new Class<?>[] {String.class, boolean.class}, "s2", false);

        assertFalse(config.hasSessionOverride(SonicConfiguration.DEBUG_VIEW_ENABLED));
        assertFalse(config.hasSessionOverride(SonicConfiguration.DISPLAY_ASPECT));
        assertFalse((boolean) getPrivateField(engine, "debugViewEnabled"));
        assertEquals(320, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(320.0, (double) getPrivateField(engine, "realWidth"));
        assertEquals(224.0, (double) getPrivateField(engine, "realHeight"));
        assertEquals(320.0, (double) getPrivateField(engine, "projectionWidth"));
        assertEquals(640, getPrivateField(engine, "windowWidth"));
        assertEquals(448, getPrivateField(engine, "windowHeight"));
        assertEquals(320, graphics.getProjectionWidth());
        assertEquals(20, tilemapRenderer.getVScrollColumnCapacity());
    }

    @Test
    void sonic1GameModule_exposesWarmupCapableImageCacheManager() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        Sonic1GameModule module = new Sonic1GameModule();

        S1DataSelectImageCacheManager manager = module.getGameService(S1DataSelectImageCacheManager.class);
        S1DataSelectImageCacheManager secondLookup = module.getGameService(S1DataSelectImageCacheManager.class);
        Optional<com.openggf.game.startup.DonatedDataSelectWarmupTask> warmup =
                module.getDonatedDataSelectWarmupTask();

        assertNotNull(manager);
        assertTrue(manager instanceof Sonic1GameModule.S1DataSelectImageWarmup);
        assertSame(manager, secondLookup);
        assertTrue(warmup.isPresent());
        assertSame(manager, warmup.orElseThrow());
    }

    @Test
    void sonic2GameModule_exposesWarmupCapableImageCacheManager() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        Sonic2GameModule module = new Sonic2GameModule();

        S2DataSelectImageCacheManager manager = module.getGameService(S2DataSelectImageCacheManager.class);
        S2DataSelectImageCacheManager secondLookup = module.getGameService(S2DataSelectImageCacheManager.class);
        Optional<com.openggf.game.startup.DonatedDataSelectWarmupTask> warmup =
                module.getDonatedDataSelectWarmupTask();

        assertNotNull(manager);
        assertTrue(manager instanceof Sonic2GameModule.S2DataSelectImageWarmup);
        assertSame(manager, secondLookup);
        assertTrue(warmup.isPresent());
        assertSame(manager, warmup.orElseThrow());
    }

    @Test
    void initializeGame_runsS1WarmupBeforeStartupModeEntry() throws Exception {
        try (BootstrapHarness harness = createBootstrapHarness(true)) {
            harness.engine.initializeGame();

            assertEquals(1, harness.cacheManager.ensureStartedCalls);
            assertEquals(1, harness.cacheManager.renderTaskRuns.get());
            var order = inOrder(harness.graphics, harness.levelManager);
            order.verify(harness.graphics).runPendingRenderThreadTasks();
            order.verify(harness.levelManager).loadZoneAndActForFreshRuntime(0, 0);
            order.verifyNoMoreInteractions();
        }
    }

    @Test
    void initializeGame_skipsS1WarmupWhenDonationIsInactive() throws Exception {
        try (BootstrapHarness harness = createBootstrapHarness(false)) {
            harness.engine.initializeGame();

            assertEquals(0, harness.cacheManager.ensureStartedCalls);
            assertEquals(0, harness.cacheManager.renderTaskRuns.get());
            var order = inOrder(harness.graphics, harness.levelManager);
            order.verify(harness.graphics).runPendingRenderThreadTasks();
            order.verify(harness.levelManager).loadZoneAndActForFreshRuntime(0, 0);
            order.verifyNoMoreInteractions();
        }
    }

    @Test
    void initializeGame_surfacesUnrecognizedOrCorruptRomOnMasterTitleScreen() throws Exception {
        try (BootstrapHarness harness = createBootstrapHarness(false, Optional.empty())) {
            assertDoesNotThrow(harness.engine::initializeGame);

            assertEquals(GameMode.MASTER_TITLE_SCREEN, harness.engine.getCurrentGameMode());
            assertNotNull(harness.engine.getMasterTitleScreen());
            verify(harness.levelManager, never()).loadZoneAndActForFreshRuntime(0, 0);
        }
    }

    @Test
    void exitMasterTitleScreenFromBootstrapDoesNotRequireActiveWorldSession() throws Exception {
        SessionManager.clear();
        try (BootstrapHarness harness = createBootstrapHarness(false)) {
            MasterTitleScreen masterTitleScreen = mock(MasterTitleScreen.class);
            setPrivateField(harness.engine, "masterTitleScreen", masterTitleScreen);

            assertDoesNotThrow(() -> harness.engine.exitMasterTitleScreen("s2"));

            verify(masterTitleScreen).cleanup();
            verify(harness.graphics).clearPaletteTextures();
            assertEquals("s2", SonicConfigurationService.getInstance()
                    .getConfigValue(SonicConfiguration.DEFAULT_ROM));
        }
    }

    @Test
    void createDataSelectSaveContext_preservesClearSaveStateFromPayload(@TempDir Path saveRoot) throws Exception {
        SaveManager saveManager = new SaveManager(saveRoot);
        saveManager.writeSlot("s3k", 1, Map.of(
                "zone", 6,
                "act", 1,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 9,
                "continues", 3,
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 2),
                "clear", true
        ));

        GameModule module = mock(GameModule.class);
        when(module.getGameId()).thenReturn(GameId.S3K);

        DataSelectAction action = new DataSelectAction(
                DataSelectActionType.CLEAR_RESTART,
                1,
                10,
                0,
                new SelectedTeam("sonic", List.of("tails")));

        SaveSessionContext context = Engine.createDataSelectSaveContext(
                module, action, saveManager.readSlotSummary("s3k", 1).payload());

        assertEquals(1, context.activeSlot().orElseThrow());
        assertTrue(context.isClear(), "Clear-save launch context should preserve the clear flag");
        assertEquals("sonic", context.selectedTeam().mainCharacter());
        assertEquals(List.of("tails"), context.selectedTeam().sidekicks());
        assertEquals(10, context.startZone());
        assertEquals(0, context.startAct());
    }

    @Test
    void createDataSelectSaveContext_ignoresNonLoadableSavePayload(@TempDir Path saveRoot) throws Exception {
        SaveManager saveManager = new SaveManager(saveRoot);
        saveManager.writeSlot("s3k", 1, Map.of(
                "zone", 6,
                "act", 1,
                "mainCharacter", "knuckles",
                "sidekicks", List.of("tails"),
                "lives", 9,
                "clear", true
        ));
        Path slot = saveRoot.resolve("s3k").resolve("slot1.json");
        Files.writeString(slot, Files.readString(slot).replace("\"hash\":\"", "\"hash\":\"broken"));

        GameModule module = mock(GameModule.class);
        when(module.getGameId()).thenReturn(GameId.S3K);
        DataSelectAction action = new DataSelectAction(
                DataSelectActionType.LOAD_SLOT,
                1,
                0,
                0,
                new SelectedTeam("sonic", List.of()));

        Map<String, Object> loadedPayload = Engine.loadDataSelectPayload(module, action, saveManager);
        assertNull(loadedPayload);
        SaveSessionContext context = Engine.createDataSelectSaveContext(module, action, loadedPayload);

        assertEquals(1, context.activeSlot().orElseThrow());
        assertEquals("sonic", context.selectedTeam().mainCharacter(),
                "non-loadable save payloads must not override the selected launch team");
        assertEquals(0, context.startZone(),
                "non-loadable save payloads must not override the action destination");
        assertFalse(context.isClear(),
                "non-loadable save payloads must not preserve clear-state metadata");
    }

    @Test
    void dataSelectLaunchSaveReason_mapsExistingSlotLoad() {
        assertEquals(Optional.of(SaveReason.EXISTING_SLOT_LOAD),
                Engine.dataSelectLaunchSaveReason(DataSelectActionType.LOAD_SLOT));
        assertEquals(Optional.of(SaveReason.NEW_SLOT_START),
                Engine.dataSelectLaunchSaveReason(DataSelectActionType.NEW_SLOT_START));
        assertEquals(Optional.of(SaveReason.CLEAR_RESTART_COMMIT),
                Engine.dataSelectLaunchSaveReason(DataSelectActionType.CLEAR_RESTART));
        assertEquals(Optional.empty(),
                Engine.dataSelectLaunchSaveReason(DataSelectActionType.NO_SAVE_START));
    }

    @Test
    void resolveMainPlayableSprite_prefersSelectedTeamOverConfigDuringGameplay() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

        Engine engine = new Engine();
        SpriteManager spriteManager = mock(SpriteManager.class);
        Camera camera = mock(Camera.class);
        LevelManager levelManager = mock(LevelManager.class);

        Knuckles knuckles = new Knuckles("knuckles", (short) 100, (short) 624);
        when(spriteManager.getSprite("knuckles")).thenReturn(knuckles);

        GameplayModeContext gameplayMode = SessionManager.openGameplaySession(neutralGameModule(),
                SaveSessionContext.noSave("s1", new SelectedTeam("knuckles", List.of()), 0, 0));
        setPrivateField(engine, "gameplayMode", gameplayMode);
        setPrivateField(engine, "spriteManager", spriteManager);
        setPrivateField(engine, "camera", camera);
        setPrivateField(engine, "levelManager", levelManager);

        var method = Engine.class.getDeclaredMethod("resolveMainPlayableSprite");
        method.setAccessible(true);
        Object resolved = method.invoke(engine);

        assertSame(knuckles, resolved);
    }

    private static GameModule neutralGameModule() {
        GameModule module = mock(GameModule.class);
        when(module.createRuntimeArtCoordinator(any())).thenReturn(RuntimeArtCoordinator.NONE);
        return module;
    }

    private static final class TrackingS1ImageCacheManager extends S1DataSelectImageCacheManager
            implements Sonic1GameModule.S1DataSelectImageWarmup,
            com.openggf.game.startup.DonatedDataSelectWarmupTask {
        int ensureStartedCalls;
        final AtomicInteger renderTaskRuns = new AtomicInteger();
        private final GraphicsManager graphics;

        TrackingS1ImageCacheManager(Path cacheRoot, GraphicsManager graphics) {
            super(cacheRoot,
                    SonicConfigurationService.getInstance(),
                    () -> "test-rom-sha",
                    new com.fasterxml.jackson.databind.ObjectMapper());
            this.graphics = graphics;
        }

        @Override
        public synchronized void ensureGenerationStarted() {
            ensureStartedCalls++;
            graphics.submitRenderThreadTask(() -> {
                renderTaskRuns.incrementAndGet();
                return 42;
            });
        }

        @Override
        public void start() {
            ensureGenerationStarted();
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }

    private BootstrapHarness createBootstrapHarness(boolean donorActive) throws Exception {
        TrackingS1ImageCacheManager cacheManager = new TrackingS1ImageCacheManager(tempDir, spy(new GraphicsManager()));
        Sonic1GameModule module = newSonic1WarmupModule(cacheManager);
        return createBootstrapHarness(donorActive, Optional.of(module), cacheManager);
    }

    private BootstrapHarness createBootstrapHarness(boolean donorActive, Optional<GameModule> detectedModule) throws Exception {
        TrackingS1ImageCacheManager cacheManager = new TrackingS1ImageCacheManager(tempDir, spy(new GraphicsManager()));
        return createBootstrapHarness(donorActive, detectedModule, cacheManager);
    }

    private BootstrapHarness createBootstrapHarness(
            boolean donorActive,
            Optional<GameModule> detectedModule,
            TrackingS1ImageCacheManager suppliedCacheManager) throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, false);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_SOURCE, donorActive ? "s3k" : "s2");
        if (donorActive) {
            config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        }
        config.setConfigValue(SonicConfiguration.TITLE_SCREEN_ON_STARTUP, false);
        config.setConfigValue(SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");

        GraphicsManager graphics = suppliedCacheManager.graphics;
        RomManager romManager = mock(RomManager.class);
        Rom rom = mock(Rom.class);
        when(romManager.getRom()).thenReturn(rom);
        AudioManager audioManager = mock(AudioManager.class);
        PerformanceProfiler profiler = mock(PerformanceProfiler.class);
        DebugOverlayManager debugOverlayManager = mock(DebugOverlayManager.class);
        PlaybackDebugManager playbackDebugManager = mock(PlaybackDebugManager.class);
        RomDetectionService romDetectionService = mock(RomDetectionService.class);
        CrossGameFeatureProvider crossGameFeatureProvider = mock(CrossGameFeatureProvider.class);
        EngineContext services = new EngineContext(config, graphics, audioManager, romManager, profiler,
                debugOverlayManager, playbackDebugManager, romDetectionService, crossGameFeatureProvider);

        TrackingS1ImageCacheManager cacheManager = suppliedCacheManager;

        Camera camera = new Camera();
        SpriteManager spriteManager = mock(SpriteManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        GameStateManager gameState = new GameStateManager();
        doReturn(false).when(spriteManager).addSprite(any());
        doAnswer(invocation -> {
            assertEquals(donorActive ? 1 : 0, cacheManager.renderTaskRuns.get());
            return null;
        }).when(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        when(romDetectionService.detectAndCreateModule(rom)).thenReturn(detectedModule);

        MockedStatic<GameplaySessionFactory> gameplayFactory =
                mockStatic(GameplaySessionFactory.class, CALLS_REAL_METHODS);
        gameplayFactory.when(() -> GameplaySessionFactory.attachManagers(
                        any(GameplayModeContext.class), any(EngineContext.class)))
                .thenAnswer(invocation -> {
                    GameplayModeContext gameplayMode = invocation.getArgument(0);
                    gameplayMode.tearDownManagers();
                    when(spriteManager.rewindSnapshottable()).thenReturn(
                            new com.openggf.game.rewind.RewindSnapshottable<com.openggf.game.rewind.snapshot.SpriteManagerSnapshot>() {
                        @Override
                        public String key() {
                            return "sprites";
                        }

                        @Override
                        public com.openggf.game.rewind.snapshot.SpriteManagerSnapshot capture() {
                            return null;
                        }

                        @Override
                        public void restore(com.openggf.game.rewind.snapshot.SpriteManagerSnapshot snapshot) {
                        }
                    });
                    gameplayMode.attachGameplayManagers(
                            camera,
                            new com.openggf.timer.TimerManager(),
                            gameState,
                            new com.openggf.graphics.FadeManager(),
                            new com.openggf.game.GameRng(com.openggf.game.GameRng.Flavour.S1_S2),
                            new com.openggf.game.solid.DefaultSolidExecutionRegistry());
                    gameplayMode.attachLevelManagers(
                            new com.openggf.level.WaterSystem(),
                            new com.openggf.level.ParallaxManager(),
                            mock(com.openggf.physics.TerrainCollisionManager.class),
                            mock(com.openggf.physics.CollisionSystem.class),
                            spriteManager,
                            levelManager);
                    gameplayMode.attachSharedRegistries(
                            new com.openggf.game.zone.ZoneRuntimeRegistry(),
                            new com.openggf.game.palette.PaletteOwnershipRegistry(),
                            new com.openggf.game.animation.AnimatedTileChannelGraph(),
                            new com.openggf.game.render.SpecialRenderEffectRegistry(),
                            new com.openggf.game.render.AdvancedRenderModeController(),
                            new com.openggf.game.mutation.ZoneLayoutMutationPipeline());
                    return null;
                });

        MockedStatic<CrossGameFeatureProvider> donor = mockStatic(CrossGameFeatureProvider.class);
        donor.when(CrossGameFeatureProvider::isS3kDonorActive).thenReturn(donorActive);
        donor.when(CrossGameFeatureProvider::isActive).thenReturn(false);

        return new BootstrapHarness(
                new Engine(services),
                graphics,
                levelManager,
                cacheManager,
                donor,
                gameplayFactory);
    }

    private static Sonic1GameModule newSonic1WarmupModule(TrackingS1ImageCacheManager cacheManager) {
        return new Sonic1GameModule() {
            @Override
            public <T> T getGameService(Class<T> type) {
                if (type == S1DataSelectImageCacheManager.class) {
                    return type.cast(cacheManager);
                }
                return super.getGameService(type);
            }

            @Override
            public Optional<com.openggf.game.startup.DonatedDataSelectWarmupTask> getDonatedDataSelectWarmupTask() {
                return Optional.of(cacheManager);
            }
        };
    }

    private static final class BootstrapHarness implements AutoCloseable {
        final Engine engine;
        final GraphicsManager graphics;
        final LevelManager levelManager;
        final TrackingS1ImageCacheManager cacheManager;
        final MockedStatic<CrossGameFeatureProvider> donor;
        final MockedStatic<GameplaySessionFactory> gameplayFactory;

        BootstrapHarness(Engine engine,
                         GraphicsManager graphics,
                         LevelManager levelManager,
                         TrackingS1ImageCacheManager cacheManager,
                         MockedStatic<CrossGameFeatureProvider> donor,
                         MockedStatic<GameplaySessionFactory> gameplayFactory) {
            this.engine = engine;
            this.graphics = graphics;
            this.levelManager = levelManager;
            this.cacheManager = cacheManager;
            this.donor = donor;
            this.gameplayFactory = gameplayFactory;
        }

        @Override
        public void close() {
            donor.close();
            gameplayFactory.close();
        }
    }

    private static GraphicsManager replaceGraphicsManagerSingleton(GraphicsManager replacement) throws Exception {
        Field field = GraphicsManager.class.getDeclaredField("graphicsManager");
        field.setAccessible(true);
        GraphicsManager previous = (GraphicsManager) field.get(null);
        field.set(null, replacement);
        return previous;
    }

    private static Engine newTestEngine(SonicConfigurationService config, GraphicsManager graphics) {
        return new Engine(new EngineContext(
                config,
                graphics,
                mock(AudioManager.class),
                mock(RomManager.class),
                mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class),
                mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class)));
    }

    private static Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invokePrivateMethod(
            Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
