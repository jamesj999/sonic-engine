package com.openggf.level;

import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameRng;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.RomDetectionService;
import com.openggf.game.session.EditorModeContext;
import com.openggf.game.session.EditorSessionFactory;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.session.PatternWindowSessionState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlas;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.mods.code.ModPatternWindowAllocator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLevelManagerModPatternWindows {
    private static final int MOD_BASE = PatternAtlasRange.CONTINUE_SCREEN.endExclusive();

    @Test
    void everyLoadAndEditorRebuildRegistersModWindowsBeforeStockArtCaching() throws Exception {
        List<String> events = new ArrayList<>();
        RecordingPatternAtlas atlas = new RecordingPatternAtlas(events);
        GraphicsManager graphics = mock(GraphicsManager.class);
        when(graphics.getPatternAtlas()).thenReturn(atlas);
        EngineContext engine = engineContext(graphics);
        ObjectArtProvider provider = mock(ObjectArtProvider.class);
        when(provider.getRegularPatternCount()).thenReturn(1);
        when(provider.ensurePatternsCached(any(), anyInt())).thenAnswer(invocation -> {
            events.add("cache");
            assertDoesNotThrow(() -> atlas.cachePatternHeadless(new Pattern(), MOD_BASE));
            return ((Integer) invocation.getArgument(1)) + 1;
        });
        GameModule module = mock(GameModule.class);
        when(module.getObjectArtProvider()).thenReturn(provider);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        WorldSession world = new WorldSession(module);
        PatternWindowSessionState.install(world, new ModPatternWindowAllocator(List.of(
                new ModPatternWindowAllocator.Request("owner-one", 1)), MOD_BASE));

        GameplayModeContext gameplay = new GameplayModeContext(world);
        GameplaySessionFactory.attachManagers(gameplay, engine);
        gameplay.getLevelManager().gameModule = module;
        invokeInitObjectArt(gameplay.getLevelManager());
        invokeInitObjectArt(gameplay.getLevelManager());

        EditorModeContext editor = new EditorSessionFactory().create(world, engine);
        editor.getLevelManager().gameModule = module;
        invokeInitObjectArt(editor.getLevelManager());
        editor.destroy();

        GameplayModeContext resumed = new GameplayModeContext(world);
        GameplaySessionFactory.attachManagers(resumed, engine);
        resumed.getLevelManager().gameModule = module;
        invokeInitObjectArt(resumed.getLevelManager());

        List<String> oneCycle = List.of(
                "clear", "register:mod:owner-one", "register:Objects", "cache");
        List<String> expected = new ArrayList<>();
        for (int cycle = 0; cycle < 4; cycle++) expected.addAll(oneCycle);
        assertEquals(expected, events);
        assertEquals(1, PatternWindowSessionState.of(world).totalWindows());
    }

    private static void invokeInitObjectArt(LevelManager manager) throws Exception {
        Method method = LevelManager.class.getDeclaredMethod("initObjectArt");
        method.setAccessible(true);
        method.invoke(manager);
    }

    private static EngineContext engineContext(GraphicsManager graphics) {
        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn(320);
        when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
        return new EngineContext(configuration, graphics, mock(AudioManager.class),
                mock(RomManager.class), mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class), mock(PlaybackDebugManager.class),
                mock(RomDetectionService.class), mock(CrossGameFeatureProvider.class));
    }

    private static final class RecordingPatternAtlas extends PatternAtlas {
        private final List<String> events;

        private RecordingPatternAtlas(List<String> events) {
            super(256, 256);
            this.events = events;
        }

        @Override public void clearRanges() {
            events.add("clear");
            super.clearRanges();
        }

        @Override public void registerRange(int base, int size, String category) {
            events.add("register:" + category);
            super.registerRange(base, size, category);
        }
    }
}
