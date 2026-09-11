package com.openggf.game.sonic3k.specialstage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for S3K special stage player movement physics.
 * No ROM or OpenGL required.
 */
class TestSonic3kSpecialStagePlayer {
    private Sonic3kSpecialStagePlayer player;

    @BeforeEach
    void setUp() {
        player = new Sonic3kSpecialStagePlayer();
        player.initialize(ANGLE_NORTH, 0x1000, 0x1000, false);
    }

    @Test
    void testInitialState() {
        assertEquals(ANGLE_NORTH, player.getAngle());
        assertEquals(0x1000, player.getXPos());
        assertEquals(0x1000, player.getYPos());
        assertEquals(0, player.getVelocity());
        assertEquals(INITIAL_RATE, player.getRate());
        assertFalse(player.isAdvancing());
        assertFalse(player.isStarted());
    }

    @Test
    void testSineAtCardinalAngles() {
        // North (0x00): sin=0, cos=256
        assertEquals(0, Sonic3kSpecialStagePlayer.getSine(0x00));
        assertEquals(256, Sonic3kSpecialStagePlayer.getCosine(0x00));

        // West (0x40): sin=256, cos≈0
        assertEquals(256, Sonic3kSpecialStagePlayer.getSine(0x40));
        assertEquals(0, Sonic3kSpecialStagePlayer.getCosine(0x40));

        // South (0x80): sin≈0, cos=-256
        assertEquals(0, Sonic3kSpecialStagePlayer.getSine(0x80));
        assertEquals(-256, Sonic3kSpecialStagePlayer.getCosine(0x80));

        // East (0xC0): sin=-256, cos≈0
        assertEquals(-256, Sonic3kSpecialStagePlayer.getSine(0xC0));
        assertEquals(0, Sonic3kSpecialStagePlayer.getCosine(0xC0));
    }

    @Test
    void testVelocityCapAtRate() {
        player.setAdvancing(true);
        // Simulate pressing up for many frames
        for (int i = 0; i < 100; i++) {
            player.update(0x01, 0); // UP held
        }
        // Velocity should cap at rate (0x1000)
        assertTrue(player.getVelocity() <= INITIAL_RATE,
                "Velocity should not exceed rate: " + player.getVelocity());
    }

    @Test
    void testForwardMovementNorth() {
        // Facing north (angle=0), moving forward should decrease Y
        // (Y increases downward in Mega Drive convention, North = up = Y decreasing)
        player.setAdvancing(true);
        int initialY = player.getYPos();
        // Give some velocity
        player.setVelocity(0x1000);
        player.update(0x01, 0); // UP held

        // With angle=0: sin(0)=0, cos(0)=256
        // Y -= (cos * vel) >> 16 = (256 * 0x1000) >> 16 = 0x100000 >> 16 = 16
        // So Y should decrease
        assertTrue(player.getYPos() < initialY || player.getYPos() > 0xF000,
                "Y should decrease when moving north");
    }

    @Test
    void testAnimationSequenceLength() {
        assertEquals(14, ANIM_WALKING.length);
        assertEquals(14, ANIM_JUMP_P1.length);
        assertEquals(14, ANIM_JUMP_P2.length);
    }

    @Test
    void testSpeedIncreaseTimer() {
        player.initialize(ANGLE_NORTH, 0x1000, 0x1000, false);
        int initialRate = player.getRate();
        assertEquals(INITIAL_RATE, initialRate);

        // Simulate 30*60 frames (30 seconds)
        for (int i = 0; i < RATE_TIMER_NORMAL; i++) {
            player.update(0, 0);
        }

        // Rate should have increased by 0x400
        assertEquals(INITIAL_RATE + RATE_INCREMENT, player.getRate(),
                "Rate should increase after timer expires");
    }

    @Test
    void testBlueSpheresModeUsesLongerTimer() {
        player.initialize(ANGLE_NORTH, 0x1000, 0x1000, true);
        int initialRate = player.getRate();

        // Simulate 30*60 frames (should NOT have increased yet in BS mode)
        for (int i = 0; i < RATE_TIMER_NORMAL; i++) {
            player.update(0, 0);
        }
        assertEquals(initialRate, player.getRate(),
                "Rate should not increase yet in Blue Spheres mode");

        // Simulate remaining frames to reach 45*60
        for (int i = 0; i < RATE_TIMER_BLUE_SPHERES - RATE_TIMER_NORMAL; i++) {
            player.update(0, 0);
        }
        assertEquals(INITIAL_RATE + RATE_INCREMENT, player.getRate(),
                "Rate should increase after 45s in Blue Spheres mode");
    }

    @Test
    void testMaxRate() {
        // Set rate just below max and trigger increase
        player.initialize(ANGLE_NORTH, 0x1000, 0x1000, false);
        // Simulate multiple timer expiries
        for (int i = 0; i < 10 * RATE_TIMER_NORMAL; i++) {
            player.update(0, 0);
        }
        assertTrue(player.getRate() <= MAX_RATE,
                "Rate should not exceed MAX_RATE: " + player.getRate());
    }
}


