package com.openggf.game.sonic3k;

import com.openggf.game.*;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBonusStageLifecycle {

    @Test
    void testSelectBonusStage_ringFormula() {
        // ROM loc_2D47E dispatch (sonic3k.asm lines 61886-61912):
        //   remainder 0 -> SLOTS ($1500)
        //   remainder 1 -> PACHINKO / GLOWING_SPHERE ($1400)
        //   remainder 2 -> GUMBALL ($1300)
        var coordinator = new Sonic3kBonusStageCoordinator();
        assertEquals(BonusStageType.NONE, coordinator.selectBonusStage(19));
        assertEquals(BonusStageType.SLOT_MACHINE, coordinator.selectBonusStage(20));
        assertEquals(BonusStageType.SLOT_MACHINE, coordinator.selectBonusStage(34));
        assertEquals(BonusStageType.GLOWING_SPHERE, coordinator.selectBonusStage(35));
        assertEquals(BonusStageType.GLOWING_SPHERE, coordinator.selectBonusStage(49));
        assertEquals(BonusStageType.GUMBALL, coordinator.selectBonusStage(50));
        assertEquals(BonusStageType.GUMBALL, coordinator.selectBonusStage(64));
        assertEquals(BonusStageType.SLOT_MACHINE, coordinator.selectBonusStage(65));
    }

    @Test
    void testZoneIdMapping() {
        var coordinator = new Sonic3kBonusStageCoordinator();
        assertEquals(0x1300, coordinator.getZoneId(BonusStageType.GUMBALL));
        assertEquals(0x1400, coordinator.getZoneId(BonusStageType.GLOWING_SPHERE));
        assertEquals(0x1500, coordinator.getZoneId(BonusStageType.SLOT_MACHINE));
        assertEquals(-1, coordinator.getZoneId(BonusStageType.NONE));
    }

    @Test
    void testMusicIdMapping() {
        var coordinator = new Sonic3kBonusStageCoordinator();
        assertEquals(0x1E, coordinator.getMusicId(BonusStageType.GUMBALL));
        assertEquals(0x1B, coordinator.getMusicId(BonusStageType.GLOWING_SPHERE));
        assertEquals(0x1D, coordinator.getMusicId(BonusStageType.SLOT_MACHINE));
    }

    @Test
    void pachinkoBootstrapObjectIsOwnedByTheS3kBonusStageProvider() {
        var coordinator = new Sonic3kBonusStageCoordinator();

        BonusStageProvider.BootstrapObject bootstrap =
                coordinator.bootstrapObject(BonusStageType.GLOWING_SPHERE);

        assertNotNull(bootstrap);
        assertEquals(0x78, bootstrap.spawn().x());
        assertEquals(0x0F30, bootstrap.spawn().y());
        assertEquals(Sonic3kObjectIds.PACHINKO_ENERGY_TRAP, bootstrap.spawn().objectId());
        assertEquals(PachinkoEnergyTrapObjectInstance.class, bootstrap.objectType());
        assertInstanceOf(PachinkoEnergyTrapObjectInstance.class, bootstrap.create());
    }

    @Test
    void nonPachinkoBonusStagesDoNotDeclareABootstrapObject() {
        var coordinator = new Sonic3kBonusStageCoordinator();

        assertNull(coordinator.bootstrapObject(BonusStageType.GUMBALL));
        assertNull(coordinator.bootstrapObject(BonusStageType.SLOT_MACHINE));
        assertNull(coordinator.bootstrapObject(BonusStageType.NONE));
        assertNull(NoOpBonusStageProvider.INSTANCE.bootstrapObject(BonusStageType.GLOWING_SPHERE));
    }

    @Test
    void testEntryExitLifecycle() {
        var coordinator = new Sonic3kBonusStageCoordinator();
        var savedState = new BonusStageState(
                0x0001, 0x0001, 50, 0, 1, 0,
                4, 0,
                0x100, 0x200, 0x80, 0x100,
                (byte) 0x0C, (byte) 0x0E, 0x300,
                0L
        );

        coordinator.onEnter(BonusStageType.GUMBALL, savedState);
        assertEquals(BonusStageType.GUMBALL, coordinator.getActiveType());
        assertFalse(coordinator.isStageComplete());
        assertSame(savedState, coordinator.getSavedState());

        coordinator.addRings(30);
        coordinator.addRings(20);

        coordinator.requestExit();
        assertTrue(coordinator.isStageComplete());

        var rewards = coordinator.getRewards();
        assertEquals(50, rewards.rings());

        coordinator.onExit();
        assertEquals(BonusStageType.NONE, coordinator.getActiveType());
        assertFalse(coordinator.isStageComplete());
    }

    @Test
    void testNoOpProvider() {
        var noop = NoOpBonusStageProvider.INSTANCE;
        assertFalse(noop.hasBonusStages());
        assertEquals(BonusStageType.NONE, noop.selectBonusStage(100));
        assertFalse(noop.isStageComplete());
        assertNull(noop.getSavedState());
    }
}

