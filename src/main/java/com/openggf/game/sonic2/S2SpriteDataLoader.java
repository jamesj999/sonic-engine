package com.openggf.game.sonic2;

import com.openggf.data.RomByteReader;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable static utilities for loading S2 sprite mapping data from ROM.
 *
 * <p>S2 uses 8-byte mapping pieces (includes 2P tile word) and standard
 * word-offset tables. Normally the first word is the offset to frame 0's
 * data; frame count = firstOffset / 2. Some tables (obj51, obj52_b, obj55)
 * have a null frame 0 that self-references offset 0; in that case the
 * second word gives the true table size.
 *
 * <p>Piece format (8 bytes):
 * <pre>
 *   [y:s8] [size:u8] [1P_tileWord:u16] [2P_tileWord:u16 skip] [x:s16]
 * </pre>
 *
 * <p>Unlike S3K (6-byte pieces, no 2P word), S2 also parses the priority bit
 * (bit 15 of tileWord).
 */
public final class S2SpriteDataLoader {

    private S2SpriteDataLoader() {}

    /**
     * Loads S2 mapping frames from ROM.
     *
     * @param reader      ROM byte reader
     * @param mappingAddr ROM address of mapping table
     * @return list of mapping frames
     */
    public static List<SpriteMappingFrame> loadMappingFrames(RomByteReader reader, int mappingAddr) {
        return loadMappingFramesWithTileOffset(reader, mappingAddr, 0);
    }

    /**
     * Loads S2 mapping frames with a tile index offset applied to each piece.
     * Supports signed 16-bit frame offsets (negative offsets reference other tables).
     *
     * @param reader      ROM byte reader
     * @param mappingAddr ROM address of mapping table
     * @param tileOffset  offset to add to each piece's tile index
     * @return list of mapping frames
     */
    public static List<SpriteMappingFrame> loadMappingFramesWithTileOffset(
            RomByteReader reader, int mappingAddr, int tileOffset) {
        int firstOffset = reader.readU16BE(mappingAddr);
        int frameCount;
        if (firstOffset == 0) {
            // Null frame 0: the first entry is a self-reference (offset 0) producing
            // an empty frame. Determine the real table size from the second entry,
            // which points to the first real frame data.
            int secondOffset = reader.readU16BE(mappingAddr + 2);
            if (secondOffset == 0 || secondOffset > 1024) {
                return new ArrayList<>();
            }
            frameCount = secondOffset / 2;
        } else {
            frameCount = firstOffset / 2;
        }
        if (frameCount > 512) {
            throw new IllegalArgumentException(String.format(
                    "S2 mapping table at 0x%X has implausible frame count %d (first word=0x%04X) - wrong address?",
                    mappingAddr, frameCount, firstOffset));
        }
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int rawOffset = reader.readU16BE(mappingAddr + i * 2);
            int signedOffset = (short) rawOffset;

            int frameAddr = mappingAddr + signedOffset;
            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
            for (int p = 0; p < pieceCount; p++) {
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;
                int size = reader.readU8(frameAddr);
                frameAddr += 1;
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;
                frameAddr += 2; // 2P tile word, unused in 1P
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = (tileWord & 0x7FF) + tileOffset;
                if (tileIndex < 0) tileIndex = 0;

                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;
                boolean priority = (tileWord & 0x8000) != 0;

                pieces.add(new SpriteMappingPiece(
                        xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, vFlip, paletteIndex, priority));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return frames;
    }

    /** Loads S2 word-count DPLC frames, with four-bit lengths and twelve-bit tile indices. */
    public static List<SpriteDplcFrame> loadDplcFrames(RomByteReader reader, int dplcAddr) {
        int offsetTableSize = reader.readU16BE(dplcAddr);
        int frameCount = offsetTableSize / 2;
        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = dplcAddr + reader.readU16BE(dplcAddr + i * 2);
            int requestCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<TileLoadRequest> requests = new ArrayList<>(requestCount);
            for (int r = 0; r < requestCount; r++) {
                int entry = reader.readU16BE(frameAddr);
                frameAddr += 2;
                int count = ((entry >> 12) & 0xF) + 1;
                int startTile = entry & 0x0FFF;
                requests.add(new TileLoadRequest(startTile, count));
            }
            frames.add(new SpriteDplcFrame(requests));
        }
        return frames;
    }
}
