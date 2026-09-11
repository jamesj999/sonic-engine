package com.openggf.game.sonic1.constants;

/**
 * ROM addresses and constants for Sonic the Hedgehog 1 (Mega Drive/Genesis).
 * Addresses are for the standard REV00/REV01 ROM.
 */
public final class Sonic1Constants {

    private Sonic1Constants() {
    }

    // ---- Zone IDs (from s1disasm) ----
    public static final int ZONE_GHZ  = 0x00; // Green Hill Zone
    public static final int ZONE_LZ   = 0x01; // Labyrinth Zone
    public static final int ZONE_MZ   = 0x02; // Marble Zone
    public static final int ZONE_SLZ  = 0x03; // Star Light Zone
    public static final int ZONE_SYZ  = 0x04; // Spring Yard Zone
    public static final int ZONE_SBZ  = 0x05; // Scrap Brain Zone
    public static final int ZONE_ENDZ = 0x06; // Ending / Final Zone
    public static final int ZONE_SS   = 0x07; // Special Stage

    // ---- Level data ----
    public static final int LEVEL_HEADERS_ADDR    = 0x1DD16; // 16 bytes per zone header
    public static final int LEVEL_INDEX_ADDR      = 0x68B96; // Layout offset table
    public static final int OBJ_POS_INDEX_ADDR    = 0x6B000; // Object placement index
    public static final int OBJ_POS_LZ_PLATFORM_INDEX_ADDR = OBJ_POS_INDEX_ADDR + 0x70;
    public static final int OBJ_POS_SBZ_PLATFORM_INDEX_ADDR = OBJ_POS_INDEX_ADDR + 0x80;
    public static final int LZ_CONVEYOR_PATH_TABLE_ADDR = 0x12CBC; // LCon_Data pointer table
    public static final int SBZ_SPIN_CONVEYOR_PATH_TABLE_ADDR = 0x16C0E; // SpinC_Data pointer table
    public static final int START_LOC_ARRAY_ADDR  = 0x0611E; // Start positions (4 bytes per act)

    // ---- Level size / boundary array ----
    // LevelSizeArray from disassembly - contains camera boundaries per act
    // Each entry: 6 words (12 bytes) = unused, left, right, top, bottom, vshift
    // 4 act slots per zone, 7 zones (GHZ..Ending)
    public static final int LEVEL_SIZE_ARRAY_ADDR = 0x05F2A;

    // ---- Collision data ----
    public static final int COLLISION_ARRAY_NORMAL_ADDR  = 0x62A00; // CollArray1 (0x1000 bytes)
    public static final int COLLISION_ARRAY_ROTATED_ADDR = 0x63A00; // CollArray2 (0x1000 bytes)
    public static final int ANGLE_MAP_ADDR               = 0x62900; // AngleMap (0x100 bytes)

    // Per-zone collision index addresses (raw binary)
    public static final int COL_GHZ_ADDR = 0x64A00;
    public static final int COL_LZ_ADDR  = 0x64B9A;
    public static final int COL_MZ_ADDR  = 0x64C62;
    public static final int COL_SLZ_ADDR = 0x64DF2;
    public static final int COL_SYZ_ADDR = 0x64FE6;
    public static final int COL_SBZ_ADDR = 0x651DA;

    // ---- Palette data ----
    // PalPointers table: 8 bytes per entry (dc.l addr, dc.w ramDest, dc.w count-1)
    public static final int PALETTE_TABLE_ADDR = 0x2168;
    public static final int SONIC_PALETTE_ADDR = 0x23A0; // Pal_Sonic (palette ID 3)
    public static final int GHZ_PALETTE_ADDR   = 0x2400; // palid_GHZ = 4

    // ---- Pattern Load Cues (PLC) ----
    // ArtLoadCues pointer table: word offsets to PLC lists, indexed by PLC ID.
    // Each PLC list starts with a word (entry_count - 1), followed by 6-byte entries:
    //   dc.l rom_address, dc.w vram_byte_offset
    // Divide vram_byte_offset by 0x20 to get tile index.
    public static final int ART_LOAD_CUES_ADDR = 0x01DD86;
    public static final int ART_LOAD_CUES_ENTRY_COUNT = 32;

    // Per-zone art addresses (patterns, 16x16, 256x256) are read dynamically
    // from LevelHeaders at runtime - no need for per-zone constants here.

    // ---- Block / tile sizes (Sonic 1 uses 256x256 blocks, not 128x128) ----
    public static final int BLOCK_WIDTH_PX  = 256;
    public static final int BLOCK_HEIGHT_PX = 256;
    public static final int CHUNKS_PER_BLOCK_SIDE = 16; // 16x16 chunks in a 256x256 block
    public static final int CHUNK_SIZE_PX   = 16;       // Each chunk is 16x16 pixels
    public static final int PATTERN_SIZE_PX = 8;        // Each pattern is 8x8 pixels

    // Solid tile data sizes
    public static final int SOLID_TILE_MAP_SIZE   = 0x1000;
    public static final int SOLID_TILE_ANGLE_SIZE = 0x100;

    // ---- Player sprite data (REV01) ----
    // Sonic's uncompressed art tiles (8x8 patterns)
    public static final int ART_UNC_SONIC_ADDR = 0x22610;
    public static final int ART_UNC_SONIC_SIZE = 0xA120; // 41248 bytes = 1289 patterns

    // Sonic's sprite mappings (S1 format: 5 bytes per piece, byte header)
    public static final int MAP_SONIC_ADDR = 0x21CF4;

    // Sonic's Dynamic Pattern Load Cues (S1 format: byte header)
    public static final int DPLC_SONIC_ADDR = 0x22310;

