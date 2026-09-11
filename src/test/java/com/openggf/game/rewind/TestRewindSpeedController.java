package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRewindSpeedController {
    @Test
    void disabledPolicyPreservesOneStepWhileHeldAndNoCoastOnRelease() {
        RewindSpeedController controller = RewindSpeedController.disabled();

        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(0, controller.stepsAfterRelease());
    }

    @Test
    void enabledPolicyStartsAtMinThenAcceleratesAndDecelerates() {
        RewindSpeedController controller = new RewindSpeedController(true, 1.0, 1.0, 1.0, 3.0);

        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(2, controller.stepsWhileHeld());
        assertEquals(3, controller.stepsWhileHeld());

        assertEquals(2, controller.stepsAfterRelease());
        assertEquals(1, controller.stepsAfterRelease());
        assertEquals(0, controller.stepsAfterRelease());
    }

    @Test
    void newHoldCancelsCoastAndUsesCurrentHeldSpeed() {
        RewindSpeedController controller = new RewindSpeedController(true, 1.0, 1.0, 0.5, 3.0);

        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(2, controller.stepsWhileHeld());
        assertEquals(3, controller.stepsWhileHeld());
        assertEquals(2, controller.stepsAfterRelease());

        assertEquals(3, controller.stepsWhileHeld());
    }

    @Test
    void enabledPolicyTreatsZeroDecelerationAsImmediateReleaseStop() {
        RewindSpeedController controller = new RewindSpeedController(true, 1.0, 1.0, 0.0, 3.0);

        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(2, controller.stepsWhileHeld());

        assertEquals(0, controller.stepsAfterRelease());
    }

    @Test
    void subUnitMinAccumulatesFractionalStepsAcrossFrames() {
        RewindSpeedController controller = new RewindSpeedController(true, 0.25, 0.0, 0.5, 0.25);

        // Speed snaps to MIN=0.25 on the first held frame, then ACCEL=0 keeps it there.
        // 0.25 + 0.25 + 0.25 + 0.25 = 1.0 -> one physics step on the 4th held frame.
        assertEquals(0, controller.stepsWhileHeld());
        assertEquals(0, controller.stepsWhileHeld());
        assertEquals(0, controller.stepsWhileHeld());
        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(0.25, controller.currentSpeed(), 1e-9);
    }

    @Test
    void subUnitMaxStaysSlowMoEvenAtCap() {
        RewindSpeedController controller = new RewindSpeedController(true, 0.5, 0.25, 0.5, 0.5);

        // ACCEL is capped by MAX=0.5, so speed never exceeds 0.5.
        assertEquals(0, controller.stepsWhileHeld());
        assertEquals(1, controller.stepsWhileHeld()); // 0.5 + 0.5 = 1.0
        assertEquals(0.5, controller.currentSpeed(), 1e-9);
    }

    @Test
    void currentSpeedReportsZeroWhenDisabledControllerIdle() {
        RewindSpeedController controller = RewindSpeedController.disabled();
        assertEquals(0.0, controller.currentSpeed(), 1e-9);
        controller.stepsWhileHeld();
        assertEquals(1.0, controller.currentSpeed(), 1e-9);
        controller.stepsAfterRelease();
        assertEquals(0.0, controller.currentSpeed(), 1e-9);
    }

    @Test
    void halfSpeedMultiplierAlternatesStepsWithoutCoast() {
        RewindSpeedController controller = RewindSpeedController.disabled();
        controller.setHeldSpeedMultiplier(0.5);

        assertEquals(0, controller.stepsWhileHeld());
        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(0, controller.stepsWhileHeld());
        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(0.5, controller.currentSpeed(), 1e-9);
    }

    @Test
    void doubleSpeedMultiplierStepsTwicePerFrameWithoutCoast() {
        RewindSpeedController controller = RewindSpeedController.disabled();
        controller.setHeldSpeedMultiplier(2.0);

        assertEquals(2, controller.stepsWhileHeld());
        assertEquals(2, controller.stepsWhileHeld());
        assertEquals(2.0, controller.currentSpeed(), 1e-9);
    }

    @Test
    void unitMultiplierPreservesLegacySingleStepBehaviour() {
        RewindSpeedController controller = RewindSpeedController.disabled();
        controller.setHeldSpeedMultiplier(0.5);
        controller.setHeldSpeedMultiplier(1.0);

        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(1.0, controller.currentSpeed(), 1e-9);
    }

    @Test
    void multiplierScalesCoastSpeedWithoutCompounding() {
        RewindSpeedController controller = new RewindSpeedController(true, 1.0, 1.0, 1.0, 3.0);
        controller.setHeldSpeedMultiplier(2.0);

        assertEquals(2, controller.stepsWhileHeld());
        assertEquals(4, controller.stepsWhileHeld());
        assertEquals(6, controller.stepsWhileHeld());
        assertEquals(6, controller.stepsWhileHeld());
        assertEquals(6.0, controller.currentSpeed(), 1e-9);
    }

    @Test
    void resetRestoresUnitMultiplier() {
        RewindSpeedController controller = RewindSpeedController.disabled();
        controller.setHeldSpeedMultiplier(2.0);
        controller.stepsWhileHeld();
        controller.reset();

        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(1.0, controller.currentSpeed(), 1e-9);
    }

    @Test
    void nonPositiveMultiplierIsTreatedAsUnit() {
        RewindSpeedController controller = RewindSpeedController.disabled();
        controller.setHeldSpeedMultiplier(0.0);

        assertEquals(1, controller.stepsWhileHeld());
        assertEquals(1.0, controller.currentSpeed(), 1e-9);
    }
}
