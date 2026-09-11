package com.openggf.game.sonic2.levelselect;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.NemesisReader;
import com.openggf.util.PatternDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads level select screen graphics and data from the Sonic 2 ROM.
 *
 * <p>Graphics loaded:
 * <ul>
 *   <li>ArtNem_MenuBox - Menu borders and text frames</li>
 *   <li>ArtNem_LevelSelectPics - Zone preview icons (15 icons)</li>
 *   <li>ArtNem_FontStuff - Standard menu font</li>
 * </ul>
 *
 * <p>Mappings loaded:
 * <ul>
 *   <li>MapEng_LevSel - Main screen layout (Enigma compressed)</li>
 *   <li>MapEng_LevSelIcon - Icon box layout (Enigma compressed)</li>
 * </ul>
 *
 * <p>Palettes loaded:
 * <ul>
 *   <li>Pal_LevelIcons - 15 icon palettes (32 bytes each, uncompressed)</li>
 * </ul>
 */
public class LevelSelectDataLoader {
    private static final Logger LOGGER = Logger.getLogger(LevelSelectDataLoader.class.getName());

    /** Pattern offset for menu box art within level select patterns (ArtTile_ArtNem_MenuBox) */
    private static final int MENU_BOX_OFFSET = 0x70;

    /** Pattern offset for level select pics art (ArtTile_ArtNem_LevelSelectPics) */
    private static final int LEVEL_SELECT_PICS_OFFSET = 0x90;

    /** Pattern offset for font art (ArtTile_ArtNem_FontStuff) */
    private static final int FONT_OFFSET = 0x10;

    // Loaded art patterns
    private Pattern[] menuBoxPatterns;
    private Pattern[] levelSelectPicsPatterns;
    private Pattern[] fontPatterns;

    // Combined pattern array for VRAM-style access
    private Pattern[] combinedPatterns;

    // Screen layout decoded from Enigma
    private int[] screenLayout;
    private int screenLayoutWidth;
    private int screenLayoutHeight;

    // Icon mappings decoded from Enigma
    private int[] iconMappings;
    private int iconMappingsWidth;
    private int iconMappingsHeight;

    // Icon palettes (15 palettes, one per icon)
    private Palette[] iconPalettes;

