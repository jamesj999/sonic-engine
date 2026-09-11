package com.openggf.game.sonic1.resources;

import com.openggf.data.Rom;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.level.resources.NemesisPlcServiceQueue;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class TestSonic1PlcLifecycle {
    private final NemesisPlcServiceQueue queue = mock(NemesisPlcServiceQueue.class);
    private final Sonic1PlcService service = new Sonic1PlcService(mock(Rom.class), queue);

    @Test
    void mapsEveryPhaseToReviewedBudget() {
        EnumSet<PlcLifecyclePhase> nine = EnumSet.of(
                PlcLifecyclePhase.TITLE_SCREEN, PlcLifecyclePhase.LEVEL_SELECT,
                PlcLifecyclePhase.LEVEL_TITLE_CARD, PlcLifecyclePhase.PALETTE_FADE,
                PlcLifecyclePhase.SPECIAL_STAGE_RESULTS, PlcLifecyclePhase.CREDITS_TEXT,
                PlcLifecyclePhase.ENDING, PlcLifecyclePhase.POST_CREDITS);
        EnumSet<PlcLifecyclePhase> three = EnumSet.of(
                PlcLifecyclePhase.ORDINARY_LEVEL, PlcLifecyclePhase.CREDITS_DEMO,
                PlcLifecyclePhase.CREDITS_DEMO_FADE, PlcLifecyclePhase.NORMAL_PAUSE);
        for (PlcLifecyclePhase phase : PlcLifecyclePhase.values()) {
            reset(queue);
            service.serviceVBlank(phase);
            if (nine.contains(phase)) verify(queue).servicePatterns(9);
            else if (three.contains(phase)) verify(queue).servicePatterns(3);
            else verify(queue, never()).servicePatterns(org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Test
    void mapsPreparationIndependentlyFromService() {
        EnumSet<PlcLifecyclePhase> prepared = EnumSet.of(
                PlcLifecyclePhase.TITLE_SCREEN, PlcLifecyclePhase.LEVEL_SELECT,
                PlcLifecyclePhase.LEVEL_TITLE_CARD, PlcLifecyclePhase.ORDINARY_LEVEL,
                PlcLifecyclePhase.PALETTE_FADE, PlcLifecyclePhase.SPECIAL_STAGE_RESULTS,
                PlcLifecyclePhase.CREDITS_TEXT, PlcLifecyclePhase.CREDITS_DEMO);
        for (PlcLifecyclePhase phase : PlcLifecyclePhase.values()) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    prepared.contains(phase), service.hasPreparationBoundary(phase), phase.name());
        }
    }

}
