package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.CnzObjectEventBridge;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_3K)
class TestCutsceneKnucklesCnz2Instance {
    @Test
    void secondCnzCutsceneStartsVisibleInNativePlacedStandingFrame() throws Exception {
        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x47A0, 0x0A2C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x10, 0, false, 0));

        assertEquals(0x16, getPrivateIntField(knuckles, "mappingFrame"),
                "ObjSlot_CutsceneKnux publishes mapping_frame=$16 before the camera reveals Knuckles");
    }

    @Test
    void secondCnzCutsceneLandingScriptChainsIntoNativeLaughLoop() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        GameServices.camera().setX((short) 0x45C0);
        GameServices.camera().setY((short) 0x0A00);
        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x47A0, 0x0A2C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x10, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());
        setPrivateBooleanField(knuckles, "bounced", true);
        invokePrivateFloorContact(knuckles, 0);

        assertEquals(0x1C, getPrivateIntField(knuckles, "mappingFrame"));
        for (int frame = 1; frame <= 8; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1C, getPrivateIntField(knuckles, "mappingFrame"));
        }
        for (int frame = 9; frame <= 16; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1D, getPrivateIntField(knuckles, "mappingFrame"));
        }
        for (int frame = 17; frame <= 24; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1E, getPrivateIntField(knuckles, "mappingFrame"),
                    "byte_666B9 command $F8,+6 must enter byte_666BF's $1E/$1F laugh loop");
        }
        knuckles.update(25, null);
        assertEquals(0x1F, getPrivateIntField(knuckles, "mappingFrame"));
    }

    @Test
    void secondCnzCutsceneRendersClearNativeFacingBitDirectly() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        TestObjectServices services = new TestObjectServices();
        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x47A0, 0x0A2C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x10, 0, false, 0));
        knuckles.setServices(services);

        try (MockedStatic<AizIntroArtLoader> artLoader = mockStatic(AizIntroArtLoader.class)) {
            artLoader.when(() -> AizIntroArtLoader.getKnucklesRenderer(services)).thenReturn(renderer);

            knuckles.appendRenderCommands(new ArrayList<GLCommand>());
        }

        verify(renderer).drawFrameIndex(0x16, 0x47A0, 0x0A2C, false, false);
    }

    @Test
    void secondCnzCutsceneFirstBounceReversesVelocityWithoutChangingFacing() throws Exception {
        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x47A0, 0x0A2C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x10, 0, false, 0));
        setPrivateIntField(knuckles, "xVel", -0x0100);
        setPrivateIntField(knuckles, "yVel", 0x0200);

        invokePrivateFloorContact(knuckles, 0);

        assertFalse(getPrivateBooleanField(knuckles, "facingRight"),
                "loc_620AA negates x_vel/y_vel but does not change render_flags bit 0");
        assertEquals(0x0100, getPrivateIntField(knuckles, "xVel"));
        assertEquals(-0x0200, getPrivateIntField(knuckles, "yVel"));
    }

    @Test
    void registryRoutesCnzAct2CutsceneSubtypesToCnzHandlers() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance first = registry.create(new ObjectSpawn(
                0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        ObjectInstance second = registry.create(new ObjectSpawn(
                0x45C0, 0x0720, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0));

        assertInstanceOf(CutsceneKnucklesCnz2AInstance.class, first,
                "Subtype $0C is CutsceneKnux_CNZ2A, not the AIZ2 fallback");
        assertInstanceOf(CutsceneKnucklesCnz2BInstance.class, second,
                "Subtype $10 is CutsceneKnux_CNZ2B, not the AIZ2 fallback");
    }

    @Test
    void cnzCutsceneObjectsStartAtTheirLayoutPositions() {
        CutsceneKnucklesCnz2AInstance first = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        CutsceneKnucklesCnz2BInstance second = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x45C0, 0x0720, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0));

        assertEquals(0x1D00, first.getX());
        assertEquals(0x0280, first.getY());
        assertEquals(0x45C0, second.getX());
        assertEquals(0x0720, second.getY());
    }

    @Test
    void registryRoutesCnzCutsceneButtonSubtypeToCnzHandler() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_CNZ);

        ObjectInstance button = registry.create(new ObjectSpawn(
                0x1E00, 0x0338, Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));

        assertInstanceOf(Cnz2CutsceneButtonInstance.class, button,
                "Obj_CutsceneButton subtype $04 is the CNZ2 first-encounter button "
                        + "(off_65C40[$04] -> loc_65C78, docs/skdisasm/sonic3k.asm:133916-133994); "
                        + "the CNZ2 layout places it at X=$1E00 with subtype $04 "
                        + "(Levels/CNZ/Object Pos/2.bin), not the AIZ-only handler");
    }

    @Test
    void registryRoutesCnzVacuumTubeButtonSubtypeToCnzHandler() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_CNZ);

        ObjectInstance button = registry.create(new ObjectSpawn(
                0x4780, 0x0728, Sonic3kObjectIds.CUTSCENE_BUTTON, 6, 0, false, 0));

        assertInstanceOf(Cnz2CutsceneButtonInstance.class, button,
                "Obj_CutsceneButton subtype $06 is the CNZ2 second-encounter button "
                        + "(off_65C40[$06] -> loc_65CAC, docs/skdisasm/sonic3k.asm:133951-134019); "
                        + "it must spawn the vacuum tubes instead of falling through to the AIZ cutscene button");
    }

    @Test
    void cnzCutsceneButtonPressesWaterAndPaletteRoute_notLevelTriggerRoute() {
        RecordingCnzBridge bridge = new RecordingCnzBridge();
        Camera buttonCamera = new Camera();
        buttonCamera.setY((short) 0x0280);
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(new ObjectSpawn(
                0x1D00, 0x027C, Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));
        button.setServices(new TestObjectServices() {
            @Override
            public LevelEventProvider levelEventProvider() {
                return bridge;
            }
        }.withCamera(buttonCamera));
        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        CutsceneKnucklesCnz2AInstance.setActiveInstanceForTests(knuckles);

        button.update(0, null);

        assertEquals(0x0350, bridge.waterTargetY,
                "Obj_CutsceneButton subtype $04 dispatches to loc_65C78: Target_water_level=$350 "
                        + "and a CNZ palette flash, not loc_65C72's Level_trigger_array+8 branch (subtype $02)");
        assertEquals(true, bridge.waterButtonArmed);
        assertEquals(0x0280 + 0x100, bridge.waterMeanLevel,
                "loc_65C78 seeds Mean_water_level = Camera_Y + $100 so the flood is already risen");
        assertEquals(0x14, bridge.screenShakeFrames,
                "loc_65C78 also writes Screen_shake_flag=$14");
        assertNotNull(button.getSpawnedFlashForTest(),
                "loc_65C78 spawns the loc_62480 lights-off flash child (subtype 0, no restore)");
        assertFalse(button.getSpawnedFlashForTest().restoresAfterForTest(),
                "the cutscene button's flash leaves the dark palette in place (lights stay off)");
    }

    @Test
    void cnzVacuumTubeButtonSpawnsTubeControllersFromRomAction() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        GameServices.camera().setX((short) 0x45C0);
        GameServices.camera().setY((short) 0x0720);

        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x4780, 0x072C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());
        knuckles.update(0, fixture.sprite());

        assertTrue(fixture.sprite().isObjectControlled());
        assertFalse(fixture.sprite().isObjectControlSuppressesMovement(),
                "loc_62528 writes object_control=$80: bit 7 suppresses touch response, "
                        + "but Obj01_Control still runs movement because bit 0 is clear");

        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x4780, 0x0728, Sonic3kObjectIds.CUTSCENE_BUTTON, 6, 0, false, 0));
        button.setServices(TestEnvironment.objectServices());
        button.update(0, fixture.sprite());

        long tubeCount = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(CnzVacuumTubeInstance.class::isInstance)
                .count();
        assertTrue(tubeCount >= 2,
                "Obj_CutsceneButton subtype $06 dispatches to loc_65CAC, which allocates "
                        + "the two Obj_CNZVacuumTube controllers at $4740/$0828 and $4740/$0A28");
    }

    @Test
    void firstCnzCutsceneStoresCameraTargetsWithoutSnappingImmediately() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x1C80);
        camera.setY((short) 0x0180);
        camera.setMinX((short) 0x1B00);
        camera.setMaxX((short) 0x1C40);
        camera.setMinY((short) 0x0100);
        camera.setMaxYTarget((short) 0x0400);

        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());

        knuckles.update(0, null);

        assertEquals(0x1B00, camera.getMinX() & 0xFFFF,
                "CutsceneKnux_CNZ2A calls loc_85D70 first; it stores the target lock and old bounds, "
                        + "but does not snap Camera_min_X_pos to $1D00 on the init frame");
        assertNotEquals(0x1D00, camera.getMaxX() & 0xFFFF,
                "Camera_max_X_pos must remain on the pre-cutscene bound until loc_85CA4 observes "
                        + "Camera_X_pos reaching the target");
    }

    @Test
    void firstCnzCutsceneLocksAfterCurrentCameraWordReachesNativeBoundary() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        Camera camera = fixture.camera();
        camera.setFocusedSprite(fixture.sprite());
        camera.setX((short) 0x1CE0);
        camera.setY((short) 0x0280);
        camera.setMinX((short) 0x0000);
        camera.setMaxX((short) 0x4000);

        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1DE0, 0x032C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());
        knuckles.update(0, fixture.sprite());

        // loc_85CA4 compares the camera word visible during this object slot; it
        // does not predict the ScrollHoriz result later in the same LevelLoop.
        camera.setX((short) 0x1CF1);
        fixture.sprite().setCentreX((short) 0x1DA2);
        knuckles.update(1, fixture.sprite());
        camera.updatePosition();

        assertEquals(0x1D02, camera.getX() & 0xFFFF,
                "the pending ScrollHoriz step remains visible until the next object pass");
        assertNotEquals(0x1D00, camera.getMaxX() & 0xFFFF);

        knuckles.update(2, fixture.sprite());
        assertEquals(0x1D00, camera.getMinX() & 0xFFFF);
        assertEquals(0x1D00, camera.getMaxX() & 0xFFFF);
        fixture.sprite().setCentreX((short) 0x1D80);
        camera.updatePosition();
        assertEquals(0x1D00, camera.getX() & 0xFFFF,
                "the completed native lock must reject leftward camera movement");
        fixture.sprite().setCentreX((short) 0x1DC0);
        camera.updatePosition();
        assertEquals(0x1D00, camera.getX() & 0xFFFF,
                "the completed native lock must reject rightward camera movement");
    }

    @Test
    void firstCnzCutsceneUsesObjSlotFrameThenExactLaughRawScript() throws Exception {
        CutsceneKnucklesCnz2AInstance knuckles = initializeFirstCutsceneAtNativeLock();

        assertEquals(0x16, getPrivateIntField(knuckles, "mappingFrame"),
                "ObjSlot_CutsceneKnux initializes mapping_frame=$16 on the setup frame");

        for (int frame = 1; frame <= 8; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1F, getPrivateIntField(knuckles, "mappingFrame"),
                    "byte_666BF starts with frame $1F because Animate_Raw increments anim_frame before lookup");
        }
        for (int frame = 9; frame <= 16; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1E, getPrivateIntField(knuckles, "mappingFrame"),
                    "byte_666BF command $FC restarts on frame $1E with delay 7");
        }
        knuckles.update(17, null);
        assertEquals(0x1F, getPrivateIntField(knuckles, "mappingFrame"));
    }

    @Test
    void firstCnzCutsceneJumpUsesExactByte666AfFrameTiming() throws Exception {
        CutsceneKnucklesCnz2AInstance knuckles = initializeFirstCutsceneAtNativeLock();
        setPrivateEnumField(knuckles, "phase", "MULTI_BOUNCE");
        invokePrivateStartJump(knuckles, 0x0140, -0x0600);

        int[] expected = {4, 4, 8, 8, 5, 5, 8, 8, 6, 6, 8, 8, 7, 7, 8, 8};
        for (int frame = 0; frame < expected.length; frame++) {
            knuckles.update(frame + 1, null);
            assertEquals(expected[frame], getPrivateIntField(knuckles, "mappingFrame"),
                    "byte_666AF is delay 1 with frames $08,$04,$08,$05,$08,$06,$08,$07 and $FC restart");
        }
    }

    @Test
    void firstCnzCutsceneLandingScriptJumpsIntoLaughLoop() throws Exception {
        CutsceneKnucklesCnz2AInstance knuckles = initializeFirstCutsceneAtNativeLock();
        setPrivateIntField(knuckles, "bounceIndex", 2);
        invokePrivateFloorContact(knuckles, -1);

        assertEquals(0x1C, getPrivateIntField(knuckles, "mappingFrame"),
                "loc_62056 publishes frame $1C on the landing frame");
        for (int frame = 1; frame <= 8; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1C, getPrivateIntField(knuckles, "mappingFrame"));
        }
        for (int frame = 9; frame <= 16; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1D, getPrivateIntField(knuckles, "mappingFrame"));
        }
        for (int frame = 17; frame <= 24; frame++) {
            knuckles.update(frame, null);
            assertEquals(0x1E, getPrivateIntField(knuckles, "mappingFrame"),
                    "byte_666B9 command $F8,+6 jumps into byte_666BF at frame $1E");
        }
        knuckles.update(25, null);
        assertEquals(0x1F, getPrivateIntField(knuckles, "mappingFrame"));
    }

    @Test
    void firstCnzCutsceneReachesRomLandingCoordinateAndActivatesButton() {
        CutsceneKnucklesCnz2AInstance knuckles = initializeFirstCutsceneAtNativeLock();
        Cnz2CutsceneButtonInstance button = new Cnz2CutsceneButtonInstance(
                new ObjectSpawn(0x1E00, 0x0338,
                        Sonic3kObjectIds.CUTSCENE_BUTTON, 4, 0, false, 0));
        button.setServices(TestEnvironment.objectServices());

        boolean reachedLanding = false;
        for (int frame = 1; frame <= 500; frame++) {
            knuckles.update(frame, null);
            button.update(frame, null);
            if (knuckles.getRoutine() == 8) {
                reachedLanding = true;
                break;
            }
        }

        assertTrue(reachedLanding, "CutsceneKnux_CNZ2A must complete its three floor contacts");
        assertTrue(button.isPressedForTest(),
                "the $1E00/$033C button must activate during Knuckles' physical jump path");
        assertEquals(0x1E27, knuckles.getX(),
                "ROM aux trace enters loc_62056/routine 8 at x_pos=$1E27 (frame 21921)");
        assertEquals(0x032C, knuckles.getY(),
                "ROM aux trace enters loc_62056/routine 8 at y_pos=$032C (frame 21921)");
    }

    @Test
    void firstCnzCutsceneSpawnsBlockingWallAtRomChildOffset() {
        GameServices.camera().setX((short) 0x1D00);
        GameServices.camera().setY((short) 0x0280);
        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());

        knuckles.update(0, null);

        CutsceneKnuxCnz2WallInstance wall = knuckles.getSpawnedWallForTest();
        assertNotNull(wall,
                "CutsceneKnux_CNZ2A init creates ChildObjDat_66560 -> loc_62458, the invisible "
                        + "SolidObjectFull2 wall that blocks Sonic (docs/skdisasm/sonic3k.asm:129076,129175,134968)");
        assertEquals(0x1D00 - 0x20, wall.getX(),
                "CreateChild1_Normal applies the ChildObjDat x offset -$20 (sonic3k.asm:134971,176931-176936)");
        assertEquals(0x0280 - 0x6C, wall.getY(),
                "CreateChild1_Normal applies the ChildObjDat y offset -$6C (sonic3k.asm:134971,176937-176942)");
    }

    @Test
    void firstCnzCutsceneUsesNativeRenderBoundsForFinalJumpDeletion() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        Camera camera = GameServices.camera();
        camera.setX((short) 0x1D00);
        camera.setY((short) 0x0280);
        AbstractObjectInstance.updateCameraBounds(0x1D00, 0x0280, 0x1E40, 0x0360, 0);

        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());
        setPrivateEnumField(knuckles, "phase", "FINAL_JUMP");
        setPrivateIntField(knuckles, "currentX", 0x1E40 + 0x1C);
        setPrivateIntField(knuckles, "currentY", 0x02A0);
        knuckles.snapshotPreUpdatePosition();

        knuckles.update(0, null);

        assertEquals(0x1C, knuckles.getOnScreenHalfWidth(),
                "ObjSlot_CutsceneKnux width_pixels is $1C");
        assertEquals(0x18, knuckles.getOnScreenHalfHeight(),
                "ObjSlot_CutsceneKnux height_pixels is $18");
        assertTrue(knuckles.isDestroyed(),
                "loc_623FE observes the prior Draw_Sprite render flag; the right edge is exclusive");
    }

    @Test
    void secondCnzCutsceneExitDoesNotEnterPlayableKnucklesTeleporterRoute() throws Exception {
        RecordingCnzBridge bridge = new RecordingCnzBridge();
        Camera camera = new Camera();
        camera.setX((short) 0x45C0);
        camera.setY((short) 0x0720);
        camera.setMinX((short) 0x4300);
        camera.setMaxX((short) 0x5000);
        AbstractObjectInstance.updateCameraBounds(0x4700, 0x0600, 0x4840, 0x06E0, 0);

        CutsceneKnucklesCnz2BInstance knuckles = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x45C0, 0x0720, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0));
        knuckles.setServices(new TestObjectServices() {
            @Override
            public com.openggf.game.LevelEventProvider levelEventProvider() {
                return bridge;
            }
        }.withCamera(camera));
        setPrivateEnumField(knuckles, "phase", "EXIT_RIGHT");
        setPrivateIntField(knuckles, "currentX", 0x4900);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4760, (short) 0x0700);
        player.setObjectControlled(true);
        player.setControlLocked(true);

        knuckles.update(0, player);
        knuckles.update(1, player);

        assertEquals(0x1C, knuckles.getOnScreenHalfWidth());
        assertEquals(0x18, knuckles.getOnScreenHalfHeight());
        assertEquals(0, bridge.beginTeleporterRouteCalls,
                "CutsceneKnux_CNZ2B loc_625E2 restores Sonic/Tails control and level music; "
                        + "the $4750-$48E0 teleporter clamp belongs only to CNZ2_ScreenEvent after Player_mode==3");
        assertEquals(0x4300, camera.getMinX() & 0xFFFF,
                "The Sonic/Tails rival-Knuckles handoff must leave the current camera min X alone");
        assertEquals(0x5000, camera.getMaxX() & 0xFFFF,
                "The Sonic/Tails rival-Knuckles handoff must not install the playable-Knuckles teleporter max X");
        assertFalse(player.isObjectControlled(),
                "loc_625E2 clears Player_1 object_control before loc_6261A starts forcing left");
        assertTrue(player.isControlLocked(),
                "Ctrl_1_locked remains set continuously between loc_625E2 and loc_6261A");
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, player.getForcedInputMask(),
                "loc_6261A writes left into Ctrl_1_logical so Sonic/Tails walks into the vacuum tube");
    }

    @Test
    void cnzCutscenesSkipDispatchOutsideNativeWindowButOnlyDeleteAtNativeXRange() {
        Camera camera = new Camera();
        camera.setX((short) 0x1BFF);
        camera.setY((short) 0x0280);
        CutsceneKnucklesCnz2AInstance first = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        first.setServices(new TestObjectServices().withCamera(camera));

        first.update(0, null);

        assertFalse(first.isDestroyed(),
                "Check_CameraInRange skips dispatch, but Delete_Sprite_If_Not_In_Range returns while x is within $280");
        assertEquals(0, first.getRoutine());

        camera.setX((short) 0x45C0);
        camera.setY((short) 0x071F);
        CutsceneKnucklesCnz2BInstance second = new CutsceneKnucklesCnz2BInstance(
                new ObjectSpawn(0x45C0, 0x0720, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0));
        second.setServices(new TestObjectServices().withCamera(camera));

        second.update(0, null);

        assertFalse(second.isDestroyed());
        assertEquals(0, second.getRoutine());

        camera.setX((short) 0x4000);
        first.update(1, null);

        assertTrue(first.isDestroyed());
        assertTrue(first.isDestroyedRespawnable(),
                "a native-window miss deletes respawnably only when the object's rounded X is beyond $280");
    }

    @Test
    void activeCutsceneAlsoSkipsRoutineDispatchWhenCameraLeavesNativeWindow() throws Exception {
        Camera camera = new Camera();
        camera.setX((short) 0x1BFF);
        camera.setY((short) 0x0280);
        CutsceneKnucklesCnz2AInstance first = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        first.setServices(new TestObjectServices().withCamera(camera));
        setPrivateEnumField(first, "phase", "PRE_JUMP_WAIT");
        setPrivateIntField(first, "timer", 7);

        first.update(0, null);

        assertFalse(first.isDestroyed());
        assertEquals(7, getPrivateIntField(first, "timer"),
                "Check_CameraInRange rewrites the return address and skips the active routine dispatch");
    }

    @AfterEach
    void tearDown() {
        CutsceneKnucklesCnz2AInstance.clearActiveInstanceForTests();
        CutsceneKnucklesCnz2BInstance.clearActiveInstanceForTests();
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    private static void setPrivateEnumField(Object target, String fieldName, String enumConstant)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Enum<?> value = Enum.valueOf((Class<? extends Enum>) field.getType(), enumConstant);
        field.set(target, value);
    }

    private static void setPrivateIntField(Object target, String fieldName, int value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setPrivateBooleanField(Object target, String fieldName, boolean value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static boolean getPrivateBooleanField(Object target, String fieldName)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static int getPrivateIntField(Object target, String fieldName)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static CutsceneKnucklesCnz2AInstance initializeFirstCutsceneAtNativeLock() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        GameServices.camera().setX((short) 0x1D00);
        GameServices.camera().setY((short) 0x0280);
        CutsceneKnucklesCnz2AInstance knuckles = new CutsceneKnucklesCnz2AInstance(
                new ObjectSpawn(0x1DE0, 0x032C,
                        Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
        knuckles.setServices(TestEnvironment.objectServices());
        knuckles.update(0, null);
        return knuckles;
    }

    private static void invokePrivateFloorContact(CutsceneKnucklesCnz2AInstance target, int distance)
            throws ReflectiveOperationException {
        Method method = CutsceneKnucklesCnz2AInstance.class
                .getDeclaredMethod("applyFloorContact", int.class);
        method.setAccessible(true);
        method.invoke(target, distance);
    }

    private static void invokePrivateFloorContact(CutsceneKnucklesCnz2BInstance target, int distance)
            throws ReflectiveOperationException {
        Method method = CutsceneKnucklesCnz2BInstance.class
                .getDeclaredMethod("applyFloorContact", int.class);
        method.setAccessible(true);
        method.invoke(target, distance);
    }

    private static void invokePrivateStartJump(
            CutsceneKnucklesCnz2AInstance target, int xVelocity, int yVelocity)
            throws ReflectiveOperationException {
        Method method = CutsceneKnucklesCnz2AInstance.class
                .getDeclaredMethod("startJump", int.class, int.class);
        method.setAccessible(true);
        method.invoke(target, xVelocity, yVelocity);
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

    private static final class RecordingCnzBridge implements LevelEventProvider, CnzObjectEventBridge {
        private boolean waterButtonArmed;
        private int waterTargetY;
        private int waterMeanLevel;
        private int screenShakeFrames;
        private int beginTeleporterRouteCalls;

        @Override public void initLevel(int zone, int act) {}
        @Override public void update() {}
        @Override public void setPendingArenaChunkDestruction(int chunkWorldX, int chunkWorldY) {}
        @Override public void setBossScrollState(int offsetY, int velocityY) {}
        @Override public void signalMinibossDefeatedForScrollControl() {}
        @Override public boolean consumeMinibossDefeatSignalForScrollControl() { return false; }
        @Override public void advanceMinibossBackgroundRoutineAfterScrollSnap() {}
        @Override public void setBossFlag(boolean value) {}
        @Override public void setEventsFg5(boolean value) {}
        @Override public void setWallGrabSuppressed(boolean value) {}
        @Override public void setWaterButtonArmed(boolean value) { waterButtonArmed = value; }
        @Override public boolean isWaterButtonArmed() { return waterButtonArmed; }
        @Override public void setWaterTargetY(int targetY) { waterTargetY = targetY; }
        @Override public void setWaterMeanLevel(int meanY) { waterMeanLevel = meanY; }
        @Override public void triggerScreenShake(int frames) { screenShakeFrames = frames; }
        @Override public void beginKnucklesTeleporterRoute() { beginTeleporterRouteCalls++; }
        @Override public void endKnucklesTeleporterRoute() {}
        @Override public void markTeleporterBeamSpawned() {}
    }
}
