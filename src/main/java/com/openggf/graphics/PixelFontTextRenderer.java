package com.openggf.graphics;

import com.openggf.debug.DebugColor;

import java.io.IOException;
import java.util.Objects;

/**
 * Shared overlay text helper backed by the pixel font texture.
 */
public class PixelFontTextRenderer {

    private final PixelFont font;
    private final PixelFontVariant fontVariant;
    private TexturedQuadRenderer renderer;
    private float[] projectionMatrix;

    public PixelFontTextRenderer() {
        this(new PixelFont(), PixelFontVariant.PIXEL_FONT);
    }

    public PixelFontTextRenderer(PixelFontVariant fontVariant) {
        this(new PixelFont(), fontVariant);
    }

    protected PixelFontTextRenderer(PixelFont font) {
        this(font, PixelFontVariant.PIXEL_FONT);
    }

    protected PixelFontTextRenderer(PixelFont font, PixelFontVariant fontVariant) {
        this.font = Objects.requireNonNull(font, "font");
        this.fontVariant = Objects.requireNonNull(fontVariant, "fontVariant");
    }

    public int lineHeight() {
        return lineHeight(1.0f);
    }

    public int lineHeight(float scale) {
        return PixelFont.scaledGlyphHeight(scale) + extraLinePadding(scale);
    }

    public int measureWidth(String text) {
        return measureWidth(text, 1.0f);
    }

    public int measureWidth(String text, float scale) {
        return font.measureWidth(text, scale);
    }

    public void setProjectionMatrix(float[] projectionMatrix) {
        this.projectionMatrix = Objects.requireNonNull(projectionMatrix, "projectionMatrix");
        if (renderer != null) {
            renderer.setProjectionMatrix(this.projectionMatrix);
        }
    }

    /**
     * Starts mega-batch mode. All subsequent text draws accumulate into a shared
     * buffer and are flushed as a single GL draw call when {@link #endBatch()} is called.
     */
    public void beginBatch() {
        ensureInitialized();
        font.beginMegaBatch();
    }

    /**
     * Flushes all accumulated text from mega-batch mode in a single GL draw call.
     */
    public void endBatch() {
        font.endMegaBatch();
    }

    public void drawShadowedText(String text, int x, int y, DebugColor color) {
        drawShadowedText(text, x, y, color, 1.0f);
    }

    public void drawShadowedText(String text, int x, int y, DebugColor color, float scale) {
        if (fontVariant.drawsShadow()) {
            int shadowOffset = shadowOffset(scale);
            drawRawText(text, x + shadowOffset, y + shadowOffset, DebugColor.BLACK, scale);
        }
        drawRawText(text, x, y, color, scale);
    }

    public void cleanup() {
        if (renderer != null) {
            renderer.cleanup();
            renderer = null;
        }
        font.cleanup();
    }

    protected void drawRawText(String text, int x, int y, DebugColor color) {
        drawRawText(text, x, y, color, 1.0f);
    }

    protected void drawRawText(String text, int x, int y, DebugColor color, float scale) {
        ensureInitialized();
        float alpha = color.getAlpha() / 255f;
        font.drawText(text, x, y, scale,
                color.getRed() / 255f,
                color.getGreen() / 255f,
                color.getBlue() / 255f,
                alpha);
    }

    private static int shadowOffset(float scale) {
        return Math.max(1, Math.round(scale));
    }

    private int extraLinePadding(float scale) {
        return fontVariant.drawsShadow() ? shadowOffset(scale) + 1 : 1;
    }

    private void ensureInitialized() {
        if (renderer != null) {
            return;
        }
        try {
            renderer = createRenderer();
            renderer.init();
            font.init(fontVariant.resourcePath(), renderer);
            if (projectionMatrix != null) {
                renderer.setProjectionMatrix(projectionMatrix);
            }
        } catch (IOException e) {
            if (renderer != null) {
                renderer.cleanup();
                renderer = null;
            }
            throw new IllegalStateException("Failed to initialize pixel font renderer", e);
        }
    }

    protected TexturedQuadRenderer createRenderer() {
        return new TexturedQuadRenderer();
    }
}
