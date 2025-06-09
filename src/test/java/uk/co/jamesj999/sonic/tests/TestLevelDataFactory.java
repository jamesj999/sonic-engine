package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.LevelConstants;
import uk.co.jamesj999.sonic.tools.LevelDataFactory;

import static org.junit.Assert.*;

public class TestLevelDataFactory {
    @Test
    public void testChunksFromSegaByteArray() {
        byte[] buffer = new byte[LevelConstants.BLOCK_SIZE_IN_ROM];
        for (int i = 0; i < LevelConstants.CHUNKS_PER_BLOCK; i++) {
            buffer[i * 2] = (byte) ((i >> 8) & 0xFF);
            buffer[i * 2 + 1] = (byte) (i & 0xFF);
        }
        ChunkDesc[] descs = LevelDataFactory.chunksFromSegaByteArray(buffer);
        assertEquals(LevelConstants.CHUNKS_PER_BLOCK, descs.length);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int index = y * 8 + x;
                assertEquals(index, descs[index].getChunkIndex());
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidLength() {
        LevelDataFactory.chunksFromSegaByteArray(new byte[10]);
    }
}
