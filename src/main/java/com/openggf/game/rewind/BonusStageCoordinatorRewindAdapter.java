package com.openggf.game.rewind;

import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.AbstractBonusStageCoordinator.BonusStageAccumulatorSnapshot;

import java.util.Objects;

/**
 * Rewind adapter for a bonus stage coordinator's reward accumulators
 * (rings/lives/shield). These are mutated by item objects across frames but
 * are not part of any object's own captured state, so without this adapter a
 * backward seek would leave the pending reward totals stale relative to the
 * rolled-back item objects. Registered by {@code GameplayModeContext} only
 * while a rewind-supported bonus stage is active.
 */
public final class BonusStageCoordinatorRewindAdapter
        implements RewindSnapshottable<BonusStageAccumulatorSnapshot> {

    public static final String KEY = "bonus-stage-coordinator";

    private final AbstractBonusStageCoordinator coordinator;

    public BonusStageCoordinatorRewindAdapter(AbstractBonusStageCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public BonusStageAccumulatorSnapshot capture() {
        return coordinator.captureAccumulators();
    }

    @Override
    public void restore(BonusStageAccumulatorSnapshot snapshot) {
        coordinator.restoreAccumulators(snapshot);
    }

    @Override
    public void resetForMissingSnapshot() {
        coordinator.restoreAccumulators(
                new AbstractBonusStageCoordinator.BonusStageAccumulatorSnapshot(0, 0, null));
    }
}
