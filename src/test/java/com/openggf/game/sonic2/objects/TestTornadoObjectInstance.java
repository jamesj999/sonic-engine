package com.openggf.game.sonic2.objects;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.save.SaveReason;
import com.openggf.game.mutation.DirectLevelMutationSurface;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.session.SessionManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Level;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.physics.Sensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public class TestTornadoObjectInstance {

    private GameModule previousModule;

    @Test
    void sczTornadoDoesNotSuppressFreshLogicalHorizontalInput() {
        TornadoObjectInstance tornado = createTornado(0x700, 0x100, 0x50);

        assertEquals(0, tornado.staleHorizontalLogicalInputFramesWhileRiding(null, 121),
                "Obj01_MdNormal_Checks must see the fresh logical direction and enter Blink");
    }

    @BeforeEach
    public void setUp() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        previousModule = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.openGameplaySession(GameModuleRegistry.getCurrent());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        GameServices.sprites().resetState();
        GameServices.level().resetState();
        AizPlaneIntroInstance.resetIntroPhaseState();
    }

    @AfterEach
    public void tearDown() {
        GameServices.sprites().resetState();
        GameServices.level().resetState();
        GameModuleRegistry.setCurrent(previousModule);
        SessionManager.clear();
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    public void moveObeyPlayerClampsPlayerToTornado() throws Exception {
        TornadoObjectInstance tornado = createTornado(100, 0x100, 0x50);
        assertEquals(0x60, tornado.getBalanceWidthPixels(),
                "ObjB2 balancing uses its native width_pixels, not its smaller SolidObject width");
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 200, (short) 100);

        invokePrivate(tornado, "moveObeyPlayer",
                new Class<?>[]{AbstractPlayableSprite.class, boolean.class}, main, true);

        // ROM: move.w x_pos(a1),d1 / add.w d3,d1 / move.w d1,x_pos(a0)
        // Moves TORNADO to follow PLAYER. Player at 200, tornado follows to 200-16=184.
        assertEquals(184, tornado.getX(), "ObjB2_Move_obbey_player should move Tornado to player - 16");
        assertEquals(200, main.getCentreX(), "Player should not be moved");
    }

    @Test
    public void moveWithPlayerTransitionUsesClosestPlayerAsAnchor() throws Exception {
        TornadoObjectInstance tornado = createTornado(150, 0x100, 0x50);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 300, (short) 100);
        TestPlayableSprite sidekick = new TestPlayableSprite("sidekick", (short) 140, (short) 100);
        sidekick.setCpuControlled(true);

        GameServices.sprites().addSprite(main);
        GameServices.sprites().addSprite(sidekick);
        setField(tornado, "standingTransition", true);

        invokePrivate(tornado, "moveWithPlayer",
                new Class<?>[]{AbstractPlayableSprite.class, boolean.class}, main, false);

        assertEquals(149, tornado.getX(), "ObjB2_Move_below_player should anchor to closest player on transition");
    }

    @Test
    public void moveWithPlayerTransitionUsesClosestPlayerInScz() throws Exception {
        // SCZ suppression reaches Tornado as an empty sidekick list, so this unit
        // test exercises the fallback path directly instead of depending on
        // runtime-global SpriteManager suppression state.
        TornadoObjectInstance tornado = createTornado(150, 0x100, 0x50);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 300, (short) 100);
        TestPlayableSprite sidekick = new TestPlayableSprite("sidekick", (short) 140, (short) 100);
        sidekick.setCpuControlled(true);

        tornado.setServices(new TestObjectServices()
                .withCamera(GameServices.camera())
                .withParallaxManager(GameServices.parallax())
                .withSidekicks(List.of()));
        setField(GameServices.level(), "currentZone", 8);
        setField(tornado, "standingTransition", true);

        invokePrivate(tornado, "moveWithPlayer",
                new Class<?>[]{AbstractPlayableSprite.class, boolean.class}, main, false);

        assertEquals(151, tornado.getX(), "SCZ Tornado anchors to main when sidekick is suppressed");
    }

    @Test
    public void moveWithPlayerTransitionUsesQueryOnlyClosestPlayerAsAnchor() throws Exception {
        TornadoObjectInstance tornado = createTornado(150, 0x100, 0x50);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 300, (short) 100);
        TestPlayableSprite sidekick = new TestPlayableSprite("sidekick", (short) 140, (short) 100);
        sidekick.setCpuControlled(true);

        tornado.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));
        setField(tornado, "standingTransition", true);

        invokePrivate(tornado, "moveWithPlayer",
                new Class<?>[]{AbstractPlayableSprite.class, boolean.class}, main, false);

        assertEquals(149, tornado.getX(),
                "Tornado closest-player orientation should resolve participants through ObjectPlayerQuery");
    }

    @Test
    public void wfzStartReleaseDropsQueryOnlyRidingSidekick() throws Exception {
        TornadoObjectInstance tornado = createTornado(100, 0x100, 0x52);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 100, (short) 100);
        TestPlayableSprite sidekick = new TestPlayableSprite("sidekick", (short) 100, (short) 100);
        sidekick.setCpuControlled(true);
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, null, new TestObjectServices());
        tornado.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)).withObjectManager(objectManager));
        objectManager.forceRidingObjectForBootstrap(sidekick, tornado);
        sidekick.setOnObject(true);
        sidekick.setAir(false);
        setField(tornado, "routineSecondary", 4);
        setField(tornado, "scriptTimer", 0);

        tornado.update(0, main);

        assertFalse(objectManager.isRidingObject(sidekick, tornado),
                "WFZ start release should use ObjectPlayerQuery participants instead of raw sidekicks");
        assertFalse(sidekick.isOnObject());
        assertTrue(sidekick.getAir());
    }

    @Test
    public void wfzStartShotDownPlaysScatterSfxNotRingLoss() throws Exception {
        // ObjB2_Main_WFZ_Start_shot_down plays SndID_Scatter ($EB) every $20 frames as
        // the Tornado is gunned down, NOT the ring-loss/RingSpill sound ($C6).
        // Ref: docs/s2disasm/s2.asm:78890-78895 (moveq #signextendB(SndID_Scatter),d0).
        SfxRecordingServices services = new SfxRecordingServices();
        TornadoObjectInstance tornado = createTornado(0x700, 0x100, 0x52, services);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x700, (short) 0x100);

        setField(tornado, "routineSecondary", 4);
        setField(tornado, "scriptTimer", 0x60);

        // frameCounter 0 satisfies (frameCounter & $1F) == 0, so the scatter SFX fires.
        tornado.update(0, main);

        assertTrue(services.playedSfx.contains(Sonic2Sfx.LASER_FLOOR.id),
                "Shot-down Tornado should play SndID_Scatter ($EB)");
        assertFalse(services.playedSfx.contains(Sonic2Sfx.RING_SPILL.id),
                "Shot-down Tornado must not play the ring-loss sound ($C6)");
    }

    @Test
    public void wfzPrepareToJumpDefersScriptedJumpInputUntilJumpRoutine() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x2E58, 0x066C, 0x54);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2E31, (short) 0x05EC);

        setField(tornado, "routineSecondary", 6);
        setField(tornado, "scriptTimer", 0x2F);

        tornado.update(0, main);

        assertEquals(8, getField(tornado, "routineSecondary"));
        assertEquals(0x38, getField(tornado, "jumpTimer"));
        assertEquals(0x0038, snapshotObjoff2EWord(tornado),
                "ObjB2_Prepare_to_jump installs the normal $38 word at objoff_2E");
        assertEquals(0, main.getForcedInputMask());
        assertTrue(main.isControlLocked());
    }

    @Test
    public void wfzPrepareToJumpPublishesSuperJumpCountdownAtObjoff2E() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x2E58, 0x066C, 0x54);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2E31, (short) 0x05EC);
        main.setSuperSonic(true);

        setField(tornado, "routineSecondary", 6);
        setField(tornado, "scriptTimer", 0x2F);

        invokePrivate(tornado, "wfzPrepareToJump",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(8, getField(tornado, "routineSecondary"));
        assertEquals(0x0028, snapshotObjoff2EWord(tornado),
                "Super_Sonic_flag selects the ROM's $28 jump countdown");
    }

    @Test
    public void wfzJumpToPlaneKeepsScriptedInputOnFinalTimerFrame() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x2EC1, 0x0603, 0x54);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2EA4, (short) 0x05D0);

        setField(tornado, "routineSecondary", 8);
        setField(tornado, "jumpTimer", 0);

        tornado.update(0, main);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                main.getForcedInputMask());
        assertEquals(-1, getField(tornado, "jumpTimer"));
        assertEquals(0xFFFF, snapshotObjoff2EWord(tornado),
                "subq.w wraps ObjB2's zero countdown to $FFFF");

        tornado.update(1, main);

        assertEquals(-2, getField(tornado, "jumpTimer"));
        assertEquals(0xFFFE, snapshotObjoff2EWord(tornado),
                "the Java int remains published with ROM word-width decrement semantics");
        assertTrue(main.isControlLocked());
    }

    @Test
    public void wfzJumpToPlaneChecksLandingBeforeAdvancingPlane() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x2EC4, 0x0600, 0x54);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2EB5, (short) 0x05E4);
        main.setAir(true);
        main.setRolling(true);

        setField(tornado, "routineSecondary", 8);
        setField(tornado, "jumpTimer", -1);
        setField(tornado, "xVel", 0x100);
        setField(tornado, "yVel", -0x100);

        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        registry.beginFrame(1, List.of(main));
        registry.beginObject(tornado, () -> {
            assertEquals(0x2EC4, tornado.getX(),
                    "ObjB2 Jump_to_plane should test the landing contact before advancing x_pos for the next frame");
            assertEquals(0x0600, tornado.getY(),
                    "ObjB2 Jump_to_plane should test the landing contact before advancing y_pos for the next frame");
            return new SolidCheckpointBatch(tornado, Map.of(
                    main, new PlayerSolidContactResult(
                            ContactKind.TOP,
                            true,
                            false,
                            false,
                            false,
                            PreContactState.ZERO,
                            PostContactState.ZERO,
                            0)));
        });
        tornado.setServices(new CheckpointServices(registry.currentObject()));

        invokePrivate(tornado, "wfzJumpToPlane",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x0A, getField(tornado, "routineSecondary"));
        assertEquals(0x20, getField(tornado, "jumpTimer"));
        assertEquals(0x0020, snapshotObjoff2EWord(tornado),
                "ObjB2_Jump_to_plane resets objoff_2E to $20 on landing");
        assertEquals(0x2EC5, tornado.getX(),
                "ObjB2_Align_plane still advances the plane after the landing check");
        assertEquals(0x05FF, tornado.getY(),
                "ObjB2_Align_plane still applies y_vel after the landing check");
    }

    @Test
    public void wfzLandedOnPlanePlacesPlayerBeforePlaneMotion() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x2EC5, 0x05FF, 0x54);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2EC5, (short) 0x05E3);

        setField(tornado, "routineSecondary", 0x0A);
        setField(tornado, "scriptTimer", 0x20);
        setField(tornado, "xVel", 0x100);
        setField(tornado, "yVel", -0x100);

        invokePrivate(tornado, "wfzLandedOnPlane",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x2EC5, main.getCentreX(),
                "ObjB2_Landed_on_plane writes Sonic x_pos before ObjB2_Align_plane moves the plane");
        assertEquals(0x05E3, main.getCentreY(),
                "ObjB2_Landed_on_plane writes Sonic y_pos from the pre-align plane position");
        assertEquals(0x2EC6, tornado.getX(),
                "ObjB2_Align_plane still moves the plane later in the same routine");
        assertEquals(0x05FE, tornado.getY(),
                "ObjB2_Align_plane still applies y_vel after Sonic placement");
    }

    @Test
    public void wfzJumpToPlanePatchDecodesInterleavedLayoutOffsetsIntoBackgroundLayer() throws Exception {
        com.openggf.level.Map map = new com.openggf.level.Map(2, 128, 13);
        int[][] starts = {{82, 0}, {82, 1}, {86, 11}, {86, 12}};
        int[][] expected = {
                {0x50, 0x1F, 0x00, 0x25},
                {0x25, 0x00, 0x1F, 0x50},
                {0x50, 0x1F, 0x00, 0x25},
                {0x25, 0x00, 0x1F, 0x50}
        };
        for (int[] start : starts) {
            for (int x = start[0]; x < start[0] + 4; x++) {
                map.setValue(0, x, start[1], (byte) 0x6A);
            }
        }

        Level level = mock(Level.class);
        when(level.getMap()).thenReturn(map);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getCurrentLevel()).thenReturn(level);
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        TestObjectServices services = new TestObjectServices()
                .withLevelManager(levelManager)
                .withZoneLayoutMutationPipeline(pipeline);
        TornadoObjectInstance tornado = createTornado(0x2EC4, 0x0600, 0x54, services);

        invokePrivate(tornado, "applyJumpToPlaneLayoutPatch", new Class<?>[0]);
        List<MutationEffects> effects = new ArrayList<>();
        pipeline.flush(new LayoutMutationContext(new DirectLevelMutationSurface(level), effects::add));

        for (int i = 0; i < starts.length; i++) {
            int startX = starts[i][0];
            int y = starts[i][1];
            for (int j = 0; j < expected[i].length; j++) {
                assertEquals(expected[i][j], map.getValue(1, startX + j, y) & 0xFF,
                        "Level_Layout patch byte should target the interleaved background half-row");
                assertEquals(0x6A, map.getValue(0, startX + j, y) & 0xFF,
                        "ObjB2 layout patch must leave the matching foreground cell unchanged");
            }
        }
        assertEquals(1, effects.size());
        assertSame(MutationEffects.NONE, effects.get(0),
                "ROM Level_Layout writes must not invalidate the retained Plane-B nametable");
    }

    @Test
    public void wfzApproachingShipKeepsPlayerOnPreDockPlanePosition() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2F58, (short) 0x0550);
        TornadoObjectInstance tornado = createTornado(
                0x2F58,
                0x056C,
                0x54,
                new QueryOnlyPlayerServices(main, List.of()));
        GameServices.camera().setFocusedSprite(main);

        setField(tornado, "routineSecondary", 0x0C);
        setField(tornado, "scriptTimer", 0x100);
        setField(tornado, "xVel", 0x100);
        setField(tornado, "yVel", -0x100);

        invokePrivate(tornado, "wfzApproachingShip",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x2F58, main.getCentreX(),
                "Early ObjB2_Approaching_ship keeps Sonic on the plane position from before dock motion");
        assertEquals(0x0550, main.getCentreY(),
                "Early ObjB2_Approaching_ship keeps Sonic y_pos from before dock motion");
        assertEquals(0x2F59, tornado.getX(),
                "ObjB2_Dock_on_DEZ still advances the plane after keeping Sonic attached");
        assertEquals(0x056B, tornado.getY(),
                "ObjB2_Dock_on_DEZ still applies y_vel after keeping Sonic attached");
    }

    @Test
    public void wfzJumpToShipDefersScriptedJumpInputOnFirstCounterFrame() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x311F, (short) 0x0439);
        TornadoObjectInstance tornado = createTornado(
                0x311F,
                0x0455,
                0x54,
                new QueryOnlyPlayerServices(main, List.of()));
        GameServices.camera().setFocusedSprite(main);

        setField(tornado, "routineSecondary", 0x0E);
        setField(tornado, "scriptTimer", 0x437);
        setField(tornado, "xVel", -0x100);
        setField(tornado, "yVel", -0x100);
        setField(tornado, "dockVelocityIndex", 12);

        invokePrivate(tornado, "wfzJumpToShip",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0, main.getForcedInputMask(),
                "ObjB2_Jump_to_ship writes Ctrl_1_Logical after Sonic's player step, so the engine must not latch jump on the first counter frame");
        assertEquals(0x311F, main.getCentreX());
        assertEquals(0x0439, main.getCentreY());
        assertEquals(0x438, getField(tornado, "scriptTimer"));
    }

    @Test
    public void wfzJumpToShipKeepsPlayerAnimationAfterLeavingPlane() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x311F, (short) 0x0439);
        main.setAnimationId(Sonic2AnimationIds.ROLL);
        main.setMappingFrame(0x25);
        TornadoObjectInstance tornado = createTornado(
                0x311E,
                0x0454,
                0x54,
                new QueryOnlyPlayerServices(main, List.of()));
        GameServices.camera().setFocusedSprite(main);

        setField(tornado, "routineSecondary", 0x0E);
        setField(tornado, "scriptTimer", 0x439);
        setField(tornado, "xVel", -0x100);
        setField(tornado, "yVel", -0x100);
        setField(tornado, "dockVelocityIndex", 12);

        invokePrivate(tornado, "wfzJumpToShip",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(AbstractPlayableSprite.INPUT_JUMP, main.getForcedInputMask());
        assertEquals(Sonic2AnimationIds.ROLL.id(), main.getAnimationId(),
                "ObjB2_Jump_to_ship does not reapply the earlier approaching-state Wait tuple");
        assertEquals(0x25, main.getMappingFrame());
    }

    @Test
    public void wfzJumpToShipKeepsPlayerAnimationAtJumpWindowEnd() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x3110, (short) 0x0420);
        main.setAnimationId(Sonic2AnimationIds.HANG);
        main.setMappingFrame(0x3B);
        TornadoObjectInstance tornado = createTornado(
                0x3100,
                0x0440,
                0x54,
                new QueryOnlyPlayerServices(main, List.of()));
        GameServices.camera().setFocusedSprite(main);

        setField(tornado, "routineSecondary", 0x0E);
        setField(tornado, "scriptTimer", 0x447);
        setField(tornado, "xVel", -0x100);
        setField(tornado, "yVel", -0x100);
        setField(tornado, "dockVelocityIndex", 12);

        invokePrivate(tornado, "wfzJumpToShip",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0, main.getForcedInputMask());
        assertEquals(Sonic2AnimationIds.HANG.id(), main.getAnimationId(),
                "ObjB2_Jump_to_ship leaves the player-owned animation byte live when its jump window closes");
        assertEquals(0x3B, main.getMappingFrame());
    }

    @Test
    public void wfzInvisibleGrabberDoesNotRestartLiveHangScript() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x3118, (short) 0x03F0);
        main.setAnimationId(Sonic2AnimationIds.HANG);
        main.setAnimationFrameIndex(1);
        main.setAnimationTick(1);
        TornadoObjectInstance grabber = createTornado(0x3118, 0x03F0, 0x56);

        setField(grabber, "routineSecondary", 2);
        invokePrivate(grabber, "catchPlayerWithInvisibleGrabber",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(Sonic2AnimationIds.HANG.id(), main.getAnimationId());
        assertEquals(1, main.getAnimationFrameIndex(),
                "loc_3AC84 writes only anim=Hang and leaves anim_frame live");
        assertEquals(1, main.getAnimationTick(),
                "loc_3AC84 leaves anim_frame_duration live on the second catch");
    }

    @Test
    public void wfzJumpToShipKeepsPlayerOnPlaneWhileLatchingNextFrameJump() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x311F, (short) 0x0439);
        TornadoObjectInstance tornado = createTornado(
                0x311E,
                0x0454,
                0x54,
                new QueryOnlyPlayerServices(main, List.of()));
        GameServices.camera().setFocusedSprite(main);

        setField(tornado, "routineSecondary", 0x0E);
        setField(tornado, "scriptTimer", 0x438);
        setField(tornado, "xVel", -0x100);
        setField(tornado, "yVel", -0x100);
        setField(tornado, "dockVelocityIndex", 12);

        invokePrivate(tornado, "wfzJumpToShip",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(AbstractPlayableSprite.INPUT_JUMP, main.getForcedInputMask(),
                "The delayed ObjB2_Jump_to_ship latch should feed the following player step");
        assertEquals(0x311E, main.getCentreX(),
                "Sonic remains seated on the pre-dock plane position for the latch frame");
        assertEquals(0x0438, main.getCentreY(),
                "Sonic y_pos is still ObjB2 y_pos-$1C before the delayed jump is consumed");
        assertEquals(0x311D, tornado.getX(),
                "ObjB2_Dock_on_DEZ still advances the plane after seating Sonic");
        assertEquals(0x0453, tornado.getY(),
                "ObjB2_Dock_on_DEZ still applies y_vel after seating Sonic");
        assertEquals(0x439, getField(tornado, "scriptTimer"));
    }

    @Test
    public void tornadoSczMainReadsStandingStateFromManualCheckpointBeforeFollowMotion() throws Exception {
        TornadoObjectInstance tornado = new TornadoObjectInstance(new ObjectSpawn(
                100, 0x100, Sonic2ObjectIds.TORNADO, 0x50, 0, false, 0));
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 100, (short) 100);
        main.setAir(false);

        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        registry.beginFrame(1, List.of(main));
        registry.beginObject(tornado, () -> new SolidCheckpointBatch(tornado, Map.of(
                main, new PlayerSolidContactResult(
                        ContactKind.TOP,
                        true,
                        false,
                        false,
                        false,
                        PreContactState.ZERO,
                        PostContactState.ZERO,
                        0))));

        tornado.setServices(new CheckpointServices(registry.currentObject()));
        invokePrivate(tornado, "updateSczMain",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertTrue((boolean) getField(tornado, "lastMainStanding"),
                "SCZ Tornado should use the current-frame checkpoint standing state");
        assertTrue((boolean) getField(tornado, "standingTransition"),
                "SCZ Tornado should detect the same-frame standing transition before follow motion");
    }

    @Test
    public void tornadoWfzStartPublishesStandingBitFromManualCheckpoint() throws Exception {
        TornadoObjectInstance tornado = new TornadoObjectInstance(new ObjectSpawn(
                0x80, 0x4E4, Sonic2ObjectIds.TORNADO, 0x52, 0, false, 0));
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x80, (short) 0x4C8);
        main.setAir(false);

        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        registry.beginFrame(1, List.of(main));
        registry.beginObject(tornado, () -> new SolidCheckpointBatch(tornado, Map.of(
                main, new PlayerSolidContactResult(
                        ContactKind.TOP,
                        true,
                        false,
                        false,
                        false,
                        PreContactState.ZERO,
                        PostContactState.ZERO,
                        0))));

        tornado.setServices(new CheckpointServices(registry.currentObject()));
        setField(tornado, "routineSecondary", 2);
        setField(tornado, "scriptTimer", 2);

        invokePrivate(tornado, "updateWfzStart",
                new Class<?>[]{int.class, AbstractPlayableSprite.class}, 1, main);

        assertEquals(0x08, tornado.snapshot().statusByte(),
                "ObjB2 status must retain SolidObject's p1_standing bit after the WFZ checkpoint");
    }

    @Test
    public void tornadoInitRoutinePublicationSurvivesRewindRecreation() throws Exception {
        ObjectSpawn spawn = new ObjectSpawn(
                0x2C60, 0x05EC, Sonic2ObjectIds.TORNADO, 0x54, 0, false, 0);
        TestObjectServices services = new TestObjectServices()
                .withConfiguration(SonicConfigurationService.getInstance());
        TornadoObjectInstance tornado = new TornadoObjectInstance(spawn);
        tornado.setServices(services);

        assertEquals(0, tornado.snapshot().routine(),
                "A freshly allocated ObjB2 SST slot must expose routine 0 until ObjB2_Init executes");

        tornado.update(0, new TestPlayableSprite("main", (short) 0x2C00, (short) 0x05EC));

        assertEquals(6, tornado.snapshot().routine(),
                "ObjB2_Init publishes subtype $54 as the WFZ-end routine 6");

        PerObjectRewindSnapshot rewindState = tornado.captureRewindState();
        TornadoObjectInstance recreated = tornado.recreateForRewind(
                new RewindRecreateContext(spawn, rewindState, services));
        recreated.setServices(services);
        assertTrue((boolean) getField(recreated, "initRoutinePending"),
                "recreation starts from the constructor's routine-0 state before restore");

        recreated.restoreRewindState(rewindState);

        assertFalse((boolean) getField(recreated, "initRoutinePending"),
                "rewind restore must recover the captured post-init publication state");
        assertEquals(6, recreated.snapshot().routine(),
                "restored ObjB2 must not regress to routine 0 after recreation");
    }

    @Test
    public void tornadoWfzEndSnapshotPublishesLeaderWaitWordAtObjoff2E() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x2C60, 0x05EC, 0x54);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2C00, (short) 0x05EC);

        invokePrivate(tornado, "wfzWaitLeaderPosition",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0, tornado.snapshot().objoff2E(),
                "ObjB2_Wait_Leader_position increments the big-endian word at objoff_2E");
        assertEquals(1, tornado.snapshot().objoff2F(),
                "The first increment must appear in the low byte at objoff_2F");
    }

    @Test
    public void tornadoWfzEndRetainsCompletedLeaderWaitWordUntilJumpState() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x2C60, 0x05EC, 0x54);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x2C00, (short) 0x05EC);
        setField(tornado, "leaderWaitCounter", 0x3F);

        invokePrivate(tornado, "wfzWaitLeaderPosition",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(2, getField(tornado, "routineSecondary"));
        assertEquals(0x0040, snapshotObjoff2EWord(tornado),
                "ObjB2_Wait_Leader_position retains the completed $40 word in state 2");

        for (int state : new int[]{4, 6}) {
            setField(tornado, "routineSecondary", state);
            assertEquals(0x0040, snapshotObjoff2EWord(tornado),
                    "pre-jump state " + state + " must retain ObjB2's $40 word");
        }
    }

    @Test
    public void tornadoSczMainPreservesEntryStandingBitForReleaseFrameBob() throws Exception {
        TornadoObjectInstance tornado = new TornadoObjectInstance(new ObjectSpawn(
                0x452, 0x97, Sonic2ObjectIds.TORNADO, 0x50, 0, false, 0));
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x452, (short) 0x70);
        main.setOnObject(false);
        main.setAir(true);

        setField(tornado, "xPosFixed8", 0x45200);
        setField(tornado, "yPosFixed8", 0x9740);
        setField(tornado, "yVel", 0x120);
        setField(tornado, "moveVertActive", true);
        setField(tornado, "moveVertTimer", 0x0D);
        setField(tornado, "lastMainStanding", true);

        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        registry.beginFrame(1, List.of(main));
        registry.beginObject(tornado, () -> new SolidCheckpointBatch(tornado, Map.of(
                main, new PlayerSolidContactResult(
                        ContactKind.NONE,
                        false,
                        false,
                        false,
                        false,
                        PreContactState.ZERO,
                        PostContactState.ZERO,
                        0))));

        tornado.setServices(new CheckpointServices(registry.currentObject()));
        invokePrivate(tornado, "updateSczMain",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x9A, tornado.getY(),
                "Release frame should run ObjB2_Move_vert before SolidObject clears standing");
        assertFalse((boolean) getField(tornado, "lastMainStanding"));
        assertTrue((boolean) getField(tornado, "moveVert2Active"));
    }

    @Test
    public void tornadoSczMainUsesLivePlayerOnObjectBitForMoveWithPlayer() throws Exception {
        TornadoObjectInstance tornado = new TornadoObjectInstance(new ObjectSpawn(
                0x739, 0x8F, Sonic2ObjectIds.TORNADO, 0x50, 0, false, 0));
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x73A, (short) 0x63);
        main.setOnObject(true);
        main.setAir(false);

        setField(tornado, "xPosFixed8", 0x73900);
        setField(tornado, "yPosFixed8", 0x8F00);
        setField(tornado, "lastMainStanding", false);

        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        registry.beginFrame(1, List.of(main));
        registry.beginObject(tornado, () -> new SolidCheckpointBatch(tornado, Map.of(
                main, PlayerSolidContactResult.noContact(
                        PlayerStandingState.NONE,
                        PreContactState.ZERO,
                        PostContactState.ZERO))));

        tornado.setServices(new CheckpointServices(registry.currentObject()));
        invokePrivate(tornado, "updateSczMain",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x739, tornado.getX(),
                "ObjB2_Move_with_player branches on Sonic's live Status_OnObj bit, even if ObjB2 itself is not the current standing object");
        assertFalse((boolean) getField(tornado, "moveVert2Active"),
                "ObjB2_Move_obbey_player also branches on Sonic's live Status_OnObj bit after SolidObject");
        assertFalse((boolean) getField(tornado, "lastMainStanding"),
                "The ObjB2 standing latch should still reflect only Tornado's own SolidObject result");
    }

    @Test
    public void tornadoSczMainTreatsAirborneOnObjectFlagAsReleasedAtEntry() throws Exception {
        TornadoObjectInstance tornado = new TornadoObjectInstance(new ObjectSpawn(
                0x739, 0x8F, Sonic2ObjectIds.TORNADO, 0x50, 0, false, 0));
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x73A, (short) 0x63);
        // The S2 player can transiently retain the engine's on-object marker as
        // the jump step sets air=true. The stationary release must therefore
        // take ObjB2_Move_below_player's path for this object pass
        // (s2.asm:79343-79374).
        main.setOnObject(true);
        main.setAir(true);

        setField(tornado, "xPosFixed8", 0x73900);
        setField(tornado, "yPosFixed8", 0x8F00);
        setField(tornado, "lastMainStanding", false);

        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        registry.beginFrame(1, List.of(main));
        registry.beginObject(tornado, () -> new SolidCheckpointBatch(tornado, Map.of(
                main, PlayerSolidContactResult.noContact(
                        PlayerStandingState.NONE,
                        PreContactState.ZERO,
                        PostContactState.ZERO))));

        tornado.setServices(new CheckpointServices(registry.currentObject()));
        invokePrivate(tornado, "updateSczMain",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x73A, tornado.getX(),
                "ObjB2 must follow Move_below_player for a stationary airborne release");
        assertFalse((boolean) getField(tornado, "moveVertActive"));
        assertFalse((boolean) getField(tornado, "moveVert2Active"));
    }

    @Test
    public void tornadoSczMainKeepsLiveObjectPathForMovingAirbornePlayer() throws Exception {
        TornadoObjectInstance tornado = new TornadoObjectInstance(new ObjectSpawn(
                0x740, 0x8F, Sonic2ObjectIds.TORNADO, 0x50, 0, false, 0));
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x73A, (short) 0x63);
        main.setOnObject(true);
        main.setAir(true);
        main.setXSpeed((short) 1);

        setField(tornado, "xPosFixed8", 0x74000);
        setField(tornado, "yPosFixed8", 0x8F00);
        setField(tornado, "lastMainStanding", false);

        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        registry.beginFrame(1, List.of(main));
        registry.beginObject(tornado, () -> new SolidCheckpointBatch(tornado, Map.of(
                main, PlayerSolidContactResult.noContact(
                        PlayerStandingState.NONE,
                        PreContactState.ZERO,
                        PostContactState.ZERO))));

        tornado.setServices(new CheckpointServices(registry.currentObject()));
        invokePrivate(tornado, "updateSczMain",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x740, tornado.getX(),
                "ObjB2 must retain Move_with_player for a moving airborne player");
        assertFalse((boolean) getField(tornado, "moveVertActive"));
        assertFalse((boolean) getField(tornado, "moveVert2Active"));
    }

    @Test
    public void sczCameraEdgePushPreservesPlayerXSubpixel() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x640, 0x99, 0x50);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x63E, (short) 0x3C);
        main.setSubpixelRaw(0xE900, 0x6000);
        GameServices.camera().setX((short) 0x62F);

        invokePrivate(tornado, "updateSczMain",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(0x63F, main.getCentreX(),
                "SCZ edge push should increment x_pos by one pixel");
        assertEquals(0xE900, main.getXSubpixelRaw(),
                "SCZ edge push is a ROM word write to x_pos and must preserve x_sub");
    }

    @Test
    public void unusedMoverUsesCloudVisualsAndMarkObjGoneRange() throws Exception {
        GameServices.camera().setX((short) 0);

        TornadoObjectInstance tornado = createTornado(0x700, 0x100, 0x5A);
        String artKey = (String) invokePrivate(tornado, "resolveRenderArtKey", new Class<?>[0]);

        assertEquals(Sonic2ObjectArtKeys.CLOUDS, artKey);
        assertEquals(6, tornado.getPriorityBucket());

        tornado.update(1, null);
        assertTrue(tornado.isDestroyed(), "Routine C should cull via MarkObjGone");
    }

    @Test
    public void wfzBlinkerDisplaysOnFirstActiveTickThenAlternates() throws Exception {
        TornadoObjectInstance tornado = createTornado(0x700, 0x100, 0x58, new TestObjectServices());

        assertEquals(Sonic2ObjectArtKeys.WFZ_THRUST,
                invokePrivate(tornado, "resolveRenderArtKey", new Class<?>[0]));
        assertEquals(4, tornado.getPriorityBucket());

        tornado.update(1, null);
        assertTrue((boolean) getField(tornado, "renderThisFrame"),
                "ObjB2 loc_3AD0C displays when status.npc.misc was initially clear");

        tornado.update(2, null);
        assertFalse((boolean) getField(tornado, "renderThisFrame"),
                "ObjB2 loc_3AD0C skips the next frame after bchg sets the misc bit");

        tornado.update(3, null);
        assertTrue((boolean) getField(tornado, "renderThisFrame"),
                "ObjB2 loc_3AD0C alternates back to visible on the following frame");
    }

    @Test
    public void wfzStartUsesDeleteOffScreenCulling() {
        GameServices.camera().setX((short) 0);

        TornadoObjectInstance tornado = createTornado(0x700, 0x100, 0x52);
        tornado.update(1, null);

        assertTrue(tornado.isDestroyed(), "WFZ start routine should delete when outside Obj_DeleteOffScreen range");
    }

    @Test
    public void requestZoneAndActDeactivateLevelFlagIsSet() {
        LevelManager levelManager = GameServices.level();
        levelManager.requestZoneAndAct(9, 0, true);
        assertTrue(levelManager.isLevelInactiveForTransition());

        levelManager.requestZoneAndAct(10, 0);
        assertFalse(levelManager.isLevelInactiveForTransition());
    }

    @Test
    public void sczToWfzTransitionRequestsProgressionSave() throws Exception {
        TrackingServices services = new TrackingServices();
        TornadoObjectInstance tornado = createTornado(100, 0x100, 0x50, services);
        TestPlayableSprite main = new TestPlayableSprite("main", (short) 0x35F0, (short) 100);
        GameServices.camera().setX((short) 0x35E0);

        invokePrivate(tornado, "updateSczMain",
                new Class<?>[]{AbstractPlayableSprite.class}, main);

        assertEquals(Sonic2ZoneConstants.ZONE_WFZ, services.requestedZone);
        assertEquals(0, services.requestedAct);
        assertTrue(services.requestedDeactivateLevelNow);
        assertEquals(SaveReason.PROGRESSION_SAVE, services.lastSaveReason);
    }

    @Test
    public void wfzToDezTransitionRequestsProgressionSave() throws Exception {
        TrackingServices services = new TrackingServices();
        TornadoObjectInstance tornado = createTornado(100, 0x100, 0x52, services);
        setField(tornado, "scriptTimer", 0x9C0);

        invokePrivate(tornado, "wfzDockOnDez", new Class<?>[0]);

        assertEquals(Sonic2ZoneConstants.ZONE_DEZ, services.requestedZone);
        assertEquals(0, services.requestedAct);
        assertTrue(services.requestedDeactivateLevelNow);
        assertEquals(SaveReason.PROGRESSION_SAVE, services.lastSaveReason);
    }

    @Test
    public void rideStartPreludePreservesRomVerticalTimerSentinel() {
        TornadoObjectInstance tornado = new TornadoObjectInstance(new ObjectSpawn(
                0x136, 0x9D, Sonic2ObjectIds.TORNADO, 0x50, 0, false, 0));

        tornado.primeRideStart((short) 0x135, (short) 0x81, 0xC000);

        assertEquals(0xFF, tornado.snapshot().objoff31(),
                "ObjB2's native ride-start slot retains the idle vertical timer sentinel");
    }

    private static TornadoObjectInstance createTornado(int x, int y, int subtype) {
        return createTornado(x, y, subtype, new TestObjectServices()
                .withCamera(GameServices.camera())
                .withParallaxManager(GameServices.parallax())
                .withSpriteManager(GameServices.sprites()));
    }

    private static TornadoObjectInstance createTornado(int x, int y, int subtype, TestObjectServices services) {
        ObjectSpawn spawn = new ObjectSpawn(x, y, Sonic2ObjectIds.TORNADO, subtype, 0, false, 0);
        TornadoObjectInstance t = new TornadoObjectInstance(spawn);
        if (services.configuration() == null) {
            services.withConfiguration(SonicConfigurationService.getInstance());
        }
        if (services.levelManager() == null) {
            services.withLevelManager(GameServices.level());
        }
        t.setServices(services);
        // These cases exercise the main routines, so consume the object's
        // routine-0 frame (ObjB2_Init, docs/s2disasm/s2.asm:78799-78813) that
        // ROM spends before ever reaching them.
        t.consumePendingInitRoutine();
        return t;
    }

    private static Object invokePrivate(Object target, String methodName, Class<?>[] argTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, argTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int snapshotObjoff2EWord(TornadoObjectInstance tornado) {
        TornadoObjectInstance.Snapshot snapshot = tornado.snapshot();
        return ((snapshot.objoff2E() & 0xFF) << 8) | (snapshot.objoff2F() & 0xFF);
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite(String code, short x, short y) {
            super(code, x, y);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 28;
            runHeight = 38;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
            // No-op for unit tests.
        }
    }

    private static final class SfxRecordingServices extends TestObjectServices {
        private final java.util.List<Integer> playedSfx = new java.util.ArrayList<>();

        private SfxRecordingServices() {
            withCamera(GameServices.camera());
            withParallaxManager(GameServices.parallax());
            withSpriteManager(GameServices.sprites());
            withConfiguration(SonicConfigurationService.getInstance());
            withLevelManager(GameServices.level());
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }

    private static final class TrackingServices extends TestObjectServices {
        private int requestedZone = -1;
        private int requestedAct = -1;
        private boolean requestedDeactivateLevelNow;
        private SaveReason lastSaveReason;

        private TrackingServices() {
            withCamera(GameServices.camera());
            withParallaxManager(GameServices.parallax());
            withSpriteManager(GameServices.sprites());
            withConfiguration(SonicConfigurationService.getInstance());
            withLevelManager(GameServices.level());
        }

        @Override
        public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
            requestedZone = zone;
            requestedAct = act;
            requestedDeactivateLevelNow = deactivateLevelNow;
        }

        @Override
        public void requestSessionSave(SaveReason reason) {
            lastSaveReason = reason;
        }
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private ObjectManager objectManager;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
            withCamera(GameServices.camera());
            withParallaxManager(GameServices.parallax());
            withSpriteManager(GameServices.sprites());
            withConfiguration(SonicConfigurationService.getInstance());
            withLevelManager(GameServices.level());
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        private QueryOnlyPlayerServices withObjectManager(ObjectManager objectManager) {
            this.objectManager = objectManager;
            return this;
        }
    }

    private static final class CheckpointServices extends TestObjectServices {
        private final ObjectSolidExecutionContext solidExecution;

        private CheckpointServices(ObjectSolidExecutionContext solidExecution) {
            this.solidExecution = solidExecution;
            withCamera(GameServices.camera());
            withParallaxManager(GameServices.parallax());
            withSpriteManager(GameServices.sprites());
            withConfiguration(SonicConfigurationService.getInstance());
            withLevelManager(GameServices.level());
        }

        @Override
        public ObjectSolidExecutionContext solidExecution() {
            return solidExecution;
        }
    }
}
