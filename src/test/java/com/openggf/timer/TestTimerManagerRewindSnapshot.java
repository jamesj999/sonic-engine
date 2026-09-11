package com.openggf.timer;

import com.openggf.game.rewind.snapshot.TimerManagerSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestTimerManagerRewindSnapshot {
    private TimerManager timerManager;

    @BeforeEach
    void setUp() {
        timerManager = new TimerManager();
    }

    @Test
    void testTimerManagerSnapshotRoundTrip() {
        // Create and register test timers
        SimpleTestTimer timer1 = new SimpleTestTimer("timer1", 100);
        SimpleTestTimer timer2 = new SimpleTestTimer("timer2", 50);

        timerManager.registerTimer(timer1);
        timerManager.registerTimer(timer2);

        // Capture snapshot
        TimerManagerSnapshot snapshot = timerManager.capture();

        // Mutate state
        timerManager.removeTimerForCode("timer1");
        timer2.setTicks(25);

        // Restore from snapshot
        timerManager.restore(snapshot);

        // Verify timer states are restored
        Timer restored1 = timerManager.getTimerForCode("timer1");
        Timer restored2 = timerManager.getTimerForCode("timer2");

        assertNotNull(restored1);
        assertNotNull(restored2);
        assertEquals(100, restored1.getTicks());
        assertEquals(50, restored2.getTicks());
    }

    @Test
    void testTimerManagerSnapshotKey() {
        assertEquals("timermanager", timerManager.key());
    }

    @Test
    void testEmptyTimerManagerSnapshot() {
        // No timers registered
        TimerManagerSnapshot snapshot = timerManager.capture();
        assertTrue(snapshot.timerStates().isEmpty());

        // Add a timer
        timerManager.registerTimer(new SimpleTestTimer("test", 100));

        // Restore empty snapshot
        timerManager.restore(snapshot);

        // Should be empty again
        assertNull(timerManager.getTimerForCode("test"));
    }

    /**
     * Simple test timer for use in unit tests.
     */
    static class SimpleTestTimer implements Timer {
        private String code;
        private int ticks;

        SimpleTestTimer(String code, int ticks) {
            this.code = code;
            this.ticks = ticks;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public void setCode(String code) {
            this.code = code;
        }

        @Override
        public int getTicks() {
            return ticks;
        }

        @Override
        public void setTicks(int ticks) {
            this.ticks = ticks;
        }

        @Override
        public void decrementTick() {
            ticks--;
        }

        @Override
        public boolean perform() {
            return true;
        }
    }
}
