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

    public static final int TILE_ROWS = 28;
    public static final int TILE_COLS = 32;
    public static final int TOTAL_TILES = TILE_ROWS * TILE_COLS;

    private static final int PALETTE_LINE_3 = 0x6000;

    /**
     * Decodes a track frame into pattern descriptor words.
     *
     * @param frameData Raw track frame data from ROM
     * @param flipped Whether to decode in flipped mode (right-to-left)
     * @return Array of pattern descriptor words (16-bit) for each tile position
     */
    public static int[] decodeFrame(byte[] frameData, boolean flipped) {
        if (frameData == null || frameData.length < 12) {
            LOGGER.warning("Invalid frame data: null or too short");
            return new int[TOTAL_TILES];
        }

        int[] tiles = new int[TOTAL_TILES];
        Arrays.fill(tiles, 0);

        try {
            FrameSegments segments = parseSegments(frameData);
            if (segments == null) {
                return tiles;
            }

            BitReader bitflagsReader = new BitReader(frameData, segments.seg1Start, segments.seg1Len);
            BitReader uncReader = new BitReader(frameData, segments.seg2Start, segments.seg2Len);
            BitReader rleReader = new BitReader(frameData, segments.seg3Start, segments.seg3Len);

            int tileIndex = 0;
            int rleCount = 0;
            int rlePattern = 0;

            while (tileIndex < TOTAL_TILES) {
                if (rleCount > 0) {
                    tiles[tileIndex++] = rlePattern;
                    rleCount--;
                    continue;
                }

                int flag = bitflagsReader.readBit();
                if (flag == -1) break;

                if (flag == 1) {
                    int pattern = readUncompressedPattern(uncReader);
                    if (pattern != -1) {
                        tiles[tileIndex++] = pattern;
                    } else {
                        break;
                    }
                } else {
                    int[] rleEntry = readRleEntry(rleReader);
                    if (rleEntry != null && rleEntry[1] > 0) {
                        rlePattern = rleEntry[0];
                        rleCount = rleEntry[1] - 1;
                        tiles[tileIndex++] = rlePattern;
                    } else {
                        break;
                    }
                }
            }

            if (flipped) {
                flipTilesHorizontally(tiles);
            }

        } catch (Exception e) {
            LOGGER.warning("Error decoding frame: " + e.getMessage());
        }

        return tiles;
    }

    /**
     * Decodes a track frame (non-flipped).
     */
    public static int[] decodeFrame(byte[] frameData) {
        return decodeFrame(frameData, false);
    }

    /**
     * Reads an uncompressed pattern from the bitstream.
     * Reads the extended bit first, then either 6 or 10 bits for the index.
     */
    private static int readUncompressedPattern(BitReader reader) {
        int extendedBit = reader.readBit();
        if (extendedBit == -1) return -1;

        int index;
        if (extendedBit == 1) {
            index = reader.readBits(9);
            if (index == -1) return -1;
            index += Sonic2TrackLookupTables.UNC_LUT_PART2_OFFSET;
        } else {
            index = reader.readBits(5);
            if (index == -1) return -1;
        }

        if (index >= 0 && index < Sonic2TrackLookupTables.UNC_LUT.length) {
            int pattern = Sonic2TrackLookupTables.UNC_LUT[index];
            return pattern | PALETTE_LINE_3;
        }
        return 0;
    }

    /**
     * Reads an RLE entry from the bitstream.
     * Reads the extended bit first, then either 6 or 7 bits for the index.
     * Returns null on end-of-line marker or error.
     */
    private static int[] readRleEntry(BitReader reader) {
        int extendedBit = reader.readBit();
        if (extendedBit == -1) return null;

        int index;
        if (extendedBit == 1) {
            index = reader.readBits(6);
            if (index == -1) return null;
            index += Sonic2TrackLookupTables.RLE_LUT_PART2_OFFSET;
        } else {
            index = reader.readBits(5);
            if (index == -1) return null;
        }

        if (index >= 0 && index < Sonic2TrackLookupTables.RLE_LUT.length) {
            return Sonic2TrackLookupTables.RLE_LUT[index];
        }

        return new int[] {0, 1};
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
        if (data.length < 4) return null;

        int seg1Len = readWord(data, 2);
        int seg1Start = 4;

        int seg2HeaderOffset = seg1Start + seg1Len;
        if (seg2HeaderOffset + 4 > data.length) return null;

        int seg2Len = readWord(data, seg2HeaderOffset + 2);
        int seg2Start = seg2HeaderOffset + 4;

        int seg3HeaderOffset = seg2Start + seg2Len;
        if (seg3HeaderOffset + 4 > data.length) return null;

        int seg3Start = seg3HeaderOffset + 4;
        int seg3Len = data.length - seg3Start;

        return new FrameSegments(seg1Start, seg1Len, seg2Start, seg2Len, seg3Start, seg3Len);
    }

    private static int readWord(byte[] data, int offset) {
        if (offset + 1 >= data.length) return 0;
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
            segments.seg3Start
        );
    }

    private record FrameSegments(int seg1Start, int seg1Len, int seg2Start, int seg2Len, int seg3Start, int seg3Len) {}

    public record FrameInfo(int totalSize, int segment1Start, int segment1Len,
                            int segment2Start, int segment2Len, int segment3Start) {}

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
         * @param numBits Number of bits to read (1-16)
         * @return The value, or -1 if end of stream
         */
        int readBits(int numBits) {
            int value = 0;
            for (int i = 0; i < numBits; i++) {
                int bit = readBit();
                if (bit == -1) return -1;
                value = (value << 1) | bit;
            }
            return value;
        }
    }
}
