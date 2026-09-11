package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1MzBrickObjectInstance {

    @Test
    void solidObjectIncludesExactRightEdge() {
        Sonic1MzBrickObjectInstance brick = new Sonic1MzBrickObjectInstance(
                new ObjectSpawn(0x460, 0x490, Sonic1ObjectIds.MZ_BRICK, 0, 0, false, 0));

        SolidRoutineProfile profile = brick.getSolidRoutineProfile();

        assertTrue(profile.inclusiveRightEdge(),
                "S1 Solid_ChkCollision uses BHI, retaining exact-edge side contact");
    }
}
