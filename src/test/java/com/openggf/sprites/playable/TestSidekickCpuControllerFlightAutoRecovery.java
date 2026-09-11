package com.openggf.sprites.playable;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SidekickCpuController.FLIGHT_AUTO_RECOVERY (ROM routine 0x04,
 * Tails_FlySwim_Unknown at sonic3k.asm:26534).
 */
class TestSidekickCpuControllerFlightAutoRecovery {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) { super(code, (short) 0, (short) 0); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
        @Override protected void createSensorLines() {}
        void useGameRules(GameRules featureSet) {
            super.setGameRulesForTest(featureSet);
        }
    }

    static class GameplayWaterLineSystem extends WaterSystem {
        @Override
        public int getGameplayWaterLevelY(int zoneId, int actId) {
            return 0x0520;
        }
    }

    private TestableSprite sonicAt(int x, int y) {
        return sonicAtWithStatus(x, y, (byte) 0);
    }

    private TestableSprite sonicAtWithStatus(int x, int y, byte status) {
        return sonicAtWithHistory(x, y, (short) 0, status);
    }

    private TestableSprite sonicAtWithHistory(int x, int y, short input, byte status) {
        TestableSprite sonic = new TestableSprite("sonic");
        short[] xHistory = new short[64];
        short[] yHistory = new short[64];
        short[] inputHistory = new short[64];
        byte[] statusHistory = new byte[64];
        Arrays.fill(xHistory, (short) x);
        Arrays.fill(yHistory, (short) y);
        Arrays.fill(inputHistory, input);
        Arrays.fill(statusHistory, status);
        sonic.hydrateRecordedHistory(xHistory, yHistory, inputHistory, statusHistory, 16);
        sonic.setCentreX((short) x);
        sonic.setCentreY((short) y);
        return sonic;
    }

    @Test
    void flightSteersXByDistanceOver16ClampedTo0xC() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1F00);   // 0xF00 away from Sonic (to the right)
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);
        tails.setDoubleJumpFlag(1);
        // Tails_FlySwim_Unknown does NOT apply the -0x20 lead offset
        // (that's a NORMAL-routine adjustment at loc_13DA6, not here).
        // Sonic.x_vel defaults to 0 so the test doesn't pull in the
        // "speed match" term; step = clamp(|dx|>>4, 0xC) + |0| + 1 = 0xD.

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        // targetX = 0x1000. |dx| = 0xF00, |dx| >> 4 = 0xF0, clamped to 0xC;
        // +0 (Sonic idle) +1 = 0xD.
        // Tails is to the right of Sonic, so X decreases by 0xD.
        assertEquals(0x1EF3, tails.getCentreX() & 0xFFFF,
                "X steps toward Sonic by (clamp(|dx|>>4, 0xC) + |Sonic.x_vel| + 1) = 0xD");
        assertEquals(Direction.LEFT, tails.getDirection(),
                "ROM loc_13C98 sets Status_Facing when routine 4 steers Tails left toward the target");
    }

    @Test
    void flightClampsDelayedTargetYToGameplayWaterLine() {
        GameplayModeContext mode = TestEnvironment.activeGameplayMode();
        mode.attachLevelManagers(new GameplayWaterLineSystem(), mode.getParallaxManager(),
                mode.getTerrainCollisionManager(), mode.getCollisionSystem(),
                mode.getSpriteManager(), mode.getLevelManager());

        TestableSprite sonic = sonicAt(0x1000, 0x0600);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0500);
        tails.setAir(true);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertEquals(0x0510, controller.targetY(),
                "TailsCPU_Flying_Part2 clamps target_y to Water_Level_1-$10");
    }

    @Test
    void s3kFlightPublishesDelayedTargetYBelowGameplayWaterLine() {
        GameplayModeContext mode = TestEnvironment.activeGameplayMode();
        mode.attachLevelManagers(new GameplayWaterLineSystem(), mode.getParallaxManager(),
                mode.getTerrainCollisionManager(), mode.getCollisionSystem(),
                mode.getSpriteManager(), mode.getLevelManager());

        TestableSprite sonic = sonicAt(0x1000, 0x0600);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0500);
        tails.setAir(true);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertEquals(0x0600, controller.targetY(),
                "Tails_FlySwim_Unknown publishes Pos_table Y without S2's water clamp");
    }

    @Test
    void flightTimerRollsBackToCatchUpAfter300FramesOffscreen() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x3000);   // Far off-screen
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);
        // setRenderFlagOnScreen(boolean) sets renderFlagOnScreenValid=true internally.
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        for (int i = 0; i < 5 * 60; i++) {
            // Re-apply off-screen each tick since the controller is allowed to
            // change the sprite's render state (it doesn't, but defensive).
            tails.setRenderFlagOnScreen(false);
            controller.update(i);
        }

        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "After 5s off-screen, FLIGHT_AUTO_RECOVERY rolls back to CATCH_UP_FLIGHT");
    }

    @Test
    void diagnosticRespawnCounterMirrorsFlightTimerDuringFlightAutoRecovery() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x3000);
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertEquals(1, controller.getDiagnosticRespawnCounter(),
                "Routine 0x04 exposes ROM Tails_CPU_flight_timer, not the respawn/despawn counter");
    }

    @Test
    void onScreenFlightAutoRecoveryReassertsAirBit() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0300);
        tails.setYSpeed((short) -0x0800);
        tails.setDoubleJumpProperty((byte) 0xF0);
        tails.setAir(false);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertTrue(tails.getAir(),
                "ROM Tails_FlySwim_Unknown loc_13C3A ORs Status_InAir every on-screen frame");
        assertEquals(0x21, tails.getAnimationId(),
                "Tails_Set_Flying_Animation selects the ascending flight byte from negative y_vel");
        assertEquals(0x21, tails.getForcedAnimationId());
    }

    @Test
    void flightTransitionsToNormalWhenCloseEnoughAndSonicAlive() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);   // Already aligned horizontally
        tails.setCentreY((short) 0x0400);   // Already aligned vertically
        tails.setAir(true);
        tails.setDoubleJumpFlag(1);         // Flight gravity active during catch-up
        tails.setControlLocked(true);       // routine 4 carries object_control=$81 until NORMAL transition
        tails.setObjectControlAllowsCpu(true);
        tails.setObjectControlSuppressesMovement(true);
        tails.setDirection(Direction.LEFT);
        tails.setAnimationId(0x20);
        tails.setForcedAnimationId(0x20);
        // On-screen so the off-screen timer doesn't fire.
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.NORMAL, controller.getState(),
                "Tails aligned with Sonic + Sonic alive = transition to NORMAL (routine 0x06)");
        assertFalse(tails.isObjectControlled(),
                "Transition clears Tails's object_control");
        assertFalse(tails.isObjectControlAllowsCpu(),
                "Transition clears the object-control CPU allowance mirror");
        assertFalse(tails.isObjectControlSuppressesMovement(),
                "Transition clears the object-control movement suppression mirror");
        assertFalse(tails.isControlLocked(),
                "Transition clears the engine control-lock mirror so NORMAL CPU input reaches movement");
        assertEquals(-1, tails.getForcedAnimationId(),
                "loc_13CD2 releases the recovery-flight animation owner");
        assertEquals(Direction.RIGHT, tails.getDirection(),
                "ROM loc_13CD2 masks status down to Status_InAir plus underwater, clearing Status_Facing");
        assertEquals(0, tails.getDoubleJumpFlag(),
                "Transition clears Tails's double_jump_flag so NORMAL runs with normal air "
                        + "gravity (+0x38) instead of the FLY-gated flight gravity (+0x08). ROM "
                        + "loc_1384A (sonic3k.asm:26213) auto-clears the flag while "
                        + "object_control bit 0 is set; the engine's NORMAL transition clears "
                        + "object_control, so we must clear double_jump_flag explicitly.");
    }

    @Test
    void flightTimerCarriesIntoNormalRespawnCounterWord() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);
        tails.setRenderFlagOnScreen(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.NORMAL, controller.getState());
        assertEquals(1, controller.getDiagnosticRespawnCounter(),
                "Routine 4 to 6 preserves the shared Tails_CPU_flight_timer word");

        tails.setRenderFlagOnScreen(false);
        controller.update(11);

        assertEquals(2, controller.getDiagnosticRespawnCounter(),
                "Routine 6 continues incrementing that same shared word off-screen");
    }

    @Test
    void flightTransitionUsesDelayedStatTableInsteadOfLiveObjectControl() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        sonic.setObjectControlled(true);
        sonic.setObjectControlSuppressesMovement(true);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);
        tails.setControlLocked(true);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.NORMAL, controller.getState(),
                "S3K Tails_FlySwim_Unknown gates landing on delayed Stat_table bit 7 "
                        + "(sonic3k.asm:26623-26631), not live Sonic object_control.");
        assertFalse(tails.isObjectControlled(), "Routine 4 to 6 handoff clears Tails object_control");
    }

    @Test
    void flightTransitionIsBlockedByDelayedS3kStatusBit7() {
        TestableSprite sonic = sonicAtWithStatus(0x1000, 0x0400,
                AbstractPlayableSprite.STATUS_PREVENT_TAILS_RESPAWN);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);
        tails.setControlLocked(true);
        tails.setObjectControlled(true);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "S3K routine 4 tests delayed Stat_table bit 7 before returning to NORMAL "
                        + "(sonic3k.asm:26623-26630).");
        assertTrue(tails.isObjectControlled(), "Blocked handoff keeps object_control=$81");
    }

    @Test
    void normalCpuSkipsFollowSteeringWhileLeaderStatusTertiaryBit7Set() {
        TestableSprite sonic = sonicAtWithHistory(0x1000, 0x0400,
                (short) AbstractPlayableSprite.INPUT_RIGHT, (byte) 0);
        sonic.setWallCling(true);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0F00);
        tails.setCentreY((short) 0x0400);
        tails.setAir(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.NORMAL, 20);

        controller.update(3778);

        assertEquals("leader_status_tertiary_bit7",
                controller.getLatestNormalStepDiagnostics().followBranch(),
                "S3K loc_13D78 skips Tails normal CPU control while Player_1 "
                        + "status_tertiary bit 7 is set (sonic3k.asm:26672-26675).");
    }

    @Test
    void flightTransitionPreservesWaterSpeedConstantsAfterDirectStatusMask() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        sonic.setInWater(false);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.applyExternalPhysicsProfile(com.openggf.game.PhysicsProfile.SONIC_2_TAILS);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0400);
        tails.setInWater(true);
        tails.clearUnderwaterStatusPreserveWaterPhysics();
        tails.setAir(true);
        tails.setControlLocked(true);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.NORMAL, controller.getState(),
                "Aligned routine 4 returns to NORMAL");
        assertFalse(tails.isInWater(),
                "loc_13CD2 leaves Status_Underwater clear when it was already clear");
        assertEquals(6, tails.getRunAccel(),
                "loc_13CD2 does not run Tails_Water exit, so Acceleration_P2 remains underwater");
    }

    @Test
    void flightTransitionPreservesExistingUnderwaterStatus() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        sonic.setInWater(false);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.applyExternalPhysicsProfile(com.openggf.game.PhysicsProfile.SONIC_2_TAILS);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0400);
        tails.setInWater(true);
        tails.setAir(true);
        tails.setControlLocked(true);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.NORMAL, controller.getState(),
                "Aligned routine 4 returns to NORMAL");
        assertTrue(tails.isInWater(),
                "loc_13CD2 preserves Status_Underwater when it was already set on Tails");
        assertEquals(6, tails.getRunAccel(),
                "loc_13CD2 preserves underwater speed constants");
    }

    @Test
    void flightDoesNotTransitionToNormalOnSameFrameYReachesTarget() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0401);
        tails.setAir(true);
        tails.setControlLocked(true);
        tails.setObjectControlled(true);
        tails.setRenderFlagOnScreen(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertEquals(0x0400, tails.getCentreY() & 0xFFFF,
                "Routine 4 moves Y by one pixel toward the target");
        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "ROM loc_13CBE leaves d1 non-zero when Y only became aligned after the +/-1 step");
        assertTrue(tails.isObjectControlled(), "object_control=$81 remains set until the following aligned frame");
        assertTrue(tails.getAir(), "flight recovery remains airborne while object-controlled");
    }

    @Test
    void offscreenFlightContinuationClearsPriorObjectMappingOwnership() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x1100);
        tails.setCentreY((short) 0x0300);
        tails.setAir(false);
        tails.setRenderFlagOnScreen(false);
        tails.setAnimationId(0);
        tails.setForcedAnimationId(-1);
        tails.setObjectMappingFrameControl(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState());
        assertEquals(0, tails.getAnimationId(),
                "off-screen routine 4 preserves an animation byte written by a later object slot");
        assertEquals(-1, tails.getForcedAnimationId(),
                "off-screen routine 4 does not call Tails_Set_Flying_Animation");
        assertFalse(tails.isObjectMappingFrameControl(),
                "loc_13D42 writes object_control=$81, clearing bit 1 before Animate_Tails");
    }

    @Test
    void flightSteersNegativeYWordDownTowardPositiveTarget() {
        TestableSprite sonic = sonicAt(0x116C, 0x0080);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x116C);
        tails.setCentreY((short) 0xFFD9);
        tails.setAir(true);
        tails.setControlLocked(true);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, 0);

        controller.update(10);

        assertEquals(0xFFDA, tails.getCentreY() & 0xFFFF,
                "Tails_FlySwim_Unknown uses signed word flags after y_pos-target_Y; "
                        + "0xFFD9 is above the positive target and moves down by +1 "
                        + "(sonic3k.asm:26614-26622)");
    }

    @Test
    void normalTransitionsToFlightAutoRecoveryWhenLeaderDies() {
        TestableSprite sonic = sonicAt(0x1000, 0x0400);
        sonic.setDead(true);
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.useGameRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setCentreX((short) 0x0F00);
        tails.setCentreY((short) 0x0400);
        tails.setObjectControlAllowsCpu(true);
        tails.setObjectControlSuppressesMovement(false);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.NORMAL, 20);

        controller.update(10);

        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "Dead Sonic drives Tails into flight AI (routine 0x04)");
        assertEquals(1, tails.getDoubleJumpFlag(),
                "Flight transition sets double_jump_flag=1 so flight gravity applies");
        assertTrue(tails.getAir(), "Flight transition sets air bit");
        assertTrue(tails.isObjectControlled(), "Dead Sonic recovery writes object_control=$81");
        assertFalse(tails.isObjectControlAllowsCpu(),
                "object_control=$81 must clear stale bits-0-to-6 CPU allowance");
        assertTrue(tails.isObjectControlSuppressesMovement(),
                "object_control=$81 must suppress normal movement");
    }
}
