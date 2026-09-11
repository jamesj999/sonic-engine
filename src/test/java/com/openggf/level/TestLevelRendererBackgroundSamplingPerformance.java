package com.openggf.level;

import com.openggf.Engine;
import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.RomDetectionService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.level.render.BackgroundRenderer;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.util.IntIndexedView;
import com.openggf.util.ShortIndexedView;
import com.sun.management.ThreadMXBean;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_MODELVIEW;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_PROJECTION;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glLoadMatrixf;
import static org.lwjgl.opengl.GL11.glMatrixMode;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

class TestLevelRendererBackgroundSamplingPerformance {
    private static final String MEASUREMENT_PROPERTY = "openggf.measure.backgroundSampling";
    private static final String LIVE_CAPTURE_PROPERTY = "openggf.capture.backgroundSampling";
    private static final String LIVE_CAPTURE_LABEL_PROPERTY =
            "openggf.capture.backgroundSampling.label";
    private static final int WARMUP_RENDERS = 10_000;
    private static final int RENDERS_PER_BATCH = 10_000;
    private static final int BATCH_COUNT = 7;
    private static final int LIVE_WIDTH = 320;
    private static final int LIVE_HEIGHT = 224;
    private static final List<com.openggf.graphics.GLCommand> NO_COLLISION_COMMANDS = List.of();

    @Test
    void stationaryPositiveScrollAndNegativeYRetainExactSamplingOutput() {
        assertCapture(new Capture(0, 0, 0, 0, 0, 0, 3),
                renderCapture(0, 0, 0, 0, 0, 3));
        assertCapture(new Capture(-48, 16, 2, 0, 4, 1, 7),
                renderCapture(48, 0, 18, 4, 1, 7));
        assertCapture(new Capture(16, 16, 14, 0, 11, 6, 13),
                renderCapture(-16, -48, -18, 11, 6, 13));
    }

    @Test
    void deferredCommandsRetainAnchorsAndRingGenerationAfterSourceMutation() {
        RenderHarness harness = new RenderHarness(-16, -48, -18, 11, 6, 13);

        harness.enqueue();
        harness.tilemapManager.setSamplingSource(400, 96);
        harness.tilemapRenderer.setCapturedState(27, 19, 13);
        Capture capture = harness.executeCapture();

        assertCapture(new Capture(16, 16, 14, 0, 11, 6, 13), capture);
    }

    @Test
    void postWarmupRenderSamplingAllocationProbe() {
        Assumptions.assumeTrue(Boolean.getBoolean(MEASUREMENT_PROPERTY),
                () -> "enable with -D" + MEASUREMENT_PROPERTY + "=true");
        ThreadMXBean bean = allocationBeanOrSkip();
        RenderHarness harness = new RenderHarness(-16, -48, -18, 11, 6, 13);

        for (int render = 0; render < WARMUP_RENDERS; render++) {
            harness.enqueueAndDiscard();
        }

        long[] allocatedBytes = new long[BATCH_COUNT];
        long[] elapsedNanos = new long[BATCH_COUNT];
        long threadId = Thread.currentThread().threadId();
        for (int batch = 0; batch < BATCH_COUNT; batch++) {
            long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
            long nanosBefore = System.nanoTime();
            for (int render = 0; render < RENDERS_PER_BATCH; render++) {
                harness.enqueueAndDiscard();
            }
            elapsedNanos[batch] = System.nanoTime() - nanosBefore;
            allocatedBytes[batch] = bean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        }

        long allocatedMedian = median(allocatedBytes);
        long elapsedMedian = median(elapsedNanos);
        System.out.printf("background sampling allocatedBytes=%s elapsedNanos=%s "
                        + "medians=%d bytes/%d ns bytesPerRender=%.3f%n",
                Arrays.toString(allocatedBytes), Arrays.toString(elapsedNanos),
                allocatedMedian, elapsedMedian,
                (double) allocatedMedian / RENDERS_PER_BATCH);
        assertTrue(allocatedMedian <= 32L * RENDERS_PER_BATCH,
                "the hot render path must not allocate its two BackgroundTilemapSampling records");
    }