    // ---- Object sprite mapping addresses (S1 format, verified via ROM binary pattern search) ----
    public static final int MAP_LAMPPOST_ADDR    = 0x0178A4; // Map_Lamp (Obj79: 4 frames)
    public static final int MAP_SIGNPOST_ADDR    = 0x00F3C4; // Map_Sign (Obj0D: 5 frames)
    public static final int MAP_END_EMERALDS_ADDR = 0x005788; // Map_ECha (Obj88: 7 frames)
    public static final int MAP_END_SONIC_ADDR   = 0x00568E; // Map_ESon (Obj87: 8 frames)
    public static final int MAP_END_STH_ADDR     = 0x0057C0; // Map_ESth (Obj89: 1 frame)
    public static final int MAP_PURPLE_ROCK_ADDR = 0x00D79C; // Map_PRock (Obj3B: 2 frames)
    public static final int MAP_CHOPPER_ADDR     = 0x00B254; // Map_Chop (Obj2B: 2 frames)
    public static final int MAP_JAWS_ADDR        = 0x00B2FA; // Map_Jaws (Obj2C: 4 frames)
    public static final int MAP_BURROBOT_ADDR    = 0x00B4E6; // Map_Burro (Obj2D: 7 frames)
    public static final int MAP_FIREBALL_ADDR    = 0x00B9F8; // Map_Fire (Obj14/35/74: 6 frames)
    public static final int MAP_MZ_LARGE_GRASSY_PLATFORM_ADDR = 0x00B95E; // Map_LGrass (Obj2F: 3 frames)
    public static final int MAP_MZ_GLASS_ADDR    = 0x00BC84; // Map_Glass (Obj30: 3 frames)
    public static final int MAP_MZ_CHAINED_STOMPER_ADDR = 0x00C1D8; // Map_CStom (Obj31: 11 frames)
    public static final int MAP_BUTTON_ADDR      = 0x00C52E; // Map_But (Obj32/Obj82: 4 frames)
    public static final int MAP_PUSH_BLOCK_ADDR  = 0x00C970; // Map_Push (Obj33: 2 frames)
    public static final int MAP_MZ_SMASH_BLOCK_ADDR = 0x010460; // Map_Smab (Obj51: 2 frames)
    public static final int MAP_MZ_SBZ_MOVING_BLOCK_ADDR = 0x0106D6; // Map_MBlock (Obj52 MZ/SBZ: 5 frames)
    public static final int MAP_LZ_MOVING_BLOCK_ADDR = 0x01072C; // Map_MBlockLZ (Obj52 LZ: 1 frame)
    public static final int MAP_SMASH_ADDR       = 0x00D944; // Map_Smash (Obj2D: 3 frames)
    public static final int MAP_SBZ_FLAMETHROWER_ADDR = 0x00EC82; // Map_Flame (Obj6D: 22 frames)
    public static final int MAP_SYZ_BUMPER_ADDR  = 0x00F18C; // Map_Bump (Obj47: 3 frames)
    public static final int MAP_MZ_LAVA_GEYSER_ADDR = 0x00F8D6; // Map_Geyser (Obj4C/4D: 20 frames)
    public static final int MAP_MZ_LAVA_WALL_ADDR = 0x00FBBA; // Map_LWall (Obj4E: 5 frames)
    public static final int MAP_MOTOBUG_ADDR     = 0x00FE2C; // Map_Moto (Obj40: 7 frames)
    public static final int MAP_YADRIN_ADDR      = 0x00FFBA; // Map_Yad (Obj50: 6 frames)
    public static final int MAP_LZ_FLAPPING_DOOR_ADDR = 0x011AC6; // Map_Flap (Obj0C: 3 frames)
    public static final int MAP_GHZ_EDGE_WALL_ADDR = 0x00E8DF; // Map_Edge (Obj44: 3 frames)
    public static final int MAP_MZ_BRICK_ADDR  = 0x00EF90; // Map_Brick (Obj46: 1 frame)
    public static final int MAP_SYZ_SPINNING_LIGHT_ADDR = 0x00F00E; // Map_Light (Obj12: 6 frames)
    public static final int MAP_BRIDGE_ADDR      = 0x007FB2; // Map_Bri (Obj11: 3 frames)
    public static final int MAP_SWING_GHZ_ADDR   = 0x0082C6; // Map_Swing_GHZ (Obj15/48: 3 frames)
    public static final int MAP_SWING_SLZ_ADDR   = 0x0082E4; // Map_Swing_SLZ (Obj15: 3 frames)
    public static final int MAP_SPIKED_POLE_HELIX_ADDR = 0x008476; // Map_Hel (Obj17: 8 frames)
    public static final int MAP_GIANT_BALL_ADDR  = 0x008828; // Map_GBall (Obj15 GHZ subtype $1X: 4 frames)
    public static final int MAP_PLATFORM_GHZ_ADDR = 0x0087BA; // Map_Plat_GHZ (Obj18: 2 frames)
    public static final int MAP_PLATFORM_SYZ_ADDR = 0x008806; // Map_Plat_SYZ (Obj18: 1 frame)
    public static final int MAP_PLATFORM_SLZ_ADDR = 0x008818; // Map_Plat_SLZ (Obj18: 1 frame)
    public static final int MAP_COLLAPSING_LEDGE_ADDR = 0x008C1E; // Map_Ledge (Obj1A: 4 frames)
    public static final int MAP_COLLAPSING_FLOOR_ADDR = 0x008DC4; // Map_CFlo (Obj53: 4 frames)
    public static final int MAP_SCENERY_ADDR     = 0x008ED4; // Map_Scen (Obj1C: 1 frame)
    public static final int MAP_SBZ_SMALL_DOOR_ADDR = 0x00906A; // Map_ADoor (Obj2A: 9 frames)
    public static final int MAP_BALL_HOG_ADDR    = 0x0094E0; // Map_Hog (Obj1E/20: 6 frames)
    public static final int MAP_EXPLOSION_ADDR   = 0x009544; // Map_ExplodeItem (Obj24/27/3F: 5 frames)
    public static final int MAP_ANIMAL1_ADDR     = 0x009AE4; // Map_Animal1 (Obj28/ending animals: 3 frames)
    public static final int MAP_ANIMAL2_ADDR     = 0x009AFC; // Map_Animal2 (Obj28/ending animals: 3 frames)
    public static final int MAP_ANIMAL3_ADDR     = 0x009B14; // Map_Animal3 (Obj28/ending animals: 3 frames)
    public static final int MAP_POINTS_ADDR      = 0x009B2C; // Map_Poi (Obj29: 7 frames)
    public static final int MAP_CRABMEAT_ADDR    = 0x009DCE; // Map_Crab (Obj1F: 7 frames)
    public static final int MAP_BUZZ_BOMBER_ADDR = 0x00A0B4; // Map_Buzz (Obj22: 6 frames)
    public static final int MAP_BUZZ_MISSILE_ADDR = 0x00A184; // Map_Missile (Obj23: 4 frames)
    public static final int MAP_GIANT_RING_ADDR  = 0x00A654; // Map_GRing (Obj4B: 4 frames)
    public static final int MAP_GIANT_RING_FLASH_ADDR = 0x00A6F6; // Map_Flash (Obj7C: 8 frames)
    public static final int MAP_SHIELD_ADDR      = 0x014B1A; // Map_Shield (Obj38: shield + invincibility stars)
    public static final int MAP_UNUSED_EXPLOSION_ADDR = 0x009524; // Map_UnkExplode (Obj24: 4 frames)
    public static final int MAP_SPIKE_ADDR       = 0x00D676; // Map_Spike (Obj36: 6 frames)
    public static final int MAP_MONITOR_ADDR     = 0x00AC14; // Map_Monitor (Obj26: 12 frames)
    public static final int MAP_SPRING_ADDR      = 0x00E3A8; // Map_Spring (Obj41: 6 frames)
    public static final int MAP_NEWTRON_ADDR     = 0x00E5D0; // Map_Newt (Obj42: 11 frames)
    public static final int MAP_ROLLER_ADDR      = 0x00E830; // Map_Roll (Obj43: 5 frames)
    public static final int MAP_SLZ_PYLON_ADDR   = 0x01176A; // Map_Pylon (Obj5C: 1 frame)
    public static final int MAP_BASARAN_ADDR     = 0x0108CA; // Map_Bas (Obj4F: 4 frames)
    public static final int MAP_SYZ_SPIKEBALL_CHAIN_ADDR = 0x01102A; // Map_SBall (Obj57 SYZ: 1 frame)
    public static final int MAP_LZ_SPIKEBALL_CHAIN_ADDR = 0x011032; // Map_SBall2 (Obj57 LZ: 3 frames)
    public static final int MAP_BIG_SPIKED_BALL_ADDR = 0x011174; // Map_BBall (Obj58/SBZ swing ball: 3 frames)
    public static final int MAP_FLOATING_BLOCK_ADDR = 0x010DD4; // Map_FBlock (Obj56: 8 frames)
    public static final int MAP_SLZ_ELEVATOR_ADDR = 0x01141C; // Map_Elev (Obj59: 1 frame)
    public static final int MAP_SLZ_CIRCLING_PLATFORM_ADDR = 0x011556; // Map_Circ (Obj5A: 1 frame)
    public static final int MAP_SLZ_STAIRCASE_ADDR = 0x011710; // Map_Stair (Obj5B: 1 frame)
    public static final int MAP_SLZ_FAN_ADDR     = 0x011CE4; // Map_Fan (Obj5D: 5 frames)
    public static final int MAP_SLZ_SEESAW_ADDR  = 0x012078; // Map_Seesaw (Obj5E: 4 frames)
    public static final int MAP_SLZ_SEESAW_BALL_ADDR = 0x0120BA; // Map_SSawBall (Obj5F: 2 frames)
    public static final int MAP_BOMB_ADDR        = 0x0122FC; // Map_Bomb (Obj4A: 12 frames)
    public static final int MAP_ORBINAUT_ADDR    = 0x0125B8; // Map_Orb (Obj60: 4 frames)
    public static final int MAP_LZ_HARPOON_ADDR  = 0x01266E; // Map_Harp (Obj16: 6 frames)
    public static final int MAP_LZ_GARGOYLE_ADDR = 0x0129EC; // Map_Gar (Obj62: 4 frames)
    public static final int MAP_LZ_BLOCK_ADDR    = 0x012896; // Map_LBlock (Obj61: 4 frames)
    public static final int MAP_LZ_CONVEYOR_ADDR = 0x012D50; // Map_LConv (Obj63: 5 frames)
    public static final int MAP_LZ_BUBBLES_ADDR  = 0x0130A0; // Map_Bub (Obj0A/64: 23 frames)
    public static final int MAP_LZ_BREAKABLE_POLE_ADDR = 0x0119F6; // Map_Pole (Obj0B: 2 frames)
    public static final int MAP_RESULTS_GOT_ADDR = 0x00D266; // Map_Got (results/title-card text: 9 frames, signed offsets)
    public static final int MAP_RESULTS_SPECIAL_STAGE_ADDR = 0x00D328; // Map_SSR (special-stage results text: 9 frames, signed offsets)
    public static final int MAP_SS_RESULT_EMERALDS_ADDR = 0x00D482; // Map_SSRC (Obj7F: 7 frames)
    public static final int MAP_LZ_WATERFALL_ADDR = 0x013228; // Map_WFall (Obj65: 12 frames)
    public static final int MAP_LZ_SPLASH_ADDR   = 0x014D34; // Map_Splash (Obj08: 3 frames)
    public static final int MAP_SBZ_JUNCTION_ADDR = 0x0159FA; // Map_Jun (Obj66: 17 frames)
    public static final int MAP_SBZ_RUNNING_DISC_ADDR = 0x015DEE; // Map_Disc (Obj67: 1 frame)
    public static final int MAP_SBZ_TRAP_DOOR_ADDR = 0x016048; // Map_Trap (Obj69: 3 frames)
    public static final int MAP_SBZ_SPINNING_PLATFORM_ADDR = 0x0160A2; // Map_Spin (Obj69: 5 frames)
    public static final int MAP_SBZ_SAW_ADDR     = 0x016300; // Map_Saw (Obj6A: 4 frames)
    public static final int MAP_SBZ_STOMPER_DOOR_ADDR = 0x0166D0; // Map_Stomp (Obj6B: 5 frames)
    public static final int MAP_SBZ_VANISHING_PLATFORM_ADDR = 0x016892; // Map_VanP (Obj6C: 4 frames)
    public static final int MAP_SBZ_ELECTROCUTER_ADDR = 0x016948; // Map_Elec (Obj6E: 6 frames)
    public static final int MAP_SBZ_GIRDER_ADDR  = 0x016D90; // Map_Gird (Obj70: 1 frame)
    public static final int MAP_CATERKILLER_ADDR = 0x01751A; // Map_Cat (Obj78: 24 frames)
    public static final int MAP_HIDDEN_BONUS_ADDR = 0x0179F4; // Map_Bonus (Obj7D: 4 frames)
    public static final int MAP_EGGMAN_ADDR      = 0x0184B8; // Map_Eggman (bosses: 13 frames)
    public static final int MAP_BOSS_ITEMS_ADDR  = 0x018580; // Map_BossItems (boss extras: 8 frames)
    public static final int MAP_SYZ_BOSS_BLOCK_ADDR = 0x019FAE; // Map_BossBlock (Obj76: 5 frames)
    public static final int MAP_SEGG_ADDR        = 0x01A1E4; // Map_SEgg (Obj82/Obj85: 11 frames)
    public static final int MAP_SBZ_FALSE_FLOOR_ADDR = 0x01A4C2; // Map_FFloor (Obj83: 5 frames)
    public static final int MAP_FZ_DAMAGED_ADDR  = 0x01AB90; // Map_FZDamaged (Obj85: 2 frames)
    public static final int MAP_FZ_LEGS_ADDR     = 0x01ABD2; // Map_FZLegs (Obj85: 3 frames)
    public static final int MAP_FZ_EGGCYL_ADDR   = 0x01AE2C; // Map_EggCyl (Obj85: 12 frames)
    public static final int MAP_FZ_PLAUNCH_ADDR  = 0x01B20C; // Map_PLaunch (Obj86: 4 frames)
    public static final int MAP_FZ_PLASMA_ADDR   = 0x01B25C; // Map_Plasma (Obj87: 11 frames)
    public static final int MAP_PRISON_ADDR      = 0x01B52A; // Map_Pri (Obj3E: 7 frames)

