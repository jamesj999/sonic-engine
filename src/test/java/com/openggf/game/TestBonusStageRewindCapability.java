package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bonus-stage rewind capability is a per-provider semantic predicate:
 * Gumball and Pachinko (GLOWING_SPHERE) run the plain LevelFrameStep pipeline
 * that the rewind re-simulation stepper can replay, so they are rewindable;
 * the Slot Machine (dedicated uncaptured runtime) and NONE are not.
 */
class TestBonusStageRewindCapability {

    private static final class FakeCoordinator extends AbstractBonusStageCoordinator {
        @Override public BonusStageType selectBonusStage(int ringCount) { return BonusStageType.NONE; }
        @Override public int getZoneId(BonusStageType type) { return 0; }
        @Override public int getMusicId(BonusStageType type) { return -1; }
    }

    @Test
    void noOpProviderDoesNotSupportRewind() {
        assertFalse(NoOpBonusStageProvider.INSTANCE.supportsRewind());
    }

    @Test
    void gumballAndPachinkoSupportRewindSlotsAndNoneDoNot() {
        FakeCoordinator coordinator = new FakeCoordinator();

        coordinator.onEnter(BonusStageType.GUMBALL, null);
        assertTrue(coordinator.supportsRewind(), "Gumball should be rewindable");

        coordinator.onEnter(BonusStageType.GLOWING_SPHERE, null);
        assertTrue(coordinator.supportsRewind(), "Pachinko (GLOWING_SPHERE) should be rewindable");

        coordinator.onEnter(BonusStageType.SLOT_MACHINE, null);
        assertFalse(coordinator.supportsRewind(), "Slots is out of scope until its runtime is snapshotted");

        coordinator.onExit();
        assertFalse(coordinator.supportsRewind(), "NONE (inactive) is not rewindable");
    }
}
