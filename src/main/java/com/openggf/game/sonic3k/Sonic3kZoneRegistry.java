package com.openggf.game.sonic3k;

import com.openggf.game.AbstractZoneRegistry;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.level.LevelData;

import java.util.List;

/**
 * Zone registry for Sonic 3 &amp; Knuckles.
 * Defines all 24 zones the ROM's level tables address: AIZ (0) through the
 * Death Egg boss / Super Emerald special-stage arena pair (23), including the
 * intro/ending scenes (13), the competition zones (14-18) and the bonus
 * stages (19-21).
 *
 * <p>The outer list index matches the ROM zone ID and every zone carries two
 * act slots, exactly as the ROM's level tables do; see the constructor for
 * the citations.
 */
public class Sonic3kZoneRegistry extends AbstractZoneRegistry {

    // Zone display names -- indexed by ROM zone ID (0-23). The ROM's own
    // per-zone tables run 0-23 (LevelPtrs, LevelSizes and LevelMusic_Playlist
    // are each 48 entries indexed zone*2+act; see the constructor).
    private static final String[] ZONE_NAMES = {
            "ANGEL ISLAND",         // 0
            "HYDROCITY",            // 1
            "MARBLE GARDEN",        // 2
            "CARNIVAL NIGHT",       // 3
            "FLYING BATTERY",       // 4
            "ICECAP",               // 5
            "LAUNCH BASE",          // 6
            "MUSHROOM HILL",        // 7
            "SANDOPOLIS",           // 8
            "LAVA REEF",            // 9
            "SKY SANCTUARY",        // 10
            "DEATH EGG",            // 11
            "THE DOOMSDAY",         // 12
            "",                     // 13 AIZ intro / ending scene (no title card)
            "AZURE LAKE",           // 14 (competition)
            "BALLOON PARK",         // 15 (competition)
            "DESERT PALACE",        // 16 (competition)
            "CHROME GADGET",        // 17 (competition)
            "ENDLESS MINE",         // 18 (competition)
            "GUMBALL",              // 19 (bonus stage)
            "GLOWING SPHERES",      // 20 (bonus stage)
            "SLOT MACHINE",         // 21 (bonus stage)
            "LAVA REEF",            // 22 act 0 LRZ boss, act 1 Hidden Palace
            "DEATH EGG"             // 23 act 0 DEZ boss, act 1 special-stage arena
    };

    // Music IDs per zone/act - S3K has different music per act for most zones.
    // Competition zones and bonus stages use a single music ID per zone.
    // Bonus stage music is normally set by the coordinator, but is listed here
    // for completeness and fallback.
    // Music IDs per zone/act, transcribed from the ROM's LevelMusic_Playlist
    // (skdisasm/sonic3k.asm:7476-7500), a 48-byte table read at
    // sonic3k.asm:7676-7681 with the same zone*2+act index as LevelPtrs.
    private static final int[][] ZONE_MUSIC = {
            {Sonic3kMusic.AIZ1.id, Sonic3kMusic.AIZ2.id},   // 0  AIZ
            {Sonic3kMusic.HCZ1.id, Sonic3kMusic.HCZ2.id},   // 1  HCZ
            {Sonic3kMusic.MGZ1.id, Sonic3kMusic.MGZ2.id},   // 2  MGZ
            {Sonic3kMusic.CNZ1.id, Sonic3kMusic.CNZ2.id},   // 3  CNZ
            {Sonic3kMusic.FBZ1.id, Sonic3kMusic.FBZ2.id},   // 4  FBZ
            {Sonic3kMusic.ICZ1.id, Sonic3kMusic.ICZ2.id},   // 5  ICZ
            {Sonic3kMusic.LBZ1.id, Sonic3kMusic.LBZ2.id},   // 6  LBZ
            {Sonic3kMusic.MHZ1.id, Sonic3kMusic.MHZ2.id},   // 7  MHZ
            {Sonic3kMusic.SOZ1.id, Sonic3kMusic.SOZ2.id},   // 8  SOZ
            {Sonic3kMusic.LRZ1.id, Sonic3kMusic.LRZ2.id},   // 9  LRZ
            {Sonic3kMusic.SSZ.id, Sonic3kMusic.SSZ.id},     // 10 SSZ
            {Sonic3kMusic.DEZ1.id, Sonic3kMusic.DEZ2.id},   // 11 DEZ
            {Sonic3kMusic.DDZ.id, Sonic3kMusic.DDZ.id},     // 12 DDZ
            {Sonic3kMusic.SPECIAL_STAGE.id, Sonic3kMusic.SSZ.id}, // 13 AIZ intro / ending
            {Sonic3kMusic.AZURE_LAKE.id, Sonic3kMusic.AZURE_LAKE.id},       // 14 ALZ
            {Sonic3kMusic.BALLOON_PARK.id, Sonic3kMusic.BALLOON_PARK.id},   // 15 BPZ
            {Sonic3kMusic.DESERT_PALACE.id, Sonic3kMusic.DESERT_PALACE.id}, // 16 DPZ
            {Sonic3kMusic.CHROME_GADGET.id, Sonic3kMusic.CHROME_GADGET.id}, // 17 CGZ
            {Sonic3kMusic.ENDLESS_MINE.id, Sonic3kMusic.ENDLESS_MINE.id},   // 18 EMZ
            {Sonic3kMusic.GUMBALL.id, Sonic3kMusic.GUMBALL.id},             // 19 Gumball
            {Sonic3kMusic.PACHINKO.id, Sonic3kMusic.PACHINKO.id},           // 20 Pachinko
            {Sonic3kMusic.SLOTS.id, Sonic3kMusic.SLOTS.id},                 // 21 Slots
            {Sonic3kMusic.BOSS.id, Sonic3kMusic.LRZ2.id},   // 22 LRZ boss / Hidden Palace
            {Sonic3kMusic.DEZ2.id, Sonic3kMusic.LRZ2.id}    // 23 DEZ boss / special-stage arena
    };