    // Sonic's animation scripts (31 animations)
    public static final int SONIC_ANIM_DATA_ADDR = 0x1421C;
    public static final int SONIC_ANIM_SCRIPT_COUNT = 31;

    // Sonic's VRAM tile index
    public static final int ART_TILE_SONIC = 0x0780;
    public static final int ARTTILE_RING = 0x0780;

    // S1 mapping/DPLC frame count (Map_Sonic and SonicDynPLC both have this many entries)
    public static final int SONIC_MAPPING_FRAME_COUNT = 88;

    // ---- Animated level art (uncompressed) ----
    // Addresses calculated backward from LEVEL_INDEX_ADDR using artunc file sizes.
    // GHZ animated art
    public static final int ART_UNC_GHZ_WATER_ADDR   = 0x66A96; // 512 bytes, 16 tiles (2 frames × 8 tiles)
    public static final int ART_UNC_GHZ_FLOWER1_ADDR  = 0x66C96; // 1024 bytes, 32 tiles (2 frames × 16 tiles)
    public static final int ART_UNC_GHZ_FLOWER2_ADDR  = 0x67096; // 1152 bytes, 36 tiles (3 frames × 12 tiles)

    // MZ animated art
    public static final int ART_UNC_MZ_LAVA1_ADDR    = 0x67516; // 768 bytes, 24 tiles (3 frames × 8 tiles)
    public static final int ART_UNC_MZ_LAVA2_ADDR    = 0x67816; // 1536 bytes, 48 tiles (magma, 3 frames × 16 tiles)
    public static final int ART_UNC_MZ_TORCH_ADDR    = 0x67E16; // 768 bytes, 24 tiles (4 frames × 6 tiles)

    // SBZ animated art
    public static final int ART_UNC_SBZ_SMOKE_ADDR   = 0x68116; // 2688 bytes, 84 tiles (7 frames × 12 tiles)

    // Giant ring art (after level layout data)
    public static final int ART_UNC_GIANT_RING_ADDR  = 0x6A2E4; // Art_BigRing (verified by RomOffsetFinder)
    public static final int ART_UNC_GIANT_RING_SIZE  = 3136;     // $C40 bytes = 98 tiles

    // Giant ring flash art (Nemesis compressed)
    public static final int ART_NEM_GIANT_RING_FLASH_ADDR = 0x3AF24; // Nem_BigFlash (verified by RomOffsetFinder)

    // ---- Title card art ----
    // Nem_TitleCard: Nemesis-compressed title card sprite art (1550 bytes)
    // Contains zone name letters, "ZONE", act numbers, oval decoration
    // Verified by binary search matching docs/s1disasm/artnem/Title Cards.nem
    public static final int ART_NEM_TITLE_CARD_ADDR = 0x39204;

    // ---- GAME OVER / TIME OVER card (Obj39 GameOverCard) ----
    // Nem_GameOver (docs/s1disasm/sonic.lst:94090, artnem/Game Over.nem, 401 bytes;
    // verified by byte match against the ROM). Loaded by PLC_GameOver at
    // ArtTile_Game_Over ($55E): docs/s1disasm/_inc/Pattern Load Cues.asm:100-102.
    public static final int ART_NEM_GAME_OVER_ADDR = 0x3A678;
    // Map_Over (_maps/Game Over.asm): four frames GAME / OVER / TIME / OVER,
    // tile ids relative to ArtTile_Game_Over (docs/s1disasm/sonic.lst:46362-46367).
    public static final int MAP_GAME_OVER_ADDR = 0xD232;
    public static final int MAP_GAME_OVER_FRAME_COUNT = 4;
    // plcid_GameOver: fourth entry of ArtLoadCues (docs/s1disasm/_inc/Pattern Load Cues.asm:29-32).
    public static final int PLC_GAME_OVER = 3;
    // v_gameovertext1 / v_gameovertext2 = v_objspace + object_size*2 / *3
    // (docs/s1disasm/_Variables.asm:62-63).
    public static final int SST_SLOT_GAME_OVER_WORD = 2;
    public static final int SST_SLOT_GAME_OVER_OVER = 3;

    // ---- Ending sequence Kosinski-compressed flower art ----
    // Kos_EndFlowers: decompressed at GM_Ending into RAM buffer, used by
    // AniArt_Ending_BigFlower (offset 0), Flower3 (+$400) and Flower4 (+$A00).
    public static final int KOS_END_FLOWERS_ADDR = 0x61822;

    // ---- Animated tile VRAM destinations (ArtTile_Level = 0x000) ----
    // GHZ
    public static final int ARTTILE_GHZ_FLOWER_4      = 0x340;
    public static final int ARTTILE_GHZ_WATERFALL     = 0x378;
    public static final int ARTTILE_GHZ_BIG_FLOWER_1  = 0x35C;
    public static final int ARTTILE_GHZ_SMALL_FLOWER  = 0x36C;
    public static final int ARTTILE_GHZ_FLOWER_3      = 0x380;
    public static final int ARTTILE_GHZ_BIG_FLOWER_2  = 0x390;
    // MZ
    public static final int ARTTILE_MZ_ANIMATED_LAVA  = 0x2E2;
    public static final int ARTTILE_MZ_ANIMATED_MAGMA = 0x2D2;
    public static final int ARTTILE_MZ_TORCH          = 0x2F2;
    // SBZ
    public static final int ARTTILE_SBZ_SMOKE_PUFF_1  = 0x448;
    public static final int ARTTILE_SBZ_SMOKE_PUFF_2  = 0x454;

    // ---- Title screen art ----
    public static final int ART_NEM_TITLE_FG_ADDR    = 0x1ED80;  // Nem_TitleFg (title foreground)
    public static final int ART_NEM_TITLE_SONIC_ADDR  = 0x1FD8C;  // Nem_TitleSonic (Sonic sprite)
    public static final int ART_NEM_TITLE_TM_ADDR     = 0x2175A;  // Nem_TitleTM (trademark symbol)
    public static final int ART_NEM_SEGA_LOGO_ADDR    = 0x1E700;  // Nem_SegaLogo (REV01, sonic.lst)
    public static final int ART_NEM_CREDIT_TEXT_ADDR   = 0x6203A;  // Nem_CreditText (credit text font)
    public static final int MAP_ENI_TITLE_ADDR         = 0x1EC6C;  // Eni_Title (title foreground tilemap)
    public static final int MAP_ENI_SEGA_LOGO_ADDR     = 0x1EB70;  // Eni_SegaLogo (REV01, sonic.lst)
    public static final int ART_NEM_GHZ_1ST_ADDR       = 0x3CB3C;  // Nem_GHZ_1st (GHZ background patterns)
    public static final int BLK16_GHZ_ADDR             = 0x3C19C;  // Blk16_GHZ (16x16 chunk mappings, Enigma)
    public static final int BLK256_GHZ_ADDR            = 0x3F544;  // Blk256_GHZ (256x256 block mappings, Kosinski)
    public static final int LEVEL_GHZ_BG_ADDR          = 0x68F22;  // Level_GHZbg (background layout, uncompressed)

    // ---- Title screen palettes ----
    public static final int PAL_TITLE_ADDR       = 0x2280;  // Pal_Title (128 bytes, 4 palette lines)
    public static final int PAL_TITLE_CYCLE_ADDR = 0x1B5E;  // Pal_TitleCyc (32 bytes, water cycle palette)
    public static final int PAL_SEGA_SCAN_ADDR   = 0x20B8;  // Pal_Sega1 (12 bytes, light scan)
    public static final int PAL_SEGA_FADE_ADDR   = 0x20C4;  // Pal_Sega2 (48 bytes, fade-in sets)
    public static final int PAL_SEGA_BG_ADDR     = 0x2200;  // Pal_SegaBG (128 bytes, 4 palette lines)
    public static final int PAL_GHZ_CYCLE_ADDR = 0x1B7E;
    public static final int PAL_LZ_CYCLE1_ADDR = 0x1B9E;
    public static final int PAL_LZ_CYCLE2_ADDR = 0x1BBE;
    public static final int PAL_LZ_CYCLE3_ADDR = 0x1BD0;
    public static final int PAL_SBZ3_CYCLE_ADDR = 0x1BE2;
    public static final int PAL_SLZ_CYCLE_ADDR = 0x1C4A;
    public static final int PAL_SYZ_CYCLE1_ADDR = 0x1C6E;
    public static final int PAL_SYZ_CYCLE2_ADDR = 0x1C8E;
    public static final int PAL_SBZ_CYCLE1_ADDR = 0x1D02;
    public static final int PAL_SBZ_CYCLE2_ADDR = 0x1D12;
    public static final int PAL_SBZ_CYCLE3_ADDR = 0x1D22;
    public static final int PAL_SBZ_CYCLE4_ADDR = 0x1D32;
    public static final int PAL_SBZ_CYCLE5_ADDR = 0x1D3E;
    public static final int PAL_SBZ_CYCLE6_ADDR = 0x1D4E;
    public static final int PAL_SBZ_CYCLE7_ADDR = 0x1D5E;
    public static final int PAL_SBZ_CYCLE8_ADDR = 0x1D7E;
    public static final int PAL_SBZ_CYCLE9_ADDR = 0x1D88;
    public static final int PAL_SBZ_CYCLE10_ADDR = 0x1D98;

