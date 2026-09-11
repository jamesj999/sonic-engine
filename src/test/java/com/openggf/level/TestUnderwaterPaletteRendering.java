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
import com.openggf.graphics.RecordingUnderwaterPaletteUploadOps;
import com.openggf.graphics.RenderContext;
import com.openggf.graphics.WaterShaderProgram;
import com.openggf.graphics.color.DisplayColorProfile;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestUnderwaterPaletteRendering {
    private GraphicsManager graphics;

    @AfterEach
    void cleanup() {
        if (graphics != null) {
            graphics.clearPaletteTextures();
        }
        RenderContext.reset();
    }

    @Test
    void frameBoundaryResolvesOnceForBothShadersAndUploadsOnlyChangedFrames() {
        RenderContext.reset();
        graphics = spy(new GraphicsManager());
        graphics.initHeadless();
        RecordingUnderwaterPaletteUploadOps uploads = RecordingUnderwaterPaletteUploadOps.install(graphics);
        WaterShaderProgram scalarShader = mock(WaterShaderProgram.class);
        WaterShaderProgram instancedShader = mock(WaterShaderProgram.class);
        when(scalarShader.getUnderwaterPaletteLocation()).thenReturn(-1);
        when(instancedShader.getUnderwaterPaletteLocation()).thenReturn(-1);
        doReturn(scalarShader).when(graphics).getWaterShaderProgram();
        doReturn(instancedShader).when(graphics).getInstancedWaterShaderProgram();

        WaterSystem water = mock(WaterSystem.class);
        Palette[] underwater = {paletteWithColor(1, 100, 50, 25), new Palette(), new Palette(), new Palette()};
        when(water.getUnderwaterPalette(0, 0)).thenReturn(underwater);
        Palette normal = paletteWithColor(1, 200, 100, 50);
        Level level = mock(Level.class);
        when(level.getPalette(0)).thenReturn(normal);

        LevelManager levelManager = levelManager(graphics, water);
        levelManager.level = level;
        LevelRenderer renderer = new LevelRenderer(levelManager);

        levelManager.frameCounter = 10;
        renderer.getWaterShaderSetupCommand().execute(0, 0, 320, 224);
        renderer.getWaterShaderSetupCommand().execute(0, 0, 320, 224);
        assertEquals(1, uploads.uploadCount());
        verify(water, times(2)).getUnderwaterPalette(0, 0);
        verify(scalarShader, times(2)).setFrameCounter(10);
        verify(instancedShader, times(2)).setFrameCounter(10);

        levelManager.frameCounter = 11;
        renderer.getWaterShaderSetupCommand().execute(0, 0, 320, 224);
        assertEquals(1, uploads.uploadCount(), "an unchanged later frame must not upload");

        underwater[0].getColor(1).b++;
        levelManager.frameCounter = 12;
        renderer.getWaterShaderSetupCommand().execute(0, 0, 320, 224);
        assertEquals(2, uploads.uploadCount(), "a contributing mutation uploads once in its frame");
        verify(water, times(4)).getUnderwaterPalette(0, 0);
    }

    @Test
    void sameFrameInPlacePaletteMutationStillValidatesAndUploads() {
        RenderHarness harness = renderHarness();

        harness.executeFrame(20);
        harness.underwater()[0].getColor(1).b++;
        harness.executeFrame(20);

        assertEquals(2, harness.uploads().uploadCount());
    }

    @Test
    void sameFrameDisplayProfileChangeStillValidatesAndUploads() {
        RenderHarness harness = renderHarness();

        harness.executeFrame(30);
        graphics.setDisplayColorProfile(DisplayColorProfile.MD_ANALOG);
        harness.executeFrame(30);

        assertEquals(2, harness.uploads().uploadCount());
    }

    @Test
    void repeatedEarlierFrameCounterStillValidatesChangedContent() {
        RenderHarness harness = renderHarness();

        harness.executeFrame(40);
        harness.levelManager().frameCounter = 41;
        harness.underwater()[0].getColor(1).r++;
        harness.executeFrame(40);

        assertEquals(2, harness.uploads().uploadCount());
    }

    private RenderHarness renderHarness() {
        RenderContext.reset();
        graphics = spy(new GraphicsManager());
        graphics.initHeadless();
        RecordingUnderwaterPaletteUploadOps uploads = RecordingUnderwaterPaletteUploadOps.install(graphics);
        WaterShaderProgram scalarShader = mock(WaterShaderProgram.class);
        WaterShaderProgram instancedShader = mock(WaterShaderProgram.class);
        when(scalarShader.getUnderwaterPaletteLocation()).thenReturn(-1);
        when(instancedShader.getUnderwaterPaletteLocation()).thenReturn(-1);
        doReturn(scalarShader).when(graphics).getWaterShaderProgram();
        doReturn(instancedShader).when(graphics).getInstancedWaterShaderProgram();

        WaterSystem water = mock(WaterSystem.class);
        Palette[] underwater = {paletteWithColor(1, 100, 50, 25), new Palette(), new Palette(), new Palette()};
        when(water.getUnderwaterPalette(0, 0)).thenReturn(underwater);
        Level level = mock(Level.class);
        when(level.getPalette(0)).thenReturn(paletteWithColor(1, 200, 100, 50));
        LevelManager levelManager = levelManager(graphics, water);
        levelManager.level = level;
        return new RenderHarness(levelManager, new LevelRenderer(levelManager), underwater, uploads);
    }

    private record RenderHarness(LevelManager levelManager, LevelRenderer renderer,
                                 Palette[] underwater, RecordingUnderwaterPaletteUploadOps uploads) {
        private void executeFrame(int frameCounter) {
            levelManager.frameCounter = frameCounter;
            renderer.getWaterShaderSetupCommand().execute(0, 0, 320, 224);
        }
    }

    private static LevelManager levelManager(GraphicsManager graphics, WaterSystem water) {
        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn(320);
        when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
        EngineContext engine = new EngineContext(configuration, graphics, mock(AudioManager.class),
                mock(RomManager.class), mock(PerformanceProfiler.class), mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class), mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class));
        GameModule module = mock(GameModule.class);
        return new LevelManager(mock(Camera.class), mock(SpriteManager.class), mock(ParallaxManager.class),
                mock(CollisionSystem.class), water, new GameStateManager(), engine, new WorldSession(module));
    }

    private static Palette paletteWithColor(int index, int r, int g, int b) {
        Palette palette = new Palette();
        palette.setColor(index, new Palette.Color((byte) r, (byte) g, (byte) b));
        return palette;
    }
}