    @Test
    void captureLiveBackgroundSamplingScenes() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(LIVE_CAPTURE_PROPERTY),
                () -> "enable with -D" + LIVE_CAPTURE_PROPERTY + "=true");
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null, "Sonic 2 ROM unavailable");
        String label = System.getProperty(LIVE_CAPTURE_LABEL_PROPERTY, "capture");
        Path outputDirectory = TestSessionOutputPaths.diagnostics("perf-captures").resolve(label);
        Files.createDirectories(outputDirectory);

        long window = NULL;
        Rom rom = null;
        try {
            GLFWErrorCallback.createPrint(System.err).set();
            assertTrue(glfwInit(), "GLFW must initialize against the live display");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
            window = glfwCreateWindow(
                    LIVE_WIDTH, LIVE_HEIGHT, "Background sampling capture", NULL, NULL);
            assertTrue(window != NULL, "hidden live-display GLFW window must be created");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            GraphicsManager.destroyForReinit();
            GraphicsManager graphics = GraphicsManager.getInstance();
            graphics.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL);
            configureProjection(graphics);
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

            rom = new Rom();
            assertTrue(rom.open(romFile.getAbsolutePath()), "Sonic 2 ROM must open");
            GameModuleRegistry.detectAndSetModule(rom);
            RomManager.getInstance().setRom(rom);
            SonicConfigurationService configuration = SonicConfigurationService.getInstance();
            configuration.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
            configuration.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

            captureLiveScene(outputDirectory.resolve("stationary.png"), 3568, 624, null);
            captureLiveScene(outputDirectory.resolve("positive-scroll.png"), 3584, 624, null);
            captureLiveScene(outputDirectory.resolve("negative-y.png"), 3568, 624, (short) -18);
        } finally {
            SessionManager.clear();
            if (rom != null) {
                rom.close();
            }
            GraphicsManager.destroyForReinit();
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
        }
    }

    private static void configureProjection(GraphicsManager graphics) {
        glViewport(0, 0, LIVE_WIDTH, LIVE_HEIGHT);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        float[] matrix = new float[16];
        new Matrix4f().identity().ortho2D(0, LIVE_WIDTH, 0, LIVE_HEIGHT).get(matrix);
        glLoadMatrixf(matrix);
        graphics.setProjectionMatrixBuffer(matrix.clone());
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        graphics.setViewport(0, 0, LIVE_WIDTH, LIVE_HEIGHT);
    }

    private static void captureLiveScene(
            Path output, int playerX, int playerY, Short forcedBackgroundY) throws Exception {
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        GameServices.sprites().resetState();
        GameServices.camera().resetState();
        GameServices.parallax().resetState();

        Sonic player = new Sonic("sonic", (short) playerX, (short) playerY);
        GameServices.sprites().addSprite(player);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(player);
        camera.setFrozen(false);

        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(levelManager);
        player.setX((short) playerX);
        player.setY((short) playerY);
        camera.updatePosition(true);
        GameServices.parallax().update(
                0, 0, camera, 0, 0, levelManager.getCurrentLevel());
        if (forcedBackgroundY != null) {
            Field field = ParallaxManager.class.getDeclaredField("vscrollFactorBG");
            field.setAccessible(true);
            field.setShort(GameServices.parallax(), forcedBackgroundY);
        }
        levelManager.updateObjectPositions();
        GameServices.sprites().updateWithoutInput();

        levelManager.setClearColor();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        levelManager.drawWithSpritePriority(GameServices.sprites());
        GraphicsManager.getInstance().flush();
        glFinish();

        RgbaImage image = ScreenshotCapture.captureFramebuffer(LIVE_WIDTH, LIVE_HEIGHT);
        ScreenshotCapture.savePNG(image, output);
        System.out.printf("background live capture path=%s pixelHash=%d camera=%d,%d "
                        + "bgY=%d window=%dx%d scale=1 effects=default seed=none%n",
                output.toAbsolutePath(), Arrays.hashCode(image.pixels()),
                camera.getX(), camera.getY(), GameServices.parallax().getVscrollFactorBG(),
                LIVE_WIDTH, LIVE_HEIGHT);
    }

    private static Capture renderCapture(int sourceX, int sourceY, int alignedY,
                                         int ringBaseX, int ringBaseY, int generation) {
        RenderHarness harness = new RenderHarness(
                sourceX, sourceY, alignedY, ringBaseX, ringBaseY, generation);
        harness.enqueue();
        return harness.executeCapture();
    }

    private static void assertCapture(Capture expected, Capture actual) {
        assertEquals(expected, actual);
    }

    private static long median(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0,
                "thread allocation accounting unavailable");
        return bean;
    }

    private record Capture(int shaderScrollMidpoint,
                           int tilePassWorldOffsetY,
                           int shaderVOffset,
                           int perLineSampleYOffset,
                           int ringBaseX,
                           int ringBaseY,
                           int contentGeneration) {
    }

    private static final class RenderHarness {
        private final RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        private final SamplingParallaxManager parallax;
        private final SamplingTilemapManager tilemapManager;
        private final RecordingTilemapRenderer tilemapRenderer;
        private final LevelRenderer renderer;

        private RenderHarness(int sourceX, int sourceY, int alignedY,
                              int ringBaseX, int ringBaseY, int generation) {
            this.parallax = new SamplingParallaxManager(alignedY);
            this.tilemapManager = new SamplingTilemapManager(
                    graphics, sourceX, sourceY);
            this.tilemapRenderer = graphics.tilemapRenderer;
            tilemapRenderer.setCapturedState(ringBaseX, ringBaseY, generation);
            TestLevelManager manager = new TestLevelManager(
                    graphics, parallax, new NoWaterSystem(), tilemapManager);
            manager.level = new FixtureLevel();
            this.renderer = new LevelRenderer(manager);
        }

        private void enqueue() {
            renderer.renderBackgroundShader(NO_COLLISION_COMMANDS);
            assertEquals(3, graphics.commandCount);
        }

        private void enqueueAndDiscard() {
            renderer.renderBackgroundShader(NO_COLLISION_COMMANDS);
            graphics.discardCommands();
        }

        private Capture executeCapture() {
            graphics.executeCommands();
            return new Capture(
                    graphics.backgroundRenderer.shaderScrollMidpoint,
                    tilemapRenderer.worldOffsetY,
                    graphics.backgroundRenderer.vOffset,
                    Math.round(tilemapRenderer.perLineSampleYOffset),
                    tilemapRenderer.ringBaseX,
                    tilemapRenderer.ringBaseY,
                    tilemapRenderer.ringGeneration);
        }
    }

    private static final class TestLevelManager extends LevelManager {
        private static final Palette.Color BACKDROP =
                new Palette.Color((byte) 17, (byte) 34, (byte) 51);

        private TestLevelManager(RecordingGraphicsManager graphics,
                                 SamplingParallaxManager parallax,
                                 NoWaterSystem water,
                                 SamplingTilemapManager tilemapManager) {
            super(camera(), mock(SpriteManager.class), parallax, null, water,
                    new GameStateManager(), engine(graphics), new WorldSession(mock(GameModule.class)));
            this.tilemapManager = tilemapManager;
            this.cachedScreenWidth = 320;
            this.cachedScreenHeight = 224;
        }

        @Override
        void ensureBackgroundTilemapData() {
        }

        @Override
        boolean applyBackgroundTilemapWindowSelection(int bgCameraX) {
            return false;
        }

        @Override
        boolean shouldSuppressUnderwaterPalette(int zoneId, int actId) {
            return false;
        }

        @Override
        public int getFeatureZoneId() {
            return 0;
        }

        @Override
        public int getFeatureActId() {
            return 0;
        }

        @Override
        Palette.Color resolveLevelBackdropColor() {
            return BACKDROP;
        }

        private static Camera camera() {
            SonicConfigurationService configuration = configuration();
            Camera camera = new Camera(configuration);
            camera.setY((short) 0);
            return camera;
        }

        private static EngineContext engine(GraphicsManager graphics) {
            return new EngineContext(
                    configuration(),
                    graphics,
                    mock(AudioManager.class),
                    mock(RomManager.class),
                    mock(PerformanceProfiler.class),
                    mock(DebugOverlayManager.class),
                    mock(PlaybackDebugManager.class),
                    mock(RomDetectionService.class),
                    mock(CrossGameFeatureProvider.class));
        }

        private static SonicConfigurationService configuration() {
            SonicConfigurationService configuration = mock(SonicConfigurationService.class);
            when(configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn(320);
            when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
            when(configuration.getShort(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn((short) 320);
            when(configuration.getShort(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn((short) 224);
            when(configuration.getString(SonicConfiguration.WIDESCREEN_DEADZONE_MODE)).thenReturn("proportional");
            return configuration;
        }
    }

    private static final class SamplingParallaxManager extends ParallaxManager {
        private final int alignedY;
        private final int[] hScroll = new int[224];

        private SamplingParallaxManager(int alignedY) {
            this.alignedY = alignedY;
        }

        @Override
        public int[] getHScrollForShader() {
            return hScroll;
        }

        @Override
        public short[] getVScrollPerLineBGForShader() {
            return null;
        }

        @Override
        public short[] getVScrollPerColumnBGForShader() {
            return null;
        }

        @Override
        public int getBgCameraX() {
            return 0;
        }

        @Override
        public int getBgPeriodWidth() {
            return 512;
        }

        @Override
        public short getVscrollFactorBG() {
            return (short) alignedY;
        }
    }

    private static final class SamplingTilemapManager extends LevelTilemapManager {
        private int sourceX;
        private int sourceY;

        private SamplingTilemapManager(GraphicsManager graphics, int sourceX, int sourceY) {
            super(null, graphics, null);
            setSamplingSource(sourceX, sourceY);
        }

        private void setSamplingSource(int sourceX, int sourceY) {
            this.sourceX = sourceX;
            this.sourceY = sourceY;
        }

        @Override
        public int getBackgroundTilemapWidthTiles() {
            return 64;
        }

        @Override
        int getBackgroundTilemapSourceX() {
            return sourceX;
        }

        @Override
        int getBackgroundTilemapSourceY() {
            return sourceY;
        }
    }

    private static final class NoWaterSystem extends WaterSystem {
        @Override
        public boolean hasWater(int zoneId, int actId) {
            return false;
        }
    }

    private static final class RecordingGraphicsManager extends GraphicsManager {
        private final RecordingTilemapRenderer tilemapRenderer = new RecordingTilemapRenderer();
        private final RecordingBackgroundRenderer backgroundRenderer =
                new RecordingBackgroundRenderer(this);
        private final GLCommandable[] commands = new GLCommandable[3];
        private int commandCount;

        @Override
        public void registerCommand(GLCommandable command) {
            commands[commandCount++] = command;
        }

        @Override
        public BackgroundRenderer getBackgroundRenderer() {
            return backgroundRenderer;
        }

        @Override
        public TilemapGpuRenderer getTilemapGpuRenderer() {
            return tilemapRenderer;
        }

        @Override
        public Integer getCombinedPaletteTextureId() {
            return 1;
        }

        @Override
        public Integer getPatternAtlasTextureId() {
            return 2;
        }

        @Override
        public Integer getUnderwaterPaletteTextureId() {
            return null;
        }

        @Override
        public int getPatternAtlasWidth() {
            return 128;
        }

        @Override
        public int getPatternAtlasHeight() {
            return 128;
        }

        private void executeCommands() {
            for (int index = 0; index < commandCount; index++) {
                commands[index].execute(0, 0, 320, 224);
                commands[index] = null;
            }
            commandCount = 0;
        }

        private void discardCommands() {
            for (int index = 0; index < commandCount; index++) {
                commands[index].discard();
                commands[index] = null;
            }
            commandCount = 0;
        }
    }

    private static final class RecordingBackgroundRenderer extends BackgroundRenderer {
        private int shaderScrollMidpoint;
        private int vOffset;

        private RecordingBackgroundRenderer(GraphicsManager graphics) {
            super(graphics);
        }

        @Override
        public void setBackdropColor(float r, float g, float b) {
        }

        @Override
        public void ensureCapacity(int width, int height) {
        }

        @Override
        public void beginTilePass(int width, int height, boolean clearColor) {
        }

        @Override
        public void endTilePass() {
        }

        @Override
        public void setShimmerState(int frameCounter, int style, float waterlineScreenY) {
        }

        @Override
        public void renderWithScrollWide(IntIndexedView hScrollData,
                                         ShortIndexedView vScrollData,
                                         ShortIndexedView vScrollColumnData,
                                         int scrollMidpoint,
                                         int extraBuffer,
                                         int vOffset,
                                         boolean perLineScroll) {
            this.shaderScrollMidpoint = scrollMidpoint;
            this.vOffset = vOffset;
        }
    }

    private static final class RecordingTilemapRenderer extends TilemapGpuRenderer {
        private int capturedRingBaseX;
        private int capturedRingBaseY;
        private int capturedGeneration;
        private int ringBaseX;
        private int ringBaseY;
        private int ringGeneration;
        private int worldOffsetY;
        private float perLineSampleYOffset;

        private void setCapturedState(int ringBaseX, int ringBaseY, int generation) {
            this.capturedRingBaseX = ringBaseX;
            this.capturedRingBaseY = ringBaseY;
            this.capturedGeneration = generation;
        }

        @Override
        public BackgroundRenderState captureBackgroundRenderState() {
            return new BackgroundRenderState(
                    capturedRingBaseX, capturedRingBaseY, capturedGeneration);
        }

        @Override
        public boolean isBackgroundContentGenerationCurrent(int generation) {
            return generation == capturedGeneration;
        }

        @Override
        public int getBackgroundContentGeneration() {
            return capturedGeneration;
        }

        @Override
        public void setBackgroundRenderRingBaseOverride(
                int ringBaseX, int ringBaseY, int contentGeneration) {
            this.ringBaseX = ringBaseX;
            this.ringBaseY = ringBaseY;
            this.ringGeneration = contentGeneration;
        }

        @Override
        public void enablePerLineScroll(
                int textureId, float screenHeight, float vdpWrapWidth,
                float nametableBaseTile, float sampleYOffsetPx) {
            this.perLineSampleYOffset = sampleYOffsetPx;
        }

        @Override
        public void render(Layer layer,
                           int screenWidth,
                           int screenHeight,
                           int viewportX,
                           int viewportY,
                           int viewportWidth,
                           int viewportHeight,
                           float worldOffsetX,
                           float worldOffsetY,
                           int atlasWidth,
                           int atlasHeight,
                           int atlasTextureId,
                           int paletteTextureId,
                           int underwaterPaletteTextureId,
                           int priorityPass,
                           boolean layerIsBackground,
                           boolean verticalWrap,
                           boolean useUnderwaterPalette,
                           float waterlineScreenY) {
            this.worldOffsetY = Math.round(worldOffsetY);
        }
    }

    private static final class FixtureLevel extends AbstractLevel {
        private FixtureLevel() {
            super(0);
            palettes = new Palette[] {new Palette(), new Palette(), new Palette(), new Palette()};
            map = new Map(2, 1, 1);
        }
    }
}