    // ---- Title screen VRAM tile indices (from Constants.asm) ----
    public static final int ARTTILE_TITLE_FOREGROUND   = 0x200;
    public static final int ARTTILE_TITLE_SONIC        = 0x300;
    public static final int ARTTILE_TITLE_TRADEMARK    = 0x510;
    public static final int ARTTILE_SONIC_TEAM_FONT    = 0x0A6;

    // ---- HUD art ----
    // Art_Hud: Uncompressed HUD digit tiles (0-9 + colon), each digit = 2 tiles (top/bottom)
    public static final int ART_UNC_HUD_NUMBERS_ADDR = 0x1D2A6;
    public static final int ART_UNC_HUD_NUMBERS_SIZE = 0x300; // 768 bytes = 24 tiles

    // Art_LivesNums: Uncompressed 8x8 lives counter digit tiles (0-9)
    public static final int ART_UNC_LIVES_NUMBERS_ADDR = 0x1D5A6;
    public static final int ART_UNC_LIVES_NUMBERS_SIZE = 320; // 10 tiles

    // Art_Text: Uncompressed 8x8 font used by level select + debug HUD hex coords.
    // ASCII-aligned layout: tile n = character ('0' + n), so digits 0-9 at tiles 0-9
    // and A-F at tiles 17-22 (matches '0'=0x30, 'A'=0x41 -> offset 0x11).
    public static final int ART_UNC_TEXT_ADDR = 0x5F0;
    public static final int ART_UNC_TEXT_SIZE = 1312; // 41 tiles

    // Nem_Hud: Nemesis-compressed HUD text labels (SCORE/TIME/RINGS)
    public static final int ART_NEM_HUD_ADDR = 0x39812;

    // Nem_Lives: Nemesis-compressed life counter icon (Sonic face + name letters)
    public static final int ART_NEM_LIFE_ICON_ADDR = 0x39908;

    // Nem_Ring: Nemesis-compressed ring art (8 frames: 4 spin + 4 sparkle)
    public static final int ART_NEM_RING_ADDR = 0x39A0E;

    // Nem_Monitors: Nemesis-compressed monitor art (1120 bytes, all types + broken shell)
    public static final int ART_NEM_MONITOR_ADDR = 0x39B02;

    // Nem_Explode: Nemesis-compressed explosion art (ArtTile_Explosion = $5A0)
    public static final int ART_NEM_EXPLOSION_ADDR = 0x39F62;

    // Nem_Points: Nemesis-compressed points popups (100/200/500/1000)
    public static final int ART_NEM_POINTS_ADDR = 0x3A5C8;

    // Nem_Bonus: Nemesis-compressed hidden bonus point popup art (769 bytes)
    // ArtTile_Hidden_Points = $4B6
    // Verified by RomOffsetFinder --game s1 search Bonus
    public static final int ART_NEM_HIDDEN_BONUS_ADDR = 0x3B098;

    // Zone animal art (used by badnik destruction object 0x28)
    public static final int ART_NEM_ANIMAL_RABBIT_ADDR = 0x3B884;
    public static final int ART_NEM_ANIMAL_CHICKEN_ADDR = 0x3B9DC;
    public static final int ART_NEM_ANIMAL_PENGUIN_ADDR = 0x3BB38;
    public static final int ART_NEM_ANIMAL_SEAL_ADDR = 0x3BCB4;
    public static final int ART_NEM_ANIMAL_PIG_ADDR = 0x3BDD0;
    public static final int ART_NEM_ANIMAL_FLICKY_ADDR = 0x3BF06;
    public static final int ART_NEM_ANIMAL_SQUIRREL_ADDR = 0x3C040;

    // Nem_Shield: Nemesis-compressed shield art (ArtTile_Shield = $541)
    public static final int ART_NEM_SHIELD_ADDR = 0x2C730;
    // Nem_Stars: Nemesis-compressed invincibility stars (ArtTile_Invincibility = $55C)
    public static final int ART_NEM_INVINCIBILITY_STARS_ADDR = 0x2C8C6;

    // ---- Touch collision sizes (ReactToItem .sizes table) ----
    // S1 uses `lea .sizes-2(pc,d0.w)` so effective base is .sizes-2 = 0x1B5E4-2.
    // 36 entries indexed 0x01-0x24; need 37 array slots (0-36) for the engine's 0-based lookup.
    public static final int TOUCH_SIZES_ADDR = 0x1B5E2;
    public static final int TOUCH_ENTRY_COUNT = 37;

    // Nem_PplRock: Nemesis-compressed purple rock art (GHZ, 302 bytes)
    public static final int ART_NEM_PURPLE_ROCK_ADDR = 0x300BA;

    // Nem_Bridge: Nemesis-compressed bridge art (GHZ, ~10 tiles: log, stump, rope)
    // Loaded via PLC_GHZ2: plcm Nem_Bridge, ArtTile_GHZ_Bridge
    public static final int ART_NEM_BRIDGE_ADDR = 0x2FA2C;
    public static final int BRIDGE_BEND_Y_MAX_ADDR = 0x7D4A; // Bri_Data_Y_Max, 17 rows x 16 bytes
    public static final int BRIDGE_BEND_ALIGN_ADDR = 0x7E5A; // Bri_Data_Align, 16 rows x 16 bytes

    // Nem_MzBlock: Nemesis-compressed MZ green pushable/smashable block art
    // ArtTile_MZ_Block = $2B8, loaded via PLC_MZ
    // Verified by RomOffsetFinder --game s1 search MzBlock (356 bytes)
    public static final int ART_NEM_MZ_BLOCK_ADDR = 0x33670;

    // Nem_LzPole: Nemesis-compressed LZ breakable pole / push block art
    // ArtTile_LZ_Pole = ArtTile_LZ_Push_Block = $3DE, loaded via PLC_LZ2
    // Verified by RomOffsetFinder --game s1 search LzPole (100 bytes)
    public static final int ART_NEM_LZ_POLE_ADDR = 0x317F2;

    // Nem_Splash: Nemesis-compressed LZ waterfall/splash art
    // ArtTile_LZ_Splash = $259, loaded via PLC_LZ
    // Verified by RomOffsetFinder --game s1 search Nem_Splash
    public static final int ART_NEM_LZ_SPLASH_ADDR = 0x3040A;

    // Nem_FlapDoor: Nemesis-compressed LZ flapping door art
    // ArtTile_LZ_Flapping_Door = $328, loaded via PLC_LZ
    // Verified by RomOffsetFinder --game s1 search Nem_FlapDoor
    public static final int ART_NEM_LZ_FLAP_DOOR_ADDR = 0x30D7E;

    // Nem_LzDoor1: Nemesis-compressed LZ vertical door art (161 bytes)
    // ArtTile_LZ_Door = $3C4, loaded via PLC_LZ
    // Verified by RomOffsetFinder --game s1 search LzDoor
    public static final int ART_NEM_LZ_DOOR_VERT_ADDR = 0x315F4;

    // Nem_LzDoor2: Nemesis-compressed LZ horizontal door art (338 bytes)
    // ArtTile_LZ_Door = $3C4, loaded via PLC_LZ
    // Verified by RomOffsetFinder --game s1 search LzDoor
    public static final int ART_NEM_LZ_DOOR_HORIZ_ADDR = 0x31856;

    // Nem_LzBlock3: Nemesis-compressed LZ 32x16 moving block art (182 bytes)
    // ArtTile_LZ_Moving_Block = $3BC, loaded via PLC_LZ2
    // Verified by RomOffsetFinder --game s1 search LzBlock3
    public static final int ART_NEM_LZ_MOVING_BLOCK_ADDR = 0x3153E;

    // Nem_LzBlock2: Nemesis-compressed LZ Blocks art (695 bytes)
    // ArtTile_LZ_Blocks = $3E6, loaded via PLC_LZ2 (large horizontal door tiles)
    // Also used by Object 0x61 (LabyrinthBlock) for sinkblock frame (tile 0)
    // Verified by RomOffsetFinder --game s1 search LzBlock2
    public static final int ART_NEM_LZ_BLOCKS_ADDR = 0x31FFA;

    // Nem_LzBlock1: Nemesis-compressed LZ 32x32 Block art (271 bytes)
    // ArtTile_LZ_Block_1 = $1E0, loaded via PLC_LZ
    // Used by Object 0x61 frame 3 (.block) via VRAM wraparound: $3E6+$5FA=$9E0&$7FF=$1E0
    // Verified by RomOffsetFinder --game s1 search LzBlock1
    public static final int ART_NEM_LZ_BLOCK1_ADDR = 0x32514;

    // Nem_LzPlatfm: Nemesis-compressed LZ Rising Platform art (312 bytes)
    // ArtTile_LZ_Rising_Platform = $3E6+$69 = $44F, loaded via PLC_LZ2
    // Used by Object 0x61 frame 1 (.riseplatform)
    // Verified by RomOffsetFinder --game s1 search LzPlatfm
    public static final int ART_NEM_LZ_RISING_PLATFORM_ADDR = 0x322B2;

    // Nem_Cork: Nemesis-compressed LZ Cork art (298 bytes)
    // ArtTile_LZ_Cork = $3E6+$11A = $500, loaded via PLC_LZ2
    // Used by Object 0x61 frame 2 (.cork)
    // Verified by RomOffsetFinder --game s1 search Cork
    public static final int ART_NEM_LZ_CORK_ADDR = 0x323EA;

