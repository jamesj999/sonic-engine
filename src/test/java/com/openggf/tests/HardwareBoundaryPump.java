package com.openggf.tests;

import com.openggf.game.HardwareBoundaryDispatch;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingBoundaryObserver;
import com.openggf.game.timing.HardwareTimingService;

/**
 * Shared entry point for tests that drive hardware service boundaries by hand.
 *
 * <p>Every method delegates to {@link HardwareBoundaryDispatch}, the same sequence
 * {@code LevelFrameStep.serviceBoundary} runs in production. Tests must not compose
 * {@code timing.service(...)} with the coordinator hooks themselves: a model that omits
 * {@link RuntimeArtCoordinator#beforeTimingService} never advances the Kos module state
 * step (docs/skdisasm/sonic3k.asm:7908), so no module retires and readiness starves.
 */
public final class HardwareBoundaryPump {

    private HardwareBoundaryPump() {
    }

    /** Services a boundary against the active gameplay mode's own collaborators. */
    public static void service(HardwareServiceBoundary boundary) {
        TestEnvironment.activeGameplayMode().serviceHardwareTimingBoundary(boundary);
    }

    /**
     * Services a boundary against a hand-wired timing service and coordinator, for
     * unit contexts that build their own queues instead of a gameplay mode.
     */
    public static void service(
            HardwareTimingService timing,
            RuntimeArtCoordinator coordinator,
            HardwareServiceBoundary boundary) {
        HardwareBoundaryDispatch.serviceBoundary(
                boundary, coordinator, timing, HardwareTimingBoundaryObserver.NO_OP);
    }
}
