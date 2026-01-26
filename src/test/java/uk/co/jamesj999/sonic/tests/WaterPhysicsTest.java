package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for water physics state transitions and velocity changes.
 * These tests verify the water entry/exit behavior matches original game logic.
 */
public class WaterPhysicsTest {

    // Mock playable sprite for testing water physics
    private TestablePlayableSprite sprite;

    @Before
    public void setUp() {
        sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
    }

    @Test
    public void testWaterEntry_HalvesHorizontalVelocity() {
        // Set initial velocity
        sprite.setXSpeed((short) 1000);
        sprite.setGSpeed((short) 1000);
        sprite.setYSpeed((short) 0);

        // Simulate water entry - move player below water level
        sprite.setTestY((short) 500); // Player at Y=500
        sprite.updateWaterState(400); // Water at Y=400, player is below

        assertTrue("Player should be in water", sprite.isInWater());
        assertEquals("XSpeed should be halved", 500, sprite.getXSpeed());
        assertEquals("GSpeed should be halved", 500, sprite.getGSpeed());
    }

    @Test
    public void testWaterEntry_ReducesDownwardVelocity() {
        // Set initial downward velocity
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 400); // Moving down

        // Simulate water entry
        sprite.setTestY((short) 500);
        sprite.updateWaterState(400);

