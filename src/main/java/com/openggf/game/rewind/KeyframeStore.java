package com.openggf.game.rewind;

import java.util.Optional;

/**
 * Frame-keyed snapshot store. v1 keeps captures in memory; v2 will swap
 * in a bounded ring + disk spill backing.
 */
public interface KeyframeStore {

    /** Stores a snapshot at the given frame, replacing any existing one. */
    void put(int frame, CompositeSnapshot snapshot);

    /**
     * Returns the latest stored entry at frame F or earlier, or empty if
     * no entry is at or before F.
     */
    Optional<Entry> latestAtOrBefore(int frame);

    /** Earliest-stored frame, or {@code -1} if empty. */
    int earliestFrame();

    /** Removes all stored keyframes. */
    void clear();

    /** Removes stored keyframes strictly after {@code frame}. */
    void discardAfter(int frame);

    /** Removes stored keyframes strictly before {@code frame}. */
    void discardBefore(int frame);

    /** Stored entry record. */
    record Entry(int frame, CompositeSnapshot snapshot) {}
}
