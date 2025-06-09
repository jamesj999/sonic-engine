package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.CollisionMode;

import static org.junit.Assert.*;

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
