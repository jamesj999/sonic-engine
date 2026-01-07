package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.rings.RingFrame;
import uk.co.jamesj999.sonic.level.rings.RingFramePiece;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;
import uk.co.jamesj999.sonic.tools.NemesisReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads ring art and mappings for Sonic 2 (REV01).
 */
public class Sonic2RingArt {
    private static final int RING_ART_ADDR = 0x7945C;
    private static final int RING_MAPPING_BASE_ADDR = 0x12382;
    private static final int RING_PALETTE_INDEX = 1;
    private static final int RING_FRAME_DELAY = 8;
    private static final int RING_ANIMATION_FRAME_COUNT = 4; // Excludes pickup sparkle frames.

    private final Rom rom;
    private final RomByteReader reader;
    private RingSpriteSheet cached;

    public Sonic2RingArt(Rom rom, RomByteReader reader) {
        this.rom = rom;
        this.reader = reader;
    }

    public RingSpriteSheet load() throws IOException {
        if (cached != null) {
            return cached;
        }

        Pattern[] patterns = loadRingPatterns();
        RingFrameSet frameSet = loadRingFrames();

        cached = new RingSpriteSheet(patterns, frameSet.frames(), RING_PALETTE_INDEX, RING_FRAME_DELAY,
                frameSet.spinFrameCount(), frameSet.sparkleFrameCount());
        return cached;
    }

    private Pattern[] loadRingPatterns() throws IOException {
        FileChannel channel = rom.getFileChannel();
        channel.position(RING_ART_ADDR);
        byte[] result = NemesisReader.decompress(channel);

        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent ring pattern data");
        }

        int patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }

    private RingFrameSet loadRingFrames() {
        List<Integer> offsets = new ArrayList<>();
        int lastOffset = -1;

        for (int i = 0; i < 32; i++) {
            int offset = reader.readU16BE(RING_MAPPING_BASE_ADDR + i * 2);
            if (offset <= lastOffset) {
                break;
            }
            offsets.add(offset);
            lastOffset = offset;
        }

        int totalFrames = Math.max(0, offsets.size() - 1);
        if (totalFrames <= 0) {
            return new RingFrameSet(List.of(), 0, 0);
        }

        int spinFrameCount = Math.min(RING_ANIMATION_FRAME_COUNT, totalFrames);
        int sparkleFrameCount = Math.max(0, totalFrames - spinFrameCount);
        List<RingFrame> frames = new ArrayList<>(totalFrames);
        for (int i = 0; i < totalFrames; i++) {
            int frameAddr = RING_MAPPING_BASE_ADDR + offsets.get(i);
            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;

            List<RingFramePiece> pieces = new ArrayList<>();
            for (int p = 0; p < pieceCount; p++) {
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;
                int size = reader.readU8(frameAddr);
                frameAddr += 1;
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;
                frameAddr += 2; // Unused word in this mapping format.
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = tileWord & 0x7FF;
                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;

                pieces.add(new RingFramePiece(xOffset, yOffset, widthTiles, heightTiles,
                        tileIndex, hFlip, vFlip, paletteIndex));
            }

            frames.add(new RingFrame(pieces));
        }

        return new RingFrameSet(frames, spinFrameCount, sparkleFrameCount);
    }

    private record RingFrameSet(List<RingFrame> frames, int spinFrameCount, int sparkleFrameCount) {
    }
}
