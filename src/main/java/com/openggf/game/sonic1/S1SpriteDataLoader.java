package com.openggf.game.sonic1;

import com.openggf.data.RomByteReader;
import com.openggf.game.common.CommonSpriteDataLoader;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable static utilities for loading Sonic 1 sprite data from ROM:
 * art tiles, mapping frames, DPLC frames, and animation scripts.
 *
 * <p>S1 uses a different piece format from S2/S3K:
 * <ul>
 *   <li>5 bytes per piece: y(s8), size(u8), tileHi(u8), tileLo(u8), x(s8)</li>
 *   <li>Piece count per frame is a byte (not u16 word like S2/S3K)</li>
 *   <li>X offset is a signed byte (not s16 like S2/S3K)</li>
 *   <li>Word offset table at start of mapping data</li>
 * </ul>
 */
public final class S1SpriteDataLoader {

    private S1SpriteDataLoader() {}

    /**
     * Loads uncompressed art tiles from ROM.
     *
     * @param reader ROM byte reader
     * @param artAddr ROM address of art data
     * @param artSize size in bytes (must be multiple of 32)
     * @return array of Pattern tiles
     */
    public static Pattern[] loadArtTiles(RomByteReader reader, int artAddr, int artSize) throws IOException {
        return CommonSpriteDataLoader.loadArtTiles(reader, artAddr, artSize);
    }

    /**
     * Loads S1-format sprite mappings, auto-detecting frame count from the first offset.
     * The first word at mappingAddr is the offset to frame 0's data; frameCount = firstOffset / 2.
     *
     * @param reader ROM byte reader
     * @param mappingAddr ROM address of mapping table
     * @return list of mapping frames
     */
    public static List<SpriteMappingFrame> loadMappingFrames(RomByteReader reader, int mappingAddr) {
        int firstOffset = reader.readU16BE(mappingAddr);
        int frameCount = firstOffset / 2;
        if (frameCount <= 0 || frameCount > 512) {
            throw new IllegalArgumentException(String.format(
                    "S1 mapping table at 0x%X has implausible frame count %d (first word=0x%04X) - wrong address?",
                    mappingAddr, frameCount, firstOffset));
        }
        return loadMappingFrames(reader, mappingAddr, frameCount);
    }

    /**
     * Loads S1-format sprite mappings whose offset table may reference shared
     * frames before the table with signed word offsets.
     */
    public static List<SpriteMappingFrame> loadMappingFramesWithSignedOffsets(
            RomByteReader reader, int mappingAddr) {
        int firstOffset = reader.readU16BE(mappingAddr);
        int frameCount = firstOffset / 2;
        if (frameCount <= 0 || frameCount > 512) {
            throw new IllegalArgumentException(String.format(
                    "S1 mapping table at 0x%X has implausible frame count %d (first word=0x%04X) - wrong address?",
                    mappingAddr, frameCount, firstOffset));
        }
        return loadMappingFramesWithSignedOffsets(reader, mappingAddr, frameCount);
    }

