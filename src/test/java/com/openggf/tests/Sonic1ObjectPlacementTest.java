package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.Sonic1ObjectPlacement;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Sonic 1 object placement parsing against known-good positions
 * from the disassembly (docs/s1disasm/objpos/*.bin).
 */
@RequiresRom(SonicGame.SONIC_1)
public class Sonic1ObjectPlacementTest {
    @Test
    public void parsesGhz1ObjectCount() throws Exception {
        List<ObjectSpawn> spawns = loadGhz1();
        // GHZ1 has 214 object records (verified from disassembly binary)
        assertEquals(214, spawns.size(), "GHZ1 object count");
    }

    @Test
    public void parsesFirstGhz1Object() throws Exception {
        List<ObjectSpawn> spawns = loadGhz1();
        assertFalse(spawns.isEmpty());
        // First object in GHZ1 sorted by X (verified from objpos/ghz1.bin)
        ObjectSpawn first = spawns.get(0);
        assertTrue(first.x() < 0x0200, "First object X should be small");
    }

    /**
     * Verifies all three Crabmeat spawn positions in GHZ Act 1 match the
     * disassembly data (docs/s1disasm/objpos/ghz1.bin).
     * <p>
     * Known-good positions from the binary:
     * <ul>
     *   <li>Record 20: X=0x08B0, Y=0x0350</li>
     *   <li>Record 21: X=0x0960, Y=0x02FA</li>
     *   <li>Record 189: X=0x2180, Y=0x048C</li>
     * </ul>
     */
    @Test
    public void crabmeatPositionsMatchDisassembly() throws Exception {
        List<ObjectSpawn> spawns = loadGhz1();
        List<ObjectSpawn> crabmeats = spawns.stream()
                .filter(s -> s.objectId() == Sonic1ObjectIds.CRABMEAT)
                .toList();

        assertEquals(3, crabmeats.size(), "GHZ1 should have 3 Crabmeats");

        // Crabmeat #1: on lower platform area
        assertSpawnPosition("Crabmeat #1", crabmeats.get(0),
                0x08B0, 0x0350, Sonic1ObjectIds.CRABMEAT, 0x00, true, 0);

        // Crabmeat #2: on elevated platform
        assertSpawnPosition("Crabmeat #2", crabmeats.get(1),
                0x0960, 0x02FA, Sonic1ObjectIds.CRABMEAT, 0x00, true, 0);

        // Crabmeat #3: later in the level
        assertSpawnPosition("Crabmeat #3", crabmeats.get(2),
                0x2180, 0x048C, Sonic1ObjectIds.CRABMEAT, 0x00, true, 0);
    }

    @Test
    public void crabmeatRenderFlagsAreZeroInGhz1() throws Exception {
        List<ObjectSpawn> spawns = loadGhz1();
        List<ObjectSpawn> crabmeats = spawns.stream()
                .filter(s -> s.objectId() == Sonic1ObjectIds.CRABMEAT)
                .toList();

        for (int i = 0; i < crabmeats.size(); i++) {
            assertEquals(0, crabmeats.get(i).renderFlags(), "Crabmeat " + i + " renderFlags should be 0");
        }
    }

    @Test
    public void objectsAreSortedByX() throws Exception {
        List<ObjectSpawn> spawns = loadGhz1();
        for (int i = 1; i < spawns.size(); i++) {
            assertTrue(spawns.get(i).x() >= spawns.get(i - 1).x(), "Objects must be sorted by X: index " + i);
        }
    }

    @Test
    public void parsesGhz2Crabmeats() throws Exception {
        List<ObjectSpawn> spawns = loadZone(Sonic1Constants.ZONE_GHZ, 1);
        List<ObjectSpawn> crabmeats = spawns.stream()
                .filter(s -> s.objectId() == Sonic1ObjectIds.CRABMEAT)
                .toList();

        assertEquals(4, crabmeats.size(), "GHZ2 should have 4 Crabmeats");
        // All Crabmeats should have valid positions (Y < 0x1000, X > 0)
        for (ObjectSpawn crab : crabmeats) {
            assertTrue(crab.x() > 0, "Crabmeat X > 0");
            assertTrue(crab.y() < 0x1000, "Crabmeat Y < 0x1000");
        }
    }

    @Test
    public void pointerTableFirstEntryIsValid() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
        int firstOffset = reader.readU16BE(Sonic1Constants.OBJ_POS_INDEX_ADDR);
        // First entry should be a reasonable offset (not zero, not huge)
        assertTrue(firstOffset > 0, "First pointer offset should be > 0");
        assertTrue(firstOffset < 0x2000, "First pointer offset should be < 0x2000");
    }

    private List<ObjectSpawn> loadGhz1() throws Exception {
        return loadZone(Sonic1Constants.ZONE_GHZ, 0);
    }

    private List<ObjectSpawn> loadZone(int zone, int act) throws Exception {
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
        Sonic1ObjectPlacement placement = new Sonic1ObjectPlacement(reader);
        return placement.load(zone, act);
    }

    private static void assertSpawnPosition(
            String label,
            ObjectSpawn spawn,
            int expectedX,
            int expectedY,
            int expectedObjectId,
            int expectedSubtype,
            boolean expectedRespawn,
            int expectedRenderFlags) {
        assertEquals(expectedX, spawn.x(), label + " X");
        assertEquals(expectedY, spawn.y(), label + " Y");
        assertEquals(expectedObjectId, spawn.objectId(), label + " objectId");
        assertEquals(expectedSubtype, spawn.subtype(), label + " subtype");
        assertEquals(expectedRespawn, spawn.respawnTracked(), label + " respawnTracked");
        assertEquals(expectedRenderFlags, spawn.renderFlags(), label + " renderFlags");
    }
}


