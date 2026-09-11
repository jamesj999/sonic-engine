package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TODO #2 coverage: Sonic2Level.loadChunks() comment "TODO both collision addresses".
 * <p>
 * The code actually loads both primary and secondary collision index arrays for each
 * chunk. This test verifies the ROM contains the correct collision pointer tables
 * (Off_ColP and Off_ColS) at the expected addresses, and that zones with dual-path
 * collision (e.g., EHZ/HTZ) have distinct primary and secondary addresses.
 * <p>
 * Reference: docs/s2disasm/s2.asm lines 5910-5960
 * Off_ColP at COLLISION_LAYOUT_DIR_ADDR = 0x49E8
 * Off_ColS at ALT_COLLISION_LAYOUT_DIR_ADDR = 0x4A2C
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestTodo2_DualCollisionAddresses {
    // ROM zone IDs from s2.constants.asm
    private static final int ROM_ZONE_EHZ = 0x00;
    private static final int ROM_ZONE_HTZ = 0x07;
    private static final int ROM_ZONE_CNZ = 0x0C;
    private static final int ROM_ZONE_CPZ = 0x0D;
    private static final int ROM_ZONE_ARZ = 0x0F;
    private static final int ROM_ZONE_WFZ = 0x06;
    private static final int ROM_ZONE_SCZ = 0x10;

    /**
     * Read a 32-bit collision pointer from the primary table for a given zone.
     */
    private int readPrimaryCollisionAddr(Rom rom, int romZoneId) throws IOException {
        return rom.read32BitAddr(Sonic2Constants.COLLISION_LAYOUT_DIR_ADDR + romZoneId * 4);
    }

    /**
     * Read a 32-bit collision pointer from the secondary table for a given zone.
     */
    private int readSecondaryCollisionAddr(Rom rom, int romZoneId) throws IOException {
        return rom.read32BitAddr(Sonic2Constants.ALT_COLLISION_LAYOUT_DIR_ADDR + romZoneId * 4);
    }

    /**
     * Verify EHZ primary and secondary collision pointers are different.
     * s2.asm: ColP_EHZHTZ vs ColS_EHZHTZ (lines 5916, 5943)
     */
    @Test
    public void testEhzHasDualCollisionPaths() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        int primary = readPrimaryCollisionAddr(rom, ROM_ZONE_EHZ);
        int secondary = readSecondaryCollisionAddr(rom, ROM_ZONE_EHZ);
        assertTrue(primary > 0, "EHZ primary collision addr should be valid");
        assertTrue(secondary > 0, "EHZ secondary collision addr should be valid");
        assertNotEquals(primary, secondary, "EHZ should have different primary and secondary collision");
    }

    /**
     * Verify HTZ shares collision data with EHZ (both point to ColP_EHZHTZ / ColS_EHZHTZ).
     * s2.asm lines 5923, 5950: HTZ uses same collision as EHZ
     */
    @Test
    public void testHtzSharesCollisionWithEhz() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        assertEquals(readPrimaryCollisionAddr(rom, ROM_ZONE_EHZ), readPrimaryCollisionAddr(rom, ROM_ZONE_HTZ), "HTZ primary should match EHZ primary");
        assertEquals(readSecondaryCollisionAddr(rom, ROM_ZONE_EHZ), readSecondaryCollisionAddr(rom, ROM_ZONE_HTZ), "HTZ secondary should match EHZ secondary");
    }

    /**
     * Verify CNZ has distinct primary and secondary collision.
     * s2.asm: ColP_CNZ (line 5928) vs ColS_CNZ (line 5955)
     */
    @Test
    public void testCnzHasDualCollisionPaths() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        int primary = readPrimaryCollisionAddr(rom, ROM_ZONE_CNZ);
        int secondary = readSecondaryCollisionAddr(rom, ROM_ZONE_CNZ);
        assertNotEquals(primary, secondary, "CNZ should have different primary and secondary collision");
    }

    /**
     * Verify CPZ/DEZ share collision and have dual paths.
     * s2.asm: ColP_CPZDEZ (line 5929) vs ColS_CPZDEZ (line 5956)
     */
    @Test
    public void testCpzHasDualCollisionPaths() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        int primary = readPrimaryCollisionAddr(rom, ROM_ZONE_CPZ);
        int secondary = readSecondaryCollisionAddr(rom, ROM_ZONE_CPZ);
        assertNotEquals(primary, secondary, "CPZ should have different primary and secondary collision");
    }

    /**
     * Verify ARZ has distinct primary and secondary collision.
     * s2.asm: ColP_ARZ (line 5931) vs ColS_ARZ (line 5958)
     */
    @Test
    public void testArzHasDualCollisionPaths() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        int primary = readPrimaryCollisionAddr(rom, ROM_ZONE_ARZ);
        int secondary = readSecondaryCollisionAddr(rom, ROM_ZONE_ARZ);
        assertNotEquals(primary, secondary, "ARZ should have different primary and secondary collision");
    }

    /**
     * Verify WFZ/SCZ share collision (both use ColP_WFZSCZ / ColS_WFZSCZ).
     * s2.asm lines 5922/5932, 5949/5959
     */
    @Test
    public void testWfzSharesCollisionWithScz() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        assertEquals(readPrimaryCollisionAddr(rom, ROM_ZONE_WFZ), readPrimaryCollisionAddr(rom, ROM_ZONE_SCZ), "WFZ primary should match SCZ primary");
        assertEquals(readSecondaryCollisionAddr(rom, ROM_ZONE_WFZ), readSecondaryCollisionAddr(rom, ROM_ZONE_SCZ), "WFZ secondary should match SCZ secondary");
    }

    /**
     * Verify the known HTZ collision addresses match constants in Sonic2Constants.
     * These were previously verified and hardcoded.
     */
    @Test
    public void testHtzCollisionMatchesConstants() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        assertEquals(Sonic2Constants.HTZ_COLLISION_PRIMARY_ADDR, readPrimaryCollisionAddr(rom, ROM_ZONE_HTZ), "HTZ primary collision should match constant");
        assertEquals(Sonic2Constants.HTZ_COLLISION_SECONDARY_ADDR, readSecondaryCollisionAddr(rom, ROM_ZONE_HTZ), "HTZ secondary collision should match constant");
    }
}


