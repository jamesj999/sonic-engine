package com.openggf.game.sonic2.objects;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2SpecialStageResultsPlcReadiness {
    private Sonic2PlcService plc;
    private SpecialStageResultsScreenObjectInstance results;

    @BeforeEach
    void setUp() {
        GameServices.module().createGame(TestEnvironment.currentRom());
        plc = GameServices.module().getGameService(Sonic2PlcService.class);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public <T> T gameService(Class<T> type) {
                return GameServices.module().getGameService(type);
            }
        }.withGameModule(GameServices.module());
        results = new SpecialStageResultsScreenObjectInstance(5, false, 0, 0, services);
    }

    @Test
    void specialResultsWaitForTheWholeQueueAndReleaseOnTheFirstEmptyFrame() throws Exception {
        plc.append(0);
        results.update(1, null);
        assertEquals(0, totalFrames());

        drain();
        results.update(2, null);
        assertEquals(1, totalFrames());
    }

    @Test
    void unrelatedPlcWorkAfterInitDoesNotFreezeTheSpecialResultsCard() throws Exception {
        results.update(1, null);
        assertEquals(1, totalFrames());

        plc.append(0);
        results.update(2, null);

        assertEquals(2, totalFrames(), "Obj6F routines after init do not re-poll the PLC FIFO");
    }

    @Test
    void completedSpecialResultsHoldModeExitUntilTheFinalFifoPollIsEmpty() throws Exception {
        markObjectComplete();
        plc.append(0);

        assertEquals(false, results.isComplete(), "the special-stage mode must wait for its final PLC poll");

        drain();

        assertEquals(true, results.isComplete(), "the first empty frame releases the completed special-stage mode");
    }

    private int totalFrames() throws Exception {
        Field field = SpecialStageResultsScreenObjectInstance.class.getDeclaredField("totalFrames");
        field.setAccessible(true);
        return field.getInt(results);
    }

    private void markObjectComplete() throws Exception {
        Field field = SpecialStageResultsScreenObjectInstance.class.getDeclaredField("complete");
        field.setAccessible(true);
        field.setBoolean(results, true);
    }

    private void drain() {
        while (plc.isBusy()) {
            plc.prepare();
            plc.serviceLevelVBlank();
        }
    }
}
