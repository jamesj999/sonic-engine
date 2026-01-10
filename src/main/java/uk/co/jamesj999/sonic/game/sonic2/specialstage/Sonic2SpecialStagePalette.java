package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.level.Palette;

/**
 * Special Stage palette data for Sonic 2.
 * Each stage uses a different palette scheme.
 *
 * The palettes are organized as:
 * - Line 0: Background colors
 * - Line 1: Track/ground colors
 * - Line 2: Object colors (rings, bombs)
 * - Line 3: Player colors (Sonic/Tails)
 */
public class Sonic2SpecialStagePalette {

    private static final int[][] STAGE_PALETTES = {
        // Stage 1 palette (EHZ-style green/blue)
        {
            // Line 0: Background (sky gradient)
            0x000, 0x002, 0x004, 0x006, 0x008, 0x00A, 0x00C, 0x00E,
            0x02E, 0x04E, 0x06E, 0x08E, 0x0AE, 0x0CE, 0x0EE, 0x0EE,
            // Line 1: Track (green/gray)
            0x000, 0x020, 0x040, 0x060, 0x080, 0x0A0, 0x0C0, 0x0E0,
            0x222, 0x444, 0x666, 0x888, 0xAAA, 0xCCC, 0xEEE, 0xEEE,
            // Line 2: Objects (gold rings, red bombs)
            0x000, 0x00E, 0x02E, 0x04E, 0x06E, 0x08E, 0x0AE, 0x0CE,
            0xE00, 0xE20, 0xE40, 0xE60, 0xE80, 0xEA0, 0xEC0, 0xEE0,
            // Line 3: Player (Sonic blue)
            0x000, 0x00E, 0x02E, 0x04E, 0x06E, 0x08E, 0x0AE, 0x0CE,
            0xE20, 0xE40, 0xE60, 0xE80, 0xEA0, 0xEC0, 0xEE0, 0xEE0
        }
    };

    /**
     * Creates a set of 4 palettes for the specified special stage.
     *
     * @param stageIndex Stage index (0-6)
     * @return Array of 4 Palette objects
     */
    public static Palette[] createPalettes(int stageIndex) {
        Palette[] palettes = new Palette[4];

        int[] stageColors = STAGE_PALETTES[Math.min(stageIndex, STAGE_PALETTES.length - 1)];

        for (int palIndex = 0; palIndex < 4; palIndex++) {
            palettes[palIndex] = new Palette();
            int offset = palIndex * 16;
            for (int colorIndex = 0; colorIndex < 16; colorIndex++) {
                int genesisColor = stageColors[offset + colorIndex];
                palettes[palIndex].setColor(colorIndex, genesisColorToRgb(genesisColor));
            }
        }

        return palettes;
    }

    /**
     * Creates a default placeholder palette for testing.
     */
    public static Palette[] createDefaultPalettes() {
        return createPalettes(0);
    }

    /**
     * Converts a Genesis 9-bit color (0BGR format) to RGB Palette.Color.
     */
    private static Palette.Color genesisColorToRgb(int genesis) {
        int b = ((genesis >> 9) & 0x7) * 36;
        int g = ((genesis >> 5) & 0x7) * 36;
        int r = ((genesis >> 1) & 0x7) * 36;
        return new Palette.Color((byte) r, (byte) g, (byte) b);
    }
}
