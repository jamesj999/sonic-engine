package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;

/**
 * Interface for zone-specific features that require special initialization.
 *
 * <p>Examples of zone features:
 * <ul>
 *   <li>Casino Night Zone: Bumpers, flippers</li>
 *   <li>Aquatic Ruin Zone: Water level and underwater mechanics</li>
 *   <li>Oil Ocean Zone: Oil mechanics</li>
 *   <li>Labyrinth Zone (Sonic 1): Water level, currents</li>
 * </ul>
 *
 * <p>Zone features are separate from scroll handlers and object registries.
 * They provide collision systems and gameplay mechanics specific to certain zones.
 */
public interface ZoneFeatureProvider {
    /**
     * Called when entering a zone to initialize zone-specific features.
     *
     * @param rom the ROM for loading data
     * @param zoneIndex the zone being entered
     * @param actIndex the act within the zone
     * @param cameraX the camera X position
     * @throws IOException if initialization fails
     */
    void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) throws IOException;

    /**
     * Updates zone features each frame.
     *
     * @param player the player sprite (may be null)
     * @param cameraX the camera X position
     * @param zoneIndex the current zone
     */
    void update(AbstractPlayableSprite player, int cameraX, int zoneIndex);

    /**
     * Resets all zone feature managers.
     * Called when leaving a zone or reloading.
     */
    void reset();

    /**
     * Checks if this zone has special collision features (bumpers, etc.).
     *
     * @param zoneIndex the zone to check
     * @return true if the zone has collision features
     */
    boolean hasCollisionFeatures(int zoneIndex);

    /**
     * Checks if this zone has water mechanics.
     *
     * @param zoneIndex the zone to check
     * @return true if the zone has water
     */
    boolean hasWater(int zoneIndex);

    /**
     * Gets the water level for a zone (if applicable).
     *
     * @param zoneIndex the zone
     * @param actIndex the act
     * @return the water level Y position, or Integer.MAX_VALUE if no water
     */
    int getWaterLevel(int zoneIndex, int actIndex);

    /**
     * Renders zone-specific visual features (e.g., water surface sprites).
     * Called during the draw phase after level rendering.
     *
     * @param camera the camera for screen coordinates
     * @param frameCounter current frame number for animation
     */
    void render(Camera camera, int frameCounter);

    /**
     * Ensures zone feature patterns are cached in the graphics manager.
     * Called during level initialization.
     *
     * @param graphicsManager the graphics manager
     * @param baseIndex starting pattern index
     * @return next available pattern index after caching
     */
    int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex);
}
