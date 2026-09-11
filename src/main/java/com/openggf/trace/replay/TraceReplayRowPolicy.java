package com.openggf.trace.replay;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;

import java.util.Objects;

/**
 * Immutable, comparison-only policy for one represented trace row.
 *
 * <p>The policy selects structural replay behavior and BK2 row identity. It
 * intentionally carries no expected physics, queue, object, or art values.
 */
public record TraceReplayRowPolicy(
        int traceIndex,
        int rawFrame,
        TraceExecutionPhase phase,
        int validationBk2Index,
        int appliedBk2Index,
        int appliedPredecessorBk2Index,
        int appliedInputOffset,
        boolean observedVblankCounterAdvance,
        boolean productionPublicationClaim,
        int suppressedClosureCount,
        boolean holdFirstSidekickAnimation,
        boolean postRowPlayablePrefix) {

    public TraceReplayRowPolicy {
        if (traceIndex < 0) {
            throw new IllegalArgumentException("traceIndex must be nonnegative");
        }
        if (rawFrame < 0) {
            throw new IllegalArgumentException("rawFrame must be nonnegative");
        }
        Objects.requireNonNull(phase, "phase");
        if (validationBk2Index < 0 || appliedBk2Index < 0) {
            throw new IllegalArgumentException("BK2 row indices must be nonnegative");
        }
        if (appliedPredecessorBk2Index != appliedBk2Index - 1) {
            throw new IllegalArgumentException(
                    "applied predecessor must immediately precede applied row");
        }
        if (appliedInputOffset != appliedBk2Index - validationBk2Index) {
            throw new IllegalArgumentException(
                    "applied input offset does not match BK2 row indices");
        }
        if (suppressedClosureCount < 0 || suppressedClosureCount > 1) {
            throw new IllegalArgumentException(
                    "one stored row may own at most one suppressed closure");
        }
    }

    /** Resolves the row through the same structural predicates as headless replay. */
    public static TraceReplayRowPolicy resolve(
            TraceData trace, int traceIndex, int validationBk2Index) {
        Objects.requireNonNull(trace, "trace");
        if (traceIndex < 0 || traceIndex >= trace.frameCount()) {
            throw new IndexOutOfBoundsException(
                    "trace row " + traceIndex + " outside 0.." + (trace.frameCount() - 1));
        }
        TraceFrame current = trace.getFrame(traceIndex);
        TraceFrame previous = traceIndex > 0 ? trace.getFrame(traceIndex - 1) : null;
        TraceExecutionPhase phase =
                TraceReplayBootstrap.phaseForReplay(trace, previous, current);
        boolean observedVblank = previous == null
                || previous.vblankCounter() < 0
                || current.vblankCounter() < 0
                || ((current.vblankCounter() - previous.vblankCounter()) & 0xFFFF) != 0;
        return fromResolvedPhase(
                traceIndex,
                current.frame(),
                phase,
                validationBk2Index,
                TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace),
                observedVblank);
    }

    /**
     * True when the row's sample consumed two or more V-blank counter ticks.
     *
     * <p>Comparison-only hardware-timing classification of the recorded row
     * shape, in the same family as
     * {@link com.openggf.trace.TraceExecutionModel#isVblankStarvedRow}. A row
     * with two ticks follows a starved row: the previous iteration overran its
     * V-blank, so that V-blank's counter store lands in this row's interval
     * alongside this row's own. One of the two is the lag V-blank {@code V_Int}
     * takes with {@code v_vblank_routine} still 0
     * (docs/s1disasm/sonic.asm:709), which runs no mode handler and therefore
     * no {@code ProcessPLC_9Tiles} (sonic.asm:1430-1440).
     */
    public static boolean carriesDeferredVblank(TraceData trace, int traceIndex) {
        Objects.requireNonNull(trace, "trace");
        if (traceIndex <= 0 || traceIndex >= trace.frameCount()) {
            return false;
        }
        TraceFrame previous = trace.getFrame(traceIndex - 1);
        TraceFrame current = trace.getFrame(traceIndex);
        if (previous.vblankCounter() < 0 || current.vblankCounter() < 0) {
            return false;
        }
        return ((current.vblankCounter() - previous.vblankCounter()) & 0xFFFF) >= 2;
    }

    /**
     * Projects an already-resolved execution phase into row and input ownership.
     * Package visibility keeps phase derivation centralized in
     * {@link TraceReplayBootstrap} while allowing focused contract tests.
     */
    static TraceReplayRowPolicy fromResolvedPhase(
            int traceIndex,
            int rawFrame,
            TraceExecutionPhase phase,
            int validationBk2Index,
            boolean usePreviousInput,
            boolean observedVblankCounterAdvance) {
        Objects.requireNonNull(phase, "phase");
        boolean gameplayRunning = phase == TraceExecutionPhase.FULL_LEVEL_FRAME
                || phase == TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD;
        int appliedOffset = usePreviousInput && gameplayRunning
                && validationBk2Index > 0 ? -1 : 0;
        int appliedIndex = validationBk2Index + appliedOffset;
        boolean inputOnly = phase == TraceExecutionPhase.ADVANCE_ONLY;
        int closureCount = switch (phase) {
            case VBLANK_ONLY, PLAYABLE_ANIMATION_ONLY -> 1;
            default -> 0;
        };
        return new TraceReplayRowPolicy(
                traceIndex,
                rawFrame,
                phase,
                validationBk2Index,
                appliedIndex,
                appliedIndex - 1,
                appliedOffset,
                observedVblankCounterAdvance,
                !inputOnly,
                closureCount,
                phase == TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD,
                phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY);
    }
}
