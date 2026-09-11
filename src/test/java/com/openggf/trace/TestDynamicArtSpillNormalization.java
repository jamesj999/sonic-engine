package com.openggf.trace;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicArtSpillNormalization} rebinding semantics: spilled submission
 * edges move to their pass row with frame stamps rewritten, and nothing else
 * loosens — missing, extra, and wrongly-attributed submissions must still fail
 * through {@link DynamicArtSpecialStageComparator} after normalization.
 */
class TestDynamicArtSpillNormalization {

    private static final int PASS_ROW = 181;
    private static final int LAG_ROW = 182;
    private static final int SPILL_ROW = 183;

    private static DynamicArtTransfer.SegmentEdge expectedSubmitted(
            long ordinal, long transferId, String owner,
            int logicalFrame, int publicationFrame) {
        return new DynamicArtTransfer.SegmentEdge(
                ordinal, transferId, "submitted", owner, "segment",
                1, logicalFrame, 0, publicationFrame, false,
                0x33B3E,
                List.of(new DynamicArtTransfer.Request(-1, -1, 0xFF0000, 0x5CA0, 0x80)));
    }

    private static DynamicArtDiagnosticsSnapshot.Edge actualSubmitted(
            long ordinal, long transferId, String owner, int row, int rowIndex) {
        return new DynamicArtDiagnosticsSnapshot.Edge(
                ordinal, transferId, "submitted", owner,
                1, row, rowIndex, row, false,
                List.of(DynamicArtDiagnosticsSnapshot.Request.ram(
                        0xFF0000, 0x5CA0, 0x80)));
    }

    /** Rows: PASS_ROW non-lag, LAG_ROW lag, SPILL_ROW non-lag. */
    private static Map<Integer, TraceEvent.DynamicArtTransferState> normalize(
            Map<Integer, TraceEvent.DynamicArtTransferState> states) {
        return DynamicArtSpillNormalization.rebindSubmissionSpills(
                states, SPILL_ROW + 1, row -> row == LAG_ROW);
    }

    private static Map<Integer, TraceEvent.DynamicArtTransferState> spilledFixture() {
        // Recorder: sonic submitted on the pass row; tails from the same pass
        // crossed the frame boundary (logical = the lag row, published later).
        return Map.of(
                PASS_ROW, new TraceEvent.DynamicArtTransferState(
                        PASS_ROW,
                        List.of(expectedSubmitted(10, 5, "ss-sonic", PASS_ROW, PASS_ROW)),
                        List.of(5L)),
                SPILL_ROW, new TraceEvent.DynamicArtTransferState(
                        SPILL_ROW,
                        List.of(expectedSubmitted(11, 6, "ss-tails", LAG_ROW, SPILL_ROW)),
                        List.of(5L, 6L)));
    }

    private static long errorCount(FrameComparison comparison) {
        return comparison.fields().values().stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .count();
    }

    @Test
    void spilledSubmissionRebindsToPassRowAndMatchesAtomicPublication() {
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized =
                normalize(spilledFixture());

        TraceEvent.DynamicArtTransferState passRow = normalized.get(PASS_ROW);
        assertEquals(2, passRow.edges().size(),
                "spilled tails submission joins its pass row");
        assertEquals(List.of(10L, 11L),
                passRow.edges().stream()
                        .map(DynamicArtTransfer.SegmentEdge::edgeOrdinal).toList(),
                "in-pass ordinal order preserved");
        assertEquals(PASS_ROW, passRow.edges().get(1).logicalFrame());
        assertEquals(PASS_ROW, passRow.edges().get(1).publicationFrame());
        assertTrue(normalized.get(SPILL_ROW).edges().isEmpty(),
                "spill row no longer expects the moved submission");
        assertEquals(List.of(5L, 6L), passRow.outstandingTransferIds(),
                "moved submission is outstanding from its pass row");

        // The engine publishes the whole pass atomically on the pass row.
        DynamicArtSpecialStageComparator comparator =
                new DynamicArtSpecialStageComparator();
        FrameComparison comparison = comparator.compare(
                passRow,
                new DynamicArtDiagnosticsSnapshot(PASS_ROW, List.of(
                        actualSubmitted(10, 5, "ss-sonic", PASS_ROW, 0),
                        actualSubmitted(11, 6, "ss-tails", PASS_ROW, 1)),
                        List.of(5L, 6L)));
        assertEquals(0, errorCount(comparison),
                "atomic pass publication matches the rebased expectation: "
                        + comparison.fields().values().stream()
                                .filter(f -> f.severity() == Severity.ERROR)
                                .map(Object::toString).toList());
    }

