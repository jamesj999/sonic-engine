package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.level.Map;

import static org.junit.Assert.*;

public class TestMap {
    @Test
    public void testGetSetValue() {
        Map map = new Map(2, 3, 3);
        assertEquals(2, map.getLayerCount());
        assertEquals(3, map.getWidth());
        assertEquals(3, map.getHeight());
        map.setValue(1, 2, 1, (byte) 7);
        assertEquals(7, map.getValue(1, 2, 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidLayer() {
        Map map = new Map(1, 2, 2);
        map.getValue(5, 1, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidCoords() {
        Map map = new Map(1, 2, 2);
        map.getValue(0, 3, 1);
    }
}
