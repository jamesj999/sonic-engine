package com.openggf.game.timing;

/**
 * Immutable, self-contained rewind state for a hardware-work preparation.
 *
 * <p>Recreation must return a new preparation at the captured state on every
 * call. It must not return or mutate the preparation from which this snapshot
 * was captured.
 */
public interface HardwareWorkPreparationSnapshot {
    HardwareWorkPreparation recreatePreparation();
}
