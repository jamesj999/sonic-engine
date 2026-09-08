package com.openggf.widescreen;

import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameServices;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.RomDetectionService;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.sonic1.scroll.SwScrlGhz;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenDataLoader;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenManager;
import com.openggf.game.sonic2.titlescreen.TitleScreenDataLoader;
import com.openggf.game.sonic2.titlescreen.TitleScreenManager;
import com.openggf.game.sonic3k.titlescreen.Sonic3kTitleScreenDataLoader;
import com.openggf.game.sonic3k.titlescreen.Sonic3kTitleScreenManager;
import com.openggf.game.launch.LaunchProfileStore;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PixelFont;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.graphics.TexturedQuadRenderer;
import com.openggf.level.PatternDesc;
import com.openggf.level.Palette;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real ordinary-title render-command characterization at the supported widths.
 *
 * <p>The recording graphics and PNG renderer retain the production render paths
 * while stopping before OpenGL. Expected origins are hand-derived from the
 * native 320-pixel format, rather than calculated through production helpers.
 */
@ExtendWith(SingletonResetExtension.class)
class TestOrdinaryTitleScreenCommandEvidence {

    private static final int[] WIDTHS = {320, 400, 528};
    private RecordingGraphics graphics;

    @BeforeEach
    void setUpEngineServices() {
        graphics = new RecordingGraphics();
        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
        EngineServices.configure(new EngineContext(configuration, graphics,
                mock(AudioManager.class), mock(RomManager.class),
                mock(PerformanceProfiler.class), mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class), mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class)));
    }

    @AfterEach
    void restoreEngineServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void masterTitleDrawExpandsBackdropAndCentersForegroundAtEveryWidth() throws Exception {
        SonicConfigurationService configuration = GameServices.configuration();
        MasterTitleScreen title = new MasterTitleScreen(configuration,
                mock(LaunchProfileStore.class));
        RecordingQuadRenderer renderer = new RecordingQuadRenderer();
        RecordingFont font = new RecordingFont();
        setField(title, "renderer", renderer);
        setField(title, "font", font);
        setField(title, "bgTextureId", 1);
        setField(title, "solidWhiteTextureId", 2);
        setField(title, "titleTextId", 3);
        setField(title, "titleTextWidth", 100);
        setField(title, "titleTextHeight", 20);
        setField(title, "state", MasterTitleScreen.State.ACTIVE);

        Method drawForRecording = MasterTitleScreen.class.getDeclaredMethod("drawForRecording");
        drawForRecording.setAccessible(true);
        for (int width : WIDTHS) {
            title.setViewportWidth(width);
            renderer.clear();
            font.clear();
            drawForRecording.invoke(title);

            assertEquals(new Quad(1, 0, 0, width, 224), renderer.quads().get(0));
            assertEquals(new Quad(3, (width - 31) / 2, 216, 31, 6), renderer.quads().get(1));
            assertTrue(font.texts().stream().anyMatch(text -> text.text().equals("< >  Select    Enter  Confirm")
                            && text.x() == (width - text.text().length() * 9) / 2),
                    "navigation text must be centered by the real Master draw path at " + width);
        }
    }

    @Test
    void sonic1PublicDrawDispatchesPlaneAndFadeCommandsAtLiveWidth() throws Exception {
        for (int width : WIDTHS) {
            Sonic1TitleScreenManager manager = new Sonic1TitleScreenManager();
            Sonic1TitleScreenDataLoader loader = field(manager, "dataLoader");
            setField(loader, "planeBMap", filledMap(128, 28));
            setField(loader, "planeBWidth", 128);
            setField(loader, "planeBHeight", 28);
            setField(loader, "planeAMap", filledMap(34, 22, 0x200 + 1));
            SwScrlGhz scroll = mock(SwScrlGhz.class);
            when(scroll.getVscrollFactorBG()).thenReturn((short) 0);
            setField(manager, "scrollHandler", scroll);
            setField(manager, "state", TitleScreenProvider.State.ACTIVE);
            setField(manager, "spritesInitialized", true);
            setWidth(width);
            graphics.clear();
            manager.draw();
            assertEquals((width == 320 ? 42 : width == 400 ? 52 : 68) * 28 + 34 * 22 + 2,
                    graphics.patterns().size(), "S1 public draw command count at " + width);
            assertEquals(0, graphics.patterns().get(0).x(),
                    "S1 expanded background starts at the viewport edge at " + width);
            assertPattern(graphics.patterns().get((width == 320 ? 42 : width == 400 ? 52 : 68) * 28),
                    PatternAtlasRange.SONIC1_TITLE_FOREGROUND.base() + 1, 0x0201);
            assertEquals(width == 320 ? 0 : width == 400 ? 40 : 104,
                    graphics.patterns().get((width == 320 ? 42 : width == 400 ? 52 : 68) * 28).x() - 24,
                    "S1 foreground offset at " + width);
        }

        for (int width : WIDTHS) {
            Sonic1TitleScreenManager manager = new Sonic1TitleScreenManager();
            Sonic1TitleScreenDataLoader loader = field(manager, "dataLoader");
            setField(manager, "state", TitleScreenProvider.State.INTRO_TEXT_FADE_IN);
            setField(manager, "introTextTimer", 4);
            Palette target = new Palette();
            target.colors[0].r = target.colors[0].g = target.colors[0].b = (byte) 0xEE;
            setField(loader, "titlePaletteLines", new Palette[]{target, target, target, target});
            setField(manager, "creditTextCached", true);
            setWidth(width);
            graphics.clear();
            manager.draw();
            assertEquals(4, graphics.palettes.size(),
                    "S1 intro fade uploads every palette line at " + width);
            for (Palette uploaded : graphics.palettes) {
                assertEquals(0, Byte.toUnsignedInt(uploaded.colors[0].r));
                assertEquals(0, Byte.toUnsignedInt(uploaded.colors[0].g));
                assertTrue(Byte.toUnsignedInt(uploaded.colors[0].b) > 0,
                        "the ROM fade raises blue first, independently of viewport width");
            }
            assertTrue(graphics.rects().isEmpty(), "palette fades do not draw an alpha overlay");
        }
    }

    @Test
    void sonic2PublicDrawDispatchesBackgroundRippleAndCurvedOcclusion() throws Exception {
        for (int width : WIDTHS) {
            TitleScreenManager manager = new TitleScreenManager();
            TitleScreenDataLoader loader = field(manager, "dataLoader");
            setField(loader, "planeBMap", filledMap(64, 28));
            setField(loader, "planeAMap", filledMap(40, 28));
            setField(loader, "dataLoaded", true);
            setField(manager, "state", TitleScreenProvider.State.ACTIVE);
            setField(manager, "introComplete", true);
            // ACTIVE follows Obj0E_Sonic_LoadPalette; palette-zero Plane A
            // words (including this synthetic map) are visible at this boundary.
            setField(manager, "sonicPaletteLoaded", true);
            setWidth(width);
            graphics.clear();
            manager.draw();
            int columns = width == 320 ? 40 : width == 400 ? 50 : 66;
            assertTrue(graphics.patterns().size() > 24 * columns + 8,
                    "S2 public draw must dispatch all background passes at " + width);
            assertPattern(graphics.patterns().get(0), PatternAtlasRange.RESULTS_SCREENS.base() + 1, 1);
            assertEquals(16 + 160, graphics.scissors().size(),
                    "S2 public draw scissor count at " + width);
            assertEquals(16 + 160, graphics.scissorCaptures().size(),
                    "S2 public draw scissor restoration count at " + width);
            for (int eventIndex = 0; eventIndex < graphics.scissorEvents().size(); eventIndex++) {
                ScissorEvent event = graphics.scissorEvents().get(eventIndex);
                assertEquals(eventIndex % 2 == 0, event.enabled(),
                        "S2 scissor enable/disable order at event " + eventIndex + " width " + width);
                if (!event.enabled()) {
                    assertEquals(null, event.rectangle(), "S2 disable restores scissor state");
                }
            }
            for (int line = 0; line < 16; line++) {
                assertEquals(new Scissor(0, 31 - line, width, 1),
                        graphics.scissors().get(line), "S2 ripple tuple at line " + line + " width " + width);
            }
            int curvedStart = 16;
            for (int column = 0; column < 160; column++) {
                int mdX = column * 2;
                int startY = logoOcclusionStart(mdX + 1);
                assertEquals(new Scissor((width - 320) / 2 + mdX, 0, 2, 224 - startY),
                        graphics.scissors().get(curvedStart + column),
                        "S2 curved tuple at column " + column + " width " + width);
                ScissorCapture capture = graphics.scissorCaptures().get(curvedStart + column);
                assertTrue(capture.patternEnd() > capture.patternStart(),
                        "curved scissor must correspond to emitted pattern commands");
                assertTrue(graphics.patterns().subList(capture.patternStart(), capture.patternEnd()).stream()
                                .allMatch(pattern -> pattern.x() <= (width - 320) / 2 + mdX
                                        && (width - 320) / 2 + mdX < pattern.x() + 8),
                        "curved scissor/pattern X correspondence at column " + column);
            }
            int planeAStart = 24 * columns + 8 + 16 * (columns + 2);
            assertEquals(width == 320 ? 0 : width == 400 ? 40 : 104,
                    graphics.patterns().get(planeAStart).x(), "S2 foreground offset at " + width);
        }
    }

    @Test
    void sonic2IntroFadeCoversLiveViewportThroughPublicDraw() throws Exception {
        for (int width : WIDTHS) {
            TitleScreenManager manager = new TitleScreenManager();
            TitleScreenDataLoader loader = field(manager, "dataLoader");
            setField(loader, "dataLoaded", true);
            setField(manager, "state", TitleScreenProvider.State.INTRO_TEXT_FADE_IN);
            setField(manager, "creditTextCached", true);
            setWidth(width);
            graphics.clear();
            manager.draw();
            assertEquals(width, graphics.rects().getLast().width(),
                    "S2 intro fade must cover the live width at " + width);
        }
    }

    @Test
    void sonic3kPublicDrawDispatchesAnimationAndInteractivePlanes() throws Exception {
        for (int width : WIDTHS) {
            Sonic3kTitleScreenManager manager = sonic3kFixture();
            setField(manager, "phase", enumFieldValue(manager, "phase", "SEGA_FADE_IN"));
            setField(manager, "phaseTimer", 0);
            setWidth(width);
            setField(manager, "phase", enumFieldValue(manager, "phase", "SEGA_FADE_IN"));
            graphics.clear();
            manager.draw();
            assertEquals(40 * 28, graphics.patterns().size(), "S3K animation remains one 40x28 region at " + width);
            assertPattern(graphics.patterns().get(0), PatternAtlasRange.S3K_TITLE_SCREEN_ANIMATION.base() + 1, 1);
            assertEquals(width == 320 ? 0 : width == 400 ? 40 : 104,
                    graphics.patterns().get(0).x(), "S3K animation offset at " + width);
            assertEquals(width, graphics.rects().getLast().width(),
                    "S3K animation fade covers live width at " + width);

            graphics.clear();
            setField(manager, "phase", enumFieldValue(manager, "phase", "INTERACTIVE"));
            manager.draw();
            assertEquals(2 * 40 * 28, graphics.patterns().size(),
                    "S3K interactive scene keeps centered native Plane B/A at " + width);
            assertEquals(width == 320 ? 0 : width == 400 ? 40 : 104,
                    graphics.patterns().get(0).x(), "S3K Plane B offset at " + width);
            assertEquals(width == 320 ? 0 : width == 400 ? 40 : 104,
                    graphics.patterns().get(40 * 28).x(), "S3K Plane A offset at " + width);
        }
    }

    @Test
    void sonic3kWhiteFlashAndFadeOutCoverLiveViewportThroughPublicDraw() throws Exception {
        for (int width : WIDTHS) {
            Sonic3kTitleScreenManager manager = sonic3kFixture();
            setWidth(width);
            setField(manager, "phase", enumFieldValue(manager, "phase", "WHITE_FLASH"));
            setField(manager, "phaseTimer", 2);
            graphics.clear();
            manager.draw();
            assertEquals(width, graphics.rects().getLast().width(),
                    "S3K white flash must cover the live width at " + width);

            setField(manager, "phase", enumFieldValue(manager, "phase", "FADE_OUT"));
            setField(manager, "phaseTimer", 4);
            graphics.clear();
            manager.draw();
            assertEquals(width, graphics.rects().getLast().width(),
                    "S3K fade-out must cover the live width at " + width);
        }
    }

    @Test
    void masterOverlayAndErrorDrawPathsCoverLiveViewport() throws Exception {
        for (int width : WIDTHS) {
            MasterTitleScreen title = new MasterTitleScreen(GameServices.configuration(),
                    mock(LaunchProfileStore.class));
            RecordingQuadRenderer renderer = new RecordingQuadRenderer();
            RecordingFont font = new RecordingFont();
            setField(title, "renderer", renderer);
            setField(title, "font", font);
            setField(title, "bgTextureId", 1);
            setField(title, "solidWhiteTextureId", 2);
            setField(title, "titleTextId", 3);
            setField(title, "titleTextWidth", 100);
            setField(title, "titleTextHeight", 20);
            title.setViewportWidth(width);

            setField(title, "state", MasterTitleScreen.State.FADE_IN);
            invokeDrawForRecording(title);
            assertTrue(renderer.quads().stream().anyMatch(quad -> quad.texture() == 1
                            && quad.x() == 0 && quad.y() == 0 && quad.width() == width && quad.height() == 224),
                    "Master fade path must preserve full-width background at " + width);

            renderer.clear();
            setField(title, "state", MasterTitleScreen.State.ERROR_DISPLAY);
            invokeDrawForRecording(title);
            assertTrue(renderer.quads().stream().anyMatch(quad -> quad.texture() == 2
                            && quad.x() == 0 && quad.y() == 0 && quad.width() == width && quad.height() == 224),
                    "Master error path must preserve full-width overlay at " + width);
        }
    }

    private void setWidth(int width) {
        graphics.setProjectionWidth(width);
        graphics.setViewport(0, 0, width, 224);
    }

    private Sonic3kTitleScreenManager sonic3kFixture() throws Exception {
        Sonic3kTitleScreenManager manager = new Sonic3kTitleScreenManager();
        Sonic3kTitleScreenDataLoader loader = field(manager, "dataLoader");
        int[][] animationMappings = field(loader, "animMappings");
        animationMappings[0] = filledMap(40, 28);
        animationMappings[12] = filledMap(40, 28);
        setField(loader, "backgroundMapping", filledMap(40, 28));
        setField(loader, "dataLoaded", true);
        return manager;
    }

    private static int[] filledMap(int width, int height) {
        return filledMap(width, height, 1);
    }

    private static int[] filledMap(int width, int height, int value) {
        int[] map = new int[width * height];
        java.util.Arrays.fill(map, value);
        return map;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static void invokeDrawForRecording(MasterTitleScreen title) throws Exception {
        Method drawForRecording = method(MasterTitleScreen.class, "drawForRecording");
        drawForRecording.invoke(title);
    }

    private static <T> T field(Object target, String name) throws Exception {
        return (T) fieldObject(target, name);
    }

    private static Object fieldObject(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object enumFieldValue(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        Class<?> enumType = field.getType();
        return Enum.valueOf((Class) enumType, value);
    }

    private record PatternIdentity(int raw, int patternIndex, int paletteIndex,
                                   boolean hFlip, boolean vFlip, boolean priority) { }
    private record PatternCommand(int patternId, PatternIdentity identity, int x, int y) { }
    private record Scissor(int x, int y, int width, int height) { }
    private record ScissorCapture(Scissor rectangle, int patternStart, int patternEnd) { }
    private record ScissorEvent(boolean enabled, Scissor rectangle) { }
    private record Rect(int x, int y, int width, int height) { }
    private record Quad(int texture, int x, int y, int width, int height) { }
    private record Text(String text, int x, int y) { }

    private static final class RecordingGraphics extends GraphicsManager {
        private final List<PatternCommand> patterns = new ArrayList<>();
        private final List<Scissor> scissors = new ArrayList<>();
        private final List<ScissorCapture> scissorCaptures = new ArrayList<>();
        private final List<ScissorEvent> scissorEvents = new ArrayList<>();
        private final List<Rect> rects = new ArrayList<>();
        private final List<Palette> palettes = new ArrayList<>();

        @Override
        public void cachePaletteTexture(Palette palette, int line) {
            palettes.add(palette);
        }
        private Scissor activeScissor;
        private int activeScissorPatternStart;

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            patterns.add(new PatternCommand(patternId, new PatternIdentity(desc.get(), desc.getPatternIndex(),
                    desc.getPaletteIndex(), desc.getHFlip(), desc.getVFlip(), desc.getPriority()), x, y));
        }

        @Override
        public void registerCommand(GLCommandable command) {
            if (command instanceof GLCommand glCommand
                    && glCommand.getCommandType() == GLCommand.CommandType.RECTI) {
                rects.add(new Rect(Math.round(glCommand.getX1()), 224 - Math.round(glCommand.getY1()),
                        intField(glCommand, "x2"), 224 - intField(glCommand, "y2")));
            }
        }

        @Override public void beginPatternBatch() { }
        @Override public void flushPatternBatch() { }
        @Override public void flushScreenSpace() { }

        @Override
        public void enableScissor(int x, int y, int width, int height) {
            if (activeScissor != null) {
                throw new AssertionError("scissor enabled while another scissor is active");
            }
            activeScissor = new Scissor(x, y, width, height);
            activeScissorPatternStart = patterns.size();
            scissors.add(activeScissor);
            scissorEvents.add(new ScissorEvent(true, activeScissor));
        }

        @Override
        public void disableScissor() {
            if (activeScissor == null) {
                throw new AssertionError("scissor disabled without a matching enable");
            }
            scissorCaptures.add(new ScissorCapture(activeScissor, activeScissorPatternStart, patterns.size()));
            scissorEvents.add(new ScissorEvent(false, null));
            activeScissor = null;
        }

        List<PatternCommand> patterns() { return List.copyOf(patterns); }
        List<Scissor> scissors() { return List.copyOf(scissors); }
        List<ScissorCapture> scissorCaptures() { return List.copyOf(scissorCaptures); }
        List<ScissorEvent> scissorEvents() { return List.copyOf(scissorEvents); }
        List<Rect> rects() { return List.copyOf(rects); }

        void clear() {
            if (activeScissor != null) {
                throw new AssertionError("recording cleared while scissor remained active");
            }
            patterns.clear();
            scissors.clear();
            scissorCaptures.clear();
            scissorEvents.clear();
            rects.clear();
            palettes.clear();
        }
    }

    private static void assertPattern(PatternCommand actual, int patternId, int descriptor) {
        assertEquals(patternId, actual.patternId());
        assertEquals(new PatternIdentity(descriptor, descriptor & 0x7FF,
                        (descriptor >> 13) & 0x3, (descriptor & 0x800) != 0,
                        (descriptor & 0x1000) != 0, (descriptor & 0x8000) != 0),
                actual.identity());
    }

    private static int logoOcclusionStart(int screenX) {
        double center = (320 - 1) * 0.5;
        double halfWidth = 104.0;
        double norm = Math.min(1.0, Math.abs((screenX - center) / halfWidth));
        return 13 * 8 + (int) Math.round(norm * norm * 16);
    }

    private static int intField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read recorded GL command", exception);
        }
    }

    private static final class RecordingQuadRenderer extends TexturedQuadRenderer {
        private final List<Quad> quads = new ArrayList<>();

        @Override
        public void drawTexture(int textureId, float x, float y, float width, float height) {
            quads.add(new Quad(textureId, (int) x, (int) y, (int) width, (int) height));
        }

        @Override
        public void drawTexture(int textureId, float x, float y, float width, float height,
                                float r, float g, float b, float a) {
            quads.add(new Quad(textureId, (int) x, (int) y, (int) width, (int) height));
        }

        List<Quad> quads() { return List.copyOf(quads); }
        void clear() { quads.clear(); }
    }

    private static final class RecordingFont extends PixelFont {
        private final List<Text> texts = new ArrayList<>();

        @Override
        public void drawText(String text, float x, float y, float scale,
                             float r, float g, float b, float a) {
            texts.add(new Text(text, Math.round(x), Math.round(y)));
        }

        List<Text> texts() { return List.copyOf(texts); }
        void clear() { texts.clear(); }
    }
}
