package com.openggf.game;

import java.util.Objects;

/**
 * Immutable capture of OscillationManager state for rewind snapshots.
 * Fields mirror the static fields on OscillationManager. The values[] and
 * deltas[] arrays are defensively copied at construction so a captured
 * snapshot is safe to retain across many frames.
 */
public record OscillationSnapshot(
        int[] values,
        int[] deltas,
        int[] activeSpeeds,
        int[] activeLimits,
        int control,
        int lastFrame,
        int suppressedUpdates) {

    public OscillationSnapshot {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(deltas, "deltas");
        Objects.requireNonNull(activeSpeeds, "activeSpeeds");
        Objects.requireNonNull(activeLimits, "activeLimits");
        // Defensive copy so the record is truly immutable.
        values = values.clone();
        deltas = deltas.clone();
        activeSpeeds = activeSpeeds.clone();
        activeLimits = activeLimits.clone();
    }

    @Override public int[] values()        { return values.clone(); }
    @Override public int[] deltas()        { return deltas.clone(); }
    @Override public int[] activeSpeeds()  { return activeSpeeds.clone(); }
    @Override public int[] activeLimits()  { return activeLimits.clone(); }
}
