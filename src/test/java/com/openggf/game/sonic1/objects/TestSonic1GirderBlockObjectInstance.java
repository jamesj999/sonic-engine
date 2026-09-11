package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1GirderBlockObjectInstance {

    @Test
    public void girderUsesRomSolidObjectLatchSemantics() {
        Sonic1GirderBlockObjectInstance girder = new Sonic1GirderBlockObjectInstance(
                new ObjectSpawn(0x1800, 0x0318, Sonic1ObjectIds.GIRDER, 0, 0, false, 0));

        SolidRoutineProfile profile = girder.getSolidRoutineProfile();

        assertTrue(profile.inclusiveRightEdge(),
                "S1 Solid_ChkEnter rejects the far right edge with BHI, so exact edge contact is solid");
        assertTrue(girder.usesInstanceSolidStateLatchKey(),
                "SolidObject standing/pushing bits live in the girder SST status byte, not its dynamic spawn");
        assertEquals(0x60, girder.getBalanceWidthPixels(),
                "Sonic_Move balance reads Obj70 obActWid, not the SolidObject-expanded width");
    }
}
