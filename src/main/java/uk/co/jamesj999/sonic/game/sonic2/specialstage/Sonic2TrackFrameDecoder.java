package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Decodes Sonic 2 Special Stage track mapping frames.
 *
 * The 56 track mapping BIN frames use a specialized 3-segment bitstream format:
 *
 * Frame structure:
 * - 4 bytes: segment 1 header (only lower 2 bytes used for length)
 * - N bytes: segment 1 data (bitflags)
 * - 4 bytes: segment 2 header (only lower 2 bytes used for length)
 * - N bytes: segment 2 data (uncompressed LUT indices)
 * - 4 bytes: segment 3 header (only lower 2 bytes used for length)
 * - N bytes: segment 3 data (RLE LUT indices)
 *
 * Segment purposes:
 * - Segment 1: Bitflags determining tile source (1=uncompressed, 0=RLE)
 * - Segment 2: Uncompressed mappings indexed into SSPNT_UncLUT
 * - Segment 3: RLE mappings indexed into SSPNT_RLELUT
 *
 * The decoder reads bits from segment 1 to determine which source to use,
 * then reads indices from segment 2 or 3 accordingly.
 */
public class Sonic2TrackFrameDecoder {
    private static final Logger LOGGER = Logger.getLogger(Sonic2TrackFrameDecoder.class.getName());

    // VDP plane is 128 cells wide internally, even in H32 mode
    public static final int VDP_PLANE_WIDTH = 128;
    // We need to return the full plane width so the renderer can apply H-Scroll
    // strips
    public static final int VISIBLE_COLS = VDP_PLANE_WIDTH;
    // Screen is 224 scanlines high. Using the 4-strip interleaving trick, this
    // corresponds
    // to 28 VDP rows (28 * 8 = 224).
    public static final int SCREEN_ROWS = 28;
    // VDP buffer holds 28 rows (full track frame)
    public static final int VDP_ROWS = SCREEN_ROWS;
    // Each VDP row is split into 4 strips via per-scanline horizontal scroll
    public static final int STRIPS_PER_VDP_ROW = 4;
    // Each strip is 2 scanlines high (8 scanlines per tile / 4 strips)
    public static final int SCANLINES_PER_STRIP = 2;

    // Total tiles in VDP buffer (28 rows × 128 columns)
    public static final int TOTAL_VDP_TILES = VDP_ROWS * VDP_PLANE_WIDTH;
    // Total tiles for screen display (28 rows × 128 columns - full plane)
    public static final int TOTAL_SCREEN_TILES = SCREEN_ROWS * VISIBLE_COLS;

    // Legacy constants for compatibility
    public static final int TILE_ROWS = SCREEN_ROWS;
    public static final int TILE_COLS = VISIBLE_COLS;
    public static final int TOTAL_TILES = TOTAL_SCREEN_TILES;
    public static final int VISIBLE_ROWS = SCREEN_ROWS;

    private static final int PALETTE_LINE_3 = 0x6000;
    private static final int HIGH_PRIORITY = 0x8000;
    private static final int RLE_FLAGS = PALETTE_LINE_3 | HIGH_PRIORITY; // $E000 for RLE tiles

    /**
     * Decodes a track frame into pattern descriptor words.
     *
     * @param frameData Raw track frame data from ROM
     * @param flipped   Whether to decode in flipped mode (right-to-left)
     * @return Array of pattern descriptor words (16-bit) for each tile position
     */
    public static int[] decodeFrame(byte[] frameData, boolean flipped) {
        return decodeFrame(frameData, flipped, false);
    }

