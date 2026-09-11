package com.openggf.graphics;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class TestFadeManager {

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    /**
     * Number of update frames for a standard fade to complete.
     * Matches FadeManager.FADE_DURATION (7 steps per RGB channel = 21 frames).
     */
    private static final int FADE_DURATION_FRAMES = 21;

    @Test
    public void testFadeToWhiteCompletes() {
        GameServices.fade().resetState();
        FadeManager fadeManager = GameServices.fade();

        fadeManager.startFadeToWhite(null);
        for (int i = 0; i < FADE_DURATION_FRAMES; i++) {
            fadeManager.update();
        }

        assertEquals(FadeManager.FadeState.HOLD_WHITE, fadeManager.getState());
        fadeManager.update();
        assertEquals(FadeManager.FadeState.NONE, fadeManager.getState());
        assertFalse(fadeManager.isActive());
    }

    @Test
    public void testFadeToBlackWithHoldCompletes() {
        GameServices.fade().resetState();
        FadeManager fadeManager = GameServices.fade();

        fadeManager.startFadeToBlack(null, 5);
        for (int i = 0; i < FADE_DURATION_FRAMES; i++) {
            fadeManager.update();
        }

        assertEquals(FadeManager.FadeState.HOLD_BLACK, fadeManager.getState());

        for (int i = 0; i < 5; i++) {
            fadeManager.update();
        }

        assertEquals(FadeManager.FadeState.NONE, fadeManager.getState());
    }

    @Test
    public void customDurationFadeToBlackCompletesOnExactFrameAndHoldsBlack() {
        FadeManager fadeManager = GameServices.fade();
        fadeManager.resetState();
        int[] completions = {0};

        fadeManager.startFadeToBlack(() -> completions[0]++, 0, 60);
        for (int i = 0; i < 59; i++) {
            fadeManager.update();
        }
        assertEquals(0, completions[0]);

        fadeManager.update();

        assertEquals(1, completions[0]);
        assertEquals(FadeManager.FadeState.HOLD_BLACK, fadeManager.getState());
    }

    @Test
    void fadeFromBlackCanRetainTheRomTerminalNoOpVblank() {
        FadeManager fadeManager = GameServices.fade();
        fadeManager.resetState();
        int[] completions = {0};

        fadeManager.startFadeFromBlack(() -> completions[0]++, 1);
        for (int i = 0; i < FADE_DURATION_FRAMES; i++) {
            fadeManager.update();
        }

        assertEquals(FadeManager.FadeState.FADING_FROM_BLACK, fadeManager.getState());
        assertEquals(0, completions[0]);
        assertEquals(0.0f, fadeManager.getFadeColor()[0]);
        assertEquals(0.0f, fadeManager.getFadeColor()[1]);
        assertEquals(0.0f, fadeManager.getFadeColor()[2]);

        fadeManager.update();

        assertEquals(FadeManager.FadeState.NONE, fadeManager.getState());
        assertEquals(1, completions[0]);
    }
}
