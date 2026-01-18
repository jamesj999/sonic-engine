package uk.co.jamesj999.sonic.game;

/**
 * Interface for managing transient level state such as rings and time.
 * This state is typically reset when a level is loaded or restarted.
 */
public interface LevelState {
    /**
     * Update the level state (called once per frame).
     * Typically updates the timer.
     */
    void update();

    /**
     * Gets the current ring count.
     * @return current rings
     */
    int getRings();

    /**
     * Adds rings to the current count.
     * May trigger extra life at thresholds (100, 200 rings).
     *
     * @param amount amount to add (can be negative to subtract)
     */
    void addRings(int amount);

    /**
     * Sets the ring count directly.
     *
     * @param rings new ring count
     */
    void setRings(int rings);

    /**
     * Returns true if time is over (10 minutes elapsed).
     * @return true if time over
     */
    boolean isTimeOver();

    /**
     * Gets the display time string for the HUD.
     * @return formatted time string (e.g., "1:23")
     */
    String getDisplayTime();

    /**
     * Returns whether the timer should flash (typically when approaching time over).
     * @return true if timer should flash
     */
    boolean shouldFlashTimer();

    /**
     * Gets the flash cycle state for ring display.
     * Used when rings are 0 to flash the HUD.
     *
     * @return true if in flash-on state
     */
    boolean getFlashCycle();

    /**
     * Pauses the level timer.
     * Called when passing the end-of-stage signpost.
     */
    void pauseTimer();

    /**
     * Gets the total elapsed time in seconds.
     * @return elapsed seconds
     */
    int getElapsedSeconds();
}
