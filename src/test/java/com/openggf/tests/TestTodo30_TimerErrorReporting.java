package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameServices;
import com.openggf.timer.AbstractTimer;
import com.openggf.timer.TimerManager;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TODO #30 coverage: Timer error reporting.
 *
 * <p>Verifies that when a timer's {@code perform()} returns false, the error is
 * logged at WARNING level with a meaningful message including the timer code
 * and class name. The timer is removed regardless of success/failure.
 */
public class TestTodo30_TimerErrorReporting {
    private TimerManager manager;

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        manager = GameServices.timers();
        manager.resetState();
    }

    @AfterEach
    public void tearDown() {
        manager.resetState();
        SessionManager.clear();
    }

    /** A timer that always fails (returns false from perform()). */
    private static class FailingTimer extends AbstractTimer {
        boolean performCalled = false;

        FailingTimer(String code) {
            super(code, 1); // expires after 1 tick
        }

        @Override
        public boolean perform() {
            performCalled = true;
            return false; // simulate failure
        }
    }

    /** A timer that always succeeds. */
    private static class SucceedingTimer extends AbstractTimer {
        boolean performCalled = false;

        SucceedingTimer(String code) {
            super(code, 1);
        }

        @Override
        public boolean perform() {
            performCalled = true;
            return true;
        }
    }

    @Test
    public void testFailedTimerIsRemovedAfterPerform() {
        FailingTimer timer = new FailingTimer("FAIL_TEST");
        manager.registerTimer(timer);
        assertNotNull(manager.getTimerForCode("FAIL_TEST"), "Timer should be registered");

        // Update to trigger the timer (1 tick -> expires)
        manager.update();

        assertTrue(timer.performCalled, "perform() should have been called");
        assertNull(manager.getTimerForCode("FAIL_TEST"), "Failed timer should still be removed");
    }

    @Test
    public void testSuccessfulTimerIsRemoved() {
        SucceedingTimer timer = new SucceedingTimer("SUCCESS_TEST");
        manager.registerTimer(timer);
        manager.update();

        assertTrue(timer.performCalled, "perform() should have been called");
        assertNull(manager.getTimerForCode("SUCCESS_TEST"), "Successful timer should be removed");
    }

    @Test
    public void testFailedTimerLogsErrorMessage() {
        // Capture log output to verify the error message content
        Logger logger = Logger.getLogger(TimerManager.class.getName());
        Level originalLevel = logger.getLevel();

        final StringBuilder logCapture = new StringBuilder();
        Handler testHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logCapture.append(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        testHandler.setLevel(Level.ALL);
        logger.addHandler(testHandler);
        logger.setLevel(Level.ALL);

        try {
            FailingTimer timer = new FailingTimer("LOG_TEST_CODE");
            manager.registerTimer(timer);
            manager.update();

            String logged = logCapture.toString();
            // The implementation logs at WARNING level with:
            // "Timer failed: " + class.getSimpleName() + " code=" + timer.getCode()
            assertTrue(logged.contains("failed"), "Log should contain 'failed'");
            assertTrue(logged.contains("LOG_TEST_CODE"), "Log should contain the timer code");
            assertTrue(logged.contains("FailingTimer"), "Log should contain the timer class");
        } finally {
            logger.removeHandler(testHandler);
            logger.setLevel(originalLevel);
        }
    }

    @Test
    public void testMultipleTimersIndependentFailure() {
        // If one timer fails, other timers should still execute normally.
        FailingTimer failing = new FailingTimer("MULTI_FAIL");
        SucceedingTimer succeeding = new SucceedingTimer("MULTI_OK");
        failing.setTicks(1);
        succeeding.setTicks(1);

        manager.registerTimer(failing);
        manager.registerTimer(succeeding);

        manager.update();

        assertTrue(failing.performCalled, "Failing timer perform() called");
        assertTrue(succeeding.performCalled, "Succeeding timer perform() called");
        assertNull(manager.getTimerForCode("MULTI_FAIL"), "Failing timer removed");
        assertNull(manager.getTimerForCode("MULTI_OK"), "Succeeding timer removed");
    }
}


