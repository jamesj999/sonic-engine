package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1InvisibleBarrierBalanceWidth {

    private static Sonic1InvisibleBarrierObjectInstance at(int subtype) {
        return new Sonic1InvisibleBarrierObjectInstance(
                new ObjectSpawn(0x1000, 0x0500, Sonic1ObjectIds.INVISIBLE_BARRIER,
                        subtype, 0, false, 0));
    }

    @Test
    void barrierBalanceUsesTheSubtypeDerivedRomActiveWidth() {
        // Invis_Main: ((subtype & $F0) + $10) >> 1
        // (docs/s1disasm/_incObj/71 Invisible Solid Barriers.asm:22-27).
        // sbz1 places subtype $70 twelve times.
        Sonic1InvisibleBarrierObjectInstance wide = at(0x70);

        assertEquals(0x40, wide.getBalanceWidthPixels(),
                "Sonic_Balance reads Obj71's subtype-derived obActWid");
        assertEquals(0x40 + 0x0B, wide.getSolidParams().halfWidth(),
                "Invis_Solid pads only its SolidObject d1 by sonic_solid_width");
    }

    @Test
    void theNarrowestAndWidestPlacedSubtypesBothTrackTheRomExpression() {
        assertEquals(0x08, at(0x00).getBalanceWidthPixels(), "subtype $0x gives ($00+$10)>>1");
        assertEquals(0x78, at(0xE1).getBalanceWidthPixels(), "subtype $Ex gives ($E0+$10)>>1");
        assertEquals(0x10, at(0x11).getBalanceWidthPixels(),
                "subtype $1x happens to equal the shared default");
    }
}