    /**
     * Decodes a track frame with optional diagnostic output.
     *
     * The VDP plane is 128 cells wide internally (7 rows × 128 columns = 896
     * cells).
     * The Genesis uses per-scanline horizontal scroll to show different 32-column
     * strips at different vertical positions within each 8-scanline tile row:
     * - Scanlines 0-1: VDP columns 0-31 (strip 0, top of tile row)
     * - Scanlines 2-3: VDP columns 32-63 (strip 1)
     * - Scanlines 4-5: VDP columns 64-95 (strip 2)
     * - Scanlines 6-7: VDP columns 96-127 (strip 3, bottom of tile row)
     *
     * This maps 7 VDP rows to 28 strips.
     * However, the screen is 224 scanlines high, requiring 112 strips.
     * The pattern repeats every 28 strips (56 scanlines).
     */
    public static int[] decodeFrame(byte[] frameData, boolean flipped, boolean diagnose) {
        if (frameData == null || frameData.length < 12) {
            LOGGER.warning("Invalid frame data: null or too short");
            return new int[TOTAL_TILES];
        }

        // Decode into a buffer matching VDP plane layout (28 rows × 128 cells)
        int[] vdpBuffer = new int[TOTAL_VDP_TILES];
        Arrays.fill(vdpBuffer, 0);

        try {
            FrameSegments segments = parseSegments(frameData);
            if (segments == null) {
                if (diagnose)
                    LOGGER.info("DIAG: Failed to parse segments");
                return new int[TOTAL_TILES];
            }

            if (diagnose) {
                LOGGER.info(String.format("DIAG: Frame size=%d, seg1[%d+%d], seg2[%d+%d], seg3[%d+%d]",
                        frameData.length,
                        segments.seg1Start, segments.seg1Len,
                        segments.seg2Start, segments.seg2Len,
                        segments.seg3Start, segments.seg3Len));
                LOGGER.info(String.format("DIAG: Frame header: %02X %02X %02X %02X",
                        frameData[0] & 0xFF, frameData[1] & 0xFF,
                        frameData[2] & 0xFF, frameData[3] & 0xFF));
            }

            BitReader bitflagsReader = new BitReader(frameData, segments.seg1Start, segments.seg1Len);
            BitReader uncReader = new BitReader(frameData, segments.seg2Start, segments.seg2Len);
            BitReader rleReader = new BitReader(frameData, segments.seg3Start, segments.seg3Len);

            int vdpIndex = 0; // Position in 128-wide VDP buffer
            int rleCount = 0;
            int rlePattern = 0;
            int uncCount = 0, rleEntryCount = 0;
            int maxVdpIndex = TOTAL_VDP_TILES;

            while (vdpIndex < maxVdpIndex) {
                if (rleCount > 0) {
                    vdpBuffer[vdpIndex++] = rlePattern;
                    rleCount--;
                    continue;
                }

                int flag = bitflagsReader.readBit();
                if (flag == -1) {
                    if (diagnose)
                        LOGGER.info("DIAG: Bitflag stream exhausted at vdpIndex " + vdpIndex);
                    break;
                }

                if (flag == 1) {
                    int pattern = readUncompressedPattern(uncReader);
                    if (pattern != -1) {
                        vdpBuffer[vdpIndex++] = pattern;
                        uncCount++;
                    } else {
                        if (diagnose)
                            LOGGER.info("DIAG: UNC read failed at vdpIndex " + vdpIndex);
                        break;
                    }
                } else {
                    int cellsInRowBefore = vdpIndex % VDP_PLANE_WIDTH;
                    int[] rleEntry = readRleEntry(rleReader);
                    if (rleEntry != null && rleEntry[1] >= 0) {
                        rlePattern = rleEntry[0] | RLE_FLAGS;
                        // Genesis uses dbf which loops count+1 times
                        // We write first tile here, then rleCount more in the loop
                        // So rleCount should equal the LUT value (not value-1)
                        rleCount = rleEntry[1];
                        vdpBuffer[vdpIndex++] = rlePattern;
                        rleEntryCount++;
                    } else {
                        // EOL marker ($7F) - in Genesis, this just increments the line counter
                        // but does NOT advance the VDP write position. The VDP auto-increments
                        // and data is written sequentially. EOL is just a signal that a "line"
                        // of decode work is complete, used for the 7-lines-per-call limit.
                        // In our decoder, we process everything at once, so EOL is a no-op.
                        if (diagnose) {
                            int currentVdpRow = vdpIndex / VDP_PLANE_WIDTH;
                            int cellsInRow = vdpIndex % VDP_PLANE_WIDTH;
                            LOGGER.info("DIAG: EOL marker at row " + currentVdpRow + ", cell " + cellsInRow + " (no position change)");
                        }
                        // Don't advance vdpIndex - just continue to next entry
                    }
                }
            }

            if (diagnose) {
                int decodedVdpRows = (vdpIndex + VDP_PLANE_WIDTH - 1) / VDP_PLANE_WIDTH;
                LOGGER.info(String.format("DIAG: Decoded %d VDP cells (%d rows), %d unc, %d rle entries",
                        vdpIndex, decodedVdpRows, uncCount, rleEntryCount));

                // Count non-empty cells per row to diagnose alternating blank rows
                StringBuilder rowInfo = new StringBuilder("DIAG: Non-empty cells per row: ");
                for (int row = 0; row < Math.min(28, (vdpIndex + 127) / 128); row++) {
                    int rowStart = row * 128;
                    int nonEmpty = 0;
                    for (int col = 0; col < 128 && rowStart + col < vdpBuffer.length; col++) {
                        if ((vdpBuffer[rowStart + col] & 0x7FF) != 0) nonEmpty++;
                    }
                    rowInfo.append(String.format("%d:%d ", row, nonEmpty));
                }
                LOGGER.info(rowInfo.toString());
            }

            // Direct mapping: The buffer is exactly the plane data
            // If flipped, handle it.
            if (flipped) {
                // We need to flip rows.
                // Since we are returning the raw buffer, we can just flip the buffer.
                flipTilesHorizontally(vdpBuffer);
            }

            return vdpBuffer;

        } catch (Exception e) {
            LOGGER.warning("Error decoding frame: " + e.getMessage());
            if (diagnose)
                e.printStackTrace();
            return new int[TOTAL_TILES];
        }
    }

