package com.openggf.game.sonic2.constants;

import java.util.HashMap;
import java.util.Map;

public class Sonic2Constants {
    public static final int DEFAULT_ROM_SIZE = 0x100000; // 1MB
    public static final int DEFAULT_LEVEL_LAYOUT_DIR_ADDR = 0x045A80;
    public static final int LEVEL_LAYOUT_DIR_ADDR_LOC = 0xE46E;
    public static final int LEVEL_LAYOUT_DIR_SIZE = 68;
    public static final int LEVEL_SELECT_ADDR = 0x9454;
    public static final int LEVEL_DATA_DIR = 0x42594;
    public static final int LEVEL_DATA_DIR_ENTRY_SIZE = 12;

    // PLC (Pattern Load Cue) table address - immediately follows LEVEL_DATA_DIR
    // ArtLoadCues table at s2.asm line 88634 (word_42660)
    // LEVEL_DATA_DIR (0x42594) + 17 entries * 12 bytes = 0x42660
    public static final int ART_LOAD_CUES_ADDR = 0x42660;
    public static final int ART_LOAD_CUES_ENTRY_COUNT = 67;

    // PLC ID constants (from s2disasm ArtLoadCues offset table)
    // Standard PLCs (loaded for all zones)
    public static final int PLC_STD1 = 0;           // HUD, life icon, ring, numbers
    public static final int PLC_STD2 = 1;           // Checkpoint, signpost, monitors, shield, stars, explosion
    public static final int PLC_STD_WATER = 2;      // Explosion, super sonic stars, bubbles
    public static final int PLC_GAME_OVER = 3;      // Game over / time over text
    // Zone PLCs (2 per zone: primary + secondary)
    public static final int PLC_EHZ1 = 4;
    public static final int PLC_EHZ2 = 5;
    public static final int PLC_LVL1_1 = 6;         // Unused level 1
    public static final int PLC_LVL1_2 = 7;
    public static final int PLC_WZ1 = 8;            // Wood Zone (unused)
    public static final int PLC_WZ2 = 9;
    public static final int PLC_LVL3_1 = 10;        // Unused level 3
    public static final int PLC_LVL3_2 = 11;
    public static final int PLC_MTZ1 = 12;
    public static final int PLC_MTZ2 = 13;
    public static final int PLC_MTZ3_1 = 14;        // MTZ Act 3 (zone ID 5)
    public static final int PLC_MTZ3_2 = 15;
    public static final int PLC_WFZ1 = 16;
    public static final int PLC_WFZ2 = 17;
    public static final int PLC_HTZ1 = 18;
    public static final int PLC_HTZ2 = 19;
    public static final int PLC_HPZ1 = 20;          // Hidden Palace (unused)
    public static final int PLC_HPZ2 = 21;
    public static final int PLC_LVL9_1 = 22;        // Unused level 9
    public static final int PLC_LVL9_2 = 23;
    public static final int PLC_OOZ1 = 24;
    public static final int PLC_OOZ2 = 25;
    public static final int PLC_MCZ1 = 26;
    public static final int PLC_MCZ2 = 27;
    public static final int PLC_CNZ1 = 28;
    public static final int PLC_CNZ2 = 29;
    public static final int PLC_CPZ1 = 30;
    public static final int PLC_CPZ2 = 31;
    public static final int PLC_DEZ1 = 32;
    public static final int PLC_DEZ2 = 33;
    public static final int PLC_ARZ1 = 34;
    public static final int PLC_ARZ2 = 35;
    public static final int PLC_SCZ1 = 36;
    public static final int PLC_SCZ2 = 37;
    public static final int PLC_RESULTS = 38;        // Results screen
    public static final int PLC_SIGNPOST = 39;       // Signpost/goal plate
    // Boss PLCs (verified against s2disasm ArtLoadCues offset table, s2.asm:88676-88704)
    public static final int PLC_CPZ_BOSS = 40;       // Eggpod + CPZ boss drip + pipes + jets + smoke
    public static final int PLC_EHZ_BOSS = 41;       // EHZ boss car + choppers
    public static final int PLC_HTZ_BOSS = 42;
    public static final int PLC_ARZ_BOSS = 43;
    public static final int PLC_MCZ_BOSS = 44;
    public static final int PLC_CNZ_BOSS = 45;
    public static final int PLC_MTZ_BOSS = 46;
    public static final int PLC_OOZ_BOSS = 47;
    public static final int PLC_FIERY_EXPLOSION = 48;
    public static final int PLC_DEZ_BOSS = 49;
    // Per-zone animal pairs (verified against s2disasm, s2.asm:88686-88697)
    public static final int PLC_ANIMALS_EHZ = 50;
    public static final int PLC_ANIMALS_MCZ = 51;
    public static final int PLC_ANIMALS_HTZ_MTZ_WFZ = 52; // HTZ, MTZ, WFZ share one animal PLC
    public static final int PLC_ANIMALS_DEZ = 53;
    public static final int PLC_ANIMALS_HPZ = 54;
    public static final int PLC_ANIMALS_OOZ = 55;
    public static final int PLC_ANIMALS_SCZ = 56;
    public static final int PLC_ANIMALS_CNZ = 57;
    public static final int PLC_ANIMALS_CPZ = 58;
    public static final int PLC_ANIMALS_ARZ = 59;
    // Special stage and misc PLCs (verified against s2disasm, s2.asm:88698-88704)
    public static final int PLC_SPECIAL_STAGE = 60;
    public static final int PLC_SPECIAL_STAGE_BOMBS = 61;
    public static final int PLC_WFZ_BOSS = 62;
    public static final int PLC_TORNADO = 63;
    public static final int PLC_CAPSULE = 64;
    public static final int PLC_EXPLOSION = 65;
    public static final int PLC_RESULTS_TAILS = 66;

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
    public static final int ART_TILE_TAILS_TAILS = 0x07B0;
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
    public static final int TAILS_ANIM_DATA_ADDR = 0x01D038;
    public static final int TAILS_ANIM_SCRIPT_COUNT = 0x21; // 33 scripts (0x00-0x20)

    public static final int ART_NEM_SHIELD_ADDR = 0x71D8E;
    // Bridge art (EHZ wooden bridge - 8 blocks)
    public static final int ART_NEM_BRIDGE_ADDR = 0xF052A;

    // Waterfall art (EHZ waterfall)
    public static final int ART_NEM_EHZ_WATERFALL_ADDR = 0xF02D6;
    public static final int ART_TILE_EHZ_WATERFALL = 0x39E;
    // Palette cycling (EHZ/ARZ water)
    public static final int CYCLING_PAL_EHZ_ARZ_WATER_ADDR = 0x001E7A;
    public static final int CYCLING_PAL_EHZ_ARZ_WATER_LEN = 0x20;

    // Palette cycling (CPZ - Chemical Plant Zone)
    public static final int CYCLING_PAL_CPZ1_ADDR = 0x002022;  // 9 frames × 6 bytes = 54 bytes (3 colors)
    public static final int CYCLING_PAL_CPZ1_LEN = 54;
    public static final int CYCLING_PAL_CPZ2_ADDR = 0x002058;  // 21 frames × 2 bytes = 42 bytes (1 color)
    public static final int CYCLING_PAL_CPZ2_LEN = 42;
    public static final int CYCLING_PAL_CPZ3_ADDR = 0x002082;  // 16 frames × 2 bytes = 32 bytes (1 color)
    public static final int CYCLING_PAL_CPZ3_LEN = 32;

    // Palette cycling (WFZ - Wing Fortress Zone)
    // CyclingPal_WFZFire (s2.asm:3098) - Fire palette when WFZ_SCZ_Fire_Toggle == 0
    public static final int CYCLING_PAL_WFZ_FIRE_ADDR = 0x0020A2;  // 4 frames × 8 bytes = 32 bytes (4 colors)
    public static final int CYCLING_PAL_WFZ_FIRE_LEN = 32;
    // CyclingPal_WFZBelt (s2.asm:3101) - Conveyor belt palette when WFZ_SCZ_Fire_Toggle != 0
    public static final int CYCLING_PAL_WFZ_BELT_ADDR = 0x0020C2;  // 4 frames × 8 bytes = 32 bytes (4 colors)
    public static final int CYCLING_PAL_WFZ_BELT_LEN = 32;
    // CyclingPal_WFZ1 (s2.asm:3104) - Flashing light cycle 1 (1 color, 34 frames)
    public static final int CYCLING_PAL_WFZ1_ADDR = 0x0020E2;  // 34 frames × 2 bytes = 68 bytes
    public static final int CYCLING_PAL_WFZ1_LEN = 68;
    // CyclingPal_WFZ2 (s2.asm:3107) - Flashing light cycle 2 (1 color, 12 frames)
    public static final int CYCLING_PAL_WFZ2_ADDR = 0x002126;  // 12 frames × 2 bytes = 24 bytes
    public static final int CYCLING_PAL_WFZ2_LEN = 24;

    // Palette cycling (HTZ - Hill Top Zone) - Lava animation
    public static final int CYCLING_PAL_LAVA_ADDR = 0x001E9A;  // 16 frames × 8 bytes = 128 bytes
    public static final int CYCLING_PAL_LAVA_LEN = 128;

    // Palette cycling (MTZ - Metropolis Zone)
    public static final int CYCLING_PAL_MTZ1_ADDR = 0x001F2A;  // 6 frames × 2 bytes = 12 bytes
    public static final int CYCLING_PAL_MTZ1_LEN = 12;
    public static final int CYCLING_PAL_MTZ2_ADDR = 0x001F36;  // 3 frames × 4 bytes (2 colors + padding) = 12 bytes
    public static final int CYCLING_PAL_MTZ2_LEN = 12;
    public static final int CYCLING_PAL_MTZ3_ADDR = 0x001F42;  // 10 frames × 2 bytes = 20 bytes
    public static final int CYCLING_PAL_MTZ3_LEN = 20;

    // Palette cycling (OOZ - Oil Ocean Zone)
    public static final int CYCLING_PAL_OIL_ADDR = 0x001F76;  // 4 frames × 4 bytes = 16 bytes
    public static final int CYCLING_PAL_OIL_LEN = 16;

    // OOZ Oil Surface (Obj07) - ROM: s2.asm:49667-49672
    public static final int OIL_SURFACE_Y = 0x758;       // Default Y position of oil surface
    public static final int OIL_SUBMERSION_MAX = 0x30;    // 48 frames before suffocation
    public static final int OIL_WIDTH = 0x20;             // Platform half-width (tracks player X)

    // Palette cycling (MCZ - Mystic Cave Zone) - Lanterns
    public static final int CYCLING_PAL_LANTERN_ADDR = 0x001F86;  // 4 frames × 2 bytes = 8 bytes
    public static final int CYCLING_PAL_LANTERN_LEN = 8;

    // Palette cycling (CNZ - Casino Night Zone)
    public static final int CYCLING_PAL_CNZ1_ADDR = 0x001F8E;  // 3 frames × 12 bytes = 36 bytes (6 interleaved colors)
    public static final int CYCLING_PAL_CNZ1_LEN = 36;
    public static final int CYCLING_PAL_CNZ3_ADDR = 0x001FB2;  // 3 frames × 6 bytes = 18 bytes (3 interleaved colors)
    public static final int CYCLING_PAL_CNZ3_LEN = 18;
    public static final int CYCLING_PAL_CNZ4_ADDR = 0x001FC4;  // 18 frames × varying = 40 bytes
    public static final int CYCLING_PAL_CNZ4_LEN = 40;

    // Super Sonic transformation palette data (CyclingPal_SSTransformation, s2.asm:3236)
    public static final int CYCLING_PAL_SS_TRANSFORMATION_ADDR = 0x2246; // 128 bytes (16 frames * 8 bytes)
    public static final int CYCLING_PAL_SS_TRANSFORMATION_LEN = 0x80;
    // CPZ underwater variant (CyclingPal_CPZUWTransformation, s2.asm:3242)
    public static final int CYCLING_PAL_CPZ_UW_SS_TRANSFORMATION_ADDR = 0x22C6;
    // ARZ underwater variant (CyclingPal_ARZUWTransformation, s2.asm:3248)
    public static final int CYCLING_PAL_ARZ_UW_SS_TRANSFORMATION_ADDR = 0x2346;

    // Super Sonic stars art (Nemesis compressed, ArtNem_SuperSonic_stars, s2.asm:89802)
    public static final int ART_NEM_SUPER_SONIC_STARS_ADDR = 0x7393C;
    // Super Sonic stars mappings (uncompressed, Obj7E_MapUnc_1E1BE)
    public static final int MAP_UNC_SUPER_SONIC_STARS_ADDR = 0x1E1BE;
    // Super Sonic stars VRAM art tile (from s2.constants.asm)
    public static final int ART_TILE_SUPER_SONIC_STARS = 0x05F2;

    // Super Sonic animation data (separate table from normal Sonic, SuperSonicAniData s2.asm:38415)
    public static final int SUPER_SONIC_ANIM_DATA_ADDR = 0x1B7C6; // SuperSonicAniData (code label, verified via ROM pattern search)
    public static final int SUPER_SONIC_ANIM_SCRIPT_COUNT = 32; // Scripts 0-31 (0x00-0x1F)

    // Ring drain frame interval
    public static final int SUPER_SONIC_RING_DRAIN_INTERVAL = 60; // 1 ring per second at 60fps
    // Minimum rings to transform
    public static final int SUPER_SONIC_MIN_RINGS = 50;

    public static final int ART_NEM_INVINCIBILITY_STARS_ADDR = 0x71F14;
    public static final int MAP_UNC_INVINCIBILITY_STARS_ADDR = 0x1DCBC;
    public static final int ART_TILE_INVINCIBILITY_STARS = 0x05C0;

    public static final int MAP_UNC_OBJ18_A_ADDR = 0x107F6;
    public static final int MAP_UNC_OBJ18_B_ADDR = 0x1084E;

    // Falling Pillar (Object 0x23) - ARZ pillar that drops its lower section
    public static final int MAP_UNC_OBJ23_ADDR = 0x259E6;  // Obj23_MapUnc_259E6

    // Rising Pillar (Object 0x2B) - ARZ pillar that rises and launches player
    public static final int MAP_UNC_OBJ2B_ADDR = 0x25C6E;  // Obj2B_MapUnc_25C6E

    // Swinging Platform (Object 0x82) - ARZ swinging vine platform
    public static final int MAP_UNC_OBJ82_ADDR = 0x2A476;  // Obj82_MapUnc_2A476

    // MCZ Brick / Spike Ball (Object 0x75)
    public static final int MAP_UNC_OBJ75_ADDR = 0x28D8A;  // Obj75_MapUnc_28D8A

    // Rotating Platforms (Object 0x83) - ARZ (shared mappings with Obj15 SwingingPlatform)
    public static final int MAP_UNC_OBJ83_ADDR = 0x1021E;  // Obj15_Obj83_MapUnc_1021E

    // SwingingPlatform (Object 0x15) - chain-suspended platform in OOZ, ARZ, MCZ
    public static final int ART_NEM_OOZ_SWING_PLAT_ADDR = 0x80E26;  // ArtNem_OOZSwingPlat (verified via RomOffsetFinder)
    public static final int ART_TILE_OOZ_SWING_PLAT = 0x03E3;       // VRAM tile offset
    public static final int MAP_UNC_OBJ15_A_ADDR = 0x101E8;         // OOZ mappings (Obj15_MapUnc_101E8)
    public static final int MAP_UNC_OBJ15_MCZ_ADDR = 0x10256;       // MCZ mappings (Obj15_Obj7A_MapUnc_10256)
    public static final int MAP_UNC_OBJ15_TRAP_ADDR = 0x102DE;      // MCZ trap mappings (Obj15_MapUnc_102DE)

    // NOTE: ARZ is identified by its ROM zone id, ZONE_ARZ (0x0F), to match Level.getZoneIndex().
    // A former ZONE_AQUATIC_RUIN = 2 (a level-select index) was removed: it never matched
    // getZoneIndex()'s ROM zone id, which left ARZ's floating platform (Obj18) using the EHZ
    // Obj18A mappings and rendering corrupted graphics.

    // Checkpoint/Starpost (Object $79)
    public static final int ART_NEM_CHECKPOINT_ADDR = 0x79A86; // Star pole.nem
    public static final int MAP_UNC_CHECKPOINT_ADDR = 0x1F424; // obj79_a.asm (pole+dongle)
    public static final int MAP_UNC_CHECKPOINT_STAR_ADDR = 0x1F4A0; // obj79_b.asm (special stage stars)
    public static final int ANI_OBJ79_ADDR = 0x1F0C2; // Ani_obj79
    public static final int ANI_OBJ79_SCRIPT_COUNT = 3;
    public static final int ART_TILE_CHECKPOINT = 0x047C;

    // Signpost/Goal Plate (Object 0D)
    public static final int ART_NEM_SIGNPOST_ADDR = 0x79BDE; // Signpost.nem (78 blocks)

    // Egg Prison / Capsule (Object 0x3E)
    public static final int ART_NEM_EGG_PRISON_ADDR = 0x7BA32; // Egg Prison.nem (verified via RomOffsetFinder)
    public static final int MAP_UNC_EGG_PRISON_ADDR = 0x3F436;  // Obj3E_MapUnc_3F436
    public static final int ART_TILE_SIGNPOST = 0x0434;

