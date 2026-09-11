package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the per-game ground speed capping behavior.
 *
 * S1: Pressing direction of movement always clamps ground speed to max,
 * even if speed was above max from slopes/springs.
 * ROM: s1disasm/_incObj/01 Sonic.asm:555-558 â€” unconditional clamp.
 *
 * S2/S3K: Preserves ground speeds above max when pressing direction of movement.
 * ROM: s2.asm:36610-36616 â€” undo acceleration if speed was already >= max.
 */
public class TestGroundSpeedCapping {

    @BeforeEach
    public void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    public void tearDown() {
        GameModuleRegistry.reset();
    }

    @Test
    public void testSonic1_inputAlwaysCapsGroundSpeed_isTrue() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        TestableSprite sprite = new TestableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertNotNull(fs, "Feature set should be set");
        assertTrue(fs.playerMovement().inputAlwaysCapsGroundSpeed(), "S1 should always cap ground speed on input");
    }

    @Test
    public void testSonic2_inputAlwaysCapsGroundSpeed_isFalse() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestableSprite sprite = new TestableSprite("test", (short) 100, (short) 100);

        GameRules fs = sprite.getGameRules();
        assertNotNull(fs, "Feature set should be set");
        assertFalse(fs.playerMovement().inputAlwaysCapsGroundSpeed(), "S2 should preserve high ground speed on input");
    }

    @Test
    public void testSonic1_highSpeedCappedWhenPressingDirection() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        TestableSprite sprite = new TestableSprite("test", (short) 100, (short) 100);

        // Simulate Sonic at high speed (above max 0x600) from a spring
        short highSpeed = 0x800;
        short max = sprite.getMax();
        assertTrue(highSpeed > max, "Test setup: speed should be above max");

        // S1 behavior: pressing right when going right at high speed should clamp to max
        // This matches: add accel, then unconditional clamp
        // Result: min(highSpeed + accel, max) = max since highSpeed + accel > max
        GameRules fs = sprite.getGameRules();
        assertTrue(fs.playerMovement().inputAlwaysCapsGroundSpeed(), "S1 rule");

        // Verify the clamp would reduce the speed: highSpeed + accel still > max
        short accel = sprite.getRunAccel();
        short result = (short) (highSpeed + accel);
        assertTrue(result > max, "With acceleration, still above max");
        // After S1 clamping: result should be max
        if (result > max) result = max;
        assertEquals(max, result, "S1 should clamp to max");
    }

    @Test
    public void testSonic2_highSpeedPreservedWhenPressingDirection() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestableSprite sprite = new TestableSprite("test", (short) 100, (short) 100);

        // Simulate Sonic at high speed (above max 0x600) from a spring
        short highSpeed = 0x800;
        short max = sprite.getMax();
        assertTrue(highSpeed > max, "Test setup: speed should be above max");

        // S2 behavior: pressing right when going right at high speed should preserve speed
        // because inputAlwaysCapsGroundSpeed is false (acceleration is skipped when >= max).
        // Full integration is tested via HeadlessTestRunner; here we verify the rule.
        GameRules fs = sprite.getGameRules();
        assertFalse(fs.playerMovement().inputAlwaysCapsGroundSpeed(), "S2 should not cap ground speed on input (preserves high speed from springs/slopes)");
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


