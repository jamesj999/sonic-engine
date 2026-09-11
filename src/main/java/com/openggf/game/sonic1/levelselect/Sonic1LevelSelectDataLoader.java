package com.openggf.game.sonic1.levelselect;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads level select screen graphics and data from the Sonic 1 ROM.
 *
 * <p>Graphics loaded:
 * <ul>
 *   <li>Art_Text (artunc/menutext.bin) - Uncompressed menu font (41 patterns)</li>
 * </ul>
 *
 * <p>Palettes loaded:
 * <ul>
 *   <li>Pal_LevelSel (palette/Level Select.bin) - 4 palette lines (128 bytes)</li>
 * </ul>
 *
 * <p>The Sonic 1 font character mapping (from charset directives in sonic.asm):
 * <pre>
 *   0x00-0x09: '0'-'9'
 *   0x0A: '$'
 *   0x0B: '-'
 *   0x0C: '='
 *   0x0D: '>'
 *   0x0E: '>' (duplicate)
 *   0x0F: 'Y'
 *   0x10: 'Z'
 *   0x11-0x28: 'A'-'X'
 * </pre>
 */
public class Sonic1LevelSelectDataLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic1LevelSelectDataLoader.class.getName());

    /** ROM address of Art_Text (uncompressed font patterns) */
    private static final int ART_TEXT_ADDR = 0x5F0;
    /** Size of Art_Text: 41 patterns * 32 bytes = 1312 bytes */
    private static final int ART_TEXT_SIZE = 1312;

    /** ROM address of Pal_LevelSel (4 palette lines) */
    private static final int PAL_LEVEL_SEL_ADDR = 0x2300;
    /** Size: 4 palette lines * 16 colors * 2 bytes = 128 bytes */
    private static final int PAL_LEVEL_SEL_SIZE = 128;

    private Pattern[] fontPatterns;
    private Palette[] palettes;

    private boolean dataLoaded = false;
    private boolean artCached = false;

    /**
     * Loads all level select data from ROM.
     *
     * @return true if data was loaded successfully
     */
    public boolean loadData() {
        if (dataLoaded) {
            return true;
        }

        RomManager romManager = GameServices.rom();
        if (!romManager.isRomAvailable()) {
            LOGGER.warning("ROM not available for level select data loading");
            return false;
        }

        Rom rom;
        try {
            rom = romManager.getRom();
        } catch (IOException e) {
            LOGGER.warning("Failed to get ROM: " + e.getMessage());
            return false;
        }

        try {
            loadFontPatterns(rom);
            loadPalettes(rom);

            dataLoaded = true;
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load Sonic 1 level select data", e);
            return false;
        }
    }

    /**
     * Loads uncompressed font patterns from Art_Text.
     */
    private void loadFontPatterns(Rom rom) throws IOException {
        byte[] data = rom.readBytes(ART_TEXT_ADDR, ART_TEXT_SIZE);
        int patternCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        fontPatterns = new Pattern[patternCount];

        for (int i = 0; i < patternCount; i++) {
            fontPatterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(data,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            fontPatterns[i].fromSegaFormat(subArray);
        }

        LOGGER.info("Loaded " + patternCount + " font patterns from Art_Text");
    }

    /**
     * Loads the 4 level select palette lines from Pal_LevelSel.
     */
    private void loadPalettes(Rom rom) throws IOException {
        byte[] paletteData = rom.readBytes(PAL_LEVEL_SEL_ADDR, PAL_LEVEL_SEL_SIZE);
        palettes = new Palette[4];

        for (int line = 0; line < 4; line++) {
            palettes[line] = new Palette();
            int offset = line * 32; // 16 colors * 2 bytes per color
            if (offset + 32 <= paletteData.length) {
                byte[] lineData = Arrays.copyOfRange(paletteData, offset, offset + 32);
                palettes[line].fromSegaFormat(lineData);
            }
        }

        LOGGER.info("Loaded 4 level select palette lines from Pal_LevelSel");
    }

    /**
     * Caches font patterns and palettes to the GPU.
     */
    public void cacheToGpu(GraphicsManager gm) {
        if (!dataLoaded) {
            return;
        }
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        // Cache palettes (lines 0-3)
        if (palettes != null) {
            for (int i = 0; i < palettes.length; i++) {
                if (palettes[i] != null) {
                    gm.cachePaletteTexture(palettes[i], i);
                }
            }
        }

        if (artCached) {
            return;
        }

        // Cache font patterns
        if (fontPatterns != null) {
            for (int i = 0; i < fontPatterns.length; i++) {
                if (fontPatterns[i] != null) {
                    gm.cachePatternTexture(fontPatterns[i],
                            Sonic1LevelSelectConstants.PATTERN_BASE + i);
                }
            }
            LOGGER.info("Cached " + fontPatterns.length + " font patterns to GPU");
        }

        artCached = true;
    }

    /**
     * Gets the font tile index for a character.
     *
     * <p>Sonic 1 character encoding (from sonic.asm charset directives):
     * <ul>
     *   <li>'0'-'9' at indices 0x00-0x09</li>
     *   <li>'Y' at index 0x0F</li>
     *   <li>'Z' at index 0x10</li>
     *   <li>'A'-'X' at indices 0x11-0x28</li>
     *   <li>Space = skip (no tile)</li>
     * </ul>
     */
    public static int getCharacterTileIndex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0'; // 0x00-0x09
        } else if (c == 'Y') {
            return 0x0F;
        } else if (c == 'Z') {
            return 0x10;
        } else if (c >= 'A' && c <= 'X') {
            return 0x11 + (c - 'A'); // 0x11-0x28
        } else if (c == ' ') {
            return -1; // Space - skip
        }
        return -1; // Unknown character
    }

    /**
     * Gets the font tile index for a hex digit (0-F).
     * Used for sound test value display.
     *
     * <p>From LevSel_ChgSnd: digits 0-9 use direct tile index,
     * digits A-F add 7 to get the alpha character position.
     */
    public static int getHexDigitTileIndex(int digit) {
        if (digit < 10) {
            return digit; // 0-9 → tiles 0x00-0x09
        } else {
            return digit + 7; // A=17=0x11, B=18=0x12, ... F=22=0x16
        }
    }

    /**
     * Gets the palette for a specific line (0-3).
     */
    public Palette getPalette(int lineIndex) {
        if (palettes != null && lineIndex >= 0 && lineIndex < palettes.length) {
            return palettes[lineIndex];
        }
        return null;
    }

    /**
     * Returns whether data has been loaded.
     */
    public boolean isDataLoaded() {
        return dataLoaded;
    }

    /**
     * Resets the cached state to force re-upload on next draw.
     */
    public void resetCache() {
        artCached = false;
    }
}
