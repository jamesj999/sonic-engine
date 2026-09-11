package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.game.dataselect.DataSelectExitTransition;

/**
 * Provider interface for the Data Select screen (S3K save file selection).
 * Games that support save slots implement this to manage the data select UI.
 */
public interface DataSelectProvider {

    /** Lifecycle state of the data select screen. */
    enum State { INACTIVE, FADE_IN, ACTIVE, EXITING }

    /** Initialize the data select screen (load art, palettes, layout). */
    void initialize();

    /** Update logic each frame (cursor movement, selection, input). */
    void update(InputHandler input);

    /** Draw the data select screen. */
    void draw();

    /**
     * Sets the projection-space viewport width for widescreen centering.
     *
     * <p>The data-select background tiles to fill the full viewport width; all
     * other content is centred by {@code (viewportWidth - 320) / 2}. At native
     * width 320 the offset is 0 — byte-identical. Default implementation is a
     * no-op (native behaviour).
     */
    default void setViewportWidth(int width) {
        // default: no-op (native 320)
    }

    /** Set the OpenGL clear color for the data select background. */
    void setClearColor();

    /** Reset all state (called when leaving the data select screen). */
    void reset();

    /**
     * Shows a launch failure while keeping the data select screen active.
     *
     * <p>Providers that have a native error presentation can render the message;
     * providers that only track menu state should at least expose it through
     * {@link #launchErrorMessage()} so callers and tests can confirm recovery.
     */
    default void showLaunchError(String message) {
        // default: no provider-local error presentation
    }

    /** Last data-select launch error, when the provider records one. */
    default java.util.Optional<String> launchErrorMessage() {
        return java.util.Optional.empty();
    }

    /** Returns the current lifecycle state. */
    State getState();

    /** Returns true if the screen is in the EXITING state. */
    boolean isExiting();

    /** Returns true if the screen is in the ACTIVE state. */
    boolean isActive();

    /** Host-native audio and reveal timing used when a gameplay selection exits. */
    default DataSelectExitTransition exitTransition() {
        return DataSelectExitTransition.defaultTransition();
    }
}
