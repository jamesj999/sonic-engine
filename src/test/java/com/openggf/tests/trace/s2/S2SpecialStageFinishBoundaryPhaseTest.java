package com.openggf.tests.trace.s2;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.rules.DynamicArtDmaServiceModel;
import com.openggf.trace.DynamicArtSpecialStageComparator;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S2 special stage's finish boundary, pinned against the ROM sequence that
 * produces it.
 *
 * <p>The recorded stage finish is not a quiet row: the observation that runs
 * the finish-causing {@code RunObjects} pass reads as a hardware lag row, and
 * the player art that pass queues then stays outstanding for 39 observations
 * before retiring. Both facts are ROM structure, not recorder noise:
 * <ul>
 *   <li>The special-stage loop asks for {@code VintID_S2SS} and waits on it
 *       immediately before {@code RunObjects}
 *       (docs/s2disasm/s2.asm:6694-6706), so an observation that ran a pass
 *       cannot have taken the {@code Vint_Lag} branch — that is reached only
 *       while {@code Vint_routine} is still 0 (s2.asm:483-484).</li>
 *   <li>{@code SS_Check_Rings_flag} breaks the loop straight into
 *       {@code Pal_FadeToWhite} (s2.asm:6725-6745), 22 {@code VintID_Fade}
 *       V-blanks (s2.asm:3570-3581), then interrupts-disabled results-screen
 *       setup, and only then a {@code VintID_Level} wait
 *       (s2.asm:6800-6806). {@code ProcessDMAQueue} (s2.asm:1770) is reached
 *       from none of those until that last one.</li>
 * </ul>
 *
 * <p>These tests hold the pacing policy to that structure and, crucially,
 * prove it did not buy alignment by loosening the comparator: a missing,
 * extra, wrongly-attributed, or early-retired transfer at the finish row must
 * still error.
 */
class S2SpecialStageFinishBoundaryPhaseTest {

