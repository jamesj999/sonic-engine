package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1StomperDoorSolidProfile {

    @Test
    void movingStomperRetainsRetailSolidObjectBoundsAndSstLatch() {
        Sonic1StomperDoorObjectInstance stomper = new Sonic1StomperDoorObjectInstance(
                new ObjectSpawn(0x0D80, 0x0568, Sonic1ObjectIds.SBZ_STOMPER_DOOR,
                        0x10, 0, false, 0),
                Sonic1Constants.ZONE_SBZ);

        assertTrue(stomper.getSolidRoutineProfile().inclusiveRightEdge(),
                "Sto_Solid inherits SolidObject's inclusive-right initial bound");
        assertTrue(stomper.usesInstanceSolidStateLatchKey(),
                "Obj6B status bits remain in its SST while its dynamic spawn moves");
    }
}
