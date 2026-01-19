package uk.co.jamesj999.sonic.game.sonic2.constants;

public class Sonic2Constants {
    public static final int DEFAULT_ROM_SIZE = 0x100000; // 1MB
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
    public static final int ANIM_PAT_MAPS_ADDR = 0x40350;

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
    public static final int ART_NEM_SONIC_LIFE_ADDR = 0x79346;
    public static final int ART_NEM_TAILS_LIFE_ADDR = 0x7C20C;
    public static final int ART_NEM_EXPLOSION_ADDR = 0x7B592;
    public static final int ART_TILE_EXPLOSION = 0x05A4;
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

    public static final int ART_NEM_SHIELD_ADDR = 0x71D8E;
    // Bridge art (EHZ wooden bridge - 8 blocks)
    public static final int ART_NEM_BRIDGE_ADDR = 0xF052A;

    // Waterfall art (EHZ waterfall)
    public static final int ART_NEM_EHZ_WATERFALL_ADDR = 0xF02D6;
    public static final int ART_TILE_EHZ_WATERFALL = 0x39E;
    // Palette cycling (EHZ/ARZ water)
    public static final int CYCLING_PAL_EHZ_ARZ_WATER_ADDR = 0x001E7A;
    public static final int CYCLING_PAL_EHZ_ARZ_WATER_LEN = 0x20;

    public static final int ART_NEM_INVINCIBILITY_STARS_ADDR = 0x71F14;
    public static final int MAP_UNC_INVINCIBILITY_STARS_ADDR = 0x1DCBC;
    public static final int ART_TILE_INVINCIBILITY_STARS = 0x05C0;

    public static final int MAP_UNC_OBJ18_A_ADDR = 0x107F6;
    public static final int MAP_UNC_OBJ18_B_ADDR = 0x1084E;

    public static final int ZONE_AQUATIC_RUIN = 2;

    // Checkpoint/Starpost (Object $79)
    public static final int ART_NEM_CHECKPOINT_ADDR = 0x79A86; // Star pole.nem
    public static final int MAP_UNC_CHECKPOINT_ADDR = 0x1F424; // obj79_a.asm (pole+dongle)
    public static final int MAP_UNC_CHECKPOINT_STAR_ADDR = 0x1F4A0; // obj79_b.asm (special stage stars)
    public static final int ANI_OBJ79_ADDR = 0x1F0C2; // Ani_obj79
    public static final int ANI_OBJ79_SCRIPT_COUNT = 3;
    public static final int ART_TILE_CHECKPOINT = 0x047C;
    public static final int SndID_Explosion = 0xC1;

    // Signpost/Goal Plate (Object 0D)
    public static final int ART_NEM_SIGNPOST_ADDR = 0x79BDE; // Signpost.nem (78 blocks)
    public static final int ART_TILE_SIGNPOST = 0x0434;
    public static final int SndID_Signpost = 0xCF;
    public static final int MusID_StageClear = 0x97; // Stage clear / act complete jingle
    public static final int SndID_Blip = 0xCD; // Tally tick sound
    public static final int SndID_TallyEnd = 0xC5; // Tally complete sound

    // Results Screen Art (Obj3A)
    public static final int ART_UNC_HUD_NUMBERS_ADDR = 0x4134C; // Art_Hud (digits source)
    public static final int ART_UNC_HUD_NUMBERS_SIZE = 0x300; // 24 tiles (10 digits x 2 tiles + extras?)
    public static final int ART_UNC_LIVES_NUMBERS_ADDR = 0x4164C; // Small numbers for lives counter
    public static final int ART_UNC_LIVES_NUMBERS_SIZE = 320; // 10 tiles (0-9)
    // Debug font (italic hex digits 0-9, A-F with slashed zeros - leftover from
    // Sonic 1 level select)
    public static final int ART_UNC_DEBUG_FONT_ADDR = 0x45D74; // Art_Text (Debug font)
    public static final int ART_UNC_DEBUG_FONT_SIZE = 512; // 16 tiles (0-9, A-F) * 32 bytes each
    public static final int ART_NEM_HUD_ADDR = 0x7923E; // HUD.nem (SCORE/TIME/RING text)
    public static final int ART_NEM_TITLE_CARD_ADDR = 0x7D22C; // Title card.nem (E, N, O, Z letters)
    public static final int ART_NEM_TITLE_CARD2_ADDR = 0x7D58A; // Font using large broken letters.nem (other letters)
    public static final int ART_NEM_RESULTS_TEXT_ADDR = 0x7E86A; // End of level results text.nem
    public static final int ART_NEM_MINI_SONIC_ADDR = 0x7C0AA; // Sonic continue.nem (mini character)
    public static final int ART_NEM_PERFECT_ADDR = 0x7EEBE; // Perfect text.nem
    public static final int MAPPINGS_EOL_TITLE_CARDS_ADDR = 0x14CBC; // MapUnc_EOLTitleCards
    public static final int VRAM_BASE_NUMBERS = 0x520; // ArtTile_HUD_Bonus_Score
    public static final int VRAM_BASE_PERFECT = 0x540; // ArtTile_ArtNem_Perfect
    public static final int VRAM_BASE_TITLE_CARD = 0x580; // ArtTile_ArtNem_TitleCard
    public static final int VRAM_BASE_RESULTS_TEXT = 0x5B0; // ArtTile_ArtNem_ResultsText
    public static final int VRAM_BASE_MINI_CHARACTER = 0x5F4; // ArtTile_ArtNem_MiniCharacter
    public static final int VRAM_BASE_HUD_TEXT = 0x6CA; // ArtTile_ArtNem_HUD
    public static final int RESULTS_BONUS_DIGIT_TILES = 32; // 4 counters * 4 digits * 2 tiles
    public static final int RESULTS_BONUS_DIGIT_GROUP_TILES = 8; // 4 digits * 2 tiles

