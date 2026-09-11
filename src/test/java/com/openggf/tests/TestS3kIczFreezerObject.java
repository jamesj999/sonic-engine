package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.SessionManager;
import com.openggf.game.LevelGamestate;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczFreezerObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelManager;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kIczFreezerObject {

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        clearConstructionContext();
    }

    @Test
    void registryCreatesIczFreezerInstanceAndProfileMarksS3klSlotImplemented() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));

        assertInstanceOf(IczFreezerObjectInstance.class, instance);
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(Sonic3kObjectIds.ICZ_FREEZER));
    }

    @Test
    void touchResponseProfilePreservesOneRegionMultiTouchAtCurrentObjectPosition() {
        IczFreezerObjectInstance freezer = createFreezer(new RecordingServices(),
                new ObjectSpawn(0x02C0, 0x0180, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));

        TouchResponseProfile profile = freezer.getTouchResponseProfile();

        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertTrue(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                profile.stopAfterFirstOverlapPolicy());
        assertEquals(0x9A, freezer.getCollisionFlags());
        assertEquals(0, freezer.getCollisionProperty());
        assertEquals(1, freezer.getMultiTouchRegions().length);
        assertEquals(0x02C0, freezer.getMultiTouchRegions()[0].x());
        assertEquals(0x0180, freezer.getMultiTouchRegions()[0].y());
        assertEquals(0x9A, freezer.getMultiTouchRegions()[0].collisionFlags());
    }

    @Test
    void nearbyPlayerStartsFrostCycleAndSpawnsCaptureCloudEveryOtherPhase() {
        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x01C1, (short) 0x0100);
        freezer.update(0, player);

        assertTrue(freezer.isFrostCycleActiveForTesting());
        assertEquals(List.of(Sonic3kSfx.FROST_PUFF.id), services.playedSfx);

        for (int frame = 1; frame <= 65; frame++) {
            freezer.update(frame, player);
        }

        assertTrue(freezer.isFreezeJetActiveForTesting());
        assertEquals(1, freezer.captureCloudsSpawnedForTesting());
        assertEquals(33, freezer.frostPuffsSpawnedForTesting(),
                "ROM spawns a frost puff immediately, then every second active frame");
    }

    @Test
    void freezerRangeGateSamplesThePostMovementNativeXPosition() {
        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x01C1, (short) 0x0100);
        player.setXSpeed((short) -0x0300);

        freezer.update(0, player);

        assertFalse(freezer.isFrostCycleActiveForTesting(),
                "Find_SonicTails runs after the player slot moves from distance $3F to $42");
    }

    @Test
    void managedFreezerWaitsForPlaceholderRenderBeforeInitialization() {
        ObjectManager manager = mock(ObjectManager.class);
        RecordingServices services = new RecordingServices() {
            @Override
            public ObjectManager objectManager() {
                return manager;
            }
        };
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x0200, (short) 0x0100);

        freezer.update(0, player);
        assertFalse(freezer.isFrostCycleActiveForTesting());

        freezer.refreshPostCameraRenderState();
        freezer.update(1, player);
        assertFalse(freezer.isFrostCycleActiveForTesting(),
                "Obj_WaitOffscreen restores the saved entry point and returns");

        freezer.update(2, player);
        assertTrue(freezer.isFrostCycleActiveForTesting());
    }

    @Test
    void captureCloudFreezesPlayerThenBlockReleasesWithHurtAndInvulnerability() {
        installLevelGamestate();

        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0204, (short) 0x0134);
        player.setSubpixelRaw(0x2200, 0x7400);
        player.setRingCount(10);
        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);
        cloud.setServices(services);

        for (int frame = 0; frame <= 32; frame++) {
            cloud.update(frame, player);
        }

        IczFreezerObjectInstance.FrozenPlayerBlock block = cloud.frozenBlockForTesting();
        assertTrue(player.isObjectControlled(), "Freezer child should take over player control");
        assertTrue(player.isObjectControlSuppressesMovement(), "Freezer capture should suppress player movement");
        assertFalse(player.isObjectControlAllowsCpu(), "Freezer capture should not leave CPU movement enabled");
        assertEquals(0x0204, player.getCentreX(), "Capture must not shift x_pos while applying frozen animation");
        assertEquals(0x0134, player.getCentreY(), "Capture must not shift y_pos while applying frozen animation");
        assertEquals(0x2200, player.getXSubpixelRaw(),
                "Capture setup writes only x_pos while applying the frozen animation");
        assertEquals(0x7400, player.getYSubpixelRaw(),
                "Capture setup writes only y_pos while applying the frozen animation");
        assertEquals(0x0204, block.getX(), "Frozen block spawns at the ROM capture x_pos");
        assertEquals(0x0134, block.getY(), "Frozen block spawns at the ROM capture y_pos");
        assertEquals(0x1A, player.getAnimationId());
        assertSame(player, block.capturedPlayerForTesting());

        block.setServices(services);
        for (int frame = 32; frame <= 160; frame++) {
            block.update(frame, player);
        }

        assertFalse(player.isObjectControlled(), "Frozen block should release control when it breaks");
        assertFalse(player.isObjectControlSuppressesMovement(), "Frozen block should clear movement suppression on release");
        assertFalse(player.isObjectControlAllowsCpu(), "Frozen block should clear CPU movement allowance on release");
        assertEquals(120, player.getInvulnerableFrames());
        assertTrue(block.isDestroyed());
        assertEquals(12, block.debrisSpawnedForTesting());
    }

    @Test
    void captureCloudFreezesNativeSidekickParticipant() {
        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0400, (short) 0x0134);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0204, (short) 0x0134);
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setXSpeed((short) 0x0827);
        tails.setYSpeed((short) 0);
        tails.setGSpeed((short) 0x0827);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> List.of(tails)));

        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);
        cloud.setServices(services);

        for (int frame = 0; frame <= 32; frame++) {
            cloud.update(frame, main);
        }

        IczFreezerObjectInstance.FrozenPlayerBlock block = cloud.frozenBlockForTesting();
        assertFalse(main.isObjectControlled(), "Distant main player should not consume the sidekick capture");
        assertTrue(tails.isObjectControlled(), "Capture cloud should scan native P2/Tails, not only update() player");
        assertTrue(tails.getAir());
        assertEquals(0, tails.getXSpeed());
        assertEquals(0, tails.getYSpeed());
        assertEquals(0, tails.getGSpeed());
        assertSame(tails, block.capturedPlayerForTesting());
    }

    @Test
    void captureCloudFreezesEveryConfiguredPlayerInRange() {
        ObjectManager manager = mock(ObjectManager.class);
        RecordingServices services = new RecordingServices() {
            @Override
            public ObjectManager objectManager() {
                return manager;
            }
        };
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0202, (short) 0x0134);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0204, (short) 0x0134);
        TestablePlayableSprite extension = new TestablePlayableSprite("tails", (short) 0x0206, (short) 0x0134);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> List.of(nativeP2, extension)));

        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);
        cloud.setServices(services);
        cloud.update(0, main);
        cloud.update(1, main);

        assertTrue(main.isObjectControlled());
        assertTrue(nativeP2.isObjectControlled());
        assertTrue(extension.isObjectControlled());
        verify(manager, times(2)).addDynamicObjectAfterCurrentNextFrame(
                org.mockito.ArgumentMatchers.any(IczFreezerObjectInstance.FrozenPlayerBlock.class));
        verify(manager).addRewindableAuxiliaryDynamicObject(
                org.mockito.ArgumentMatchers.any(IczFreezerObjectInstance.FrozenPlayerBlock.class));
    }

    @Test
    void captureCloudFreezesExtendedSidekickWithoutNativeObjectSlot() {
        ObjectManager manager = mock(ObjectManager.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((AbstractObjectInstance) invocation.getArgument(0)).setDestroyed(true);
            return null;
        }).when(manager).addDynamicObjectAfterCurrentNextFrame(
                org.mockito.ArgumentMatchers.any(IczFreezerObjectInstance.FrozenPlayerBlock.class));
        RecordingServices services = new RecordingServices() {
            @Override
            public ObjectManager objectManager() {
                return manager;
            }
        };
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0400, (short) 0x0134);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0400, (short) 0x0134);
        TestablePlayableSprite extension = new TestablePlayableSprite("tails", (short) 0x0204, (short) 0x0134);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> List.of(nativeP2, extension)));

        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);
        cloud.setServices(services);
        cloud.update(0, main);
        cloud.update(1, main);

        assertTrue(extension.isObjectControlled());
        verify(manager).addRewindableAuxiliaryDynamicObject(
                org.mockito.ArgumentMatchers.any(IczFreezerObjectInstance.FrozenPlayerBlock.class));
    }

    @Test
    void parentUnloadLetsCaptureCloudEnterRomOffPhaseScanner() {
        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0204, (short) 0x0134);
        freezer.update(0, player);
        for (int frame = 1; frame <= 65; frame++) {
            freezer.update(frame, player);
        }

        IczFreezerObjectInstance.CaptureCloud cloud = freezer.lastCaptureCloudForTesting();
        cloud.setServices(services);
        assertTrue(freezer.isFreezeJetActiveForTesting(), "precondition: cloud is waiting on the active parent jet");
        assertNull(cloud.frozenBlockForTesting(), "precondition: active-phase capture delay has not elapsed");

        freezer.onUnload();
        assertNull(freezer.lastCaptureCloudForTesting(),
                "Unloading the parent slot must drop its reference to the independently surviving child SST");
        cloud.update(66, player);
        assertNull(cloud.frozenBlockForTesting(),
                "ROM off-phase init runs first after the parent slot is cleared");
        cloud.update(67, player);

        assertTrue(player.isObjectControlled(),
                "A capture cloud whose parent was unloaded must keep scanning in its off-phase routine");
        assertSame(player, cloud.frozenBlockForTesting().capturedPlayerForTesting());
    }

    @Test
    void captureCloudLifetimeIsOwnedByOffPhaseTimerNotCameraRange() {
        IczFreezerObjectInstance freezer = createFreezer(new RecordingServices(),
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));

        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);

        assertTrue(cloud.isPersistent(),
                "ROM capture child loc_8A7A2 scans/deletes by its own timer and does not tail-call Sprite_CheckDeleteTouch");
    }

    @Test
    void frozenPlayerBlockClearsLeftwardVelocityAtCameraSideClampBeforeMoving() {
        RecordingServices services = new RecordingServices();
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x40A9);
        services.withCamera(camera);

        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x3FC4, (short) 0x0373);
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(tails, 0x3FC4, 0x0373, 0x4060, false);
        block.setServices(services);

        block.update(2837, tails);

        assertEquals(0x3FC4, block.getX(),
                "ROM loc_8A80C clears negative x_vel when Camera_X_pos+$20 has crossed x_pos");
        assertEquals(0x036F, block.getY());
        assertEquals(0x3FC4, tails.getCentreX());
        assertEquals(0x036F, tails.getCentreY());
    }

    @Test
    void frozenPlayerBlockPersistsOutsideCameraRangeWhileCarryingPlayer() {
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x3FC4, (short) 0x0373);
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(tails, 0x3FC4, 0x0373, 0x4060, false);

        assertTrue(block.isPersistent(),
                "ROM loc_8A84C keeps the frozen-player block alive through Draw_Sprite/player sync, not MarkObjGone");
    }

    @Test
    void frozenPlayerBlockBreaksOnRomPreDecrementFrameAndOverridesKnockbackDirection() {
        installLevelGamestate();

        RecordingServices services = new RecordingServices();
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x3FC4, (short) 0x0373);
        tails.setCpuControlled(true);
        tails.setRingCount(42);
        tails.setRolling(true);
        tails.setDirection(com.openggf.physics.Direction.RIGHT);
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(tails, 0x3FC4, 0x0373, 0x4060, false);
        block.setServices(services);

        for (int frame = 32; frame < 159; frame++) {
            block.update(frame, tails);
        }
        assertFalse(block.isDestroyed(), "ROM $2E has not gone negative before the 128th loc_8A84C tick");

        block.update(159, tails);

        assertTrue(block.isDestroyed(), "ROM loc_8A84C pre-decrements $2E and breaks as soon as it is negative");
        assertFalse(tails.isObjectControlled(), "loc_8A8BA clears object_control on the break frame");
        assertFalse(tails.getRolling(), "HurtCharacter clears rolling before the break frame is compared");
        assertEquals((short) -0x0200, tails.getXSpeed(),
                "loc_8A88A overwrites freezer-break x_vel from render_flags bit 0, not source-X comparison");
        assertEquals((short) -0x0400, tails.getYSpeed());
        assertEquals(42, tails.getRingCount(), "ROM Hurt_Sidekick does not spend the main player's rings");
        assertEquals(List.of(), services.lostRingSpawnFrames);
    }

    @Test
    void fallingNativePartnerShattersFrozenBlockAndBouncesWithoutHurtingCaptive() {
        installLevelGamestate();

        RecordingServices services = new RecordingServices();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x43A6, (short) 0x069C);
        sonic.setAnimationId(2);
        sonic.setYSpeed((short) 0x03E0);
        sonic.setRingCount(12);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x43B0, (short) 0x06B3);
        tails.setCpuControlled(true);
        tails.setRingCount(42);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> sonic, () -> List.of(tails)));
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(tails, 0x43B0, 0x06B3, 0x4360, false);
        block.setServices(services);

        block.update(23822, sonic);

        assertTrue(block.isDestroyed(), "ROM sub_8AA38 accepts a descending roll from the other native slot");
        assertEquals((short) -0x03E0, sonic.getYSpeed(), "The attacker bounces by negating y_vel");
        assertFalse(sonic.isHurt(), "Shattering the block does not damage the attacker");
        assertFalse(tails.isHurt(), "The attack-break path skips HurtCharacter for the captive");
        assertFalse(tails.isObjectControlled());
        assertTrue(tails.getAir());
        assertEquals(120, tails.getInvulnerableFrames());
        assertEquals(12, block.debrisSpawnedForTesting());
        assertEquals(List.of(), services.lostRingSpawnFrames);
    }

    @Test
    void fallingExtendedSidekickCanShatterFrozenBlock() {
        installLevelGamestate();

        RecordingServices services = new RecordingServices();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x43B0, (short) 0x06B3);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x4500, (short) 0x06B3);
        TestablePlayableSprite extension = new TestablePlayableSprite("tails", (short) 0x43A6, (short) 0x069C);
        extension.setAnimationId(2);
        extension.setYSpeed((short) 0x03E0);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> sonic, () -> List.of(nativeP2, extension)));
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(sonic, 0x43B0, 0x06B3, 0x4360, false);
        block.setServices(services);

        block.update(23822, sonic);

        assertTrue(block.isDestroyed());
        assertEquals((short) -0x03E0, extension.getYSpeed());
        assertFalse(sonic.isObjectControlled());
    }

    @Test
    void extendedCaptiveShatterKeepsDebrisOutOfNativeObjectPool() {
        installLevelGamestate();

        ObjectManager manager = mock(ObjectManager.class);
        RecordingServices services = new RecordingServices() {
            @Override
            public ObjectManager objectManager() {
                return manager;
            }
        };
        TestablePlayableSprite attacker =
                new TestablePlayableSprite("sonic", (short) 0x43A6, (short) 0x069C);
        attacker.setAnimationId(2);
        attacker.setYSpeed((short) 0x03E0);
        TestablePlayableSprite captive =
                new TestablePlayableSprite("sonic_p3", (short) 0x43B0, (short) 0x06B3);
        captive.setCpuControlled(true);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> attacker, () -> List.of(captive)));
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(
                        captive, 0x43B0, 0x06B3, 0x4360, false, false, true);
        block.setServices(services);

        block.update(23822, attacker);

        assertTrue(block.isDestroyed());
        verify(manager, times(12)).addRewindableAuxiliaryDynamicObject(any());
        verify(manager, org.mockito.Mockito.never()).addDynamicObjectAfterCurrent(any());
        verify(manager, org.mockito.Mockito.never()).addDynamicObjectAfterCurrentNextFrame(any());
    }

    @Test
    void frozenPlayerBlockSyncPreservesCapturedPlayerSubpixelsUntilBreak() {
        installLevelGamestate();

        RecordingServices services = new RecordingServices();
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x3FC4, (short) 0x0373);
        tails.setCpuControlled(true);
        tails.setSubpixelRaw(0x2200, 0x7400);
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(tails, 0x3FC4, 0x0373, 0x4060, false);
        block.setServices(services);

        block.update(32, tails);

        assertEquals(0x2200, tails.getXSubpixelRaw(),
                "ROM loc_8A84C writes only x_pos(a1), preserving the captured player's x_sub");
        assertEquals(0x7400, tails.getYSubpixelRaw(),
                "ROM loc_8A84C writes only y_pos(a1), preserving the captured player's y_sub");
        assertEquals(0x3FC2, tails.getCentreX());
        assertEquals(0x036F, tails.getCentreY());
    }

    @Test
    void frozenPlayerBlockReleaseSpendsRingsThroughLostRingPathWhenShieldless() {
        installLevelGamestate();

        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0204, (short) 0x0134);
        player.setRingCount(10);
        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);
        cloud.setServices(services);

        for (int frame = 0; frame <= 32; frame++) {
            cloud.update(frame, player);
        }

        IczFreezerObjectInstance.FrozenPlayerBlock block = cloud.frozenBlockForTesting();
        block.setServices(services);
        for (int frame = 32; frame <= 160; frame++) {
            block.update(frame, player);
        }

        assertTrue(player.isHurt(), "Freeze break damage should put shieldless Sonic into hurt state");
        assertEquals(0, player.getRingCount(),
                "Freeze break damage should spend rings like ordinary touch damage");
        assertEquals(List.of(159), services.lostRingSpawnFrames,
                "Freeze break damage should spawn lost rings before applying hurt");
    }

    @Test
    void captureCloudDeletesAfterRomOffPhaseGraceAndStopsFreezingPlayers() {
        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);
        cloud.setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0204, (short) 0x0200);

        for (int frame = 0; frame <= 33; frame++) {
            cloud.update(frame, player);
        }

        assertTrue(cloud.isDestroyed(),
                "ROM switches the capture child to Obj_Wait/Go_Delete_Sprite when the freezer jet turns off");

        player.setCentreY((short) 0x0134);
        cloud.update(34, player);

        assertFalse(player.isObjectControlled(),
                "The off-phase gap must not keep an invisible freezer hitbox after the ROM delete grace");
        assertNull(cloud.frozenBlockForTesting());
    }

    @Test
    void frozenPlayerBlockStopsCarryingPlayerDownwardWhenItHitsTerrain() {
        installFlatFloorAt(0x0170);

        RecordingServices services = new RecordingServices();
        IczFreezerObjectInstance freezer = createFreezer(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.ICZ_FREEZER, 0, 0, false, 0));
        freezer.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0204, (short) 0x0134);
        IczFreezerObjectInstance.CaptureCloud cloud =
                freezer.createCaptureCloudForTesting(0x0200, 0x0130, false);
        cloud.setServices(services);

        for (int frame = 0; frame <= 32; frame++) {
            cloud.update(frame, player);
        }

        IczFreezerObjectInstance.FrozenPlayerBlock block = cloud.frozenBlockForTesting();
        block.setServices(services);
        for (int frame = 33; frame < 90; frame++) {
            block.update(frame, player);
        }

        int landedY = player.getCentreY();
        for (int frame = 90; frame < 110; frame++) {
            block.update(frame, player);
        }

        assertEquals(landedY, player.getCentreY(),
                "ROM frozen block runs ObjCheckFloorDist and stops carrying Sonic downward after terrain contact");
        assertTrue(player.getCentreY() < 0x01A0, "Frozen Sonic should remain near the floor contact, not fall through");
    }

    @Test
    void frostPuffUsesRomOffsetTableForFastDeepMistTravel() {
        AbstractObjectInstance puff = createFrostPuffForTesting(0x0200, 0x010C, false);
        puff.setServices(new RecordingServices());

        for (int frame = 0; frame < 35; frame++) {
            puff.update(frame, null);
        }

        assertTrue(puff.getY() >= 0x0148,
                "ROM sub_8A916 drives the mist near the $52 child_dy table peak, not one pixel per step");
    }

    @Test
    void frostPuffAppliesRomRandomChildOffsets() {
        RecordingServices services = new RecordingServices();
        services.rng().setSeed(0x1234);
        AbstractObjectInstance puff = createFrostPuffForTesting(0x0200, 0x010C, false);
        puff.setServices(services);

        puff.update(0, null);
        puff.update(1, null);

        assertEquals(0x0201, puff.getX(), "ROM masks Random_Number low byte into child_dx");
        assertEquals(0x010F, puff.getY(), "ROM adds the swapped Random_Number byte into child_dy");
    }

    @Test
    void frostPuffOnlyRendersOnRomDrawSpriteFrames() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_PLATFORMS)).thenReturn(renderer);

        AbstractObjectInstance puff = createFrostPuffForTesting(0x0200, 0x010C, false);
        puff.setServices(new RenderingServices(renderManager));
        List<GLCommand> commands = new ArrayList<>();

        puff.update(0, null);
        puff.appendRenderCommands(commands);
        verify(renderer, times(0)).drawFrameIndex(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(2));

        puff.update(1, null);
        puff.appendRenderCommands(commands);
        verify(renderer, times(1)).drawFrameIndex(0x16, 0x01FF, 0x0110, false, false, 2);

        puff.update(2, null);
        puff.appendRenderCommands(commands);
        verify(renderer, times(1)).drawFrameIndex(0x16, 0x01FF, 0x0110, false, false, 2);

        puff.update(3, null);
        puff.appendRenderCommands(commands);
        verify(renderer, times(1)).drawFrameIndex(0x16, 0x01FF, 0x0110, false, false, 2);

        puff.update(4, null);
        puff.appendRenderCommands(commands);
        verify(renderer, times(1)).drawFrameIndex(0x16, 0x0202, 0x0113, false, false, 2);
    }

    @Test
    void frostPuffDeletesWhenRomScriptOffsetReaches48() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_PLATFORMS)).thenReturn(renderer);

        AbstractObjectInstance puff = createFrostPuffForTesting(0x0200, 0x010C, false);
        assertTrue(puff.isPersistent(),
                "loc_8A72C owns deletion and does not run a camera-range cleanup tail");
        puff.setServices(new RenderingServices(renderManager));
        List<GLCommand> commands = new ArrayList<>();

        for (int frame = 0; frame <= 51; frame++) {
            puff.update(frame, null);
            puff.appendRenderCommands(commands);
        }

        assertFalse(puff.isDestroyed(), "ROM keeps the puff until the next $39 += 4 reaches $48");
        verify(renderer, times(17)).drawFrameIndex(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(2));

        puff.update(52, null);
        puff.appendRenderCommands(commands);

        assertTrue(puff.isDestroyed(), "ROM deletes instead of drawing once the script offset reaches $48");
        verify(renderer, times(17)).drawFrameIndex(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(2));
    }

    private static IczFreezerObjectInstance createFreezer(ObjectServices services, ObjectSpawn spawn) {
        setConstructionContext(services);
        try {
            return new IczFreezerObjectInstance(spawn);
        } finally {
            clearConstructionContext();
        }
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices services) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(services);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void installFlatFloorAt(int floorProbeY) {
        var mode = SessionManager.openGameplaySession(new Sonic3kGameModule());
        GameplaySessionFactory.attachManagers(mode, EngineServices.current());
        ChunkDesc floorDesc = new ChunkDesc(0x1000);
        SolidTile floorTile = new SolidTile(1, filled(8), new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getChunkDescAt(eq((byte) 0), anyInt(), anyInt())).thenAnswer(invocation -> {
            int y = invocation.getArgument(2, Integer.class);
            return y >= floorProbeY ? floorDesc : ChunkDesc.EMPTY;
        });
        when(levelManager.getSolidTileForChunkDesc(eq(floorDesc), eq(0x0C), anyBoolean())).thenReturn(floorTile);
        mode.attachLevelManagers(mode.getWaterSystem(), mode.getParallaxManager(),
                mode.getTerrainCollisionManager(), mode.getCollisionSystem(),
                mode.getSpriteManager(), levelManager);
    }

    private static void installLevelGamestate() {
        var mode = SessionManager.openGameplaySession(new Sonic3kGameModule());
        GameplaySessionFactory.attachManagers(mode, EngineServices.current());
        LevelGamestate levelState = new LevelGamestate();
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getLevelGamestate()).thenReturn(levelState);
        mode.attachLevelManagers(mode.getWaterSystem(), mode.getParallaxManager(),
                mode.getTerrainCollisionManager(), mode.getCollisionSystem(),
                mode.getSpriteManager(), levelManager);
    }

    private static byte[] filled(int value) {
        byte[] data = new byte[SolidTile.TILE_SIZE_IN_ROM];
        java.util.Arrays.fill(data, (byte) value);
        return data;
    }

    private static AbstractObjectInstance createFrostPuffForTesting(int x, int y, boolean verticalFlip) {
        try {
            Class<?> type = Class.forName(
                    "com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$FrostPuff");
            Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class, boolean.class);
            constructor.setAccessible(true);
            return (AbstractObjectInstance) constructor.newInstance(x, y, verticalFlip);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                 | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static class RecordingServices extends StubObjectServices {
        private final List<Integer> playedSfx = new ArrayList<>();
        private final List<Integer> lostRingSpawnFrames = new ArrayList<>();
        private Camera camera;

        private RecordingServices() {
            withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        }

        private void withCamera(Camera camera) {
            this.camera = camera;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }

        @Override
        public void spawnLostRings(PlayableEntity player, int frameCounter) {
            lostRingSpawnFrames.add(frameCounter);
            if (player instanceof com.openggf.sprites.playable.AbstractPlayableSprite sprite) {
                sprite.setRingCount(0);
            }
        }

        @Override
        public Camera camera() {
            return camera;
        }
    }

    private static final class RenderingServices extends StubObjectServices {
        private final ObjectRenderManager renderManager;

        private RenderingServices(ObjectRenderManager renderManager) {
            this.renderManager = renderManager;
            withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }
    }
}