    // Results Screen Art (Obj3A)
    public static final int ART_UNC_HUD_NUMBERS_ADDR = 0x4134C; // Art_Hud (digits source)
    public static final int ART_UNC_HUD_NUMBERS_SIZE = 0x300; // 24 tiles (10 digits x 2 tiles + extras?)
    public static final int ART_UNC_LIVES_NUMBERS_ADDR = 0x4164C; // Small numbers for lives counter
    public static final int ART_UNC_LIVES_NUMBERS_SIZE = 320; // 10 tiles (0-9)
    // Art_Text: Uncompressed 8x8 font used by the debug HUD (HudDb_XY) to render hex
    // player/camera coords. ASCII-aligned layout: digits 0-9 at tiles 0-9, A-F at
    // tiles 17-22 ('0'=0x30, 'A'=0x41 -> offset 0x11). Shared binary with
    // "Big and small numbers used on counters - 3.bin".
    public static final int ART_UNC_DEBUG_FONT_ADDR = 0x4178C;
    public static final int ART_UNC_DEBUG_FONT_SIZE = 736; // 23 tiles
    public static final int ART_NEM_HUD_ADDR = 0x7923E; // HUD.nem (SCORE/TIME/RING text)
    public static final int ART_NEM_TITLE_CARD_ADDR = 0x7D22C; // Title card.nem (E, N, O, Z letters)
    // ArtNem_Game_Over ("Game and Time Over text.nem", 401 bytes; verified by byte match
    // against the ROM). Loaded by PLCID_GameOver at ArtTile_ArtNem_Game_Over ($4DE):
    // docs/s2disasm/s2.asm:89312, s2.constants.asm:2581.
    public static final int ART_NEM_GAME_OVER_ADDR = 0x7B400;
    // Obj39_MapUnc_14C6C: GAME / OVER / TIME / OVER, tiles relative to ArtTile_ArtNem_Game_Over
    // (docs/s2disasm/s2.asm:28816, mappings/sprite/obj39.asm).
    public static final int MAPPINGS_GAME_OVER_ADDR = 0x14C6C;
    // GameOver_GameText / GameOver_OverText share the TitleCard slots directly after
    // MainCharacter and Sidekick in Object_RAM (docs/s2disasm/s2.constants.asm:1095-1112).
    public static final int SST_SLOT_GAME_OVER_WORD = 2;
    public static final int SST_SLOT_GAME_OVER_OVER = 3;
    public static final int ART_NEM_TITLE_CARD2_ADDR = 0x7D58A; // Font using large broken letters.nem (other letters)

    // Level Select Art (Nemesis compressed)
    public static final int ART_NEM_MENU_BOX_ADDR = 0x7D990;           // Menu borders, boxes, text frames
    public static final int ART_NEM_LEVEL_SELECT_PICS_ADDR = 0x7DA10;  // Zone preview icons (15 icons)
    public static final int ART_NEM_FONT_STUFF_ADDR = 0x7C43A;         // Standard menu font

    // Level Select Mappings (Enigma compressed)
    public static final int MAP_ENI_LEVEL_SELECT_ADDR = 0x9ADE;        // Main screen layout (40x28 tiles)
    public static final int MAP_ENI_LEVEL_SELECT_ICON_ADDR = 0x9C32;   // Icon box layout (preview area)

    // Menu Background (Sonic/Miles) - uncompressed art + Enigma mappings
    public static final int ART_UNC_MENU_BACK_ADDR = 0x7CD2C;          // ArtUnc_MenuBack (40 tiles)
    public static final int ART_UNC_MENU_BACK_SIZE = 1280;             // 40 tiles * 32 bytes
    public static final int MAP_ENI_MENU_BACK_ADDR = 0x7CB80;          // MapEng_MenuBack (40x28 tiles)
    public static final int MAP_ENI_MENU_BACK_SIZE = 428;              // Enigma-compressed map size

    // Level Select Palettes (uncompressed)
    public static final int PAL_MENU_ADDR = 0x30E2;                    // Pal_Menu - 4 palette lines (128 bytes)
    public static final int PAL_MENU_SIZE = 128;                       // 4 lines * 16 colors * 2 bytes
    public static final int PAL_LEVEL_ICONS_ADDR = 0x9880;             // 15 icon palettes (32 bytes each)
    public static final int PAL_LEVEL_ICONS_SIZE = 480;                // 15 * 32 bytes
    // Title Screen Art (Nemesis compressed)
    public static final int ART_NEM_TITLE_ADDR = 0x74F6C;          // Background patterns
    public static final int ART_NEM_TITLE_SPRITES_ADDR = 0x7667A;  // ArtNem_TitleSprites (Sonic/Tails/stars, verified)
    public static final int ART_NEM_SEGA_LOGO_ADDR = 0x74876;      // ArtNem_SEGA (verified via RomOffsetFinder)
    public static final int ART_NEM_MENU_JUNK_ADDR = 0x78CBC;     // ArtNem_MenuJunk (extra sprite tiles at VRAM 0x03F2)

    // Credit Text Art (Nemesis compressed, 64 patterns)
    // Used for "SONIC AND MILES 'TAILS' PROWER IN" intro screen and credits screens
    public static final int ART_NEM_CREDIT_TEXT_ADDR = 0xBD26;     // ArtNem_CreditText

    // =====================================================================
    // Ending Sequence & Credits (s2.asm lines 12998-14640)
    // ROM Reference: EndingSequence, EndgameCredits, EndgameLogoFlash,
    //   ObjCA (cutscene controller), ObjCB (clouds), ObjCC (tornado),
    //   ObjCD (birds), ObjCE (Sonic/Tails jumping off plane),
    //   ObjCF (ending mini-sprites)
    // =====================================================================

    // --- Ending Art (Nemesis compressed) ---
    // Character art loaded to VRAM at ArtTile_EndingCharacter (0x0019)
    public static final int ART_NEM_ENDING_SONIC_ADDR = 0x92F0A;       // ArtNem_EndingSonic (verified)
    public static final int ART_NEM_ENDING_SUPER_SONIC_ADDR = 0x93848; // ArtNem_EndingSuperSonic (verified)
    public static final int ART_NEM_ENDING_TAILS_ADDR = 0x93F3C;       // ArtNem_EndingTails (verified)
    // Final tornado closeup loaded to ArtTile_ArtNem_EndingFinalTornado (0x0156)
    public static final int ART_NEM_ENDING_FINAL_TORNADO_ADDR = 0x91F3C; // ArtNem_EndingFinalTornado (verified)
    // Photo frames loaded to ArtTile_ArtNem_EndingPics (0x0328)
    public static final int ART_NEM_ENDING_PICS_ADDR = 0x90992;        // ArtNem_EndingPics (verified)
    // Small tornado sprites loaded to ArtTile_ArtNem_EndingMiniTornado (0x0493)
    public static final int ART_NEM_ENDING_MINI_TORNADO_ADDR = 0x927E0; // ArtNem_EndingMiniTornado (verified)
    // "Sonic 2" logo at end of credits (loaded to VRAM 0x0000)
    public static final int ART_NEM_ENDING_TITLE_ADDR = 0x94B28;       // ArtNem_EndingTitle (verified)

    // --- Ending Photo Mappings (Enigma compressed) ---
    // These are decoded into Chunk_Table and DMA'd to Plane A at planeLoc(64,14,8)
    // using PlaneMapToVRAM_H40 with dimensions 12x9
    public static final int MAP_ENG_ENDING1_ADDR = 0x906E0;  // MapEng_Ending1 (verified, 24 bytes)
    public static final int MAP_ENG_ENDING2_ADDR = 0x906F8;  // MapEng_Ending2 (verified, 42 bytes)
    public static final int MAP_ENG_ENDING3_ADDR = 0x90722;  // MapEng_Ending3 (verified, 26 bytes)
    public static final int MAP_ENG_ENDING4_ADDR = 0x9073C;  // MapEng_Ending4 (verified, 50 bytes)

    // --- Ending Plane Closeup Mappings (Enigma compressed) ---
    // Decoded to Chunk_Table with art_tile ArtTile_ArtNem_EndingFinalTornado + priority
    public static final int MAP_ENG_ENDING_TAILS_PLANE_ADDR = 0x9076E;  // MapEng_EndingTailsPlane (verified, 82 bytes)
    public static final int MAP_ENG_ENDING_SONIC_PLANE_ADDR = 0x907C0;  // MapEng_EndingSonicPlane (verified, 106 bytes)

    // --- End Game Logo Mapping (Enigma compressed) ---
    // "Sonic the Hedgehog 2" logo shown after all credits screens
    // Decoded at art_tile 0, written to Plane A at planeLoc(64,12,11), dimensions 16x6
    public static final int MAP_ENG_END_GAME_LOGO_ADDR = 0xB23A; // MapEng_EndGameLogo (verified, 40 bytes)

    // --- Ending Palettes (uncompressed binary) ---
    public static final int PAL_ENDING_SONIC_ADDR = 0xAC7E;     // Pal_AC7E - Ending Sonic (32 bytes)
    public static final int PAL_ENDING_TAILS_ADDR = 0xAC9E;     // Pal_AC9E - Ending Tails (64 bytes)
    public static final int PAL_ENDING_BACKGROUND_ADDR = 0xACDE; // Pal_ACDE - Ending Background (64 bytes)
    public static final int PAL_ENDING_PHOTOS_ADDR = 0xAD1E;    // Pal_AD1E - Ending Photos (32 bytes)
    public static final int PAL_ENDING_SUPER_SONIC_ADDR = 0xAD3E; // Pal_AD3E - Ending Super Sonic (32 bytes)
    // Full palette loaded at EndingSequence init: all 4 lines (128 bytes) from Pal_AC7E
    public static final int PAL_ENDING_FULL_ADDR = 0xAC7E;      // All 4 palette lines (128 bytes total)
    public static final int PAL_ENDING_FULL_SIZE = 128;

    // --- Logo Flash Palette Cycle ---
    // EndgameLogoFlash uses byte_A0EC as a strobe index (18 entries),
    // each referencing a 24-byte (0x18) palette frame within pal_A0FE (216 bytes, 9 frames)
    public static final int LOGO_FLASH_STROBE_ADDR = 0xA0EC;    // byte_A0EC - 18-byte strobe sequence
    public static final int LOGO_FLASH_STROBE_SIZE = 18;
    public static final int PAL_ENDING_CYCLE_ADDR = 0xA0FE;     // pal_A0FE - Ending Cycle (216 bytes, 9 frames x 24 bytes)
    public static final int PAL_ENDING_CYCLE_SIZE = 216;
    public static final int PAL_ENDING_CYCLE_FRAME_SIZE = 24;   // Each palette frame is 24 bytes (12 colors)

    // --- Credits Screen Pointer Table ---
    // off_B2CA: 21 screen pointers + $FFFFFFFF terminator (88 bytes)
    // Each pointer references a creditsPtrs structure:
    //   repeated { dc.l textAddr, dc.w vramDest } terminated by dc.w -1
    public static final int CREDITS_SCREEN_TABLE_ADDR = 0xB2CA; // off_B2CA
    public static final int CREDITS_SCREEN_COUNT = 21;

    // --- Intro Text Pointer Table ---
    // off_B2B0: single screen for "SONIC AND MILES 'TAILS' PROWER IN"
    public static final int INTRO_TEXT_TABLE_ADDR = 0xB2B0;     // off_B2B0

    // --- Ending Sprite Mappings (uncompressed, assembly-included) ---
    // ObjCF sprites (ending mini-Sonic/Tails/plane/Super Sonic walk frames)
    public static final int MAP_UNC_OBJCF_ADDR = 0xADA2;        // ObjCF_MapUnc_ADA2
    // Obj28 animal sprites (used by ObjCD birds in ending)
    public static final int MAP_UNC_OBJ28_A_ADDR = 0x11E1C;     // Obj28_MapUnc_11E1C (Animal_2, flicky/eagle/chicken)

    // --- Ending VRAM Tile Indices (from s2.constants.asm) ---
    public static final int ART_TILE_ENDING_CHARACTER = 0x0019;          // ArtTile_EndingCharacter
    public static final int ART_TILE_ENDING_FINAL_TORNADO = 0x0156;      // ArtTile_ArtNem_EndingFinalTornado
    public static final int ART_TILE_ENDING_PICS = 0x0328;               // ArtTile_ArtNem_EndingPics
    public static final int ART_TILE_ENDING_MINI_TORNADO = 0x0493;       // ArtTile_ArtNem_EndingMiniTornado
    public static final int ART_TILE_ENDING_TORNADO = 0x0500;            // ArtTile_ArtNem_Tornado (shared with WFZ/SCZ)
    public static final int ART_TILE_ENDING_CLOUDS = 0x054F;             // ArtTile_ArtNem_Clouds (shared with WFZ/SCZ)
    public static final int ART_TILE_ENDING_ANIMAL_2 = 0x0594;           // ArtTile_ArtNem_Animal_2 (flicky/eagle/chicken)
    public static final int ART_TILE_CREDIT_TEXT_CREDITS = 0x0001;       // ArtTile_ArtNem_CreditText_CredScr (credits screen)

    // --- Ending VDP Layout ---
    public static final int VRAM_END_SEQ_PLANE_A = 0xC000;  // VRAM_EndSeq_Plane_A_Name_Table
    public static final int VRAM_END_SEQ_PLANE_B1 = 0xE000; // VRAM_EndSeq_Plane_B_Name_Table1
    public static final int VRAM_END_SEQ_PLANE_B2 = 0x4000; // VRAM_EndSeq_Plane_B_Name_Table2

    // --- Ending Timing Constants ---
    public static final int CREDITS_SLIDE_DURATION_NTSC = 0x18E; // 398 frames at 60fps per credits slide
    public static final int CREDITS_SLIDE_DURATION_PAL = 0x144;  // 324 frames at 50fps per credits slide
    public static final int LOGO_FLASH_CYCLE_LIMIT = 0x5E;       // 94 frames of logo flash cycling
    public static final int LOGO_FLASH_HOLD_FRAMES = 0x3B;       // 59 frames hold before cycling starts

    // Title Screen Sonic Palette (uncompressed, 32 bytes, loaded to palette line 0)
    public static final int PAL_TITLE_SONIC_ADDR = 0x133EC;        // Pal_133EC (Title Sonic.bin)
    public static final int PAL_TITLE_SONIC_SIZE = 32;

    // Title Screen Mappings (Enigma compressed)
    public static final int MAP_ENI_TITLE_SCREEN_ADDR = 0x74DC6;   // Plane B background (40x28)
    public static final int MAP_ENI_TITLE_BACK_ADDR = 0x74E3A;     // Plane B water/horizon (24x28, col 40+)
    public static final int MAP_ENI_TITLE_LOGO_ADDR = 0x74E86;     // Plane A logo/emblem (40x28)
    // CopyrightText (word_3E82): "@ 1992 SEGA" as 11 plane-map words that TitleScreen copies
    // into the decoded logo map at planeLoc(40,28,26) before it is sent to Plane A.
    public static final int TITLE_COPYRIGHT_TEXT_ADDR = 0x3E82;
    public static final int TITLE_COPYRIGHT_TEXT_WORDS = 11;
    public static final int TITLE_COPYRIGHT_PLANE_COLUMN = 28;
    public static final int TITLE_COPYRIGHT_PLANE_ROW = 26;
    // ArtTile_ArtNem_FontStuff_TtlScr: the standard font's VRAM tile on the title screen
    public static final int ART_TILE_FONT_STUFF_TITLE_SCREEN = 0x680;
    public static final int MAP_ENI_SEGA_LOGO_ADDR = 0x74D0E;      // MapEng_SEGA (40x28, verified)
    public static final int MAP_UNC_SEGA_GIANT_SONIC_ADDR = 0x3A5A6; // ObjB1_MapUnc_3A5A6

    // Title Screen Palettes (uncompressed, 32 bytes each)
    // Pal_Title loaded at palette line 1 via PalLoad_ForFade (palptr Pal_Title, 1)
    public static final int PAL_TITLE_ADDR = 0x2942;
    public static final int PAL_TITLE_SIZE = 32;
    public static final int PAL_SEGA_SCREEN_ADDR = 0x28C2;         // Pal_SEGA (128 bytes, verified)
    public static final int PAL_SEGA_SCREEN_SIZE = 128;
    public static final int PAL_SEGA_SCREEN_WIPE_BG_ADDR = 0x3A4A4; // Sega Screen 2.bin, 7 frames x 8 colors
    public static final int PAL_SEGA_SCREEN_WIPE_FG_ADDR = 0x3A51A; // Sega Screen 3.bin, 7 frames x 8 colors
    public static final int PAL_SEGA_SCREEN_WIPE_SIZE = 112;
    // Pal_1340C loaded at palette line 2 (Normal_palette_line3) by Obj0E intro animation
    // Used by Plane B background (make_art_tile palette 2)
    public static final int PAL_TITLE_BACKGROUND_ADDR = 0x1340C;
    public static final int PAL_TITLE_BACKGROUND_SIZE = 32;
    // Pal_1342C loaded at palette line 3 (Normal_palette_line4) by Obj0E intro animation
    // Used by Plane A logo/emblem (make_art_tile palette 3)
    public static final int PAL_TITLE_EMBLEM_ADDR = 0x1342C;
    public static final int PAL_TITLE_EMBLEM_SIZE = 32;

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

    // MCZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_CRAWLTON_ADDR = 0x8AB36; // Crawlton / Snake badnik from MCZ (verified)
    public static final int ART_NEM_FLASHER_ADDR = 0x8AC5E;  // Flasher / Firefly from MCZ (verified)

    // CPZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_SPINY_ADDR = 0x8B430; // Spiny (CPZ crawling badnik)
    public static final int ART_NEM_GRABBER_ADDR = 0x8B6B4; // Grabber (CPZ spider badnik)

    // ARZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_CHOPCHOP_ADDR = 0x89B9A; // ChopChop (piranha from ARZ)
    public static final int ART_NEM_WHISP_ADDR = 0x895E4;    // Whisp (blowfly from ARZ)
    public static final int ART_NEM_GROUNDER_ADDR = 0x8970E; // Grounder (drill badnik from ARZ)

    // MTZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_MTZ_SUPERNOVA_ADDR = 0x8B300; // Asteron (exploding starfish from MTZ)
    public static final int ART_NEM_MTZ_MANTIS_ADDR = 0x8AD80;    // Slicer (praying mantis from MTZ)
    public static final int ART_NEM_SHELLCRACKER_ADDR = 0x8B058;  // Shellcracker (crab badnik from MTZ)

    // HTZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_SPIKER_ADDR = 0x89FAA;   // Spiker (drill badnik from HTZ)
    public static final int ART_NEM_REXON_ADDR = 0x89DEC;    // Rexon (lava snake from HTZ)

    // SCZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_NEBULA_ADDR = 0x8A142;   // Nebula (bomber badnik from SCZ, verified via RomOffsetFinder)
    public static final int ART_NEM_TURTLOID_ADDR = 0x8A362; // Turtloid (turtle badnik from SCZ)
    public static final int ART_NEM_BALKIRY_ADDR = 0x8BC16;   // Balkiry (jet badnik from SCZ, verified via RomOffsetFinder)

    // OOZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_OCTUS_ADDR = 0x8336A;    // Octus (octopus badnik from OOZ)
    public static final int ART_NEM_AQUIS_ADDR = 0x8368A;    // Aquis (seahorse badnik from OOZ)
    public static final int ART_NEM_OOZ_BOSS_ADDR = 0x882D6; // ArtNem_OOZBoss (verified via RomOffsetFinder)

    // CNZ Badnik Art (Nemesis compressed)
    public static final int ART_NEM_CRAWL_ADDR = 0x901A4;    // Crawl (bouncer badnik from CNZ, 42 tiles)

    // Arrow Shooter (Object 0x22) - ARZ
    public static final int ART_NEM_ARROW_SHOOTER_ADDR = 0x82F74; // ArtNem_ArrowAndShooter
    public static final int MAP_UNC_ARROW_SHOOTER_ADDR = 0x25804; // Obj22_MapUnc_25804
    public static final int ART_TILE_ARROW_SHOOTER = 0x0417;      // ArtTile_ArtNem_ArrowAndShooter

    // MCZ Boss art
    public static final int ART_NEM_MCZ_BOSS_ADDR = 0x86B6E;         // ArtNem_MCZBoss (verified via RomOffsetFinder)
    public static final int ART_UNC_FALLING_ROCKS_ADDR = 0x894E4;    // ArtUnc_FallingRocks (256 bytes, verified)
    public static final int ART_TILE_MCZ_BOSS = 0x03C0;              // ArtTile_ArtNem_MCZBoss
    public static final int ART_TILE_FALLING_ROCKS = 0x0560;         // ArtTile_ArtUnc_FallingRocks
    public static final int MAP_UNC_MCZ_BOSS_ADDR = 0x316EC;         // Obj57_MapUnc_316EC (21 frames)
    public static final int PAL_MCZ_BOSS_ADDR = 0x3082;              // Pal_MCZ_B (32 bytes, verified)
    public static final int PAL_CNZ_BOSS_ADDR = 0x30A2;              // Pal_CNZ_B (32 bytes, verified)
    public static final int PAL_OOZ_BOSS_ADDR = 0x30C2;              // Pal_OOZ_B (32 bytes, after Pal_CNZ_B)

    // Boss art (Nemesis compressed, verified offsets)
    public static final int ART_NEM_EGGPOD_ADDR = 0x83BF6;     // ArtNem_Eggpod (flying vehicle)
    public static final int ART_NEM_EHZ_BOSS_ADDR = 0x8507C;   // ArtNem_EHZBoss (ground vehicle/wheels/spike)
    public static final int ART_NEM_HTZ_BOSS_ADDR = 0x8595C;   // ArtNem_HTZBoss (flamethrower/lava ball components)
    public static final int ART_NEM_CNZ_BOSS_ADDR = 0x87AAC;   // ArtNem_CNZBoss (verified via RomOffsetFinder)
    public static final int ART_NEM_EGG_CHOPPERS_ADDR = 0x85868; // ArtNem_EggChoppers (propeller blades)
    public static final int ART_NEM_CPZ_BOSS_ADDR = 0x84332;   // ArtNem_CPZBoss (verified via RomOffsetFinder)
    public static final int ART_NEM_EGGPOD_JETS_ADDR = 0x84F18; // ArtNem_EggpodJets (verified via RomOffsetFinder)
    public static final int ART_NEM_BOSS_SMOKE_ADDR = 0x84F96;  // ArtNem_BossSmoke (verified via RomOffsetFinder)
    public static final int ART_NEM_FIERY_EXPLOSION_ADDR = 0x84890; // ArtNem_FieryExplosion (Obj58, verified)
    public static final int ART_NEM_ARZ_BOSS_ADDR = 0x86128;    // ArtNem_ARZBoss (verified via RomOffsetFinder)

    // Boss art tile bases (VRAM pattern IDs)
    public static final int ART_TILE_EGGPOD = 0x03A0;      // ArtTile_ArtNem_Eggpod_1
    public static final int ART_TILE_EHZ_BOSS = 0x0400;    // ArtTile_ArtNem_EHZBoss
    public static final int ART_TILE_EGG_CHOPPERS = 0x056C; // ArtTile_ArtNem_EggChoppers
    public static final int ART_TILE_EGGPOD_3 = 0x0420;    // ArtTile_ArtNem_Eggpod_3 (CPZ boss)
    public static final int ART_TILE_EGGPOD_JETS_1 = 0x0418; // ArtTile_ArtNem_EggpodJets_1 (CPZ boss)
    public static final int ART_TILE_CPZ_BOSS = 0x0500;    // ArtTile_ArtNem_CPZBoss
    public static final int ART_TILE_BOSS_SMOKE_1 = 0x0570; // ArtTile_ArtNem_BossSmoke_1
    public static final int ART_TILE_FIERY_EXPLOSION = 0x0580; // ArtTile_ArtNem_FieryExplosion
    public static final int ART_TILE_ARZ_BOSS = 0x03E0;    // ArtTile_ArtNem_ARZBoss
    public static final int ART_TILE_MTZ_BOSS = 0x037C;   // ArtTile_ArtNem_MTZBoss
    public static final int ART_TILE_EGGPOD_4 = 0x0500;   // ArtTile_ArtNem_Eggpod_4 (ARZ/MCZ/CNZ/MTZ boss)
    public static final int ART_TILE_EGGPOD_JETS_2 = 0x0560; // ArtTile_ArtNem_EggpodJets_2 (MTZ boss)
    public static final int ART_TILE_CNZ_BOSS = 0x0407;   // ArtTile_ArtNem_CNZBoss
    public static final int ART_TILE_CNZ_BOSS_FUDGE = 0x03A7; // ArtTile_ArtNem_CNZBoss_Fudge (= 0x0407 - 0x60)
    public static final int ART_TILE_HTZ_BOSS = 0x0421;  // ArtTile_ArtNem_HTZBoss (flamethrower/lava ball)
    public static final int ART_TILE_EGGPOD_2 = 0x03C1;  // ArtTile_ArtNem_Eggpod_2 (HTZ boss uses this)
    public static final int ART_TILE_OOZ_BOSS = 0x03E8;  // ArtTile_ArtNem_OOZBoss

    // CNZ Boss mappings (uncompressed)
    public static final int MAP_UNC_CNZ_BOSS_ADDR = 0x320EA;  // Obj51_MapUnc_320EA (21 frames)

    // CNZ Boss palette cycling (addresses from disassembly)
    public static final int CYCLING_PAL_CNZ_BOSS1_ADDR = 0x1FEC;  // CyclingPal_CNZ1_B (boss cycle 1)
    public static final int CYCLING_PAL_CNZ_BOSS1_LEN = 18;
    public static final int CYCLING_PAL_CNZ_BOSS2_ADDR = 0x1FFE;  // CyclingPal_CNZ2_B (boss cycle 2)
    public static final int CYCLING_PAL_CNZ_BOSS2_LEN = 20;
    public static final int CYCLING_PAL_CNZ_BOSS3_ADDR = 0x2012;  // CyclingPal_CNZ3_B (boss cycle 3)
    public static final int CYCLING_PAL_CNZ_BOSS3_LEN = 16;

    // CPZ Boss mappings (uncompressed)
    public static final int MAP_UNC_CPZ_BOSS_PARTS_ADDR = 0x2EADC;   // Obj5D_MapUnc_2EADC (CPZ boss parts)
    public static final int MAP_UNC_CPZ_BOSS_EGGPOD_ADDR = 0x2ED8C;  // Obj5D_MapUnc_2ED8C (eggpod body)
    public static final int MAP_UNC_CPZ_BOSS_JETS_ADDR = 0x2EE88;    // Obj5D_MapUnc_2EE88 (eggpod jets)
    public static final int MAP_UNC_CPZ_BOSS_SMOKE_ADDR = 0x2EEA0;   // Obj5D_MapUnc_2EEA0 (boss smoke)
    public static final int MAP_UNC_BOSS_EXPLOSION_ADDR = 0x2D50A;   // Obj58_MapUnc_2D50A (boss explosion)
    public static final int MAP_UNC_ARZ_BOSS_PARTS_ADDR = 0x30D68;   // Obj89_MapUnc_30D68 (ARZ boss parts)
    public static final int MAP_UNC_ARZ_BOSS_MAIN_ADDR = 0x30E04;    // Obj89_MapUnc_30E04 (ARZ boss main)

    // DEZ Boss art (Silver Sonic / Mecha Sonic, ObjAF)
    public static final int ART_NEM_SILVER_SONIC_ADDR = 0x8BE12;       // ArtNem_SilverSonic (tile base $0380, palette 1)
    public static final int ART_NEM_DEZ_WINDOW_ADDR = 0x8EF96;         // ArtNem_DEZWindow (tile base $0378, palette 0)
    public static final int MAP_UNC_SILVER_SONIC_ADDR = 0x39E68;        // ObjAF_MapUnc_39E68 (23 frames: Silver Sonic + spikeballs)
    public static final int MAP_UNC_DEZ_WINDOW_ADDR = 0x3A08C;          // ObjAF_MapUnc_3A08C (8 frames: DEZ window)

    // DEZ Boss art (Death Egg Robot, ObjC7)
    public static final int ART_NEM_DEZ_BOSS_ADDR = 0x8F024;            // ArtNem_DEZBoss / Eggrobo (tile base $0330, palette 0)
    public static final int MAP_UNC_DEZ_BOSS_ADDR = 0x3E5F8;            // ObjC7_MapUnc_3E5F8 (23 frames: Death Egg Robot parts)

    // WFZ Boss art (ObjC5) - laser platform boss
    public static final int ART_NEM_WFZ_BOSS_ADDR = 0x8E138;             // ArtNem_WfzBoss (tile base $0379, palette 0)
    public static final int ART_NEM_WFZ_FLOAT_PLATFORM_ADDR = 0x8D96E;   // ArtNem_WfzFloatingPlatform (tile base $046D, palette 1)
    public static final int ART_NEM_ROBOTNIK_UPPER_ADDR = 0x8E886;        // ArtNem_Eggpod_1 (tile base $0500, palette 0)
    public static final int ART_NEM_ROBOTNIK_RUNNING_ADDR = 0x8EA5A;      // ArtNem_EggpodRunning (tile base $0518, palette 0)
    public static final int ART_NEM_ROBOTNIK_LOWER_ADDR = 0x8EE52;        // ArtNem_RobotnikLower (tile base $0564, palette 0)
    public static final int MAP_UNC_WFZ_BOSS_ADDR = 0x3CCD8;             // ObjC5_MapUnc_3CCD8 (19 frames: case, walls, platforms, laser)
    public static final int MAP_UNC_WFZ_ROBOTNIK_PLATFORM_ADDR = 0x3CEBC; // ObjC6_MapUnc_3CEBC (1 frame: Robotnik platform)
    public static final int MAP_UNC_WFZ_ROBOTNIK_ADDR = 0x3D0EE;         // ObjC6_MapUnc_3D0EE (8 frames: Robotnik)
    public static final int MAP_UNC_DEZ_WALL_ADDR = 0x3D1DE;             // ObjC6_MapUnc_3D1DE (4 frames: DEZ barrier wall, construction stripes)

    // HTZ Boss mappings (uncompressed)
    public static final int MAP_UNC_HTZ_BOSS_SMOKE_ADDR = 0x30258;  // Obj52_MapUnc_30258 (smoke particles)
    public static final int MAP_UNC_HTZ_BOSS_MAIN_ADDR = 0x302BC;   // Obj52_MapUnc_302BC (main boss, flamethrower, lava ball)

    // MTZ Boss art and mappings
    public static final int ART_NEM_MTZ_BOSS_ADDR = 0x88DA6;        // ArtNem_MTZBoss (verified via RomOffsetFinder)
    public static final int MAP_UNC_MTZ_BOSS_ADDR = 0x32DC6;        // Obj54_MapUnc_32DC6 (verified via ROM search)
    public static final int MAP_UNC_OOZ_BOSS_ADDR = 0x33756;        // Obj55_MapUnc_33756

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

    // Springboard / Lever Spring (Object 0x40) - CPZ, ARZ, MCZ
    public static final int ART_NEM_LEVER_SPRING_ADDR = 0x7AB4A;  // ArtNem_LeverSpring

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

    // CNZ Rect Blocks (Object 0xD2) - "Caterpiller" flashing blocks
    public static final int ART_NEM_CNZ_SNAKE_ADDR = 0x81600; // ArtNem_CNZSnake

    // CNZ Big Block (Object 0xD4) - Large 64x64 oscillating platform
    public static final int ART_NEM_CNZ_BIG_BLOCK_ADDR = 0x816C8; // ArtNem_BigMovingBlock

    // CNZ Elevator (Object 0xD5) - Vertical platform that moves when stood on
    public static final int ART_NEM_CNZ_ELEVATOR_ADDR = 0x817B4; // ArtNem_CNZElevator

    // CNZ Point Pokey Cage (Object 0xD6) - Casino cage that awards points
    public static final int ART_NEM_CNZ_CAGE_ADDR = 0x81826; // ArtNem_CNZCage (verified)

    // CNZ Bonus Spike (Object 0xD3) - Spiky ball prize from slot machine
    public static final int ART_NEM_CNZ_BONUS_SPIKE_ADDR = 0x81668; // ArtNem_CNZBonusSpike (verified)

    // CNZ Slot Machine Pictures (uncompressed) - 6 faces × 512 bytes = 3072 bytes
    // 4×4 tiles (32×32 pixels) per face, 4bpp indexed color
    public static final int ART_UNC_CNZ_SLOT_PICS_ADDR = 0x4EEFE; // ArtUnc_CNZSlotPics (verified)
    public static final int ART_UNC_CNZ_SLOT_PICS_SIZE = 3072;    // 6 faces × 16 tiles × 32 bytes

    // CNZ LauncherSpring (Object 0x85) - pressure springs
    public static final int ART_NEM_CNZ_VERT_PLUNGER_ADDR = 0x81C96;  // Vertical spring art
    public static final int ART_NEM_CNZ_DIAG_PLUNGER_ADDR = 0x81AB0;  // Diagonal spring art
    public static final int ART_TILE_CNZ_VERT_PLUNGER = 0x0422;       // VRAM tile offset
    public static final int ART_TILE_CNZ_DIAG_PLUNGER = 0x0402;       // VRAM tile offset

    // Water Surface Art (Object $04 / SurfaceWater)
    // CPZ uses the same water surface art as HPZ (pink/purple chemical water)
    public static final int ART_NEM_WATER_SURFACE_CPZ_ADDR = 0x82364; // Top of water in HPZ and CPZ (24 blocks)
    // ARZ has distinct water surface art (natural blue water)
    public static final int ART_NEM_WATER_SURFACE_ARZ_ADDR = 0x82E02; // Top of water in ARZ (16 blocks)

