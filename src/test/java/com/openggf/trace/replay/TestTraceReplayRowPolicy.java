package com.openggf.trace.replay;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceReplayRowPolicy {

    @Test
    void resolvesCurrentValidationAndApplicationForS1Rows() {
        TraceData fullTrace = TraceFixtures.trace(
                TraceFixtures.metadata("s1", 0, 1),
                List.of(
                        TraceFrame.executionTestFrame(100, 10, 20, 0),
                        TraceFrame.executionTestFrame(101, 11, 21, 0)));
        TraceReplayRowPolicy full =
                TraceReplayRowPolicy.resolve(fullTrace, 1, 41);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME, full.phase());
        assertEquals(41, full.validationBk2Index());
        assertEquals(41, full.appliedBk2Index());
        assertEquals(40, full.appliedPredecessorBk2Index());
        assertEquals(0, full.appliedInputOffset());
        assertTrue(full.productionPublicationClaim());
        assertEquals(0, full.suppressedClosureCount());

        TraceData lagTrace = TraceFixtures.trace(
                TraceFixtures.metadata("s1", 0, 1),
                List.of(
                        TraceFrame.executionTestFrame(100, 10, 20, 0),
                        TraceFrame.executionTestFrame(101, 11, 20, 0)));
        TraceReplayRowPolicy lag =
                TraceReplayRowPolicy.resolve(lagTrace, 1, 42);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY, lag.phase());
        assertEquals(42, lag.appliedBk2Index());
        assertEquals(1, lag.suppressedClosureCount());
        assertTrue(lag.productionPublicationClaim());
    }

    @Test
    void previousInputPolicyAppliesOnlyToGameplayRunningPrefixRows()
            throws Exception {
        TraceData prefix = TraceData.load(Path.of(
                "src/test/resources/traces/s3k/aiz1_to_hcz_fullrun"));

        TraceReplayRowPolicy full =
                TraceReplayRowPolicy.resolve(prefix, 290, 1_100);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME, full.phase());
        assertEquals(1_100, full.validationBk2Index());
        assertEquals(1_099, full.appliedBk2Index());
        assertEquals(1_098, full.appliedPredecessorBk2Index());
        assertEquals(-1, full.appliedInputOffset());

        TraceReplayRowPolicy suppressed = TraceReplayRowPolicy.fromResolvedPhase(
                7, 77, TraceExecutionPhase.VBLANK_ONLY, 1_100,
                true, true);
        assertEquals(1_100, suppressed.appliedBk2Index());
        assertEquals(0, suppressed.appliedInputOffset());
    }

    @Test
    void previousInputPolicyFallsBackToCurrentAtMovieStart() {
        TraceReplayRowPolicy policy = TraceReplayRowPolicy.fromResolvedPhase(
                0, 0, TraceExecutionPhase.FULL_LEVEL_FRAME, 0,
                true, true);

        assertEquals(0, policy.validationBk2Index());
        assertEquals(0, policy.appliedBk2Index());
        assertEquals(0, policy.appliedInputOffset());
        assertEquals(-1, policy.appliedPredecessorBk2Index());
    }

    @Test
    void phaseProjectionAssignsAtMostOneSuppressedClosureAndNoUnexpectedActions() {
        record Expected(
                TraceExecutionPhase phase,
                int closureCount,
                boolean publishes,
                boolean sidekickHold,
                boolean postRowPrefix) {
        }
        List<Expected> cases = List.of(
                new Expected(TraceExecutionPhase.ADVANCE_ONLY, 0, false, false, false),
                new Expected(TraceExecutionPhase.VBLANK_ONLY, 1, true, false, false),
                new Expected(TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY,
                        1, true, false, true),
                new Expected(TraceExecutionPhase.FULL_LEVEL_FRAME,
                        0, true, false, false),
                new Expected(TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD,
                        0, true, true, false));

        for (Expected expected : cases) {
            TraceReplayRowPolicy policy = TraceReplayRowPolicy.fromResolvedPhase(
                    3, 103, expected.phase(), 55, true, true);
            assertEquals(expected.closureCount(), policy.suppressedClosureCount(),
                    expected.phase().name());
            assertEquals(expected.publishes(), policy.productionPublicationClaim(),
                    expected.phase().name());
            assertEquals(expected.sidekickHold(), policy.holdFirstSidekickAnimation(),
                    expected.phase().name());
            assertEquals(expected.postRowPrefix(), policy.postRowPlayablePrefix(),
                    expected.phase().name());
            assertTrue(policy.suppressedClosureCount() >= 0
                    && policy.suppressedClosureCount() <= 1);
        }
    }

    @Test
    void counterPlateauSeparatesPublicationClaimFromObservedVblankDelta() {
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadata("s2", 0, 1),
                List.of(
                        TraceFrame.executionTestFrame(100, 10, 20, 0),
                        TraceFrame.executionTestFrame(101, 10, 20, 0)));

        TraceReplayRowPolicy policy = TraceReplayRowPolicy.resolve(trace, 1, 9);

        assertFalse(policy.observedVblankCounterAdvance());
        assertTrue(policy.productionPublicationClaim(),
                "the PLC lifecycle marker still closes a represented starved row");
        assertEquals(1, policy.suppressedClosureCount());
    }
}
