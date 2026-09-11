package com.openggf.trace.replay.runs;

import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;

import java.util.Objects;

/**
 * Immutable structural snapshot consumed by whole-run playback policy.
 * It deliberately contains no mutable gameplay owner or trace comparison row.
 */
public record RunPlaybackObservation(
        GameMode mode,
        int sharedBk2Cursor,
        long admittedStepOrdinal,
        LevelIdentity level,
        boolean initialTitleCardPending,
        BonusIdentity bonus,
        Integer specialStageIndex,
        boolean productionOpen,
        boolean currentSegmentExhausted,
        int destinationRowsConsumed,
        boolean lagOnlySameLevelContinuation,
        long timingScheduleGeneration,
        long dynamicArtGeneration) {

    public RunPlaybackObservation {
        mode = Objects.requireNonNull(mode, "mode");
        if (sharedBk2Cursor < 0) {
            throw new IllegalArgumentException("sharedBk2Cursor must be non-negative");
        }
        if (admittedStepOrdinal < 0) {
            throw new IllegalArgumentException("admittedStepOrdinal must be non-negative");
        }
    }

    /**
     * Whether {@code mode} is inside the single ROM game mode a recorded
     * {@code special_stage} segment represents.
     *
     * <p>A run recorder cuts that segment on the raw ROM byte — it opens on the
     * first {@code Game_Mode == GameModeID_SpecialStage} frame and closes on the
     * first frame that is no longer it. The ROM keeps that mode for far more
     * than the stage proper: in S2, {@code SS_MainLoop} leaves its object loop
     * when {@code SS_Check_Rings_flag} rises, and the emerald/perfect
     * accounting, {@code Pal_FadeToWhite}, the results-screen build and the
     * whole {@code Obj6F} tally loop below it all still run under
     * {@code GameModeID_SpecialStage}; {@code Game_Mode} is only rewritten by
     * the {@code move.b #GameModeID_Level,(Game_Mode).w} at the very end
     * (docs/s2disasm/s2.asm:6721-6800). S1 has the same shape —
     * {@code GM_Special} owns the results screen through
     * {@code sonic.asm:3419-3421}.
     *
     * <p>The engine splits that one ROM mode into {@code SPECIAL_STAGE} plus
     * {@code SPECIAL_STAGE_RESULTS}, so segment ownership must accept either;
     * a bare {@code == SPECIAL_STAGE} test reads the engine's internal
     * boundary as a premature exit from the recorded segment.
     */
    public static boolean insideRecordedSpecialStageMode(GameMode mode) {
        return mode == GameMode.SPECIAL_STAGE
                || mode == GameMode.SPECIAL_STAGE_RESULTS;
    }

    /** Typed identity assigned by the production level-load lifecycle. */
    public record LevelIdentity(
            long loadGeneration,
            int progressionZone,
            int romZone,
            int act) {
        public LevelIdentity {
            if (loadGeneration < 0) {
                throw new IllegalArgumentException("loadGeneration must be non-negative");
            }
        }
    }

    /** Active bonus-stage identity, separate from the coarse game mode. */
    public record BonusIdentity(int romZone, int act, BonusStageType type) {
        public BonusIdentity {
            type = Objects.requireNonNull(type, "type");
        }
    }
}
