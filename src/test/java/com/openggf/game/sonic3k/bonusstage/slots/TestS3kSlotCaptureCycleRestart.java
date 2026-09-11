package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kSlotCaptureCycleRestart {

    @Test
    void captureRestartUsesRomSpinInitStateInsteadOfPassiveSettleState() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotStageState state = controller.stageStateForTest();
        state.setOptionCycleState(0x18);
        state.setOptionCycleCountdown(0x20);
        state.setOptionCycleSpinCycleCounter(7);
        state.setOptionCycleLockProgress(3);
        state.setOptionCycleResolvedDisplayTimer(0x12);
        state.setOptionCycleLastPrize(30);

        controller.restartCaptureCycleIfResolved();

        assertEquals(0x08, state.optionCycleState());
        assertEquals(0, state.optionCycleCountdown());
        assertEquals(0, state.optionCycleSpinCycleCounter());
        assertEquals(0, state.optionCycleLockProgress());
        assertEquals(0, state.optionCycleResolvedDisplayTimer());
        assertEquals(Integer.MIN_VALUE, state.optionCycleLastPrize());
    }
}


