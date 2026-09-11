package com.openggf.tests.graphics;

import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameServices;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.FadeManager.FadeState;
import com.openggf.graphics.FadeManager.FadeType;
import com.openggf.tests.TestEnvironment;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FadeManager state machine transitions and timing.
 * These tests verify fade behavior to ensure render pipeline consolidation
 * doesn't change timing or state transitions.
 */
public class FadeManagerTest {

    private static final int FADE_DURATION = 21;
    private static final int FRAMES_PER_CHANNEL = 7;

    private FadeManager fadeManager;

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
        GameServices.fade().resetState();
        fadeManager = GameServices.fade();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    // === Initial State Tests ===

    @Test
    public void testInitialStateIsNone() {
        assertEquals(FadeState.NONE, fadeManager.getState());
    }

    @Test
    public void testInitiallyNotActive() {
        assertFalse(fadeManager.isActive());
    }

    @Test
    public void testInitialFadeColorIsZero() {
        float[] color = fadeManager.getFadeColor();
        assertEquals(0f, color[0], 0.001f);
        assertEquals(0f, color[1], 0.001f);
        assertEquals(0f, color[2], 0.001f);
    }

    // === Fade To White Tests ===

    @Test
    public void testFadeToWhiteStartsCorrectState() {
        fadeManager.startFadeToWhite(null);

        assertEquals(FadeState.FADING_TO_WHITE, fadeManager.getState());
        assertEquals(FadeType.WHITE, fadeManager.getFadeType());
        assertTrue(fadeManager.isActive());
    }

    @Test
    public void testFadeToWhiteRedChannelFirst() {
        fadeManager.startFadeToWhite(null);

        // After 1 frame, only red should increase
        fadeManager.update();
        float[] color = fadeManager.getFadeColor();
        assertTrue(color[0] > 0, "Red should increase first");
        assertEquals(0f, color[1], 0.001f, "Green should be zero");
        assertEquals(0f, color[2], 0.001f, "Blue should be zero");
    }

    @Test
    public void testFadeToWhiteGreenChannelSecond() {
        fadeManager.startFadeToWhite(null);

        // Advance past red phase (7 frames)
        for (int i = 0; i < FRAMES_PER_CHANNEL; i++) {
            fadeManager.update();
        }

        // One more frame should start green
        fadeManager.update();
        float[] color = fadeManager.getFadeColor();
        assertEquals(1.0f, color[0], 0.01f, "Red should be at max");
        assertTrue(color[1] > 0, "Green should start increasing");
        assertEquals(0f, color[2], 0.001f, "Blue should still be zero");
    }

    @Test
    public void testFadeToWhiteBlueChannelThird() {
        fadeManager.startFadeToWhite(null);

        // Advance past red and green phases (14 frames)
        for (int i = 0; i < FRAMES_PER_CHANNEL * 2; i++) {
            fadeManager.update();
        }

        // One more frame should start blue
        fadeManager.update();
        float[] color = fadeManager.getFadeColor();
        assertEquals(1.0f, color[0], 0.01f, "Red should be at max");
        assertEquals(1.0f, color[1], 0.01f, "Green should be at max");
        assertTrue(color[2] > 0, "Blue should start increasing");
    }

    @Test
    public void testFadeToWhiteCompletesAt21Frames() {
        fadeManager.startFadeToWhite(null);

        // Advance 21 frames
        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        // Should transition to HOLD_WHITE (default hold of 1 frame when holdDuration=0)
        assertEquals(FadeState.HOLD_WHITE, fadeManager.getState());
    }

    @Test
    public void testFadeToWhiteCallbackExecutes() {
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);
        fadeManager.startFadeToWhite(() -> callbackExecuted.set(true));

        // Complete the fade (21 frames) + default hold (1 frame)
        for (int i = 0; i < FADE_DURATION + 1; i++) {
            fadeManager.update();
        }

