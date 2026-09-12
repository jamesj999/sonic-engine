package com.openggf.graphics;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Menu typography with two native pixel sizes: authored compact lettering with
 * lowercase descenders in 6x8 cells, and the existing 9x10 font at integer multiples. Fractional caller
 * scales select the compact font; they never resample either font's artwork.
 */
public final class MenuPixelFont extends PixelFont {
    private enum Batch { NONE, COMPACT, FULL }
    private TexturedQuadRenderer menuRenderer;
    private int compactTexture;
    private int solidTexture;
    private boolean initialized;
    private boolean batching;
    private Batch activeBatch = Batch.NONE;
    private float[] compactVertices = new float[TexturedQuadRenderer.COLORED_QUAD_FLOATS * 64];
    private int compactQuads;

    @Override
    public void init(String fontPngPath, TexturedQuadRenderer renderer) throws IOException {
        if (initialized) cleanup();
        menuRenderer = Objects.requireNonNull(renderer, "renderer");
        super.init(fontPngPath, renderer);
        initialized = true;
        try {
            compactTexture = upload(MenuGlyphs.ATLAS_WIDTH, MenuGlyphs.ATLAS_HEIGHT, MenuGlyphs.atlasRgba());
            solidTexture = upload(1, 1, new byte[] {-1, -1, -1, -1});
        } catch (RuntimeException | Error failure) {
            cleanup();
            throw failure;
        }
    }

    /** Actual advance of the selected native font, in projection pixels. */
    public static int glyphAdvance(float scale) {
        return scale < 1 ? MenuGlyphs.ADVANCE : PixelFont.glyphWidth() * integerScale(scale);
    }

    /** Actual cell height of the selected native font, in projection pixels. */
    public static int glyphLineHeight(float scale) {
        return scale < 1 ? MenuGlyphs.HEIGHT : PixelFont.glyphHeight() * integerScale(scale);
    }

    private static int integerScale(float scale) {
        return Math.max(1, Math.round(scale));
    }

    @Override
    public int measureWidth(String text, float scale) {
        return text.length() * glyphAdvance(scale);
    }

    @Override
    public int measureWidth(String text) {
        return measureWidth(text, 1);
    }

    @Override
    public void drawText(String text, int x, int y, float r, float g, float b, float a) {
        drawText(text, (float) x, (float) y, 1, r, g, b, a);
    }

    @Override
    public void drawText(String text, int x, int y, float scale, float r, float g, float b, float a) {
        drawText(text, (float) x, (float) y, scale, r, g, b, a);
    }

    @Override
    public void drawText(String text, float x, float y, float scale, float r, float g, float b, float a) {
        if (menuRenderer == null || text.isEmpty()) return;
        int originX = Math.round(x);
        int originY = Math.round(y);
        if (scale >= 1) {
            selectBatch(Batch.FULL);
            // ASCII quotes have curly counterparts in the existing full-size atlas.
            String fullText = text.replace('\'', '\u2019').replace('"', '\u201c');
            super.drawText(fullText, (float) originX, (float) originY, integerScale(scale), r, g, b, a);
            return;
        }
        selectBatch(Batch.COMPACT);
        for (int i = 0; i < text.length(); i++) {
            char character = MenuGlyphs.normalized(text.charAt(i));
            if (character == ' ') continue;
            int index = character - 32;
            int sourceX = (index % MenuGlyphs.COLUMNS) * MenuGlyphs.ADVANCE;
            int sourceY = (index / MenuGlyphs.COLUMNS) * MenuGlyphs.HEIGHT;
            ensureCompactCapacity(compactQuads + 1);
            TexturedQuadRenderer.writeColoredQuadVerticesAtOffset(compactVertices,
                    compactQuads * TexturedQuadRenderer.COLORED_QUAD_FLOATS,
                    originX + i * MenuGlyphs.ADVANCE, 224 - originY - MenuGlyphs.HEIGHT,
                    MenuGlyphs.ADVANCE, MenuGlyphs.HEIGHT,
                    (float) sourceX / MenuGlyphs.ATLAS_WIDTH,
                    1f - (float) (sourceY + MenuGlyphs.HEIGHT) / MenuGlyphs.ATLAS_HEIGHT,
                    (float) (sourceX + MenuGlyphs.ADVANCE) / MenuGlyphs.ATLAS_WIDTH,
                    1f - (float) sourceY / MenuGlyphs.ATLAS_HEIGHT,
                    r, g, b, a);
            compactQuads++;
        }
        if (!batching) flushActiveBatch();
    }

