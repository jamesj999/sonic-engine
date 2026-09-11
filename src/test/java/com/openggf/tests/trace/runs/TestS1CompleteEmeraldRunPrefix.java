package com.openggf.tests.trace.runs;

import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable parity frontier for the committed S1 emerald route. The prefix
 * includes the complete GHZ1 source, its giant-ring handoff, and the first
 * atomically published special-stage row.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestS1CompleteEmeraldRunPrefix extends AbstractRunChainTest {
    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s1", "runs",
            "s1-sonic-complete-withemeralds");

    @Test
    void ghz1ToFirstSpecialStageRow() throws Exception {
        assertChainReplayThroughSegmentRow(RUN_DIR, 1, 1);
    }

    /**
     * Extends the frontier past the special stage's own exit: segment 2 is the
     * GHZ2 presentation bridge the stage returns through, whose structural rows
     * are the ones a visual run reported failing atomic dynamic-art publication.
     */
    @Test
    void specialStageReturnThroughGhz2PresentationBridge() throws Exception {
        assertChainReplayThroughSegmentRow(RUN_DIR, 2, 800);
    }


    @Override
    protected void assertCompletedSegmentComparison(
            int segmentIndex, LiveTraceComparator comparator) {
        if (segmentIndex != 0) {
            return;
        }
        assertTrue(comparator.recentMismatches().stream()
                        .noneMatch(mismatch -> "player_animation_id".equals(mismatch.field())),
                "GHZ1 giant-ring handoff must retain id_Null after the native player slot is cleared");
    }
}
