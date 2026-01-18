package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.level.LevelData;

import java.util.List;

/**
 * Interface for game-specific zone/level metadata.
 * Each game module provides its own implementation with zone names,
 * act counts, start positions, and other level-specific data.
 *
 * <p>The zone registry is queried by LevelManager to determine
 * what levels are available and how to load them.
 */
public interface ZoneRegistry {
    /**
     * Returns the total number of zones in this game.
     *
     * @return the zone count
     */
    int getZoneCount();

    /**
     * Returns the number of acts in a given zone.
     *
     * @param zoneIndex the zone index (0-based)
     * @return the act count for the zone
     */
    int getActCount(int zoneIndex);

    /**
     * Returns the display name for a zone.
     *
     * @param zoneIndex the zone index (0-based)
     * @return the zone name (e.g., "EMERALD HILL")
     */
    String getZoneName(int zoneIndex);

    /**
     * Returns the start position for a level.
     *
     * @param zoneIndex the zone index (0-based)
     * @param actIndex the act index (0-based)
     * @return array of [x, y] start coordinates
     */
    int[] getStartPosition(int zoneIndex, int actIndex);

    /**
     * Returns the LevelData entries for all acts in a zone.
     *
     * @param zoneIndex the zone index (0-based)
     * @return list of LevelData for each act
     */
    List<LevelData> getLevelDataForZone(int zoneIndex);

    /**
     * Returns all zones as a list of lists of LevelData.
     * Outer list is zones, inner list is acts.
     *
     * @return the complete zone/act structure
     */
    List<List<LevelData>> getAllZones();

    /**
     * Returns the music ID for a given level.
     *
     * @param zoneIndex the zone index (0-based)
     * @param actIndex the act index (0-based)
     * @return the music ID, or -1 if no music is defined
     */
    int getMusicId(int zoneIndex, int actIndex);
}