    public static final int ART_NEM_NUMBERS_ADDR = 0x799AC; // Numbers.nem (points)

    // EHZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_BUZZER_ADDR = 0x8316A; // 28 blocks
    public static final int ART_NEM_MASHER_ADDR = 0x839EA; // 22 blocks
    public static final int ART_NEM_COCONUTS_ADDR = 0x8A87A; // 38 blocks
    public static final int ART_NEM_ANIMAL_ADDR = 0x7FDD2; // Rabbit (Pocky) fallback

    // Animal art (Nemesis compressed, verified offsets)
    public static final int ART_NEM_FLICKY_ADDR = 0x7EF60;
    public static final int ART_NEM_SQUIRREL_ADDR = 0x7F0A2;
    public static final int ART_NEM_MOUSE_ADDR = 0x7F206;
    public static final int ART_NEM_CHICKEN_ADDR = 0x7F340;
    public static final int ART_NEM_MONKEY_ADDR = 0x7F4A2;
    public static final int ART_NEM_EAGLE_ADDR = 0x7F5E2;
    public static final int ART_NEM_PIG_ADDR = 0x7F710;
    public static final int ART_NEM_SEAL_ADDR = 0x7F846;
    public static final int ART_NEM_PENGUIN_ADDR = 0x7F962;
    public static final int ART_NEM_TURTLE_ADDR = 0x7FADE;
    public static final int ART_NEM_BEAR_ADDR = 0x7FC90;
    public static final int ART_NEM_RABBIT_ADDR = 0x7FDD2;

    // CNZ Bumpers (addresses from Nemesis S2 art listing)
    public static final int ART_NEM_HEX_BUMPER_ADDR = 0x81894; // ArtNem_CNZHexBumper - Hex Bumper (ObjD7) - 6 blocks
    public static final int ART_NEM_BUMPER_ADDR = 0x8191E; // ArtNem_CNZRoundBumper - Round Bumper (Obj44) - 24 blocks
    public static final int ART_NEM_BONUS_BLOCK_ADDR = 0x81DCC; // ArtNem_CNZMiniBumper - Bonus Block (ObjD8) - 28
                                                                // blocks

    // CNZ Map Bumpers (triangular bumpers embedded in level tiles)
    // ROM Reference: SpecialCNZBumpers at s2.asm line 32146
    public static final int CNZ_BUMPERS_ACT1_ADDR = 0x1781A; // SpecialCNZBumpers_Act1
    public static final int CNZ_BUMPERS_ACT2_ADDR = 0x1795E; // SpecialCNZBumpers_Act2
    public static final int ZONE_CNZ = 0x0C; // Casino Night Zone index (s2.constants.asm: casino_night_zone = $0C)

    // CNZ Flipper (Object 0x86)
    public static final int ART_NEM_FLIPPER_ADDR = 0x81EF2; // ArtNem_CNZFlipper

    // Water Surface Art (Object $04 / SurfaceWater)
    // CPZ uses the same water surface art as HPZ (pink/purple chemical water)
    public static final int ART_NEM_WATER_SURFACE_CPZ_ADDR = 0x82364; // Top of water in HPZ and CPZ (24 blocks)
    // ARZ has distinct water surface art (natural blue water)
    public static final int ART_NEM_WATER_SURFACE_ARZ_ADDR = 0x82E02; // Top of water in ARZ (16 blocks)

    // CPZ Speed Booster (Object 0x1B)
    public static final int ART_NEM_SPEED_BOOSTER_ADDR = 0x824D4;  // ArtNem_CPZBooster (verified)
    public static final int MAP_UNC_SPEED_BOOSTER_ADDR = 0x223E2;  // Obj1B_MapUnc

    // CPZ Pipe Exit Spring (Object 0x7B) - warp tube exit spring
    public static final int ART_NEM_PIPE_EXIT_SPRING_ADDR = 0x82C06;  // ArtNem_CPZTubeSpring (verified)

    // CPZ Tipping Floor (Object 0x0B) - Small yellow platform that tips
    public static final int ART_NEM_CPZ_ANIMATED_BITS_ADDR = 0x82864;  // ArtNem_CPZAnimatedBits (verified)

