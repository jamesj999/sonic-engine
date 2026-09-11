package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPixelFont {

    private static final class RecordingTexturedQuadRenderer extends TexturedQuadRenderer {
        private int immediateDrawCalls;
        private int batchDrawCalls;
        private int lastBatchQuadCount;

        @Override
        public void drawTextureRegion(int textureId, float x, float y, float w, float h,
                                      float u0, float v0, float u1, float v1,
                                      float r, float g, float b, float a) {
            immediateDrawCalls++;
        }

        @Override
        public void drawTextureBatch(int textureId, float[] vertexData, int quadCount,
                                     float r, float g, float b, float a) {
            batchDrawCalls++;
            lastBatchQuadCount = quadCount;
        }
    }

    @Test
    void drawText_batchesMultipleGlyphsIntoOneRendererSubmission() throws Exception {
        PixelFont font = new PixelFont();
        RecordingTexturedQuadRenderer renderer = new RecordingTexturedQuadRenderer();
        primeFont(font, renderer, 'A', 0, 0);

        font.drawText("AAA", 12, 34, 1.0f, 1f, 1f, 1f, 1f);

        assertEquals(0, renderer.immediateDrawCalls);
        assertEquals(1, renderer.batchDrawCalls);
        assertEquals(3, renderer.lastBatchQuadCount);
    }

    private static void primeFont(PixelFont font, TexturedQuadRenderer renderer,
                                  char c, int row, int col) throws Exception {
        setField(font, "renderer", renderer);
        setField(font, "textureId", 7);
        setField(font, "textureWidth", 416);
        setField(font, "textureHeight", 80);

        int[] charToCol = (int[]) getField(font, "charToCol");
        int[] charToRow = (int[]) getField(font, "charToRow");
        boolean[] charKnown = (boolean[]) getField(font, "charKnown");
        charToCol[c] = col;
        charToRow[c] = row;
        charKnown[c] = true;
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = PixelFont.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = PixelFont.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
