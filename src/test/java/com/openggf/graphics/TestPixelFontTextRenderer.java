package com.openggf.graphics;

import com.openggf.debug.DebugColor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestPixelFontTextRenderer {

    private static final float[] PROJECTION_A = {
            1f, 2f, 3f, 4f,
            5f, 6f, 7f, 8f,
            9f, 10f, 11f, 12f,
            13f, 14f, 15f, 16f
    };

    private static final float[] PROJECTION_B = {
            16f, 15f, 14f, 13f,
            12f, 11f, 10f, 9f,
            8f, 7f, 6f, 5f,
            4f, 3f, 2f, 1f
    };

    private static class FakeTexturedQuadRenderer extends TexturedQuadRenderer {
        private int initCalls;
        private int cleanupCalls;
        private int projectionCalls;
        private float[] lastProjection;

        @Override
        public void init() {
            initCalls++;
        }

        @Override
        public void setProjectionMatrix(float[] matrixBuffer) {
            projectionCalls++;
            lastProjection = matrixBuffer.clone();
        }

        @Override
        public void cleanup() {
            cleanupCalls++;
        }
    }

    private static class FakePixelFont extends PixelFont {
        private int initCalls;
        private int cleanupCalls;
        private int measureWidthValue = 77;
        private boolean failInit;
        private String lastFontPath;

        @Override
        public void init(String fontPngPath, TexturedQuadRenderer renderer) throws IOException {
            initCalls++;
            lastFontPath = fontPngPath;
            if (failInit) {
                throw new IOException("boom");
            }
        }

        @Override
        public int measureWidth(String text) {
            return measureWidthValue;
        }

        @Override
        public int measureWidth(String text, float scale) {
            return measureWidthValue;
        }

        @Override
        public void cleanup() {
            cleanupCalls++;
        }

        @Override
        public void drawText(String text, int x, int y, float r, float g, float b, float a) {
            // no-op for tests
        }
    }

    private static final class RecordingPixelFontTextRenderer extends PixelFontTextRenderer {
        private final List<DrawCall> calls = new ArrayList<>();

        private RecordingPixelFontTextRenderer(PixelFont font) {
            super(font);
        }

        private RecordingPixelFontTextRenderer(PixelFont font, PixelFontVariant fontVariant) {
            super(font, fontVariant);
        }

        @Override
        protected void drawRawText(String text, int x, int y, DebugColor color) {
            calls.add(new DrawCall(text, x, y, color));
        }

        @Override
        protected void drawRawText(String text, int x, int y, DebugColor color, float scale) {
            calls.add(new DrawCall(text, x, y, color, scale));
        }
    }

    private static final class TestablePixelFontTextRenderer extends PixelFontTextRenderer {
        private final FakePixelFont font;
        private final List<FakeTexturedQuadRenderer> renderers = new ArrayList<>();

        private TestablePixelFontTextRenderer(FakePixelFont font) {
            super(font);
            this.font = font;
        }

        private TestablePixelFontTextRenderer(FakePixelFont font, PixelFontVariant fontVariant) {
            super(font, fontVariant);
            this.font = font;
        }

        @Override
        protected TexturedQuadRenderer createRenderer() {
            FakeTexturedQuadRenderer renderer = new FakeTexturedQuadRenderer();
            renderers.add(renderer);
            return renderer;
        }

        void drawSampleText() {
            drawRawText("Hello", 12, 34, DebugColor.RED);
        }

        FakeTexturedQuadRenderer renderer(int index) {
            return renderers.get(index);
        }
    }

    private record DrawCall(String text, int x, int y, DebugColor color, float scale) {
        private DrawCall(String text, int x, int y, DebugColor color) {
            this(text, x, y, color, 1.0f);
        }
    }

    @Test
    void lineHeight_matchesPixelFontMetricsPlusShadowPadding() {
        PixelFontTextRenderer renderer = new PixelFontTextRenderer();

        assertEquals(PixelFont.glyphHeight() + 2, renderer.lineHeight());
    }

    @Test
    void drawShadowedText_emitsShadowThenForeground() {
        RecordingPixelFontTextRenderer renderer = new RecordingPixelFontTextRenderer(new FakePixelFont());

        renderer.drawShadowedText("Hello", 12, 34, DebugColor.RED);

        assertEquals(List.of(
                new DrawCall("Hello", 13, 35, DebugColor.BLACK, 1.0f),
                new DrawCall("Hello", 12, 34, DebugColor.RED, 1.0f)
        ), renderer.calls);
    }

    @Test
    void drawShadowedText_withScale_emitsScaledShadowThenForeground() {
        RecordingPixelFontTextRenderer renderer = new RecordingPixelFontTextRenderer(new FakePixelFont());

        renderer.drawShadowedText("Hello", 12, 34, DebugColor.RED, 0.5f);

        assertEquals(List.of(
                new DrawCall("Hello", 13, 35, DebugColor.BLACK, 0.5f),
                new DrawCall("Hello", 12, 34, DebugColor.RED, 0.5f)
        ), renderer.calls);
    }

    @Test
    void drawShadowedText_noShadowVariant_emitsForegroundOnly() {
        RecordingPixelFontTextRenderer renderer = new RecordingPixelFontTextRenderer(
                new FakePixelFont(), PixelFontVariant.PIXEL_FONT_NO_SHADOW);

        renderer.drawShadowedText("Hello", 12, 34, DebugColor.RED);

        assertEquals(List.of(
                new DrawCall("Hello", 12, 34, DebugColor.RED, 1.0f)
        ), renderer.calls);
    }

    @Test
    void measureWidth_delegatesToPixelFont() {
        FakePixelFont font = new FakePixelFont();
        font.measureWidthValue = 123;

        PixelFontTextRenderer renderer = new PixelFontTextRenderer(font);

        assertEquals(123, renderer.measureWidth("ignored"));
    }

    @Test
    void lineHeight_scalesWithPixelFontMetrics() {
        PixelFontTextRenderer renderer = new PixelFontTextRenderer();

        assertEquals(PixelFont.scaledGlyphHeight(0.5f) + 2, renderer.lineHeight(0.5f));
    }

    @Test
    void projectionMatrix_isCachedBeforeInit_andAppliedOnLazyInit_andUpdatedImmediatelyAfterInit() {
        FakePixelFont font = new FakePixelFont();
        TestablePixelFontTextRenderer renderer = new TestablePixelFontTextRenderer(font);

        renderer.setProjectionMatrix(PROJECTION_A);
        renderer.drawSampleText();

        assertEquals(1, font.initCalls);
        assertEquals(1, renderer.renderer(0).initCalls);
        assertEquals(1, renderer.renderer(0).projectionCalls);
        assertArrayEquals(PROJECTION_A, renderer.renderer(0).lastProjection);

        renderer.setProjectionMatrix(PROJECTION_B);

        assertEquals(2, renderer.renderer(0).projectionCalls);
        assertArrayEquals(PROJECTION_B, renderer.renderer(0).lastProjection);
    }

    @Test
    void cleanup_releasesRenderer_andAllowsReuse() {
        FakePixelFont font = new FakePixelFont();
        TestablePixelFontTextRenderer renderer = new TestablePixelFontTextRenderer(font);

        renderer.setProjectionMatrix(PROJECTION_A);
        renderer.drawSampleText();

        FakeTexturedQuadRenderer firstRenderer = renderer.renderer(0);
        renderer.cleanup();

        assertEquals(1, firstRenderer.cleanupCalls);
        assertEquals(1, font.cleanupCalls);

        renderer.drawSampleText();

        FakeTexturedQuadRenderer secondRenderer = renderer.renderer(1);
        assertEquals(2, font.initCalls);
        assertEquals(1, secondRenderer.initCalls);
        assertEquals(1, secondRenderer.projectionCalls);
        assertArrayEquals(PROJECTION_A, secondRenderer.lastProjection);
    }

    @Test
    void cleanup_whenFontInitFails_cleansRendererBeforeThrowing() {
        FakePixelFont font = new FakePixelFont();
        font.failInit = true;
        TestablePixelFontTextRenderer renderer = new TestablePixelFontTextRenderer(font);

        IllegalStateException exception = assertThrows(IllegalStateException.class, renderer::drawSampleText);

        assertEquals("Failed to initialize pixel font renderer", exception.getMessage());
        assertEquals(1, renderer.renderer(0).cleanupCalls);
    }

    @Test
    void defaultConstructor_usesDefaultPixelFontResource() {
        FakePixelFont font = new FakePixelFont();
        TestablePixelFontTextRenderer renderer = new TestablePixelFontTextRenderer(font);

        renderer.drawSampleText();

        assertEquals("pixel-font.png", font.lastFontPath);
    }

    @Test
    void variantConstructor_usesSelectedPixelFontResource() {
        FakePixelFont font = new FakePixelFont();
        TestablePixelFontTextRenderer renderer =
                new TestablePixelFontTextRenderer(font, PixelFontVariant.PIXEL_FONT_NO_SHADOW);

        renderer.drawSampleText();

        assertEquals("pixel-font-ns.png", font.lastFontPath);
    }
}
