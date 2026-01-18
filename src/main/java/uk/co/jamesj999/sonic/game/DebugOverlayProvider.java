package uk.co.jamesj999.sonic.game;

import java.util.List;

/**
 * Interface for game-specific debug overlay content.
 * Provides game-specific information for the debug renderer.
 *
 * <p>The debug overlay system has two layers:
 * <ul>
 *   <li>Game-agnostic infrastructure (DebugRenderer, DebugOverlayManager)</li>
 *   <li>Game-specific content (object labels, touch response data, etc.)</li>
 * </ul>
 *
 * <p>This interface allows each game to provide its own debug content
 * without modifying the core debug rendering system.
 */
public interface DebugOverlayProvider {
    /**
     * Gets object debug labels for display.
     * Each label contains position, text, and optional color.
     *
     * @return list of object labels to render
     */
    List<ObjectDebugLabel> getObjectLabels();

    /**
     * Gets debug art viewer targets available for this game.
     * Each target represents a type of object art that can be viewed.
     *
     * @return list of art viewer targets
     */
    List<ArtViewerTarget> getArtViewerTargets();

    /**
     * Gets touch response debug information.
     *
     * @return touch response state, or null if not available
     */
    TouchResponseDebugInfo getTouchResponseInfo();

    /**
     * Record for an object debug label.
     */
    record ObjectDebugLabel(
            int x,
            int y,
            String text,
            float r,
            float g,
            float b
    ) {
        public ObjectDebugLabel(int x, int y, String text) {
            this(x, y, text, 1.0f, 1.0f, 1.0f);
        }
    }

    /**
     * Record for an art viewer target.
     */
    record ArtViewerTarget(
            String name,
            String description,
            int patternBase,
            int patternCount
    ) {}

    /**
     * Interface for touch response debug information.
     */
    interface TouchResponseDebugInfo {
        int getPlayerX();
        int getPlayerY();
        int getPlayerHeight();
        int getPlayerYRadius();
        boolean isCrouching();
        List<String> getOverlappingObjects();
        List<String> getHitCategories();
    }
}
