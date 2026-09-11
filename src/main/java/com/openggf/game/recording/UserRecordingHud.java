package com.openggf.game.recording;

public final class UserRecordingHud {
    public static final String HOLD_PROMPT_TEXT = "Hold Shift+Record for 1 Sec to Begin Recording";

    private UserRecordingHud() {
    }

    public static UserRecordingHudState holdPromptState(int heldFrames, int requiredFrames) {
        int safeRequired = Math.max(1, requiredFrames);
        int safeHeld = Math.max(0, Math.min(heldFrames, safeRequired));
        return new UserRecordingHudState(
                true,
                HOLD_PROMPT_TEXT,
                "Hold " + safeHeld + "/" + safeRequired,
                safeHeld,
                false,
                true);
    }

    public void render(UserRecordingHudState state) {
        // Task 11 wires actual GameLoop rendering. Keep this side-effect-free so
        // session lifecycle tests do not need an OpenGL context.
    }
}
