package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.IczMinibossPostBossPaletteController;
import com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance;
import com.openggf.game.sonic3k.objects.S3kBossDefeatSignpostFlow;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kIczMinibossObject {

    private static final int ICZ_MINIBOSS_ID = 0xBC;

    // Clear any gameplay session leaked by a prior test in this fork so the registry
    // resolves the S3KL zone set (not a leaked SKL zone). Parallel-suite flake fix.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesIczMinibossInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));

        assertEquals("IczMinibossInstance", instance.getClass().getSimpleName());
    }

    @Test
    void initialStateMatchesRomSetup() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        Object boss = instance;
        object.setServices(new StubObjectServices());

        assertEquals(0x05F0, object.getX());
        assertEquals(0x07F0, object.getY());
        assertEquals(6, invokeInt(boss, "getCollisionProperty"));
        assertEquals(0, invokeInt(boss, "getCollisionFlags"),
                "The shared boss-camera gate runs before Obj_ICZMiniboss sets collision_flags=$C6");
        assertEquals(8, invokeInt(boss, "getOrbCountForTesting"));
        assertEquals(6, invokeInt(boss, "getShardCountForTesting"));
        assertEquals(0xBF, invokeInt(boss, "getRoutineTimerForTesting"));
        assertEquals(0, invokeInt(boss, "getCurrentRoutine"));

        PlayableEntity player = mock(PlayableEntity.class);
        instance.update(1, player);
        instance.update(2, player);
        instance.update(3, player);

        assertEquals(0x07F0, object.getY(),
                "The ROM waits on the shared boss-camera gate before starting the descent");
        assertEquals(0x80, invokeInt(boss, "getYVelocityForTesting"));
        assertEquals(0x06CD, invokeInt(boss, "getOrbXForTesting", 0),
                "Snowballs use _unkFAB4+word_71730 plus the ROM Random_Number jitter");
        assertEquals(0x0392, invokeInt(boss, "getOrbYForTesting", 0),
                "Snowballs use _unkFAB0+$D8 plus the ROM Random_Number jitter");
        assertEquals(2, invokeInt(boss, "getOrbPaletteLineForTesting"));
        assertTrue((Boolean) boss.getClass().getMethod("isOrbVisibleForTesting", int.class).invoke(boss, 0),
                "The ROM draws the floor snowballs while they wait for the parent arm flag");
    }

    @Test
    void minibossTouchCollisionIsInactiveUntilSharedBossGateCompletes() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);

        instance.update(1, mock(PlayableEntity.class));

        assertFalse((Boolean) instance.getClass().getMethod("isArenaGateCompleteForTesting").invoke(instance),
                "The first frame has only entered the shared boss gate, not the live boss routine");
        assertEquals(0, ((TouchResponseProvider) instance).getCollisionFlags(),
                "Obj_ICZMiniboss does not run SetUp_ObjAttributes/collision_flags until after loc_85CA4");
        assertEquals(null, ((TouchResponseProvider) instance).getMultiTouchRegions(),
                "The invisible pre-gate miniboss must not expose body or orb touch regions");
    }

    @Test
    void renderDuringCameraGateSkipsOrbsThatHaveNotBeenCreatedYet() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x07F0, 0x0280, ICZ_MINIBOSS_ID, 0x00, 0, false, 0x0280));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        RecordingServices services = new RecordingServices(renderer);
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);

        instance.update(1, mock(PlayableEntity.class));

        assertDoesNotThrow(() -> instance.appendRenderCommands(new ArrayList<>()),
                "The camera gate renders before loc_711EC creates the eight orb children");
    }

    @Test
    void secondDispatchLocksArenaAndMinibossMusicStartsAfterFadeGate() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);
        Object boss = instance;

        instance.update(1, mock(PlayableEntity.class));

        assertEquals(0x0000, services.camera.getMinX() & 0xFFFF,
                "Obj_ICZMiniboss returns after sub_85D6A; loc_85CA4 starts next dispatch");
        instance.update(2, mock(PlayableEntity.class));

        assertEquals(0x06F0, services.camera.getMinX() & 0xFFFF);
        assertEquals(0x06F0, services.camera.getMaxX() & 0xFFFF);
        assertEquals(0x02B8, services.camera.getMinY() & 0xFFFF);
        assertEquals(0x0000, services.camera.getMaxY() & 0xFFFF,
                "loc_85CF2 updates Camera_target_max_Y_pos, not the live maximum word");
        assertEquals(0x02B8, services.camera.getMaxYTarget() & 0xFFFF);
        assertEquals(Sonic3kObjectIds.ICZ_MINIBOSS, services.gameState.getCurrentBossId());
        assertEquals(1, services.fadeOutCalls);
        assertEquals(0, services.lastMusicId);
        assertFalse((Boolean) boss.getClass().getMethod("isArenaGateCompleteForTesting").invoke(boss));

        for (int frame = 3; frame <= 122; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
        }

        assertEquals(Sonic3kMusic.MINIBOSS.id, services.lastMusicId);
        assertTrue((Boolean) boss.getClass().getMethod("isArenaGateCompleteForTesting").invoke(boss));
    }

    @Test
    void offRouteVariantDeletesBeforeOwningArena() {
        ObjectInstance lowerRouteInstance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x07F0, 0x0890, ICZ_MINIBOSS_ID, 0x02, 0, false, 0));
        AbstractObjectInstance object = (AbstractObjectInstance) lowerRouteInstance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);

        lowerRouteInstance.update(1, mock(PlayableEntity.class));

        assertFalse(lowerRouteInstance.isDestroyed(),
                "The ROM only deletes from Check_CameraInRange if the object is outside the object-load window");
        assertEquals(0, services.fadeOutCalls);
        assertEquals(0, services.gameState.getCurrentBossId());
        assertEquals(0x0000, services.camera.getMinY() & 0xFFFF);
    }

    @Test
    void upperRouteVariantWaitsBeforeTriggerAndStartsWhenCameraEntersRange() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x07F0, 0x0280, ICZ_MINIBOSS_ID, 0x00, 0, false, 0x0280));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x0580);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);
        Object boss = instance;

        instance.update(1, mock(PlayableEntity.class));

        assertFalse(instance.isDestroyed(),
                "The boss can spawn before the Check_CameraInRange X threshold and must wait for the trigger");
        assertEquals(0, services.fadeOutCalls);
        assertFalse((Boolean) boss.getClass().getMethod("isArenaGateCompleteForTesting").invoke(boss));

        services.camera.setX((short) 0x05F0);
        instance.update(2, mock(PlayableEntity.class));

        assertEquals(1, services.fadeOutCalls);
        assertEquals(Sonic3kObjectIds.ICZ_MINIBOSS, services.gameState.getCurrentBossId());
        assertEquals(0x0000, services.camera.getMinX() & 0xFFFF,
                "The initialization dispatch returns before loc_85CA4");

        instance.update(3, mock(PlayableEntity.class));

        assertEquals(0x05F0, services.camera.getMinX() & 0xFFFF,
                "loc_85CA4 follows the approaching camera until it reaches the X lock");
        assertEquals(0x02B8, services.camera.getMinY() & 0xFFFF);
    }

    @Test
    void directIcz2EntryDeletesMinibossBeforeOwningArena() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x07F0, 0x0280, ICZ_MINIBOSS_ID, 0x00, 0, false, 0x0280));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.currentAct = 1;
        services.apparentAct = 1;
        services.camera.setX((short) 0x05F0);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);

        instance.update(1, mock(PlayableEntity.class));

        assertTrue(instance.isDestroyed(),
                "ROM Obj_ICZMiniboss deletes immediately when Apparent_zone_and_act == $0501");
        assertEquals(0, services.fadeOutCalls);
        assertEquals(0, services.gameState.getCurrentBossId());
        assertEquals(0x0000, services.camera.getMinY() & 0xFFFF);
    }

    @Test
    void descentTimerRunsCallbackAndStopsVerticalMotion() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x07F0, 0x0280, ICZ_MINIBOSS_ID, 0x00, 0, false, 0x0280));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);
        Object boss = instance;

        for (int frame = 1; frame <= 124 + 0xC1; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
        }
        int yAfterCallback = object.getY();
        for (int frame = 124 + 0xC2; frame <= 124 + 0xD0; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
        }

        assertEquals(4, invokeInt(instance, "getCurrentRoutine"),
                "loc_71236 should switch the parent from descent to the wait/shard-release state"
                        + " timer=" + invokeInt(instance, "getRoutineTimerForTesting")
                        + " y=" + object.getY()
                        + " gate=" + boss.getClass().getMethod("isArenaGateCompleteForTesting").invoke(boss));
        assertEquals(yAfterCallback, object.getY(),
                "The boss must stop descending once Obj_Wait invokes the loc_71236 callback");
    }

    @Test
    void parentArcPassesNegateXVelocityLikeRomScratchWord3E() throws Exception {
        ObjectInstance instance = createTriggeredUpperRouteBoss();

        stepUntil(instance, () -> invokeInt(instance, "getCurrentRoutine") == 0x0A
                && invokeInt(instance, "getXVelocityForTesting") == -0x200, 900);

        assertEquals(-0x200, invokeInt(instance, "getXVelocityForTesting"),
                "loc_712DA negates $3E before the first arc, so the first pass moves left");

        stepUntil(instance, () -> invokeInt(instance, "getCurrentRoutine") == 0x0C, 120);

        assertEquals(0x3F, invokeInt(instance, "getRoutineTimerForTesting"),
                "loc_71318 enters routine $C and waits before loc_7133A decides whether to repeat the arc");
        for (int frame = 0; frame < 63; frame++) {
            instance.update(10_000 + frame, mock(PlayableEntity.class));
        }
        assertEquals(0x0C, invokeInt(instance, "getCurrentRoutine"),
                "Obj_Wait must retain the ROM's full $3F countdown between arc passes");

        stepUntil(instance, () -> invokeInt(instance, "getCurrentRoutine") == 0x0A
                && invokeInt(instance, "getXVelocityForTesting") == 0x200, 140);

        assertEquals(0x200, invokeInt(instance, "getXVelocityForTesting"),
                "loc_7133A repeats the same pattern pass by falling back to loc_712DA, not by selecting a new pattern");
    }

    @Test
    void shardsUseParentRelativePackedByteMotion() throws Exception {
        ObjectInstance instance = createTriggeredUpperRouteBoss();
        AbstractObjectInstance object = (AbstractObjectInstance) instance;

        stepUntil(instance, () -> (invokeInt(instance, "getParentFlagsForTesting") & (1 << 3)) != 0, 400);

        assertEquals(0x04, invokeInt(instance, "getShardRoutineForTesting", 0));
        int parentX = object.getX();
        int parentY = object.getY();
        assertEquals(parentX - 0x0E, invokeInt(instance, "getShardXForTesting", 0));
        assertEquals(parentY - 0x0B, invokeInt(instance, "getShardYForTesting", 0));

        instance.update(999, mock(PlayableEntity.class));

        assertEquals(parentX - 0x0F, invokeInt(instance, "getShardXForTesting", 0),
                "word_716DC=-1 adds low byte -1 to child_dx");
        assertEquals(parentY - 0x0C, invokeInt(instance, "getShardYForTesting", 0),
                "word_716DC=-1 adds high byte -1 to child_dy");

        assertEquals(parentX + 0x0F, invokeInt(instance, "getShardXForTesting", 1),
                "word_716DC=$01FF adds high byte +1 to child_dx");
        assertEquals(parentY - 0x0C, invokeInt(instance, "getShardYForTesting", 1),
                "word_716DC=$01FF adds low byte -1 to child_dy");
    }

    @Test
    void orbsRiseAttachOrbitAndReleaseParentGate() throws Exception {
        ObjectInstance instance = createTriggeredUpperRouteBoss();

        stepUntil(instance, () -> (invokeInt(instance, "getParentFlagsForTesting") & (1 << 2)) != 0, 700);

        assertEquals(0x04, invokeInt(instance, "getOrbRoutineForTesting", 0),
                "When parent $38 bit 2 is set, loc_71502 lifts the floor snowballs");

        stepUntil(instance, () -> invokeInt(instance, "getOrbRoutineForTesting", 0) == 0x08, 120);

        assertEquals(0x8B, invokeInt(instance, "getOrbCollisionFlagsForTesting", 0),
                "loc_71566 makes the orbiting snowballs touch-active");

        stepUntil(instance, () -> invokeInt(instance, "getCurrentRoutine") == 0x10, 900);

        assertEquals(0, invokeInt(instance, "getParentFlagsForTesting") & (1 << 1),
                "Orb routine $C must clear parent $38 bit 1 so the boss can leave loc_7135E");
        assertEquals(0, invokeInt(instance, "getParentFlagsForTesting") & (1 << 2),
                "loc_7136C clears parent $38 bit 2 during palette slowdown");
    }

    @Test
    void paletteSlowdownUsesRomScriptDurationBeforeRecoverWait() throws Exception {
        ObjectInstance instance = createTriggeredUpperRouteBoss();

        stepUntil(instance, () -> invokeInt(instance, "getCurrentRoutine") == 0x10, 1_500);

        for (int frame = 0; frame < 104; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
            assertEquals(0x10, invokeInt(instance, "getCurrentRoutine"),
                    "word_71B52 must retain routine $10 until its 105th dispatch");
        }

        instance.update(104, mock(PlayableEntity.class));
        assertEquals(0x12, invokeInt(instance, "getCurrentRoutine"),
                "word_71B52 calls loc_71390 on the 105th dispatch");
        assertEquals(0x3F, invokeInt(instance, "getRoutineTimerForTesting"),
                "loc_71390 starts the separate $3F recovery wait");
    }

    @Test
    void orbitAnglesUseRomSubtypeSpacingForCompleteRing() throws Exception {
        ObjectInstance instance = createTriggeredUpperRouteBoss();

        stepUntil(instance, () -> invokeInt(instance, "getOrbRoutineForTesting", 0) == 0x08, 820);

        for (int i = 0; i < 8; i++) {
            assertEquals((i << 5) & 0xFF, invokeInt(instance, "getOrbAngleCForTesting", i),
                    "sub_71740 uses subtype<<4; CreateChild6 assigns subtypes 0,2,4... so adjacent orbs are 0x20 apart");
        }
    }

    @Test
    void orbitingOrbsExposeIndependentHurtTouchRegions() throws Exception {
        ObjectInstance instance = createTriggeredUpperRouteBoss();

        stepUntil(instance, () -> invokeInt(instance, "getOrbRoutineForTesting", 0) == 0x08, 820);

        TouchResponseProvider.TouchRegion[] regions =
                ((TouchResponseProvider) instance).getMultiTouchRegions();
        assertNotNull(regions);
        assertTrue(Arrays.stream(regions).anyMatch(region ->
                        region.x() == invokeIntUnchecked(instance, "getOrbXForTesting", 0)
                                && region.y() == invokeIntUnchecked(instance, "getOrbYForTesting", 0)
                                && region.collisionFlags() == 0x8B),
                "loc_71566 sets collision_flags=$8B on the orbiting snowballs, so touch must check orb regions");
    }

    @Test
    void bossHitAppliesRomPaletteFlashWords() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));
        ((AbstractObjectInstance) instance).setServices(new StubObjectServices());
        Object boss = instance;

        invokeVoid(boss, "simulateHitForTest");

        assertTrue((Boolean) boss.getClass().getMethod("isHitFlashBrightForTesting").invoke(boss));
        assertEquals(0x0EEE, invokeInt(boss, "getHitFlashPaletteWordForTesting", 0));
        assertEquals(0x0EEE, invokeInt(boss, "getHitFlashPaletteWordForTesting", 1));

        instance.update(1, mock(PlayableEntity.class));
        assertTrue((Boolean) boss.getClass().getMethod("isHitFlashBrightForTesting").invoke(boss));

        instance.update(2, mock(PlayableEntity.class));

        assertFalse((Boolean) boss.getClass().getMethod("isHitFlashBrightForTesting").invoke(boss));
        assertEquals(0x0222, invokeInt(boss, "getHitFlashPaletteWordForTesting", 0));
        assertEquals(0x0020, invokeInt(boss, "getHitFlashPaletteWordForTesting", 1));
    }

    @Test
    void sixHitsEnterDefeatAndDisableTouch() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));
        ((AbstractObjectInstance) instance).setServices(new StubObjectServices());
        Object boss = instance;

        for (int i = 0; i < 6; i++) {
            invokeVoid(boss, "simulateHitForTest");
        }

        assertEquals(0, invokeInt(boss, "getCollisionProperty"));
        assertEquals(0, invokeInt(boss, "getCollisionFlags"));
        assertTrue((Boolean) boss.getClass().getMethod("isDefeated").invoke(boss));
    }

    @Test
    void defeatRunsRomExplosionThenQueuesSignpostFlow() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        ((AbstractObjectInstance) instance).setServices(services);
        Object boss = instance;

        for (int frame = 1; frame <= 123; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
        }
        assertTrue((Boolean) boss.getClass().getMethod("isArenaGateCompleteForTesting").invoke(boss));

        for (int i = 0; i < 6; i++) {
            invokeVoid(boss, "simulateHitForTest");
        }

        for (int frame = 124; frame <= 260; frame++) {
            instance.update(frame, mock(PlayableEntity.class));
            for (ObjectInstance child : List.copyOf(services.spawnedChildren)) {
                if (child.getClass().getSimpleName().equals("IczMinibossExplosionControllerChild")
                        && !child.isDestroyed()) {
                    child.update(frame, mock(PlayableEntity.class));
                }
            }
        }

        assertTrue(services.spawnedChildren.stream().anyMatch(S3kBossExplosionChild.class::isInstance),
                "loc_71926 creates Child6_CreateBossExplosion before handing off to Obj_EndSignControl");
        assertTrue(services.spawnedChildren.stream().anyMatch(IczMinibossPostBossPaletteController.class::isInstance),
                "loc_713E8 allocates loc_71420 before the signpost drop; it runs word_719FA on BG palette line 4");
        ObjectInstance signpostFlow = services.spawnedChildren.stream()
                .filter(S3kBossDefeatSignpostFlow.class::isInstance)
                .findFirst()
                .orElse(null);
        assertNotNull(signpostFlow,
                "loc_713E8 must enter Obj_EndSignControl so the signpost/results/title-card sequence can run");
        assertEquals(0, readIntField(signpostFlow, "apparentAct"),
                "ICZ has already reloaded act 2 resources, but Apparent_act remains 0 until the results/title-card handoff");
        assertEquals(S3kBossDefeatSignpostFlow.CleanupAction.RESTORE_ICZ2_OBJECT_PALETTE,
                readField(signpostFlow, "cleanupAction"),
                "AfterBoss_ICZ2 reloads Pal_ICZ2 through PalLoad_Line1 so ICZ2 badniks do not keep the boss palette");
        assertEquals(0x1D, readIntField(signpostFlow, "initialWaitCatchUpEntries"),
                "the separate explosion-controller SST leaves 29 folded Obj_EndSignControl entries");
        assertEquals(true, readField(signpostFlow, "preservesGroundedResultsDispatchBoundary"),
                "the separately allocated flow must retain the grounded routine-$06 result dispatch");
        assertEquals(0, readIntField(signpostFlow, "resultsWaitDurationAdjustment"),
                "the preloaded-act camera handoff does not add a results wait entry before control restoration");
        assertTrue(instance.isDestroyed(),
                "The boss body should delete only after queuing the persistent signpost flow");
    }

    @Test
    void bossDefeatedWaitStopsSnowEmitterBeforeExplosionControllerFinishes() throws Exception {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x05F0, 0x07F0, ICZ_MINIBOSS_ID, 0, 0, false, 0));
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        ((AbstractObjectInstance) instance).setServices(services);

        IczSnowPileObjectInstance emitter = new IczSnowPileObjectInstance(
                new ObjectSpawn(0x06F0, 0x02B0, Sonic3kObjectIds.ICZ_SNOW_PILE,
                        0x18, 0, false, -1));
        emitter.setServices(services);
        when(services.objectManager.activeObjectsOfType(IczSnowPileObjectInstance.class))
                .thenReturn(List.of(emitter));

        PlayableEntity player = mock(PlayableEntity.class);
        for (int frame = 1; frame <= 123; frame++) {
            instance.update(frame, player);
        }
        for (int hit = 0; hit < 6; hit++) {
            invokeVoid(instance, "simulateHitForTest");
        }

        for (int frame = 124; frame <= 186; frame++) {
            instance.update(frame, player);
        }
        assertFalse(emitter.isDestroyed(),
                "BossDefeated $2E=$3F must retain the active snow owner through its 63rd wait dispatch");

        instance.update(187, player);
        assertTrue(emitter.isDestroyed(),
                "Wait_FadeToLevelMusic must execute loc_713E8 on the 64th dispatch and set the snow emitter stop bit");
    }

    @Test
    void icz2AfterBossCleanupRestoresObjectPaletteLineFromPalPointers() throws Exception {
        Palette[] palettes = blankPalettes();
        Level level = mock(Level.class);
        when(level.getPaletteCount()).thenReturn(palettes.length);
        when(level.getPalette(0)).thenReturn(palettes[0]);
        when(level.getPalette(1)).thenReturn(palettes[1]);
        when(level.getPalette(2)).thenReturn(palettes[2]);
        when(level.getPalette(3)).thenReturn(palettes[3]);

        byte[] icz2Line1 = {
                0x00, 0x00, 0x0E, (byte) 0xEE, 0x0C, (byte) 0xEA, 0x0A, (byte) 0xC8,
                0x08, (byte) 0xA6, 0x06, (byte) 0x84, 0x04, 0x62, 0x02, 0x40,
                0x00, 0x20, 0x00, 0x00, 0x02, 0x22, 0x04, 0x44,
                0x06, 0x66, 0x08, (byte) 0x88, 0x0A, (byte) 0xAA, 0x0C, (byte) 0xCC
        };
        int palEntry = Sonic3kConstants.PAL_POINTERS_ADDR
                + Sonic3kConstants.PAL_POINTERS_ICZ2_INDEX * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
        Rom rom = mock(Rom.class);
        when(rom.read32BitAddr(palEntry)).thenReturn(0x00123456);
        when(rom.readBytes(0x123456, 32)).thenReturn(icz2Line1);

        S3kBossDefeatSignpostFlow flow = new S3kBossDefeatSignpostFlow(
                0x06F0, 0, S3kBossDefeatSignpostFlow.CleanupAction.RESTORE_ICZ2_OBJECT_PALETTE);
        RecordingServices services = new RecordingServices();
        services.currentLevel = level;
        services.rom = rom;
        flow.setServices(services);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        for (int frame = 1; frame <= 120; frame++) {
            flow.update(frame, player);
        }

        // Obj_EndSignControl's installation call returns before Obj_Wait starts.
        // Calls 2-120 perform 119 WAIT_FADE decrements; call 121 runs cleanup.
        assertColorWord(palettes[1], 1, 0x0000);
        flow.update(121, player);

        assertColorWord(palettes[1], 1, 0x0EEE);
        assertColorWord(palettes[1], 15, 0x0CCC);
        assertEquals(S3kPaletteOwners.ICZ_MINIBOSS,
                services.paletteOwnershipRegistry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertEquals(S3kPaletteOwners.ICZ_MINIBOSS,
                services.paletteOwnershipRegistry.ownerAt(PaletteSurface.NORMAL, 1, 15));
    }

    @Test
    void postBossPaletteControllerRunsWord719faPaletteScript() {
        Palette[] palettes = blankPalettes();
        Level level = mock(Level.class);
        when(level.getPaletteCount()).thenReturn(palettes.length);
        when(level.getPalette(3)).thenReturn(palettes[3]);

        IczMinibossPostBossPaletteController controller = new IczMinibossPostBossPaletteController();
        RecordingServices services = new RecordingServices();
        services.currentLevel = level;
        controller.setServices(services);

        PlayableEntity player = mock(PlayableEntity.class);
        controller.update(1, player);

        assertColorWord(palettes[3], 1, 0x0EEC);
        assertColorWord(palettes[3], 10, 0x0600);

        for (int frame = 2; frame <= 8; frame++) {
            controller.update(frame, player);
        }
        assertColorWord(palettes[3], 3, 0x0C80);

        controller.update(9, player);
        assertColorWord(palettes[3], 3, 0x0C82);

        for (int frame = 10; frame <= 73; frame++) {
            controller.update(frame, player);
        }
        assertColorWord(palettes[3], 10, 0x0E00);

        for (int frame = 74; frame <= 81; frame++) {
            controller.update(frame, player);
        }
        assertTrue(controller.isDestroyed(),
                "palscriptrun finishes after the last 8-frame row and the ROM deletes the helper object");
    }

    @Test
    void profileMarksIczMinibossImplementedOnlyForS3kl() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();

        assertTrue(profile.getImplementedIds().contains(ICZ_MINIBOSS_ID));
        assertTrue(profile.getImplementedIds(profile.getLevels().get(10)).contains(ICZ_MINIBOSS_ID));
        assertFalse(profile.getImplementedIds(profile.getLevels().get(14)).contains(ICZ_MINIBOSS_ID));
    }

    @Test
    void iczMinibossSheetIsRegisteredDuringIczLoad() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 1)
                .build();

        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) com.openggf.game.GameModuleRegistry
                        .getCurrent().getObjectArtProvider();

        var sheet = provider.getSheet("icz_miniboss");
        assertNotNull(sheet, "ICZ miniboss sheet should be registered after ICZ load");
        assertTrue(sheet.getPatterns().length > 0);
        assertTrue(sheet.getFrameCount() >= 15);
    }

    private static int invokeInt(Object target, String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
        }
        return (Integer) target.getClass().getMethod(method, types).invoke(target, args);
    }

    private static void invokeVoid(Object target, String method) throws Exception {
        target.getClass().getMethod(method).invoke(target);
    }

    private static int invokeIntUnchecked(Object target, String method, Object... args) {
        try {
            return invokeInt(target, method, args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        return (Integer) readField(target, fieldName);
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Palette[] blankPalettes() {
        Palette[] palettes = new Palette[4];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = new Palette();
        }
        return palettes;
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int expectedR = (((lowByte >> 1) & 0x07) * 255 + 3) / 7;
        int expectedG = (((lowByte >> 5) & 0x07) * 255 + 3) / 7;
        int expectedB = (((highByte >> 1) & 0x07) * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF,
                "Red for color " + colorIndex);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF,
                "Green for color " + colorIndex);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF,
                "Blue for color " + colorIndex);
    }

    private static ObjectInstance createTriggeredUpperRouteBoss() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x07F0, 0x0280, ICZ_MINIBOSS_ID, 0x00, 0, false, 0x0280));
        AbstractObjectInstance object = (AbstractObjectInstance) instance;
        RecordingServices services = new RecordingServices();
        services.camera.setX((short) 0x06F0);
        services.camera.setY((short) 0x02B8);
        object.setServices(services);
        return instance;
    }

    private static void stepUntil(ObjectInstance instance, ThrowingCondition condition, int maxFrames) throws Exception {
        PlayableEntity player = mock(PlayableEntity.class);
        for (int frame = 1; frame <= maxFrames; frame++) {
            instance.update(frame, player);
            if (condition.matches()) {
                return;
            }
        }
        throw new AssertionError("Condition was not reached within " + maxFrames + " frames"
                + " routine=" + invokeInt(instance, "getCurrentRoutine")
                + " timer=" + invokeInt(instance, "getRoutineTimerForTesting"));
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean matches() throws Exception;
    }

    private static final class RecordingServices extends StubObjectServices {
        private final Camera camera = new Camera();
        private final GameStateManager gameState = new GameStateManager();
        private final PaletteOwnershipRegistry paletteOwnershipRegistry = new PaletteOwnershipRegistry();
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;
        private ObjectRenderManager renderManager;
        private Level currentLevel;
        private Rom rom;
        private int fadeOutCalls;
        private int lastMusicId;
        private int currentAct;
        private int apparentAct;

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof AbstractObjectInstance instance) {
                    instance.setServices(this);
                }
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        private RecordingServices(PatternSpriteRenderer renderer) {
            this();
            renderManager = new ObjectRenderManager(null) {
                @Override
                public PatternSpriteRenderer getRenderer(String key) {
                    return Sonic3kObjectArtKeys.ICZ_MINIBOSS.equals(key) ? renderer : null;
                }
            };
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
            return rom;
        }

        @Override
        public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
            return paletteOwnershipRegistry;
        }

        @Override
        public int currentAct() {
            return currentAct;
        }

        @Override
        public int apparentAct() {
            return apparentAct;
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