        assertTrue(callbackExecuted.get(), "Callback should have executed");
    }

    // === Fade To Black Tests ===

    @Test
    public void testFadeToBlackStartsCorrectState() {
        fadeManager.startFadeToBlack(null);

        assertEquals(FadeState.FADING_TO_BLACK, fadeManager.getState());
        assertEquals(FadeType.BLACK, fadeManager.getFadeType());
        assertTrue(fadeManager.isActive());
    }

    @Test
    public void testFadeToBlackIncreasesFromZero() {
        fadeManager.startFadeToBlack(null);
        float[] initialColor = fadeManager.getFadeColor();

        fadeManager.update();
        float[] afterUpdate = fadeManager.getFadeColor();

        // For black fade, values represent darkness (0 = full color, 1 = black)
        assertTrue(afterUpdate[0] > initialColor[0] ||
                afterUpdate[1] > initialColor[1] || afterUpdate[2] > initialColor[2], "Darkness should increase");
    }

    @Test
    public void testFadeToBlackCompletesAt21Frames() {
        fadeManager.startFadeToBlack(null);

        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        assertEquals(FadeState.HOLD_BLACK, fadeManager.getState());
    }

    // === Fade From White Tests ===

    @Test
    public void testFadeFromWhiteStartsAtFullWhite() {
        fadeManager.startFadeFromWhite(null);

        float[] color = fadeManager.getFadeColor();
        assertEquals(1.0f, color[0], 0.001f, "Red should start at max");
        assertEquals(1.0f, color[1], 0.001f, "Green should start at max");
        assertEquals(1.0f, color[2], 0.001f, "Blue should start at max");
    }

    @Test
    public void testFadeFromWhiteDecreasesToZero() {
        fadeManager.startFadeFromWhite(null);

        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        float[] color = fadeManager.getFadeColor();
        assertEquals(0f, color[0], 0.01f, "Red should be zero");
        assertEquals(0f, color[1], 0.01f, "Green should be zero");
        assertEquals(0f, color[2], 0.01f, "Blue should be zero");
    }

    @Test
    public void testFadeFromWhiteCompletesToNone() {
        fadeManager.startFadeFromWhite(null);

        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        assertEquals(FadeState.NONE, fadeManager.getState());
        assertFalse(fadeManager.isActive());
    }

    // === Fade From Black Tests ===

    @Test
    public void testFadeFromBlackStartsAtFullBlack() {
        fadeManager.startFadeFromBlack(null);

        float[] color = fadeManager.getFadeColor();
        assertEquals(1.0f, color[0], 0.001f, "Darkness values should start at max");
        assertEquals(1.0f, color[1], 0.001f);
        assertEquals(1.0f, color[2], 0.001f);
    }

    @Test
    public void testFadeFromBlackCompletesToNone() {
        fadeManager.startFadeFromBlack(null);

        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        assertEquals(FadeState.NONE, fadeManager.getState());
        assertFalse(fadeManager.isActive());
    }

    // === Hold Tests ===

    @Test
    public void testHoldWhiteState() {
        fadeManager.startFadeToWhite(null, 5); // 5 frame hold

        // Complete fade to white
        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        assertEquals(FadeState.HOLD_WHITE, fadeManager.getState());

        // Should stay in hold for specified frames
        fadeManager.update();
        assertEquals(FadeState.HOLD_WHITE, fadeManager.getState());
    }

    @Test
    public void testHoldBlackState() {
        fadeManager.startFadeToBlack(null, 10); // 10 frame hold

        // Complete fade to black
        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        assertEquals(FadeState.HOLD_BLACK, fadeManager.getState());
    }

    @Test
    public void testHoldDurationRespected() {
        int holdFrames = 5;
        fadeManager.startFadeToWhite(null, holdFrames);

        // Complete fade to white (21 frames)
        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }
        assertEquals(FadeState.HOLD_WHITE, fadeManager.getState());

        // Should stay in hold for exactly holdFrames
        for (int i = 0; i < holdFrames - 1; i++) {
            fadeManager.update();
            assertEquals(FadeState.HOLD_WHITE, fadeManager.getState(), "Should remain in HOLD_WHITE during hold period");
        }

        // One more update should complete the hold
        fadeManager.update();
        assertEquals(FadeState.NONE, fadeManager.getState());
    }

    // === Cancel Tests ===

    @Test
    public void testCancelResetsFade() {
        fadeManager.startFadeToWhite(null);
        fadeManager.update();
        fadeManager.update();

        fadeManager.cancel();

        assertEquals(FadeState.NONE, fadeManager.getState());
        assertFalse(fadeManager.isActive());

        float[] color = fadeManager.getFadeColor();
        assertEquals(0f, color[0], 0.001f);
        assertEquals(0f, color[1], 0.001f);
        assertEquals(0f, color[2], 0.001f);
    }

    @Test
    public void testCancelPreventsCallback() {
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);
        fadeManager.startFadeToWhite(() -> callbackExecuted.set(true));

        fadeManager.update();
        fadeManager.cancel();

        // Continue updates
        for (int i = 0; i < FADE_DURATION + 10; i++) {
            fadeManager.update();
        }

        assertFalse(callbackExecuted.get(), "Callback should not execute after cancel");
    }

    // === Frame Counter Tests ===

    @Test
    public void testFrameCounterIncrements() {
        fadeManager.startFadeToWhite(null);
        assertEquals(0, fadeManager.getFrameCount());

        fadeManager.update();
        assertEquals(1, fadeManager.getFrameCount());

        fadeManager.update();
        assertEquals(2, fadeManager.getFrameCount());
    }

    @Test
    public void testNoUpdateWhenInactive() {
        // When not active, update should not change anything
        fadeManager.update();
        assertEquals(FadeState.NONE, fadeManager.getState());
        assertEquals(0, fadeManager.getFrameCount());
    }

    // === Color Monotonicity Tests ===

    @Test
    public void testFadeToWhiteColorsMonotonicallyIncrease() {
        fadeManager.startFadeToWhite(null);

        float prevR = 0, prevG = 0, prevB = 0;
        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
            float[] color = fadeManager.getFadeColor();

            assertTrue(color[0] >= prevR, "Red should not decrease");
            assertTrue(color[1] >= prevG, "Green should not decrease");
            assertTrue(color[2] >= prevB, "Blue should not decrease");

            prevR = color[0];
            prevG = color[1];
            prevB = color[2];
        }
    }

    @Test
    public void testFadeFromWhiteColorsMonotonicallyDecrease() {
        fadeManager.startFadeFromWhite(null);

        float prevR = 1, prevG = 1, prevB = 1;
        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
            float[] color = fadeManager.getFadeColor();

            assertTrue(color[0] <= prevR, "Red should not increase");
            assertTrue(color[1] <= prevG, "Green should not increase");
            assertTrue(color[2] <= prevB, "Blue should not increase");

            prevR = color[0];
            prevG = color[1];
            prevB = color[2];
        }
    }

    // === Channel Increment Value Tests ===

    @Test
    public void testChannelIncrementValue() {
        fadeManager.startFadeToWhite(null);

        // After 1 frame, red should be approximately 1/7
        fadeManager.update();
        float[] color = fadeManager.getFadeColor();
        assertEquals(1.0f / 7.0f, color[0], 0.001f, "Red increment should be 1/7");
    }

    @Test
    public void testFullWhiteAfter21Frames() {
        fadeManager.startFadeToWhite(null);

        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        float[] color = fadeManager.getFadeColor();
        assertEquals(1.0f, color[0], 0.01f, "Red should be 1.0");
        assertEquals(1.0f, color[1], 0.01f, "Green should be 1.0");
        assertEquals(1.0f, color[2], 0.01f, "Blue should be 1.0");
    }

    // === Fade Type Tests ===

    @Test
    public void testFadeTypeWhite() {
        fadeManager.startFadeToWhite(null);
        assertEquals(FadeType.WHITE, fadeManager.getFadeType());

        fadeManager.cancel();
        fadeManager.startFadeFromWhite(null);
        assertEquals(FadeType.WHITE, fadeManager.getFadeType());
    }

    @Test
    public void testFadeTypeBlack() {
        fadeManager.startFadeToBlack(null);
        assertEquals(FadeType.BLACK, fadeManager.getFadeType());

        fadeManager.cancel();
        fadeManager.startFadeFromBlack(null);
        assertEquals(FadeType.BLACK, fadeManager.getFadeType());
    }

    // === Sequential Channel Tests for Black Fade ===

    @Test
    public void testFadeToBlackRedChannelFirst() {
        fadeManager.startFadeToBlack(null);

        // After 1 frame, only red darkness should increase
        fadeManager.update();
        float[] color = fadeManager.getFadeColor();
        assertTrue(color[0] > 0, "Red darkness should increase first");
        assertEquals(0f, color[1], 0.001f, "Green darkness should be zero");
        assertEquals(0f, color[2], 0.001f, "Blue darkness should be zero");
    }

    @Test
    public void testFadeToBlackGreenChannelSecond() {
        fadeManager.startFadeToBlack(null);

        // Advance past red phase (7 frames)
        for (int i = 0; i < FRAMES_PER_CHANNEL; i++) {
            fadeManager.update();
        }

        // One more frame should start green
        fadeManager.update();
        float[] color = fadeManager.getFadeColor();
        assertEquals(1.0f, color[0], 0.01f, "Red darkness should be at max");
        assertTrue(color[1] > 0, "Green darkness should start increasing");
        assertEquals(0f, color[2], 0.001f, "Blue darkness should still be zero");
    }

    // === Restart Fade Tests ===

    @Test
    public void testCanRestartFadeAfterCompletion() {
        fadeManager.startFadeToWhite(null);

        // Complete the fade
        for (int i = 0; i < FADE_DURATION + 1; i++) {
            fadeManager.update();
        }
        assertEquals(FadeState.NONE, fadeManager.getState());

        // Start a new fade
        fadeManager.startFadeToBlack(null);
        assertEquals(FadeState.FADING_TO_BLACK, fadeManager.getState());
        assertEquals(0, fadeManager.getFrameCount());
    }

    @Test
    public void testCanRestartFadeMidway() {
        fadeManager.startFadeToWhite(null);

        // Advance 10 frames
        for (int i = 0; i < 10; i++) {
            fadeManager.update();
        }

        // Start a new fade (should reset)
        fadeManager.startFadeToBlack(null);
        assertEquals(FadeState.FADING_TO_BLACK, fadeManager.getState());
        assertEquals(0, fadeManager.getFrameCount());

        float[] color = fadeManager.getFadeColor();
        assertEquals(0f, color[0], 0.001f, "Colors should reset");
        assertEquals(0f, color[1], 0.001f);
        assertEquals(0f, color[2], 0.001f);
    }

    @Test
    public void holdBlackReplacesRunningRevealAndRemainsOpaque() {
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);
        fadeManager.startFadeFromBlack(() -> callbackExecuted.set(true));

        fadeManager.holdBlack();
        for (int frame = 0; frame < 30; frame++) {
            fadeManager.update();
        }

        assertEquals(FadeState.HOLD_BLACK, fadeManager.getState());
        assertEquals(FadeType.BLACK, fadeManager.getFadeType());
        assertArrayEquals(new float[] {1f, 1f, 1f}, fadeManager.getFadeColor(), 0.001f);
        assertFalse(fadeManager.hasPendingCompletion());
        assertFalse(callbackExecuted.get());
    }

    @Test
    public void holdWhiteReplacesRunningFadeAndRemainsOpaque() {
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);
        fadeManager.startFadeToBlack(() -> callbackExecuted.set(true));

        fadeManager.holdWhite();
        for (int frame = 0; frame < 30; frame++) {
            fadeManager.update();
        }

        assertEquals(FadeState.HOLD_WHITE, fadeManager.getState());
        assertEquals(FadeType.WHITE, fadeManager.getFadeType());
        assertArrayEquals(new float[] {1f, 1f, 1f}, fadeManager.getFadeColor(), 0.001f);
        assertFalse(fadeManager.hasPendingCompletion());
        assertFalse(callbackExecuted.get());
    }

    // === Callback Execution Timing Tests ===

    @Test
    public void testCallbackExecutesAfterHold() {
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);
        int holdFrames = 3;
        fadeManager.startFadeToWhite(() -> callbackExecuted.set(true), holdFrames);

        // Complete fade (21 frames)
        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }
        assertFalse(callbackExecuted.get(), "Callback should not execute during fade");

        // During hold (should not execute yet)
        for (int i = 0; i < holdFrames - 1; i++) {
            fadeManager.update();
            assertFalse(callbackExecuted.get(), "Callback should not execute during hold");
        }

        // Final hold frame should trigger callback
        fadeManager.update();
        assertTrue(callbackExecuted.get(), "Callback should execute after hold completes");
    }

    @Test
    public void testCallbackExecutedOnlyOnce() {
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);
        int[] callCount = {0};
        fadeManager.startFadeToWhite(() -> callCount[0]++);

        // Complete fade + hold + extra updates
        for (int i = 0; i < FADE_DURATION + 10; i++) {
            fadeManager.update();
        }

        assertEquals(1, callCount[0], "Callback should execute exactly once");
    }
}

