package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.level.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    public void testInvalidLayer() {
        Map map = new Map(1, 2, 2);
        assertThrows(IllegalArgumentException.class, () -> map.getValue(5, 1, 1));
    }

    @Test
    public void testInvalidCoords() {
        Map map = new Map(1, 2, 2);
        assertThrows(IllegalArgumentException.class, () -> map.getValue(0, 3, 1));
    }
}


