package com.openggf.game.timing;

/** Session-owned notification emitted after a production hardware service boundary. */
@FunctionalInterface
public interface HardwareTimingBoundaryObserver {
    HardwareTimingBoundaryObserver NO_OP = boundary -> {
    };

    void onBoundary(HardwareServiceBoundary boundary);
}
