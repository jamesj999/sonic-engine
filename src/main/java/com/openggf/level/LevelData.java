package com.openggf.level;

public enum LevelData {
    EMERALD_HILL_1(0x00, 0x0060, 0x028F),
    EMERALD_HILL_2(0x01, 0x0060, 0x02AF),
    CHEMICAL_PLANT_1(0x02, 0x0060, 0x01EC),
    CHEMICAL_PLANT_2(0x03, 0x0060, 0x012C),
    AQUATIC_RUIN_1(0x04, 0x0060, 0x037E),
    AQUATIC_RUIN_2(0x05, 0x0060, 0x037E),
    CASINO_NIGHT_1(0x06, 0x0060, 0x02AC),
    CASINO_NIGHT_2(0x07, 0x0060, 0x058C),
    HILL_TOP_1(0x08, 0x0060, 0x03EF),
    HILL_TOP_2(0x09, 0x0060, 0x06AF),
    MYSTIC_CAVE_1(0x0A, 0x0060, 0x06AC),
    MYSTIC_CAVE_2(0x0B,0x0060, 0x05AC),
    OIL_OCEAN_1(0x0C, 0x0060, 0x06AC),
    OIL_OCEAN_2(0x0D, 0x0060, 0x056C),
    METROPOLIS_1(0x0E, 0x0060, 0x028C),
    METROPOLIS_2(0x0F, 0x0060, 0x05EC),
    METROPOLIS_3(0x10, 0x0060, 0x020C),
    SKY_CHASE(0x11, 0x0120, 0x0070),
    WING_FORTRESS(0x12, 0x0060, 0x04CC),
    DEATH_EGG(0x13,0x0060,0x012D),

    // Sonic 1 levels (levelIndex offset by 0x80 to avoid collision with Sonic 2)
    // Start positions from ROM StartLocArray at 0x0611E
    S1_GREEN_HILL_1(0x80, 0x0050, 0x03B0),
    S1_GREEN_HILL_2(0x81, 0x0050, 0x00FC),
    S1_GREEN_HILL_3(0x82, 0x0050, 0x03B0),
    S1_LABYRINTH_1(0x83, 0x0060, 0x006C),
    S1_LABYRINTH_2(0x84, 0x0050, 0x00EC),
    S1_LABYRINTH_3(0x85, 0x0050, 0x02EC),
    S1_MARBLE_1(0x86, 0x0030, 0x0266),
    S1_MARBLE_2(0x87, 0x0030, 0x0266),
    S1_MARBLE_3(0x88, 0x0030, 0x0166),
    S1_STAR_LIGHT_1(0x89, 0x0040, 0x02CC),
    S1_STAR_LIGHT_2(0x8A, 0x0040, 0x014C),
    S1_STAR_LIGHT_3(0x8B, 0x0040, 0x014C),
    S1_SPRING_YARD_1(0x8C, 0x0030, 0x03BD),
    S1_SPRING_YARD_2(0x8D, 0x0030, 0x01BD),
    S1_SPRING_YARD_3(0x8E, 0x0030, 0x00EC),
    S1_SCRAP_BRAIN_1(0x8F, 0x0030, 0x048C),
    S1_SCRAP_BRAIN_2(0x90, 0x0030, 0x074C),
    S1_SCRAP_BRAIN_3(0x91, 0x0B80, 0x0000),
    S1_FINAL_ZONE(0x92, 0x2140, 0x05AC),
    // Sonic 1 ending sequence variants (ROM id_EndZ act 0/1)
    // Act 0 = all emeralds (flowers), Act 1 = missing emeralds (no flowers)
    S1_ENDING_FLOWERS(0x95, 0x0620, 0x016B),
    S1_ENDING_NO_EMERALDS(0x96, 0x0EE0, 0x016C),

