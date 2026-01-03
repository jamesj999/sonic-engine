package uk.co.jamesj999.sonic.data.games;

public class Sonic2Constants {
    public static final int DEFAULT_ROM_SIZE = 0x100000;  // 1MB
    public static final int DEFAULT_LEVEL_LAYOUT_DIR_ADDR = 0x045A80;
    public static final int LEVEL_LAYOUT_DIR_ADDR_LOC = 0xE46E;
    public static final int LEVEL_LAYOUT_DIR_SIZE = 68;
    public static final int LEVEL_SELECT_ADDR = 0x9454;
    public static final int LEVEL_DATA_DIR = 0x42594;
    public static final int LEVEL_DATA_DIR_ENTRY_SIZE = 12;
    public static final int LEVEL_PALETTE_DIR = 0x2782;
    public static final int SONIC_TAILS_PALETTE_ADDR = 0x29E2;
    public static final int COLLISION_LAYOUT_DIR_ADDR = 0x49E8;
    public static final int ALT_COLLISION_LAYOUT_DIR_ADDR = 0x4A2C;
    public static final int OBJECT_LAYOUT_DIR_ADDR = 0x44D34;
    public static final int SOLID_TILE_VERTICAL_MAP_ADDR = 0x42E50;
    public static final int SOLID_TILE_HORIZONTAL_MAP_ADDR = 0x43E50;
    public static final int SOLID_TILE_MAP_SIZE = 0x1000;
    public static final int SOLID_TILE_ANGLE_ADDR = 0x42D50;
    public static final int SOLID_TILE_ANGLE_SIZE = 0x100;
    public static final int LEVEL_BOUNDARIES_ADDR = 0xC054;
    public static final int MUSIC_PLAYLIST_ADDR = 0x3EA0;

    // Player sprite art (Rev01, no header)
    public static final int ART_UNC_SONIC_ADDR = 0x50000;
    public static final int ART_UNC_SONIC_SIZE = 0x14320;
    public static final int ART_UNC_TAILS_ADDR = 0x64320;
    public static final int ART_UNC_TAILS_SIZE = 0x0B8C0;
    public static final int MAP_UNC_SONIC_ADDR = 0x6FBE0;
    public static final int MAP_R_UNC_SONIC_ADDR = 0x714E0;
    public static final int MAP_UNC_TAILS_ADDR = 0x739E2;
    public static final int MAP_R_UNC_TAILS_ADDR = 0x7446C;
    public static final int ART_TILE_SONIC = 0x0780;
    public static final int ART_TILE_TAILS = 0x07A0;
    public static final int ART_UNC_SPLASH_DUST_ADDR = 0x71FFC;
    public static final int ART_UNC_SPLASH_DUST_SIZE = 0x1940;
    public static final int MAP_UNC_OBJ08_ADDR = 0x1DF5E;
    public static final int MAP_R_UNC_OBJ08_ADDR = 0x1E074;
    public static final int ART_TILE_SONIC_DUST = 0x049C;
    public static final int ART_TILE_TAILS_DUST = 0x048C;
    // Object art (Nemesis) + mappings
    public static final int ART_NEM_MONITOR_ADDR = 0x79550;
    public static final int ART_NEM_SPIKES_ADDR = 0x7995C;
    public static final int ART_NEM_SPIKES_SIDE_ADDR = 0x7AC9A;
    public static final int ART_NEM_SPRING_VERTICAL_ADDR = 0x78E84;
    public static final int ART_NEM_SPRING_HORIZONTAL_ADDR = 0x78FA0;
    public static final int ART_NEM_SPRING_DIAGONAL_ADDR = 0x7906A;
    public static final int MAP_UNC_MONITOR_ADDR = 0x12D36;
    public static final int MAP_UNC_SPIKES_ADDR = 0x15B68;
    public static final int MAP_UNC_SPRING_ADDR = 0x1901C;
    public static final int MAP_UNC_SPRING_RED_ADDR = 0x19032;
    public static final int ANI_OBJ26_ADDR = 0x12CCE;
    public static final int ANI_OBJ26_SCRIPT_COUNT = 0x0B;
    public static final int ANI_OBJ41_ADDR = 0x18FE2;
    public static final int ANI_OBJ41_SCRIPT_COUNT = 0x06;
    public static final int SONIC_ANIM_DATA_ADDR = 0x01B618;
    public static final int SONIC_ANIM_SCRIPT_COUNT = 0x22;