    @Override
    public void beginMegaBatch() {
        // Flush rather than discard if callers open an adjacent batch accidentally.
        flushActiveBatch();
        batching = true;
    }

    @Override
    public void endMegaBatch() {
        flushActiveBatch();
        batching = false;
    }

    /** Draw a solid rectangle in the same top-left coordinate system as text. */
    public void fillRect(int x, int y, int width, int height, float r, float g, float b, float a) {
        if (menuRenderer == null || width <= 0 || height <= 0) return;
        // A rectangle may cover prior text or sit behind later text: preserve call order.
        flushActiveBatch();
        menuRenderer.drawTexture(solidTexture, x, 224 - y - height, width, height, r, g, b, a);
    }

    // Reused native-grid geometry: the static checkerboard needs one submission,
    // regardless of the number of checks or the current text batching mode.
    private record CheckerGeometry(int start, int width, float[] vertices, int quads) { }
    private final CheckerGeometry[] checkerCache = new CheckerGeometry[4];
    private int nextCheckerSlot;

    public void drawCheckerboard(int startX, int width) {
        if (menuRenderer == null || width <= startX) return;
        flushActiveBatch();
        CheckerGeometry geometry = null;
        for (CheckerGeometry cached : checkerCache) {
            if (cached != null && cached.start() == startX && cached.width() == width) { geometry = cached; break; }
        }
        if (geometry == null) {
            int checkerQuads = 0;
            int capacity = 11 * ((width - startX + 31) / 32 + 1);
            float[] checkerVertices = new float[capacity * TexturedQuadRenderer.COLORED_QUAD_FLOATS];
            for (int y = 29, row = 0; y < 198; y += 16, row++) {
                for (int x = startX + (row & 1) * 16; x < width; x += 32) {
                    int height = Math.min(15, 198 - y);
                    TexturedQuadRenderer.writeColoredQuadVerticesAtOffset(checkerVertices,
                            checkerQuads++ * TexturedQuadRenderer.COLORED_QUAD_FLOATS,
                            x, 224 - y - height, Math.min(15, width - x), height,
                            0, 0, 1, 1, .05f, .115f, .28f, 1);
                }
            }
            geometry = new CheckerGeometry(startX, width, checkerVertices, checkerQuads);
            checkerCache[nextCheckerSlot] = geometry;
            nextCheckerSlot = (nextCheckerSlot + 1) % checkerCache.length;
        }
        menuRenderer.drawColoredTextureBatch(solidTexture, geometry.vertices(), geometry.quads());
    }

    private void selectBatch(Batch next) {
        if (activeBatch == next) return;
        flushActiveBatch();
        activeBatch = next;
        if (next == Batch.FULL && batching) super.beginMegaBatch();
    }

    private void flushActiveBatch() {
        if (activeBatch == Batch.FULL && batching) super.endMegaBatch();
        if (activeBatch == Batch.COMPACT && compactQuads != 0) {
            menuRenderer.drawColoredTextureBatch(compactTexture, compactVertices, compactQuads);
            compactQuads = 0;
        }
        activeBatch = Batch.NONE;
    }

    private void ensureCompactCapacity(int quadCount) {
        int required = quadCount * TexturedQuadRenderer.COLORED_QUAD_FLOATS;
        if (required > compactVertices.length)
            compactVertices = Arrays.copyOf(compactVertices, Math.max(required, compactVertices.length * 2));
    }

    @Override
    public void cleanup() {
        // Cleanup deliberately drops queued text; it must not draw while tearing down GL.
        batching = false;
        activeBatch = Batch.NONE;
        compactQuads = 0;
        if (initialized) {
            // Reset the parent batch state without submitting queued full-size quads.
            super.beginMegaBatch();
            super.endMegaBatch();
            super.cleanup();
        }
        PngTextureLoader.deleteTexture(compactTexture);
        PngTextureLoader.deleteTexture(solidTexture);
        compactTexture = 0;
        solidTexture = 0;
        initialized = false;
        menuRenderer = null;
    }

    private static int upload(int width, int height, byte[] rgba) {
        ByteBuffer pixels = MemoryUtil.memAlloc(rgba.length);
        int texture = 0;
        try {
            pixels.put(rgba).flip();
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            return texture;
        } catch (RuntimeException | Error failure) {
            PngTextureLoader.deleteTexture(texture);
            throw failure;
        } finally {
            glBindTexture(GL_TEXTURE_2D, 0);
            MemoryUtil.memFree(pixels);
        }
    }
}
