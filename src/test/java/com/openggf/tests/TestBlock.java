package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.level.Block;
import com.openggf.level.LevelConstants;

import static org.junit.jupiter.api.Assertions.*;

public class TestBlock {
    @Test
    public void testBlockParsing() {
        byte[] buffer = new byte[LevelConstants.BLOCK_SIZE_IN_ROM];
        for (int i = 0; i < LevelConstants.CHUNKS_PER_BLOCK; i++) {
            buffer[i * 2] = (byte) ((i >> 8) & 0xFF);
            buffer[i * 2 + 1] = (byte) (i & 0xFF);
        }
        Block block = new Block();
        block.fromSegaFormat(buffer);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(y * 8 + x, block.getChunkDesc(x, y).getChunkIndex());
            }
        }
    }

    @Test
    public void testInvalidCoords() {
        Block block = new Block();
        assertThrows(IllegalArgumentException.class, () -> block.getChunkDesc(8, 0));
    }
}