    private static SpecialStageTraceData trace() {
        try {
            return SpecialStageTraceData.load(
                    AbstractS2SpecialStageTraceReplayTest.TRACE_DIRECTORY);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long errorCount(FrameComparison comparison) {
        return comparison.fields().values().stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .count();
    }

    // ==================== Phase policy ====================

    @Test
    void anObservationThatRanAPassIsNeverALagVblank() {
        assertEquals(PlcLifecyclePhase.SPECIAL_STAGE,
                S2SpecialStageReplayHarness.observationPhase(true, true),
                "a pass ran, so Vint_routine was VintID_S2SS, not 0");
        assertEquals(PlcLifecyclePhase.LAG,
                S2SpecialStageReplayHarness.observationPhase(true, false),
                "a lag row with no pass keeps the recorder's lag classification");
        assertEquals(PlcLifecyclePhase.SPECIAL_STAGE,
                S2SpecialStageReplayHarness.observationPhase(false, false),
                "a non-lag row runs the mode handler whether or not it ran a pass");

        // The two phases are not interchangeable: they differ in exactly the
        // property the finish boundary turns on.
        DynamicArtDmaServiceModel model =
                DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE;
        assertTrue(model.services(PlcLifecyclePhase.SPECIAL_STAGE));
        assertFalse(model.services(PlcLifecyclePhase.LAG));
    }

    /**
     * The override above is a property of pass-owning observations, not a
     * finish special case: across the whole 5299-row recording exactly one
     * pass-owning observation is a lag row, and it is the finish.
     */
    @Test
    void theFinishIsTheOnlyPassOwningLagRowInTheRecording() {
        SpecialStageTraceData trace = trace();
        List<Integer> passOwningLagRows = new ArrayList<>();
        for (TraceEvent.StateSnapshot pass : trace.runObjectsEndSnapshots()) {
            if (trace.getFrame(pass.frame()).lag()) {
                passOwningLagRows.add(pass.frame());
            }
        }
        assertEquals(List.of(trace.stageFinishedObservedFrame().orElseThrow()),
                passOwningLagRows,
                "pass-owning lag rows must be exactly the finish observation");
    }

    @Test
    void fadeVblanksDoNotRetireTransfersButTheResultsVintDoes() {
        DynamicArtDmaServiceModel model =
                DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE;
        assertFalse(model.services(PlcLifecyclePhase.PALETTE_FADE),
                "Vint_Fade never reaches ProcessDMAQueue");
        assertTrue(model.services(PlcLifecyclePhase.SPECIAL_STAGE_RESULTS),
                "the results loop waits on VintID_Level, which does");
    }

    // ==================== The recorded outstanding window ====================

    /**
     * {@code Pal_FadeToWhite}'s {@code d4 = $15} loop is the whole reason the
     * finish pass's art stays in flight: its 22 V-blanks, then the lag block of
     * interrupts-disabled results setup, then one {@code Vint_Level}. The
     * recording must show exactly that shape, or the constant is wrong.
     */
    @Test
    void palFadeToWhiteLengthMatchesTheRecordedPostFinishNonLagRun() {
        SpecialStageTraceData trace = trace();
        int observed = trace.stageFinishedObservedFrame().orElseThrow();

        int fadeRows = 0;
        while (!trace.getFrame(observed + 1 + fadeRows).lag()) {
            fadeRows++;
        }
        assertEquals(AbstractS2SpecialStageTraceReplayTest.PAL_FADE_TO_WHITE_FRAMES,
                fadeRows,
                "the post-finish non-lag run is Pal_FadeToWhite's $15+1 iterations");

        // Compared against what the replay actually expects per row: the
        // finish pass's submissions are bound to the pass's own observation.
        Map<Integer, TraceEvent.DynamicArtTransferState> expected =
                AbstractS2SpecialStageTraceReplayTest.normalizedDynamicArtRows(trace);
        List<Long> inFlight = expected.get(observed).outstandingTransferIds();
        assertFalse(inFlight.isEmpty(),
                "the finish pass leaves player art queued");

        // Nothing retires during the fade or the setup block.
        int row = observed + 1;
        for (; row < observed + 1 + fadeRows; row++) {
            assertTrue(expected.get(row).edges().isEmpty(),
                    "a Vint_Fade V-blank must not retire anything (row " + row + ")");
            assertEquals(inFlight, expected.get(row).outstandingTransferIds(),
                    "the queue is untouched across the fade (row " + row + ")");
        }
        while (trace.getFrame(row).lag()) {
            assertTrue(expected.get(row).edges().isEmpty(),
                    "the interrupts-disabled results setup retires nothing (row "
                            + row + ")");
            row++;
        }

        // The first V-blank after it is the results loop's Vint_Level, and it
        // retires exactly what the finish pass queued.
        TraceEvent.DynamicArtTransferState retirement = expected.get(row);
        assertEquals(inFlight,
                retirement.edges().stream()
                        .filter(e -> "completed".equals(e.phase()))
                        .map(DynamicArtTransfer.SegmentEdge::transferId)
                        .toList(),
                "the first post-setup V-int retires the finish pass's transfers");
        assertTrue(retirement.outstandingTransferIds().isEmpty(),
                "nothing remains in flight afterwards");
    }

    // ==================== Comparator sensitivity at the finish row ====================

    private static TraceEvent.DynamicArtTransferState expectedFinishRow() {
        SpecialStageTraceData trace = trace();
        int observed = trace.stageFinishedObservedFrame().orElseThrow();
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized =
                AbstractS2SpecialStageTraceReplayTest.normalizedDynamicArtRows(trace);
        TraceEvent.DynamicArtTransferState row = normalized.get(observed);
        assertEquals(2, row.edges().size(),
                "the finish pass submits both players' art");
        return row;
    }

    /** Mirrors an expected edge as the engine's own diagnostics view. */
    private static DynamicArtDiagnosticsSnapshot.Edge mirror(
            DynamicArtTransfer.SegmentEdge edge, String owner, int rowIndex) {
        List<DynamicArtDiagnosticsSnapshot.Request> requests = edge.requests().stream()
                .map(r -> r.romSourceAddress() >= 0
                        ? DynamicArtDiagnosticsSnapshot.Request.rom(
                                r.romSourceAddress(), r.sourceTileIndex(),
                                r.vramDestination(), r.byteLength())
                        : DynamicArtDiagnosticsSnapshot.Request.ram(
                                r.ramSourceAddress(), r.vramDestination(),
                                r.byteLength()))
                .toList();
        return new DynamicArtDiagnosticsSnapshot.Edge(
                edge.edgeOrdinal(), edge.transferId(), edge.phase(), owner,
                edge.mappingFrame(), edge.logicalFrame(), rowIndex,
                edge.publicationFrame(), edge.terminalForwarded(), requests);
    }

    private static long compareFinishRow(List<DynamicArtDiagnosticsSnapshot.Edge> actual,
                                         List<Long> outstanding) {
        TraceEvent.DynamicArtTransferState expected = expectedFinishRow();
        return errorCount(new DynamicArtSpecialStageComparator().compare(
                expected,
                new DynamicArtDiagnosticsSnapshot(
                        expected.frame(), actual, outstanding)));
    }

    private static List<DynamicArtDiagnosticsSnapshot.Edge> faithfulFinishEdges() {
        TraceEvent.DynamicArtTransferState expected = expectedFinishRow();
        List<DynamicArtDiagnosticsSnapshot.Edge> edges = new ArrayList<>();
        for (int i = 0; i < expected.edges().size(); i++) {
            edges.add(mirror(expected.edges().get(i),
                    expected.edges().get(i).owner(), i));
        }
        return edges;
    }

    @Test
    void faithfulTerminalPassPublicationMatches() {
        assertEquals(0, compareFinishRow(faithfulFinishEdges(),
                        expectedFinishRow().outstandingTransferIds()),
                "the terminal pass published atomically on its observation matches");
    }

    @Test
    void missingTerminalSubmissionStillFails() {
        assertTrue(compareFinishRow(
                        List.of(faithfulFinishEdges().get(0)),
                        List.of(expectedFinishRow().outstandingTransferIds().get(0))) > 0,
                "a terminal submission the engine never made must still error");
    }

    @Test
    void extraTerminalSubmissionStillFails() {
        TraceEvent.DynamicArtTransferState expected = expectedFinishRow();
        List<DynamicArtDiagnosticsSnapshot.Edge> edges =
                new ArrayList<>(faithfulFinishEdges());
        DynamicArtTransfer.SegmentEdge template = expected.edges().get(1);
        edges.add(new DynamicArtDiagnosticsSnapshot.Edge(
                template.edgeOrdinal() + 1, template.transferId() + 1,
                "submitted", "ss-tails-tails", template.mappingFrame(),
                template.logicalFrame(), 2, template.publicationFrame(),
                false, mirror(template, "ss-tails-tails", 2).requests()));
        List<Long> outstanding =
                new ArrayList<>(expected.outstandingTransferIds());
        outstanding.add(template.transferId() + 1);
        assertTrue(compareFinishRow(edges, outstanding) > 0,
                "a submission the ROM never made must still error");
    }

    @Test
    void wrongOwnerTerminalSubmissionStillFails() {
        TraceEvent.DynamicArtTransferState expected = expectedFinishRow();
        List<DynamicArtDiagnosticsSnapshot.Edge> edges =
                new ArrayList<>(faithfulFinishEdges());
        edges.set(1, mirror(expected.edges().get(1), "ss-tails-tails", 1));
        assertTrue(compareFinishRow(edges, expected.outstandingTransferIds()) > 0,
                "a misattributed terminal submission must still error");
    }

    /**
     * The whole point of the fade window: had the engine retired the finish
     * pass's art on its own observation (or anywhere before the results
     * {@code Vint_Level}), the comparison must reject it.
     */
    @Test
    void earlyRetirementOfTheTerminalTransfersStillFails() {
        TraceEvent.DynamicArtTransferState expected = expectedFinishRow();
        List<DynamicArtDiagnosticsSnapshot.Edge> edges =
                new ArrayList<>(faithfulFinishEdges());
        DynamicArtTransfer.SegmentEdge first = expected.edges().get(0);
        edges.add(new DynamicArtDiagnosticsSnapshot.Edge(
                first.edgeOrdinal() + 100, first.transferId(), "completed",
                first.owner(), first.mappingFrame(), first.logicalFrame(), 2,
                first.publicationFrame(), false,
                mirror(first, first.owner(), 2).requests()));
        assertTrue(compareFinishRow(edges, expected.outstandingTransferIds()) > 0,
                "retiring on the finish observation must still error");
    }
}
