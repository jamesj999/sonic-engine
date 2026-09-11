package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzRisingPlatformInstance {

    @Test
    void solidTopWidthMatchesRomD1() {
        CnzRisingPlatformInstance platform = new CnzRisingPlatformInstance(
                new ObjectSpawn(0x1A40, 0x0790, 0x43, 0, 0, false, 0));

        assertEquals(0x30, platform.getSolidParams().halfWidth());
        assertEquals(0x10, platform.getSolidParams().airHalfHeight());
        assertEquals(0x11, platform.getSolidParams().groundHalfHeight());
    }

    @Test
    void risingPlatformUsesRomWidthPixelsForObjectBalance() {
        CnzRisingPlatformInstance platform = new CnzRisingPlatformInstance(
                new ObjectSpawn(0x1180, 0x0848, 0x43, 0, 0, false, 0));

        assertEquals(0x30, platform.getOnScreenHalfWidth(),
                "Obj_CNZRisingPlatform writes width_pixels=$30; Sonic_Move reads width_pixels(a1) "
                        + "for object balance (sonic3k.asm:67132,22462-22473).");

        int sonicX = 0x1166;
        int d1 = sonicX + platform.getOnScreenHalfWidth() - platform.getX();
        int d2 = platform.getOnScreenHalfWidth() * 2 - 2;

        assertFalse(d1 < 2 || d1 >= d2,
                "CNZ2 f21107 geometry must not enter Sonic_BalanceOnObjLeft/Right; "
                        + "using the default $10 sprite width would produce d1=$FFF6 and flip Sonic left.");
    }

    @Test
    void ridingBoundsDoNotUseEngineStickyBuffer() {
        CnzRisingPlatformInstance platform = new CnzRisingPlatformInstance(
                new ObjectSpawn(0x1A40, 0x0790, 0x43, 0, 0, false, 0));

        assertFalse(platform.usesStickyContactBuffer(),
                "ROM SolidObjectTop_1P exits at the exact d1*2 ride bounds");
    }

    @Test
    void exposesTopSolidRoutineProfileWithoutStickyBuffer() {
        CnzRisingPlatformInstance platform = new CnzRisingPlatformInstance(
                new ObjectSpawn(0x1A40, 0x0790, 0x43, 0, 0, false, 0));

        SolidRoutineProfile profile = platform.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, profile.kind());
        assertTrue(profile.topSolidOnly());
        assertFalse(profile.stickyContactBuffer(),
                "CNZ rising platform keeps ROM exact d1*2 ride bounds through its profile");
    }

    @Test
    void floorSnappedPlatformDoesNotBounceAgainWhenRiderLeaves() throws Exception {
        CnzRisingPlatformInstance platform = new CnzRisingPlatformInstance(
                new ObjectSpawn(0x1A40, 0x0790, 0x43, 0, 0, false, 0));

        setField(platform, "armed", true);
        setField(platform, "floorSettledRoutine", true);
        setField(platform, "displayFrame", 2);

        platform.update(0, null);

        assertEquals(0, platform.getYSpeedForTest(), "Terminal floor-snap state must not create a release bounce");
        assertEquals(2, platform.getRenderFrameForTest());
    }

    @Test
    void animationFrameTwoDoesNotMeanTerminalRoutine() throws Exception {
        CnzRisingPlatformInstance platform = new CnzRisingPlatformInstance(
                new ObjectSpawn(0x1A40, 0x0790, 0x43, 0, 0, false, 0));

        setField(platform, "armed", true);
        setField(platform, "displayFrame", 2);
        setField(platform, "motion.yVel", 0x0100);

        platform.update(0, null);

        assertFalse(platform.isArmedForTest());
        assertEquals(-0x0180, platform.getYSpeedForTest(),
                "ROM loc_31C86 negates y_vel and subtracts $80 when the rider leaves");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        if (name.contains(".")) {
            String[] parts = name.split("\\.", 2);
            Field outer = target.getClass().getDeclaredField(parts[0]);
            outer.setAccessible(true);
            Object nested = outer.get(target);
            setField(nested, parts[1], value);
            return;
        }
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