    // Barrier (Object 0x2D) - One-way rising barrier
    public static final int ART_NEM_CONSTRUCTION_STRIPES_ADDR = 0x827F8;  // ArtNem_ConstructionStripes (CPZ/DEZ)
    public static final int ART_NEM_ARZ_BARRIER_ADDR = 0x830D2;           // ArtNem_ARZBarrierThing
    public static final int MAP_UNC_BARRIER_ADDR = 0x11822;               // Obj2D_MapUnc_11822 (Enigma)

    // CPZ BlueBalls (Object 0x1D) - Bouncing water droplet hazard
    public static final int ART_NEM_CPZ_DROPLET_ADDR = 0x8253C;  // ArtNem_CPZDroplet (verified)

    // Breakable Block (Object 0x32) - CPZ metal blocks / HTZ rocks
    public static final int ART_NEM_CPZ_METAL_BLOCK_ADDR = 0x827B8;  // ArtNem_CPZMetalBlock (verified)

    // CPZ/OOZ/WFZ Moving Platform (Object 0x19)
    public static final int MAP_UNC_OBJ19_ADDR = 0x2222A;  // Obj19_MapUnc_2222A
    public static final int ART_NEM_CPZ_ELEVATOR_ADDR = 0x82216;    // ArtNem_CPZElevator (verified)
    public static final int ART_NEM_OOZ_ELEVATOR_ADDR = 0x810B8;    // ArtNem_OOZElevator (verified)

    // CPZ Staircase (Object 0x78) - shares appearance with CPZ platform
    public static final int ART_NEM_CPZ_STAIRBLOCK_ADDR = 0x82A46;  // ArtNem_CPZStairBlock (Moving block from CPZ)
    public static final int ART_NEM_WFZ_PLATFORM_ADDR = 0x8D96E;    // ArtNem_WfzFloatingPlatform (verified)
    public static final int ART_TILE_CPZ_ELEVATOR = 0x03A0;  // palette 3
    public static final int ART_TILE_OOZ_ELEVATOR = 0x02F4;  // palette 3
    public static final int ART_TILE_WFZ_PLATFORM = 0x046D;  // palette 1, priority

    // Zone indices (from s2.constants.asm zoneID macro)
    public static final int ZONE_CHEMICAL_PLANT = 0x0D;  // chemical_plant_zone
    public static final int ZONE_OIL_OCEAN = 0x0A;       // oil_ocean_zone
    public static final int ZONE_WING_FORTRESS = 0x06;   // wing_fortress_zone

    public static final int[][] START_POSITIONS = {
            { 0x0060, 0x028F }, // 0 Emerald Hill 1 (EHZ_1.bin)
            { 0x0060, 0x02AF }, // 1 Emerald Hill 2 (EHZ_2.bin)
            { 0x0000, 0x0000 }, // 2 Unused (e.g. HPZ / WZ / etc. – not wired in final game)
            { 0x0000, 0x0000 }, // 3 Unused
            { 0x0060, 0x01EC }, // 4 Chemical Plant 1 (CPZ_1.bin)
            { 0x0000, 0x0000 }, // 5 Chemical Plant 2 (CPZ_2.bin – not fetched)
            { 0x0000, 0x0000 }, // 6 Aquatic Ruin 1 (ARZ_1.bin – not fetched)
            { 0x0000, 0x0000 }, // 7 Aquatic Ruin 2 (ARZ_2.bin – not fetched)
            { 0x0000, 0x0000 }, // 8 Casino Night 1 (CNZ_1.bin – not fetched)
            { 0x0000, 0x0000 }, // 9 Casino Night 2 (CNZ_2.bin – not fetched)
            { 0x0060, 0x03EF }, // 10 Hill Top 1 (HTZ_1.bin)
            { 0x0000, 0x0000 }, // 11 Hill Top 2 (HTZ_2.bin – not fetched)
            { 0x0060, 0x06AC }, // 12 Mystic Cave 1 (MCZ_1.bin)
            { 0x0000, 0x0000 }, // 13 Mystic Cave 2 (MCZ_2.bin – not fetched)
            { 0x0060, 0x06AC }, // 14 Oil Ocean 1 (OOZ_1.bin)
            { 0x0000, 0x0000 }, // 15 Oil Ocean 2 (OOZ_2.bin – not fetched)
            { 0x0060, 0x028C }, // 16 Metropolis 1 (MTZ_1.bin)
            { 0x0000, 0x0000 }, // 17 Metropolis 2 (MTZ_2.bin – not fetched)
            { 0x0000, 0x0000 }, // 18 Metropolis 3 (MTZ_3.bin – not fetched)
            { 0x0000, 0x0000 }, // 19 Unused
            { 0x0120, 0x0070 }, // 20 Sky Chase 1 (SCZ.bin)
            { 0x0000, 0x0000 }, // 21 Unused
            { 0x0060, 0x04CC }, // 22 Wing Fortress 1 (WFZ_1.bin)
            { 0x0000, 0x0000 }, // 23 Unused
            { 0x0060, 0x012D }, // 24 Death Egg 1 (DEZ_1.bin)
            { 0x0000, 0x0000 }, // 25 Unused
            { 0x0000, 0x0000 }, // 26 Special Stage
    };
}
