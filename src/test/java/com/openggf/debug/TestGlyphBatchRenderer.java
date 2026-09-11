package com.openggf.debug;

import com.openggf.graphics.PixelFont;
import com.openggf.graphics.PixelFontTextRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestGlyphBatchRenderer {

    private static final class RecordingPixelFontTextRenderer extends PixelFontTextRenderer {
        private int measuredWidth = 36;
        private String lastText;
        private int lastX;
        private int lastY;
        private DebugColor lastColor;
        private float lastScale = -1f;
        private float lastMeasuredScale = -1f;

        @Override
        public int measureWidth(String text, float scale) {
            lastText = text;
            lastMeasuredScale = scale;
            return measuredWidth;
        }

        @Override
        public void drawShadowedText(String text, int x, int y, DebugColor color, float scale) {
            lastText = text;
            lastX = x;
            lastY = y;
            lastColor = color;
            lastScale = scale;
        }

        @Override
        public void beginBatch() {
            // No-op: skip GL initialization in tests
        }

        @Override
        public void endBatch() {
            // No-op: skip GL flush in tests
        }
    }

    @Test
    void lineHeight_halvesLegacyDebugMetricsAtBaseResolution() {
        GlyphBatchRenderer renderer = new GlyphBatchRenderer(new RecordingPixelFontTextRenderer());

        renderer.init(null);

        assertEquals(5, renderer.getLineHeight(FontSize.SMALL));
        assertEquals(6, renderer.getLineHeight(FontSize.MEDIUM));
        assertEquals(7, renderer.getLineHeight(FontSize.LARGE));
    }

    @Test
    void measureTextWidth_usesHalfScalePixelFontMetrics() {
        RecordingPixelFontTextRenderer textRenderer = new RecordingPixelFontTextRenderer();
        GlyphBatchRenderer renderer = new GlyphBatchRenderer(textRenderer);

        renderer.init(null);

        assertEquals(36, renderer.measureTextWidth("FPS", FontSize.MEDIUM));
        assertEquals("FPS", textRenderer.lastText);
        assertEquals(0.5f, textRenderer.lastMeasuredScale);
    }

    @Test
    void drawText_usesHalfScaleAndAdjustedTopLeftCoordinates() {
        RecordingPixelFontTextRenderer textRenderer = new RecordingPixelFontTextRenderer();
        GlyphBatchRenderer renderer = new GlyphBatchRenderer(textRenderer);

        renderer.init(null);
        renderer.begin();
        renderer.drawText("SPD", 20, 30, DebugColor.WHITE, FontSize.MEDIUM);

        assertEquals("SPD", textRenderer.lastText);
        assertEquals(20, textRenderer.lastX);
        assertEquals(224 - 30 - PixelFont.scaledGlyphHeight(0.5f), textRenderer.lastY);
        assertEquals(DebugColor.WHITE, textRenderer.lastColor);
        assertEquals(0.5f, textRenderer.lastScale);
    }
}
