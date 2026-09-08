package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPaletteFadePresentation {
    private static final int WHITE = 0xEEEEEE;

    @Test
    void fadeFromBlackRaisesBlueThenGreenThenRedOneLevelPerPass() {
        assertEquals(0x000000, PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.FROM_BLACK, 0));
        assertEquals(0x0000FF, PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.FROM_BLACK, 7));
        assertEquals(0x00FFFF, PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.FROM_BLACK, 14));
        assertEquals(0xFFFFFF, PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.FROM_BLACK, 21));
        int step3 = PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.FROM_BLACK, 3);
        assertEquals(0, step3 >>> 16, "red waits for blue and green");
        assertEquals(0, (step3 >>> 8) & 0xFF, "green waits for blue");
        assertTrue((step3 & 0xFF) > 0, "blue rises first");
    }

    @Test
    void fadeToBlackDropsRedThenGreenThenBlue() {
        assertEquals(0x00FFFF, PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.TO_BLACK, 7));
        assertEquals(0x0000FF, PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.TO_BLACK, 14));
        assertEquals(0x000000, PaletteFadePresentation.fadeRgb(0xEE, 0xEE, 0xEE, PaletteFadePresentation.Mode.TO_BLACK, 21));
    }

    @Test
    void coloursThatArriveEarlyStopWhileOthersKeepStepping() {
        // Dark blue ($200) is complete after one pass; a full step count leaves it alone.
        assertEquals(PaletteFadePresentation.fadeRgb(0, 0, 0x24, PaletteFadePresentation.Mode.NONE, 0),
                PaletteFadePresentation.fadeRgb(0, 0, 0x24, PaletteFadePresentation.Mode.FROM_BLACK, 21));
    }

    @Test
    void maskedLinesFadeAndUnmaskedLinesPassThrough() {
        PaletteFadePresentation fade = new PaletteFadePresentation();
        assertTrue(fade.set(PaletteFadePresentation.Mode.FROM_BLACK, 0, PaletteFadePresentation.LINES_1_TO_3));
        assertFalse(fade.affects(0), "line 0 stays at full colour ($202F starts at line 1)");
        assertTrue(fade.affects(1));
        assertTrue(fade.affects(3));
        assertFalse(fade.affects(4));
        assertEquals(0x000000, fade.fadeRgb(0xEE, 0xEE, 0xEE));
        assertTrue(fade.set(PaletteFadePresentation.Mode.FROM_BLACK, 7, PaletteFadePresentation.LINES_1_TO_3));
        assertEquals(0x0000FF, fade.fadeRgb(0xEE, 0xEE, 0xEE));
    }

    @Test
    void repeatingTheSameStepIsNotAChangeAndClearReportsOnlyWhenActive() {
        PaletteFadePresentation fade = new PaletteFadePresentation();
        assertFalse(fade.clear());
        assertTrue(fade.set(PaletteFadePresentation.Mode.FROM_BLACK, 4, PaletteFadePresentation.LINES_1_TO_3));
        assertFalse(fade.set(PaletteFadePresentation.Mode.FROM_BLACK, 4, PaletteFadePresentation.LINES_1_TO_3));
        assertTrue(fade.set(PaletteFadePresentation.Mode.NONE, 9, 0), "NONE clears an active fade");
        assertFalse(fade.isActive());
        assertFalse(fade.set(PaletteFadePresentation.Mode.NONE, 9, 0), "NONE with nothing active is a no-op");
        assertTrue(fade.set(PaletteFadePresentation.Mode.TO_BLACK, 1, 0b0010));
        assertTrue(fade.clear());
        assertFalse(fade.isActive());
        assertFalse(fade.affects(1));
    }
}
