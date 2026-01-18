package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.ZoneRegistry;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.level.LevelData;

import java.util.List;

/**
 * Zone registry for Sonic the Hedgehog 2.
 * Defines all 11 zones with their acts, names, and music.
 */
public class Sonic2ZoneRegistry implements ZoneRegistry {
    private static final Sonic2ZoneRegistry INSTANCE = new Sonic2ZoneRegistry();

    // Zone structure: outer list = zones, inner list = acts
    private final List<List<LevelData>> zones = List.of(
            List.of(LevelData.EMERALD_HILL_1, LevelData.EMERALD_HILL_2),
            List.of(LevelData.CHEMICAL_PLANT_1, LevelData.CHEMICAL_PLANT_2),
            List.of(LevelData.AQUATIC_RUIN_1, LevelData.AQUATIC_RUIN_2),
            List.of(LevelData.CASINO_NIGHT_1, LevelData.CASINO_NIGHT_2),
            List.of(LevelData.HILL_TOP_1, LevelData.HILL_TOP_2),
            List.of(LevelData.MYSTIC_CAVE_1, LevelData.MYSTIC_CAVE_2),
            List.of(LevelData.OIL_OCEAN_1, LevelData.OIL_OCEAN_2),
            List.of(LevelData.METROPOLIS_1, LevelData.METROPOLIS_2, LevelData.METROPOLIS_3),
            List.of(LevelData.SKY_CHASE),
            List.of(LevelData.WING_FORTRESS),
            List.of(LevelData.DEATH_EGG)
    );

    // Zone names for title cards
    private static final String[] ZONE_NAMES = {
            "EMERALD HILL",
            "CHEMICAL PLANT",
            "AQUATIC RUIN",
            "CASINO NIGHT",
            "HILL TOP",
            "MYSTIC CAVE",
            "OIL OCEAN",
            "METROPOLIS",
            "SKY CHASE",
            "WING FORTRESS",
            "DEATH EGG"
    };

    // Music IDs per zone (all acts in a zone typically share the same music)
    private static final int[] ZONE_MUSIC = {
            Sonic2AudioConstants.MUS_EMERALD_HILL,    // Emerald Hill
            Sonic2AudioConstants.MUS_CHEMICAL_PLANT,  // Chemical Plant
            Sonic2AudioConstants.MUS_AQUATIC_RUIN,    // Aquatic Ruin
            Sonic2AudioConstants.MUS_CASINO_NIGHT,    // Casino Night
            Sonic2AudioConstants.MUS_HILL_TOP,        // Hill Top
            Sonic2AudioConstants.MUS_MYSTIC_CAVE,     // Mystic Cave
            Sonic2AudioConstants.MUS_OIL_OCEAN,       // Oil Ocean
            Sonic2AudioConstants.MUS_METROPOLIS,      // Metropolis
            Sonic2AudioConstants.MUS_SKY_CHASE,       // Sky Chase
            Sonic2AudioConstants.MUS_WING_FORTRESS,   // Wing Fortress
            Sonic2AudioConstants.MUS_DEATH_EGG        // Death Egg
    };

    private Sonic2ZoneRegistry() {
    }

    public static Sonic2ZoneRegistry getInstance() {
        return INSTANCE;
    }

    @Override
    public int getZoneCount() {
        return zones.size();
    }

    @Override
    public int getActCount(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= zones.size()) {
            return 0;
        }
        return zones.get(zoneIndex).size();
    }

    @Override
    public String getZoneName(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_NAMES.length) {
            return "UNKNOWN";
        }
        return ZONE_NAMES[zoneIndex];
    }

    @Override
    public int[] getStartPosition(int zoneIndex, int actIndex) {
        if (zoneIndex < 0 || zoneIndex >= zones.size()) {
            return new int[]{0x60, 0x280};
        }
        List<LevelData> acts = zones.get(zoneIndex);
        if (actIndex < 0 || actIndex >= acts.size()) {
            return new int[]{0x60, 0x280};
        }
        LevelData level = acts.get(actIndex);
        return new int[]{level.getStartXPos(), level.getStartYPos()};
    }

    @Override
    public List<LevelData> getLevelDataForZone(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= zones.size()) {
            return List.of();
        }
        return zones.get(zoneIndex);
    }

    @Override
    public List<List<LevelData>> getAllZones() {
        return zones;
    }

    @Override
    public int getMusicId(int zoneIndex, int actIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_MUSIC.length) {
            return -1;
        }
        return ZONE_MUSIC[zoneIndex];
    }
}
