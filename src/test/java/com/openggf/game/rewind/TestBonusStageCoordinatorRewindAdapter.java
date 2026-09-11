package com.openggf.game.rewind;

import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.BonusStageType;
import com.openggf.game.ShieldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestBonusStageCoordinatorRewindAdapter {

    private static final class FakeCoordinator extends AbstractBonusStageCoordinator {
        @Override public BonusStageType selectBonusStage(int ringCount) { return BonusStageType.NONE; }
        @Override public int getZoneId(BonusStageType type) { return 0; }
        @Override public int getMusicId(BonusStageType type) { return -1; }
    }

    @Test
    void keyIsStable() {
        FakeCoordinator coordinator = new FakeCoordinator();
        BonusStageCoordinatorRewindAdapter adapter = new BonusStageCoordinatorRewindAdapter(coordinator);
        assertEquals("bonus-stage-coordinator", adapter.key());
        assertEquals(BonusStageCoordinatorRewindAdapter.KEY, adapter.key());
    }

    @Test
    void roundTripsRewardAccumulators() {
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.onEnter(BonusStageType.GUMBALL, null);
        BonusStageCoordinatorRewindAdapter adapter = new BonusStageCoordinatorRewindAdapter(coordinator);

        // Capture the empty starting state.
        var floor = adapter.capture();

        // Mutate as item objects would.
        coordinator.addRings(7);
        coordinator.addLife();
        coordinator.setAwardedShield(ShieldType.FIRE);
        assertEquals(7, coordinator.getRewards().rings());

        // Restore rolls the accumulators back to the captured floor.
        adapter.restore(floor);
        assertEquals(0, coordinator.getRewards().rings());
        assertEquals(0, coordinator.getRewards().lives());
        assertNull(coordinator.captureAccumulators().shield());
    }

    @Test
    void resetForMissingSnapshotZeroesAccumulators() {
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.onEnter(BonusStageType.GUMBALL, null);
        BonusStageCoordinatorRewindAdapter adapter = new BonusStageCoordinatorRewindAdapter(coordinator);

        coordinator.addRings(7);
        coordinator.addLife();
        coordinator.setAwardedShield(ShieldType.FIRE);
        assertNotNull(coordinator.captureAccumulators().shield());

        adapter.resetForMissingSnapshot();

        assertEquals(0, coordinator.getRewards().rings());
        assertEquals(0, coordinator.getRewards().lives());
        assertNull(coordinator.captureAccumulators().shield());
    }
}
