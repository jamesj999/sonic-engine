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
        byte[] data = new byte[CYCLING_PAL_EHZ_ARZ_WATER_LEN];
        for (int i = 0; i < data.length; i++) {
            data[i] = rom.readByte(CYCLING_PAL_EHZ_ARZ_WATER_ADDR + i);
        }
        assertEquals("EHZ/ARZ water palette should be 32 bytes", 32, data.length);
        assertNotEquals("First byte should not be zero", 0, data[0]);
    }

    @Test
    public void testCpzCycle1PaletteData() throws IOException {
        byte[] data = new byte[CYCLING_PAL_CPZ1_LEN];
        for (int i = 0; i < data.length; i++) {
            data[i] = rom.readByte(CYCLING_PAL_CPZ1_ADDR + i);
        }
        assertEquals("CPZ cycle 1 should be 54 bytes (9 frames × 6 bytes)", 54, data.length);
        assertEquals("First byte should be 0x0E (Sega color format)", 0x0E, data[0] & 0xFF);
        assertEquals("Second byte should be 0x40", 0x40, data[1] & 0xFF);
    }

    @Test
    public void testCpzCycle2PaletteData() throws IOException {
        byte[] data = new byte[CYCLING_PAL_CPZ2_LEN];
        for (int i = 0; i < data.length; i++) {
            data[i] = rom.readByte(CYCLING_PAL_CPZ2_ADDR + i);
        }
        assertEquals("CPZ cycle 2 should be 42 bytes (21 frames × 2 bytes)", 42, data.length);
        assertEquals("First color should be 0x00E0 (bright green)", 0x00, data[0] & 0xFF);
        assertEquals("Second byte of first color", 0xE0, data[1] & 0xFF);
    }

    @Test
    public void testCpzCycle3PaletteData() throws IOException {
        byte[] data = new byte[CYCLING_PAL_CPZ3_LEN];
        for (int i = 0; i < data.length; i++) {
            data[i] = rom.readByte(CYCLING_PAL_CPZ3_ADDR + i);
        }
        assertEquals("CPZ cycle 3 should be 32 bytes (16 frames × 2 bytes)", 32, data.length);
        assertEquals("First color should be 0x000E (blue)", 0x00, data[0] & 0xFF);
        assertEquals("Second byte of first color", 0x0E, data[1] & 0xFF);
    }
}
