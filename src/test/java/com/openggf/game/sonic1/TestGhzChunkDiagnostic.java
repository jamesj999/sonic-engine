package com.openggf.game.sonic1;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.KosinskiReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROM-backed GHZ waterfall mapping diagnostic with no disassembly-file dependency. */
@RequiresRom(SonicGame.SONIC_1)
class TestGhzChunkDiagnostic {
    private static final Set<Integer> WATERFALL_CHUNKS = Set.of(0x1A9, 0x1AA, 0x1B5, 0x1B6);

    @Test
    void ghzMappingsContainWaterfallPatternsAndBlocks() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        byte[] chunks;
        try (ByteArrayInputStream input = new ByteArrayInputStream(
                reader.slice(Sonic1Constants.BLK16_GHZ_ADDR, 4096))) {
            chunks = EnigmaReader.decompress(Channels.newChannel(input), 0);
        }
        assertTrue(chunks.length > 0);
        assertEquals(0, chunks.length % 8);

        long waterfallPatternChunks = java.util.stream.IntStream.range(0, chunks.length / 8)
                .filter(chunk -> java.util.stream.IntStream.range(0, 4)
                        .map(piece -> word(chunks, chunk * 8 + piece * 2) & 0x7FF)
                        .anyMatch(pattern -> pattern >= 0x378 && pattern <= 0x37F))
                .count();
        assertTrue(waterfallPatternChunks > 0,
                "GHZ 16x16 mappings should reference the waterfall pattern range");

        byte[] blocks;
        try (ByteArrayInputStream input = new ByteArrayInputStream(
                reader.slice(Sonic1Constants.BLK256_GHZ_ADDR, 16384))) {
            blocks = KosinskiReader.decompress(Channels.newChannel(input), false);
        }
        assertTrue(blocks.length > 0);
        assertEquals(0, blocks.length % 512);
        assertTrue(java.util.stream.IntStream.range(0, blocks.length / 2)
                        .map(index -> word(blocks, index * 2) & 0x03FF)
                        .anyMatch(WATERFALL_CHUNKS::contains),
                "GHZ 256x256 mappings should reference the waterfall chunks");
    }

    private static int word(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }
}
