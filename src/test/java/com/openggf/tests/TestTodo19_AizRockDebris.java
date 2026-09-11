package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verify AIZ/LRZ rock debris position and velocity tables match ROM data.
 * ROM reference: sonic3k.asm lines 44643-44720
 */
public class TestTodo19_AizRockDebris {

    private static final int[][] EXPECTED_FRAME0_POSITIONS = {
            {-8, -0x18}, {0x0B, -0x1C}, {-4, -0x0C}, {0x0C, -4},
            {-0x0C, 4}, {4, 0x0C}, {-0x0C, 0x1C}, {0x0C, 0x1C}
    };

    private static final int[][] EXPECTED_FRAME0_VELOCITIES = {
            {-0x300, -0x300}, {-0x2C0, -0x280}, {-0x2C0, -0x280}, {-0x280, -0x200},
            {-0x280, -0x180}, {-0x240, -0x180}, {-0x240, -0x100}, {-0x200, -0x100}
    };

    @Test
    public void testDebrisPositionTableFrame0MatchesRom() throws Exception {
        int[][][] positions = getPrivateStaticField("DEBRIS_POSITIONS");
        assertTrue(positions.length >= 1, "Debris positions table should have at least 1 frame");
        assertEquals(8, positions[0].length, "Frame 0 should have 8 debris pieces");
        for (int i = 0; i < EXPECTED_FRAME0_POSITIONS.length; i++) {
            assertEquals(EXPECTED_FRAME0_POSITIONS[i][0], positions[0][i][0], "Piece " + i + " X offset");
            assertEquals(EXPECTED_FRAME0_POSITIONS[i][1], positions[0][i][1], "Piece " + i + " Y offset");
        }
    }

    @Test
    public void testDebrisVelocityTableFrame0MatchesRom() throws Exception {
        int[][][] velocities = getPrivateStaticField("DEBRIS_VELOCITIES");
        assertTrue(velocities.length >= 1, "Debris velocities table should have at least 1 frame");
        assertEquals(8, velocities[0].length, "Frame 0 should have 8 velocity entries");
        for (int i = 0; i < EXPECTED_FRAME0_VELOCITIES.length; i++) {
            assertEquals(EXPECTED_FRAME0_VELOCITIES[i][0], velocities[0][i][0], "Piece " + i + " X velocity");
            assertEquals(EXPECTED_FRAME0_VELOCITIES[i][1], velocities[0][i][1], "Piece " + i + " Y velocity");
        }
    }

    @Test
    public void testPositionAndVelocityTablesHaveSameFrameCount() throws Exception {
        int[][][] positions = getPrivateStaticField("DEBRIS_POSITIONS");
        int[][][] velocities = getPrivateStaticField("DEBRIS_VELOCITIES");
        assertEquals(positions.length, velocities.length, "Position and velocity tables must have same number of frames");
        for (int f = 0; f < positions.length; f++) {
            assertEquals(positions[f].length, velocities[f].length, "Frame " + f + " piece count must match");
        }
    }

    @SuppressWarnings("unchecked")
    private static int[][][] getPrivateStaticField(String fieldName) throws Exception {
        Class<?> clazz = Class.forName(
                "com.openggf.game.sonic3k.objects.AizLrzRockObjectInstance");
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int[][][]) field.get(null);
    }
}


