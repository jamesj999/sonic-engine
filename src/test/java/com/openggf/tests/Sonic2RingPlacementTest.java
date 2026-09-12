package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2RingPlacement;
import com.openggf.game.sonic2.ZoneAct;
import com.openggf.level.rings.RingSpawn;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_2)
public class Sonic2RingPlacementTest {
    private RomByteReader reader;

    @BeforeEach
    public void setUp() throws IOException {
        reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
    }

    @Test
    public void ringPointerTableMatchesRev01Offsets() throws Exception {
        assertEquals(0x0044, reader.readU16BE(Sonic2RingPlacement.OFF_RINGS_REV01));
        assertEquals(0x026A, reader.readU16BE(Sonic2RingPlacement.OFF_RINGS_REV01 + 2));
    }

    @Test
    public void parsesEmeraldHillAct1RingGroup() throws Exception {
        Sonic2RingPlacement placement = new Sonic2RingPlacement(reader);

        List<RingSpawn> rings = placement.load(new ZoneAct(0, 0));
        assertFalse(rings.isEmpty());

        assertContainsRing(rings, 0x0124, 0x0240);
        assertContainsRing(rings, 0x013C, 0x0240);
        assertContainsRing(rings, 0x0154, 0x0240);
    }

    @Test
    public void singleActZoneFallsBackToAct0() throws Exception {
        Sonic2RingPlacement placement = new Sonic2RingPlacement(reader);

        List<RingSpawn> act0 = placement.load(new ZoneAct(16, 0)); // SCZ
        List<RingSpawn> act1 = placement.load(new ZoneAct(16, 1));
        assertFalse(act0.isEmpty());
        assertFalse(act1.isEmpty());
        assertEquals(act0.get(0), act1.get(0));
    }

    private static void assertContainsRing(List<RingSpawn> rings, int x, int y) {
        for (RingSpawn ring : rings) {
            if (ring.x() == x && ring.y() == y) {
                return;
            }
        }
        fail(String.format("Expected ring at 0x%04X,0x%04X", x, y));
    }

    @Test
    public void emeraldHillWaterfallRingIsVisibleBeforeSonicApproaches() {
        var configuration = com.openggf.configuration.SonicConfigurationService.getInstance();
        var widthKey = com.openggf.configuration.SonicConfiguration.SCREEN_WIDTH_PIXELS;
        int previousWidth = configuration.getInt(widthKey);
        try {
            TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_2);
            configuration.setSessionOverride(widthKey, 320);
            List<RingSpawn> rings = new Sonic2RingPlacement(reader).load(new ZoneAct(0, 0));
            var manager = new com.openggf.level.rings.RingManager(rings, null, null, null, null);
            // EHZ1 screenshot: Sonic's top-left X=3339, camera X=3195.
            // (3464,948) must be drawn even while (3528,900) is outside the window.
            manager.reset(3100);
            manager.update(3195, null, 0);
            assertContainsRing(List.copyOf(manager.getActiveSpawns()), 3464, 948);
            manager.update(3212, null, 1);
            assertContainsRing(List.copyOf(manager.getActiveSpawns()), 3464, 948);
            manager.update(3195, null, 2);
            assertContainsRing(List.copyOf(manager.getActiveSpawns()), 3464, 948);
        } finally {
            configuration.setSessionOverride(widthKey, previousWidth);
            com.openggf.game.session.SessionManager.clear();
        }
    }

    @Test
    public void expandedEmeraldHillRingsAreSortedByFullX() {
        List<RingSpawn> rings = new Sonic2RingPlacement(reader).load(new ZoneAct(0, 0));
        for (int i = 1; i < rings.size(); i++) {
            assertTrue(rings.get(i - 1).x() <= rings.get(i).x(),
                    "RingsMgr_SortRings sorts full X, including rings inside the same chunk");
        }
    }
}

