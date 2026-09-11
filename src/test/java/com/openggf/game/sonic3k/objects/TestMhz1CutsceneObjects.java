package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.CheckpointState;
import com.openggf.game.GameRng;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rules.GameRules;
import com.openggf.game.RespawnState;
import com.openggf.game.save.SaveReason;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.data.Rom;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.data.Rom;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Nested;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhz1CutsceneObjects {
    private static final int MHZ1_SWITCH_SPAWN_Y = 0x0650;

    @Test
    void registryRoutesSklMhz1CutsceneSlotsToMhzHandlers() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance knuckles = registry.create(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        ObjectInstance button = registry.create(new ObjectSpawn(
                0x0380, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));

        assertInstanceOf(Mhz1CutsceneKnucklesInstance.class, knuckles,
                "SKL slot $A8 is Obj_MHZ1CutsceneKnuckles, not the S3KL Blaster slot");
        assertInstanceOf(Mhz1CutsceneButtonInstance.class, button,
                "SKL slot $A9 is Obj_MHZ1CutsceneButton, not the S3KL TechnoSqueek slot");
    }

    @Test
    void registryRoutesGenericMhzCutsceneKnucklesSubtypes() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance mhz2 = registry.create(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        ObjectInstance skIntro = registry.create(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        ObjectInstance mhz1 = registry.create(new ObjectSpawn(
                0x0374, 0x066C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));

        assertInstanceOf(CutsceneKnucklesMhz2Instance.class, mhz2,
                "Subtype $20 indexes CutsceneKnux_MHZ2 in the ROM table");
        assertInstanceOf(CutsceneKnucklesSkIntroInstance.class, skIntro,
                "Subtype $30 indexes CutsceneKnux_SKIntro in the ROM table");
        assertInstanceOf(CutsceneKnucklesMhz1Instance.class, mhz1,
                "Subtype $1C indexes CutsceneKnux_MHZ1; the peering sprite is its ChildObjDat_665B0 child");
    }

    @Test
    void mhz1CutsceneKnucklesDeletesImmediatelyForKnucklesRoute() {
        Mhz1CutsceneKnucklesInstance cutscene = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        cutscene.setServices(new TestObjectServices().withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0389, (short) 0x0580);

        cutscene.update(0, knuckles);

        assertTrue(cutscene.isDestroyed(),
                "Obj_MHZ1CutsceneKnuckles branches to CutsceneKnux_Delete when Player_1 character_id is Knuckles");
        assertEquals(0, cutscene.getWorkspaceRoutineForTest(),
                "the Knuckles route should not arm _unkFAB8/routine $02 for the Sonic/Tails cutscene");
    }

    @Test
    void mhz1CutsceneKnucklesDeletesImmediatelyWhenLastStarPostHitIsSet() {
        CheckpointState checkpointState = new CheckpointState();
        checkpointState.restoreFromSaved(0x0190, 0x056C, 0, 0, 1);
        Mhz1CutsceneKnucklesInstance cutscene = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        cutscene.setServices(new TestObjectServices() {
            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }
        });
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        cutscene.update(0, sonic);

        assertTrue(cutscene.isDestroyed(),
                "Obj_MHZ1CutsceneKnuckles branches to CutsceneKnux_Delete when Last_star_post_hit is nonzero");
        assertEquals(0, cutscene.getWorkspaceRoutineForTest(),
                "a resumed post-cutscene state must not arm _unkFAB8/routine $02 again");
    }

    @Test
    void mhz1PeerKnucklesRendersInitialRomFrame4FromPeerArt() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ1_CUTSCENE_KNUCKLES_PEER))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CutsceneKnucklesMhz1PeerInstance peer = new CutsceneKnucklesMhz1PeerInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        peer.setServices(new TestObjectServices().withLevelManager(levelManager));

        peer.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(4, 0x0374, 0x066C, false, false);
    }

    @Test
    void mhz1PeerKnucklesSlidesOutWithRomMoveSprite2BeforeAnimating() {
        CutsceneKnucklesMhz1PeerInstance peer = new CutsceneKnucklesMhz1PeerInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        peer.update(0, sonic);

        assertEquals(0x0376, peer.getX(),
                "loc_62FA2 calls MoveSprite2 with x_vel=$200 before Obj_Wait counts $2E");
        assertEquals(0x066C, peer.getY());

        for (int frame = 1; frame < 8; frame++) {
            peer.update(frame, sonic);
        }

        assertEquals(0x0384, peer.getX(),
                "Obj_Wait starts at $2E=7, so loc_62FB4 is reached after eight +2px MoveSprite2 steps");
    }

    @Test
    void mhz1PeerKnucklesUsesRomRawMultiDelayAnimationAfterSlidingOut() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ1_CUTSCENE_KNUCKLES_PEER))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CutsceneKnucklesMhz1PeerInstance peer = new CutsceneKnucklesMhz1PeerInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        peer.setServices(new TestObjectServices().withLevelManager(levelManager));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        for (int frame = 0; frame < 9; frame++) {
            peer.update(frame, sonic);
        }
        peer.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0, 0x0384, 0x066C, false, false);
    }

    @Test
    void mhz1FullCutsceneKnucklesRendersSharedRomInitialFrame() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        knuckles.setServices(new TestObjectServices().withLevelManager(levelManager));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        knuckles.update(0, sonic);
        knuckles.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0x16, 0x02B0, 0x066C, false, false);
    }

    @Test
    void mhz1FullCutsceneKnucklesLoadsCutscenePaletteOnInit() {
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        TestObjectServices services = new TestObjectServices();
        knuckles.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        try (MockedStatic<AizIntroArtLoader> artLoader = mockStatic(AizIntroArtLoader.class)) {
            knuckles.update(0, sonic);

            artLoader.verify(() -> AizIntroArtLoader.applyKnucklesPalette(services), times(1));
        }
    }

    @Test
    void mhz1FullCutsceneKnucklesQueuesKnucklesThemeOnInit() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        knuckles.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        try (MockedStatic<AizIntroArtLoader> ignored = mockStatic(AizIntroArtLoader.class)) {
            knuckles.update(0, sonic);
        }

        assertEquals(1, spawned.stream().filter(SongFadeTransitionInstance.class::isInstance).count(),
                "loc_62B68 calls sub_65DD6, which queues Obj_Song_Fade_Transition for mus_Knuckles");
    }

    @Test
    void mhz1FullCutsceneKnucklesStartsRomWalkAnimationWhenTimerExpires() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        knuckles.setServices(new TestObjectServices().withLevelManager(levelManager));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        for (int frame = 0; frame < 122; frame++) {
            knuckles.update(frame, sonic);
        }
        knuckles.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0x0A, 0x02B2, 0x066C, false, false);
    }

    @Test
    void mhz1FullCutsceneKnucklesBouncesOnceThenEntersRomExitWalk() {
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        knuckles.setServices(new TestObjectServices());
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        for (int frame = 0; frame < 122; frame++) {
            knuckles.update(frame, sonic);
        }
        for (int frame = 122; knuckles.getRoutineForTest() != 0x06 && frame < 260; frame++) {
            knuckles.update(frame, sonic);
        }
        knuckles.signalPeerReturned();
        for (int frame = 260; knuckles.getRoutineForTest() != 0x0A && frame < 280; frame++) {
            knuckles.update(frame, sonic);
        }

        assertEquals(0x0A, knuckles.getRoutineForTest(),
                "loc_62C14 enters the first ObjHitFloor_DoRoutine jump at routine $0A");

        for (int frame = 280; knuckles.getRoutineForTest() != 0x0E && frame < 520; frame++) {
            knuckles.update(frame, sonic);
        }

        assertEquals(0x0E, knuckles.getRoutineForTest(),
                "ObjHitFloor_DoRoutine should call loc_62C5A on the first landing and loc_62C76 on the second");
    }

    @Test
    void mhz1FullCutsceneKnucklesJumpLandingsUseRomObjectFloorProbeRadii() {
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        knuckles.setServices(new TestObjectServices());
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        for (int frame = 0; frame < 122; frame++) {
            knuckles.update(frame, sonic);
        }
        for (int frame = 122; knuckles.getRoutineForTest() != 0x06 && frame < 260; frame++) {
            knuckles.update(frame, sonic);
        }
        knuckles.signalPeerReturned();
        for (int frame = 260; knuckles.getRoutineForTest() != 0x0A && frame < 320; frame++) {
            knuckles.update(frame, sonic);
        }

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-2, (byte) 0, 0));

            for (int frame = 320; knuckles.getRoutineForTest() != 0x0C && frame < 520; frame++) {
                knuckles.update(frame, sonic);
            }
            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), eq(0x17)), atLeastOnce());

            for (int frame = 520; knuckles.getRoutineForTest() != 0x0E && frame < 740; frame++) {
                knuckles.update(frame, sonic);
            }
            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), eq(0x13)), atLeastOnce());
        }

        assertEquals(0x0E, knuckles.getRoutineForTest(),
                "loc_62C14 sets y_radius=$17 for the first jump, then loc_62C5A sets y_radius=$13 for the bounce");
    }

    @Test
    void mhz1FullCutsceneKnucklesDeletesWhenExitWalkLeavesRomRenderRange() {
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        knuckles.setServices(new TestObjectServices());
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        for (int frame = 0; frame < 122; frame++) {
            knuckles.update(frame, sonic);
        }
        for (int frame = 122; knuckles.getRoutineForTest() != 0x06 && frame < 260; frame++) {
            knuckles.update(frame, sonic);
        }
        knuckles.signalPeerReturned();
        for (int frame = 260; knuckles.getRoutineForTest() != 0x0E && frame < 520; frame++) {
            knuckles.update(frame, sonic);
        }
        assertEquals(0x0E, knuckles.getRoutineForTest());

        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        knuckles.update(520, sonic);

        assertTrue(knuckles.isDestroyed(),
                "loc_62C90 tests render_flags bit 7 and deletes subtype $1C once the exit walk is off-screen");
    }

    @Test
    void mhz2CutsceneKnucklesRendersRomPressInitialFrame() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer("mhz2_cutscene_knuckles_press"))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CutsceneKnucklesMhz2Instance knuckles = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        knuckles.setServices(new TestObjectServices().withLevelManager(levelManager));

        knuckles.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(3, 0x03D0, 0x0748, false, false);
    }

    @Test
    void mhz2CutsceneKnucklesLoadsCutscenePaletteOnInit() {
        Camera camera = mhz2TriggerCamera();
        CutsceneKnucklesMhz2Instance knuckles = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices()
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        knuckles.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);

        try (MockedStatic<AizIntroArtLoader> artLoader = mockStatic(AizIntroArtLoader.class)) {
            knuckles.update(0, sonic);

            artLoader.verify(() -> AizIntroArtLoader.applyKnucklesPalette(services), times(1));
        }
    }

    @Test
    void mhz2CutsceneDeletesWithoutInitializingWhenCameraOutsideRomRange() {
        ObjectManager objectManager = mock(ObjectManager.class);
        Camera camera = mhz2TriggerCamera();
        camera.setY((short) 0x0647);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withCamera(camera).withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        CutsceneKnucklesMhz2Instance knuckles = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        knuckles.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);

        try (MockedStatic<AizIntroArtLoader> artLoader = mockStatic(AizIntroArtLoader.class)) {
            knuckles.update(0, sonic);

            assertTrue(knuckles.isDestroyed(),
                    "Check_CameraInRange deletes/skips CutsceneKnux_MHZ2 when Camera_Y_pos is below $648");
            artLoader.verifyNoInteractions();
            verify(objectManager, never()).addDynamicObject(any(ObjectInstance.class));
        }
    }

    @Test
    void mhz2CutsceneKnucklesAdvancesPressMappingWithRomRawNoSstDelays() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        Camera camera = mhz2TriggerCamera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_PRESS))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CutsceneKnucklesMhz2Instance knuckles = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        knuckles.setServices(new TestObjectServices()
                .withLevelManager(levelManager)
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE)));

        knuckles.update(0, sonic);
        knuckles.update(1, sonic);
        knuckles.update(2, sonic);
        for (int frame = 0; frame < 33; frame++) {
            knuckles.update(3 + frame, sonic);
        }
        knuckles.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0, 0x03D0, 0x0748, false, false);
    }

    @Test
    void mhz2CutsceneKnucklesRouteSpawnsSwitchChildBeforeDeletingController() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services.withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x052A, (short) 0x0748);

        controller.update(0, knuckles);

        assertTrue(controller.isDestroyed(),
                "CutsceneKnux_MHZ2 uses Go_Delete_Sprite after creating the Knuckles-route switch child");
        assertEquals(1, spawned.size(),
                "loc_6311A creates ChildObjDat_665BC before deleting the subtype $20 controller");
        ObjectInstance switchChild = spawned.getFirst();
        assertEquals(0x03C8, switchChild.getX(),
                "ChildObjDat_665BC applies x offset -8 from the subtype $20 controller");
        assertEquals(0x0748, switchChild.getY(),
                "ChildObjDat_665BC keeps the controller y position");
    }

    @Test
    void mhz2CutsceneKnucklesRouteIgnoresPostCutsceneCheckpointBeforeDeletingController() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        CheckpointState checkpointState = new CheckpointState();
        checkpointState.restoreFromSaved(0x052A, 0x05AC, 0, 0, 7);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }
        };
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services.withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x052A, (short) 0x0748);

        controller.update(0, knuckles);

        assertTrue(controller.isDestroyed(),
                "CutsceneKnux_MHZ2 still deletes the controller on the Knuckles route");
        assertEquals(1, spawned.size(),
                "the ROM checks Player_1 character_id before Last_star_post_hit, so Knuckles still gets ChildObjDat_665BC");
        assertEquals(0x03C8, spawned.getFirst().getX());
    }

    @Test
    void mhz2CutsceneNormalRouteSpawnsSwitchChildDuringRomInit() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services
                .withCamera(mhz2TriggerCamera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE)));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);

        controller.update(0, sonic);

        assertFalse(controller.isDestroyed());
        assertEquals(1, spawned.size(),
                "loc_63134 creates ChildObjDat_665BC during normal MHZ2 cutscene initialization");
        assertEquals(0x03C8, spawned.getFirst().getX(),
                "ChildObjDat_665BC applies x offset -8 from the subtype $20 controller");
        assertEquals(0x0748, spawned.getFirst().getY(),
                "ChildObjDat_665BC keeps the controller y position");
    }

    @Test
    void mhz2SwitchChildRendersRomFrameDrivenByParentPressMapping() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer("mhz2_cutscene_knuckles_switch"))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);
        Camera camera = mhz2TriggerCamera();
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services
                .withLevelManager(levelManager)
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE)));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);

        controller.update(0, sonic);
        ObjectInstance switchChild = spawned.getFirst();
        assertInstanceOf(AbstractObjectInstance.class, switchChild).setServices(services);
        switchChild.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0, 0x03C8, 0x0748, false, false);

        clearInvocations(renderer);
        controller.update(1, sonic);
        controller.update(2, sonic);
        for (int frame = 0; frame < 80; frame++) {
            controller.update(3 + frame, sonic);
        }
        switchChild.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(1, 0x03C8, 0x0748, false, false);
    }

    @Test
    void mhz2SwitchChildUsesPaletteLine0OnKnucklesRoute() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_SWITCH))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services
                .withLevelManager(levelManager)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));

        controller.update(0, new TestablePlayableSprite("knuckles", (short) 0x052A, (short) 0x0748));
        ObjectInstance switchChild = spawned.getFirst();
        assertInstanceOf(AbstractObjectInstance.class, switchChild).setServices(services);
        AbstractObjectInstance.updateCameraBounds(0x0300, 0, 0x0440, 224, 0);
        switchChild.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0, 0x03C8, 0x0748, false, false, 0);
    }

    @Test
    void mhz2NormalRouteSwitchChildDeletesWhenParentControllerIsGone() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_SWITCH))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services
                .withLevelManager(levelManager)
                .withCamera(mhz2TriggerCamera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE)));
        controller.update(0, new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748));
        ObjectInstance switchChild = spawned.getFirst();
        AbstractObjectInstance childObject = assertInstanceOf(AbstractObjectInstance.class, switchChild);
        childObject.setServices(services);

        controller.setDestroyed(true);
        switchChild.appendRenderCommands(new ArrayList<>());

        assertTrue(switchChild.isDestroyed(),
                "loc_63308 ends with Child_Draw_Sprite, which deletes the normal-route switch child when parent bit 7 is set");
        verify(renderer, never()).drawFrameIndex(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void mhz2CutsceneInitSetsRomLeafBlowerEventFlag() {
        Sonic3kMHZEvents events = new Sonic3kMHZEvents();
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, events);
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(runtimeState);
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(new TestObjectServices()
                .withCamera(mhz2TriggerCamera())
                .withZoneRuntimeRegistry(registry));

        controller.update(0, new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748));

        assertTrue(runtimeState.isLeafBlowerCutsceneFlagSet(),
                "loc_63134 sets Events_bg+$16 when the MHZ2 leaf-blower cutscene initializes");
    }

    @Test
    void mhz2KnucklesRouteSwitchChildUsesSpriteOnScreenTestAfterParentDelete() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ2_CUTSCENE_KNUCKLES_SWITCH))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services
                .withLevelManager(levelManager)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));

        controller.update(0, new TestablePlayableSprite("knuckles", (short) 0x052A, (short) 0x0748));
        ObjectInstance switchChild = spawned.getFirst();
        AbstractObjectInstance childObject = assertInstanceOf(AbstractObjectInstance.class, switchChild);
        childObject.setServices(services);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);

        switchChild.appendRenderCommands(new ArrayList<>());

        assertTrue(switchChild.isDestroyed(),
                "loc_632CA swaps Knuckles-route child to Sprite_OnScreen_Test, so it deletes by its own X range");
        verify(renderer, never()).drawFrameIndex(anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    void mhz2PressSpawnsRomLeafParticlesFromCameraAndParentStrength() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer("mhz2_cutscene_knuckles_leaves"))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);
        Camera camera = mhz2TriggerCamera();
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 0x00010001);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withLevelManager(levelManager)
                .withCamera(camera)
                .withRng(rng)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        CutsceneKnucklesMhz2Instance controller = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        controller.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);

        controller.update(0, sonic);
        camera.setY((short) 0x0500);
        controller.update(1, sonic);
        controller.update(2, sonic);
        for (int frame = 0; frame < 80; frame++) {
            controller.update(3 + frame, sonic);
        }
        assertTrue(spawned.size() > 1,
                "sub_65EB4 allocates loc_63324 leaf children once loc_63220 reaches the leaf-blower phase");
        ObjectInstance leaf = spawned.get(1);
        assertInstanceOf(AbstractObjectInstance.class, leaf).setServices(services);

        assertTrue(leaf.getX() >= 0x03D0 && leaf.getX() <= 0x03D0 + 0x013F,
                "loc_63324 derives leaf x_pos from Camera_X_pos_copy plus the folded $1FF random span");
        assertEquals(0x05E8, leaf.getY(),
                "loc_63324 starts leaf y_pos at Camera_Y_pos_copy+$E8 before its first loc_63372 update");

        leaf.update(83, sonic);
        assertEquals(0x05E4, leaf.getY(),
                "after the first press-frame switch, $39 is 2 so loc_63324 initializes y_vel=-(2*2)");
        leaf.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0, leaf.getX(), leaf.getY(), false, false);
    }

    @Test
    void mhz1FullCutsceneKnucklesWaitsWalksThenSpawnsPeerChild() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0));
        knuckles.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        knuckles.update(0, sonic);
        assertEquals(0x02B0, knuckles.getX(),
                "loc_62B68 starts CutsceneKnux_MHZ1 at x_pos=$2B0");
        assertEquals(0x066C, knuckles.getY(),
                "loc_62B68 starts CutsceneKnux_MHZ1 at y_pos=$66C");
        for (int frame = 1; frame <= 120; frame++) {
            knuckles.update(frame, sonic);
        }
        assertEquals(0x02B0, knuckles.getX(),
                "loc_62BB2 waits (2*60)-1 frames before the walk routine starts");

        for (int frame = 121; frame < 260 && spawned.isEmpty(); frame++) {
            knuckles.update(frame, sonic);
        }

        assertEquals(0x0360, knuckles.getX(),
                "loc_62BC0 walks at x_vel=$200 until x_pos reaches $360");
        assertEquals(1, spawned.stream().filter(CutsceneKnucklesMhz1PeerInstance.class::isInstance).count(),
                "loc_62BD4 creates ChildObjDat_665B0, the separate MHZ peering sprite");
    }

    @Test
    void mhz2CutsceneLocksAtCameraX3d0AndWaitsForGroundedPlayers() {
        Camera camera = mhz2TriggerCamera();
        camera.setMinX((short) 0x0200);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        cutscene.setServices(new TestObjectServices()
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE)));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);

        assertEquals(0x03D0, camera.getMinX() & 0xFFFF,
                "loc_63182 clamps Camera_min_X_pos to word_630DC ($3D0)");
        assertTrue(sonic.isControlLocked(),
                "loc_63182 sets Ctrl_1_locked before the press sequence");
        assertEquals(0, sonic.getLogicalInputState());
        assertEquals(0x04, cutscene.getRoutineForTest(),
                "routine $04 waits for the players to be grounded or for the 60-frame timer to expire");
    }

    @Test
    void mhz2CutsceneDeletesWhenRestartCheckpointIsAtOrPastRomCutsceneCheckpoint() {
        CheckpointState checkpointState = new CheckpointState();
        checkpointState.restoreFromSaved(0x052A, 0x05AC, 0, 0, 7);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }
        };
        services.withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        cutscene.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);

        cutscene.update(0, sonic);

        assertTrue(cutscene.isDestroyed(),
                "CutsceneKnux_MHZ2 deletes when Last_star_post_hit >= 7 before starting the press sequence");
        assertEquals(0, cutscene.getRoutineForTest(),
                "a post-MHZ2-cutscene restart must not lock camera or player control");
    }

    @Test
    void mhz2CutscenePressStartAppliesRomObjectControlAndAnimation5ToNativePlayers() {
        Camera camera = mhz2TriggerCamera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0520, (short) 0x0748);
        sonic.setAir(false);
        tails.setAir(false);
        sonic.setXSpeed((short) 0x0200);
        tails.setXSpeed((short) -0x0100);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        cutscene.setServices(new TestObjectServices()
                .withCamera(camera)
                .withSidekicks(List.of(tails))
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_AND_TAILS)));

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);

        assertEquals(0x06, cutscene.getRoutineForTest(),
                "loc_631E0 switches to routine $06 once both native players are grounded");
        assertTrue(sonic.isControlLocked());
        assertTrue(tails.isControlLocked());
        assertTrue(sonic.isObjectControlled(),
                "sub_6320E writes object_control=$81 to Player_1 at press start, not only Ctrl_1_locked");
        assertFalse(sonic.isObjectControlAllowsCpu(),
                "object_control=$81 has bit 7 set, so CPU/touch participation is suppressed for Player_1");
        assertTrue(sonic.isObjectControlSuppressesMovement(),
                "object_control=$81 suppresses normal movement for Player_1 during the MHZ2 press sequence");
        assertTrue(tails.isObjectControlled(),
                "sub_6320E writes object_control=$81 to Player_2 when present");
        assertFalse(tails.isObjectControlAllowsCpu(),
                "object_control=$81 has bit 7 set, so CPU/touch participation is suppressed for Player_2");
        assertTrue(tails.isObjectControlSuppressesMovement(),
                "object_control=$81 suppresses normal movement for Player_2 during the MHZ2 press sequence");
        assertEquals(0, sonic.getXSpeed());
        assertEquals(0, tails.getXSpeed());
        assertEquals(5, sonic.getAnimationId(),
                "sub_6320E writes anim=$05 to Player_1 at press start");
        assertEquals(5, tails.getAnimationId(),
                "sub_6320E writes anim=$05 to Player_2 at press start when present");
    }

    @Test
    void mhz2CutscenePressStartIgnoresOffscreenAirborneNativeP2LikeRom() {
        Camera camera = mhz2TriggerCamera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0520, (short) 0x0748);
        sonic.setAir(false);
        tails.setAir(true);
        tails.setRenderFlagOnScreen(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        cutscene.setServices(new TestObjectServices()
                .withCamera(camera)
                .withSidekicks(List.of(tails))
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_AND_TAILS)));

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);

        assertEquals(0x06, cutscene.getRoutineForTest(),
                "loc_631A4 treats off-screen Player_2 as ready, so airborne off-screen P2 must not hold routine $04");
        assertEquals(5, sonic.getAnimationId(),
                "sub_6320E should start Player_1's press animation as soon as the ROM grounded mask reaches 3");
    }

    @Test
    void mhz2CutscenePressedAnimationOnlyAppliesToOnScreenPlayerTwo() {
        Camera camera = mhz2TriggerCamera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0520, (short) 0x0748);
        sonic.setAir(false);
        tails.setAir(false);
        tails.setRenderFlagOnScreen(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        cutscene.setServices(new TestObjectServices()
                .withCamera(camera)
                .withSidekicks(List.of(tails))
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_AND_TAILS)));

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        assertEquals(5, tails.getAnimationId(),
                "sub_6320E writes anim=$05 to Player_2 before the render_flags-gated pressed animation");

        for (int frame = 3; frame < 500 && sonic.getAnimationId() != 0x14; frame++) {
            cutscene.update(frame, sonic);
        }

        assertEquals(0x14, sonic.getAnimationId(),
                "loc_63238 always writes anim=$14 to Player_1 when anim_frame reaches $0C");
        assertEquals(5, tails.getAnimationId(),
                "loc_63238 checks Player_2 render_flags and skips anim=$14 when Player_2 is off-screen");
    }

    @Test
    void mhz2CutsceneLaunchUsesLiftAnimationAndRestoresControlWhenArcEnds() {
        Camera camera = mhz2TriggerCamera();
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        cutscene.setServices(services);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        int liftFrame = advanceMhz2CutsceneThroughPressAnimation(cutscene, sonic);
        cutscene.update(liftFrame, sonic);
        AbstractObjectInstance liftChild = mhz2LiftChild(spawned);
        liftChild.setServices(services);
        liftChild.update(liftFrame + 1, sonic);

        assertEquals(0x08, cutscene.getRoutineForTest());
        assertTrue(sonic.isControlLocked());
        assertEquals(0x0F, sonic.getAnimationId(),
                "loc_6338E writes anim=$0F when the player lift child starts");
        assertTrue((sonic.getYSpeed() & 0xFFFF) > 0x8000,
                "loc_6338E starts the lift with y_vel=-$1000");

        for (int frame = 0; frame < 180 && !liftChild.isDestroyed(); frame++) {
            cutscene.update(liftFrame + 2 + frame, sonic);
            liftChild.update(liftFrame + 2 + frame, sonic);
        }

        assertFalse(sonic.isControlLocked(),
                "loc_633D6 clears object_control once y_vel becomes non-negative");
        assertEquals(0, sonic.getAnimationId(),
                "loc_633D6 clears anim after the leaf-blower arc returns control");
        assertEquals(0x10, sonic.getGSpeed() & 0xFFFF,
                "loc_633D6 sets ground_vel=$10 on release");
        assertTrue(liftChild.isDestroyed(),
                "loc_633D6 deletes the per-player lift child after restoring control");
    }

    @Test
    void mhz2CutsceneLiftRequestsRomFastVerticalScrollWhileCarryingPlayer() {
        Camera camera = spy(mhz2TriggerCamera());
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        cutscene.setServices(services);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        int liftFrame = advanceMhz2CutsceneThroughPressAnimation(cutscene, sonic);
        cutscene.update(liftFrame, sonic);
        AbstractObjectInstance liftChild = mhz2LiftChild(spawned);
        liftChild.setServices(services);

        clearInvocations(camera);
        liftChild.update(liftFrame + 1, sonic);
        verify(camera).requestFastVerticalScroll();

        clearInvocations(camera);
        liftChild.update(liftFrame + 2, sonic);
        verify(camera).requestFastVerticalScroll();
    }

    @Test
    void mhz2CutsceneAllocatesRomLiftChildBeforeMovingPlayer() {
        Camera camera = mhz2TriggerCamera();
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        cutscene.setServices(services);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        int liftFrame = advanceMhz2CutsceneThroughPressAnimation(cutscene, sonic);
        int yBeforeLiftChildrenRun = sonic.getCentreY() & 0xFFFF;
        cutscene.update(liftFrame, sonic);

        assertTrue(spawned.stream().anyMatch(o -> o.getClass().getSimpleName().contains("Lift")),
                "loc_63280 allocates a separate loc_6338E child for the Player_1 leaf-blower lift");
        assertEquals(yBeforeLiftChildrenRun, sonic.getCentreY() & 0xFFFF,
                "the subtype $20 controller only allocates loc_6338E; the lift child moves the player on its own update");
    }

    @Test
    void mhz2CutsceneSkipsPlayerTwoLiftChildWhenRenderFlagsAreOffscreen() {
        Camera camera = mhz2TriggerCamera();
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_AND_TAILS));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0520, (short) 0x0748);
        sonic.setAir(false);
        tails.setAir(false);
        tails.setRenderFlagOnScreen(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        cutscene.setServices(services.withSidekicks(List.of(tails)));

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        int liftFrame = advanceMhz2CutsceneThroughPressAnimation(cutscene, sonic);
        cutscene.update(liftFrame, sonic);

        long liftChildren = spawned.stream()
                .filter(object -> object.getClass().getSimpleName().contains("Lift"))
                .count();
        assertEquals(1, liftChildren,
                "loc_63294 only allocates the Player_2 loc_6338E lift child when Player_2 render_flags is negative");
    }

    @Test
    void mhz2CutscenePressPlaysLeafBlowerAndSwitchSfx() {
        Camera camera = mhz2TriggerCamera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        List<Integer> sfxIds = new ArrayList<>();
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void playSfx(int soundId) {
                sfxIds.add(soundId);
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        cutscene.setServices(services);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        for (int frame = 0; frame < 80; frame++) {
            cutscene.update(3 + frame, sonic);
        }

        assertEquals(0x06, cutscene.getRoutineForTest());
        assertTrue(sfxIds.contains(Sonic3kSfx.LEAF_BLOWER.id),
                "loc_63220 calls Play_SFX_Continuous(sfx_LeafBlower) once anim_frame reaches 8");
        assertTrue(sfxIds.contains(Sonic3kSfx.SWITCH.id),
                "loc_63266 calls Play_SFX(sfx_Switch) when the press mapping reaches frame 2");
    }

    @Test
    void mhz2CutsceneReleaseSavesRomRestartPoint() {
        Camera camera = mhz2TriggerCamera();
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        CheckpointState checkpointState = new CheckpointState();
        AtomicReference<SaveReason> saveReason = new AtomicReference<>();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }

            @Override
            public void requestSessionSave(SaveReason reason) {
                saveReason.set(reason);
            }

            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        cutscene.setServices(services);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        int liftFrame = advanceMhz2CutsceneThroughPressAnimation(cutscene, sonic);
        cutscene.update(liftFrame, sonic);
        AbstractObjectInstance liftChild = mhz2LiftChild(spawned);
        liftChild.setServices(services);
        liftChild.update(liftFrame + 1, sonic);
        for (int frame = 0; frame < 180 && !liftChild.isDestroyed(); frame++) {
            cutscene.update(liftFrame + 2 + frame, sonic);
            liftChild.update(liftFrame + 2 + frame, sonic);
        }

        assertEquals(7, checkpointState.getLastCheckpointIndex(),
                "loc_633D6 writes Last_star_post_hit=7 when the MHZ2 lift releases control");
        assertEquals(0x052A, checkpointState.getSavedX(),
                "loc_633D6 writes Saved_X_pos=$52A before Save_Level_Data");
        assertEquals(0x05AC, checkpointState.getSavedY(),
                "loc_633D6 writes Saved_Y_pos=$5AC before Save_Level_Data");
        assertEquals(camera.getX() & 0xFFFF, checkpointState.getSavedCameraX(),
                "Save_Level_Data preserves the live camera X present at MHZ2 cutscene release");
        assertEquals(camera.getY() & 0xFFFF, checkpointState.getSavedCameraY(),
                "Save_Level_Data preserves the live camera Y present at MHZ2 cutscene release");
        assertEquals(SaveReason.PROGRESSION_SAVE, saveReason.get(),
                "loc_633D6 calls Save_Level_Data after updating the MHZ2 restart variables");
    }

    @Test
    void mhz2CutsceneReleaseClearsRomLeafBlowerEventFlag() {
        Camera camera = mhz2TriggerCamera();
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        Sonic3kMHZEvents events = new Sonic3kMHZEvents();
        events.setLeafBlowerCutsceneFlag(true);
        MhzZoneRuntimeState runtimeState = new MhzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE, events);
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(runtimeState);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(registry);
        cutscene.setServices(services);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        int liftFrame = advanceMhz2CutsceneThroughPressAnimation(cutscene, sonic);
        cutscene.update(liftFrame, sonic);
        AbstractObjectInstance liftChild = mhz2LiftChild(spawned);
        liftChild.setServices(services);
        liftChild.update(liftFrame + 1, sonic);
        for (int frame = 0; frame < 180 && !liftChild.isDestroyed(); frame++) {
            cutscene.update(liftFrame + 2 + frame, sonic);
            liftChild.update(liftFrame + 2 + frame, sonic);
        }

        assertFalse(runtimeState.isLeafBlowerCutsceneFlagSet(),
                "loc_633D6 clears Events_bg+$16 when the lift releases player control");
    }

    @Test
    void mhz2CutsceneLiftPlaysLeafBlowerContinuousSfx() {
        Camera camera = mhz2TriggerCamera();
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x052A, (short) 0x0748);
        sonic.setAir(false);
        camera.setFocusedSprite(sonic);
        setMhz2TriggerCameraPosition(camera);
        List<Integer> sfxIds = new ArrayList<>();
        CutsceneKnucklesMhz2Instance cutscene = new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                0x03D0, 0x0748, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void playSfx(int soundId) {
                sfxIds.add(soundId);
            }
        };
        services.withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE));
        cutscene.setServices(services);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        int frame = advanceMhz2CutsceneThroughPressAnimation(cutscene, sonic);
        sfxIds.clear();
        cutscene.update(frame, sonic);

        assertTrue(sfxIds.contains(Sonic3kSfx.LEAF_BLOWER.id),
                "loc_633D6 calls Play_SFX_Continuous(sfx_LeafBlower) on the MHZ2 lift frame");
    }

    @Test
    void skIntroDeletesUnlessKnucklesAloneAndThenSetsCameraAndControl() {
        Camera camera = new Camera();
        CutsceneKnucklesSkIntroInstance sonicPath = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        sonicPath.setServices(new TestObjectServices()
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_ALONE)));

        sonicPath.update(0, new TestablePlayableSprite("sonic", (short) 0x0560, (short) 0x0948));

        assertTrue(sonicPath.isDestroyed(),
                "CutsceneKnux_SKIntro deletes unless Player_mode is Knuckles-alone");

        CutsceneKnucklesSkIntroInstance knucklesPath = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        knucklesPath.setServices(new TestObjectServices()
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        knucklesPath.update(0, knuckles);

        assertFalse(knucklesPath.isDestroyed());
        assertEquals(0x0560, camera.getX() & 0xFFFF,
                "loc_634CA writes Camera_X_pos=$560 at SK intro start");
        assertEquals(0x0948, camera.getY() & 0xFFFF,
                "loc_634CA writes Camera_Y_pos=$948 at SK intro start");
        assertTrue(camera.getFrozen(),
                "loc_634CA sets Scroll_lock at SK intro start");
        assertFalse(camera.isLevelStarted(),
                "loc_634CA clears Level_started_flag at SK intro start");
        assertTrue(knuckles.isControlLocked(),
                "loc_634CA writes object_control=$83, taking control from Knuckles");
    }

    @Test
    void skIntroDeletesImmediatelyWhenLastStarPostHitIsSet() {
        CheckpointState checkpointState = new CheckpointState();
        checkpointState.restoreFromSaved(0x052A, 0x05AC, 0, 0, 7);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }
        };
        skIntro.setServices(services
                .withCamera(new Camera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);

        assertTrue(skIntro.isDestroyed(),
                "CutsceneKnux_SKIntro branches to CutsceneKnux_Delete when Last_star_post_hit is nonzero");
        assertFalse(knuckles.isControlLocked(),
                "a resumed post-cutscene state must not run loc_634CA and seize Knuckles control");
    }

    @Test
    void skIntroRendersRomInitialLayingFrame() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        Camera camera = new Camera();
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.KNUX_INTRO_LAYING)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        skIntro.setServices(new TestObjectServices()
                .withLevelManager(levelManager)
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));

        skIntro.update(0, new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948));
        skIntro.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0, 0x0560, 0x0948, false, false);
    }

    @Test
    void skIntroCompletionSavesRestartPointAndQueuesMhzReload() {
        Camera camera = new Camera();
        camera.setX((short) 0x0560);
        CheckpointState checkpointState = new CheckpointState();
        AtomicReference<SaveReason> saveReason = new AtomicReference<>();
        int[] requestedZoneAct = {-1, -1};
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }

            @Override
            public void requestSessionSave(SaveReason reason) {
                saveReason.set(reason);
            }

            @Override
            public void requestZoneAndAct(int zone, int act) {
                requestedZoneAct[0] = zone;
                requestedZoneAct[1] = act;
            }
        };
        skIntro.setServices(services
                .withCamera(camera)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        skIntro.signalBombFallComplete();
        skIntro.update(1, knuckles);
        skIntro.signalGrabAnimationComplete();
        skIntro.update(2, knuckles);
        skIntro.signalFallingMotionComplete();
        skIntro.update(3, knuckles);
        skIntro.setFloorDistanceForTest(-3);
        skIntro.update(4, knuckles);
        skIntro.signalLandingAnimationComplete();
        skIntro.update(5, knuckles);
        for (int frame = 6; frame < 520 && !skIntro.isDestroyed(); frame++) {
            skIntro.update(frame, knuckles);
        }

        assertTrue(skIntro.isDestroyed(),
                "loc_6364E deletes the SK intro controller after Knuckles exits past Camera_X_pos+$180");
        assertEquals(1, checkpointState.getLastCheckpointIndex(),
                "loc_6364E writes Last_star_post_hit=1 before Save_Level_Data");
        assertEquals(0x06F4, checkpointState.getSavedX(),
                "loc_6364E writes Saved_X_pos=$6F4 before Save_Level_Data");
        assertEquals(0x09EC, checkpointState.getSavedY(),
                "loc_6364E writes Saved_Y_pos=$9EC before Save_Level_Data");
        assertEquals(SaveReason.PROGRESSION_SAVE, saveReason.get(),
                "loc_6364E calls Save_Level_Data after updating the restart variables");
        assertEquals(Sonic3kZoneIds.ZONE_MHZ, requestedZoneAct[0],
                "loc_6364E writes Current_zone_and_act=$0700 before setting Restart_level_flag");
        assertEquals(0, requestedZoneAct[1],
                "loc_6364E reloads MHZ Act 1 after the S&K intro");
    }

    @Test
    void skIntroBombSignalAdvancesToGrabRoutineAndFrame() {
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        skIntro.setServices(new TestObjectServices()
                .withCamera(new Camera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        skIntro.signalBombFallComplete();
        skIntro.update(1, knuckles);

        assertEquals(0x04, skIntro.getRoutineForTest(),
                "loc_6354C switches to routine $04 when _unkFAB8 bit 0 is set");
        assertEquals(4, skIntro.getMappingFrameForTest(),
                "loc_6354C writes mapping_frame=4 before starting byte_668C7");
    }

    @Test
    void skIntroTimerExpiryAllocatesBombChildAndKeepsWaitingForBombSignal() {
        Camera camera = new Camera();
        camera.setX((short) 0x0560);
        camera.setY((short) 0x0948);
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        List<Integer> sfxIds = new ArrayList<>();
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void playSfx(int soundId) {
                sfxIds.add(soundId);
            }
        };
        skIntro.setServices(services
                .withCamera(camera)
                .withLevelManager(levelManager)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        doAnswer(invocation -> {
            ObjectInstance child = invocation.getArgument(0);
            if (child instanceof AbstractObjectInstance object) {
                object.setServices(services);
            }
            spawned.add(child);
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        for (int frame = 1; frame <= 0xEF; frame++) {
            skIntro.update(frame, knuckles);
        }

        assertEquals(0x02, skIntro.getRoutineForTest(),
                "loc_63526 allocates loc_63790 when $2E reaches zero but keeps waiting for _unkFAB8 bit 0");
        assertEquals(1, spawned.size(),
                "loc_63526 should AllocateObject exactly once for the falling SK intro bomb");
        ObjectInstance bomb = spawned.get(0);
        assertEquals(0x0600, bomb.getX(),
                "loc_63790 spawns the bomb at Camera_X_pos+$A0");
        assertEquals(0x0908, bomb.getY(),
                "loc_63790 spawns the bomb at Camera_Y_pos-$40");
        assertTrue(sfxIds.contains(Sonic3kSfx.MISSILE_THROW.id),
                "loc_63790 plays sfx_MissileThrow when the bomb is allocated");
    }

    @Test
    void skIntroBombFloorImpactSignalsParentAndBounces() {
        Camera camera = new Camera();
        camera.setX((short) 0x0560);
        camera.setY((short) 0x0948);
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        List<Integer> sfxIds = new ArrayList<>();
        boolean[] fadeOutMusic = {false};
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void playSfx(int soundId) {
                sfxIds.add(soundId);
            }

            @Override
            public void fadeOutMusic() {
                fadeOutMusic[0] = true;
            }
        };
        skIntro.setServices(services
                .withCamera(camera)
                .withLevelManager(levelManager)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        doAnswer(invocation -> {
            ObjectInstance child = invocation.getArgument(0);
            if (child instanceof AbstractObjectInstance object) {
                object.setServices(services);
            }
            spawned.add(child);
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        for (int frame = 1; frame <= 0xEF; frame++) {
            skIntro.update(frame, knuckles);
        }
        CutsceneKnucklesSkIntroBombInstance bomb = assertInstanceOf(
                CutsceneKnucklesSkIntroBombInstance.class, spawned.get(0));
        AbstractObjectInstance.updateCameraBounds(0x0560, 0x0900, 0x0560 + 320, 0x0900 + 224, 0);
        sfxIds.clear();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0600, 0x0908, 0x10))
                    .thenReturn(new TerrainCheckResult(-4, (byte) 0, 0));

            bomb.update(0xF0, knuckles);
            skIntro.update(0xF1, knuckles);

            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(0x0600, 0x0908, 0x10), times(1));
        }

        assertEquals(0x04, skIntro.getRoutineForTest(),
                "loc_637EC bsets _unkFAB8 bit 0 so the controller enters the grab animation routine");
        assertEquals(0x0904, bomb.getY(),
                "loc_637EC snaps the bomb by the negative ObjCheckFloorDist result before bouncing");
        assertEquals(-0x000E, bomb.getYVelocityForTest(),
                "loc_637EC sets y_vel=-(old y_vel>>2) after MoveSprite gravity raises y_vel to $38");
        assertTrue(sfxIds.contains(Sonic3kSfx.FLOOR_THUMP.id),
                "loc_637EC plays sfx_FloorThump when the bomb hits the floor");
        assertTrue(fadeOutMusic[0],
                "loc_637EC issues cmd_FadeOut when the bounce is small enough to enter loc_63846");
    }

    @Test
    void skIntroBombImpactWaitExpirySignalsGrabCompleteAndExplodes() {
        Camera camera = new Camera();
        camera.setX((short) 0x0560);
        camera.setY((short) 0x0948);
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        List<Integer> sfxIds = new ArrayList<>();
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void playSfx(int soundId) {
                sfxIds.add(soundId);
            }
        };
        skIntro.setServices(services
                .withCamera(camera)
                .withLevelManager(levelManager)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        doAnswer(invocation -> {
            ObjectInstance child = invocation.getArgument(0);
            if (child instanceof AbstractObjectInstance object) {
                object.setServices(services);
            }
            spawned.add(child);
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        for (int frame = 1; frame <= 0xEF; frame++) {
            skIntro.update(frame, knuckles);
        }
        CutsceneKnucklesSkIntroBombInstance bomb = assertInstanceOf(
                CutsceneKnucklesSkIntroBombInstance.class, spawned.get(0));
        AbstractObjectInstance.updateCameraBounds(0x0560, 0x0900, 0x0560 + 320, 0x0900 + 224, 0);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0600, 0x0908, 0x10))
                    .thenReturn(new TerrainCheckResult(-4, (byte) 0, 0));
            bomb.update(0xF0, knuckles);
        }
        skIntro.update(0xF1, knuckles);
        sfxIds.clear();

        for (int frame = 0; frame < 120; frame++) {
            bomb.update(0xF2 + frame, knuckles);
        }
        skIntro.update(0xF2 + 120, knuckles);

        assertEquals(0x06, skIntro.getRoutineForTest(),
                "loc_63846 bsets _unkFAB8 bit 1 after the $2E countdown underflows");
        assertEquals(0x8D, skIntro.getMappingFrameForTest(),
                "the controller should enter loc_63570's falling pose when the bomb explosion signal arrives");
        assertTrue(sfxIds.contains(Sonic3kSfx.MISSILE_EXPLODE.id),
                "loc_63846 plays sfx_MissileExplode when the bomb changes to loc_638A2");
    }

    @Test
    void skIntroBombExplosionCompletionSignalsFallingMotionComplete() {
        Camera camera = new Camera();
        camera.setX((short) 0x0560);
        camera.setY((short) 0x0948);
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        TestObjectServices services = new TestObjectServices();
        skIntro.setServices(services
                .withCamera(camera)
                .withLevelManager(levelManager)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        doAnswer(invocation -> {
            ObjectInstance child = invocation.getArgument(0);
            if (child instanceof AbstractObjectInstance object) {
                object.setServices(services);
            }
            spawned.add(child);
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        for (int frame = 1; frame <= 0xEF; frame++) {
            skIntro.update(frame, knuckles);
        }
        CutsceneKnucklesSkIntroBombInstance bomb = assertInstanceOf(
                CutsceneKnucklesSkIntroBombInstance.class, spawned.get(0));
        AbstractObjectInstance.updateCameraBounds(0x0560, 0x0900, 0x0560 + 320, 0x0900 + 224, 0);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0600, 0x0908, 0x10))
                    .thenReturn(new TerrainCheckResult(-4, (byte) 0, 0));
            bomb.update(0xF0, knuckles);
        }
        skIntro.update(0xF1, knuckles);
        for (int frame = 0; frame < 120; frame++) {
            bomb.update(0xF2 + frame, knuckles);
        }
        skIntro.update(0xF2 + 120, knuckles);
        assertEquals(0x06, skIntro.getRoutineForTest(),
                "the bomb explosion signal should put the controller in the falling routine before loc_638A2 completes");

        for (int frame = 0; frame < 160 && skIntro.getRoutineForTest() == 0x06; frame++) {
            bomb.update(0x200 + frame, knuckles);
            skIntro.update(0x200 + frame, knuckles);
        }

        assertEquals(0x08, skIntro.getRoutineForTest(),
                "loc_638A2 bsets _unkFAB8 bit 2 when the explosion controller sets status bit 5");
        assertEquals(3, skIntro.getMappingFrameForTest(),
                "the parent should enter loc_635A2's landing setup after the bomb's explosion controller finishes");
        assertTrue(bomb.isDestroyed(),
                "loc_638A2 deletes the bomb object after the loc_85E64 palette flash reaches its midpoint");
        assertTrue(spawned.stream().anyMatch(S3kBossExplosionChild.class::isInstance),
                "loc_63846 creates Child6_CreateBossExplosion, whose controller emits boss explosion children");
    }

    @Test
    void skIntroBombCompletionRestoresSszPaletteLine2FromPalPointers() {
        Camera camera = new Camera();
        camera.setX((short) 0x0560);
        camera.setY((short) 0x0948);
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        byte[] sszLine2 = new byte[32];
        for (int i = 0; i < sszLine2.length; i += 2) {
            sszLine2[i] = 0x0E;
            sszLine2[i + 1] = (byte) (0xEE - i);
        }
        Palette[] palettes = {new Palette(), new Palette(), new Palette(), new Palette()};
        Level level = mock(Level.class);
        when(level.getPaletteCount()).thenReturn(palettes.length);
        when(level.getPalette(anyInt())).thenAnswer(invocation -> palettes[invocation.getArgument(0)]);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public Level currentLevel() {
                return level;
            }
        };
        services
                .withCamera(camera)
                .withLevelManager(levelManager)
                .withRom(new PalPointersSszRom(sszLine2))
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES));
        skIntro.setServices(services);
        doAnswer(invocation -> {
            ObjectInstance child = invocation.getArgument(0);
            if (child instanceof AbstractObjectInstance object) {
                object.setServices(services);
            }
            spawned.add(child);
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        for (int frame = 1; frame <= 0xEF; frame++) {
            skIntro.update(frame, knuckles);
        }
        CutsceneKnucklesSkIntroBombInstance bomb = assertInstanceOf(
                CutsceneKnucklesSkIntroBombInstance.class, spawned.get(0));
        AbstractObjectInstance.updateCameraBounds(0x0560, 0x0900, 0x0560 + 320, 0x0900 + 224, 0);
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0600, 0x0908, 0x10))
                    .thenReturn(new TerrainCheckResult(-4, (byte) 0, 0));
            bomb.update(0xF0, knuckles);
        }
        for (int frame = 0; frame < 200 && !bomb.isDestroyed(); frame++) {
            bomb.update(0xF1 + frame, knuckles);
        }

        assertColorWord(palettes[1], 0, 0x0EEE);
        assertColorWord(palettes[1], 1, 0x0EEC);
    }

    @Test
    void skIntroBombCompletionSpawnsEggRoboEntryAtCameraOffsetAndStartsBossMusic() {
        Camera camera = new Camera();
        camera.setX((short) 0x0560);
        camera.setY((short) 0x0948);
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        List<Integer> musicIds = new ArrayList<>();
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public void playMusic(int musicId) {
                musicIds.add(musicId);
            }
        };
        skIntro.setServices(services
                .withCamera(camera)
                .withLevelManager(levelManager)
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        doAnswer(invocation -> {
            ObjectInstance child = invocation.getArgument(0);
            if (child instanceof AbstractObjectInstance object) {
                object.setServices(services);
            }
            spawned.add(child);
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        for (int frame = 1; frame <= 0xEF; frame++) {
            skIntro.update(frame, knuckles);
        }
        CutsceneKnucklesSkIntroBombInstance bomb = assertInstanceOf(
                CutsceneKnucklesSkIntroBombInstance.class, spawned.get(0));
        AbstractObjectInstance.updateCameraBounds(0x0560, 0x0900, 0x0560 + 320, 0x0900 + 224, 0);
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x0600, 0x0908, 0x10))
                    .thenReturn(new TerrainCheckResult(-4, (byte) 0, 0));
            bomb.update(0xF0, knuckles);
        }
        for (int frame = 0; frame < 200 && !bomb.isDestroyed(); frame++) {
            bomb.update(0xF1 + frame, knuckles);
        }

        AbstractObjectInstance entry = spawned.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> "CutsceneKnuxSKIntroEggRoboEntry".equals(child.getName()))
                .findFirst()
                .orElse(null);
        assertTrue(entry != null,
                "loc_638A2 allocates loc_639C8 after the palette flash completes and before deleting the bomb");
        assertEquals(0x0670, entry.getX(),
                "loc_639C8 initializes x_pos to Camera_X_pos+$110");
        assertEquals(0x08E8, entry.getY(),
                "loc_639C8 initializes y_pos to Camera_Y_pos-$60");
        assertTrue(musicIds.contains(Sonic3kMusic.BOSS.id),
                "loc_639C8 starts mus_EndBoss when the EggRobo entry child is allocated");
        AbstractObjectInstance lowerVisualChild = spawned.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> "CutsceneKnuxSKIntroEggRoboLowerVisual".equals(child.getName()))
                .findFirst()
                .orElse(null);
        assertTrue(lowerVisualChild != null,
                "ChildObjDat_919D0 first entry creates loc_916A8 as an attached lower EggRobo visual child");
        assertEquals(0x0664, lowerVisualChild.getX(),
                "ChildObjDat_919D0 applies child_dx=-$0C for loc_916A8");
        assertEquals(0x0904, lowerVisualChild.getY(),
                "ChildObjDat_919D0 applies child_dy=$1C for loc_916A8");
        AbstractObjectInstance upperVisualChild = spawned.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> "CutsceneKnuxSKIntroEggRoboUpperVisual".equals(child.getName()))
                .findFirst()
                .orElse(null);
        assertTrue(upperVisualChild != null,
                "ChildObjDat_919D0 second entry creates loc_916EE as an attached upper EggRobo visual child");
        assertEquals(0x0654, upperVisualChild.getX(),
                "ChildObjDat_919D0 applies child_dx=-$1C for loc_916EE");
        assertEquals(0x08E4, upperVisualChild.getY(),
                "ChildObjDat_919D0 applies child_dy=-4 for loc_916EE");

        entry.update(0x300, knuckles);

        assertEquals(0x08E9, entry.getY(),
                "loc_63A16 increments y_pos by one pixel before comparing against Camera_Y_pos+$40");
    }

    @Test
    void skIntroEggRoboUpperChildSpawnsLaserWhenParentRequestBitIsSet() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            ObjectInstance child = invocation.getArgument(0);
            spawned.add(child);
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        CutsceneKnucklesSkIntroEggRoboEntryInstance parent =
                new CutsceneKnucklesSkIntroEggRoboEntryInstance(new ObjectSpawn(
                        0x0670, 0x08E8, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        parent.setServices(services);
        CutsceneKnucklesSkIntroEggRoboUpperVisualChild upper =
                new CutsceneKnucklesSkIntroEggRoboUpperVisualChild(parent);
        upper.setServices(services);
        parent.requestLaserFire();

        upper.update(0, new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948));

        AbstractObjectInstance laser = spawned.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> "CutsceneKnuxSKIntroEggRoboLaser".equals(child.getName()))
                .findFirst()
                .orElse(null);
        assertTrue(laser != null,
                "loc_91712 should allocate ChildObjDat_919DE when parent $38 bit 1 is set");
        assertEquals(0x065F, laser.getX(),
                "ChildObjDat_919DE applies child_dx=$0B from the upper EggRobo child position");
        assertEquals(0x08E0, laser.getY(),
                "ChildObjDat_919DE applies child_dy=-4 from the upper EggRobo child position");
    }

    @Test
    void skIntroGrabSignalAdvancesToFallingRoutineAndVelocity() {
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        skIntro.setServices(new TestObjectServices()
                .withCamera(new Camera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        skIntro.signalBombFallComplete();
        skIntro.update(1, knuckles);
        skIntro.signalGrabAnimationComplete();
        skIntro.update(2, knuckles);

        assertEquals(0x06, skIntro.getRoutineForTest(),
                "loc_63570 switches to routine $06 when _unkFAB8 bit 1 is set");
        assertEquals(0x8D, skIntro.getMappingFrameForTest(),
                "loc_63570 writes mapping_frame=$8D for the falling pose");
        assertEquals(-0x0100, skIntro.getXVelocityForTest(),
                "loc_63570 seeds x_vel=-$100 before the MoveSprite2 fall");
        assertEquals(-0x0100, skIntro.getYVelocityForTest(),
                "loc_63570 seeds y_vel=-$100 before the MoveSprite2 fall");
    }

    @Test
    void skIntroFallSignalAdvancesToLandingRoutineAndAdjustsBounds() {
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        skIntro.setServices(new TestObjectServices()
                .withCamera(new Camera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        skIntro.signalBombFallComplete();
        skIntro.update(1, knuckles);
        skIntro.signalGrabAnimationComplete();
        skIntro.update(2, knuckles);
        skIntro.signalFallingMotionComplete();
        skIntro.update(3, knuckles);

        assertEquals(0x08, skIntro.getRoutineForTest(),
                "loc_635A2 switches to routine $08 when _unkFAB8 bit 2 is set");
        assertEquals(3, skIntro.getMappingFrameForTest(),
                "loc_635A2 writes mapping_frame=3 for the landing setup");
        assertEquals(0x0940, skIntro.getY(),
                "loc_635A2 subtracts 8 from y_pos before the landing routine");
        assertEquals(0x13, skIntro.getYRadiusForTest(),
                "loc_635A2 writes y_radius=$13");
        assertEquals(-0x0100, skIntro.getXVelocityForTest(),
                "loc_635A2 keeps x_vel=-$100 for the light-gravity landing hop");
        assertEquals(0, skIntro.getYVelocityForTest(),
                "loc_635A2 clears y_vel before MoveSprite_LightGravity");
    }

    @Test
    void skIntroLandingRoutineMovesWithLightGravityAndSnapsToFloor() {
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        skIntro.setServices(new TestObjectServices()
                .withCamera(new Camera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        skIntro.signalBombFallComplete();
        skIntro.update(1, knuckles);
        skIntro.signalGrabAnimationComplete();
        skIntro.update(2, knuckles);
        skIntro.signalFallingMotionComplete();
        skIntro.update(3, knuckles);
        skIntro.setFloorDistanceForTest(-3);
        skIntro.update(4, knuckles);

        assertEquals(0x0A, skIntro.getRoutineForTest(),
                "loc_635C8 switches to routine $0A when ObjCheckFloorDist returns a negative floor distance");
        assertEquals(0x055F, skIntro.getX(),
                "MoveSprite_LightGravity applies old x_vel=-$100 before the floor snap");
        assertEquals(0x093D, skIntro.getY(),
                "loc_635C8 snaps y_pos by d1 after MoveSprite_LightGravity");
        assertEquals(0x20, skIntro.getYVelocityForTest(),
                "MoveSprite_LightGravity uses custom gravity $20 after moving with old y_vel");
        assertTrue(skIntro.hasLandingAnimationCallbackForTest(),
                "loc_635C8 stores loc_635FC in the callback slot before starting byte_668D0");
    }

    @Test
    void skIntroLandingAnimationCallbackStartsExitRunPose() {
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        skIntro.setServices(new TestObjectServices()
                .withCamera(new Camera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        skIntro.update(0, knuckles);
        skIntro.signalBombFallComplete();
        skIntro.update(1, knuckles);
        skIntro.signalGrabAnimationComplete();
        skIntro.update(2, knuckles);
        skIntro.signalFallingMotionComplete();
        skIntro.update(3, knuckles);
        skIntro.setFloorDistanceForTest(-3);
        skIntro.update(4, knuckles);
        skIntro.signalLandingAnimationComplete();
        skIntro.update(5, knuckles);

        assertEquals(0x0C, skIntro.getRoutineForTest(),
                "loc_635FC switches to routine $0C when the landing animation callback fires");
        assertEquals(7, skIntro.getMappingFrameForTest(),
                "loc_635FC writes mapping_frame=7 before starting byte_6682F");
        assertEquals(0, skIntro.getXVelocityForTest(),
                "loc_635FC clears x_vel before the exit-run acceleration routine takes over");
        assertEquals(0, skIntro.getYVelocityForTest(),
                "loc_635FC clears y_vel before the exit-run animation");
    }

    @Test
    void skIntroLandingRoutineUsesSharedObjectFloorDistance() {
        CutsceneKnucklesSkIntroInstance skIntro = new CutsceneKnucklesSkIntroInstance(new ObjectSpawn(
                0x0560, 0x0948, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x30, 0, false, 0));
        skIntro.setServices(new TestObjectServices()
                .withCamera(new Camera())
                .withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES)));
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0560, (short) 0x0948);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x055F, 0x0940, 0x13))
                    .thenReturn(new TerrainCheckResult(-3, (byte) 0, 0));

            skIntro.update(0, knuckles);
            skIntro.signalBombFallComplete();
            skIntro.update(1, knuckles);
            skIntro.signalGrabAnimationComplete();
            skIntro.update(2, knuckles);
            skIntro.signalFallingMotionComplete();
            skIntro.update(3, knuckles);
            skIntro.update(4, knuckles);

            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(0x055F, 0x0940, 0x13), times(1));
        }

        assertEquals(0x0A, skIntro.getRoutineForTest(),
                "loc_635C8 must use ObjCheckFloorDist and switch to routine $0A when the terrain probe returns d1 < 0");
        assertEquals(0x093D, skIntro.getY(),
                "the shared ObjCheckFloorDist distance should be applied to y_pos, replacing the test-only override path");
    }

    @Test
    void knucklesCutsceneClampsSonicAtRomX389AndLocksControl() {
        Mhz1CutsceneKnucklesInstance cutscene = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        cutscene.setServices(new TestObjectServices().withCamera(new Camera()));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0300, (short) 0x0580);
        sonic.setRenderFlips(true, false);

        cutscene.update(0, sonic);

        assertEquals(0x02, cutscene.getWorkspaceRoutineForTest(),
                "Obj_MHZ1CutsceneKnuckles init stores routine $02 in _unkFAB8");

        sonic.setCentreX((short) 0x0389);
        cutscene.update(1, sonic);

        assertEquals(0x0389, sonic.getCentreX() & 0xFFFF,
                "loc_62CF8 clamps Player_1.x_pos to $389 before the camera scroll sequence");
        assertTrue(sonic.isControlLocked(),
                "loc_62CF8 sets Control_Locked and clears Ctrl_1_logical");
        assertEquals(0, sonic.getLogicalInputState());
        assertFalse(sonic.getRenderHFlip(),
                "loc_62D04 clears render_flags bit 0 when the controller clamps Player_1 at x_pos=$389");
        assertEquals(0x04, cutscene.getWorkspaceRoutineForTest());
    }

    @Test
    void knucklesCutsceneForcesDownThenScrollsCameraTo5b0() {
        Camera camera = new Camera();
        camera.setY((short) 0x0570);
        Mhz1CutsceneKnucklesInstance cutscene = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        cutscene.setServices(new TestObjectServices().withCamera(camera));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);
        sonic.setCentreX((short) 0x0389);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        for (int frame = 0; frame < 0x42; frame++) {
            cutscene.update(3 + frame, sonic);
        }

        assertEquals(AbstractPlayableSprite.INPUT_DOWN, sonic.getForcedInputMask(),
                "loc_62D42 writes down into Ctrl_1_logical when the timer expires");
        assertEquals(0x05B0, camera.getY() & 0xFFFF,
                "loc_62D5A scrolls Camera_Y_pos by two pixels until it reaches $5B0");
        assertEquals(0x0A, cutscene.getWorkspaceRoutineForTest(),
                "routine $0A waits for the MHZ1 button to finish its press callback");
    }

    @Test
    void knucklesCutsceneWaitCounterFallsThroughOnLandingFrame() {
        Camera camera = new Camera();
        camera.setY((short) 0x054C);
        Mhz1CutsceneKnucklesInstance cutscene = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        cutscene.setServices(new TestObjectServices().withCamera(camera));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);
        sonic.setCentreX((short) 0x0389);
        sonic.setAir(false);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        for (int frame = 3; frame < 34; frame++) {
            cutscene.update(frame, sonic);
        }

        assertEquals(0x054C, camera.getY() & 0xFFFF,
                "loc_62D2C sets $2E=$20 and falls through to loc_62D42; the first 32 decrements do not scroll yet");

        cutscene.update(34, sonic);

        assertEquals(0x054E, camera.getY() & 0xFFFF,
                "the next loc_62D42 tick underflows the wait counter and falls through to loc_62D5A in the same frame");
    }

    @Test
    void knucklesCutsceneSetsAndClearsScrollLockAroundCameraPan() {
        Camera camera = new Camera();
        camera.setY((short) 0x0570);
        Mhz1CutsceneKnucklesInstance cutscene = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        cutscene.setServices(new TestObjectServices().withCamera(camera));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);
        sonic.setCentreX((short) 0x0389);

        cutscene.update(0, sonic);
        cutscene.update(1, sonic);
        cutscene.update(2, sonic);
        for (int frame = 0; frame < 0x21; frame++) {
            cutscene.update(3 + frame, sonic);
        }

        assertTrue(camera.getFrozen(),
                "loc_62D42 sets Scroll_lock when the forced camera pan begins");

        cutscene.forceReadyForButtonForTest();
        cutscene.signalButtonCallback();
        cutscene.update(0, sonic);

        assertFalse(camera.getFrozen(),
                "loc_62D70 clears Scroll_lock when the MHZ1 Knuckles cutscene exits");
    }

    @Test
    void knucklesCutsceneCleanupRestoresPaletteLine1Snapshot() {
        Palette[] palettes = {new Palette(), new Palette(), new Palette(), new Palette()};
        byte[] originalLine = paletteLine(0x0000, 0x0222, 0x0444, 0x0666,
                0x0888, 0x0AAA, 0x0CCC, 0x0EEE,
                0x0020, 0x0040, 0x0060, 0x0080,
                0x00A0, 0x00C0, 0x00E0, 0x0E00);
        byte[] cutsceneLine = paletteLine(0x0000, 0x000E, 0x002E, 0x004E,
                0x006E, 0x008E, 0x00AE, 0x00CE,
                0x00EE, 0x02EE, 0x04EE, 0x06EE,
                0x08EE, 0x0AEE, 0x0CEE, 0x0EEE);
        palettes[1].fromSegaFormat(originalLine);
        Palette expected = palettes[1].deepCopy();
        Level level = mock(Level.class);
        when(level.getPaletteCount()).thenReturn(palettes.length);
        when(level.getPalette(anyInt())).thenAnswer(invocation -> palettes[invocation.getArgument(0)]);

        Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        knuckles.setServices(new StubObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> null, List::of);
            }

            @Override
            public Level currentLevel() {
                return level;
            }
        });
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        knuckles.update(0, sonic);
        palettes[1].fromSegaFormat(cutsceneLine);
        knuckles.forceReadyForButtonForTest();
        knuckles.signalButtonCallback();
        knuckles.update(1, sonic);

        assertTrue(palettes[1].dataEquals(expected),
                "CutsceneKnux_MHZ1 snapshots Normal_palette_line_2 before Pal_CutsceneKnux; cleanup must restore it");
    }


    @Test
    void mhz1ButtonNormalRouteRunsInlineSolidCheckpointForSidePush() {
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.activeObjectsOfType(Mhz1CutsceneKnucklesInstance.class))
                .thenReturn(List.of());
        DefaultSolidExecutionRegistry solidExecution = new DefaultSolidExecutionRegistry();
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES))
                .withSolidExecutionRegistry(solidExecution);

        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x03FA, 0x067C, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        button.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x03E6, (short) 0x066D);

        assertTrue(button.usesInclusiveRightEdge(),
                "sub_65DEC reaches SolidObject_cont, whose X-window check rejects with bhi, not bhs; "
                        + "relX == width*2 is still a valid zero-distance side contact");

        solidExecution.beginFrame(0, List.of(sonic));
        solidExecution.beginObject(button, () -> {
            sonic.setPushing(true);
            return new SolidCheckpointBatch(button, Map.of(sonic, new PlayerSolidContactResult(
                    ContactKind.SIDE,
                    false,
                    false,
                    true,
                    false,
                    PreContactState.ZERO,
                    new PostContactState((short) 0, (short) 0, false, false, true),
                    1)));
        });
        try {
            button.update(936, sonic);
        } finally {
            solidExecution.endObject(button);
            solidExecution.finishFrame();
        }

        assertTrue(sonic.getPushing(),
                "loc_62F0A calls sub_65DEC/SolidObjectFull before testing standing bits, so side contacts "
                        + "must set Status_Push from the button slot itself "
                        + "(docs/skdisasm/sonic3k.asm:130120-130128,134100-134105,41488-41495)");
    }

    @Test
    void mhz1DoorUsesInclusiveRightEdgeForRestingSidePush() {
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        Mhz1CutsceneDoorInstance door = new Mhz1CutsceneDoorInstance(button);

        assertTrue(door.usesInclusiveRightEdge(),
                "MHZ1CutsceneButton_Door_Wait's jsr sub_65E4C reaches SolidObjectFull/SolidObject_cont, whose "
                        + "right-edge X-window check rejects with bhi, not bhs; relX == width*2 (a player resting "
                        + "exactly against the door's right edge) is still a valid zero-distance side contact, "
                        + "just like the sibling Obj_MHZ1CutsceneButton "
                        + "(docs/skdisasm/sonic3k.asm:130214,134149,41399)");
    }

    @Test
    void knucklesCutsceneCleanupSavesRomRestartPoint() {
        Camera camera = new Camera();
        camera.setX((short) 0x0100);
        camera.setY((short) 0x05B0);
        CheckpointState checkpointState = new CheckpointState();
        AtomicReference<SaveReason> saveReason = new AtomicReference<>();

        Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        knuckles.setServices(new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }

            @Override
            public void requestSessionSave(SaveReason reason) {
                saveReason.set(reason);
            }
        });
        knuckles.forceReadyForButtonForTest();
        knuckles.signalButtonCallback();

        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);
        knuckles.update(0, sonic);

        assertEquals(1, checkpointState.getLastCheckpointIndex(),
                "loc_62D70 writes Last_star_post_hit=1 for the post-cutscene restart point");
        assertEquals(0x0190, checkpointState.getSavedX(),
                "loc_62D70 writes Saved_X_pos=$190 before Save_Level_Data");
        assertEquals(0x056C, checkpointState.getSavedY(),
                "loc_62D70 writes Saved_Y_pos=$56C before Save_Level_Data");
        assertEquals(0x0100, checkpointState.getSavedCameraX(),
                "Save_Level_Data preserves the camera X present at cutscene cleanup");
        assertEquals(0x05B0, checkpointState.getSavedCameraY(),
                "Save_Level_Data preserves the camera Y reached by the forced pan");
        assertEquals(SaveReason.PROGRESSION_SAVE, saveReason.get(),
                "loc_62D70 calls Save_Level_Data after updating the restart variables");
    }


    @Test
    void mhz1CutsceneSpritesUseRomLowVdpPriorityForTunnelLayering() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0);
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(spawn);
        Mhz1CutsceneDoorInstance door = new Mhz1CutsceneDoorInstance(button);
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0), button);
        CutsceneKnucklesMhz1PeerInstance peer = new CutsceneKnucklesMhz1PeerInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0, 0, false, 0), knuckles);

        assertFalse(button.isHighPriority(),
                "ObjDat_MHZ1CutsceneButton uses make_art_tile(...,0,0), so priority tiles hide it");
        assertEquals(2, button.getPriorityBucket(),
                "ObjDat_MHZ1CutsceneButton has priority word $100");
        assertFalse(door.isHighPriority(),
                "ObjDat3_66462 uses make_art_tile(ArtTile_MHZMisc+$82,3,0), so the door belongs inside the tunnel");
        assertEquals(1, door.getPriorityBucket(),
                "ObjDat3_66462 has priority word $80");
        assertFalse(knuckles.isHighPriority(),
                "CutsceneKnux_MHZ1 clears bit 7 of art_tile after ObjSlot_CutsceneKnux setup");
        assertEquals(3, knuckles.getPriorityBucket(),
                "ObjSlot_CutsceneKnux has priority word $180");
        assertFalse(peer.isHighPriority(),
                "ObjDat3_6643E uses make_art_tile(ArtTile_MHZKnuxPeer,1,0)");
        assertEquals(3, peer.getPriorityBucket(),
                "ObjDat3_6643E has priority word $180");
    }



    @Test
    void mhz1FullCutsceneKnucklesUsesRawJumpAnimationWhenLeapingOntoButton() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        CutsceneKnucklesMhz1Instance knuckles = new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0), button);
        knuckles.setServices(new TestObjectServices().withLevelManager(levelManager));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        for (int frame = 0; frame < 122; frame++) {
            knuckles.update(frame, sonic);
        }
        for (int frame = 122; knuckles.getRoutineForTest() != 0x06 && frame < 260; frame++) {
            knuckles.update(frame, sonic);
        }
        knuckles.signalPeerReturned();
        for (int frame = 260; knuckles.getRoutineForTest() != 0x0A && frame < 360; frame++) {
            knuckles.update(frame, sonic);
        }
        knuckles.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(eq(0x08), anyInt(), anyInt(), eq(false), eq(false));
    }


    @Test
    void buttonUsesNormalSwitchPathForSonicAfterMhz1CutsceneCheckpoint() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        CheckpointState checkpointState = new CheckpointState();
        checkpointState.restoreFromSaved(0x0190, 0x056C, 0, 0, 1);
        List<Integer> sfxIds = new ArrayList<>();
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }

            @Override
            public void playSfx(int soundId) {
                sfxIds.add(soundId);
            }
        }.withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_AND_TAILS));
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        button.setServices(services);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

        button.onSolidContact(sonic, new SolidContact(true, false, false, false, false), 0);
        button.update(0, sonic);

        assertTrue(button.isDoorSwitchActive(),
                "Obj_MHZ1CutsceneButton keeps loc_62F0A when Last_star_post_hit is nonzero, even for Sonic/Tails");
        assertTrue(button.isDoorLowered(),
                "loc_62F0A toggles _unkFAA9 on the checkpoint normal-switch path");
        assertEquals(1, spawned.stream().filter(Mhz1CutsceneDoorInstance.class::isInstance).count(),
                "the normal-switch checkpoint path should only keep the init-time door child");
        assertEquals(2, sfxIds.stream().filter(id -> id == Sonic3kSfx.SWITCH.id).count(),
                "loc_62F0A plays sfx_Switch once for the press and once for the door toggle when bit 2 is clear");
    }

    @Test
    void mhz1ButtonAndDoorOnlyBecomeUnloadableAfterMainRoutineIsInstalled() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        CheckpointState checkpointState = new CheckpointState();
        checkpointState.restoreFromSaved(0x0190, 0x056C, 0, 0, 1);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }

            @Override
            public RespawnState checkpointState() {
                return checkpointState;
            }
        }.withZoneRuntimeRegistry(runtime(PlayerCharacter.SONIC_AND_TAILS));
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        button.setServices(services);

        assertTrue(button.isPersistent(),
                "WaitForKnucklesEvent/Depress/Wait_Draw only draw; they do not run Sprite_CheckDelete");

        button.update(0, new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580));
        Mhz1CutsceneDoorInstance door = spawned.stream()
                .filter(Mhz1CutsceneDoorInstance.class::isInstance)
                .map(Mhz1CutsceneDoorInstance.class::cast)
                .findFirst().orElseThrow();

        assertFalse(button.isPersistent(),
                "Last_star_post_hit selects MHZ1CutsceneButton_Main, whose tail is Sprite_CheckDelete");
        assertTrue(door.isPersistent(),
                "Child_Draw_Sprite keeps the door alive while its parent remains live");

        button.onUnload();

        assertTrue(door.isDestroyed(),
                "Child_Draw_Sprite observes the deleted parent and retires the door child");
    }

    @Test
    void buttonSpawnsMhz1DoorChildFromRomInit() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        button.setServices(services);

        button.update(0, new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580));

        assertEquals(1, spawned.size(),
                "Obj_MHZ1CutsceneButton creates ChildObjDat_665B6 during init before the Knuckles peer path");
        Mhz1CutsceneDoorInstance door = assertInstanceOf(Mhz1CutsceneDoorInstance.class, spawned.get(0));
        assertEquals(0x0390, door.getX(),
                "loc_6300C initializes the MHZ1 switch door child x_pos to $390");
        assertEquals(0x0620, door.getY(),
                "loc_6300C initializes the MHZ1 switch door child y_pos to $620 when _unkFAA9 is clear");
    }

    @Test
    void mhz1DoorSpawnsLoweredWhenUnkFaa9IsAlreadySetAtInit() {
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        // Simulate mid-act re-entry: this button/door pair is recreated (e.g.
        // after a checkpoint respawn) while the shared _unkFAA9 door-lowered
        // latch from a prior life is still set.
        button.setDoorLowered(true);

        Mhz1CutsceneDoorInstance door = new Mhz1CutsceneDoorInstance(button);

        assertEquals(0x0390, door.getX(),
                "loc_6300C initializes the MHZ1 switch door child x_pos to $390 regardless of _unkFAA9");
        assertEquals(0x0660, door.getY(),
                "loc_6300C adds $40 to y_pos when _unkFAA9 is already set at spawn (asm 130210-130212)");
    }

    @Test
    void mhz1DoorUsesRomSolidObjectFullDimensions() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        button.setServices(services);

        button.update(0, new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580));

        Mhz1CutsceneDoorInstance door = spawned.stream()
                .filter(Mhz1CutsceneDoorInstance.class::isInstance)
                .map(Mhz1CutsceneDoorInstance.class::cast)
                .findFirst().orElseThrow();
        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, door,
                "loc_6303C/loc_630A6 call sub_65E4C, which dispatches SolidObjectFull for the MHZ1 switch door");

        assertEquals(new SolidObjectParams(0x1B, 0x20, 0x20), solid.getSolidParams(),
                "sub_65E4C passes d1=$1B,d2=$20,d3=$20 into SolidObjectFull");
        assertFalse(solid.isTopSolidOnly(),
                "SolidObjectFull is a full solid door, not a top-only platform");
    }


    @Test
    void mhz1CutsceneCleanupQueuesFadeBackToLevelMusic() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        knuckles.setServices(services);
        knuckles.forceReadyForButtonForTest();
        knuckles.signalButtonCallback();

        knuckles.update(0, new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580));

        SongFadeTransitionInstance transition = spawned.stream()
                .filter(SongFadeTransitionInstance.class::isInstance)
                .map(SongFadeTransitionInstance.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(Sonic3kMusic.MHZ1.id, transition.getMusicIdForTest(),
                "Obj_MHZ1CutsceneKnuckles cleanup should allocate Obj_Song_Fade_ToLevelMusic for MHZ1");
    }

    @Test
    void knucklesRouteButtonStandingTogglesMhz1DoorDownThenUp() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES));
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        button.setServices(services);
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0380, (short) 0x05A0);

        button.onSolidContact(knuckles, new SolidContact(true, false, false, true, false), 0);
        button.update(0, knuckles);
        Mhz1CutsceneDoorInstance door = spawned.stream()
                .filter(Mhz1CutsceneDoorInstance.class::isInstance)
                .map(Mhz1CutsceneDoorInstance.class::cast)
                .findFirst().orElseThrow();
        door.setServices(services);
        assertTrue(button.isDoorSwitchActive(),
                "loc_62F0A bsets parent bit 1 when the normal Knuckles-route switch is newly stood on");
        assertTrue(button.isDoorLowered(),
                "loc_62F0A not.b _unkFAA9 turns the clear initial state into the lowered door state");

        door.update(1, knuckles);
        // loc_63078 falls through into loc_630A6, so update(1) already runs the
        // first MoveSprite2; the 64-pixel slide therefore completes over
        // update(1)..update(64) rather than update(1)..update(65).
        for (int frame = 0; frame < 63; frame++) {
            door.update(2 + frame, knuckles);
        }
        assertEquals(0x0660, door.getY());

        button.update(66, knuckles);
        button.onSolidContact(knuckles, new SolidContact(true, false, false, true, false), 67);
        button.update(67, knuckles);
        assertTrue(button.isDoorSwitchActive(),
                "A second loc_62F0A standing transition toggles the switch again once the door is idle");
        assertFalse(button.isDoorLowered(),
                "The second normal button press clears _unkFAA9, so loc_63078 raises the door");

        door.update(68, knuckles);
        for (int frame = 0; frame < 64; frame++) {
            door.update(69 + frame, knuckles);
        }
        assertEquals(0x0620, door.getY(),
                "loc_63078 negates y_vel when _unkFAA9 is clear, moving the door back up 64 pixels");
    }

    @Test
    void loweredMhz1DoorAutoRaisesWhenPlayerReachesRomLeftSideRange() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withZoneRuntimeRegistry(runtime(PlayerCharacter.KNUCKLES));
        Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                0x0380, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
        button.setServices(services);
        TestablePlayableSprite knuckles = new TestablePlayableSprite("knuckles", (short) 0x0380, (short) 0x05A0);

        button.onSolidContact(knuckles, new SolidContact(true, false, false, true, false), 0);
        button.update(0, knuckles);
        Mhz1CutsceneDoorInstance door = spawned.stream()
                .filter(Mhz1CutsceneDoorInstance.class::isInstance)
                .map(Mhz1CutsceneDoorInstance.class::cast)
                .findFirst().orElseThrow();
        door.setServices(services);
        door.update(1, knuckles);
        // loc_63078 falls through into loc_630A6, so update(1) already runs the
        // first MoveSprite2; the 64-pixel slide therefore completes over
        // update(1)..update(64) rather than update(1)..update(65).
        for (int frame = 0; frame < 63; frame++) {
            door.update(2 + frame, knuckles);
        }
        assertEquals(0x0660, door.getY());
        assertTrue(button.isDoorLowered());
        assertFalse(button.isDoorMovingForTest());

        TestablePlayableSprite playerLeftOfDoor = new TestablePlayableSprite("knuckles",
                (short) 0x0380, (short) 0x05A0);
        door.update(67, playerLeftOfDoor);

        assertFalse(button.isDoorLowered(),
                "loc_6303C clears _unkFAA9 when Player_1 is left of the lowered door, within $40 X and at least $60 Y away");
        assertTrue(button.isDoorMovingForTest(),
                "loc_6303C falls through to loc_63078 immediately after clearing _unkFAA9, setting parent bit 2");
        assertEquals(0x065F, door.getY(),
                "loc_63078 arms y_vel=-$100 and falls through loc_63094 into loc_630A6 with no rts, so "
                        + "the auto-raise trigger frame already moves the door up one pixel");

        door.update(68, playerLeftOfDoor);

        assertEquals(0x065E, door.getY(),
                "loc_630A6 keeps moving the door upward on each following frame");
    }

    @Test
    void knucklesCutsceneAllocatesP2StopperAndReleasesItWhenCutsceneEnds() {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));

        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0300, (short) 0x0580);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x0371, (short) 0x0580);
        tails.setGameRulesForTest(GameRules.SONIC_3K);
        tails.setLogicalInputState(false, false, true, false, true);
        StubObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        }.withPlayerQuery(new ObjectPlayerQuery(() -> sonic, () -> List.of(tails)));
        Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
        knuckles.setServices(services);

        knuckles.update(0, sonic);

        assertEquals(1, spawned.size(),
                "loc_62CDE allocates the Player_2 helper when Player_2 is present");
        AbstractObjectInstance p2Stopper = assertInstanceOf(AbstractObjectInstance.class, spawned.get(0));
        p2Stopper.setServices(services);
        p2Stopper.update(1, sonic);

        assertTrue(tails.isControlLocked(),
                "loc_62DC4 sets Ctrl_2_locked once Player_2 reaches x_pos $371");
        assertEquals(0, tails.getLogicalInputState(),
                "loc_62DC4 clears Ctrl_2_logical while the MHZ1 cutscene owns Player_2");

        sonic.setCentreX((short) 0x0389);
        knuckles.update(2, sonic);
        p2Stopper.update(3, sonic);

        assertEquals(Sonic3kAnimationIds.DUCK.id(), tails.getAnimationId(),
                "loc_62DDC writes animation $08 while Player_2 is held at the stop point");

        knuckles.forceReadyForButtonForTest();
        knuckles.signalButtonCallback();
        knuckles.update(4, sonic);
        p2Stopper.update(5, sonic);

        assertEquals(Sonic3kAnimationIds.WAIT.id(), tails.getAnimationId(),
                "loc_62D70 writes Player_2 anim=$05 when the MHZ1 Knuckles cutscene exits");
        assertFalse(tails.isControlLocked(),
                "loc_62E1A clears Ctrl_2_locked when _unkFAB8 returns to zero");
        assertEquals(0, tails.getLogicalInputState());
        assertTrue(p2Stopper.isDestroyed());
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
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

    private static ZoneRuntimeRegistry runtime(PlayerCharacter playerCharacter) {
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(new MhzZoneRuntimeState(0, playerCharacter));
        return registry;
    }

    private static Camera mhz2TriggerCamera() {
        Camera camera = new Camera();
        setMhz2TriggerCameraPosition(camera);
        return camera;
    }

    private static void setMhz2TriggerCameraPosition(Camera camera) {
        camera.setX((short) 0x03D0);
        camera.setY((short) 0x0748);
    }

    private static void advanceMhz1CutsceneKnucklesToButtonRange(CutsceneKnucklesMhz1Instance cutsceneKnuckles,
                                                                 Mhz1CutsceneButtonInstance button,
                                                                 TestablePlayableSprite sonic) {
        for (int frame = 0; frame < 260 && cutsceneKnuckles.getRoutineForTest() < 0x06; frame++) {
            cutsceneKnuckles.update(frame, sonic);
        }
        cutsceneKnuckles.signalPeerReturned();
        for (int frame = 260; frame < 420 && !button.isDoorSwitchActive(); frame++) {
            cutsceneKnuckles.update(frame, sonic);
            button.update(frame, sonic);
        }
    }

    private static void registerActiveCutsceneController(ObjectManager objectManager,
                                                         Mhz1CutsceneKnucklesInstance controller) {
        when(objectManager.activeObjectsOfType(Mhz1CutsceneKnucklesInstance.class))
                .thenReturn(List.of(controller));
    }

    private static int advanceMhz2CutsceneThroughPressAnimation(CutsceneKnucklesMhz2Instance cutscene,
                                                               TestablePlayableSprite sonic) {
        int frame = 3;
        for (; frame < 500 && cutscene.getRoutineForTest() != 0x08; frame++) {
            cutscene.update(frame, sonic);
        }
        return frame;
    }

    private static AbstractObjectInstance mhz2LiftChild(List<ObjectInstance> spawned) {
        return spawned.stream()
                .filter(o -> o.getClass().getSimpleName().contains("Lift"))
                .map(AbstractObjectInstance.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void assertColorWord(Palette palette, int colorIndex, int word) {
        int expectedR = (((word >> 1) & 0x7) * 255 + 3) / 7;
        int expectedG = (((word >> 5) & 0x7) * 255 + 3) / 7;
        int expectedB = (((word >> 9) & 0x7) * 255 + 3) / 7;
        Palette.Color color = palette.getColor(colorIndex);
        assertEquals(expectedR, color.r & 0xFF);
        assertEquals(expectedG, color.g & 0xFF);
        assertEquals(expectedB, color.b & 0xFF);
    }

    private static byte[] paletteLine(int... words) {
        byte[] line = new byte[words.length * 2];
        for (int i = 0; i < words.length; i++) {
            line[i * 2] = (byte) ((words[i] >>> 8) & 0xFF);
            line[i * 2 + 1] = (byte) (words[i] & 0xFF);
        }
        return line;
    }

    private static final class PalPointersSszRom extends Rom {
        private static final int PAL_POINTERS_SSZ1_INDEX = 0x1E;
        private static final int SSZ1_SOURCE_ADDR = 0x123450;

        private final byte[] line2;

        private PalPointersSszRom(byte[] line2) {
            this.line2 = Arrays.copyOf(line2, line2.length);
        }

        @Override
        public int read32BitAddr(long offset) throws IOException {
            if (offset == ssz1EntryAddr()) {
                return SSZ1_SOURCE_ADDR;
            }
            return 0;
        }

        @Override
        public int read16BitAddr(long offset) throws IOException {
            if (offset == ssz1EntryAddr() + 4) {
                return 0xFC20;
            }
            if (offset == ssz1EntryAddr() + 6) {
                return 23;
            }
            return 0;
        }

        @Override
        public byte[] readBytes(long offset, int count) throws IOException {
            if (offset == SSZ1_SOURCE_ADDR && count == 32) {
                return Arrays.copyOf(line2, line2.length);
            }
            return new byte[count];
        }

        private static int ssz1EntryAddr() {
            return Sonic3kConstants.PAL_POINTERS_ADDR
                    + PAL_POINTERS_SSZ1_INDEX * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
        }
    }

    /**
     * Button tests that reach {@code MHZ1CutsceneButton_LoadKnucklesPeer}.
     * That path submits {@code ArtKosM_MHZKnuxPeer} through
     * {@code Queue_Kos_Module} (sonic3k.asm:130077-130081), so these need a
     * real KosM archive and are ROM-backed; the rest of the class is not.
     */
    @Nested
    @RequiresRom(SonicGame.SONIC_3K)
    class KnucklesPeerArtSubmission {
        @Test
        void buttonSignalsRoutineCAndKnucklesCleanupRestoresPlayerControl() {
            Camera camera = new Camera();
            ObjectManager objectManager = mock(ObjectManager.class);
            List<ObjectInstance> spawned = new ArrayList<>();
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
            HardwareTimingService peerArtTiming = new HardwareTimingService();
            S3kRuntimeArtCoordinator peerArtCoordinator =
                    new S3kRuntimeArtCoordinator(peerArtTiming);
            TestObjectServices services = new TestObjectServices() {
                @Override
                public HardwareTimingService hardwareTiming() {
                    return peerArtTiming;
                }

                @Override
                public Rom rom() {
                    return TestEnvironment.currentRom();
                }

                @Override
                public RuntimeArtCoordinator runtimeArtCoordinator() {
                    return peerArtCoordinator;
                }
                @Override
                public ObjectManager objectManager() {
                    return objectManager;
                }
            }.withCamera(camera);
            Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                    0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
            knuckles.setServices(services);
            knuckles.forceReadyForButtonForTest();
            registerActiveCutsceneController(objectManager, knuckles);
            TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);
            sonic.setControlLocked(true);
            sonic.setForcedInputMask(AbstractPlayableSprite.INPUT_DOWN);

            Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                    0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
            button.setServices(services);

            button.update(0, sonic);
            button.update(1, sonic);
            CutsceneKnucklesMhz1Instance cutsceneKnuckles = spawned.stream()
                    .filter(CutsceneKnucklesMhz1Instance.class::isInstance)
                    .map(CutsceneKnucklesMhz1Instance.class::cast)
                    .findFirst().orElseThrow();
            cutsceneKnuckles.setServices(services);
            advanceMhz1CutsceneKnucklesToButtonRange(cutsceneKnuckles, button, sonic);
            for (int frame = 0; frame < 0x5F; frame++) {
                button.update(420 + frame, sonic);
            }
            knuckles.update(0, sonic);

            assertFalse(sonic.isControlLocked(),
                    "Obj_MHZ1CutsceneButton reaches Wait_Draw at loc_62ED0 before its long callback wait; "
                            + "Obj_Wait must branch to loc_62EFC soon enough for the next controller slot pass");
            assertEquals(0, sonic.getForcedInputMask());
            assertTrue(knuckles.isDestroyed());
        }

        @Test
        void buttonSpawnsMhz1PeerKnucklesChildWhenCameraPanCompletes() {
            ObjectManager objectManager = mock(ObjectManager.class);
            List<ObjectInstance> spawned = new ArrayList<>();
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
            HardwareTimingService peerArtTiming = new HardwareTimingService();
            S3kRuntimeArtCoordinator peerArtCoordinator =
                    new S3kRuntimeArtCoordinator(peerArtTiming);
            StubObjectServices services = new StubObjectServices() {
                @Override
                public HardwareTimingService hardwareTiming() {
                    return peerArtTiming;
                }

                @Override
                public Rom rom() {
                    return TestEnvironment.currentRom();
                }

                @Override
                public RuntimeArtCoordinator runtimeArtCoordinator() {
                    return peerArtCoordinator;
                }
                @Override
                public ObjectManager objectManager() {
                    return objectManager;
                }
            };
            Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                    0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
            knuckles.setServices(services);
            knuckles.forceReadyForButtonForTest();
            registerActiveCutsceneController(objectManager, knuckles);
            Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                    0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
            button.setServices(services);

            button.update(0, new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580));
            button.update(1, new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580));

            long peerCount = spawned.stream()
                    .filter(CutsceneKnucklesMhz1Instance.class::isInstance)
                    .count();
            assertEquals(1, peerCount,
                    "loc_62E6A creates ChildObjDat_665AA once when _unkFAB8 reaches $0A; subtype $1C then runs CutsceneKnux_MHZ1");
            assertEquals(1, spawned.stream().filter(Mhz1CutsceneDoorInstance.class::isInstance).count(),
                    "Obj_MHZ1CutsceneButton also keeps its init-time ChildObjDat_665B6 door child");
        }

        @Test
        void buttonWaitsForSpawnedMhz1KnucklesToEnterRomRangeBeforePressing() {
            ObjectManager objectManager = mock(ObjectManager.class);
            List<ObjectInstance> spawned = new ArrayList<>();
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
            List<Integer> sfxIds = new ArrayList<>();
            HardwareTimingService peerArtTiming = new HardwareTimingService();
            S3kRuntimeArtCoordinator peerArtCoordinator =
                    new S3kRuntimeArtCoordinator(peerArtTiming);
            TestObjectServices services = new TestObjectServices() {
                @Override
                public HardwareTimingService hardwareTiming() {
                    return peerArtTiming;
                }

                @Override
                public Rom rom() {
                    return TestEnvironment.currentRom();
                }

                @Override
                public RuntimeArtCoordinator runtimeArtCoordinator() {
                    return peerArtCoordinator;
                }
                @Override
                public ObjectManager objectManager() {
                    return objectManager;
                }

                @Override
                public void playSfx(int soundId) {
                    sfxIds.add(soundId);
                }
            };
            Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                    0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
            knuckles.setServices(services);
            knuckles.forceReadyForButtonForTest();
            registerActiveCutsceneController(objectManager, knuckles);
            Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                    0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
            button.setServices(services);
            TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

            button.update(0, sonic);
            button.update(1, sonic);

            assertEquals(1, spawned.stream().filter(CutsceneKnucklesMhz1Instance.class::isInstance).count(),
                    "loc_62E6A still creates ChildObjDat_665AA as soon as _unkFAB8 reaches $0A");
            assertFalse(button.isDoorSwitchActive(),
                    "loc_62E92 only sets parent bit 1 after Check_InMyRange succeeds for the spawned subtype $1C child");
            assertFalse(button.isDoorLowered(),
                    "loc_62E92 should not set _unkFAA9 on the same frame the offscreen child is created");
            assertFalse(sfxIds.contains(Sonic3kSfx.SWITCH.id),
                    "loc_62E92 plays sfx_Switch only when the spawned Knuckles child enters word_65C48");
        }

        @Test
        void buttonPressesWhenSpawnedMhz1KnucklesEntersRomRange() {
            ObjectManager objectManager = mock(ObjectManager.class);
            List<ObjectInstance> spawned = new ArrayList<>();
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
            List<Integer> sfxIds = new ArrayList<>();
            HardwareTimingService peerArtTiming = new HardwareTimingService();
            S3kRuntimeArtCoordinator peerArtCoordinator =
                    new S3kRuntimeArtCoordinator(peerArtTiming);
            TestObjectServices services = new TestObjectServices() {
                @Override
                public HardwareTimingService hardwareTiming() {
                    return peerArtTiming;
                }

                @Override
                public Rom rom() {
                    return TestEnvironment.currentRom();
                }

                @Override
                public RuntimeArtCoordinator runtimeArtCoordinator() {
                    return peerArtCoordinator;
                }
                @Override
                public ObjectManager objectManager() {
                    return objectManager;
                }

                @Override
                public void playSfx(int soundId) {
                    sfxIds.add(soundId);
                }
            };
            Mhz1CutsceneKnucklesInstance controller = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                    0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
            controller.setServices(services);
            controller.forceReadyForButtonForTest();
            registerActiveCutsceneController(objectManager, controller);
            Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                    0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
            button.setServices(services);
            TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

            button.update(0, sonic);
            button.update(1, sonic);
            CutsceneKnucklesMhz1Instance cutsceneKnuckles = spawned.stream()
                    .filter(CutsceneKnucklesMhz1Instance.class::isInstance)
                    .map(CutsceneKnucklesMhz1Instance.class::cast)
                    .findFirst().orElseThrow();
            cutsceneKnuckles.setServices(services);
            advanceMhz1CutsceneKnucklesToButtonRange(cutsceneKnuckles, button, sonic);

            assertTrue(button.isDoorSwitchActive(),
                    "loc_62E92 bsets parent bit 1 once Check_InMyRange succeeds");
            assertTrue(button.isDoorLowered(),
                    "loc_62E92 sets _unkFAA9 when the spawned Knuckles child reaches the switch");
            assertTrue(sfxIds.contains(Sonic3kSfx.SWITCH.id),
                    "loc_62E92 plays sfx_Switch on the delayed cutscene button press");
        }

        @Test
        void cutsceneButtonPressedFrameReturnsToUnpressedDuringCallbackWait() {
            ObjectManager objectManager = mock(ObjectManager.class);
            List<ObjectInstance> spawned = new ArrayList<>();
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
            HardwareTimingService peerArtTiming = new HardwareTimingService();
            S3kRuntimeArtCoordinator peerArtCoordinator =
                    new S3kRuntimeArtCoordinator(peerArtTiming);
            StubObjectServices services = new StubObjectServices() {
                @Override
                public HardwareTimingService hardwareTiming() {
                    return peerArtTiming;
                }

                @Override
                public Rom rom() {
                    return TestEnvironment.currentRom();
                }

                @Override
                public RuntimeArtCoordinator runtimeArtCoordinator() {
                    return peerArtCoordinator;
                }
                @Override
                public ObjectManager objectManager() {
                    return objectManager;
                }
            };
            Mhz1CutsceneKnucklesInstance controller = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                    0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
            controller.setServices(services);
            controller.forceReadyForButtonForTest();
            registerActiveCutsceneController(objectManager, controller);
            Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                    0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
            button.setServices(services);
            TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

            button.update(0, sonic);
            button.update(1, sonic);
            CutsceneKnucklesMhz1Instance cutsceneKnuckles = spawned.stream()
                    .filter(CutsceneKnucklesMhz1Instance.class::isInstance)
                    .map(CutsceneKnucklesMhz1Instance.class::cast)
                    .findFirst().orElseThrow();
            cutsceneKnuckles.setServices(services);
            advanceMhz1CutsceneKnucklesToButtonRange(cutsceneKnuckles, button, sonic);

            assertEquals(1, button.getVisibleMappingFrameForTest(),
                    "loc_62E92 writes mapping_frame=1 when the scripted Knuckles child presses the switch");

            button.update(2, sonic);
            button.update(3, sonic);

            assertEquals(0, button.getVisibleMappingFrameForTest(),
                    "loc_62ED0 restores mapping_frame=0 while the long callback wait continues");
        }

        @Test
        void mhz1DoorSlidesDownForRomWaitWhenButtonPressSetsUnkFaa9() {
            ObjectManager objectManager = mock(ObjectManager.class);
            List<ObjectInstance> spawned = new ArrayList<>();
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
            HardwareTimingService peerArtTiming = new HardwareTimingService();
            S3kRuntimeArtCoordinator peerArtCoordinator =
                    new S3kRuntimeArtCoordinator(peerArtTiming);
            StubObjectServices services = new StubObjectServices() {
                @Override
                public HardwareTimingService hardwareTiming() {
                    return peerArtTiming;
                }

                @Override
                public Rom rom() {
                    return TestEnvironment.currentRom();
                }

                @Override
                public RuntimeArtCoordinator runtimeArtCoordinator() {
                    return peerArtCoordinator;
                }
                @Override
                public ObjectManager objectManager() {
                    return objectManager;
                }
            };
            Mhz1CutsceneKnucklesInstance knuckles = new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                    0x0380, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0));
            knuckles.setServices(services);
            knuckles.forceReadyForButtonForTest();
            registerActiveCutsceneController(objectManager, knuckles);
            Mhz1CutsceneButtonInstance button = new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                    0x0380, MHZ1_SWITCH_SPAWN_Y, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0));
            button.setServices(services);
            TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x0389, (short) 0x0580);

            button.update(0, sonic);
            Mhz1CutsceneDoorInstance door = spawned.stream()
                    .filter(Mhz1CutsceneDoorInstance.class::isInstance)
                    .map(Mhz1CutsceneDoorInstance.class::cast)
                    .findFirst().orElseThrow();
            door.setServices(services);
            button.update(1, sonic);
            CutsceneKnucklesMhz1Instance cutsceneKnuckles = spawned.stream()
                    .filter(CutsceneKnucklesMhz1Instance.class::isInstance)
                    .map(CutsceneKnucklesMhz1Instance.class::cast)
                    .findFirst().orElseThrow();
            cutsceneKnuckles.setServices(services);
            advanceMhz1CutsceneKnucklesToButtonRange(cutsceneKnuckles, button, sonic);

            door.update(2, sonic);
            assertEquals(0x0621, door.getY(),
                    "loc_63078 falls through loc_63094 into loc_630A6 with no rts, so the arming frame "
                            + "already runs MoveSprite2 with y_vel=$100 while _unkFAA9 is set");

            door.update(3, sonic);
            assertEquals(0x0622, door.getY(),
                    "loc_630A6 keeps calling MoveSprite2 each following frame");

            for (int frame = 0; frame < 63; frame++) {
                door.update(4 + frame, sonic);
            }

            assertEquals(0x0660, door.getY(),
                    "Obj_Wait starts at $3F, so the switch door moves down exactly 64 pixels");
            assertFalse(button.isDoorMovingForTest(),
                    "loc_630BE clears parent bit 2 when the 64-frame door slide completes");

            TestablePlayableSprite nearDoor = new TestablePlayableSprite("sonic", (short) 0x0380, (short) 0x0580);
            door.update(68, nearDoor);
            assertEquals(0x0660, door.getY(),
                    "the scripted MHZ1 press latches _unkFAA9; the door must not auto-raise before the cutscene cleanup");
        }

    }
}
