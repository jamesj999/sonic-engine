package com.openggf.game;

import com.openggf.control.InputHandler;

/**
 * Interface for game-specific title screens.
 * Each game (Sonic 1, Sonic 2, etc.) provides its own implementation
 * with game-accurate art, palettes, and scrolling.
 */
public interface TitleScreenProvider {

    enum TitleScreenAction {
        ONE_PLAYER,
        TWO_PLAYER,
        OPTIONS,
        OTHER
    }

    enum State {
        /** Screen is not active */
        INACTIVE,
        /** Intro text fading in from black */
        INTRO_TEXT_FADE_IN,
        /** Intro text displayed (hold) */
        INTRO_TEXT_HOLD,
        /** Intro text fading out to black */
        INTRO_TEXT_FADE_OUT,
        /** Pre-title SEGA boot logo screen */
        SEGA_LOGO,
        /** Fading in from black */
        FADE_IN,
        /** Main interactive state */
        ACTIVE,
        /** Ready to exit */
        EXITING
    }

    /**
     * Initializes the title screen.
     * Loads data from ROM and begins fade-in.
     */
    void initialize();

    /**
     * Updates the title screen state machine.
     *
     * @param input Input handler for keyboard input
     */
    void update(InputHandler input);

    /**
     * Renders the title screen.
     */
    void draw();

    /**
     * Sets the OpenGL clear color to the title screen backdrop color.
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
     * Returns true if the title screen is exiting (should transition to next screen).
     */
    boolean isExiting();

    /**
     * Returns true if the title screen is active (not inactive).
     */
    boolean isActive();

    /**
     * Returns true if this title screen supports rendering as a frozen background
     * behind the level select overlay (like Sonic 1, where the title logo and Sonic
     * sprite remain visible with a brown palette while the level select text overlays).
     */
    default boolean supportsLevelSelectOverlay() {
        return false;
    }

    /**
     * Renders the title screen foreground elements (logo, sprites) without
     * the scrolling background, for use as a frozen backdrop behind the level select.
     * <p>The caller is responsible for uploading the level select palette before
     * calling this, so the art appears with the appropriate tint.
     */
    default void drawFrozenForLevelSelect() {
        // Default: no-op (Sonic 2 etc. have a completely different level select)
    }

    default TitleScreenAction consumeExitAction() {
        return TitleScreenAction.OTHER;
    }

    default void setExitToLevelHandler(Runnable handler) {
        // Default: no-op.
    }
}
