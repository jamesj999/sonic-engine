package com.openggf.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestMenuRepeat {
    @Test
    void holdsRepeatAfterDelayAndQueriesWithinFrameAreStable() {
        MenuRepeat repeat = new MenuRepeat();
        for (int frame = 0; frame <= 40; frame++) {
            boolean expected = frame == 0 || frame >= 24 && (frame - 24) % 4 == 0;
            assertEquals(expected, repeat.pulse(1, frame, true, frame == 0), "frame " + frame);
            assertEquals(expected, repeat.pulse(1, frame, true, frame == 0));
        }
    }

    @Test
    void releaseAndFocusGapRestartDelayWithoutInventingAnEdge() {
        MenuRepeat repeat = new MenuRepeat();
        assertTrue(repeat.pulse(1, 0, true, true));
        assertFalse(repeat.pulse(1, 1, false, false));
        assertTrue(repeat.pulse(1, 2, true, true));
        assertFalse(repeat.pulse(1, 30, true, false));
        assertFalse(repeat.pulse(1, 31, true, false));
    }

    @Test
    void pressedOnlyLogicalEdgesArePreservedWithoutRepeatingAfterRelease() {
        MenuRepeat repeat = new MenuRepeat();
        assertTrue(repeat.pulse(1, 0, false, true));
        assertFalse(repeat.pulse(1, 1, false, false));
    }

    @Test
    void replacementLogicalSnapshotInSameFrameDoesNotReuseStalePulse() {
        MenuRepeat repeat = new MenuRepeat();
        assertFalse(repeat.pulse(1, 0, false, false));
        assertTrue(repeat.pulse(1, 0, false, true));
        assertFalse(repeat.pulse(1, 0, false, false));
    }

    @Test
    void controllerFamiliesHavePositionFallbackForUnrecognizedDevices() {
        assertEquals("Cross", ControllerPromptStyle.forName("Sony DualSense Wireless Controller").confirm());
        assertEquals("Circle", ControllerPromptStyle.forName("PS4 Controller").back());
        assertEquals("A", ControllerPromptStyle.forName("Xbox Wireless Controller").confirm());
        assertEquals("South", ControllerPromptStyle.forName("Switch Pro Controller").confirm());
        assertEquals("East", ControllerPromptStyle.forName(null).back());
    }
}
