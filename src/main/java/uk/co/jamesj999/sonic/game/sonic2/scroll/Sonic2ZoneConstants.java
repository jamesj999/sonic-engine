package uk.co.jamesj999.sonic.game.sonic2.scroll;

import uk.co.jamesj999.sonic.game.ScrollHandlerProvider.ZoneConstants;

/**
 * Zone index constants for Sonic 2.
 * Contains both internal list indices (for LevelManager/ParallaxManager) and
 * ROM zone IDs (from s2.constants.asm zoneID macro).
 */
public class Sonic2ZoneConstants implements ZoneConstants {
    public static final Sonic2ZoneConstants INSTANCE = new Sonic2ZoneConstants();

    // Internal zone indices (matching LevelManager list order)
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

    // ROM zone IDs (from s2.constants.asm zoneID macro)
    // These are the actual values stored in the ROM and returned by level.getZoneIndex()
    public static final int ROM_ZONE_EHZ = 0x00;  // emerald_hill_zone
    public static final int ROM_ZONE_WZ  = 0x02;  // wood_zone (unused)
    public static final int ROM_ZONE_MTZ = 0x04;  // metropolis_zone
    public static final int ROM_ZONE_WFZ = 0x06;  // wing_fortress_zone
    public static final int ROM_ZONE_HTZ = 0x07;  // hill_top_zone
    public static final int ROM_ZONE_HPZ = 0x08;  // hidden_palace_zone (unused)
    public static final int ROM_ZONE_OOZ = 0x0A;  // oil_ocean_zone
    public static final int ROM_ZONE_MCZ = 0x0B;  // mystic_cave_zone
    public static final int ROM_ZONE_CNZ = 0x0C;  // casino_night_zone
    public static final int ROM_ZONE_CPZ = 0x0D;  // chemical_plant_zone
    public static final int ROM_ZONE_DEZ = 0x0E;  // death_egg_zone
    public static final int ROM_ZONE_ARZ = 0x0F;  // aquatic_ruin_zone
    public static final int ROM_ZONE_SCZ = 0x10;  // sky_chase_zone

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
