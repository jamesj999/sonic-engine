package com.openggf.trace.replay.runs;

import com.openggf.game.BonusStageType;

import java.util.Objects;

/**
 * Immutable proof of which structural owners were installed for a destination
 * row. A receipt carries identity and clock ownership, never gameplay state.
 */
public record DestinationAdmissionReceipt(
        int segmentIndex,
        InputClock inputClock,
        int absoluteBk2Row,
        int rowsConsumed,
        DestinationIdentity identity,
        long loadGeneration,
        long timingScheduleGeneration,
        long dynamicArtGeneration,
        TraceRunReplayWalker.SegmentExecutionPolicy executionPolicy) {

    public DestinationAdmissionReceipt(
            int segmentIndex,
            InputClock inputClock,
            int absoluteBk2Row,
            int rowsConsumed,
            DestinationIdentity identity,
            long loadGeneration,
            long timingScheduleGeneration,
            long dynamicArtGeneration) {
        this(segmentIndex, inputClock, absoluteBk2Row, rowsConsumed, identity,
                loadGeneration, timingScheduleGeneration, dynamicArtGeneration,
                inputClock == InputClock.SPECIAL_LOCAL
                        ? TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL
                        : TraceRunReplayWalker.SegmentExecutionPolicy.GAMEPLAY);
    }

    public DestinationAdmissionReceipt {
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be non-negative");
        }
        inputClock = Objects.requireNonNull(inputClock, "inputClock");
        if (absoluteBk2Row < 0) {
            throw new IllegalArgumentException("absoluteBk2Row must be non-negative");
        }
        if (rowsConsumed < 0 || rowsConsumed > 1) {
            throw new IllegalArgumentException("rowsConsumed must be 0 or 1");
        }
        identity = Objects.requireNonNull(identity, "identity");
        executionPolicy = Objects.requireNonNull(
                executionPolicy, "executionPolicy");
        if (identity instanceof LevelIdentity && loadGeneration < 0) {
            throw new IllegalArgumentException(
                    "level admission requires a load generation");
        }
    }

    public enum InputClock {
        SHARED,
        SPECIAL_LOCAL
    }

    public sealed interface DestinationIdentity permits
            LevelIdentity, LevelPresentationIdentity, BonusIdentity,
            SpecialStageIdentity {
    }

    public record LevelIdentity(
            int progressionZone, int romZone, int act)
            implements DestinationIdentity {
    }

    /** Level identity retained while presentation owns the physical clock. */
    public record LevelPresentationIdentity(
            int progressionZone, int romZone, int act)
            implements DestinationIdentity {
    }

    public record BonusIdentity(
            int romZone, int act, BonusStageType type)
            implements DestinationIdentity {
        public BonusIdentity {
            type = Objects.requireNonNull(type, "type");
        }
    }

    public record SpecialStageIdentity(int stageIndex)
            implements DestinationIdentity {
        public SpecialStageIdentity {
            if (stageIndex < 0) {
                throw new IllegalArgumentException("stageIndex must be non-negative");
            }
        }
    }
}
