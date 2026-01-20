package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.IOException;

import static org.junit.Assert.*;
import static uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants.*;

/**
 * Tests for palette cycling ROM data locations.
 */
public class TestPaletteCycling {
    private Rom rom;

    @Before
    public void setUp() throws IOException {
        rom = new Rom();
        String romFile = RomTestUtils.ensureRomAvailable().getAbsolutePath();
        rom.open(romFile);
    }

    @Test
    public void testEhzArzWaterPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_EHZ_ARZ_WATER_ADDR, CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        assertEquals("EHZ/ARZ water palette should be 32 bytes", 32, data.length);
        assertNotEquals("First byte should not be zero", 0, data[0]);
    }

    @Test
    public void testCpzCycle1PaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_CPZ1_ADDR, CYCLING_PAL_CPZ1_LEN);
        assertEquals("CPZ cycle 1 should be 54 bytes", 54, data.length);
        assertEquals("First byte should be 0x0E", 0x0E, data[0] & 0xFF);
        assertEquals("Second byte should be 0x40", 0x40, data[1] & 0xFF);
    }

    @Test
    public void testCpzCycle2PaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_CPZ2_ADDR, CYCLING_PAL_CPZ2_LEN);
        assertEquals("CPZ cycle 2 should be 42 bytes", 42, data.length);
        assertEquals("First color should be 0x00E0", 0x00, data[0] & 0xFF);
        assertEquals("Second byte of first color", 0xE0, data[1] & 0xFF);
    }

    @Test
    public void testCpzCycle3PaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_CPZ3_ADDR, CYCLING_PAL_CPZ3_LEN);
        assertEquals("CPZ cycle 3 should be 32 bytes", 32, data.length);
        assertEquals("First color should be 0x000E", 0x00, data[0] & 0xFF);
        assertEquals("Second byte of first color", 0x0E, data[1] & 0xFF);
    }

    @Test
    public void testHtzLavaPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_LAVA_ADDR, CYCLING_PAL_LAVA_LEN);
        assertEquals("HTZ lava palette should be 128 bytes", 128, data.length);
    }

    @Test
    public void testMtzCyclePaletteData() throws IOException {
        byte[] data1 = readBytes(CYCLING_PAL_MTZ1_ADDR, CYCLING_PAL_MTZ1_LEN);
        byte[] data2 = readBytes(CYCLING_PAL_MTZ2_ADDR, CYCLING_PAL_MTZ2_LEN);
        byte[] data3 = readBytes(CYCLING_PAL_MTZ3_ADDR, CYCLING_PAL_MTZ3_LEN);
        assertEquals("MTZ cycle 1 should be 12 bytes", 12, data1.length);
        assertEquals("MTZ cycle 2 should be 12 bytes", 12, data2.length);
        assertEquals("MTZ cycle 3 should be 20 bytes", 20, data3.length);
    }

    @Test
    public void testOozOilPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_OIL_ADDR, CYCLING_PAL_OIL_LEN);
        assertEquals("OOZ oil palette should be 16 bytes", 16, data.length);
    }

    @Test
    public void testMczLanternPaletteData() throws IOException {
        byte[] data = readBytes(CYCLING_PAL_LANTERN_ADDR, CYCLING_PAL_LANTERN_LEN);
        assertEquals("MCZ lantern palette should be 8 bytes", 8, data.length);
    }

    @Test
    public void testCnzCyclePaletteData() throws IOException {
        byte[] data1 = readBytes(CYCLING_PAL_CNZ1_ADDR, CYCLING_PAL_CNZ1_LEN);
        byte[] data3 = readBytes(CYCLING_PAL_CNZ3_ADDR, CYCLING_PAL_CNZ3_LEN);
        byte[] data4 = readBytes(CYCLING_PAL_CNZ4_ADDR, CYCLING_PAL_CNZ4_LEN);
        assertEquals("CNZ cycle 1 should be 36 bytes", 36, data1.length);
        assertEquals("CNZ cycle 3 should be 18 bytes", 18, data3.length);
        assertEquals("CNZ cycle 4 should be 40 bytes", 40, data4.length);
    }

    private byte[] readBytes(int addr, int len) throws IOException {
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = rom.readByte(addr + i);
        }
        return data;
    }
}
