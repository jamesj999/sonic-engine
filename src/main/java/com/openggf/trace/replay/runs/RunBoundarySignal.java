package com.openggf.trace.replay.runs;

import com.openggf.game.BonusStageType;

import java.util.Objects;

/**
 * Semantic notification that production lifecycle code already crossed or
 * requested a run boundary. Signals are observations only and never initiate
 * gameplay transitions.
 */
public sealed interface RunBoundarySignal permits
        RunBoundarySignal.BonusRequest,
        RunBoundarySignal.SpecialStageRequest,
        RunBoundarySignal.StageExit,
        RunBoundarySignal.LevelLoaded {

    int physicalBk2Frame();

    record BonusRequest(int physicalBk2Frame, BonusStageType type)
            implements RunBoundarySignal {
        public BonusRequest {
            requireFrame(physicalBk2Frame);
            type = Objects.requireNonNull(type, "type");
        }
    }

    record SpecialStageRequest(int physicalBk2Frame, int stageIndex)
            implements RunBoundarySignal {
        public SpecialStageRequest {
            requireFrame(physicalBk2Frame);
            if (stageIndex < 0) {
                throw new IllegalArgumentException("stageIndex must be non-negative");
            }
        }
    }

    record StageExit(int physicalBk2Frame) implements RunBoundarySignal {
        public StageExit {
            requireFrame(physicalBk2Frame);
        }
    }

    record LevelLoaded(
            int physicalBk2Frame,
            RunLevelLoadCause cause,
            RunPlaybackObservation.LevelIdentity identity)
            implements RunBoundarySignal {
        public LevelLoaded {
            requireFrame(physicalBk2Frame);
            cause = Objects.requireNonNull(cause, "cause");
            identity = Objects.requireNonNull(identity, "identity");
        }
    }

    private static void requireFrame(int physicalBk2Frame) {
        if (physicalBk2Frame < 0) {
            throw new IllegalArgumentException(
                    "physicalBk2Frame must be non-negative");
        }
    }
}
