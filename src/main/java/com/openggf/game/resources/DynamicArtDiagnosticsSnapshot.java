package com.openggf.game.resources;

import java.util.List;
import java.util.Objects;

/** Immutable production-side player dynamic-art diagnostics for one row. */
public record DynamicArtDiagnosticsSnapshot(
        int frame,
        List<Edge> edges,
        List<Long> outstandingTransferIds,
        long deliverySerial,
        long segmentGeneration,
        boolean published) {

    private static final DynamicArtDiagnosticsSnapshot EMPTY =
            new DynamicArtDiagnosticsSnapshot(
                    -1, List.of(), List.of(), 0, 0, false);

    public DynamicArtDiagnosticsSnapshot(
            int frame,
            List<Edge> edges,
            List<Long> outstandingTransferIds) {
        this(frame, edges, outstandingTransferIds, 0, 0, true);
    }

    public DynamicArtDiagnosticsSnapshot {
        if (frame < -1) {
            throw new IllegalArgumentException("frame must be at least -1");
        }
        if (deliverySerial < 0) {
            throw new IllegalArgumentException(
                    "deliverySerial must be nonnegative");
        }
        if (segmentGeneration < 0) {
            throw new IllegalArgumentException(
                    "segmentGeneration must be nonnegative");
        }
        if (!published && frame != -1) {
            throw new IllegalArgumentException(
                    "unpublished snapshot must not identify a row");
        }
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        outstandingTransferIds =
                List.copyOf(Objects.requireNonNull(
                        outstandingTransferIds, "outstandingTransferIds"));
    }

    public static DynamicArtDiagnosticsSnapshot empty() {
        return EMPTY;
    }

    public static DynamicArtDiagnosticsSnapshot empty(long deliverySerial) {
        return new DynamicArtDiagnosticsSnapshot(
                -1, List.of(), List.of(), deliverySerial, 0, false);
    }

    public static DynamicArtDiagnosticsSnapshot unpublished(
            long deliverySerial,
            long segmentGeneration) {
        return new DynamicArtDiagnosticsSnapshot(
                -1, List.of(), List.of(), deliverySerial,
                segmentGeneration, false);
    }

    public record Request(
            int romSourceAddress,
            int sourceTileIndex,
            int ramSourceAddress,
            int vramDestination,
            int byteLength) {
        public Request {
            boolean rom = romSourceAddress >= 0
                    && sourceTileIndex >= 0 && ramSourceAddress == -1;
            boolean ram = romSourceAddress == -1
                    && sourceTileIndex == -1 && ramSourceAddress >= 0;
            if (!rom && !ram) {
                throw new IllegalArgumentException(
                        "request must select exactly one source domain");
            }
            if (vramDestination < 0 || vramDestination > 0xFFFF) {
                throw new IllegalArgumentException(
                        "vramDestination outside unsigned word range");
            }
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
        }

        public static Request rom(
                int romSourceAddress,
                int sourceTileIndex,
                int vramDestination,
                int byteLength) {
            return new Request(romSourceAddress, sourceTileIndex, -1,
                    vramDestination, byteLength);
        }

        public static Request ram(
                int ramSourceAddress,
                int vramDestination,
                int byteLength) {
            return new Request(-1, -1, ramSourceAddress,
                    vramDestination, byteLength);
        }
    }

    public record Edge(
            long edgeOrdinal,
            long transferId,
            String phase,
            String owner,
            int mappingFrame,
            int logicalFrame,
            int logicalEdgeIndex,
            int publicationFrame,
            boolean terminalForwarded,
            List<Request> requests,
            String submissionOrigin) {

        /**
         * Legacy shape for callers that do not model where the transfer was
         * submitted; defaults to an in-segment submission.
         */
        public Edge(long edgeOrdinal, long transferId, String phase,
                    String owner, int mappingFrame, int logicalFrame,
                    int logicalEdgeIndex, int publicationFrame,
                    boolean terminalForwarded, List<Request> requests) {
            this(edgeOrdinal, transferId, phase, owner, mappingFrame,
                    logicalFrame, logicalEdgeIndex, publicationFrame,
                    terminalForwarded, requests, "segment");
        }

        public Edge {
            if (edgeOrdinal < 0 || transferId < 0 || mappingFrame < 0
                    || logicalFrame < 0 || logicalEdgeIndex < 0
                    || publicationFrame < 0) {
                throw new IllegalArgumentException(
                        "dynamic-art edge numeric fields must be nonnegative");
            }
            if (!"submitted".equals(phase) && !"completed".equals(phase)) {
                throw new IllegalArgumentException("unknown phase: " + phase);
            }
            owner = Objects.requireNonNull(owner, "owner");
            requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
            submissionOrigin = Objects.requireNonNull(
                    submissionOrigin, "submissionOrigin");
            if (!"segment".equals(submissionOrigin)
                    && !"run_gap".equals(submissionOrigin)) {
                throw new IllegalArgumentException(
                        "unknown submission origin: " + submissionOrigin);
            }
            if ("submitted".equals(phase) && requests.isEmpty()) {
                throw new IllegalArgumentException(
                        "submitted edge requires at least one request");
            }
        }
    }
}
