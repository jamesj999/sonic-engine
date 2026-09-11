package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotExitSequence {

    @Test
    void windDownPhaseLastsExpectedFrames() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotExitSequence exit = new S3kSlotExitSequence(controller);

        int windDownFrames = 0;
        while (!exit.isFading() && !exit.isComplete() && windDownFrames < 200) {
            exit.tick();
            windDownFrames++;
        }
        // ROM loc_4BC1E starts from the live scalar index (0x40) and reaches 0x1800 in 95 ticks.
        assertEquals(95, windDownFrames);
        assertTrue(exit.isFading());
    }

    @Test
    void fadePhaseLasts60Frames() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotExitSequence exit = new S3kSlotExitSequence(controller);

        // Skip wind-down
        for (int i = 0; i < 95; i++) exit.tick();
        assertTrue(exit.isFading());

        int fadeFrames = 0;
        while (!exit.isComplete() && fadeFrames < 100) {
            exit.tick();
            fadeFrames++;
        }
        assertEquals(60, fadeFrames);
        assertTrue(exit.isComplete());
    }

    @Test
    void totalExitSequenceIs156Frames() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotExitSequence exit = new S3kSlotExitSequence(controller);

        int totalFrames = 0;
        while (!exit.isComplete() && totalFrames < 300) {
            exit.tick();
            totalFrames++;
        }
        assertEquals(155, totalFrames); // 95 wind-down + 60 fade
    }

    @Test
    void completedSequenceStaysComplete() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotExitSequence exit = new S3kSlotExitSequence(controller);

        for (int i = 0; i < 200; i++) exit.tick();
        assertTrue(exit.isComplete());

        // Additional ticks don't change state
        exit.tick();
        assertTrue(exit.isComplete());
    }

    @Test
    void fadeProgressAdvancesDuringFade() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotExitSequence exit = new S3kSlotExitSequence(controller);

        assertEquals(0f, exit.fadeProgress());

        // Skip to fade phase
        for (int i = 0; i < 95; i++) exit.tick();
        assertEquals(0f, exit.fadeProgress(), 0.02f);

        // Half through fade
        for (int i = 0; i < 30; i++) exit.tick();
        assertEquals(0.5f, exit.fadeProgress(), 0.02f);
    }

    @Test
    void scalarIndexKeepsAcceleratingDuringFade() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotExitSequence exit = new S3kSlotExitSequence(controller);

        for (int i = 0; i < 95; i++) {
            exit.tick();
        }
        assertTrue(exit.isFading());
        assertEquals(0x1800, controller.scalarIndex());

        exit.tick();
        assertEquals(0x1840, controller.scalarIndex());

        exit.tick();
        assertEquals(0x1880, controller.scalarIndex());
    }
}


