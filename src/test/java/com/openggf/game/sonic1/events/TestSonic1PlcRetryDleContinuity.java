package com.openggf.game.sonic1.events;

import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@RequiresRom(SonicGame.SONIC_1)
@ExtendWith(SingletonResetExtension.class)
class TestSonic1PlcRetryDleContinuity {
    private Sonic1SBZEvents events;
    private Sonic1PlcService plcService;

    @BeforeEach
    void setUp() throws Exception {
        GameServices.module().createGame(TestEnvironment.currentRom());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        events = new Sonic1SBZEvents();
        events.init();
        plcService = GameServices.module().getGameService(Sonic1PlcService.class);

        for (int i = 0; i < 15; i++) {
            plcService.append(2);
        }
        assertEquals(15, plcService.capture().queuedEntries().size(),
                "fixture must fill every safe descriptor slot");

        GameServices.camera().setX((short) 0x2148);
        events.updateFZ();
        assertEquals(2, events.getEventRoutine(),
                "the original one-shot advances before its fail-closed publication");
        assertEquals(31, events.getPendingPlcIdForRewind());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void failedRetryRetainsPendingCueWithoutSuppressingCurrentDleRoutine() {
        GameServices.camera().setX((short) 0x2200);

        events.updateFZ();

        assertEquals((short) 0x2200, GameServices.camera().getMinX(),
                "retry bookkeeping must not suppress FZ routine 2's boundary lock");
        assertEquals(2, events.getEventRoutine());
        assertEquals(31, events.getPendingPlcIdForRewind());
        assertEquals(15, plcService.capture().queuedEntries().size());
    }

    @Test
    void successfulRetryClearsPendingCueAndRunsCurrentDleRoutineExactlyOnce() {
        plcService.clearQueued();
        GameServices.camera().setX((short) 0x2200);

        events.updateFZ();

        assertEquals((short) 0x2200, GameServices.camera().getMinX());
        assertEquals(2, events.getEventRoutine());
        assertEquals(-1, events.getPendingPlcIdForRewind());
        assertEquals(5, plcService.capture().queuedEntries().size(),
                "the deferred Final Zone PLC must be published exactly once");

        GameServices.camera().setX((short) 0x2210);
        events.updateFZ();
        assertEquals((short) 0x2210, GameServices.camera().getMinX());
        assertEquals(5, plcService.capture().queuedEntries().size(),
                "the successful retry must not replay the original one-shot producer");
    }
}
