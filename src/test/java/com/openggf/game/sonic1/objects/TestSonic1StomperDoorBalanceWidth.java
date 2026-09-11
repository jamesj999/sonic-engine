package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1StomperDoorBalanceWidth {

    @Test
    void slidingDoorBalanceUsesRomActiveWidthWithoutSolidPadding() {
        Sonic1StomperDoorObjectInstance door = new Sonic1StomperDoorObjectInstance(
                new ObjectSpawn(0x0F40, 0x060C, Sonic1ObjectIds.SBZ_STOMPER_DOOR,
                        0x81, 0, false, 0),
                Sonic1Constants.ZONE_SBZ);

        assertEquals(0x40, door.getBalanceWidthPixels(),
                "Sonic_Move reads Obj6B obActWid from Sto_Var");
        assertEquals(0x4B, door.getSolidParams().halfWidth(),
                "Sto_Action adds $B only to SolidObject d1");
    }
}
