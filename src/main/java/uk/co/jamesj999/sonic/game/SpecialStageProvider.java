package uk.co.jamesj999.sonic.game;

import java.io.IOException;

/**
 * Interface for special stage implementations that award Chaos Emeralds.
 * Extends {@link MiniGameProvider} with emerald-specific functionality.
 *
 * <p>
 * Access method varies by game:
 * <ul>
 * <li>Sonic 1: Giant rings (hidden in levels)</li>
 * <li>Sonic 2: Star posts with 50+ rings</li>
 * <li>Sonic 3&K: Giant rings (Blue Sphere stages)</li>
 * </ul>
 */
public interface SpecialStageProvider extends MiniGameProvider {
    /**
     * Checks if this game has special stages.
     *
     * @return true if special stages are available
     */
    boolean hasSpecialStages();

    /**
     * Gets the access method for special stages in this game.
     *
     * @return the access type (GIANT_RING or STARPOST)
     */
    SpecialStageAccessType getAccessType();

    /**
     * Initializes a specific special stage.
     *
     * @param stageIndex the stage index (0-6 for Sonic 2)
     * @throws IOException if initialization fails
     */
    void initializeStage(int stageIndex) throws IOException;

    /**
     * Gets the current stage index.
     *
     * @return the stage index (0-based)
     */
    int getCurrentStage();

    /**
     * Checks if an emerald was collected in the current stage.
     * Only valid after the stage is finished.
     *
     * @return true if the emerald was collected
     */
    boolean isEmeraldCollected();

    /**
     * Gets the index of the emerald collected (if any).
     * Same as getCurrentStage() in most implementations.
     *
     * @return the emerald index, or -1 if not collected
     */
    int getEmeraldIndex();

    /**
     * Gets the number of rings collected in the current stage.
     *
     * @return the ring count
     */
    int getRingsCollected();

    /**
     * Sets the emerald collected flag (for debug purposes).
     *
     * @param collected true to mark emerald as collected
     */
    void setEmeraldCollected(boolean collected);

    // ==================== Debug Methods ====================

    /**
     * Checks if sprite debug mode is active.
     *
     * @return true if sprite debug viewer is enabled
     */
    boolean isSpriteDebugMode();

    /**
     * Toggles sprite debug mode on/off.
     */
    void toggleSpriteDebugMode();

    /**
     * Cycles through plane visibility debug modes (A/B/both/off).
     */
    void cyclePlaneDebugMode();

    /**
     * Gets the debug provider for sprite viewing.
     *
     * @return the debug provider, or null if not available
     */
    SpecialStageDebugProvider getDebugProvider();

    // ==================== Alignment Test Methods ====================

    /**
     * Checks if alignment test mode is active.
     *
     * @return true if alignment testing is enabled
     */
    boolean isAlignmentTestMode();

    /**
     * Toggles alignment test mode on/off.
     */
    void toggleAlignmentTestMode();

    /**
     * Adjusts the alignment offset for testing.
     *
     * @param delta amount to adjust the offset
     */
    void adjustAlignmentOffset(int delta);

    /**
     * Adjusts the alignment speed for testing.
     *
     * @param delta amount to adjust the speed
     */
    void adjustAlignmentSpeed(double delta);

    /**
     * Toggles alignment step mode for frame-by-frame testing.
     */
    void toggleAlignmentStepMode();

    /**
     * Renders the alignment test overlay.
     *
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     */
    void renderAlignmentOverlay(int viewportWidth, int viewportHeight);

    // ==================== Lag Compensation Methods ====================

    /**
     * Renders the lag compensation debug overlay.
     *
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     */
    void renderLagCompensationOverlay(int viewportWidth, int viewportHeight);

    /**
     * Gets the current lag compensation factor.
     *
     * @return the lag compensation factor
     */
    double getLagCompensation();

    /**
     * Sets the lag compensation factor.
     *
     * @param factor the new lag compensation factor
     */
    void setLagCompensation(double factor);

    // ==================== Results Screen ====================

    /**
     * Creates a results screen for this special stage type.
     *
     * @param ringsCollected    number of rings collected
     * @param gotEmerald        true if an emerald was collected
     * @param stageIndex        the stage index (0-based)
     * @param totalEmeraldCount total emeralds collected so far
     * @return a results screen instance
     */
    ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
            int stageIndex, int totalEmeraldCount);
}
