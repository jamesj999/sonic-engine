package com.openggf.trace;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDynamicArtDiagnosticsComparator {

    private final DynamicArtSpecialStageComparator comparator =
            new DynamicArtSpecialStageComparator();

    @Test
    void exactEnvelopeMatchesAtZeroTolerance() {
        TraceEvent.DynamicArtTransferState expected = state(
                7, List.of(expectedSubmitted(31, 70, false,
                        List.of(romRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));
        DynamicArtDiagnosticsSnapshot actual = snapshot(
                7, List.of(actualSubmitted(31, 70, false,
                        List.of(actualRomRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));

        FrameComparison result = comparator.compare(expected, actual);

        assertFalse(result.hasDivergence());
        assertTrue(result.fields().keySet().stream()
                .allMatch(field -> field.startsWith("dynamic_art.")));
    }

    @Test
    void firstInteriorAndLastHeartbeatRowsCompareExactly() {
        for (int frame : List.of(0, 1, 99)) {
            FrameComparison result = comparator.compare(
                    state(frame, List.of(), List.of()),
                    snapshot(frame, List.of(), List.of()));

            assertFalse(result.hasDivergence(), "frame " + frame);
        }
    }

    @Test
    void missingExpectedEdgeIsAnExactFrontier() {
        TraceEvent.DynamicArtTransferState expected = state(
                7, List.of(expectedSubmitted(31, 70, false,
                        List.of(romRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));

        FrameComparison result = comparator.compare(
                expected, snapshot(7, List.of(), List.of()));

        assertError(result, "dynamic_art.edge[0].present", "true", "false");
    }

    @Test
    void extraProductionEdgeIsAnExactFrontier() {
        TraceEvent.DynamicArtTransferState expected =
                state(7, List.of(), List.of());
        DynamicArtDiagnosticsSnapshot actual = snapshot(
                7, List.of(actualSubmitted(31, 70, false,
                        List.of(actualRomRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));

        FrameComparison result = comparator.compare(expected, actual);

        assertError(result, "dynamic_art.edge[0].present", "false", "true");
    }

    @Test
    void requestOrderMismatchNamesTheFirstWrongRequestField() {
        TraceEvent.DynamicArtTransferState expected = state(
                7, List.of(expectedSubmitted(31, 70, false, List.of(
                        romRequest(0x50000, 2, 0xF000, 64),
                        romRequest(0x50100, 3, 0xF040, 32)))),
                List.of(70L));
        DynamicArtDiagnosticsSnapshot actual = snapshot(
                7, List.of(actualSubmitted(31, 70, false, List.of(
                        actualRomRequest(0x50100, 3, 0xF040, 32),
                        actualRomRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));

        FrameComparison result = comparator.compare(expected, actual);

        assertError(result,
                "dynamic_art.edge[0].request[0].rom_source_address",
                "327680", "327936");
    }

    @Test
    void lifecycleLedgerMismatchIsAnExactFrontier() {
        TraceEvent.DynamicArtTransferState expected =
                state(7, List.of(), List.of(70L, 71L));

        FrameComparison result = comparator.compare(
                expected, snapshot(7, List.of(), List.of(71L, 70L)));

        // Ids are rebased onto each side's own segment origin, so ledger
        // ORDER divergence still fails loudly while the absolute recorder
        // epoch is discarded.
        assertError(result, "dynamic_art.outstanding_transfer_ids",
                "[0, 1]", "[0, -1]");
    }

    @Test
    void segmentOriginOffsetDoesNotDivergeWhenStructureMatches() {
        // The recorder allocates transfer_id/edge_ordinal from emulator
        // power-on, so a segment cut later in a movie opens at a large origin
        // while the engine necessarily opens at zero. No ROM carries a
        // cumulative transfer identity (docs/s2disasm/s2.asm:1713 and :1769,
        // docs/s1disasm/sonic.asm:831), so only relative structure compares.
        FrameComparison first = comparator.compare(
                state(1, List.of(expectedSubmitted(3688, 1844, false,
                        List.of(romRequest(0x50000, 2, 0xF000, 64)))),
                        List.of(1844L)),
                snapshot(1, List.of(actualSubmitted(2, 1, false,
                        List.of(actualRomRequest(0x50000, 2, 0xF000, 64)))),
                        List.of(1L)));
        FrameComparison second = comparator.compare(
                state(2, List.of(expectedSubmitted(3690, 1845, false,
                        List.of(romRequest(0x50100, 3, 0xF040, 32)))),
                        List.of(1844L, 1845L)),
                snapshot(2, List.of(actualSubmitted(4, 2, false,
                        List.of(actualRomRequest(0x50100, 3, 0xF040, 32)))),
                        List.of(1L, 2L)));

        assertFalse(first.hasDivergence());
        assertFalse(second.hasDivergence());
    }

    @Test
    void rebasedLedgerStillFailsOnCardinality() {
        // The S2 scz/mcz frame-0 shape: ROM holds nothing outstanding while the
        // engine holds one transfer. A size mismatch survives normalisation.
        FrameComparison result = comparator.compare(
                state(0, List.of(), List.of()),
                snapshot(0, List.of(), List.of(0L)));

        assertError(result, "dynamic_art.outstanding_transfer_ids",
                "[]", "[0]");
    }

    @Test
    void rebasedEdgeStillFailsOnContent() {
        FrameComparison result = comparator.compare(
                state(1, List.of(expectedSubmitted(3688, 1844, false,
                        List.of(romRequest(0x50000, 2, 0xF000, 64)))),
                        List.of(1844L)),
                snapshot(1, List.of(actualSubmitted(2, 1, false,
                        List.of(actualRomRequest(0x50000, 2, 0xF000, 32)))),
                        List.of(1L)));

        assertError(result, "dynamic_art.edge[0].request[0].byte_length",
                "64", "32");
    }

    @Test
    void rebasedEdgeStillFailsOnRelativeOrdinalGap() {
        // A skipped transfer changes the RELATIVE structure and must fail even
        // though both sides anchor on their own origin.
        comparator.compare(
                state(1, List.of(expectedSubmitted(3688, 1844, false,
                        List.of(romRequest(0x50000, 2, 0xF000, 64)))),
                        List.of(1844L)),
                snapshot(1, List.of(actualSubmitted(2, 1, false,
                        List.of(actualRomRequest(0x50000, 2, 0xF000, 64)))),
                        List.of(1L)));

        FrameComparison result = comparator.compare(
                state(2, List.of(expectedSubmitted(3690, 1845, false,
                        List.of(romRequest(0x50100, 3, 0xF040, 32)))),
                        List.of(1844L, 1845L)),
                snapshot(2, List.of(actualSubmitted(6, 3, false,
                        List.of(actualRomRequest(0x50100, 3, 0xF040, 32)))),
                        List.of(1L, 3L)));

        assertError(result, "dynamic_art.edges", "[2]", "[4]");
    }

    @Test
    void lagHeartbeatWithRepeatedLedgerMatches() {
        TraceEvent.DynamicArtTransferState expected =
                state(8, List.of(), List.of(70L));

        FrameComparison result = comparator.compare(
                expected, snapshot(8, List.of(), List.of(70L)));

        assertFalse(result.hasDivergence());
        assertEquals("[]", result.fields().get("dynamic_art.edges").expected());
    }

    @Test
    void terminalForwardingMismatchIsAnExactFrontier() {
        TraceEvent.DynamicArtTransferState expected = state(
                9, List.of(expectedCompleted(32, 70, true,
                        List.of(ramRequest(0xFFC800, 0xF000, 0x2E0)))),
                List.of());
        DynamicArtDiagnosticsSnapshot actual = snapshot(
                9, List.of(actualCompleted(32, 70, false,
                        List.of(actualRamRequest(0xFFC800, 0xF000, 0x2E0)))),
                List.of());

        FrameComparison result = comparator.compare(expected, actual);

        assertError(result,
                "dynamic_art.edge[0].terminal_forwarded", "true", "false");
    }

    @Test
    void romCallbackPcIsValidatedEvidenceButNeverCompared() {
        TraceEvent.DynamicArtTransferState first = state(
                7, List.of(expectedSubmittedWithCallback(
                        31, 70, false, 0x1B848,
                        List.of(romRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));
        TraceEvent.DynamicArtTransferState second = state(
                7, List.of(expectedSubmittedWithCallback(
                        31, 70, false, 0x1B84E,
                        List.of(romRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));
        DynamicArtDiagnosticsSnapshot actual = snapshot(
                7, List.of(actualSubmitted(31, 70, false,
                        List.of(actualRomRequest(0x50000, 2, 0xF000, 64)))),
                List.of(70L));

        FrameComparison firstResult = comparator.compare(first, actual);
        FrameComparison secondResult = comparator.compare(second, actual);

        assertFalse(firstResult.hasDivergence());
        assertFalse(secondResult.hasDivergence());
        assertTrue(firstResult.fields().keySet().stream()
                .noneMatch(field -> field.contains("callback")));
    }

    @Test
    void traceBinderCanPublishADplcOnlyLagRow() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceEvent.DynamicArtTransferState expected =
                state(8, List.of(), List.of(70L));

        FrameComparison result = binder.compareDynamicArt(
                expected, snapshot(8, List.of(), List.of(70L)));

        assertFalse(result.hasDivergence());
        assertEquals(result, binder.comparisonForFrame(8));
        assertEquals(List.of("dynamic_art.frame", "dynamic_art.edges",
                        "dynamic_art.outstanding_transfer_ids"),
                result.fields().keySet().stream().toList());
    }

    @Test
    void runWalkerComparesMetadataOnlySpecialStageRowsWithoutStepping() {
        TraceEvent.DynamicArtTransferState expected =
                state(0, List.of(), List.of());
        TraceData metadataOnly = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s1", 0, 0, 1),
                List.of(),
                Map.of(0, List.of(expected)));

        FrameComparison result = TraceRunReplayWalker.compareDynamicArtRow(
                metadataOnly, 0, snapshot(0, List.of(), List.of()));

        assertNotNull(result);
        assertFalse(result.hasDivergence());
    }

    private static void assertError(
            FrameComparison result,
            String field,
            String expected,
            String actual) {
        FieldComparison comparison = result.fields().get(field);
        assertNotNull(comparison, "missing comparison field " + field);
        assertEquals(Severity.ERROR, comparison.severity());
        assertEquals(expected, comparison.expected());
        assertEquals(actual, comparison.actual());
    }

    private static TraceEvent.DynamicArtTransferState state(
            int frame,
            List<DynamicArtTransfer.SegmentEdge> edges,
            List<Long> outstanding) {
        return new TraceEvent.DynamicArtTransferState(frame, edges, outstanding);
    }

    private static DynamicArtTransfer.SegmentEdge expectedSubmitted(
            long ordinal,
            long transferId,
            boolean terminal,
            List<DynamicArtTransfer.Request> requests) {
        return expectedSubmittedWithCallback(
                ordinal, transferId, terminal, 0x1B848, requests);
    }

    private static DynamicArtTransfer.SegmentEdge expectedSubmittedWithCallback(
            long ordinal,
            long transferId,
            boolean terminal,
            int callbackPc,
            List<DynamicArtTransfer.Request> requests) {
        return expectedEdge(ordinal, transferId, "submitted",
                terminal, callbackPc, requests);
    }

    private static DynamicArtTransfer.SegmentEdge expectedCompleted(
            long ordinal,
            long transferId,
            boolean terminal,
            List<DynamicArtTransfer.Request> requests) {
        return expectedEdge(ordinal, transferId, "completed",
                terminal, 0x0D50, requests);
    }

    private static DynamicArtTransfer.SegmentEdge expectedEdge(
            long ordinal,
            long transferId,
            String phase,
            boolean terminal,
            int callbackPc,
            List<DynamicArtTransfer.Request> requests) {
        return new DynamicArtTransfer.SegmentEdge(
                ordinal, transferId, phase, "sonic", "segment",
                4, 6, 0, terminal ? 9 : 7, terminal,
                callbackPc, requests);
    }

    private static DynamicArtDiagnosticsSnapshot snapshot(
            int frame,
            List<DynamicArtDiagnosticsSnapshot.Edge> edges,
            List<Long> outstanding) {
        return new DynamicArtDiagnosticsSnapshot(frame, edges, outstanding);
    }

    private static DynamicArtDiagnosticsSnapshot.Edge actualSubmitted(
            long ordinal,
            long transferId,
            boolean terminal,
            List<DynamicArtDiagnosticsSnapshot.Request> requests) {
        return actualEdge(ordinal, transferId, "submitted", terminal, requests);
    }

    private static DynamicArtDiagnosticsSnapshot.Edge actualCompleted(
            long ordinal,
            long transferId,
            boolean terminal,
            List<DynamicArtDiagnosticsSnapshot.Request> requests) {
        return actualEdge(ordinal, transferId, "completed", terminal, requests);
    }

    private static DynamicArtDiagnosticsSnapshot.Edge actualEdge(
            long ordinal,
            long transferId,
            String phase,
            boolean terminal,
            List<DynamicArtDiagnosticsSnapshot.Request> requests) {
        return new DynamicArtDiagnosticsSnapshot.Edge(
                ordinal, transferId, phase, "sonic",
                4, 6, 0, terminal ? 9 : 7, terminal, requests);
    }

    private static DynamicArtTransfer.Request romRequest(
            int source,
            int tile,
            int destination,
            int length) {
        return new DynamicArtTransfer.Request(
                source, tile, -1, destination, length);
    }

    private static DynamicArtTransfer.Request ramRequest(
            int source,
            int destination,
            int length) {
        return new DynamicArtTransfer.Request(
                -1, -1, source, destination, length);
    }

    private static DynamicArtDiagnosticsSnapshot.Request actualRomRequest(
            int source,
            int tile,
            int destination,
            int length) {
        return DynamicArtDiagnosticsSnapshot.Request.rom(
                source, tile, destination, length);
    }

    private static DynamicArtDiagnosticsSnapshot.Request actualRamRequest(
            int source,
            int destination,
            int length) {
        return DynamicArtDiagnosticsSnapshot.Request.ram(
                source, destination, length);
    }
}
