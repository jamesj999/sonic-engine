package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameRng;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzBalloonInstance {

    @Test
    void touchResponseProfileUsesS3kSpecialContinuousRenderFlagPolicy() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x1990, 0x012C, 0x41, 0, 0, false, 0));

        TouchResponseProfile profile = balloon.getTouchResponseProfile(false);

        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY, profile.categoryDecodeMode());
        assertTrue(profile.continuousCallbacks());
        assertTrue(profile.requiresRenderFlagForTouch());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    @Test
    void collisionResponseListDereferencesLiveBobPosition() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x1920, 0x022C, 0x41, 2, 0, false, 0));

        assertTrue(balloon.usesCurrentTouchResponseState(),
                "S3K Touch_Loop dereferences the live SST y_pos published after the balloon bob");
    }

    @Test
    void touchResponseProfileStopsContinuousCallbacksAfterMovingOffscreen() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x17C0, 0x860, 0x41, 0, 0, false, 0));
        balloon.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        for (int i = 0; i < 7; i++) {
            balloon.update(i, player);
        }

        TouchResponseProfile profile = balloon.getTouchResponseProfile(false);

        assertFalse(profile.continuousCallbacks(),
                "CNZ balloon continuous touch callbacks mirror the old movedOffscreen hook gate");
        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY, profile.categoryDecodeMode());
    }

    @Test
    void constructorDoesNotConsumeRngBeforeRomInitRoutineRuns() {
        long seed = 0x14A7ABBBL;
        GameRng rng = new GameRng(GameRng.Flavour.S3K, seed);
        GameRng expected = new GameRng(GameRng.Flavour.S3K, seed);
        TestObjectServices services = new TestObjectServices().withRng(rng);

        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x1990, 0x012C, 0x41, 0, 0, false, 0));
        balloon.setServices(services);

        assertEquals(seed, rng.getSeed(),
                "Obj_CNZBalloon calls Random_Number from its init routine, not placement construction");

        int expectedAngle = expected.nextByte();
        balloon.update(0, null);

        assertEquals(expected.getSeed(), rng.getSeed());
        assertEquals(0x012C + (TrigLookupTable.sinHex(expectedAngle) >> 5), balloon.getY());
    }

    @Test
    void hydrateFromRomSnapshotRestoresRandomBobPhase() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x180, 0x680, 0x41, 0, 0, false, 0));
        balloon.setServices(new TestObjectServices());

        balloon.hydrateFromRomSnapshot(new RomObjectSnapshot(
                Map.of(
                        0x26, 0xC0,
                        0x28, 0xD7
                ),
                Map.of(
                        0x08, 0x180,
                        0x0C, 0x678,
                        0x32, 0x680
                )));

        assertEquals(0x678, balloon.getY());
        balloon.update(0, null);
        assertEquals(0x680 + (TrigLookupTable.sinHex(0xC0) >> 5), balloon.getY());
    }

    @Test
    void updateDoesNotLaunchOutsideRomTouchRegion() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0, 0, 0x41, 0, 0, false, 0));
        balloon.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.applyRollingRadii(false);
        player.setCentreX((short) 12);
        player.setCentreY((short) 25);
        player.setYSpeed((short) 0x370);

        balloon.update(0, player);

        assertFalse(balloon.isPoppedForTest());
        assertEquals((short) 0x370, player.getYSpeed());
    }

    @Test
    void touchResponseLaunchesAndPopsBalloon() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0, 0, 0x41, 0, 0, false, 0));
        balloon.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setYSpeed((short) 0x370);

        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);

        assertTrue(balloon.isPoppedForTest());
        assertEquals((short) -0x700, player.getYSpeed());
        assertTrue(player.getAir());
        assertEquals(0xD7, balloon.getCollisionFlags(),
                "ROM Obj_CNZBalloon leaves collision_flags set after bset #0,anim(a0)");
    }

    @Test
    void poppedBalloonKeepsLaunchingUntilAnimationMovesOffscreen() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0, 0, 0x41, 0, 0, false, 0));
        balloon.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        player.setYSpeed((short) -0x6C8);
        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 1);

        assertTrue(balloon.isPoppedForTest());
        assertEquals((short) -0x700, player.getYSpeed(),
                "sub_317AE is not gated by the sound latch and re-applies y_vel while touching");
        assertEquals(0xD7, balloon.getCollisionFlags());
    }

    @Test
    void underwaterNegativeSubtypeLaunchesWithoutPlayerPositionSnap() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x15C0, 0x0B60, 0x41, 0x80, 0, false, 0));
        balloon.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setInWater(true);
        player.setCentreX((short) 0x15B5);
        player.setCentreY((short) 0x0B6C);

        balloon.update(0, null);
        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);

        assertEquals(0x15B5, player.getCentreX(),
                "sub_317AE's four sub_3181E calls clobber a1 with Obj_Bubbler before the later x_pos write");
        assertEquals(0x0B6C, player.getCentreY(),
                "Retail S3K launches the player but does not apply the intended underwater snap");
        assertEquals((short) -0x380, player.getYSpeed());

        player.setCentreX((short) 0x15B5);
        player.setCentreY((short) 0x0B6C);
        player.setYSpeed((short) 0);
        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 1);

        assertEquals(0x15B5, player.getCentreX(),
                "sub_317AE repeats y_vel while $34 suppresses effects, still without a player position snap");
        assertEquals(0x0B6C, player.getCentreY(),
                "Repeated popped-balloon contacts still launch but do not repeat the first-touch snap/effects block");
        assertEquals((short) -0x380, player.getYSpeed());
    }

    @Test
    void underwaterLaunchOverwritesFourthBubblerOffsetThroughClobberedA1() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        WaterSystem waterSystem = new WaterSystem() {
            @Override
            public boolean hasWater(int zoneId, int actId) {
                return true;
            }
        };
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withWaterSystem(waterSystem);
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x15C0, 0x0B60, 0x41, 0x80, 0, false, 0));
        balloon.setServices(services);
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        balloon.update(0, null);
        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        int balloonXAtTouch = balloon.getX();
        int balloonYAtTouch = balloon.getY();
        balloon.update(1, null);

        assertEquals(4, spawned.size());
        ObjectInstance fourth = spawned.get(3);
        assertEquals(balloonXAtTouch, fourth.getX(),
                "loc_31808 writes balloon x_pos through a1 after four child allocations");
        assertEquals(balloonYAtTouch, fourth.getY(),
                "loc_31808 writes balloon y_pos into the fourth Obj_Bubbler, not the player");
    }

    @Test
    void poppedBalloonAnimationMovesOffscreenForNormalDeletePath() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x17C0, 0x860, 0x41, 0, 0, false, 0));
        balloon.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        for (int i = 0; i < 7; i++) {
            balloon.update(i, player);
        }

        assertTrue(balloon.hasMovedOffscreenForTest());
        assertTrue(balloon.isDestroyed());
        assertEquals(0x7F00, balloon.getX());
        assertEquals(0, balloon.getCollisionFlags());
    }

    @Test
    void repeatedLaunchDoesNotRestartPopAnimation() {
        CnzBalloonInstance balloon =
                new CnzBalloonInstance(new ObjectSpawn(0x17C0, 0x860, 0x41, 0, 0, false, 0));
        balloon.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        balloon.update(0, player);
        balloon.update(1, player);
        balloon.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 2);
        for (int i = 2; i < 7; i++) {
            balloon.update(i, player);
        }

        assertTrue(balloon.hasMovedOffscreenForTest(),
                "bset #0,anim does not restart an already-selected pop script");
    }
}
