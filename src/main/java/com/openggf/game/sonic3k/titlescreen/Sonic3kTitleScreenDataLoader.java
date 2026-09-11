package com.openggf.game.sonic3k.titlescreen;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.S3kFrontendPaletteUploader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.util.PatternDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads all Sonic 3&K title screen ROM data and caches patterns/palettes to GPU.
 *
 * <p>The S3K title screen uses a multi-frame animation sequence showing Sonic
 * running toward the camera. There are 13 animation frames (indices 1-D), each
 * with Kosinski-compressed art, an Enigma-compressed plane mapping, and a palette.
 * Frames 1-7 share the same Kosinski art (Sonic1) but use different palettes and
 * mappings. Frames 8-D each have unique art.
 *
 * <p>Graphics loaded:
 * <ul>
 *   <li>ArtKos_S3TitleSonic1 through ArtKos_S3TitleSonicD (7 Kosinski art sets)</li>
 *   <li>ArtNem_Title_SonicSprites, ArtNem_Title_ANDKnuckles, ArtNem_Title_S3Banner,
 *       ArtNem_TitleScreenText (4 Nemesis sprite art sets)</li>
 * </ul>
 *
 * <p>Mappings loaded:
 * <ul>
 *   <li>MapEni_S3TitleSonic1 through MapEni_S3TitleSonicD (13 Enigma animation mappings)</li>
 *   <li>MapEni_S3TitleBg (Plane B background mapping)</li>
 * </ul>
 *
 * <p>Palettes loaded:
 * <ul>
 *   <li>Pal_Title transition (112 bytes, 7 colors x 8 steps)</li>
 *   <li>Per-frame palettes (Pal_TitleSonic1 through Pal_TitleSonicD)</li>
 *   <li>Pal_TitleWaterRot (32 bytes, banner palette cycling)</li>
 * </ul>
 */
