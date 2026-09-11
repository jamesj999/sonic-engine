package com.openggf.tests.trace.s2;

import com.openggf.tests.RomTestUtils;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.SpecialStageTraceData;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S2SpecialStageFinishBoundaryMappingTest {

    @Test
    void lagObservedFinishConsumesExactCapturedCompletedPass() throws Exception {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(romFile != null && Files.exists(Path.of("s2.gen")),
                "s2.gen ROM required for S2 special-stage finish mapping");
        Path dir = AbstractS2SpecialStageTraceReplayTest.TRACE_DIRECTORY;
        SpecialStageTraceData trace = SpecialStageTraceData.load(dir);
        S2SpecialStageReplayHarness harness =
                AbstractS2SpecialStageTraceReplayTest.bootHarness(trace, dir, romFile);

        DivergenceReport report =
                AbstractS2SpecialStageTraceReplayTest.compareReplay(trace, harness);

        assertFalse(report.hasErrors(), report.toAssertionSummary());
        assertTrue(report.getContextWindow(
                        trace.stageFinishedObservedFrame().getAsInt(), 0)
                        .contains("rings_togo_bcd"),
                "the raw finish observation must retain its final rings-to-go comparison");
        assertEquals(1, java.util.Collections.frequency(
                harness.steppedPassSequencesForTest(), terminalPassSequence(trace)));
    }

    @Test
    void finishingBeforeTerminalPassIsAnErrorAndStillConsumesTerminalExactlyOnce() throws Exception {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(romFile != null && Files.exists(Path.of("s2.gen")),
                "s2.gen ROM required for S2 special-stage finish mapping");
        Path dir = AbstractS2SpecialStageTraceReplayTest.TRACE_DIRECTORY;
        SpecialStageTraceData trace = SpecialStageTraceData.load(dir);
        S2SpecialStageReplayHarness harness =
                AbstractS2SpecialStageTraceReplayTest.bootHarness(trace, dir, romFile);
        harness.forceFinishedAfterPassForTest(terminalPassSequence(trace) - 1);

        DivergenceReport report =
                AbstractS2SpecialStageTraceReplayTest.compareReplay(trace, harness);

        assertTrue(report.hasErrors(), "finishing before terminal pass must not compare green");
        assertTrue(report.toAssertionSummary().contains(
                        "before-terminal-pass@" + trace.stageFinishedFrame().getAsInt()),
                report.toAssertionSummary());
        assertEquals(1, java.util.Collections.frequency(
                harness.steppedPassSequencesForTest(), terminalPassSequence(trace)),
                "terminal pass must still be consumed through its exact BK2 identity");
    }

    /**
     * Sequence of the stage's last recorded {@code RunObjects} pass. Derived
     * from the stream rather than written out, so it stays the ROM's terminal
     * pass when the recorder's coverage changes -- adding the pre-start half of
     * {@code SpecialStage_MainLoop} (docs/s2disasm/s2.asm:6674-6692) shifts
     * every sequence number by the pre-start pass count.
     */
    private static int terminalPassSequence(SpecialStageTraceData trace) {
        return trace.runObjectsEndSnapshots().stream()
                .mapToInt(snapshot -> ((Number) snapshot.fields()
                        .get("pass_sequence")).intValue())
                .max()
                .orElseThrow();
    }
}
