package com.openggf.physics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSwingMotion {

    @Test
    public void swingsDownFromZeroVelocity() {
        // direction=true (down), accel=0x18, vel=0, max=0x100
        var result = SwingMotion.update(0x18, 0, 0x100, true);
        assertEquals(0x18, result.velocity());
        assertTrue(result.directionDown());
        assertFalse(result.directionChanged());
    }

    @Test
    public void reversesAtMaxVelocity() {
        // direction=true (down), vel is near max
        var result = SwingMotion.update(0x18, 0xF0, 0x100, true);
        // ROM cancels the overshoot step when switching direction.
        assertEquals(0xF0, result.velocity());
        assertFalse(result.directionDown()); // direction flipped
        assertTrue(result.directionChanged());
    }

    @Test
    public void swingsUpFromZero() {
        // direction=false (up), accel=0x18, vel=0, max=0x100
        var result = SwingMotion.update(0x18, 0, 0x100, false);
        assertEquals(-0x18, result.velocity());
        assertFalse(result.directionDown());
        assertFalse(result.directionChanged());
    }

    @Test
    public void reversesAtNegativeMaxVelocity() {
        // direction=false (up), vel is near -max
        var result = SwingMotion.update(0x18, -0xF0, 0x100, false);
        // ROM cancels the overshoot step when switching direction.
        assertEquals(-0xF0, result.velocity());
        assertTrue(result.directionDown());
        assertTrue(result.directionChanged());
    }
}


