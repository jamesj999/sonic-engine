package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.IczFreezerObjectInstance;
import com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossGradualMaxXExtender;
import com.openggf.game.sonic3k.objects.bosses.IczEndBossEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.Level;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ResultsHardwareTimingFixture;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kIczEndBossObject {
    private static final int FINAL_DEFEAT_HANDOFF_FRAMES = 0xBA;
    private static final int ICZ_END_BOSS_ID = 0xBD;

    // Reset per-test state without discarding the @RequiresRom S3K gameplay session,
    // so registry and runtime-art ownership both remain tied to the S3KL fixture.
    @BeforeEach
    void resetS3kGameplayFixture() {
        TestEnvironment.resetPerTest();
    }

    @Test
    void registryCreatesIczEndBossInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));

        assertEquals("IczEndBossInstance", instance.getClass().getSimpleName());
    }

    @Test
    void initialStateMatchesRomSetupBeforeSharedBossGateCompletes() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        object.setServices(new RecordingServices());

        assertEquals(0x4490, object.getX());
        assertEquals(0x05B8, object.getY());
        assertEquals(8, invokeInt(instance, "getCollisionProperty"));
        assertEquals(0, invokeInt(instance, "getCollisionFlags"),
                "Obj_ICZEndBoss waits on Check_CameraInRange/sub_85D6A before exposing collision_flags=$CF");
        assertEquals(0xCF, invokeInt(instance, "getRoutineTimerForTesting"));
        assertEquals(0, invokeInt(instance, "getCurrentRoutine"));
        assertEquals(0x80, invokeInt(instance, "getYVelocityForTesting"));
        assertFalse((Boolean) instance.getClass().getMethod("isArenaGateCompleteForTesting").invoke(instance));
    }

    @Test
    void cameraGateLocksArenaAndStartsEndBossMusicAfterFadeGate() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        services.camera.setMaxY((short) 0x0700);
        object.setServices(services);

        instance.update(1, mock(PlayableEntity.class));

        assertEquals(0x4390, services.camera.getMinX() & 0xFFFF);
        assertEquals(0x4390, services.camera.getMaxX() & 0xFFFF);
        assertEquals(0x05F8, services.camera.getMinY() & 0xFFFF);
        assertEquals(0x0700, services.camera.getMaxY() & 0xFFFF,
                "loc_85CA4 writes Camera_target_max_Y_pos, not Camera_max_Y_pos, so vertical easing stays smooth");
        assertEquals(0x05F8, services.camera.getMaxYTarget() & 0xFFFF);
        assertEquals(Sonic3kObjectIds.ICZ_END_BOSS, services.gameState.getCurrentBossId());
        assertEquals(1, services.fadeOutCalls);
        assertEquals(0, services.lastMusicId);

        for (int frame = 2; frame <= 122; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
        }

        assertEquals(Sonic3kMusic.BOSS.id, services.lastMusicId);
        assertTrue((Boolean) instance.getClass().getMethod("isArenaGateCompleteForTesting").invoke(instance));
        assertEquals(2, invokeInt(instance, "getCurrentRoutine"),
                "The first live Obj_ICZEndBoss frame runs setup and enters the descent routine");
        assertEquals(0xCF, invokeInt(instance, "getRoutineTimerForTesting"));
        assertEquals(0xCF, ((TouchResponseProvider) instance).getCollisionFlags());
    }

    @Test
    void cameraGateFollowsCameraBeforeLockingArenaLikeSharedBossGate() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4350);
        services.camera.setY((short) 0x05F0);
        services.camera.setMinX((short) 0);
        services.camera.setMaxX((short) 0x7000);
        services.camera.setMinY((short) 0);
        services.camera.setMaxY((short) 0x0700);
        object.setServices(services);

        instance.update(1, mock(PlayableEntity.class));

        assertEquals(0x4350, services.camera.getMinX() & 0xFFFF,
                "sub_85CA4 writes the current Camera_X_pos into Camera_min_X_pos until the boss gate reaches $4390");
        assertEquals(0x7000, services.camera.getMaxX() & 0xFFFF,
                "The shared gate must not snap Camera_max_X_pos to the arena before the camera reaches the lock point");
        assertEquals(0x05F0, services.camera.getMinY() & 0xFFFF,
                "sub_85CA4 also follows Camera_Y_pos before locking to $05F8");
        assertFalse((Boolean) instance.getClass().getMethod("isArenaGateCompleteForTesting").invoke(instance));
    }

    @Test
    void arenaGateDoesNotSynthesizeSnowdustWhenNoPlacedEmitterExists() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        instance.update(1, mock(PlayableEntity.class));

        assertFalse(services.spawnedChildren.stream().anyMatch(IczSnowPileObjectInstance.class::isInstance),
                "Obj_ICZEndBoss initialization does not AllocateObject an emitter; snow comes from the placed subtype-$18 object");
    }

    @Test
    void bossSnowdustUsesIczPlatformSnowFramesAndStopsOnFinalHit() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        PatternSpriteRenderer platformRenderer = mock(PatternSpriteRenderer.class);
        when(platformRenderer.isReady()).thenReturn(true);
        when(services.renderManager.getRenderer(anyString())).thenReturn(platformRenderer);
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        IczSnowPileObjectInstance emitter = new IczSnowPileObjectInstance(
                new ObjectSpawn(0x44A0, 0x06A0, Sonic3kObjectIds.ICZ_SNOW_PILE,
                        0x18, 0, false, 0x06A0));
        emitter.setServices(services);
        when(services.objectManager.getActiveObjects()).thenReturn(List.of(emitter));

        instance.update(1, mock(PlayableEntity.class));
        for (int frame = 0; frame < 10; frame++) {
            emitter.update(10 + frame, mock(PlayableEntity.class));
        }
        AbstractObjectInstance particle = services.spawnedChildren.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> child.getClass().getSimpleName().equals("SnowdustParticle"))
                .findFirst()
                .orElseThrow();

        // AllocateObject only installs loc_8B6AE; the particle samples RNG and
        // initializes when its SST first executes.
        particle.update(20, mock(PlayableEntity.class));
        particle.update(21, mock(PlayableEntity.class));
        particle.appendRenderCommands(new ArrayList<>());
        particle.refreshPostCameraRenderState();

        org.mockito.Mockito.verify(services.renderManager, org.mockito.Mockito.atLeastOnce())
                .getRenderer(Sonic3kObjectArtKeys.ICZ_PLATFORMS);
        org.mockito.Mockito.verify(platformRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(anyInt(), anyInt(), anyInt(), eq(false), eq(false), eq(2));
        ArgumentCaptor<Integer> frameCaptor = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(platformRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(frameCaptor.capture(), anyInt(), anyInt(), eq(false), eq(false), eq(2));
        Assertions.assertTrue(frameCaptor.getAllValues().stream().anyMatch(frame -> frame == 0x0B || frame == 0x10),
                "loc_8B6AE draws snow particles through ObjDat3_8B838 / Map_ICZPlatforms frames $0B/$10");

        org.mockito.Mockito.clearInvocations(platformRenderer);
        AbstractObjectInstance.updateCameraBounds(
                particle.getX() - 0x20,
                particle.getY() - 0x20,
                particle.getX() + 0x140,
                particle.getY() + 0xE0,
                0);
        for (int frame = 0; frame < 5; frame++) {
            particle.update(40 + frame, mock(PlayableEntity.class));
            particle.appendRenderCommands(new ArrayList<>());
            particle.refreshPostCameraRenderState();
        }
        org.mockito.Mockito.verify(platformRenderer, org.mockito.Mockito.atLeast(2))
                .drawFrameIndex(anyInt(), anyInt(), anyInt(), eq(false), eq(false), eq(2));
        org.mockito.Mockito.verify(platformRenderer, org.mockito.Mockito.atMost(3))
                .drawFrameIndex(anyInt(), anyInt(), anyInt(), eq(false), eq(false), eq(2));

        stepFrames(instance, 122);
        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }

        assertTrue(emitter.isDestroyed(), "The boss snow emitter should stop as soon as the boss is destroyed");
    }

    @Test
    void descentTimerEntersSwingLoopWithRomVelocityAndFlags() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122 + 0xD0);

        assertEquals(4, invokeInt(instance, "getCurrentRoutine"));
        assertEquals(0x3F, invokeInt(instance, "getRoutineTimerForTesting"));
        assertEquals(0xC0, invokeInt(instance, "getYVelocityForTesting"));
        assertEquals(0x10, invokeInt(instance, "getSwingAmplitudeForTesting"));
        assertFalse((Boolean) instance.getClass().getMethod("isFrostPuffArmedForTesting").invoke(instance));
    }

    @Test
    void objWaitTimersFireOnRomPreDecrementFrame() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122 + 0xD0);

        assertEquals(4, invokeInt(instance, "getCurrentRoutine"),
                "Obj_Wait subtracts before checking BMI, so a $CF wait expires after $D0 frames, not $D1");
    }

    @Test
    void swingMotionUsesRomInitialDirectionAndVelocityLimit() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122 + 0xD0);

        instance.update(500, mock(PlayableEntity.class));

        assertEquals(0xB0, invokeInt(instance, "getYVelocityForTesting"),
                "Swing_UpAndDown starts with $38 bit 0 clear, so the first swing frame subtracts $40=$10 from y_vel=$C0");

        int maxAbsVelocity = 0;
        for (int frame = 0; frame < 80; frame++) {
            instance.update(501 + frame, mock(PlayableEntity.class));
            maxAbsVelocity = Math.max(maxAbsVelocity, Math.abs(invokeInt(instance, "getYVelocityForTesting")));
        }

        assertTrue(maxAbsVelocity <= 0xC0,
                "sub_72120 stores $3E=$C0, so Swing_UpAndDown must not use a wider y velocity bound");
    }

    @Test
    void bodyRenderingUsesIczPaletteBaseAndRobotnikShipUsesShipPalette() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);

        assertEquals(1, invokeInt(instance, "getBossPaletteLineForTesting"));
        assertEquals(1, invokeInt(instance, "getBodyPaletteBaseForTesting"));
        assertEquals(0, invokeInt(instance, "getRobotnikShipPaletteLineForTesting"));
    }

    @Test
    void bottomPlatformRendersBehindBodyAndUpperShell() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        PatternSpriteRenderer bossRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(bossRenderer.isReady()).thenReturn(true);
        when(shipRenderer.isReady()).thenReturn(true);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_END_BOSS)).thenReturn(bossRenderer);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        stepFrames(instance, 122);
        instance.appendRenderCommands(new ArrayList<>());

        InOrder order = inOrder(bossRenderer);
        order.verify(bossRenderer).drawFrameIndexWithPaletteBase(eq(2), anyInt(), anyInt(), anyBoolean(), eq(false), eq(1));
        order.verify(bossRenderer).drawFrameIndexWithPaletteBase(eq(0), anyInt(), anyInt(), anyBoolean(), eq(false), eq(1));
        order.verify(bossRenderer).drawFrameIndexWithPaletteBase(eq(3), anyInt(), anyInt(), anyBoolean(), eq(false), eq(1));
    }

    @Test
    void bossPaletteAddressPointsAtPalIczEndBossBlock() throws Exception {
        assertEquals(0x0723B0, Sonic3kConstants.class.getField("PAL_ICZ_END_BOSS_ADDR").getInt(null));
    }

    @Test
    void bottomChildExposesRomHurtCollisionRegion() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);

        TouchResponseProvider.TouchRegion[] regions =
                ((TouchResponseProvider) instance).getMultiTouchRegions();
        assertNotNull(regions);
        boolean hasBottomHurtRegion = false;
        for (TouchResponseProvider.TouchRegion region : regions) {
            if (region.x() == invokeInt(instance, "getBottomHurtXForTesting")
                    && region.y() == invokeInt(instance, "getBottomHurtYForTesting")
                    && region.collisionFlags() == 0x9B) {
                hasBottomHurtRegion = true;
                break;
            }
        }
        assertTrue(hasBottomHurtRegion);
    }

    @Test
    void frostPuffsUseTransientMappingsWithoutReplacingStructuralChildren() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 660);

        assertEquals(0, invokeInt(instance, "getBodyMappingFrameForTesting"));
        assertEquals(3, invokeInt(instance, "getStructuralChildCountForTesting"));
        assertEquals(3, invokeInt(instance, "getStructuralChildFrameForTesting", 0));
        assertEquals(1, invokeInt(instance, "getStructuralChildFrameForTesting", 1));
        assertEquals(2, invokeInt(instance, "getStructuralChildFrameForTesting", 2));
        int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
        assertTrue(puffCount > 0);
        boolean hasEffectFrame = false;
        for (int i = 0; i < puffCount; i++) {
            int frame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
            hasEffectFrame |= frame >= 0x05 && frame <= 0x15;
        }
        assertTrue(hasEffectFrame);
    }

    @Test
    void phaseOneFrostPuffsUseRomStaggeredRawMultiDelayTimers() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepUntilFrostPuffsSpawn(instance, 760);

        boolean sawStaggeredLargeSmoke = false;
        for (int frame = 0; frame < 20 && !sawStaggeredLargeSmoke; frame++) {
            instance.update(900 + frame, mock(PlayableEntity.class));
            sawStaggeredLargeSmoke = distinctFrameCount(instance, 0x0B, 0x0F) > 1;
        }

        assertTrue(sawStaggeredLargeSmoke,
                "loc_72168 seeds phase-one frost puffs from word_721C2, so same-script large smoke children should not animate in lockstep");
    }

    @Test
    void staggeredFrostPuffsWaitBeforeDrawingLikeRomChildren() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        PatternSpriteRenderer bossRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(bossRenderer.isReady()).thenReturn(true);
        when(shipRenderer.isReady()).thenReturn(true);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_END_BOSS)).thenReturn(bossRenderer);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        stepUntilFrostPuffsSpawn(instance, 760);
        instance.appendRenderCommands(new ArrayList<>());

        org.mockito.Mockito.verify(bossRenderer, org.mockito.Mockito.never())
                .drawFrameIndexWithPaletteBase(eq(5), anyInt(), anyInt(), anyBoolean(), anyBoolean(), eq(1));
    }

    @Test
    void parentFrostPuffClearsNextFrameThenReemitsAfterRomWait() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepUntilFrostPuffsSpawn(instance, 760);
        assertTrue((Boolean) instance.getClass().getMethod("isFrostPuffArmedForTesting").invoke(instance));

        instance.update(901, mock(PlayableEntity.class));
        assertFalse((Boolean) instance.getClass().getMethod("isFrostPuffArmedForTesting").invoke(instance),
                "loc_71D1E only changes the callback to loc_71D46; it does not reload $2E, so Obj_Wait clears $38 bit 1 on the next frame");

        for (int frame = 1; frame < 0x40; frame++) {
            instance.update(980 + frame, mock(PlayableEntity.class));
            assertFalse((Boolean) instance.getClass().getMethod("isFrostPuffArmedForTesting").invoke(instance));
        }

        instance.update(980 + 0x40, mock(PlayableEntity.class));
        assertTrue((Boolean) instance.getClass().getMethod("isFrostPuffArmedForTesting").invoke(instance),
                "loc_71D1E emits the next puff group after the next 64-frame Obj_Wait interval");
    }

    @Test
    void bottomSpikeRegionTracksAnimatedBottomChildPosition() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122 + 0xD1 + 0x50);

        assertTrue(invokeInt(instance, "getBottomChildLocalYOffsetForTesting") > 0);
        assertEquals(invokeInt(instance, "getBottomChildYForTesting") + 8,
                invokeInt(instance, "getBottomHurtYForTesting"));
    }

    @Test
    void bottomChildArmsSideToggleBeforeMovingOnNextSstPass() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        for (int frame = 0; frame < 900
                && invokeInt(instance, "getBottomChildShiftTimerForTesting") == 0; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
        }

        assertEquals(0x42, invokeInt(instance, "getBottomChildShiftTimerForTesting"));
        assertEquals(0, invokeInt(instance, "getBottomChildLocalYOffsetForTesting"),
                "loc_71F92 arms routine 4 but does not change child offset on that dispatch");

        int previousBottomY = invokeInt(instance, "getBottomChildYForTesting");
        instance.update(901, mock(PlayableEntity.class));
        assertEquals(1, invokeInt(instance, "getBottomChildLocalYOffsetForTesting"));
        assertEquals(0x41, invokeInt(instance, "getBottomChildShiftTimerForTesting"));
        assertEquals(invokeInt(instance, "getBottomChildYForTesting") - previousBottomY,
                invokeInt(instance, "getBottomChildWholePixelDeltaForTesting"),
                "continued ride correction is sourced from the published child displacement");

        for (int pass = 0; pass < 0x42; pass++) {
            instance.update(902 + pass, mock(PlayableEntity.class));
        }
        assertEquals(-1, invokeInt(instance, "getBottomChildShiftTimerForTesting"));
        assertEquals(0x43, invokeInt(instance, "getBottomChildLocalYOffsetForTesting"),
                "Obj_Wait invokes loc_71F1E only after the terminal 0 -> -1 decrement");

    }

    @Test
    void foldedBottomChildPublishesItsNativeFreshLandingPhase() throws Exception {
        ObjectInstance instance = createTriggeredBoss();
        SolidObjectProvider boss = (SolidObjectProvider) instance;
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getYRadius()).thenReturn((short) 0x0E);

        for (int frame = 0; frame < 900
                && invokeInt(instance, "getBottomChildShiftTimerForTesting") == 0; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
        }
        instance.update(901, mock(PlayableEntity.class));
        assertEquals(1, boss.getTopLandingSnapAdjustment(player, 0x13),
                "loc_71F30 resolves its child-local surface one pixel before the folded parent pass");
        assertEquals(0, boss.getTopLandingSnapAdjustment(player, 0x0F),
                "a one-pixel radius restoration uses the ordinary SolidObjectFull surface");

        for (int pass = 0; pass < 0x42; pass++) {
            instance.update(902 + pass, mock(PlayableEntity.class));
        }
        assertEquals(0, boss.getTopLandingSnapAdjustment(player, 0x13),
                "routine 2 landings use the ordinary child surface after the $43 shift finishes");
        assertTrue(boss.usesPreUpdateXForContinuedRide(player),
                "the later child SST carries from the parent slot's saved pre-update X");
    }

    @Test
    void foldedBottomChildUsesItsSavedEntryXForFreshContact() throws Exception {
        ObjectInstance instance = createTriggeredBoss();
        MultiPieceSolidProvider boss = (MultiPieceSolidProvider) instance;
        PlayableEntity player = mock(PlayableEntity.class);

        for (int frame = 0; frame < 900; frame++) {
            instance.snapshotPreUpdatePosition();
            int entryX = instance.getX();
            instance.update(1200 + frame, player);
            if (instance.getX() != entryX) {
                assertEquals(entryX, boss.getPieceFreshContactX(0, player),
                        "loc_71F30 saves x_pos before folded parent motion is published");
                assertEquals(instance.getPreUpdateY()
                                + invokeInt(instance, "getBottomChildLocalYOffsetForTesting") + 0x2D,
                        boss.getPieceFreshContactY(0, player),
                        "fresh contact keeps the child-local offset on its saved parent Y");
                return;
            }
        }
        assertTrue(false, "boss swing did not publish a horizontal step");
    }

    @Test
    void bottomSpikedPartIsAlsoTheRomSolidPlatform() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122 + 0xD1 + 0x50);

        assertTrue(instance instanceof SolidObjectProvider);
        SolidObjectProvider solid = (SolidObjectProvider) instance;
        SolidObjectParams params = solid.getSolidParams();
        assertEquals(0x23, params.halfWidth());
        assertEquals(4, params.airHalfHeight());
        assertEquals(0x0A, params.groundHalfHeight());
        assertEquals(invokeInt(instance, "getBottomChildXForTesting"), invokeInt(instance, "getSolidPlatformXForTesting"));
        assertEquals(invokeInt(instance, "getBottomChildYForTesting"), invokeInt(instance, "getSolidPlatformYForTesting"));
    }

    @Test
    void defeatedBottomSpikedPartStopsRunningSolidObjectFull() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);
        SolidObjectProvider solid = (SolidObjectProvider) instance;
        assertTrue(solid.isSolidFor(mock(PlayableEntity.class)));

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(200 + i, mock(PlayableEntity.class));
        }

        assertFalse(solid.isSolidFor(mock(PlayableEntity.class)));
    }

    @Test
    void damagedBottomPlatformFallsWithObjFlickerMoveGravity() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }
        int detachedY = invokeInt(instance, "getBottomChildYForTesting");

        stepFrames(instance, 48);

        assertTrue(invokeInt(instance, "getBottomChildYForTesting") > detachedY,
                "loc_849D8 switches detached children to Obj_FlickerMove, whose MoveSprite applies +$38 gravity each frame");
    }

    @Test
    void sixthHitStartsRomDamagedRiseBeforeFinalDefeat() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }

        assertEquals(2, invokeInt(instance, "getCollisionProperty"));
        assertFalse((Boolean) instance.getClass().getMethod("isDefeatStartedForTesting").invoke(instance));
        assertEquals(6, invokeInt(instance, "getCurrentRoutine"),
                "ROM sets routine=6 and $3C=$7F when collision_property reaches 2");
        assertFalse(((SolidObjectProvider) instance).isSolidFor(mock(PlayableEntity.class)),
                "The bottom child jumps to loc_849D8 once parent $3B is set, so SolidObjectFull stops");

        stepFrames(instance, 0x82);

        assertEquals(4, invokeInt(instance, "getCurrentRoutine"),
                "loc_71D78 returns the damaged boss to the swing/frost loop after the rise timer");
        assertFalse((Boolean) instance.getClass().getMethod("isDefeatStartedForTesting").invoke(instance));
    }

    @Test
    void damagedPhaseBreaksTopShellAndEmitsFastTopSteam() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(320 + i, mock(PlayableEntity.class));
        }

        assertEquals(4, invokeInt(instance, "getStructuralChildFrameForTesting", 0),
                "loc_71E60 switches the upper child to mapping frame 4 in the damaged phase");

        boolean hasTopSteam = false;
        boolean topSteamIsAnchoredToBrokenShell = false;
        for (int frame = 0; frame < 0x30 && !hasTopSteam; frame++) {
            instance.update(400 + frame, mock(PlayableEntity.class));
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            int topX = invokeInt(instance, "getStructuralChildXForTesting", 0);
            int topY = invokeInt(instance, "getStructuralChildYForTesting", 0);
            for (int i = 0; i < puffCount; i++) {
                int puffFrame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
                if (puffFrame >= 0x10 && puffFrame <= 0x15) {
                    hasTopSteam = true;
                    int puffX = invokeInt(instance, "getFrostPuffXForTesting", i);
                    int puffY = invokeInt(instance, "getFrostPuffYForTesting", i);
                    topSteamIsAnchoredToBrokenShell |= isTopSteamOffset(topX, topY, puffX, puffY);
                }
            }
        }
        assertTrue(invokeInt(instance, "getDamagedTopSteamEmissionCountForTesting") > 0);
        assertTrue(hasTopSteam,
                "loc_71E82 repeatedly spawns the selector-6 top steam puffs while the craft is damaged");
        assertTrue(topSteamIsAnchoredToBrokenShell,
                "selector-6 steam is spawned by the upper child, not the boss parent");
    }

    @Test
    void damagedTopSteamUsesRomStaggeredSideSmokeTimers() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(320 + i, mock(PlayableEntity.class));
        }

        boolean sawStaggeredSideSmoke = false;
        for (int frame = 0; frame < 0x40 && !sawStaggeredSideSmoke; frame++) {
            instance.update(500 + frame, mock(PlayableEntity.class));
            sawStaggeredSideSmoke = distinctFrameCount(instance, 0x10, 0x15) > 1;
        }

        assertTrue(sawStaggeredSideSmoke,
                "ChildObjDat_7235E selector-6 smoke reads word_721D0 timers 8,5,2,-1, so the side smoke should spread through several mapping frames at once");
    }

    @Test
    void damagedTopSteamRepeatsOnRomTwentyFourFrameInterval() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(320 + i, mock(PlayableEntity.class));
        }

        int firstEmissionCount = 0;
        int frame = 500;
        for (; frame < 560 && firstEmissionCount == 0; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
            firstEmissionCount = invokeInt(instance, "getDamagedTopSteamEmissionCountForTesting");
        }
        assertEquals(1, firstEmissionCount);

        for (int i = 1; i < 0x18; i++) {
            instance.update(frame + i, mock(PlayableEntity.class));
            assertEquals(firstEmissionCount, invokeInt(instance, "getDamagedTopSteamEmissionCountForTesting"));
        }

        instance.update(frame + 0x18, mock(PlayableEntity.class));
        assertEquals(firstEmissionCount + 1, invokeInt(instance, "getDamagedTopSteamEmissionCountForTesting"),
                "loc_71E82 stores $2E=$17, so Obj_Wait emits the next top steam group after 24 frames");
    }

    @Test
    void damagedPhaseContinuesParentSelectorTwoLargeSmokePuffs() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepUntilFrostPuffsSpawn(instance, 760);
        stepUntilNoFrostPuffsRemain(instance, 120);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(900 + i, mock(PlayableEntity.class));
        }

        boolean hasSelectorTwoLargeSmoke = false;
        for (int frame = 0; frame < 0xA0 && !hasSelectorTwoLargeSmoke; frame++) {
            instance.update(1000 + frame, mock(PlayableEntity.class));
            hasSelectorTwoLargeSmoke = hasSelectorTwoLargeParentSmoke(instance);
        }

        assertTrue(hasSelectorTwoLargeSmoke,
                "sub_7213A forces $26=2 once parent $3B is set, and loc_71D1E still spawns ChildObjDat_72352 parent smoke puffs");
    }

    @Test
    void selectorTwoSmallPuffsOnlyFreezeDuringRomRawAnimationFrames() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepUntilFrostPuffsSpawn(instance, 760);
        stepUntilNoFrostPuffsRemain(instance, 120);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(900 + i, mock(PlayableEntity.class));
        }

        boolean[] seenFrame = new boolean[0x10];
        boolean[] activeFrame = new boolean[0x10];
        for (int frame = 0; frame < 0xC0; frame++) {
            instance.update(1000 + frame, mock(PlayableEntity.class));
            int bossX = ((AbstractObjectInstance) instance).getX();
            int bossY = ((AbstractObjectInstance) instance).getY();
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                int puffFrame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
                if (puffFrame < seenFrame.length && isSelectorTwoSmallSmokeOffset(
                        bossX,
                        bossY,
                        invokeInt(instance, "getFrostPuffXForTesting", i),
                        invokeInt(instance, "getFrostPuffYForTesting", i))) {
                    seenFrame[puffFrame] = true;
                    activeFrame[puffFrame] |= (Boolean) instance.getClass()
                            .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                            .invoke(instance, i);
                }
            }
        }

        assertTrue(seenFrame[0x06] && seenFrame[0x07] && seenFrame[0x08]
                        && seenFrame[0x09] && seenFrame[0x0A],
                "selector-2 subtype 4/5 puffs use byte_72380 and should visibly reach frames 6..A");
        assertTrue(activeFrame[0x06] && activeFrame[0x07] && activeFrame[0x08],
                "loc_7205E freezes only while raw anim_frame is 4, 6, or 8; for byte_72380 those are mapping frames 6, 7, and 8");
        assertFalse(activeFrame[0x09] || activeFrame[0x0A],
                "frames 9 and A are after raw anim_frame > 8 and must no longer freeze Sonic");
    }

    @Test
    void selectorTwoLastSmallPuffUsesRomImmediateInitialDelay() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepUntilFrostPuffsSpawn(instance, 760);
        stepUntilNoFrostPuffsRemain(instance, 120);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(900 + i, mock(PlayableEntity.class));
        }

        int nextFrame = 1000;
        int secondSmallPuff = -1;
        for (; nextFrame < 1100 && secondSmallPuff < 0; nextFrame++) {
            instance.update(nextFrame, mock(PlayableEntity.class));
            secondSmallPuff = findSelectorTwoSecondSmallSmoke(instance);
        }
        assertTrue(secondSmallPuff >= 0);

        for (int i = 0; i < 3; i++) {
            instance.update(nextFrame + i, mock(PlayableEntity.class));
        }

        secondSmallPuff = findSelectorTwoSecondSmallSmoke(instance);
        assertTrue(secondSmallPuff >= 0);

        for (int i = 0; i < 8 && !(Boolean) instance.getClass()
                .getMethod("isFrostPuffVisibleForTesting", int.class)
                .invoke(instance, secondSmallPuff); i++) {
            instance.update(nextFrame + 3 + i, mock(PlayableEntity.class));
            secondSmallPuff = findSelectorTwoSecondSmallSmoke(instance);
            assertTrue(secondSmallPuff >= 0);
        }
        assertTrue((Boolean) instance.getClass()
                .getMethod("isFrostPuffVisibleForTesting", int.class)
                .invoke(instance, secondSmallPuff));
        assertEquals(0x05, invokeInt(instance, "getFrostPuffFrameForTesting", secondSmallPuff),
                "byte_72380 starts visible on mapping frame 5 after Obj_Wait transfers control to loc_7205E");

        instance.update(nextFrame + 11, mock(PlayableEntity.class));
        instance.update(nextFrame + 12, mock(PlayableEntity.class));

        secondSmallPuff = findSelectorTwoSecondSmallSmoke(instance);
        assertTrue(secondSmallPuff >= 0);
        assertEquals(0x06, invokeInt(instance, "getFrostPuffFrameForTesting", secondSmallPuff),
                "word_721CC/word_721D0 overlap gives selector-2 subtype $A an initial $FFFF delay; once visible, Animate_RawMultiDelay reaches raw frame 4 after two updates");
        assertTrue((Boolean) instance.getClass()
                .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                .invoke(instance, secondSmallPuff));
    }

    @Test
    void damagedTopSteamUsesAdjustedPositionWhenBrokenShellIsFlipped() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 900);
        int bossX = ((AbstractObjectInstance) instance).getX();
        assertTrue(invokeInt(instance, "getStructuralChildXForTesting", 0) < bossX,
                "The upper child should be following Refresh_ChildPositionAdjusted on the flipped side");

        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(1000 + i, mock(PlayableEntity.class));
        }

        boolean hasMirroredTopSteam = false;
        for (int frame = 0; frame < 0x30 && !hasMirroredTopSteam; frame++) {
            instance.update(1100 + frame, mock(PlayableEntity.class));
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            int topX = invokeInt(instance, "getStructuralChildXForTesting", 0);
            int topY = invokeInt(instance, "getStructuralChildYForTesting", 0);
            for (int i = 0; i < puffCount; i++) {
                int puffFrame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
                if (puffFrame >= 0x10 && puffFrame <= 0x15) {
                    int puffX = invokeInt(instance, "getFrostPuffXForTesting", i);
                    int puffY = invokeInt(instance, "getFrostPuffYForTesting", i);
                    hasMirroredTopSteam |= isMirroredTopSteamOffset(topX, topY, puffX, puffY);
                }
            }
        }

        assertTrue(hasMirroredTopSteam,
                "loc_72092 uses Refresh_ChildPositionAdjusted, so selector-6 steam mirrors child_dx with the broken shell");
    }

    @Test
    void damagedTopSteamFreezesPlayersInsideRomCaptureWindow() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> player, List::of));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        stepFrames(instance, 122);
        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(700 + i, player);
        }

        int activeTopSteam = -1;
        int nextFrame = 800;
        for (; nextFrame < 860 && activeTopSteam < 0; nextFrame++) {
            instance.update(nextFrame, player);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                int puffFrame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
                boolean active = (Boolean) instance.getClass()
                        .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i);
                if (active && puffFrame >= 0x10 && puffFrame <= 0x15) {
                    activeTopSteam = i;
                    break;
                }
            }
        }
        assertTrue(activeTopSteam >= 0);

        bindPlayerToFrostPuff(instance, player, activeTopSteam);

        instance.update(nextFrame, player);
        publishBossSolidContact(instance, player, nextFrame);

        org.mockito.Mockito.verify(player, org.mockito.Mockito.atLeastOnce()).setAnimationId(0x1A);
        assertTrue(services.spawnedChildren.stream().anyMatch(child ->
                child.getClass().getSimpleName().contains("FrozenPlayerBlock")));
    }

    @Test
    void damagedTopSteamCanFreezePlayerAfterEarlierFrostCaptureHasEnded() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> player, List::of));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        int nextFrame = 1;
        int normalPuff = -1;
        for (; nextFrame <= 720 && normalPuff < 0; nextFrame++) {
            instance.update(nextFrame, player);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                boolean active = (Boolean) instance.getClass()
                        .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i);
                if (active) {
                    normalPuff = i;
                    break;
                }
            }
        }
        assertTrue(normalPuff >= 0);

        bindPlayerToFrostPuff(instance, player, normalPuff);
        instance.update(nextFrame++, player);
        instance.update(nextFrame++, player);
        publishBossSolidContact(instance, player, nextFrame - 1);
        long capturesBeforeDamagedTopSteam = frozenPlayerBlockCount(services);
        assertTrue(capturesBeforeDamagedTopSteam > 0);

        for (int i = 0; i < 6; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(nextFrame++, player);
        }

        int activeTopSteam = -1;
        for (; nextFrame < 860 && activeTopSteam < 0; nextFrame++) {
            instance.update(nextFrame, player);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                int puffFrame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
                boolean active = (Boolean) instance.getClass()
                        .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i);
                if (active && puffFrame >= 0x10 && puffFrame <= 0x15) {
                    activeTopSteam = i;
                    break;
                }
            }
        }
        assertTrue(activeTopSteam >= 0);

        bindPlayerToFrostPuff(instance, player, activeTopSteam);
        instance.update(nextFrame++, player);
        instance.update(nextFrame, player);
        publishBossSolidContact(instance, player, nextFrame);

        assertTrue(frozenPlayerBlockCount(services) > capturesBeforeDamagedTopSteam,
                "sub_8A9E0 gates on current object_control/invulnerability only; prior boss smoke captures do not immunize Sonic");
    }

    @Test
    void frostPuffsFreezePlayersInsideRomCaptureWindow() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> player, List::of));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        int activePuff = -1;
        int nextFrame = 1;
        for (; nextFrame <= 720 && activePuff < 0; nextFrame++) {
            int frame = nextFrame;
            instance.update(frame, player);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                if ((Boolean) instance.getClass().getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i)) {
                    activePuff = i;
                    break;
                }
            }
        }
        assertTrue(activePuff >= 0);
        bindPlayerToFrostPuff(instance, player, activePuff);

        instance.update(nextFrame++, player);
        instance.update(nextFrame, player);
        publishBossSolidContact(instance, player, nextFrame);

        org.mockito.Mockito.verify(player, org.mockito.Mockito.atLeastOnce()).setAnimationId(0x1A);
        assertTrue(services.spawnedChildren.stream().anyMatch(child ->
                child.getClass().getSimpleName().contains("FrozenPlayerBlock")));
    }

    @Test
    void frostPuffsFreezeThirdPlayerWithoutDisplacingNativeP1P2Prefix() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite main = new Sonic("sonic", (short) 0, (short) 0);
        AbstractPlayableSprite nativeP2 = new Sonic("sonic_p2", (short) 0, (short) 0);
        AbstractPlayableSprite extension = new Sonic("sonic_p3", (short) 0, (short) 0);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> List.of(nativeP2, extension)));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        int activePuff = -1;
        int nextFrame = 1;
        for (; nextFrame <= 720 && activePuff < 0; nextFrame++) {
            instance.update(nextFrame, main);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                if ((Boolean) instance.getClass()
                        .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i)) {
                    activePuff = i;
                    break;
                }
            }
        }
        assertTrue(activePuff >= 0);
        placePlayerAtFrostPuff(instance, main, activePuff);
        placePlayerAtFrostPuff(instance, nativeP2, activePuff);
        placePlayerAtFrostPuff(instance, extension, activePuff);

        instance.update(nextFrame++, main);
        instance.update(nextFrame, main);
        publishBossSolidContact(instance, main, nextFrame);
        publishBossSolidContact(instance, nativeP2, nextFrame);
        publishBossSolidContact(instance, extension, nextFrame);

        List<IczFreezerObjectInstance.FrozenPlayerBlock> blocks = services.spawnedChildren.stream()
                .filter(IczFreezerObjectInstance.FrozenPlayerBlock.class::isInstance)
                .map(IczFreezerObjectInstance.FrozenPlayerBlock.class::cast)
                .toList();
        assertEquals(3, blocks.size());
        assertSame(main, blocks.get(0).capturedPlayerForTesting());
        assertSame(nativeP2, blocks.get(1).capturedPlayerForTesting());
        assertSame(extension, blocks.get(2).capturedPlayerForTesting());
        assertTrue(main.isObjectControlled());
        assertTrue(nativeP2.isObjectControlled());
        assertTrue(extension.isObjectControlled());
    }

    @Test
    void queuedExtendedCaptureFollowsPlayerIdentityWhenSidekickOrderChanges() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite main = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite extension = mock(AbstractPlayableSprite.class);
        List<PlayableEntity> sidekicks = new ArrayList<>(List.of(nativeP2, extension));
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> sidekicks));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        var queueCapture = instance.getClass().getDeclaredMethod("queueExtendedFrostCapture",
                AbstractPlayableSprite.class, int.class, int.class, boolean.class, boolean.class);
        queueCapture.setAccessible(true);
        queueCapture.invoke(instance, extension, 0x4480, -1, false, true);
        sidekicks.remove(nativeP2);
        publishBossSolidContact(instance, extension, 1);

        List<IczFreezerObjectInstance.FrozenPlayerBlock> blocks = services.spawnedChildren.stream()
                .filter(IczFreezerObjectInstance.FrozenPlayerBlock.class::isInstance)
                .map(IczFreezerObjectInstance.FrozenPlayerBlock.class::cast)
                .toList();
        assertEquals(1, blocks.size());
        assertSame(extension, blocks.getFirst().capturedPlayerForTesting());
    }

    @Test
    void queuedExtendedCaptureDoesNotInheritVacatedNativeP2Mask() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite main = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite extension = mock(AbstractPlayableSprite.class);
        List<PlayableEntity> sidekicks = new ArrayList<>(List.of(nativeP2, extension));
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> sidekicks));
        doAnswer(invocation -> {
            ((AbstractObjectInstance) invocation.getArgument(0)).setDestroyed(true);
            return null;
        }).when(services.objectManager).addDynamicObjectAfterCurrent(any());
        object.setServices(services);

        var queueNativeCapture = instance.getClass().getDeclaredMethod("queueFrostCapture",
                int.class, AbstractPlayableSprite.class, int.class, int.class, boolean.class, boolean.class);
        queueNativeCapture.setAccessible(true);
        queueNativeCapture.invoke(instance, 1, nativeP2, 0x4470, -1, false, true);
        var queueExtendedCapture = instance.getClass().getDeclaredMethod("queueExtendedFrostCapture",
                AbstractPlayableSprite.class, int.class, int.class, boolean.class, boolean.class);
        queueExtendedCapture.setAccessible(true);
        queueExtendedCapture.invoke(instance, extension, 0x4480, -1, false, true);

        sidekicks.remove(nativeP2);
        publishBossSolidContact(instance, extension, 1);

        org.mockito.Mockito.verify(extension).setAir(true);
        List<IczFreezerObjectInstance.FrozenPlayerBlock> blocks = services.spawnedChildren.stream()
                .filter(IczFreezerObjectInstance.FrozenPlayerBlock.class::isInstance)
                .map(IczFreezerObjectInstance.FrozenPlayerBlock.class::cast)
                .toList();
        assertEquals(1, blocks.size());
        assertSame(extension, blocks.getFirst().capturedPlayerForTesting());
    }

    @Test
    void unqueuedSidekickDoesNotInheritVacatedNativeP2Capture() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite main = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite replacement = mock(AbstractPlayableSprite.class);
        List<PlayableEntity> sidekicks = new ArrayList<>(List.of(nativeP2, replacement));
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> sidekicks));
        object.setServices(services);

        var queueNativeCapture = instance.getClass().getDeclaredMethod("queueFrostCapture",
                int.class, AbstractPlayableSprite.class, int.class, int.class, boolean.class, boolean.class);
        queueNativeCapture.setAccessible(true);
        queueNativeCapture.invoke(instance, 1, nativeP2, 0x4470, -1, false, true);

        sidekicks.remove(nativeP2);
        publishBossSolidContact(instance, replacement, 1);

        org.mockito.Mockito.verify(replacement, org.mockito.Mockito.never()).setAir(true);
        assertEquals(0, frozenPlayerBlockCount(services));
    }

    @Test
    void frostPuffsFreezeExtendedSidekickWithoutConsumingNativeObjectSlot() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite main = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite nativeP2 = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite extension = mock(AbstractPlayableSprite.class);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> main, () -> List.of(nativeP2, extension)));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        doAnswer(invocation -> {
            ((AbstractObjectInstance) invocation.getArgument(0)).setDestroyed(true);
            return null;
        }).when(services.objectManager).addDynamicObjectAfterCurrent(any());
        object.setServices(services);

        int activePuff = -1;
        int nextFrame = 1;
        for (; nextFrame <= 720 && activePuff < 0; nextFrame++) {
            instance.update(nextFrame, main);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                if ((Boolean) instance.getClass()
                        .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i)) {
                    activePuff = i;
                    break;
                }
            }
        }
        assertTrue(activePuff >= 0);
        bindPlayerToFrostPuff(instance, extension, activePuff);

        instance.update(nextFrame++, main);
        instance.update(nextFrame, main);
        publishBossSolidContact(instance, extension, nextFrame);

        org.mockito.Mockito.verify(extension).setAir(true);
        assertEquals(1, frozenPlayerBlockCount(services));
        org.mockito.Mockito.verify(services.objectManager).addRewindableAuxiliaryDynamicObject(any());
    }

    @Test
    void matureFrostPuffPublishesCaptureAtCurrentSolidCheckpoint() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> player, List::of));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        int activePuff = -1;
        int nextFrame = 1;
        for (; nextFrame <= 720 && activePuff < 0; nextFrame++) {
            instance.update(nextFrame, player);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                int puffFrame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
                boolean active = (Boolean) instance.getClass()
                        .getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i);
                if (active && puffFrame < 0x10) {
                    activePuff = i;
                    break;
                }
            }
        }
        assertTrue(activePuff >= 0);

        bindPlayerToFrostPuff(instance, player, activePuff);
        instance.update(nextFrame, player);
        publishBossSolidContact(instance, player, nextFrame);

        assertTrue(frozenPlayerBlockCount(services) > 0,
                "A loc_7205E child already inside raw anim_frame 4..8 runs sub_8A9C6 in its current later-slot pass");
    }

    @Test
    void frostPuffRangeTableUsesStartOffsetPlusExtent() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        services.withPlayerQuery(new ObjectPlayerQuery(() -> player, List::of));
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);

        int activePuff = -1;
        int nextFrame = 1;
        for (; nextFrame <= 720 && activePuff < 0; nextFrame++) {
            instance.update(nextFrame, player);
            int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
            for (int i = 0; i < puffCount; i++) {
                if ((Boolean) instance.getClass().getMethod("isFrostPuffCaptureActiveForTesting", int.class)
                        .invoke(instance, i)) {
                    activePuff = i;
                    break;
                }
            }
        }
        assertTrue(activePuff >= 0);
        int puffIndex = activePuff;
        when(player.getCentreX()).thenAnswer(invocation ->
                (short) (frostPuffCoordinate(instance, "getFrostPuffXForTesting", puffIndex) + 0x18));
        when(player.getCentreY()).thenAnswer(invocation ->
                frostPuffCoordinate(instance, "getFrostPuffYForTesting", puffIndex));

        instance.update(nextFrame++, player);
        assertEquals(0, frozenPlayerBlockCount(services),
                "word_7208A's second word is an extent, making +$18 the excluded right edge");

        bindPlayerToFrostPuff(instance, player, puffIndex);
        instance.update(nextFrame++, player);
        instance.update(nextFrame, player);
        publishBossSolidContact(instance, player, nextFrame);
        assertTrue(frozenPlayerBlockCount(services) > 0);
    }

    @Test
    void bossFrostBlockInitializesThenKeepsRightwardVelocityInsideCameraEdge() {
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(
                        player, 0x4443, 0x066F, 0x443A, false, true);
        block.setServices(services);

        block.update(1, player);
        assertEquals(0x4443, block.getX(), "loc_8A7AE initializes without running MoveSprite");
        block.update(2, player);

        assertEquals(0x4445, block.getX(),
                "loc_8A80C keeps +$200 while x_pos is not beyond Camera_X_pos+$128");
        assertEquals(0x066B, block.getY());
    }

    @Test
    void robotnikShipChildIsVisibleAndTracksBossCentre() throws Exception {
        ObjectInstance instance = createTriggeredBoss();

        stepFrames(instance, 122);

        assertTrue((Boolean) instance.getClass().getMethod("isRobotnikShipVisibleForTesting").invoke(instance));
        assertEquals(((AbstractObjectInstance) instance).getX(), invokeInt(instance, "getRobotnikShipXForTesting"));
        assertEquals(((AbstractObjectInstance) instance).getY(), invokeInt(instance, "getRobotnikShipYForTesting"));
        assertEquals(9, invokeInt(instance, "getRobotnikShipFrameForTesting"));
        assertEquals(((AbstractObjectInstance) instance).getX(), invokeInt(instance, "getRobotnikHeadXForTesting"));
        assertEquals(((AbstractObjectInstance) instance).getY() - 0x1C, invokeInt(instance, "getRobotnikHeadYForTesting"));
        assertTrue(invokeInt(instance, "getRobotnikHeadFrameForTesting") <= 1);
    }

    @Test
    void eighthHitStartsDefeatHandoffAndSpawnsCapsule() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        stepFrames(instance, 122);

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(200 + i, mock(PlayableEntity.class));
        }

        assertTrue((Boolean) instance.getClass().getMethod("isDefeatStartedForTesting").invoke(instance));

        stepFrames(instance, FINAL_DEFEAT_HANDOFF_FRAMES);

        assertEquals(0, services.gameState.getCurrentBossId());
        assertEquals(0x4390, services.camera.getMinX() & 0xFFFF);
        assertEquals(0x4390, services.camera.getMaxX() & 0xFFFF,
                "loc_71D9E stores _unkFAB4+$130 separately and leaves live Camera_max_X_pos locked");
        assertTrue(services.spawnedChildren.stream().anyMatch(child ->
                child.getClass().getSimpleName().contains("EggCapsule")));
        assertTrue(services.spawnedChildren.stream().anyMatch(
                        HczEndBossGradualMaxXExtender.class::isInstance),
                "loc_71D9E attempts a first-free Obj_IncLevEndXGradual allocation after the capsule");
    }

    @Test
    void finalHitKeepsRobotnikVisibleAngryThenEscapesAfterDefeatHandoff() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        PatternSpriteRenderer bossRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(bossRenderer.isReady()).thenReturn(true);
        when(shipRenderer.isReady()).thenReturn(true);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_END_BOSS)).thenReturn(bossRenderer);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        stepFrames(instance, 122);

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }
        int shipX = invokeInt(instance, "getRobotnikShipXForTesting");

        assertTrue((Boolean) instance.getClass().getMethod("isRobotnikShipVisibleForTesting").invoke(instance));
        assertEquals(3, invokeInt(instance, "getRobotnikHeadFrameForTesting"),
                "Obj_RobotnikHeadMain switches to mapping frame 3 while the destroyed craft explodes");

        boolean spawnedExplosion = false;
        for (int frame = 0; frame < 16; frame++) {
            instance.update(400 + frame, mock(PlayableEntity.class));
            spawnedExplosion |= services.spawnedChildren.stream().anyMatch(child ->
                    child.getClass().getSimpleName().equals("S3kBossExplosionChild"));
        }

        assertTrue(spawnedExplosion,
                "Obj_RobotnikShipMain creates Child6_CreateBossExplosion when the defeated parent sets status bit 7");
        assertEquals(shipX, invokeInt(instance, "getRobotnikShipXForTesting"),
                "Obj_RobotnikShip4 waits on parent $38 bit 4 before escaping right");

        stepFrames(instance, FINAL_DEFEAT_HANDOFF_FRAMES);

        AbstractObjectInstance escapeShip = findRobotnikEscapeShip(services);
        int escapeStartX = escapeShip.getX();

        boolean movedRight = false;
        for (int frame = 0; frame < 16; frame++) {
            escapeShip.update(500 + frame, mock(PlayableEntity.class));
            movedRight |= escapeShip.getX() > escapeStartX;
        }

        assertTrue(movedRight, "Obj_RobotnikShipEscape flies right with x_vel=$300 after the handoff");
        escapeShip.appendRenderCommands(new ArrayList<>());
        org.mockito.Mockito.verify(shipRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(eq(0x0A), anyInt(), anyInt(), eq(true), eq(false), eq(0));
        org.mockito.Mockito.verify(shipRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(eq(3), anyInt(), anyInt(), eq(true), eq(false), eq(0));
        org.mockito.Mockito.verify(shipRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(eq(6), anyInt(), anyInt(), eq(true), eq(false), eq(0));
    }

    @Test
    void finalDefeatWaitDoesNotMoveBossBodyBeforeRobotnikEscapeHandoff() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        stepFrames(instance, 122);

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }
        int defeatX = object.getX();
        int defeatY = object.getY();

        stepFrames(instance, 0x40);

        assertEquals(defeatX, object.getX());
        assertEquals(defeatY, object.getY(),
                "loc_722C6 switches to Wait_FadeToLevelMusic; the final defeat wait should not reuse loc_71D64's sinking movement");
        assertTrue(services.spawnedChildren.stream().anyMatch(child ->
                        child.getClass().getSimpleName().equals("IczEndBossDefeatDebrisChild")),
                "loc_71D80 releases the shell fragments after the retained $3F wait");
        assertFalse(services.spawnedChildren.stream().anyMatch(child ->
                        child.getClass().getSimpleName().contains("EggCapsule")),
                "loc_71D80 retains the newly seeded 119-count wait before loc_71D9E");
    }

    @Test
    void postHandoffDrawsRobotnikShipWithoutDeadBossShell() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        PatternSpriteRenderer bossRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(bossRenderer.isReady()).thenReturn(true);
        when(shipRenderer.isReady()).thenReturn(true);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_END_BOSS)).thenReturn(bossRenderer);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        stepFrames(instance, 122);

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }
        stepFrames(instance, FINAL_DEFEAT_HANDOFF_FRAMES);

        AbstractObjectInstance escapeShip = findRobotnikEscapeShip(services);
        escapeShip.appendRenderCommands(new ArrayList<>());

        org.mockito.Mockito.verify(bossRenderer, org.mockito.Mockito.never())
                .drawFrameIndexWithPaletteBase(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyInt());
        org.mockito.Mockito.verify(shipRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(eq(0x0A), anyInt(), anyInt(), eq(true), eq(false), eq(0));
    }

    @Test
    void finalHandoffTransfersRobotnikToIndependentShipObject() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(shipRenderer.isReady()).thenReturn(true);
        when(services.renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        stepFrames(instance, 122);

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }
        stepFrames(instance, FINAL_DEFEAT_HANDOFF_FRAMES);

        AbstractObjectInstance ship = services.spawnedChildren.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> child.getClass().getSimpleName().equals("IczEndBossRobotnikEscapeShip"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Obj_RobotnikShip4 escape must be an independent child object"));
        int startX = ship.getX();

        object.setDestroyed(true);
        for (int frame = 0; frame < 8; frame++) {
            ship.update(700 + frame, mock(PlayableEntity.class));
        }
        ship.appendRenderCommands(new ArrayList<>());

        assertFalse(ship.isDestroyed(),
                "The Robotnik escape ship should not vanish when the defeated boss parent is removed");
        assertTrue(ship.getX() > startX,
                "Obj_RobotnikShipEscape moves independently with x_vel=$300");
        org.mockito.Mockito.verify(shipRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(eq(0x0A), anyInt(), anyInt(), eq(true), eq(false), eq(0));
        org.mockito.Mockito.verify(shipRenderer, org.mockito.Mockito.atLeastOnce())
                .drawFrameIndex(eq(3), anyInt(), anyInt(), eq(true), eq(false), eq(0));
    }

    @Test
    void robotnikEscapeShipMovesThreePixelsPerFrameLikeMoveSprite2() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        stepFrames(instance, 122);

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }
        stepFrames(instance, FINAL_DEFEAT_HANDOFF_FRAMES);

        AbstractObjectInstance ship = findRobotnikEscapeShip(services);
        int startX = ship.getX();

        ship.update(700, mock(PlayableEntity.class));

        assertEquals(startX + 3, ship.getX(),
                "Obj_RobotnikShipEscape uses MoveSprite2 with x_vel=$300, i.e. 3 pixels per frame");
    }

    @Test
    void finalHandoffSpawnsRomIndexedFallingDebrisPieces() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        stepFrames(instance, 122);

        for (int i = 0; i < 8; i++) {
            instance.getClass().getMethod("forceHitForTesting").invoke(instance);
            instance.update(300 + i, mock(PlayableEntity.class));
        }
        stepFrames(instance, FINAL_DEFEAT_HANDOFF_FRAMES);

        List<AbstractObjectInstance> debris = services.spawnedChildren.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> child.getClass().getSimpleName().equals("IczEndBossDefeatDebrisChild"))
                .toList();

        assertEquals(3, debris.size(),
                "loc_71D80 creates ChildObjDat_7236C: three falling shell debris children");
        int firstX = debris.get(0).getX();
        int firstY = debris.get(0).getY();
        for (int frame = 0; frame < 16; frame++) {
            for (AbstractObjectInstance child : debris) {
                child.update(600 + frame, mock(PlayableEntity.class));
            }
        }

        assertTrue(debris.get(0).getX() < firstX,
                "Set_IndexedVelocity d0=$34 gives the first ICZ defeat fragment x_vel=-$100");
        assertTrue(debris.get(0).getY() > firstY,
                "Obj_FlickerMove applies gravity so the broken boss shell falls off-screen");
    }

    @Test
    void iczEggCapsuleExposesRomBodyAndButtonSolidPieces() {
        ObjectInstance capsule = new IczEndBossEggCapsuleInstance(0x4560, 0x06A3);

        assertTrue(capsule instanceof MultiPieceSolidProvider);
        MultiPieceSolidProvider solid = (MultiPieceSolidProvider) capsule;
        assertEquals(2, solid.getPieceCount());
        assertEquals(0x4560, solid.getPieceX(0));
        assertEquals(0x06A3, solid.getPieceY(0));
        assertEquals(0x4560, solid.getPieceX(1));
        assertEquals(0x067F, solid.getPieceY(1),
                "Normal Obj_EggCapsule creates its button child at child_dy=-$24");
        assertEquals(0x2B, solid.getPieceParams(0).halfWidth());
        assertEquals(0x18, solid.getPieceParams(0).groundHalfHeight());
        assertEquals(0x1B, solid.getPieceParams(1).halfWidth());
        assertEquals(4, solid.getPieceParams(1).airHalfHeight());
        assertEquals(6, solid.getPieceParams(1).groundHalfHeight());
    }

    @Test
    void iczEggCapsuleButtonPressStartsResultsScreenAfterRomDelay() {
        IczEndBossEggCapsuleInstance capsule = new IczEndBossEggCapsuleInstance(0x4560, 0x06A3);
        RecordingServices services = new RecordingServices();
        services.withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        ((AbstractObjectInstance) capsule).setServices(services);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        org.mockito.Mockito.when(player.getCentreX()).thenReturn((short) 0x4560);
        org.mockito.Mockito.when(player.getCentreY()).thenReturn((short) 0x067F);
        org.mockito.Mockito.when(player.getAir()).thenReturn(false);
        org.mockito.Mockito.when(player.getCode()).thenReturn("sonic");

        capsule.onPieceContact(1, player, new SolidContact(true, false, false, true, false), 1);
        capsule.update(1, player);
        assertEquals(0x067F, ((MultiPieceSolidProvider) capsule).getPieceY(1),
                "loc_8672A changes the button mapping without changing child_dy");
        for (int frame = 2; frame <= 0x45; frame++) {
            capsule.update(frame, player);
        }

        assertTrue(services.spawnedChildren.stream().anyMatch(
                        S3kResultsScreenObjectInstance.class::isInstance),
                "sub_865DE waits $40 pre-decremented frames before Obj_LevelResults is allocated");
        assertTrue(services.gameState.isEndOfLevelActive());
        org.mockito.Mockito.verify(player).setAnimationId(Sonic3kAnimationIds.VICTORY);
    }

    @Test
    void iczEggCapsuleKeepsLevelStartLockedToCurrentCameraUntilResultsHandoff() {
        IczEndBossEggCapsuleInstance capsule = new IczEndBossEggCapsuleInstance(0x4560, 0x06A3);
        RecordingServices services = new RecordingServices();
        services.withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        services.camera.setX((short) 0x4520);
        services.camera.setMinX((short) 0x4390);
        ((AbstractObjectInstance) capsule).setServices(services);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        org.mockito.Mockito.when(player.getCentreX()).thenReturn((short) 0x4560);
        org.mockito.Mockito.when(player.getCentreY()).thenReturn((short) 0x067F);
        org.mockito.Mockito.when(player.getAir()).thenReturn(false);
        org.mockito.Mockito.when(player.getCode()).thenReturn("sonic");

        capsule.update(1, player);
        assertEquals(0x4520, services.camera.getMinX() & 0xFFFF,
                "ICZ Obj_ICZEndBoss keeps Camera_min_X_pos at Camera_X_pos while waiting for the capsule");

        services.camera.setX((short) 0x4560);
        capsule.onPieceContact(1, player, new SolidContact(true, false, false, true, false), 2);
        for (int frame = 2; frame <= 0x45; frame++) {
            capsule.update(frame, player);
        }

        assertEquals(0x4560, services.camera.getMinX() & 0xFFFF,
                "The left lock must survive through the frame that creates Obj_LevelResults");
        assertTrue(services.gameState.isEndOfLevelActive());
    }

    @Test
    void iczEggCapsuleDoesNotOpenFromOverlapWithoutSolidButtonContact() {
        IczEndBossEggCapsuleInstance capsule = new IczEndBossEggCapsuleInstance(0x4560, 0x06A3);
        RecordingServices services = new RecordingServices();
        services.withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        ((AbstractObjectInstance) capsule).setServices(services);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        org.mockito.Mockito.when(player.getCentreX()).thenReturn((short) 0x4560);
        org.mockito.Mockito.when(player.getCentreY()).thenReturn((short) 0x067F);
        org.mockito.Mockito.when(player.getAir()).thenReturn(false);

        for (int frame = 1; frame <= 0x50; frame++) {
            capsule.update(frame, player);
        }

        assertFalse(services.gameState.isEndOfLevelActive(),
                "Obj_EggCapsule's upright button is a SolidObjectFull child; overlap alone must not press it");
        assertEquals(0x067F, ((MultiPieceSolidProvider) capsule).getPieceY(1));
    }

    @Test
    void profileMarksIczEndBossImplementedOnlyForS3kl() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(ICZ_END_BOSS_ID));
        assertTrue(profile.getImplementedIds(profile.getLevels().get(11)).contains(ICZ_END_BOSS_ID));
        assertFalse(profile.getImplementedIds(profile.getLevels().get(14)).contains(ICZ_END_BOSS_ID));
    }

    @Test
    void iczEndBossSheetIsRegisteredDuringIczLoad() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 1)
                .build();

        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) com.openggf.game.GameModuleRegistry
                        .getCurrent().getObjectArtProvider();

        var sheet = provider.getSheet("icz_end_boss");
        assertNotNull(sheet, "ICZ end boss sheet should be registered after ICZ load");
        assertTrue(sheet.getPatterns().length > 0);
        assertTrue(sheet.getFrameCount() >= 25);
    }

    @Test
    void productionIcz2BossAreaKeepsSnowingAfterArenaGateArms() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 1)
                .startPosition((short) 0x4430, (short) 0x0658)
                .startPositionIsCentre()
                .build();
        ObjectManager objectManager = GameServices.level().getObjectManager();
        fixture.camera().setX((short) 0x4390);
        fixture.camera().setY((short) 0x05F8);
        fixture.camera().setMinX((short) 0x4300);
        fixture.camera().setMaxX((short) 0x4700);
        fixture.camera().setMinY((short) 0x0500);
        fixture.camera().setMaxY((short) 0x0700);
        objectManager.reset(0x4390);

        boolean sawEmitter = false;
        boolean sawParticle = false;
        for (int frame = 0; frame < 24; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            sawEmitter |= hasActiveObjectNamed(objectManager, "IczSnowPileObjectInstance");
            sawParticle |= hasActiveObjectNamed(objectManager, "SnowdustParticle");
        }

        assertTrue(sawEmitter,
                "The production ICZ2 boss entry path should keep the subtype-$18 Obj_ICZSnowPile snow emitter alive");
        assertEquals(1, activeSnowdustEmitterCount(objectManager),
                "Boss initialization must reuse the single placed emitter rather than allocate a dynamic duplicate");
        assertFalse(objectManager.getActiveObjects().stream()
                        .filter(IczSnowPileObjectInstance.class::isInstance)
                        .map(IczSnowPileObjectInstance.class::cast)
                        .filter(IczSnowPileObjectInstance::isSnowdustEmitter)
                        .anyMatch(emitter -> emitter.getSpawn().layoutIndex() < 0),
                "Obj_ICZEndBoss does not synthesize a subtype-$18 emitter");
        assertTrue(sawParticle,
                () -> "The production ICZ2 boss entry path should let the snow emitter allocate visible Obj_ICZSnowdust particles; "
                        + "allocatedSlots=" + objectManager.getAllocatedSlotCount()
                        + ", active=" + objectManager.getActiveObjects().stream()
                                .map(object -> object.getClass().getSimpleName())
                                .sorted()
                                .toList());
    }

    @Test
    void foldedIczEndBossReservesEveryLiveNativeChildSst() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 1)
                .startPosition((short) 0x4430, (short) 0x0658)
                .startPositionIsCentre()
                .build();
        ObjectManager objectManager = GameServices.level().getObjectManager();
        fixture.camera().setX((short) 0x4390);
        fixture.camera().setY((short) 0x05F8);
        objectManager.reset(0x4390);

        for (int frame = 0; frame < 140; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        int[] reserved = objectManager.rewindSnapshottable().capture().childSpawns().stream()
                .filter(entry -> entry.parentSpawn().objectId() == ICZ_END_BOSS_ID)
                .map(entry -> entry.reservedSlots())
                .findFirst()
                .orElseThrow();

        assertEquals(6, reserved.length,
                "Obj_ICZEndBoss folds Robotnik's ship/head and the bottom hurt child, "
                        + "but their live SST pressure must remain alongside the three body children");
    }

    @Test
    void productionIcz2BossDefeatStopsAllBossSnowing() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 1)
                .startPosition((short) 0x4430, (short) 0x0658)
                .startPositionIsCentre()
                .build();
        ObjectManager objectManager = GameServices.level().getObjectManager();
        fixture.camera().setX((short) 0x4390);
        fixture.camera().setY((short) 0x05F8);
        fixture.camera().setMinX((short) 0x4300);
        fixture.camera().setMaxX((short) 0x4700);
        fixture.camera().setMinY((short) 0x0500);
        fixture.camera().setMaxY((short) 0x0700);
        objectManager.reset(0x4390);

        for (int frame = 0; frame < 150; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(activeSnowdustEmitterCount(objectManager) > 0,
                "ICZ2 boss setup should have active subtype-$18 snow emitters before defeat");
        assertTrue(hasActiveObjectNamed(objectManager, "SnowdustParticle"),
                "ICZ2 boss setup should have visible snow particles before defeat");

        AbstractObjectInstance boss = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getSimpleName().equals("IczEndBossInstance"))
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
        for (int hit = 0; hit < 8; hit++) {
            boss.getClass().getMethod("forceHitForTesting").invoke(boss);
            fixture.stepFrame(false, false, false, false, false);
        }
        for (int frame = 0; frame < 2; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertEquals(0, activeSnowdustEmitterCount(objectManager),
                "Final boss defeat should stop every active subtype-$18 ICZ snow emitter, including layout-loaded emitters");
        assertTrue(hasActiveObjectNamed(objectManager, "SnowdustParticle"),
                "Final boss defeat should stop new ICZ snow spawns without deleting already-active flakes");

        int particleCountAfterDefeat = activeObjectCountNamed(objectManager, "SnowdustParticle");
        for (int frame = 0; frame < 260; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            assertTrue(activeObjectCountNamed(objectManager, "SnowdustParticle") <= particleCountAfterDefeat,
                    "No new ICZ boss snow particles should spawn after the final hit");
        }
        assertFalse(hasActiveObjectNamed(objectManager, "SnowdustParticle"),
                "Existing ICZ boss snow particles should drain naturally once they leave the screen");
    }

    private static ObjectInstance createTriggeredBoss() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x4490, 0x05B8, ICZ_END_BOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x4390);
        services.camera.setY((short) 0x05F8);
        object.setServices(services);
        return instance;
    }

    private static void stepFrames(ObjectInstance instance, int frames) {
        PlayableEntity player = mock(PlayableEntity.class);
        stepFrames(instance, frames, player);
    }

    private static void stepFrames(ObjectInstance instance, int frames, PlayableEntity player) {
        for (int frame = 1; frame <= frames; frame++) {
            instance.update(frame, player);
        }
    }

    private static void stepUntilFrostPuffsSpawn(ObjectInstance instance, int maxFrames) throws Exception {
        PlayableEntity player = mock(PlayableEntity.class);
        for (int frame = 1; frame <= maxFrames; frame++) {
            instance.update(frame, player);
            if (invokeInt(instance, "getFrostPuffCountForTesting") > 0) {
                return;
            }
        }
        throw new AssertionError("Expected Obj_ICZEndBoss to spawn frost puffs within " + maxFrames + " frames");
    }

    private static void stepUntilNoFrostPuffsRemain(ObjectInstance instance, int maxFrames) throws Exception {
        PlayableEntity player = mock(PlayableEntity.class);
        for (int frame = 1; frame <= maxFrames; frame++) {
            instance.update(800 + frame, player);
            if (invokeInt(instance, "getFrostPuffCountForTesting") == 0) {
                return;
            }
        }
        throw new AssertionError("Expected existing Obj_ICZEndBoss frost puffs to finish within " + maxFrames + " frames");
    }

    private static int invokeInt(Object target, String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
        }
        return (Integer) target.getClass().getMethod(method, types).invoke(target, args);
    }

    private static void bindPlayerToFrostPuff(ObjectInstance instance, AbstractPlayableSprite player, int puffIndex) {
        org.mockito.Mockito.when(player.getCentreX()).thenAnswer(invocation ->
                frostPuffCoordinate(instance, "getFrostPuffXForTesting", puffIndex));
        org.mockito.Mockito.when(player.getCentreY()).thenAnswer(invocation ->
                frostPuffCoordinate(instance, "getFrostPuffYForTesting", puffIndex));
    }

    private static void placePlayerAtFrostPuff(
            ObjectInstance instance, AbstractPlayableSprite player, int puffIndex) {
        player.setCentreX(frostPuffCoordinate(instance, "getFrostPuffXForTesting", puffIndex));
        player.setCentreY(frostPuffCoordinate(instance, "getFrostPuffYForTesting", puffIndex));
    }

    private static void publishBossSolidContact(ObjectInstance instance, PlayableEntity player, int frameCounter) {
        ((SolidObjectListener) instance).onSolidContactCleared(player, frameCounter);
    }

    private static short frostPuffCoordinate(ObjectInstance instance, String method, int puffIndex) {
        try {
            return (short) invokeInt(instance, method, puffIndex);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isTopSteamOffset(int topX, int topY, int puffX, int puffY) {
        int[][] offsets = {{0x18, -4}, {0x14, 0}, {0x10, -8}, {8, -4}};
        for (int[] offset : offsets) {
            if (puffX == topX + offset[0] && puffY == topY + offset[1]) {
                return true;
            }
        }
        return false;
    }

    private static int distinctFrameCount(ObjectInstance instance, int minInclusive, int maxInclusive) throws Exception {
        int distinct = 0;
        int[] seen = new int[maxInclusive - minInclusive + 1];
        int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
        for (int i = 0; i < puffCount; i++) {
            int frame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
            if (frame < minInclusive || frame > maxInclusive) {
                continue;
            }
            int index = frame - minInclusive;
            if (seen[index] == 0) {
                seen[index] = 1;
                distinct++;
            }
        }
        return distinct;
    }

    private static boolean hasSelectorTwoLargeParentSmoke(ObjectInstance instance) throws Exception {
        int bossX = ((AbstractObjectInstance) instance).getX();
        int bossY = ((AbstractObjectInstance) instance).getY();
        int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
        for (int i = 0; i < puffCount; i++) {
            int frame = invokeInt(instance, "getFrostPuffFrameForTesting", i);
            if (frame >= 0x0B && frame <= 0x0F
                    && isSelectorTwoLargeSmokeOffset(
                            bossX,
                            bossY,
                            invokeInt(instance, "getFrostPuffXForTesting", i),
                            invokeInt(instance, "getFrostPuffYForTesting", i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelectorTwoLargeSmokeOffset(int bossX, int bossY, int puffX, int puffY) {
        int[][] offsets = {{0x08, 0x40}, {0, 0x3C}, {-0x10, 0x40}, {-0x08, 0x3C}};
        for (int[] offset : offsets) {
            if (puffX == bossX + offset[0] && puffY == bossY + offset[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelectorTwoSmallSmokeOffset(int bossX, int bossY, int puffX, int puffY) {
        int[][] offsets = {{-0x04, 0x34}, {-0x04, 0x28}};
        for (int[] offset : offsets) {
            if (puffX == bossX + offset[0] && puffY == bossY + offset[1]) {
                return true;
            }
        }
        return false;
    }

    private static int findSelectorTwoSecondSmallSmoke(ObjectInstance instance) throws Exception {
        int bossX = ((AbstractObjectInstance) instance).getX();
        int bossY = ((AbstractObjectInstance) instance).getY();
        int puffCount = invokeInt(instance, "getFrostPuffCountForTesting");
        for (int i = 0; i < puffCount; i++) {
            if (invokeInt(instance, "getFrostPuffXForTesting", i) == bossX - 0x04
                    && invokeInt(instance, "getFrostPuffYForTesting", i) == bossY + 0x28) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMirroredTopSteamOffset(int topX, int topY, int puffX, int puffY) {
        int[][] offsets = {{0x18, -4}, {0x14, 0}, {0x10, -8}, {8, -4}};
        for (int[] offset : offsets) {
            if (puffX == topX - offset[0] && puffY == topY + offset[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActiveObjectNamed(ObjectManager objectManager, String simpleName) {
        return objectManager.getActiveObjects().stream()
                .anyMatch(object -> object.getClass().getSimpleName().equals(simpleName));
    }

    private static int activeObjectCountNamed(ObjectManager objectManager, String simpleName) {
        return (int) objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getSimpleName().equals(simpleName))
                .count();
    }

    private static long activeSnowdustEmitterCount(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(IczSnowPileObjectInstance.class::isInstance)
                .map(IczSnowPileObjectInstance.class::cast)
                .filter(emitter -> (emitter.getSpawn().subtype() & 0x7F) == 0x18)
                .filter(emitter -> !emitter.isDestroyed())
                .count();
    }

    private static long frozenPlayerBlockCount(RecordingServices services) {
        return services.spawnedChildren.stream()
                .filter(child -> child.getClass().getSimpleName().contains("FrozenPlayerBlock"))
                .count();
    }

    private static AbstractObjectInstance findRobotnikEscapeShip(RecordingServices services) {
        return services.spawnedChildren.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> child.getClass().getSimpleName().equals("IczEndBossRobotnikEscapeShip"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected independent Obj_RobotnikShip4 escape child"));
    }

    private static final class RecordingServices extends StubObjectServices {
        private final ResultsHardwareTimingFixture resultsTiming = new ResultsHardwareTimingFixture();
        private final Camera camera = new Camera();
        private final GameStateManager gameState = new GameStateManager();
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;
        private final ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        private Level currentLevel;
        private Rom rom;
        private int fadeOutCalls;
        private int lastMusicId;

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            doAnswer(this::recordSpawnedChild).when(objectManager).addDynamicObjectAfterCurrent(any());
            doAnswer(this::recordSpawnedChild).when(objectManager).addDynamicObjectAfterCurrentNextFrame(any());
            doAnswer(this::recordSpawnedChild).when(objectManager).addDynamicObject(any());
            doAnswer(this::recordSpawnedChild).when(objectManager).addDynamicObjectNextFrame(any());
            doAnswer(this::recordSpawnedChild).when(objectManager).addAuxiliaryDynamicObject(any());
            doAnswer(this::recordSpawnedChild).when(objectManager).addRewindableAuxiliaryDynamicObject(any());
        }

        private Object recordSpawnedChild(org.mockito.invocation.InvocationOnMock invocation) {
            ObjectInstance child = invocation.getArgument(0);
            if (child instanceof AbstractObjectInstance instance) {
                instance.setServices(this);
            }
            spawnedChildren.add(child);
            return null;
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }

        @Override
        public Level currentLevel() {
            return currentLevel;
        }

        @Override
        public Rom rom() {
            return rom != null ? rom : TestEnvironment.currentRom();
        }

        @Override
        public com.openggf.data.RomByteReader romReader() {
            try {
                return com.openggf.data.RomByteReader.fromRom(rom());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public com.openggf.game.timing.HardwareTimingService hardwareTiming() {
            return resultsTiming.hardwareTiming();
        }

        @Override
        public RuntimeArtCoordinator runtimeArtCoordinator() {
            return TestEnvironment.activeGameplayMode().runtimeArtCoordinator();
        }

        @Override
        public void fadeOutMusic() {
            fadeOutCalls++;
        }

        @Override
        public void playMusic(int musicId) {
            lastMusicId = musicId;
        }
    }
}