    public Sonic3kZoneRegistry() {
        // Zone structure: outer list = zones (indexed by ROM zone ID), inner
        // list = acts. The shape is the ROM's own: LevelPtrs is 48 longwords
        // (skdisasm/sonic3k.asm:200438-200485) and Load_Level indexes it as
        // zone*2 + act (sonic3k.asm:38746-38753), so every zone 0-23 has two
        // act slots. LevelSizes (sonic3k.asm:38096-38143) and
        // LevelMusic_Playlist (sonic3k.asm:7476-7500) are the same 48 entries
        // with the same index, and name each slot.
        super(List.of(
                List.of(LevelData.S3K_ANGEL_ISLAND_1, LevelData.S3K_ANGEL_ISLAND_2),   // 0  AIZ
                List.of(LevelData.S3K_HYDROCITY_1, LevelData.S3K_HYDROCITY_2),         // 1  HCZ
                List.of(LevelData.S3K_MARBLE_GARDEN_1, LevelData.S3K_MARBLE_GARDEN_2), // 2  MGZ
                List.of(LevelData.S3K_CARNIVAL_NIGHT_1, LevelData.S3K_CARNIVAL_NIGHT_2),// 3  CNZ
                List.of(LevelData.S3K_FLYING_BATTERY_1, LevelData.S3K_FLYING_BATTERY_2),// 4  FBZ
                List.of(LevelData.S3K_ICECAP_1, LevelData.S3K_ICECAP_2),               // 5  ICZ
                List.of(LevelData.S3K_LAUNCH_BASE_1, LevelData.S3K_LAUNCH_BASE_2),     // 6  LBZ
                List.of(LevelData.S3K_MUSHROOM_HILL_1, LevelData.S3K_MUSHROOM_HILL_2), // 7  MHZ
                List.of(LevelData.S3K_SANDOPOLIS_1, LevelData.S3K_SANDOPOLIS_2),       // 8  SOZ
                List.of(LevelData.S3K_LAVA_REEF_1, LevelData.S3K_LAVA_REEF_2),         // 9  LRZ
                List.of(LevelData.S3K_SKY_SANCTUARY_1, LevelData.S3K_SKY_SANCTUARY_2), // 10 SSZ
                List.of(LevelData.S3K_DEATH_EGG_1, LevelData.S3K_DEATH_EGG_2),         // 11 DEZ
                List.of(LevelData.S3K_DOOMSDAY, LevelData.S3K_DOOMSDAY_2),             // 12 DDZ
                List.of(LevelData.S3K_AIZ_INTRO, LevelData.S3K_ENDING_SCENE),          // 13 intro/ending
                List.of(LevelData.S3K_AZURE_LAKE, LevelData.S3K_AZURE_LAKE_2),         // 14 ALZ
                List.of(LevelData.S3K_BALLOON_PARK, LevelData.S3K_BALLOON_PARK_2),     // 15 BPZ
                List.of(LevelData.S3K_DESERT_PALACE, LevelData.S3K_DESERT_PALACE_2),   // 16 DPZ
                List.of(LevelData.S3K_CHROME_GADGET, LevelData.S3K_CHROME_GADGET_2),   // 17 CGZ
                List.of(LevelData.S3K_ENDLESS_MINE, LevelData.S3K_ENDLESS_MINE_2),     // 18 EMZ
                List.of(LevelData.S3K_GUMBALL, LevelData.S3K_GUMBALL_2),               // 19 Gumball
                List.of(LevelData.S3K_GLOWING_SPHERE, LevelData.S3K_GLOWING_SPHERE_2), // 20 Glowing Spheres
                List.of(LevelData.S3K_SLOT_MACHINE, LevelData.S3K_SLOT_MACHINE_2),     // 21 Slot Machine
                List.of(LevelData.S3K_LRZ_BOSS, LevelData.S3K_HIDDEN_PALACE),          // 22 LRZ boss / HPZ
                List.of(LevelData.S3K_DEZ_BOSS, LevelData.S3K_SPECIAL_STAGE_ARENA)     // 23 DEZ boss / SS arena
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
        int[] acts = ZONE_MUSIC[zoneIndex];
        if (actIndex < 0 || actIndex >= acts.length) {
            // Fall back to first act's music
            return acts[0];
        }
        return acts[actIndex];
    }
}
