package com.openggf.game.sonic3k.events;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.DamageCause;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.MgzEndBossInstance;
import com.openggf.game.sonic3k.objects.MgzEndBossKnuxInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.runtime.HczZoneRuntimeState;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.Direction;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.NativePlayableRoutine;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the MGZ Act 2 end-boss arena handoff.
 *
 * <p>ROM: {@code MGZ2_Resize} (sonic3k.asm:39343-39418) and
 * {@code Obj_MGZEndBoss} setup (sonic3k.asm:142715+).
 */
class TestSonic3kMgz2EndBossEvents {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameServices.configuration().resetToDefaults();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x3A10, (short) 0x0680);
        installNoHitSensors(main);
        GameServices.sprites().addSprite(main);
        GameServices.camera().setFocusedSprite(main);
    }

    private static void installNoHitSensors(AbstractPlayableSprite player) {
        player.setGroundSensors(new Sensor[]{noHitSensor(player, Direction.DOWN), noHitSensor(player, Direction.DOWN)});
        player.setCeilingSensors(new Sensor[]{noHitSensor(player, Direction.UP), noHitSensor(player, Direction.UP)});
        player.setPushSensors(new Sensor[]{noHitSensor(player, Direction.LEFT), noHitSensor(player, Direction.RIGHT)});
    }

    private static Sensor noHitSensor(AbstractPlayableSprite player, Direction direction) {
        return new Sensor(player, direction, (byte) 0, (byte) 0, true) {
            @Override
            protected SensorResult doScan(short dx, short dy) {
                return new SensorResult((byte) 0, (byte) 0x10, 0, direction);
            }
        };
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    private AbstractPlayableSprite triggerBossTransitionWithTailsBelowLine(Sonic3kMGZEvents events) {
        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        tails.setCentreY((short) 0x0780);
        runBossTransitionTimer(events);
        return tails;
    }

    private void runBossTransitionTimer(Sonic3kMGZEvents events) {
        for (int frame = 0; frame < 0x168; frame++) {
            events.update(1, frame);
        }
    }

    @Test
    void mgz2BossApproach_locksCameraYAndArenaMaxX() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        GameServices.camera().getFocusedSprite().setCentreY((short) 0x0668);
        camera.setX((short) 0x3A00);
        camera.setY((short) 0x0600);

        events.update(1, 0);

        assertEquals((short) 0x06A0, camera.getMinY());
        assertEquals((short) 0x06A0, camera.getMinYTarget());
        assertEquals((short) 0x06A0, camera.getMaxY());
        assertEquals((short) 0x06A0, camera.getMaxYTarget());
        assertEquals((short) 0x3C80, camera.getMaxX());
        assertEquals((short) 0x3C80, camera.getMaxXTarget());
    }

    @Test
    void mgz2BossApproachRetreat_restoresCameraTopBoundToZero() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        GameServices.camera().getFocusedSprite().setCentreY((short) 0x0668);
        camera.setX((short) 0x3A00);
        camera.setY((short) 0x0600);

        events.update(1, 0);
        camera.setX((short) 0x39FF);
        events.update(1, 1);

        assertEquals((short) 0, camera.getMinY(),
                "ROM move.l #$1000 restores Camera_min_Y_pos=0 and Camera_max_Y_pos=$1000");
        assertEquals((short) 0, camera.getMinYTarget());
        assertEquals((short) 0x1000, camera.getMaxY());
        assertEquals((short) 0x1000, camera.getMaxYTarget());
    }

    @Test
    void mgz2BossApproach_usesCameraBandNotPlayerY() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        GameServices.camera().getFocusedSprite().setCentreY((short) 0x06F8);
        camera.setX((short) 0x3A10);
        camera.setY((short) 0x0600);
        camera.setMaxX((short) 0x6000);
        camera.setMaxXTarget((short) 0x6000);

        events.update(1, 0);

        assertEquals((short) 0x3C80, camera.getMaxX(),
                "MGZ2_Resize gates on camera Y, not Sonic's centre Y");
        assertEquals((short) 0x3C80, camera.getMaxXTarget());
    }

    @Test
    void mgz2AfterSignpostLowerCameraBand_canContinuePastBossApproachCameraStop() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        GameServices.camera().getFocusedSprite().setCentreY((short) 0x06D0);
        camera.setX((short) 0x3A8C);
        camera.setY((short) 0x0700);
        camera.setMaxX((short) 0x6000);
        camera.setMaxXTarget((short) 0x6000);

        events.update(1, 0);

        assertEquals((short) 0x6000, camera.getMaxX(),
                "lower route after the signpost must not stop the camera at the pre-boss clamp");
        assertEquals(0, GameServices.gameState().getCurrentBossId());
    }

    @Test
    void mgz2BossSpawn_locksLeftEdgeAndMarksEndBossActive() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        GameServices.camera().getFocusedSprite().setCentreY((short) 0x0668);
        camera.setX((short) 0x3A00);
        camera.setY((short) 0x0600);
        events.update(1, 0);

        camera.setX((short) 0x3C80);
        events.update(1, 1);

        assertEquals((short) 0x3C80, camera.getMinX());
        assertEquals((short) 0x3C80, camera.getMinXTarget());
        assertEquals(Sonic3kObjectIds.MGZ_END_BOSS, GameServices.gameState().getCurrentBossId());
    }

    @Test
    void mgz2BossSpawn_insertsEndBossThroughObjectManager() throws Exception {
        ObjectManager objectManager = installObjectManager();
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        GameServices.camera().getFocusedSprite().setCentreY((short) 0x0668);
        camera.setX((short) 0x3A00);
        camera.setY((short) 0x0600);
        events.update(1, 0);

        camera.setX((short) 0x3C80);
        events.update(1, 1);

        ObjectInstance endBoss = objectManager.getActiveObjects().stream()
                .filter(MgzEndBossInstance.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertEquals(Sonic3kObjectIds.MGZ_END_BOSS, endBoss.getSpawn().objectId(),
                "MGZ2 boss spawn should create the live Obj_MGZEndBoss object, not only mark currentBossId");
    }

    @Test
    void mgz2BossCollapseHandoff_usesRomPlayerModeForSonicAloneNotSpriteClass() {
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        GameServices.camera().setFocusedSprite(new TestablePlayableSprite("player1", (short) 0x3A10, (short) 0x0680));
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "Obj_MGZ2_BossTransition branches from Player_mode, not Player_1's Java class or sprite code");
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertEquals("tails", GameServices.sprites().getSidekickCharacterName(tails));
        assertEquals("MGZ_RESCUE_WAIT", tails.getCpuController().getState().name());
    }

    @Test
    void mgz2BossCollapseHandoff_ignoresStaleNonMgzRuntimeStateForPlayerMode() {
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        Sonic3kHCZEvents staleHczEvents = new Sonic3kHCZEvents(() -> 0);
        staleHczEvents.init(0);
        GameServices.zoneRuntimeRegistry().install(
                new HczZoneRuntimeState(0, PlayerCharacter.KNUCKLES, staleHczEvents));
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "Obj_MGZ2_BossTransition reads global Player_mode; stale non-MGZ runtime state must not suppress Tails");
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertEquals("tails", GameServices.sprites().getSidekickCharacterName(tails));
        assertEquals("MGZ_RESCUE_WAIT", tails.getCpuController().getState().name());
    }

    @Test
    void mgz2BossCollapseHandoff_leavesTailsFreeDuringRescueWait() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertFalse(tails.isObjectControlled(),
                "Obj_MGZ2_BossTransition puts Tails in CPU routine $12, which only clears Ctrl_2_logical");
        assertFalse(tails.isControlLocked(),
                "Routine $12 does not set object_control before the pickup routine starts");
    }

    @Test
    void mgz2BossTransition_doesNotOverrideCameraOwnedByDynamicResize() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x06A0);
        camera.setMinX((short) 0x3C80);
        camera.setMaxX((short) 0x3C80);
        camera.setMinY((short) 0x06A0);
        camera.setMaxY((short) 0x06A0);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        camera.setX((short) 0x3C90);
        camera.setY((short) 0x0698);
        camera.setMinY((short) 0x0690);
        camera.setMaxY((short) 0x06B0);
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(0x3C90, camera.getX() & 0xFFFF,
                "Obj_MGZ2_BossTransition reads Camera_X_pos only during creation; it never rewrites the live camera");
        assertEquals(0x0698, camera.getY() & 0xFFFF,
                "Obj_MGZ2_BossTransition must not snap Camera_Y_pos back after the collapse");
        assertEquals(0x0690, camera.getMinY() & 0xFFFF);
        assertEquals(0x06B0, camera.getMaxY() & 0xFFFF);
    }

    @Test
    void mgz2BossCollapseHandoff_repositionsExistingTailsSidekick() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        Tails tails = new Tails("tails", (short) 0x0100, (short) 0x0100);
        tails.setDead(true);
        tails.setCpuControlled(true);
        GameServices.sprites().addSprite(tails, "tails");

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(1, GameServices.sprites().getSidekicks().size());
        assertEquals((short) 0x3CC0, tails.getCentreX());
        assertEquals((short) 0x06FF, tails.getCentreY());
        assertTrue(tails.isCpuControlled());
        assertEquals("MGZ_RESCUE_WAIT", tails.getCpuController().getState().name());
    }

    @Test
    void mgz2BossCollapseHandoff_leavesVisibleTailsInCurrentRoutine() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        Tails tails = new Tails("tails", (short) 0x3C9C, (short) 0x0711);
        tails.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(tails, camera.getFocusedSprite());
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        tails.setCpuController(controller);
        tails.setHurt(true);
        tails.setXSpeed((short) 0xFE00);
        tails.setYSpeed((short) 0x0560);
        tails.setRenderFlagOnScreen(true);
        GameServices.sprites().addSprite(tails, "tails");
        short initialX = tails.getCentreX();
        short initialY = tails.getCentreY();

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(initialX, tails.getCentreX());
        assertEquals(initialY, tails.getCentreY());
        assertEquals((short) 0xFE00, tails.getXSpeed());
        assertEquals((short) 0x0560, tails.getYSpeed());
        assertTrue(tails.isHurt());
        assertEquals(SidekickCpuController.State.NORMAL, tails.getCpuController().getState(),
                "Obj_MGZ2_BossTransition branches past Player_2 setup while render_flags.on_screen is set");
    }

    @Test
    void mgz2BossCollapseHandoff_reusesExistingTailsEvenWithoutSidekickNameMetadata() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        Tails tails = new Tails("tails_p2", (short) 0x0100, (short) 0x0100);
        tails.setCpuControlled(true);
        tails.setCpuController(new SidekickCpuController(tails, camera.getFocusedSprite()));
        GameServices.sprites().addSprite(tails);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "The MGZ transition must take over an existing Tails sidekick instead of spawning a second Tails");
        assertEquals((short) 0x3CC0, tails.getCentreX());
        assertEquals((short) 0x06FF, tails.getCentreY());
        assertEquals("MGZ_RESCUE_WAIT", tails.getCpuController().getState().name());
    }

    @Test
    void mgz2BossTransition_clampsSonicAtTransitionHeightWhileWaitingForTailsCarry() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();
        sonic.setCentreY((short) 0x0760);
        sonic.setSubpixelRaw(0, 0x1000);
        sonic.setXSpeed((short) 0x0120);
        sonic.setYSpeed((short) 0x0340);
        sonic.setGSpeed((short) 0x0400);
        sonic.setSpindash(true);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        events.update(1, 0);

        assertEquals((short) 0x0700, sonic.getCentreY());
        assertEquals(0x1000, sonic.getYSubpixelRaw(),
                "ROM move.w to y_pos preserves Sonic's fractional position word");
        assertEquals((short) 0, sonic.getXSpeed());
        assertEquals((short) 0, sonic.getYSpeed());
        assertEquals((short) 0, sonic.getGSpeed());
        assertEquals(false, sonic.getSpindash());
    }

    @Test
    void mgz2BossTransition_cancelsSameFramePitDeathBeforeCameraStep() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x06A0);
        camera.setMinY((short) 0x06A0);
        camera.setMaxY((short) 0x06A0);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        sonic.setCentreY((short) 0x0781);
        sonic.applyPitDeath();
        assertTrue(sonic.getDead(), "Sanity check: S3K bottom-boundary physics already put Sonic in pit death");
        assertTrue(camera.getFrozen(), "Pit death freezes the camera before object/event handoff can run");

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        events.update(1, 0);
        camera.updatePosition();

        assertFalse(sonic.getDead(),
                "Obj_MGZEndBoss sets Disable_death_plane before Obj_MGZ2_BossTransition; a same-frame engine pit death must not leak to render");
        assertFalse(camera.getFrozen(),
                "The transition must clear the death camera freeze before the same frame's camera step");
        assertEquals((short) 0x06A0, camera.getY(),
                "The camera should render the boss-transition scene, not a one-frame death/fall camera position");
    }

    @Test
    void mgz2BossTransition_holdsTailsAloneEightPixelsBelowTransitionUntilTimerExpires() {
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");
        GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        GameServices.camera().setFocusedSprite(new Tails("tails_p1", (short) 0x3CC0, (short) 0x0780));
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite tails = camera.getFocusedSprite();
        tails.setCentreY((short) 0x0780);
        tails.setXSpeed((short) 0x0120);
        tails.setYSpeed((short) 0x0340);
        tails.setGSpeed((short) 0x0400);
        tails.setSpindash(true);
        tails.setHurt(true);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        events.update(1, 0);

        assertEquals(0, GameServices.sprites().getSidekicks().size(),
                "Player_mode 2 uses the Tails-alone transition path, not a spawned rescue sidekick");
        assertEquals((short) 0x0708, tails.getCentreY(),
                "ROM loc_163F4 keeps Tails at y_pos(a0)+8 while the $168 timer is nonzero");
        assertEquals((short) 0x0120, tails.getXSpeed());
        assertEquals((short) 0x0340, tails.getYSpeed());
        assertEquals((short) 0x0400, tails.getGSpeed());
        assertTrue(tails.getSpindash());
        assertTrue(tails.isHurt(),
                "Tails-alone routine restoration is delayed until loc_163F4's timer reaches zero");

        for (int frame = 1; frame <= 0x168; frame++) {
            events.update(1, frame);
        }

        assertEquals((short) 0x0700, tails.getCentreY());
        assertEquals((short) 0, tails.getXSpeed());
        assertEquals((short) 0, tails.getYSpeed());
        assertEquals((short) 0, tails.getGSpeed());
        assertFalse(tails.getSpindash());
        assertFalse(tails.isHurt());
    }

    @Test
    void mgz2BossTransition_usesFocusedSonicForRescueEvenIfConfiguredModeIsStale() {
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");
        GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        GameServices.camera().setFocusedSprite(new Sonic("sonic_p1", (short) 0x3CC0, (short) 0x0780));
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "The MGZ boss transition should spawn rescue Tails based on the actual focused Sonic, not stale config/session mode");
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState());
    }

    @Test
    void mgz2BossTransition_clampsSonicAfterReleasedCarryUntilRegrab() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        AbstractPlayableSprite tails = triggerBossTransitionWithTailsBelowLine(events);
        SidekickCpuController controller = tails.getCpuController();
        controller.update(1);

        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(2);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
        assertFalse(controller.isFlyingCarrying(),
                "Jump release clears Flying_carrying_Sonic_flag while leaving MGZ Tails in routine $18");

        sonic.setJumpInputPressed(false);
        sonic.setCentreY((short) 0x0780);
        sonic.setXSpeed((short) 0x0120);
        sonic.setYSpeed((short) 0x0340);
        sonic.setGSpeed((short) 0x0400);
        sonic.setSpindash(true);

        events.update(1, 0);

        assertEquals((short) 0x0700, sonic.getCentreY(),
                "Obj_MGZ2_BossTransition checks Flying_carrying_Sonic_flag, not the CPU routine number");
        assertEquals((short) 0, sonic.getXSpeed());
        assertEquals((short) 0, sonic.getYSpeed());
        assertEquals((short) 0, sonic.getGSpeed());
        assertFalse(sonic.getSpindash());
    }

    @Test
    void mgz2BossTransition_fixedObjectPassClampsBeforeLaterDynamicObjects() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        sonic.setCentreY((short) 0x0780);
        sonic.setYSpeed((short) 0x0340);
        sonic.setAir(false);
        sonic.setOnObject(true);

        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals((short) 0x0700, sonic.getCentreY(),
                "Obj_MGZ2_BossTransition must execute before later collapse-solid SST slots");
        assertEquals((short) 0, sonic.getYSpeed());
        assertFalse(sonic.getAir(),
                "The native routine=2 write does not synthesize Status_InAir");
        assertTrue(sonic.isOnObject(),
                "The native routine=2 write leaves Status_OnObj for later solid SST slots");
    }

    @Test
    void mgz2BossTransition_restartsCarryInitWhenActiveCarrierFallsBelowTransitionHeight() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);

        AbstractPlayableSprite tails = triggerBossTransitionWithTailsBelowLine(events);
        SidekickCpuController controller = tails.getCpuController();
        controller.update(1);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());

        tails.setCentreY((short) 0x0720);
        events.update(1, 0);

        assertEquals((short) 0x0700, tails.getCentreY(),
                "loc_16384 re-places Tails even while Flying_carrying_Sonic_flag is set");
        assertEquals(SidekickCpuController.State.CARRY_INIT, controller.getState(),
                "loc_16384 writes CPU routine $14 independently of the carry flag");
        assertTrue(controller.isFlyingCarrying());
    }

    @Test
    void mgz2BossTransition_waitsForReleasedTailsToFallBelowTransitionHeightBeforeRearming() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        AbstractPlayableSprite tails = triggerBossTransitionWithTailsBelowLine(events);
        SidekickCpuController controller = tails.getCpuController();
        controller.update(1);
        assertTrue(controller.isFlyingCarrying());

        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(2);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
        assertFalse(controller.isFlyingCarrying());

        sonic.setJumpInputPressed(false);
        sonic.setCentreX((short) 0x3CE0);
        sonic.setCentreY((short) 0x0780);
        sonic.setXSpeed((short) 0x0120);
        sonic.setYSpeed((short) 0x0340);
        sonic.setGSpeed((short) 0x0400);
        sonic.setSpindash(true);
        tails.setCentreX((short) 0x3C80);
        tails.setCentreY((short) 0x06D0);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) 0);

        events.update(1, 0);

        assertEquals((short) 0x0700, sonic.getCentreY());
        assertEquals((short) 0x3C80, tails.getCentreX(),
                "Obj_MGZ2_BossTransition does not re-place Tails until Tails is below the transition y");
        assertEquals((short) 0x06D0, tails.getCentreY());
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
        assertFalse(controller.isFlyingCarrying());

        tails.setCentreY((short) 0x0720);
        events.update(1, 1);

        assertEquals((short) 0x3CE0, tails.getCentreX(),
                "Once Tails is below the transition y, routine $18 lets the object copy Sonic's current x before rearming");
        assertEquals((short) 0x0700, tails.getCentreY());
        assertEquals(SidekickCpuController.State.CARRY_INIT, controller.getState(),
                "Released MGZ rescue carry must be able to return to CPU routine $14 after the first pickup");
    }

    @Test
    void mgz2BossTransition_clearsSonicHurtRoutineWhenClampingForRescueRegrab() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        AbstractPlayableSprite tails = triggerBossTransitionWithTailsBelowLine(events);
        SidekickCpuController controller = tails.getCpuController();
        controller.update(1);
        assertTrue(controller.isFlyingCarrying());

        sonic.setHurt(true);
        controller.update(2);
        assertFalse(controller.isFlyingCarrying(),
                "Tails_Carry_Sonic clears Flying_carrying_Sonic_flag when Sonic is in routine >= 4");

        sonic.setCentreX((short) 0x3CE0);
        sonic.setCentreY((short) 0x0780);
        sonic.setXSpeed((short) 0x0120);
        sonic.setYSpeed((short) 0x0340);
        sonic.setGSpeed((short) 0x0400);
        tails.setCentreY((short) 0x0720);

        events.update(1, 0);
        controller.update(3);

        assertFalse(sonic.isHurt(),
                "Obj_MGZ2_BossTransition writes Player_1 routine=2 after clamping Sonic below the transition y");
        assertTrue(controller.isFlyingCarrying(),
                "Sonic must stay latched after routine=2 clears the hurt-state rejection in Tails_Carry_Sonic");
    }

    @Test
    void mgz2BossTransitionDoesNotReviveNoRingDamageDeathForRescueRegrab() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        AbstractPlayableSprite tails = triggerBossTransitionWithTailsBelowLine(events);
        SidekickCpuController controller = tails.getCpuController();
        controller.update(1);
        assertTrue(controller.isFlyingCarrying());

        sonic.applyHurtOrDeath(0x3D20, DamageCause.NORMAL, false);
        controller.update(2);
        assertFalse(controller.isFlyingCarrying(),
                "Tails_Carry_Sonic must release when Sonic enters a real death routine");

        sonic.setCentreX((short) 0x3CE0);
        sonic.setCentreY((short) 0x0780);
        tails.setCentreY((short) 0x0720);

        events.update(1, 3);
        controller.update(3);

        assertTrue(sonic.getDead(),
                "A no-ring damage death during the MGZ2 rescue must continue to the normal death sequence");
        assertFalse(sonic.isObjectControlled(),
                "Rescue Tails must not regrab Sonic after a real no-ring damage death");
        assertFalse(controller.isFlyingCarrying(),
                "The MGZ rescue carry should stay released while Sonic is dead");
    }

    @Test
    void mgz2BossTransitionKeepsTemporaryRescueTailsUntilRespawnReloadWhenSonicOnlyPlayerDies() {
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();
        GameServices.sprites().addSprite(sonic);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "Sonic-only MGZ2 rescue should donate a temporary Tails for the boss transition");
        assertEquals("mgz2_boss_tails", GameServices.sprites().getSidekicks().getFirst().getCode());

        sonic.applyHurtOrDeath(0x3D20, DamageCause.NORMAL, false);
        events.update(1, 1);

        assertTrue(sonic.getDead(),
                "The no-ring damage death should remain active after transition cleanup");
        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "Temporary rescue Tails should remain visible during Sonic's death animation and fade");

        GameServices.level().spawnSidekicks(-32, 4);

        assertEquals(0, GameServices.sprites().getSidekicks().size(),
                "Temporary rescue Tails must be removed when the respawn reload places the configured team");
    }

    @Test
    void mgz2BossTransitionKeepsConfiguredTailsSidekickWhenSonicDies() {
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration().setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();
        GameServices.sprites().addSprite(sonic);
        Tails tails = new Tails("tails_p2", (short) 0x0100, (short) 0x0100);
        tails.setCpuControlled(true);
        GameServices.sprites().addSprite(tails, "tails");

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        sonic.applyHurtOrDeath(0x3D20, DamageCause.NORMAL, false);
        events.update(1, 1);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "Configured Sonic-and-Tails play should keep the real sidekick across Sonic's death");
        assertEquals("tails_p2", GameServices.sprites().getSidekicks().getFirst().getCode());

        GameServices.level().spawnSidekicks(-32, 4);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "Respawn sidekick placement should purge temporary rescue sprites without deleting configured Tails");
        assertEquals("tails_p2", GameServices.sprites().getSidekicks().getFirst().getCode());
    }

    @Test
    void mgz2BossTransition_restartsAscentWhenActiveCarrierFallsBelowTransition() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        AbstractPlayableSprite tails = triggerBossTransitionWithTailsBelowLine(events);
        SidekickCpuController controller = tails.getCpuController();
        controller.update(1);
        tails.setCentreY((short) 0x0690);
        controller.update(2);
        sonic.setDirectionalInputPressed(false, false, true, false);
        controller.update(3);
        assertTrue(controller.getInputLeft(),
                "At Camera_Y+$90, MGZ routine $18 should copy P1 left/right into Ctrl_2");

        tails.setCentreY((short) 0x0720);
        // Player 2's CPU/carry pass precedes Obj_MGZ2_BossTransition in the
        // native object order. It first publishes routine-$18 input and moves
        // carried Sonic to Tails+$1C; the transition then observes Sonic's
        // off-screen render state and republishes routine $14. Skipping this
        // pass leaves Sonic at the previous carrier position and tests an
        // impossible loc_16384 input state.
        controller.update(4);
        events.update(1, 4);

        sonic.setDirectionalInputPressed(false, false, true, false);
        controller.update(5);

        assertFalse(controller.getInputLeft(),
                "the unconditional loc_16384 routine-$14 write restarts the ascent input phase");
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
    }

    @Test
    void mgz2BossTransition_usesCameraAnchoredXWhenCurrentTailsRoutineIsBelowCarryRoutine() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        runBossTransitionTimer(events);
        SidekickCpuController controller = tails.getCpuController();
        controller.setInitialState(SidekickCpuController.State.SPAWNING);
        tails.setCentreX((short) 0x4000);
        tails.setCentreY((short) 0x0720);

        sonic.setCentreX((short) 0x3CF0);
        sonic.setCentreY((short) 0x0780);
        sonic.setXSpeed((short) 0x0120);
        sonic.setYSpeed((short) 0x0340);
        sonic.setGSpeed((short) 0x0400);
        sonic.setSpindash(true);

        events.update(1, 0);

        assertEquals((short) 0x3CC0, tails.getCentreX(),
                "Obj_MGZ2_BossTransition only copies Sonic's x when Tails_CPU_routine is $14 or higher");
        assertEquals((short) 0x0700, tails.getCentreY());
        assertEquals(SidekickCpuController.State.CARRY_INIT, controller.getState());
    }

    @Test
    void mgz2BossCollapseHandoff_disablesPitDeathForRescueTransition() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameModuleRegistry.getCurrent().getLevelEventProvider();
        manager.initLevel(Sonic3kZoneIds.ZONE_MGZ, 1);
        AbstractPlayableSprite sonic = GameServices.camera().getFocusedSprite();

        manager.getMgzEvents().triggerBossCollapseHandoff();

        assertTrue(manager.interceptPitDeath(sonic),
                "Obj_MGZEndBoss floor impact sets Disable_death_plane before Obj_MGZ2_BossTransition runs");
    }

    @Test
    void mgzEndBossObjectIdCreatesEndBossInstance() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance instance = registry.create(new com.openggf.level.objects.ObjectSpawn(
                0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));

        assertInstanceOf(MgzEndBossInstance.class, instance,
                "MGZ_END_BOSS must be registered; otherwise level placement falls back to a placeholder");
    }

    @Test
    void mgz2BossArenaSpawnsDedicatedKnucklesBossWithoutSonicTransitionPath() throws Exception {
        GameServices.configuration().setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        ObjectManager objectManager = installObjectManager();
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3A00);
        camera.setY((short) 0x0000);
        events.update(1, 0);
        camera.setX((short) 0x3C80);

        events.update(1, 1);

        assertEquals(Sonic3kObjectIds.MGZ_END_BOSS_KNUX, GameServices.gameState().getCurrentBossId());
        assertEquals(1, objectManager.getActiveObjects().stream()
                .filter(MgzEndBossKnuxInstance.class::isInstance).count());
        MgzEndBossKnuxInstance boss = objectManager.getActiveObjects().stream()
                .filter(MgzEndBossKnuxInstance.class::isInstance)
                .map(MgzEndBossKnuxInstance.class::cast)
                .findFirst().orElseThrow();
        assertEquals(0x0068, boss.getSpawn().y());
        assertEquals(0x00A0, camera.getMinY() & 0xFFFF);
        assertFalse(events.isBossTransitionDeathPlaneDisabled(),
                "Knuckles' grounded boss path must not enter Sonic's rescue/death-plane transition");
        assertEquals(0, GameServices.sprites().getSidekicks().size(),
                "Knuckles' boss path must not donate the Sonic-route rescue Tails");
    }

    @Test
    void mgz2BossTransition_startsTailsCarryAfterRomDelay() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        tails.setCentreY((short) 0x0780);

        for (int frame = 0; frame < 0x167; frame++) {
            events.update(1, frame);
        }

        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState(),
                "the lower transition SST cannot consume its countdown on the allocating boss pass");

        events.update(1, 0x167);

        assertEquals(SidekickCpuController.State.CARRY_INIT, tails.getCpuController().getState(),
                "Obj_MGZ2_BossTransition switches Tails from CPU routine $12 to $14 after the $168-frame wait");
    }

    @Test
    void mgz2BossTransition_entersRescueWaitBeforeRomDelayExpires() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();
        sonic.setRenderFlagOnScreen(false);

        Tails tails = new Tails("tails", (short) 0x3C90, (short) 0x0710);
        tails.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        tails.setCpuController(controller);
        tails.setRenderFlagOnScreen(true);
        GameServices.sprites().addSprite(tails, "tails");

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        tails.setCentreY((short) 0x0701);
        events.update(1, 0);

        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, controller.getState(),
                "loc_16384 writes Tails_CPU_routine=$12 before testing the $168-frame timer");
        assertEquals((short) 0x0701, tails.getCentreY(),
                "the live Tails slot is not repositioned until the timer reaches zero");

        controller.setController2Input(AbstractPlayableSprite.INPUT_RIGHT, 0);
        controller.update(1);

        assertEquals(0, controller.getDiagnosticGeneratedHeldInput(),
                "Tails CPU routine $12 executes loc_140C6's full Ctrl_2_logical word clear");
    }

    @Test
    void mgz2BossTransition_waitsForSonicToLeaveScreenBeforeStartingTailsCarry() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        AbstractPlayableSprite sonic = camera.getFocusedSprite();
        sonic.setCentreX((short) 0x3CC0);
        sonic.setCentreY((short) 0x0700);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        tails.setCentreY((short) 0x0780);
        sonic.setRenderFlagOnScreen(true);

        runBossTransitionTimer(events);

        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState(),
                "ROM loc_16384 returns while Player_1 render_flags has the on-screen high bit set");

        tails.getCpuController().update(0);

        assertFalse(sonic.isObjectControlled(),
                "Starting the carry before Sonic leaves the screen latches object_control until the jump-release path clears it");
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState());
    }

    @Test
    void mgz2BossTransition_waitsForTailsToDescendPastTransitionYAfterDelay() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);

        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        tails.setCentreY((short) 0x06D0);

        runBossTransitionTimer(events);

        assertFalse(tails.isObjectControlled(),
                "Routine $12 leaves object_control clear while Tails descends under normal flight/freespace physics");
        assertEquals((short) 0x06D0, tails.getCentreY());
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState(),
                "The timeout alone is not enough; Obj_MGZ2_BossTransition also requires Tails below transition y");

        tails.setCentreY((short) 0x0701);
        events.update(1, 0);

        assertEquals((short) 0x0700, tails.getCentreY());
        assertEquals(SidekickCpuController.State.CARRY_INIT, tails.getCpuController().getState());
    }

    @Test
    void mgz2BossTransitionCarry_pulsesJumpNotRightInput() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);

        AbstractPlayableSprite tails = triggerBossTransitionWithTailsBelowLine(events);

        boolean sawJumpPulse = false;
        for (int frame = 7; frame < 24; frame++) {
            tails.getCpuController().update(frame);
            assertEquals(SidekickCpuController.State.CARRYING, tails.getCpuController().getState());
            assertFalse(tails.getCpuController().getInputRight(),
                    "MGZ rescue routine $16 does not reuse the CNZ carry's synthetic-right input");
            sawJumpPulse |= tails.getCpuController().getInputJump()
                    && tails.getCpuController().getInputJumpPress();
        }

        assertTrue(sawJumpPulse,
                "MGZ rescue routine $16 pulses A/B/C every 8 frames so Tails keeps flying upward");
    }

    @Test
    void mgz2BossTransition_initializesFromNextObjectPassCameraPosition() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3C70);
        camera.setY((short) 0x05F0);

        events.triggerBossCollapseHandoff();
        camera.setX((short) 0x3C80);
        camera.setY((short) 0x0600);
        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        assertEquals((short) 0x3CC0, tails.getCentreX(),
                "The allocated transition SST initializes from the following object pass camera X");
        assertEquals((short) 0x06FF, tails.getCentreY(),
                "The allocated transition SST initializes from the following object pass camera Y");
    }

    @Test
    void mgz2BossTransition_recreatesMissingTemporaryPlayer2AndThenResumes() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        GameServices.camera().setX((short) 0x3C80);
        GameServices.camera().setY((short) 0x0600);
        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        AbstractPlayableSprite first = GameServices.sprites().getSidekicks().getFirst();
        assertTrue(GameServices.sprites().removeTemporarySidekick(first));

        events.updateBossTransitionObjectBeforeDynamicObjects(1);

        assertEquals(1, GameServices.sprites().getSidekicks().size(),
                "The persistent native Player_2 SST must be reconstructed without duplicates");
        AbstractPlayableSprite replacement = GameServices.sprites().getSidekicks().getFirst();
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, replacement.getCpuController().getState());
        replacement.setCentreY((short) 0x0701);
        GameServices.camera().getFocusedSprite().setRenderFlagOnScreen(false);
        runBossTransitionTimer(events);
        assertEquals(SidekickCpuController.State.CARRY_INIT, replacement.getCpuController().getState());
    }

    @Test
    void mgz2BossTransition_nonRoutine2WaitResumesWhenNormalRoutineReturns() {
        Sonic3kMGZEvents events = new Sonic3kMGZEvents();
        events.init(1);
        GameServices.camera().setX((short) 0x3C80);
        GameServices.camera().setY((short) 0x0600);
        events.triggerBossCollapseHandoff();
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
        player.setRenderFlagOnScreen(false);
        player.setDebugMode(true);
        tails.setCentreY((short) 0x0701);
        runBossTransitionTimer(events);
        assertEquals(SidekickCpuController.State.MGZ_RESCUE_WAIT, tails.getCpuController().getState(),
                "ROM loc_16384 requires Player_1 routine exactly $02");

        player.setDebugMode(false);
        events.updateBossTransitionObjectBeforeDynamicObjects(1);
        assertEquals(SidekickCpuController.State.CARRY_INIT, tails.getCpuController().getState(),
                "A transient non-$02 routine must not permanently stall the transition");
    }

    @Test
    void playerNativeRoutineContractDistinguishesControlFromNonRoutine2States() {
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        assertEquals(NativePlayableRoutine.CONTROL, NativePlayableRoutine.resolve(player));
        player.setHurt(true);
        assertEquals(NativePlayableRoutine.HURT, NativePlayableRoutine.resolve(player));
        player.setHurt(false);
        player.setDead(true);
        assertEquals(NativePlayableRoutine.DEAD, NativePlayableRoutine.resolve(player));
        player.setDead(false);
        player.setDebugMode(true);
        assertEquals(NativePlayableRoutine.BYPASSED_BY_DEBUG_MODE, NativePlayableRoutine.resolve(player));
    }

    private static ObjectManager installObjectManager() throws NoSuchFieldException, IllegalAccessException {
        ObjectManager objectManager = new ObjectManager(
                new ArrayList<>(),
                new Sonic3kObjectRegistry(),
                GameModuleRegistry.getCurrent().getPlaneSwitcherObjectId(),
                GameModuleRegistry.getCurrent().getPlaneSwitcherConfig(),
                null);
        Field field = GameServices.level().getClass().getDeclaredField("objectManager");
        field.setAccessible(true);
        field.set(GameServices.level(), objectManager);
        return objectManager;
    }
}
