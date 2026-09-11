package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.level.ChunkDesc;
import com.openggf.level.CollisionMode;

import static org.junit.jupiter.api.Assertions.*;

public class TestChunkDesc {
    @Test
    public void testChunkDescParsing() {
        int index = (3 << 14) | (1 << 12) | (1 << 10) | 0x1A3;
        ChunkDesc desc = new ChunkDesc(index);
        assertEquals(0x1A3, desc.getChunkIndex());
        assertTrue(desc.getHFlip());
        assertFalse(desc.getVFlip());
        assertEquals(CollisionMode.TOP_SOLID, desc.getPrimaryCollisionMode());
        assertEquals(CollisionMode.ALL_SOLID, desc.getSecondaryCollisionMode());

        desc.set(0);
        assertEquals(0, desc.getChunkIndex());
        assertFalse(desc.getHFlip());
    }
}


