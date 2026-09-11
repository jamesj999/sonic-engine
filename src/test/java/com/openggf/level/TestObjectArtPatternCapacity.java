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
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.RomDetectionService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.WorldSession;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlas;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestObjectArtPatternCapacity {
    private GraphicsManager graphics;

    @BeforeEach
    void setUp() {
        graphics = GraphicsManager.getInstance();
        graphics.initHeadless();
        graphics.getPatternAtlas().clearRanges();
    }

    @Test
    void levelRegistersOneFullObjectGovernanceRange() throws Exception {
        RecordingProvider provider = new RecordingProvider(graphics, 12);
        LevelManager manager = manager(provider);

        invokeInitObjectArt(manager);

        assertEquals(List.of(new PatternAtlas.PatternRange(
                        PatternAtlasRange.OBJECTS.base(),
                        PatternAtlasRange.OBJECTS.size(),
                        PatternAtlasRange.OBJECTS.category())),
                graphics.getPatternAtlas().registeredRangesForTesting());
        assertEquals(1, provider.ensureCalls());
    }

    @Test
    void refreshRejectsOverflowBeforeAnyRendererCaches() {
        RecordingProvider provider = new RecordingProvider(
                graphics, PatternAtlasRange.OBJECTS.size() + 1);
        LevelManager manager = manager(provider);
        manager.objectRenderManager = new ObjectRenderManager(provider);

        assertThrows(IllegalStateException.class, manager::refreshObjectArtPatterns);

        assertEquals(0, provider.ensureCalls());
    }

    @Test
    void refreshRejectsNegativeCountBeforeAnyRendererCaches() {
        RecordingProvider provider = new RecordingProvider(graphics, -1);
        LevelManager manager = manager(provider);
        manager.objectRenderManager = new ObjectRenderManager(provider);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, manager::refreshObjectArtPatterns);

        assertEquals("Invalid regular object pattern count: -1", error.getMessage());
        assertEquals(0, provider.ensureCalls());
    }

    @Test
    void refreshRejectsHugeCountBeforeAdditionOrRendererCache() {
        RecordingProvider provider = new RecordingProvider(graphics, Integer.MAX_VALUE);
        LevelManager manager = manager(provider);
        manager.objectRenderManager = new ObjectRenderManager(provider);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, manager::refreshObjectArtPatterns);

        assertEquals("Object patterns exceed reserved atlas range: " + Integer.MAX_VALUE,
                error.getMessage());
        assertEquals(0, provider.ensureCalls());
    }

    @Test
    void successfulRefreshEndMatchesPreflightEnd() {
        RecordingProvider provider = new RecordingProvider(graphics, 24);
        LevelManager manager = manager(provider);
        manager.objectRenderManager = new ObjectRenderManager(provider);

        int end = manager.refreshObjectArtPatterns();

        assertEquals(PatternAtlasRange.OBJECTS.base() + 24, end);
        assertEquals(1, provider.ensureCalls());
    }

    @Test
    void refreshRejectsProviderEndThatDiffersFromPreflight() {
        RecordingProvider provider = new RecordingProvider(graphics, 24) {
            @Override
            public int ensurePatternsCached(GraphicsManager manager, int baseIndex) {
                super.ensurePatternsCached(manager, baseIndex);
                return baseIndex + getRegularPatternCount() - 1;
            }
        };
        LevelManager manager = manager(provider);
        manager.objectRenderManager = new ObjectRenderManager(provider);

        assertThrows(IllegalStateException.class, manager::refreshObjectArtPatterns);
        assertEquals(1, provider.ensureCalls());
    }

    @Test
    void repeatedRefreshKeepsRendererPatternBaseStable() {
        RecordingProvider provider = new RecordingProvider(graphics, 24);
        LevelManager manager = manager(provider);
        manager.objectRenderManager = new ObjectRenderManager(provider);

        int firstEnd = manager.refreshObjectArtPatterns();
        int firstBase = provider.renderer().getPatternBase();
        int secondEnd = manager.refreshObjectArtPatterns();

        assertEquals(firstEnd, secondEnd);
        assertEquals(PatternAtlasRange.OBJECTS.base(), firstBase);
        assertEquals(firstBase, provider.renderer().getPatternBase());
    }

    @Test
    void refreshFailsWhenObjectRenderManagerIsNotInitialized() {
        LevelManager manager = manager(null);

        assertThrows(IllegalStateException.class, manager::refreshObjectArtPatterns);
    }

    private LevelManager manager(ObjectArtProvider provider) {
        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn(320);
        when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
        EngineContext engine = new EngineContext(configuration, graphics, mock(AudioManager.class),
                mock(RomManager.class), mock(PerformanceProfiler.class), mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class), mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class));
        GameModule module = mock(GameModule.class);
        when(module.getObjectArtProvider()).thenReturn(provider);
        LevelManager manager = new LevelManager(mock(Camera.class), mock(SpriteManager.class),
                mock(ParallaxManager.class), mock(CollisionSystem.class), mock(WaterSystem.class),
                new GameStateManager(), engine, new WorldSession(module));
        manager.gameModule = module;
        return manager;
    }

    private static void invokeInitObjectArt(LevelManager manager) throws Exception {
        Method method = LevelManager.class.getDeclaredMethod("initObjectArt");
        method.setAccessible(true);
        try {
            method.invoke(manager);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static class RecordingProvider implements ObjectArtProvider {
        private final int patternCount;
        private final PatternSpriteRenderer renderer;
        private int ensureCalls;

        RecordingProvider(GraphicsManager graphics, int patternCount) {
            this.patternCount = patternCount;
            int rendererPatternCount = Math.max(0, Math.min(patternCount, 24));
            Pattern[] patterns = new Pattern[rendererPatternCount];
            for (int i = 0; i < patterns.length; i++) {
                patterns[i] = new Pattern();
            }
            renderer = new PatternSpriteRenderer(
                    new ObjectSpriteSheet(patterns, List.of(), 0, 1), graphics);
        }

        PatternSpriteRenderer renderer() {
            return renderer;
        }

        int ensureCalls() {
            return ensureCalls;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return renderer;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return null;
        }

        @Override
        public SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return null;
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return null;
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return null;
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return null;
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of("recording");
        }

        @Override
        public int getRegularPatternCount() {
            return patternCount;
        }

        @Override
        public int ensurePatternsCached(GraphicsManager manager, int baseIndex) {
            ensureCalls++;
            renderer.ensurePatternsCached(manager, baseIndex);
            return baseIndex + patternCount;
        }

        @Override
        public boolean isReady() {
            return renderer.isReady();
        }
    }
}
