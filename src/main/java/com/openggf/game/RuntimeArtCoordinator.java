package com.openggf.game;

import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.resources.QueueDiagnosticsProvider;
import com.openggf.game.timing.HardwareServiceBoundary;

/**
 * Game-owned coordinator for runtime art work submitted to hardware timing.
 *
 * <p>Shared session and frame code owns only this boundary. Concrete queue
 * implementations remain inside the game module which creates the
 * coordinator.
 */
public interface RuntimeArtCoordinator extends QueueDiagnosticsProvider {
    RuntimeArtCoordinator NONE = new RuntimeArtCoordinator() {
    };

    /**
     * Runs before the timing ledger is serviced at a production boundary, for
     * coordinator work the ROM performs in the previous loop iteration's tail.
     */
    default void beforeTimingService(HardwareServiceBoundary boundary) {
    }

    default void afterTimingService(HardwareServiceBoundary boundary) {
    }

    /**
     * Defers only a new production submission from a held loop tail. Existing
     * hardware work may still retire at that tail.
     */
    default void deferProductionSubmissionForHeldLoopTail() {
    }

    /**
     * Declares that the producer running right now publishes its module-queue
     * parents after this iteration's module state step, so their first direct
     * child belongs to the following loop.
     */
    default void deferProductionFirstChildForLateProducer() {
    }

    /** Defers only the child handoff owned by a closure consuming a held tail. */
    default void deferProductionSubmissionForHeldLoopTailClosure() {
    }

    /** Completes a held-tail closure after its direct-FIFO boundary. */
    default void finishHeldLoopTailClosure() {
    }

    /**
     * Returns whether this game owns the hardware queue tail on a held
     * level-counter row. Such a row skips the ordinary object/physics body but
     * can still run the ROM's module and direct-FIFO service boundaries.
     */
    default boolean ownsHeldLevelCounterHardwareTail() {
        return false;
    }

    /**
     * Runs the loop tail of an iteration that absorbed a lag V-blank, on the
     * closure that consumed it.
     *
     * <p>{@code VBlank_Lag} (docs/s1disasm/sonic.asm:709) fires inside an
     * iteration that has not yet reached the loop top's re-arm (:3000), so that
     * iteration's {@code RunPLC} (:3032) still runs before the row is sampled --
     * a V-blank-only closure has no loop tail of its own but does carry the
     * held one. Whether the tail's arm becomes visible on this row is still the
     * arm owner's decision from its own submitted job's readiness; this call
     * only offers the tail the row the ROM ran it on.
     *
     * <p>Default is a no-op: a coordinator whose loop tail is not hardware-timed
     * has nothing to offer here.
     */
    default void runHeldIterationLoopTail() {
    }

    default void registerRewindAdapters(RewindRegistry registry) {
    }

    default void deregisterRewindAdapters(RewindRegistry registry) {
    }

    default void resetForMissingSnapshot() {
    }
}
