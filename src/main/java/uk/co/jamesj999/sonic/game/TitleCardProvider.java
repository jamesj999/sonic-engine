package uk.co.jamesj999.sonic.game;

/**
 * Interface for title card display management.
 * Title cards appear when a level first loads, after player respawns,
 * and when returning from special stages.
 */
public interface TitleCardProvider {
    /**
     * Initializes the title card for a zone/act.
     *
     * @param zoneIndex Zone index (0-10)
     * @param actIndex  Act index (0-2)
     */
    void initialize(int zoneIndex, int actIndex);

    /**
     * Updates the title card animation.
     * Call this once per frame while in TITLE_CARD mode.
     */
    void update();

    /**
     * Returns true if player control should be released.
     * Control is released at the start of TEXT_WAIT phase,
     * allowing the player to move while text is still visible.
     *
     * @return true if control should be released
     */
    boolean shouldReleaseControl();

    /**
     * Returns true if the title card overlay should still be drawn.
     * The overlay remains visible during TEXT_WAIT and TEXT_EXIT phases,
     * even though player control has been released.
     *
     * @return true if overlay is active
     */
    boolean isOverlayActive();

    /**
     * Returns true if the title card animation is fully complete.
     *
     * @return true if complete
     */
    boolean isComplete();

    /**
     * Renders the title card.
     * Call this from Engine.draw() when in TITLE_CARD mode.
     */
    void draw();

    /**
     * Resets the manager state.
     */
    void reset();

    /**
     * Gets the current zone index.
     * @return zone index
     */
    int getCurrentZone();

    /**
     * Gets the current act index.
     * @return act index
     */
    int getCurrentAct();
}
