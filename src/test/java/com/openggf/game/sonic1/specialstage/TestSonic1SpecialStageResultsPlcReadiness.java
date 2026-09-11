package com.openggf.game.sonic1.specialstage;

import com.openggf.game.GameServices;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_1)
class TestSonic1SpecialStageResultsPlcReadiness {
    private Sonic1PlcService plc;
    private Sonic1SpecialStageResultsScreen results;

    @BeforeEach
    void setUp() {
        GameServices.module().createGame(TestEnvironment.currentRom());
        plc = GameServices.module().getGameService(Sonic1PlcService.class);
        results = new Sonic1SpecialStageResultsScreen(5, false, 0, 0);
    }

    @Test
    void specialResultsWaitForTheWholeQueueAndReleaseOnTheFirstEmptyFrame() throws Exception {
        plc.append(0);
        plc.append(27);
        results.update(1, null);
        assertEquals(0, totalFrames());

        drain();
        results.update(2, null);
        assertEquals(1, totalFrames());
    }

    @Test
    void unrelatedPlcWorkAfterRoutineZeroDoesNotFreezeTheSpecialResultsCard() throws Exception {
        results.update(1, null);
        assertEquals(1, totalFrames());

        plc.append(27);
        results.update(2, null);

        assertEquals(2, totalFrames(), "SSR routines after SSR_ChkPLC continue while later work is queued");
    }

    @Test
    void completedSpecialResultsHoldModeExitUntilTheFinalFifoPollIsEmpty() throws Exception {
        markObjectComplete();
        plc.append(27);

        assertEquals(false, results.isComplete(), "the special-stage mode must wait for its final PLC poll");

        drain();

        assertEquals(true, results.isComplete(), "the first empty frame releases the completed special-stage mode");
    }

    private int totalFrames() throws Exception {
        Field field = Sonic1SpecialStageResultsScreen.class.getDeclaredField("totalFrames");
        field.setAccessible(true);
        return field.getInt(results);
    }

    private void markObjectComplete() throws Exception {
        Field field = Sonic1SpecialStageResultsScreen.class.getDeclaredField("complete");
        field.setAccessible(true);
        field.setBoolean(results, true);
    }

    private void drain() {
        while (plc.isBusy()) {
            plc.prepare();
            plc.serviceFastVBlank();
        }
    }
}
