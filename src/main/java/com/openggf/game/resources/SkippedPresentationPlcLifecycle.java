package com.openggf.game.resources;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Replays only the production PLC boundaries omitted with an initial level
 * presentation. Trace/bootstrap state is deliberately outside this contract.
 */
public final class SkippedPresentationPlcLifecycle {
    private SkippedPresentationPlcLifecycle() {
    }

    public static void drain(
            PlcLifecycleService service,
            PlcLifecyclePhase phase,
            BooleanSupplier busy) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(busy, "busy");
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(service);
        while (busy.getAsBoolean()) {
            runIteration(coordinator, service, phase);
        }
    }

    public static void runIterations(
            PlcLifecycleService service, PlcLifecyclePhase phase, int iterations) {
        Objects.requireNonNull(service, "service");
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be nonnegative");
        }
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(service);
        for (int i = 0; i < iterations; i++) {
            runIteration(coordinator, service, phase);
        }
    }

    private static void runIteration(
            PlcFrameLifecycleCoordinator coordinator,
            PlcLifecycleService service,
            PlcLifecyclePhase phase) {
        coordinator.runLogicalIteration(() -> { }, frame -> {
            if (!frame.claim(phase)) {
                throw new IllegalStateException(
                        "skipped presentation PLC frame was already claimed");
            }
            if (service.hasPreparationBoundary(phase)) {
                frame.prepareAfterLoop(phase);
            }
            return null;
        });
    }
}
