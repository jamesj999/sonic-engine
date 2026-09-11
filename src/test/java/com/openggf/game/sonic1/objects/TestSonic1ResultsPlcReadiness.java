package com.openggf.game.sonic1.objects;

import com.openggf.game.GameServices;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_1)
class TestSonic1ResultsPlcReadiness {
    private Sonic1PlcService plc;
    private Sonic1ResultsScreenObjectInstance results;

    @BeforeEach
    void setUp() {
        GameServices.module().createGame(TestEnvironment.currentRom());
        plc = GameServices.module().getGameService(Sonic1PlcService.class);
        results = new Sonic1ResultsScreenObjectInstance(30, 7, 1);
        results.setServices(new TestObjectServices() {
            @Override
            public <T> T gameService(Class<T> type) {
                return GameServices.module().getGameService(type);
            }
        }.withGameModule(GameServices.module())
                .withCamera(GameServices.camera()));
    }

    @Test
    void resultsAdvanceOnTheFirstEmptyFrameNotAStaticTileCountdown() throws Exception {
        plc.append(16);
        results.update(1, null);
        assertEquals(0, stateTimer(), "results routine must poll the FIFO while work remains");

        drain();
        results.update(2, null);
        assertEquals(1, stateTimer(), "the first empty frame starts the results card");
    }

    @Test
    void unrelatedPlcWorkAfterRoutineZeroDoesNotFreezeOrHideTheCard() throws Exception {
        results.update(1, null);
        assertEquals(1, stateTimer());

        plc.append(16);
        results.update(2, null);

        assertEquals(2, stateTimer(), "later Got routines do not re-poll the PLC FIFO");
        var commands = new ArrayList<com.openggf.graphics.GLCommand>();
        results.appendRenderCommands(commands);
        assertEquals(false, commands.isEmpty(), "an initialized card stays visible during unrelated PLC work");
    }

    @Test
    void sbz2BoundaryWaitsForTheScanAfterTheRingCardReachesItsExit() throws Exception {
        Field state = com.openggf.level.objects.AbstractResultsScreen.class
                .getDeclaredField("state");
        state.setAccessible(true);
        state.setInt(results, 5);
        Field positions = Sonic1ResultsScreenObjectInstance.class
                .getDeclaredField("elemCurrentX");
        positions.setAccessible(true);
        int[] currentX = (int[]) positions.get(results);
        currentX[5] = 0x540;
        GameServices.camera().setMaxX((short) 0x1E40);

        results.update(1, null);
        assertEquals(0x1E40, GameServices.camera().getMaxX() & 0xFFFF,
                "the move that reaches got_finalX must return without starting the cutscene");

        results.update(2, null);
        assertEquals(0x1E40, GameServices.camera().getMaxX() & 0xFFFF,
                "the next scan changes routine but does not run the boundary routine");

        results.update(3, null);
        assertEquals(0x1E42, GameServices.camera().getMaxX() & 0xFFFF,
                "the following routine-$10 scan performs the first boundary increment");
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
