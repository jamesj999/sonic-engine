package com.openggf.game;

import com.openggf.graphics.GLCommand;

import java.util.List;
import java.util.Objects;

/**
 * Interface for results screens displayed after completing a stage.
 * Handles the tally animation, bonus display, and transition timing.
 *
 * <p>Used for both special stage results and level completion results.
 */
public interface ResultsScreen {
    /**
     * Decorates a results owner with work that must be retried before each of
     * its presentation updates, without duplicating mode-loop orchestration.
     */
    static ResultsScreen withBeforeUpdate(ResultsScreen delegate, Runnable beforeUpdate) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(beforeUpdate, "beforeUpdate");
        return new ResultsScreen() {
            @Override
            public void update(int frameCounter, Object context) {
                beforeUpdate.run();
                delegate.update(frameCounter, context);
            }

            @Override
            public boolean isComplete() {
                return delegate.isComplete();
            }

            @Override
            public void appendRenderCommands(List<GLCommand> commands) {
                delegate.appendRenderCommands(commands);
            }

            @Override
            public void setViewportWidth(int width) {
                delegate.setViewportWidth(width);
            }
        };
    }

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

    /**
     * Sets the projection-space viewport width for widescreen centering.
     *
     * <p>The results content is always 320 px wide. At widths greater than 320
     * the content is shifted right by {@code (viewportWidth - 320) / 2} so it
     * remains visually centered. At native width 320 the offset is 0 —
     * byte-identical output. Default implementation is a no-op (native behaviour).
     */
    default void setViewportWidth(int width) {
        // default: no-op (native 320 — offset 0)
    }
}