    // Nem_Stomper: Nemesis-compressed SBZ stomper / short moving block art (413 bytes)
    // ArtTile_SBZ_Moving_Block_Short = $2C0, loaded via PLC_SBZ
    // Verified by RomOffsetFinder --game s1 search Stomper
    public static final int ART_NEM_SBZ_STOMPER_ADDR = 0x34D28;

    // Nem_SlideFloor: Nemesis-compressed SBZ sliding floor / long moving block art (88 bytes)
    // ArtTile_SBZ_Moving_Block_Long = $460, loaded via PLC_SBZ
    // Verified by RomOffsetFinder --game s1 search SlideFloor
    public static final int ART_NEM_SBZ_SLIDE_FLOOR_ADDR = 0x35886;

    // Nem_SbzDoor2: Nemesis-compressed SBZ large horizontal door art (252 bytes)
    // ArtTile_SBZ_Horizontal_Door = $46F, loaded via PLC_SBZ2
    // Used by Object 0x6B (ScrapStomp) frame 0 (.door)
    // Verified by RomOffsetFinder --game s1 search SbzDoor2
    public static final int ART_NEM_SBZ_HORIZONTAL_DOOR_ADDR = 0x358DE;

    // Nem_SlzBlock: Nemesis-compressed SLZ 32x32 collapsing floor block art (267 bytes)
    // ArtTile_SLZ_Collapsing_Floor = $4E0, loaded via PLC_SLZ
    // Verified by RomOffsetFinder --game s1 search SlzBlock
    public static final int ART_NEM_SLZ_COLLAPSING_FLOOR_ADDR = 0x34148;

    // Nem_SbzFloor: Nemesis-compressed SBZ collapsing floor art (88 bytes)
    // ArtTile_SBZ_Collapsing_Floor = $3F5, loaded via PLC_SBZ
    // Verified by RomOffsetFinder --game s1 search SbzFloor
    public static final int ART_NEM_SBZ_COLLAPSING_FLOOR_ADDR = 0x353D4;

    // Nem_SbzBlock: Nemesis-compressed SBZ vanishing block art (253 bytes)
    // ArtTile_SBZ_Vanishing_Block = $4C3, palette line 2, loaded via PLC_SBZ
    // Verified by RomOffsetFinder --game s1 search SbzBlock
    public static final int ART_NEM_SBZ_VANISHING_BLOCK_ADDR = 0x355AC;

    // Nem_Electric: Nemesis-compressed SBZ electrocution orb art (384 bytes)
    // ArtTile_SBZ_Electric_Orb = $47E, palette line 0
    // Verified by RomOffsetFinder --game s1 search Nem_Electric
    public static final int ART_NEM_SBZ_ELECTROCUTER_ADDR = 0x3542C;

    // Nem_FlamePipe: Nemesis-compressed SBZ flaming pipe art (395 bytes)
    // ArtTile_SBZ_Flamethrower = $3D9, palette line 0, priority bit 1
    // Verified by RomOffsetFinder --game s1 search FlamePipe
    public static final int ART_NEM_SBZ_FLAMETHROWER_ADDR = 0x356AA;

    // Nem_Seesaw: Nemesis-compressed SLZ seesaw art (572 bytes, ArtTile_SLZ_Seesaw = $374)
    // Verified by RomOffsetFinder --game s1 search Seesaw
    public static final int ART_NEM_SLZ_SEESAW_ADDR = 0x3385C;

    // Nem_SlzSpike: Nemesis-compressed SLZ seesaw spikeball art (326 bytes, ArtTile_SLZ_Spikeball = $4F0)
    // Verified by RomOffsetFinder --game s1 search SlzSpike
    public static final int ART_NEM_SLZ_SPIKEBALL_ADDR = 0x33A98;

    // Nem_Pylon: Nemesis-compressed SLZ foreground pylon art (225 bytes, ArtTile_SLZ_Pylon = $3CC)
    // Verified by RomOffsetFinder --game s1 search Pylon
    public static final int ART_NEM_SLZ_PYLON_ADDR = 0x33E84;

    // ArtTile_SLZ_Pylon VDP tile index (from Constants.asm)
    public static final int ARTTILE_SLZ_PYLON = 0x3CC;

    // Nem_Fan: Nemesis-compressed SLZ fan art (579 bytes, ArtTile_SLZ_Fan = $3A0)
    // Verified by RomOffsetFinder --game s1 search Fan
    public static final int ART_NEM_SLZ_FAN_ADDR = 0x33BDE;

    // ArtTile_SLZ_Fan VDP tile index (from Constants.asm)
    public static final int ARTTILE_SLZ_FAN = 0x3A0;

    // Nem_SlzCannon: Nemesis-compressed SLZ fireball launcher / lava thrower art
    // Loaded via PLC_SLZ: plcm Nem_SlzCannon, ArtTile_SLZ_Fireball_Launcher
    // Verified by RomOffsetFinder --game s1 search Cannon
    public static final int ART_NEM_SLZ_CANNON_ADDR = 0x34254;

    // Nem_Lamp: Nemesis-compressed lamppost art (10 tiles: pole, blue ball, red ball)
    public static final int ART_NEM_LAMPPOST_ADDR = 0x3AE64;

    // Nem_Swing: Nemesis-compressed GHZ/MZ swinging platform art (281 bytes, ArtTile $380)
    // Verified by RomOffsetFinder --game s1 search Swing
    public static final int ART_NEM_SWING_ADDR = 0x2F912;

    // Nem_Ball: Nemesis-compressed GHZ giant ball art (413 bytes, ArtTile $3AA)
    // Verified by RomOffsetFinder --game s1 search Ball
    public static final int ART_NEM_GIANT_BALL_ADDR = 0x2FB60;

    // Nem_SpikePole: Nemesis-compressed spiked pole helix art (GHZ, 300 bytes, ArtTile $398)
    // Verified by RomOffsetFinder --game s1 search SpikePole
    public static final int ART_NEM_SPIKE_POLE_ADDR = 0x2FF8E;

    // Nem_Spikes: Nemesis-compressed spike art (upward + sideways, 8 tiles)
    public static final int ART_NEM_SPIKES_ADDR = 0x2FCFE;

    // Nem_Buzz: Nemesis-compressed Buzz Bomber art (GHZ/MZ/SYZ, ArtTile $444)
    public static final int ART_NEM_BUZZ_BOMBER_ADDR = 0x3639E;

    // Nem_Crabmeat: Nemesis-compressed Crabmeat art (GHZ/SYZ, ArtTile $400)
    // Verified by decompression at ROM offset via RomOffsetFinder
    public static final int ART_NEM_CRABMEAT_ADDR = 0x35EB0;

    // Nem_Chopper: Nemesis-compressed Chopper art (GHZ, ArtTile_Chopper = $47B)
    // Verified by RomOffsetFinder --game s1 search Chopper (616 bytes, 1024 decompressed)
    public static final int ART_NEM_CHOPPER_ADDR = 0x37016;

    // Nem_Burrobot: Nemesis-compressed Burrobot art (LZ, ArtTile_Burrobot = $4A6)
    // Verified by RomOffsetFinder --game s1 search Nem_Burrobot
    public static final int ART_NEM_BURROBOT_ADDR = 0x3692C;

    // Nem_Motobug: Nemesis-compressed Motobug art (GHZ, ArtTile $4F0)
    // Verified by RomOffsetFinder --game s1 search Motobug
    public static final int ART_NEM_MOTOBUG_ADDR = 0x37A2C;

    // Nem_Newtron: Nemesis-compressed Newtron art (GHZ, ArtTile_Newtron = $49B)
    // Verified by RomOffsetFinder --game s1 find Nem_Newtron (2720 bytes decompressed = 85 tiles)
    public static final int ART_NEM_NEWTRON_ADDR = 0x37CB6;

    // Nem_MzGlass: Nemesis-compressed MZ green glass block art (ArtTile_MZ_Glass_Pillar = $38E)
    // Verified by RomOffsetFinder --game s1 search MzGlass (183 bytes)
    public static final int ART_NEM_MZ_GLASS_ADDR = 0x32970;

    // Nem_Bumper: Nemesis-compressed SYZ bumper art (ArtTile_SYZ_Bumper = $380, palette 0)
    // Same ROM data as ART_NEM_SS_BUMPER_ADDR (reused in special stage)
    // Verified by RomOffsetFinder --game s1 search Bumper (362 bytes)
    public static final int ART_NEM_BUMPER_ADDR = 0x342F8;

    // Nem_Roller: Nemesis-compressed Roller art (SYZ, ArtTile_Roller = $4B8)
    // Verified by RomOffsetFinder --game s1 search Roller (1316 bytes)
    public static final int ART_NEM_ROLLER_ADDR = 0x37508;

    // Nem_Yadrin: Nemesis-compressed Yadrin art (SYZ, ArtTile_Yadrin = $47B)
    // Verified by RomOffsetFinder --game s1 search Yadrin (999 bytes)
    public static final int ART_NEM_YADRIN_ADDR = 0x382D4;

    // Nem_Basaran: Nemesis-compressed Basaran/Batbrain art (MZ, ArtTile_Basaran = $4B8)
    // Verified by RomOffsetFinder --game s1 search Basaran (763 bytes)
    public static final int ART_NEM_BASARAN_ADDR = 0x386BC;

    // Nem_BallHog: Nemesis-compressed Ball Hog enemy art (SBZ, ArtTile_Ball_Hog = $302)
    // 960 bytes compressed, palette line 1. Ball Hog + cannonball share this sprite sheet.
    // Verified by RomOffsetFinder --game s1 search BallHog
    public static final int ART_NEM_BALL_HOG_ADDR = 0x35AF0;

    // Nem_Bomb: Nemesis-compressed Bomb enemy art (SLZ/SBZ, ArtTile_Bomb)
    // Verified by RomOffsetFinder --game s1 search Bomb (664 bytes)
    public static final int ART_NEM_BOMB_ADDR = 0x38C00;

