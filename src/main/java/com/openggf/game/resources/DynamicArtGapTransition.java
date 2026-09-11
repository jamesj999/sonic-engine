package com.openggf.game.resources;

import java.util.List;
import java.util.Objects;

/** Immutable read-only production transition recorded between compared segments. */
public record DynamicArtGapTransition(
        GapEdge edge,
        List<Long> beforeOutstandingTransferIds,
        List<Long> afterOutstandingTransferIds) {

    public DynamicArtGapTransition {
        edge = Objects.requireNonNull(edge, "edge");
        beforeOutstandingTransferIds =
                List.copyOf(beforeOutstandingTransferIds);
        afterOutstandingTransferIds =
                List.copyOf(afterOutstandingTransferIds);
    }

    /**
     * Run-wide gap edge without segment-local cursor fields.
     *
     * <p>{@code unannouncedRowsAtEmit} is {@code DynamicArtLifecycleService}'s
     * count of movie rows that passed with no row announced, taken when the
     * edge was emitted and including the edge's own row when that row was
     * itself unannounced. Where the shared cursor is frozen across a
     * transition gap, {@code movieLogicalFrame} is the same stale row for
     * every edge in the gap; the difference between this count and the same
     * count at the gap's end says how many rows the stamp is late by.
     */
    public record GapEdge(
            long edgeOrdinal,
            long transferId,
            String phase,
            String owner,
            int mappingFrame,
            int movieLogicalFrame,
            int gapEdgeIndex,
            int unannouncedRowsAtEmit,
            List<DynamicArtDiagnosticsSnapshot.Request> requests) {
        public GapEdge {
            requests = List.copyOf(requests);
        }
    }
}
