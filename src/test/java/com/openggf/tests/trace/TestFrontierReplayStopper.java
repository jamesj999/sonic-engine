package com.openggf.tests.trace;

import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.TraceVerificationScope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFrontierReplayStopper {
    @Test
    void reportsEarlyStopOnlyAfterContextBoundaryWasActuallyReached() {
        FrontierReplayStopper stopper = FrontierReplayStopper.enabled(2);
        stopper.observe(errorFrame(10));

        assertFalse(stopper.shouldStopAfterFrame(11));
        assertFalse(stopper.stoppedEarly());
        assertTrue(stopper.shouldStopAfterFrame(12));
        assertTrue(stopper.stoppedEarly());
    }


    @Test
    void disabledStopperNeverStopsAfterErrors() {
        FrontierReplayStopper stopper = FrontierReplayStopper.disabled(2);

        stopper.observe(errorFrame(10));

        assertFalse(stopper.shouldStopAfterFrame(12));
        assertFalse(stopper.shouldStopAfterFrame(200));
    }

    @Test
    void enabledStopperStopsAfterFirstErrorPlusRadius() {
        FrontierReplayStopper stopper = FrontierReplayStopper.enabled(2);

        stopper.observe(matchFrame(9));
        stopper.observe(errorFrame(10));

        assertFalse(stopper.shouldStopAfterFrame(11));
        assertTrue(stopper.shouldStopAfterFrame(12));
        assertTrue(stopper.shouldStopAfterFrame(50));
    }

    @Test
    void laterErrorsDoNotMoveTheFrontierStopFrame() {
        FrontierReplayStopper stopper = FrontierReplayStopper.enabled(2);

        stopper.observe(errorFrame(10));
        stopper.observe(errorFrame(20));

        assertTrue(stopper.shouldStopAfterFrame(12));
    }

    @Test
    void physicsFrontierIgnoresEarlierAnimationError() {
        FrontierReplayStopper stopper =
                FrontierReplayStopper.enabled(2, TraceVerificationScope.PHYSICS);

        stopper.observe(animationErrorFrame(3));
        assertFalse(stopper.shouldStopAfterFrame(20));

        stopper.observe(errorFrame(10));
        assertFalse(stopper.shouldStopAfterFrame(11));
        assertTrue(stopper.shouldStopAfterFrame(12));
    }

    private static FrameComparison matchFrame(int frame) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put("x", new FieldComparison("x", "0x0001", "0x0001", Severity.MATCH, 0));
        return new FrameComparison(frame, fields);
    }

    private static FrameComparison errorFrame(int frame) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put("x", new FieldComparison("x", "0x0001", "0x0002", Severity.ERROR, 1));
        return new FrameComparison(frame, fields);
    }

    private static FrameComparison animationErrorFrame(int frame) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put("player_animation_id", FieldComparison.animation(
                "player_animation_id", "0x01", "0x02", Severity.ERROR, 1));
        return new FrameComparison(frame, fields);
    }
}
