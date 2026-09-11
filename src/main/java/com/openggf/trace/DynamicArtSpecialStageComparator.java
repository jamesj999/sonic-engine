package com.openggf.trace;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DPLC-only comparison for special-stage trace rows.
 *
 * <p>The comparator accepts one validated expected envelope and one immutable
 * production snapshot. It owns no engine step, lifecycle, submission, or
 * completion surface.
 *
 * <p>One instance represents one comparison segment: it carries the
 * {@link DynamicArtIdEpoch} that rebases recorder delivery identities onto each
 * side's own segment origin. Reuse the same instance for every row of a
 * segment, and take a fresh one when a new segment opens.
 */
public final class DynamicArtSpecialStageComparator {

    private final DynamicArtIdEpoch epoch = new DynamicArtIdEpoch();

    public FrameComparison compare(
            TraceEvent.DynamicArtTransferState expected,
            DynamicArtDiagnosticsSnapshot actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        return new FrameComparison(
                expected.frame(), comparisonFields(expected, actual, epoch));
    }

    static Map<String, FieldComparison> comparisonFields(
            TraceEvent.DynamicArtTransferState expected,
            DynamicArtDiagnosticsSnapshot actual,
            DynamicArtIdEpoch epoch) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        put(fields, "dynamic_art.frame", expected.frame(), actual.frame());
        // transfer_id / edge_ordinal are recorder delivery identities allocated
        // from emulator power-on, not ROM state: S2 QueueDMATransfer keeps only
        // the per-frame-rewound VDP_Command_Buffer_Slot
        // (docs/s2disasm/s2.asm:1713, drained by ProcessDMAQueue at
        // docs/s2disasm/s2.asm:1769), S1 V-blank writes VRAM unconditionally
        // with no queue (docs/s1disasm/sonic.asm:831), and S3K's DMA_queue_slot
        // is rewound the same way (docs/skdisasm/s3.asm:1831 and :1881).
        // Compare their relative structure, per segment origin.
        put(fields, "dynamic_art.edges",
                expected.edges().stream()
                        .map(edge -> epoch.expectedEdgeOrdinal(edge.edgeOrdinal()))
                        .toList(),
                actual.edges().stream()
                        .map(edge -> epoch.actualEdgeOrdinal(edge.edgeOrdinal()))
                        .toList());
        put(fields, "dynamic_art.outstanding_transfer_ids",
                epoch.expectedTransferIds(expected.outstandingTransferIds()),
                epoch.actualTransferIds(actual.outstandingTransferIds()));

        int edgeCount = Math.max(expected.edges().size(), actual.edges().size());
        for (int edgeIndex = 0; edgeIndex < edgeCount; edgeIndex++) {
            DynamicArtTransfer.ComparisonEdge expectedEdge =
                    edgeIndex < expected.edges().size()
                            ? expected.edges().get(edgeIndex).comparisonView()
                            : null;
            DynamicArtDiagnosticsSnapshot.Edge actualEdge =
                    edgeIndex < actual.edges().size()
                            ? actual.edges().get(edgeIndex)
                            : null;
            String prefix = "dynamic_art.edge[" + edgeIndex + "].";
            put(fields, prefix + "present",
                    expectedEdge != null, actualEdge != null);
            if (expectedEdge == null || actualEdge == null) {
                continue;
            }
            put(fields, prefix + "edge_ordinal",
                    epoch.expectedEdgeOrdinal(expectedEdge.edgeOrdinal()),
                    epoch.actualEdgeOrdinal(actualEdge.edgeOrdinal()));
            put(fields, prefix + "transfer_id",
                    epoch.expectedTransferId(expectedEdge.transferId()),
                    epoch.actualTransferId(actualEdge.transferId()));
            put(fields, prefix + "phase",
                    expectedEdge.phase(), actualEdge.phase());
            put(fields, prefix + "owner",
                    expectedEdge.owner(), actualEdge.owner());
            // The engine records where the transfer was submitted: an edge
            // for work queued while no comparison window was open (a run gap
            // between two recorded segments) reports "run_gap", exactly as the
            // recorder does.
            put(fields, prefix + "submission_origin",
                    expectedEdge.submissionOrigin(),
                    actualEdge.submissionOrigin());
            put(fields, prefix + "mapping_frame",
                    expectedEdge.mappingFrame(), actualEdge.mappingFrame());
            put(fields, prefix + "logical_frame",
                    expectedEdge.logicalFrame(), actualEdge.logicalFrame());
            put(fields, prefix + "logical_edge_index",
                    expectedEdge.logicalEdgeIndex(), actualEdge.logicalEdgeIndex());
            put(fields, prefix + "publication_frame",
                    expectedEdge.publicationFrame(), actualEdge.publicationFrame());
            put(fields, prefix + "terminal_forwarded",
                    expectedEdge.terminalForwarded(), actualEdge.terminalForwarded());
            compareRequests(fields, prefix,
                    expectedEdge.requests(), actualEdge.requests());
        }
        return fields;
    }

    private static void compareRequests(
            Map<String, FieldComparison> fields,
            String edgePrefix,
            List<DynamicArtTransfer.Request> expected,
            List<DynamicArtDiagnosticsSnapshot.Request> actual) {
        put(fields, edgePrefix + "request_count",
                expected.size(), actual.size());
        int requestCount = Math.max(expected.size(), actual.size());
        for (int requestIndex = 0; requestIndex < requestCount; requestIndex++) {
            DynamicArtTransfer.Request expectedRequest =
                    requestIndex < expected.size() ? expected.get(requestIndex) : null;
            DynamicArtDiagnosticsSnapshot.Request actualRequest =
                    requestIndex < actual.size() ? actual.get(requestIndex) : null;
            String prefix = edgePrefix + "request[" + requestIndex + "].";
            put(fields, prefix + "present",
                    expectedRequest != null, actualRequest != null);
            if (expectedRequest == null || actualRequest == null) {
                continue;
            }
            put(fields, prefix + "rom_source_address",
                    expectedRequest.romSourceAddress(),
                    actualRequest.romSourceAddress());
            put(fields, prefix + "source_tile_index",
                    expectedRequest.sourceTileIndex(),
                    actualRequest.sourceTileIndex());
            put(fields, prefix + "ram_source_address",
                    expectedRequest.ramSourceAddress(),
                    actualRequest.ramSourceAddress());
            put(fields, prefix + "vram_destination",
                    expectedRequest.vramDestination(),
                    actualRequest.vramDestination());
            put(fields, prefix + "byte_length",
                    expectedRequest.byteLength(),
                    actualRequest.byteLength());
        }
    }

    private static void put(
            Map<String, FieldComparison> fields,
            String name,
            Object expected,
            Object actual) {
        String expectedText = String.valueOf(expected);
        String actualText = String.valueOf(actual);
        Severity severity = expectedText.equals(actualText)
                ? Severity.MATCH : Severity.ERROR;
        fields.put(name, new FieldComparison(
                name, expectedText, actualText, severity,
                severity == Severity.MATCH ? 0 : 1));
    }
}
