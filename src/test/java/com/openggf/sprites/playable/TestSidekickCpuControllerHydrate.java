package com.openggf.sprites.playable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SidekickCpuController#hydrateFromRomCpuState} widened signature
 * that accepts ROM {@code Tails_CPU_target_X} and {@code Tails_CPU_target_Y}
 * (ROM addresses 0xF70A / 0xF70C).
 *
 * Follows the lightweight {@code TestableSprite} pattern from {@link TestSidekickChainHealing}
 * so the test does not require ROM/OpenGL bootstrap.
 */
class TestSidekickCpuControllerHydrate {

    /** Minimal stub matching the pattern in {@link TestSidekickChainHealing}. */
    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) { super(code, (short) 0, (short) 0); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
        @Override protected void createSensorLines() {}
    }

    private SidekickCpuController newController() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk = new TestableSprite("tails_p2");
        sk.setCpuControlled(true);
        SidekickCpuController ctrl = new SidekickCpuController(sk, main);
        sk.setCpuController(ctrl);
        return ctrl;
    }

    @Test
    void hydrate_sets_internal_state_correctly() {
        SidekickCpuController controller = newController();
        controller.hydrateFromRomCpuState(0x06, 100, 200, 5, true, 0x0100, 0x0200);

        // State after hydration should be NORMAL (0x06 maps to State.NORMAL per mapRomCpuRoutine).
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "0x06 maps to NORMAL state");
        // jumping flag round-trips through the hydrated value.
        assertTrue(controller.getInputJump() == false,
                "clearInputs() resets input flags");
        // New target_x / target_y getters return the hydrated values.
        assertEquals(0x0100, controller.targetX(),
                "hydrated target_x should be reflected by targetX()");
        assertEquals(0x0200, controller.targetY(),
                "hydrated target_y should be reflected by targetY()");
    }

    @Test
    void target_xy_getters_return_hydrated_values() {
        SidekickCpuController controller = newController();
        controller.hydrateFromRomCpuState(0x06, 0, 0, 0, false, 0x0100, 0x0200);

        assertEquals(0x0100, controller.targetX());
        assertEquals(0x0200, controller.targetY());
    }

    @Test
    void target_xy_masked_to_16_bits() {
        SidekickCpuController controller = newController();
        // ROM Tails_CPU_target_X/Y are 16-bit words; mask must drop high bits.
        controller.hydrateFromRomCpuState(0x06, 0, 0, 0, false, 0x12345, 0x67890);

        assertEquals(0x2345, controller.targetX(),
                "targetX() should mask to 16 bits");
        assertEquals(0x7890, controller.targetY(),
                "targetY() should mask to 16 bits");
    }
}
