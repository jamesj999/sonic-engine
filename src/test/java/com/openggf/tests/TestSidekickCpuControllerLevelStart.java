package com.openggf.tests;

import com.openggf.game.rules.GameRules;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSidekickCpuControllerLevelStart {
    private static final int ROM_FOLLOW_DELAY_FRAMES = 16;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void firstGameplayTickUsesRomSidekickStartPlacementAndFollowInput() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x60);
        leader.setCentreY((short) 0x290);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x38);
        tails.setCentreY((short) 0x28C);
        tails.setCpuControlled(true);

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        tails.setCpuController(controller);

        controller.update(1);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());
        assertEquals(0x60, leader.getCentreX(ROM_FOLLOW_DELAY_FRAMES) & 0xFFFF);
        assertEquals(0x290, leader.getCentreY(ROM_FOLLOW_DELAY_FRAMES) & 0xFFFF);
        assertEquals(0x40, tails.getCentreX() & 0xFFFF);
        assertEquals(0x294, tails.getCentreY() & 0xFFFF);
        // TailsCPU_Init sets Tails_CPU_routine to 6 and RETURNS -- it does not fall
        // through to TailsCPU_Normal (docs/s2disasm/s2.asm:39093-39103, next label
        // TailsCPU_Spawning; dispatched via TailsCPU_States :39081-39087). S3K's
        // routine-0 entry likewise ends every path in rts (sonic3k.asm:26389-26471).
        // So the init pass sets up and steers nothing; the first follow input is
        // produced on the NEXT pass, once the dispatcher reaches routine 6. This
        // assertion previously required the opposite, pinning a fall-through the
        // ROM does not perform.
        assertFalse(controller.getInputRight(),
                "TailsCPU_Init returns without steering; follow input starts on the next pass");

        controller.update(2);
        assertTrue(controller.getInputRight(),
                "the pass after init dispatches TailsCPU_Normal and steers");
    }

    @Test
    void delayedHeldDirectionDoesNotReportCtrl2PressedLogical() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x100);
        leader.setCentreY((short) 0x200);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x100);
        tails.setCentreY((short) 0x200);
        tails.setCpuControlled(true);

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        controller.update(17);

        assertTrue(controller.getInputRight(), "Delayed held RIGHT should remain visible as Ctrl_2_Held_Logical");
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, controller.getDiagnosticGeneratedHeldInput());
        assertEquals(0, controller.getDiagnosticGeneratedPressedInput(),
                "Copied delayed held input must not masquerade as Ctrl_2_Press_Logical");
    }

    @Test
    void followSteeringOverrideReportsCtrl2PressedLogicalDirection() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x200);
        leader.setCentreY((short) 0x200);
        seedLeaderHistory(leader, 0, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x100);
        tails.setCentreY((short) 0x200);
        tails.setCpuControlled(true);

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        controller.update(17);

        assertTrue(controller.getInputRight(), "Follow steering should synthesize RIGHT when the leader is ahead");
        assertFalse(controller.getInputLeft());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, controller.getDiagnosticGeneratedHeldInput());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, controller.getDiagnosticGeneratedPressedInput(),
                "ROM TailsCPU_Normal_FollowRight writes RIGHT into both Ctrl_2 logical bytes");
    }

    @Test
    void s2GroundedRidingPushGraceBypassesFollowSteering() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C70);
        leader.setCentreY((short) 0x0571);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE3);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(true);
        tails.setGSpeed((short) 0x000C);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        controller.update(1774);

        tails.setPushing(false);
        tails.setOnObject(true);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());
        controller.update(1775);

        assertFalse(controller.getInputLeft(),
                "S2 TailsCPU_Normal should not synthesize LEFT while the ROM-visible prior-frame "
                        + "Status_Push bypass is represented by grounded riding-object grace");
        assertTrue(controller.getInputRight(),
                "The bypass should preserve the delayed Ctrl_1 RIGHT sample that Tails_InputAcceleration_Path consumes");
        assertEquals("riding_push_grace", controller.getLatestNormalStepDiagnostics().followBranch(),
                "Diagnostic branch should identify the live-riding-object push bridge");
    }

    @Test
    void s2AgedObj36RidingPushGraceStillPreservesDelayedRightAtOozFrontier() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C78);
        leader.setCentreY((short) 0x0574);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE3);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setGSpeed((short) 0xFF80);
        tails.setXSpeed((short) 0xFF80);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 11);
        controller.update(1779);

        assertEquals("riding_push_grace", controller.getLatestNormalStepDiagnostics().followBranch(),
                "S2 Obj36 keeps its SolidObject push state visible to TailsCPU_Normal long enough "
                        + "for the OOZ f1779 push-bypass branch.");
        assertFalse(controller.getInputLeft(),
                "FollowLeft must not replace the delayed Ctrl_1 sample while Obj36 preserves the push bridge");
        assertTrue(controller.getInputRight(),
                "The bypass preserves delayed RIGHT, allowing Tails_TurnRight to flip inertia to +$80");
    }

    @Test
    void s2AgedObj36RidingPushGraceFallsThroughWhileStillMovingRightBeforeOozFlip() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C78);
        leader.setCentreY((short) 0x0574);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE4);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setGSpeed((short) 0x0030);
        tails.setXSpeed((short) 0x0030);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 12);
        controller.update(1778);

        assertEquals("follow_steering", controller.getLatestNormalStepDiagnostics().followBranch(),
                "OOZ f1778 still falls through FollowLeft before the Obj36 side response flips Tails to -$80.");
        assertTrue(controller.getInputLeft());
        assertFalse(controller.getInputRight());
    }

    @Test
    void s2FreshNegativeObj36RidingPushGracePreservesDelayedRightAtOozFrontier() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C78);
        leader.setCentreY((short) 0x0574);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE3);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setDirection(Direction.LEFT);
        tails.setGSpeed((short) 0xFFE8);
        tails.setXSpeed((short) 0xFFE8);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 0);
        controller.update(1794);

        assertEquals("riding_push_grace", controller.getLatestNormalStepDiagnostics().followBranch(),
                "Obj36 inner-left-edge negative inertia keeps the live SolidObject status byte visible "
                        + "before TailsCPU_Normal can synthesize FollowLeft.");
        assertFalse(controller.getInputLeft());
        assertTrue(controller.getInputRight(),
                "Delayed RIGHT should reach Tails_TurnRight and flip inertia through the +$80 turn path");
    }

    @Test
    void s2StationaryObj36RidingPushGracePreservesDelayedRightAtOozFrontier() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C78);
        leader.setCentreY((short) 0x0574);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE3);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setDirection(Direction.LEFT);
        tails.setGSpeed((short) 0);
        tails.setXSpeed((short) 0);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 6);
        controller.update(1785);

        assertEquals("riding_push_grace", controller.getLatestNormalStepDiagnostics().followBranch(),
                "Stationary Obj36 ride keeps the live SolidObject push bit visible to TailsCPU_Normal "
                        + "for the zero-inertia OOZ handoff.");
        assertFalse(controller.getInputLeft());
        assertTrue(controller.getInputRight(),
                "Delayed RIGHT should reach Tails_MoveRight's ordinary +$0C acceleration path");
    }

    @Test
    void s2RightFacingStationaryObj36RidingPushGraceStillFallsThroughBeforeOozFlip() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C78);
        leader.setCentreY((short) 0x0574);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE4);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setDirection(Direction.RIGHT);
        tails.setGSpeed((short) 0);
        tails.setXSpeed((short) 0);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 9);
        controller.update(1782);

        assertEquals("follow_steering", controller.getLatestNormalStepDiagnostics().followBranch(),
                "Right-facing stationary Obj36 state must not use the later left-facing push bridge.");
        assertTrue(controller.getInputLeft());
        assertFalse(controller.getInputRight());
    }

    @Test
    void s2LowGracePositiveObj36RidingPushGraceContinuesOozAccelerationLadder() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C78);
        leader.setCentreY((short) 0x0574);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE3);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setDirection(Direction.RIGHT);
        tails.setGSpeed((short) 0x000C);
        tails.setXSpeed((short) 0x000C);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 5);
        controller.update(1786);

        assertEquals("riding_push_grace", controller.getLatestNormalStepDiagnostics().followBranch(),
                "Late low-grace Obj36 positive-speed samples keep delayed RIGHT visible after the zero-speed handoff.");
        assertFalse(controller.getInputLeft());
        assertTrue(controller.getInputRight());
    }

    @Test
    void s2LateThirtySpeedObj36RidingPushGraceCompletesOozAccelerationLadder() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x0C78);
        leader.setCentreY((short) 0x0574);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x0CE3);
        tails.setCentreY((short) 0x0574);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setDirection(Direction.RIGHT);
        tails.setGSpeed((short) 0x0030);
        tails.setXSpeed((short) 0x0030);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject());

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 3);
        controller.update(1789);

        assertEquals("riding_push_grace", controller.getLatestNormalStepDiagnostics().followBranch(),
                "The late branch-time $30 sample still sees Obj36's status byte before the bridge expires.");
        assertFalse(controller.getInputLeft());
        assertTrue(controller.getInputRight());
    }

    @Test
    void s2WideLeftEdgeObj36LowSpeedPushGraceFallsThrough() throws Exception {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x113D);
        leader.setCentreY((short) 0x026C);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT, false);

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x115A);
        tails.setCentreY((short) 0x0278);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setAir(false);
        tails.setOnObject(true);
        tails.setRolling(false);
        tails.setPushing(false);
        tails.setDirection(Direction.RIGHT);
        tails.setGSpeed((short) 0x0018);
        tails.setXSpeed((short) 0x0018);
        tails.setLatchedSolidObject(0x36, new DummyRidingObject(0x1170, 0x0298));

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        setNormalPushingGraceFrames(controller, 5);
        controller.update(4224);

        assertEquals("follow_steering", controller.getLatestNormalStepDiagnostics().followBranch(),
                "Obj36 low-speed riding grace is only ROM-visible near the inner solid edge; "
                        + "the wider left-edge contact should fall through to follow steering.");
        assertTrue(controller.getInputLeft());
        assertFalse(controller.getInputRight());
    }

    @Test
    void delayedDirectionalEdgeReportsCtrl2PressedLogical() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x100);
        leader.setCentreY((short) 0x200);
        seedLeaderHistory(leader, 0, false);
        recordLeaderHistory(leader, AbstractPlayableSprite.INPUT_LEFT, false);
        for (int i = 0; i < ROM_FOLLOW_DELAY_FRAMES; i++) {
            recordLeaderHistory(leader, 0, false);
        }

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x100);
        tails.setCentreY((short) 0x200);
        tails.setCpuControlled(true);

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        controller.update(17);

        assertTrue(controller.getInputLeft(), "Delayed held LEFT should remain visible as Ctrl_2_Held_Logical");
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, controller.getDiagnosticGeneratedHeldInput());
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, controller.getDiagnosticGeneratedPressedInput(),
                "Copied delayed Ctrl_1_Logical must preserve directional press edges from the stat table");
    }

    @Test
    void hurtObjectRoutineKeepsCtrl2LogicalDiagnosticLatched() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        leader.setCentreX((short) 0x100);
        leader.setCentreY((short) 0x200);
        seedLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP, false);
        recordLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP, true);
        for (int i = 0; i < ROM_FOLLOW_DELAY_FRAMES; i++) {
            recordLeaderHistory(leader, AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP, false);
        }

        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0, (short) 0);
        tails.setCentreX((short) 0x100);
        tails.setCentreY((short) 0x200);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        controller.setInitialState(SidekickCpuController.State.NORMAL);
        controller.update(17);
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedHeldInput());
        assertEquals(AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedPressedInput() & AbstractPlayableSprite.INPUT_JUMP);

        tails.setHurt(true);
        controller.update(18);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedHeldInput(),
                "S2 Obj02_Hurt bypasses Obj02_Control, so Ctrl_2_Logical keeps its last CPU-written word");
        assertEquals(AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedPressedInput() & AbstractPlayableSprite.INPUT_JUMP,
                "The comparison-only pressed byte should remain latched while the hurt routine bypasses CPU writes");
    }

    @Test
    void levelBoundaryKillPreservesRomOnObjectBitOnEntryFrame() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0x29D, (short) 0x749);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setOnObject(true);
        tails.setAir(false);
        tails.setPushing(true);
        tails.setRollingJump(true);
        tails.setJumping(true);
        tails.setXSpeed((short) 0x120);
        tails.setYSpeed((short) 0x40);
        tails.setGSpeed((short) 0x120);

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        tails.setCpuController(controller);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.despawn(SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);

        assertEquals(SidekickCpuController.State.DEAD_FALLING, controller.getState());
        assertTrue(tails.isOnObject(),
                "S2 KillCharacter calls Tails_ResetOnFloor_Part2, which clears in_air/pushing/rolljumping "
                        + "but does not clear Status_OnObj before setting Status_InAir");
        assertTrue(tails.getAir(), "KillCharacter sets Status_InAir after ResetOnFloor_Part2");
        assertFalse(tails.getPushing(), "ResetOnFloor_Part2 clears Status_Push");
        assertFalse(tails.getRollingJump(), "ResetOnFloor_Part2 clears Status_RollJump");
        assertFalse(tails.isJumping(), "ResetOnFloor_Part2 clears jumping");
        assertEquals(0, tails.getXSpeed(), "KillCharacter clears x_vel");
        assertEquals((short) -0x700, tails.getYSpeed(), "KillCharacter writes y_vel=-$700");
        assertEquals(0, tails.getGSpeed(), "KillCharacter clears ground_vel");
        assertEquals(0x18, tails.getAnimationId(),
                "KillCharacter publishes Tails' Death animation in the boundary-kill frame");
        assertEquals(0x18, tails.getForcedAnimationId(),
                "dead-fall updates retain the Death animation owner after the kill frame");
    }

    @Test
    void deadSidekickClearsStaleOnObjectAfterLeavingVisibleWindowAgain() {
        TestablePlayableSprite leader = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestableTailsSprite tails = new TestableTailsSprite("tails_p2", (short) 0x29D, (short) 0x700);
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setDead(true);
        tails.setAir(true);
        tails.setOnObject(true);
        tails.currentCamera().setY((short) 0x640);

        SidekickCpuController controller = new SidekickCpuController(tails, leader);
        tails.setCpuController(controller);
        controller.setInitialState(SidekickCpuController.State.NORMAL);

        controller.update(1);
        assertTrue(tails.isOnObject(),
                "Dead-fall support should not clear before Tails has re-entered the visible vertical window");

        tails.setCentreY((short) 0x6F0);
        controller.update(2);
        assertTrue(tails.isOnObject(), "Re-entering the visible window only arms the stale-support release");

        tails.setCentreY((short) 0x6FF);
        controller.update(3);
        assertFalse(tails.isOnObject(),
                "After re-entering the visible window, leaving it again clears the stale Status_OnObj bit");
    }

    private static void seedLeaderHistory(TestablePlayableSprite leader, int heldMask, boolean jumpPress) {
        for (int i = 0; i < 64; i++) {
            recordLeaderHistory(leader, heldMask, jumpPress);
        }
    }

    private static void recordLeaderHistory(TestablePlayableSprite leader, int heldMask, boolean jumpPress) {
        leader.setLogicalInputState(
                (heldMask & AbstractPlayableSprite.INPUT_UP) != 0,
                (heldMask & AbstractPlayableSprite.INPUT_DOWN) != 0,
                (heldMask & AbstractPlayableSprite.INPUT_LEFT) != 0,
                (heldMask & AbstractPlayableSprite.INPUT_RIGHT) != 0,
                (heldMask & AbstractPlayableSprite.INPUT_JUMP) != 0,
                jumpPress);
        leader.endOfTick();
    }

    private static void setNormalPushingGraceFrames(SidekickCpuController controller, int frames) throws Exception {
        Field field = SidekickCpuController.class.getDeclaredField("normalPushingGraceFrames");
        field.setAccessible(true);
        field.setInt(controller, frames);
    }

    private static final class DummyRidingObject implements ObjectInstance, SolidObjectProvider {
        private final ObjectSpawn spawn;

        private DummyRidingObject() {
            this(0x0CF0, 0x0594);
        }

        private DummyRidingObject(int x, int y) {
            this.spawn = new ObjectSpawn(x, y, 0x36, 0, 0, false, y);
        }

        @Override
        public ObjectSpawn getSpawn() {
            return spawn;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return new SolidObjectParams(0x1B, 0x10, 0x11);
        }

        @Override
        public boolean preservesSidekickCpuPushGraceWhileRiding(PlayableEntity player) {
            return true;
        }

        @Override
        public int sidekickCpuPushGraceMinimumFramesWhileRiding(PlayableEntity player) {
            if (player == null) {
                return Integer.MAX_VALUE;
            }
            int gSpeed = player.getGSpeed();
            if (!usesInnerLeftEdgeSidekickPushGraceLadder(player)) {
                return gSpeed < 0 ? 11 : 14;
            }
            return gSpeed == -0x80 || isFreshNegativeTurnBridge(player) ? 0
                    : gSpeed == 0 && player.getXSpeed() == 0 && player.getDirection() == Direction.LEFT ? 6
                    : gSpeed > 0 && gSpeed < 0x30 ? 2
                    : gSpeed == 0x30 ? 2
                    : gSpeed < 0 ? 11 : 14;
        }

        @Override
        public int sidekickCpuPushGraceMaximumFramesWhileRiding(PlayableEntity player) {
            if (player == null) {
                return Integer.MIN_VALUE;
            }
            if (!usesInnerLeftEdgeSidekickPushGraceLadder(player)) {
                return Integer.MAX_VALUE;
            }
            int gSpeed = player.getGSpeed();
            return gSpeed == 0x30 ? 3
                    : Integer.MAX_VALUE;
        }

        private boolean usesInnerLeftEdgeSidekickPushGraceLadder(PlayableEntity player) {
            return player.getCentreX() - spawn.x() >= -0x10;
        }

        private boolean isFreshNegativeTurnBridge(PlayableEntity player) {
            int gSpeed = player.getGSpeed();
            return gSpeed <= -0x18
                    && gSpeed >= -0x80
                    && player.getDirection() == Direction.LEFT
                    && player.getXSpeed() == gSpeed;
        }
    }
}
