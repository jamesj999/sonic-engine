package com.openggf.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestAnimationTimer {

    @Test
    public void testInitialState() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testNoAdvanceBeforeDuration() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        assertFalse(timer.tick());
        assertEquals(0, timer.getFrame());
        assertFalse(timer.tick());
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testAdvancesAtDuration() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        timer.tick(); // 1
        timer.tick(); // 2
        boolean changed = timer.tick(); // 3 â€” should advance
        assertTrue(changed);
        assertEquals(1, timer.getFrame());
    }

    @Test
    public void testWrapsAround() {
        AnimationTimer timer = new AnimationTimer(1, 3);
        timer.tick(); // 0->1
        timer.tick(); // 1->2
        timer.tick(); // 2->0
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testReset() {
        AnimationTimer timer = new AnimationTimer(2, 3);
        timer.tick();
        timer.tick();
        assertEquals(1, timer.getFrame());
        timer.reset();
        assertEquals(0, timer.getFrame());
        assertFalse(timer.tick());
        assertEquals(0, timer.getFrame());
    }

    @Test
    public void testSetFrame() {
        AnimationTimer timer = new AnimationTimer(3, 4);
        timer.setFrame(2);
        assertEquals(2, timer.getFrame());
    }

    @Test
    public void testDurationOne() {
        AnimationTimer timer = new AnimationTimer(1, 3);
        assertTrue(timer.tick());
        assertEquals(1, timer.getFrame());
        assertTrue(timer.tick());
        assertEquals(2, timer.getFrame());
        assertTrue(timer.tick());
        assertEquals(0, timer.getFrame());
    }
}