        assertTrue("Player should be in water", sprite.isInWater());
        // Downward velocity should be quartered (400 / 4 = 100)
        assertEquals("YSpeed should be quartered when positive", 100, sprite.getYSpeed());
    }

    @Test
    public void testWaterEntry_QuartersUpwardVelocity() {
        // Set initial upward velocity
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) -400); // Moving up

        // Simulate water entry
        sprite.setTestY((short) 500);
        sprite.updateWaterState(400);

        assertTrue("Player should be in water", sprite.isInWater());
        // ROM: asr.w y_vel(a0) twice - divides by 4 unconditionally (both up and down)
        assertEquals("YSpeed should be quartered (ROM-accurate)", -100, sprite.getYSpeed());
    }

    @Test
    public void testWaterExit_DoesNotModifyHorizontalVelocity() {
        // Start in water
        sprite.setInWater(true);
        sprite.setXSpeed((short) 500);
        sprite.setGSpeed((short) 500);
        sprite.setYSpeed((short) -200); // Moving up

        // Simulate water exit - move player above water level
        sprite.setTestY((short) 300); // Player at Y=300
        sprite.updateWaterState(400); // Water at Y=400, player is above

        assertFalse("Player should be out of water", sprite.isInWater());
        // ROM does NOT modify x_vel on water exit - only top_speed/accel/decel change
        assertEquals("XSpeed should be unchanged (ROM-accurate)", 500, sprite.getXSpeed());
        assertEquals("GSpeed should be unchanged (ROM-accurate)", 500, sprite.getGSpeed());
    }

    @Test
    public void testWaterExit_BoostsUpwardVelocity() {
        // Start in water moving up
        sprite.setInWater(true);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) -300); // Moving up

        // Simulate water exit
        sprite.setTestY((short) 300);
        sprite.updateWaterState(400);

        assertFalse("Player should be out of water", sprite.isInWater());
        // Upward velocity should be doubled (-300 * 2 = -600)
        assertEquals("YSpeed should be doubled when exiting upward", -600, sprite.getYSpeed());
    }

    @Test
    public void testWaterExit_SkipsBoostWhenHurt() {
        // ROM: cmpi.b #4,routine(a0) - skip y_vel doubling if hurt (routine=4)
        // Start in water moving up, but in hurt state
        sprite.setInWater(true);
        sprite.setHurt(true);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) -300); // Moving up

        // Simulate water exit
        sprite.setTestY((short) 300);
        sprite.updateWaterState(400);

        assertFalse("Player should be out of water", sprite.isInWater());
        // When hurt, y velocity should NOT be doubled (ROM-accurate)
        assertEquals("YSpeed should be unchanged when hurt (ROM-accurate)", -300, sprite.getYSpeed());
    }

    @Test
    public void testUnderwaterConstants_ReducedValues() {
        // Not in water
        sprite.setInWater(false);
        short normalAccel = sprite.getRunAccel();
        short normalMax = sprite.getMax();
        short normalJump = sprite.getJump();
        // Use getEffectiveGravity() which returns the net gravity accounting for water state
        // getGravity() returns the BASE gravity used by ObjectMoveAndFall (underwater reduction
        // is applied separately in modeAirborne())
        short normalGravity = sprite.getEffectiveGravity();

        // In water
        sprite.setInWater(true);
        short waterAccel = sprite.getRunAccel();
        short waterMax = sprite.getMax();
        short waterJump = sprite.getJump();
        short waterGravity = sprite.getEffectiveGravity();

        // Verify underwater constants are reduced
        assertEquals("Underwater accel should be half", normalAccel / 2, waterAccel);
        assertEquals("Underwater max should be half", normalMax / 2, waterMax);
        assertTrue("Underwater jump should be reduced", waterJump < normalJump);
        assertTrue("Underwater gravity should be reduced", waterGravity < normalGravity);
    }

    @Test
    public void testNoTransition_WhenStayingAboveWater() {
        // Player above water
        sprite.setTestY((short) 300);
        sprite.setXSpeed((short) 1000);

        // Update water state - player stays above
        sprite.updateWaterState(400);
        sprite.updateWaterState(400); // Second update should not change anything

        assertFalse("Player should not be in water", sprite.isInWater());
        assertEquals("Velocity should be unchanged", 1000, sprite.getXSpeed());
    }

    @Test
    public void testNoTransition_WhenStayingUnderwater() {
        // Player starts underwater
        sprite.setInWater(true);
        sprite.setTestY((short) 500);
        sprite.setXSpeed((short) 500);

        // Simulate frame update while staying underwater
        sprite.updateWaterState(400);

        assertTrue("Player should still be in water", sprite.isInWater());
        assertEquals("Velocity should be unchanged", 500, sprite.getXSpeed());
    }

    @Test
    public void testHurtKnockback_NormalVelocities() {
        // Player is not underwater
        sprite.setInWater(false);
        sprite.setTestY((short) 300);

        // Apply hurt from the left (sourceX < player center)
        sprite.applyHurt(0);

        assertTrue("Player should be hurt", sprite.isHurt());
        // Normal knockback: xSpeed = 0x200 (512), ySpeed = -0x400 (-1024)
        assertEquals("Normal hurt xSpeed should be 0x200", 0x200, sprite.getXSpeed());
        assertEquals("Normal hurt ySpeed should be -0x400", -0x400, sprite.getYSpeed());
    }

    @Test
    public void testHurtKnockback_UnderwaterHalvedVelocities() {
        // Player is underwater (ROM s2.asm lines 84936-84941)
        sprite.setInWater(true);
        sprite.setTestY((short) 500);

        // Apply hurt from the left (sourceX < player center)
        sprite.applyHurt(0);

        assertTrue("Player should be hurt", sprite.isHurt());
        // Underwater knockback is halved: xSpeed = 0x100 (256), ySpeed = -0x200 (-512)
        assertEquals("Underwater hurt xSpeed should be 0x100 (halved)", 0x100, sprite.getXSpeed());
        assertEquals("Underwater hurt ySpeed should be -0x200 (halved)", -0x200, sprite.getYSpeed());
    }

    @Test
    public void testHurtGravity_UnderwaterValue() {
        // According to ROM s2.asm: hurt gravity = 0x30, underwater subtracts 0x20
        // So underwater hurt gravity = 0x30 - 0x20 = 0x10 (same as normal underwater)
        // Use getEffectiveGravity() which returns the net gravity accounting for water state
        // (getGravity() returns the BASE gravity; underwater reduction is applied in modeAirborne())
        sprite.setInWater(true);
        sprite.setHurt(true);

        short gravity = sprite.getEffectiveGravity();
        assertEquals("Underwater hurt gravity should be 0x10", 0x10, gravity);
    }

    /**
     * Minimal test subclass of AbstractPlayableSprite for physics testing.
     */
    private static class TestablePlayableSprite extends uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite {

        public TestablePlayableSprite(String code, short x, short y) {
            super(code, x, y);
        }

        @Override
        public void draw() {
            // No-op for testing
        }

        @Override
        public void defineSpeeds() {
            // Set standard Sonic values
            runAccel = 12;
            runDecel = 128;
            friction = 12;
            max = 1536;
            jump = 1664;
        }

        @Override
        protected void createSensorLines() {
            // No-op for testing
        }

        public void setTestY(short y) {
            this.yPixel = y;
        }
    }
}