    // Nem_Cater: Nemesis-compressed Caterkiller art (MZ/SYZ ArtTile $4FF, SBZ ArtTile $2B0)
    // Verified by RomOffsetFinder --game s1 search Caterkiller (398 bytes)
    public static final int ART_NEM_CATERKILLER_ADDR = 0x39076;

    // Nem_Orbinaut: Nemesis-compressed Orbinaut art (LZ/SLZ/SBZ)
    // ArtTile_LZ_Orbinaut = $467, ArtTile_SLZ_Orbinaut = $429, ArtTile_SBZ_Orbinaut = $429
    // Verified by RomOffsetFinder --game s1 search Nem_Orbinaut
    public static final int ART_NEM_ORBINAUT_ADDR = 0x38E98;

    // Nem_MzSwitch: Nemesis-compressed MZ button/switch art (190 bytes)
    // Verified by RomOffsetFinder --game s1 search Switch
    public static final int ART_NEM_MZ_SWITCH_ADDR = 0x328B2;

    // Nem_LzSwitch: Nemesis-compressed button/switch art (LZ/SYZ/SBZ, 225 bytes)
    // Verified by RomOffsetFinder --game s1 search Switch
    public static final int ART_NEM_LZ_SWITCH_ADDR = 0x344C4;

    // Nem_HSpring: Nemesis-compressed horizontal spring art (up/down springs, 16 tiles)
    // ArtTile_Spring_Horizontal = $523
    public static final int ART_NEM_HSPRING_ADDR = 0x3A80A;

    // Nem_VSpring: Nemesis-compressed vertical spring art (left/right springs, ~14 tiles)
    // ArtTile_Spring_Vertical = $533
    public static final int ART_NEM_VSPRING_ADDR = 0x3A90C;

    // Nem_Sign: Nemesis-compressed signpost art (end-of-act sign, 58 tiles)
    public static final int ART_NEM_SIGNPOST_ADDR = 0x3A9E8;

    // Nem_GhzWall1: Nemesis-compressed GHZ breakable wall art (ArtTile_GHZ_SLZ_Smashable_Wall = $50F)
    // Verified by RomOffsetFinder --game s1 search GhzWall1 (157 bytes)
    public static final int ART_NEM_GHZ_BREAKABLE_WALL_ADDR = 0x301E8;

    // Nem_GhzWall2: Nemesis-compressed GHZ edge wall art (ArtTile_GHZ_Edge_Wall = $34C)
    // Verified by RomOffsetFinder --game s1 search GhzWall (96 bytes)
    public static final int ART_NEM_GHZ_EDGE_WALL_ADDR = 0x30286;

    // Nem_SlzWall: Nemesis-compressed SLZ breakable wall art (ArtTile_GHZ_SLZ_Smashable_Wall+4)
    // Verified by RomOffsetFinder --game s1 search SlzWall (97 bytes)
    public static final int ART_NEM_SLZ_BREAKABLE_WALL_ADDR = 0x33E22;

    // Nem_SlzSwing: Nemesis-compressed SLZ swinging platform art (482 bytes, ArtTile $3DC)
    // Verified by RomOffsetFinder --game s1 search Swing
    public static final int ART_NEM_SLZ_SWING_ADDR = 0x33F66;

    // Nem_SyzSpike2: Nemesis-compressed SYZ small spikeball on chain art (98 bytes, ArtTile $3BA)
    // Used by Object 0x57 (Spiked Ball and Chain) in SYZ
    // Verified by RomOffsetFinder --game s1 search SpikeBall
    public static final int ART_NEM_SYZ_SMALL_SPIKEBALL_ADDR = 0x34462;

    // Nem_LzSpikeBall: Nemesis-compressed LZ spiked ball & chain art (384 bytes, ArtTile $310)
    // Used by Object 0x57 (Spiked Ball and Chain) in LZ
    // Verified by RomOffsetFinder --game s1 search SpikeBall
    public static final int ART_NEM_LZ_SPIKEBALL_ADDR = 0x30BFE;

    // Nem_SyzSpike1: Nemesis-compressed SYZ large spikeball / SBZ spiked ball art (654 bytes, ArtTile $391)
    // Used by swinging platform object in SBZ (spiked ball on a chain)
    // Verified by RomOffsetFinder --game s1 search Ball
    public static final int ART_NEM_SBZ_SPIKED_BALL_ADDR = 0x345A6;

    // Nem_SbzDoor1: Nemesis-compressed SBZ small vertical door art (79 bytes)
    // ArtTile_SBZ_Door = $2E8, palette line 2
    // Verified by RomOffsetFinder --game s1 search SbzDoor1
    public static final int ART_NEM_SBZ_SMALL_DOOR_ADDR = 0x35836;

    // Nem_TrapDoor: Nemesis-compressed SBZ trapdoor art (477 bytes)
    // ArtTile_SBZ_Trap_Door = $492, palette line 2
    // Verified by RomOffsetFinder --game s1 search TrapDoor
    public static final int ART_NEM_SBZ_TRAP_DOOR_ADDR = 0x351F6;

    // Nem_Girder: Nemesis-compressed SBZ large girder block art (277 bytes)
    // ArtTile_SBZ_Girder = $2F0, palette line 2
    // Verified by RomOffsetFinder --game s1 search Girder
    public static final int ART_NEM_SBZ_GIRDER_ADDR = 0x359DA;

    // Nem_Cutter: Nemesis-compressed SBZ pizza cutter / ground saw art (515 bytes)
    // ArtTile_SBZ_Saw = $3B5, palette line 2
    // Verified by RomOffsetFinder --game s1 search Cutter
    public static final int ART_NEM_SBZ_SAW_ADDR = 0x34B24;

    // Nem_SpinPform: Nemesis-compressed SBZ spinning platform art (816 bytes)
    // ArtTile_SBZ_Spinning_Platform = $4DF, palette line 0
    // Verified by RomOffsetFinder --game s1 search SpinPform
    public static final int ART_NEM_SBZ_SPINNING_PLATFORM_ADDR = 0x34EC6;

    // Nem_SbzWheel1: Nemesis-compressed SBZ running disc spot art (83 bytes)
    // ArtTile_SBZ_Disc = $344, palette line 2, priority bit 1
    // Verified by RomOffsetFinder --game s1 search Disc
    public static final int ART_NEM_SBZ_RUNNING_DISC_ADDR = 0x34834;

    // Nem_SbzWheel2: Nemesis-compressed SBZ junction wheel art (668 bytes)
    // ArtTile_SBZ_Junction = $348, palette line 2
    // Verified by RomOffsetFinder --game s1 search Junction
    public static final int ART_NEM_SBZ_JUNCTION_ADDR = 0x34888;

    // ---- Boss art (Nemesis compressed) ----
    // Nem_MzMetal: Nemesis-compressed MZ metal block/chain stomper art (ArtTile_MZ_Spike_Stomper = $300)
    // Used by Object 0x31 (Chained Stompers) and Object 0x45 (Sideways Stomper)
    // Verified by RomOffsetFinder --game s1 search MzMetal (654 bytes)
    public static final int ART_NEM_MZ_METAL_ADDR = 0x32624;

    // Nem_MzFire: Nemesis-compressed MZ fireball art (ArtTile_MZ_Fireball = $345)
    // Used by Object 0x35 (Burning Grass) and lava fireballs
    // Verified by RomOffsetFinder --game s1 search MzFire (734 bytes)
    public static final int ART_NEM_MZ_FIREBALL_ADDR = 0x32A7C;

    // Nem_Lava: Nemesis-compressed MZ lava geyser art (ArtTile_MZ_Lava = $3A8)
    // Used by Objects 0x4C (GeyserMaker) and 0x4D (LavaGeyser)
    // Verified by RomOffsetFinder --game s1 search Lava (2325 bytes)
    public static final int ART_NEM_LAVA_ADDR = 0x32D5A;

    // ---- Final Zone boss art & constants ----
    // Nem_FzBoss: Nemesis-compressed FZ boss art (cylinders, plasma, cockpit)
    public static final int ART_NEM_FZ_BOSS_ADDR = 0x5ECFA;
    // Nem_FzEggman: Nemesis-compressed "Eggman after FZ fight" art (legs + damaged ship)
    // Verified by RomOffsetFinder --game s1 search Nem_FzEggman
    public static final int ART_NEM_FZ_EGGMAN_ADDR = 0x5F462;
    // FZ boss VRAM tile addresses (from Constants.asm)
    public static final int ART_TILE_FZ_BOSS             = 0x300;
    public static final int ART_TILE_FZ_EGGMAN_FLEEING   = 0x3A0;
    public static final int ART_TILE_FZ_EGGMAN_NO_VEHICLE = 0x470;
    // FZ boss arena constants
    public static final int BOSS_FZ_X   = 0x2450;
    public static final int BOSS_FZ_Y   = 0x510;
    public static final int BOSS_FZ_END = BOSS_FZ_X + 0x2B0; // 0x2700

    // ---- Ending sequence art (Nemesis compressed) ----
    // Nem_EndSonic: Ending Sonic sprite art (4574 bytes, verified by RomOffsetFinder)
    public static final int ART_NEM_END_SONIC_ADDR = 0x5FD00;
    // Nem_EndEm: Ending chaos emeralds art (509 bytes, verified by RomOffsetFinder)
    public static final int ART_NEM_END_EMERALDS_ADDR = 0x5FB02;
    // Nem_EndStH: Ending "SONIC THE HEDGEHOG" logo art (647 bytes, verified by RomOffsetFinder)
    public static final int ART_NEM_END_STH_ADDR = 0x62638;
    // Nem_TryAgain: "TRY AGAIN" / "END" Eggman art (verified by RomOffsetFinder)
    public static final int ART_NEM_TRY_AGAIN_ADDR = 0x60EDE;

