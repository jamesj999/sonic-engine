package com.openggf.game.sonic2.credits;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSonic2CreditsData {
    @Test
    public void testCreditScreenCount() {
        assertEquals(21, Sonic2CreditsData.TOTAL_CREDITS);
    }

    @Test
    public void testTimingConstants() {
        assertEquals(0x18E, Sonic2CreditsData.SLIDE_DURATION_60FPS);
        assertEquals(0x16, Sonic2CreditsData.FADE_DURATION);
    }

    @Test
    public void testLogoFlashConstants() {
        assertEquals(0x257, Sonic2CreditsData.LOGO_HOLD_FRAMES);
        assertEquals(9, Sonic2CreditsData.PALETTE_CYCLE_FRAME_COUNT);
        assertEquals(24, Sonic2CreditsData.PALETTE_CYCLE_BYTES_PER_FRAME);
    }

    @Test
    public void testVramTileBases() {
        // VRAM tile base for credit text font
        assertEquals(0x0500, Sonic2CreditsData.ARTTILE_CREDIT_TEXT);
        // Credits screen uses tile base 1 (not 0x500)
        assertEquals(0x0001, Sonic2CreditsData.ARTTILE_CREDIT_TEXT_CREDSCR);
    }
}