    // Bubbles Art (Object $0A Small Bubbles, Object $24 Bubble Generator)
    // ArtNem_Bubbles: 0x7AEE2 (10 tiles) - standard-water bubble art at VRAM tile 0x05E8
    // ArtNem_BigBubbles: 0x7AD16 (37 tiles) - Obj0A/Obj24 base art at VRAM tile 0x055B
    // Countdown numbers: 0x7AF80 (uncompressed)
    public static final int ART_NEM_BUBBLES_ADDR = 0x7AEE2;  // ArtNem_Bubbles
    public static final int ART_NEM_BUBBLE_GENERATOR_ADDR = 0x7AD16;  // ArtNem_BigBubbles
    public static final int ART_UNC_COUNTDOWN_ADDR = 0x7AF80;  // Countdown numbers for drowning (uncompressed)
    public static final int MAP_UNC_SMALL_BUBBLES_ADDR = 0x1FBF6;  // Obj24_MapUnc_1FBF6 - Sonic breathing bubbles (shared with Obj24)
    public static final int MAP_UNC_BUBBLES_ADDR = 0x1FCA2;  // Obj24_MapUnc - bubble generator / countdown bubbles
    public static final int ART_TILE_BUBBLES = 0x055B;  // ArtTile_ArtNem_BigBubbles - VRAM tile base (original)
    public static final int ART_TILE_STANDARD_WATER_BUBBLES = 0x05E8;  // ArtTile_ArtNem_Bubbles

    // Leaves Art (Object $2C LeavesGenerator - ARZ falling leaves)
    public static final int ART_NEM_LEAVES_ADDR = 0x82EE8;  // ArtNem_Leaves - falling leaves (7 tiles)

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
    public static final int ART_NEM_HTZ_VALVE_BARRIER_ADDR = 0xF08F6;    // ArtNem_HtzValveBarrier (HTZ subtype 0)
    public static final int MAP_UNC_BARRIER_ADDR = 0x11822;               // Obj2D_MapUnc_11822 (Enigma)

    // CPZ BlueBalls (Object 0x1D) - Bouncing water droplet hazard
    public static final int ART_NEM_CPZ_DROPLET_ADDR = 0x8253C;  // ArtNem_CPZDroplet (verified)

    // Breakable Block (Object 0x32) - CPZ metal blocks / HTZ rocks
    public static final int ART_NEM_CPZ_METAL_BLOCK_ADDR = 0x827B8;  // ArtNem_CPZMetalBlock (verified)
    public static final int ART_NEM_HTZ_ROCK_ADDR = 0x0F0C14;        // ArtNem_HtzRock (verified)
    public static final int MAP_UNC_OBJ32_HTZ_ADDR = 0x23852;        // Obj32_MapUnc_23852 (HTZ rock)
    public static final int MAP_UNC_OBJ32_CPZ_ADDR = 0x23886;        // Obj32_MapUnc_23886 (CPZ metal block)

    // HTZ Dynamic Background Art (loaded at runtime based on camera position)
    public static final int ART_NEM_HTZ_CLIFFS_ADDR = 0x49A14;       // ArtNem_HTZCliffs (Nemesis, ~6KB decompressed)
    public static final int ART_UNC_HTZ_CLOUDS_ADDR = 0x4A33E;       // ArtUnc_HTZClouds (Uncompressed, 1024 bytes)
    public static final int ART_UNC_HTZ_CLOUDS_SIZE = 1024;          // 32 tiles × 32 bytes

    // CPZ/OOZ/WFZ Moving Platform (Object 0x19)
    public static final int MAP_UNC_OBJ19_ADDR = 0x2222A;  // Obj19_MapUnc_2222A
    public static final int ART_NEM_CPZ_ELEVATOR_ADDR = 0x82216;    // ArtNem_CPZElevator (verified)
    public static final int ART_NEM_OOZ_ELEVATOR_ADDR = 0x810B8;    // ArtNem_OOZElevator (verified)

    // Button (Object 0x47) - trigger button for MTZ/MCZ
    public static final int ART_NEM_BUTTON_ADDR = 0x78DAC;  // ArtNem_Button (verified)

    // MTZ Floor Spike (Object 0x6D) - retractable floor spike from Metropolis Zone
    public static final int ART_NEM_MTZ_SPIKE_ADDR = 0xF148E;  // ArtNem_MtzSpike (verified)

    // MTZ SpikyBlock (Object 0x68) - block with rotating spike from Metropolis Zone
    public static final int ART_NEM_MTZ_SPIKE_BLOCK_ADDR = 0xF12B6;  // ArtNem_MtzSpikeBlock (verified)

    // MTZ Cog (Object 0x65 child) - small cog attached to long platform
    public static final int ART_NEM_MTZ_COG_ADDR = 0xF178E;  // ArtNem_MtzCog (verified)

    // MTZ Steam (Object 0x42 child) - steam puff from SteamSpring
    public static final int ART_NEM_MTZ_STEAM_ADDR = 0xF1384;  // ArtNem_MtzSteam (verified)

    // MTZ Lava Bubble (Object 0x71) - animated lava bubble from Metropolis Zone
    public static final int ART_NEM_MTZ_LAVA_BUBBLE_ADDR = 0xF15C6;  // ArtNem_MtzLavaBubble (verified)

    // MTZ Spin Tube Flash (Object 0x67) - flash sprite shown during tube entry
    public static final int ART_NEM_MTZ_SPIN_TUBE_FLASH_ADDR = 0xF1870;  // ArtNem_MtzSpinTubeFlash (verified)

    // MTZ Wheel Indent (Object 0x6E subtype 3) - indent on large spinning wheel
    public static final int ART_NEM_MTZ_WHEEL_INDENT_ADDR = 0xF120E;  // ArtNem_MtzWheelIndent (verified)

    // MTZ Wheel art (Object 0x70 / Obj6E) - large spinning wheel from MTZ
    public static final int ART_NEM_MTZ_WHEEL_ADDR = 0xF0DB6;  // ArtNem_MtzWheel (verified)

    // Object 0x6E mappings (Obj6E_MapUnc_2852C) - LargeRotPform sprite mappings
    public static final int MAP_UNC_OBJ6E_ADDR = 0x2852C;

    // Object 0x70 mappings (Obj70_MapUnc_28786) - Cog sprite mappings
    public static final int MAP_UNC_OBJ70_ADDR = 0x28786;

    // MTZ Conveyor / Lava Cup (Object 0x6C) - small platform on pulleys
    public static final int ART_NEM_MTZ_LAVA_CUP_ADDR = 0xF167C;  // ArtNem_LavaCup (verified)

    // MTZ Nut (Object 0x69) - screw nut from Metropolis Zone
    public static final int ART_NEM_MTZ_ASST_BLOCKS_ADDR = 0xF1550;  // ArtNem_MtzAsstBlocks (verified)

    // CPZ Staircase (Object 0x78) - shares appearance with CPZ platform
    public static final int ART_NEM_CPZ_STAIRBLOCK_ADDR = 0x82A46;  // ArtNem_CPZStairBlock (Moving block from CPZ)

    // CPZ Pylon (Object 0x7C) - decorative background pylon
    public static final int ART_NEM_CPZ_METAL_THINGS_ADDR = 0x825AE;  // ArtNem_CPZMetalThings (verified)
    public static final int ART_NEM_WFZ_PLATFORM_ADDR = 0x8D96E;    // ArtNem_WfzFloatingPlatform (verified)
    public static final int ART_NEM_TORNADO_ADDR = 0x8CC44;         // ArtNem_Tornado (ObjB2 main sheet)
    public static final int ART_NEM_TORNADO_THRUSTER_ADDR = 0x90520; // ArtNem_TornadoThruster (ObjB2 subtype $5C)
    public static final int ART_NEM_WFZ_THRUST_ADDR = 0x8E0C4;      // ArtNem_WfzThrust (ObjBC/ObjB2 subtype $56/$58)
    public static final int MAP_UNC_OBJB2_A_ADDR = 0x3AFF2;         // ObjB2_MapUnc_3AFF2
    public static final int MAP_UNC_OBJB2_B_ADDR = 0x3B292;         // ObjB2_MapUnc_3B292
    public static final int MAP_UNC_OBJBC_ADDR = 0x3BC08;           // ObjBC_MapUnc_3BC08
    public static final int ART_TILE_CPZ_ELEVATOR = 0x03A0;  // palette 3
    public static final int ART_TILE_OOZ_ELEVATOR = 0x02F4;  // palette 3
    public static final int ART_TILE_WFZ_PLATFORM = 0x046D;  // palette 1, priority

    // WallTurret (Object 0xB8) - wall-mounted turret from WFZ
    // ROM Reference: s2.asm lines 79665-79776 (ObjB8 code)
    public static final int ART_NEM_WFZ_WALL_TURRET_ADDR = 0x8D1A0;  // ArtNem_WfzWallTurret (verified via RomOffsetFinder)
    public static final int MAP_UNC_WFZ_WALL_TURRET_ADDR = 0x3BA46;  // ObjB8_Obj98_MapUnc_3BA46
    public static final int ART_TILE_WFZ_WALL_TURRET = 0x03AB;       // ArtTile_ArtNem_WfzWallTurret (palette 0)

    // TiltingPlatform (Object 0xB6) - tilting/spinning platform from WFZ
    // ROM Reference: s2.asm lines 79331-79629 (ObjB6 code)
    public static final int ART_NEM_WFZ_TILT_PLATFORMS_ADDR = 0x8E010; // ArtNem_WfzTiltPlatforms (verified via RomOffsetFinder)
    public static final int MAP_UNC_OBJB6_ADDR = 0x3B856;             // ObjB6_MapUnc_3B856

    // VerticalLaser (Object 0xB7) - unused huge vertical laser from WFZ, spawned by ObjB6
    // ROM Reference: s2.asm lines 79632-79663 (ObjB7 code)
    public static final int ART_NEM_WFZ_VRTCL_LAZER_ADDR = 0x8DA6E;   // ArtNem_WfzVrtclLazer (verified via RomOffsetFinder)
    public static final int MAP_UNC_OBJB7_ADDR = 0x3B8E4;             // ObjB7_MapUnc_3B8E4

    // SmallMetalPform (Object 0xBD) - ascending/descending metal platform from WFZ
    // ROM Reference: s2.asm lines 79938-80074 (ObjBD code)
    public static final int ART_NEM_WFZ_BELT_PLATFORM_ADDR = 0x8DD0C; // ArtNem_WfzBeltPlatform (verified via RomOffsetFinder)
    public static final int ART_NEM_WFZ_UNUSED_BADNIK_ADDR = 0x8DDF6; // ArtNem_WfzUnusedBadnik (ObjBF, verified via RomOffsetFinder)

    // LateralCannon (Object 0xBE) - retracting platform from WFZ
    // ROM Reference: s2.asm lines 80080-80170 (ObjBE code)
    public static final int ART_NEM_WFZ_GUN_PLATFORM_ADDR = 0x8D540; // ArtNem_WfzGunPlatform (verified via RomOffsetFinder)
    public static final int MAP_UNC_OBJBE_ADDR = 0x3BE46;            // ObjBE_MapUnc_3BE46

    // Laser (Object 0xB9) - horizontal laser beam from WFZ
    // ROM Reference: s2.asm lines 79779-79829 (ObjB9 code)
    public static final int ART_NEM_WFZ_HRZNTL_LAZER_ADDR = 0x8DC42;  // ArtNem_WfzHrzntlLazer (verified via RomOffsetFinder)

    // SpeedLauncher (Object 0xC0) - catapult platform from WFZ
    // ROM Reference: s2.asm lines 80215-80381 (ObjC0 code)
    public static final int ART_NEM_WFZ_LAUNCH_CATAPULT_ADDR = 0x8DCA2; // ArtNem_WfzLaunchCatapult (verified via RomOffsetFinder)
    public static final int MAP_UNC_OBJC0_ADDR = 0x3C098;               // ObjC0_MapUnc_3C098

    // BreakablePlating (Object 0xC1) - breakable plating from WFZ
    // ROM Reference: s2.asm lines 80384-80565 (ObjC1 code)
    public static final int ART_NEM_BREAK_PANELS_ADDR = 0x7FF98; // ArtNem_BreakPanels (verified via RomOffsetFinder)
    public static final int MAP_UNC_OBJC1_ADDR = 0x3C280;        // ObjC1_MapUnc_3C280

    // Rivet (Object 0xC2) - rivet at end of WFZ that opens the ship when busted
    // ROM Reference: s2.asm lines 80568-80625 (ObjC2 code)
    public static final int ART_NEM_WFZ_SWITCH_ADDR = 0x7FF2A; // ArtNem_WfzSwitch (verified via RomOffsetFinder)

    // Clucker (Object 0xAD/0xAE) - chicken turret badnik from WFZ
    // ROM Reference: s2.asm lines 76778-76960 (ObjAD/ObjAE code)
    public static final int ART_NEM_WFZ_SCRATCH_ADDR = 0x8B9DC;   // ArtNem_WfzScratch (verified via RomOffsetFinder)

    // Vertical Propeller (Object 0xB4) - vertical spinning blades from WFZ/SCZ
    public static final int ART_NEM_WFZ_VRTCL_PRPLLR_ADDR = 0x8DEB8;  // ArtNem_WfzVrtclPrpllr (verified via RomOffsetFinder)

    // Horizontal Propeller (Object 0xB5) - horizontal spinning blades from WFZ/SCZ
    public static final int ART_NEM_WFZ_HRZNTL_PRPLLR_ADDR = 0x8DEE8;  // ArtNem_WfzHrzntlPrpllr (verified via RomOffsetFinder)

    // WFZWheel (Object 0xBA) - conveyor belt wheel from WFZ
    // ROM Reference: s2.asm lines 79835-79860 (ObjBA code)
    public static final int ART_NEM_WFZ_CONVEYOR_BELT_WHEEL_ADDR = 0x8D7D8; // ArtNem_WfzConveyorBeltWheel (verified via RomOffsetFinder)

    // SCZ Cloud (Object 0xB3) - decorative clouds from Sky Chase Zone
    public static final int ART_NEM_CLOUDS_ADDR = 0x8DAFC;  // ArtNem_Clouds (verified via RomOffsetFinder)

    // Zone indices (from s2.constants.asm zoneID macro)
    public static final int ZONE_CHEMICAL_PLANT = 0x0D;  // chemical_plant_zone
    public static final int ZONE_OIL_OCEAN = 0x0A;       // oil_ocean_zone
    public static final int ZONE_WING_FORTRESS = 0x06;   // wing_fortress_zone
    public static final int ZONE_MYSTIC_CAVE = 0x0B;     // mystic_cave_zone
    public static final int ZONE_ARZ = 0x0F;             // aquatic_ruin_zone

    // MovingVine (Object 0x80) - MCZ vine / WFZ hook
    // ROM Reference: s2.asm lines 56145-56412 (Obj80 code)
    public static final int ART_NEM_VINE_PULLEY_ADDR = 0xF1D5C;    // ArtNem_VinePulley (MCZ vine art)
    public static final int ART_NEM_WFZ_HOOK_ADDR = 0x8D388;        // ArtNem_WfzHook (WFZ hook art)
    public static final int MAP_UNC_OBJ80_MCZ_ADDR = 0x29C64;       // Obj80_MapUnc_29C64 (MCZ mappings, 7 frames)
    public static final int MAP_UNC_OBJ80_WFZ_ADDR = 0x29DD0;       // Obj80_MapUnc_29DD0 (WFZ mappings, 13 frames)
    public static final int ART_TILE_VINE_PULLEY = 0x041E;          // ArtTile_ArtNem_VinePulley (MCZ palette 3)
    public static final int ART_TILE_WFZ_HOOK = 0x03FA;             // ArtTile_ArtNem_WfzHook (WFZ palette 1)
    public static final int ART_TILE_WFZ_HOOK_FUDGE = 0x03FE;       // ArtTile_ArtNem_WfzHook_Fudge (bad mappings offset)

    // VineSwitch (Object 0x7F) - MCZ pull switch
    // ROM Reference: s2.asm lines 56022-56132 (Obj7F code)
    public static final int ART_NEM_VINE_SWITCH_ADDR = 0xF1C64;     // ArtNem_VineSwitch (Pull switch from MCZ.nem)
    public static final int MAP_UNC_OBJ7F_ADDR = 0x29938;           // Obj7F_MapUnc_29938 (2 frames)
    public static final int ART_TILE_VINE_SWITCH = 0x040E;          // ArtTile_ArtNem_VineSwitch (MCZ palette 3)

    // MCZRotPforms (Object 0x6A) - MCZ wooden crate / MTZ moving platform
    // ROM Reference: s2.asm lines 53645-53850 (Obj6A code)
    public static final int ART_NEM_CRATE_ADDR = 0xF187C;           // ArtNem_Crate (Large wooden box from MCZ.nem)
    public static final int ART_TILE_CRATE = 0x03C8;                // ArtTile_ArtNem_Crate (MCZ palette 3)

    // MCZDrawbridge (Object 0x81) - MCZ rotatable drawbridge
    // ROM Reference: s2.asm lines 56420-56617 (Obj81 code)
    public static final int ART_NEM_MCZ_GATE_LOG_ADDR = 0xF1E06;    // ArtNem_MCZGateLog (Drawbridge logs from MCZ.nem)
    public static final int MAP_UNC_OBJ81_ADDR = 0x2A24E;           // Obj81_MapUnc_2A24E (2 frames)
    public static final int ART_TILE_MCZ_GATE_LOG = 0x043C;         // ArtTile_ArtNem_MCZGateLog (MCZ palette 3)

