package com.openggf.sprites.playable;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.rules.GameRules;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.ObjectInteractionRules;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.objects.AizTransitionFloorObjectInstance;
import com.openggf.game.sonic3k.objects.CorkFloorObjectInstance;
import com.openggf.game.sonic3k.objects.DoorObjectInstance;
import com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance;
import com.openggf.game.sonic3k.objects.MhzCurledVineObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSpringObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.camera.Camera;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.PerObjectRewindSnapshot.SidekickCpuRewindExtra;
import com.openggf.physics.Direction;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSidekickCpuDespawnParity {

    @BeforeEach
    void configureRuntime() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDownRuntime() {
        SessionManager.clear();
    }

    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) {
            super(code, (short) 0, (short) 0);
        }

        @Override
        public void draw() {}

        @Override
        public void defineSpeeds() {
            runHeight = 38;
            rollHeight = 28;
            standXRadius = 9;
            standYRadius = 19;
            rollXRadius = 7;
            rollYRadius = 14;
            setWidth(18);
            setHeight(runHeight);
        }

        @Override
        protected void createSensorLines() {}

        void useGameRules(GameRules featureSet) {
            super.setGameRulesForTest(featureSet);
        }

        void useS3kTailsRadii() {
            runHeight = 30;
            rollHeight = 28;
            standXRadius = 9;
            standYRadius = 15;
            rollXRadius = 7;
            rollYRadius = 14;
            setHeight(runHeight);
            restoreDefaultRadii();
        }
    }

    private static final class DestroyedRideObject extends AbstractObjectInstance {
        private DestroyedRideObject(int objectId) {
            super(new ObjectSpawn(0x1200, 0x0800, objectId, 0, 0, false, 0), "DestroyedRideObject");
            setDestroyed(true);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // Test sentinel only.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No rendering needed for this test sentinel.
        }
    }

    private static final class UnloadedRideObject extends AbstractObjectInstance {
        private UnloadedRideObject(int objectId) {
            super(new ObjectSpawn(0x1200, 0x0800, objectId, 0, 0, false, 0), "UnloadedRideObject");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // Test sentinel only.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No rendering needed for this test sentinel.
        }
    }

    @Test
    void levelBoundsPreserveWriteOnlyMinYAcrossRewind() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.setLevelBounds(null, null, 0x0400, null);

        assertEquals(0x0400, controller.getMinYBound(0x0123),
                "S2 Tails_Min_Y_pos is write-only in the ROM, but the engine should still preserve the value");

        SidekickCpuRewindExtra snapshot = controller.captureRewindState();
        controller.setLevelBounds(0x1111, 0x2222, 0x0333, 0x0444);
        controller.restoreRewindState(snapshot);

        assertEquals(0x0400, controller.getMinYBound(Integer.MIN_VALUE),
                "Sidekick min-Y bound should round-trip through rewind snapshots");
        assertEquals(Integer.MIN_VALUE, controller.getMinXBound(Integer.MIN_VALUE),
                "Unwritten min-X bound should remain unset");
        assertEquals(Integer.MIN_VALUE, controller.getMaxYBound(Integer.MIN_VALUE),
                "Unwritten max-Y bound should remain unset");
    }

    @Test
    void despawnPreservesMotionAndMatchesRomStatusReset() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setRolling(true);
        tails.setRollingJump(true);
        tails.setOnObject(true);
        tails.setPushing(true);
        tails.setDirection(Direction.LEFT);
        tails.setCentreX((short) 0x0545);
        tails.setCentreY((short) 0x0270);
        tails.setSubpixelRaw(0x0300, 0x2700);
        tails.setXSpeed((short) 0x041C);
        tails.setYSpeed((short) 0x0000);
        tails.setGSpeed((short) 0x041C);
        tails.setSpindash(true);
        tails.setSpindashCounter((short) 0x0400);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.despawn();

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState());
        assertEquals((short) 0x4000, tails.getCentreX());
        assertEquals((short) 0x0000, tails.getCentreY());
        assertEquals(0x0300, tails.getXSubpixelRaw());
        assertEquals(0x2700, tails.getYSubpixelRaw());
        assertEquals((short) 0x041C, tails.getXSpeed());
        assertEquals((short) 0x0000, tails.getYSpeed());
        assertEquals((short) 0x041C, tails.getGSpeed());
        assertTrue(tails.getSpindash(),
                "S2 TailsCPU_Despawn does not clear pinball_mode/spindash_flag");
        assertEquals((short) 0x0400, tails.getSpindashCounter(),
                "S2 TailsCPU_Despawn does not touch spindash_counter");
        assertTrue(tails.getAir());
        assertFalse(tails.getRolling());
        assertFalse(tails.getRollingJump());
        assertFalse(tails.isOnObject());
        assertFalse(tails.getPushing());
        assertEquals(Direction.RIGHT, tails.getDirection());
        assertTrue(tails.isControlLocked());
        assertTrue(tails.isObjectControlled());
    }

    @Test
    void s3kPanicOffscreenTimeoutUsesDirectMarkerFacing() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setDirection(Direction.LEFT);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.PANIC, 0);

        controller.despawn(SidekickCpuController.DespawnCause.OFF_SCREEN_TIMEOUT);

        assertEquals(Direction.RIGHT, tails.getDirection(),
                "TailsCPU_CheckDespawn calls sub_13ECA directly and leaves status=Status_InAir");
    }

    @Test
    void s3kPanicInteractMismatchContinuesIntoRoutineEightFacing() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.useGameRules(GameRules.SONIC_3K);
        sonic.setCentreX((short) 0x0200);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.PANIC, 0);

        controller.despawn(SidekickCpuController.DespawnCause.OBJECT_ID_MISMATCH);

        assertEquals(Direction.LEFT, tails.getDirection(),
                "loc_13F40 continues after sub_13EFC and faces from the post-warp sentinel");
    }

    @Test
    void s2FlyingRespawnTimeoutReturnsToSpawningAtZeroMarker() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x2375);
        tails.setCentreY((short) 0x03D9);
        tails.setSubpixelRaw(0x2300, 0xE000);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) 0x00A8);
        tails.setGSpeed((short) 0xFFD0);
        tails.setRolling(true);
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x04, 0, 0, 0, true, 0, 0);
        controller.forceStateForTest(SidekickCpuController.State.APPROACHING, 0);

        for (int i = 0; i < 300; i++) {
            tails.setRenderFlagOnScreen(false);
            controller.update(i);
        }

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState());
        assertEquals((short) 0x0000, tails.getCentreX(),
                "S2 TailsCPU_Flying timeout writes x_pos=0, not the normal $4000 despawn marker");
        assertEquals((short) 0x0000, tails.getCentreY());
        assertEquals(0x2300, tails.getXSubpixelRaw());
        assertEquals(0xE000, tails.getYSubpixelRaw());
        assertTrue(tails.getAir());
        assertFalse(tails.getRolling());
        assertTrue(tails.isObjectControlled());
        assertEquals(1, controller.getDiagnosticJumpingFlag(),
                "S2 TailsCPU_Flying timeout does not clear Tails_CPU_jumping "
                        + "(docs/s2disasm/s2.asm:39136-39149)");
    }

    @Test
    void s2FlyingRespawnClearsCounterAsSoonAsTheRenderFlagIsOnScreen() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.setCentreX((short) 0x1CAE);
        sonic.setCentreY((short) 0x052C);
        sonic.resetPositionAndStatTableHistory();

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1C97);
        tails.setCentreY((short) 0x04AD);
        tails.setAir(true);
        tails.setRenderFlagOnScreen(true);

        GameServices.camera().setX((short) 0x1C0E);
        GameServices.camera().setY((short) 0x04CC);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x04, 0, 0, 0x74, false, 0x1CA4, 0x052C);
        controller.forceStateForTest(SidekickCpuController.State.APPROACHING, 0);

        for (int i = 0; i < 0x3E; i++) {
            tails.setRenderFlagOnScreen(false);
            controller.update(i);
            assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());
        }

        tails.setCentreX((short) 0x1C97);
        tails.setCentreY((short) 0x04AD);
        tails.setRenderFlagOnScreen(true);

        controller.update(0x193F);

        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());
        // ROM TailsCPU_Flying (docs/s2disasm/s2.asm:39141-39158) tests the render
        // flag FIRST and branches straight to TailsCPU_FlyingOnscreen, whose only
        // statement is move.w #0,(Tails_respawn_counter).w. There is no
        // counter-value gate and no camera-relative-Y gate anywhere in the
        // routine, so an on-screen render flag zeroes the counter on the frame it
        // is observed -- it never "keeps" a prior count for a frame.
        //
        // This assertion previously expected 0x003F, a value measured from an
        // HTZ1 BizHawk probe rather than read from the ROM, and it partnered a
        // fitted window in TailsRespawnStrategy that has since been removed. The
        // HTZ traces that window was added for stay green without it.
        assertEquals(0, controller.getDiagnosticRespawnCounter(),
                "TailsCPU_Flying branches to TailsCPU_FlyingOnscreen on an on-screen "
                        + "render flag and zeroes Tails_respawn_counter that frame "
                        + "(docs/s2disasm/s2.asm:39142-39158)");
    }

    @Test
    void s2FlyingRespawnLaterTopEdgeUsesRefreshedRenderFlag() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.setCentreX((short) 0x0427);
        sonic.setCentreY((short) 0x0425);
        sonic.resetPositionAndStatTableHistory();

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0423);
        tails.setCentreY((short) 0x03A9);
        tails.setAir(true);
        tails.setRenderFlagOnScreen(true);

        GameServices.camera().setX((short) 0x03B1);
        GameServices.camera().setY((short) 0x03C8);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x04, 0, 0, 0x74, false, 0x0427, 0x0425);
        controller.forceStateForTest(SidekickCpuController.State.APPROACHING, 0);

        for (int i = 0; i < 0x5B; i++) {
            tails.setRenderFlagOnScreen(false);
            controller.update(i);
            assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());
        }

        tails.setCentreX((short) 0x0423);
        tails.setCentreY((short) 0x03A9);
        tails.setRenderFlagOnScreen(true);

        controller.update(0x045A);

        assertEquals(SidekickCpuController.State.APPROACHING, controller.getState());
        assertEquals(0x0000, controller.getDiagnosticRespawnCounter(),
                "CNZ f1115 guard: a later TailsCPU_Flying top-edge pass has refreshed render_flags "
                        + "and must reset Tails_respawn_counter instead of carrying $5C");
    }

    @Test
    void s2FlyingOffscreenCounterCarriesIntoNormalDespawnAfterApproachCompletes() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.setCentreX((short) 0x2908);
        sonic.setCentreY((short) 0x0691);
        sonic.resetPositionAndStatTableHistory();

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x2908);
        tails.setCentreY((short) 0x0682);
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.APPROACHING, 0);

        for (int i = 0; i < 15; i++) {
            tails.setRenderFlagOnScreen(false);
            controller.update(i);
            assertEquals(SidekickCpuController.State.APPROACHING, controller.getState(),
                    "The test setup should keep accumulating the S2 flying off-screen counter before landing");
        }

        tails.setRenderFlagOnScreen(false);
        controller.update(15);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "The approach completion frame should carry the accumulated flying counter into NORMAL");
        assertEquals(0x2908, controller.targetX(),
                "S2 TailsCPU_Flying leaves the last Tails_CPU_target_x word live after returning to NORMAL");
        assertEquals(0x0691, controller.targetY(),
                "S2 TailsCPU_Flying leaves the last Tails_CPU_target_y word live after returning to NORMAL");

        for (int i = 16; i < 299; i++) {
            tails.setRenderFlagOnScreen(false);
            controller.update(i);
            assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                    "S2 normal despawn should not fire before the shared counter reaches 300");
        }

        tails.setRenderFlagOnScreen(false);
        controller.update(299);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState());
        assertEquals((short) 0x4000, tails.getCentreX(),
                "After TailsCPU_Flying lands, S2 TailsCPU_CheckDespawn continues the same counter and uses $4000");
        assertEquals((short) 0x0000, tails.getCentreY());
    }

    @Test
    void s3kDespawnMarkerReturnsToCatchUpFlightRoutine() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0545);
        tails.setCentreY((short) 0x0270);
        tails.setSubpixelRaw(0x0300, 0x2700);
        tails.setXSpeed((short) 0x041C);
        tails.setYSpeed((short) 0x0020);
        tails.setGSpeed((short) 0x041C);
        tails.setAnimationId(2);
        tails.setMappingFrame(0x96);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.despawn();

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "S3K sub_13ECA writes Tails_CPU_routine=2, not the S2 SPAWNING approach");
        assertEquals((short) 0x7F00, tails.getCentreX(),
                "S3K despawn marker uses x_pos=$7F00");
        assertEquals((short) 0x0000, tails.getCentreY());
        assertEquals(0x0300, tails.getXSubpixelRaw());
        assertEquals(0x2700, tails.getYSubpixelRaw());
        assertEquals((short) 0x041C, tails.getXSpeed());
        assertEquals((short) 0x0020, tails.getYSpeed());
        assertEquals((short) 0x041C, tails.getGSpeed());
        assertEquals(2, tails.getAnimationId(),
                "sub_13ECA does not replace the raw animation byte with Fly");
        assertEquals(0x96, tails.getMappingFrame(),
                "bit-7 object control retains the pre-warp displayed frame");
        assertEquals(0, tails.getDoubleJumpFlag(),
                "S3K sub_13ECA clears double_jump_flag");
        assertTrue(tails.getAir());
        assertTrue(tails.isControlLocked());
        assertTrue(tails.isObjectControlled());
    }

    @Test
    void s3kSonicSidekickDespawnUsesRunInRespawnStrategy() {
        TestableSprite leader = new TestableSprite("tails");
        TestableSprite sonicSidekick = new TestableSprite("sonic_p2");
        sonicSidekick.useGameRules(GameRules.SONIC_3K);
        sonicSidekick.setCpuControlled(true);
        sonicSidekick.setCentreX((short) 0x0545);
        sonicSidekick.setCentreY((short) 0x0270);

        SidekickCpuController controller = new SidekickCpuController(sonicSidekick, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.despawn();

        assertInstanceOf(SonicRespawnStrategy.class, controller.getRespawnStrategy());
        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(),
                "Sonic sidekicks must not enter S3K Tails_Catch_Up_Flying after off-screen despawn");
        assertEquals((short) 0x7F00, sonicSidekick.getCentreX(),
                "S3K still parks at the off-screen despawn marker before the character strategy starts");
        assertEquals((short) 0x0000, sonicSidekick.getCentreY());
    }

    @Test
    void s3kDespawnMarkerClearsRollStatusWithoutRestoringRadii() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.useS3kTailsRadii();
        tails.setCpuControlled(true);
        tails.setRolling(true);
        tails.setCentreX((short) 0x1E8F);
        tails.setCentreY((short) 0x057B);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.despawn();

        assertFalse(tails.getRolling(),
                "S3K sub_13ECA writes status=Status_InAir and clears Status_Roll");
        assertEquals(7, tails.getXRadius(),
                "S3K sub_13ECA does not restore x_radius");
        assertEquals(14, tails.getYRadius(),
                "S3K sub_13ECA does not restore y_radius");
    }

    @Test
    void levelBoundaryKillRunsTailsTouchFloorBeforeDeathState() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.useS3kTailsRadii();
        tails.setCpuControlled(true);
        tails.setRolling(true);
        tails.setRollingJump(true);
        tails.setCentreX((short) 0x2D90);
        tails.setCentreY((short) 0x0402);
        tails.setAir(true);
        tails.setPushing(true);
        tails.setAngle((byte) 0x00);
        tails.setXSpeed((short) 0x0000);
        tails.setYSpeed((short) 0x0198);
        tails.setGSpeed((short) 0x0000);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.despawn(SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);

        assertEquals(SidekickCpuController.State.DEAD_FALLING, controller.getState(),
                "S3K Kill_Character leaves Tails in object routine 6 for one frame");
        // setRollingJump(true) mirrors Sonic_Jump restoring default radii while
        // leaving Status_Roll set. S3K Tails_TouchFloor computes the y_pos
        // shift from current y_radius - default_y_radius
        // (sonic3k.asm:29133-29156), so this setup has no radius delta.
        assertEquals((short) 0x0402, tails.getCentreY(),
                "Tails_TouchFloor uses the current y_radius byte, not sprite height");
        assertEquals(15, tails.getYRadius());
        assertFalse(tails.getRolling());
        assertTrue(tails.getAir(), "Kill_Character sets Status_InAir after Tails_TouchFloor");
        assertFalse(tails.getRollingJump());
        assertFalse(tails.getPushing());
        assertEquals((short) 0x0000, tails.getXSpeed());
        // ROM Kill_Character (sonic3k.asm:21149) writes y_vel=-$700, NOT zero.
        // The kill is reached via `jmp` from Tails_Check_Screen_Boundaries
        // (sonic3k.asm:28443), and Kill_Character's `rts` (sonic3k.asm:21159)
        // unwinds to the caller of Tails_Check_Screen_Boundaries
        // (e.g. Tails_Stand_Path at sonic3k.asm:27526), which then runs
        // MoveSprite_TestGravity2 (->MoveSprite2 at sonic3k.asm:36088,36053)
        // applying the negative y-velocity to y_pos in the same frame.
        // Trace AIZ F7171 records the post-shift state with y_vel=-$700
        // retained.
        assertEquals((short) -0x700, tails.getYSpeed());
        assertEquals((short) 0x0000, tails.getGSpeed());
    }

    @Test
    void s3kSonicSidekickLevelBoundaryKillUsesDeathAnimation() {
        TestableSprite leader = new TestableSprite("tails");
        TestableSprite sonicSidekick = new TestableSprite("sonic_p2");
        sonicSidekick.useGameRules(GameRules.SONIC_3K);
        sonicSidekick.setCpuControlled(true);
        sonicSidekick.setCentreX((short) 0x2D90);
        sonicSidekick.setCentreY((short) 0x0402);
        sonicSidekick.setAir(true);
        sonicSidekick.setXSpeed((short) 0x0000);
        sonicSidekick.setYSpeed((short) 0x0198);
        sonicSidekick.setGSpeed((short) 0x0000);

        SidekickCpuController controller = new SidekickCpuController(sonicSidekick, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.despawn(SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);

        assertInstanceOf(SonicRespawnStrategy.class, controller.getRespawnStrategy());
        assertEquals(SidekickCpuController.State.DEAD_FALLING, controller.getState());
        assertEquals(Sonic3kAnimationIds.DEATH.id(), sonicSidekick.getForcedAnimationId(),
                "Kill_Character writes the death animation; Sonic sidekicks must not inherit Tails' fly animation");
    }

    @Test
    void s3kLevelBoundaryKillPreservesCpuGlobalsUntilDespawnMarker() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.useS3kTailsRadii();
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x2D40);
        tails.setCentreY((short) 0x0402);
        tails.setAir(true);
        tails.setXSpeed((short) 0x00F7);
        tails.setYSpeed((short) 0x0198);
        tails.setGSpeed((short) -0x00FC);

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x2DCB);
        Arrays.fill(yHistory, (short) 0x0339);
        sonic.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x06, 0, 0x003E, 0x0002, true, 0x2648, 0x0329);
        controller.update(0x1126);

        assertEquals(0x0018, controller.getDiagnosticGeneratedHeldInput(),
                "Normal routine should have written Ctrl_2_logical RIGHT|JUMP before the kill");
        assertEquals(0x003F, controller.getDiagnosticRespawnCounter());
        assertEquals(0x0002, controller.getDiagnosticInteractId());
        assertEquals(1, controller.getDiagnosticJumpingFlag());

        controller.despawn(SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);

        assertEquals(SidekickCpuController.State.DEAD_FALLING, controller.getState());
        assertEquals(0x0006, controller.getDiagnosticRomCpuRoutine(),
                "Kill_Character writes object routine 6 but does not touch Tails_CPU_routine "
                        + "(sonic3k.asm:21136-21151,26354-26364)");
        assertEquals(0x003F, controller.getDiagnosticRespawnCounter(),
                "Kill_Character does not clear Tails_CPU_flight_timer; sub_13ECA does "
                        + "(sonic3k.asm:21136-21151,26800-26803)");
        assertEquals(0x0002, controller.getDiagnosticInteractId(),
                "Kill_Character does not clear Tails_CPU_interact; AIZ F7171 keeps the "
                        + "0x0002 stood-on object pointer word through object routine 6");
        assertEquals(0x0018, controller.getDiagnosticGeneratedHeldInput(),
                "Ctrl_2_logical is a ROM global latch and survives the kill frame");
        assertEquals(1, controller.getDiagnosticJumpingFlag(),
                "Tails_CPU_auto_jump_flag is not cleared by Kill_Character");

        controller.update(0x1127);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState());
        assertEquals(0x0002, controller.getDiagnosticRomCpuRoutine(),
                "sub_13ECA writes Tails_CPU_routine=2 on the next CPU tick");
        assertEquals(0x0000, controller.getDiagnosticRespawnCounter(),
                "sub_13ECA clears Tails_CPU_flight_timer");
        assertEquals(0x0002, controller.getDiagnosticInteractId(),
                "sub_13ECA also leaves Tails_CPU_interact untouched "
                        + "(sonic3k.asm:26800-26809)");
        assertEquals(0x0018, controller.getDiagnosticGeneratedHeldInput(),
                "sub_13ECA does not clear Ctrl_2_logical during the marker frame");
        assertEquals(1, controller.getDiagnosticJumpingFlag(),
                "sub_13ECA does not clear Tails_CPU_auto_jump_flag (sonic3k.asm:26800-26809)");

        controller.update(0x1128);

        assertEquals(0x0000, controller.getDiagnosticGeneratedHeldInput(),
                "routine 2 exposes the current Ctrl_2_logical latch after the marker frame");
        assertEquals(1, controller.getDiagnosticJumpingFlag(),
                "routine 2 wait path also leaves Tails_CPU_auto_jump_flag intact");
    }

    @Test
    void s2LevelBoundaryKillPreservesPanicCpuRoutineUntilDespawnMarker() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x2679);
        tails.setCentreY((short) 0x02E9);
        tails.setAir(false);
        tails.setGSpeed((short) 0);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x08, 0, 0x00B0, 0x0007, false, 0x24EE, 0x016C);

        controller.despawn(SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);

        assertEquals(SidekickCpuController.State.DEAD_FALLING, controller.getState());
        assertEquals(0x0008, controller.getDiagnosticRomCpuRoutine(),
                "S2 Kill_Character writes Obj02 routine 6 but leaves the separate "
                        + "Tails_CPU_routine word unchanged until Obj02_CheckGameOver "
                        + "branches to TailsCPU_Despawn (s2.asm:41131-41154, 39397-39401)");
    }

    @Test
    void groundedPushAutoJumpFlagPersistsOnPushBypassLikeRom() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setPushing(true);
        tails.setCentreX((short) 0x3693);
        tails.setCentreY((short) 0x01DF);

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x36AF);
        Arrays.fill(yHistory, (short) 0x01D9);
        Arrays.fill(inputHistory, (short) AbstractPlayableSprite.INPUT_LEFT);
        sonic.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 48);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x06, 0, 0, 0x0002, true, 0x3784, 0x015C);

        controller.update(0x3DB3);

        assertEquals(1, controller.getDiagnosticJumpingFlag(),
                "S3K loc_13DD0 branches around loc_13E64 while Status_Push is set, so "
                        + "Tails_CPU_auto_jump_flag persists instead of clearing on that grounded frame.");
        assertEquals(0,
                controller.getDiagnosticGeneratedHeldInput() & AbstractPlayableSprite.INPUT_JUMP,
                "The same push-bypass path skips loc_13E64's A/B/C OR into Ctrl_2.");

        tails.setPushing(false);
        controller.update(0x3DC5);

        assertEquals(0, controller.getDiagnosticJumpingFlag(),
                "Once the ROM clear path has run, the auto-jump flag remains clear on later frames.");
    }

    @Test
    void s2DeadFallWaitsForTailsMaxYPlus100BeforeDespawnMarker() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1800);
        tails.setCentreY((short) 0x04FF);
        tails.setAir(true);
        tails.setYSpeed((short) 0x0200);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.DEAD_FALLING, 0);
        controller.setLevelBounds(null, null, 0x0400);

        controller.update(0x2200);

        assertEquals(SidekickCpuController.State.DEAD_FALLING, controller.getState(),
                "S2 Obj02_CheckGameOver returns while y_pos <= Tails_Max_Y_pos+$100");
        assertEquals((short) 0x1800, tails.getCentreX());
        assertTrue(controller.isDeferredDespawnDeadFallContinuingThisFrame(),
                "The movement phase owns the pre-threshold ObjectMoveAndFall step");
    }

    @Test
    void s2DeadFallAppliesDespawnMarkerAfterTailsMaxYPlus100Threshold() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1800);
        tails.setCentreY((short) 0x0501);
        tails.setSubpixelRaw(0x0000, 0x2C00);
        tails.setAir(true);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) 0x0200);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.DEAD_FALLING, 0);
        controller.setLevelBounds(null, null, 0x0400);

        controller.update(0x2201);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(),
                "Once y_pos exceeds Tails_Max_Y_pos+$100, Obj02_CheckGameOver branches to TailsCPU_Despawn");
        assertEquals((short) 0x4000, tails.getCentreX());
        assertEquals((short) 0x0002, tails.getCentreY(),
                "TailsCPU_Despawn writes y_pos=0, then Obj02_Dead continues with ObjectMoveAndFall");
        assertEquals((short) 0x0238, tails.getYSpeed());
    }

    @Test
    void s3kDeadFallWaitsForCameraYPlus100BeforeDespawnMarker() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x493F);
        tails.setCentreY((short) 0x022F);
        tails.setSubpixelRaw(0xBA00, 0xB400);
        tails.setAir(true);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) -0x0648);
        tails.setGSpeed((short) 0);
        GameServices.camera().setY((short) 0x015A);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.DEAD_FALLING, 0);
        controller.setLevelBounds(null, null, 0x015A);

        controller.update(0x47A2);

        assertEquals(SidekickCpuController.State.DEAD_FALLING, controller.getState(),
                "S3K sub_123C2 returns while y_pos <= Camera_Y_pos+$100; it does not call "
                        + "sub_13ECA yet (sonic3k.asm:24549-24565,24578)");
        assertEquals((short) 0x493F, tails.getCentreX());
        assertEquals((short) 0x022F, tails.getCentreY());
        assertEquals((short) -0x0648, tails.getYSpeed());
        assertTrue(controller.isDeferredDespawnDeadFallContinuingThisFrame(),
                "The subsequent movement phase owns the dead-fall MoveSprite_TestGravity step");
    }

    @Test
    void s3kDeadFallAppliesDespawnMarkerAfterCameraYPlus100Threshold() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x493F);
        tails.setCentreY((short) 0x0260);
        tails.setSubpixelRaw(0xBA00, 0xB400);
        tails.setAir(true);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) -0x0700);
        tails.setGSpeed((short) 0);
        GameServices.camera().setY((short) 0x015A);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.DEAD_FALLING, 0);
        controller.setLevelBounds(null, null, 0x015A);

        controller.update(0x47A3);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Once y_pos exceeds Camera_Y_pos+$100, sub_123C2 branches to sub_13ECA "
                        + "(sonic3k.asm:24565-24578,26800-26809)");
        assertEquals((short) 0x7F00, tails.getCentreX());
        assertEquals((short) -0x0007, tails.getCentreY(),
                "After sub_13ECA writes y_pos=0, loc_157C8 still runs MoveSprite_TestGravity "
                        + "with the old y_vel (sonic3k.asm:29284-29285,36032-36042)");
        assertEquals((short) -0x06C8, tails.getYSpeed());
    }

    @Test
    void s3kGenericKillAdoptsDeadRoutineAndAppliesNextFrameMarker() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0A7D);
        tails.setCentreY((short) 0x0780);
        tails.setAir(true);
        tails.setYSpeed((short) -0x0700);
        tails.setDead(true);
        GameServices.camera().setY((short) 0x066C);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.update(0x2F7B);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState());
        assertEquals((short) 0x7F00, tails.getCentreX());
        assertEquals((short) -0x0007, tails.getCentreY());
        assertEquals((short) -0x06C8, tails.getYSpeed());
        assertEquals(0x0002, controller.getDiagnosticRomCpuRoutine(),
                "sub_13ECA replaces the prior CPU routine after generic Kill_Character");
    }

    @Test
    void s3kOffscreenDestroyedRideSlotDespawnsEvenWhenInteractIdIsUnchanged() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x12BE);
        tails.setCentreY((short) 0x08A9);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(0x4E, new DestroyedRideObject(0x4E));
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x4E, false, 0, 0);
        tails.setLatchedSolidObject(0x4E, new DestroyedRideObject(0x4E));
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(2262);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "S3K sub_13EFC reads a freed ride slot as a mismatch and jumps through sub_13ECA");
        assertEquals((short) 0x7F00, tails.getCentreX());
        assertEquals((short) 0x0000, tails.getCentreY());
        assertTrue(tails.getAir());
    }

    @Test
    void s3kOffscreenUnloadedRideSlotDespawnsEvenWhenInstanceWasNotDestroyed() throws Exception {
        installEmptyObjectManager();
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x142C);
        tails.setCentreY((short) 0x0AB0);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(0x47, new UnloadedRideObject(0x47));
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x47, false, 0, 0);
        tails.setLatchedSolidObject(0x47, new UnloadedRideObject(0x47));
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(18917);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "S3K sub_13EFC compares the cached interact word with a freed slot; "
                        + "counter-window unload leaves the engine instance inactive rather than destroyed "
                        + "(sonic3k.asm:26816-26833,36116-36124)");
        assertEquals((short) 0x7F00, tails.getCentreX());
        assertEquals((short) 0x0000, tails.getCentreY());
        assertEquals((short) 0x0000, tails.getYSpeed());
        assertTrue(tails.getAir());
    }

    @Test
    void s3kDiagnosticInteractDefaultsToClearedRomWord() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);

        assertEquals(0, controller.getDiagnosticInteractId(),
                "S3K Tails_CPU_interact is cleared with active play RAM and starts at word 0 "
                        + "(docs/skdisasm/sonic3k.asm:5415,7621,26816-26843)");
    }

    @Test
    void s2DiagnosticInteractKeepsUnsetSentinelUntilFirstSnapshotRefresh() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);

        assertEquals(-1, controller.getDiagnosticInteractId(),
                "S2 keeps the engine unset sentinel until TailsCPU_UpdateObjInteract seeds "
                        + "the id snapshot used by the slot-mismatch despawn guard");
    }

    @Test
    void s3kDiagnosticInteractHydratesRecordedPointerWord() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x0004, false, 0, 0);

        assertEquals(0x0004, controller.getDiagnosticInteractId(),
                "S3K Tails_CPU_interact is the recorded pointer-word diagnostic "
                        + "rather than the S2 Tails_interact_ID object-id byte");
    }

    @Test
    void s3kDiagnosticInteractRefreshesOnCpuUpdateAfterLanding() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0, false, tails.getCentreX(), tails.getCentreY());
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(true);
        tails.setLatchedSolidObjectInstance(new AizTransitionFloorObjectInstance());

        assertEquals(0, controller.getDiagnosticInteractId(),
                "sub_13EFC samples the sidekick object state during Tails CPU control; "
                        + "a landing resolved later in the frame must not change the "
                        + "ROM-visible global until the next CPU update");

        controller.update(0);

        assertEquals(0x0004, controller.getDiagnosticInteractId(),
                "sub_13EFC refreshes Tails_CPU_interact from word 0 of the stood-on object "
                        + "SST; Obj_AIZTransitionFloor lives at 0x0004FE38 "
                        + "(sonic3k.asm:26842-26843,104777)");
    }

    @Test
    void s3kEstablishedFollowerHandoffPreservesDiagnosticInteractWord() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x0004, false, 0x2FC5, 0x037A);
        controller.forceStateForTest(SidekickCpuController.State.INIT, 0);
        controller.captureLevelStartLeaderAnchor(0x00D1, 0x02FD);
        controller.setEnteredFromSeedCompareFrame0(true);
        tails.setCentreX((short) 0x00B1);
        tails.setCentreY((short) 0x0301);
        tails.setOnObject(false);

        controller.update(0);

        assertEquals(0x0004, controller.getDiagnosticInteractId(),
                "A mid-run established-follower handoff does not run SpawnLevelMainSprites; "
                        + "S3K Tails_CPU_interact survives the AIZ1->AIZ2 reload even after "
                        + "Tails leaves the transition floor (sonic3k.asm:8359-8369,26816-26843)");
    }

    @Test
    void s3kDiagnosticInteractPreservesWordWhenStaleRideHasNoRomPointer() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x0004, false, tails.getCentreX(), tails.getCentreY());
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(true);
        tails.setLatchedSolidObject(0x30, new DestroyedRideObject(0x30));

        controller.update(0);

        assertEquals(0x0004, controller.getDiagnosticInteractId(),
                "S3K sub_13EFC only refreshes Tails_CPU_interact from a real object "
                        + "routine pointer word; stale/engine-only ride latches after a reload "
                        + "must leave the previous ROM global intact");
    }

    @Test
    void s3kDiagnosticInteractRefreshesFromCorkFloorPointerHighWord() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x0004, false, tails.getCentreX(), tails.getCentreY());
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(true);
        tails.setLatchedSolidObject(0x2A, new CorkFloorObjectInstance(
                new ObjectSpawn(0x0240, 0x033C, 0x2A, 0, 0, false, 0)));

        controller.update(0);

        assertEquals(0x0002, controller.getDiagnosticInteractId(),
                "S3K Obj_CorkFloor lives at 0x0002A618, so sub_13EFC should "
                        + "refresh Tails_CPU_interact to pointer high word 0x0002");
    }

    @Test
    void s3kDespawnMarkerPreservesDiagnosticInteractWord() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0885);
        tails.setCentreY((short) 0x033B);
        tails.setXSpeed((short) 0x0445);
        tails.setGSpeed((short) 0x0445);
        tails.setAir(false);
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x0002, false, 0x2FC5, 0x037A);

        controller.despawn(SidekickCpuController.DespawnCause.EXPLICIT);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState());
        assertEquals((short) 0x7F00, tails.getCentreX());
        assertEquals(0x0002, controller.getDiagnosticInteractId(),
                "S3K sub_13ECA writes x_pos/y_pos/status/object_control/CPU routine "
                        + "but does not clear Tails_CPU_interact (sonic3k.asm:26800-26809); "
                        + "AIZ F6255 keeps the prior 0x0002 CorkFloor pointer word "
                        + "through the marker warp");
    }

    @Test
    void s3kInteractWordProvidersMatchRomCodePointerHighWords() {
        assertEquals(0x0003,
                new DoorObjectInstance(
                        new ObjectSpawn(0x1230, 0x0350, 0x3C, 0x80, 0, false, 0))
                        .romObjectCodePointerHighWord(),
                "Obj_Door variant pointers live at 0x00030xxx-0x00031xxx, so off-screen "
                        + "Tails retains SST word 0 as 0x0003 "
                        + "(docs/skdisasm/sonic3k.asm:66036-66167,26816-26843)");
        assertEquals(0x0002,
                new Sonic3kSpringObjectInstance(
                        new ObjectSpawn(0x1888, 0x0330, 0x07, 0x12, 0, false, 0))
                        .romObjectCodePointerHighWord(),
                "Obj_Spring variant pointers live at 0x00022xxx-0x00023xxx, so off-screen "
                        + "Tails compares SST word 0 as 0x0002 "
                        + "(docs/skdisasm/sonic3k.asm:47500-47540,26816-26843)");
        assertEquals(0x0003,
                new MhzCurledVineObjectInstance(
                        new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0))
                        .romObjectCodePointerHighWord(),
                "Obj_MHZCurledVine code pointer loc_3E8A2 is at 0x0003Exxx "
                        + "(docs/skdisasm/sonic3k.asm:82778-82797)");
        assertEquals(0x0006,
                new Mhz1CutsceneButtonInstance(
                        new ObjectSpawn(0x03F7, 0x067C, 0xA9, 0, 0, false, 0))
                        .romObjectCodePointerHighWord(),
                "Obj_MHZ1CutsceneButton code pointer MHZ1CutsceneButton_Main is at 0x00062xxx "
                        + "(docs/skdisasm/sonic3k.asm:130055-130125)");
    }

    @Test
    void s3kOffscreenWordChangeDespawnsAndPreservesVelocity() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0668);
        tails.setCentreY((short) 0x0598);
        tails.setSubpixelRaw(0x0000, 0x0000);
        tails.setXSpeed((short) 0x0370);
        tails.setYSpeed((short) 0x0000);
        tails.setGSpeed((short) 0x0370);
        tails.setAir(false);
        tails.setOnObject(true);
        // Latched to the 0x0003 MHZ curled vine while off-screen; the CPU
        // interact latch still holds 0x0006 from the earlier MHZ1 cutscene
        // button ride, so sub_13EFC's off-screen word compare mismatches.
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 40, 0x0006, false, 0, 0);
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(2159);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "S3K sub_13EFC despawns when the stood-on object's code word differs from "
                        + "the Tails_CPU_interact latch (sonic3k.asm:26825-26826)");
        assertEquals((short) 0x7F00, tails.getCentreX());
        assertEquals((short) 0x0000, tails.getCentreY());
        assertEquals((short) 0x0370, tails.getXSpeed(),
                "sub_13ECA warps position but leaves x_vel untouched (sonic3k.asm:26800-26809)");
        assertEquals((short) 0x0370, tails.getGSpeed());
        assertTrue(tails.getAir());
        assertEquals(0x0006, controller.getDiagnosticInteractId(),
                "sub_13ECA does not clear Tails_CPU_interact; the prior 0x0006 latch is preserved");
    }

    @Test
    void s3kOffscreenSameWordDoesNotDespawn() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0668);
        tails.setCentreY((short) 0x0598);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        // Latch already holds the vine's own 0x0003 word: no mismatch.
        controller.hydrateFromRomCpuState(6, 0, 40, 0x0003, false, 0, 0);
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(2159);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "A matching code word keeps Tails alive (sonic3k.asm:26825 same-word fall-through)");
        assertNotEquals((short) 0x7F00, tails.getCentreX());
    }

    @Test
    void s3kOnScreenWordChangeRelatchesWithoutDespawn() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0668);
        tails.setCentreY((short) 0x0598);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x0006, false, tails.getCentreX(), tails.getCentreY());
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(true);

        controller.update(2159);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "On-screen sub_13EFC re-latches Tails_CPU_interact and returns without comparing "
                        + "(sonic3k.asm:26840-26843)");
        assertEquals(0x0003, controller.getDiagnosticInteractId(),
                "The on-screen branch refreshes the latch to the current 0x0003 vine word");
    }

    /**
     * <b>Assertion deliberately inverted.</b> This case previously asserted that a
     * cleared {@code Tails_CPU_interact} latch is "unarmed" and must NOT despawn on
     * the first stood-on object. ROM has no such precondition: once the off-screen +
     * {@code Status_OnObj} branch of {@code sub_13EFC} is taken it performs
     * {@code cmp.w (a3),d0} unconditionally (docs/skdisasm/sonic3k.asm:26816-26843),
     * and {@code Tails_CPU_interact} is zeroed with the rest of the CPU block at
     * level init ({@code clearRAM Tails_CPU_interact,$100}, sonic3k.asm:5415,7621).
     * The refresh at {@code loc_13F2E} only runs on a frame where Tails is already
     * on an object, so the FIRST off-screen on-object CPU frame always compares 0
     * against a live code-pointer high word and mismatches into {@code sub_13ECA}.
     * Witnessed by the FBZ1 sonic+tails trace at frame 116: Tails lands off-screen
     * on {@code Obj_FBZDEZPlayerLauncher} (slot 5, code $0003BA4A) with the latch
     * still 0 and ROM parks him at ($7F00, 0) in CATCH_UP_FLIGHT.
     */
    @Test
    void s3kOffscreenFirstStoodOnObjectDespawnsAgainstClearedLatch() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0668);
        tails.setCentreY((short) 0x0598);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        // Latch is the cleared 0x0000 level-init default; ROM still compares it.
        controller.hydrateFromRomCpuState(6, 0, 40, 0x0000, false, 0, 0);
        tails.setLatchedSolidObject(0x09, new MhzCurledVineObjectInstance(
                new ObjectSpawn(0x0668, 0x0598, 0x09, 0, 0, false, 0)));
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(2159);

        assertEquals(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "sub_13EFC compares the cleared (0x0000) latch against the stood-on object's "
                        + "code word unconditionally, so the first off-screen landing despawns");
        assertEquals((short) 0x7F00, tails.getCentreX(),
                "sub_13ECA writes x_pos = $7F00 (sonic3k.asm:26800-26809)");
    }

    @Test
    void interactCodeWordChangeDespawnGatedToSonic3kRulesOnly() {
        assertTrue(GameRules.SONIC_3K.objectInteraction().sidekickDespawnUsesInteractCodeWordChange(),
                "S3K sub_13EFC uses the routine-pointer word compare (sonic3k.asm:26816-26843)");
        assertFalse(GameRules.SONIC_2.objectInteraction().sidekickDespawnUsesInteractCodeWordChange(),
                "S2 TailsCPU_CheckDespawn compares the Tails_interact_ID byte, not a code word");
        assertFalse(GameRules.SONIC_1.objectInteraction().sidekickDespawnUsesInteractCodeWordChange(),
                "S1 has no CPU sidekick and never runs the word-change watchdog");
    }

    private static void installEmptyObjectManager() throws Exception {
        var field = GameServices.level().getClass().getDeclaredField("objectManager");
        field.setAccessible(true);
        field.set(GameServices.level(), new ObjectManager(List.of(), null, 0, null, null));
    }

    @Test
    void s2DestroyedRideSlotDespawnsThroughFreedObjectIdMismatch() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x12BE);
        tails.setCentreY((short) 0x08A9);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(0x4E, new DestroyedRideObject(0x4E));
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x4E, false, 0, 0);
        tails.setLatchedSolidObject(0x4E, new DestroyedRideObject(0x4E));
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(2262);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(),
                "S2 TailsCPU_CheckDespawn compares the cached interact ID against the freed slot's id byte");
        assertEquals((short) 0x4000, tails.getCentreX());
        assertEquals((short) 0x0000, tails.getCentreY());
        assertTrue(tails.getAir());
    }

    @Test
    void offscreenObjectSwitchDespawnsUsingLatchedInteractObjectId() throws Exception {
        installEmptyObjectManager();
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0545);
        tails.setCentreY((short) 0x0270);
        tails.setAir(false);
        tails.setOnObject(true);
        // Tails RODE object 0x11 via the interact slot (RideObject_SetRide,
        // s2.asm:35980-36006), so interact(a0) points at a real slot index and
        // the latched Tails_interact_ID snapshot is 0x11. Off-screen the ridden
        // object is deleted/recycled away, so the live slot now reads ROM id 0
        // (DeleteObject zeroes the object RAM, s2.asm:30324-30339). 0 != 0x11 ->
        // TailsCPU_CheckDespawn mismatch -> TailsCPU_Despawn (s2.asm:39403-39429).
        // This is the ridden-then-emptied case (interactSlotIndex >= 0), distinct
        // from a never-ridden sidekick (interactSlotIndex < 0) whose slot ROM
        // reads as the default MainCharacter id 0x01.
        UnloadedRideObject rideObj = new UnloadedRideObject(0x11);
        rideObj.setSlotIndex(0x20);
        tails.setLatchedSolidObject(0x11, rideObj);
        // The ride object is NOT added to the manager, so the live interact slot
        // dereferences to nothing (engine -1) -> ROM zeroed slot id 0.

        GameServices.camera().setX((short) 0x058C);
        GameServices.camera().setY((short) 0x0200);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 90, 0x11, false, 0, 0);
        tails.setLatchedSolidObject(0x11, rideObj);
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(1131);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState());
        assertEquals((short) 0x4000, tails.getCentreX());
        assertEquals((short) 0x0000, tails.getCentreY());
    }

    @Test
    void s2ObjectIdMismatchDespawnPreservesCachedInteractId() throws Exception {
        installEmptyObjectManager();
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x02BC);
        tails.setCentreY((short) 0x0250);
        tails.setOnObject(true);
        tails.setAir(false);
        tails.setInteractSlotIndex(0x13);
        tails.setRenderFlagOnScreen(false);

        UnloadedRideObject steamSpring = new UnloadedRideObject(0x42);
        GameServices.level().getObjectManager().addDynamicObjectAtSlot(steamSpring, 0x13);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x01, true, 0, 0);
        tails.setInteractSlotIndex(0x13);
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(375);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(),
                "S2 TailsCPU_CheckDespawn branches to TailsCPU_Despawn when "
                        + "Tails_interact_ID 0x01 differs from id(interact slot) 0x42 "
                        + "(docs/s2disasm/s2.asm:39403-39429)");
        assertEquals((short) 0x4000, tails.getCentreX());
        assertEquals((short) 0x0000, tails.getCentreY());
        assertEquals(0x01, controller.getDiagnosticInteractId(),
                "TailsCPU_Despawn writes counters/routine/object_control/status/position/anim "
                        + "but does not clear Tails_interact_ID "
                        + "(docs/s2disasm/s2.asm:39391-39400)");
    }

    @Test
    void objectIdMismatchDespawnPrefersTypedObjectInteractionRules() throws Exception {
        installEmptyObjectManager();
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        GameRules base = GameRules.SONIC_2;
        ObjectInteractionRules objectRules = base.objectInteraction();
        setGameRulesForTest(tails, withObjectInteractionRules(base, new ObjectInteractionRules(
                objectRules.bossHitNegatesGroundSpeed(),
                objectRules.bossHitHalvesBounceVelocity(),
                false,
                objectRules.sidekickNormalDespawnDelaysFreshRenderEntry(),
                objectRules.sidekickDespawnUsesRidingInstanceLoss(),
                objectRules.sidekickDespawnUsesInteractCodeWordChange(),
                objectRules.sidekickNormalCpuSkipsHurtRoutine(),
                objectRules.permanentRespawnTableLatch(),
                objectRules.objectsExecuteAfterPlayerPhysics(),
                objectRules.touchResponseUsesRenderFlagYGate(),
                objectRules.touchResponseUsesPreviousCollisionResponseList(),
                objectRules.animalObjectPreservesObjectMoveXSubpixel(),
                objectRules.animalObjectUsesRenderFlagDeleteBounds(),
                objectRules.solidPushReleaseWritesWalkRunAnimationWord(),
                objectRules.solidPushReleaseSkipsWalkRunWhenRolling(),
                objectRules.solidPushReleaseSkipsWalkRunWhenSpindashing(),
                objectRules.duckTouchBoxMappingFrame())));
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x02BC);
        tails.setCentreY((short) 0x0250);
        tails.setOnObject(true);
        tails.setAir(false);
        tails.setInteractSlotIndex(0x13);
        tails.setRenderFlagOnScreen(false);

        UnloadedRideObject steamSpring = new UnloadedRideObject(0x42);
        GameServices.level().getObjectManager().addDynamicObjectAtSlot(steamSpring, 0x13);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x01, true, 0, 0);
        tails.setInteractSlotIndex(0x13);
        tails.setOnObject(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(375);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "Typed ObjectInteractionRules should override the legacy S2 object-id mismatch despawn gate");
        assertEquals((short) 0x02BC, tails.getCentreX());
        assertEquals((short) 0x0250, tails.getCentreY());
    }

    @Test
    void renderFlagBottomMarginKeepsDespawnTimerReset() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.setCentreX((short) 0x0610);
        sonic.setCentreY((short) 0x0200);

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0610);
        tails.setCentreY((short) 0x02F0);
        tails.setAir(true);

        GameServices.camera().setX((short) 0x058C);
        GameServices.camera().setY((short) 0x0200);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 299, 0x11, false, 0, 0);
        tails.setRenderFlagOnScreen(true);

        controller.update(3532);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());
        assertNotEquals((short) 0x4000, tails.getCentreX());
        assertNotEquals((short) 0x0000, tails.getCentreY());
    }

    @Test
    void s2NormalDespawnConsumesFreshRenderEntryForAirRollingDelayedLeaderSample() {
        TestableSprite sonic = new TestableSprite("sonic");
        seedDelayedLeaderStatus(sonic,
                (byte) (AbstractPlayableSprite.STATUS_IN_AIR | AbstractPlayableSprite.STATUS_ROLLING),
                false);

        TestableSprite tails = createS2Ooz2FreshRenderEntrySidekick();
        SidekickCpuController controller = createS2Ooz2FreshRenderEntryController(tails, sonic);

        tails.setRenderFlagOnScreen(false);
        controller.update(3671);
        assertEquals(0x0078, controller.getDiagnosticRespawnCounter());

        tails.setRenderFlagOnScreen(true);
        controller.update(3672);
        assertEquals(0x0079, controller.getDiagnosticRespawnCounter(),
                "OOZ2 f3672 reads the delayed Stat_table status as air+rolling (0x06); "
                        + "S2 TailsCPU_CheckDespawn must consume this fresh render-entry one CPU tick late");

        tails.setRenderFlagOnScreen(true);
        controller.update(3673);
        assertEquals(0x0000, controller.getDiagnosticRespawnCounter());
    }

    @Test
    void s2NormalDespawnResetsFreshRenderEntryForNonAirRollingDelayedLeaderSamples() {
        byte[] protectedStatuses = {
                0,
                AbstractPlayableSprite.STATUS_ROLLING,
                AbstractPlayableSprite.STATUS_ON_OBJECT,
                AbstractPlayableSprite.STATUS_FACING_LEFT
                        | AbstractPlayableSprite.STATUS_IN_AIR
                        | AbstractPlayableSprite.STATUS_ROLLING
        };
        for (byte delayedStatus : protectedStatuses) {
            TestableSprite sonic = new TestableSprite("sonic");
            seedDelayedLeaderStatus(sonic, delayedStatus,
                    (delayedStatus & AbstractPlayableSprite.STATUS_FACING_LEFT) != 0);

            TestableSprite tails = createS2Ooz2FreshRenderEntrySidekick();
            SidekickCpuController controller = createS2Ooz2FreshRenderEntryController(tails, sonic);

            tails.setRenderFlagOnScreen(false);
            controller.update(3671);
            assertEquals(0x0078, controller.getDiagnosticRespawnCounter(),
                    "precondition failed for delayed Stat_table status " + delayedStatus);

            tails.setRenderFlagOnScreen(true);
            controller.update(3672);
            assertEquals(0x0000, controller.getDiagnosticRespawnCounter(),
                    "HTZ2/HTZ1/MTZ1-shaped delayed Stat_table statuses must reset immediately, status="
                            + delayedStatus);
        }
    }

    @Test
    void s2NormalDespawnResetsAirRollingRenderEntryAwayFromVerticalBoundary() {
        TestableSprite sonic = new TestableSprite("sonic");
        seedDelayedLeaderStatus(sonic,
                (byte) (AbstractPlayableSprite.STATUS_IN_AIR | AbstractPlayableSprite.STATUS_ROLLING),
                false);

        TestableSprite tails = createS2Ooz2FreshRenderEntrySidekick();
        tails.setCentreY((short) 0x0210);
        SidekickCpuController controller = createS2Ooz2FreshRenderEntryController(tails, sonic);

        tails.setRenderFlagOnScreen(false);
        controller.update(3671);
        tails.setRenderFlagOnScreen(true);
        controller.update(3672);

        assertEquals(0, controller.getDiagnosticRespawnCounter(),
                "A horizontal entry near the vertical centre resets immediately; the delayed "
                        + "render-entry shape requires proximity to the native lower boundary");
    }

    private static TestableSprite createS2Ooz2FreshRenderEntrySidekick() {
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0F38);
        tails.setCentreY((short) 0x0296);
        tails.setAir(true);
        tails.setRolling(true);
        GameServices.camera().setX((short) 0x0F49);
        GameServices.camera().setY((short) 0x01D3);
        return tails;
    }

    private static SidekickCpuController createS2Ooz2FreshRenderEntryController(
            TestableSprite tails,
            TestableSprite sonic) {
        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0x0077, 0x16, false, 0x08FB, 0x01AC);
        controller.forceStateForTest(SidekickCpuController.State.NORMAL, 16);
        return controller;
    }

    private static void seedDelayedLeaderStatus(TestableSprite sonic, byte delayedStatus, boolean delayedLeftInput) {
        sonic.useGameRules(GameRules.SONIC_2);
        sonic.setCentreX((short) 0x08FB);
        sonic.setCentreY((short) 0x01AC);
        sonic.resetPositionAndStatTableHistory();
        applyStatusBits(sonic, delayedStatus);
        sonic.setLogicalInputState(false, false, delayedLeftInput, false, false, false);
        sonic.recordFollowerHistoryForTick();
        sonic.endOfTick();
        applyStatusBits(sonic, (byte) 0);
        for (int i = 0; i < 16; i++) {
            sonic.endOfTick();
        }
    }

    private static void applyStatusBits(TestableSprite sprite, byte status) {
        sprite.setDirection((status & AbstractPlayableSprite.STATUS_FACING_LEFT) != 0
                ? Direction.LEFT
                : Direction.RIGHT);
        sprite.setAir((status & AbstractPlayableSprite.STATUS_IN_AIR) != 0);
        sprite.setRolling((status & AbstractPlayableSprite.STATUS_ROLLING) != 0);
        sprite.setOnObject((status & AbstractPlayableSprite.STATUS_ON_OBJECT) != 0);
    }

    @Test
    void blinkHiddenSidekickDoesNotRefreshRenderFlagFromCameraVisibility() {
        SpriteManager sprites = new SpriteManager();
        Camera camera = new Camera();
        camera.setX((short) 0x1CA1);
        camera.setY((short) 0x0360);

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCentreX((short) 0x1C8B);
        tails.setCentreY((short) 0x03C0);
        tails.setInvulnerableFrames(0x02);
        tails.setRenderFlagOnScreen(false);
        sprites.addSprite(tails);

        sprites.refreshPlayableRenderFlags(camera);

        assertFalse(tails.isRenderFlagOnScreen(),
                "ROM Tails_Display skips Draw_Sprite on blink-hidden frames, so Render_Sprites leaves bit 7 unchanged");

        tails.setInvulnerableFrames(0x04);

        sprites.refreshPlayableRenderFlags(camera);

        assertTrue(tails.isRenderFlagOnScreen(),
                "Blink-visible frames enqueue Draw_Sprite and refresh render_flags bit 7 from camera visibility");

        tails.setInvulnerableFrames(0x47);
        tails.setRenderFlagOnScreen(false);

        sprites.refreshPlayableRenderFlags(camera);

        assertFalse(tails.isRenderFlagOnScreen(),
                "AIZ f2679 stores the post-decrement timer ($47), but ROM tested pre-decrement $48 and skipped Draw_Sprite");

        tails.setInvulnerableFrames(0x46);

        sprites.refreshPlayableRenderFlags(camera);

        assertTrue(tails.isRenderFlagOnScreen(),
                "AIZ f2680 stores post-decrement $46 after ROM tested pre-decrement $47 and refreshed render_flags");
    }

    @Test
    void nonzeroInvulnerabilityTimerBlocksTouchHurt() {
        // ROM Touch_Hurt (sonic3k.asm:21044-21047) gates on
        // `tst.b invulnerability_timer(a0); bne.s Touch_ChkHurt_Return`: ANY nonzero
        // timer blocks the hit. The timer is decremented earlier in the same object
        // slot by Tails_Display (sonic3k.asm:26279-26282), so the value applyHurt sees
        // is already post-decrement; a post-decrement value of 1 is still nonzero and
        // must block. Verified against the AIZ2 miniboss trace at frame 7723: ROM keeps
        // CPU Tails following (tails_x_speed=0x008C) while the body hitbox overlaps,
        // because Tails has one invulnerability frame remaining, and only hurts at
        // frame 7724 once the timer reaches 0.
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCentreX((short) 0x11E6);
        tails.setInvulnerableFrames(1);

        assertFalse(tails.applyHurt(0x11F0),
                "A nonzero (post-decrement) invulnerability timer must block touch hurt, per ROM Touch_Hurt bne");
        assertFalse(tails.isHurt());
        assertEquals(1, tails.getInvulnerableFrames());

        // Once the timer has expired to 0, the same touch lands.
        tails.setInvulnerableFrames(0);
        assertTrue(tails.applyHurt(0x11F0),
                "With the timer at 0 the ROM Touch_Hurt bne falls through and the hit applies");
        assertTrue(tails.isHurt());
        assertEquals(0x78, tails.getInvulnerableFrames());
    }

    @Test
    void s3kHurtRoutineDoesNotAdvanceNormalDespawnTimer() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0B04);
        tails.setCentreY((short) 0x0DF2);
        tails.setAir(true);
        tails.setHurt(true);
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 299, 0, false, 0, 0);
        tails.setHurt(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(1910);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "S3K Tails_Index routine 4 dispatches the hurt/object path, not Tails_Control");
        assertEquals((short) 0x0B04, tails.getCentreX());
        assertEquals((short) 0x0DF2, tails.getCentreY());
        assertTrue(tails.getAir());
        assertEquals("sidekick_hurt_object_routine",
                controller.getLatestNormalStepDiagnostics().followBranch());
    }

    @Test
    void s3kHurtRoutineDoesNotAdvancePanicDespawnTimer() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0B04);
        tails.setCentreY((short) 0x0DF2);
        tails.setAir(true);
        tails.setHurt(true);
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x08, 0, 299, 0, false, 0, 0);
        tails.setHurt(true);
        tails.setRenderFlagOnScreen(false);

        controller.update(1910);

        assertEquals(SidekickCpuController.State.PANIC, controller.getState(),
                "S3K Tails_Index routine 4 dispatches the hurt/object path before Tails_Control, "
                        + "so PANIC must not run TailsCPU_CheckDespawn while hurt");
        assertEquals(299, controller.getDiagnosticRespawnCounter());
        assertEquals(0, controller.getDiagnosticGeneratedHeldInput(),
                "The skipped CPU path must preserve the previous Ctrl_2 logical latch");
        assertEquals((short) 0x0B04, tails.getCentreX());
        assertEquals((short) 0x0DF2, tails.getCentreY());
    }

    @Test
    void despawnTimerUsesCachedRenderFlagInsteadOfCurrentCameraGeometry() throws Exception {
        installEmptyObjectManager();
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0700);
        tails.setCentreY((short) 0x0200);
        tails.setOnObject(true);
        // The interact(a0) slot still holds the same object 0x11 (it was NOT
        // recycled or deleted), so TailsCPU_CheckDespawn's id compare matches and
        // the off-screen path falls through to the respawn timer. Model that with
        // a live same-id object in the dereferenced slot so this test isolates the
        // render-flag-vs-camera behaviour rather than an empty-slot artifact.
        UnloadedRideObject rideObj = new UnloadedRideObject(0x11);
        rideObj.setSlotIndex(0x11);
        GameServices.level().getObjectManager().addDynamicObject(rideObj);
        tails.setLatchedSolidObject(0x11, rideObj);

        GameServices.camera().setX((short) 0x05A0);
        GameServices.camera().setY((short) 0x0200);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 299, 0x11, false, 0, 0);
        tails.setLatchedSolidObject(0x11, rideObj);
        tails.setOnObject(true);

        tails.setRenderFlagOnScreen(true);
        controller.update(1);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());

        tails.setRenderFlagOnScreen(false);
        controller.update(2);
        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());
    }

    @Test
    void onScreenEmptyInteractSlotRefreshesCachedInteractIdToZero() throws Exception {
        installEmptyObjectManager();
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0399);
        tails.setCentreY((short) 0x03C2);
        tails.setAir(false);

        UnloadedRideObject oldRide = new UnloadedRideObject(0x2B);
        oldRide.setSlotIndex(0x11);
        tails.setLatchedSolidObject(0x2B, oldRide);
        tails.setOnObject(false);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(6, 0, 0, 0x2B, false, 0, 0);
        tails.setLatchedSolidObject(0x2B, oldRide);
        tails.setOnObject(false);
        tails.setRenderFlagOnScreen(true);

        controller.update(372);

        assertEquals(0, controller.getDiagnosticInteractId(),
                "S2 TailsCPU_UpdateObjInteract writes id(Object_RAM[interact]) every "
                        + "non-despawning frame; a deleted slot reads id 0 "
                        + "(docs/s2disasm/s2.asm:39435-39445)");
    }

    @Test
    void panicRoutineClearsTraceVisibleCtrl2LatchFromPreviousFollowStep() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x138C);
        tails.setCentreY((short) 0x0324);

        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) 0x1400);
        Arrays.fill(yHistory, (short) 0x0324);
        Arrays.fill(inputHistory, (short) AbstractPlayableSprite.INPUT_RIGHT);
        sonic.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x06, 0, 0x007A, 0, false, 0x1204, 0x0324);
        controller.update(0x23C6);
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, controller.getDiagnosticGeneratedHeldInput() & AbstractPlayableSprite.INPUT_RIGHT,
                "The setup should create a non-zero follow-steering Ctrl_2 latch first");

        controller.setController2Input(0, 0);
        tails.setGSpeed((short) 0x0038);
        controller.hydrateFromRomCpuState(0x08, 0, 0x007A, 0, false, 0x1204, 0x0324);
        controller.update(0x23C6);

        assertEquals(0, controller.getDiagnosticGeneratedHeldInput(),
                "TailsCPU_Panic does not reuse the previous follow-steering Ctrl_2_logical byte");
        assertEquals(0, controller.getDiagnosticGeneratedPressedInput());
    }

    @Test
    void panicRoutineWritesDownIntoTraceVisibleCtrl2LatchWhileCharging() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1444);
        tails.setCentreY((short) 0x043D);
        tails.setGSpeed((short) 0);
        tails.setSpindash(false);
        tails.setPinballMode(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x08, 0, 0x00AB, 0, false, 0x1204, 0x0324);
        controller.update(0x23F8);

        assertEquals(AbstractPlayableSprite.INPUT_DOWN, controller.getDiagnosticGeneratedHeldInput());
        assertEquals(AbstractPlayableSprite.INPUT_DOWN, controller.getDiagnosticGeneratedPressedInput(),
                "TailsCPU_Panic writes #(button_down<<8)|button_down to Ctrl_2_Logical");
    }

    @Test
    void s2PanicTreatsEnginePinballModeAsSpindashBranch() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1444);
        tails.setCentreY((short) 0x043D);
        tails.setGSpeed((short) 0x0038);
        tails.setSpindash(false);
        tails.setPinballMode(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x08, 0, 0x00AB, 0, false, 0x1204, 0x0324);
        controller.update(0x23F9);

        assertEquals(AbstractPlayableSprite.INPUT_DOWN, controller.getDiagnosticGeneratedHeldInput(),
                "S2 traces use the engine pinball-mode representation to feed TailsCPU_Panic's "
                        + "spin_dash_flag branch for the release cadence.");
        assertEquals(AbstractPlayableSprite.INPUT_DOWN, controller.getDiagnosticGeneratedPressedInput());
    }

    @Test
    void panicContinuesAfterCheckDespawnMarkerAndRewritesCtrl2Latch() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0100);
        tails.setCentreY((short) 0x0200);
        tails.setGSpeed((short) 0);
        tails.setSpindash(false);
        tails.setPinballMode(false);
        tails.setRenderFlagOnScreen(false);

        sonic.setCentreX((short) 0x0200);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x08, 0, 299, 0, false, 0, 0);
        tails.setRenderFlagOnScreen(false);

        controller.update(5);

        assertEquals(SidekickCpuController.State.SPAWNING, controller.getState(),
                "TailsCPU_CheckDespawn's timeout branch writes routine=2 via TailsCPU_Despawn");
        assertEquals((short) 0x4000, tails.getCentreX());
        assertEquals(AbstractPlayableSprite.INPUT_DOWN, controller.getDiagnosticGeneratedHeldInput(),
                "TailsCPU_Panic calls CheckDespawn with bsr; after TailsCPU_Despawn returns, "
                        + "the PANIC body still writes DOWN to Ctrl_2_Logical");
    }

    @Test
    void normalRoutineUsesDelayedLogicalJumpPressHistory() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.useGameRules(GameRules.SONIC_3K);
        sonic.setCentreX((short) 0x1200);
        sonic.setCentreY((short) 0x0324);
        sonic.setLogicalInputState(false, false, false, true, true, true);
        sonic.recordFollowerHistoryForTick();
        sonic.endOfTick();
        for (int i = 0; i < 16; i++) {
            sonic.setLogicalInputState(false, false, false, true, true, false);
            sonic.endOfTick();
        }

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0324);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x06, 0, 0, 0, false, 0, 0);
        controller.update(0x2340);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedHeldInput());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedPressedInput(),
                "TailsCPU_Normal copies the delayed Ctrl_1_Logical low-byte jump press; "
                        + "it must not reconstruct that byte from held-history edges");
    }

    @Test
    void normalRoutinePreservesRepeatedDelayedJumpPressBytesForS3k() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.useGameRules(GameRules.SONIC_3K);
        sonic.setCentreX((short) 0x1200);
        sonic.setCentreY((short) 0x0324);
        sonic.setLogicalInputState(false, false, false, true, true, true);
        sonic.recordFollowerHistoryForTick();
        sonic.endOfTick();
        sonic.endOfTick();
        for (int i = 0; i < 16; i++) {
            sonic.setLogicalInputState(false, false, false, true, true, false);
            sonic.endOfTick();
        }

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0324);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x06, 0, 0, 0, false, 0, 0);
        controller.update(0x2341);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedHeldInput());
        assertEquals(AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedPressedInput() & AbstractPlayableSprite.INPUT_JUMP,
                "S3K Stat_table stores the real low-byte Ctrl_1_Press_Logical value; "
                        + "a consecutive action press must not be reconstructed from the aggregate held edge.");
    }

    @Test
    void groundedPinballSuppressesJumpMovementButPreservesCtrl2PressDiagnostic() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.useGameRules(GameRules.SONIC_3K);
        sonic.setCentreX((short) 0x1200);
        sonic.setCentreY((short) 0x0324);
        sonic.setLogicalInputState(false, false, false, true, true, true);
        sonic.recordFollowerHistoryForTick();
        sonic.endOfTick();
        for (int i = 0; i < 16; i++) {
            sonic.setLogicalInputState(false, false, false, true, true, false);
            sonic.endOfTick();
        }

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0324);
        tails.setRolling(true);
        tails.setPinballMode(true);
        tails.setAir(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.hydrateFromRomCpuState(0x06, 0, 0, 0, false, 0, 0);
        controller.update(0x2342);

        assertTrue(controller.getInputJumpPress(),
                "TailsCPU_Normal still writes Ctrl_2_Press_Logical; Obj02_MdRoll skips "
                        + "Tails_Jump later while pinball_mode is set");
        assertEquals(AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedHeldInput() & AbstractPlayableSprite.INPUT_JUMP,
                "The movement guard must not rewrite the ROM-visible Ctrl_2_Logical byte");
        assertEquals(AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedPressedInput() & AbstractPlayableSprite.INPUT_JUMP,
                "The movement guard must not rewrite the ROM-visible Ctrl_2_Press_Logical byte");
    }

    @Test
    void s2DeadSidekickRoutinePreservesPreviousCtrl2LogicalLatch() {
        TestableSprite sonic = new TestableSprite("sonic");
        sonic.useGameRules(GameRules.SONIC_2);
        sonic.setCentreX((short) 0x1200);
        sonic.setCentreY((short) 0x0324);

        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0340);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);
        controller.setController2Input(AbstractPlayableSprite.INPUT_RIGHT, AbstractPlayableSprite.INPUT_RIGHT);
        controller.update(1);
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT,
                controller.getDiagnosticGeneratedHeldInput() & AbstractPlayableSprite.INPUT_RIGHT,
                "The setup frame seeds the ROM-visible Ctrl_2_Logical latch.");

        controller.setController2Input(0, 0);
        controller.forceStateForTest(SidekickCpuController.State.NORMAL, 16);
        tails.setDead(true);
        controller.update(2);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT,
                controller.getDiagnosticGeneratedHeldInput() & AbstractPlayableSprite.INPUT_RIGHT,
                "S2 Obj02_Dead bypasses TailsCPU_Control, so Ctrl_2_Logical must keep its previous word.");
    }

    private static void setGameRulesForTest(TestableSprite sprite, GameRules rules) throws Exception {
        var field = AbstractPlayableSprite.class.getDeclaredField("gameRules");
        field.setAccessible(true);
        field.set(sprite, rules);
    }

    private static GameRules withObjectInteractionRules(GameRules base, ObjectInteractionRules objectInteractionRules) {
        return new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                objectInteractionRules,
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble());
    }
}
