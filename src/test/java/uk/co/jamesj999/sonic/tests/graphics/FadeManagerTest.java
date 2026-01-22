package uk.co.jamesj999.sonic.tests.graphics;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.graphics.FadeManager;
import uk.co.jamesj999.sonic.graphics.FadeManager.FadeState;
import uk.co.jamesj999.sonic.graphics.FadeManager.FadeType;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Tests for FadeManager state machine transitions and timing.
 * These tests verify fade behavior to ensure render pipeline consolidation
 * doesn't change timing or state transitions.
 */
public class FadeManagerTest {

    private static final int FADE_DURATION = 21;
    private static final int FRAMES_PER_CHANNEL = 7;

    private FadeManager fadeManager;

    @Before
    public void setUp() {
        FadeManager.resetInstance();
        fadeManager = FadeManager.getInstance();
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
        assertTrue("Red should increase first", color[0] > 0);
        assertEquals("Green should be zero", 0f, color[1], 0.001f);
        assertEquals("Blue should be zero", 0f, color[2], 0.001f);
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
        assertEquals("Red should be at max", 1.0f, color[0], 0.01f);
        assertTrue("Green should start increasing", color[1] > 0);
        assertEquals("Blue should still be zero", 0f, color[2], 0.001f);
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
        assertEquals("Red should be at max", 1.0f, color[0], 0.01f);
        assertEquals("Green should be at max", 1.0f, color[1], 0.01f);
        assertTrue("Blue should start increasing", color[2] > 0);
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

        assertTrue("Callback should have executed", callbackExecuted.get());
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
        assertTrue("Darkness should increase", afterUpdate[0] > initialColor[0] ||
                afterUpdate[1] > initialColor[1] || afterUpdate[2] > initialColor[2]);
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
        assertEquals("Red should start at max", 1.0f, color[0], 0.001f);
        assertEquals("Green should start at max", 1.0f, color[1], 0.001f);
        assertEquals("Blue should start at max", 1.0f, color[2], 0.001f);
    }

    @Test
    public void testFadeFromWhiteDecreasesToZero() {
        fadeManager.startFadeFromWhite(null);

        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        float[] color = fadeManager.getFadeColor();
        assertEquals("Red should be zero", 0f, color[0], 0.01f);
        assertEquals("Green should be zero", 0f, color[1], 0.01f);
        assertEquals("Blue should be zero", 0f, color[2], 0.01f);
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
        assertEquals("Darkness values should start at max", 1.0f, color[0], 0.001f);
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
            assertEquals("Should remain in HOLD_WHITE during hold period", FadeState.HOLD_WHITE, fadeManager.getState());
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

        assertFalse("Callback should not execute after cancel", callbackExecuted.get());
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

            assertTrue("Red should not decrease", color[0] >= prevR);
            assertTrue("Green should not decrease", color[1] >= prevG);
            assertTrue("Blue should not decrease", color[2] >= prevB);

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

            assertTrue("Red should not increase", color[0] <= prevR);
            assertTrue("Green should not increase", color[1] <= prevG);
            assertTrue("Blue should not increase", color[2] <= prevB);

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
        assertEquals("Red increment should be 1/7", 1.0f / 7.0f, color[0], 0.001f);
    }

    @Test
    public void testFullWhiteAfter21Frames() {
        fadeManager.startFadeToWhite(null);

        for (int i = 0; i < FADE_DURATION; i++) {
            fadeManager.update();
        }

        float[] color = fadeManager.getFadeColor();
        assertEquals("Red should be 1.0", 1.0f, color[0], 0.01f);
        assertEquals("Green should be 1.0", 1.0f, color[1], 0.01f);
        assertEquals("Blue should be 1.0", 1.0f, color[2], 0.01f);
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
        assertTrue("Red darkness should increase first", color[0] > 0);
        assertEquals("Green darkness should be zero", 0f, color[1], 0.001f);
        assertEquals("Blue darkness should be zero", 0f, color[2], 0.001f);
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
        assertEquals("Red darkness should be at max", 1.0f, color[0], 0.01f);
        assertTrue("Green darkness should start increasing", color[1] > 0);
        assertEquals("Blue darkness should still be zero", 0f, color[2], 0.001f);
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
        assertEquals("Colors should reset", 0f, color[0], 0.001f);
        assertEquals(0f, color[1], 0.001f);
        assertEquals(0f, color[2], 0.001f);
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
        assertFalse("Callback should not execute during fade", callbackExecuted.get());

        // During hold (should not execute yet)
        for (int i = 0; i < holdFrames - 1; i++) {
            fadeManager.update();
            assertFalse("Callback should not execute during hold", callbackExecuted.get());
        }

        // Final hold frame should trigger callback
        fadeManager.update();
        assertTrue("Callback should execute after hold completes", callbackExecuted.get());
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

        assertEquals("Callback should execute exactly once", 1, callCount[0]);
    }
}
