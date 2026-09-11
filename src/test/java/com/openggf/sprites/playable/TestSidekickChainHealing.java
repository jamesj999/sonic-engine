package com.openggf.sprites.playable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SidekickCpuController.getEffectiveLeader() chain healing.
 * Uses minimal TestableSprite stubs — no ROM/OpenGL required.
 */
class TestSidekickChainHealing {

    /** Minimal stub for testing chain relationships. */
    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) { super(code, (short) 0, (short) 0); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
        @Override protected void createSensorLines() {}
    }

    @Test
    void settledLeaderReturnedDirectly() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk1 = new TestableSprite("tails_p2");
        sk1.setCpuControlled(true);
        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, main);
        sk1.setCpuController(ctrl1);

        // Simulate settled: force to NORMAL for 15+ frames
        ctrl1.forceStateForTest(SidekickCpuController.State.NORMAL, 20);

        assertSame(main, ctrl1.getEffectiveLeader(),
            "When leader is main player (not CPU), should return main directly");
    }

    @Test
    void unsettledMiddleSkipsToMainPlayer() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk1 = new TestableSprite("tails_p2");
        TestableSprite sk2 = new TestableSprite("knux_p3");
        sk1.setCpuControlled(true);
        sk2.setCpuControlled(true);

        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, main);
        SidekickCpuController ctrl2 = new SidekickCpuController(sk2, sk1);
        sk1.setCpuController(ctrl1);
        sk2.setCpuController(ctrl2);

        // sk1 is NOT settled (spawning state)
        ctrl1.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);

        assertSame(main, ctrl2.getEffectiveLeader(),
            "When direct leader is unsettled, should walk up chain to main player");
    }

    @Test
    void settledMiddleStopsChainWalk() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk1 = new TestableSprite("tails_p2");
        TestableSprite sk2 = new TestableSprite("knux_p3");
        sk1.setCpuControlled(true);
        sk2.setCpuControlled(true);

        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, main);
        SidekickCpuController ctrl2 = new SidekickCpuController(sk2, sk1);
        sk1.setCpuController(ctrl1);
        sk2.setCpuController(ctrl2);

        // sk1 IS settled
        ctrl1.forceStateForTest(SidekickCpuController.State.NORMAL, 20);

        assertSame(sk1, ctrl2.getEffectiveLeader(),
            "When direct leader is settled, should return it directly");
    }

    @Test
    void normalMiddleStopsChainWalkBeforeSettledThreshold() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk1 = new TestableSprite("tails_p2");
        TestableSprite sk2 = new TestableSprite("knux_p3");
        sk1.setCpuControlled(true);
        sk2.setCpuControlled(true);

        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, main);
        SidekickCpuController ctrl2 = new SidekickCpuController(sk2, sk1);
        sk1.setCpuController(ctrl1);
        sk2.setCpuController(ctrl2);

        ctrl1.forceStateForTest(SidekickCpuController.State.NORMAL, 0);

        assertFalse(ctrl1.isSettled(), "Precondition: direct leader has not crossed the settled threshold");
        assertSame(sk1, ctrl2.getEffectiveLeader(),
            "A direct CPU leader in NORMAL should be usable immediately; settled only gates chain healing");
    }

    @Test
    void nullLeaderReturnsNull() {
        TestableSprite sk1 = new TestableSprite("tails_p2");
        sk1.setCpuControlled(true);
        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, null);
        sk1.setCpuController(ctrl1);

        assertNull(ctrl1.getEffectiveLeader(),
            "Null leader should return null (level transition case)");
    }
}

