package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1SpinPlatformBalanceWidth {

    private static Sonic1SpinPlatformObjectInstance at(int subtype) {
        return new Sonic1SpinPlatformObjectInstance(
                new ObjectSpawn(0x0C00, 0x0400, Sonic1ObjectIds.SBZ_SPINNING_PLATFORM, subtype, 0, false, 0));
    }

    @Test
    void trapdoorKeepsSpinMainsShippedActiveWidth() {
        // Spin_Main writes move.b #256/2,obActWid(a0) on the shipped FixBugs = 0
        // branch and the trapdoor path never overwrites it
        // (docs/s1disasm/_incObj/69 SBZ Spinning Platforms and Trapdoors.asm:28-31,46).
        Sonic1SpinPlatformObjectInstance trapdoor = at(0x01);

        assertEquals(0x80, trapdoor.getOnScreenHalfWidth(),
                "BuildSprites culls the trapdoor at #256/2");
        assertEquals(0x80, trapdoor.getBalanceWidthPixels(),
                "Sonic_Balance reads the trapdoor's obActWid, not the shared 16");
        assertEquals(0x4B, trapdoor.getSolidParams().halfWidth(),
                "Spin_Trapdoor d1 is #128/2+sonic_solid_width at :85");
    }

    @Test
    void spinnerTakesTheOverwrittenActiveWidth() {
        // The spinner path overwrites obActWid with #32/2 at :49.
        Sonic1SpinPlatformObjectInstance spinner = at(0x80);

        assertEquals(0x10, spinner.getOnScreenHalfWidth(),
                "Spin_Main overwrites obActWid with #32/2 for spinners");
        assertEquals(0x10, spinner.getBalanceWidthPixels(),
                "the spinner's ROM byte happens to equal the shared default");
    }
}
