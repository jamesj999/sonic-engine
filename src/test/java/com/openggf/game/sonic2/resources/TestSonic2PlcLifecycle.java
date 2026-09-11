package com.openggf.game.sonic2.resources;

import com.openggf.data.Rom;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.level.resources.NemesisPlcServiceQueue;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class TestSonic2PlcLifecycle {
    private final NemesisPlcServiceQueue queue = mock(NemesisPlcServiceQueue.class);
    private final Sonic2PlcService service = new Sonic2PlcService(mock(Rom.class), queue);

    @Test
    void mapsEveryPhaseToReviewedBudget() {
        EnumSet<PlcLifecyclePhase> six = EnumSet.of(
                PlcLifecyclePhase.TITLE_SCREEN, PlcLifecyclePhase.LEVEL_SELECT,
                PlcLifecyclePhase.LEVEL_TITLE_CARD, PlcLifecyclePhase.PALETTE_FADE,
                PlcLifecyclePhase.TWO_PLAYER_RESULTS);
        EnumSet<PlcLifecyclePhase> three = EnumSet.of(
                PlcLifecyclePhase.ORDINARY_LEVEL, PlcLifecyclePhase.SPECIAL_STAGE,
                PlcLifecyclePhase.SPECIAL_STAGE_RESULTS, PlcLifecyclePhase.NORMAL_PAUSE);
        for (PlcLifecyclePhase phase : PlcLifecyclePhase.values()) {
            reset(queue);
            service.serviceVBlank(phase);
            if (six.contains(phase)) verify(queue).servicePatterns(6);
            else if (three.contains(phase)) verify(queue).servicePatterns(3);
            else verify(queue, never()).servicePatterns(org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Test
    void mapsPreparationIndependentlyFromService() {
        EnumSet<PlcLifecyclePhase> prepared = EnumSet.of(
                PlcLifecyclePhase.TITLE_SCREEN, PlcLifecyclePhase.LEVEL_TITLE_CARD,
                PlcLifecyclePhase.ORDINARY_LEVEL, PlcLifecyclePhase.PALETTE_FADE,
                PlcLifecyclePhase.SPECIAL_STAGE, PlcLifecyclePhase.SPECIAL_STAGE_RESULTS,
                PlcLifecyclePhase.TWO_PLAYER_RESULTS);
        for (PlcLifecyclePhase phase : PlcLifecyclePhase.values()) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    prepared.contains(phase), service.hasPreparationBoundary(phase), phase.name());
        }
    }
}
