package com.openggf.tests;

import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2ObjectPlacement;
import com.openggf.game.sonic2.ZoneAct;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
public class Sonic2ObjectPlacementTest {

    @Test
    void parsesFirstEmeraldHillAct1Object() throws Exception {
        Sonic2ObjectPlacement placement = new Sonic2ObjectPlacement(RomByteReader.fromRom(GameServices.rom().getRom()));

        List<ObjectSpawn> spawns = placement.load(new ZoneAct(0, 0));
        assertFalse(spawns.isEmpty());

        ObjectSpawn first = spawns.get(0);
        assertEquals(0x0227, first.x());
        assertEquals(0x0228, first.y());
        assertEquals(0x9D, first.objectId());
        assertEquals(0x1E, first.subtype());
        assertTrue(first.respawnTracked());
        assertEquals(0, first.renderFlags());
    }

    @Test
    void parsesFirstObjectsForAdditionalActs() throws Exception {
        Sonic2ObjectPlacement placement = new Sonic2ObjectPlacement(RomByteReader.fromRom(GameServices.rom().getRom()));

        assertFirstSpawn(placement, new ZoneAct(4, 0), 0x0160, 0x0140, 0xA4, 0x2E, true, 0);
        assertFirstSpawn(placement, new ZoneAct(5, 0), 0x0230, 0x01F0, 0xA1, 0x28, true, 0);
        assertFirstSpawn(placement, new ZoneAct(6, 0), 0x0060, 0x04E4, 0xB2, 0x52, true, 0);
        assertFirstSpawn(placement, new ZoneAct(7, 1), 0x03D0, 0x0636, 0x32, 0x00, false, 0);
        assertFirstSpawn(placement, new ZoneAct(8, 0), 0x0348, 0x0180, 0x71, 0x11, false, 0);
        assertFirstSpawn(placement, new ZoneAct(10, 0), 0x01C0, 0x06F0, 0x1F, 0x00, false, 0);
    }

    @Test
    void clampsActIndexForMetropolisAct3() throws Exception {
        Sonic2ObjectPlacement placement = new Sonic2ObjectPlacement(RomByteReader.fromRom(GameServices.rom().getRom()));

        List<ObjectSpawn> act2 = placement.load(new ZoneAct(7, 1));
        List<ObjectSpawn> act3 = placement.load(new ZoneAct(7, 2));
        assertFalse(act2.isEmpty());
        assertFalse(act3.isEmpty());
        assertEquals(act2.get(0), act3.get(0));
    }

    @Test
    void clampsActIndexForSingleActZones() throws Exception {
        Sonic2ObjectPlacement placement = new Sonic2ObjectPlacement(RomByteReader.fromRom(GameServices.rom().getRom()));

        List<ObjectSpawn> act0 = placement.load(new ZoneAct(6, 0));
        List<ObjectSpawn> act1 = placement.load(new ZoneAct(6, 1));
        assertFalse(act0.isEmpty());
        assertFalse(act1.isEmpty());
        assertEquals(act0.get(0), act1.get(0));
    }

    @Test
    void pointerTableMatchesRev01Offsets() throws Exception {
        RomByteReader reader = RomByteReader.fromRom(GameServices.rom().getRom());
        assertEquals(0x004A, reader.readU16BE(Sonic2ObjectPlacement.OFF_OBJECTS_REV01));
        assertEquals(0x037A, reader.readU16BE(Sonic2ObjectPlacement.OFF_OBJECTS_REV01 + 2));
    }

    private static void assertFirstSpawn(
            Sonic2ObjectPlacement placement,
            ZoneAct zoneAct,
            int expectedX,
            int expectedY,
            int expectedObjectId,
            int expectedSubtype,
            boolean expectedRespawn,
            int expectedRenderFlags) {
        List<ObjectSpawn> spawns = placement.load(zoneAct);
        assertFalse(spawns.isEmpty());
        ObjectSpawn first = spawns.get(0);
        assertEquals(expectedX, first.x());
        assertEquals(expectedY, first.y());
        assertEquals(expectedObjectId, first.objectId());
        assertEquals(expectedSubtype, first.subtype());
        assertEquals(expectedRespawn, first.respawnTracked());
        assertEquals(expectedRenderFlags, first.renderFlags());
    }
}


