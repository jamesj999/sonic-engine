package uk.co.jamesj999.sonic.game.sonic2.scroll;

import uk.co.jamesj999.sonic.game.ScrollHandlerProvider.ZoneConstants;

/**
 * Zone index constants for Sonic 2.
 * These match the zone indices used by LevelManager and ParallaxManager.
 */
public class Sonic2ZoneConstants implements ZoneConstants {
    public static final Sonic2ZoneConstants INSTANCE = new Sonic2ZoneConstants();

    // Zone indices (matching LevelManager list order)
    public static final int ZONE_EHZ = 0;  // Emerald Hill
    public static final int ZONE_CPZ = 1;  // Chemical Plant
    public static final int ZONE_ARZ = 2;  // Aquatic Ruin
    public static final int ZONE_CNZ = 3;  // Casino Night
    public static final int ZONE_HTZ = 4;  // Hill Top
    public static final int ZONE_MCZ = 5;  // Mystic Cave
    public static final int ZONE_OOZ = 6;  // Oil Ocean
    public static final int ZONE_MTZ = 7;  // Metropolis
    public static final int ZONE_SCZ = 8;  // Sky Chase
    public static final int ZONE_WFZ = 9;  // Wing Fortress
    public static final int ZONE_DEZ = 10; // Death Egg

    public static final int ZONE_COUNT = 11;

    private static final String[] ZONE_NAMES = {
            "Emerald Hill",
            "Chemical Plant",
            "Aquatic Ruin",
            "Casino Night",
            "Hill Top",
            "Mystic Cave",
            "Oil Ocean",
            "Metropolis",
            "Sky Chase",
            "Wing Fortress",
            "Death Egg"
    };

    private Sonic2ZoneConstants() {
    }

    @Override
    public int getZoneCount() {
        return ZONE_COUNT;
    }

    @Override
    public String getZoneName(int index) {
        if (index >= 0 && index < ZONE_NAMES.length) {
            return ZONE_NAMES[index];
        }
        return "Unknown";
    }
}
