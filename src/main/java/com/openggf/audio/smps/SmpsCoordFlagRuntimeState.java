package com.openggf.audio.smps;

import java.util.Objects;

/**
 * Mutable SMPS coordination-flag state owned by one game session.
 */
public final class SmpsCoordFlagRuntimeState {
    public record Snapshot(int spindashRevCounter) {
    }

    private int spindashRevCounter;

    public int spindashRevCounter() {
        return spindashRevCounter;
    }

    public void setSpindashRevCounter(int value) {
        spindashRevCounter = value;
    }

    public Snapshot snapshot() {
        return new Snapshot(spindashRevCounter);
    }

    public void restore(Snapshot snapshot) {
        spindashRevCounter =
                Objects.requireNonNull(snapshot, "snapshot").spindashRevCounter();
    }

    public void reset() {
        spindashRevCounter = 0;
    }
}