    /**
     * Loads S1-format sprite mappings with signed word offsets.
     */
    public static List<SpriteMappingFrame> loadMappingFramesWithSignedOffsets(
            RomByteReader reader, int mappingAddr, int frameCount) {
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameOffset = (short) reader.readU16BE(mappingAddr + i * 2);
            int frameAddr = mappingAddr + frameOffset;
            frames.add(loadMappingFrameAt(reader, frameAddr));
        }
        return frames;
    }

    /**
     * Loads S1-format sprite mappings with an explicit frame count.
     *
     * <p>S1 format (SonicMappingsVer=1):
     * <ul>
     *   <li>Offset table: {@code frameCount} x word (offset from table start to frame data)</li>
     *   <li>Per frame: byte (piece count) + pieces x 5 bytes</li>
     *   <li>Piece format: y:signed_byte, size:byte, tileHi:byte, tileLo:byte, x:signed_byte</li>
     * </ul>
     *
     * @param reader ROM byte reader
     * @param mappingAddr ROM address of mapping table
     * @param frameCount number of frames to read
     * @return list of mapping frames
     */
    public static List<SpriteMappingFrame> loadMappingFrames(RomByteReader reader, int mappingAddr, int frameCount) {
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameOffset = reader.readU16BE(mappingAddr + i * 2);
            int frameAddr = mappingAddr + frameOffset;
            frames.add(loadMappingFrameAt(reader, frameAddr));
        }
        return frames;
    }

    private static SpriteMappingFrame loadMappingFrameAt(RomByteReader reader, int frameAddr) {
        int pieceCount = reader.readU8(frameAddr);
        frameAddr += 1;

        List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
        for (int p = 0; p < pieceCount; p++) {
            int yOffset = (byte) reader.readU8(frameAddr);
            int size = reader.readU8(frameAddr + 1);
            int tileHi = reader.readU8(frameAddr + 2);
            int tileLo = reader.readU8(frameAddr + 3);
            int xOffset = (byte) reader.readU8(frameAddr + 4);
            frameAddr += 5;

            int widthTiles = ((size >> 2) & 0x3) + 1;
            int heightTiles = (size & 0x3) + 1;

            int tileWord = (tileHi << 8) | tileLo;
            pieces.add(new SpriteMappingPiece(
                    xOffset, yOffset, widthTiles, heightTiles,
                    tileWord & 0x7FF,
                    (tileWord & 0x800) != 0,
                    (tileWord & 0x1000) != 0,
                    (tileWord >> 13) & 0x3,
                    (tileWord & 0x8000) != 0));
        }
        return new SpriteMappingFrame(pieces);
    }

    /**
     * Loads S1-format DPLCs, auto-detecting frame count from the first offset.
     *
     * @param reader ROM byte reader
     * @param dplcAddr ROM address of DPLC table
     * @return list of DPLC frames
     */
    public static List<SpriteDplcFrame> loadDplcFrames(RomByteReader reader, int dplcAddr) {
        int firstOffset = reader.readU16BE(dplcAddr);
        int frameCount = firstOffset / 2;
        return loadDplcFrames(reader, dplcAddr, frameCount);
    }

    /**
     * Loads S1-format DPLCs with an explicit frame count.
     *
     * <p>S1 format (SonicDplcVer=1):
     * <ul>
     *   <li>Offset table: {@code frameCount} x word (offset from table start to frame data)</li>
     *   <li>Per frame: byte (entry count) + entries x 2 bytes</li>
     *   <li>Entry format: bits 12-15 = tile_count-1, bits 0-11 = start_tile</li>
     * </ul>
     *
     * @param reader ROM byte reader
     * @param dplcAddr ROM address of DPLC table
     * @param frameCount number of frames to read
     * @return list of DPLC frames
     */
    public static List<SpriteDplcFrame> loadDplcFrames(RomByteReader reader, int dplcAddr, int frameCount) {
        List<SpriteDplcFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameOffset = reader.readU16BE(dplcAddr + i * 2);
            int frameAddr = dplcAddr + frameOffset;
            int entryCount = reader.readU8(frameAddr);
            frameAddr += 1;

            List<TileLoadRequest> requests = new ArrayList<>(entryCount);
            for (int r = 0; r < entryCount; r++) {
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

    /**
     * Computes the DPLC bank size (max tiles needed in VRAM at once).
     *
     * @param dplcFrames DPLC frames
     * @param mappingFrames mapping frames
     * @return bank size in tiles
     */
    public static int resolveBankSize(List<SpriteDplcFrame> dplcFrames, List<SpriteMappingFrame> mappingFrames) {
        return CommonSpriteDataLoader.resolveBankSize(dplcFrames, mappingFrames);
    }

    /**
     * Parses a single animation script from ROM.
     * Same format as S2/S3K: delay byte + frame IDs + end action byte.
     *
     * @param reader ROM byte reader
     * @param scriptAddr ROM address of the script data
     * @return parsed animation script
     */
    public static SpriteAnimationScript parseAnimationScript(RomByteReader reader, int scriptAddr) {
        return CommonSpriteDataLoader.parseAnimationScript(reader, scriptAddr);
    }

    /**
     * Loads a set of animation scripts from a pointer-offset table.
     *
     * @param reader ROM byte reader
     * @param baseAddr ROM address of the offset table
     * @param count number of animation entries
     * @return animation set
     */
    public static SpriteAnimationSet loadAnimationSet(RomByteReader reader, int baseAddr, int count) {
        return CommonSpriteDataLoader.loadAnimationSet(reader, baseAddr, count);
    }
}