    // Menu palettes (4 palette lines from ROM Pal_Menu)
    private Palette[] menuPalettes;

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
            // Load Nemesis-compressed art
            menuBoxPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_MENU_BOX_ADDR, "MenuBox");
            levelSelectPicsPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_LEVEL_SELECT_PICS_ADDR, "LevelSelectPics");
            fontPatterns = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_FONT_STUFF_ADDR, "FontStuff");

            LOGGER.info("Loaded patterns: MenuBox=" + (menuBoxPatterns != null ? menuBoxPatterns.length : 0) +
                    ", LevelSelectPics=" + (levelSelectPicsPatterns != null ? levelSelectPicsPatterns.length : 0) +
                    ", Font=" + (fontPatterns != null ? fontPatterns.length : 0));

            // Combine all patterns into a single VRAM-style array
            combinePatternsToVram();

            // Load Enigma-compressed mappings
            loadScreenLayout(rom);
            loadIconMappings(rom);

            // Load icon palettes
            loadIconPalettes(rom);

            // Load menu palettes from ROM
            loadMenuPalettes(rom);

            dataLoaded = true;
            return true;

        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load level select data", e);
            return false;
        }
    }


    /**
     * Combines loaded patterns into a VRAM-style array for easy access.
     */
    private void combinePatternsToVram() {
        int menuBoxLength = menuBoxPatterns != null ? menuBoxPatterns.length : 0;
        int picsLength = levelSelectPicsPatterns != null ? levelSelectPicsPatterns.length : 0;
        int fontLength = fontPatterns != null ? fontPatterns.length : 0;

        int totalSize = 1;
        totalSize = Math.max(totalSize, MENU_BOX_OFFSET + menuBoxLength);
        totalSize = Math.max(totalSize, LEVEL_SELECT_PICS_OFFSET + picsLength);
        totalSize = Math.max(totalSize, FONT_OFFSET + fontLength);

        combinedPatterns = new Pattern[totalSize];

        // Fill with empty patterns
        Pattern emptyPattern = new Pattern();
        Arrays.fill(combinedPatterns, emptyPattern);

        // Copy menu box patterns to offset 0x70 (ArtTile_ArtNem_MenuBox)
        if (menuBoxPatterns != null) {
            for (int i = 0; i < menuBoxPatterns.length && (MENU_BOX_OFFSET + i) < combinedPatterns.length; i++) {
                if (menuBoxPatterns[i] != null) {
                    combinedPatterns[MENU_BOX_OFFSET + i] = menuBoxPatterns[i];
                }
            }
        }

        // Copy level select pics to offset 0x90 (ArtTile_ArtNem_LevelSelectPics)
        if (levelSelectPicsPatterns != null) {
            for (int i = 0; i < levelSelectPicsPatterns.length; i++) {
                if (levelSelectPicsPatterns[i] != null) {
                    combinedPatterns[LEVEL_SELECT_PICS_OFFSET + i] = levelSelectPicsPatterns[i];
                }
            }
        }

        // Copy font patterns to offset 0x10 (ArtTile_ArtNem_FontStuff)
        if (fontPatterns != null) {
            for (int i = 0; i < fontPatterns.length; i++) {
                if (fontPatterns[i] != null) {
                    combinedPatterns[FONT_OFFSET + i] = fontPatterns[i];
                }
            }
        }

        LOGGER.info("Combined " + combinedPatterns.length + " patterns for level select");
    }

    /**
     * Loads the main screen layout from Enigma-compressed data.
     */
    private void loadScreenLayout(Rom rom) {
        try {
            // Read Enigma data
            byte[] compressed = rom.readBytes(Sonic2Constants.MAP_ENI_LEVEL_SELECT_ADDR, 1024);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel, 0);

                // Enigma output is 16-bit words in big-endian format
                // Convert to tile indices
                int wordCount = decompressed.length / 2;
                screenLayout = new int[wordCount];
                ByteBuffer buf = ByteBuffer.wrap(decompressed);
                for (int i = 0; i < wordCount; i++) {
                    screenLayout[i] = buf.getShort() & 0xFFFF;
                }

                // Screen is 40 tiles wide (H40 mode) x 28 tiles tall
                screenLayoutWidth = 40;
                screenLayoutHeight = wordCount / screenLayoutWidth;

                // Clear sound test placeholder tiles at (col 34-35, row 18)
                // The original ROM Enigma data contains "00" here which we overwrite dynamically
                // in LevelSelectManager.drawSoundTestValue(). Clearing prevents ghosting.
                int soundTestIdx1 = 18 * screenLayoutWidth + 34;
                int soundTestIdx2 = 18 * screenLayoutWidth + 35;
                if (soundTestIdx1 < screenLayout.length) {
                    screenLayout[soundTestIdx1] = 0;
                }
                if (soundTestIdx2 < screenLayout.length) {
                    screenLayout[soundTestIdx2] = 0;
                }

                LOGGER.info("Loaded screen layout: " + screenLayoutWidth + "x" + screenLayoutHeight +
                        " (" + wordCount + " tiles)");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load screen layout: " + e.getMessage());
            screenLayout = new int[0];
            screenLayoutWidth = 0;
            screenLayoutHeight = 0;
        }
    }

    /**
     * Loads the icon box mappings from Enigma-compressed data.
     */
    private void loadIconMappings(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic2Constants.MAP_ENI_LEVEL_SELECT_ICON_ADDR, 256);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel, LEVEL_SELECT_PICS_OFFSET);

                int wordCount = decompressed.length / 2;
                iconMappings = new int[wordCount];
                ByteBuffer buf = ByteBuffer.wrap(decompressed);
                for (int i = 0; i < wordCount; i++) {
                    iconMappings[i] = buf.getShort() & 0xFFFF;
                }

                // Icon box is 4 tiles wide x 3 tiles tall per icon (12 words each)
                iconMappingsWidth = 4;
                iconMappingsHeight = wordCount / iconMappingsWidth;

                LOGGER.info("Loaded icon mappings: " + iconMappingsWidth + "x" + iconMappingsHeight);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load icon mappings: " + e.getMessage());
            iconMappings = new int[0];
            iconMappingsWidth = 0;
            iconMappingsHeight = 0;
        }
    }

    /**
     * Loads the menu palettes from ROM (Pal_Menu at 0x30E2).
     */
    private void loadMenuPalettes(Rom rom) {
        try {
            byte[] paletteData = rom.readBytes(Sonic2Constants.PAL_MENU_ADDR, Sonic2Constants.PAL_MENU_SIZE);
            menuPalettes = new Palette[4];

            for (int line = 0; line < 4; line++) {
                menuPalettes[line] = new Palette();
                // Each palette line is 32 bytes (16 colors * 2 bytes per color)
                int offset = line * 32;
                if (offset + 32 <= paletteData.length) {
                    byte[] lineData = Arrays.copyOfRange(paletteData, offset, offset + 32);
                    menuPalettes[line].fromSegaFormat(lineData);
                }
            }

            LOGGER.info("Loaded 4 menu palette lines from ROM at 0x" +
                    Integer.toHexString(Sonic2Constants.PAL_MENU_ADDR));
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load menu palettes from ROM: " + e.getMessage());
            menuPalettes = new Palette[0];
        }
    }

    /**
     * Loads the 15 icon palettes from ROM (uncompressed).
     */
    private void loadIconPalettes(Rom rom) {
        try {
            byte[] paletteData = rom.readBytes(Sonic2Constants.PAL_LEVEL_ICONS_ADDR, Sonic2Constants.PAL_LEVEL_ICONS_SIZE);
            iconPalettes = new Palette[15];

            for (int i = 0; i < 15; i++) {
                iconPalettes[i] = new Palette();
                // Each palette is 32 bytes (16 colors * 2 bytes per color in Mega Drive format)
                int offset = i * 32;
                if (offset + 32 <= paletteData.length) {
                    byte[] lineData = Arrays.copyOfRange(paletteData, offset, offset + 32);
                    iconPalettes[i].fromSegaFormat(lineData);
                }
            }

            LOGGER.info("Loaded 15 icon palettes");
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load icon palettes: " + e.getMessage());
            iconPalettes = new Palette[0];
        }
    }

    /**
     * Caches all loaded patterns and palettes to the GPU.
     */
    public void cacheToGpu(GraphicsManager graphicsManager) {
        if (artCached || !dataLoaded || combinedPatterns == null) {
            return;
        }
        if (graphicsManager == null || graphicsManager.isHeadlessMode()) {
            return;
        }

        // Cache menu palettes (lines 0-3)
        if (menuPalettes != null) {
            for (int i = 0; i < menuPalettes.length; i++) {
                if (menuPalettes[i] != null) {
                    graphicsManager.cachePaletteTexture(menuPalettes[i], i);
                }
            }
            LOGGER.info("Cached 4 menu palette lines to GPU");
        }

        // Cache all patterns with level select pattern base ID
        int cachedCount = 0;
        for (int i = 0; i < combinedPatterns.length; i++) {
            if (combinedPatterns[i] != null) {
                graphicsManager.cachePatternTexture(combinedPatterns[i], LevelSelectConstants.PATTERN_BASE + i);
                cachedCount++;
            }
        }

        LOGGER.info("Cached " + cachedCount + " level select patterns to GPU");
        artCached = true;
    }

    /**
     * Gets the screen layout tile array.
     */
    public int[] getScreenLayout() {
        return screenLayout;
    }

    public int getScreenLayoutWidth() {
        return screenLayoutWidth;
    }

    public int getScreenLayoutHeight() {
        return screenLayoutHeight;
    }

    /**
     * Gets the icon mappings tile array.
     */
    public int[] getIconMappings() {
        return iconMappings;
    }

    public int getIconMappingsWidth() {
        return iconMappingsWidth;
    }

    public int getIconMappingsHeight() {
        return iconMappingsHeight;
    }

    /**
     * Gets the palette for a specific icon index.
     */
    public Palette getIconPalette(int iconIndex) {
        if (iconPalettes != null && iconIndex >= 0 && iconIndex < iconPalettes.length) {
            return iconPalettes[iconIndex];
        }
        return null;
    }

    /**
     * Gets the menu palette for a specific line (0-3).
     * Line 0 = normal text.
     */
    public Palette getMenuPalette(int lineIndex) {
        if (menuPalettes != null && lineIndex >= 0 && lineIndex < menuPalettes.length) {
            return menuPalettes[lineIndex];
        }
        return null;
    }

    /**
     * Gets the combined pattern array.
     */
    public Pattern[] getCombinedPatterns() {
        return combinedPatterns;
    }

    /**
     * Gets the level select pics patterns (zone preview icons).
     */
    public Pattern[] getLevelSelectPicsPatterns() {
        return levelSelectPicsPatterns;
    }

    /**
     * Gets the pattern offset for level select pics.
     */
    public int getLevelSelectPicsOffset() {
        return LEVEL_SELECT_PICS_OFFSET;
    }

    /**
     * Gets the font patterns.
     */
    public Pattern[] getFontPatterns() {
        return fontPatterns;
    }

    /**
     * Gets the pattern offset for font.
     */
    public int getFontOffset() {
        return FONT_OFFSET;
    }

    /**
     * Returns whether data has been loaded.
     */
    public boolean isDataLoaded() {
        return dataLoaded;
    }

    /**
     * Returns whether art has been cached to GPU.
     */
    public boolean isArtCached() {
        return artCached;
    }

    /**
     * Resets the cached state to force re-upload on next draw.
     */
    public void resetCache() {
        artCached = false;
    }
}
