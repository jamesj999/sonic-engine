package com.openggf.game.sonic1.titlescreen;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.titlescreen.SegaPaletteFade;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelConstants;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.util.PatternDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads Sonic 1 title screen graphics and data from ROM.
 *
 * <p>Graphics loaded:
 * <ul>
 *   <li>Nem_TitleFg - Title foreground patterns (Nemesis)</li>
 *   <li>Nem_TitleSonic - Sonic sprite patterns (Nemesis)</li>
 *   <li>Nem_TitleTM - TM symbol patterns (Nemesis)</li>
 *   <li>Nem_CreditText - Credit text font patterns (Nemesis)</li>
 *   <li>Eni_Title - Title foreground nametable (Enigma, 34x22)</li>
 * </ul>
 *
 * <p>Palettes loaded:
 * <ul>
 *   <li>Pal_Title - 4 palette lines (128 bytes)</li>
 *   <li>Pal_TitleCyc - Water palette cycle data (32 bytes)</li>
 * </ul>
 */
public class Sonic1TitleScreenDataLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic1TitleScreenDataLoader.class.getName());

    /** Pattern base ID for title foreground (Plane A). */
    static final int FG_PATTERN_BASE = PatternAtlasRange.SONIC1_TITLE_FOREGROUND.base();

    /** Pattern base ID for Sonic sprite art. */
    static final int SPRITE_PATTERN_BASE = PatternAtlasRange.SONIC1_TITLE_SPRITES.base();

    /** Pattern base ID for credit text font. */
    public static final int CREDIT_TEXT_PATTERN_BASE = PatternAtlasRange.SONIC1_CREDIT_TEXT.base();

    /** Pattern base ID for TM symbol. */
    static final int TM_PATTERN_BASE = PatternAtlasRange.SONIC1_TITLE_TM.base();

    /** Pattern base ID for GHZ background patterns. */
    static final int GHZ_PATTERN_BASE = PatternAtlasRange.SONIC1_TITLE_GHZ_BACKGROUND.base();

    /** Pattern base ID for the boot SEGA logo. */
    static final int SEGA_LOGO_PATTERN_BASE = PatternAtlasRange.SEGA_BOOT_LOGOS.base();

    // Plane A dimensions from PlaneEd: x-Size=0x22 (34), y-Size=0x16 (22)
    private static final int PLANE_A_WIDTH = 34;
    private static final int PLANE_A_HEIGHT = 22;

    // Sonic 1 block format constants (same as Sonic1Level)
    private static final int S1_CHUNKS_PER_BLOCK = 256; // 16x16 grid
    private static final int S1_BLOCK_SIZE_IN_ROM = S1_CHUNKS_PER_BLOCK * LevelConstants.BYTES_PER_CHUNK; // 512 bytes
    private static final int S1_GRID_SIDE = 16;

    // Loaded art patterns
    private Pattern[] fgPatterns;        // Title foreground
    private Pattern[] sonicPatterns;     // Sonic sprite
    private Pattern[] tmPatterns;        // TM symbol
    private Pattern[] creditTextPatterns; // Credit text font
    private Pattern[] segaLogoPatterns;   // Boot SEGA logo

    // GHZ background data
    private Pattern[] ghzPatterns;       // GHZ 8x8 patterns
    private Chunk[] ghzChunks;           // GHZ 16x16 chunks (from Enigma)
    private Block[] ghzBlocks;           // GHZ 256x256 blocks (from Kosinski, S1→S2 converted)
    private byte[] bgLayout;             // Background level layout (block IDs)
    private int bgLayoutWidth;           // Layout width in blocks
    private int bgLayoutHeight;          // Layout height in blocks

    // Pre-built Plane B nametable
    private int[] planeBMap;
    private int planeBWidth;             // Nametable width in tiles
    private int planeBHeight;            // Nametable height in tiles

    // Plane A nametable (Enigma-decoded)
    private int[] planeAMap;
    private int[] segaLogoMap;
    private int[] segaLogoScanMap;

    // Palettes (4 lines from Pal_Title, 32 bytes each)
    private Palette[] titlePaletteLines;
    private Palette[] segaLogoPalettes;
    private byte[] segaLogoBasePaletteData;
    private byte[] segaLogoScanPaletteData;
    private byte[] segaLogoFadePaletteData;
    private byte[] paletteCycleData;     // Raw Pal_TitleCyc data (32 bytes)

    private boolean dataLoaded = false;
    private boolean fgCached = false;
    private boolean spriteCached = false;
    private boolean creditTextCached = false;
    private boolean tmCached = false;
    private boolean ghzCached = false;
    private boolean palettesCached = false;
    private boolean segaLogoCached = false;
    private boolean segaLogoPaletteDirty = false;

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
            LOGGER.warning("ROM not available for S1 title screen data loading");
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
            // Load title foreground patterns
            fgPatterns = PatternDecompressor.nemesis(rom, Sonic1Constants.ART_NEM_TITLE_FG_ADDR, 8192, "TitleFg");
            LOGGER.info("Loaded S1 title FG patterns: " + (fgPatterns != null ? fgPatterns.length : 0));

            // Load Sonic sprite patterns
            sonicPatterns = PatternDecompressor.nemesis(rom, Sonic1Constants.ART_NEM_TITLE_SONIC_ADDR, 65536, "TitleSonic");
            LOGGER.info("Loaded S1 title Sonic patterns: " + (sonicPatterns != null ? sonicPatterns.length : 0));

            // Load TM patterns
            tmPatterns = PatternDecompressor.nemesis(rom, Sonic1Constants.ART_NEM_TITLE_TM_ADDR, 1024, "TitleTM");
            LOGGER.info("Loaded S1 title TM patterns: " + (tmPatterns != null ? tmPatterns.length : 0));

            // Load credit text patterns
            creditTextPatterns = PatternDecompressor.nemesis(rom, Sonic1Constants.ART_NEM_CREDIT_TEXT_ADDR, 4096, "CreditText");
            LOGGER.info("Loaded S1 credit text patterns: " + (creditTextPatterns != null ? creditTextPatterns.length : 0));

            // Load Enigma-compressed title foreground nametable
            loadPlaneAMap(rom);
            loadSegaLogoData(rom);

            // Load GHZ background data
            loadGhzPatterns(rom);
            loadGhzChunks(rom);
            loadGhzBlocks(rom);
            loadGhzBgLayout(rom);
            buildPlaneBMap();

            // Load palettes
            loadTitlePalette(rom);
            loadPaletteCycleData(rom);

            dataLoaded = true;
            return true;

        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load S1 title screen data", e);
            return false;
        }
    }

    /**
     * Caches foreground patterns to GPU.
     */
    public void cacheForegroundToGpu(GraphicsManager gm) {
        if (fgCached || fgPatterns == null) {
            return;
        }
        for (int i = 0; i < fgPatterns.length; i++) {
            gm.cachePatternTexture(fgPatterns[i], FG_PATTERN_BASE + i);
        }
        fgCached = true;
        LOGGER.info("Cached " + fgPatterns.length + " S1 title FG patterns to GPU");
    }

    /**
     * Caches Sonic sprite patterns to GPU.
     */
    public void cacheSpriteArtToGpu(GraphicsManager gm) {
        if (spriteCached || sonicPatterns == null) {
            return;
        }
        for (int i = 0; i < sonicPatterns.length; i++) {
            gm.cachePatternTexture(sonicPatterns[i], SPRITE_PATTERN_BASE + i);
        }
        spriteCached = true;
        LOGGER.info("Cached " + sonicPatterns.length + " S1 title Sonic patterns to GPU");
    }

    /**
     * Caches credit text patterns to GPU.
     */
    public void cacheCreditTextToGpu(GraphicsManager gm) {
        if (creditTextCached || creditTextPatterns == null) {
            return;
        }
        for (int i = 0; i < creditTextPatterns.length; i++) {
            gm.cachePatternTexture(creditTextPatterns[i], CREDIT_TEXT_PATTERN_BASE + i);
        }
        creditTextCached = true;
        LOGGER.info("Cached " + creditTextPatterns.length + " S1 credit text patterns to GPU");
    }

    /**
     * Uploads all 4 palette lines to the GPU.
     * Must be called before any pattern rendering, as the GPU palette texture
     * is required by GraphicsManager.renderPatternWithId().
     */
    public void cachePalettesToGpu(GraphicsManager gm) {
        cachePalettesToGpu(gm, SegaPaletteFade.Mode.NONE, 0);
    }

    /**
     * Uploads all 4 palette lines to the GPU through a Sonic 1 {@code PaletteFadeIn} /
     * {@code PaletteFadeOut} step. The ROM keeps the target colours in
     * {@code v_palette_fading} and rebuilds {@code v_palette} one channel step per
     * frame; here the stored lines stay untouched and the faded copy is what reaches
     * the GPU. {@code fadeSteps} is the number of {@code FadeIn_AddColor} /
     * {@code FadeOut_DecColor} passes already applied (0 = black for a fade-in, 0 =
     * full palette for a fade-out).
     */
    public void cachePalettesToGpu(GraphicsManager gm, SegaPaletteFade.Mode fadeMode, int fadeSteps) {
        if (titlePaletteLines == null) {
            return;
        }
        boolean uploadedAny = false;
        for (int line = 0; line < titlePaletteLines.length; line++) {
            Palette palette = resolveTitlePaletteLine(line, fadeMode, fadeSteps);
            if (palette == null) {
                continue;
            }
            gm.cachePaletteTexture(palette, line);
            uploadedAny = true;
        }
        if (uploadedAny && !palettesCached) {
            palettesCached = true;
            LOGGER.info("Cached S1 title palettes to GPU (4 lines)");
        }
    }

    /**
     * Caches TM patterns to GPU.
     */
    public void cacheTmToGpu(GraphicsManager gm) {
        if (tmCached || tmPatterns == null) {
            return;
        }
        for (int i = 0; i < tmPatterns.length; i++) {
            gm.cachePatternTexture(tmPatterns[i], TM_PATTERN_BASE + i);
        }
        tmCached = true;
        LOGGER.info("Cached " + tmPatterns.length + " S1 title TM patterns to GPU");
    }

    /**
     * Caches GHZ background patterns to GPU.
     */
    public void cacheGhzToGpu(GraphicsManager gm) {
        if (ghzCached || ghzPatterns == null) {
            return;
        }
        for (int i = 0; i < ghzPatterns.length; i++) {
            gm.cachePatternTexture(ghzPatterns[i], GHZ_PATTERN_BASE + i);
        }
        ghzCached = true;
        LOGGER.info("Cached " + ghzPatterns.length + " S1 GHZ patterns to GPU");
    }

    public void cacheSegaLogoToGpu(GraphicsManager gm, SegaPaletteFade.Mode fadeMode, int fadeSteps) {
        if (segaLogoPatterns == null) {
            return;
        }
        boolean fadeActive = fadeMode != null && fadeMode != SegaPaletteFade.Mode.NONE;
        if (segaLogoPalettes != null && (!segaLogoCached || segaLogoPaletteDirty || fadeActive)) {
            for (int line = 0; line < segaLogoPalettes.length; line++) {
                gm.cachePaletteTexture(resolveSegaLogoPaletteLine(line, fadeMode, fadeSteps), line);
            }
            segaLogoPaletteDirty = false;
        }
        if (!segaLogoCached) {
            for (int i = 0; i < segaLogoPatterns.length; i++) {
                gm.cachePatternTexture(segaLogoPatterns[i], SEGA_LOGO_PATTERN_BASE + i);
            }
            segaLogoCached = true;
        }
    }

    Palette resolveTitlePaletteLine(int line, SegaPaletteFade.Mode fadeMode, int fadeSteps) {
        if (titlePaletteLines == null || line < 0 || line >= titlePaletteLines.length) {
            return null;
        }
        return SegaPaletteFade.apply(titlePaletteLines[line], fadeMode, fadeSteps);
    }

    Palette resolveSegaLogoPaletteLine(int line, SegaPaletteFade.Mode fadeMode, int fadeSteps) {
        if (segaLogoPalettes == null || line < 0 || line >= segaLogoPalettes.length) {
            return null;
        }
        return SegaPaletteFade.apply(segaLogoPalettes[line], fadeMode, fadeSteps);
    }

    /**
     * Re-uploads a single palette line to the GPU.
     * Used for palette cycling (water animation).
     */
    public void rechachePaletteLine(GraphicsManager gm, int line) {
        if (titlePaletteLines == null || line < 0 || line >= titlePaletteLines.length) {
            return;
        }
        gm.cachePaletteTexture(titlePaletteLines[line], line);
    }


    /**
     * Loads the Plane A (title foreground) nametable from Enigma-compressed data.
     * The Enigma tilemap uses startingArtTile = ArtTile_Title_Foreground (0x200).
     */
    private void loadPlaneAMap(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic1Constants.MAP_ENI_TITLE_ADDR, 1024);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                // Starting art tile = 0, matching the disassembly:
                //   move.w  #0,d0
                //   bsr.w   EniDec
                // The Enigma data is encoded with absolute VDP tile values,
                // so pattern indices already include ArtTile_Title_Foreground (0x200).
                int startingArtTile = 0;
                byte[] decoded = EnigmaReader.decompress(channel, startingArtTile);
                // Convert byte pairs to int array of nametable words (big-endian)
                int wordCount = decoded.length / 2;
                planeAMap = new int[wordCount];
                for (int i = 0; i < wordCount; i++) {
                    planeAMap[i] = ((decoded[i * 2] & 0xFF) << 8) | (decoded[i * 2 + 1] & 0xFF);
                }
                LOGGER.info("Loaded S1 title Plane A map: " + decoded.length + " entries " +
                        "(expected " + (PLANE_A_WIDTH * PLANE_A_HEIGHT) + ")");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load Eni_Title: " + e.getMessage());
            planeAMap = new int[0];
        }
    }

    private void loadSegaLogoData(Rom rom) throws IOException {
        segaLogoPatterns = PatternDecompressor.nemesis(
                rom, Sonic1Constants.ART_NEM_SEGA_LOGO_ADDR, 8192, "S1SegaLogo");

        byte[] compressed = rom.readBytes(Sonic1Constants.MAP_ENI_SEGA_LOGO_ADDR, 1024);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            byte[] decoded = EnigmaReader.decompress(channel, 0);
            int wordCount = decoded.length / 2;
            int[] words = new int[wordCount];
            for (int i = 0; i < wordCount; i++) {
                words[i] = ((decoded[i * 2] & 0xFF) << 8) | (decoded[i * 2 + 1] & 0xFF);
            }
            segaLogoScanMap = new int[24 * 8];
            System.arraycopy(words, 0, segaLogoScanMap, 0, Math.min(segaLogoScanMap.length, words.length));
            segaLogoMap = new int[40 * 28];
            int foregroundOffset = 24 * 8;
            for (int i = 0; i < segaLogoMap.length && foregroundOffset + i < words.length; i++) {
                segaLogoMap[i] = words[foregroundOffset + i];
            }
        }

        segaLogoBasePaletteData = rom.readBytes(Sonic1Constants.PAL_SEGA_BG_ADDR, 128);
        segaLogoScanPaletteData = rom.readBytes(Sonic1Constants.PAL_SEGA_SCAN_ADDR, 12);
        segaLogoFadePaletteData = rom.readBytes(Sonic1Constants.PAL_SEGA_FADE_ADDR, 48);
        segaLogoPalettes = new Palette[4];
        for (int line = 0; line < segaLogoPalettes.length; line++) {
            segaLogoPalettes[line] = new Palette();
        }
        resetSegaLogoPaletteCycle();
        LOGGER.info("Loaded S1 SEGA boot logo art/map/palette");
    }

    public void resetSegaLogoPaletteCycle() {
        if (segaLogoPalettes == null || segaLogoBasePaletteData == null) {
            return;
        }
        for (int line = 0; line < segaLogoPalettes.length; line++) {
            byte[] lineData = Arrays.copyOfRange(
                    segaLogoBasePaletteData,
                    line * Palette.PALETTE_SIZE_IN_ROM,
                    (line + 1) * Palette.PALETTE_SIZE_IN_ROM);
            segaLogoPalettes[line].fromSegaFormat(lineData);
        }
        segaLogoPaletteDirty = true;
    }

    public boolean advanceSegaLogoPaletteCycle(SegaLogoPaletteCycleState state) {
        if (segaLogoPalettes == null || segaLogoScanPaletteData == null || segaLogoFadePaletteData == null) {
            return false;
        }
        if (!state.scanComplete) {
            advanceSegaLogoLightScan(state);
            segaLogoPaletteDirty = true;
            return true;
        }
        state.fadeDelay--;
        if (state.fadeDelay >= 0) {
            return true;
        }
        state.fadeDelay = 4;
        int fadeOffset = state.position + 12;
        if (fadeOffset >= 48) {
            return false;
        }
        state.position = fadeOffset;
        writeFadeInPaletteStep(fadeOffset);
        segaLogoPaletteDirty = true;
        return true;
    }

    private void advanceSegaLogoLightScan(SegaLogoPaletteCycleState state) {
        int dataOffset = 0;
        int colorsLeft = 5;
        int targetOffset = state.position;
        while (targetOffset < 0 && colorsLeft >= 0) {
            dataOffset += 2;
            colorsLeft--;
            targetOffset += 2;
        }
        while (colorsLeft >= 0) {
            if ((targetOffset & 0x1E) == 0) {
                targetOffset += 2;
            }
            if (targetOffset < 0x60 && dataOffset + 1 < segaLogoScanPaletteData.length) {
                setSegaLogoColorByByteOffset(0x20 + targetOffset, segaLogoScanPaletteData, dataOffset);
            }
            targetOffset += 2;
            dataOffset += 2;
            colorsLeft--;
        }

        int nextPosition = state.position + 2;
        if ((nextPosition & 0x1E) == 0) {
            nextPosition += 2;
        }
        if (nextPosition >= 0x64) {
            state.scanComplete = true;
            state.fadeDelay = 4;
            nextPosition = -12;
        }
        state.position = nextPosition;
    }

    private void writeFadeInPaletteStep(int fadeOffset) {
        for (int i = 0; i < 5; i++) {
            setSegaLogoColorByByteOffset(0x04 + i * 2, segaLogoFadePaletteData, fadeOffset + i * 2);
        }
        int fillOffset = fadeOffset + 10;
        for (int line = 1; line <= 3; line++) {
            for (int color = 1; color < Palette.PALETTE_SIZE; color++) {
                setSegaLogoColor(line, color, segaLogoFadePaletteData, fillOffset);
            }
        }
    }

    private void setSegaLogoColorByByteOffset(int paletteByteOffset, byte[] source, int sourceOffset) {
        int line = paletteByteOffset / Palette.PALETTE_SIZE_IN_ROM;
        int color = (paletteByteOffset % Palette.PALETTE_SIZE_IN_ROM) / Palette.BYTES_PER_COLOR;
        setSegaLogoColor(line, color, source, sourceOffset);
    }

    private void setSegaLogoColor(int line, int color, byte[] source, int sourceOffset) {
        if (line < 0 || line >= segaLogoPalettes.length
                || color < 0 || color >= Palette.PALETTE_SIZE
                || sourceOffset < 0 || sourceOffset + 1 >= source.length) {
            return;
        }
        segaLogoPalettes[line].getColor(color).fromSegaFormat(source, sourceOffset);
    }

    static final class SegaLogoPaletteCycleState {
        private int position = -10;
        private int fadeDelay = 0;
        private boolean scanComplete = false;

        void reset() {
            position = -10;
            fadeDelay = 0;
            scanComplete = false;
        }
    }

    /**
     * Loads the title palette (4 lines, 128 bytes).
     */
    private void loadTitlePalette(Rom rom) throws IOException {
        byte[] data = rom.readBytes(Sonic1Constants.PAL_TITLE_ADDR, 128);
        titlePaletteLines = new Palette[4];
        for (int line = 0; line < 4; line++) {
            titlePaletteLines[line] = new Palette();
            byte[] lineData = Arrays.copyOfRange(data,
                    line * Palette.PALETTE_SIZE_IN_ROM,
                    (line + 1) * Palette.PALETTE_SIZE_IN_ROM);
            titlePaletteLines[line].fromSegaFormat(lineData);
        }
        LOGGER.info("Loaded S1 title palette (4 lines)");
    }

    /**
     * Loads the palette cycle data for water animation.
     */
    private void loadPaletteCycleData(Rom rom) throws IOException {
        paletteCycleData = rom.readBytes(Sonic1Constants.PAL_TITLE_CYCLE_ADDR, 32);
        LOGGER.info("Loaded S1 title palette cycle data (" + paletteCycleData.length + " bytes)");
    }

    // ---- GHZ background loading ----

    /**
     * Loads GHZ 8x8 patterns (Nemesis compressed).
     */
    private void loadGhzPatterns(Rom rom) {
        ghzPatterns = PatternDecompressor.nemesis(rom, Sonic1Constants.ART_NEM_GHZ_1ST_ADDR, 16384, "GHZ_1st");
        LOGGER.info("Loaded GHZ patterns: " + (ghzPatterns != null ? ghzPatterns.length : 0));
    }

    /**
     * Loads GHZ 16x16 chunk mappings (Enigma compressed).
     * No collision data needed for title screen background rendering.
     */
    private void loadGhzChunks(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic1Constants.BLK16_GHZ_ADDR, 4096);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] chunkBuffer = EnigmaReader.decompress(channel, 0);
                int chunkCount = chunkBuffer.length / Chunk.CHUNK_SIZE_IN_ROM;
                ghzChunks = new Chunk[chunkCount];
                for (int i = 0; i < chunkCount; i++) {
                    ghzChunks[i] = new Chunk();
                    byte[] subArray = Arrays.copyOfRange(chunkBuffer, i * Chunk.CHUNK_SIZE_IN_ROM,
                            (i + 1) * Chunk.CHUNK_SIZE_IN_ROM);
                    // No collision needed for title screen rendering
                    ghzChunks[i].fromSegaFormat(subArray, 0, 0);
                }
                LOGGER.info("Loaded GHZ chunks: " + chunkCount);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load GHZ chunks: " + e.getMessage());
            ghzChunks = new Chunk[0];
        }
    }

    /**
     * Loads GHZ 256x256 block mappings (Kosinski compressed) and converts
     * from Sonic 1 block word format to Sonic 2 ChunkDesc format.
     * Block IDs are 1-based (ID 0 = empty).
     */
    private void loadGhzBlocks(Rom rom) {
        try {
            byte[] compressed = rom.readBytes(Sonic1Constants.BLK256_GHZ_ADDR, 16384);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] blockBuffer = KosinskiReader.decompress(channel);
                int dataBlockCount = blockBuffer.length / S1_BLOCK_SIZE_IN_ROM;

                // 1-based block IDs: index 0 = empty, data starts at index 1
                ghzBlocks = new Block[dataBlockCount + 1];
                ghzBlocks[0] = new Block(S1_GRID_SIDE); // Empty block for ID 0

                for (int i = 0; i < dataBlockCount; i++) {
                    ghzBlocks[i + 1] = new Block(S1_GRID_SIDE);
                    byte[] subArray = Arrays.copyOfRange(blockBuffer, i * S1_BLOCK_SIZE_IN_ROM,
                            (i + 1) * S1_BLOCK_SIZE_IN_ROM);
                    byte[] converted = convertS1BlockData(subArray);
                    ghzBlocks[i + 1].fromSegaFormat(converted, S1_CHUNKS_PER_BLOCK);
                }
                LOGGER.info("Loaded GHZ blocks: " + (dataBlockCount + 1) + " (1 empty + " + dataBlockCount + " data)");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load GHZ blocks: " + e.getMessage());
            ghzBlocks = new Block[0];
        }
    }

    /**
     * Converts Sonic 1 block word data to Sonic 2 ChunkDesc format.
     * S1: {@code 0SSY X0II IIII IIII}
     * S2: {@code SSTT YXII IIII IIII}
     */
    private byte[] convertS1BlockData(byte[] s1Data) {
        byte[] result = new byte[s1Data.length];
        for (int i = 0; i < s1Data.length; i += 2) {
            int s1Word = ((s1Data[i] & 0xFF) << 8) | (s1Data[i + 1] & 0xFF);

            int chunkIndex = s1Word & 0x03FF;
            boolean xFlip = (s1Word & 0x0800) != 0;
            boolean yFlip = (s1Word & 0x1000) != 0;
            int solidity = (s1Word >> 13) & 0x3;

            int s2Word = chunkIndex
                    | (xFlip ? 0x0400 : 0)
                    | (yFlip ? 0x0800 : 0)
                    | ((solidity & 0x3) << 12)
                    | ((solidity & 0x3) << 14);

            result[i] = (byte) ((s2Word >> 8) & 0xFF);
            result[i + 1] = (byte) (s2Word & 0xFF);
        }
        return result;
    }

    /**
     * Loads GHZ background level layout (uncompressed with 2-byte header).
     * Header: byte 0 = width-1, byte 1 = height-1.
     */
    private void loadGhzBgLayout(Rom rom) {
        try {
            byte[] header = rom.readBytes(Sonic1Constants.LEVEL_GHZ_BG_ADDR, 2);
            bgLayoutWidth = (header[0] & 0xFF) + 1;
            bgLayoutHeight = (header[1] & 0xFF) + 1;
            bgLayout = rom.readBytes(Sonic1Constants.LEVEL_GHZ_BG_ADDR + 2, bgLayoutWidth * bgLayoutHeight);

            // Strip loop flag (bit 7) from layout bytes
            for (int i = 0; i < bgLayout.length; i++) {
                bgLayout[i] = (byte) (bgLayout[i] & 0x7F);
            }

            LOGGER.info("Loaded GHZ BG layout: " + bgLayoutWidth + "x" + bgLayoutHeight);
        } catch (IOException e) {
            LOGGER.warning("Failed to load GHZ BG layout: " + e.getMessage());
            bgLayout = new byte[0];
            bgLayoutWidth = 0;
            bgLayoutHeight = 0;
        }
    }

    /**
     * Builds a pre-computed Plane B nametable covering the full scroll range.
     *
     * <p>For each tile position, resolves block → chunk → pattern to produce
     * a flat array of 16-bit pattern descriptor words.
     *
     * <p>Height: 32 tiles (256px, one S1 block tall).
     * Width: enough tiles for 320px screen + scroll range + margin.
     * The BG layout wraps horizontally.
     */
    private void buildPlaneBMap() {
        if (ghzBlocks == null || ghzBlocks.length == 0 ||
                ghzChunks == null || ghzChunks.length == 0 ||
                bgLayout == null || bgLayout.length == 0) {
            planeBMap = new int[0];
            planeBWidth = 0;
            planeBHeight = 0;
            return;
        }

        // S1 blocks are 256x256px = 32x32 tiles
        // BG layout wraps horizontally. We need enough tiles to cover
        // 320px screen + ~760px of scroll + some margin.
        // Total scroll range ~1080px = 135 tiles. Round up to a safe width.
        // The layout wraps, so we just need to cover the maximum visible area.
        planeBHeight = 32; // One S1 block tall (256px)

        // Use enough width for screen + max scroll. The layout wraps via modulo.
        // bgLayoutWidth blocks * 32 tiles/block gives the natural repeat period.
        planeBWidth = bgLayoutWidth * 32;

        planeBMap = new int[planeBWidth * planeBHeight];

        ChunkDesc reusableChunkDesc = new ChunkDesc();

        for (int tileY = 0; tileY < planeBHeight; tileY++) {
            for (int tileX = 0; tileX < planeBWidth; tileX++) {
                // Which block in the layout (wrapping X, BG is typically 1 row tall)
                int blockCol = (tileX / 32) % bgLayoutWidth;
                int blockRow = (tileY / 32) % bgLayoutHeight;
                int blockId = bgLayout[blockRow * bgLayoutWidth + blockCol] & 0xFF;

                if (blockId == 0 || blockId >= ghzBlocks.length) {
                    planeBMap[tileY * planeBWidth + tileX] = 0;
                    continue;
                }

                Block block = ghzBlocks[blockId];

                // Position within the 256x256 block (in chunks: 16x16 grid)
                int chunkX = (tileX % 32) / 2; // 0-15
                int chunkY = (tileY % 32) / 2; // 0-15

                ChunkDesc chunkDesc = block.getChunkDesc(chunkX, chunkY);
                if (chunkDesc == null) {
                    planeBMap[tileY * planeBWidth + tileX] = 0;
                    continue;
                }

                int chunkIndex = chunkDesc.getChunkIndex();
                if (chunkIndex < 0 || chunkIndex >= ghzChunks.length) {
                    planeBMap[tileY * planeBWidth + tileX] = 0;
                    continue;
                }

                Chunk chunk = ghzChunks[chunkIndex];

                // Pattern position within the 16x16 chunk (2x2 patterns)
                int patX = tileX % 2;
                int patY = tileY % 2;

                // Apply chunk-level H/V flip to swap pattern coordinates
                boolean chunkHFlip = chunkDesc.getHFlip();
                boolean chunkVFlip = chunkDesc.getVFlip();
                int effectivePatX = chunkHFlip ? (1 - patX) : patX;
                int effectivePatY = chunkVFlip ? (1 - patY) : patY;

                PatternDesc patternDesc = chunk.getPatternDesc(effectivePatX, effectivePatY);
                if (patternDesc == null) {
                    planeBMap[tileY * planeBWidth + tileX] = 0;
                    continue;
                }

                // Compose final pattern descriptor word:
                // XOR chunk flip flags with pattern flip flags
                int word = patternDesc.get();
                if (chunkHFlip) {
                    word ^= 0x0800; // Toggle H flip (bit 11)
                }
                if (chunkVFlip) {
                    word ^= 0x1000; // Toggle V flip (bit 12)
                }

                planeBMap[tileY * planeBWidth + tileX] = word;
            }
        }

        LOGGER.info("Built Plane B nametable: " + planeBWidth + "x" + planeBHeight +
                " (" + (planeBWidth * planeBHeight) + " entries)");
    }

    // ---- Accessors ----

    public int[] getPlaneAMap() {
        return planeAMap;
    }

    public int getPlaneAWidth() {
        return PLANE_A_WIDTH;
    }

    public int getPlaneAHeight() {
        return PLANE_A_HEIGHT;
    }

    public Palette getTitlePaletteLine(int line) {
        if (titlePaletteLines == null || line < 0 || line >= titlePaletteLines.length) {
            return null;
        }
        return titlePaletteLines[line];
    }

    public byte[] getPaletteCycleData() {
        return paletteCycleData;
    }

    public int[] getPlaneBMap() {
        return planeBMap;
    }

    public int getPlaneBWidth() {
        return planeBWidth;
    }

    public int getPlaneBHeight() {
        return planeBHeight;
    }

    public Pattern[] getSonicPatterns() {
        return sonicPatterns;
    }

    public Pattern[] getTmPatterns() {
        return tmPatterns;
    }

    public Pattern[] getCreditTextPatterns() {
        return creditTextPatterns;
    }

    public int[] getSegaLogoMap() {
        return segaLogoMap;
    }

    public int[] getSegaLogoScanMap() {
        return segaLogoScanMap;
    }

    public int getSegaLogoScanWidth() {
        return 24;
    }

    public int getSegaLogoScanHeight() {
        return 8;
    }

    public int getSegaLogoWidth() {
        return 40;
    }

    public int getSegaLogoHeight() {
        return 28;
    }

    public boolean isDataLoaded() {
        return dataLoaded;
    }

    /**
     * Resets the cached state to force re-upload on next draw.
     */
    public void resetCache() {
        fgCached = false;
        spriteCached = false;
        creditTextCached = false;
        tmCached = false;
        ghzCached = false;
        palettesCached = false;
        segaLogoCached = false;
    }
}
