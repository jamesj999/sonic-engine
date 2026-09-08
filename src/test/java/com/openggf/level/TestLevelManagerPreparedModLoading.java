package com.openggf.level;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameStateManager;
import com.openggf.game.RomDetectionService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.WorldSession;
import com.openggf.graphics.GraphicsManager;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.Test;
import com.openggf.data.Game;
import com.openggf.game.ZoneKey;
import com.openggf.game.ZoneRegistry;
import com.openggf.level.resources.PreparableLevelLoader;
import java.util.List;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLevelManagerPreparedModLoading {
    @Test
    void additiveDestinationNeverSubmitsItsSyntheticIndexToStockRomLoader() {
        GameModule module = mock(GameModule.class);
        ZoneRegistry registry = mock(ZoneRegistry.class);
        when(module.getZoneRegistry()).thenReturn(registry);
        when(registry.zoneKey(0)).thenReturn(ZoneKey.mod("example", "custom-zone"));
        LevelManager manager = managerFor(module);
        Game game = mock(Game.class, withSettings().extraInterfaces(PreparableLevelLoader.class));
        manager.game = game;
        LevelDescriptor descriptor = mock(LevelDescriptor.class);
        when(descriptor.levelIndex()).thenReturn(0x400);
        manager.levels.add(List.of(descriptor));

        assertFalse(manager.prepareActTransitionLevelLoad(0, 0, null));
        verifyNoInteractions(game);
    }

    private static LevelManager managerFor(GameModule module) {
        return managerFor(module, mock(GraphicsManager.class));
    }

    private static LevelManager managerFor(
            GameModule module, GraphicsManager graphicsManager) {
        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn(320);
        when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
        EngineContext context = new EngineContext(
                configuration,
                graphicsManager,
                mock(AudioManager.class),
                mock(RomManager.class),
                mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class),
                mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class));
        LevelManager manager = new LevelManager(
                mock(Camera.class),
                mock(SpriteManager.class),
                mock(ParallaxManager.class),
                mock(CollisionSystem.class),
                mock(WaterSystem.class),
                new GameStateManager(),
                context,
                new WorldSession(module));
        manager.gameModule = module;
        return manager;
    }
}
