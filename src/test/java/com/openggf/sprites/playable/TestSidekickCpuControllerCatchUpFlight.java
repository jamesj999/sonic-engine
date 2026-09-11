package com.openggf.sprites.playable;

import com.openggf.game.GameServices;
import com.openggf.game.rules.GameRules;
import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SidekickCpuController.CATCH_UP_FLIGHT (ROM routine 0x02,
 * Tails_Catch_Up_Flying at sonic3k.asm:26474).
 */
class TestSidekickCpuControllerCatchUpFlight {

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
        public void setGameRulesForTest(GameRules featureSet) { super.setGameRulesForTest(featureSet); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
        @Override protected void createSensorLines() {}
    }

    static class RecordingRunInStrategy implements SidekickRespawnStrategy {
        boolean began;

        @Override
        public boolean beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
            began = true;
            sidekick.setCentreX((short) 0x1234);
            sidekick.setCentreY((short) 0x0456);
            sidekick.setControlLocked(false);
            sidekick.setForcedAnimationId(-1);
            ObjectControlState.none().applyTo(sidekick);
            return true;
        }

        @Override
        public boolean updateApproaching(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader,
                                         int frameCounter) {
            return false;
        }

        @Override
        public boolean requiresPhysics() {
            return true;
        }
    }

    @Test
    void catchUpTeleportsTailsToSonicMinus0xC0Y_onCtrl2ABCPress() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x0400);
        tails.setCentreX((short) 0x0200);  // Far away
        tails.setCentreY((short) 0x0500);
        tails.setObjectControlAllowsCpu(true);
        tails.setObjectControlSuppressesMovement(false);
        tails.setFlipType(3);
        tails.setFlipsRemaining(2);
        tails.setFlipSpeed(8);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);
        controller.setController2Input(0, AbstractPlayableSprite.INPUT_JUMP);  // A/B/C press

        controller.update(0);

        assertEquals(0x1000, tails.getCentreX() & 0xFFFF,
                "Catch-up teleport writes Sonic.x to Tails");
        assertEquals(0x0340, tails.getCentreY() & 0xFFFF,
                "Catch-up teleport writes Sonic.y - 0xC0 to Tails");
        assertEquals((short) 0, tails.getXSpeed(), "Velocities zeroed");
        assertEquals((short) 0, tails.getYSpeed(), "Velocities zeroed");
        assertEquals((short) 0, tails.getGSpeed(), "Velocities zeroed");
        assertEquals(0, tails.getDoubleJumpFlag(),
                "ROM loc_13B50 clears double_jump_flag; catch-up flight is CPU/object-control driven");
        assertEquals(0, tails.getFlipType());
        assertEquals(0, tails.getFlipsRemaining());
        assertEquals(0, tails.getFlipSpeed());
        assertTrue(tails.getAir(), "status air bit set");
        assertTrue(tails.isObjectControlled(), "ROM loc_13B50 writes object_control=$81");
        assertFalse(tails.isObjectControlAllowsCpu(),
                "object_control=$81 must clear stale bits-0-to-6 CPU allowance");
        assertTrue(tails.isObjectControlSuppressesMovement(),
                "object_control=$81 must keep normal movement suppressed");
        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "Transitions to routine 0x04 (FLIGHT_AUTO_RECOVERY) on trigger");
    }

    @Test
    void dormantReleaseUsesConfiguredNonTailsRunInStrategy() {
        TestableSprite leader = new TestableSprite("tails");
        TestableSprite sonicSidekick = new TestableSprite("sonic_p2");
        sonicSidekick.setCpuControlled(true);
        sonicSidekick.setObjectControlAllowsCpu(false);
        sonicSidekick.setObjectControlSuppressesMovement(true);
        sonicSidekick.setForcedAnimationId(sonicSidekick.resolveAnimationId(com.openggf.game.CanonicalAnimation.FLY));

        SidekickCpuController controller = new SidekickCpuController(sonicSidekick, leader);
        RecordingRunInStrategy strategy = new RecordingRunInStrategy();
        controller.setRespawnStrategy(strategy);
        controller.forceStateForTest(SidekickCpuController.State.DORMANT_MARKER, 0);

        assertTrue(controller.releaseDormantMarkerForLevelEvent());

        assertTrue(strategy.began, "A non-Tails sidekick must enter its configured run-in strategy");
        assertSame(SidekickCpuController.State.APPROACHING, controller.getState());
        assertEquals(0x1234, sonicSidekick.getCentreX() & 0xFFFF);
        assertFalse(sonicSidekick.isObjectControlSuppressesMovement(),
                "Sonic run-in must not remain in Tails object-controlled flight");
        assertEquals(-1, sonicSidekick.getForcedAnimationId(),
                "Sonic run-in must not keep the Tails fly animation forced");
    }

    @Test
    void catchUp64FrameGateTriggersOnlyEvery64Frames() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x0400);
        tails.setCentreX((short) 0x0200);
        tails.setCentreY((short) 0x0500);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);

        // Frames not divisible by 64 — should NOT trigger
        controller.update(1);
        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Frame 1 is not a 64-frame boundary; stay in CATCH_UP");

        controller.update(63);
        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Frame 63 is not a 64-frame boundary; stay in CATCH_UP");

        // Frame 64 = 0x40 = divisible by 64 — SHOULD trigger
        controller.update(64);
        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "Frame 64 hits the 64-frame gate; transition to FLIGHT_AUTO_RECOVERY");
    }

    @Test
    void catchUp64FrameGateSuppressedWhenSonicIsObjectControlled() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        sonic.setObjectControlled(true);  // ROM checks bit 7 of object_control (bmi)

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);

        controller.update(64);

        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Sonic object-controlled suppresses the 64-frame gate");
    }

    @Test
    void catchUpWaitDoesNotRewriteObjectControlOrAirState() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setAir(false);
        tails.setControlLocked(false);
        tails.setObjectControlled(false);
        tails.setForcedAnimationId(0x3A);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);

        controller.update(1);

        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "Frame 1 has no catch-up trigger");
        assertFalse(tails.getAir(),
                "ROM Tails_Catch_Up_Flying wait path returns without writing status");
        assertFalse(tails.isControlLocked(),
                "ROM Tails_Catch_Up_Flying wait path returns without writing object_control bit 0");
        assertFalse(tails.isObjectControlled(),
                "ROM Tails_Catch_Up_Flying wait path returns without writing object_control bit 7");
        assertEquals(0x3A, tails.getForcedAnimationId(),
                "ROM Tails_Catch_Up_Flying wait path returns without changing animation");
    }

    @Test
    void resetClearsPendingCatchUpCounterOverride() {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_3K);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x0400);
        tails.setCentreX((short) 0x7F00);
        tails.setCentreY((short) 0);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.DORMANT_MARKER, 0);
        controller.releaseDormantMarkerForLevelEvent();

        controller.reset();
        controller.forceStateForTest(SidekickCpuController.State.CATCH_UP_FLIGHT, 0);
        controller.update(1);

        assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                "reset must discard the one-shot AIZ catch-up counter override");
        assertEquals(0x7F00, tails.getCentreX() & 0xFFFF,
                "post-reset catch-up uses the supplied frame counter, not stale AIZ cadence");
    }

    @Test
    void aizDormantReleaseBridgePersistsUntilCatchUpGate() throws Exception {
        TestableSprite sonic = new TestableSprite("sonic");
        TestableSprite tails = new TestableSprite("tails_p2");
        tails.setCpuControlled(true);
        tails.setGameRulesForTest(GameRules.SONIC_3K);
        sonic.setCentreX((short) 0x13CC);
        sonic.setCentreY((short) 0x0400);
        sonic.setObjectControlled(false);
        tails.setCentreX((short) 0x7F00);
        tails.setCentreY((short) 0);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.DORMANT_MARKER, 0);

        setLevelFrameCounter(0x02F3);
        controller.releaseDormantMarkerForLevelEvent();
        for (int storedCounter = 0x02F4; storedCounter < 0x02FF; storedCounter++) {
            setLevelFrameCounter(storedCounter);
            controller.update(storedCounter + 1);
            assertSame(SidekickCpuController.State.CATCH_UP_FLIGHT, controller.getState(),
                    "AIZ bridge must not force non-gate catch-up frames to warp");
        }

        setLevelFrameCounter(0x02FF);
        controller.update(0x0300);

        assertSame(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                "AIZ bridge must keep the ROM-visible post-increment counter through the catch-up gate");
        assertEquals(0x13CC, tails.getCentreX() & 0xFFFF);
        assertEquals(0x0340, tails.getCentreY() & 0xFFFF);
    }

    private static void setLevelFrameCounter(int value) throws Exception {
        Field frameCounter = GameServices.level().getClass().getDeclaredField("frameCounter");
        frameCounter.setAccessible(true);
        frameCounter.setInt(GameServices.level(), value);
    }
}
