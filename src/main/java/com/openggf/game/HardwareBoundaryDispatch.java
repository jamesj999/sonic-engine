package com.openggf.game;

import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingBoundaryObserver;
import com.openggf.game.timing.HardwareTimingService;

import java.util.Objects;

/**
 * The single definition of what traversing a hardware service boundary means.
 *
 * <p>A boundary is not just {@link HardwareTimingService#service}: the runtime art
 * coordinator runs its module state step <em>before</em> the ledger is serviced and
 * completes physical retirement <em>after</em> it. The pre-step models LevelLoop's tail
 * call to {@code Process_Kos_Module_Queue} (docs/skdisasm/sonic3k.asm:7908) reaching the
 * next iteration's {@code Process_Kos_Queue} (7887) across {@code Wait_VSync} (7888).
 * Dropping it starves module readiness — no module ever retires — so every caller that
 * models a boundary must route through here rather than hand-rolling the sequence.
 *
 * @see com.openggf.LevelFrameStep
 * @see com.openggf.game.session.GameplayModeContext#serviceHardwareTimingBoundary
 */
public final class HardwareBoundaryDispatch {

    private HardwareBoundaryDispatch() {
    }

    /**
     * Traverses {@code boundary} in the canonical production order.
     *
     * <p>{@code PRE_MAIN_LOOP} notifies the observer ahead of the coordinator's
     * post-service step; every other boundary notifies after it.
     */
    public static void serviceBoundary(
            HardwareServiceBoundary boundary,
            RuntimeArtCoordinator runtimeArtCoordinator,
            HardwareTimingService hardwareTiming,
            HardwareTimingBoundaryObserver observer) {
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(runtimeArtCoordinator, "runtimeArtCoordinator");
        Objects.requireNonNull(hardwareTiming, "hardwareTiming");
        HardwareTimingBoundaryObserver notify = observer != null
                ? observer
                : HardwareTimingBoundaryObserver.NO_OP;

        runtimeArtCoordinator.beforeTimingService(boundary);
        hardwareTiming.service(boundary);
        if (boundary == HardwareServiceBoundary.PRE_MAIN_LOOP) {
            notify.onBoundary(boundary);
        }
        runtimeArtCoordinator.afterTimingService(boundary);
        if (boundary != HardwareServiceBoundary.PRE_MAIN_LOOP) {
            notify.onBoundary(boundary);
        }
    }
}