    /**
     * Decodes a track frame (non-flipped).
     */
    public static int[] decodeFrame(byte[] frameData) {
        return decodeFrame(frameData, false);
    }

    private static int lastUncIndex = -1; // For diagnostics

    /**
     * Reads an uncompressed pattern from the bitstream.
     *
     * From s2disasm (lines 8711-8713):
     * "if the first bit is set, 10 bits form an index into SSPNT_UncLUT_Part2,
     * otherwise 6 bits are used as an index into SSPNT_UncLUT"
     *
     * Per s2disasm comments on Read10/Read6 routines:
     * - Extended (flag=1): Read 10 more bits for index into Part2
     * - Non-extended (flag=0): Read 6 more bits for index into base table (0-63)
     */
    private static int readUncompressedPattern(BitReader reader) {
        int extendedBit = reader.readBit();
        if (extendedBit == -1)
            return -1;

        int index;
        if (extendedBit == 1) {
            // Extended: 10 bits after flag, then add Part2 offset
            // Per s2disasm: "Reads 10 bits from uncompressed mappings"
            index = reader.readBits(10);
            if (index == -1)
                return -1;
            index += Sonic2TrackLookupTables.UNC_LUT_PART2_OFFSET;
        } else {
            // Non-extended: 6 bits after flag, indexes base table (0-63)
            // Per s2disasm: "Reads 6 bits from uncompressed mappings", masks with $3F
            index = reader.readBits(6);
            if (index == -1)
                return -1;
        }

        lastUncIndex = index; // Save for diagnostics

        if (index >= 0 && index < Sonic2TrackLookupTables.UNC_LUT.length) {
            int pattern = Sonic2TrackLookupTables.UNC_LUT[index];
            return pattern | PALETTE_LINE_3;
        }
        return 0;
    }

    public static int getLastUncIndex() {
        return lastUncIndex;
    }

    private static int lastRleIndex = -1; // For diagnostics

    /**
     * Reads an RLE entry from the bitstream.
     *
     * From s2disasm analysis (SSTrackDrawRLE_Read6/Read7 routines):
     * - Extended (flag=1): 7 bits after flag form index into Part2 (mask $7F)
     * - Non-extended (flag=0): 6 bits after flag form index into base table (mask
     * $3F)
     *
     * End-of-line marker: In extended mode, value $7F signals end of line.
     * (A raw $FF byte at stream start also signals EOL, handled separately)
     */
    private static int[] readRleEntry(BitReader reader) {
        int extendedBit = reader.readBit();
        if (extendedBit == -1)
            return null;

        int index;
        if (extendedBit == 1) {
            // Extended: 7 bits after flag, indexes into Part2
            index = reader.readBits(7);
            if (index == -1)
                return null;
            // Check for end-of-line marker ($7F = max 7-bit value)
            if (index == 0x7F)
                return null;
            index += Sonic2TrackLookupTables.RLE_LUT_PART2_OFFSET;
        } else {
            // Non-extended: 6 bits after flag, indexes into base table (0-63)
            index = reader.readBits(6);
            if (index == -1)
                return null;
            // No EOL check for non-extended (EOL only in extended mode)
        }

        lastRleIndex = index; // Save for diagnostics

        if (index >= 0 && index < Sonic2TrackLookupTables.RLE_LUT.length) {
            return Sonic2TrackLookupTables.RLE_LUT[index];
        }

        return new int[] { 0, 1 };
    }

