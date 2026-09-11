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
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestLevelManagerPlaneSwitcherDispatch {

    @Test
    void playerSlotHookPreservesModuleOwnedP1ThenP2DispatchExactlyOnce() {
        GameModule module = mock(GameModule.class);
        LevelManager manager = managerFor(module);
        clearInvocations(module);
        AbstractPlayableSprite p1 = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite p2 = mock(AbstractPlayableSprite.class);

        manager.applyPlaneSwitchers(p1);
        manager.applyPlaneSwitchers(p2);

        InOrder order = inOrder(module);
        order.verify(module, times(1)).applyPlaneSwitching(p1);
        order.verify(module, times(1)).applyPlaneSwitching(p2);
        verifyNoMoreInteractions(module);
    }

    private static LevelManager managerFor(GameModule module) {
        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn(320);
        when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
        EngineContext context = new EngineContext(
                configuration,
                mock(GraphicsManager.class),
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