public class Sonic3kTitleScreenDataLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kTitleScreenDataLoader.class.getName());

    /** Total animation frames (indices 1-D). */
    private static final int FRAME_COUNT = 13;

    /** Nametable dimensions (40x28 = standard VDP plane). */
    private static final int MAP_WIDTH = 40;
    private static final int MAP_HEIGHT = 28;

    /** Virtual pattern ID base for animation frame art. */
    static final int ANIM_PATTERN_BASE = PatternAtlasRange.S3K_TITLE_SCREEN_ANIMATION.base();

    /** Virtual pattern ID base for combined sprite art (Sonic sprites + &Knuckles + banner + menu). */
    static final int SPRITE_PATTERN_BASE = PatternAtlasRange.S3K_TITLE_SCREEN_SPRITES.base();

    // ---- Loaded animation data (indexed 0-12, representing frames 1-D) ----

    /** Kosinski-decompressed patterns per animation frame. */
    private final Pattern[][] animPatterns = new Pattern[FRAME_COUNT][];

    /** Enigma-decompressed nametable per animation frame (40x28 words). */
    private final int[][] animMappings = new int[FRAME_COUNT][];

    /** Per-frame palette data (raw bytes, 2 palette lines = 64 bytes for frames 1-B,
     *  64 bytes for frame C, 128 bytes for frame D). */
    private final byte[][] animPaletteData = new byte[FRAME_COUNT][];

    // ---- Background mapping ----

    /** Plane B background nametable (40x28 words). */
    private int[] backgroundMapping;

    // ---- Sprite art ----

    /** Combined sprite pattern array: index = (VRAM tile - 0x400). */
    private Pattern[] spritePatterns;

    // ---- Raw palette data ----

    /** Pal_Title transition data (112 bytes). */
    private byte[] palTransitionData;

    /** Pal_TitleWaterRot cycling data (32 bytes). */
    private byte[] waterRotData;

    // ---- State ----

    private boolean dataLoaded = false;
    private boolean artCached = false;

    // ---- ROM address tables for frame data lookup ----

    /** Kosinski art addresses indexed by frame (0=frame1, ..., 12=frameD).
     *  Frames 1-7 all use Sonic1 art. */
    private static final int[] FRAME_ART_ADDRS = {
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,  // frame 1
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,  // frame 2
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,  // frame 3
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,  // frame 4
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,  // frame 5
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,  // frame 6
            Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR,  // frame 7
            Sonic3kConstants.ART_KOS_TITLE_SONIC8_ADDR,  // frame 8
            Sonic3kConstants.ART_KOS_TITLE_SONIC9_ADDR,  // frame 9
            Sonic3kConstants.ART_KOS_TITLE_SONIC_A_ADDR, // frame A
            Sonic3kConstants.ART_KOS_TITLE_SONIC_B_ADDR, // frame B
            Sonic3kConstants.ART_KOS_TITLE_SONIC_C_ADDR, // frame C
            Sonic3kConstants.ART_KOS_TITLE_SONIC_D_ADDR, // frame D
    };

    /** Enigma mapping addresses indexed by frame (0=frame1, ..., 12=frameD). */
    private static final int[] FRAME_MAP_ADDRS = {
            Sonic3kConstants.MAP_ENI_TITLE_SONIC1_ADDR,  // frame 1
            Sonic3kConstants.MAP_ENI_TITLE_SONIC2_ADDR,  // frame 2
            Sonic3kConstants.MAP_ENI_TITLE_SONIC3_ADDR,  // frame 3
            Sonic3kConstants.MAP_ENI_TITLE_SONIC4_ADDR,  // frame 4
            Sonic3kConstants.MAP_ENI_TITLE_SONIC5_ADDR,  // frame 5
            Sonic3kConstants.MAP_ENI_TITLE_SONIC6_ADDR,  // frame 6
            Sonic3kConstants.MAP_ENI_TITLE_SONIC7_ADDR,  // frame 7
            Sonic3kConstants.MAP_ENI_TITLE_SONIC8_ADDR,  // frame 8
            Sonic3kConstants.MAP_ENI_TITLE_SONIC9_ADDR,  // frame 9
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_A_ADDR,  // frame A
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_B_ADDR,  // frame B
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_C_ADDR,  // frame C
            Sonic3kConstants.MAP_ENI_TITLE_SONIC_D_ADDR,  // frame D
    };

    /**
     * Loads all title screen data from ROM.
     *
     * @return true if data was loaded successfully
     */
    public boolean loadData() {
        if (dataLoaded) {
            return true;
        }

        RomManager romManager = GameServices.rom();
        if (!romManager.isRomAvailable()) {
            LOGGER.warning("ROM not available for S3K title screen data loading");
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
            // Load animation frame data (art, mappings, palettes)
            loadAnimationFrames(rom);

            // Load background mapping (Plane B)
            backgroundMapping = loadEnigmaMap(rom, Sonic3kConstants.MAP_ENI_TITLE_BG_ADDR,
                    0x4000, "TitleBg");

            // Load combined sprite art (4 Nemesis sets into one array)
            loadSpriteArt(rom);

            // Load raw palette data
            loadPaletteData(rom);

            dataLoaded = true;
            LOGGER.info("S3K title screen data loaded successfully");
            return true;

        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load S3K title screen data", e);
            return false;
        }
    }

    /**
     * Loads all 13 animation frames: Kosinski art, Enigma mappings, and palettes.
     *
     * <p>Frames 1-7 share the same Kosinski art (Sonic1) but each has a unique
     * Enigma mapping and palette offset. Frames 8-D each have unique art.
     */
    private void loadAnimationFrames(Rom rom) {
        // Cache the shared Sonic1 Kosinski art (frames 1-7 all use the same patterns)
        Pattern[] sonic1Art = null;
        try {
            sonic1Art = PatternDecompressor.kosinski(rom, Sonic3kConstants.ART_KOS_TITLE_SONIC1_ADDR);
            LOGGER.info("Loaded shared Sonic1 title art: " + sonic1Art.length + " patterns");
        } catch (IOException e) {
            LOGGER.warning("Failed to load Sonic1 title art: " + e.getMessage());
        }

        for (int i = 0; i < FRAME_COUNT; i++) {
            int frameIndex = i + 1; // frames are 1-based (1-D)

            // Load Kosinski art
            if (frameIndex <= 7) {
                // Frames 1-7 share Sonic1 art
                animPatterns[i] = sonic1Art;
            } else {
                // Frames 8-D have unique art
                try {
                    animPatterns[i] = PatternDecompressor.kosinski(rom, FRAME_ART_ADDRS[i]);
                    LOGGER.fine("Loaded title frame " + Integer.toHexString(frameIndex).toUpperCase() +
                            " art: " + (animPatterns[i] != null ? animPatterns[i].length : 0) + " patterns");
                } catch (IOException e) {
                    LOGGER.warning("Failed to load title frame " + Integer.toHexString(frameIndex).toUpperCase() +
                            " art: " + e.getMessage());
                }
            }

            // Load Enigma mapping — animation frames use startingArtTile=0;
            // palette/priority adjustments are handled at render time.
            // Frame D Plane A uses 0x8000 (priority bit set).
            int startingArtTile = (frameIndex == 0xD) ? 0x8000 : 0x0000;
            animMappings[i] = loadEnigmaMap(rom, FRAME_MAP_ADDRS[i], startingArtTile,
                    "TitleSonic" + Integer.toHexString(frameIndex).toUpperCase());

            // Load per-frame palette data
            loadFramePalette(rom, i, frameIndex);
        }
    }

    /**
     * Loads palette data for a single animation frame.
     *
     * <p>Frames 1-B: 64 bytes (2 palette lines) at PAL_TITLE_SONIC1_ADDR + (frameIndex-1) * 0x20.
     * Frame C: 64 bytes at its calculated address.
     * Frame D: 128 bytes (4 palette lines) at PAL_TITLE_SONIC_D_ADDR.
     */
    private void loadFramePalette(Rom rom, int arrayIndex, int frameIndex) {
        try {
            if (frameIndex == 0xD) {
                // Frame D: full 128 bytes (4 palette lines)
                animPaletteData[arrayIndex] = rom.readBytes(
                        Sonic3kConstants.PAL_TITLE_SONIC_D_ADDR,
                        Sonic3kConstants.PAL_TITLE_SONIC_D_SIZE);
            } else {
                // Frames 1-C: 64 bytes (2 palette lines) from base + offset
                int addr = Sonic3kConstants.PAL_TITLE_SONIC1_ADDR + (frameIndex - 1) * 0x20;
                animPaletteData[arrayIndex] = rom.readBytes(addr, 64);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load palette for title frame " +
                    Integer.toHexString(frameIndex).toUpperCase() + ": " + e.getMessage());
        }
    }

    /**
     * Loads 4 Nemesis-compressed sprite art sets into a combined pattern array.
     *
     * <p>Layout within the combined array uses VRAM-relative offsets from base tile $400:
     * <ul>
     *   <li>Offset $000: SonicSprites (VRAM $400)</li>
     *   <li>Offset $0C0: ANDKnuckles (VRAM $4C0)</li>
     *   <li>Offset $100: S3Banner (VRAM $500)</li>
     *   <li>Offset $280: TitleScreenText (VRAM $680)</li>
     * </ul>
     */
    private void loadSpriteArt(Rom rom) {
        Pattern[] sonicSprites = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_SONIC_SPRITES_ADDR, 65536, "Title_SonicSprites");
        Pattern[] andKnuckles = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_AND_KNUCKLES_ADDR, 8192, "Title_ANDKnuckles");
        Pattern[] banner = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_BANNER_ADDR, 65536, "Title_S3Banner");
        Pattern[] menuText = PatternDecompressor.nemesis(rom,
                Sonic3kConstants.ART_NEM_TITLE_SCREEN_TEXT_ADDR, 8192, "TitleScreenText");

        LOGGER.info("Loaded sprite art: SonicSprites=" + sonicSprites.length +
                ", ANDKnuckles=" + andKnuckles.length +
                ", Banner=" + banner.length +
                ", MenuText=" + menuText.length);

        // VRAM-relative offsets from base tile $400
        int sonicOffset = Sonic3kConstants.VRAM_TITLE_MISC - 0x400;           // $000
        int andKnucklesOffset = Sonic3kConstants.VRAM_TITLE_AND_KNUCKLES - 0x400; // $0C0
        int bannerOffset = Sonic3kConstants.VRAM_TITLE_BANNER - 0x400;         // $100
        int menuOffset = Sonic3kConstants.VRAM_TITLE_MENU - 0x400;             // $280

        // Calculate total size needed
        int totalSize = Math.max(menuOffset + menuText.length,
                Math.max(bannerOffset + banner.length,
                        Math.max(andKnucklesOffset + andKnuckles.length,
                                sonicOffset + sonicSprites.length)));

        spritePatterns = new Pattern[totalSize];

        // Place each set at its VRAM-relative offset
        System.arraycopy(sonicSprites, 0, spritePatterns, sonicOffset, sonicSprites.length);
        System.arraycopy(andKnuckles, 0, spritePatterns, andKnucklesOffset, andKnuckles.length);
        System.arraycopy(banner, 0, spritePatterns, bannerOffset, banner.length);
        System.arraycopy(menuText, 0, spritePatterns, menuOffset, menuText.length);

        LOGGER.info("Combined sprite array: " + totalSize + " entries");
    }

    /**
     * Loads raw palette data (transition, water rotation).
     */
    private void loadPaletteData(Rom rom) {
        try {
            palTransitionData = rom.readBytes(
                    Sonic3kConstants.PAL_TITLE_TRANSITION_ADDR,
                    Sonic3kConstants.PAL_TITLE_TRANSITION_SIZE);
            LOGGER.info("Loaded title transition palette: " + palTransitionData.length + " bytes");
        } catch (IOException e) {
            LOGGER.warning("Failed to load title transition palette: " + e.getMessage());
        }

        try {
            waterRotData = rom.readBytes(
                    Sonic3kConstants.PAL_TITLE_WATER_ROT_ADDR,
                    Sonic3kConstants.PAL_TITLE_WATER_ROT_SIZE);
            LOGGER.info("Loaded title water rotation palette: " + waterRotData.length + " bytes");
        } catch (IOException e) {
            LOGGER.warning("Failed to load title water rotation palette: " + e.getMessage());
        }
    }

    /**
     * Loads a single Enigma-compressed map from ROM.
     *
     * @param rom             the ROM to read from
     * @param address         ROM address of Enigma-compressed data
     * @param startingArtTile starting art tile value for Enigma decompression
     * @param name            descriptive name for logging
     * @return word array of nametable entries, or empty array on failure
     */
    private int[] loadEnigmaMap(Rom rom, int address, int startingArtTile, String name) {
        try {
            byte[] compressed = rom.readBytes(address, Sonic3kConstants.MAP_ENI_TITLE_READ_SIZE);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel, startingArtTile);
                return convertToWordArray(decompressed);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load " + name + " map: " + e.getMessage());
            return new int[0];
        }
    }

    /**
     * Converts a byte array of big-endian words into an int array.
     */
    private int[] convertToWordArray(byte[] data) {
        int wordCount = data.length / 2;
        int[] words = new int[wordCount];
        ByteBuffer buf = ByteBuffer.wrap(data);
        for (int i = 0; i < wordCount; i++) {
            words[i] = buf.getShort() & 0xFFFF;
        }
        return words;
    }

    // ===== GPU Caching Methods =====

    /**
     * Caches the current art and palettes to the GPU.
     * This is an initial cache that uploads sprite art.
     * Animation frame art is cached per-frame via {@link #cacheAnimationFrame(int)}.
     */
    public void cacheToGpu() {
        if (artCached || !dataLoaded) {
            return;
        }

        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        // Cache sprite patterns at SPRITE_PATTERN_BASE
        int spriteCachedCount = 0;
        if (spritePatterns != null) {
            for (int i = 0; i < spritePatterns.length; i++) {
                if (spritePatterns[i] != null) {
                    gm.cachePatternTexture(spritePatterns[i], SPRITE_PATTERN_BASE + i);
                    spriteCachedCount++;
                }
            }
        }

        LOGGER.info("Cached " + spriteCachedCount + " sprite patterns to GPU");
        artCached = true;
    }

    /**
     * Uploads patterns and palette for a specific animation frame to the GPU.
     *
     * <p>Patterns are cached at {@link #ANIM_PATTERN_BASE} + tile index.
     * Palettes are uploaded to VDP palette lines 0 and 1 (2 lines from frame data).
     *
     * @param index 1-based frame index (1 through 0xD)
     */
    public void cacheAnimationFrame(int index) {
        if (!dataLoaded || index < 1 || index > FRAME_COUNT) {
            return;
        }

        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        int arrayIdx = index - 1;

        // Cache patterns for this frame
        Pattern[] patterns = animPatterns[arrayIdx];
        if (patterns != null) {
            for (int i = 0; i < patterns.length; i++) {
                if (patterns[i] != null) {
                    gm.cachePatternTexture(patterns[i], ANIM_PATTERN_BASE + i);
                }
            }
        }

        // Cache palette for this frame
        byte[] palData = animPaletteData[arrayIdx];
        if (palData != null) {
            if (index == 0xD) {
                // Frame D: 128 bytes = 4 palette lines (lines 0-3)
                for (int line = 0; line < 4 && (line + 1) * Palette.PALETTE_SIZE_IN_ROM <= palData.length; line++) {
                    byte[] lineData = new byte[Palette.PALETTE_SIZE_IN_ROM];
                    System.arraycopy(palData, line * Palette.PALETTE_SIZE_IN_ROM, lineData, 0,
                            Palette.PALETTE_SIZE_IN_ROM);
                    S3kFrontendPaletteUploader.cacheLineFromBytes(gm, lineData, line);
                }
            } else {
                // Frames 1-C: 64 bytes = 2 palette lines (lines 0 and 1)
                for (int line = 0; line < 2 && (line + 1) * Palette.PALETTE_SIZE_IN_ROM <= palData.length; line++) {
                    byte[] lineData = new byte[Palette.PALETTE_SIZE_IN_ROM];
                    System.arraycopy(palData, line * Palette.PALETTE_SIZE_IN_ROM, lineData, 0,
                            Palette.PALETTE_SIZE_IN_ROM);
                    S3kFrontendPaletteUploader.cacheLineFromBytes(gm, lineData, line);
                }
            }
        }
    }

    /**
     * Uploads the final scene art (frame D patterns), full 4-line palette,
     * and sprite art to the GPU.
     *
     * <p>Called when the animation completes and the static title screen is shown.
     */
    public void cacheFinalScene() {
        if (!dataLoaded) {
            return;
        }

        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        // Cache frame D patterns (final scene background)
        int frameDIdx = FRAME_COUNT - 1; // index 12 = frame D
        Pattern[] patterns = animPatterns[frameDIdx];
        if (patterns != null) {
            for (int i = 0; i < patterns.length; i++) {
                if (patterns[i] != null) {
                    gm.cachePatternTexture(patterns[i], ANIM_PATTERN_BASE + i);
                }
            }
        }

        // Cache full 4-line palette from frame D
        byte[] palData = animPaletteData[frameDIdx];
        if (palData != null) {
            int lineCount = palData.length / Palette.PALETTE_SIZE_IN_ROM;
            for (int line = 0; line < lineCount; line++) {
                byte[] lineData = new byte[Palette.PALETTE_SIZE_IN_ROM];
                System.arraycopy(palData, line * Palette.PALETTE_SIZE_IN_ROM, lineData, 0,
                        Palette.PALETTE_SIZE_IN_ROM);
                S3kFrontendPaletteUploader.cacheLineFromBytes(gm, lineData, line);
            }
        }

        // Ensure sprite art is cached
        if (!artCached) {
            cacheToGpu();
        }
    }

    /**
     * Marks art as needing re-cache on next draw.
     */
    public void resetCache() {
        artCached = false;
    }

    // ===== Accessors =====

    /**
     * Returns the Enigma-decompressed nametable for an animation frame.
     *
     * @param frameIndex 1-based frame index (1 through 0xD)
     * @return 40x28 word array of nametable entries, or null if not loaded
     */
    public int[] getAnimationMapping(int frameIndex) {
        if (frameIndex < 1 || frameIndex > FRAME_COUNT) {
            return null;
        }
        return animMappings[frameIndex - 1];
    }

    /**
     * Returns the Enigma-decompressed Plane B background nametable.
     *
     * @return 40x28 word array of nametable entries, or null if not loaded
     */
    public int[] getBackgroundMapping() {
        return backgroundMapping;
    }

    /**
     * Returns the combined sprite pattern array.
     * Index = (VRAM tile - 0x400): SonicSprites at +$000, &Knuckles at +$0C0,
     * Banner at +$100, MenuText at +$280.
     *
     * @return sprite patterns array, or null if not loaded
     */
    public Pattern[] getSpritePatterns() {
        return spritePatterns;
    }

    /**
     * Returns raw Pal_Title transition data (112 bytes: 8 blocks of 14 bytes,
     * 7 colors per block, progressive fade to black).
     *
     * @return transition palette bytes, or null if not loaded
     */
    public byte[] getPalTransitionData() {
        return palTransitionData;
    }

    /**
     * Returns the raw palette data for a specific animation frame.
     * @param frameIndex 1-based frame index (1-D)
     * @return raw palette bytes (64 bytes for frames 1-C, 128 bytes for frame D), or null
     */
    public byte[] getAnimPaletteData(int frameIndex) {
        if (frameIndex < 1 || frameIndex > FRAME_COUNT) {
            return null;
        }
        return animPaletteData[frameIndex - 1];
    }

    /**
     * Returns raw Pal_TitleWaterRot cycling data (32 bytes for banner palette cycling).
     *
     * @return water rotation palette bytes, or null if not loaded
     */
    public byte[] getWaterRotData() {
        return waterRotData;
    }

    /**
     * Returns the raw palette data for frame D (128 bytes = 4 palette lines).
     * Used by the manager to build modified palette line 3 for water rotation cycling.
     */
    public byte[] getFrameDPaletteData() {
        if (animPaletteData == null || FRAME_COUNT < 1) {
            return null;
        }
        return animPaletteData[FRAME_COUNT - 1]; // Frame D is the last entry
    }

    /**
     * Returns the frame-D (final scene) pattern array — the same patterns the
     * interactive Plane B background indexes into. Used to sample a representative
     * background colour for the widescreen side bands.
     *
     * @return frame D patterns, or null if not loaded
     */
    public Pattern[] getFrameDPatterns() {
        if (animPatterns == null || FRAME_COUNT < 1) {
            return null;
        }
        return animPatterns[FRAME_COUNT - 1]; // Frame D is the last entry
    }

    /**
     * Returns the virtual pattern ID base for animation frame art.
     *
     * @return {@link #ANIM_PATTERN_BASE}
     */
    public int getAnimPatternBase() {
        return ANIM_PATTERN_BASE;
    }

    /**
     * Returns the virtual pattern ID base for sprite art.
     *
     * @return {@link #SPRITE_PATTERN_BASE}
     */
    public int getSpritePatternBase() {
        return SPRITE_PATTERN_BASE;
    }

    /**
     * Returns whether all ROM data has been loaded.
     */
    public boolean isDataLoaded() {
        return dataLoaded;
    }

    /**
     * Returns whether art has been cached to the GPU.
     */
    public boolean isArtCached() {
        return artCached;
    }
}