    // Sonic 3&K levels (levelIndex offset by 0xC0 to avoid collision with S1 and S2)
    // Start positions from ROM Sonic_Start_Locations table at $1E3C18
    S3K_ANGEL_ISLAND_1(0xC0, 0x13A0, 0x041A),
    S3K_ANGEL_ISLAND_2(0xC1, 0x18A0, 0x04DA),
    S3K_HYDROCITY_1(0xC2, 0x0280, 0x0020),
    S3K_HYDROCITY_2(0xC3, 0x0170, 0x082C),
    S3K_MARBLE_GARDEN_1(0xC4, 0x00C0, 0x0F00),
    S3K_MARBLE_GARDEN_2(0xC5, 0x0060, 0x08BE),
    S3K_CARNIVAL_NIGHT_1(0xC6, 0x0018, 0x0600),
    S3K_CARNIVAL_NIGHT_2(0xC7, 0x02C0, 0x06B0),
    S3K_FLYING_BATTERY_1(0xC8, 0x0060, 0x076C),
    S3K_FLYING_BATTERY_2(0xC9, 0x0060, 0x05EC),
    S3K_ICECAP_1(0xCA, 0x0010, 0x00F0),
    S3K_ICECAP_2(0xCB, 0x07E0, 0x0370),
    S3K_LAUNCH_BASE_1(0xCC, 0x00B0, 0x0650),
    S3K_LAUNCH_BASE_2(0xCD, 0x0630, 0x03EC),
    S3K_MUSHROOM_HILL_1(0xCE, 0x00D8, 0x0500),
    S3K_MUSHROOM_HILL_2(0xCF, 0x0150, 0x07AC),
    S3K_SANDOPOLIS_1(0xD0, 0x00C0, 0x0400),
    S3K_SANDOPOLIS_2(0xD1, 0x0140, 0x03AC),
    S3K_LAVA_REEF_1(0xD2, 0x0100, 0x0020),
    S3K_LAVA_REEF_2(0xD3, 0x09E0, 0x076C),
    S3K_SKY_SANCTUARY_1(0xD4, 0x0100, 0x0C00),
    S3K_SKY_SANCTUARY_2(0xD5, 0x0080, 0x0020),
    S3K_DEATH_EGG_1(0xD6, 0x0030, 0x09AC),
    S3K_DEATH_EGG_2(0xD7, 0x0140, 0x03AC),
    S3K_DOOMSDAY(0xD8, 0x0000, 0x0100),
    S3K_DOOMSDAY_2(0xD9, 0x0060, 0x058C),

    // S3K zone $0D: the AIZ intro scene (act 0) and the ending scene (act 1).
    // LevelSizes labels the pair "AIZ Intro (?)" / "Ending scene"
    // (skdisasm/sonic3k.asm:38106-38107); LevelMusic_Playlist labels the row
    // "AIZ INTRO & ENDING" (skdisasm/sonic3k.asm:7489); LevelPtrs points both
    // acts at Layout_SSZ2 (skdisasm/sonic3k.asm:200464-200465).
    S3K_AIZ_INTRO(0xDA, 0x0060, 0x01EC),
    S3K_ENDING_SCENE(0xDB, 0x0060, 0x012C),

    // S3K competition zones (ROM zone IDs 14-18, not playable in single-player)
    S3K_AZURE_LAKE(0xDC, 0x0430, 0x0194),
    S3K_AZURE_LAKE_2(0xDD, 0x0030, 0x008C),
    S3K_BALLOON_PARK(0xDE, 0x0440, 0x0264),
    S3K_BALLOON_PARK_2(0xDF, 0x0060, 0x012C),
    S3K_DESERT_PALACE(0xE0, 0x0758, 0x0144),
    S3K_DESERT_PALACE_2(0xE1, 0x0060, 0x0070),
    S3K_CHROME_GADGET(0xE2, 0x0454, 0x00B4),
    S3K_CHROME_GADGET_2(0xE3, 0x0060, 0x0070),
    S3K_ENDLESS_MINE(0xE4, 0x0430, 0x0194),
    S3K_ENDLESS_MINE_2(0xE5, 0x0060, 0x0070),

    // S3K bonus stages (zone IDs 19-21)
    // Start positions are loaded from ROM at runtime; values here are fallback defaults
    S3K_GUMBALL(0xE6, 0x0100, 0x0120),
    S3K_GUMBALL_2(0xE7, 0x0060, 0x0070),
    S3K_GLOWING_SPHERE(0xE8, 0x0120, 0x0100),
    S3K_GLOWING_SPHERE_2(0xE9, 0x0060, 0x0070),
    S3K_SLOT_MACHINE(0xEA, 0x0120, 0x0100),
    S3K_SLOT_MACHINE_2(0xEB, 0x0060, 0x0070),

    // S3K zone $16: the Lava Reef boss act (act 0, Current_zone_and_act $1600)
    // and Hidden Palace Zone (act 1, $1601 -- skdisasm/sonic3k.asm:62150);
    // zone $17: the Death Egg boss act (act 0, $1700) and the Super Emerald
    // special-stage arena (act 1, $1701 -- skdisasm/sonic3k.asm:4994-4996).
    // LevelSizes names all four (skdisasm/sonic3k.asm:38140-38143).
    S3K_LRZ_BOSS(0xEC, 0x0040, 0x0070),
    S3K_HIDDEN_PALACE(0xED, 0x0030, 0x0AEC),
    S3K_DEZ_BOSS(0xEE, 0x0060, 0x0070),
    S3K_SPECIAL_STAGE_ARENA(0xEF, 0x1640, 0x03AC);

    private final int levelIndex;
    private final int startXPos;
    private final int startYPos;

    LevelData(int levelIndex, int startXPos, int startYPos) {
        this.levelIndex = levelIndex;
        this.startXPos = startXPos;
        this.startYPos = startYPos;
    }

    public int getLevelIndex() {
        return levelIndex;
    }
    public int getStartXPos() { return startXPos; }
    public int getStartYPos() { return startYPos; }
}
