package com.openggf.game;

import com.openggf.game.rules.GameRules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the per-game gating of the
 * {@code SolidObject_cont} top-branch upward-velocity lift.
 *
 * <p>S3K {@code loc_1E154} (sonic3k.asm:41606-41632) writes the position lift
 * unconditionally before testing {@code y_vel(a1)}, so an upward-moving player
 * inside the small-overlap window receives the +(3 - distY) px shift even
 * though no standing/landing state changes.  S1 {@code Solid_Landed}
 * (s1disasm/_incObj/sub SolidObject.asm:267-286) and S2
 * {@code SolidObject_Landed} (s2.asm:35368-35388) bail at the
 * {@code tst.w y_vel(a1) / bmi.s SolidObject_Miss} test before any position
 * write.  The engine encodes that divergence on
 * {@link GameRules#solidObjectTopBranchAlwaysLiftsOnUpwardVelocity()};
 * if the per-game value flips, regressed S3K spring/horizontal-spring
 * geometry (CNZ trace F7614 Tails_Jump on Obj_Spring_Horizontal) or S1/S2
 * landing baselines will mismatch the ROM trace at the next contact frame.
 */
class TestSolidObjectTopBranchUpwardLift {

    @Test
    void s3kEnablesUpwardVelocityLift() {
        assertTrue(GameRules.SONIC_3K.collision().solidObjectTopBranchAlwaysLiftsOnUpwardVelocity(),
                "S3K loc_1E154 (sonic3k.asm:41606-41632) writes y_pos before tst.w y_vel(a1)");
    }

    @Test
    void s2DoesNotEnableUpwardVelocityLift() {
        assertFalse(GameRules.SONIC_2.collision().solidObjectTopBranchAlwaysLiftsOnUpwardVelocity(),
                "S2 SolidObject_Landed (s2.asm:35379-35380) bails on tst.w y_vel BEFORE the lift");
    }

    @Test
    void s1DoesNotEnableUpwardVelocityLift() {
        assertFalse(GameRules.SONIC_1.collision().solidObjectTopBranchAlwaysLiftsOnUpwardVelocity(),
                "S1 Solid_Landed (s1disasm/_incObj/sub SolidObject.asm:278) bails on tst.w obVelY BEFORE the lift");
    }
}
