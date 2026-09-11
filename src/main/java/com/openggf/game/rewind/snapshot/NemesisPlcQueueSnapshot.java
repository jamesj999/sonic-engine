package com.openggf.game.rewind.snapshot;

import java.util.List;
import java.util.Objects;

/** Immutable rewind state for a logical S1/S2 Nemesis PLC service queue. */
public record NemesisPlcQueueSnapshot(
        Entry activeEntry,
        List<Entry> queuedEntries) {

    public NemesisPlcQueueSnapshot {
        queuedEntries = List.copyOf(Objects.requireNonNull(queuedEntries, "queuedEntries"));
    }

    /** A queued Nemesis source and its logical decompression progress. */
    public record Entry(
            int sourceAddress,
            int destinationTile,
            int totalPatterns,
            int remainingPatterns) {
    }
}
