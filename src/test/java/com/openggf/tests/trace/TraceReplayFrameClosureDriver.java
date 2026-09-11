package com.openggf.tests.trace;

import com.openggf.level.objects.ObjectManager;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Keeps trace frame driving and rewind-reference validation in one ordered operation.
 */
final class TraceReplayFrameClosureDriver {
    record CurrentLevelContext(ObjectManager objectManager, int zone, int act) {
    }

    private TraceReplayFrameClosureDriver() {
    }

    static int driveGeneral(
            TraceExecutionPhase phase,
            IntSupplier step,
            IntSupplier skip,
            Runnable validateAfterStep) {
        if (phase == TraceExecutionPhase.VBLANK_ONLY) {
            return skip.getAsInt();
        }
        int input = step.getAsInt();
        validateAfterStep.run();
        return input;
    }

    static int driveS3k(
            TraceExecutionPhase phase,
            boolean usePreviousInput,
            IntSupplier step,
            IntSupplier stepUsingPreviousInput,
            IntSupplier skip,
            IntSupplier consumeInputOnly,
            Runnable advancePlayableAnimations,
            Runnable suppressFirstSidekickAnimation,
            Runnable validateAfterStep) {
        if (phase == TraceExecutionPhase.ADVANCE_ONLY) {
            return consumeInputOnly.getAsInt();
        }
        if (phase == TraceExecutionPhase.VBLANK_ONLY
                || phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
            int input = skip.getAsInt();
            if (phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
                advancePlayableAnimations.run();
                validateAfterStep.run();
            }
            return input;
        }
        if (phase == TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD) {
            suppressFirstSidekickAnimation.run();
        }
        int input = (usePreviousInput ? stepUsingPreviousInput : step).getAsInt();
        validateAfterStep.run();
        return input;
    }

    static void validateCurrentObjectManager(
            Supplier<CurrentLevelContext> currentLevelContext,
            String game,
            String traceZone,
            int traceAct,
            int traceIndex,
            TraceFrame frame,
            TraceExecutionPhase phase) {
        CurrentLevelContext current = currentLevelContext.get();
        if (current == null || current.objectManager() == null) {
            return;
        }
        try {
            current.objectManager().validateRewindReferenceClosure();
        } catch (IllegalStateException cause) {
            throw new IllegalStateException(
                    "Invalid rewind reference closure after game=" + game
                            + " currentZone=" + current.zone()
                            + " currentAct=" + current.act()
                            + " traceZone=" + traceZone + " traceAct=" + traceAct
                            + " traceIndex=" + traceIndex + " romFrame=" + frame.frame()
                            + " phase=" + phase,
                    cause);
        }
    }
}
