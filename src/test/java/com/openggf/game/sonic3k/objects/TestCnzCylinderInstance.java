package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.game.rules.GameRules;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TestCnzCylinderInstance {

    @Test
    void twistRenderFlipDoesNotChangePlayerStatusFacing() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setDirection(Direction.RIGHT);

        Object slot = playerOneSlot(cylinder);
        setSlotField(slot, "player", player);
        setSlotField(slot, "active", true);
        setSlotField(slot, "twistAngle", 0x16);
        setSlotField(slot, "horizontalDistance", 0x10);

        invokeHoldSlot(cylinder, slot);

        assertEquals(0x59, player.getMappingFrame());
        assertTrue(player.getRenderHFlip());
        assertEquals(Direction.RIGHT, player.getDirection(),
                "loc_32610 copies PlayerTwistFlip to render_flags only; it does not "
                        + "write Status_Facing (docs/skdisasm/sonic3k.asm:68078-68100)");
    }

    @Test
    void heldTwistMappingUsesPostIncrementAngle() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();

        Object slot = playerOneSlot(cylinder);
        setSlotField(slot, "player", player);
        setSlotField(slot, "active", true);
        setSlotField(slot, "twistAngle", 0x0A);
        setSlotField(slot, "horizontalDistance", 0x10);
        setSlotField(slot, "priorityThresholdSource", 0x60);

        invokeHoldSlot(cylinder, slot);

        assertEquals(0x0C, (int) getSlotField(slot, "twistAngle"));
        assertEquals(0x59, player.getMappingFrame(),
                "loc_32538 increments the twist byte before loc_32610 selects the mapping");
    }

    @Test
    void firstUpdateRunsTheSingleRomFallthroughMotionPass() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnWithSubtype(0xF1));
        cylinder.setServices(new TestObjectServices());

        cylinder.update(1, null);

        assertEquals(0x1BDF, cylinder.getX());
    }

    @Test
    void movingCylinderKeepsNativeSolidBitsOnTheLiveInstance() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnWithSubtype(0x45));

        assertTrue(cylinder.usesInstanceSolidStateLatchKey(),
                "The moving dynamic spawn must not replace the SST-owned standing/pushing latch key");
    }

    @Test
    void standingContactCaptureRestoresDefaultRadiiAndClearsRolling() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1BC6);
        player.setCentreY((short) 0x07AC);
        player.setRolling(true);
        player.applyRollingRadii(false);
        int defaultYRadius = player.getStandYRadius();

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 4310);
        cylinder.update(4311, player);

        assertTrue(player.isObjectControlled());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
        assertFalse(player.isControlLocked());
        assertFalse(player.getRolling());
        assertFalse(player.getAir());
        assertEquals(9, player.getXRadius());
        assertEquals(defaultYRadius, player.getYRadius());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
    }

    @Test
    void standingContactCapturesImmediatelyAfterRecentObjectControlRelease() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(
                spawnAtWithSubtype(0x147E, 0x0AE0, 0x42));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1495);
        player.setCentreY((short) 0x0AAC);
        player.setXSpeed((short) -0x0400);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) -0x0400);
        player.setAir(false);
        player.releaseFromObjectControl(18154);

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 18154);
        cylinder.update(18155, player);

        assertTrue(player.isObjectControlled(),
                "CNZ f18155: sub_324C0 captures from the standing bit without "
                        + "an engine recapture cooldown (sonic3k.asm:67985-68005)");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
    }

    @Test
    void firstOnscreenPassConsumesStandingBitPreservedByPriorOffscreenSolidSkip() throws Exception {
        TestEnvironment.activeGameplayMode();
        CnzCylinderInstance cylinder = new CnzCylinderInstance(
                spawnAtWithSubtype(0x1BDF, 0x07E0, 0x41));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite tails = new TestPlayableSprite();
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1A4F);
        tails.setCentreY((short) 0x062B);
        tails.setRenderFlagOnScreen(false);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(tails);
        tails.setAir(false);

        Object slot = playerTwoSlot(cylinder);
        setSlotField(slot, "player", tails);
        setSlotField(slot, "active", true);
        setSlotField(slot, "twistAngle", 0x80);
        setPrivateField(cylinder, "standingMask", 0x02);

        cylinder.update(0, null);

        assertFalse((boolean) getSlotField(slot, "active"),
                "offscreen loc_325F2 clears the rider slot after SolidObjectFull skips P2");
        assertEquals(0x02, getPrivateField(cylinder, "standingMaskDeferredBySkippedSolidPass"));
        tails.setRenderFlagOnScreen(true);
        ObjectControlState.nativeBit7FullControl().applyTo(tails);
        tails.setAir(true);
        tails.setAnimationId(0x20);
        tails.setMappingFrame(0xA0);

        cylinder.update(1, null);

        assertTrue((boolean) getSlotField(slot, "active"),
                "the next sub_324C0 must consume the still-live cylinder standing bit: "
                        + cylinder.traceDebugDetails());
        assertEquals(0, tails.getAnimationId(),
                "sub_324C0 capture overwrites the earlier Tails CPU animation pass");
        assertEquals(0x55, tails.getMappingFrame(),
                "PlayerTwist_UpdateFrame owns the post-capture mapping");
        assertEquals(0x062B, tails.getCentreY() & 0xFFFF,
                "sub_324C0 capture does not write y_pos before SolidObjectFull evaluates geometry");
        assertTrue(tails.getAir(),
                "the later on-screen SolidObjectFull pass releases stale support without clearing object_control");
        assertTrue(tails.isObjectControlAllowsCpu());
        assertTrue(tails.isObjectControlSuppressesMovement());
    }

    @Test
    void captureDoesNotLatchStaleLogicalInputWhileHeld() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        GameRulesTestPlayableSprite player = new GameRulesTestPlayableSprite();
        player.setGameRulesForTest(GameRules.SONIC_3K);
        player.setCentreX((short) 0x1BC6);
        player.setCentreY((short) 0x07AC);
        player.setLogicalInputState(false, false, false, true, false);

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 4310);
        cylinder.update(4311, player);
        player.setLogicalInputState(false, false, false, false, false);
        player.recordFollowerHistoryForTick();

        assertFalse(player.isControlLocked());
        assertEquals(0, player.getInputHistory(0) & 0xFFFF);
    }

    @Test
    void jumpReleaseAppliesRomHoldPositionBeforeLaunch() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1BC6);
        player.setCentreY((short) 0x07AC);

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 4310);
        cylinder.update(4311, player);
        int preReleaseY = player.getCentreY();
        player.setMappingFrame(0x56);

        player.setJumpInputPressed(true);
        player.setLogicalInputState(false, false, false, false, true);
        cylinder.update(4312, player);

        int thresholdByte = ((TrigLookupTable.sinHex(0x80) + 0x100) >> 2) & 0xFF;
        int distanceWord = (25 << 8) | thresholdByte;
        int expectedOffset = (TrigLookupTable.cosHex(0x80) * distanceWord) >> 16;
        assertFalse(player.isObjectControlled());
        assertTrue(player.getAir());
        assertTrue(player.getRolling());
        assertEquals(0x1BDF + expectedOffset, player.getCentreX());
        assertEquals(preReleaseY, player.getCentreY());
        assertEquals(0x56, player.getMappingFrame(),
                "loc_325B6 skips loc_3260A's twist mapping write on the jump-release row");
        assertEquals(7, player.getXRadius());
        assertEquals(14, player.getYRadius());
        assertTrue(player.getYSpeed() < 0);
    }

    @Test
    void jumpReleaseKeepsHeldStandingYAndClearsObjectSupport() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x19D0, 0x0160, 0x42));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCpuControlled(true);
        player.setCentreX((short) (cylinder.getX() - 0x09));
        player.setCentreY((short) 0x0130);

        cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), 10965);
        cylinder.update(10966, player);
        int heldStandingRadius = player.getStandYRadius();
        player.applyRollingRadii(false);
        player.setOnObject(true);
        player.setLatchedSolidObject(0x47, cylinder);

        player.setJumpInputPressed(true);
        player.setLogicalInputState(false, false, false, false, true);
        cylinder.update(10967, player);

        assertEquals(cylinder.getY() - 0x21 - heldStandingRadius, player.getCentreY());
        assertFalse(player.isOnObject());
        assertEquals(0, player.getLatchedSolidObjectId());
        assertFalse(player.isObjectControlled());
        assertTrue(player.getAir());
        assertTrue(player.getRolling());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getGSpeed());
        assertTrue(player.getYSpeed() < 0);
        assertFalse(cylinder.isSolidFor(player));

        cylinder.snapshotPreUpdatePosition();

        assertTrue(cylinder.isSolidFor(player));
    }

    @Test
    void heldCpuSidekickDoesNotReleaseFromLiveJumpHeldWithoutCtrl2LogicalPress() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x17EA, 0x0B3C, 0x41));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCpuControlled(true);
        player.setCentreX((short) 0x17FB);
        player.setCentreY((short) 0x0B10);

        cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), 19295);
        cylinder.update(19295, player);

        player.setJumpInputPressed(true);
        player.setLogicalInputState(false, false, false, false, false, false);
        cylinder.update(19296, player);

        assertTrue(player.isObjectControlled(),
                "CNZ f19296: Obj_CNZCylinder passes Ctrl_2_logical in d5 to sub_324C0 "
                        + "and loc_325B6 tests only the low-byte A/B/C press bits "
                        + "(sonic3k.asm:67656-67672,68059-68064)");
        assertFalse(player.getAir());
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
    }

    @Test
    void holdPreservesPlayerXSubpixelLikeWordXPosWrites() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1BC6);
        player.setCentreY((short) 0x07AC);
        player.setSubpixelRaw(0x1200, 0x9200);

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 4310);
        cylinder.update(4311, player);
        cylinder.update(4312, player);

        assertEquals(0x1200, player.getXSubpixelRaw());
    }

    @Test
    void holdUsesRomCombinedDistanceWordForXOffset() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1BE5);
        player.setCentreY((short) 0x07AC);

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 4310);
        cylinder.update(4311, player);
        for (int frame = 4312; frame <= 4376; frame++) {
            cylinder.update(frame, player);
        }

        int twistAngle = 0x80;
        int thresholdByte = ((TrigLookupTable.sinHex(twistAngle) + 0x100) >> 2) & 0xFF;
        int distanceWord = (6 << 8) | thresholdByte;
        int expectedOffset = (TrigLookupTable.cosHex(twistAngle) * distanceWord) >> 16;
        assertEquals(0x1BDF + expectedOffset, player.getCentreX());
    }

    @Test
    void horizontalFirstCaptureAndHeldStepUseCurrentPostMotionAnchor() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnWithSubtype(0x42));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        int frameEntryX = cylinder.getX();
        player.setCentreX((short) (frameEntryX - 0x22));
        player.setCentreY((short) 0x07AC);

        cylinder.snapshotPreUpdatePosition();
        cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), 4310);
        cylinder.update(4311, player);
        cylinder.update(4312, player);

        int twistAngle = 0x80;
        int thresholdByte = ((TrigLookupTable.sinHex(twistAngle) + 0x100) >> 2) & 0xFF;
        int distanceWord = (0x22 << 8) | thresholdByte;
        int expectedOffset = (TrigLookupTable.cosHex(twistAngle) * distanceWord) >> 16;
        assertEquals(cylinder.getX() + expectedOffset, player.getCentreX());
    }

    @Test
    void horizontalWidePositiveStepCapturesCurrentDistanceForNonCpuRider() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x2060, 0x01A0, 0x52));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x2074);
        player.setCentreY((short) 0x016C);

        setCylinderCenter(cylinder, 0x206C, 0x01A0);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x206E, 0x01A0);
        Object slot = playerOneSlot(cylinder);

        invokeCaptureSlot(cylinder, slot, player, true);

        assertEquals(0x06, (int) getSlotField(slot, "horizontalDistance"));
        assertNotEquals(0x08, (int) getSlotField(slot, "horizontalDistance"));

        setCylinderCenter(cylinder, 0x206E, 0x01A0);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x206F, 0x01A0);
        invokeHoldSlot(cylinder, slot);

        assertEquals(0x2075, player.getCentreX());
        assertNotEquals(0x2074, player.getCentreX());
    }

    @Test
    void horizontalNarrowNegativeStepCapturesCurrentDistanceForNonCpuRider() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x2370, 0x0460, 0x41));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x2357);
        player.setCentreY((short) 0x042C);

        setCylinderCenter(cylinder, 0x236C, 0x0460);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x236B, 0x0460);
        Object slot = playerOneSlot(cylinder);

        invokeCaptureSlot(cylinder, slot, player, true);

        assertEquals(0x14, (int) getSlotField(slot, "horizontalDistance"));
        assertNotEquals(0x15, (int) getSlotField(slot, "horizontalDistance"));

        setCylinderCenter(cylinder, 0x236B, 0x0460);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x236B, 0x0460);
        invokeHoldSlot(cylinder, slot);

        assertEquals(0x2356, player.getCentreX());
        assertNotEquals(0x2355, player.getCentreX());
    }

    @Test
    void horizontalWideHeldPostPeakStepUsesCurrentAnchorForNonCpuRider() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x19E0, 0x0160, 0x42));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) (cylinder.getX() - 0x0C));
        player.setCentreY((short) 0x012C);

        cylinder.snapshotPreUpdatePosition();
        cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), 1);
        cylinder.update(1, player);

        int holdFrames = 0;
        for (int frame = 2; frame < 120; frame++) {
            cylinder.snapshotPreUpdatePosition();
            int preUpdateX = cylinder.getPreUpdateX();
            int twistAngle = (0x80 + (holdFrames * 2)) & 0xFF;
            cylinder.update(frame, player);
            int currentX = cylinder.getX();
            if (currentX < preUpdateX) {
                int expectedOffset = heldOffset(0x0C, twistAngle);
                assertEquals(currentX + expectedOffset, player.getCentreX());
                assertNotEquals(preUpdateX + expectedOffset, player.getCentreX());
                return;
            }
            holdFrames++;
        }

        fail("Expected subtype $42 to reach a negative horizontal step");
    }

    @Test
    void horizontalWideHeldPostPeakStepUsesCurrentAnchorForCpuSidekickRider() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x19E0, 0x0160, 0x42));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCpuControlled(true);
        player.setCentreX((short) (cylinder.getX() - 0x17));
        player.setCentreY((short) 0x0130);

        cylinder.snapshotPreUpdatePosition();
        cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), 1);
        cylinder.update(1, player);

        int holdFrames = 0;
        for (int frame = 2; frame < 120; frame++) {
            cylinder.snapshotPreUpdatePosition();
            int preUpdateX = cylinder.getPreUpdateX();
            int twistAngle = (0x80 + (holdFrames * 2)) & 0xFF;
            cylinder.update(frame, player);
            int currentX = cylinder.getX();
            if (currentX < preUpdateX) {
                int expectedOffset = heldOffset(0x17, twistAngle);
                assertEquals(currentX + expectedOffset, player.getCentreX());
                assertNotEquals(preUpdateX + expectedOffset, player.getCentreX());
                return;
            }
            holdFrames++;
        }

        fail("Expected subtype $42 to reach a negative horizontal step");
    }

    @Test
    void horizontalNarrowHeldPostPeakStepUsesCurrentAnchorForNonCpuRider() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x1BDF, 0x07E0, 0x41));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) (cylinder.getX() - 0x18));
        player.setCentreY((short) 0x07AC);

        cylinder.snapshotPreUpdatePosition();
        cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), 1);
        cylinder.update(1, player);

        int holdFrames = 0;
        for (int frame = 2; frame < 120; frame++) {
            cylinder.snapshotPreUpdatePosition();
            int preUpdateX = cylinder.getPreUpdateX();
            int twistAngle = (0x80 + (holdFrames * 2)) & 0xFF;
            cylinder.update(frame, player);
            int currentX = cylinder.getX();
            if (currentX < preUpdateX) {
                int expectedOffset = heldOffset(0x18, twistAngle);
                assertEquals(currentX + expectedOffset, player.getCentreX());
                assertNotEquals(preUpdateX + expectedOffset, player.getCentreX());
                return;
            }
            holdFrames++;
        }

        fail("Expected subtype $41 to reach a negative horizontal step");
    }

    @Test
    void horizontalNarrowHeldPositiveStepUsesCurrentAnchorForCpuSidekickRider() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x1BA0, 0x07E0, 0x41));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCpuControlled(true);
        player.setCentreX((short) 0x1B9A);
        player.setCentreY((short) 0x07B0);

        setCylinderCenter(cylinder, 0x1BA0, 0x07E0);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x1BA1, 0x07E0);
        Object slot = playerTwoSlot(cylinder);
        setSlotField(slot, "active", true);
        setSlotField(slot, "twistAngle", 0x8E);
        setSlotField(slot, "horizontalDistance", 0x06);
        setSlotField(slot, "priorityThresholdSource", 0x60);
        setSlotField(slot, "player", player);

        invokeHoldSlot(cylinder, slot);

        int expectedOffset = heldOffset(0x06, 0x8E);
        assertEquals(0x1BA1 + expectedOffset, player.getCentreX());
        assertNotEquals(0x1BA0 + expectedOffset, player.getCentreX());
    }

    @Test
    void circularHeldPositiveStepUsesCurrentAnchorForNonCpuRider() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x1B90, 0x0120, 0x4B));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1B7E);
        player.setCentreY((short) 0x00EC);

        setCylinderCenter(cylinder, 0x1B93, 0x0120);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x1B94, 0x0120);
        Object slot = playerOneSlot(cylinder);
        setSlotField(slot, "active", true);
        setSlotField(slot, "twistAngle", 0x80);
        setSlotField(slot, "horizontalDistance", 0x15);
        setSlotField(slot, "priorityThresholdSource", 0x60);
        setSlotField(slot, "player", player);

        invokeHoldSlot(cylinder, slot);

        int expectedOffset = heldOffset(0x15, 0x80);
        assertEquals(0x1B94 + expectedOffset, player.getCentreX());
        assertNotEquals(0x1B93 + expectedOffset, player.getCentreX());
    }

    @Test
    void circularFirstCapturePositiveStepUsesCurrentDistanceForNonCpuRider() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x1CE0, 0x0120, 0x4C));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1D14);
        player.setCentreY((short) 0x00EC);

        setCylinderCenter(cylinder, 0x1CFE, 0x0120);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x1CFF, 0x0120);
        Object slot = playerOneSlot(cylinder);

        invokeCaptureSlot(cylinder, slot, player, true);

        assertEquals(0x15, (int) getSlotField(slot, "horizontalDistance"));
        assertNotEquals(0x16, (int) getSlotField(slot, "horizontalDistance"));
        assertEquals(0x00, (int) getSlotField(slot, "twistAngle"));
    }

    @Test
    void circularVerticalObjectControlledSolidContactUsesCurrentSupportAnchor() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x1CE0, 0x0120, 0x4C));
        TestPlayableSprite player = new TestPlayableSprite();
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);

        setCylinderCenter(cylinder, 0x1D00, 0x0120);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x1D00, 0x0121);

        assertFalse(cylinder.usesPreUpdatePositionForSolidContact(player));
    }

    @Test
    void verticalOscillatorNewSideContactUsesCurrentAnchorForNonCpuRider() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x15C0, 0x04E0, 0x45));
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x15E9);
        player.setCentreY((short) 0x052C);
        player.setXSpeed((short) 0xFE9A);
        player.setGSpeed((short) 0xFE9A);
        player.setAir(false);

        setCylinderCenter(cylinder, 0x15C0, 0x04FD);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x15C0, 0x04FE);

        assertFalse(cylinder.usesPreUpdatePositionForSolidContact(player),
                "Obj_CNZCylinder moves before its same-slot SolidObjectFull call");
    }

    @Test
    void verticalOscillatorHeldRiderUsesCurrentYAnchorOnUpStep() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x2920, 0x0458, 0x46));
        TestPlayableSprite player = new TestPlayableSprite();
        player.setAir(false);

        setCylinderCenter(cylinder, 0x2920, 0x041D);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x2920, 0x041C);
        Object slot = playerOneSlot(cylinder);
        setSlotField(slot, "active", true);
        setSlotField(slot, "twistAngle", 0x00);
        setSlotField(slot, "horizontalDistance", 0x09);
        setSlotField(slot, "priorityThresholdSource", 0x60);
        setSlotField(slot, "player", player);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);

        invokeHoldSlot(cylinder, slot);

        assertFalse(cylinder.usesPreUpdatePositionForSolidContact(player));
        assertEquals(0x041C - 0x21 - player.getYRadius(), player.getCentreY(),
                "sub_321E2's upward step is visible to same-slot rider control");
        assertNotEquals(0x041D - 0x21 - player.getYRadius(), player.getCentreY());
    }

    @Test
    void verticalOscillatorCpuSidekickNewContactUsesCurrentYAnchorOnUpStep() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x2920, 0x0458, 0x46));
        TestPlayableSprite tails = new TestPlayableSprite();
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setYSpeed((short) 0x0320);
        tails.setCentreX((short) 0x2921);
        tails.setCentreY((short) 0x03E3);

        setCylinderCenter(cylinder, 0x2920, 0x0416);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x2920, 0x0415);

        assertFalse(cylinder.usesPreUpdatePositionForSolidContact(tails),
                "P2 SolidObjectFull observes the same post-motion object position as P1");
    }

    @Test
    void verticalOscillatorCpuSidekickUndersideContactUsesCurrentYAnchorOnDownStep() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x14E0, 0x01E0, 0x45));
        TestPlayableSprite tails = new TestPlayableSprite();
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setCentreX((short) 0x14D6);
        tails.setCentreY((short) 0x020D);

        setCylinderCenter(cylinder, 0x14E0, 0x01E7);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x14E0, 0x01E8);

        assertFalse(cylinder.usesPreUpdatePositionForSolidContact(tails),
                "P2 underside separation observes the post-motion object position");
    }

    @Test
    void horizontalOscillatorCpuSidekickNewSideContactUsesCurrentXAnchor() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x1415, 0x0AE0, 0x42));
        TestPlayableSprite tails = new TestPlayableSprite();
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setCentreX((short) 0x143B);
        tails.setCentreY((short) 0x0B09);

        setCylinderCenter(cylinder, 0x1415, 0x0AE0);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x1414, 0x0AE0);

        assertFalse(cylinder.usesPreUpdatePositionForSolidContact(tails),
                "P2 side separation observes sub_321E2's current x_pos");
    }

    @Test
    void verticalOscillatorCpuCapturedRiderUsesCurrentYAnchorOnUpStep() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x2920, 0x0458, 0x46));
        TestPlayableSprite tails = new TestPlayableSprite();
        tails.setCpuControlled(true);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(tails);

        setCylinderCenter(cylinder, 0x2920, 0x0414);
        cylinder.snapshotPreUpdatePosition();
        setCylinderCenter(cylinder, 0x2920, 0x0413);

        assertFalse(cylinder.usesPreUpdatePositionForSolidContact(tails),
                "capture and the following SolidObjectFull call share the post-motion anchor");
    }

    @Test
    void sineVerticalJumpReleaseUsesStoredRomYVelocityNotPositionDelta() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x2920, 0x0458, 0x46));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x2916);
        player.setCentreY((short) 0x0396);
        Object slot = playerOneSlot(cylinder);
        setSlotField(slot, "active", true);
        setSlotField(slot, "player", player);
        setPrivateField(cylinder, "currentYVelocity", -0x0200);

        invokeReleaseSlot(cylinder, slot, 13116, true, (short) 0x0396);

        assertEquals((short) -0x0680, player.getYSpeed(),
                "CNZ f13116: loc_3238C writes y_pos directly and does not update "
                        + "y_vel(a0), so loc_325B6 must add -$680 to the stored "
                        + "ROM y_vel=$0000 rather than to the engine's synthetic "
                        + "position delta (sonic3k.asm:67865-67872, 68059-68068)");
    }

    @Test
    void firstCaptureKeepsGroundSpeedZeroUntilTheNextActiveRiderPass() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(
                spawnAtWithSubtype(0x2D20, 0x01A0, 0x30));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x2D1E);
        player.setCentreY((short) 0x01B4);
        player.setAir(false);
        Object slot = playerOneSlot(cylinder);
        setPrivateField(cylinder, "currentYVelocity", -0x0600);

        invokeCaptureSlot(cylinder, slot, player, true);
        cylinder.onSolidContact(player,
                new SolidContact(true, true, false, true, false), 30660);

        assertEquals(0, player.getGSpeed(),
                "sub_324C0's inactive-slot capture clears ground_vel and branches past "
                        + "loc_32594 before the same-frame SolidObjectFull pass");

        setPrivateField(cylinder, "capturedThisUpdateMask", 0);
        cylinder.onSolidContact(player,
                new SolidContact(true, true, false, true, false), 30661);

        assertEquals((short) 0x0800, player.getGSpeed(),
                "the following active-rider pass may publish the vertical launch speed");
    }

    @Test
    void nonJumpReleasePreservesActiveRiderVelocity() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setXSpeed((short) 0x0123);
        player.setYSpeed((short) -0x0456);
        player.setGSpeed((short) 0x0800);
        Object slot = playerOneSlot(cylinder);
        setSlotField(slot, "active", true);
        setSlotField(slot, "player", player);

        invokeReleaseSlot(cylinder, slot, 31050, false, (short) 0);

        assertTrue(player.getAir());
        assertFalse(player.isObjectControlled());
        assertEquals((short) 0x0123, player.getXSpeed());
        assertEquals((short) -0x0456, player.getYSpeed());
        assertEquals((short) 0x0800, player.getGSpeed(),
                "loc_325F2 must preserve loc_32594's active-rider launch speed");
    }

    @Test
    void mode0VerticalControllerUsesCurrentHeldInputWhileStandingOnCylinder() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x28A0, 0x04E0, 0x20));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x28B5);
        player.setCentreY((short) 0x04AC);

        player.setDirectionalInputPressed(false, false, false, false);
        cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), 12552);
        for (int frame = 12553; frame <= 12588; frame++) {
            boolean down = frame >= 12554 && frame <= 12579;
            boolean up = frame >= 12582;
            player.setDirectionalInputPressed(up, down, false, false);
            cylinder.snapshotPreUpdatePosition();
            cylinder.update(frame, player);
            cylinder.onSolidContact(player, new SolidContact(true, true, false, true, false), frame);
        }

        assertEquals(0x04FD, getPrivateIntField(cylinder, "centerY"),
                "CNZ f12588: loc_32254 reads current Ctrl_held_logical after MoveSprite2, "
                        + "so the first UP frame must affect mode-0 deceleration "
                        + "(sonic3k.asm:67736-67752, 67772-67782)");
        assertEquals(0xFD50, getPrivateIntField(cylinder, "mode0Velocity") & 0xFFFF);
    }

    @Test
    void mode0VerticalControllerTreatsZeroVelocityAsNonNegativeOnUpReturn() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawnAtWithSubtype(0x28A0, 0x04E0, 0x20));
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setDirectionalInputPressed(true, false, false, false);

        setPrivateField(cylinder, "centerY", 0x056D);
        setPrivateField(cylinder, "mode0Velocity", 0x0020);
        setPrivateField(cylinder, "mode0YSubpixel", 0x00);
        setPrivateField(cylinder, "standingMask", 0x01);
        setPrivateField(cylinder, "standingMaskCache", 0x01);
        setSlotField(playerOneSlot(cylinder), "player", player);

        for (int frame = 12802; frame <= 12808; frame++) {
            invokeMode0VerticalController(cylinder);
        }

        assertEquals(0x0569, getPrivateIntField(cylinder, "centerY"),
                "CNZ f12808: loc_322AC branches with BPL after the -$20 step, "
                        + "so y_vel == 0 must take loc_322D2's -$10 path before "
                        + "later UP-held acceleration resumes (sonic3k.asm:67772-67782)");
        assertEquals(0xFE70, getPrivateIntField(cylinder, "mode0Velocity") & 0xFFFF);
    }

    @Test
    void externalAirLaunchDuringHeldSlotPreservesVelocityAfterHeldXWrite() {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1BC6);
        player.setCentreY((short) 0x07AC);
        player.setSubpixelRaw(0x3400, 0x5600);

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 4310);
        cylinder.update(4311, player);

        player.setCentreX((short) 0x1C20);
        player.setSubpixelRaw(0x3400, 0x5600);
        player.setYSpeed((short) -0x700);
        player.setXSpeed((short) 0x0123);
        player.setGSpeed((short) 0x0456);
        player.setAir(true);
        player.setOnObject(false);
        ObjectControlState.none().applyTo(player);

        cylinder.update(4312, player);

        assertEquals(0x1BDF + heldOffset(25, 0x80), player.getCentreX());
        assertEquals(0x3400, player.getXSubpixelRaw());
        assertEquals((short) -0x700, player.getYSpeed());
        assertEquals((short) 0x0123, player.getXSpeed());
        assertEquals((short) 0x0456, player.getGSpeed());
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
        assertFalse(player.isObjectControlled());
    }

    @Test
    void externalAirLaunchRetainsRiderThroughFinalTwistThenRetiresAfterNextAnimatePass() throws Exception {
        CnzCylinderInstance cylinder = new CnzCylinderInstance(spawn());
        cylinder.setServices(new TestObjectServices());
        GameRulesTestPlayableSprite player = new GameRulesTestPlayableSprite();
        player.setGameRulesForTest(GameRules.SONIC_3K);
        player.setCentreX((short) 0x1BC6);
        player.setCentreY((short) 0x07AC);
        player.setAnimationProfile(new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(5)
                .setWalkAnimId(0)
                .setRunAnimId(1)
                .setRollAnimId(2)
                .setAirAnimId(0));
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(
                0, List.of(0x08), SpriteAnimationEndAction.LOOP, 0));
        player.setAnimationSet(animations);
        player.setAnimationId(0);

        cylinder.onSolidContact(player, new SolidContact(true, false, false, true, false), 1);
        cylinder.update(2, player);
        setSlotField(playerOneSlot(cylinder), "twistAngle", 0x0A);

        player.setYSpeed((short) -0x700);
        player.setAir(true);
        player.setOnObject(false);
        ObjectControlState.none().applyTo(player);
        cylinder.update(3, player);

        assertEquals(0x59, player.getMappingFrame(),
                "f10727 sub_324C0 publishes the last twist mapping before SolidObjectFull clears standing");
        assertTrue((boolean) getSlotField(playerOneSlot(cylinder), "active"),
                "loc_32604 cannot retire the rider until the cylinder's following object dispatch");

        player.getAnimationManager().update(4);

        assertEquals(0x08, player.getMappingFrame(),
                "after external object_control release, the next Animate_Sonic pass owns mapping_frame");

        cylinder.update(4, player);

        assertFalse((boolean) getSlotField(playerOneSlot(cylinder), "active"),
                "the following cylinder loc_32604 dispatch retires the preserved rider");
        assertEquals(0x08, player.getMappingFrame(),
                "retiring the rider must not overwrite the mapping published by the earlier player slot");
    }

    private static ObjectSpawn spawn() {
        return new ObjectSpawn(0x1BDF, 0x07E0, 0x47, 0, 0, false, 0);
    }

    private static ObjectSpawn spawnWithSubtype(int subtype) {
        return new ObjectSpawn(0x1BDF, 0x07E0, 0x47, subtype, 0, false, 0);
    }

    private static ObjectSpawn spawnAtWithSubtype(int x, int y, int subtype) {
        return new ObjectSpawn(x, y, 0x47, subtype, 0, false, 0);
    }

    private static int heldOffset(int horizontalDistance, int twistAngle) {
        int thresholdByte = ((TrigLookupTable.sinHex(twistAngle) + 0x100) >> 2) & 0xFF;
        int distanceWord = (horizontalDistance << 8) | thresholdByte;
        return (TrigLookupTable.cosHex(twistAngle) * distanceWord) >> 16;
    }

    private static void setCylinderCenter(CnzCylinderInstance cylinder, int x, int y) throws Exception {
        setPrivateField(cylinder, "centerX", x);
        setPrivateField(cylinder, "centerY", y);
        var updateDynamicSpawn = CnzCylinderInstance.class.getSuperclass()
                .getDeclaredMethod("updateDynamicSpawn", int.class, int.class);
        updateDynamicSpawn.setAccessible(true);
        updateDynamicSpawn.invoke(cylinder, x, y);
    }

    private static Object playerOneSlot(CnzCylinderInstance cylinder) throws Exception {
        var field = CnzCylinderInstance.class.getDeclaredField("playerOneSlot");
        field.setAccessible(true);
        return field.get(cylinder);
    }

    private static Object playerTwoSlot(CnzCylinderInstance cylinder) throws Exception {
        var field = CnzCylinderInstance.class.getDeclaredField("playerTwoSlot");
        field.setAccessible(true);
        return field.get(cylinder);
    }

    private static void setSlotField(Object slot, String name, Object value) throws Exception {
        var field = slot.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(slot, value);
    }

    private static Object getSlotField(Object slot, String name) throws Exception {
        var field = slot.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(slot);
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getPrivateField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int getPrivateIntField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void invokeMode0VerticalController(CnzCylinderInstance cylinder) throws Exception {
        var method = CnzCylinderInstance.class.getDeclaredMethod("updateMode0VerticalController");
        method.setAccessible(true);
        method.invoke(cylinder);
    }

    private static void invokeHoldSlot(CnzCylinderInstance cylinder, Object slot) throws Exception {
        var holdSlot = CnzCylinderInstance.class.getDeclaredMethod("holdSlot", slot.getClass());
        holdSlot.setAccessible(true);
        holdSlot.invoke(cylinder, slot);
    }

    private static void invokeCaptureSlot(CnzCylinderInstance cylinder, Object slot,
                                          AbstractPlayableSprite player, boolean latchedContact) throws Exception {
        var captureSlot = CnzCylinderInstance.class.getDeclaredMethod(
                "captureSlot", slot.getClass(), AbstractPlayableSprite.class, boolean.class);
        captureSlot.setAccessible(true);
        captureSlot.invoke(cylinder, slot, player, latchedContact);
    }

    private static void invokeReleaseSlot(CnzCylinderInstance cylinder, Object slot,
                                          int frameCounter, boolean jumpedOff, short jumpReleaseY) throws Exception {
        var releaseSlot = CnzCylinderInstance.class.getDeclaredMethod(
                "releaseSlot", slot.getClass(), int.class, boolean.class, short.class);
        releaseSlot.setAccessible(true);
        releaseSlot.invoke(cylinder, slot, frameCounter, jumpedOff, jumpReleaseY);
    }

    private static final class GameRulesTestPlayableSprite extends TestPlayableSprite {
        public void setGameRulesForTest(GameRules featureSet) {
            super.setGameRulesForTest(featureSet);
        }
    }
}
