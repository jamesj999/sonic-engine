package com.openggf.tests.trace.runs;

import com.openggf.trace.FrameComparison;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunBoundaryComparator;
import com.openggf.trace.replay.runs.TraceRunBoundaryComparator.ActualBoundary;
import com.openggf.trace.replay.runs.TraceRunBoundaryComparator.ExpectedBoundary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunBoundaryComparator {

    /**
     * The destination segment metadata's recorded arm-frame position — the
     * frame the transition's {@code mode_change_bk2_frame} names, which no
     * recorder writes a row for. Distinct from every {@code frame(...)} row 0
     * below so the two expectation sources can never be confused.
     */
    private static final int ARM_X = 4736;
    private static final int ARM_Y = 876;

    /**
     * One destination row consumed: the engine sampled on row 0's frame, so
     * row 0 is the co-temporal recorded sample.
     */
    private static final int ROW_ZERO_RUN = 1;

    /** No destination row consumed: the engine is still on the arm frame. */
    private static final int ARM_FRAME = 0;

    @Test
    void positionalReturnComparesFrameZeroCentreRingsAndRecordedEmeraldProgression() {
        ExpectedBoundary expected = expected(
                transition("starpost_special", 320, 170, null, 42, 0, null),
                stageExit(0, 1),
                level(0, 1), level(0, 1), frame(320, 173), 0);
        ActualBoundary actual = new ActualBoundary(
                320, 173, 0, 0, 0, 0, 99, false, ROW_ZERO_RUN);

        FrameComparison comparison = TraceRunBoundaryComparator.compare(
                9701, expected, actual);

        assertFalse(comparison.hasError(), comparison.divergentFields()::toString);
        assertTrue(comparison.fields().containsKey("run_boundary.position.x"));
        assertTrue(comparison.fields().containsKey("run_boundary.position.y"));
        assertTrue(comparison.fields().containsKey("run_boundary.rings"));
        assertTrue(comparison.fields().containsKey("run_boundary.emeralds.recorded_progression"));
        assertFalse(comparison.fields().containsKey("run_boundary.emeralds.live"));
    }

    @Test
    void checkpointReturnRequiresCheckpointAndFrameZeroPosition() {
        ExpectedBoundary expected = expected(
                transition("starpost_bonus", 400, 220, 3, 12, null, 4),
                stageExit(12, 4),
                level(5, 1), level(5, 1), frame(404, 224), 5);
        ActualBoundary actual = new ActualBoundary(
                404, 224, 2, 5, 0, 99, 4, true, ROW_ZERO_RUN);

        FrameComparison comparison = TraceRunBoundaryComparator.compare(
                200, expected, actual);

        assertTrue(comparison.hasErrorInField("run_boundary.checkpoint"));
        assertFalse(comparison.hasErrorInField("run_boundary.position.x"));
        assertFalse(comparison.hasErrorInField("run_boundary.position.y"));
        assertTrue(comparison.hasErrorInField("run_boundary.rings"));
    }

    @Test
    void nextActReturnComparesAdvanceIdentityAndLiveEmeralds() {
        ExpectedBoundary expected = expected(
                transition("giant_ring", null, null, null, 50, 2, null),
                stageExit(55, 3),
                level(1, 1), level(1, 2), frame(999, 999), 7);
        ActualBoundary actual = new ActualBoundary(
                1, 2, 0, 7, 1, 0, 3, true, ROW_ZERO_RUN);

        FrameComparison comparison = TraceRunBoundaryComparator.compare(
                350, expected, actual);

        assertFalse(comparison.hasError(), comparison.divergentFields()::toString);
        assertTrue(comparison.fields().containsKey("run_boundary.next_act.manifest_advance"));
        assertTrue(comparison.fields().containsKey("run_boundary.next_act.zone"));
        assertTrue(comparison.fields().containsKey("run_boundary.next_act.act"));
        assertTrue(comparison.fields().containsKey("run_boundary.emeralds.live"));
        assertFalse(comparison.fields().containsKey("run_boundary.position.x"));
        assertFalse(comparison.fields().containsKey("run_boundary.rings"));
    }

    @Test
    void giantRingWithSavedPositionUsesS3kPositionPolicyNotNextActPolicy() {
        ExpectedBoundary expected = expected(
                transition("giant_ring", 800, 300, null, 20, 6, null),
                stageExit(33, 7),
                level(2, 1), level(2, 1), frame(801, 302), 2);
        ActualBoundary actual = new ActualBoundary(
                801, 302, 0, 99, 99, 0, 7, true, ROW_ZERO_RUN);

        FrameComparison comparison = TraceRunBoundaryComparator.compare(
                500, expected, actual);

        assertTrue(comparison.hasErrorInField("run_boundary.rings"));
        assertFalse(comparison.hasErrorInField("run_boundary.position.x"));
        assertFalse(comparison.hasErrorInField("run_boundary.position.y"));
        assertTrue(comparison.fields().containsKey("run_boundary.position.x"));
        assertFalse(comparison.fields().containsKey("run_boundary.next_act.zone"));
    }

    @Test
    void uncomparedInteriorReportsImpossibleRecordedEmeraldDelta() {
        ExpectedBoundary expected = expected(
                transition("starpost_special", 320, 170, null, 42, 2, null),
                stageExit(0, 5),
                level(0, 1), level(0, 1), frame(320, 173), 0);
        ActualBoundary actual = new ActualBoundary(
                320, 173, 0, 0, 0, 0, 5, false, ROW_ZERO_RUN);

        FrameComparison comparison = TraceRunBoundaryComparator.compare(
                9701, expected, actual);

        assertTrue(comparison.hasErrorInField(
                "run_boundary.emeralds.recorded_progression"));
    }

    @Test
    void boundarySampledOnTheArmFrameComparesTheRecordedArmFramePosition() {
        // Same expectation record; only the number of destination rows the
        // engine has run differs. On the arm frame the recorded row 0 has not
        // happened yet, so comparing against it is a phase error, not a
        // physics one.
        ExpectedBoundary expected = expected(
                transition("giant_ring", 800, 300, null, 20, 6, null),
                stageExit(33, 7),
                level(2, 1), level(2, 1), frame(801, 302), 2);
        ActualBoundary onArmFrame = new ActualBoundary(
                ARM_X, ARM_Y, 0, 99, 99, 33, 7, true, ARM_FRAME);
        ActualBoundary onRowZero = new ActualBoundary(
                801, 302, 0, 99, 99, 33, 7, true, ROW_ZERO_RUN);

        assertFalse(TraceRunBoundaryComparator.compare(500, expected, onArmFrame)
                .hasErrorInField("run_boundary.position.x"));
        assertFalse(TraceRunBoundaryComparator.compare(500, expected, onArmFrame)
                .hasErrorInField("run_boundary.position.y"));
        assertFalse(TraceRunBoundaryComparator.compare(500, expected, onRowZero)
                .hasErrorInField("run_boundary.position.x"));
        // The two samples are genuinely different values, so each still fails
        // against the other's frame.
        assertTrue(TraceRunBoundaryComparator.compare(500, expected,
                new ActualBoundary(ARM_X, ARM_Y, 0, 99, 99, 33, 7, true,
                        ROW_ZERO_RUN))
                .hasErrorInField("run_boundary.position.x"));
        assertTrue(TraceRunBoundaryComparator.compare(500, expected,
                new ActualBoundary(801, 302, 0, 99, 99, 33, 7, true, ARM_FRAME))
                .hasErrorInField("run_boundary.position.x"));
    }

    private static ExpectedBoundary expected(
            TraceRunManifest.Transition entry,
            TraceRunManifest.Transition exit,
            TraceRunManifest.Segment preEntry,
            TraceRunManifest.Segment returned,
            TraceFrame returnFrameZero,
            int resolvedReturnZone) {
        return new ExpectedBoundary(entry, exit, preEntry, returned,
                returnFrameZero, resolvedReturnZone, ARM_X, ARM_Y);
    }

    private static TraceRunManifest.Transition transition(
            String kind, Integer savedX, Integer savedY,
            Integer checkpoint, Integer ringsBefore,
            Integer emeraldsBefore, Integer emeraldsAfter) {
        return new TraceRunManifest.Transition(
                0, 1, kind, 100, 1, savedX, savedY, checkpoint,
                ringsBefore, null, emeraldsBefore, emeraldsAfter);
    }

    private static TraceRunManifest.Transition stageExit(
            Integer ringsAfter, Integer emeraldsAfter) {
        return new TraceRunManifest.Transition(
                1, 2, "stage_exit", 200, null, null, null, null,
                null, ringsAfter, null, emeraldsAfter);
    }

    private static TraceRunManifest.Segment level(int zone, int act) {
        return new TraceRunManifest.Segment(
                "level-" + zone + "-" + act, "level", "gameplay_unlock",
                0, 1, zone, act, null, null);
    }

    private static TraceFrame frame(int x, int y) {
        return TraceFrame.of(0, 0, (short) x, (short) y,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
    }
}
