package uk.co.jamesj999.sonic.game;

/**
 * Interface for game-specific debug modes.
 * Each game can provide its own debug modes for special stages, level testing, etc.
 *
 * <p>Debug modes are additional gameplay states that allow developers to:
 * <ul>
 *   <li>Visualize internal state (alignment, timing, etc.)</li>
 *   <li>Step through animation frames</li>
 *   <li>View graphics sets and sprite mappings</li>
 *   <li>Adjust runtime parameters for testing</li>
 * </ul>
 */
public interface DebugModeProvider {
    /**
     * Checks if this game has special stage debug modes.
     *
     * @return true if special stage debug is available
     */
    boolean hasSpecialStageDebug();

    /**
     * Gets the special stage debug controller.
     *
     * @return the debug controller, or null if not available
     */
    SpecialStageDebugController getSpecialStageDebugController();

    /**
     * Checks if this game has level debug modes.
     *
     * @return true if level debug is available
     */
    boolean hasLevelDebug();

    /**
     * Interface for special stage-specific debug controls.
     */
    interface SpecialStageDebugController {
        /**
         * Toggles alignment test mode (shows frame timing overlay).
         */
        void toggleAlignmentTestMode();

        /**
         * Checks if alignment test mode is active.
         */
        boolean isAlignmentTestMode();

        /**
         * Toggles sprite debug mode (shows raw graphics).
         */
        void toggleSpriteDebugMode();

        /**
         * Checks if sprite debug mode is active.
         */
        boolean isSpriteDebugMode();

        /**
         * Cycles through plane visibility modes.
         */
        void cyclePlaneDebugMode();

        /**
         * Adjusts the alignment offset for timing testing.
         *
         * @param delta offset adjustment (-1 or +1 typically)
         */
        void adjustAlignmentOffset(int delta);

        /**
         * Adjusts the animation speed multiplier.
         *
         * @param delta speed adjustment
         */
        void adjustAlignmentSpeed(double delta);

        /**
         * Toggles frame-by-frame stepping mode.
         */
        void toggleAlignmentStepMode();

        /**
         * Renders the alignment test overlay.
         *
         * @param viewportWidth current viewport width
         * @param viewportHeight current viewport height
         */
        void renderAlignmentOverlay(int viewportWidth, int viewportHeight);

        /**
         * Renders the lag compensation overlay.
         *
         * @param viewportWidth current viewport width
         * @param viewportHeight current viewport height
         */
        void renderLagCompensationOverlay(int viewportWidth, int viewportHeight);

        /**
         * Renders the sprite debug view (replaces normal rendering).
         */
        void renderSpriteDebug();

        /**
         * Handles input for sprite debug navigation.
         *
         * @param leftPressed left key pressed this frame
         * @param rightPressed right key pressed this frame
         * @param upPressed up key pressed this frame
         * @param downPressed down key pressed this frame
         */
        void handleSpriteDebugInput(boolean leftPressed, boolean rightPressed,
                                    boolean upPressed, boolean downPressed);
    }
}