    // OOZPoppingPform (Object 0x33) - green burner platform
    public static final int ART_NEM_BURNER_LID_ADDR = 0x80274;   // ArtNem_BurnerLid (verified via RomOffsetFinder)
    public static final int ART_NEM_OOZ_BURN_ADDR = 0x81514;     // ArtNem_OOZBurn (verified via RomOffsetFinder)

    // SlidingSpike / OOZSpring (Objects 0x43/0x45)
    public static final int ART_NEM_SPIKY_THING_ADDR = 0x8007C;   // ArtNem_SpikyThing (verified via RomOffsetFinder)
    public static final int ART_NEM_PUSH_SPRING_ADDR = 0x80C64;   // ArtNem_PushSpring (verified via RomOffsetFinder)

    // OOZ Launcher (Object 0x3D) - striped blocks that launch rolling player
    public static final int ART_NEM_STRIPED_BLOCKS_VERT_ADDR = 0x8030A;  // ArtNem_StripedBlocksVert (CPZ)
    public static final int ART_NEM_STRIPED_BLOCKS_HORIZ_ADDR = 0x81048; // ArtNem_StripedBlocksHoriz (OOZ)

    // Fan (Object 0x3F) - OOZ wind fan (horizontal and vertical variants share art)
    public static final int ART_NEM_OOZ_FAN_ADDR = 0x81254;  // ArtNem_OOZFanHoriz (verified via RomOffsetFinder)

    // LauncherBall (Object 0x48) - OOZ transporter ball
    public static final int ART_NEM_LAUNCH_BALL_ADDR = 0x806E0;  // ArtNem_LaunchBall (verified via RomOffsetFinder)

    // Collapsing Platform art (Object 0x1F)
    public static final int ART_NEM_OOZ_COLLAPSING_PLATFORM_ADDR = 0x809D0;  // ArtNem_OOZPlatform
    public static final int ART_NEM_MCZ_COLLAPSING_PLATFORM_ADDR = 0xF1ABA;  // ArtNem_MCZCollapsePlat

    // HTZ/EHZ Level Resource Composition (from SonLVL.ini)
    // HTZ uses EHZ_HTZ base data with HTZ-specific overlays.
    // Reference: docs/s2disasm/SonLVL.ini [Hill Top Zone Act 1/2]
    //
    // TERMINOLOGY NOTE: SonLVL uses inverted terminology from this engine!
    //   - SonLVL "blocks" (16x16) = Engine "chunks"
    //   - SonLVL "chunks" (128x128) = Engine "blocks"
    // The constants below use ENGINE terminology (chunks=16x16, blocks=128x128).

    // Foreground 8x8 patterns (Kosinski-compressed):
    // SonLVL: tiles=../art/kosinski/EHZ_HTZ.bin|../art/kosinski/HTZ_Supp.bin:0x3F80
    public static final int HTZ_PATTERNS_BASE_ADDR = 0x095C24;      // Base patterns (shared EHZ/HTZ)
    public static final int HTZ_PATTERNS_OVERLAY_ADDR = 0x098AB4;   // HTZ supplemental patterns
    public static final int HTZ_PATTERNS_OVERLAY_OFFSET = 0x3F80;   // Byte offset for HTZ pattern overlay

    // 16x16 chunk mappings (engine terminology - SonLVL calls these "blocks"):
    // SonLVL: blocks=../mappings/16x16/EHZ.bin|../mappings/16x16/HTZ.bin:0x980
    public static final int HTZ_CHUNKS_BASE_ADDR = 0x094E74;        // Base 16x16 chunks (EHZ)
    public static final int HTZ_CHUNKS_OVERLAY_ADDR = 0x0985A4;     // HTZ supplemental 16x16 chunks
    public static final int HTZ_CHUNKS_OVERLAY_OFFSET = 0x0980;     // Byte offset for HTZ chunk overlay

    // 128x128 block mappings (engine terminology - SonLVL calls these "chunks"):
    // SonLVL: chunks=../mappings/128x128/EHZ_HTZ.bin
    public static final int HTZ_BLOCKS_ADDR = 0x099D34;             // Shared 128x128 blocks (no overlay)

    // Collision index arrays (shared between EHZ and HTZ):
    // colind1=../collision/EHZ and HTZ primary 16x16 collision index.bin
    // colind2=../collision/EHZ and HTZ secondary 16x16 collision index.bin
    public static final int HTZ_COLLISION_PRIMARY_ADDR = 0x044E50;    // Primary collision index
    public static final int HTZ_COLLISION_SECONDARY_ADDR = 0x044F40;  // Secondary collision index

    // WFZ foreground pattern supplement (ArtKos_WFZ = "WFZ_Supp.kos"): like HTZ, WFZ
    // shares the SCZ base art (ArtKos_SCZ) and overlays a supplement that "overwrites
    // several SCZ tiles" starting at tile ArtTile_ArtKos_NumTiles_WFZ_Main = $0307.
    // The ROM applies this via a hardcoded patch (s2.asm:6494-6499), the sibling of the
    // HTZ patch. Building the getaway ship (and other WFZ-specific FG tiles) needs it.
    public static final int WFZ_PATTERNS_OVERLAY_ADDR = 0x0C7EC4;   // ArtKos_WFZ (WFZ_Supp.kos)
    public static final int WFZ_PATTERNS_OVERLAY_OFFSET = 0x60E0;   // $0307 tiles * $20 bytes/tile

    // HTZ ROM zone ID (from s2.constants.asm)
    public static final int ZONE_HTZ = 0x07;  // hill_top_zone

    // HTZ Seesaw (Object 0x14)
    public static final int ART_NEM_HTZ_SEESAW_ADDR = 0xF096E;  // Nemesis compressed
    public static final int ART_NEM_SOL_ADDR = 0xF0D4A;         // Ball uses Sol badnik art
    public static final int ART_NEM_HTZ_FIREBALL1_ADDR = 0xF0160; // ArtNem_HtzFireball1 (Sol fireball)
    public static final int ART_NEM_HTZ_FIREBALL2_ADDR = 0xF03DC; // ArtNem_HtzFireball2 (Lava Bubble Obj20)

    // HTZ Zipline Lift (Object 0x16)
    public static final int ART_NEM_HTZ_ZIPLINE_ADDR = 0xF0602;  // Nemesis compressed

    // HTZ Dynamic Art Tile Indices (from s2.constants.asm)
    // These tiles are normally populated by Dynamic_HTZ at runtime
    // ArtTile_ArtUnc_HTZMountains = $0500 (24 tiles for mountain/cliff art)
    // ArtTile_ArtUnc_HTZClouds = $0518 (8 tiles for cloud art)
    public static final int HTZ_MOUNTAINS_TILE_INDEX = 0x0500;  // Tile index 1280
    public static final int HTZ_MOUNTAINS_TILE_COUNT = 0x18;    // 24 tiles
    public static final int HTZ_CLOUDS_TILE_INDEX = 0x0518;     // Tile index 1304
    public static final int HTZ_CLOUDS_TILE_COUNT = 8;          // 8 tiles
    public static final int HTZ_DYNAMIC_TILES_END = 0x0520;     // First tile after dynamic art (1312)

    // ROM-parsed mapping addresses (from s2disasm MapUnc labels)
    public static final int MAP_UNC_EXPLOSION_ADDR = 0x21120;       // Obj27_MapUnc_21120
    public static final int MAP_UNC_SHIELD_ADDR = 0x1DBE4;           // Obj38_MapUnc_1DBE4
    public static final int MAP_UNC_BRIDGE_ADDR = 0xFC70;            // Obj11_MapUnc_FC70
    public static final int MAP_UNC_BUMPER_ADDR = 0x1F85A;           // Obj44_MapUnc_1F85A
    public static final int MAP_UNC_HEX_BUMPER_ADDR = 0x2C626;       // ObjD7_MapUnc_2C626
    public static final int MAP_UNC_BONUS_BLOCK_ADDR = 0x2C8C4;      // ObjD8_MapUnc_2C8C4
    public static final int MAP_UNC_WATERFALL_ADDR = 0x20C50;        // Obj49_MapUnc_20C50
    public static final int MAP_UNC_MASHER_ADDR = 0x2D442;           // Obj5C_MapUnc_2D442 (Masher = Obj5C)
    public static final int MAP_UNC_OCTUS_ADDR = 0x2CBFE;            // Obj4A_MapUnc_2CBFE
    public static final int MAP_UNC_BUZZER_ADDR = 0x2D2EA;           // Obj4B_MapUnc_2D2EA
    public static final int MAP_UNC_COCONUTS_ADDR = 0x37D96;         // Obj9D_Obj98_MapUnc_37D96
    public static final int MAP_UNC_CRAWLTON_ADDR = 0x37FF2;         // Obj9E_MapUnc_37FF2
    public static final int MAP_UNC_FLASHER_ADDR = 0x388F0;          // ObjA3_MapUnc_388F0
    public static final int MAP_UNC_ASTERON_ADDR = 0x38A96;          // ObjA4_Obj98_MapUnc_38A96
    public static final int MAP_UNC_SHELLCRACKER_ADDR = 0x38314;     // Obj9F_MapUnc_38314
    public static final int MAP_UNC_SLICER_ADDR = 0x385E2;           // ObjA1_MapUnc_385E2
    public static final int MAP_UNC_SPINY_ADDR = 0x38CCA;            // ObjA5_ObjA6_Obj98_MapUnc_38CCA
    public static final int MAP_UNC_GRABBER_ADDR = 0x3921A;          // ObjA7_ObjA8_ObjA9_Obj98_MapUnc_3921A
    public static final int MAP_UNC_GRABBER_STRING_ADDR = 0x39228;   // ObjAA_MapUnc_39228
    public static final int MAP_UNC_CHOPCHOP_ADDR = 0x36EF6;         // Obj91_MapUnc_36EF6
    public static final int MAP_UNC_WHISP_ADDR = 0x36A4E;            // Obj8C_MapUnc_36A4E
    public static final int MAP_UNC_GROUNDER_ADDR = 0x36CF0;         // Obj8D_MapUnc_36CF0
    public static final int MAP_UNC_GROUNDER_ROCK_ADDR = 0x36CFA;    // Obj90_MapUnc_36CFA
    public static final int MAP_UNC_SPIKER_ADDR = 0x37092;           // Obj92_Obj93_MapUnc_37092
    public static final int MAP_UNC_SOL_ADDR = 0x372E6;              // Obj95_MapUnc_372E6
    public static final int MAP_UNC_REXON_ADDR = 0x37678;            // Obj94_Obj98_MapUnc_37678
    public static final int MAP_UNC_CRAWL_ADDR = 0x3D450;            // ObjC8_MapUnc_3D450
    public static final int MAP_UNC_AQUIS_ADDR = 0x2CF94;            // Obj50_MapUnc_2CF94
    public static final int MAP_UNC_NEBULA_ADDR = 0x3789A;           // Obj99_Obj98_MapUnc_3789A
    public static final int MAP_UNC_TURTLOID_ADDR = 0x37B62;         // Obj9A_Obj98_MapUnc_37B62
    public static final int MAP_UNC_BALKIRY_ADDR = 0x393CC;          // ObjAC_MapUnc_393CC
    public static final int MAP_UNC_CLUCKER_ADDR = 0x395B4;          // ObjAD_Obj98_MapUnc_395B4 (shared with Turtloid)
    public static final int MAP_UNC_SEESAW_ADDR = 0x21CF0;           // Obj14_MapUnc_21CF0
    public static final int MAP_UNC_SEESAW_BALL_ADDR = 0x21D7C;      // Obj14_MapUnc_21D7C
    public static final int MAP_UNC_HTZ_LIFT_ADDR = 0x21F14;         // Obj16_MapUnc_21F14
    public static final int MAP_UNC_POINTS_ADDR = 0x11ED0;           // Obj29_MapUnc_11ED0
    public static final int MAP_UNC_SIGNPOST_A_ADDR = 0x195BE;       // Obj0D_MapUnc_195BE
    public static final int MAP_UNC_FLIPPER_ADDR = 0x2B45A;          // Obj86_MapUnc_2B45A
    public static final int MAP_UNC_CNZ_RECT_BLOCKS_ADDR = 0x2B694;  // ObjD2_MapUnc_2B694
    public static final int MAP_UNC_CNZ_BIG_BLOCK_ADDR = 0x2B9CA;    // ObjD4_MapUnc_2B9CA
    public static final int MAP_UNC_CNZ_ELEVATOR_ADDR = 0x2BB40;     // ObjD5_MapUnc_2BB40
    public static final int MAP_UNC_CNZ_CAGE_ADDR = 0x2BEBC;         // ObjD6_MapUnc_2BEBC
    public static final int MAP_UNC_CNZ_BONUS_SPIKE_ADDR = 0x2B8D4;  // ObjD3_MapUnc_2B8D4
    public static final int MAP_UNC_LAUNCHER_SPRING_VERT_ADDR = 0x2B07E; // Obj85_MapUnc_2B07E
    public static final int MAP_UNC_LAUNCHER_SPRING_DIAG_ADDR = 0x2B0EC; // Obj85_MapUnc_2B0EC
    public static final int MAP_UNC_BLUE_BALLS_ADDR = 0x22576;       // Obj1D_MapUnc_22576
    public static final int MAP_UNC_CPZ_STAIR_BLOCK_ADDR = 0x29564;  // Obj7A_MapUnc_29564
    public static final int MAP_UNC_CPZ_PYLON_ADDR = 0x2103C;        // Obj7C_MapUnc_2103C
    public static final int MAP_UNC_PIPE_EXIT_SPRING_ADDR = 0x29780; // Obj7B_MapUnc_29780
    public static final int MAP_UNC_TIPPING_FLOOR_ADDR = 0x201A0;    // Obj0B_MapUnc_201A0
    public static final int MAP_UNC_SPRINGBOARD_ADDR = 0x265F4;      // Obj40_MapUnc_265F4
    public static final int MAP_UNC_LEAVES_ADDR = 0x2631E;           // Obj2C_MapUnc_2631E
    public static final int MAP_UNC_OBJ33_A_ADDR = 0x23DDC;          // Obj33_MapUnc_23DDC (Burner Lid)
    public static final int MAP_UNC_OBJ33_B_ADDR = 0x23DF0;          // Obj33_MapUnc_23DF0 (Burn Flame)
    public static final int MAP_UNC_OBJ3F_HORIZ_ADDR = 0x2AA12;      // Obj3F_MapUnc_2AA12 (Fan Horiz)
    public static final int MAP_UNC_OBJ3F_VERT_ADDR = 0x2AAC4;       // Obj3F_MapUnc_2AAC4 (Fan Vert)
    public static final int MAP_UNC_OBJ43_ADDR = 0x23FE0;            // Obj43_MapUnc_23FE0 (OOZ SlidingSpike)
    public static final int MAP_UNC_OBJ45_ADDR = 0x2451A;            // Obj45_MapUnc_2451A (OOZ Pressure Spring)
    public static final int MAP_UNC_LAUNCH_BALL_ADDR = 0x254FE;      // Obj48_MapUnc_254FE
    public static final int MAP_UNC_OBJ3D_ADDR = 0x250BA;            // Obj3D_MapUnc_250BA (OOZ Launcher)
    public static final int MAP_UNC_OBJ1F_A_ADDR = 0x10F0C;          // Obj1F_MapUnc_10F0C (default/MZ/SLZ/SBZ Collapsing - obj1F_a.asm)
    public static final int MAP_UNC_OBJ1F_B_ADDR = 0x110C6;          // Obj1F_MapUnc_110C6 (OOZ Collapsing - obj1F_b.asm)
    public static final int MAP_UNC_OBJ1F_C_ADDR = 0x11106;          // Obj1F_MapUnc_11106 (MCZ Collapsing - obj1F_c.asm)
    public static final int MAP_UNC_CRATE_ADDR = 0x27D30;            // Obj6A_MapUnc_27D30
    public static final int MAP_UNC_OBJ77_ADDR = 0x29064;             // Obj77_MapUnc_29064 (MCZ Bridge)
    public static final int MAP_UNC_OBJBD_ADDR = 0x3BD3E;            // ObjBD_MapUnc_3BD3E (WFZ Belt Platform)
    public static final int MAP_UNC_OBJB9_ADDR = 0x3BB18;            // ObjB9_MapUnc_3BB18 (WFZ Laser)
    public static final int MAP_UNC_OBJC2_ADDR = 0x3C3C2;            // ObjC2_MapUnc_3C3C2 (WFZ Rivet)
    public static final int MAP_UNC_OBJBA_ADDR = 0x3BB70;            // ObjBA_MapUnc_3BB70 (WFZ Conveyor Belt Wheel)
    public static final int MAP_UNC_CLOUD_ADDR = 0x3B32C;            // ObjB3_MapUnc_3B32C
    public static final int MAP_UNC_VPROPELLER_ADDR = 0x3B3BE;       // ObjB4_MapUnc_3B3BE
    public static final int MAP_UNC_HPROPELLER_ADDR = 0x3B548;       // ObjB5_MapUnc_3B548
    // Obj65 standalone/child cog mappings (Obj65_MapUnc_26F04 = obj65_b.asm, 3 frames).
    // Used by MTZLongPlatformCogInstance with ArtNem_MtzCog (s2.asm:52402/52436/52754).
    public static final int MAP_UNC_MTZ_COG_ADDR = 0x26F04;          // Obj65_MapUnc_26F04 (obj65_b)
    // Shared Obj65/Obj6A/Obj6B MTZ moving-platform level-art mappings
    // (Obj65_Obj6A_Obj6B_MapUnc_26EC8 = obj65_a.asm, 4 frames), rendered against level art
    // (ArtTile_ArtKos_LevelArt) on VDP palette line 3. Used by Obj65 (s2.asm:52381),
    // Obj6A MTZ (s2.asm:53688), and Obj6B MTZ (s2.asm:53911).
    public static final int MAP_UNC_MTZ_PLATFORM_LEVELART_ADDR = 0x26EC8; // Obj65_Obj6A_Obj6B_MapUnc_26EC8 (obj65_a)
    public static final int MAP_UNC_BUTTON_ADDR = 0x24D96;           // Obj47_MapUnc_24D96
    public static final int MAP_UNC_MTZ_FLOOR_SPIKE_ADDR = 0x27750;  // Obj68_Obj6D_MapUnc_27750
    public static final int MAP_UNC_MTZ_SPIKE_BLOCK_ADDR = MAP_UNC_MTZ_FLOOR_SPIKE_ADDR; // Same table (Obj68)
    public static final int MAP_UNC_MTZ_SPIKE_ADDR = 0x27120;        // Obj66_MapUnc_27120
    public static final int MAP_UNC_MTZ_STEAM_ADDR = 0x2686C;        // Obj42_MapUnc_2686C
    public static final int MAP_UNC_MTZ_SPIN_TUBE_FLASH_ADDR = 0x27548; // Obj67_MapUnc_27548
    public static final int MAP_UNC_MTZ_LAVA_CUP_ADDR = 0x28372;     // Obj6C_MapUnc_28372
    public static final int MAP_UNC_EHZ_BOSS_A_ADDR = 0x2F970;       // Obj56_MapUnc_2F970 (propellers/EggChoppers)
    public static final int MAP_UNC_EHZ_BOSS_B_ADDR = 0x2FA58;       // Obj56_MapUnc_2FA58 (ground vehicle/wheels/spike)
    public static final int MAP_UNC_EHZ_BOSS_C_ADDR = 0x2FAF8;       // Obj56_MapUnc_2FAF8 (flying vehicle/Eggman)
    public static final int MAP_UNC_SMASHABLE_GROUND_A_ADDR = 0x236FA; // Obj2F_MapUnc_236FA (SmashableGround)
    public static final int MAP_UNC_LAVA_BUBBLE_ADDR = 0x23254;       // Obj20_MapUnc_23254 (HTZ Lava Bubble / fire source)
    public static final int MAP_UNC_GROUND_FIRE_ADDR = 0x23294;       // Obj20_MapUnc_23294 (HTZ ground fire)
    public static final int MAP_UNC_MTZ_NUT_ADDR = 0x27A26;          // Obj69_MapUnc_27A26
    public static final int MAP_UNC_MTZ_LAVA_BUBBLE_A_ADDR = 0x11396; // Obj71_MapUnc_11396
    public static final int MAP_UNC_MTZ_LAVA_BUBBLE_B_ADDR = 0x11576; // Obj71_MapUnc_11576 (MTZ lava bubble)
    public static final int MAP_UNC_OBJBB_ADDR = 0x3BBA0;            // ObjBB_MapUnc_3BBA0 (removed WFZ unknown object)
    public static final int MAP_UNC_OBJBF_ADDR = 0x3BEE0;            // ObjBF_MapUnc_3BEE0 (WFZStick / unused badnik)

