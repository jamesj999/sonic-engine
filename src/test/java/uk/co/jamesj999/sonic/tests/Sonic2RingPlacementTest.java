package uk.co.jamesj999.sonic.tests;

import org.junit.Assume;

import org.junit.Test;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.data.games.Sonic2RingPlacement;
import uk.co.jamesj999.sonic.data.games.ZoneAct;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class Sonic2RingPlacementTest {

    private static final Path REV01_ROM = Path.of("Sonic The Hedgehog 2 (W) (REV01) [!].gen");

    @Test
    public void ringPointerTableMatchesRev01Offsets() throws Exception {
        Assume.assumeTrue("Rev01 ROM missing", Files.exists(REV01_ROM));
        RomByteReader reader = new RomByteReader(Files.readAllBytes(REV01_ROM));
        assertEquals(0x0044, reader.readU16BE(Sonic2RingPlacement.OFF_RINGS_REV01));
        assertEquals(0x026A, reader.readU16BE(Sonic2RingPlacement.OFF_RINGS_REV01 + 2));
    }

    @Test
    public void parsesEmeraldHillAct1RingGroup() throws Exception {
        Assume.assumeTrue("Rev01 ROM missing", Files.exists(REV01_ROM));
        RomByteReader reader = new RomByteReader(Files.readAllBytes(REV01_ROM));
        Sonic2RingPlacement placement = new Sonic2RingPlacement(reader);

        List<RingSpawn> rings = placement.load(new ZoneAct(0, 0));
        assertFalse(rings.isEmpty());

        assertContainsRing(rings, 0x0124, 0x0240);
        assertContainsRing(rings, 0x013C, 0x0240);
        assertContainsRing(rings, 0x0154, 0x0240);
    }

    @Test
    public void singleActZoneFallsBackToAct0() throws Exception {
        Assume.assumeTrue("Rev01 ROM missing", Files.exists(REV01_ROM));
        RomByteReader reader = new RomByteReader(Files.readAllBytes(REV01_ROM));
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
