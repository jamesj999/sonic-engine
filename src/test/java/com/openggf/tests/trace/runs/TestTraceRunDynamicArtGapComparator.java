package com.openggf.tests.trace.runs;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.DynamicArtGapTransition;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapComparator;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapComparator.RuntimeGap;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapComparator.RuntimeTerminalTail;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapComparator.StructuralOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunDynamicArtGapComparator {

    @Test
    void comparesOrdinalsFingerprintsCompletedForwardingAndDestinationLedger() {
        DynamicArtTransfer.Descriptor forwarded = descriptor(
                7, "sonic", 4, "segment", request(0x50000, 4));
        DynamicArtTransfer.Descriptor destination = descriptor(
                8, "tails", 9, "run_gap", request(0x64000, 9));
        List<DynamicArtTransfer.GapTransition> expected = List.of(
                expectedGap(10, forwarded, "completed", 111, 0,
                        List.of(forwarded), List.of()),
                expectedGap(11, destination, "submitted", 112, 0,
                        List.of(), List.of(destination)));
        TraceRunManifest manifest = manifest(expected, destination);
        RuntimeGap actual = runtimeGap(List.of(forwarded), List.of(
                actualGap(10, forwarded, "completed", 111, 0,
                        List.of(7L), List.of()),
                actualGap(11, destination, "submitted", 112, 0,
                        List.of(), List.of(8L))));

        FrameComparison comparison = TraceRunDynamicArtGapComparator.compare(
                120, manifest, 0, actual);

        assertFalse(comparison.hasError(), comparison.divergentFields()::toString);
        assertTrue(comparison.fields().containsKey(
                "run_gap.edge[0].forwarded_completion"));
        assertTrue(comparison.fields().containsKey(
                "run_gap.destination.initial_ledger_fingerprint"));
    }

    @Test
    void reportsWrongEdgeOrdinalAndAfterLedgerFingerprint() {
        DynamicArtTransfer.Descriptor expectedDescriptor = descriptor(
                8, "tails", 9, "run_gap", request(0x64000, 9));
        DynamicArtTransfer.Descriptor actualDescriptor = descriptor(
                8, "tails", 10, "run_gap", request(0x64000, 10));
        TraceRunManifest manifest = manifest(List.of(
                expectedGap(11, expectedDescriptor, "submitted", 112, 0,
                        List.of(), List.of(expectedDescriptor))),
                expectedDescriptor);
        RuntimeGap actual = runtimeGap(List.of(), List.of(
                actualGap(12, actualDescriptor, "submitted", 112, 0,
                        List.of(), List.of(8L))));

        FrameComparison comparison = TraceRunDynamicArtGapComparator.compare(
                120, manifest, 0, actual);

        assertTrue(comparison.hasErrorInField("run_gap.edge[0].edge_ordinal"));
        assertTrue(comparison.hasErrorInField(
                "run_gap.edge[0].after_ledger_fingerprints"));
        assertTrue(comparison.hasErrorInField(
                "run_gap.destination.initial_ledger_fingerprint"));
    }

    @Test
    void reportsForwardedCompletionWithWrongSubmissionOwnership() {
        DynamicArtTransfer.Descriptor expectedForwarded = descriptor(
                7, "sonic", 4, "segment", request(0x50000, 4));
        DynamicArtTransfer.Descriptor actualInherited = descriptor(
                7, "sonic", 4, "run_gap", request(0x50000, 4));
        TraceRunManifest manifest = manifest(List.of(
                expectedGap(10, expectedForwarded, "completed", 111, 0,
                        List.of(expectedForwarded), List.of())), null);
        RuntimeGap actual = runtimeGap(List.of(actualInherited), List.of(
                actualGap(10, actualInherited, "completed", 111, 0,
                        List.of(7L), List.of())));

        FrameComparison comparison = TraceRunDynamicArtGapComparator.compare(
                120, manifest, 0, actual);

        assertTrue(comparison.hasErrorInField(
                "run_gap.edge[0].submission_origin"));
        assertTrue(comparison.hasErrorInField(
                "run_gap.edge[0].forwarded_completion"));
    }

    @Test
    void emptyGapArrayStillChecksStructuralCloseGapOpenOrder() {
        TraceRunManifest manifest = manifest(List.of(), null);
        RuntimeGap actual = new RuntimeGap(
                "source", "destination", new StructuralOrder(2, 1, 3),
                List.of(), List.of());

        FrameComparison comparison = TraceRunDynamicArtGapComparator.compare(
                120, manifest, 0, actual);

        assertTrue(comparison.hasErrorInField(
                "run_gap.structure.source_closed_before_gap"));
        assertTrue(comparison.fields().containsKey("run_gap.edge_count"));
    }

    @Test
    void emptyGapArrayAcceptsOrderedCloseGapAndDestinationOpen() {
        TraceRunManifest manifest = manifest(List.of(), null);
        RuntimeGap actual = runtimeGap(List.of(), List.of());

        FrameComparison comparison = TraceRunDynamicArtGapComparator.compare(
                120, manifest, 0, actual);

        assertFalse(comparison.hasError(), comparison.divergentFields()::toString);
    }

    @Test
    void terminalTailUsesExclusiveMovieRangeAndChecksFinalLedgerWithoutDestination() {
        DynamicArtTransfer.Descriptor terminal = descriptor(
                7, "sonic", 4, "run_gap", request(0x50000, 4));
        DynamicArtTransfer.Descriptor outsideMovie = descriptor(
                8, "sonic", 5, "run_gap", request(0x51000, 5));
        List<DynamicArtTransfer.GapTransition> expected = List.of(
                expectedGap(10, terminal, "submitted", 110, 0,
                        List.of(), List.of(terminal)),
                expectedGap(11, terminal, "completed", 119, 0,
                        List.of(terminal), List.of()),
                expectedGap(12, outsideMovie, "submitted", 120, 0,
                        List.of(), List.of(outsideMovie)));
        TraceRunManifest manifest = terminalManifest(expected);
        RuntimeTerminalTail actual = new RuntimeTerminalTail(
                "source", 0, 1, 2, List.of(), List.of(
                        actualGap(10, terminal, "submitted", 110, 0,
                                List.of(), List.of(7L)),
                        actualGap(11, terminal, "completed", 119, 0,
                                List.of(7L), List.of())));

        FrameComparison comparison =
                TraceRunDynamicArtGapComparator.compareTerminalTail(
                        110, manifest, 0, 120, actual);

        assertFalse(comparison.hasError(), comparison.divergentFields()::toString);
        assertTrue(comparison.fields().containsKey(
                "run_tail.final_ledger_fingerprints"));
        assertFalse(comparison.fields().keySet().stream()
                        .anyMatch(field -> field.contains("destination")),
                "the terminal-tail contract has no fabricated destination");
    }

    @Test
    void excusesOnlyTheRowStampInsideAnUnrepresentedUnclosedSpan() {
        DynamicArtTransfer.Descriptor terminal = descriptor(
                7, "sonic", 4, "run_gap", request(0x50000, 4));
        TraceRunManifest manifest = terminalManifest(List.of(
                expectedGap(10, terminal, "submitted", 118, 0,
                        List.of(), List.of(terminal)),
                expectedGap(11, terminal, "completed", 118, 1,
                        List.of(terminal), List.of())));
        RuntimeTerminalTail actual = new RuntimeTerminalTail(
                "source", 0, 1, 2, List.of(), List.of(
                        actualGap(10, terminal, "submitted", 112, 0,
                                List.of(), List.of(7L)),
                        actualGap(11, terminal, "completed", 112, 1,
                                List.of(7L), List.of())));

        FrameComparison comparison =
                TraceRunDynamicArtGapComparator.compareTerminalTail(
                        110, manifest, 0, 120, actual);

        assertFalse(comparison.hasError(), comparison.divergentFields()::toString);
        assertEquals(Severity.WARNING, comparison.fields()
                .get("run_tail.edge[0].movie_logical_frame").severity(),
                "an excused row stamp must stay visible as a warning");
    }

    @Test
    void stillFailsOnEdgeIdentityInsideAnUnrepresentedUnclosedSpan() {
        DynamicArtTransfer.Descriptor terminal = descriptor(
                7, "sonic", 4, "run_gap", request(0x50000, 4));
        DynamicArtTransfer.Descriptor wrongFrame = descriptor(
                7, "sonic", 5, "run_gap", request(0x50000, 4));
        TraceRunManifest manifest = terminalManifest(List.of(
                expectedGap(10, terminal, "submitted", 118, 0,
                        List.of(), List.of(terminal))));
        RuntimeTerminalTail actual = new RuntimeTerminalTail(
                "source", 0, 1, 2, List.of(), List.of(
                        actualGap(10, wrongFrame, "submitted", 112, 0,
                                List.of(), List.of(7L))));

        FrameComparison comparison =
                TraceRunDynamicArtGapComparator.compareTerminalTail(
                        110, manifest, 0, 120, actual);

        assertTrue(comparison.hasErrorInField(
                        "run_tail.edge[0].mapping_frame"),
                "identity is never excused, whatever the span declares");
    }

    @Test
    void comparesTheRowStampByRowWhereRecordedCoverageFollowsTheSpan() {
        DynamicArtTransfer.Descriptor moved = descriptor(
                7, "sonic", 4, "run_gap", request(0x50000, 4));
        TraceRunManifest manifest = manifest(List.of(
                expectedGap(10, moved, "submitted", 112, 0,
                        List.of(), List.of(moved))), null);
        RuntimeGap actual = runtimeGap(List.of(), List.of(
                actualGap(10, moved, "submitted", 115, 0,
                        List.of(), List.of(7L))));

        FrameComparison comparison =
                TraceRunDynamicArtGapComparator.compare(0, manifest, 0, actual);

        assertTrue(comparison.hasErrorInField(
                        "run_gap.edge[0].movie_logical_frame"),
                "a span closed by later recorded coverage still compares by row");
    }

    private static RuntimeGap runtimeGap(
            List<DynamicArtTransfer.Descriptor> opening,
            List<DynamicArtGapTransition> transitions) {
        return new RuntimeGap("source", "destination",
                new StructuralOrder(0, 1, 2), opening, transitions);
    }

    private static TraceRunManifest manifest(
            List<DynamicArtTransfer.GapTransition> gaps,
            DynamicArtTransfer.Descriptor destinationLedger) {
        var source = new TraceRunManifest.Segment(
                "source", "level", "gameplay_unlock", 100, 10,
                0, 1, null, null, List.of(), null);
        List<DynamicArtTransfer.Descriptor> ledger = destinationLedger == null
                ? List.of() : List.of(destinationLedger);
        var destination = new TraceRunManifest.Segment(
                "destination", "level", "gameplay_unlock", 120, 10,
                0, 1, null, null, ledger, DynamicArtTransfer.ledgerHash(ledger));
        return new TraceRunManifest("s2", "run", "movie.bk2",
                "crc", List.of(source, destination), List.of(), gaps,
                TraceRunManifest.ExpectedMovieEndMode.UNSPECIFIED);
    }

    private static TraceRunManifest terminalManifest(
            List<DynamicArtTransfer.GapTransition> gaps) {
        var source = new TraceRunManifest.Segment(
                "source", "level", "gameplay_unlock", 100, 10,
                0, 1, null, null, List.of(), null);
        return new TraceRunManifest("s1", "terminal", "movie.bk2",
                "crc", List.of(source), List.of(), gaps,
                TraceRunManifest.ExpectedMovieEndMode.LEVEL);
    }

    private static DynamicArtTransfer.GapTransition expectedGap(
            long ordinal,
            DynamicArtTransfer.Descriptor descriptor,
            String phase,
            int movieFrame,
            int gapIndex,
            List<DynamicArtTransfer.Descriptor> before,
            List<DynamicArtTransfer.Descriptor> after) {
        DynamicArtTransfer.GapEdge edge = new DynamicArtTransfer.GapEdge(
                ordinal, descriptor.transferId(), phase, descriptor.owner(),
                descriptor.submissionOrigin(), descriptor.mappingFrame(),
                movieFrame, gapIndex, 0,
                "submitted".equals(phase) ? descriptor.requests()
                        : descriptor.requests());
        return new DynamicArtTransfer.GapTransition(
                edge, DynamicArtTransfer.ledgerHash(before), after);
    }

    private static DynamicArtGapTransition actualGap(
            long ordinal,
            DynamicArtTransfer.Descriptor descriptor,
            String phase,
            int movieFrame,
            int gapIndex,
            List<Long> before,
            List<Long> after) {
        DynamicArtGapTransition.GapEdge edge = new DynamicArtGapTransition.GapEdge(
                ordinal, descriptor.transferId(), phase, descriptor.owner(),
                descriptor.mappingFrame(), movieFrame, gapIndex, 0,
                descriptor.requests().stream().map(TestTraceRunDynamicArtGapComparator::runtimeRequest)
                        .toList());
        return new DynamicArtGapTransition(edge, before, after);
    }

    private static DynamicArtTransfer.Descriptor descriptor(
            long id, String owner, int mappingFrame, String origin,
            DynamicArtTransfer.Request request) {
        return new DynamicArtTransfer.Descriptor(
                id, owner, mappingFrame, origin, List.of(request), null);
    }

    private static DynamicArtTransfer.Request request(int romAddress, int tile) {
        return new DynamicArtTransfer.Request(
                romAddress, tile, -1, 0xF000, 0x80);
    }

    private static DynamicArtDiagnosticsSnapshot.Request runtimeRequest(
            DynamicArtTransfer.Request request) {
        return DynamicArtDiagnosticsSnapshot.Request.rom(
                request.romSourceAddress(), request.sourceTileIndex(),
                request.vramDestination(), request.byteLength());
    }
}
