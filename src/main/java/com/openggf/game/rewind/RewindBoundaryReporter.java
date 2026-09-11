package com.openggf.game.rewind;

@FunctionalInterface
public interface RewindBoundaryReporter {
    RewindBoundaryReporter NO_OP = boundary -> {
    };

    void markBoundary(RewindBoundary boundary);
}
