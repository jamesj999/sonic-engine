package com.openggf.game.resources;

/** Game-owned mapping from semantic ROM loops to PLC service and preparation. */
public interface PlcLifecycleService extends QueueDiagnosticsProvider {
    void serviceVBlank(PlcLifecyclePhase phase);

    boolean hasPreparationBoundary(PlcLifecyclePhase phase);

    /**
     * Whether this service's loop-tail arm is itself submitted as hardware
     * timing work, so its visibility is decided by that job's readiness.
     *
     * <p>A service that answers {@code true} is never additionally held by the
     * replay row-shape classification: the two would stack, and the submitted
     * job is the stronger statement -- it names the exact descriptor being
     * armed and fails loudly when the engine and the recording disagree about
     * which one it is.
     */
    default boolean ownsTimedLoopTailArm() {
        return false;
    }

    void prepareAfterLoop(PlcLifecyclePhase phase);
}
