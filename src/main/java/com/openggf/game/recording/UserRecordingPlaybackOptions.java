package com.openggf.game.recording;

public record UserRecordingPlaybackOptions(
        int targetFrame,
        boolean pauseOnDesync,
        boolean fastForward
) {
    public static UserRecordingPlaybackOptions defaults(int movieFrameCount) {
        if (movieFrameCount <= 0) {
            throw new IllegalArgumentException("movieFrameCount must be greater than zero");
        }
        return new UserRecordingPlaybackOptions(movieFrameCount - 1, true, false);
    }
}
