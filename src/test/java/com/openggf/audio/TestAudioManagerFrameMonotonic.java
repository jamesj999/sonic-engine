package com.openggf.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: commandTimeline.currentFrame must move forward-only across
 * game-mode transitions where audio is updated via the non-gameplay path
 * (audioManager.update()) followed by an entry into LEVEL/TITLE_CARD where
 * the GameLoop's gameplayAudioFrame counter starts back at a low value.
 *
 * <p>Without the clamp in {@link AudioManager#beginGameplayAudioFrame(long)},
 * commands queued during the non-gameplay window (e.g. playMusic for the
 * selected level, or fadeOutMusic during exitLevelSelect) would sit in the
 * runtime's pendingCommands at a frame number larger than the new currentFrame
 * — and the consumeCommands frame-window filter could not drain them.
 */
class TestAudioManagerFrameMonotonic {

    private AudioManager audio;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @Test
    void beginGameplayAudioFrameNeverMovesCurrentFrameBackward() {
        // Simulate a long non-gameplay window (MASTER_TITLE_SCREEN, LEVEL_SELECT,
        // DATA_SELECT, etc.) where audioManager.update() advances the command
        // timeline by 1 each tick.
        for (int i = 0; i < 5; i++) {
            audio.update();
        }
        long advancedFrame = audio.commandTimeline().currentFrame();
        assertTrue(advancedFrame >= 5,
                "audioManager.update() should have advanced currentFrame at least 5 times");

        // First gameplay tick — GameLoop's gameplayAudioFrame counter starts
        // from a low value (the very first LEVEL tick after boot uses 1).
        audio.beginGameplayAudioFrame(1);

        assertTrue(audio.commandTimeline().currentFrame() > advancedFrame,
                "beginGameplayAudioFrame must clamp forward; currentFrame went backward "
                        + "from " + advancedFrame
                        + " to " + audio.commandTimeline().currentFrame());
    }

    @Test
    void beginGameplayAudioFrameAdvancesMonotonicallyOnSubsequentTicks() {
        // Establish the "ahead" state.
        for (int i = 0; i < 3; i++) {
            audio.update();
        }
        long baseline = audio.commandTimeline().currentFrame();

        // Several first-LEVEL-tick-equivalents in a row, each at a low frame.
        audio.beginGameplayAudioFrame(1);
        long afterFirst = audio.commandTimeline().currentFrame();
        audio.beginGameplayAudioFrame(2);
        long afterSecond = audio.commandTimeline().currentFrame();
        audio.beginGameplayAudioFrame(3);
        long afterThird = audio.commandTimeline().currentFrame();

        assertTrue(afterFirst > baseline, "first gameplay tick must advance currentFrame");
        assertEquals(afterFirst + 1, afterSecond,
                "subsequent gameplay tick must advance currentFrame by exactly 1");
        assertEquals(afterSecond + 1, afterThird,
                "third gameplay tick must advance currentFrame by exactly 1");
    }

    @Test
    void beginGameplayAudioFrameNormallyHonorsCallerValueWhenAhead() {
        // No prior update() calls — currentFrame is 0.
        long base = audio.commandTimeline().currentFrame();
        assertEquals(0L, base);

        audio.beginGameplayAudioFrame(1);
        assertEquals(1L, audio.commandTimeline().currentFrame(),
                "When the caller value is ahead of currentFrame, use it directly");

        audio.beginGameplayAudioFrame(2);
        assertEquals(2L, audio.commandTimeline().currentFrame());
    }
}
