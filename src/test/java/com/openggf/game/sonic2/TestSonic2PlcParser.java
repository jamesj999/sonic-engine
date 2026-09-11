package com.openggf.game.sonic2;

import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.objects.ObjectArtKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the Sonic 2 PLC (Pattern Load Cue) parser correctly reads
 * art loading data from the ROM's ArtLoadCues table.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestSonic2PlcParser {
    private Rom rom;

    @BeforeEach
    public void setUp() {
        rom = com.openggf.tests.TestEnvironment.currentRom();
    }

    @Test
    public void testParseStd1() throws IOException {
        PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, Sonic2Constants.PLC_STD1);
        assertNotNull(plc);
        assertFalse(plc.entries().isEmpty(), "Std1 PLC should have entries");

        // Std1 loads HUD, life icon, ring art, numbers - verify known addresses appear
        Set<Integer> addrs = collectAddresses(plc);
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_HUD_ADDR), "Std1 should contain HUD art address 0x" + Integer.toHexString(Sonic2Constants.ART_NEM_HUD_ADDR));
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_SONIC_LIFE_ADDR), "Std1 should contain Sonic life art address");
    }

    @Test
    public void testParseStd2() throws IOException {
        PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, Sonic2Constants.PLC_STD2);
        assertNotNull(plc);
        assertFalse(plc.entries().isEmpty(), "Std2 PLC should have entries");

        // Std2 loads checkpoint, signpost, monitors, shield, stars, explosion
        Set<Integer> addrs = collectAddresses(plc);
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_CHECKPOINT_ADDR), "Std2 should contain checkpoint art");
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_MONITOR_ADDR), "Std2 should contain monitor art");
    }

    @Test
    public void testParseStdWaterIncludesSmallBubbles() throws IOException {
        PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, Sonic2Constants.PLC_STD_WATER);
        assertNotNull(plc);
        assertFalse(plc.entries().isEmpty(), "StdWtr PLC should have entries");

        Set<Integer> addrs = collectAddresses(plc);
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_BUBBLES_ADDR),
                "StdWtr should contain ArtNem_Bubbles for Obj0A breathing bubbles");
        assertEquals(Sonic2ObjectArtKeys.BUBBLES,
                Sonic2PlcArtRegistry.lookup(Sonic2Constants.ART_NEM_BUBBLES_ADDR).key(),
                "StdWtr ArtNem_Bubbles should dispatch to the shared S2 bubble sheet");
    }

    @Test
    public void testLoadArtForArzRegistersStdWaterBubblesRenderer() throws IOException {
        Sonic2ObjectArtProvider provider = new Sonic2ObjectArtProvider();

        provider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_ARZ);

        assertNotNull(provider.getRenderer(ObjectArtKeys.BUBBLES),
                "ARZ Obj0A mouth bubbles need the StdWtr ArtNem_Bubbles renderer");
        assertNotNull(provider.getSheet(ObjectArtKeys.BUBBLES),
                "ARZ Obj0A mouth bubbles need the StdWtr ArtNem_Bubbles sheet");
    }

    @Test
    public void testParseEhz1() throws IOException {
        PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, Sonic2Constants.PLC_EHZ1);
        assertNotNull(plc);
        assertFalse(plc.entries().isEmpty(), "EHZ1 PLC should have entries");

        // EHZ1 loads waterfall, bridge, buzzer, coconuts, masher
        Set<Integer> addrs = collectAddresses(plc);
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_EHZ_WATERFALL_ADDR), "EHZ1 should contain waterfall art");
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_BRIDGE_ADDR), "EHZ1 should contain bridge art");
        assertTrue(addrs.contains(Sonic2Constants.ART_NEM_BUZZER_ADDR), "EHZ1 should contain buzzer badnik art");
    }

    @Test
    public void testParseAllPlcsNoErrors() throws IOException {
        // Parse all 67 PLC IDs - should not throw
        for (int i = 0; i < Sonic2Constants.ART_LOAD_CUES_ENTRY_COUNT; i++) {
            PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, i);
            assertNotNull(plc, "PLC " + i + " should not be null");
            // All valid PLCs should have at least one entry (some unused zones may be empty)
        }
    }

    @Test
    public void testGetZonePlcIdsEhz() throws IOException {
        // EHZ is zone index 0 in the LevelArtPointers table
        int[] plcIds = Sonic2PlcLoader.getZonePlcIds(rom, 0);
        assertEquals(2, plcIds.length);
        assertEquals(Sonic2Constants.PLC_EHZ1, plcIds[0], "EHZ primary PLC should be PLC_EHZ1 (" + Sonic2Constants.PLC_EHZ1 + ")");
        assertEquals(Sonic2Constants.PLC_EHZ2, plcIds[1], "EHZ secondary PLC should be PLC_EHZ2 (" + Sonic2Constants.PLC_EHZ2 + ")");
    }

    @Test
    public void testOutOfRangePlcReturnsEmpty() throws IOException {
        PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, 99);
        assertNotNull(plc);
        assertTrue(plc.entries().isEmpty(), "Out-of-range PLC should return empty entries");
    }

    @Test
    public void testAllPlcEntriesHaveValidAddresses() throws IOException {
        for (int i = 0; i < Sonic2Constants.ART_LOAD_CUES_ENTRY_COUNT; i++) {
            PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, i);
            for (PlcEntry entry : plc.entries()) {
                assertTrue(entry.romAddr() > 0 && entry.romAddr() < Sonic2Constants.DEFAULT_ROM_SIZE, String.format("PLC %d entry has invalid ROM address: 0x%06X",
                        i, entry.romAddr()));
                assertTrue(entry.tileIndex() >= 0 && entry.tileIndex() < 0x800, String.format("PLC %d entry has invalid tile index: 0x%03X",
                        i, entry.tileIndex()));
            }
        }
    }

    @Test
    public void testWfzRuntimePlcsHaveArtDispatchRegistrations() throws IOException {
        assertPlcEntriesRegisteredForRuntimeDispatch(Sonic2Constants.PLC_WFZ_BOSS);
        assertPlcEntriesRegisteredForRuntimeDispatch(Sonic2Constants.PLC_TORNADO);
    }

    private Set<Integer> collectAddresses(PlcDefinition plc) {
        Set<Integer> addrs = new HashSet<>();
        for (PlcEntry entry : plc.entries()) {
            addrs.add(entry.romAddr());
        }
        return addrs;
    }

    private void assertPlcEntriesRegisteredForRuntimeDispatch(int plcId) throws IOException {
        PlcDefinition plc = Sonic2PlcLoader.parsePlc(rom, plcId);
        assertFalse(plc.entries().isEmpty(), "WFZ runtime PLC " + plcId + " should contain art entries");
        for (PlcEntry entry : plc.entries()) {
            assertNotNull(Sonic2PlcArtRegistry.lookup(entry.romAddr()),
                    String.format("WFZ runtime PLC %d entry 0x%06X should dispatch through Sonic2PlcArtRegistry",
                            plcId, entry.romAddr()));
        }
    }
}

