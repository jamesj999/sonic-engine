package com.openggf.game.sonic1;

import com.openggf.game.AbstractZoneRegistry;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.level.LevelData;

import java.util.List;

/**
 * Zone registry for Sonic the Hedgehog 1.
 * Defines all main gameplay zones, Final Zone, and the ending sequence variants.
 */
public class Sonic1ZoneRegistry extends AbstractZoneRegistry {

    private static final String[] ZONE_NAMES = {
            "GREEN HILL",
            "MARBLE",
            "SPRING YARD",
            "LABYRINTH",
            "STAR LIGHT",
            "SCRAP BRAIN",
            "FINAL",
            "ENDING"
    };

    private static final int[] ZONE_MUSIC = {
            Sonic1Music.GHZ.id,  // Green Hill
            Sonic1Music.MZ.id,   // Marble
            Sonic1Music.SYZ.id,  // Spring Yard
            Sonic1Music.LZ.id,   // Labyrinth
            Sonic1Music.SLZ.id,  // Star Light
            Sonic1Music.SBZ.id,  // Scrap Brain
            Sonic1Music.FZ.id,   // Final Zone
            Sonic1Music.ENDING.id // Ending sequence
    };

    public Sonic1ZoneRegistry() {
        // Gameplay progression order: GHZ -> MZ -> SYZ -> LZ -> SLZ -> SBZ -> FZ -> ENDING
        super(List.of(
                List.of(LevelData.S1_GREEN_HILL_1, LevelData.S1_GREEN_HILL_2, LevelData.S1_GREEN_HILL_3),
                List.of(LevelData.S1_MARBLE_1, LevelData.S1_MARBLE_2, LevelData.S1_MARBLE_3),
                List.of(LevelData.S1_SPRING_YARD_1, LevelData.S1_SPRING_YARD_2, LevelData.S1_SPRING_YARD_3),
                List.of(LevelData.S1_LABYRINTH_1, LevelData.S1_LABYRINTH_2, LevelData.S1_LABYRINTH_3),
                List.of(LevelData.S1_STAR_LIGHT_1, LevelData.S1_STAR_LIGHT_2, LevelData.S1_STAR_LIGHT_3),
                List.of(LevelData.S1_SCRAP_BRAIN_1, LevelData.S1_SCRAP_BRAIN_2, LevelData.S1_SCRAP_BRAIN_3),
                List.of(LevelData.S1_FINAL_ZONE),
                List.of(LevelData.S1_ENDING_FLOWERS, LevelData.S1_ENDING_NO_EMERALDS)
        ), ZONE_NAMES);
    }

    @Override
    public int[] getStartPosition(int zoneIndex, int actIndex) {
        if (zoneIndex < 0 || zoneIndex >= zones.size()) {
            return new int[]{0x0080, 0x00A8};
        }
        List<LevelData> acts = zones.get(zoneIndex);
        if (actIndex < 0 || actIndex >= acts.size()) {
            return new int[]{0x0080, 0x00A8};
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