    @Test
    void missingSubmissionStillFails() {
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized =
                normalize(spilledFixture());
        FrameComparison comparison = new DynamicArtSpecialStageComparator().compare(
                normalized.get(PASS_ROW),
                new DynamicArtDiagnosticsSnapshot(PASS_ROW, List.of(
                        actualSubmitted(10, 5, "ss-sonic", PASS_ROW, 0)),
                        List.of(5L)));
        assertTrue(errorCount(comparison) > 0,
                "a genuinely missing submission must still error");
    }

    @Test
    void extraSubmissionStillFails() {
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized =
                normalize(spilledFixture());
        FrameComparison comparison = new DynamicArtSpecialStageComparator().compare(
                normalized.get(PASS_ROW),
                new DynamicArtDiagnosticsSnapshot(PASS_ROW, List.of(
                        actualSubmitted(10, 5, "ss-sonic", PASS_ROW, 0),
                        actualSubmitted(11, 6, "ss-tails", PASS_ROW, 1),
                        actualSubmitted(12, 7, "ss-tails-tails", PASS_ROW, 2)),
                        List.of(5L, 6L, 7L)));
        assertTrue(errorCount(comparison) > 0,
                "an extra submission must still error");
    }

    @Test
    void wrongOwnerAttributionStillFails() {
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized =
                normalize(spilledFixture());
        FrameComparison comparison = new DynamicArtSpecialStageComparator().compare(
                normalized.get(PASS_ROW),
                new DynamicArtDiagnosticsSnapshot(PASS_ROW, List.of(
                        actualSubmitted(10, 5, "ss-sonic", PASS_ROW, 0),
                        actualSubmitted(11, 6, "ss-tails-tails", PASS_ROW, 1)),
                        List.of(5L, 6L)));
        assertTrue(errorCount(comparison) > 0,
                "a wrong-owner submission must still error");
    }

    // ---- Paced region (recorded run_objects_end pass bindings) ----

    private static final int PACED_START = 400;

    /** Pass-8 shape: crossing 439, pass cursor 440, bound row 441. */
    private static final List<DynamicArtSpillNormalization.PassBinding> PACED_BINDINGS =
            List.of(
                    new DynamicArtSpillNormalization.PassBinding(438, 438),
                    new DynamicArtSpillNormalization.PassBinding(440, 441),
                    new DynamicArtSpillNormalization.PassBinding(443, 443));

    private static Map<Integer, TraceEvent.DynamicArtTransferState> normalizePaced(
            Map<Integer, TraceEvent.DynamicArtTransferState> states,
            java.util.function.IntPredicate cursorPreceded) {
        return DynamicArtSpillNormalization.rebindSubmissionSpills(
                states, 450, PACED_START, row -> row == 440,
                cursorPreceded, PACED_BINDINGS);
    }

    @Test
    void pacedEdgePublishedBeforeItsBoundRowBindsForward() {
        // Recorder: crossing 439, published 439; the covering pass (cursor
        // 440) is bound to row 441 — the engine publishes it there.
        // The recording carries a heartbeat for every row; the bound row's
        // outstanding list already includes the in-flight id.
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized = normalizePaced(
                Map.of(
                        439, new TraceEvent.DynamicArtTransferState(
                                439,
                                List.of(expectedSubmitted(20, 9, "ss-sonic", 439, 439)),
                                List.of(9L)),
                        441, new TraceEvent.DynamicArtTransferState(
                                441, List.of(), List.of(9L))),
                row -> false);
        assertTrue(normalized.get(439).edges().isEmpty(),
                "early-published edge leaves its recorder row");
        assertEquals(1, normalized.get(441).edges().size(),
                "early-published edge binds forward to the pass's bound row");
        assertEquals(441, normalized.get(441).edges().get(0).publicationFrame());
        assertEquals(List.of(),
                normalized.get(439).outstandingTransferIds(),
                "the moved id is not outstanding before its bound row");

        FrameComparison comparison = new DynamicArtSpecialStageComparator().compare(
                normalized.get(441),
                new DynamicArtDiagnosticsSnapshot(441, List.of(
                        actualSubmitted(20, 9, "ss-sonic", 441, 0)),
                        List.of(9L)));
        assertEquals(0, errorCount(comparison),
                "atomic publication at the bound row matches");
    }

