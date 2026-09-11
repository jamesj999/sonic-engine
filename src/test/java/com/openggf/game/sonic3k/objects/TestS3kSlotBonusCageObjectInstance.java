package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotBonusCageObjectInstance {

    @Test
    void spikePayoutClockIncludesBlockedSpawnsAndFinalChildrenButExcludesEjection() throws Exception {
        var state = com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageState.bootstrap();
        var controller = new S3kSlotStageController(state);
        controller.latchResolvedPrizeForCapture(-4);
        forceOptionCycleState(controller, 0x0C);
        var cage = new S3kSlotBonusCageObjectInstance(
                new ObjectSpawn(0x460, 0x430, 0, 0, 0, false, 0), controller);
        cage.setServices(new TestObjectServices());
        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x430);
        cage.update(0, player);
        assertEquals(0, state.scalarIndex2(), "unresolved reels do not tick payout");
        forceOptionCycleState(controller, 0x18);
        for (int i = 0; i < 16; i++) controller.onRewardSpawned();
        for (int frame = 2; frame < 7; frame++) cage.update(frame, player);
        assertEquals(5, state.scalarIndex2());
        assertEquals(100, cage.pendingRewardsForTest(), "full child slots prevent spawning");
        assertTrue(controller.consumeSpikeSound());
        Field remaining = S3kSlotBonusCageObjectInstance.class.getDeclaredField("rewardsToSpawn");
        remaining.setAccessible(true);
        remaining.setInt(cage, 0);
        for (int frame = 7; frame < 12; frame++) cage.update(frame, player);
        assertEquals(5, state.scalarIndex2(), "last travelling spikes keep the clock running");
        for (int i = 0; i < 16; i++) controller.onRewardExpired();
        cage.update(12, player);
        assertEquals(2, cage.cageStateForTest());
        assertEquals(5, state.scalarIndex2(), "ejection bypasses the increment");
    }

    @Test
    void captureCentersNearbyPlayableAndLocksControl() {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn, controller);
        cage.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x44C, (short) 0x41C);
        player.setXSpeed((short) 0x0123);
        player.setYSpeed((short) -0x0456);
        player.setGSpeed((short) 0x0789);
        player.setSubpixelRaw(0x8800, 0xE000);
        player.setAir(false);
        player.setOnObject(true);

        cage.update(0, player);

        assertEquals(spawn.x(), player.getCentreX());
        assertEquals(spawn.y(), player.getCentreY());
        assertEquals(0x8800, player.getXSubpixelRaw());
        assertEquals(0xE000, player.getYSubpixelRaw());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
        assertTrue(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertTrue(player.isTouchResponseSuppressedByObjectControl());
        assertTrue(player.isControlLocked());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
    }

    @Test
    void debugPlayableUpdatesAnimatedAnchorButDoesNotCapture() {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.setScalarIndex(0x4000);
        controller.tick();

        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn, controller);
        cage.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x430, (short) 0x440);
        player.setDebugMode(true);
        short originalX = player.getX();
        short originalY = player.getY();

        cage.update(0, player);

        int angle = controller.angle() & 0xFC;
        int sin = com.openggf.physics.TrigLookupTable.sinHex(angle);
        int cos = com.openggf.physics.TrigLookupTable.cosHex(angle);
        int dx = spawn.x() - player.getCentreX();
        int dy = spawn.y() - player.getCentreY();
        int expectedX = (((dx * cos) - (dy * sin)) >> 8) + player.getCentreX();
        int expectedY = (((dx * sin) + (dy * cos)) >> 8) + player.getCentreY();
        assertEquals(expectedX, cage.getCurrentX());
        assertEquals(expectedY, cage.getCurrentY());
        assertEquals(originalX, player.getX());
        assertEquals(originalY, player.getY());
        assertFalse(player.isObjectControlled());
        assertFalse(player.isControlLocked());
    }

    @Test
    void captureLatchesPositiveResolvedPrizeIntoRingPayout() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.latchResolvedPrizeForCapture(6);
        forceOptionCycleState(controller, 0x0C);

        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn, controller);
        cage.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x430);
        cage.update(0, player);
        forceOptionCycleState(controller, 0x18);
        cage.update(2, player);

        assertTrue(cage.spawnsRingsForTest());
        assertEquals(6, cage.pendingRewardsForTest());
        assertFalse(controller.consumeSpikeSound());
    }

    @Test
    void captureLatchesNegativeResolvedPrizeIntoFixedSpikePenaltyBudget() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.latchResolvedPrizeForCapture(-4);
        forceOptionCycleState(controller, 0x0C);

        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn, controller);
        cage.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x430);
        cage.update(0, player);
        forceOptionCycleState(controller, 0x18);
        cage.update(2, player);

        assertFalse(cage.spawnsRingsForTest());
        assertEquals(0x64, cage.pendingRewardsForTest());
    }

    @Test
    void captureDoesNotStartRewardSpawnWhileOptionCycleIsStillUnresolved() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.latchResolvedPrizeForCapture(6);
        forceOptionCycleState(controller, 0x0C);

        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn, controller);
        cage.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x430);
        cage.update(0, player);

        assertEquals(1, cage.cageStateForTest());
        assertEquals(0, cage.pendingRewardsForTest());
    }

    @Test
    void appendRenderCommandsSuppressesUnusedCageVisual() {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();

        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn, controller);
        cage.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x420, (short) 0x400);
        cage.suppressInitialCaptureOnce();
        cage.update(0, player);
        cage.update(1, player);

        List<GLCommand> cmds = new ArrayList<>();
        cage.appendRenderCommands(cmds);
        assertTrue(cmds.isEmpty(), "unused cage visual must emit no render commands");
    }

    @Test
    void releaseClearsObjectControlProfileAfterPayoutCompletes() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.latchResolvedPrizeForCapture(0);
        forceOptionCycleState(controller, 0x0C);

        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn, controller);
        cage.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x430);
        cage.update(0, player);
        forceOptionCycleState(controller, 0x18);
        cage.update(2, player);
        forceControllerAngle(controller, 0);
        cage.update(4, player);

        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
        assertFalse(player.isControlLocked());
        assertTrue(player.getAir());
        // ROM loc_4C250 (sonic3k.asm:99533-99552): GetSineCosine returns sin in d0 / cos
        // in d1, and x_vel takes d0 (sin) while y_vel takes d1 (cos) -- the opposite
        // pairing from the jump-launch convention (sub_4BBB2). At angle 0, sin=0 and
        // cos=0x100, so the release must leave x_speed at 0 and y_speed at 0x400 (both
        // scaled by the release's `asl.w #2` == *4). A cos->x/sin->y swap here silently
        // produced x_speed=0x400/y_speed=0x0000 instead.
        assertEquals(0, player.getXSpeed());
        assertEquals(0x400, player.getYSpeed());
    }

    private void forceOptionCycleState(S3kSlotStageController controller, int stateValue) throws Exception {
        Field stageStateField = S3kSlotStageController.class.getDeclaredField("stageState");
        stageStateField.setAccessible(true);
        Object stageState = stageStateField.get(controller);
        Field optionCycleStateField = stageState.getClass().getDeclaredField("optionCycleState");
        optionCycleStateField.setAccessible(true);
        optionCycleStateField.setInt(stageState, stateValue);
    }

    private void forceControllerAngle(S3kSlotStageController controller, int angle) throws Exception {
        Field stageStateField = S3kSlotStageController.class.getDeclaredField("stageState");
        stageStateField.setAccessible(true);
        Object stageState = stageStateField.get(controller);
        Field statTableField = stageState.getClass().getDeclaredField("statTable");
        statTableField.setAccessible(true);
        statTableField.setInt(stageState, (angle & 0xFF) << 8);
    }
}

