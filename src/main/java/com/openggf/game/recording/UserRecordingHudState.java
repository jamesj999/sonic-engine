package com.openggf.game.recording;

public record UserRecordingHudState(
        boolean visible,
        String primaryText,
        String secondaryText,
        int frame,
        boolean amberWarning,
        boolean redWarning
) {
    public static UserRecordingHudState hidden() {
        return new UserRecordingHudState(false, "", "", 0, false, false);
    }
}
