package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelConstants;
import com.openggf.tools.LevelDataFactory;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    public void testInvalidLength() {
        assertThrows(IllegalArgumentException.class,
                () -> LevelDataFactory.chunksFromSegaByteArray(new byte[10]));
    }
}


