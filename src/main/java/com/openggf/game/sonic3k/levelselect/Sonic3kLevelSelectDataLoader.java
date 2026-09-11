package com.openggf.game.sonic3k.levelselect;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.S3kFrontendPaletteUploader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.data.compression.EnigmaReader;
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
 * Loads level select screen graphics and data from the Sonic 3&amp;K ROM.
 *
 * <p>Loads art, mappings, and palettes matching the S3K disassembly init flow
 * (s3.asm MenuScreen_LevelSelect, lines 7700-8444). Also loads the SONICMILES
 * background art for the animated Plane B background.
 *
 * <p>The screen layout is modified in-memory to replace S2/S3 zone names with
 * the full S3K zone list (including S&amp;K zones).
 */
public class Sonic3kLevelSelectDataLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kLevelSelectDataLoader.class.getName());

    // Combined pattern array for VRAM-style access
    private Pattern[] combinedPatterns;

    // SONICMILES animation source patterns (40 tiles)
    private Pattern[] sonicMilesPatterns;

    // Screen layout (Plane A, modified with S3K zone text)
    private int[] screenLayout;

    // Background layout (Plane B, from MapEni_S22POptions)
    private int[] backgroundLayout;
    private int backgroundWidth;
    private int backgroundHeight;

    // Icon mappings decoded from Enigma
    private int[] iconMappings;
    private int iconMappingsWidth;

    // Icon palettes (15 palettes, one per icon)
    private Palette[] iconPalettes;

    // Menu palettes (4 palette lines from ROM Pal_S2Menu)
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
            LOGGER.warning("ROM not available for S3K level select data loading");
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
            Pattern[] fontPatterns = PatternDecompressor.nemesis(rom,
                    Sonic3kConstants.ART_NEM_S22P_OPTIONS_ADDR, "S22POptions");
            Pattern[] menuBoxPatterns = PatternDecompressor.nemesis(rom,
                    Sonic3kConstants.ART_NEM_S2_MENU_BOX_ADDR, "S2MenuBox");
            Pattern[] levelSelectPicsPatterns = PatternDecompressor.nemesis(rom,
                    Sonic3kConstants.ART_NEM_S2_LEVEL_SELECT_PICS_ADDR, "S2LevelSelectPics");

            LOGGER.info("Loaded patterns: Font=" + (fontPatterns != null ? fontPatterns.length : 0) +
                    ", MenuBox=" + (menuBoxPatterns != null ? menuBoxPatterns.length : 0) +
                    ", LevelSelectPics=" + (levelSelectPicsPatterns != null ? levelSelectPicsPatterns.length : 0));

            // Load uncompressed SONICMILES art (40 tiles for animation)
            loadSonicMilesArt(rom);

            // Combine all patterns into VRAM-style array
            combinePatternsToVram(fontPatterns, menuBoxPatterns, levelSelectPicsPatterns);

            // Load Enigma-compressed mappings
            loadScreenLayout(rom);
            loadBackgroundLayout(rom);
            loadIconMappings(rom);

            // Modify screen layout with S3K zone names
            buildZoneText();

            // Load palettes
            loadMenuPalettes(rom);
            loadIconPalettes(rom);

            dataLoaded = true;
            return true;

        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load S3K level select data", e);
            return false;
        }
    }

    /**
     * Loads uncompressed SONICMILES animation art from ROM.
     * 40 tiles (4 unique frames x 10 tiles each) for the background animation.
     */
    private void loadSonicMilesArt(Rom rom) {
        try {
            byte[] raw = rom.readBytes(Sonic3kConstants.ART_UNC_SONICMILES_ADDR,
                    Sonic3kConstants.ART_UNC_SONICMILES_SIZE);
            int patternCount = raw.length / Pattern.PATTERN_SIZE_IN_ROM;
            sonicMilesPatterns = new Pattern[patternCount];
            for (int i = 0; i < patternCount; i++) {
                sonicMilesPatterns[i] = new Pattern();
                byte[] subArray = Arrays.copyOfRange(raw,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                sonicMilesPatterns[i].fromSegaFormat(subArray);
            }
            LOGGER.info("Loaded " + patternCount + " SONICMILES animation patterns");
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load SONICMILES art: " + e.getMessage());
            sonicMilesPatterns = new Pattern[0];
        }
    }

    /**
     * Combines loaded patterns into a VRAM-style array.
     * Indices match the VDP VRAM tile layout from the disassembly.
     */
    private void combinePatternsToVram(Pattern[] fontPatterns, Pattern[] menuBoxPatterns,
                                        Pattern[] levelSelectPicsPatterns) {
        int fontOffset = Sonic3kLevelSelectConstants.FONT_OFFSET;
        int menuBoxOffset = Sonic3kLevelSelectConstants.MENU_BOX_OFFSET;
        int picsOffset = Sonic3kLevelSelectConstants.LEVEL_SELECT_PICS_OFFSET;

        int fontLength = fontPatterns != null ? fontPatterns.length : 0;
        int menuBoxLength = menuBoxPatterns != null ? menuBoxPatterns.length : 0;
        int picsLength = levelSelectPicsPatterns != null ? levelSelectPicsPatterns.length : 0;

        int totalSize = 1;
        totalSize = Math.max(totalSize, 11); // SONICMILES tiles 0-10
        totalSize = Math.max(totalSize, fontOffset + fontLength);
        totalSize = Math.max(totalSize, menuBoxOffset + menuBoxLength);
        totalSize = Math.max(totalSize, picsOffset + picsLength);

        combinedPatterns = new Pattern[totalSize];

        // Fill with empty patterns
        Pattern emptyPattern = new Pattern();
        Arrays.fill(combinedPatterns, emptyPattern);

        // SONICMILES animation: tile 0 = blank, tiles 1-10 = initial frame
        if (sonicMilesPatterns != null && sonicMilesPatterns.length >= 10) {
            for (int i = 0; i < 10 && i < sonicMilesPatterns.length; i++) {
                if (sonicMilesPatterns[i] != null) {
                    combinedPatterns[1 + i] = sonicMilesPatterns[i];
                }
            }
        }

        // Font patterns at offset 0x10
        if (fontPatterns != null) {
            for (int i = 0; i < fontPatterns.length && (fontOffset + i) < combinedPatterns.length; i++) {
                if (fontPatterns[i] != null) {
                    combinedPatterns[fontOffset + i] = fontPatterns[i];
                }
            }
        }

        // Menu box patterns at offset 0x70
        if (menuBoxPatterns != null) {
            for (int i = 0; i < menuBoxPatterns.length && (menuBoxOffset + i) < combinedPatterns.length; i++) {
                if (menuBoxPatterns[i] != null) {
                    combinedPatterns[menuBoxOffset + i] = menuBoxPatterns[i];
                }
            }
        }

        // Level select pics at offset 0x90
        if (levelSelectPicsPatterns != null) {
            for (int i = 0; i < levelSelectPicsPatterns.length && (picsOffset + i) < combinedPatterns.length; i++) {
                if (levelSelectPicsPatterns[i] != null) {
                    combinedPatterns[picsOffset + i] = levelSelectPicsPatterns[i];
                }
            }
        }

        LOGGER.info("Combined " + combinedPatterns.length + " patterns for S3K level select");
    }

    /**
     * Loads the main screen layout (Plane A) from Enigma-compressed data.
     * This is the S2 level select layout that gets modified with S3K zone names.
     */
    private void loadScreenLayout(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic3kConstants.MAP_ENI_S2_LEV_SEL_ADDR, 1024);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel, 0);

                int wordCount = decompressed.length / 2;
                int planeSize = Sonic3kLevelSelectConstants.PLANE_WIDTH * Sonic3kLevelSelectConstants.PLANE_HEIGHT;
                screenLayout = new int[planeSize];
                ByteBuffer buf = ByteBuffer.wrap(decompressed);
                for (int i = 0; i < wordCount && i < planeSize; i++) {
                    screenLayout[i] = buf.getShort() & 0xFFFF;
                }

                LOGGER.info("Loaded screen layout: " + wordCount + " words from Enigma");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load screen layout: " + e.getMessage());
            screenLayout = new int[Sonic3kLevelSelectConstants.PLANE_WIDTH *
                    Sonic3kLevelSelectConstants.PLANE_HEIGHT];
        }
    }

    /**
     * Loads the background layout (Plane B) from Enigma-compressed data.
     * MapEni_S22POptions with base make_art_tile(0, 3, 0) = 0x6000 (palette line 3).
     */
    private void loadBackgroundLayout(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic3kConstants.MAP_ENI_S22P_OPTIONS_ADDR, 512);
            int baseTile = (3 & 0x3) << 13; // palette line 3 = 0x6000
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel, baseTile);

                int wordCount = decompressed.length / 2;
                int planeSize = Sonic3kLevelSelectConstants.PLANE_WIDTH * Sonic3kLevelSelectConstants.PLANE_HEIGHT;
                backgroundLayout = new int[planeSize];
                ByteBuffer buf = ByteBuffer.wrap(decompressed);
                for (int i = 0; i < wordCount && i < planeSize; i++) {
                    backgroundLayout[i] = buf.getShort() & 0xFFFF;
                }
                backgroundWidth = Sonic3kLevelSelectConstants.PLANE_WIDTH;
                backgroundHeight = Sonic3kLevelSelectConstants.PLANE_HEIGHT;

                LOGGER.info("Loaded background layout: " + wordCount + " words from Enigma");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load background layout: " + e.getMessage());
            backgroundLayout = new int[0];
            backgroundWidth = 0;
            backgroundHeight = 0;
        }
    }

    /**
     * Modifies the decoded screen layout with S3K zone names.
     * Matches the disasm init flow (s3.asm lines 8336-8393):
     * 1. Clear first 800 words (rows 0-19)
     * 2. Clear OOZ leftover text
     * 3. Write zone names at mapping offsets
     * 4. Write act numbers (1/2) below each zone name
     * 5. Replace last act "2" with "*" for sound test
     */
    private void buildZoneText() {
        if (screenLayout == null) {
            return;
        }

        int width = Sonic3kLevelSelectConstants.PLANE_WIDTH;

        // Clear first 800 words (rows 0-19) — preserves icon emblem area (rows 20-27)
        // From disasm: move.w #bytesToWcnt($320*2),d1 → clear $320 words = 800 words
        int clearCount = Math.min(800, screenLayout.length);
        Arrays.fill(screenLayout, 0, clearCount, 0);

        // Clear OOZ leftover at planeLocH28(3,$15) — 16 words
        // planeLocH28(3,$15) = $15 * 80 + 3 * 2 = 21 * 80 + 6 = 1686 byte offset
        // As word index: 1686 / 2 = 843
        int oozOffset = 21 * width + 3;
        for (int i = 0; i < 16 && (oozOffset + i) < screenLayout.length; i++) {
            screenLayout[oozOffset + i] = 0;
        }

        String[] zoneText = Sonic3kLevelSelectConstants.ZONE_TEXT;
        int[] mappingOffsets = Sonic3kLevelSelectConstants.MAPPING_OFFSETS;
        int textCount = Math.min(zoneText.length, mappingOffsets.length);

        for (int i = 0; i < textCount; i++) {
            // Convert byte offset to word index
            int wordIndex = mappingOffsets[i] / 2;
            if (wordIndex < 0 || wordIndex >= screenLayout.length) {
                continue;
            }

            String name = zoneText[i];
            int maxLen = Sonic3kLevelSelectConstants.ZONE_NAME_TOTAL_CHARS;

            // Write zone name characters
            for (int c = 0; c < name.length() && c <= maxLen; c++) {
                int tileIndex = Sonic3kLevelSelectConstants.charToTile(name.charAt(c));
                int pos = wordIndex + c;
                if (pos >= 0 && pos < screenLayout.length) {
                    screenLayout[pos] = tileIndex;
                }
            }

            // Pad remaining space with blanks (space = tile 0)
            int remaining = maxLen - name.length();
            for (int c = 0; c < remaining; c++) {
                int pos = wordIndex + name.length() + c;
                if (pos >= 0 && pos < screenLayout.length) {
                    screenLayout[pos] = Sonic3kLevelSelectConstants.charToTile(' ');
                }
            }

            // Write act "1" one tile past the max name length
            int act1Pos = wordIndex + maxLen;
            if (act1Pos >= 0 && act1Pos < screenLayout.length) {
                screenLayout[act1Pos] = Sonic3kLevelSelectConstants.charToTile('1');
            }

            // Write act "2" one row below act "1" (next row = +40 words)
            int act2Pos = act1Pos + width;
            if (act2Pos >= 0 && act2Pos < screenLayout.length) {
                screenLayout[act2Pos] = Sonic3kLevelSelectConstants.charToTile('2');
            }
        }

        // Sound test entry: replace act "2" with space, act "1" with "*"
        // The last text entry is "SOUND TEST  *" which already has the asterisk in the name.
        // But we also wrote act numbers after it. Fix: clear the act numbers for sound test.
        if (textCount > 0) {
            int lastOffset = mappingOffsets[textCount - 1] / 2;
            int act1Pos = lastOffset + Sonic3kLevelSelectConstants.ZONE_NAME_TOTAL_CHARS;
            // Replace act "1" with "*"
            if (act1Pos >= 0 && act1Pos < screenLayout.length) {
                screenLayout[act1Pos] = Sonic3kLevelSelectConstants.charToTile('*');
            }
            // Clear act "2"
            int act2Pos = act1Pos + width;
            if (act2Pos >= 0 && act2Pos < screenLayout.length) {
                screenLayout[act2Pos] = Sonic3kLevelSelectConstants.charToTile(' ');
            }
        }

        LOGGER.info("Built S3K zone text in screen layout");
    }

    /**
     * Loads the icon box mappings from Enigma-compressed data.
     */
    private void loadIconMappings(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic3kConstants.MAP_ENI_S2_LEV_SEL_ICON_ADDR, 256);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel,
                        Sonic3kLevelSelectConstants.LEVEL_SELECT_PICS_OFFSET);

                int wordCount = decompressed.length / 2;
                iconMappings = new int[wordCount];
                ByteBuffer buf = ByteBuffer.wrap(decompressed);
                for (int i = 0; i < wordCount; i++) {
                    iconMappings[i] = buf.getShort() & 0xFFFF;
                }

                // Icon box is 4 tiles wide x 3 tiles tall per icon
                iconMappingsWidth = 4;

                LOGGER.info("Loaded icon mappings: " + wordCount + " words");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load icon mappings: " + e.getMessage());
            iconMappings = new int[0];
            iconMappingsWidth = 0;
        }
    }

    /**
     * Loads the menu palettes from ROM (Pal_S2Menu, 4 palette lines).
     */
    private void loadMenuPalettes(Rom rom) {
        try {
            byte[] paletteData = rom.readBytes(Sonic3kConstants.PAL_S2_MENU_ADDR,
                    Sonic3kConstants.PAL_S2_MENU_SIZE);
            menuPalettes = new Palette[4];

            for (int line = 0; line < 4; line++) {
                menuPalettes[line] = new Palette();
                int offset = line * 32;
                if (offset + 32 <= paletteData.length) {
                    byte[] lineData = Arrays.copyOfRange(paletteData, offset, offset + 32);
                    menuPalettes[line].fromSegaFormat(lineData);
                }
            }

            LOGGER.info("Loaded 4 menu palette lines from ROM at 0x" +
                    Integer.toHexString(Sonic3kConstants.PAL_S2_MENU_ADDR));
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load menu palettes: " + e.getMessage());
            menuPalettes = new Palette[0];
        }
    }

    /**
     * Loads the 15 icon palettes from ROM (uncompressed).
     */
    private void loadIconPalettes(Rom rom) {
        try {
            byte[] paletteData = rom.readBytes(Sonic3kConstants.PAL_S2_LEVEL_ICONS_ADDR,
                    Sonic3kConstants.PAL_S2_LEVEL_ICONS_SIZE);
            iconPalettes = new Palette[15];

            for (int i = 0; i < 15; i++) {
                iconPalettes[i] = new Palette();
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
            S3kFrontendPaletteUploader.cacheAll(graphicsManager, menuPalettes);
        }

        // Cache all patterns
        int cachedCount = 0;
        for (int i = 0; i < combinedPatterns.length; i++) {
            if (combinedPatterns[i] != null) {
                graphicsManager.cachePatternTexture(combinedPatterns[i],
                        Sonic3kLevelSelectConstants.PATTERN_BASE + i);
                cachedCount++;
            }
        }

        LOGGER.info("Cached " + cachedCount + " S3K level select patterns to GPU");
        artCached = true;
    }

    // --- Accessors ---

    public int[] getScreenLayout() { return screenLayout; }
    public int[] getBackgroundLayout() { return backgroundLayout; }
    public int getBackgroundWidth() { return backgroundWidth; }
    public int getBackgroundHeight() { return backgroundHeight; }
    public int[] getIconMappings() { return iconMappings; }
    public int getIconMappingsWidth() { return iconMappingsWidth; }
    public Pattern[] getSonicMilesPatterns() { return sonicMilesPatterns; }

    public Palette getIconPalette(int iconIndex) {
        if (iconPalettes != null && iconIndex >= 0 && iconIndex < iconPalettes.length) {
            return iconPalettes[iconIndex];
        }
        return null;
    }

    public Palette getMenuPalette(int lineIndex) {
        if (menuPalettes != null && lineIndex >= 0 && lineIndex < menuPalettes.length) {
            return menuPalettes[lineIndex];
        }
        return null;
    }

    public boolean isDataLoaded() { return dataLoaded; }
    public boolean isArtCached() { return artCached; }

    public void resetCache() { artCached = false; }
}
