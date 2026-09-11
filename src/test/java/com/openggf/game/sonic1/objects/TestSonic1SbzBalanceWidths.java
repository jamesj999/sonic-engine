package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1SbzBalanceWidths {

    @Test
    void junctionBalancesAtTheParentsRomActiveWidth() {
        // Jun_Main writes move.b #96/2,obActWid(a0) for the parent
        // (docs/s1disasm/_incObj/66 SBZ Rotating Junction.asm:47-48). The #112/2
        // at :43 is the cover-up child, which Jun_Main puts into routine 4
        // (display-only) at :32 and never makes solid.
        Sonic1JunctionObjectInstance junction = new Sonic1JunctionObjectInstance(
                new ObjectSpawn(1424, 1136, Sonic1ObjectIds.JUNCTION, 0, 0, false, 0));

        assertEquals(0x30, junction.getBalanceWidthPixels(),
                "Sonic_Balance reads the junction parent's obActWid, not the shared 16");
        assertEquals(0x30, junction.getOnScreenHalfWidth(),
                "BuildSprites culls the parent at the same byte");
        assertEquals(74 / 2 + 0x0B, junction.getSolidParams().halfWidth(),
                "Jun_Action d1 is #74/2+sonic_solid_width at :60");
    }

    @Test
    void smallDoorBalancesNarrowerThanTheSharedDefault() {
        // ADoor_Main writes move.b #16/2,obActWid(a0) = 8
        // (docs/s1disasm/_incObj/2A SBZ Small Door.asm:20-21).
        Sonic1SmallDoorObjectInstance door = new Sonic1SmallDoorObjectInstance(
                new ObjectSpawn(1800, 1196, Sonic1ObjectIds.SBZ_SMALL_DOOR, 0, 0, false, 0));

        assertEquals(0x08, door.getBalanceWidthPixels(),
                "Sonic_Balance reads Obj2A obActWid = #16/2");
        assertEquals(0x11, door.getSolidParams().halfWidth(),
                "ADoor_Animate d1 is #12/2+sonic_solid_width at :62");

        // At 8 the ROM's window is wider than the engine's old one, not narrower:
        // d1 = 8 + dx, d2 = 12, so it balances outside |dx| >= 4 where the
        // inherited 16 balanced only outside |dx| >= 12 (01 Sonic.asm:422-431).
        int w = door.getBalanceWidthPixels();
        int d2 = 2 * w - 4;
        int dx = 6;
        assertTrue((w + dx) >= d2, "ROM balances at dx=6 on a small door");
        assertTrue(!((16 + dx) < 4 || (16 + dx) >= (2 * 16 - 4)),
                "the inherited 16 did not balance at dx=6, which is the divergence");
    }
}
