package com.openggf.level.objects.boss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BossStateContext fixed-point math.
 *
 * Validates 16.16 fixed-point format against s2.asm:62970-62976:
 * - Position is longword: upper 16 bits = integer, lower 16 bits = fraction
 * - Velocity is word in "pixels * 256" format (0x200 = 2.0 pixels)
 * - Velocity application: ext.l d0; asl.l #8,d0; add.l d0,x_pos
 */
public class BossStateContextTest {

    private BossStateContext context;

    @BeforeEach
    public void setUp() {
        context = new BossStateContext(0, 0, 8);
    }

    @Test
    public void testFixedPointFormat_IntegerVelocity() {
        // Velocity 0x200 = 2.0 pixels/frame (ROM format: pixels * 256)
        context.xVel = 0x200;
        context.xFixed = 0;

        // Run for 10 frames
        for (int i = 0; i < 10; i++) {
            context.xFixed += (context.xVel << 8);
        }

        context.updatePositionFromFixed();

        // Expected: 10 frames * 2 px/frame = 20 pixels
        assertEquals(20, context.x, "Position should advance 20 pixels");
    }

    @Test
    public void testFixedPointFormat_FractionalVelocity() {
        // Velocity 0x180 = 1.5 pixels/frame (0x180 / 0x100 = 1.5)
        context.xVel = 0x180;
        context.xFixed = 0;

        // Run for 2 frames
        for (int i = 0; i < 2; i++) {
            context.xFixed += (context.xVel << 8);
        }

        context.updatePositionFromFixed();

        // Expected: 2 frames * 1.5 px/frame = 3 pixels
        assertEquals(3, context.x, "Position should advance 3 pixels from fractional velocity");
    }

    @Test
    public void testFixedPointFormat_SubPixelAccumulation() {
        // Velocity 0x100 = 1.0 pixels/frame
        context.xVel = 0x100;
        context.xFixed = 0;

        // First frame: accumulate but don't overflow to integer part yet
        context.xFixed += (context.xVel << 8);
        context.updatePositionFromFixed();
        assertEquals(1, context.x, "First frame should move 1 pixel");

        // Add sub-pixel velocity 0x80 = 0.5 px/frame
        context.xVel = 0x80;
        context.xFixed += (context.xVel << 8);
        context.updatePositionFromFixed();
        assertEquals(1, context.x, "Sub-pixel should not advance integer position yet");

        // Second frame with 0x80
        context.xFixed += (context.xVel << 8);
        context.updatePositionFromFixed();
        assertEquals(2, context.x, "Two 0.5 px movements should accumulate to 1 pixel");
    }

    @Test
    public void testFixedPointFormat_NegativeVelocity() {
        // Velocity -0x200 = -2.0 pixels/frame
        context.xVel = -0x200;
        context.xFixed = 100 << 16; // Start at X=100 in fixed-point
        context.x = 100;

        // Run for 10 frames
        for (int i = 0; i < 10; i++) {
            context.xFixed += (context.xVel << 8);
        }

        context.updatePositionFromFixed();

        // Expected: 100 - (10 * 2) = 80 pixels
        assertEquals(80, context.x, "Negative velocity should move left");
    }

    @Test
    public void testFixedPointFormat_YAxis() {
        // Test Y-axis uses same fixed-point math
        context.yVel = 0x180; // 1.5 px/frame
        context.yFixed = 0;

        for (int i = 0; i < 4; i++) {
            context.yFixed += (context.yVel << 8);
        }

        context.updatePositionFromFixed();

        // Expected: 4 * 1.5 = 6 pixels
        assertEquals(6, context.y, "Y-axis fixed-point should work identically");
    }

    @Test
    public void testPositionExtraction_UpperBitsOnly() {
        // Set fixed-point value directly: 0x00012A80
        // Upper 16 bits = 0x0001 (integer = 1)
        // Lower 16 bits = 0x2A80 (fraction)
        context.xFixed = 0x00012A80;
        context.updatePositionFromFixed();

        // Position extraction: x = xFixed >> 16
        int expected = 0x00012A80 >> 16;
        assertEquals(expected, context.x, "Position should extract upper bits only");
    }

    @Test
    public void testVelocityApplication_ShiftBy8() {
        // Verify velocity shift matches ROM: asl.l #8,d0
        context.xVel = 0x200; // 2 px/frame
        context.xFixed = 0;

        // ROM: move.w x_vel(a0),d0 ; asl.l #8,d0 ; add.l d0,x_pos(a0)
        int velocityShifted = context.xVel << 8;
        context.xFixed += velocityShifted;

        // Verify shifted value
        assertEquals(0x20000, velocityShifted, "Velocity shift should multiply by 256");
        assertEquals(0x20000, context.xFixed, "Fixed position should match shifted velocity");
    }

    @Test
    public void testRoundingBehavior() {
        // Test that integer extraction truncates (doesn't round)
        context.xFixed = 0x0001FF00; // 1.996... pixels (0xFF00 / 0x10000 â‰ˆ 0.996)
        context.updatePositionFromFixed();
        assertEquals(1, context.x, "Should truncate, not round");

        context.xFixed = 0x00020100; // 2.003... pixels
        context.updatePositionFromFixed();
        assertEquals(2, context.x, "Should truncate fraction");
    }

    @Test
    public void testZeroVelocity() {
        context.xVel = 0;
        context.yVel = 0;
        context.xFixed = 100 << 16;
        context.yFixed = 200 << 16;
        context.x = 100;
        context.y = 200;

        // Run for 100 frames
        for (int i = 0; i < 100; i++) {
            context.xFixed += (context.xVel << 8);
            context.yFixed += (context.yVel << 8);
        }

        context.updatePositionFromFixed();

        assertEquals(100, context.x, "Zero velocity should not move X");
        assertEquals(200, context.y, "Zero velocity should not move Y");
    }

    @Test
    public void testLargeVelocity() {
        // Test velocity 0x600 = 6.0 px/frame
        context.xVel = 0x600;
        context.xFixed = 0;

        for (int i = 0; i < 5; i++) {
            context.xFixed += (context.xVel << 8);
        }

        context.updatePositionFromFixed();

        // Expected: 5 * 6 = 30 pixels
        assertEquals(30, context.x, "Large velocity should work correctly");
    }

    @Test
    public void testApplyVelocity_Helper() {
        // Test the applyVelocity() helper method
        context.xVel = 0x200; // 2 px/frame
        context.yVel = 0x100; // 1 px/frame
        context.xFixed = 0;
        context.yFixed = 0;

        // Run for 5 frames
        for (int i = 0; i < 5; i++) {
            context.applyVelocity();
        }

        assertEquals(10, context.x, "applyVelocity should apply X correctly");
        assertEquals(5, context.y, "applyVelocity should apply Y correctly");
    }

    @Test
    public void testConstructor_InitializesFixedPoint() {
        // Constructor should initialize fixed-point values from pixel positions
        BossStateContext ctx = new BossStateContext(100, 200, 8);

        assertEquals(100, ctx.x, "X should match constructor");
        assertEquals(200, ctx.y, "Y should match constructor");
        assertEquals(100 << 16, ctx.xFixed, "xFixed should be X << 16");
        assertEquals(200 << 16, ctx.yFixed, "yFixed should be Y << 16");
    }
}


