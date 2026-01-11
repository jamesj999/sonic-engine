package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.Palette;

import java.io.IOException;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Loads Special Stage palette data from ROM for Sonic 2.
 *
 * Palette structure (from s2disasm):
 * - Pal_SS (Main): 96 bytes loaded to palette lines 0, 1, 2
 *   - Line 0: Background/sky colors
 *   - Line 1: Track/ground colors
 *   - Line 2: Object colors (rings, bombs, shadows)
 * - Pal_SS1-7: 32 bytes each loaded to palette line 3
 *   - Line 3: Per-stage varying colors
 *
 * Genesis color format: ----BBB0GGG0RRR0 (big-endian 16-bit)
 * - Bits 1-3: Red (0-7)
 * - Bits 5-7: Green (0-7)
 * - Bits 9-11: Blue (0-7)
 */
public class Sonic2SpecialStagePalette {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStagePalette.class.getName());

    private static byte[] cachedMainPalette;
    private static byte[][] cachedStagePalettes;

    /**
     * Creates a set of 4 palettes for the specified special stage by loading from ROM.
     *
     * @param stageIndex Stage index (0-6)
     * @return Array of 4 Palette objects
     */
    public static Palette[] createPalettes(int stageIndex) {
        try {
            return createPalettesFromRom(stageIndex);
        } catch (IOException e) {
            LOGGER.warning("Failed to load palettes from ROM, using fallback: " + e.getMessage());
            return createFallbackPalettes();
        }
    }

    /**
     * Loads palette data from ROM and creates palette objects.
     */
    private static Palette[] createPalettesFromRom(int stageIndex) throws IOException {
        loadPaletteDataIfNeeded();

        Palette[] palettes = new Palette[4];

        // Lines 0, 1, 2 come from the main palette (96 bytes = 3 lines of 32 bytes)
        for (int line = 0; line < 3; line++) {
            palettes[line] = new Palette();
            int offset = line * 32; // 32 bytes per palette line (16 colors * 2 bytes)
            loadPaletteLineFromBytes(palettes[line], cachedMainPalette, offset);
        }

        // Line 3 comes from the per-stage palette
        int safeStageIndex = Math.max(0, Math.min(stageIndex, SPECIAL_STAGE_COUNT - 1));
        palettes[3] = new Palette();
        loadPaletteLineFromBytes(palettes[3], cachedStagePalettes[safeStageIndex], 0);

        LOGGER.fine("Created special stage palettes for stage " + (stageIndex + 1) + " from ROM");

        return palettes;
    }

    /**
     * Loads cached palette data from ROM if not already loaded.
     */
    private static void loadPaletteDataIfNeeded() throws IOException {
        if (cachedMainPalette == null || cachedStagePalettes == null) {
            SonicConfigurationService configService = SonicConfigurationService.getInstance();
            Rom rom = new Rom();
            rom.open(configService.getString(SonicConfiguration.ROM_FILENAME));

            // Load main palette (96 bytes = 3 palette lines)
            cachedMainPalette = rom.readBytes(PALETTE_MAIN_OFFSET, PALETTE_MAIN_SIZE);
            LOGGER.fine("Loaded main palette from ROM offset 0x" +
                       Long.toHexString(PALETTE_MAIN_OFFSET) + " (" + cachedMainPalette.length + " bytes)");

            // Load all 7 stage palettes (32 bytes each)
            cachedStagePalettes = new byte[SPECIAL_STAGE_COUNT][];
            for (int i = 0; i < SPECIAL_STAGE_COUNT; i++) {
                cachedStagePalettes[i] = rom.readBytes(PALETTE_STAGE_OFFSETS[i], PALETTE_STAGE_SIZE);
            }
            LOGGER.fine("Loaded " + SPECIAL_STAGE_COUNT + " stage palettes from ROM");
        }
    }

    /**
     * Loads 16 colors from raw ROM bytes into a Palette object.
     * Each color is a big-endian 16-bit Genesis color.
     *
     * @param palette The palette to populate
     * @param data Raw palette bytes (32 bytes = 16 colors)
     * @param offset Starting offset in the data array
     */
    private static void loadPaletteLineFromBytes(Palette palette, byte[] data, int offset) {
        for (int colorIndex = 0; colorIndex < 16; colorIndex++) {
            int byteOffset = offset + (colorIndex * 2);
            if (byteOffset + 1 < data.length) {
                // Read big-endian 16-bit Genesis color
                int genesisColor = ((data[byteOffset] & 0xFF) << 8) | (data[byteOffset + 1] & 0xFF);
                palette.setColor(colorIndex, genesisColorToRgb(genesisColor));
            }
        }
    }

    /**
     * Converts a Genesis 9-bit color (----BBB0GGG0RRR0 format) to RGB.
     * Each component is 0-7, scaled to 0-252 for 8-bit RGB.
     */
    private static Palette.Color genesisColorToRgb(int genesis) {
        // Genesis color format: ----BBB0GGG0RRR0
        int r = ((genesis >> 1) & 0x7) * 36;  // Bits 1-3
        int g = ((genesis >> 5) & 0x7) * 36;  // Bits 5-7
        int b = ((genesis >> 9) & 0x7) * 36;  // Bits 9-11
        return new Palette.Color((byte) r, (byte) g, (byte) b);
    }

    /**
     * Creates fallback palettes when ROM loading fails.
     * Uses a simple grayscale gradient for visibility.
     */
    private static Palette[] createFallbackPalettes() {
        Palette[] palettes = new Palette[4];
        for (int i = 0; i < 4; i++) {
            palettes[i] = new Palette();
            for (int c = 0; c < 16; c++) {
                int gray = (c * 16) & 0xFF;
                palettes[i].setColor(c, new Palette.Color((byte) gray, (byte) gray, (byte) gray));
            }
        }
        return palettes;
    }

    /**
     * Clears cached palette data. Call this when ROM changes or on reset.
     */
    public static void clearCache() {
        cachedMainPalette = null;
        cachedStagePalettes = null;
    }

    /**
     * Debug method to dump palette data to logs.
     */
    public static void dumpPaletteData(int stageIndex) {
        try {
            loadPaletteDataIfNeeded();

            StringBuilder sb = new StringBuilder();
            sb.append("=== Special Stage Palette Data (Stage ").append(stageIndex + 1).append(") ===\n");

            for (int line = 0; line < 3; line++) {
                sb.append("Line ").append(line).append(" (main): ");
                int offset = line * 32;
                for (int c = 0; c < 16; c++) {
                    int colorOffset = offset + c * 2;
                    int color = ((cachedMainPalette[colorOffset] & 0xFF) << 8) |
                               (cachedMainPalette[colorOffset + 1] & 0xFF);
                    sb.append(String.format("%04X ", color));
                }
                sb.append("\n");
            }

            sb.append("Line 3 (stage ").append(stageIndex + 1).append("): ");
            byte[] stagePal = cachedStagePalettes[Math.min(stageIndex, SPECIAL_STAGE_COUNT - 1)];
            for (int c = 0; c < 16; c++) {
                int color = ((stagePal[c * 2] & 0xFF) << 8) | (stagePal[c * 2 + 1] & 0xFF);
                sb.append(String.format("%04X ", color));
            }

            LOGGER.info(sb.toString());
        } catch (IOException e) {
            LOGGER.warning("Failed to dump palette data: " + e.getMessage());
        }
    }
}
