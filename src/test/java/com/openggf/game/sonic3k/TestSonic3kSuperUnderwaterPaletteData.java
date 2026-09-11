package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies the underwater Super Sonic palette-cycle tables the water data
 * provider selects, so Super Sonic stays gold below the surface instead of
 * reverting to his blue palette.
 *
 * <p>ROM: {@code SuperHyper_PalCycle_SonicApply} (sonic3k.asm:4666-4681).
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kSuperUnderwaterPaletteData {

    /** First cycle frame of each table (3 words), from sonic3k.asm:4855-4890. */
    private static final int[] SURFACE_FIRST_FRAME = {0x0E66, 0x0C42, 0x0822};
    private static final int[] AIZ_ICZ_FIRST_FRAME = {0x0A82, 0x0860, 0x0640};
    private static final int[] HCZ_CNZ_LBZ_FIRST_FRAME = {0x0C66, 0x0A44, 0x0624};

    @Test
    void underwaterCycleTableAddressesMatchRomData() throws IOException {
        Rom rom = GameServices.rom().getRom();

        assertFirstFrame(rom, Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_ADDR, SURFACE_FIRST_FRAME);
        assertFirstFrame(rom, Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_AIZ_ICZ_ADDR,
                AIZ_ICZ_FIRST_FRAME);
        assertFirstFrame(rom, Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_HCZ_CNZ_LBZ_ADDR,
                HCZ_CNZ_LBZ_FIRST_FRAME);
    }

    @Test
    void underwaterTablesDivergeFromTheSurfaceTableOnlyWhereTheRomSaysSo() throws IOException {
        Rom rom = GameServices.rom().getRom();
        int surfaceAddr = Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_ADDR;
        int underwaterAddr = Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_AIZ_ICZ_ADDR;
        int frameSize = Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_ENTRY_SIZE;

        // Frame 0 is the character's water-tinted base color, so it differs per table.
        assertNotEquals(rom.read16BitAddr(surfaceAddr) & 0xFFFF,
                rom.read16BitAddr(underwaterAddr) & 0xFFFF,
                "underwater frame 0 should be the water-tinted base color");

        // Frames 1-5 (the fade-in ramp to white) are shared byte-for-byte.
        for (int offset = frameSize; offset < 6 * frameSize; offset += 2) {
            assertEquals(rom.read16BitAddr(surfaceAddr + offset) & 0xFFFF,
                    rom.read16BitAddr(underwaterAddr + offset) & 0xFFFF,
                    "fade-in word at offset " + offset + " should match the surface table");
        }
    }

    @Test
    void aizAndIczUseTheGreenTintedTable() {
        Sonic3kWaterDataProvider provider = new Sonic3kWaterDataProvider();

        assertEquals(Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_AIZ_ICZ_ADDR,
                provider.getUnderwaterSuperPaletteCycleAddress(Sonic3kZoneIds.ZONE_AIZ, 0));
        assertEquals(Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_AIZ_ICZ_ADDR,
                provider.getUnderwaterSuperPaletteCycleAddress(Sonic3kZoneIds.ZONE_ICZ, 1));
    }

    @Test
    void otherWaterZonesUseTheAlternateTable() {
        Sonic3kWaterDataProvider provider = new Sonic3kWaterDataProvider();

        assertEquals(Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_HCZ_CNZ_LBZ_ADDR,
                provider.getUnderwaterSuperPaletteCycleAddress(Sonic3kZoneIds.ZONE_HCZ, 0));
        assertEquals(Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_HCZ_CNZ_LBZ_ADDR,
                provider.getUnderwaterSuperPaletteCycleAddress(Sonic3kZoneIds.ZONE_CNZ, 1));
        assertEquals(Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_HCZ_CNZ_LBZ_ADDR,
                provider.getUnderwaterSuperPaletteCycleAddress(Sonic3kZoneIds.ZONE_LBZ, 1));
    }

    private static void assertFirstFrame(Rom rom, int addr, int[] expected) throws IOException {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], rom.read16BitAddr(addr + i * 2) & 0xFFFF,
                    String.format("word %d at ROM 0x%X", i, addr));
        }
    }
}
