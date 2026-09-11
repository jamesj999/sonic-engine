package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.rewind.LiveRewindManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRewindReleaseRetryCoordinator {

    @AfterEach
    void clearActiveTraceHost() throws Exception {
        setActiveTrace(null);
    }

    @Test
    void traceRetryPrecedesLiveRetryAndEachConsumedFrameUpdatesInputOnce()
            throws Exception {
        TraceSessionLauncher trace = mock(TraceSessionLauncher.class);
        LiveRewindManager live = mock(LiveRewindManager.class);
        CountingInputHandler input = new CountingInputHandler();
        when(trace.retryPendingTeardown())
                .thenReturn(true, true, false, false);
        when(live.retryPendingRelease())
                .thenReturn(true, false);
        setActiveTrace(trace);

        assertTrue(RewindReleaseRetryCoordinator.consumePendingFrame(
                live, input));
        assertSame(trace, TraceSessionLauncher.active(),
                "a failed consumed retry must retain the all-mode Trace host");
        verify(trace).retryPendingTeardown();
        verifyNoInteractions(live);
        assertEquals(1, input.updateCalls);

        assertTrue(RewindReleaseRetryCoordinator.consumePendingFrame(
                live, input));
        assertSame(trace, TraceSessionLauncher.active(),
                "the retained host must continue owning later mode frames");
        verify(trace, times(2)).retryPendingTeardown();
        verifyNoInteractions(live);
        assertEquals(2, input.updateCalls);

        assertTrue(RewindReleaseRetryCoordinator.consumePendingFrame(
                live, input));
        verify(trace, times(3)).retryPendingTeardown();
        verify(live).retryPendingRelease();
        assertEquals(3, input.updateCalls,
                "falling through to the live host still consumes one input update");

        assertFalse(RewindReleaseRetryCoordinator.consumePendingFrame(
                live, input));
        verify(trace, times(4)).retryPendingTeardown();
        verify(live, times(2)).retryPendingRelease();
        assertEquals(3, input.updateCalls,
                "an unconsumed poll must not update input");
    }

    private static void setActiveTrace(TraceSessionLauncher launcher)
            throws Exception {
        Field active = TraceSessionLauncher.class.getDeclaredField(
                "activeSession");
        active.setAccessible(true);
        active.set(null, launcher);
    }

    private static final class CountingInputHandler extends InputHandler {
        private int updateCalls;

        @Override
        public void update() {
            updateCalls++;
        }
    }
}
