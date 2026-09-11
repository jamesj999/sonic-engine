package com.openggf.game;

/**
 * Mode for level-load profile execution.
 */
public enum LevelLoadMode {
    /**
     * Full level load path (default).
     */
    FULL,

    /**
     * Hidden preview-capture load path.
     * Skips user-facing presentation side effects like music changes and title cards.
     */
    PREVIEW_CAPTURE
}
