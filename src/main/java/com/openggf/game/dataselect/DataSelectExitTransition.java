package com.openggf.game.dataselect;

/**
 * Host-owned audio and reveal timing for leaving a donated or native data select.
 *
 * @param confirmationSfxId confirmation cue, or {@code -1} for none
 * @param musicFadeSteps SMPS music-fade volume steps
 * @param musicFadeDelay SMPS driver services between fade steps
 * @param revealTerminalNoOpFrames fully revealed VBlanks retained before completion
 */
public record DataSelectExitTransition(
        int confirmationSfxId,
        int musicFadeSteps,
        int musicFadeDelay,
        int revealTerminalNoOpFrames) {

    public DataSelectExitTransition {
        if (confirmationSfxId < -1) {
            throw new IllegalArgumentException("confirmationSfxId must be -1 or a native sound id");
        }
        if (musicFadeSteps <= 0) {
            throw new IllegalArgumentException("musicFadeSteps must be positive");
        }
        if (musicFadeDelay < 0 || revealTerminalNoOpFrames < 0) {
            throw new IllegalArgumentException("fade delays must not be negative");
        }
    }

    /** Legacy fallback for providers without a host profile. */
    public static DataSelectExitTransition defaultTransition() {
        return new DataSelectExitTransition(-1, 0x28, 3, 0);
    }
}
