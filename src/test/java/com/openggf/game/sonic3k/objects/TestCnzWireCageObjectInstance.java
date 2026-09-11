package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzWireCageObjectInstance {

    @Test
    void unloadUsesLatchedLifecycleDestructionPolicy() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 0, false, 0));

        cage.onUnload();

        assertTrue(cage.isDestroyed());
    }

    @Test
    void activeCageSetsObjectControlBitSixWallCollisionSuppression() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 0, false, 0));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setCentreX((short) 0x1D80);
        player.setCentreY((short) 0x04F3);
        player.setAir(false);
        player.setGSpeed((short) 0x0C00);

        cage.update(0, player);

        assertTrue(player.isSuppressGroundWallCollision(),
                "Obj_CNZWireCage sets object_control bit 6, which makes Sonic_WalkSpeed skip CalcRoomInFront");
        assertTrue(player.isObjectControlled(),
                "Normal cage riding sets object_control bits 6 and 1, so the engine should preserve object-control state");
        assertTrue(player.isObjectControlAllowsCpu(),
                "Bits 6+1 are not ROM bit 7; sidekick CPU must still be allowed to generate logical input");
        assertFalse(player.isObjectControlSuppressesMovement(),
                "Bits 6+1 are not ROM bit 0; normal movement must still run");

        player.setDead(true);
        cage.update(1, player);

        assertFalse(player.isSuppressGroundWallCollision());
    }

    @Test
    void leaderReleasedSidekickNormalLatchKeepsCpuInputAndMovementEnabled() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 0, false, 0));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);
        sidekick.setCpuControlled(true);
        cage.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));

        leader.setCentreX((short) 0x1300);
        leader.setCentreY((short) 0x07C0);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        sidekick.setCentreX((short) 0x1200);
        sidekick.setCentreY((short) 0x07C0);
        sidekick.setAir(false);
        sidekick.setGSpeed((short) 0x0800);

        cage.update(0, leader);
        leader.setDead(true);

        sidekick.setCentreX((short) 0x1300);
        sidekick.setCentreY((short) 0x07C0);
        sidekick.setAir(false);
        sidekick.setGSpeed((short) 0x0800);

        cage.update(1, leader);

        assertTrue(sidekick.isObjectControlled(),
                "loc_3397A sets object_control bits 6+1 for a normal sidekick cage latch");
        assertTrue(sidekick.isObjectControlAllowsCpu(),
                "Bits 6+1 do not take the ROM bit-7 path, so Tails_CPU_Control must keep providing logical input");
        assertFalse(sidekick.isObjectControlSuppressesMovement(),
                "Normal latch does not take the loc_3394C/loc_339B6 bit 0 path");

        sidekick.setAir(true);
        sidekick.setOnObject(false);
        sidekick.setXSpeed((short) -0x0444);
        sidekick.setSubpixelRaw(0xC100, 0);
        sidekick.setYSpeed((short) 0x01B8);
        sidekick.setLogicalInputState(false, false, true, false, false);

        cage.update(2, leader);

        assertTrue(sidekick.isObjectControlled(),
                "The leader-released sidekick path leaves normal object_control bits 6+1 in place");
        assertTrue(sidekick.isObjectControlAllowsCpu(),
                "Bits 6+1 do not take the ROM bit-7 path, so Tails_CPU_Control must keep providing logical left");
        assertFalse(sidekick.isObjectControlSuppressesMovement(),
                "No loc_3394C/loc_339B6 bit 0 path is active, so movement must be able to apply the -0x18 air-control tick");
        assertEquals((short) -0x0444, sidekick.getXSpeed(),
                "The cage flag update itself must not apply the movement tick; it only leaves movement unsuppressed");
    }

    @Test
    void airborneAngleZeroCaptureFallsThroughToTouchFloorBeforeLatch() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 1, false, 0xA540));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setAir(true);
        player.setRolling(true);
        player.applyCustomRadii(7, 14);
        player.setCentreX((short) 0x1DD0);
        player.setCentreY((short) 0x04ED);
        player.setAngle((byte) 0);
        player.setXSpeed((short) -0x573);
        player.setYSpeed((short) -0x3A8);
        player.setGSpeed((short) -0x330);

        cage.update(0, player);

        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
        assertTrue(player.isObjectControlled());
        assertFalse(player.getRolling());
        assertEquals(0x13, player.getYRadius());
        assertEquals(9, player.getXRadius());
        assertEquals((short) 0x04E8, player.getCentreY(),
                "Player_TouchFloor subtracts the rolling-to-standing radius delta before the cage sets angle");
        assertEquals((short) 0, player.getXSpeed());
        assertEquals((short) -0x3A8, player.getYSpeed());
        assertEquals((short) -0x330, player.getGSpeed());
        assertEquals((byte) 0x40, player.getAngle());
    }

    @Test
    void airborneNonzeroAngleHighSpeedCapturePreservesXSpeed() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 0, false, 0));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setCentreX((short) 0x1DCE);
        player.setCentreY((short) 0x05F3);
        player.setAir(true);
        player.setAngle((byte) 0x2C);
        player.setXSpeed((short) -0x0717);
        player.setYSpeed((short) -0x0D4A);
        player.setGSpeed((short) -0x0F1E);

        cage.update(0, player);

        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
        assertEquals((short) -0x0717, player.getXSpeed(),
                "Obj_CNZWireCage clears Status_InAir before sub_33C34 on the nonzero-angle high-speed path, "
                        + "so sub_33C34 does not zero x_vel");
    }

    @Test
    void airborneNonRollingSidekickCaptureRestoresTailsRadiusBeforeOrbitX() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 1, false, 0));
        cage.setServices(new TestObjectServices());
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);

        sidekick.setCentreX((short) 0x130D);
        sidekick.setCentreY((short) 0x0707);
        sidekick.setAir(true);
        sidekick.setRolling(false);
        sidekick.setAngle((byte) 0);
        sidekick.applyCustomRadii(9, 19);
        sidekick.setXSpeed((short) -0x0117);
        sidekick.setYSpeed((short) 0x0147);
        sidekick.setGSpeed((short) 0x0271);

        cage.update(0, sidekick);

        assertTrue(sidekick.isOnObject());
        assertFalse(sidekick.getAir());
        assertEquals(15, sidekick.getYRadius(),
                "sub_33C34 calls Player_TouchFloor, which restores Tails's default y_radius before orbit math");
        assertEquals(9, sidekick.getXRadius(),
                "Player_TouchFloor restores Tails's default x_radius too");

        cage.update(1, sidekick);

        assertEquals((short) 0x134F, sidekick.getCentreX(),
                "loc_33BBA uses phase 0 and Tails y_radius=$0F: 0x1300 + ($100 >> 2) + $0F");
    }

    @Test
    void jumpPressedLogicalDuringLatchedCooldownReleasesAndPreservesSubpixels() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 1, false, 0xA540));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setAir(true);
        player.setRolling(true);
        player.applyCustomRadii(7, 14);
        player.setCentreX((short) 0x1DD0);
        player.setCentreY((short) 0x04ED);
        player.setAngle((byte) 0);
        player.setXSpeed((short) -0x573);
        player.setYSpeed((short) -0x3A8);
        player.setGSpeed((short) -0x330);
        player.setSubpixelRaw(0x6500, 0xD800);

        player.setJumpInputPressed(false);
        cage.update(0, player);
        player.setJumpInputPressed(true);
        cage.update(1, player);

        assertFalse(player.isOnObject());
        assertTrue(player.getAir());
        assertEquals((short) 0x0800, player.getXSpeed());
        assertEquals((short) JUMP_RELEASE_Y_SPEED_FOR_TEST, player.getYSpeed());
        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
        assertEquals(0x6500, player.getXSubpixelRaw(),
                "loc_33B62 clears the cage latch without touching x_sub");
        assertEquals(0xD800, player.getYSubpixelRaw(),
                "loc_33B62 clears the cage latch without touching y_sub");
    }

    @Test
    void heldJumpWithoutLowBytePressDoesNotReleaseFromLatchedCooldown() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 1, false, 0xA540));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setAir(true);
        player.setRolling(true);
        player.applyCustomRadii(7, 14);
        player.setCentreX((short) 0x1DD0);
        player.setCentreY((short) 0x04ED);
        player.setAngle((byte) 0);
        player.setXSpeed((short) -0x573);
        player.setYSpeed((short) -0x3A8);
        player.setGSpeed((short) -0x330);

        player.setJumpInputPressed(true);
        cage.update(0, player);
        player.setJumpInputPressed(true);
        cage.update(1, player);

        assertTrue(player.isOnObject(),
                "loc_33ADE masks only the low Ctrl_logical byte; held-only A/B/C does not release");
        assertFalse(player.getAir());
        assertNotEquals((short) 0x0800, player.getXSpeed());
        assertNotEquals((short) JUMP_RELEASE_Y_SPEED_FOR_TEST, player.getYSpeed());
    }

    @Test
    void jumpReleasePreservesRomCentrePositionWhileRestoringStandingRadii() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 0, false, 0));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setCentreX((short) 0x1D80);
        player.setCentreY((short) 0x04F3);
        player.setAir(false);
        player.setGSpeed((short) 0x0C00);
        cage.update(0, player);

        player.setRolling(true);
        player.setCentreX((short) 0x1DB4);
        player.setCentreY((short) 0x0496);
        player.setAir(true);
        player.setJumping(true);
        short releaseX = player.getCentreX();
        short releaseY = player.getCentreY();

        cage.update(1, player);

        assertEquals(releaseX, player.getCentreX(),
                "Obj_CNZWireCage release restores radii without changing ROM x_pos");
        assertEquals(releaseY, player.getCentreY(),
                "Obj_CNZWireCage release restores radii without changing ROM y_pos");
        assertEquals(0x13, player.getYRadius());
        assertEquals(9, player.getXRadius());
        assertEquals((short) JUMP_RELEASE_Y_SPEED_FOR_TEST, player.getYSpeed());
        assertFalse(player.getRolling());
        assertEquals(0, player.getAnimationId(),
                "move.w #1,anim publishes the big-endian high byte as raw Walk");
        assertEquals(1, player.getAnimationManager().captureRewindState().lastAnimationId(),
                "move.w #1,anim publishes the low byte into adjacent prev_anim");
    }

    @Test
    void lowSpeedReleaseSetsObjectControlBitZeroForReleaseRide() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 0, false, 0));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setCentreX((short) 0x1D80);
        player.setCentreY((short) 0x04F3);
        player.setAir(false);
        player.setGSpeed((short) 0x0300);
        cage.update(0, player);

        player.setGSpeed((short) 0x02F7);
        cage.update(1, player);

        assertTrue(player.isObjectControlled(),
                "loc_339B6 sets object_control bit 0 before entering the release ride path");
        assertTrue(player.isObjectControlAllowsCpu(),
                "loc_339B6 sets bits 0+1+6, not bit 7, so sidekick CPU is still allowed");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "loc_339B6 sets object_control bit 0, which suppresses normal movement");
        assertFalse(player.isControlLocked(),
                "loc_339B6 sets object_control bit 0 and the byte at 1(a2), "
                        + "but does not write Ctrl_1_locked");
        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
    }

    @Test
    void releaseRideAtRangeOnlyExitsOnPhaseBoundary() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 1, false, 1));
        cage.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setCentreX((short) 0x1300);
        player.setCentreY((short) 0x08AF);
        player.setAir(false);
        player.setGSpeed((short) 0x0300);
        cage.update(0, player);

        player.setGSpeed((short) 0x02F0);
        cage.update(1, player);
        assertEquals((short) 0x08B0, player.getCentreY(),
                "release ride reached Obj_CNZWireCage verticalRange");

        cage.update(2, player);

        assertTrue(player.isOnObject(),
                "loc_33B1E branches to loc_33BBA when vertical == range and (phase & $7F) != 0");
        assertFalse(player.getAir(),
                "loc_33B62 release only runs at the range boundary when the phase low bits are zero");
        assertTrue(player.isObjectControlled(),
                "loc_33BBA keeps the bit-0 release-ride state active");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "loc_33BBA remains in the bit-0 release-ride state");
        assertEquals((short) 0x08B0, player.getCentreY(),
                "loc_33BBA updates x/mapping only; it does not add the cage y_vel");
    }

    @Test
    void leaderDplcFrameChangeSkipsSidekickMountedRideForOneFrame() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 0, false, 0));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);
        cage.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));

        leader.setCentreX((short) 0x1300);
        leader.setCentreY((short) 0x07C0);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        sidekick.setCentreX((short) 0x1300);
        sidekick.setCentreY((short) 0x07C0);
        sidekick.setAir(false);
        sidekick.setGSpeed((short) 0x0800);

        cage.update(0, leader);
        short sidekickXAfterLatch = sidekick.getCentreX();
        short sidekickYAfterLatch = sidekick.getCentreY();
        int sidekickFrameAfterLatch = sidekick.getMappingFrame();
        sidekick.setAir(true);
        sidekick.setOnObject(false);

        cage.update(1, leader);

        assertNotEquals(0, leader.getMappingFrame(),
                "Leader cage rotation changed mapping_frame, so Sonic_Load_PLC2 would clobber d6");
        assertEquals(sidekickXAfterLatch, sidekick.getCentreX(),
                "FixBugs-off d6 corruption makes the P2 btst miss and ROM skips Tails's mounted cage update");
        assertEquals(sidekickYAfterLatch, sidekick.getCentreY());
        assertEquals(sidekickFrameAfterLatch, sidekick.getMappingFrame());
        assertFalse(sidekick.getAir(),
                "The skipped P2 call still sees ROM's persistent Status_OnObj/grounded latch");
        assertTrue(sidekick.isOnObject());
    }

    @Test
    void dirtyD6SidekickLatchClearsWallSuppressionAfterLeaderRelease() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x28, 0, false, 0));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);
        cage.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));

        leader.setCentreX((short) 0x1300);
        leader.setCentreY((short) 0x07C0);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        sidekick.setCentreX((short) 0x1200);
        sidekick.setCentreY((short) 0x07C0);
        sidekick.setAir(false);
        sidekick.setGSpeed((short) 0x0800);

        cage.update(0, leader);

        sidekick.setCentreX((short) 0x1300);
        cage.update(1, leader);

        assertTrue(sidekick.isOnObject());
        assertTrue(sidekick.isObjectControlled());
        assertEquals(Direction.RIGHT, sidekick.getDirection(),
                "loc_3397A clears Status_Facing even when loc_33958 latched the left-side phase");
        short sidekickXAfterDirtyLatch = sidekick.getCentreX();
        short sidekickYAfterDirtyLatch = sidekick.getCentreY();
        int sidekickFrameAfterDirtyLatch = sidekick.getMappingFrame();
        sidekick.setSuppressGroundWallCollision(true);
        leader.setDead(true);

        cage.update(2, leader);

        assertEquals(sidekickXAfterDirtyLatch, sidekick.getCentreX(),
                "F2137: dirty d6 wrote the P2 latch under status bit 1, but real d6=4 misses loc_339A0");
        assertEquals(sidekickYAfterDirtyLatch, sidekick.getCentreY(),
                "sub_338C4 falls through to loc_338D8 and exits at tst.b object_control(a1)");
        assertEquals(sidekickFrameAfterDirtyLatch, sidekick.getMappingFrame(),
                "No mounted orbit/DPLC frame update should run when the cage status bit test misses");
        assertTrue(sidekick.isObjectControlled(),
                "ROM leaves object_control bits 6+1 set after the skipped mounted branch");
        assertFalse(sidekick.isSuppressGroundWallCollision(),
                "Once the leader has released, the stale cage latch must not suppress Tails' terrain wall correction");
    }

    @Test
    void sidekickCanLatchAfterLeaderReleasedSameCage() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 0, false, 0));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);
        cage.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));

        leader.setCentreX((short) 0x1300);
        leader.setCentreY((short) 0x07C0);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        sidekick.setCentreX((short) 0x1200);
        sidekick.setCentreY((short) 0x07C0);
        sidekick.setAir(false);
        sidekick.setGSpeed((short) 0x0800);

        cage.update(0, leader);
        leader.setDead(true);
        sidekick.setCentreX((short) 0x1300);

        cage.update(1, leader);

        assertEquals(Sonic3kObjectIds.CNZ_WIRE_CAGE, sidekick.getLatchedSolidObjectId());
        assertTrue(sidekick.isOnObject());
    }

    @Test
    void nativeP2QuerySidekickCanLatchWhenRawSidekickListIsEmpty() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 0, false, 0));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails nativeP2 = new Tails("tails", (short) 0, (short) 0);
        cage.setServices(new QueryOnlyPlayerServices(leader, List.of(nativeP2)));

        leader.setCentreX((short) 0x1200);
        leader.setCentreY((short) 0x07C0);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        nativeP2.setCentreX((short) 0x1300);
        nativeP2.setCentreY((short) 0x07C0);
        nativeP2.setAir(false);
        nativeP2.setGSpeed((short) 0x0800);

        cage.update(0, leader);

        assertEquals(Sonic3kObjectIds.CNZ_WIRE_CAGE, nativeP2.getLatchedSolidObjectId(),
                "CNZ wire cage has only native P1/P2 standing bits, so P2 must come from ObjectPlayerQuery");
        assertTrue(nativeP2.isOnObject());
        assertTrue(nativeP2.isObjectControlled());
    }

    @Test
    void nonSpriteUpdateDoesNotPromoteQueriedMainIntoNativeP2CageSlot() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 0, false, 0));
        Tails main = new Tails("tails-main", (short) 0, (short) 0);
        Tails nativeP2 = new Tails("tails", (short) 0, (short) 0);
        main.setCpuControlled(true);
        nativeP2.setCpuControlled(true);
        cage.setServices(new QueryOnlyPlayerServices(main, List.of(nativeP2)));

        main.setCentreX((short) 0x1300);
        main.setCentreY((short) 0x07C0);
        main.setAir(false);
        main.setGSpeed((short) 0x0800);
        nativeP2.setCentreX((short) 0x1300);
        nativeP2.setCentreY((short) 0x07C0);
        nativeP2.setAir(false);
        nativeP2.setGSpeed((short) 0x0800);

        cage.update(0, mock(PlayableEntity.class));

        assertFalse(main.isOnObject(),
                "The queried main player must not be consumed as CNZ's native P2 slot when update input is non-sprite");
        assertEquals(Sonic3kObjectIds.CNZ_WIRE_CAGE, nativeP2.getLatchedSolidObjectId(),
                "CNZ wire cage should still use the first queried sidekick as native P2");
    }

    @Test
    void extraEngineSidekickDoesNotShareNativeP2CageLatchState() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 0, false, 0));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails nativeP2 = new Tails("tails", (short) 0, (short) 0);
        Tails extraSidekick = new Tails("tails-extra", (short) 0, (short) 0);
        cage.setServices(new TestObjectServices().withSidekicks(List.of(nativeP2, extraSidekick)));

        leader.setCentreX((short) 0x1200);
        leader.setCentreY((short) 0x07C0);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        nativeP2.setCentreX((short) 0x1200);
        nativeP2.setCentreY((short) 0x07C0);
        nativeP2.setAir(false);
        nativeP2.setGSpeed((short) 0x0800);
        extraSidekick.setCentreX((short) 0x1300);
        extraSidekick.setCentreY((short) 0x07C0);
        extraSidekick.setAir(false);
        extraSidekick.setGSpeed((short) 0x0800);

        cage.update(0, leader);

        assertFalse(extraSidekick.isOnObject(),
                "Additional engine sidekicks must not consume or share the native P2 cage standing bit");
        assertFalse(extraSidekick.isObjectControlled());
        assertNotEquals(Sonic3kObjectIds.CNZ_WIRE_CAGE, extraSidekick.getLatchedSolidObjectId());
    }

    @Test
    void leaderReleasedNormalSidekickLatchDoesNotSetObjectControlBitZero() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1300, 0x07C0, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x1E, 0, false, 0));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);
        cage.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));

        leader.setCentreX((short) 0x1300);
        leader.setCentreY((short) 0x07C0);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        sidekick.setCentreX((short) 0x1200);
        sidekick.setCentreY((short) 0x07C0);
        sidekick.setAir(false);
        sidekick.setGSpeed((short) 0x0800);

        cage.update(0, leader);
        leader.setDead(true);
        sidekick.setCentreX((short) 0x1300);
        cage.update(1, leader);

        assertTrue(sidekick.isObjectControlled(),
                "loc_3397A sets object_control bits 6+1 on a normal ground latch");
        assertTrue(sidekick.isObjectControlAllowsCpu(),
                "Bits 6+1 do not set ROM bit 7");
        assertFalse(sidekick.isObjectControlSuppressesMovement(),
                "Bits 6+1 do not set ROM bit 0");

        cage.update(2, leader);

        assertFalse(sidekick.isObjectControlSuppressesMovement(),
                "When the leader-released d6 quirk makes the P2 cage call fall through, ROM leaves bits 6+1 alone");
        assertTrue(sidekick.isObjectControlled());
        assertTrue(sidekick.isObjectControlAllowsCpu());
    }

    @Test
    void cleanSidekickStandingBitContinuesMountedRideOnLeaderReleaseFrame() {
        CnzWireCageObjectInstance cage = new CnzWireCageObjectInstance(new ObjectSpawn(
                0x1D80, 0x0540, Sonic3kObjectIds.CNZ_WIRE_CAGE, 0x18, 1, false, 1));
        AbstractPlayableSprite leader = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);
        cage.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));

        leader.setCentreX((short) 0x1D80);
        leader.setCentreY((short) 0x0540);
        leader.setAir(false);
        leader.setGSpeed((short) 0x0800);
        sidekick.setCentreX((short) 0x1D80);
        sidekick.setCentreY((short) 0x0540);
        sidekick.setAir(false);
        sidekick.setGSpeed((short) 0x0800);

        cage.update(0, leader);
        sidekick.setGSpeed((short) 0x02F0);
        leader.setAir(true);
        leader.setJumping(true);
        short sidekickXBeforeLeaderRelease = sidekick.getCentreX();

        cage.update(1, leader);

        assertEquals((short) 0x1DCF, sidekick.getCentreX(),
                "Sonic release clears P1 standing only; clean P2 bit 4 still reaches loc_339A0 "
                        + "and runs the release-ride orbit update");
        assertNotEquals(sidekickXBeforeLeaderRelease, sidekick.getCentreX());
        assertTrue(sidekick.isObjectControlSuppressesMovement(),
                "Low-speed mounted P2 path reaches loc_339B6 and sets object_control bit 0");
    }

    private static final int JUMP_RELEASE_Y_SPEED_FOR_TEST = -0x200;

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
