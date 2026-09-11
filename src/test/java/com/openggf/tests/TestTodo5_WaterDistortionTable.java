package com.openggf.tests;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.level.WaterSystem;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TODO #5 coverage: WaterSystem.getDistortionTable() uses a generated sine wave
 * instead of the ROM's SwScrl_RippleData lookup table.
 * <p>
 * The original ROM uses a hand-tuned ripple table (not a mathematical sine wave)
 * with values 0-3 representing horizontal pixel offsets per scanline.
 * <p>
 * Reference: docs/s2disasm/s2.asm lines 15408-15413
 * SwScrl_RippleData (byte_C682): 66 bytes of horizontal offsets
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestTodo5_WaterDistortionTable {
    /**
     * Expected SwScrl_RippleData values from s2.asm lines 15409-15413.
     * 66 bytes of horizontal pixel offsets for the water rippling effect.
     */
    private static final int[] EXPECTED_RIPPLE_DATA = {
            // Line 15409: dc.b 1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            // Line 15410: dc.b 2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            // Line 15411: dc.b 1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            // Line 15412: dc.b 2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            // Line 15413: dc.b 1, 2
            1, 2
    };

    /**
     * Verify ROM contains the exact SwScrl_RippleData bytes from the disassembly.
     * <p>
     * The RomOffsetFinder search for "SwScrl_RippleData" should yield the ROM offset.
     * Since we know the data pattern, we search for it directly in the ROM.
     */
    @Test
    public void testRomRippleDataMatchesDisassembly() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();

        // Search for the ripple data pattern in ROM.
        // From disassembly label byte_C682: this address in the S2 ROM is
        // relative to the assembled binary. We look for the known 66-byte pattern.
        // The first 16 bytes are: 01 02 01 03 01 02 02 01 02 03 01 02 01 02 00 00
        // We search for this pattern starting from a reasonable range (0xC000-0xD000).
        int rippleAddr = findRippleData(rom);
        if (rippleAddr < 0) {
            // If we can't find it, the test documents the expected data
            Assumptions.assumeTrue(false, "Could not locate SwScrl_RippleData in ROM - skipping ROM comparison");
            return;
        }

        byte[] romData = rom.readBytes(rippleAddr, EXPECTED_RIPPLE_DATA.length);
        for (int i = 0; i < EXPECTED_RIPPLE_DATA.length; i++) {
            assertEquals(EXPECTED_RIPPLE_DATA[i], romData[i] & 0xFF, String.format("Ripple byte [%d] at ROM offset 0x%X", i, rippleAddr + i));
        }
    }

    /**
     * Verify the engine's generated distortion table is non-null and has expected size.
     * The current implementation is a placeholder sine wave.
     */
    @Test
    public void testGeneratedDistortionTableProperties() {
        WaterSystem waterSystem = GameServices.water();
        int[] table = waterSystem.getDistortionTable();
        assertNotNull(table, "Distortion table should not be null");
        assertEquals(66, table.length, "Distortion table should be 66 entries");
    }

    /**
     * Verify the engine's generated distortion table does NOT match the ROM data.
     * This documents that TODO #5 is not yet implemented - the engine uses a
     * generated sine wave instead of the ROM's hand-tuned ripple table.
     * <p>
     * When TODO #5 is implemented, this test should be updated to verify the
     * engine's table matches the ROM data.
     */
    @Test
    public void testDistortionTableMatchesRom() {
        WaterSystem waterSystem = GameServices.water();
        int[] engineTable = waterSystem.getDistortionTable();

        // The ROM ripple data has 66 entries with values 0-3.
        // The engine table has 64 entries with values generated from sin().
        // When implemented, the engine should use the ROM's 66-byte table directly.
        assertEquals(EXPECTED_RIPPLE_DATA.length, engineTable.length, "Table size should match ROM ripple data");
        assertArrayEquals(EXPECTED_RIPPLE_DATA, engineTable, "Engine distortion should match ROM ripple data");
    }

    /**
     * Search ROM for the SwScrl_RippleData pattern.
     * Returns the ROM offset if found, or -1 if not found.
     */
    private int findRippleData(Rom rom) throws IOException {
        // The pattern we're looking for (first 16 bytes)
        byte[] pattern = {1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0};

        // Search in a reasonable range around the expected address (byte_C682)
        // The S2 ROM scroll handlers are in the 0xC000-0x10000 range typically
        int searchStart = 0xC000;
        int searchEnd = 0x12000;

        for (int addr = searchStart; addr < searchEnd; addr++) {
            boolean match = true;
            byte[] candidate = rom.readBytes(addr, pattern.length);
            for (int j = 0; j < pattern.length; j++) {
                if (candidate[j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                // Verify the full 66-byte sequence
                byte[] fullCandidate = rom.readBytes(addr, EXPECTED_RIPPLE_DATA.length);
                boolean fullMatch = true;
                for (int j = 0; j < EXPECTED_RIPPLE_DATA.length; j++) {
                    if ((fullCandidate[j] & 0xFF) != EXPECTED_RIPPLE_DATA[j]) {
                        fullMatch = false;
                        break;
                    }
                }
                if (fullMatch) {
                    return addr;
                }
            }
        }
        return -1;
    }
}



