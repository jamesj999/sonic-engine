package uk.co.jamesj999.sonic.game;

/**
 * Interface for special stage debug viewing functionality.
 * Provides paginated display of sprite graphics, animations, and other debug visuals.
 *
 * <p>Implementations handle their own rendering and navigation controls.
 */
public interface SpecialStageDebugProvider {
    /**
     * Renders the debug view to the screen.
     * Should be called instead of normal special stage rendering when debug mode is active.
     */
    void draw();

    /**
     * Advances to the next page within the current graphics set.
     */
    void nextPage();

    /**
     * Returns to the previous page within the current graphics set.
     */
    void previousPage();

    /**
     * Advances to the next graphics set (e.g., sprites → HUD → banners).
     */
    void nextSet();

    /**
     * Returns to the previous graphics set.
     */
    void previousSet();

    /**
     * Checks if the debug viewer is currently enabled.
     *
     * @return true if debug mode is active
     */
    boolean isEnabled();

    /**
     * Toggles the debug viewer on/off.
     */
    void toggle();
}
