package com.openggf.game;

import java.util.logging.Logger;

/**
 * Shared base class for bonus stage lifecycle coordination.
 * Manages entry/exit state, ring persistence, and object communication.
 * Subclasses provide game-specific zone/music mapping.
 * Methods are non-final to allow overrides (e.g., Slots player sprite swap).
 */
public abstract class AbstractBonusStageCoordinator implements BonusStageProvider {

    private static final Logger LOGGER = Logger.getLogger(AbstractBonusStageCoordinator.class.getName());

    private BonusStageState savedState;
    private BonusStageType activeType = BonusStageType.NONE;
    private boolean exitRequested;
    private int ringsCollected;
    private int livesAwarded;
    private ShieldType awardedShield; // null if none

    @Override
    public boolean hasBonusStages() { return true; }

    @Override
    public boolean supportsRewind() {
        return activeType == BonusStageType.GUMBALL
                || activeType == BonusStageType.GLOWING_SPHERE;
    }

    @Override
    public void onEnter(BonusStageType type, BonusStageState savedState) {
        this.savedState = savedState;
        this.activeType = type;
        this.exitRequested = false;
        this.ringsCollected = 0;
        this.livesAwarded = 0;
        this.awardedShield = null;
        LOGGER.info("Entering bonus stage: " + type + " (zone 0x"
                + Integer.toHexString(getZoneId(type)) + ")");
    }

    @Override
    public void onExit() {
        LOGGER.info("Exiting bonus stage: " + activeType
                + " (rings collected: " + ringsCollected + ")");
        activeType = BonusStageType.NONE;
        exitRequested = false;
    }

    @Override
    public void onFrameUpdate() {
        // Default: no per-frame work. Subclasses may override.
    }

    @Override
    public void onDeferredSetupComplete() {
        // Default: no deferred setup. Subclasses may override.
    }

    @Override
    public boolean isStageComplete() { return exitRequested; }

    @Override
    public void requestExit() { exitRequested = true; }

    @Override
    public BonusStageRewards getRewards() {
        boolean basic = false, fire = false, lightning = false, bubble = false;
        if (awardedShield != null) {
            switch (awardedShield) {
                case FIRE -> fire = true;
                case BUBBLE -> bubble = true;
                case LIGHTNING -> lightning = true;
                default -> basic = true;
            }
        }
        return new BonusStageRewards(ringsCollected, livesAwarded,
                basic, fire, lightning, bubble);
    }

    @Override
    public BonusStageState getSavedState() { return savedState; }

    @Override
    public BonusStageType getActiveType() { return activeType; }

    /** Accumulate rings during the bonus stage. Called by gumball item objects. */
    @Override
    public void addRings(int count) { ringsCollected += count; }

    /** Accumulate lives during the bonus stage. */
    @Override
    public void addLife() { livesAwarded++; }

    /**
     * Records the shield awarded by a bonus-stage gumball.
     * ROM awards overwrite any previous shield with the latest pickup,
     * so we track only the most recent. Surfaces as the matching flag in
     * {@link #getRewards()} so {@code doExitBonusStage} can restore it
     * after the level reload clears the player state.
     */
    @Override
    public void setAwardedShield(ShieldType type) {
        this.awardedShield = type;
    }

    /**
     * Immutable capture of the reward accumulators that objects mutate across
     * frames during a bonus stage. Held rewind restores these so a backward
     * seek that un-collects a gumball item rolls the pending reward totals back
     * in lockstep with the item objects' own restored state.
     */
    public record BonusStageAccumulatorSnapshot(int rings, int lives, ShieldType shield) {}

    /** Snapshots the live reward accumulators for rewind capture. */
    public BonusStageAccumulatorSnapshot captureAccumulators() {
        return new BonusStageAccumulatorSnapshot(ringsCollected, livesAwarded, awardedShield);
    }

    /** Restores reward accumulators from a rewind snapshot. */
    public void restoreAccumulators(BonusStageAccumulatorSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.ringsCollected = snapshot.rings();
        this.livesAwarded = snapshot.lives();
        this.awardedShield = snapshot.shield();
    }
}