    @Test
    void pacedEdgePublishedAfterItsBoundRowBindsBackward() {
        // Recorder: crossing 440 (the lag row), published 442; the covering
        // pass (cursor 440) is bound to row 441.
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized = normalizePaced(
                Map.of(442, new TraceEvent.DynamicArtTransferState(
                        442,
                        List.of(expectedSubmitted(21, 10, "ss-tails", 440, 442)),
                        List.of(10L))),
                row -> false);
        assertTrue(normalized.get(442).edges().isEmpty(),
                "late-published edge leaves its recorder row");
        assertEquals(1, normalized.get(441).edges().size(),
                "late-published edge binds back to the pass's bound row");
        assertEquals(List.of(10L),
                normalized.get(441).outstandingTransferIds(),
                "the moved id is outstanding from its bound row");
    }

    @Test
    void pacedMissingExtraAndWrongOwnerStillFail() {
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized = normalizePaced(
                Map.of(441, new TraceEvent.DynamicArtTransferState(
                        441,
                        List.of(expectedSubmitted(20, 9, "ss-sonic", 441, 441),
                                expectedSubmitted(21, 10, "ss-tails", 441, 441)),
                        List.of(9L, 10L))),
                row -> false);
        DynamicArtSpecialStageComparator comparator;

        comparator = new DynamicArtSpecialStageComparator();
        assertTrue(errorCount(comparator.compare(
                normalized.get(441),
                new DynamicArtDiagnosticsSnapshot(441, List.of(
                        actualSubmitted(20, 9, "ss-sonic", 441, 0)),
                        List.of(9L)))) > 0,
                "missing paced submission must still error");

        comparator = new DynamicArtSpecialStageComparator();
        assertTrue(errorCount(comparator.compare(
                normalized.get(441),
                new DynamicArtDiagnosticsSnapshot(441, List.of(
                        actualSubmitted(20, 9, "ss-sonic", 441, 0),
                        actualSubmitted(21, 10, "ss-tails", 441, 1),
                        actualSubmitted(22, 11, "ss-tails-tails", 441, 2)),
                        List.of(9L, 10L, 11L)))) > 0,
                "extra paced submission must still error");

        comparator = new DynamicArtSpecialStageComparator();
        assertTrue(errorCount(comparator.compare(
                normalized.get(441),
                new DynamicArtDiagnosticsSnapshot(441, List.of(
                        actualSubmitted(20, 9, "ss-sonic", 441, 0),
                        actualSubmitted(21, 10, "ss-tails-tails", 441, 1)),
                        List.of(9L, 10L)))) > 0,
                "wrong-owner paced submission must still error");
    }

    @Test
    void crossingStampRewrittenOnlyOnCursorPrecededRows() {
        // Row-425 shape: the edge already sits on its bound row with a
        // crossing stamp one frame earlier, and the engine reproduces that
        // stamp (terminal-boundary queueing). Without the row being flagged
        // cursor-preceded, the stamp must stay absolute...
        TraceEvent.DynamicArtTransferState onBoundRow =
                new TraceEvent.DynamicArtTransferState(
                        441,
                        List.of(expectedSubmitted(20, 9, "ss-sonic", 440, 441)),
                        List.of(9L));
        Map<Integer, TraceEvent.DynamicArtTransferState> untouched = normalizePaced(
                Map.of(441, onBoundRow), row -> false);
        assertEquals(440, untouched.get(441).edges().get(0).logicalFrame(),
                "recorded crossing stamp stays absolute off cursor-preceded rows");

        // ...and on a cursor-preceded row (row-436 shape) it is compared as
        // published-on-this-row, because the engine provably queues there.
        Map<Integer, TraceEvent.DynamicArtTransferState> rewritten = normalizePaced(
                Map.of(441, onBoundRow), row -> row == 441);
        assertEquals(441, rewritten.get(441).edges().get(0).logicalFrame(),
                "cursor-preceded rows compare the stamp as published-on-row");
    }

    @Test
    void completionEdgesAreNeverMoved() {
        DynamicArtTransfer.SegmentEdge completed = new DynamicArtTransfer.SegmentEdge(
                12, 5, "completed", "ss-sonic", "segment",
                1, LAG_ROW, 0, SPILL_ROW, false,
                0x14AC,
                List.of(new DynamicArtTransfer.Request(-1, -1, 0xFF0000, 0x5CA0, 0x80)));
        Map<Integer, TraceEvent.DynamicArtTransferState> normalized = normalize(Map.of(
                SPILL_ROW, new TraceEvent.DynamicArtTransferState(
                        SPILL_ROW, List.of(completed), List.of())));
        assertEquals(1, normalized.get(SPILL_ROW).edges().size(),
                "completed edges stay on their recorded row");
        assertEquals(SPILL_ROW,
                normalized.get(SPILL_ROW).edges().get(0).publicationFrame());
    }
}
