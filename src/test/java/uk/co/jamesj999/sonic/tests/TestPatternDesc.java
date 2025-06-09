package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.level.PatternDesc;

import static org.junit.Assert.*;

public class TestPatternDesc {
    @Test
    public void testPatternDescParsing() {
        int index = (1 << 15) | (2 << 13) | (1 << 11) | 0x155;
        PatternDesc desc = new PatternDesc(index);
        assertEquals(index, desc.get());
        assertTrue(desc.getPriority());
        assertEquals(2, desc.getPaletteIndex());
        assertTrue(desc.getHFlip());
        assertFalse(desc.getVFlip());
        assertEquals(0x155, desc.getPatternIndex());

        desc.set(0);
        assertFalse(desc.getPriority());
        assertEquals(0, desc.getPaletteIndex());
    }
}
