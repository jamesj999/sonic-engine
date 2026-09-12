package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.Pattern;
import com.openggf.level.rings.RingFrame;
import com.openggf.level.rings.RingMappingFrames;
import com.openggf.data.RomByteReader;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.data.compression.NemesisReader;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;

/**
 * Loads ring art and mapping frames for Sonic 3&amp;K.
 *
 * <p>S3K ring mappings come from the ROM ({@code Map - Ring.asm}):
 * <ul>
 *   <li>Frames 0-3: spin animation (front, angled, edge, angled-mirrored)</li>
 *   <li>Frames 4-7: sparkle animation (collection effect)</li>
 * </ul>
 *
 * <p>Art is Nemesis-compressed at {@link Sonic3kConstants#ART_NEM_RING_HUD_TEXT_ADDR}.
 * Only the first 14 patterns (tiles 0-13) are ring sprite data; the remainder
 * is HUD/text art that we discard.
 * Palette index 1 (same as Sonic 1/2).
 */
public class Sonic3kRingArt {

    private static final int RING_PALETTE_INDEX = 1;
    private static final int RING_FRAME_DELAY = 8;
    private static final int SPARKLE_FRAME_DELAY = 5;
    private static final int SPIN_FRAME_COUNT = 4;
    private static final int SPARKLE_FRAME_COUNT = 4;
    /** Number of 8x8 patterns used by ring sprite frames (tiles 0-13). */
    private static final int RING_PATTERN_COUNT = 14;

    private final Rom rom;
    private RingSpriteSheet cached;

    public Sonic3kRingArt(Rom rom) {
        this.rom = rom;
    }

    public RingSpriteSheet load() throws IOException {
        if (cached != null) {
            return cached;
        }

        Pattern[] patterns = loadRingPatterns();
        List<RingFrame> frames = RingMappingFrames.fromMappings(
                S3kSpriteDataLoader.loadMappingFrames(RomByteReader.fromRom(rom),
                        Sonic3kConstants.MAP_RING_ADDR));

        cached = new RingSpriteSheet(patterns, frames, RING_PALETTE_INDEX, RING_FRAME_DELAY,
                SPARKLE_FRAME_DELAY, SPIN_FRAME_COUNT, SPARKLE_FRAME_COUNT);
        return cached;
    }

    private Pattern[] loadRingPatterns() throws IOException {
        FileChannel channel = rom.getFileChannel();
        // Rom exposes a shared FileChannel; lock around seek+decode so concurrent
        // readers cannot move the channel position mid-stream.
        byte[] result;
        synchronized (rom) {
            channel.position(Sonic3kConstants.ART_NEM_RING_HUD_TEXT_ADDR);
            result = NemesisReader.decompress(channel);
        }
        return PatternDecompressor.fromBytes(result, RING_PATTERN_COUNT);
    }

}
