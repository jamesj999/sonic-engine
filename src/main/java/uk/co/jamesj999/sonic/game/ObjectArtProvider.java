package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.objects.ObjectSpriteSheet;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

import java.io.IOException;
import java.util.List;

/**
 * Provider interface for game-specific object art.
 * Abstracts the loading and access of object sprites, animations, and related data
 * to support multiple games (Sonic 1, Sonic 2, Sonic 3&K, etc.).
 * <p>
 * Implementations wrap game-specific art loaders and expose renderers/sheets via
 * string keys for flexible lookup.
 */
public interface ObjectArtProvider {

    /**
     * Loads object art for the specified zone.
     *
     * @param zoneIndex the zone index (-1 for default/non-zone-specific)
     * @throws IOException if loading fails
     */
    void loadArtForZone(int zoneIndex) throws IOException;

    /**
     * Gets a renderer by its key.
     *
     * @param key the renderer key (e.g., "monitor", "spring_vertical")
     * @return the renderer, or null if not found
     */
    PatternSpriteRenderer getRenderer(String key);

    /**
     * Gets a sprite sheet by its key.
     *
     * @param key the sheet key (e.g., "monitor", "spring_vertical")
     * @return the sprite sheet, or null if not found
     */
    ObjectSpriteSheet getSheet(String key);

    /**
     * Gets an animation set by its key.
     *
     * @param key the animation key (e.g., "monitor", "spring", "checkpoint")
     * @return the animation set, or null if not found
     */
    SpriteAnimationSet getAnimations(String key);

    /**
     * Gets zone-specific integer data.
     *
     * @param key       the data key (e.g., "animal_type_a", "animal_type_b")
     * @param zoneIndex the zone index
     * @return the data value, or -1 if not found
     */
    int getZoneData(String key, int zoneIndex);

    /**
     * Gets HUD digit patterns for score/time/ring display.
     *
     * @return the digit patterns array
     */
    Pattern[] getHudDigitPatterns();

    /**
     * Gets HUD text patterns for label display.
     *
     * @return the text patterns array
     */
    Pattern[] getHudTextPatterns();

    /**
     * Gets HUD lives icon patterns.
     *
     * @return the lives icon patterns array
     */
    Pattern[] getHudLivesPatterns();

    /**
     * Gets HUD lives number patterns.
     *
     * @return the lives number patterns array
     */
    Pattern[] getHudLivesNumbers();

    /**
     * Gets all available renderer keys.
     *
     * @return list of renderer keys
     */
    List<String> getRendererKeys();

    /**
     * Caches all patterns to GPU memory.
     *
     * @param graphicsManager the graphics manager
     * @param baseIndex       the base pattern index
     * @return the next available pattern index after caching
     */
    int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex);

    /**
     * Checks if the provider has loaded and is ready to render.
     *
     * @return true if ready
     */
    boolean isReady();
}
