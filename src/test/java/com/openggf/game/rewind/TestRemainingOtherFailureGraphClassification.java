package com.openggf.game.rewind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.openggf.graphics.GraphicsManager;

class TestRemainingOtherFailureGraphClassification {
    private static final String GUMBALL_SPRING =
            "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$GumballSpringChild";
    private static final String ICZ_SEGMENT =
            "com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$Segment";

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void formerGraphOnlyRowsAreReportedAsGraphCovered() {
        Map<String, String> expected = Map.of(
                GUMBALL_SPRING, "TestS3kBadnikChildGraphRewind",
                ICZ_SEGMENT, "TestS3kIczSegmentColumnGraphRewind");

        expected.forEach((className, evidence) -> {
            RewindRoundTripHarness.RoundTripSweepResult result = RewindRoundTripHarness.probeClass(className);
            RewindRoundTripHarness.RoundTripSweepResult.GraphCovered covered =
                    assertInstanceOf(RewindRoundTripHarness.RoundTripSweepResult.GraphCovered.class, result, className);
            assertEquals(evidence, covered.evidence());
        });
    }
}
