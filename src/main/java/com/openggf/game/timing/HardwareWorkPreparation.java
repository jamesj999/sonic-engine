package com.openggf.game.timing;

/**
 * Runtime-owned preparation for a hardware job.
 *
 * <p>One call to {@link #stepOneWorkUnit()} consumes one integer work unit.
 * Implementations define that unit from ROM decoder or queue semantics; elapsed
 * host time is never an input.
 *
 * <p>Once {@link #isPrepared()} returns true the preparation is terminal: every
 * later {@link #stepOneWorkUnit()} and {@link #serviceBoundary} call is a no-op,
 * {@link #preparedPayload()} and {@link #snapshot()} keep returning equal bytes,
 * and {@link #restore} is never used on a live instance (hardware timing restores
 * by recreating preparations from their snapshots). {@code HardwareTimingJob}
 * relies on this to share one immutable rewind snapshot per prepared job across
 * checkpoints instead of re-cloning its payload every capture.
 */
public interface HardwareWorkPreparation {
    boolean stepOneWorkUnit();

    boolean isPrepared();

    byte[] preparedPayload();

    HardwareWorkPreparationSnapshot snapshot();

    void restore(HardwareWorkPreparationSnapshot snapshot);

    /**
     * Whether this preparation owns ROM-specific service-boundary ordering.
     * Boundary-driven preparations are not also advanced by the generic budget
     * scheduler.
     */
    default boolean isBoundaryDriven() {
        return false;
    }

    /**
     * Services one production boundary for a boundary-driven preparation.
     *
     * @return whether deterministic preparation state advanced
     */
    default boolean serviceBoundary(HardwareServiceBoundary boundary) {
        return false;
    }
}