    public static final int[][] START_POSITIONS = {
            { 0x0060, 0x028F }, // 0 Emerald Hill 1 (EHZ_1.bin)
            { 0x0060, 0x02AF }, // 1 Emerald Hill 2 (EHZ_2.bin)
            { 0x0000, 0x0000 }, // 2 Unused (e.g. HPZ / WZ / etc. – not wired in final game)
            { 0x0000, 0x0000 }, // 3 Unused
            { 0x0060, 0x01EC }, // 4 Chemical Plant 1 (CPZ_1.bin)
            { 0x0060, 0x012C }, // 5 Chemical Plant 2 (CPZ_2.bin)
            { 0x0060, 0x037E }, // 6 Aquatic Ruin 1 (ARZ_1.bin)
            { 0x0060, 0x037E }, // 7 Aquatic Ruin 2 (ARZ_2.bin)
            { 0x0060, 0x02AC }, // 8 Casino Night 1 (CNZ_1.bin)
            { 0x0060, 0x058C }, // 9 Casino Night 2 (CNZ_2.bin)
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

    /**
     * Returns all integer constants as a name-to-value map.
     * Used by Sonic2RomOffsetProvider to avoid reflection.
     */
    public static Map<String, Integer> getAllOffsets() {
        Map<String, Integer> offsets = new HashMap<>();
        offsets.put("DEFAULT_ROM_SIZE", DEFAULT_ROM_SIZE);
        offsets.put("DEFAULT_LEVEL_LAYOUT_DIR_ADDR", DEFAULT_LEVEL_LAYOUT_DIR_ADDR);
        offsets.put("LEVEL_LAYOUT_DIR_ADDR_LOC", LEVEL_LAYOUT_DIR_ADDR_LOC);
        offsets.put("LEVEL_LAYOUT_DIR_SIZE", LEVEL_LAYOUT_DIR_SIZE);
        offsets.put("LEVEL_SELECT_ADDR", LEVEL_SELECT_ADDR);
        offsets.put("LEVEL_DATA_DIR", LEVEL_DATA_DIR);
        offsets.put("LEVEL_DATA_DIR_ENTRY_SIZE", LEVEL_DATA_DIR_ENTRY_SIZE);
        offsets.put("LEVEL_PALETTE_DIR", LEVEL_PALETTE_DIR);
        offsets.put("SONIC_TAILS_PALETTE_ADDR", SONIC_TAILS_PALETTE_ADDR);
        offsets.put("COLLISION_LAYOUT_DIR_ADDR", COLLISION_LAYOUT_DIR_ADDR);
        offsets.put("ALT_COLLISION_LAYOUT_DIR_ADDR", ALT_COLLISION_LAYOUT_DIR_ADDR);
        offsets.put("OBJECT_LAYOUT_DIR_ADDR", OBJECT_LAYOUT_DIR_ADDR);
        offsets.put("SOLID_TILE_VERTICAL_MAP_ADDR", SOLID_TILE_VERTICAL_MAP_ADDR);
        offsets.put("SOLID_TILE_HORIZONTAL_MAP_ADDR", SOLID_TILE_HORIZONTAL_MAP_ADDR);
        offsets.put("SOLID_TILE_MAP_SIZE", SOLID_TILE_MAP_SIZE);
        offsets.put("SOLID_TILE_ANGLE_ADDR", SOLID_TILE_ANGLE_ADDR);
        offsets.put("SOLID_TILE_ANGLE_SIZE", SOLID_TILE_ANGLE_SIZE);
        offsets.put("LEVEL_BOUNDARIES_ADDR", LEVEL_BOUNDARIES_ADDR);
        offsets.put("MUSIC_PLAYLIST_ADDR", MUSIC_PLAYLIST_ADDR);
        offsets.put("ANIM_PAT_MAPS_ADDR", ANIM_PAT_MAPS_ADDR);
        offsets.put("ART_UNC_SONIC_ADDR", ART_UNC_SONIC_ADDR);
        offsets.put("ART_UNC_SONIC_SIZE", ART_UNC_SONIC_SIZE);
        offsets.put("ART_UNC_TAILS_ADDR", ART_UNC_TAILS_ADDR);
        offsets.put("ART_UNC_TAILS_SIZE", ART_UNC_TAILS_SIZE);
        offsets.put("MAP_UNC_SONIC_ADDR", MAP_UNC_SONIC_ADDR);
        offsets.put("MAP_R_UNC_SONIC_ADDR", MAP_R_UNC_SONIC_ADDR);
        offsets.put("MAP_UNC_TAILS_ADDR", MAP_UNC_TAILS_ADDR);
        offsets.put("MAP_R_UNC_TAILS_ADDR", MAP_R_UNC_TAILS_ADDR);
        offsets.put("ART_TILE_SONIC", ART_TILE_SONIC);
        offsets.put("ART_TILE_TAILS", ART_TILE_TAILS);
        offsets.put("ART_UNC_SPLASH_DUST_ADDR", ART_UNC_SPLASH_DUST_ADDR);
        offsets.put("ART_UNC_SPLASH_DUST_SIZE", ART_UNC_SPLASH_DUST_SIZE);
        offsets.put("MAP_UNC_OBJ08_ADDR", MAP_UNC_OBJ08_ADDR);
        offsets.put("MAP_R_UNC_OBJ08_ADDR", MAP_R_UNC_OBJ08_ADDR);
        offsets.put("ART_TILE_SONIC_DUST", ART_TILE_SONIC_DUST);
        offsets.put("ART_TILE_TAILS_DUST", ART_TILE_TAILS_DUST);
        offsets.put("ART_TILE_TAILS_TAILS", ART_TILE_TAILS_TAILS);
        offsets.put("ART_NEM_MONITOR_ADDR", ART_NEM_MONITOR_ADDR);
        offsets.put("ART_NEM_SONIC_LIFE_ADDR", ART_NEM_SONIC_LIFE_ADDR);
        offsets.put("ART_NEM_TAILS_LIFE_ADDR", ART_NEM_TAILS_LIFE_ADDR);
        offsets.put("ART_NEM_EXPLOSION_ADDR", ART_NEM_EXPLOSION_ADDR);
        offsets.put("ART_TILE_EXPLOSION", ART_TILE_EXPLOSION);
        offsets.put("ART_NEM_SPIKES_ADDR", ART_NEM_SPIKES_ADDR);
        offsets.put("ART_NEM_SPIKES_SIDE_ADDR", ART_NEM_SPIKES_SIDE_ADDR);
        offsets.put("ART_NEM_SPRING_VERTICAL_ADDR", ART_NEM_SPRING_VERTICAL_ADDR);
        offsets.put("ART_NEM_SPRING_HORIZONTAL_ADDR", ART_NEM_SPRING_HORIZONTAL_ADDR);
        offsets.put("ART_NEM_SPRING_DIAGONAL_ADDR", ART_NEM_SPRING_DIAGONAL_ADDR);
        offsets.put("MAP_UNC_MONITOR_ADDR", MAP_UNC_MONITOR_ADDR);
        offsets.put("MAP_UNC_SPIKES_ADDR", MAP_UNC_SPIKES_ADDR);
        offsets.put("MAP_UNC_SPRING_ADDR", MAP_UNC_SPRING_ADDR);
        offsets.put("MAP_UNC_SPRING_RED_ADDR", MAP_UNC_SPRING_RED_ADDR);
        offsets.put("ANI_OBJ26_ADDR", ANI_OBJ26_ADDR);
        offsets.put("ANI_OBJ26_SCRIPT_COUNT", ANI_OBJ26_SCRIPT_COUNT);
        offsets.put("ANI_OBJ41_ADDR", ANI_OBJ41_ADDR);
        offsets.put("ANI_OBJ41_SCRIPT_COUNT", ANI_OBJ41_SCRIPT_COUNT);
        offsets.put("SONIC_ANIM_DATA_ADDR", SONIC_ANIM_DATA_ADDR);
        offsets.put("SONIC_ANIM_SCRIPT_COUNT", SONIC_ANIM_SCRIPT_COUNT);
        offsets.put("TAILS_ANIM_DATA_ADDR", TAILS_ANIM_DATA_ADDR);
        offsets.put("TAILS_ANIM_SCRIPT_COUNT", TAILS_ANIM_SCRIPT_COUNT);
        offsets.put("ART_NEM_SHIELD_ADDR", ART_NEM_SHIELD_ADDR);
        offsets.put("ART_NEM_BRIDGE_ADDR", ART_NEM_BRIDGE_ADDR);
        offsets.put("ART_NEM_EHZ_WATERFALL_ADDR", ART_NEM_EHZ_WATERFALL_ADDR);
        offsets.put("ART_TILE_EHZ_WATERFALL", ART_TILE_EHZ_WATERFALL);
        offsets.put("CYCLING_PAL_EHZ_ARZ_WATER_ADDR", CYCLING_PAL_EHZ_ARZ_WATER_ADDR);
        offsets.put("CYCLING_PAL_EHZ_ARZ_WATER_LEN", CYCLING_PAL_EHZ_ARZ_WATER_LEN);
        offsets.put("CYCLING_PAL_CPZ1_ADDR", CYCLING_PAL_CPZ1_ADDR);
        offsets.put("CYCLING_PAL_CPZ1_LEN", CYCLING_PAL_CPZ1_LEN);
        offsets.put("CYCLING_PAL_CPZ2_ADDR", CYCLING_PAL_CPZ2_ADDR);
        offsets.put("CYCLING_PAL_CPZ2_LEN", CYCLING_PAL_CPZ2_LEN);
        offsets.put("CYCLING_PAL_CPZ3_ADDR", CYCLING_PAL_CPZ3_ADDR);
        offsets.put("CYCLING_PAL_CPZ3_LEN", CYCLING_PAL_CPZ3_LEN);
        offsets.put("CYCLING_PAL_WFZ_FIRE_ADDR", CYCLING_PAL_WFZ_FIRE_ADDR);
        offsets.put("CYCLING_PAL_WFZ_FIRE_LEN", CYCLING_PAL_WFZ_FIRE_LEN);
        offsets.put("CYCLING_PAL_WFZ_BELT_ADDR", CYCLING_PAL_WFZ_BELT_ADDR);
        offsets.put("CYCLING_PAL_WFZ_BELT_LEN", CYCLING_PAL_WFZ_BELT_LEN);
        offsets.put("CYCLING_PAL_WFZ1_ADDR", CYCLING_PAL_WFZ1_ADDR);
        offsets.put("CYCLING_PAL_WFZ1_LEN", CYCLING_PAL_WFZ1_LEN);
        offsets.put("CYCLING_PAL_WFZ2_ADDR", CYCLING_PAL_WFZ2_ADDR);
        offsets.put("CYCLING_PAL_WFZ2_LEN", CYCLING_PAL_WFZ2_LEN);
        offsets.put("CYCLING_PAL_LAVA_ADDR", CYCLING_PAL_LAVA_ADDR);
        offsets.put("CYCLING_PAL_LAVA_LEN", CYCLING_PAL_LAVA_LEN);
        offsets.put("CYCLING_PAL_MTZ1_ADDR", CYCLING_PAL_MTZ1_ADDR);
        offsets.put("CYCLING_PAL_MTZ1_LEN", CYCLING_PAL_MTZ1_LEN);
        offsets.put("CYCLING_PAL_MTZ2_ADDR", CYCLING_PAL_MTZ2_ADDR);
        offsets.put("CYCLING_PAL_MTZ2_LEN", CYCLING_PAL_MTZ2_LEN);
        offsets.put("CYCLING_PAL_MTZ3_ADDR", CYCLING_PAL_MTZ3_ADDR);
        offsets.put("CYCLING_PAL_MTZ3_LEN", CYCLING_PAL_MTZ3_LEN);
        offsets.put("CYCLING_PAL_OIL_ADDR", CYCLING_PAL_OIL_ADDR);
        offsets.put("CYCLING_PAL_OIL_LEN", CYCLING_PAL_OIL_LEN);
        offsets.put("CYCLING_PAL_LANTERN_ADDR", CYCLING_PAL_LANTERN_ADDR);
        offsets.put("CYCLING_PAL_LANTERN_LEN", CYCLING_PAL_LANTERN_LEN);
        offsets.put("CYCLING_PAL_CNZ1_ADDR", CYCLING_PAL_CNZ1_ADDR);
        offsets.put("CYCLING_PAL_CNZ1_LEN", CYCLING_PAL_CNZ1_LEN);
        offsets.put("CYCLING_PAL_CNZ3_ADDR", CYCLING_PAL_CNZ3_ADDR);
        offsets.put("CYCLING_PAL_CNZ3_LEN", CYCLING_PAL_CNZ3_LEN);
        offsets.put("CYCLING_PAL_CNZ4_ADDR", CYCLING_PAL_CNZ4_ADDR);
        offsets.put("CYCLING_PAL_CNZ4_LEN", CYCLING_PAL_CNZ4_LEN);
        offsets.put("ART_NEM_TITLE_ADDR", ART_NEM_TITLE_ADDR);
        offsets.put("MAP_ENI_TITLE_SCREEN_ADDR", MAP_ENI_TITLE_SCREEN_ADDR);
        offsets.put("MAP_ENI_TITLE_BACK_ADDR", MAP_ENI_TITLE_BACK_ADDR);
        offsets.put("MAP_ENI_TITLE_LOGO_ADDR", MAP_ENI_TITLE_LOGO_ADDR);
        offsets.put("TITLE_COPYRIGHT_TEXT_ADDR", TITLE_COPYRIGHT_TEXT_ADDR);
        offsets.put("PAL_TITLE_ADDR", PAL_TITLE_ADDR);
        offsets.put("ART_NEM_INVINCIBILITY_STARS_ADDR", ART_NEM_INVINCIBILITY_STARS_ADDR);
        offsets.put("MAP_UNC_INVINCIBILITY_STARS_ADDR", MAP_UNC_INVINCIBILITY_STARS_ADDR);
        offsets.put("ART_TILE_INVINCIBILITY_STARS", ART_TILE_INVINCIBILITY_STARS);
        offsets.put("MAP_UNC_OBJ18_A_ADDR", MAP_UNC_OBJ18_A_ADDR);
        offsets.put("MAP_UNC_OBJ18_B_ADDR", MAP_UNC_OBJ18_B_ADDR);
        offsets.put("MAP_UNC_OBJ23_ADDR", MAP_UNC_OBJ23_ADDR);
        offsets.put("MAP_UNC_OBJ2B_ADDR", MAP_UNC_OBJ2B_ADDR);
        offsets.put("MAP_UNC_OBJ82_ADDR", MAP_UNC_OBJ82_ADDR);
        offsets.put("MAP_UNC_OBJ83_ADDR", MAP_UNC_OBJ83_ADDR);
        offsets.put("ART_NEM_OOZ_SWING_PLAT_ADDR", ART_NEM_OOZ_SWING_PLAT_ADDR);
        offsets.put("ART_TILE_OOZ_SWING_PLAT", ART_TILE_OOZ_SWING_PLAT);
        offsets.put("MAP_UNC_OBJ15_A_ADDR", MAP_UNC_OBJ15_A_ADDR);
        offsets.put("MAP_UNC_OBJ15_MCZ_ADDR", MAP_UNC_OBJ15_MCZ_ADDR);
        offsets.put("MAP_UNC_OBJ15_TRAP_ADDR", MAP_UNC_OBJ15_TRAP_ADDR);
        offsets.put("ART_NEM_CHECKPOINT_ADDR", ART_NEM_CHECKPOINT_ADDR);
        offsets.put("MAP_UNC_CHECKPOINT_ADDR", MAP_UNC_CHECKPOINT_ADDR);
        offsets.put("MAP_UNC_CHECKPOINT_STAR_ADDR", MAP_UNC_CHECKPOINT_STAR_ADDR);
        offsets.put("ANI_OBJ79_ADDR", ANI_OBJ79_ADDR);
        offsets.put("ANI_OBJ79_SCRIPT_COUNT", ANI_OBJ79_SCRIPT_COUNT);
        offsets.put("ART_TILE_CHECKPOINT", ART_TILE_CHECKPOINT);
        offsets.put("ART_NEM_SIGNPOST_ADDR", ART_NEM_SIGNPOST_ADDR);
        offsets.put("ART_NEM_EGG_PRISON_ADDR", ART_NEM_EGG_PRISON_ADDR);
        offsets.put("MAP_UNC_EGG_PRISON_ADDR", MAP_UNC_EGG_PRISON_ADDR);
        offsets.put("ART_TILE_SIGNPOST", ART_TILE_SIGNPOST);
        offsets.put("ART_UNC_HUD_NUMBERS_ADDR", ART_UNC_HUD_NUMBERS_ADDR);
        offsets.put("ART_UNC_HUD_NUMBERS_SIZE", ART_UNC_HUD_NUMBERS_SIZE);
        offsets.put("ART_UNC_LIVES_NUMBERS_ADDR", ART_UNC_LIVES_NUMBERS_ADDR);
        offsets.put("ART_UNC_LIVES_NUMBERS_SIZE", ART_UNC_LIVES_NUMBERS_SIZE);
        offsets.put("ART_UNC_DEBUG_FONT_ADDR", ART_UNC_DEBUG_FONT_ADDR);
        offsets.put("ART_UNC_DEBUG_FONT_SIZE", ART_UNC_DEBUG_FONT_SIZE);
        offsets.put("ART_NEM_HUD_ADDR", ART_NEM_HUD_ADDR);
        offsets.put("ART_NEM_TITLE_CARD_ADDR", ART_NEM_TITLE_CARD_ADDR);
        offsets.put("ART_NEM_TITLE_CARD2_ADDR", ART_NEM_TITLE_CARD2_ADDR);
        offsets.put("ART_NEM_MENU_BOX_ADDR", ART_NEM_MENU_BOX_ADDR);
        offsets.put("ART_NEM_LEVEL_SELECT_PICS_ADDR", ART_NEM_LEVEL_SELECT_PICS_ADDR);
        offsets.put("ART_NEM_FONT_STUFF_ADDR", ART_NEM_FONT_STUFF_ADDR);
        offsets.put("MAP_ENI_LEVEL_SELECT_ADDR", MAP_ENI_LEVEL_SELECT_ADDR);
        offsets.put("MAP_ENI_LEVEL_SELECT_ICON_ADDR", MAP_ENI_LEVEL_SELECT_ICON_ADDR);
        offsets.put("ART_UNC_MENU_BACK_ADDR", ART_UNC_MENU_BACK_ADDR);
        offsets.put("ART_UNC_MENU_BACK_SIZE", ART_UNC_MENU_BACK_SIZE);
        offsets.put("MAP_ENI_MENU_BACK_ADDR", MAP_ENI_MENU_BACK_ADDR);
        offsets.put("MAP_ENI_MENU_BACK_SIZE", MAP_ENI_MENU_BACK_SIZE);
        offsets.put("PAL_MENU_ADDR", PAL_MENU_ADDR);
        offsets.put("PAL_MENU_SIZE", PAL_MENU_SIZE);
        offsets.put("PAL_LEVEL_ICONS_ADDR", PAL_LEVEL_ICONS_ADDR);
        offsets.put("PAL_LEVEL_ICONS_SIZE", PAL_LEVEL_ICONS_SIZE);
        offsets.put("ART_NEM_RESULTS_TEXT_ADDR", ART_NEM_RESULTS_TEXT_ADDR);
        offsets.put("ART_NEM_MINI_SONIC_ADDR", ART_NEM_MINI_SONIC_ADDR);
        offsets.put("ART_NEM_PERFECT_ADDR", ART_NEM_PERFECT_ADDR);
        offsets.put("MAPPINGS_EOL_TITLE_CARDS_ADDR", MAPPINGS_EOL_TITLE_CARDS_ADDR);
        offsets.put("VRAM_BASE_NUMBERS", VRAM_BASE_NUMBERS);
        offsets.put("VRAM_BASE_PERFECT", VRAM_BASE_PERFECT);
        offsets.put("VRAM_BASE_TITLE_CARD", VRAM_BASE_TITLE_CARD);
        offsets.put("VRAM_BASE_RESULTS_TEXT", VRAM_BASE_RESULTS_TEXT);
        offsets.put("VRAM_BASE_MINI_CHARACTER", VRAM_BASE_MINI_CHARACTER);
        offsets.put("VRAM_BASE_HUD_TEXT", VRAM_BASE_HUD_TEXT);
        offsets.put("RESULTS_BONUS_DIGIT_TILES", RESULTS_BONUS_DIGIT_TILES);
        offsets.put("RESULTS_BONUS_DIGIT_GROUP_TILES", RESULTS_BONUS_DIGIT_GROUP_TILES);
        offsets.put("ART_NEM_NUMBERS_ADDR", ART_NEM_NUMBERS_ADDR);
        offsets.put("ART_NEM_BUZZER_ADDR", ART_NEM_BUZZER_ADDR);
        offsets.put("ART_NEM_MASHER_ADDR", ART_NEM_MASHER_ADDR);
        offsets.put("ART_NEM_COCONUTS_ADDR", ART_NEM_COCONUTS_ADDR);
        offsets.put("ART_NEM_ANIMAL_ADDR", ART_NEM_ANIMAL_ADDR);
        offsets.put("ART_NEM_MTZ_SUPERNOVA_ADDR", ART_NEM_MTZ_SUPERNOVA_ADDR);
        offsets.put("ART_NEM_MTZ_MANTIS_ADDR", ART_NEM_MTZ_MANTIS_ADDR);
        offsets.put("ART_NEM_SHELLCRACKER_ADDR", ART_NEM_SHELLCRACKER_ADDR);
        offsets.put("ART_NEM_SPINY_ADDR", ART_NEM_SPINY_ADDR);
        offsets.put("ART_NEM_GRABBER_ADDR", ART_NEM_GRABBER_ADDR);
        offsets.put("ART_NEM_CHOPCHOP_ADDR", ART_NEM_CHOPCHOP_ADDR);
        offsets.put("ART_NEM_WHISP_ADDR", ART_NEM_WHISP_ADDR);
        offsets.put("ART_NEM_GROUNDER_ADDR", ART_NEM_GROUNDER_ADDR);
        offsets.put("ART_NEM_SPIKER_ADDR", ART_NEM_SPIKER_ADDR);
        offsets.put("ART_NEM_REXON_ADDR", ART_NEM_REXON_ADDR);
        offsets.put("ART_NEM_OCTUS_ADDR", ART_NEM_OCTUS_ADDR);
        offsets.put("ART_NEM_AQUIS_ADDR", ART_NEM_AQUIS_ADDR);
        offsets.put("ART_NEM_OOZ_BOSS_ADDR", ART_NEM_OOZ_BOSS_ADDR);
        offsets.put("ART_NEM_CRAWL_ADDR", ART_NEM_CRAWL_ADDR);
        offsets.put("ART_NEM_ARROW_SHOOTER_ADDR", ART_NEM_ARROW_SHOOTER_ADDR);
        offsets.put("MAP_UNC_ARROW_SHOOTER_ADDR", MAP_UNC_ARROW_SHOOTER_ADDR);
        offsets.put("ART_TILE_ARROW_SHOOTER", ART_TILE_ARROW_SHOOTER);
        offsets.put("ART_NEM_EGGPOD_ADDR", ART_NEM_EGGPOD_ADDR);
        offsets.put("ART_NEM_EHZ_BOSS_ADDR", ART_NEM_EHZ_BOSS_ADDR);
        offsets.put("ART_NEM_CNZ_BOSS_ADDR", ART_NEM_CNZ_BOSS_ADDR);
        offsets.put("ART_NEM_EGG_CHOPPERS_ADDR", ART_NEM_EGG_CHOPPERS_ADDR);
        offsets.put("ART_NEM_CPZ_BOSS_ADDR", ART_NEM_CPZ_BOSS_ADDR);
        offsets.put("ART_NEM_EGGPOD_JETS_ADDR", ART_NEM_EGGPOD_JETS_ADDR);
        offsets.put("ART_NEM_BOSS_SMOKE_ADDR", ART_NEM_BOSS_SMOKE_ADDR);
        offsets.put("ART_NEM_FIERY_EXPLOSION_ADDR", ART_NEM_FIERY_EXPLOSION_ADDR);
        offsets.put("ART_NEM_ARZ_BOSS_ADDR", ART_NEM_ARZ_BOSS_ADDR);
        offsets.put("ART_TILE_EGGPOD", ART_TILE_EGGPOD);
        offsets.put("ART_TILE_EHZ_BOSS", ART_TILE_EHZ_BOSS);
        offsets.put("ART_TILE_EGG_CHOPPERS", ART_TILE_EGG_CHOPPERS);
        offsets.put("ART_TILE_EGGPOD_3", ART_TILE_EGGPOD_3);
        offsets.put("ART_TILE_EGGPOD_JETS_1", ART_TILE_EGGPOD_JETS_1);
        offsets.put("ART_TILE_CPZ_BOSS", ART_TILE_CPZ_BOSS);
        offsets.put("ART_TILE_BOSS_SMOKE_1", ART_TILE_BOSS_SMOKE_1);
        offsets.put("ART_TILE_FIERY_EXPLOSION", ART_TILE_FIERY_EXPLOSION);
        offsets.put("ART_TILE_ARZ_BOSS", ART_TILE_ARZ_BOSS);
        offsets.put("ART_TILE_EGGPOD_4", ART_TILE_EGGPOD_4);
        offsets.put("ART_TILE_CNZ_BOSS", ART_TILE_CNZ_BOSS);
        offsets.put("ART_TILE_CNZ_BOSS_FUDGE", ART_TILE_CNZ_BOSS_FUDGE);
        offsets.put("ART_TILE_OOZ_BOSS", ART_TILE_OOZ_BOSS);
        offsets.put("ART_NEM_MCZ_BOSS_ADDR", ART_NEM_MCZ_BOSS_ADDR);
        offsets.put("ART_UNC_FALLING_ROCKS_ADDR", ART_UNC_FALLING_ROCKS_ADDR);
        offsets.put("MAP_UNC_MCZ_BOSS_ADDR", MAP_UNC_MCZ_BOSS_ADDR);
        offsets.put("PAL_MCZ_BOSS_ADDR", PAL_MCZ_BOSS_ADDR);
        offsets.put("PAL_CNZ_BOSS_ADDR", PAL_CNZ_BOSS_ADDR);
        offsets.put("PAL_OOZ_BOSS_ADDR", PAL_OOZ_BOSS_ADDR);
        offsets.put("MAP_UNC_CNZ_BOSS_ADDR", MAP_UNC_CNZ_BOSS_ADDR);
        offsets.put("CYCLING_PAL_CNZ_BOSS1_ADDR", CYCLING_PAL_CNZ_BOSS1_ADDR);
        offsets.put("CYCLING_PAL_CNZ_BOSS1_LEN", CYCLING_PAL_CNZ_BOSS1_LEN);
        offsets.put("CYCLING_PAL_CNZ_BOSS2_ADDR", CYCLING_PAL_CNZ_BOSS2_ADDR);
        offsets.put("CYCLING_PAL_CNZ_BOSS2_LEN", CYCLING_PAL_CNZ_BOSS2_LEN);
        offsets.put("CYCLING_PAL_CNZ_BOSS3_ADDR", CYCLING_PAL_CNZ_BOSS3_ADDR);
        offsets.put("CYCLING_PAL_CNZ_BOSS3_LEN", CYCLING_PAL_CNZ_BOSS3_LEN);
        offsets.put("MAP_UNC_CPZ_BOSS_PARTS_ADDR", MAP_UNC_CPZ_BOSS_PARTS_ADDR);
        offsets.put("MAP_UNC_CPZ_BOSS_EGGPOD_ADDR", MAP_UNC_CPZ_BOSS_EGGPOD_ADDR);
        offsets.put("MAP_UNC_CPZ_BOSS_JETS_ADDR", MAP_UNC_CPZ_BOSS_JETS_ADDR);
        offsets.put("MAP_UNC_CPZ_BOSS_SMOKE_ADDR", MAP_UNC_CPZ_BOSS_SMOKE_ADDR);
        offsets.put("MAP_UNC_BOSS_EXPLOSION_ADDR", MAP_UNC_BOSS_EXPLOSION_ADDR);
        offsets.put("MAP_UNC_ARZ_BOSS_PARTS_ADDR", MAP_UNC_ARZ_BOSS_PARTS_ADDR);
        offsets.put("MAP_UNC_ARZ_BOSS_MAIN_ADDR", MAP_UNC_ARZ_BOSS_MAIN_ADDR);
        offsets.put("MAP_UNC_OOZ_BOSS_ADDR", MAP_UNC_OOZ_BOSS_ADDR);
        offsets.put("ART_NEM_FLICKY_ADDR", ART_NEM_FLICKY_ADDR);
        offsets.put("ART_NEM_SQUIRREL_ADDR", ART_NEM_SQUIRREL_ADDR);
        offsets.put("ART_NEM_MOUSE_ADDR", ART_NEM_MOUSE_ADDR);
        offsets.put("ART_NEM_CHICKEN_ADDR", ART_NEM_CHICKEN_ADDR);
        offsets.put("ART_NEM_MONKEY_ADDR", ART_NEM_MONKEY_ADDR);
        offsets.put("ART_NEM_EAGLE_ADDR", ART_NEM_EAGLE_ADDR);
        offsets.put("ART_NEM_PIG_ADDR", ART_NEM_PIG_ADDR);
        offsets.put("ART_NEM_SEAL_ADDR", ART_NEM_SEAL_ADDR);
        offsets.put("ART_NEM_PENGUIN_ADDR", ART_NEM_PENGUIN_ADDR);
        offsets.put("ART_NEM_TURTLE_ADDR", ART_NEM_TURTLE_ADDR);
        offsets.put("ART_NEM_BEAR_ADDR", ART_NEM_BEAR_ADDR);
        offsets.put("ART_NEM_RABBIT_ADDR", ART_NEM_RABBIT_ADDR);
        offsets.put("ART_NEM_LEVER_SPRING_ADDR", ART_NEM_LEVER_SPRING_ADDR);
        offsets.put("ART_NEM_HEX_BUMPER_ADDR", ART_NEM_HEX_BUMPER_ADDR);
        offsets.put("ART_NEM_BUMPER_ADDR", ART_NEM_BUMPER_ADDR);
        offsets.put("ART_NEM_BONUS_BLOCK_ADDR", ART_NEM_BONUS_BLOCK_ADDR);
        offsets.put("CNZ_BUMPERS_ACT1_ADDR", CNZ_BUMPERS_ACT1_ADDR);
        offsets.put("CNZ_BUMPERS_ACT2_ADDR", CNZ_BUMPERS_ACT2_ADDR);
        offsets.put("ZONE_CNZ", ZONE_CNZ);
        offsets.put("ART_NEM_FLIPPER_ADDR", ART_NEM_FLIPPER_ADDR);
        offsets.put("ART_NEM_CNZ_SNAKE_ADDR", ART_NEM_CNZ_SNAKE_ADDR);
        offsets.put("ART_NEM_CNZ_BIG_BLOCK_ADDR", ART_NEM_CNZ_BIG_BLOCK_ADDR);
        offsets.put("ART_NEM_CNZ_ELEVATOR_ADDR", ART_NEM_CNZ_ELEVATOR_ADDR);
        offsets.put("ART_NEM_CNZ_CAGE_ADDR", ART_NEM_CNZ_CAGE_ADDR);
        offsets.put("ART_NEM_CNZ_BONUS_SPIKE_ADDR", ART_NEM_CNZ_BONUS_SPIKE_ADDR);
        offsets.put("ART_UNC_CNZ_SLOT_PICS_ADDR", ART_UNC_CNZ_SLOT_PICS_ADDR);
        offsets.put("ART_UNC_CNZ_SLOT_PICS_SIZE", ART_UNC_CNZ_SLOT_PICS_SIZE);
        offsets.put("ART_NEM_CNZ_VERT_PLUNGER_ADDR", ART_NEM_CNZ_VERT_PLUNGER_ADDR);
        offsets.put("ART_NEM_CNZ_DIAG_PLUNGER_ADDR", ART_NEM_CNZ_DIAG_PLUNGER_ADDR);
        offsets.put("ART_TILE_CNZ_VERT_PLUNGER", ART_TILE_CNZ_VERT_PLUNGER);
        offsets.put("ART_TILE_CNZ_DIAG_PLUNGER", ART_TILE_CNZ_DIAG_PLUNGER);
        offsets.put("ART_NEM_WATER_SURFACE_CPZ_ADDR", ART_NEM_WATER_SURFACE_CPZ_ADDR);
        offsets.put("ART_NEM_WATER_SURFACE_ARZ_ADDR", ART_NEM_WATER_SURFACE_ARZ_ADDR);
        offsets.put("ART_NEM_BUBBLES_ADDR", ART_NEM_BUBBLES_ADDR);
        offsets.put("ART_NEM_BUBBLE_GENERATOR_ADDR", ART_NEM_BUBBLE_GENERATOR_ADDR);
        offsets.put("ART_UNC_COUNTDOWN_ADDR", ART_UNC_COUNTDOWN_ADDR);
        offsets.put("MAP_UNC_SMALL_BUBBLES_ADDR", MAP_UNC_SMALL_BUBBLES_ADDR);
        offsets.put("MAP_UNC_BUBBLES_ADDR", MAP_UNC_BUBBLES_ADDR);
        offsets.put("ART_TILE_BUBBLES", ART_TILE_BUBBLES);
        offsets.put("ART_NEM_LEAVES_ADDR", ART_NEM_LEAVES_ADDR);
        offsets.put("ART_NEM_SPEED_BOOSTER_ADDR", ART_NEM_SPEED_BOOSTER_ADDR);
        offsets.put("MAP_UNC_SPEED_BOOSTER_ADDR", MAP_UNC_SPEED_BOOSTER_ADDR);
        offsets.put("ART_NEM_PIPE_EXIT_SPRING_ADDR", ART_NEM_PIPE_EXIT_SPRING_ADDR);
        offsets.put("ART_NEM_CPZ_ANIMATED_BITS_ADDR", ART_NEM_CPZ_ANIMATED_BITS_ADDR);
        offsets.put("ART_NEM_CONSTRUCTION_STRIPES_ADDR", ART_NEM_CONSTRUCTION_STRIPES_ADDR);
        offsets.put("ART_NEM_ARZ_BARRIER_ADDR", ART_NEM_ARZ_BARRIER_ADDR);
        offsets.put("ART_NEM_HTZ_VALVE_BARRIER_ADDR", ART_NEM_HTZ_VALVE_BARRIER_ADDR);
        offsets.put("MAP_UNC_BARRIER_ADDR", MAP_UNC_BARRIER_ADDR);
        offsets.put("ART_NEM_CPZ_DROPLET_ADDR", ART_NEM_CPZ_DROPLET_ADDR);
        offsets.put("ART_NEM_CPZ_METAL_BLOCK_ADDR", ART_NEM_CPZ_METAL_BLOCK_ADDR);
        offsets.put("ART_NEM_HTZ_ROCK_ADDR", ART_NEM_HTZ_ROCK_ADDR);
        offsets.put("MAP_UNC_OBJ32_HTZ_ADDR", MAP_UNC_OBJ32_HTZ_ADDR);
        offsets.put("MAP_UNC_OBJ32_CPZ_ADDR", MAP_UNC_OBJ32_CPZ_ADDR);
        offsets.put("ART_NEM_HTZ_CLIFFS_ADDR", ART_NEM_HTZ_CLIFFS_ADDR);
        offsets.put("ART_UNC_HTZ_CLOUDS_ADDR", ART_UNC_HTZ_CLOUDS_ADDR);
        offsets.put("ART_UNC_HTZ_CLOUDS_SIZE", ART_UNC_HTZ_CLOUDS_SIZE);
        offsets.put("MAP_UNC_OBJ19_ADDR", MAP_UNC_OBJ19_ADDR);
        offsets.put("ART_NEM_CPZ_ELEVATOR_ADDR", ART_NEM_CPZ_ELEVATOR_ADDR);
        offsets.put("ART_NEM_OOZ_ELEVATOR_ADDR", ART_NEM_OOZ_ELEVATOR_ADDR);
        offsets.put("ART_NEM_MTZ_COG_ADDR", ART_NEM_MTZ_COG_ADDR);
        offsets.put("ART_NEM_MTZ_STEAM_ADDR", ART_NEM_MTZ_STEAM_ADDR);
        offsets.put("ART_NEM_MTZ_WHEEL_INDENT_ADDR", ART_NEM_MTZ_WHEEL_INDENT_ADDR);
        offsets.put("ART_NEM_MTZ_SPIN_TUBE_FLASH_ADDR", ART_NEM_MTZ_SPIN_TUBE_FLASH_ADDR);
        offsets.put("ART_NEM_MTZ_WHEEL_ADDR", ART_NEM_MTZ_WHEEL_ADDR);
        offsets.put("MAP_UNC_OBJ6E_ADDR", MAP_UNC_OBJ6E_ADDR);
        offsets.put("MAP_UNC_OBJ70_ADDR", MAP_UNC_OBJ70_ADDR);
        offsets.put("ART_NEM_CPZ_STAIRBLOCK_ADDR", ART_NEM_CPZ_STAIRBLOCK_ADDR);
        offsets.put("ART_NEM_CPZ_METAL_THINGS_ADDR", ART_NEM_CPZ_METAL_THINGS_ADDR);
        offsets.put("ART_NEM_WFZ_PLATFORM_ADDR", ART_NEM_WFZ_PLATFORM_ADDR);
        offsets.put("ART_NEM_TORNADO_ADDR", ART_NEM_TORNADO_ADDR);
        offsets.put("ART_NEM_TORNADO_THRUSTER_ADDR", ART_NEM_TORNADO_THRUSTER_ADDR);
        offsets.put("ART_NEM_WFZ_THRUST_ADDR", ART_NEM_WFZ_THRUST_ADDR);
        offsets.put("MAP_UNC_OBJB2_A_ADDR", MAP_UNC_OBJB2_A_ADDR);
        offsets.put("MAP_UNC_OBJB2_B_ADDR", MAP_UNC_OBJB2_B_ADDR);
        offsets.put("MAP_UNC_OBJBC_ADDR", MAP_UNC_OBJBC_ADDR);
        offsets.put("ART_TILE_CPZ_ELEVATOR", ART_TILE_CPZ_ELEVATOR);
        offsets.put("ART_TILE_OOZ_ELEVATOR", ART_TILE_OOZ_ELEVATOR);
        offsets.put("ART_TILE_WFZ_PLATFORM", ART_TILE_WFZ_PLATFORM);
        offsets.put("ZONE_CHEMICAL_PLANT", ZONE_CHEMICAL_PLANT);
        offsets.put("ZONE_OIL_OCEAN", ZONE_OIL_OCEAN);
        offsets.put("ZONE_WING_FORTRESS", ZONE_WING_FORTRESS);
        offsets.put("ZONE_MYSTIC_CAVE", ZONE_MYSTIC_CAVE);
        offsets.put("ZONE_ARZ", ZONE_ARZ);
        offsets.put("ART_NEM_BURNER_LID_ADDR", ART_NEM_BURNER_LID_ADDR);
        offsets.put("ART_NEM_OOZ_BURN_ADDR", ART_NEM_OOZ_BURN_ADDR);
        offsets.put("ART_NEM_STRIPED_BLOCKS_VERT_ADDR", ART_NEM_STRIPED_BLOCKS_VERT_ADDR);
        offsets.put("ART_NEM_STRIPED_BLOCKS_HORIZ_ADDR", ART_NEM_STRIPED_BLOCKS_HORIZ_ADDR);
        offsets.put("ART_NEM_OOZ_FAN_ADDR", ART_NEM_OOZ_FAN_ADDR);
        offsets.put("ART_NEM_LAUNCH_BALL_ADDR", ART_NEM_LAUNCH_BALL_ADDR);
        offsets.put("ART_NEM_OOZ_COLLAPSING_PLATFORM_ADDR", ART_NEM_OOZ_COLLAPSING_PLATFORM_ADDR);
        offsets.put("ART_NEM_MCZ_COLLAPSING_PLATFORM_ADDR", ART_NEM_MCZ_COLLAPSING_PLATFORM_ADDR);
        offsets.put("HTZ_PATTERNS_BASE_ADDR", HTZ_PATTERNS_BASE_ADDR);
        offsets.put("HTZ_PATTERNS_OVERLAY_ADDR", HTZ_PATTERNS_OVERLAY_ADDR);
        offsets.put("HTZ_PATTERNS_OVERLAY_OFFSET", HTZ_PATTERNS_OVERLAY_OFFSET);
        offsets.put("HTZ_CHUNKS_BASE_ADDR", HTZ_CHUNKS_BASE_ADDR);
        offsets.put("HTZ_CHUNKS_OVERLAY_ADDR", HTZ_CHUNKS_OVERLAY_ADDR);
        offsets.put("HTZ_CHUNKS_OVERLAY_OFFSET", HTZ_CHUNKS_OVERLAY_OFFSET);
        offsets.put("HTZ_BLOCKS_ADDR", HTZ_BLOCKS_ADDR);
        offsets.put("HTZ_COLLISION_PRIMARY_ADDR", HTZ_COLLISION_PRIMARY_ADDR);
        offsets.put("HTZ_COLLISION_SECONDARY_ADDR", HTZ_COLLISION_SECONDARY_ADDR);
        offsets.put("ZONE_HTZ", ZONE_HTZ);
        offsets.put("ART_NEM_HTZ_SEESAW_ADDR", ART_NEM_HTZ_SEESAW_ADDR);
        offsets.put("ART_NEM_SOL_ADDR", ART_NEM_SOL_ADDR);
        offsets.put("ART_NEM_HTZ_FIREBALL1_ADDR", ART_NEM_HTZ_FIREBALL1_ADDR);
        offsets.put("ART_NEM_HTZ_ZIPLINE_ADDR", ART_NEM_HTZ_ZIPLINE_ADDR);
        offsets.put("HTZ_MOUNTAINS_TILE_INDEX", HTZ_MOUNTAINS_TILE_INDEX);
        offsets.put("HTZ_MOUNTAINS_TILE_COUNT", HTZ_MOUNTAINS_TILE_COUNT);
        offsets.put("HTZ_CLOUDS_TILE_INDEX", HTZ_CLOUDS_TILE_INDEX);
        offsets.put("HTZ_CLOUDS_TILE_COUNT", HTZ_CLOUDS_TILE_COUNT);
        offsets.put("HTZ_DYNAMIC_TILES_END", HTZ_DYNAMIC_TILES_END);
        offsets.put("ART_NEM_BALKIRY_ADDR", ART_NEM_BALKIRY_ADDR);
        offsets.put("ART_NEM_NEBULA_ADDR", ART_NEM_NEBULA_ADDR);
        offsets.put("ART_NEM_TURTLOID_ADDR", ART_NEM_TURTLOID_ADDR);
        offsets.put("ART_NEM_CLOUDS_ADDR", ART_NEM_CLOUDS_ADDR);
        offsets.put("ART_NEM_WFZ_HRZNTL_PRPLLR_ADDR", ART_NEM_WFZ_HRZNTL_PRPLLR_ADDR);
        offsets.put("ART_NEM_WFZ_WALL_TURRET_ADDR", ART_NEM_WFZ_WALL_TURRET_ADDR);
        offsets.put("MAP_UNC_WFZ_WALL_TURRET_ADDR", MAP_UNC_WFZ_WALL_TURRET_ADDR);
        offsets.put("ART_TILE_WFZ_WALL_TURRET", ART_TILE_WFZ_WALL_TURRET);
        offsets.put("ART_NEM_WFZ_GUN_PLATFORM_ADDR", ART_NEM_WFZ_GUN_PLATFORM_ADDR);
        offsets.put("MAP_UNC_OBJBE_ADDR", MAP_UNC_OBJBE_ADDR);
        offsets.put("ART_LOAD_CUES_ADDR", ART_LOAD_CUES_ADDR);
        offsets.put("ART_LOAD_CUES_ENTRY_COUNT", ART_LOAD_CUES_ENTRY_COUNT);

        // Ending Sequence & Credits
        offsets.put("ART_NEM_ENDING_SONIC_ADDR", ART_NEM_ENDING_SONIC_ADDR);
        offsets.put("ART_NEM_ENDING_SUPER_SONIC_ADDR", ART_NEM_ENDING_SUPER_SONIC_ADDR);
        offsets.put("ART_NEM_ENDING_TAILS_ADDR", ART_NEM_ENDING_TAILS_ADDR);
        offsets.put("ART_NEM_ENDING_FINAL_TORNADO_ADDR", ART_NEM_ENDING_FINAL_TORNADO_ADDR);
        offsets.put("ART_NEM_ENDING_PICS_ADDR", ART_NEM_ENDING_PICS_ADDR);
        offsets.put("ART_NEM_ENDING_MINI_TORNADO_ADDR", ART_NEM_ENDING_MINI_TORNADO_ADDR);
        offsets.put("ART_NEM_ENDING_TITLE_ADDR", ART_NEM_ENDING_TITLE_ADDR);
        offsets.put("MAP_ENG_ENDING1_ADDR", MAP_ENG_ENDING1_ADDR);
        offsets.put("MAP_ENG_ENDING2_ADDR", MAP_ENG_ENDING2_ADDR);
        offsets.put("MAP_ENG_ENDING3_ADDR", MAP_ENG_ENDING3_ADDR);
        offsets.put("MAP_ENG_ENDING4_ADDR", MAP_ENG_ENDING4_ADDR);
        offsets.put("MAP_ENG_ENDING_TAILS_PLANE_ADDR", MAP_ENG_ENDING_TAILS_PLANE_ADDR);
        offsets.put("MAP_ENG_ENDING_SONIC_PLANE_ADDR", MAP_ENG_ENDING_SONIC_PLANE_ADDR);
        offsets.put("MAP_ENG_END_GAME_LOGO_ADDR", MAP_ENG_END_GAME_LOGO_ADDR);
        offsets.put("PAL_ENDING_SONIC_ADDR", PAL_ENDING_SONIC_ADDR);
        offsets.put("PAL_ENDING_TAILS_ADDR", PAL_ENDING_TAILS_ADDR);
        offsets.put("PAL_ENDING_BACKGROUND_ADDR", PAL_ENDING_BACKGROUND_ADDR);
        offsets.put("PAL_ENDING_PHOTOS_ADDR", PAL_ENDING_PHOTOS_ADDR);
        offsets.put("PAL_ENDING_SUPER_SONIC_ADDR", PAL_ENDING_SUPER_SONIC_ADDR);
        offsets.put("LOGO_FLASH_STROBE_ADDR", LOGO_FLASH_STROBE_ADDR);
        offsets.put("PAL_ENDING_CYCLE_ADDR", PAL_ENDING_CYCLE_ADDR);
        offsets.put("CREDITS_SCREEN_TABLE_ADDR", CREDITS_SCREEN_TABLE_ADDR);
        offsets.put("INTRO_TEXT_TABLE_ADDR", INTRO_TEXT_TABLE_ADDR);
        offsets.put("MAP_UNC_OBJCF_ADDR", MAP_UNC_OBJCF_ADDR);
        offsets.put("MAP_UNC_OBJ28_A_ADDR", MAP_UNC_OBJ28_A_ADDR);

        return offsets;
    }
}
