package com.openggf.trace.replay.runs;

import com.openggf.level.LevelManager;

import java.util.Objects;
import java.util.Optional;

/**
 * Gameplay-session-owned structural receipt for completed level loads.
 * It records what production already loaded and why; it cannot request a load
 * or carry trace/gameplay values into the engine.
 */
public final class RunLevelLoadTracker {
    public record Receipt(
            RunLevelLoadCause cause,
            RunPlaybackObservation.LevelIdentity identity) {
        public Receipt {
            cause = Objects.requireNonNull(cause, "cause");
            identity = Objects.requireNonNull(identity, "identity");
        }
    }

    private RunLevelLoadCause nextCause = RunLevelLoadCause.ORDINARY;
    private long lastCompletedLoadGeneration = -1;
    private Receipt latest;
    private RunPlaybackObservation.LevelIdentity seamlessAdvance;
    private long seamlessAdvanceOrdinal;

    public void prime(LevelManager levelManager) {
        Objects.requireNonNull(levelManager, "levelManager");
        lastCompletedLoadGeneration =
                levelManager.getCompletedProductionLoadGeneration();
        latest = null;
        seamlessAdvance = null;
    }

    /**
     * Records a seamless in-level act advance production has already applied.
     *
     * <p>The ROM performs this advance from inside the level's own background
     * event handler -- {@code AIZ1BGE_Finish} writes
     * {@code move.w #1,(Current_zone_and_act).w} and calls {@code Load_Level}
     * without leaving {@code GameModeID_Level} or re-entering the {@code Level:}
     * routine (docs/skdisasm/sonic3k.asm:104733-104746) -- and the engine
     * mirrors that with an act transition rather than a {@code FULL} load, so
     * the completed production load generation deliberately does not move. The
     * identity therefore has to be published separately from a load receipt.
     */
    public void observeSeamlessAdvance(LevelManager levelManager) {
        Objects.requireNonNull(levelManager, "levelManager");
        if (levelManager.getCurrentLevel() == null) {
            return;
        }
        seamlessAdvance = new RunPlaybackObservation.LevelIdentity(
                generation(),
                levelManager.getCurrentZone(),
                levelManager.getRomZoneId(),
                levelManager.getCurrentAct());
        seamlessAdvanceOrdinal = Math.incrementExact(seamlessAdvanceOrdinal);
    }

    public Optional<RunPlaybackObservation.LevelIdentity> seamlessAdvance() {
        return Optional.ofNullable(seamlessAdvance);
    }

    /** Monotonic count of observed seamless advances; an edge detector only. */
    public long seamlessAdvanceOrdinal() {
        return seamlessAdvanceOrdinal;
    }

    public void markNext(RunLevelLoadCause cause) {
        nextCause = Objects.requireNonNull(cause, "cause");
    }

    public Optional<Receipt> observeLoaded(LevelManager levelManager) {
        Objects.requireNonNull(levelManager, "levelManager");
        Object current = levelManager.getCurrentLevel();
        long completedLoadGeneration =
                levelManager.getCompletedProductionLoadGeneration();
        if (current == null
                || completedLoadGeneration == lastCompletedLoadGeneration) {
            return Optional.empty();
        }
        lastCompletedLoadGeneration = completedLoadGeneration;
        latest = new Receipt(nextCause,
                new RunPlaybackObservation.LevelIdentity(
                        completedLoadGeneration,
                        levelManager.getCurrentZone(),
                        levelManager.getRomZoneId(),
                        levelManager.getCurrentAct()));
        nextCause = RunLevelLoadCause.ORDINARY;
        return Optional.of(latest);
    }

    public Optional<Receipt> latest() {
        return Optional.ofNullable(latest);
    }

    public long generation() {
        return Math.max(0, lastCompletedLoadGeneration);
    }
}
