package com.openggf.game.sonic2;

import com.openggf.game.AbstractZoneRegistry;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.level.LevelData;

import java.util.List;

/**
 * Zone registry for Sonic the Hedgehog 2.
 * Defines all 11 zones with their acts, names, and music.
 */
public class Sonic2ZoneRegistry extends AbstractZoneRegistry {

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
            Sonic2Music.EMERALD_HILL.id,    // Emerald Hill
            Sonic2Music.CHEMICAL_PLANT.id,  // Chemical Plant
            Sonic2Music.AQUATIC_RUIN.id,    // Aquatic Ruin
            Sonic2Music.CASINO_NIGHT.id,    // Casino Night
            Sonic2Music.HILL_TOP.id,        // Hill Top
            Sonic2Music.MYSTIC_CAVE.id,     // Mystic Cave
            Sonic2Music.OIL_OCEAN.id,       // Oil Ocean
            Sonic2Music.METROPOLIS.id,      // Metropolis
            Sonic2Music.SKY_CHASE.id,       // Sky Chase
            Sonic2Music.WING_FORTRESS.id,   // Wing Fortress
            Sonic2Music.DEATH_EGG.id        // Death Egg
    };

    public Sonic2ZoneRegistry() {
        // Zone structure: outer list = zones, inner list = acts
        super(List.of(
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
        ), ZONE_NAMES);
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
    public int getMusicId(int zoneIndex, int actIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_MUSIC.length) {
            return -1;
        }
        return ZONE_MUSIC[zoneIndex];
    }
}
