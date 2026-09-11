package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.BonusStageState;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameServices;
import com.openggf.graphics.FadeManager;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusPlayer;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotRenderBuffers;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageState;
import com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSlotBonusStageRuntime {

    private HeadlessTestFixture fixture;

    @AfterEach
    void tearDown() {
        fixture = null;
    }

    @Test
    void slotDeferredSetupReplacesMainPlayerAndBuildsLayoutGridInZone15() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x15, 0)
                .build();

        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        fixture.runtime().setActiveBonusStageProvider(coordinator);
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());

        coordinator.onDeferredSetupComplete();

        S3kSlotBonusStageRuntime runtime = coordinator.activeSlotRuntimeForTest();
        assertNotNull(runtime);
        assertTrue(runtime.isInitialized());
        assertInstanceOf(S3kSlotBonusPlayer.class, GameServices.sprites().getSprite("sonic"));

        coordinator.onFrameUpdate();

        short[] pointGrid = runtime.activePointGridForTest();
        assertNotNull(pointGrid);
        assertEquals(16 * 16 * 2, pointGrid.length);
        assertEquals(runtime.activeSlotCageForTest().getCurrentX(), runtime.slotVisualState().eventsBgX());
        assertEquals(runtime.activeSlotCageForTest().getCurrentY(), runtime.slotVisualState().eventsBgY());
    }

    @Test
    void bootstrapStagesRomShapedStateAndRenderBuffers() {
        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();

        runtime.bootstrap();

        S3kSlotStageState state = runtime.stageStateForTest();
        S3kSlotRenderBuffers buffers = runtime.renderBuffersForTest();

        assertNotNull(state);
        assertNotNull(buffers);
        assertEquals(0, state.statTable());
        assertEquals(0x40, state.scalarIndex1());
        assertFalse(state.paletteCycleEnabled());
        assertEquals(0, state.lastCollisionTileId());
        assertEquals(0, runtime.paletteCycleMode());
        assertEquals(0x10, state.eventsBgX());
        assertEquals(0x2D, state.eventsBgY());
        assertEquals(0x80, buffers.layoutStrideBytes());
        assertEquals(0x80, buffers.layoutRows());
        assertEquals(0x80, buffers.layoutColumns());
    }

    @Test
    void slotPlayerMovesUnderRightInputInBootstrappedZone15() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x15, 0)
                .build();

        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        fixture.runtime().setActiveBonusStageProvider(coordinator);
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());
        coordinator.onDeferredSetupComplete();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("sonic"));
        short startX = slotPlayer.getX();
        short startY = slotPlayer.getY();

        for (int frame = 0; frame < 8; frame++) {
            SpriteManager.tickPlayablePhysics(slotPlayer,
                    false, false, false, true, false, false, false, false,
                    GameServices.level(), 100 + frame);
        }

        assertTrue(slotPlayer.getX() != startX || slotPlayer.getY() != startY,
                "x=" + slotPlayer.getX() + " y=" + slotPlayer.getY()
                        + " startX=" + startX + " startY=" + startY
                        + " gSpeed=" + slotPlayer.getGSpeed()
                        + " xSpeed=" + slotPlayer.getXSpeed()
                        + " ySpeed=" + slotPlayer.getYSpeed());
    }

    @Test
    void slotPlayerMovesUnderRightInputThroughFullHeadlessFrameStep() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x15, 0)
                .build();

        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        fixture.runtime().setActiveBonusStageProvider(coordinator);
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());
        coordinator.onDeferredSetupComplete();

        AbstractPlayableSprite slotPlayer = assertInstanceOf(
                AbstractPlayableSprite.class, GameServices.sprites().getSprite("sonic"));
        HeadlessTestRunner runner = new HeadlessTestRunner(slotPlayer);
        short startX = slotPlayer.getX();
        short startY = slotPlayer.getY();

        for (int frame = 0; frame < 8; frame++) {
            runner.stepFrame(false, false, false, true, false);
        }

        assertTrue(slotPlayer.getX() != startX || slotPlayer.getY() != startY,
                "x=" + slotPlayer.getX() + " y=" + slotPlayer.getY()
                        + " startX=" + startX + " startY=" + startY
                        + " gSpeed=" + slotPlayer.getGSpeed()
                        + " xSpeed=" + slotPlayer.getXSpeed()
                        + " ySpeed=" + slotPlayer.getYSpeed());
    }

    @Test
    void fullSlotStageLifecycleFromBootstrapThroughFrameUpdates() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x15, 0)
                .build();

        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        fixture.runtime().setActiveBonusStageProvider(coordinator);
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());
        coordinator.onDeferredSetupComplete();

        S3kSlotBonusStageRuntime runtime = coordinator.activeSlotRuntimeForTest();
        assertNotNull(runtime);
        assertTrue(runtime.isInitialized());

        // Verify subsystems are wired in
        S3kSlotBonusCageObjectInstance cage = runtime.activeSlotCageForTest();
        assertNotNull(cage);
        assertTrue(fixture.runtime().getObjectManager().getActiveObjects().contains(cage));
        assertTrue(runtime.activeSlotRingRewardsForTest().isEmpty());
        assertTrue(runtime.activeSlotSpikeRewardsForTest().isEmpty());
        assertNotNull(runtime.activeLayoutForTest());
        assertEquals(32 * 32, runtime.activeLayoutForTest().length);

        // Run 300 frames of gameplay
        for (int i = 0; i < 300; i++) {
            coordinator.onFrameUpdate();
        }

        // Runtime should still be running (no goal tile reached from idle position)
        assertTrue(runtime.isInitialized());
        assertFalse(runtime.isExitTriggered());

        // Verify the option cycle system has been ticking
        assertNotNull(runtime.optionCycleSystemForTest());

        // Clean exit
        coordinator.onExit();
        assertFalse(runtime.isInitialized());
        assertNull(coordinator.activeSlotRuntimeForTest());
        assertFalse(fixture.runtime().getObjectManager().getActiveObjects().contains(cage));
    }

    @Test
    void exitSequenceStartsFadeToBlackWhenFadePhaseBegins() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x15, 0)
                .build();

        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        fixture.runtime().setActiveBonusStageProvider(coordinator);
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());
        coordinator.onDeferredSetupComplete();

        S3kSlotBonusStageRuntime runtime = coordinator.activeSlotRuntimeForTest();
        assertNotNull(runtime);

        runtime.startGoalExitForTest();

        for (int i = 0; i < 96; i++) {
            coordinator.onFrameUpdate();
        }

        assertTrue(GameServices.fade().isActive());
        assertEquals(FadeManager.FadeState.FADING_TO_BLACK, GameServices.fade().getState());
    }

    private static BonusStageState savedState() {
        return new BonusStageState(0x0001, 0x0001, 40, 0, 1, 0, 0, 0,
                0x460, 0x430, 0x400, 0x400, (byte) 0x0C, (byte) 0x0D, 0x600, 0L);
    }
}