    // Audio Constants
    public static final int SFX_JUMP = 0xA0;
    public static final int SFX_RING_LEFT = 0xCE;
    public static final int SFX_RING_RIGHT = 0xB5;
    public static final int SFX_RING_SPILL = 0xC6;
    public static final int SFX_SPINDASH_CHARGE = 0xE0;
    public static final int SFX_SPINDASH_RELEASE = 0xBC;
    public static final int SFX_SKID = 0xA4;
    public static final int SFX_HURT = 0xA3;
    public static final int SFX_DEATH = SFX_HURT;
    public static final int SFX_BADNIK_HIT = 0xC1;
    public static final int SFX_CHECKPOINT = 0xA1;
    public static final int SFX_SPIKE_HIT = 0xA6;
    public static final int SFX_DROWN = 0xB2;
    public static final int SFX_SPRING = 0xCC;
    public static final int SFX_ROLLING = 0xBE;

    public static final int[][] START_POSITIONS = {
            {0x0060, 0x028F}, // 0 Emerald Hill 1   (EHZ_1.bin)
            {0x0060, 0x02AF}, // 1 Emerald Hill 2   (EHZ_2.bin)
            {0x0000, 0x0000}, // 2 Unused           (e.g. HPZ / WZ / etc. – not wired in final game)
            {0x0000, 0x0000}, // 3 Unused
            {0x0060, 0x01EC}, // 4 Chemical Plant 1 (CPZ_1.bin)
            {0x0000, 0x0000}, // 5 Chemical Plant 2 (CPZ_2.bin – not fetched)
            {0x0000, 0x0000}, // 6 Aquatic Ruin 1   (ARZ_1.bin – not fetched)
            {0x0000, 0x0000}, // 7 Aquatic Ruin 2   (ARZ_2.bin – not fetched)
            {0x0000, 0x0000}, // 8 Casino Night 1   (CNZ_1.bin – not fetched)
            {0x0000, 0x0000}, // 9 Casino Night 2   (CNZ_2.bin – not fetched)
            {0x0060, 0x03EF}, // 10 Hill Top 1      (HTZ_1.bin)
            {0x0000, 0x0000}, // 11 Hill Top 2      (HTZ_2.bin – not fetched)
            {0x0060, 0x06AC}, // 12 Mystic Cave 1   (MCZ_1.bin)
            {0x0000, 0x0000}, // 13 Mystic Cave 2   (MCZ_2.bin – not fetched)
            {0x0060, 0x06AC}, // 14 Oil Ocean 1     (OOZ_1.bin)
            {0x0000, 0x0000}, // 15 Oil Ocean 2     (OOZ_2.bin – not fetched)
            {0x0060, 0x028C}, // 16 Metropolis 1    (MTZ_1.bin)
            {0x0000, 0x0000}, // 17 Metropolis 2    (MTZ_2.bin – not fetched)
            {0x0000, 0x0000}, // 18 Metropolis 3    (MTZ_3.bin – not fetched)
            {0x0000, 0x0000}, // 19 Unused
            {0x0120, 0x0070}, // 20 Sky Chase 1     (SCZ.bin)
            {0x0000, 0x0000}, // 21 Unused
            {0x0060, 0x04CC}, // 22 Wing Fortress 1 (WFZ_1.bin)
            {0x0000, 0x0000}, // 23 Unused
            {0x0060, 0x012D}, // 24 Death Egg 1     (DEZ_1.bin)
            {0x0000, 0x0000}, // 25 Unused
            {0x0000, 0x0000}, // 26 Special Stage
    };
}
