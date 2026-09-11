package com.openggf.game.recording;

import java.util.Objects;

public final class UserRecordingPlaybackController {
    private final UserRecordingPlaybackOptions options;

    public UserRecordingPlaybackController(UserRecordingPlaybackOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public UserRecordingPlaybackState afterFrame(int currentMovieFrame,
                                                 boolean desync,
                                                 boolean levelEnded,
                                                 boolean movieEnded) {
        if (options.pauseOnDesync() && desync) {
            return UserRecordingPlaybackState.PAUSED_ON_DESYNC;
        }
        if (levelEnded || movieEnded) {
            return UserRecordingPlaybackState.PAUSED_AT_COMPLETION;
        }
        if (currentMovieFrame >= options.targetFrame()) {
            return UserRecordingPlaybackState.PAUSED_AT_TARGET;
        }
        return UserRecordingPlaybackState.PLAYING;
    }
}
