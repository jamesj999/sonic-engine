package com.openggf.game.common;

import com.openggf.data.RomByteReader;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Game-agnostic static utilities for parsing animation scripts, loading
 * animation sets, and resolving DPLC bank sizes.
 *
 * <p>These methods are shared across S1, S2, and S3K sprite data loaders.
 * The animation script format is common to all three games: a delay byte
 * followed by frame IDs and terminated by an end-action byte (>= 0xF0).
 *
 * <p>Game-specific loaders delegate to these methods rather than
 * duplicating the logic.
 */
public final class CommonSpriteDataLoader {

    private CommonSpriteDataLoader() {}

    /**
     * Loads uncompressed art tiles from ROM.
     *
     * <p>This is a thin wrapper around {@link PatternDecompressor#uncompressed}
     * shared by S1 and S3K sprite data loaders.
     *
     * @param reader ROM byte reader
     * @param artAddr ROM address of art data
     * @param artSize size in bytes (must be multiple of 32)
     * @return array of Pattern tiles
     * @throws IOException if reading fails
     */
    public static Pattern[] loadArtTiles(RomByteReader reader, int artAddr, int artSize) throws IOException {
        return PatternDecompressor.uncompressed(reader, artAddr, artSize);
    }

    /**
     * Parses a single animation script from ROM.
     *
     * <p>Format: delay byte, then frame ID bytes until an end-action code
     * (>= 0xF0) is encountered:
     * <ul>
     *   <li>0xFF = LOOP (restart from frame 0)</li>
     *   <li>0xFE = LOOP_BACK (next byte = rewind count)</li>
     *   <li>0xFD = SWITCH (next byte = animation to switch to)</li>
     *   <li>0xFC = LOOP (S3K variant, same behavior as 0xFF)</li>
     *   <li>>= 0xF0 (other) = HOLD (freeze on last frame)</li>
     * </ul>
     *
     * @param reader ROM byte reader
     * @param scriptAddr ROM address of the script data
     * @return parsed animation script
     */
    /**
     * Raw ROM bytes captured past a script's terminator so an out-of-range
     * {@code anim_frame} can be resolved the way the 68000 handlers resolve it:
     * {@code move.b 1(a1,d1.w),d0} with no bounds check, reading straight on
     * into the next script in the block. The longest table that can strand an
     * {@code anim_frame} in another script is {@code AniRaw_Tails_Carry}'s 17
     * entries (sonic3k.asm:27417-27419, advanced at 24932-24938 against the
     * player's shared {@code anim_frame}), so a window this size covers every
     * index those routines can leave behind.
     */
    private static final int SCRIPT_TRAILING_BYTE_WINDOW = 32;

    public static SpriteAnimationScript parseAnimationScript(RomByteReader reader, int scriptAddr) {
        int delay = reader.readU8(scriptAddr);
        scriptAddr += 1;

        List<Integer> frames = new ArrayList<>();
        SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
        int endParam = 0;

        int terminatorAddr = scriptAddr;
        while (true) {
            terminatorAddr = scriptAddr;
            int value = reader.readU8(scriptAddr);
            scriptAddr += 1;
            if (value >= 0xF0) {
                if (value == 0xFF) {
                    endAction = SpriteAnimationEndAction.LOOP;
                    break;
                }
                if (value == 0xFE) {
                    endAction = SpriteAnimationEndAction.LOOP_BACK;
                    endParam = reader.readU8(scriptAddr);
                    break;
                }
                if (value == 0xFD) {
                    endAction = SpriteAnimationEndAction.SWITCH;
                    endParam = reader.readU8(scriptAddr);
                    break;
                }
                if (value == 0xFC) {
                    endAction = SpriteAnimationEndAction.LOOP;
                    break;
                }
                endAction = SpriteAnimationEndAction.HOLD;
                break;
            }
            frames.add(value);
        }

        List<Integer> trailingBytes = new ArrayList<>(SCRIPT_TRAILING_BYTE_WINDOW);
        for (int i = 0; i < SCRIPT_TRAILING_BYTE_WINDOW; i++) {
            trailingBytes.add(reader.readU8(terminatorAddr + i));
        }

        return new SpriteAnimationScript(delay, frames, endAction, endParam,
                List.copyOf(trailingBytes));
    }

    /**
     * Loads a set of animation scripts from a word-offset table in ROM.
     *
     * <p>The table at {@code baseAddr} contains {@code count} 16-bit word
     * offsets. Each offset is relative to {@code baseAddr} and points to
     * an animation script parsed by {@link #parseAnimationScript}.
     *
     * @param reader ROM byte reader
     * @param baseAddr ROM address of the offset table
     * @param count number of animation entries
     * @return animation set containing all parsed scripts
     */
    public static SpriteAnimationSet loadAnimationSet(RomByteReader reader, int baseAddr, int count) {
        SpriteAnimationSet set = new SpriteAnimationSet();
        for (int i = 0; i < count; i++) {
            int scriptAddr = baseAddr + reader.readU16BE(baseAddr + i * 2);
            SpriteAnimationScript script = parseAnimationScript(reader, scriptAddr);
            set.addScript(i, script);
        }
        return set;
    }

    /**
     * Computes the DPLC bank size (maximum tiles needed in VRAM at once).
     *
     * <p>The bank size is the maximum of:
     * <ul>
     *   <li>The largest total tile count across all DPLC frames</li>
     *   <li>The highest tile index + tile count from any mapping piece</li>
     * </ul>
     *
     * @param dplcFrames DPLC frames (tile load requests per animation frame)
     * @param mappingFrames mapping frames (sprite piece layouts)
     * @return bank size in tiles
     */
    public static int resolveBankSize(List<SpriteDplcFrame> dplcFrames, List<SpriteMappingFrame> mappingFrames) {
        int maxDplcTotal = 0;
        for (SpriteDplcFrame frame : dplcFrames) {
            int total = 0;
            for (TileLoadRequest request : frame.requests()) {
                total += Math.max(0, request.count());
            }
            maxDplcTotal = Math.max(maxDplcTotal, total);
        }

        int maxMappingIndex = 0;
        for (SpriteMappingFrame frame : mappingFrames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tileCount = piece.widthTiles() * piece.heightTiles();
                maxMappingIndex = Math.max(maxMappingIndex, piece.tileIndex() + tileCount);
            }
        }

        return Math.max(maxDplcTotal, maxMappingIndex);
    }
}
