package com.openggf.sprites.managers;

import com.openggf.game.rules.GameRules;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression guard for the water-entry/exit splash.
 *
 * <p>The S2/S3K splash rides the fixed Sonic_Dust object (ROM writes
 * {@code anim=1} into it; sonic3k.asm:22241,22281) rather than spawning an
 * object slot. A 2026-05-26 refactor stubbed the splash path out entirely, so
 * no splash showed in S3K water zones (e.g. CNZ Act 2). This verifies the
 * controller plays and then ends the splash animation.
 */
class TestSpindashDustControllerSplash {

    @Test
    void s3kDustSidecarsUseFixedLevelObjectSlotsOutsideDynamicAllocator() {
        assertEquals(98, GameRules.SONIC_3K.powerUp().fixedDustSlotIndex(false),
                "S3K Dust is fixed Level_object_RAM slot 98 (sonic3k.constants.asm:309-317)");
        assertEquals(99, GameRules.SONIC_3K.powerUp().fixedDustSlotIndex(true),
                "S3K Dust_P2 is fixed Level_object_RAM slot 99 (sonic3k.constants.asm:309-317)");
        assertTrue(GameRules.SONIC_3K.powerUp().fixedSkidDustAllocatesAfterDynamicObjectPass(),
                "S3K Dust/Dust_P2 execute after Dynamic_object_RAM and may AllocateObject skid children");
        assertFalse(ObjectSlotLayout.SONIC_3K.isDynamicSlot(98),
                "Dust must not consume S3K AllocateObject dynamic slot pressure");
        assertFalse(ObjectSlotLayout.SONIC_3K.isDynamicSlot(99),
                "Dust_P2 must not consume S3K AllocateObject dynamic slot pressure");
    }

    @Test
    void s2DustSidecarsShareTheSameRelativeFixedSlotLayout() {
        assertEquals(132, GameRules.SONIC_2.powerUp().fixedDustSlotIndex(false),
                "S2 Sonic_Dust sits two slots before Sonic_Shield");
        assertEquals(133, GameRules.SONIC_2.powerUp().fixedDustSlotIndex(true),
                "S2 Tails_Dust sits one slot before Sonic_Shield");
    }

    @Test
    void groundedSpindashChargeDrawsDashDust() {
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0200);
        sonic.setSpindash(true);
        sonic.setAir(false);
        PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
        SpindashDustController controller = new SpindashDustController(sonic, renderer);

        controller.update();
        controller.draw();

        verify(renderer).drawFrame(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void forcedTunnelRollDoesNotDrawDashDustFromPinballSpindashAlias() {
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0200);
        sonic.setSpindash(true);
        sonic.setPinballMode(true);
        sonic.setRolling(true);
        sonic.setAir(false);
        PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
        SpindashDustController controller = new SpindashDustController(sonic, renderer);

        controller.update();
        controller.draw();

        verify(renderer, never()).drawFrame(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void splashTriggersAndAnimatesToCompletion() {
        // Null sprite/renderer is fine: update() ticks the splash before the
        // spindash-dust path (which guards null sprite), and we only assert on
        // the splash lifecycle, not rendering.
        SpindashDustController controller = new SpindashDustController(null, null);

        assertFalse(controller.isSplashActive(), "no splash before trigger");

        controller.triggerSplash(0x100, 0x200, false);
        assertTrue(controller.isSplashActive(), "splash active immediately after trigger");

        // 10 frames * 3 ticks/frame = 30 ticks; run a comfortable margin past it.
        boolean endedWithinWindow = false;
        for (int i = 0; i < 40; i++) {
            controller.update();
            if (!controller.isSplashActive()) {
                endedWithinWindow = true;
                break;
            }
        }
        assertTrue(endedWithinWindow, "splash animation should finish and clear itself");
    }

    /**
     * Surface-emerge splash (ROM Obj_DashDust anim 4 / Ani_DashSplashDrown byte_18DE8:
     * frames $16..$1D, duration 5 -> 6 displayed frames each, then $FD 0 ends it).
     * This is the LBZ1 "break the surface" splash, driven through the controller so it
     * never becomes an SST object (ROM mutates the fixed Dust slot).
     */
    @Test
    void surfaceSplashPlaysFramesSixteenThroughTwentyNineThenEnds() {
        SpindashDustController controller = new SpindashDustController(null, null);
        PlayerSpriteRenderer splashRenderer = mock(PlayerSpriteRenderer.class);

        assertFalse(controller.isSurfaceSplashActive(), "no surface splash before trigger");

        controller.triggerSurfaceSplash(splashRenderer, 0x00B0, 0x05C0);
        assertTrue(controller.isSurfaceSplashActive(), "active immediately after trigger");
        assertEquals(0x16, controller.surfaceSplashMappingFrame(), "starts on frame $16");

        // Each of the 8 frames ($16..$1D) holds for 6 update ticks.
        for (int frame = 0x16; frame <= 0x1D; frame++) {
            for (int tick = 0; tick < 6; tick++) {
                controller.update();
                if (frame < 0x1D || tick < 5) {
                    assertTrue(controller.isSurfaceSplashActive(),
                            "alive while frame 0x" + Integer.toHexString(frame) + " plays");
                    assertEquals(frame, controller.surfaceSplashMappingFrame(),
                            "frame 0x" + Integer.toHexString(frame) + " holds for 6 ticks");
                }
            }
        }

        // After 8*6 = 48 ticks the $FD 0 sentinel ends the effect on the next advance.
        controller.update();
        assertFalse(controller.isSurfaceSplashActive(), "surface splash ends after its final frame");
    }

    @Test
    void surfaceSplashIgnoresNullRenderer() {
        SpindashDustController controller = new SpindashDustController(null, null);
        controller.triggerSurfaceSplash(null, 0, 0);
        assertFalse(controller.isSurfaceSplashActive(), "null renderer must not start a splash");
    }

    @Test
    void controllerReportsAssignedFixedSlotWithoutAllocatingAnObject() {
        SpindashDustController controller = new SpindashDustController(null, null, 98);
        assertEquals(98, controller.fixedSlotIndex(),
                "controller should carry the fixed Dust slot identity for diagnostics");
    }
}
