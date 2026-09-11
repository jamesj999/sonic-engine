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
}


