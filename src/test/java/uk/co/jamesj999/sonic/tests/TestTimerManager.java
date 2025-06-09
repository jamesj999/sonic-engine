package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.timer.AbstractTimer;
import uk.co.jamesj999.sonic.timer.Timer;
import uk.co.jamesj999.sonic.timer.TimerManager;

import static org.junit.Assert.*;

public class TestTimerManager {
    private static class DummyTimer extends AbstractTimer {
        boolean performed = false;
        DummyTimer(String code, int ticks) { super(code, ticks); }
        @Override
        public boolean perform() { performed = true; return true; }
    }

    @Test
    public void testTimerLifecycle() {
        TimerManager manager = TimerManager.getInstance();
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
