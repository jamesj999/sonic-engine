package com.openggf.game.sonic1.titlecard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GM_Level reveals the level with PalFadeIn_Alt on palette lines 1-3
 * ({@code move.w #$202F,(v_pfade_start).w}, docs/s1disasm/sonic.asm:2965-2966):
 * 22 VBlank periods, each transferring the palette built so far and then
 * applying one FadeIn_AddColor pass (blue, then green, then red).
 */
class TestSonic1TitleCardPaletteFade {
    @Test
    void fadeStepsFollowThePalFadeInAltVBlankCadence() {
        assertEquals(22, Sonic1TitleCardManager.PALETTE_FADE_FRAMES);
        assertEquals(-1, Sonic1TitleCardManager.paletteFadeStepsAt(Sonic1TitleCardState.SLIDE_IN, 5));
        assertEquals(-1, Sonic1TitleCardManager.paletteFadeStepsAt(Sonic1TitleCardState.DISPLAY, 5));
        assertEquals(0, Sonic1TitleCardManager.paletteFadeStepsAt(Sonic1TitleCardState.SLIDE_OUT, 0),
                "the DISPLAY->SLIDE_OUT flip frame draws no black plane, so lines 1-3 must already be black");
        assertEquals(0, Sonic1TitleCardManager.paletteFadeStepsAt(Sonic1TitleCardState.SLIDE_OUT, 1),
                "first fade frame transfers the black lines before the first FadeIn_AddColor pass");
        assertEquals(20, Sonic1TitleCardManager.paletteFadeStepsAt(Sonic1TitleCardState.SLIDE_OUT, 21));
        assertEquals(-1, Sonic1TitleCardManager.paletteFadeStepsAt(Sonic1TitleCardState.SLIDE_OUT, 22),
                "the 22nd frame shows all 21 steps, which is the full palette");
        assertEquals(-1, Sonic1TitleCardManager.paletteFadeStepsAt(Sonic1TitleCardState.COMPLETE, 3));
    }

    @Test
    void levelRevealNoLongerUsesAnAlphaOverlay() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/game/sonic1/titlecard/Sonic1TitleCardManager.java"));
        assertFalse(source.contains("ONE_MINUS_SRC_ALPHA"),
                "the S1 level fade-in goes through CRAM (PalFadeIn_Alt), not a blended black rect");
        assertTrue(source.contains("setPaletteFadePresentation("));
    }
}