    public static int getLastRleIndex() {
        return lastRleIndex;
    }

    /**
     * Flips the tiles horizontally within each row.
     * Also toggles the horizontal flip bit in each pattern.
     */
    private static void flipTilesHorizontally(int[] tiles) {
        for (int row = 0; row < TILE_ROWS; row++) {
            int rowStart = row * TILE_COLS;
            for (int col = 0; col < TILE_COLS / 2; col++) {
                int leftIdx = rowStart + col;
                int rightIdx = rowStart + (TILE_COLS - 1 - col);

                int leftTile = tiles[leftIdx];
                int rightTile = tiles[rightIdx];

                tiles[leftIdx] = flipTileH(rightTile);
                tiles[rightIdx] = flipTileH(leftTile);
            }
        }
    }

    /**
     * Toggles the horizontal flip bit in a pattern word.
     */
    private static int flipTileH(int pattern) {
        return pattern ^ 0x0800;
    }

    /**
     * Parses the segment structure from frame data.
     */
    private static FrameSegments parseSegments(byte[] data) {
        if (data.length < 4)
            return null;

        int seg1Len = readWord(data, 2);
        int seg1Start = 4;

        int seg2HeaderOffset = seg1Start + seg1Len;
        if (seg2HeaderOffset + 4 > data.length)
            return null;

        int seg2Len = readWord(data, seg2HeaderOffset + 2);
        int seg2Start = seg2HeaderOffset + 4;

        int seg3HeaderOffset = seg2Start + seg2Len;
        if (seg3HeaderOffset + 4 > data.length)
            return null;

        int seg3Start = seg3HeaderOffset + 4;
        int seg3Len = data.length - seg3Start;

        return new FrameSegments(seg1Start, seg1Len, seg2Start, seg2Len, seg3Start, seg3Len);
    }

    private static int readWord(byte[] data, int offset) {
        if (offset + 1 >= data.length)
            return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /**
     * Gets frame segment information for debugging.
     */
    public static FrameInfo getFrameInfo(byte[] frameData) {
        if (frameData == null || frameData.length < 12) {
            return new FrameInfo(0, 0, 0, 0, 0, 0);
        }

        FrameSegments segments = parseSegments(frameData);
        if (segments == null) {
            return new FrameInfo(frameData.length, 0, 0, 0, 0, 0);
        }

        return new FrameInfo(
                frameData.length,
                segments.seg1Start,
                segments.seg1Len,
                segments.seg2Start,
                segments.seg2Len,
                segments.seg3Start);
    }

    private record FrameSegments(int seg1Start, int seg1Len, int seg2Start, int seg2Len, int seg3Start, int seg3Len) {
    }

    public record FrameInfo(int totalSize, int segment1Start, int segment1Len,
            int segment2Start, int segment2Len, int segment3Start) {
    }

    /**
     * Helper class for reading bits from a byte array.
     */
    private static class BitReader {
        private final byte[] data;
        private final int start;
        private final int end;
        private int bytePos;
        private int bitPos;

        BitReader(byte[] data, int start, int length) {
            this.data = data;
            this.start = start;
            this.end = start + length;
            this.bytePos = start;
            this.bitPos = 7;
        }

        /**
         * Reads a single bit from the stream.
         * 
         * @return 0 or 1, or -1 if end of stream
         */
        int readBit() {
            if (bytePos >= end || bytePos >= data.length) {
                return -1;
            }

            int bit = (data[bytePos] >> bitPos) & 1;

            bitPos--;
            if (bitPos < 0) {
                bitPos = 7;
                bytePos++;
            }

            return bit;
        }

        /**
         * Reads multiple bits from the stream.
         * 
         * @param numBits Number of bits to read (1-16)
         * @return The value, or -1 if end of stream
         */
        int readBits(int numBits) {
            int value = 0;
            for (int i = 0; i < numBits; i++) {
                int bit = readBit();
                if (bit == -1)
                    return -1;
                value = (value << 1) | bit;
            }
            return value;
        }
    }
}
