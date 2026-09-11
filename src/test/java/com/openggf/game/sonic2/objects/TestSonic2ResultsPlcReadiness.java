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
class TestSonic2ResultsPlcReadiness {
    private Sonic2PlcService plc;
    private ResultsScreenObjectInstance results;

    @BeforeEach
    void setUp() {
        GameServices.module().createGame(TestEnvironment.currentRom());
        plc = GameServices.module().getGameService(Sonic2PlcService.class);
        results = new ResultsScreenObjectInstance(30, 7, 1, false);
        results.setServices(new TestObjectServices() {
            @Override
            public <T> T gameService(Class<T> type) {
                return GameServices.module().getGameService(type);
            }
        }.withGameModule(GameServices.module()));
    }

    @Test
    void resultsAdvanceOnTheFirstEmptyFrameAfterTheWholeQueueDrains() throws Exception {
        plc.append(38);
        results.update(1, null);
        assertEquals(0, stateTimer());

        drain();
        results.update(2, null);
        assertEquals(1, stateTimer());
    }

    @Test
    void unrelatedPlcWorkAfterRoutineZeroDoesNotFreezeTheResultsCard() throws Exception {
        results.update(1, null);
        assertEquals(1, stateTimer());

        plc.append(38);
        results.update(2, null);

        assertEquals(2, stateTimer(), "Obj3A routines after init do not re-poll the PLC FIFO");
    }

    @Test
    void postTallyWaitUsesTheNativeAccumulatedBonusThreshold() throws Exception {
        Field totalBonus = ResultsScreenObjectInstance.class.getDeclaredField("totalBonus");
        totalBonus.setAccessible(true);
        var waitDuration = ResultsScreenObjectInstance.class
                .getDeclaredMethod("getWaitDuration");
        waitDuration.setAccessible(true);

        totalBonus.setInt(results, 999);
        assertEquals(180, waitDuration.invoke(results));
        totalBonus.setInt(results, 1000);
        assertEquals(300, waitDuration.invoke(results));
    }

    private int stateTimer() throws Exception {
        Field field = com.openggf.level.objects.AbstractResultsScreen.class.getDeclaredField("stateTimer");
        field.setAccessible(true);
        return field.getInt(results);
    }

    private void drain() {
        while (plc.isBusy()) {
            plc.prepare();
            plc.serviceLevelVBlank();
        }
    }
}