    // Nem_Sbz2Eggman: Eggman without vehicle (SBZ2/FZ) — loaded at ArtTile_FZ_Eggman_No_Vehicle
    // PLC: Nem_Sbz2Eggman -> ArtTile_FZ_Eggman_No_Vehicle ($470) — used by Map_SEgg
    public static final int ART_NEM_SBZ2_EGGMAN_ADDR = 0x5E4CE;

    // ---- SBZ2 cutscene art tile addresses (from PLC_EggmanSBZ2) ----
    // ArtTile_Eggman = $400 — SBZ2 Eggman VRAM base (palette line 0)
    public static final int ARTTILE_SBZ2_EGGMAN = 0x400;
    // ArtTile_Eggman_Button = $4A4 — button sprite base (loaded at $4A0, but $4A4 used for mapping)
    public static final int ARTTILE_SBZ2_BUTTON = 0x4A4;
    // ArtTile_Eggman_Trap_Floor = $518 — false floor block art
    public static final int ARTTILE_SBZ2_TRAP_FLOOR = 0x518;
    // Boss arena constants for SBZ2 cutscene
    public static final int BOSS_SBZ2_X = 0x2050;
    public static final int BOSS_SBZ2_Y = 0x510;

    // Nem_Eggman: Main Eggman ship + face + flame art (verified by RomOffsetFinder)
    public static final int ART_NEM_EGGMAN_ADDR = 0x5D0FC;
    // Nem_Weapons: Boss weapons art — chain anchor, pipes, spikes (verified)
    public static final int ART_NEM_BOSS_WEAPONS_ADDR = 0x5D960;
    // Nem_Exhaust: Boss exhaust/escape flame art (verified)
    public static final int ART_NEM_BOSS_EXHAUST_ADDR = 0x5F9E2;
    // Nem_Prison: Prison capsule art (verified)
    public static final int ART_NEM_PRISON_ADDR = 0x5DC4A;

    // ---- Loop / Plane Switching ----
    // LoopTileNums table from Sonic_Loops (_incObj/01 Sonic.asm lines 1536-1611).
    // Per-zone: { loop1, loop2, roll1, roll2 } - raw layout values including bit 7.
    // 0x7F means "disabled" (no loop/roll in that zone).
    public static final int LOOP_DISABLED = 0x7F;
    // GHZ loop tile IDs
    public static final int GHZ_LOOP1 = 0xB5;  // 0x35 | 0x80
    public static final int GHZ_LOOP2 = 0x7F;  // disabled
    public static final int GHZ_ROLL1 = 0x1F;
    public static final int GHZ_ROLL2 = 0x20;
    // SLZ loop tile IDs
    public static final int SLZ_LOOP1 = 0xAA;  // 0x2A | 0x80
    public static final int SLZ_LOOP2 = 0xB4;  // 0x34 | 0x80
    public static final int SLZ_ROLL1 = 0x7F;  // disabled
    public static final int SLZ_ROLL2 = 0x7F;  // disabled
    // Position thresholds within 256x256 block for plane switching
    public static final int LOOP_HIGH_PLANE_X_MAX = 0x2C; // X < 0x2C → high plane
    public static final int LOOP_LOW_PLANE_X_MIN = 0xE0;  // X >= 0xE0 → low plane
    // Special-case block remap: block 0x29 remaps to 0x51 in FindNearestTile
    public static final int LOOP_BLOCK_REMAP_FROM = 0x29;
    public static final int LOOP_BLOCK_REMAP_TO = 0x51;

    // ---- Results screen VRAM layout ----
    // ArtTile_Title_Card = $580: title card letters, zone names, oval, act numbers
    // ArtTile_HUD = $6CA = $580 + $14A: HUD text labels (SCORE/TIME/RINGS)
    // Bonus digits occupy $570-$57F (16 tiles below ArtTile_Title_Card)
    public static final int VRAM_RESULTS_BASE = 0x570;
    public static final int VRAM_RESULTS_TITLE_CARD = 0x580;
    public static final int VRAM_RESULTS_HUD_TEXT = 0x6CA;
    // Tile adjust: mapping tile IDs are relative to $580, array starts at $570
    public static final int RESULTS_TILE_ADJUST = VRAM_RESULTS_TITLE_CARD - VRAM_RESULTS_BASE; // 0x10
    // Bonus digit tiles: 2 groups (time bonus, ring bonus) x 4 digits x 2 tiles = 16
    public static final int S1_RESULTS_BONUS_DIGIT_TILES = 16;
    public static final int S1_RESULTS_BONUS_DIGIT_GROUP_TILES = 8;

    // ---- Special Stage data ----

    // SS_LayoutIndex: 6 longword pointers to Enigma-compressed stage layouts
    public static final int SS_LAYOUT_INDEX_ADDR = 0x1BE02;
    // Individual layout ROM offsets (verified via RomOffsetFinder)
    public static final int SS_LAYOUT_1_ADDR = 0x65432;
    public static final int SS_LAYOUT_2_ADDR = 0x656AC;
    public static final int SS_LAYOUT_3_ADDR = 0x65ABE;
    public static final int SS_LAYOUT_4_ADDR = 0x65E1A;
    public static final int SS_LAYOUT_5_ADDR = 0x662F4;
    public static final int SS_LAYOUT_6_ADDR = 0x667A4;

    // SS_StartLoc: 6 entries x 4 bytes (word X, word Y) immediately after SS_LayoutIndex
    public static final int SS_START_LOC_ADDR = 0x1BE1A;

    // Layout format constants
    public static final int SS_STAGE_COUNT = 6;
    public static final int SS_LAYOUT_STRIDE = 0x80;   // stride per row in block buffer
    public static final int SS_LAYOUT_COLS = 0x40;      // data columns per row
    public static final int SS_BLOCK_SIZE_PX = 0x18;    // 24px per block
    public static final int SS_LAYOUT_RAM_SIZE = 0x4000; // v_ssbuffer1..v_ssbuffer2 clear range
    public static final int SS_BLOCKBUFFER_OFFSET = 0x1020; // v_ssblockbuffer = v_ssbuffer1 + $1020
    public static final int SS_BLOCKBUFFER_ROWS = 0x40; // (v_ssblockbuffer_end-v_ssblockbuffer)/$80

    // ---- Special Stage palette ----
    // palid_Special = index 10 in PalIndex table
    public static final int PAL_SS_ADDR = 0x2460;       // Pal_Special (128 bytes, 4 palette lines)
    public static final int PAL_SS_SIZE = 128;
    public static final int PAL_SS_CYC1_ADDR = 0x4AD6;  // Pal_SSCyc1 (72 bytes)
    public static final int PAL_SS_CYC1_SIZE = 72;
    public static final int PAL_SS_CYC2_ADDR = 0x4B1E;  // Pal_SSCyc2 (210 bytes)
    public static final int PAL_SS_CYC2_SIZE = 210;

    // ---- Special Stage art (Nemesis compressed) ----
    // All verified via RomOffsetFinder --game s1
    public static final int ART_NEM_SS_WALLS_ADDR     = 0x2CA8E; // 2360 bytes
    public static final int ART_NEM_SS_WALLS_SIZE      = 2360;
    public static final int ART_NEM_SS_BUMPER_ADDR     = 0x342F8; // Nem_Bumper (SYZ bumper reused), 362 bytes
    public static final int ART_NEM_SS_BUMPER_SIZE     = 362;
    public static final int ART_NEM_SS_GOAL_ADDR       = 0x2E97E; // 237 bytes
    public static final int ART_NEM_SS_GOAL_SIZE       = 237;
    public static final int ART_NEM_SS_UPDOWN_ADDR     = 0x2F1E0; // 500 bytes
    public static final int ART_NEM_SS_UPDOWN_SIZE     = 500;
    public static final int ART_NEM_SS_RBLOCK_ADDR     = 0x2EA6C; // 207 bytes
    public static final int ART_NEM_SS_RBLOCK_SIZE     = 207;
    public static final int ART_NEM_SS_1UP_ADDR        = 0x2EB3C; // 245 bytes
    public static final int ART_NEM_SS_1UP_SIZE        = 245;
    public static final int ART_NEM_SS_EM_STARS_ADDR   = 0x2EC32; // 93 bytes
    public static final int ART_NEM_SS_EM_STARS_SIZE   = 93;
    public static final int ART_NEM_SS_RED_WHITE_ADDR  = 0x2EC90; // 145 bytes
    public static final int ART_NEM_SS_RED_WHITE_SIZE  = 145;
    public static final int ART_NEM_SS_GHOST_ADDR      = 0x2F53C; // 176 bytes
    public static final int ART_NEM_SS_GHOST_SIZE      = 176;
    public static final int ART_NEM_SS_WBLOCK_ADDR     = 0x2F5EC; // 218 bytes
    public static final int ART_NEM_SS_WBLOCK_SIZE     = 218;
    public static final int ART_NEM_SS_GLASS_ADDR      = 0x2F6C6; // 131 bytes
    public static final int ART_NEM_SS_GLASS_SIZE      = 131;
    public static final int ART_NEM_SS_EMERALD_ADDR    = 0x2F3D4; // 359 bytes
    public static final int ART_NEM_SS_EMERALD_SIZE    = 359;
    public static final int ART_NEM_SS_ZONE1_ADDR      = 0x2ED22; // 193 bytes
    public static final int ART_NEM_SS_ZONE1_SIZE      = 193;
    public static final int ART_NEM_SS_ZONE2_ADDR      = 0x2EDE4; // 206 bytes
    public static final int ART_NEM_SS_ZONE2_SIZE      = 206;
    public static final int ART_NEM_SS_ZONE3_ADDR      = 0x2EEB2; // 203 bytes
    public static final int ART_NEM_SS_ZONE3_SIZE      = 203;
    public static final int ART_NEM_SS_ZONE4_ADDR      = 0x2EF7E; // 199 bytes
    public static final int ART_NEM_SS_ZONE4_SIZE      = 199;
    public static final int ART_NEM_SS_ZONE5_ADDR      = 0x2F046; // 201 bytes
    public static final int ART_NEM_SS_ZONE5_SIZE      = 201;
    public static final int ART_NEM_SS_ZONE6_ADDR      = 0x2F110; // 207 bytes
    public static final int ART_NEM_SS_ZONE6_SIZE      = 207;
    public static final int ART_NEM_SS_RESULT_EM_ADDR  = 0x2F74A; // 382 bytes
    public static final int ART_NEM_SS_RESULT_EM_SIZE  = 382;

