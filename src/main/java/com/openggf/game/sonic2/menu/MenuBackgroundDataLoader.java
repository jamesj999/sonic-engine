package com.openggf.game.sonic2.menu;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.data.compression.EnigmaReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the Sonic/Miles menu background art and mappings from ROM.
 * Shared by menus (Level Select, Options, etc).
 */
public class MenuBackgroundDataLoader {
    private static final Logger LOGGER = Logger.getLogger(MenuBackgroundDataLoader.class.getName());

    private Pattern[] menuBackPatterns;
    private int[] menuBackMappings;
    private int menuBackWidth;
    private int menuBackHeight;

    private boolean dataLoaded = false;
    private boolean artCached = false;

    public boolean loadData() {
        if (dataLoaded) {
            return true;
        }

        RomManager romManager = GameServices.rom();
        if (!romManager.isRomAvailable()) {
            LOGGER.warning("ROM not available for menu background loading");
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
            menuBackPatterns = loadUncompressedPatterns(rom, Sonic2Constants.ART_UNC_MENU_BACK_ADDR,
                    Sonic2Constants.ART_UNC_MENU_BACK_SIZE);
            loadMenuBackMappings(rom);

            dataLoaded = true;
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load menu background data", e);
            return false;
        }
    }

    private Pattern[] loadUncompressedPatterns(Rom rom, int address, int length) throws IOException {
        byte[] raw = rom.readBytes(address, length);
        int patternCount = raw.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            Pattern pattern = new Pattern();
            byte[] subArray = Arrays.copyOfRange(raw,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            pattern.fromSegaFormat(subArray);
            patterns[i] = pattern;
        }
        return patterns;
    }

    private void loadMenuBackMappings(Rom rom) throws IOException {
        byte[] compressed = rom.readBytes(Sonic2Constants.MAP_ENI_MENU_BACK_ADDR,
                Sonic2Constants.MAP_ENI_MENU_BACK_SIZE);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            byte[] decompressed = EnigmaReader.decompress(channel, makeArtTile(0, 3));
            int wordCount = decompressed.length / 2;
            menuBackMappings = new int[wordCount];
            ByteBuffer buf = ByteBuffer.wrap(decompressed);
            for (int i = 0; i < wordCount; i++) {
                menuBackMappings[i] = buf.getShort() & 0xFFFF;
            }
            menuBackWidth = 40;
            menuBackHeight = wordCount / menuBackWidth;
        }
    }

    private static int makeArtTile(int tileIndex, int paletteLine) {
        return ((paletteLine & 0x3) << 13) | (tileIndex & 0x7FF);
    }

    /**
     * Number of VRAM tiles used by the menu background.
     * The animation writes to VRAM tiles 1-10, so only tiles 0-10 are valid.
     * Tiles 11+ in the Enigma mapping should be blank (they exist in the animation
     * source data but are not meant to be displayed statically).
     */
    private static final int VRAM_TILE_COUNT = 11;

    public void cacheToGpu(GraphicsManager graphicsManager, int patternBase, int patternOffset) {
        if (artCached || !dataLoaded || menuBackPatterns == null) {
            return;
        }
        if (graphicsManager == null || graphicsManager.isHeadlessMode()) {
            return;
        }

        // Original game VRAM layout:
        // - VRAM tile 0: BLANK (uninitialized/transparent)
        // - VRAM tiles 1-10: Animated (written by animation system each frame)
        // - VRAM tiles 11+: BLANK (unused)
        //
        // The animation declaration "zoneanimdecl -1, ArtUnc_MenuBack, 1, 6, $A" means
        // write to VRAM starting at tile 1 (not 0!), with 10 tiles per frame.
        //
        // Animation source data (ArtUnc_MenuBack = 40 tiles):
        // - Tiles 0-9: Frame 0
        // - Tiles 10-19: Frame 1
        // - Tiles 20-29: Frame 2
        // - Tiles 30-39: Frame 3

        // VRAM tile 0 is BLANK in the original game - cache a blank pattern at index 0.
        Pattern blankPattern = new Pattern();
        graphicsManager.cachePatternTexture(blankPattern, patternBase + patternOffset + 0);

        // Cache tiles 1-10 from animation source as initial values.
        // Source tile i-1 → VRAM tile i (source tiles 0-9 map to VRAM tiles 1-10).
        // The animator will overwrite these with the current frame.
        for (int i = 1; i <= 10 && (i - 1) < menuBackPatterns.length; i++) {
            if (menuBackPatterns[i - 1] != null) {
                graphicsManager.cachePatternTexture(menuBackPatterns[i - 1], patternBase + patternOffset + i);
            }
        }

        // Cache blank patterns for indices 11+ (unused in original VRAM)
        for (int i = 11; i < 16; i++) {
            graphicsManager.cachePatternTexture(blankPattern, patternBase + patternOffset + i);
        }

        artCached = true;
        LOGGER.info("Cached menu background patterns to GPU (tile 0=blank, 1-10=anim, 11-15=blank)");
    }

    public Pattern[] getMenuBackPatterns() {
        return menuBackPatterns;
    }

    public int[] getMenuBackMappings() {
        return menuBackMappings;
    }

    public int getMenuBackWidth() {
        return menuBackWidth;
    }

    public int getMenuBackHeight() {
        return menuBackHeight;
    }

    public boolean isDataLoaded() {
        return dataLoaded;
    }

    public void resetCache() {
        artCached = false;
    }
}
