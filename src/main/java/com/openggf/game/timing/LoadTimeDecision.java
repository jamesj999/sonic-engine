package com.openggf.game.timing;

import java.util.Objects;
import java.util.Set;

public record LoadTimeDecision(
        int serviceFrames,
        Set<HardwareServiceBoundary> eligibleBoundaries,
        LoadTimeDecisionSource source,
        String serviceModel) {

    public LoadTimeDecision {
        if (serviceFrames < 0) {
            throw new IllegalArgumentException("serviceFrames must be non-negative");
        }
        eligibleBoundaries = Set.copyOf(Objects.requireNonNull(
                eligibleBoundaries, "eligibleBoundaries"));
        if (serviceFrames > 0 && eligibleBoundaries.isEmpty()) {
            throw new IllegalArgumentException(
                    "positive serviceFrames require an eligible boundary");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(serviceModel, "serviceModel");
    }
}
