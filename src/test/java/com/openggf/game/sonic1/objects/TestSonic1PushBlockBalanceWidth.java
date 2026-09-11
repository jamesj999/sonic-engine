package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1PushBlockBalanceWidth {

    private static Sonic1PushBlockObjectInstance at(int subtype) {
        return new Sonic1PushBlockObjectInstance(
                new ObjectSpawn(0x0600, 0x03C0, Sonic1ObjectIds.PUSH_BLOCK,
                        subtype, 0, false, 0));
    }

    @Test
    void theWideBlockBalancesAtPushBVarsSixtyFour() {
        // PushB_Var's second entry is dc.b 128/2,1
        // (docs/s1disasm/_incObj/33 MZ, LZ Pushable Blocks.asm:22-24,48-49).
        // mz2 places the one 4x1 block as subtype $81.
        Sonic1PushBlockObjectInstance wide = at(0x81);

        assertEquals(0x40, wide.getBalanceWidthPixels(),
                "Sonic_Balance reads the 4x1 block's obActWid, not the shared 16");
        assertEquals(0x40 + 0x0B, wide.getSolidParams().halfWidth(),
                "PushB_Action pads only its SolidObject d1 by sonic_solid_width");
    }

    @Test
    void theSingleBlockKeepsPushBVarsSixteen() {
        assertEquals(0x10, at(0x00).getBalanceWidthPixels(),
                "the 1x1 block's ROM byte equals the shared default");
    }
}
