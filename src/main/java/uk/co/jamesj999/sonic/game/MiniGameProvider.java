package uk.co.jamesj999.sonic.game;

import java.io.IOException;

/**
 * Base interface for any non-level gameplay mode (special stages, bonus stages, etc.).
 * Defines the common lifecycle and interaction methods for mini-games.
 *
 * <p>Implementations handle their own initialization, update loops, and rendering.
 */
public interface MiniGameProvider {
    /**
     * Initializes the mini-game. Called before the first update.
     *
     * @throws IOException if initialization fails
     */
    void initialize() throws IOException;

    /**
     * Updates the mini-game logic by one frame.
     * Called at 60fps during gameplay.
     */
    void update();

    /**
     * Renders the mini-game to the screen.
     * Called after update() each frame.
     */
    void draw();

    /**
     * Handles player input.
     *
     * @param heldButtons  bitmask of currently held buttons
     * @param pressedButtons bitmask of buttons pressed this frame
     */
    void handleInput(int heldButtons, int pressedButtons);

    /**
     * Checks if the mini-game has finished (success or failure).
     *
     * @return true if the mini-game is complete
     */
    boolean isFinished();

    /**
     * Resets the mini-game to its initial state.
     * Called when exiting or restarting.
     */
    void reset();

    /**
     * Checks if the mini-game has been initialized and is ready to play.
     *
     * @return true if initialized
     */
    boolean isInitialized();
}
