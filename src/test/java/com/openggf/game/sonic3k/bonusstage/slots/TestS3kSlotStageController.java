package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotStageController {

    @Test
    void spikeSoundCounterUsesUnsignedWordAndResetsOnStageBootstrap() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotStageController controller = new S3kSlotStageController(state);
        state.setScalarIndex2(0xFFFF);
        controller.advanceSpikePayout();
        assertEquals(0, state.scalarIndex2());
        state.setScalarIndex2(0x8000);
        assertTrue(controller.consumeSpikeSound());
        assertEquals(0, state.scalarIndex2());
        state.setScalarIndex2(5);
        controller.bootstrap();
        assertEquals(0, state.scalarIndex2());
    }

    @Test
    void bootstrapResetsAngleStateAfterMovement() {
        S3kSlotStageController controller = new S3kSlotStageController();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("tails", (short) 0, (short) 0, newRuntime());

        controller.bootstrap();
        controller.tick(); // advance by scalarIndex (0x40)
        assertEquals(0x40, controller.rawStatTable());
        assertEquals(0, controller.angle());

        controller.bootstrap();

        assertEquals(0, controller.angle());

        controller.tick(); // advance by 0x40 again after re-bootstrap
        assertEquals(0x40, controller.rawStatTable());
        assertEquals(0, controller.angle());
        controller.tickPlayer((S3kSlotBonusPlayer) player, false, false, false, 1);
        assertEquals((byte) 0, player.getAngle());
    }

    @Test
    void leftAndRightInputsDoNotSkipJumpWhenBothPressed() {
        S3kSlotStageController controller = new S3kSlotStageController();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("tails", (short) 0, (short) 0, newRuntime());

        controller.bootstrap();
        controller.tick(); // angle = 0x40
        controller.tickPlayer((S3kSlotBonusPlayer) player, false, false, false, 0);
        assertEquals(0, controller.angle());
        assertEquals((byte) 0, player.getAngle());

        player.setAir(false);
        player.setJumpInputPressed(true);
        controller.tickPlayer((S3kSlotBonusPlayer) player, true, true, true, 1);
        int angle = controller.angle();
        int launchAngle = (-((angle & 0xFC)) - 0x40) & 0xFF;

        assertEquals(0, angle);
        assertEquals((byte) 0, player.getAngle());
        assertTrue(player.getAir());
        assertEquals((short) ((TrigLookupTable.cosHex(launchAngle) * 0x680) >> 8), player.getXSpeed());
        assertEquals((short) ((TrigLookupTable.sinHex(launchAngle) * 0x680) >> 8), player.getYSpeed());
    }

    @Test
    void angleWraparoundIsMaskedToEightBits() {
        S3kSlotStageController controller = new S3kSlotStageController();

        controller.bootstrap();
        // Stat_table is a 16-bit accumulator; the byte angle is its high byte.
        controller.setScalarIndex(0xC000);
        controller.tick(); // angle = 0xC0
        assertEquals(0xC0, controller.angle());
        controller.tick(); // angle = 0xC0 + 0xC0 = 0x180 & 0xFF = 0x80
        assertEquals(0x80, controller.angle());
    }

    @Test
    void jumpLaunchesPlayerUsingCurrentAngleAndSetsAirborneState() {
        S3kSlotStageController controller = new S3kSlotStageController();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("tails", (short) 0, (short) 0, newRuntime());

        controller.bootstrap();
        controller.tick(); // angle = 0x40
        int angle = controller.angle();
        player.setAir(false);
        player.setJumpInputPressed(true);
        controller.tickPlayer((S3kSlotBonusPlayer) player, false, false, true, 1);
        int launchAngle = (-((angle & 0xFC)) - 0x40) & 0xFF;

        assertTrue(player.getAir());
        assertEquals((short) ((TrigLookupTable.cosHex(launchAngle) * 0x680) >> 8), player.getXSpeed());
        assertEquals((short) ((TrigLookupTable.sinHex(launchAngle) * 0x680) >> 8), player.getYSpeed());
        assertEquals((byte) angle, player.getAngle());
    }

    @Test
    void heldJumpDoesNotRelaunchAfterTheFirstPress() {
        S3kSlotStageController controller = new S3kSlotStageController();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("tails", (short) 0, (short) 0, newRuntime());

        controller.bootstrap();
        controller.tick(); // angle = 0x40
        player.setAir(false);
        player.setJumpInputPressed(true);
        controller.tickPlayer((S3kSlotBonusPlayer) player, false, false, true, 1);

        player.setXSpeed((short) 0x0123);
        player.setYSpeed((short) 0x0456);

        player.setJumpInputPressed(true);
        controller.tickPlayer((S3kSlotBonusPlayer) player, false, false, true, 2);

        assertEquals((short) 0x0123, player.getXSpeed());
        assertEquals((short) 0x0456, player.getYSpeed());
    }

    @Test
    void bootstrapInitializesScalarIndexTo0x40() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        assertEquals(0x40, controller.scalarIndex());
    }

    @Test
    void tickAccumulatesStatTableByScalarIndex() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        assertEquals(0, controller.angle());

        controller.tick();
        assertEquals(0x40, controller.rawStatTable());
        assertEquals(0, controller.angle());

        controller.tick();
        assertEquals(0x80, controller.rawStatTable());
        assertEquals(0, controller.angle());

        controller.tick();
        controller.tick();
        assertEquals(0x0100, controller.rawStatTable());
        assertEquals(1, controller.angle());
    }

    @Test
    void negateScalarReversesRotationDirection() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        controller.tick(); // angle = 0x40
        assertEquals(0x40, controller.rawStatTable());
        assertEquals(0, controller.angle());

        controller.negateScalar();
        assertEquals(-0x40, controller.scalarIndex());

        controller.tick(); // angle = 0x40 + (-0x40) = 0x00
        assertEquals(0, controller.rawStatTable());
        assertEquals(0x00, controller.angle());
    }

    @Test
    void jumpOnlyWorksWhenGrounded() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic", (short) 0, (short) 0, newRuntime());

        // Set player airborne
        player.setAir(true);
        player.setJumpInputPressed(true);

        controller.tickPlayer((S3kSlotBonusPlayer) player, false, false, true, 0);

        // Should NOT have launched (was already airborne)
        assertEquals((short) 0, player.getXSpeed());
        assertEquals((short) 0, player.getYSpeed());
    }

    @Test
    void restartCaptureCycleRestartsVisibleSpinFromResolvedIdle() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotStageState state = controller.stageStateForTest();
        state.setOptionCycleState(0x18);
        state.setOptionCycleResolvedDisplayTimer(0);

        controller.restartCaptureCycleIfResolved();

        assertEquals(0x08, state.optionCycleState());
        assertEquals(0, state.optionCycleCountdown());
        assertEquals(0, state.optionCycleSpinCycleCounter());
    }

    @Test
    void negativePayoutUsesRomFixedHundredSpikeBudget() throws Exception {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic",
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X,
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y,
                newRuntime());
        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(
                new ObjectSpawn(S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X,
                        S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y, 0, 0, 0, false, 0),
                controller);

        cage.tickSlotRuntime(0, player,
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X,
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y);
        controller.stageStateForTest().setOptionCycleState(0x18);
        controller.latchResolvedPrizeForCapture(-1);
        cage.tickSlotRuntime(2, player,
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X,
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y);

        assertEquals(0x64, cage.pendingRewardsForTest());
    }

    private static S3kSlotPlayerRuntime newRuntime() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem collisionSystem = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);
        return new S3kSlotPlayerRuntime(state, collisionSystem);
    }

}


