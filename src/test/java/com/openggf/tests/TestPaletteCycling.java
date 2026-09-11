package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;

import static com.openggf.game.sonic2.constants.Sonic2Constants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for palette cycling ROM data locations.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestPaletteCycling {
    private Rom rom;

    @BeforeEach
    public void setUp() {
        rom = com.openggf.tests.TestEnvironment.currentRom();
    }

    @Test
    public void testEhzArzWaterPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_EHZ_ARZ_WATER_ADDR, CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        assertEquals(32, data.length, "EHZ/ARZ water palette should be 32 bytes");
        assertNotEquals(0, data[0], "First byte should not be zero");
    }

    @Test
    public void testCpzCycle1PaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_CPZ1_ADDR, CYCLING_PAL_CPZ1_LEN);
        assertEquals(54, data.length, "CPZ cycle 1 should be 54 bytes");
        assertEquals(0x0E, data[0] & 0xFF, "First byte should be 0x0E");
        assertEquals(0x40, data[1] & 0xFF, "Second byte should be 0x40");
    }

    @Test
    public void testCpzCycle2PaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_CPZ2_ADDR, CYCLING_PAL_CPZ2_LEN);
        assertEquals(42, data.length, "CPZ cycle 2 should be 42 bytes");
        assertEquals(0x00, data[0] & 0xFF, "First color should be 0x00E0");
        assertEquals(0xE0, data[1] & 0xFF, "Second byte of first color");
    }

    @Test
    public void testCpzCycle3PaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_CPZ3_ADDR, CYCLING_PAL_CPZ3_LEN);
        assertEquals(32, data.length, "CPZ cycle 3 should be 32 bytes");
        assertEquals(0x00, data[0] & 0xFF, "First color should be 0x000E");
        assertEquals(0x0E, data[1] & 0xFF, "Second byte of first color");
    }

    @Test
    public void testHtzLavaPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_LAVA_ADDR, CYCLING_PAL_LAVA_LEN);
        assertEquals(128, data.length, "HTZ lava palette should be 128 bytes");
    }

    @Test
    public void testMtzCyclePaletteData() throws IOException {
        byte[] data1 = readBytes(CYCLING_PAL_MTZ1_ADDR, CYCLING_PAL_MTZ1_LEN);
        byte[] data2 = readBytes(CYCLING_PAL_MTZ2_ADDR, CYCLING_PAL_MTZ2_LEN);
        byte[] data3 = readBytes(CYCLING_PAL_MTZ3_ADDR, CYCLING_PAL_MTZ3_LEN);
        assertEquals(12, data1.length, "MTZ cycle 1 should be 12 bytes");
        assertEquals(12, data2.length, "MTZ cycle 2 should be 12 bytes");
        assertEquals(20, data3.length, "MTZ cycle 3 should be 20 bytes");
    }

    @Test
    public void testOozOilPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_OIL_ADDR, CYCLING_PAL_OIL_LEN);
        assertEquals(16, data.length, "OOZ oil palette should be 16 bytes");
    }

    @Test
    public void testMczLanternPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_LANTERN_ADDR, CYCLING_PAL_LANTERN_LEN);
        assertEquals(8, data.length, "MCZ lantern palette should be 8 bytes");
    }

    @Test
    public void testCnzCyclePaletteData() throws IOException {
        byte[] data1 = readBytes(CYCLING_PAL_CNZ1_ADDR, CYCLING_PAL_CNZ1_LEN);
        byte[] data3 = readBytes(CYCLING_PAL_CNZ3_ADDR, CYCLING_PAL_CNZ3_LEN);
        byte[] data4 = readBytes(CYCLING_PAL_CNZ4_ADDR, CYCLING_PAL_CNZ4_LEN);
        assertEquals(36, data1.length, "CNZ cycle 1 should be 36 bytes");
        assertEquals(18, data3.length, "CNZ cycle 3 should be 18 bytes");
        assertEquals(40, data4.length, "CNZ cycle 4 should be 40 bytes");
    }

    private byte[] readBytes(int addr, int len) throws IOException {
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = rom.readByte(addr + i);
        }
        return data;
    }
}


