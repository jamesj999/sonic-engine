package com.openggf.game.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUserRecordingPlaybackController {
    @Test
    void desyncBeatsTargetWhenPauseOnDesyncIsEnabled() {
        UserRecordingPlaybackController controller = new UserRecordingPlaybackController(
                new UserRecordingPlaybackOptions(10, true, false));

        UserRecordingPlaybackState state = controller.afterFrame(10, true, false, false);

        assertEquals(UserRecordingPlaybackState.PAUSED_ON_DESYNC, state);
    }

    @Test
    void desyncDoesNotPauseWhenPauseOnDesyncIsDisabled() {
        UserRecordingPlaybackController controller = new UserRecordingPlaybackController(
                new UserRecordingPlaybackOptions(10, false, false));

        UserRecordingPlaybackState state = controller.afterFrame(9, true, false, false);

        assertEquals(UserRecordingPlaybackState.PLAYING, state);
    }

    @Test
    void completionBeatsTargetWhenNoDesyncIsPresent() {
        UserRecordingPlaybackController controller = new UserRecordingPlaybackController(
                new UserRecordingPlaybackOptions(10, true, false));

        UserRecordingPlaybackState state = controller.afterFrame(10, false, true, false);

        assertEquals(UserRecordingPlaybackState.PAUSED_AT_COMPLETION, state);
    }

    @Test
    void movieEndBeatsTargetWhenNoDesyncIsPresent() {
        UserRecordingPlaybackController controller = new UserRecordingPlaybackController(
                new UserRecordingPlaybackOptions(10, true, false));

        UserRecordingPlaybackState state = controller.afterFrame(10, false, false, true);

        assertEquals(UserRecordingPlaybackState.PAUSED_AT_COMPLETION, state);
    }

    @Test
    void targetPausesExactlyOnRequestedFrame() {
        UserRecordingPlaybackController controller = new UserRecordingPlaybackController(
                new UserRecordingPlaybackOptions(10, true, false));

        assertEquals(UserRecordingPlaybackState.PLAYING, controller.afterFrame(9, false, false, false));
        assertEquals(UserRecordingPlaybackState.PAUSED_AT_TARGET, controller.afterFrame(10, false, false, false));
    }

    @Test
    void fastForwardDoesNotChangeStateClassification() {
        UserRecordingPlaybackController normal = new UserRecordingPlaybackController(
                new UserRecordingPlaybackOptions(10, true, false));
        UserRecordingPlaybackController fastForward = new UserRecordingPlaybackController(
                new UserRecordingPlaybackOptions(10, true, true));

        assertEquals(
                normal.afterFrame(10, false, false, false),
                fastForward.afterFrame(10, false, false, false));
        assertEquals(
                normal.afterFrame(8, true, false, false),
                fastForward.afterFrame(8, true, false, false));
        assertEquals(
                normal.afterFrame(8, false, false, true),
                fastForward.afterFrame(8, false, false, true));
    }

    @Test
    void defaultsTargetLastFrameAndPauseOnDesyncWithoutFastForward() {
        UserRecordingPlaybackOptions options = UserRecordingPlaybackOptions.defaults(42);

        assertEquals(41, options.targetFrame());
        assertTrue(options.pauseOnDesync());
        assertFalse(options.fastForward());
    }

    @Test
    void defaultsRejectsEmptyMovie() {
        assertThrows(IllegalArgumentException.class, () -> UserRecordingPlaybackOptions.defaults(0));
    }
}