    // Background art (Nemesis compressed)
    public static final int ART_NEM_SS_BG_CLOUD_ADDR   = 0x2E48A; // 1268 bytes
    public static final int ART_NEM_SS_BG_CLOUD_SIZE   = 1268;
    public static final int ART_NEM_SS_BG_FISH_ADDR    = 0x2D4FA; // 3215 bytes
    public static final int ART_NEM_SS_BG_FISH_SIZE    = 3215;

    // Background tilemaps (Enigma compressed)
    public static final int ENI_SS_BG1_ADDR            = 0x2D3C6; // 308 bytes
    public static final int ENI_SS_BG1_SIZE            = 308;
    public static final int ENI_SS_BG2_ADDR            = 0x2E18A; // 768 bytes
    public static final int ENI_SS_BG2_SIZE            = 768;

    // ---- Special Stage ArtTile bases (from Constants.asm) ----
    public static final int ARTTILE_SS_BG_CLOUDS       = 0x000;
    public static final int ARTTILE_SS_BG_FISH         = 0x051;
    public static final int ARTTILE_SS_WALL            = 0x142;
    public static final int ARTTILE_SS_BUMPER          = 0x23B;
    public static final int ARTTILE_SS_GOAL            = 0x251;
    public static final int ARTTILE_SS_UP_DOWN         = 0x263;
    public static final int ARTTILE_SS_R_BLOCK         = 0x2F0;
    public static final int ARTTILE_SS_EXTRA_LIFE      = 0x370;
    public static final int ARTTILE_SS_EMERALD_SPARKLE = 0x3F0;
    public static final int ARTTILE_SS_RED_WHITE       = 0x470;
    public static final int ARTTILE_SS_GHOST           = 0x4F0;
    public static final int ARTTILE_SS_W_BLOCK         = 0x570;
    public static final int ARTTILE_SS_GLASS           = 0x5F0;
    public static final int ARTTILE_SS_EMERALD         = 0x770;
    public static final int ARTTILE_SS_ZONE_1          = 0x797;
    public static final int ARTTILE_SS_ZONE_2          = 0x7A0;
    public static final int ARTTILE_SS_ZONE_3          = 0x7A9;
    public static final int ARTTILE_SS_ZONE_4          = 0x7B2;
    public static final int ARTTILE_SS_ZONE_5          = 0x7BB;
    public static final int ARTTILE_SS_ZONE_6          = 0x7C4;

    // ---- Special Stage BG animation data (from SS_BGAnimate, sonic.asm) ----

    /**
     * byte_4CCC: 10 sine oscillator amplitude/speed pairs.
     * Each oscillator has: amplitude (unsigned byte), speed (signed byte).
     * ROM data: 8,2, 4,$FF, 2,3, 8,$FF, 4,2, 2,3, 8,$FD, 4,2, 2,3, 2,$FF
     */
    public static final int[] SS_BG_SINE_AMPLITUDES = {8, 4, 2, 8, 4, 2, 8, 4, 2, 2};
    public static final int[] SS_BG_SINE_SPEEDS = {2, -1, 3, -1, 2, 3, -3, 2, 3, -1};

    /**
     * byte_4CB8: Sine band widths. First byte = band count - 1, rest = scanline heights.
     * Widths: 40,24,16,40,24,16,48,24,8,16 = 256 total.
     */
    public static final int[] SS_SINE_BAND_WIDTHS = {9, 0x28, 0x18, 0x10, 0x28, 0x18, 0x10, 0x30, 0x18, 8, 0x10};

    /**
     * byte_4CC4: Scroll band widths for band-scroll transitions.
     * First byte = band count - 1, rest = scanline heights.
     * Widths: 48,48,48,40,24,24,24 = 256 total.
     */
    public static final int[] SS_SCROLL_BAND_WIDTHS = {6, 0x30, 0x30, 0x30, 0x28, 0x18, 0x18, 0x18};

    // Nem_Jaws: Nemesis-compressed Jaws badnik art (LZ, 650 bytes)
    // ArtTile_Jaws = $486, palette line 1, loaded via PLC_LZ2
    // Verified by RomOffsetFinder --game s1 search Jaws
    public static final int ART_NEM_JAWS_ADDR = 0x3727E;

    // Nem_Harpoon: Nemesis-compressed LZ harpoon spike trap art (347 bytes)
    // ArtTile_LZ_Harpoon = $3CC, loaded via PLC_LZ
    // Verified by RomOffsetFinder --game s1 search Harpoon
    public static final int ART_NEM_LZ_HARPOON_ADDR = 0x31696;

    // Nem_Gargoyle: Nemesis-compressed LZ gargoyle head & fireball art (367 bytes)
    // ArtTile_LZ_Gargoyle = $2E9, loaded via PLC_LZ2
    // Verified by RomOffsetFinder --game s1 search Gargoyle
    public static final int ART_NEM_LZ_GARGOYLE_ADDR = 0x31E8A;

    // Nem_LzWheel: Nemesis-compressed LZ conveyor belt wheel/platform art (1249 bytes)
    // ArtTile_LZ_Conveyor_Belt = $3F6, loaded via PLC_LZ2
    // Verified by RomOffsetFinder --game s1 search LzWheel
    public static final int ART_NEM_LZ_WHEEL_ADDR = 0x319A8;

    // ArtTile_LZ_Conveyor_Belt VDP tile index (from Constants.asm)
    public static final int ARTTILE_LZ_CONVEYOR_BELT = 0x3F6;

    // Nem_Bubbles: Nemesis-compressed LZ bubble & countdown art (1622 bytes)
    // ArtTile_LZ_Bubbles = $348, loaded via PLC_LZ
    // Verified by RomOffsetFinder --game s1 search Bubbles
    public static final int ART_NEM_LZ_BUBBLES_ADDR = 0x30EE8;

    // ---- Labyrinth Zone water surface art (Nemesis compressed) ----
    // Nem_Water: 292 bytes compressed, 16 tiles decompressed (2 sets of 8 tiles).
    // Loaded into VRAM at ArtTile_LZ_Water_Surface ($300) via PLC_LZ.
    // Verified by RomOffsetFinder --game s1 search Water
    public static final int ART_NEM_LZ_WATER_SURFACE_ADDR = 0x302E6;

    // ---- Labyrinth Zone water heights (LZWaterFeatures.asm lines 49-52) ----
    // Values are Y position in pixels from level top.
    // S1 bakes these into the ASM rather than a ROM table (unlike S2).
    public static final int WATER_HEIGHT_LZ1  = 0x00B8; // LZ Act 1: 184px
    public static final int WATER_HEIGHT_LZ2  = 0x0328; // LZ Act 2: 808px
    public static final int WATER_HEIGHT_LZ3  = 0x0900; // LZ Act 3: 2304px
    public static final int WATER_HEIGHT_SBZ3 = 0x0228; // SBZ Act 3 (reuses LZ water): 552px

    // ---- Underwater palette ROM addresses (verified by RomOffsetFinder --game s1) ----
    // Pal_LZWater: 128 bytes (4 palette lines), all underwater palette lines for LZ
    public static final int PAL_LZ_UNDERWATER_ADDR      = 0x2460;
    public static final int PAL_LZ_UNDERWATER_SIZE      = 128;
    // Pal_SBZ3Water: 128 bytes (4 palette lines), underwater palette lines for SBZ3
    public static final int PAL_SBZ3_UNDERWATER_ADDR    = 0x27A0;
    public static final int PAL_SBZ3_UNDERWATER_SIZE    = 128;
    // Pal_LZSonWater: 32 bytes (1 palette line), Sonic's underwater palette for LZ
    public static final int PAL_LZ_SONIC_UNDERWATER_ADDR  = 0x2820;
    public static final int PAL_LZ_SONIC_UNDERWATER_SIZE  = 32;
    // Pal_SBZ3SonWat: 32 bytes (1 palette line), Sonic's underwater palette for SBZ3
    public static final int PAL_SBZ3_SONIC_UNDERWATER_ADDR = 0x2840;
    public static final int PAL_SBZ3_SONIC_UNDERWATER_SIZE = 32;

    // ---- Special Stage physics constants (from Obj09) ----
    public static final int SS_ACCEL           = 0x0C;   // movement acceleration
    public static final int SS_BRAKE           = 0x40;   // braking deceleration
    public static final int SS_MAX_SPEED       = 0x800;  // max inertia
    public static final int SS_JUMP_FORCE      = 0x680;  // jump force multiplier
    public static final int SS_GRAVITY         = 0x2A;   // gravity multiplier
    public static final int SS_BUMPER_FORCE    = 0x700;  // bumper bounce force
    public static final int SS_INIT_ROTATION   = 0x40;   // initial rotation speed
    public static final int SS_UP_DOWN_COOLDOWN = 0x1E;  // UP/DOWN/R block cooldown frames
    public static final int SS_JUMP_BUTTONS     = 0x70;  // btnABC: A/B/C bits SonicSS_Jump tests (v_jpadpress2)
}
