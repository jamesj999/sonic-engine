package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.timer.AbstractTimer;
import com.openggf.timer.TimerManager;

import static org.junit.jupiter.api.Assertions.*;

public class TestTimerManager {

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }
    private static class DummyTimer extends AbstractTimer {
        boolean performed = false;
        DummyTimer(String code, int ticks) { super(code, ticks); }
        @Override
        public boolean perform() { performed = true; return true; }
    }

    @Test
    public void testTimerLifecycle() {
        TimerManager manager = GameServices.timers();
        manager.removeTimerForCode("TEST");
        DummyTimer timer = new DummyTimer("TEST", 2);
        manager.registerTimer(timer);
        manager.update();
        assertEquals(1, timer.getTicks());
        assertNotNull(manager.getTimerForCode("TEST"));
        manager.update();
        assertTrue(timer.performed);
        assertNull(manager.getTimerForCode("TEST"));
    }
}


