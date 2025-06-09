package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.level.Block;
import uk.co.jamesj999.sonic.level.LevelConstants;

import static org.junit.Assert.*;

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

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCoords() {
        Block block = new Block();
        block.getChunkDesc(8, 0);
    }
}
