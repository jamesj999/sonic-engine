package com.openggf.game.sonic1.specialstage;

import com.openggf.data.Rom;
import com.openggf.level.Pattern;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.logging.Logger;

import static com.openggf.game.sonic1.constants.Sonic1Constants.*;

/**
 * Loads and caches Sonic 1 Special Stage data from the ROM.
 * All data is loaded lazily on first access.
 *
 * Layout format: Enigma-compressed layouts decompress to big-endian 16-bit words.
 * The low byte of each word is the block ID. The decompressed data is 0x40 bytes
 * wide; SS_Load copies it into v_ssblockbuffer, which starts at offset 0x1020 inside
 * the 0x4000-byte v_ssbuffer1 RAM window.
 */
public class Sonic1SpecialStageDataLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic1SpecialStageDataLoader.class.getName());

    private final Rom rom;

    private byte[][] stageLayouts;
    private int[][] startPositions;
    private byte[] ssPalette;
    private byte[] ssPaletteCycle1;
    private byte[] ssPaletteCycle2;

    // SS_BGLoad-generated plane tilemaps (64x64 words per plane)
    private byte[][] ssFgPlaneTilemaps; // planes 1..4 (indices 0..3)
    private byte[] ssBgPlane5Tilemap;
    private byte[] ssBgPlane6Tilemap;

    // Art pattern caches
    private Pattern[] wallPatterns;
    private Pattern[] bumperPatterns;
    private Pattern[] goalPatterns;
    private Pattern[] upDownPatterns;
    private Pattern[] rBlockPatterns;
    private Pattern[] oneUpPatterns;
    private Pattern[] emStarsPatterns;
    private Pattern[] redWhitePatterns;
    private Pattern[] ghostPatterns;
    private Pattern[] wBlockPatterns;
    private Pattern[] glassPatterns;
    private Pattern[] emeraldPatterns;
    private Pattern[][] zonePatterns; // [0..5]
    private Pattern[] bgCloudPatterns;
    private Pattern[] bgFishPatterns;
    private Pattern[] resultEmPatterns;

    public Sonic1SpecialStageDataLoader(Rom rom) {
        this.rom = rom;
    }

    /**
     * Gets the layout for a stage in special-stage RAM layout format.
     */
    public byte[] getStageLayout(int stageIndex) throws IOException {
        if (stageLayouts == null) {
            stageLayouts = new byte[SS_STAGE_COUNT][];
        }
        if (stageIndex < 0 || stageIndex >= SS_STAGE_COUNT) {
            throw new IllegalArgumentException("Stage index out of range: " + stageIndex);
        }
        if (stageLayouts[stageIndex] == null) {
            stageLayouts[stageIndex] = loadStageLayout(stageIndex);
        }
        return stageLayouts[stageIndex];
    }

    /**
     * Gets the start position for a stage.
     * @return int[]{x, y} in pixels
     */
    public int[] getStartPosition(int stageIndex) throws IOException {
        if (startPositions == null) {
            startPositions = new int[SS_STAGE_COUNT][];
            for (int i = 0; i < SS_STAGE_COUNT; i++) {
                int addr = SS_START_LOC_ADDR + i * 4;
                int x = rom.read16BitAddr(addr);
                int y = rom.read16BitAddr(addr + 2);
                startPositions[i] = new int[]{x, y};
            }
        }
        return startPositions[stageIndex];
    }

    /**
     * Gets the special stage palette (128 bytes = 4 palette lines).
     */
    public byte[] getSSPalette() throws IOException {
        return getSSPalette(PAL_SS_ADDR);
    }

    /**
     * Gets the special stage palette from a verified ROM address.
     */
    public byte[] getSSPalette(int addr) throws IOException {
        if (ssPalette == null) {
            ssPalette = rom.readBytes(addr, PAL_SS_SIZE);
            LOGGER.fine("Loaded SS palette from 0x" + Integer.toHexString(addr) + ": " + ssPalette.length + " bytes");
        }
        return ssPalette;
    }

    /**
     * Gets special stage palette cycle data table 1 (Pal_SSCyc1).
     */
    public byte[] getSSPaletteCycle1() throws IOException {
        if (ssPaletteCycle1 == null) {
            ssPaletteCycle1 = rom.readBytes(PAL_SS_CYC1_ADDR, PAL_SS_CYC1_SIZE);
            LOGGER.fine("Loaded SS palette cycle 1: " + ssPaletteCycle1.length + " bytes");
        }
        return ssPaletteCycle1;
    }

    /**
     * Gets special stage palette cycle data table 2 (Pal_SSCyc2).
     */
    public byte[] getSSPaletteCycle2() throws IOException {
        if (ssPaletteCycle2 == null) {
            ssPaletteCycle2 = rom.readBytes(PAL_SS_CYC2_ADDR, PAL_SS_CYC2_SIZE);
            LOGGER.fine("Loaded SS palette cycle 2: " + ssPaletteCycle2.length + " bytes");
        }
        return ssPaletteCycle2;
    }

    // ---- Art pattern loading ----

    public Pattern[] getWallPatterns() throws IOException {
        if (wallPatterns == null) {
            wallPatterns = loadNemesisPatterns(ART_NEM_SS_WALLS_ADDR, ART_NEM_SS_WALLS_SIZE);
            LOGGER.fine("Loaded SS wall art: " + wallPatterns.length + " patterns");
        }
        return wallPatterns;
    }

    public Pattern[] getBumperPatterns() throws IOException {
        if (bumperPatterns == null) {
            bumperPatterns = loadNemesisPatterns(ART_NEM_SS_BUMPER_ADDR, ART_NEM_SS_BUMPER_SIZE);
            LOGGER.fine("Loaded SS bumper art: " + bumperPatterns.length + " patterns");
        }
        return bumperPatterns;
    }

    public Pattern[] getGoalPatterns() throws IOException {
        if (goalPatterns == null) {
            goalPatterns = loadNemesisPatterns(ART_NEM_SS_GOAL_ADDR, ART_NEM_SS_GOAL_SIZE);
            LOGGER.fine("Loaded SS GOAL art: " + goalPatterns.length + " patterns");
        }
        return goalPatterns;
    }

    public Pattern[] getUpDownPatterns() throws IOException {
        if (upDownPatterns == null) {
            upDownPatterns = loadNemesisPatterns(ART_NEM_SS_UPDOWN_ADDR, ART_NEM_SS_UPDOWN_SIZE);
            LOGGER.fine("Loaded SS UP/DOWN art: " + upDownPatterns.length + " patterns");
        }
        return upDownPatterns;
    }

    public Pattern[] getRBlockPatterns() throws IOException {
        if (rBlockPatterns == null) {
            rBlockPatterns = loadNemesisPatterns(ART_NEM_SS_RBLOCK_ADDR, ART_NEM_SS_RBLOCK_SIZE);
            LOGGER.fine("Loaded SS R block art: " + rBlockPatterns.length + " patterns");
        }
        return rBlockPatterns;
    }

    public Pattern[] getOneUpPatterns() throws IOException {
        if (oneUpPatterns == null) {
            oneUpPatterns = loadNemesisPatterns(ART_NEM_SS_1UP_ADDR, ART_NEM_SS_1UP_SIZE);
            LOGGER.fine("Loaded SS 1UP art: " + oneUpPatterns.length + " patterns");
        }
        return oneUpPatterns;
    }

    public Pattern[] getEmStarsPatterns() throws IOException {
        if (emStarsPatterns == null) {
            emStarsPatterns = loadNemesisPatterns(ART_NEM_SS_EM_STARS_ADDR, ART_NEM_SS_EM_STARS_SIZE);
            LOGGER.fine("Loaded SS emerald stars art: " + emStarsPatterns.length + " patterns");
        }
        return emStarsPatterns;
    }

    public Pattern[] getRedWhitePatterns() throws IOException {
        if (redWhitePatterns == null) {
            redWhitePatterns = loadNemesisPatterns(ART_NEM_SS_RED_WHITE_ADDR, ART_NEM_SS_RED_WHITE_SIZE);
            LOGGER.fine("Loaded SS red-white art: " + redWhitePatterns.length + " patterns");
        }
        return redWhitePatterns;
    }

    public Pattern[] getGhostPatterns() throws IOException {
        if (ghostPatterns == null) {
            ghostPatterns = loadNemesisPatterns(ART_NEM_SS_GHOST_ADDR, ART_NEM_SS_GHOST_SIZE);
            LOGGER.fine("Loaded SS ghost art: " + ghostPatterns.length + " patterns");
        }
        return ghostPatterns;
    }

    public Pattern[] getWBlockPatterns() throws IOException {
        if (wBlockPatterns == null) {
            wBlockPatterns = loadNemesisPatterns(ART_NEM_SS_WBLOCK_ADDR, ART_NEM_SS_WBLOCK_SIZE);
            LOGGER.fine("Loaded SS W block art: " + wBlockPatterns.length + " patterns");
        }
        return wBlockPatterns;
    }

    public Pattern[] getGlassPatterns() throws IOException {
        if (glassPatterns == null) {
            glassPatterns = loadNemesisPatterns(ART_NEM_SS_GLASS_ADDR, ART_NEM_SS_GLASS_SIZE);
            LOGGER.fine("Loaded SS glass art: " + glassPatterns.length + " patterns");
        }
        return glassPatterns;
    }

    public Pattern[] getEmeraldPatterns() throws IOException {
        if (emeraldPatterns == null) {
            emeraldPatterns = loadNemesisPatterns(ART_NEM_SS_EMERALD_ADDR, ART_NEM_SS_EMERALD_SIZE);
            LOGGER.fine("Loaded SS emerald art: " + emeraldPatterns.length + " patterns");
        }
        return emeraldPatterns;
    }

    public Pattern[] getZonePatterns(int zoneIndex) throws IOException {
        if (zonePatterns == null) {
            zonePatterns = new Pattern[6][];
        }
        if (zoneIndex < 0 || zoneIndex >= 6) {
            throw new IllegalArgumentException("Zone index out of range: " + zoneIndex);
        }
        if (zonePatterns[zoneIndex] == null) {
            int[] addrs = {ART_NEM_SS_ZONE1_ADDR, ART_NEM_SS_ZONE2_ADDR, ART_NEM_SS_ZONE3_ADDR,
                    ART_NEM_SS_ZONE4_ADDR, ART_NEM_SS_ZONE5_ADDR, ART_NEM_SS_ZONE6_ADDR};
            int[] sizes = {ART_NEM_SS_ZONE1_SIZE, ART_NEM_SS_ZONE2_SIZE, ART_NEM_SS_ZONE3_SIZE,
                    ART_NEM_SS_ZONE4_SIZE, ART_NEM_SS_ZONE5_SIZE, ART_NEM_SS_ZONE6_SIZE};
            zonePatterns[zoneIndex] = loadNemesisPatterns(addrs[zoneIndex], sizes[zoneIndex]);
            LOGGER.fine("Loaded SS zone " + (zoneIndex + 1) + " art: " + zonePatterns[zoneIndex].length + " patterns");
        }
        return zonePatterns[zoneIndex];
    }

    public Pattern[] getBgCloudPatterns() throws IOException {
        if (bgCloudPatterns == null) {
            bgCloudPatterns = loadNemesisPatterns(ART_NEM_SS_BG_CLOUD_ADDR, ART_NEM_SS_BG_CLOUD_SIZE);
            LOGGER.fine("Loaded SS BG cloud art: " + bgCloudPatterns.length + " patterns");
        }
        return bgCloudPatterns;
    }

    public Pattern[] getBgFishPatterns() throws IOException {
        if (bgFishPatterns == null) {
            bgFishPatterns = loadNemesisPatterns(ART_NEM_SS_BG_FISH_ADDR, ART_NEM_SS_BG_FISH_SIZE);
            LOGGER.fine("Loaded SS BG fish art: " + bgFishPatterns.length + " patterns");
        }
        return bgFishPatterns;
    }

    // ---- BG/FG plane tilemap loading (SS_BGLoad parity) ----

    /**
     * Gets Special Stage FG plane tilemap 1..4 (64x64 words, big-endian).
     */
    public byte[] getFgPlaneTilemap(int planeNumber) throws IOException {
        if (planeNumber < 1 || planeNumber > 4) {
            throw new IllegalArgumentException("FG plane number out of range: " + planeNumber);
        }
        ensureSpecialStageBackgroundPlanesLoaded();
        return ssFgPlaneTilemaps[planeNumber - 1];
    }

    /**
     * Gets Special Stage BG plane 5 tilemap (64x64 words, big-endian).
     */
    public byte[] getBgPlane5Tilemap() throws IOException {
        ensureSpecialStageBackgroundPlanesLoaded();
        return ssBgPlane5Tilemap;
    }

    /**
     * Gets Special Stage BG plane 6 tilemap (64x64 words, big-endian).
     */
    public byte[] getBgPlane6Tilemap() throws IOException {
        ensureSpecialStageBackgroundPlanesLoaded();
        return ssBgPlane6Tilemap;
    }

    /**
     * Backwards-compatible aliases used by older callers:
     * BG1 => active BG plane 6, BG2 => active BG plane 5.
     */
    public byte[] getBgTilemap1() throws IOException {
        return getBgPlane6Tilemap();
    }

    public byte[] getBgTilemap2() throws IOException {
        return getBgPlane5Tilemap();
    }

    private byte[] loadEnigmaTilemap(int addr, int size, int startingArtTile) throws IOException {
        byte[] compressed = rom.readBytes(addr, size + 64);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return EnigmaReader.decompress(channel, startingArtTile);
        }
    }

    private void ensureSpecialStageBackgroundPlanesLoaded() throws IOException {
        if (ssFgPlaneTilemaps != null && ssBgPlane5Tilemap != null && ssBgPlane6Tilemap != null) {
            return;
        }

        // Simulated VRAM namespace used by SS_BGLoad path (word-addressed, 0x0000..0xFFFF bytes).
        short[] vramWords = new short[0x10000 / 2];

        // 1) Eni_SSBg1: birds/fish mappings (starting art tile make_art_tile(ArtTile_SS_Background_Fish,2,0)).
        byte[] fishSource = loadEnigmaTilemap(ENI_SS_BG1_ADDR, ENI_SS_BG1_SIZE, 0x4051);

        // SS_BGLoad loop: stamp 8x8 mapping chunks into 64x32 regions at $5000..$B000.
        int srcChunkOffset = 0x80; // a2 = v_ssbuffer1 + $80
        for (int d7 = 6; d7 >= 0; d7--) {
            int regionIndex = 6 - d7;
            int destBase = 0x5000 + regionIndex * 0x1000;
            int d4 = (d7 < 3) ? 1 : 0;

            for (int rowBlock = 0; rowBlock < 4; rowBlock++) {
                for (int colBlock = 0; colBlock < 8; colBlock++) {
                    d4 ^= 1;

                    int chunkOffset = srcChunkOffset;
                    boolean shouldWrite = true;

                    if (d4 == 0) {
                        if (d7 == 6) {
                            chunkOffset = 0; // Special case: first region alternates with chunk 0.
                        } else {
                            shouldWrite = false; // Skip write, leaving cleared cells.
                        }
                    }

                    if (shouldWrite) {
                        int chunkDest = destBase + rowBlock * 0x400 + colBlock * 0x10;
                        copy8x8ChunkToVram(fishSource, chunkOffset, vramWords, chunkDest);
                    }
                }
                d4 ^= 1;
            }

            srcChunkOffset += 0x80; // adda.w #$80,a2
        }

        // 2) Eni_SSBg2: clouds mapping, copied by SS_BGLoad to plane 5/6 namespaces.
        byte[] cloudSource = loadEnigmaTilemap(ENI_SS_BG2_ADDR, ENI_SS_BG2_SIZE, 0x4000);
        copyTilemapToVram(cloudSource, 0xC000, 64, 32, vramWords); // ArtTile_SS_Plane_5
        copyTilemapToVram(cloudSource, 0xD000, 64, 64, vramWords); // ArtTile_SS_Plane_5 + plane_size_64x32

        ssFgPlaneTilemaps = new byte[4][];
        ssFgPlaneTilemaps[0] = extract64x64Plane(vramWords, 0x4000); // Plane 1
        ssFgPlaneTilemaps[1] = extract64x64Plane(vramWords, 0x6000); // Plane 2
        ssFgPlaneTilemaps[2] = extract64x64Plane(vramWords, 0x8000); // Plane 3
        ssFgPlaneTilemaps[3] = extract64x64Plane(vramWords, 0xA000); // Plane 4
        ssBgPlane5Tilemap = extract64x64Plane(vramWords, 0xC000);    // Plane 5
        ssBgPlane6Tilemap = extract64x64Plane(vramWords, 0xE000);    // Plane 6

        LOGGER.fine("Built SS BG planes from disassembly logic: FG1-4 + BG5-6 (64x64 each)");
    }

    private void copy8x8ChunkToVram(byte[] source, int sourceOffset, short[] vramWords, int destVramAddr) {
        for (int y = 0; y < 8; y++) {
            int srcRowOffset = sourceOffset + y * 16;
            int destWordIndex = (destVramAddr >> 1) + y * 64;
            for (int x = 0; x < 8; x++) {
                int srcIdx = srcRowOffset + x * 2;
                short word = readWordSafe(source, srcIdx);
                int dstIdx = destWordIndex + x;
                if (dstIdx >= 0 && dstIdx < vramWords.length) {
                    vramWords[dstIdx] = word;
                }
            }
        }
    }

    private void copyTilemapToVram(byte[] source, int destVramAddr, int width, int height, short[] vramWords) {
        int srcWordIndex = 0;
        int srcWordCount = source.length / 2;
        for (int y = 0; y < height; y++) {
            int dstRow = (destVramAddr >> 1) + y * 64;
            for (int x = 0; x < width; x++) {
                short word = 0;
                if (srcWordIndex < srcWordCount) {
                    int srcByte = srcWordIndex * 2;
                    word = readWordSafe(source, srcByte);
                }
                int dst = dstRow + x;
                if (dst >= 0 && dst < vramWords.length) {
                    vramWords[dst] = word;
                }
                srcWordIndex++;
            }
        }
    }

    private byte[] extract64x64Plane(short[] vramWords, int baseVramAddr) {
        byte[] out = new byte[64 * 64 * 2];
        int outIdx = 0;
        int baseWord = baseVramAddr >> 1;
        for (int y = 0; y < 64; y++) {
            int row = baseWord + y * 64;
            for (int x = 0; x < 64; x++) {
                short word = 0;
                int idx = row + x;
                if (idx >= 0 && idx < vramWords.length) {
                    word = vramWords[idx];
                }
                out[outIdx++] = (byte) ((word >> 8) & 0xFF);
                out[outIdx++] = (byte) (word & 0xFF);
            }
        }
        return out;
    }

    private short readWordSafe(byte[] data, int byteOffset) {
        if (data == null || byteOffset < 0 || byteOffset + 1 >= data.length) {
            return 0;
        }
        int hi = data[byteOffset] & 0xFF;
        int lo = data[byteOffset + 1] & 0xFF;
        return (short) ((hi << 8) | lo);
    }

    // ---- Internal helpers ----

    private byte[] loadStageLayout(int stageIndex) throws IOException {
        // Read layout pointer
        int layoutAddr = rom.read32BitAddr(SS_LAYOUT_INDEX_ADDR + stageIndex * 4) & 0x00FFFFFF;
        LOGGER.fine("Loading SS layout " + (stageIndex + 1) + " from 0x" + Integer.toHexString(layoutAddr));

        // Read compressed data (generous size, Enigma reader stops at terminator)
        byte[] compressed = rom.readBytes(layoutAddr, 0x2000);
        byte[] enigmaOutput;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            // startingArtTile = 0 for SS layouts (block IDs, not VRAM tiles)
            enigmaOutput = EnigmaReader.decompress(channel, 0);
        }

        // ROM path (SS_Load):
        // 1) clear v_ssbuffer1..v_ssbuffer2 (0x4000 bytes)
        // 2) copy $1000 bytes from v_ssbuffer2 into v_ssblockbuffer
        //    with $80 stride and $40-byte row padding.
        final int sourceBytesToCopy = 0x1000;
        final int rows = SS_BLOCKBUFFER_ROWS;
        byte[] buffer = new byte[SS_LAYOUT_RAM_SIZE];

        int bytesAvailable = Math.min(sourceBytesToCopy, enigmaOutput.length);
        for (int row = 0; row < rows; row++) {
            int srcOff = row * SS_LAYOUT_COLS;
            int dstOff = SS_BLOCKBUFFER_OFFSET + row * SS_LAYOUT_STRIDE;
            if (srcOff >= bytesAvailable) {
                break;
            }
            int colsThisRow = Math.min(SS_LAYOUT_COLS, bytesAvailable - srcOff);
            System.arraycopy(enigmaOutput, srcOff, buffer, dstOff, colsThisRow);
        }

        LOGGER.fine("SS layout " + (stageIndex + 1) + ": decompressed=" + enigmaOutput.length +
                " bytes, copied=" + bytesAvailable +
                " bytes into blockbuffer@" + Integer.toHexString(SS_BLOCKBUFFER_OFFSET) +
                " (" + rows + "x" + SS_LAYOUT_COLS + ", stride " + SS_LAYOUT_STRIDE + ")");
        return buffer;
    }

    private Pattern[] loadNemesisPatterns(long romAddr, int compressedSize) throws IOException {
        // Read with padding for decompressor edge cases
        byte[] compressed = rom.readBytes(romAddr, compressedSize + 16);
        byte[] decompressed;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            decompressed = NemesisReader.decompress(channel);
        }
        return bytesToPatterns(decompressed);
    }

    private Pattern[] bytesToPatterns(byte[] data) {
        int patternCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(data, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }
}
