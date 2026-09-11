package com.openggf.game.sonic3k;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;

/**
 * Palette upload boundary for S3K menu/frontend screens.
 *
 * <p>These screens are outside gameplay level runtime, so they intentionally do
 * not use {@code PaletteOwnershipRegistry}. Keep their direct GPU palette
 * updates centralized here until a broader menu palette pipeline exists.</p>
 */
public final class S3kFrontendPaletteUploader {

    private S3kFrontendPaletteUploader() {}

    public static void cacheAll(GraphicsManager graphics, Palette[] palettes) {
        if (graphics == null || palettes == null) {
            return;
        }
        for (int line = 0; line < palettes.length; line++) {
            cacheLine(graphics, palettes[line], line);
        }
    }

    public static void cacheLine(GraphicsManager graphics, Palette palette, int line) {
        if (graphics == null || palette == null) {
            return;
        }
        graphics.cachePaletteTexture(palette, line);
    }

    public static void cacheLineFromBytes(GraphicsManager graphics, byte[] lineData, int line) {
        if (graphics == null || lineData == null) {
            return;
        }
        Palette palette = new Palette();
        palette.fromSegaFormat(lineData);
        cacheLine(graphics, palette, line);
    }
}
