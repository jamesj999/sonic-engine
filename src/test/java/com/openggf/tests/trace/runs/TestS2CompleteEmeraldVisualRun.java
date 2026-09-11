package com.openggf.tests.trace.runs;

import com.openggf.TraceSessionLauncher;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@RequiresRom(SonicGame.SONIC_2)
class TestS2CompleteEmeraldVisualRun {
    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s2", "runs",
            "s2-sonic-tails-complete-emeralds");

    @AfterEach
    void tearDown() {
        VisualRunReplayHarness.tearDown();
    }

    @Test
    void replaysEveryComparedRowOfTheFirstEhz1Segment() throws Exception {
        VisualRunReplayHarness.Result result = VisualRunReplayHarness.replay(
                RUN_DIR, VisualRunReplayHarness.stopAfterSegmentBody(0));

        assertEquals(VisualRunReplayHarness.Outcome.REACHED_SEGMENT, result.outcome());
        assertEquals(4_479, result.sharedCursor(),
                "769 + 3710 is the first row after the complete EHZ1 body: " + result);
        assertEquals(0, result.currentSegmentIndex());
        assertNull(TraceSessionLauncher.active(),
                "the harness must tear down its transferred run lease");
    }
}
