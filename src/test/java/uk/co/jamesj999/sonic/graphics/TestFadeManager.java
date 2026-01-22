package uk.co.jamesj999.sonic.graphics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TestFadeManager {
    @Test
    public void testFadeToWhiteCompletes() {
        FadeManager.resetInstance();
        FadeManager fadeManager = FadeManager.getInstance();

        fadeManager.startFadeToWhite(null);
        for (int i = 0; i < 21; i++) {
            fadeManager.update();
        }

        assertEquals(FadeManager.FadeState.HOLD_WHITE, fadeManager.getState());
        fadeManager.update();
        assertEquals(FadeManager.FadeState.NONE, fadeManager.getState());
        assertFalse(fadeManager.isActive());
    }

    @Test
    public void testFadeToBlackWithHoldCompletes() {
        FadeManager.resetInstance();
        FadeManager fadeManager = FadeManager.getInstance();

        fadeManager.startFadeToBlack(null, 5);
        for (int i = 0; i < 21; i++) {
            fadeManager.update();
        }

        assertEquals(FadeManager.FadeState.HOLD_BLACK, fadeManager.getState());

        for (int i = 0; i < 5; i++) {
            fadeManager.update();
        }

        assertEquals(FadeManager.FadeState.NONE, fadeManager.getState());
    }
}
