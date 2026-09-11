package com.openggf;

import com.openggf.audio.AudioBackend;
import com.openggf.audio.GameSound;
import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.RomDetectionService;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestEngineConfiguredHeadlessStartup {
    private SonicConfigurationService config;
    private AudioManager audio;
    private CrossGameFeatureProvider crossGameFeatures;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, true);
        config.setConfigValue(
                SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, false);
        config.setConfigValue(
                SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP, false);
        audio = AudioManager.getInstance();
        audio.destroy();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        crossGameFeatures = mock(CrossGameFeatureProvider.class);
    }

    @AfterEach
    void tearDown() {
        Engine.clearGlobalInstance();
        SessionManager.clear();
        audio.destroy();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void headlessStartupRetainsTheExactInputAndInitializedBackend()
            throws Exception {
        StartupProbeEngine engine = new StartupProbeEngine(context());
        InputHandler input = new InputHandler();
        TrackingBackend backend = new TrackingBackend();

        engine.initializeConfiguredHeadlessSession(input, backend);

        assertSame(input, engine.getGameLoop().getInputHandler());
        assertSame(backend, audio.getBackend());
        assertTrue(backend.initialized);
        assertEquals(1, engine.initializeGameCalls,
                "the shared configured-startup branch must own game startup");
        var ensureBackend = Engine.class.getDeclaredMethod(
                "ensureAudioBackend");
        ensureBackend.setAccessible(true);
        ensureBackend.invoke(engine);
        assertSame(backend, audio.getBackend(),
                "later startup owners must ensure the retained sink, never install LWJGL");
        assertThrows(IllegalStateException.class,
                () -> engine.initializeConfiguredHeadlessSession(
                        new InputHandler(), new TrackingBackend()));

        engine.closeConfiguredHeadlessSession();
        engine.closeConfiguredHeadlessSession();
        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void failedBackendInitializationCannotSilentlyInstallAnAlternateBackend() {
        StartupProbeEngine engine = new StartupProbeEngine(context());

        assertThrows(IllegalStateException.class,
                () -> engine.initializeConfiguredHeadlessSession(
                        new InputHandler(), new FailingBackend()));
        assertEquals(0, engine.initializeGameCalls);
    }

    @Test
    void failedConfiguredStartupDestroysTheInstalledBackendAndAllowsRetry() {
        FailingStartupProbeEngine engine =
                new FailingStartupProbeEngine(context());
        InputHandler firstInput = new InputHandler();
        firstInput.setLogicalOverride(LogicalInputSnapshot.neutral());
        TrackingBackend firstBackend = new TrackingBackend();

        assertThrows(IllegalStateException.class,
                () -> engine.initializeConfiguredHeadlessSession(
                        firstInput, firstBackend));

        assertEquals(0, firstBackend.destroyCalls,
                "the caller must retain cleanup ordering after startup fails");
        assertTrue(firstInput.hasLogicalOverride());
        assertFalse(audio.captureLogicalSnapshot().ringLeft());

        engine.closeConfiguredHeadlessSession();

        assertEquals(1, firstBackend.destroyCalls);
        assertFalse(firstInput.hasLogicalOverride());
        assertCleanLogicalAudioState();

        TrackingBackend retryBackend = new TrackingBackend();
        engine.initializeConfiguredHeadlessSession(
                new InputHandler(), retryBackend);
        assertSame(retryBackend, audio.getBackend());
        assertEquals(2, engine.initializeGameCalls);
        assertCleanLogicalAudioState();
        engine.closeConfiguredHeadlessSession();
        assertEquals(1, retryBackend.destroyCalls);
    }

    @Test
    void successiveHeadlessSessionsStartWithFreshAudioAndReleaseInputLast() {
        InputHandler firstInput = new InputHandler();
        firstInput.setLogicalOverride(LogicalInputSnapshot.neutral());
        TrackingBackend firstBackend = new TrackingBackend(firstInput);
        StartupProbeEngine first = new StartupProbeEngine(context());
        first.initializeConfiguredHeadlessSession(firstInput, firstBackend);
        audio.playSfx(GameSound.RING);
        AudioLogicalSnapshot dirty = audio.captureLogicalSnapshot();
        assertFalse(dirty.ringLeft());
        assertTrue(dirty.commandEntryCount() > 0);

        first.closeConfiguredHeadlessSession();

        assertTrue(firstBackend.logicalOverridePresentDuringDestroy,
                "runtime resources must close before the BK2 input override");
        assertFalse(firstInput.hasLogicalOverride());
        assertCleanLogicalAudioState();
        verify(crossGameFeatures).resetState();

        StartupProbeEngine second = new StartupProbeEngine(context());
        TrackingBackend secondBackend = new TrackingBackend();
        second.initializeConfiguredHeadlessSession(
                new InputHandler(), secondBackend);

        assertCleanLogicalAudioState();
        second.closeConfiguredHeadlessSession();
        verify(crossGameFeatures, times(2)).resetState();
    }

    @Test
    void configuredStartupBranchSelectionIsSharedAndComplete() {
        assertEquals(Engine.ConfiguredStartupBranch.GAME,
                Engine.resolveConfiguredStartupBranch(config));

        config.setConfigValue(
                SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP, true);
        assertEquals(Engine.ConfiguredStartupBranch.MASTER_TITLE,
                Engine.resolveConfiguredStartupBranch(config));

        config.setConfigValue(
                SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, true);
        assertEquals(Engine.ConfiguredStartupBranch.LEGAL_DISCLAIMER,
                Engine.resolveConfiguredStartupBranch(config));
    }

    @Test
    void closeConfiguredHeadlessSessionClearsGameplaySessionOwnership() {
        StartupProbeEngine engine = new StartupProbeEngine(context());
        engine.initializeConfiguredHeadlessSession(
                new InputHandler(), new TrackingBackend());
        GameModule module = mock(GameModule.class);
        when(module.createRuntimeArtCoordinator(any()))
                .thenReturn(RuntimeArtCoordinator.NONE);
        SessionManager.openGameplaySession(module);

        engine.closeConfiguredHeadlessSession();

        assertNull(SessionManager.getCurrentWorldSession());
        assertNull(SessionManager.getCurrentGameplayMode());
    }

    private EngineContext context() {
        return new EngineContext(
                config,
                new GraphicsManager(),
                audio,
                mock(RomManager.class),
                mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class),
                mock(RomDetectionService.class),
                crossGameFeatures);
    }

    private void assertCleanLogicalAudioState() {
        AudioLogicalSnapshot snapshot = audio.captureLogicalSnapshot();
        assertTrue(snapshot.ringLeft());
        assertEquals(0, snapshot.commandEntryCount());
        assertTrue(snapshot.donorGameIds().isEmpty());
        assertTrue(snapshot.donorBindings().isEmpty());
    }

    private static class StartupProbeEngine extends Engine {
        protected int initializeGameCalls;

        protected StartupProbeEngine(EngineContext context) {
            super(context);
        }

        @Override
        public void initializeGame() {
            initializeGameCalls++;
        }
    }

    private static class TrackingBackend extends NullAudioBackend {
        private final InputHandler trackedInput;
        private boolean initialized;
        private int destroyCalls;
        private boolean logicalOverridePresentDuringDestroy;

        private TrackingBackend() {
            this(null);
        }

        private TrackingBackend(InputHandler trackedInput) {
            this.trackedInput = trackedInput;
        }

        @Override
        public void init() {
            initialized = true;
        }

        @Override
        public void destroy() {
            destroyCalls++;
            if (trackedInput != null) {
                logicalOverridePresentDuringDestroy =
                        trackedInput.hasLogicalOverride();
            }
        }
    }

    private static final class FailingStartupProbeEngine
            extends StartupProbeEngine {
        private final AudioManager audio;
        private boolean failStartup = true;

        private FailingStartupProbeEngine(EngineContext context) {
            super(context);
            audio = context.audio();
        }

        @Override
        public void initializeGame() {
            super.initializeGame();
            if (failStartup) {
                failStartup = false;
                audio.playSfx(GameSound.RING);
                throw new IllegalStateException("injected startup failure");
            }
        }
    }

    private static final class FailingBackend extends TrackingBackend {
        @Override
        public void init() {
            throw new IllegalStateException("injected initialization failure");
        }
    }
}
