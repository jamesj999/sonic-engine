package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLbzRollingDrumInstance {

    @Test
    void registryRoutesS3klSlot31ToLbzRollingDrum() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance drum = registry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_ROLLING_DRUM, 0x40, 0, false, 0));

        assertFalse(drum instanceof PlaceholderObjectInstance,
                "S3KL slot $31 is Obj_LBZRollingDrum and must not remain a placeholder");
        assertInstanceOf(LbzRollingDrumInstance.class, drum);
        assertEquals("LBZRollingDrum", drum.getName());
    }

    @Test
    void exposesRomCodePointerHighWordForS3kTailsCpuInteract() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);

        assertEquals(0x0002, ((RomObjectCodePointerProvider) drum).romObjectCodePointerHighWord(),
                "Obj_LBZRollingDrum stores loc_2C3CA in word 0, so Tails_CPU_interact samples high word $0002");
    }

    @Test
    void subtypeDefinesHorizontalCaptureWidth() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1840, 0x05C0);
        player.setYSpeed((short) 0);

        drum.update(0, player);

        assertFalse(player.isOnObject(),
                "Obj_LBZRollingDrum rejects x_pos deltas >= subtype; subtype $40 accepts -$40..+$3F only");
        assertFalse(drum.isRidingForTest(player));
    }

    @Test
    void firstContactSeedsRideStateAndMinimumGroundSpeed() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);
        player.setGSpeed((short) 0);
        player.setMappingFrame(0x96);
        player.setForcedAnimationId(Sonic3kAnimationIds.FLY.id());

        drum.update(0, player);

        assertTrue(player.isOnObject(), "loc_2C42C calls RideObject_SetRide for a player inside the drum window");
        assertEquals(Sonic3kObjectIds.LBZ_ROLLING_DRUM, player.getLatchedSolidObjectId(),
                "The invisible controller must latch the live object so Status_OnObj is not treated as stale");
        assertEquals(0x80, player.getFlipType(),
                "loc_2C44E writes flip_type=$80 on the capture frame");
        assertEquals(Sonic3kAnimationIds.WALK.id(), player.getAnimationId(),
                "loc_2C44E writes move.w #1,anim(a1), which means anim=0 and prev_anim=1");
        assertEquals(-1, player.getForcedAnimationId(),
                "the post-CPU anim write must retire an engine-only forced flight animation");
        assertTrue(((UnitPlayableSprite) player).wasAnimationRestartForced(),
                "move.w #1,anim(a1) forces anim != prev_anim, so the walk/tumble script must restart");
        assertEquals(0x96, player.getMappingFrame(),
                "The post-player capture must retain the mapping published before Obj31 executes");
        assertFalse(player.isObjectMappingFrameControl(),
                "Anim_Tumble resumes from the following player-slot dispatch");
        assertEquals((short) 1, player.getGSpeed(),
                "loc_2C44E seeds ground_vel=1 when it was zero");
        assertEquals(0x81, drum.getRideAngleForTest(player),
                "d0 < 8 stores $81 in the per-player angle byte before the ride update increments it");
    }

    @Test
    void airborneTopLandingClearsAirAndContinuesIntoRideArc() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);
        player.setAirForTest(true);
        player.setYSpeed((short) 0x0200);
        player.setXSpeed((short) 0x0300);
        player.setGSpeed((short) 0);

        drum.update(0, player);

        assertTrue(player.isOnObject(), "RideObject_SetRide should set Status_OnObj for an airborne top landing");
        assertFalse(player.getAir(), "RideObject_SetRide clears Status_InAir before the next object update");
        assertEquals((short) 0, player.getYSpeed(), "RideObject_SetRide zeroes y_vel on landing");
        assertEquals((short) 0x0300, player.getGSpeed(), "RideObject_SetRide copies x_vel to ground_vel");

        drum.update(1, player);

        assertTrue(player.isOnObject(),
                "The drum must keep the rider latched after the airborne landing instead of releasing immediately");
        assertEquals(expectedRideY(0x81, player.getYRadius(), 0x0600), player.getCentreY() & 0xFFFF,
                "The next frame should enter loc_2C4BA and rotate Sonic around the drum");
    }

    @Test
    void airborneRollingLandingRunsPlayerTouchFloorBeforeRide() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);
        player.setAirForTest(true);
        player.setRolling(true);
        player.setYSpeed((short) 0x0200);

        drum.update(0, player);

        assertFalse(player.getRolling(), "RideObject_SetRide calls Player_TouchFloor, which clears Status_Roll");
        assertEquals(player.getStandYRadius(), player.getYRadius(),
                "Player_TouchFloor restores default y_radius before the drum's sine path consumes it");
        assertEquals(Sonic3kAnimationIds.WALK.id(), player.getAnimationId(),
                "Player_TouchFloor plus loc_2C44E leave the player in the walk/tumble animation slot");
    }

    @Test
    void middleRecapturePreservesPreviousNativeAngleByte() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);

        drum.update(0, player);
        drum.update(1, player);
        int angleAfterRide = drum.getRideAngleForTest(player);

        player.setCentreXPreserveSubpixel((short) 0x1840);
        player.setAir(false);
        drum.update(2, player);
        assertFalse(player.isOnObject());

        player.setCentreXPreserveSubpixel((short) 0x1800);
        player.setCentreY((short) 0x0600);
        player.setYSpeed((short) 0);
        player.setAir(false);
        drum.update(3, player);

        assertTrue(player.isOnObject());
        assertEquals(angleAfterRide, drum.getRideAngleForTest(player),
                "loc_2C42C only writes _unkF7B0 for d0<8 or d0>=$9E; middle recapture leaves it unchanged");
    }

    @Test
    void nativeAngleByteIsSharedAcrossRollingDrumControllerObjects() {
        ZoneRuntimeRegistry registry = lbzRuntimeRegistry();
        LbzRollingDrumInstance first = drum(0x1800, 0x0600, 0x40);
        LbzRollingDrumInstance second = drum(0x1880, 0x0600, 0x40);
        TestObjectServices services = new TestObjectServices().withZoneRuntimeRegistry(registry);
        first.setServices(services);
        second.setServices(services);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);

        first.update(0, player);
        first.update(1, player);
        int angleAfterFirstController = first.getRideAngleForTest(player);
        player.setCentreXPreserveSubpixel((short) 0x1840);
        player.setAir(false);
        first.update(2, player);
        assertFalse(player.isOnObject());

        player.setCentreXPreserveSubpixel((short) 0x1880);
        player.setCentreY((short) 0x0600);
        player.setYSpeed((short) 0);
        player.setAir(false);
        second.update(3, player);

        assertTrue(player.isOnObject());
        assertEquals(angleAfterFirstController, second.getRideAngleForTest(player),
                "_unkF7B0 is LBZ runtime RAM shared by every Obj31 controller, so middle handoff must not reset angle");
    }

    @Test
    void sameFrameDrumTransferUsesLiveAirStatusForTouchFloorReset() {
        LbzRollingDrumInstance outgoing = drum(0x0600, 0x0640, 0x80);
        LbzRollingDrumInstance incoming = drum(0x0700, 0x0640, 0x80);
        TestablePlayableSprite player = groundedPlayer(0x0600, 0x0640);
        player.setRolling(true);
        outgoing.update(0, player);
        assertTrue(player.isOnObject());

        player.captureOnObjectAtFrameStart();
        player.setCentreXPreserveSubpixel((short) 0x0683);
        player.setCentreY((short) 0x0645);
        outgoing.update(1, player);
        assertTrue(player.getAir(), "The outgoing controller releases first in the engine's slot order");

        incoming.update(1, player);

        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
        assertFalse(player.getRolling(),
                "RideObject_SetRide calls Player_TouchFloor when the outgoing drum set the live InAir bit");
        assertEquals(0, player.getFlipAngle(),
                "Player_TouchFloor clears the outgoing drum's tumble angle before the incoming capture");
    }

    @Test
    void rightwardOverlappingDrumHandoffPreservesLiveRideUntilReceiverRuns() {
        LbzRollingDrumInstance outgoing = drum(0x0600, 0x0640, 0x80);
        LbzRollingDrumInstance incoming = drum(0x0700, 0x0640, 0x80);
        outgoing.setSlotIndex(13);
        incoming.setSlotIndex(6);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(outgoing, incoming));
        outgoing.setServices(new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });
        TestablePlayableSprite player = groundedPlayer(0x0600, 0x0640);
        outgoing.update(0, player);
        player.setRolling(true);
        player.setFlipAngle(0x44);
        player.setCentreXPreserveSubpixel((short) 0x0683);
        player.setCentreY((short) 0x0645);

        outgoing.update(1, player);
        assertFalse(player.getAir(),
                "the right-hand receiver's earlier native SST slot captures before the old drum releases");

        incoming.update(1, player);
        assertTrue(player.isOnObject());
        assertFalse(player.getAir());
        assertTrue(player.getRolling(),
                "a live Status_OnObj handoff must not call Player_TouchFloor");
        assertEquals(0x44, player.getFlipAngle(),
                "the receiving drum retains the active tumble phase");
    }

    @Test
    void laterSlotRightDrumLetsOutgoingReleaseBeforeRecapture() {
        LbzRollingDrumInstance outgoing = drum(0x0600, 0x0640, 0x80);
        LbzRollingDrumInstance incoming = drum(0x0700, 0x0640, 0x80);
        outgoing.setSlotIndex(6);
        incoming.setSlotIndex(13);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(outgoing, incoming));
        outgoing.setServices(new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });
        TestablePlayableSprite player = groundedPlayer(0x0600, 0x0640);
        outgoing.update(0, player);
        player.setFlipAngle(0x44);
        player.setCentreXPreserveSubpixel((short) 0x0683);
        player.setCentreY((short) 0x0645);

        outgoing.update(1, player);
        assertTrue(player.getAir(),
                "a receiver in a later SST slot cannot capture before the outgoing controller releases");

        incoming.update(1, player);
        assertFalse(player.getAir());
        assertEquals(0, player.getFlipAngle(),
                "the later receiver observes live Status_InAir and runs Player_TouchFloor");
    }

    @Test
    void activeRideWritesYFromRomSinePathAndAdvancesPerPlayerAngle() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);

        drum.update(0, player);
        drum.update(1, player);

        int expectedY = expectedRideY(0x81, player.getYRadius(), 0x0600);
        assertEquals(expectedY, player.getCentreY() & 0xFFFF,
                "loc_2C4BA writes y_pos from GetSineCosine(angle), y_radius, and the drum centre");
        assertEquals(0x01, player.getFlipAngle(),
                "loc_2C4BA stores angle+$80 in flip_angle");
        assertEquals(0x83, drum.getRideAngleForTest(player),
                "loc_2C4BA increments the per-player angle byte by 2 each active ride frame");
        assertTrue(player.isHighPriority(),
                "loc_2C506 keeps art_tile priority when angle+$80 remains positive");
    }

    @Test
    void activeRideLeavesTumbleMappingOwnedByPlayerAnimator() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);

        drum.update(0, player);
        player.setObjectMappingFrameControl(false);
        player.setMappingFrame(0x37);
        drum.update(1, player);

        assertFalse(player.isObjectMappingFrameControl(),
                "Obj31 updates flip_angle after Animate and must not seize mapping ownership");
        assertEquals(0x37, player.getMappingFrame(),
                "The object pass must retain the Anim_Tumble mapping published by the earlier player slot");
    }

    @Test
    void activeRideAppliesRomNegativeTumbleRenderFlagsForRightFacingPlayer() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);
        player.setDirection(Direction.RIGHT);

        drum.update(0, player);
        drum.update(1, player);

        assertFalse(player.getRenderHFlip());
        assertFalse(player.getRenderVFlip(),
                "Obj31 must leave render flags at the values published by the earlier Anim_Tumble pass");
    }

    @Test
    void activeRideAppliesRomNegativeTumbleRenderFlagsForLeftFacingPlayer() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05AD);
        player.setDirection(Direction.LEFT);

        drum.update(0, player);
        drum.update(1, player);

        assertFalse(player.getRenderHFlip());
        assertFalse(player.getRenderVFlip(),
                "Obj31 updates flip_angle after Animate without retroactively changing render flags");
    }

    @Test
    void latchedStaleAirBitDoesNotDropRiderThroughBottom() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05C0);
        player.setGSpeed((short) 0x0200);

        drum.update(0, player);
        drum.update(1, player);
        player.setCentreXPreserveSubpixel((short) 0x1830);
        player.setCentreY((short) 0x0660);
        player.setAir(true);
        player.setOnObject(true);
        player.setLatchedSolidObject(Sonic3kObjectIds.LBZ_ROLLING_DRUM, drum);
        drum.update(2, player);

        assertTrue(player.isOnObject(),
                "A non-jumping, non-hurt rider still latched to Obj31 should remain captured inside the horizontal window");
        assertFalse(player.getAir(),
                "The engine's stale air bit must be cleared instead of taking loc_2C48A's release branch");
        assertTrue(drum.isRidingForTest(player));
    }

    @Test
    void nativeP2AirBitReleasesBeforeSinePathPositioning() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite p1 = groundedPlayer(0x1000, 0x0500);
        TestablePlayableSprite p2 = groundedPlayer(0x1800, 0x05C0);
        drum.setServices(new TestObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> p1, () -> List.of(p2));
            }
        });
        drum.update(0, p1);
        assertTrue(drum.isNativeRidingForTest(1));
        p2.setCentreY((short) 0x05D0);
        p2.setAir(true);

        drum.update(1, p1);

        assertFalse(drum.isNativeRidingForTest(1));
        assertTrue(p2.getAir());
        assertEquals(0x05D0, p2.getCentreY() & 0xFFFF,
                "loc_2C46E branches to release on native P2 Status_InAir before loc_2C4BA writes y_pos");
    }

    @Test
    void rightMovingRiderWithLostPlayerLatchIsReattachedInsideHorizontalWindow() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05C0);
        player.setGSpeed((short) 0x0300);

        drum.update(0, player);
        drum.update(1, player);
        player.setCentreXPreserveSubpixel((short) 0x1838);
        player.setCentreY((short) 0x0660);
        player.setAir(true);
        player.setOnObject(false);
        player.setLatchedSolidObjectId(0);
        drum.update(2, player);

        assertTrue(player.isOnObject(),
                "Obj31 standing state still owns Sonic while x_pos is within -subtype..+subtype-1");
        assertFalse(player.getAir(),
                "A lost player-side Status_OnObj latch must be repaired instead of dropping the rider from the bottom");
        assertTrue(drum.isRidingForTest(player));
    }

    @Test
    void leavingHorizontalWindowReleasesWithFlipRecoveryState() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05C0);

        drum.update(0, player);
        drum.update(1, player);
        assertFalse(player.isObjectMappingFrameControl());
        player.setCentreXPreserveSubpixel((short) 0x1840);
        player.setAir(false);
        player.setFlipsRemaining(0x22);
        player.setFlipSpeed(0x08);
        drum.update(2, player);

        assertFalse(player.isOnObject(), "loc_2C48A clears Status_OnObj when x_pos exits the drum range");
        assertEquals(0, player.getLatchedSolidObjectId());
        assertTrue(player.getAir(), "loc_2C48A sets Status_InAir on release");
        assertEquals(0, player.getFlipsRemaining(), "loc_2C48A clears flips_remaining");
        assertEquals(4, player.getFlipSpeed(), "loc_2C48A writes flip_speed=4");
        assertFalse(player.isObjectMappingFrameControl(),
                "Object-owned tumble mapping must end when loc_2C48A releases the player");
    }

    @Test
    void airborneRiderBelowDrumGetsDownwardVelocityOnRelease() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite player = groundedPlayer(0x1800, 0x05C0);

        drum.update(0, player);
        player.setAir(true);
        player.setOnObject(false);
        player.setJumping(true);
        player.setCentreY((short) 0x0610);
        drum.update(1, player);

        assertFalse(player.isOnObject());
        assertEquals((short) 0x0400, player.getYSpeed(),
                "loc_2C4A8 writes y_vel=$400 when an airborne rider has crossed below the drum centre");
    }

    @Test
    void nativeP2UsesIndependentAngleStateInSameObjectUpdate() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite sonic = groundedPlayer(0x1800, 0x05AD);
        TestablePlayableSprite tails = groundedPlayer(0x17F8, 0x05AD);
        drum.setServices(new TestObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> sonic, () -> List.of(tails));
            }
        });

        drum.update(0, sonic);

        assertTrue(sonic.isOnObject());
        assertTrue(tails.isOnObject(), "loc_2C3CA processes Player_2 after Player_1 in the same object update");
        assertEquals(0x81, drum.getNativeRideAngleForTest(0));
        assertEquals(0x81, drum.getNativeRideAngleForTest(1),
                "The ROM stores P1/P2 drum angles in adjacent bytes, not one shared global angle");
    }

    @Test
    void rewindRoundTripsNativeRideAngleState() {
        LbzRollingDrumInstance drum = drum(0x1800, 0x0600, 0x40);
        TestablePlayableSprite sonic = groundedPlayer(0x1800, 0x05AD);
        TestablePlayableSprite tails = groundedPlayer(0x17F8, 0x05AD);
        drum.setServices(new TestObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> sonic, () -> List.of(tails));
            }
        });
        drum.update(0, sonic);
        drum.update(1, sonic);

        PerObjectRewindSnapshot snapshot = drum.captureRewindState();

        sonic.setCentreXPreserveSubpixel((short) 0x1840);
        tails.setCentreXPreserveSubpixel((short) 0x1840);
        drum.update(2, sonic);
        assertFalse(drum.isNativeRidingForTest(0));
        assertFalse(drum.isNativeRidingForTest(1));

        drum.restoreRewindState(snapshot);

        assertTrue(drum.isNativeRidingForTest(0));
        assertTrue(drum.isNativeRidingForTest(1));
        assertEquals(0x83, drum.getNativeRideAngleForTest(0));
        assertEquals(0x83, drum.getNativeRideAngleForTest(1));
    }

    private static LbzRollingDrumInstance drum(int x, int y, int subtype) {
        return new LbzRollingDrumInstance(new ObjectSpawn(
                x, y, Sonic3kObjectIds.LBZ_ROLLING_DRUM, subtype, 0, false, 0));
    }

    private static TestablePlayableSprite groundedPlayer(int x, int y) {
        TestablePlayableSprite player = new UnitPlayableSprite("sonic", (short) x, (short) y);
        player.setAir(false);
        return player;
    }

    private static int expectedRideY(int angle, int yRadius, int drumY) {
        int cos = TrigLookupTable.cosHex(angle);
        int radius = (yRadius << 8) + 0x4000;
        return drumY + ((cos * radius) >> 16);
    }

    private static ZoneRuntimeRegistry lbzRuntimeRegistry() {
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(new LbzZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE));
        return registry;
    }

    private static final class UnitPlayableSprite extends TestablePlayableSprite {
        private boolean animationRestartForced;

        private UnitPlayableSprite(String characterCode, short x, short y) {
            super(characterCode, x, y);
        }

        @Override
        public void setAir(boolean air) {
            setAirForTest(air);
        }

        @Override
        public void forceAnimationRestart() {
            animationRestartForced = true;
        }

        private boolean wasAnimationRestartForced() {
            return animationRestartForced;
        }
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
