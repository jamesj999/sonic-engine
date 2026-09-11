package com.openggf.game.sonic3k.specialstage;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;

final class Sonic3kSpecialStagePaletteUploader {

    private Sonic3kSpecialStagePaletteUploader() {}

    static void cacheAll(GraphicsManager graphics, Palette[] palettes) {
        if (graphics == null || palettes == null) {
            return;
        }
        for (int line = 0; line < palettes.length; line++) {
            cacheLine(graphics, palettes[line], line);
        }
    }

    static void cacheLine(GraphicsManager graphics, Palette palette, int line) {
        if (graphics == null || palette == null) {
            return;
        }
        graphics.cachePaletteTexture(palette, line);
    }
}
