package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1LabyrinthBlockObjectInstance {

    @Test
    void balanceUsesRomActiveWidthWithoutSolidObjectPadding() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x13E0,
                0x070C,
                Sonic1ObjectIds.LABYRINTH_BLOCK,
                0x13,
                0,
                false,
                0
        );
        Sonic1LabyrinthBlockObjectInstance block = new Sonic1LabyrinthBlockObjectInstance(spawn);

        assertEquals(0x20, block.getBalanceWidthPixels(),
                "Sonic_Move reads Obj61 obActWid from LBlk_Var");
        assertEquals(0x2B, block.getSolidParams().halfWidth(),
                "LBlk_Solid adds $B only to the SolidObject collision width");
        assertTrue(block.getSolidRoutineProfile().inclusiveRightEdge(),
                "LBlk_Solid retains SolidObject's inclusive right edge");
    }
}
