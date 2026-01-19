package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.graphics.GLCommand;

import java.util.List;

/**
 * Interface for results screens displayed after completing a stage.
 * Handles the tally animation, bonus display, and transition timing.
 *
 * <p>Used for both special stage results and level completion results.
 */
public interface ResultsScreen {
    /**
     * Updates the results screen state by one frame.
     *
     * @param frameCounter the current frame number since results started
     * @param context optional game-specific context data (may be null)
     */
    void update(int frameCounter, Object context);

    /**
     * Checks if the results screen has completed all animations
     * and is ready to transition to the next screen.
     *
     * @return true if the results screen is complete
     */
    boolean isComplete();

    /**
     * Appends render commands for the results screen to the command list.
     * Used for integration with the graphics command batching system.
     *
     * @param commands the list to append commands to
     */
    void appendRenderCommands(List<GLCommand> commands);
}
