package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.scroll.ZoneScrollHandler;

import java.io.IOException;

/**
 * Interface for providing zone-specific scroll handlers.
 * Each game module can provide its own scroll handlers for parallax effects.
 *
 * <p>Scroll handlers control horizontal and vertical scrolling for background
 * planes, implementing per-line parallax effects specific to each zone.
 */
public interface ScrollHandlerProvider {
    /**
     * Loads any required data for scroll handlers from the ROM.
     * Called once when the ROM is first loaded.
     *
     * @param rom the ROM to load data from
     * @throws IOException if loading fails
     */
    void load(Rom rom) throws IOException;

    /**
     * Gets a scroll handler for the specified zone.
     *
     * @param zoneIndex the zone index
     * @return the scroll handler, or null if the zone uses default scrolling
     */
    ZoneScrollHandler getHandler(int zoneIndex);

    /**
     * Returns the zone constants for this game.
     * Used by ParallaxManager to map zone indices to scroll behavior.
     *
     * @return the zone constants
     */
    ZoneConstants getZoneConstants();

    /**
     * Interface containing zone index constants.
     */
    interface ZoneConstants {
        int getZoneCount();
        String getZoneName(int index);
    }
}
