package com.openggf.game;

import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.Engine;
import com.openggf.GameLoop;
import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEngineContext {
    @AfterEach
    void tearDown() {
        // See TestEngine.tearDown: a leaked Engine static poisons the fork.
        com.openggf.Engine.clearGlobalInstance();
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void defaultRootCollectsExistingProcessServicesAtCompositionBoundary() {
        EngineContext services = EngineContext.fromLegacySingletonsForBootstrap();

        assertSame(SonicConfigurationService.getInstance(), services.configuration());
        assertSame(GraphicsManager.getInstance(), services.graphics());
        assertSame(AudioManager.getInstance(), services.audio());
        assertSame(RomManager.getInstance(), services.roms());
        assertSame(PerformanceProfiler.getInstance(), services.profiler());
        assertSame(DebugOverlayManager.getInstance(), services.debugOverlay());
        assertSame(PlaybackDebugManager.getInstance(), services.playbackDebug());
        assertSame(RomDetectionService.getInstance(), services.romDetection());
        assertSame(CrossGameFeatureProvider.getInstance(), services.crossGameFeatures());
    }

    @Test
    void engineConstructorConfiguresEngineServicesRoot() {
        EngineContext staleRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineContext injectedRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineServices.configure(staleRoot);

        new Engine(injectedRoot);
        assertSame(injectedRoot, EngineServices.current());
    }

    @Test
    void gameLoopInjectedRootConfiguresEngineServicesRoot() {
        EngineContext staleRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineContext injectedRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineServices.configure(staleRoot);

        new GameLoop(injectedRoot);
        assertSame(injectedRoot, EngineServices.current());
    }

    @Test
    void engineServicesRootCanBeReconfiguredExplicitly() {
        EngineContext staleRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineContext injectedRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineServices.configure(staleRoot);
        assertSame(staleRoot, EngineServices.current());

        EngineServices.configure(injectedRoot);
        assertSame(injectedRoot, EngineServices.current());
    }

    @Test
    void defaultEngineConstructorUsesCurrentlyConfiguredEngineServicesRoot() {
        EngineContext configuredRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineServices.configure(configuredRoot);

        new Engine();

        assertSame(configuredRoot, EngineServices.current());
    }

    @Test
    void defaultGameLoopConstructorsUseCurrentlyConfiguredEngineServicesRoot() {
        EngineContext configuredRoot = EngineContext.fromLegacySingletonsForBootstrap();
        EngineServices.configure(configuredRoot);

        new GameLoop();
        assertSame(configuredRoot, EngineServices.current());

        new GameLoop(new InputHandler());
        assertSame(configuredRoot, EngineServices.current());
    }

    @Test
    void scopedEngineLoopCodeDoesNotBypassRootOwnedServices() throws IOException {
        assertNoRootBypass(Path.of("src/main/java/com/openggf/Engine.java"));
        assertNoRootBypass(Path.of("src/main/java/com/openggf/GameLoop.java"));
    }

    @Test
    void engineCreatesInputHandlerFromEngineOwnedConfiguration() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/openggf/Engine.java"));

        assertFalse(source.contains("new InputHandler()"),
                "Production Engine input must use the engine-owned SonicConfigurationService");
        assertFalse(source.contains("new InputHandler(configService)"),
                "Production Engine input must use the live input factory");
        assertTrue(source.contains("InputHandler.live(InputBindingFactory.supplier(configService))"),
                "Production Engine input must use the live input factory");
    }

    private static void assertNoRootBypass(Path path) throws IOException {
        String source = Files.readString(path);
        assertFalse(source.contains("GameServices.debugOverlay()"), path + " should use EngineContext.debugOverlay()");
        assertFalse(source.contains("GameServices.rom()"), path + " should use EngineContext.roms()");
    }
}
