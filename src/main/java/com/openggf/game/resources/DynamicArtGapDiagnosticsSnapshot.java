package com.openggf.game.resources;

import java.util.List;

/** Immutable production evidence exposed to structural gap comparators. */
public record DynamicArtGapDiagnosticsSnapshot(
        int movieLogicalFrame,
        int unannouncedRows,
        List<DynamicArtGapTransition> transitions,
        List<Descriptor> ledger) {

    public DynamicArtGapDiagnosticsSnapshot {
        transitions = List.copyOf(transitions);
        ledger = List.copyOf(ledger);
    }

    public record Descriptor(
            long transferId,
            String owner,
            int mappingFrame,
            String submissionOrigin,
            List<DynamicArtDiagnosticsSnapshot.Request> requests) {

        public Descriptor {
            requests = List.copyOf(requests);
        }
    }
}
