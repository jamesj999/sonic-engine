package com.openggf.game.timing;

/**
 * Runtime-owned preparation for a hardware job.
 *
 * <p>One call to {@link #stepOneWorkUnit()} consumes one integer work unit.
 * Implementations define that unit from ROM decoder or queue semantics; elapsed
 * host time is never an input.
 *
 * <p>A live preparation stays reachable through
 * {@code HardwareTimingService.coordinatorPreparation} until its job is claimed,
 * and callers may step or {@link #restore} it there. {@code HardwareTimingJob}
 * therefore re-snapshots an unclaimed job on every capture and shares one
 * immutable snapshot only once the job is claimed and its preparation is no
 * longer exposed. Implementations need not be immutable after
 * {@link #isPrepared()}; the shipped S1 arm and S3K Kosinski preparations
 * happen to be, and throw on live {@link #restore}.
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
