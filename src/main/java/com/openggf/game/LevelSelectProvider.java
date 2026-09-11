package com.openggf.game;

import com.openggf.control.InputHandler;

/**
 * Interface for game-specific level select screens.
 * Each game (Sonic 1, Sonic 2, etc.) provides its own implementation
 * with game-accurate menu layout, text, and navigation.
 */
public interface LevelSelectProvider {

    enum State {
        /** Screen is not active */
        INACTIVE,
        /** Fading in from black */
        FADE_IN,
        /** Main interactive state */
        ACTIVE,
        /** Ready to exit and load selected level */
        EXITING
    }

    /**
     * Initializes the level select screen.
     * Loads data from ROM and begins fade-in.
     */
    void initialize();

    /**
     * Updates the level select state machine.
     *
     * @param input Input handler for keyboard input
     */
    void update(InputHandler input);

    /**
     * Renders the level select screen.
     */
    void draw();

    /**
     * Sets the OpenGL clear color to the level select backdrop color.
     */
    void setClearColor();

    /**
     * Resets the manager to inactive state.
     */
    void reset();

    /**
     * Returns the current state.
     */
    State getState();

    /**
     * Returns true if the level select is exiting (level should be loaded).
     */
    boolean isExiting();

    /**
     * Returns true if the level select is active (not inactive).
     */
    boolean isActive();

    /**
     * Returns true if Special Stage is selected.
     */
    boolean isSpecialStageSelected();

    /**
     * Returns true if Sound Test is selected.
     */
    boolean isSoundTestSelected();

    /**
     * Gets the selected zone index or -1 for special stage/sound test.
     */
    int getSelectedZone();

    /**
     * Gets the selected act index or -1 for special stage/sound test.
     */
    int getSelectedAct();

    /**
     * Gets the selected zone/act word value.
     * High byte = zone ID, low byte = act number.
     * Special values are game-specific.
     */
    int getSelectedZoneAct();

    /**
     * Gets the current menu selection index.
     */
    int getSelectedIndex();

    /**
     * Gets the current sound test value.
     */
    int getSoundTestValue();

    /**
     * Initializes the level select screen when transitioning from the title screen.
     * Unlike {@link #initialize()}, this does not restart music (it continues
     * playing from the title screen) and performs no fade transition.
     * <p>Default implementation delegates to {@link #initialize()}.
     */
    default void initializeFromTitleScreen() {
        initialize();
    }

    /**
     * Sets the viewport width so the level select can center its 320-px-wide
     * content block within a wider projection (widescreen support).
     *
     * <p>At native width (320) every element stays in its exact original position —
     * the x offset is {@code (320 - 320) / 2 = 0}, so native output is byte-identical.
     * Default implementation is a no-op (safe for implementations that have not yet
     * been made widescreen-aware).
     *
     * @param width the projection-space viewport width; must be &ge; 320
     */
    default void setViewportWidth(int width) {
        // no-op default — subclasses opt in
    }
}
