package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1SpringBalanceWidth {

    private static Sonic1SpringObjectInstance at(int subtype) {
        return new Sonic1SpringObjectInstance(
                new ObjectSpawn(0x0800, 0x0280, Sonic1ObjectIds.SPRING, subtype, 0, false, 0));
    }

    @Test
    void sidewaysSpringTakesSpringMainsNarrowerActiveWidth() {
        // Spring_Main's btst #4 branch overwrites obActWid with #16/2
        // (docs/s1disasm/_incObj/41 Springs.asm:49-56).
        Sonic1SpringObjectInstance sideways = at(0x10);

        assertEquals(0x08, sideways.getOnScreenHalfWidth(),
                "BuildSprites culls the sideways spring at #16/2");
        assertEquals(0x08, sideways.getBalanceWidthPixels(),
                "Sonic_Balance reads the sideways spring's obActWid, not the shared 16");
        assertEquals(0x13, sideways.getSolidParams().halfWidth(),
                "Spring_LR d1 is #16/2+sonic_solid_width at 41 Springs.asm:117");
    }

    @Test
    void uprightAndDownwardSpringsKeepTheInitialActiveWidth() {
        // Spring_Main writes #32/2 at :45; only the sideways branch overwrites it.
        assertEquals(0x10, at(0x00).getBalanceWidthPixels(),
                "the upright spring's ROM byte equals the shared default");
        assertEquals(0x10, at(0x20).getBalanceWidthPixels(),
                "the downward branch does not touch obActWid");
    }
}
