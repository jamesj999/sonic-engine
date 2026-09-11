package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests look-scroll delay feature gating per game module.
 * S1: no delay - camera pans immediately when looking up/down (s1.asm: Sonic_LookUp).
 * S2/S3K: 120-frame delay before camera pans (s2.asm:36402-36405).
 */
public class TestLookScrollDelay {

    @BeforeEach
    public void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    public void tearDown() {
        GameModuleRegistry.reset();
    }

    @Test
    public void testSonic1_InstantLookScroll() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        TestableSprite sprite = new TestableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertNotNull(fs, "Feature set should be set");
        assertEquals((short) 0, fs.camera().lookScrollDelay(), "S1 should have no look scroll delay");
    }

    @Test
    public void testSonic2_DelayedLookScroll() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestableSprite sprite = new TestableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertNotNull(fs, "Feature set should be set");
        assertEquals((short) 120, fs.camera().lookScrollDelay(), "S2 should have 120-frame look scroll delay");
    }

    @Test
    public void testSonic3k_DelayedLookScroll() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestableSprite sprite = new TestableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertNotNull(fs, "Feature set should be set");
        assertEquals((short) 120, fs.camera().lookScrollDelay(), "S3K should have 120-frame look scroll delay");
    }

    @Test
    public void testSonic1_ZeroDelayMeansImmediatePan() {
        // Verify that delay=0 means lookDelay (starting at 0) immediately
        // satisfies the >= threshold, so panning starts on the first frame.
        short lookScrollDelay = (short) 0;
        short lookDelay = 1; // After one increment
        assertTrue(lookDelay >= lookScrollDelay, "With zero delay, first frame should pan");
    }

    @Test
    public void testSonic2_DelayRequiresFrames() {
        // Verify that delay=0x78 requires 120 frames before panning starts.
        short lookScrollDelay = (short) 120;
        assertEquals(0x78, lookScrollDelay, "S2 delay should be 120 frames");

        // After 1 frame, should NOT pan yet
        short lookDelay = 1;
        assertFalse(lookDelay >= lookScrollDelay, "After 1 frame, should not pan yet");

        // After 119 frames, should NOT pan yet
        lookDelay = 0x77;
        assertFalse(lookDelay >= lookScrollDelay, "After 119 frames, should not pan yet");

        // After 120 frames, SHOULD pan
        lookDelay = 0x78;
        assertTrue(lookDelay >= lookScrollDelay, "After 120 frames, should pan");
    }

    private static class TestableSprite extends AbstractPlayableSprite {
        public TestableSprite(String code, short x, short y) {
            super(code, x, y);
        }

        @Override
        public void draw() {
        }

        @Override
        public void defineSpeeds() {
            runAccel = 12;
            runDecel = 128;
            friction = 12;
            max = 1536;
            jump = 1664;
            slopeRunning = 32;
            slopeRollingDown = 80;
            slopeRollingUp = 20;
            rollDecel = 32;
            minStartRollSpeed = 128;
            minRollSpeed = 128;
            maxRoll = 4096;
            rollHeight = 28;
            runHeight = 38;
            standXRadius = 9;
            standYRadius = 19;
            rollXRadius = 7;
            rollYRadius = 14;
        }

        @Override
        protected void createSensorLines() {
        }
    }
}


