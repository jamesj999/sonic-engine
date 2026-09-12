package com.openggf.game.sonic3k.constants;

import com.openggf.level.Pattern;

/**
 * ROM offset constants for Sonic 3 &amp; Knuckles (combined S3K ROM).
 *
 * <p>Verified addresses are for the standard "Sonic and Knuckles &amp; Sonic 3 (W) [!].gen"
 * combined ROM (~4MB). Addresses were verified by binary pattern matching against
 * the skdisasm data files on 2026-02-08.
 *
 * <p>Key data format differences from Sonic 2:
 * <ul>
 *   <li>8x8 pattern art: Kosinski Moduled (KosM), not plain Kosinski</li>
 *   <li>LevelLoadBlock: 24 bytes/entry (6 longwords via levartptrs macro)</li>
 *   <li>Level layout: uncompressed, variable size per act</li>
 *   <li>Collision index: noninterleaved format (primary 0x600 + secondary 0x600),
 *       noninterleaved entries commonly use pointer bit 0 (+1 marker),
 *       bit 31 reserved for S3Complete builds</li>
 *   <li>Start locations: per-character tables (Sonic vs Knuckles)</li>
 * </ul>
 */
public class Sonic3kConstants {
    /**
     * Uncompressed Map_Ring in the S&K half; Obj_RingInit's pointer is at $01A538.
     * make_art_tile(ArtTile_Ring,1,1) supplies palette line 1; pieces have offset 0.
     */
    public static final int MAP_RING_ADDR = 0x01A99A;

    /** Obj_FBZCloud stores exactly ten stable addresses at FBZ_cloud_addr. */
    public static final int FBZ_CLOUD_REWIND_SLOT_COUNT = 10;

    private Sonic3kConstants() {}

    // ===== LevelLoadBlock table =====
    // 24 bytes per entry (via levartptrs macro):
    //   dc.l (plc1<<24)|art1        - PLC index + primary 8x8 art (KosM)
    //   dc.l (plc2<<24)|art2        - PLC index + secondary 8x8 art (KosM)
    //   dc.l (palette<<24)|blocks1  - palette index + primary 16x16 blocks (Kos)
    //   dc.l (palette<<24)|blocks2  - palette index + secondary 16x16 blocks (Kos)
    //   dc.l chunks1                - primary 128x128 chunks (Kos)
    //   dc.l chunks2                - secondary 128x128 chunks (Kos)
    public static int LEVEL_LOAD_BLOCK_ADDR = 0x091F0C;
    public static final int LEVEL_LOAD_BLOCK_ENTRY_SIZE = 24;
    // LevelLoadBlock entry index for "SONIC/TAILS INTRO" in sonic3k.asm levartptrs table.
    // Used by AIZ1 intro-skip bootstrap to source gameplay-ready 16x16/8x8 secondary data.
    public static final int LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX = 26;
    // Current_zone_and_act=$1701: nonlinear resource slot used by the
    // Super Emerald sanctuary presented to the engine as canonical HPZ $1601.
    public static final int LEVEL_LOAD_BLOCK_HPZ_SANCTUARY_INDEX = 47;
    public static final int HPZ_SANCTUARY_LAYOUT_ADDR = 0x0A7924;
    public static final int HPZ_PRIMARY_ART_ADDR = 0x1BEE58;
    public static final int HPZ_SECONDARY_ART_ADDR = 0x1C3F2C;
    public static final int HPZ_PRIMARY_BLOCKS_ADDR = 0x1BECF8;
    public static final int HPZ_SECONDARY_BLOCKS_ADDR = 0x1C30FC;
    public static final int HPZ_PRIMARY_CHUNKS_ADDR = 0x1BFBEA;
    public static final int HPZ_SECONDARY_CHUNKS_ADDR = 0x1C71FE;
    public static final int HPZ_INTRO_PALETTE_ADDR = 0x0A9D3C;
    public static final int HPZ_MAIN_PALETTE_ADDR = 0x0669D2;
    public static final int HPZ_LEVEL_PLC = 0x48;

    // HPZ Super Emerald sanctuary object assets (S&K half).
    // PLC $48 supplies the two Nemesis archives. Obj_HPZSSEntryControl queues
    // the Kosinski-moduled teleporter and small-emerald archives separately.
    public static final int ART_NEM_HPZ_EMERALD_MISC_ADDR = 0x174B28;
    public static final int ART_NEM_HPZ_GRAY_EMERALD_ADDR = 0x1757B4;
    public static final int ART_KOSM_TELEPORTER_ADDR = 0x17588A;
    public static final int ART_KOSM_HPZ_SMALL_EMERALDS_ADDR = 0x1759AC;
    public static final int MAP_HPZ_EMERALD_MISC_ADDR = 0x091006;
    public static final int MAP_HPZ_CHAOS_EMERALDS_ADDR = 0x09147E;
    /** {@code off_914CE}: completed Master Emerald palette rotation script. */
    public static final int HPZ_MASTER_EMERALD_PALETTE_SCRIPT_ADDR = 0x0914CE;
    /** {@code RawAni_90768}: completed Master Emerald glow frames. */
    public static final int HPZ_MASTER_EMERALD_GLOW_ANIMATION_ADDR = 0x090768;
    public static final int ARTTILE_HPZ_EMERALD_MISC = 0x03B5;
    public static final int ARTTILE_HPZ_GRAY_EMERALD = 0x0477;
    public static final int ARTTILE_HPZ_ENTRY_TELEPORTER = 0x0488;
    public static final int ARTTILE_HPZ_SMALL_EMERALDS = 0x04AC;
    public static final int ARTTILE_HPZ_TELEPORTER = 0x052E;

    // ===== Level sizes table =====
    // 8 bytes per act: dc.w xstart, xend, ystart, yend
    // Sequential: AIZ1, AIZ2, HCZ1, HCZ2, ...
    public static int LEVEL_SIZES_ADDR = 0x01BCC6;
    public static final int LEVEL_SIZES_ENTRY_SIZE = 8;
    // LevelSizes entry index for "AIZ Intro" (Current_zone_and_act = $0D00).
    // This has the taller vertical bounds needed for post-intro AIZ1 gameplay.
    public static final int LEVEL_SIZES_AIZ1_INTRO_INDEX = 26;

    // ===== Start location tables =====
    // 4 bytes per act: dc.w x_pos, y_pos
    // Sequential: AIZ1, AIZ2, HCZ1, HCZ2, ... (48 entries each)
    public static int SONIC_START_LOCATIONS_ADDR = 0x1E3C18;
    public static int KNUX_START_LOCATIONS_ADDR = 0x1E3CD8;
    public static final int START_LOCATION_ENTRY_SIZE = 4;
    public static final int START_LOCATION_ENTRY_COUNT = 48;

    // ===== Object and ring position pointer tables =====
    // 4 bytes per act: dc.l data_addr
    // Sequential: AIZ1, AIZ2, HCZ1, HCZ2, ...
    public static int SPRITE_LOC_PTRS_ADDR = 0x1E3D98;
    public static int RING_LOC_PTRS_ADDR = 0x1E3E58;

    // ===== Layout pointer table =====
    // 4 bytes per act: dc.l layout_addr
    // Sequential: AIZ1, AIZ2, HCZ1, HCZ2, ...
    public static int LEVEL_PTRS_ADDR = 0x09D5C0;
    public static final int LEVEL_PTRS_ENTRY_SIZE = 4;

    // ===== Layout data format =====
    // Uncompressed, variable size per act.
    // Layout format: FG layer followed by BG layer, each composed of rows of chunk indices.
    public static final int LEVEL_LAYOUT_TOTAL_SIZE = 0x1000;
    public static final int LEVEL_LAYOUT_HEADER_SIZE = 8;
    public static final int LEVEL_LAYOUT_RAM_BASE = 0x8000;
    public static final int LEVEL_LAYOUT_ROW_POINTER_MASK = 0x7FFF;

    // ===== Collision =====
    // SolidIndexes: 4 bytes per act (indexed as zone*2+act), dc.l pointing to collision index data
    // Format detection: addresses >= S3_LEVEL_SOLID_DATA are non-interleaved (S3 zones),
    //                   addresses < S3_LEVEL_SOLID_DATA are interleaved (SK zones)
    // Non-interleaved: primary 0x600 bytes, then secondary 0x600 bytes
    // Interleaved: primary/secondary alternate bytes in 0xC00 block
    public static int SOLID_INDEXES_ADDR = 0x098100;
    public static final int SOLID_INDEXES_ENTRY_SIZE = 4;
    public static final int COLLISION_INDEX_SIZE = 0x600; // per layer (primary or secondary)
    public static final int COLLISION_INDEX_STRIDE_BYTES = 2;

    // Address threshold for collision format detection (from sonic3k.asm LoadSolids routine)
    // S3 zones have collision data at >= this address (non-interleaved)
    // SK zones have collision data below this address (interleaved)
    public static final int S3_LEVEL_SOLID_DATA = 0x260000;

    // Height maps and angles
    // AngleArray: 256 bytes of tile slope angles
    // HeightMaps: 256 entries x 16 bytes = 4096 bytes (vertical collision)
    // HeightMapsRot: 256 entries x 16 bytes = 4096 bytes (horizontal collision)
    public static int SOLID_TILE_ANGLE_ADDR = 0x096000;
    public static int SOLID_TILE_VERTICAL_MAP_ADDR = 0x096100;
    public static int SOLID_TILE_HORIZONTAL_MAP_ADDR = 0x097100;
    public static final int SOLID_TILE_MAP_SIZE = 0x1000;  // 256 x 16 bytes
    public static final int SOLID_TILE_ANGLE_SIZE = 0x100;  // 256 bytes

    // ===== Map dimensions =====
    // Safety caps for map allocation. Actual dimensions are derived per-level from
    // the layout header (fgColsPerRow × fgRows) in Sonic3kLevel.loadMap().
    // S3K levels vary widely: ICZ1 is 216×16, DEZ is 122×32, LBZ is 160×24.
    public static final int MAP_LAYERS = 2;
    public static final int MAP_WIDTH = 256;
    public static final int MAP_HEIGHT = 32;

    // ===== Acts per zone stride =====
    // The LevelLoadBlock indexes by zone*2+act (each zone has 2 act slots)
    public static final int ACTS_PER_ZONE_STRIDE = 2;

    // ===== Palette =====
    // PalPoint table: 8 bytes per entry (dc.l source_addr, dc.w ram_dest, dc.w longword_count)
    // Palette index from LevelLoadBlock selects entry in this table.
    // Index 3 = Pal_SonicTails, Index 5 = Pal_Knuckles
    public static int PAL_POINTERS_ADDR = 0x0A872C;
    public static final int PAL_POINTER_ENTRY_SIZE = 8;
    public static final int PAL_INDEX_SONIC_TAILS = 3;
    public static final int PAL_INDEX_KNUCKLES = 5;

    // Character palette addresses
    public static int SONIC_PALETTE_ADDR = 0x0A8A3C;  // Pal_SonicTails (64 bytes)
    public static int KNUCKLES_PALETTE_ADDR = 0x0A8AFC; // Pal_Knuckles (32 bytes)
    // S2-compatible Knuckles palette for "Knuckles in Sonic 2" lock-on.
    // Indices 2-5 have Knuckles' reds; indices 0-1 and 6-15 are identical
    // to S2's Pal_SonicTails, so title cards, badniks, etc. are unaffected.
    public static final int KNUCKLES_S2_PALETTE_ADDR = 0x060BEA;

    // Pal_WaterKnux - Knuckles water palette patch (42 bytes = 7 zones x 6 bytes)
    // 6 bytes per zone (3 Mega Drive colors), written to Water_palette+$04 (colors 2-4 of line 0)
    // Covers S3 zones only (0=AIZ through 6=LBZ). Verified via RomOffsetFinder find.
    public static final int PAL_WATER_KNUX_ADDR = 0x7A4A;
    public static final int PAL_WATER_KNUX_ENTRY_SIZE = 6; // 3 colors x 2 bytes each
    public static final int PAL_WATER_KNUX_ZONE_COUNT = 7; // Zones 0-6 (AIZ-LBZ)

    // ===== AIZ Intro Cinematic =====================================================
    // Addresses verified 2026-02-13 by ROM binary pattern search and LockOn Pointer
    // cumulative offset calculation, cross-checked against disassembly frame labels.

    // --- Art addresses ---
    // ArtKosM_AIZIntroPlane - Tornado biplane sprite art (KosinskiM compressed)
    // Decompresses to 4352 bytes (136 tiles, 8x8 @ 4bpp)
    public static final int ART_KOSM_AIZ_INTRO_PLANE_ADDR = 0x382624;

    // ArtKosM_AIZIntroEmeralds - Chaos Emerald sprite art (KosinskiM compressed)
    // Decompresses to 224 bytes (7 tiles)
    public static final int ART_KOSM_AIZ_INTRO_EMERALDS_ADDR = 0x387CA6;

    // ArtNem_AIZIntroSprites - Wave/water spray sprite art (Nemesis compressed)
    // Decompresses to 11008 bytes (344 tiles)
    public static final int ART_NEM_AIZ_INTRO_SPRITES_ADDR = 0x3481A0;
    public static final int ART_NEM_AIZ_SWING_VINE_ADDR = 0x38D8BC;
    public static final int ART_NEM_AIZ_SLIDE_ROPE_ADDR = 0x38DA22;
    public static final int ART_NEM_AIZ_MISC1_ADDR = 0x38DC90;
    public static final int ART_NEM_AIZ_FALLING_LOG_ADDR = 0x38E4D8;
    public static final int ART_NEM_AIZ_CORK_FLOOR_ADDR = 0x38D586;
    public static final int ART_NEM_AIZ_CORK_FLOOR_2_ADDR = 0x38D72A;

    // ===== Falling Log mappings (Obj_AIZFallingLog, ID 0x2D) =====
    // 4 mapping tables: act-specific log body + splash. ROM addresses from LockOn Data.asm.
    public static final int MAP_AIZ_FALLING_LOG_ADDR = 0x22AE30;         // Map_AIZFallingLog (Act 1 log, 1 frame)
    public static final int MAP_AIZ_FALLING_LOG_2_ADDR = 0x22AE20;       // Map_AIZFallingLog2 (Act 2 log, 1 frame)
    public static final int MAP_AIZ_FALLING_LOG_SPLASH_ADDR = 0x22AEB0;  // Map_AIZFallingLogSplash (Act 1 splash, 4 frames)
    public static final int MAP_AIZ_FALLING_LOG_SPLASH_2_ADDR = 0x22AE40; // Map_AIZFallingLogSplash2 (Act 2 splash, 4 frames)

    // ===== Spiked Log mappings (Obj_AIZSpikedLog, ID 0x2E) =====
    // Map_AIZSpikedLog: 16 frames (rotating spiked log platform).
    // art_tile = make_art_tile(ArtTile_AIZMisc2, 2, 0) → base tile 0x2E9, palette 2.
    public static final int MAP_AIZ_SPIKED_LOG_ADDR = 0x22B0F0;

    // ===== Cork Floor mappings (Obj_CorkFloor, ID 0x2A) =====
    // Each zone has its own mapping table: frame 0 = intact, frame 1 = broken fragments.
    // Addresses derived from frame labels in LockOn Data.asm sequential includes.
    public static final int MAP_AIZ_CORK_FLOOR_ADDR = 0x229B60;   // Map_AIZCorkFloor (2 frames, 6/12 pieces)
    public static final int MAP_AIZ_CORK_FLOOR_2_ADDR = 0x229BD4; // Map_AIZCorkFloor2 (2 frames, 6/12 pieces)
    public static final int MAP_CNZ_CORK_FLOOR_ADDR = 0x229C48;   // Map_CNZCorkFloor (2 frames, 8/16 pieces)
    public static final int MAP_ICZ_CORK_FLOOR_ADDR = 0x229CE0;   // Map_ICZCorkFloor (12 frames: 6 intact pairs + break frames)
    public static final int MAP_LBZ_CORK_FLOOR_ADDR = 0x229EE8;   // Map_LBZCorkFloor (2 frames, 8/16 pieces)
    public static final int MAP_LBZ_MOVING_PLATFORM_ADDR = 0x025338; // Map_LBZMovingPlatform (3 frames, S&K-side)
    // Map_LBZExplodingTrigger (1 frame). LBZ S3-era misc mapping referenced by Obj_LBZExplodingTrigger;
    // no S&K-side duplicate exists, verified by RomArtIntakeTool + byte search at 0x2249A8.
    public static final int MAP_LBZ_EXPLODING_TRIGGER_ADDR = 0x2249A8;
    // Map_LBZTriggerBridge (24 frames). Address derived from first frame label
    // Frame_224E16 minus 24 word offsets in the mapping table.
    public static final int MAP_LBZ_TRIGGER_BRIDGE_ADDR = 0x224DE6;
    public static final int MAP_LBZ_PLAYER_LAUNCHER_ADDR = 0x22534C; // Map_LBZPlayerLauncher (2 frames, S3K lock-on data)
    // Map_LBZFlameThrower (9 frames). LockOn S3 data; Obj_LBZFlameThrower in sonic3k.asm references this S3-half table.
    public static final int MAP_LBZ_FLAME_THROWER_ADDR = 0x22544E;
    public static final int MAP_LBZ_RIDE_GRAPPLE_ADDR = 0x026930; // Map_LBZRideGrapple (3 frames, S&K-side)
    // Map_LBZCupElevator (5 frames). LockOn data include; verified by ROM byte
    // search for offset-table prefix 00 0A 00 18 00 20 00 2E 00 42.
    public static final int MAP_LBZ_CUP_ELEVATOR_ADDR = 0x22619A;
    // Map_LBZGateLaser (3 frames). LockOn S3 data; Obj_LBZGateLaser in sonic3k.asm
    // references this S3-half table and no S&K-side duplicate exists.
    public static final int MAP_LBZ_GATE_LASER_ADDR = 0x228394;
    // Map_LBZSpinLauncher (1 frame). LockOn S3 data; Obj_LBZSpinLauncher in
    // sonic3k.asm references this S3-half table and no S&K-side duplicate exists.
    public static final int MAP_LBZ_SPIN_LAUNCHER_ADDR = 0x227BC2;
    // Map_LBZLoweringGrapple (15 frames). LockOn S3 data; Obj_LBZLoweringGrapple
    // in sonic3k.asm references this S3-half table and no S&K-side duplicate exists.
    public static final int MAP_LBZ_LOWERING_GRAPPLE_ADDR = 0x227DBC;
    // Map_LBZPipePlug (8 frames). LockOn S3 data; Obj_LBZPipePlug in
    // sonic3k.asm references this S3-half table and no S&K-side duplicate exists.
    public static final int MAP_LBZ_PIPE_PLUG_ADDR = 0x226854;
    public static final int MAP_TUNNEL_EXHAUST_ADDR = 0x029C8A; // Map_TunnelExhaust (2 frames, S&K-side)
    public static final int MAP_FBZ_CORK_FLOOR_ADDR = 0x2A920;    // Map_FBZCorkFloor (2 frames, 2/4 pieces, in sonic3k.asm)
    public static final int MAP_FBZ_FLOATING_PLATFORM_ADDR = 0x03A742;
    public static final int MAP_FBZ_CHAIN_LINK_ADDR = 0x03AD8A;
    public static final int MAP_FBZ_MAGNETIC_SPIKE_BALL_ADDR = 0x03B25C;
    public static final int MAP_FBZ_MAGNETIC_PLATFORM_ADDR = 0x03B4DE;
    public static final int MAP_FBZ_SNAKE_PLATFORM_ADDR = 0x03B6CE;
    public static final int MAP_FBZ_BENT_PIPE_ADDR = 0x03B73C;
    public static final int MAP_FBZ_ROTATING_PLATFORM_ADDR = 0x03B91A;
    public static final int MAP_FBZ_DISAPPEARING_PLATFORM_ADDR = 0x03BBBE;
    public static final int MAP_FBZ_SCREW_DOOR_ADDR = 0x03BD8E;
    public static final int MAP_FBZ_SPINNING_POLE_ADDR = 0x03C19C;
    public static final int MAP_FBZ_PROPELLER_ADDR = 0x03C20C;
    public static final int MAP_FBZ_PISTON_ADDR = 0x03C328;
    public static final int MAP_FBZ_PLATFORM_BLOCKS_ADDR = 0x03C416;
    public static final int MAP_FBZ_MISSILE_LAUNCHER_ADDR = 0x03C78E;
    public static final int MAP_FBZ_WALL_MISSILE_ADDR = 0x03C906;
    public static final int MAP_FBZ_MINE_ADDR = 0x03CA06;
    /** Locked-on {@code Map_FBZElevator}; S&K-side mapping table. */
    public static final int MAP_FBZ_ELEVATOR_ADDR = 0x03CB0C;
    public static final int MAP_FBZ_TRAP_SPRING_ADDR = 0x03CC5A;
    public static final int MAP_FBZ_FLAMETHROWER_ADDR = 0x03CFD0;
    public static final int MAP_FBZ_SPIDER_CRANE_ADDR = 0x03D2FC;
    public static final int MAP_FBZ_MAGNETIC_PENDULUM_ADDR = 0x03D9AE;
    public static final int ARTTILE_FBZ_OUTDOORS = 0x02E5;
    public static final int ARTTILE_FBZ_MISC2 = 0x02D2;
    public static final int MAP_FBZ_DEZ_PLAYER_LAUNCHER_ADDR = 0x03BA8A; // Map_FBZDEZPlayerLauncher (2 frames, sonic3k.asm:79510)

    // ===== Breakable Wall mappings (Obj_BreakableWall, ID 0x0D) =====
    // Each zone has its own mapping table: even frames = intact, odd frames = broken fragments.
    // Provenance is per mapping table: AIZ/HCZ/MGZ/LBZ currently use lock-on
    // S3-side tables; CNZ/SOZ, MHZ, and LRZ use S&K-side tables. Verify labels
    // and ROM bytes before changing an address rather than inferring from zone era.
    public static final int MAP_AIZ_BREAKABLE_WALL_ADDR = 0x21FD52;     // Map_AIZBreakableWall (6 frames)
    public static final int MAP_HCZ_BREAKABLE_WALL_ADDR = 0x21FFD8;     // Map_HCZBreakableWall (4 frames)
    public static final int MAP_MGZ_BREAKABLE_WALL_ADDR = 0x21FF18;     // Map_MGZBreakableWall (4 frames)
    public static final int MAP_CNZ_SOZ_BREAKABLE_WALL_ADDR = 0x021ABA; // Map_CNZSOZBreakableWall (6 frames)
    public static final int MAP_LBZ_BREAKABLE_WALL_ADDR = 0x22005E;     // Map_LBZBreakableWall (2 frames)
    public static final int MAP_MHZ_BREAKABLE_WALL_ADDR = 0x021BCE;     // Map_MHZBreakableWall (2 frames)
    public static final int MAP_LRZ_BREAKABLE_WALL_ADDR = 0x021C1E;     // Map_LRZBreakableWall (2 frames)

    // ArtTile constants for breakable walls (from sonic3k.constants.asm)
    public static final int ARTTILE_HCZ2_KNUX_WALL = 0x0350;   // ArtTile_HCZ2KnuxWall
    public static final int ARTTILE_CNZ_MISC = 0x0351;          // ArtTile_CNZMisc
    public static final int ARTTILE_LBZ2_MISC = 0x02EA;         // ArtTile_LBZ2Misc
    public static final int ARTTILE_MHZ_MISC = 0x0347;          // ArtTile_MHZMisc
    public static final int ARTTILE_SOZ_MISC = 0x03C9;          // ArtTile_SOZMisc

    // ===== HCZ Breakable Bar mappings (Obj_HCZBreakableBar, ID 0x36) =====
    // LockOn data (assembled into S3 half of combined ROM — no S&K-side copy exists)
    public static final int MAP_HCZ_BREAKABLE_BAR_ADDR = 0x21CDCA; // Map_HCZBreakableBar (8 frames)

    // ===== HCZ Conveyor Spike (Obj_HCZConveyorSpike, ID 0x3F) =====
    // LockOn data (S3 half only — no S&K-side copy). Verified via ROM byte search
    // for the assembled single-frame mapping table.
    public static final int MAP_HCZ_CONVEYOR_SPIKE_ADDR = 0x23035E; // Map_HCZConveyorSpike (1 frame)
    // ROM: make_art_tile(ArtTile_HCZSpikeBall, 1, 0)
    public static final int ARTTILE_HCZ_CONVEYOR_SPIKE = 0x043E;

    // ===== MGZ/LBZ Smashing Pillar (Obj_MGZLBZSmashingPillar, IDs 0x20 + 0x52) =====
    // Map_LBZSmashingSpikes_: 1-word offset table + 1 frame (2 pieces = $E bytes) = $10 total.
    // Map_MGZSmashingPillar_: 1-word offset table + 1 frame ($A pieces = $3E bytes) = $40 total.
    // LockOn data (S3 half — no S&K-side copy). Verified via ROM byte search at 0x228246/0x228236.
    public static final int MAP_LBZ_SMASHING_SPIKES_ADDR = 0x228236; // Map_LBZSmashingSpikes (1 frame, 2 pieces)
    public static final int MAP_MGZ_SMASHING_PILLAR_ADDR = 0x228246; // Map_MGZSmashingPillar (1 frame, 10 pieces)
    // Map_LBZTubeElevator (7 frames). Verified by ROM search for the assembled
    // offset-table prefix 00 0E 00 4C 00 8A 00 B6 00 F4 01 20 01 5E.
    public static final int MAP_LBZ_TUBE_ELEVATOR_ADDR = 0x2291A4;
    // ArtTile_LBZTubeTrans = $0455 (from sonic3k.constants.asm)
    public static final int ARTTILE_LBZ_TUBE_TRANS = 0x0455;

    // Map_MGZSwingingPlatform has a 3-word frame-offset table immediately before Frame_23331E.
    public static final int MAP_MGZ_SWINGING_PLATFORM_ADDR = 0x233318; // Map_MGZSwingingPlatform (3 frames)
    // Map_MGZTriggerPlatform_ has a 2-word frame-offset table immediately before Frame_2339B6.
    // Base = first frame payload ($2339B6) - 4 bytes of offsets = $2339B2.
    public static final int MAP_MGZ_TRIGGER_PLATFORM_ADDR = 0x2339B2; // Map_MGZTriggerPlatform (2 frames)
    // Map_MGZSwingingSpikeBall_ has a 4-word frame-offset table immediately before Frame_23357A.
    public static final int MAP_MGZ_SWINGING_SPIKE_BALL_ADDR = 0x233572; // Map_MGZSwingingSpikeBall (4 frames)
    // Map_MGZDashTrigger_ has a 5-word frame-offset table immediately before Frame_224B92.
    // LockOn data (assembled into S3 half — no S&K-side copy exists).
    public static final int MAP_MGZ_DASH_TRIGGER_ADDR = 0x224B88; // Map_MGZDashTrigger (5 frames)
    public static final int ARTTILE_MGZ_MISC1 = 0x035F; // ArtTile_MGZMisc1
    public static final int ARTTILE_MGZ_MISC2 = 0x03FF; // ArtTile_MGZMisc2

    // ===== MGZ Pulley (Obj_MGZPulley, ID 0x5A) =====
    // Mapping table has 7 word offsets at 0x2340C0; first frame payload starts at 0x2340CE.
    // Using 0x2340CE causes the loader to treat frame payload bytes as frame offsets.
    public static final int MAP_MGZ_PULLEY_ADDR = 0x2340C0; // Map_MGZPulley (7 frames)

    // Map_MGZHeadTrigger_ has an 8-word frame-offset table immediately before Frame_233822.
    // Base = first frame payload ($233822) - $10 bytes of offsets = $233812. Verified via
    // ROM search for Frame_233822 bytes "00 01 FC 0C E0 4E FF F0".
    public static final int MAP_MGZ_HEAD_TRIGGER_ADDR = 0x233812; // Map_MGZHeadTrigger (8 frames)

    // Map_MGZMovingSpikePlatform_ has a 4-word frame-offset table immediately before Frame_233BA0.
    // Base = first frame payload ($233BA0) - 8 bytes of offsets = $233B98. Verified via
    // ROM search for offset table bytes "00 08 00 52 00 9C 00 E6".
    public static final int MAP_MGZ_MOVING_SPIKE_PLATFORM_ADDR = 0x233B98; // Map_MGZMovingSpikePlatform (4 frames)

    // ===== MGZ Top Platform / Launcher (Obj_MGZTopPlatform, ID 0x5B) =====
    // Map_MGZTopPlatform_ has a 3-word frame-offset table immediately before word_3596A.
    // Base = word_3596A ($3596A) - 6 bytes of offsets = $035964.
    public static final int MAP_MGZ_TOP_PLATFORM_ADDR = 0x035964; // Map_MGZTopPlatform (3 frames)
    // Waypoint tables for sub_35666/sub_35868 arc-teleport system.
    // Each entry = 16 bytes: trigger_x, trigger_y, flag_word, dest_x, dest_y_raw, dest_y_delta, ...
    // Header word is (count - 1) for a dbf loop; full size is 7 entries × 16 bytes.
    public static final int MGZ_TOP_PLATFORM_WAYPOINTS_ACT1_ADDR = 0x035784; // word_35784
    public static final int MGZ_TOP_PLATFORM_WAYPOINTS_ACT2_ADDR = 0x0357F6; // word_357F6

    // ===== HCZ Block mappings (Obj_HCZBlock, ID 0x40) =====
    // LockOn data (assembled into S3 half of combined ROM — no S&K-side copy exists).
    // Derived from the frame labels in Map - Block.asm: Frame_21D052 starts 8 bytes after the table base.
    public static final int MAP_HCZ_BLOCK_ADDR = 0x21D04A; // Map_HCZBlock (4 frames)
    // Map_HCZSpinningColumn_ has a 3-word frame-offset table immediately before Frame_231AF4.
    // Use the table base, not the first frame payload, or the renderer will parse garbage.
    public static final int MAP_HCZ_SPINNING_COLUMN_ADDR = 0x231AEE; // Map_HCZSpinningColumn (3 frames)

    // ===== HCZ Water Rush (Obj_HCZWaterRush, ID 0x37) =====
    // ArtNem_HCZWaterRush: Nemesis compressed, 2560 bytes decompressed (80 tiles)
    // Loaded via PLC_0E. LockOn data (S3 half only — no S&K-side copy).
    public static final int ART_NEM_HCZ_WATER_RUSH_ADDR = 0x390348;
    // ArtTile_HCZWaterRush = $037A (from sonic3k.constants.asm)
    public static final int ARTTILE_HCZ_WATER_RUSH = 0x037A;
    // WaterRushBlock uses ArtTile_HCZMisc + $A = 0x03D4 (level-loaded art)
    public static final int ARTTILE_HCZ_WATER_RUSH_BLOCK = 0x03CA + 0xA; // 0x03D4
    // Map_HCZWaterRush: 4 frames. LockOn data referenced by sonic3k.asm Obj_HCZWaterRush.
    public static final int MAP_HCZ_WATER_RUSH_ADDR = 0x22E60C;
    // Map_HCZWaterRushBlock: 2 frames. LockOn data (S3 half).
    public static final int MAP_HCZ_WATER_RUSH_BLOCK_ADDR = 0x22E76C;

    // ===== HCZ Hand Launcher (Obj_HCZHandLauncher, ID 0x3A) =====
    // Map_HCZHandLauncher: 8 frames (6-piece arm cycle x6, 3-piece cup, 1-piece compact).
    // LockOn data (S3 half only — no S&K-side copy).
    public static final int MAP_HCZ_HAND_LAUNCHER_ADDR = 0x22F998;
    // Hand uses ArtTile_HCZMisc + $1A = $03E4, palette 1
    public static final int ARTTILE_HCZ_HAND_LAUNCHER = 0x03CA + 0x1A; // ArtTile_HCZMisc + $1A = 0x03E4

    // ===== HCZ/CGZ Fan (Obj_HCZCGZFan, ID 0x38) =====
    // Map_HCZFan: 5 frames, 3 pieces each. LockOn data (S3 half).
    public static final int MAP_HCZ_FAN_ADDR = 0x22F3AA;
    // Fan uses ArtTile_HCZMisc + $41 = $040B, palette 1
    public static final int ARTTILE_HCZ_FAN = 0x03CA + 0x41; // ArtTile_HCZMisc + $41 = 0x040B

    // ===== HCZ Large Fan (Obj_HCZLargeFan, ID 0x39) =====
    // Map_HCZLargeFan immediately follows Map_HCZFan in LockOn S3 pointer data.
    public static final int MAP_HCZ_LARGE_FAN_ADDR = 0x22F418;
    // ArtKosM_HCZLargeFan from Split/s3.txt: 0x00190900-0x00190C02, shifted into
    // the lock-on ROM's S3 half at +0x200000.
    public static final int ART_KOSM_HCZ_LARGE_FAN_ADDR = 0x390900;
    // ArtTile_HCZLargeFan = $0500 (sonic3k.constants.asm), palette 1
    public static final int ARTTILE_HCZ_LARGE_FAN = 0x0500;

    // ===== HCZ Water Drop (Obj_WaterDrop, ID 0x6E, sonic3k.asm:75145) =====
    // ArtTile_HCZ2Slide = $035C (sonic3k.constants.asm:1183), palette 1
    public static final int ARTTILE_HCZ2_SLIDE = 0x035C;
    // Map_HCZWaterDrop (7 frames), ROM address derived from label Frame_23795C
    public static final int MAP_HCZ_WATER_DROP_ADDR = 0x23794E;

    // ===== Floating Platform mappings (Obj_FloatingPlatform, ID 0x51) =====
    public static final int MAP_AIZ_FLOATING_PLATFORM_ADDR = 0x256A2; // Map_AIZFloatingPlatform (1 frame, 4 pieces)
    public static final int MAP_HCZ_FLOATING_PLATFORM_ADDR = 0x25688; // Map_HCZFloatingPlatform (2 frames, 2/1 pieces)
    public static final int MAP_HCZ_WAVE_SPLASH_ADDR = 0x01F2CE;     // Map_HCZWaveSplash (7 frames, S&K side)
    public static final int ART_NEM_HCZ_WAVE_SPLASH_ADDR = 0x38FBB4; // ArtNem_HCZWaveSplash (Nemesis, 16 tiles)

    // ===== HCZ Water Skim splash (Obj_HCZWaterSplash subtype 1, sonic3k.asm:75247) =====
    // ArtUnc_HCZWaterSplash2: uncompressed, 1920 bytes (60 tiles), 5 frames × 12 tiles
    public static final int ART_UNC_HCZ_WATER_SPLASH2_ADDR = 0x392394;
    public static final int ART_UNC_HCZ_WATER_SPLASH2_SIZE = 1920;
    // ArtUnc_HCZWaterSplash: uncompressed, 3072 bytes (96 tiles), 4 frames × 24 tiles (subtype 0, not used here)
    public static final int ART_UNC_HCZ_WATER_SPLASH_ADDR = 0x392B14;
    public static final int ART_UNC_HCZ_WATER_SPLASH_SIZE = 3072;
    // Map_HCZWaterSplash: 4 active subtype-0 frames plus an unused empty frame.
    // LockOn data referenced by sonic3k.asm Obj_HCZWaterSplash.
    public static final int MAP_HCZ_WATER_SPLASH_ADDR = 0x237C60;
    // Map_HCZWaterSplash2: 5 active skim frames plus an empty frame that points
    // two bytes before this table (the empty frame shared with Map_HCZWaterSplash).
    public static final int MAP_HCZ_WATER_SPLASH2_ADDR = 0x237C7A;
    // VRAM tile indices from sonic3k.constants.asm
    public static final int ARTTILE_HCZ1_WATER_SPLASH2 = 0x0344;
    public static final int ARTTILE_HCZ2_WATER_SPLASH2 = 0x036E;
    public static final int ARTTILE_HCZ_WATER_SPLASH = 0x03B2;

    // ===== HCZ/CNZ/DEZ Door (Obj_Door, ID 0x3C) =====
    // Map_HCZCNZDEZDoor (vertical frames 0=HCZ, 1=CNZ, 2=DEZ), S&K side.
    public static final int MAP_HCZ_CNZ_DEZ_DOOR_ADDR = 0x030F86;
    // Map_CNZDoorHorizontal (horizontal frame), S&K side.
    public static final int MAP_CNZ_DOOR_HORIZONTAL_ADDR = 0x031108;
    public static final int MAP_MGZ_FLOATING_PLATFORM_ADDR = 0x25654; // Map_MGZFloatingPlatform (1 frame, 8 pieces)

    // ===== ArtTile constants from sonic3k.constants.asm =====
    public static final int ARTTILE_AIZ_FLOATING_PLATFORM = 0x03F7;
    public static final int ARTTILE_AIZ2_FLOATING_PLATFORM = 0x0440;
    public static final int ARTTILE_HCZ_MISC = 0x03CA;
    public static final int ARTTILE_HCZ2_BLOCK_PLAT = 0x0028;
    public static final int ARTTILE_HCZ_TENSION_BRIDGE = ARTTILE_HCZ2_BLOCK_PLAT + 0x10; // 0x0038
    public static final int ARTTILE_CNZ_PLATFORM = 0x0430;
    public static final int ARTTILE_ICZ_MISC2 = 0x0377;
    public static final int ARTTILE_ICZ_MISC1 = 0x03B6;
    // ArtTile_ICZIntroSprites (sonic3k.constants.asm:1263) - loaded by PLC_1E_1F (ICZ1).
    // Base for the breakable-wall break debris (ObjDat3_8A41E: make_art_tile(ArtTile_ICZIntroSprites,2,1)).
    public static final int ARTTILE_ICZ_INTRO_SPRITES = 0x0347;
    public static final int ARTTILE_LRZ_TENSION_BRIDGE = 0x0113;
    public static final int ARTTILE_LBZ_MISC = 0x03C3;
    public static final int ARTTILE_FBZ_MISC = 0x0379;
    public static final int ARTTILE_DEZ_MISC = 0x034D;

    // ===== Tension Bridge mappings (Obj_TensionBridge, ID 0x6C) =====
    public static final int MAP_TENSION_BRIDGE_ADDR = 0x038FF2;   // Map_TensionBridge
    public static final int MAP_ICZ_TENSION_BRIDGE_ADDR = 0x038FBA; // Map_ICZTensionBridge

    // ===== HUD Art =====
    // ArtUnc_HUDDigits - Score/Time/Ring digits (0-9, colon, E)
    // 768 bytes = 24 tiles (12 characters x 2 tiles each, column-major 8x16)
    // Verified via RomOffsetFinder binary match
    public static final int ART_UNC_HUD_DIGITS_ADDR = 0xE18A;
    public static final int ART_UNC_HUD_DIGITS_SIZE = 768;

    // ArtUnc_LivesDigits - Small 8x8 digits (0-9) for lives counter
    // 320 bytes = 10 tiles
    // Verified via RomOffsetFinder binary match
    public static final int ART_UNC_LIVES_DIGITS_ADDR = 0xE48A;
    public static final int ART_UNC_LIVES_DIGITS_SIZE = 320;

    // ArtUnc_DebugDigits - 8x8 font used by HUD_Debug for player/camera hex coords.
    // ASCII-aligned layout: digits 0-9 at tiles 0-9, A-F at tiles 17-22 (char offset
    // from '0'). Sits immediately after ArtUnc_LivesDigits in ROM.
    // 736 bytes = 23 tiles. MD5 matches General/Sprites/HUD Icon/Debug Digits.bin.
    public static final int ART_UNC_DEBUG_DIGITS_ADDR = 0xE5CA;
    public static final int ART_UNC_DEBUG_DIGITS_SIZE = 736;

    // Touch_Sizes table: 58 entries of 2 bytes (width, height radius)
    // sonic3k.asm line 20713, verified via ROM binary search
    public static final int TOUCH_SIZES_ADDR = 0x00FF62;
    public static final int TOUCH_SIZES_COUNT = 58;

    // ===== Pattern Load Cue (PLC) tables =====
    // Offs_PLC: 2-byte offset table, 124 entries (IDs 0x00-0x7B)
    // Each word is an offset from Offs_PLC to the PLC data block.
    // PLC data block: dc.w count-1, then count × 6-byte entries:
    //   dc.l nemesis_rom_addr, dc.w vram_dest_bytes (tile_index * 32)
    // Verified by ROM binary search for PLC_01 fingerprint: 0x09249E - 0x112 = 0x09238C
    public static final int OFFS_PLC_ADDR = 0x09238C;
    public static final int OFFS_PLC_ENTRY_COUNT = 124;
    public static final int PLC_ENTRY_SIZE = 6; // 4-byte ROM addr + 2-byte VRAM dest

    public static final int ART_NEM_SONIC_LIFE_ICON_ADDR = 0x190D34;
    public static final int ART_NEM_KNUCKLES_LIFE_ICON_ADDR = 0x190E4C; // ArtNem_KnucklesLifeIcon
    public static final int ART_NEM_TAILS_LIFE_ICON_ADDR = 0x35CFFE;   // ArtNem_TailsLifeIcon (LockOn S3 data in combined S3&K ROM)
    public static final int ART_NEM_MONITORS_ADDR = 0x190F4A;
    public static final int ART_NEM_EXPLOSION_ADDR = 0x19200A;
    public static final int MAP_MONITOR_ADDR = 0x01DBA2; // Map_Monitor (12 frames, S&K side)
    public static final int MAP_EXPLOSION_ADDR = 0x01E758; // Map_Explosion (5 frames, S&K side)
    public static final int ART_NEM_BUBBLES_ADDR = 0x191B46;
    // ArtNem_GameOver (General/Sprites/Game Over/GameOver.bin, 549 bytes; S&K half,
    // docs/skdisasm/sonic3k.lst:313029; the S3 half copy is at 0x35E49A). Loaded by
    // Load_PLC_2 #3 (PLC_03: plreq ArtTile_Shield, ArtNem_GameOver, sonic3k.asm:199633-199635).
    public static final int ART_NEM_GAME_OVER_ADDR = 0x191DE4;
    // Map_GameOver: GAME / OVER / TIME / OVER, tiles relative to ArtTile_Shield
    // (docs/skdisasm/sonic3k.lst:76186-76191, General/Sprites/Game Over/Map - Game Over.asm).
    public static final int MAP_GAME_OVER_ADDR = 0x02EDD0;
    public static final int MAP_GAME_OVER_FRAME_COUNT = 4;
    // Reserved_object_3 and the first Dynamic_object_RAM slot, the two slots the
    // dead player's routine writes Obj_GameOver into (docs/skdisasm/sonic3k.asm:24596-24597;
    // Object_RAM layout sonic3k.constants.asm:303-323).
    public static final int SST_SLOT_GAME_OVER_WORD = 2;
    public static final int SST_SLOT_GAME_OVER_OVER = 3;
    public static final int MAP_BUBBLER_ADDR = 0x02FCB2; // Map_Bubbler (23 frames, S&K side)
    // ArtUnc_AirCountdown (S&K side): the drowning countdown digits, 60 tiles.
    // Obj_AirCountdown DMAs six tiles per mapping frame $09-$12 into
    // ArtTile_DashDust (sonic3k.asm:33489-33516).
    public static final int ART_UNC_AIR_COUNTDOWN_ADDR = 0x0A9DFC;
    public static final int ART_UNC_AIR_COUNTDOWN_SIZE = 0x780;
    /** {@code ArtTile_DashDust - ArtTile_Bubbles}: digit tile index inside Map_Bubbler. */
    public static final int AIR_COUNTDOWN_DIGIT_TILE_OFFSET = 0x0384;
    /** {@code $60} words per {@code Add_To_DMA_Queue} transfer = six tiles per digit frame. */
    public static final int AIR_COUNTDOWN_TILES_PER_DIGIT = 6;
    /** First and last {@code Map_Bubbler} frame that draws a countdown digit. */
    public static final int AIR_COUNTDOWN_FIRST_DIGIT_FRAME = 0x09;
    public static final int AIR_COUNTDOWN_DIGIT_FRAME_COUNT = 10;
    public static final int ART_NEM_RING_HUD_TEXT_ADDR = 0x192AEE;
    public static final int ART_NEM_ENEMY_PTS_STARPOST_ADDR = 0x192D2A;
    public static final int ART_NEM_STARPOST_ADDR = 0x35D8A2; // Dedicated StarPost art (20 tiles)
    // ArtKosM_StarPost_Stars1/Stars2/Stars3 - bonus star variants (KosinskiM, 3 tiles each)
    // Loaded dynamically at checkpoint activation based on ring count.
    // Stars1=Blue (Glowing Spheres), Stars2=Red (Slot Machine), Stars3=Yellow (Gumball)
    public static final int ART_KOSM_STARPOST_STARS1_ADDR = 0x187B8A; // Blue stars
    public static final int ART_KOSM_STARPOST_STARS2_ADDR = 0x187BEC; // Red stars
    public static final int ART_KOSM_STARPOST_STARS3_ADDR = 0x187C4E; // Yellow stars
    public static final int ART_NEM_SPIKES_SPRINGS_ADDR = 0x1927FE;

    // Animal art (Nemesis compressed, per-type)
    public static final int ART_NEM_SEAL_ADDR = 0x192F6E;
    public static final int ART_NEM_PIG_ADDR = 0x19308A;
    public static final int ART_NEM_BLUE_FLICKY_ADDR = 0x1931D6;
    public static final int ART_NEM_CHICKEN_ADDR = 0x193308;
    public static final int ART_NEM_PENGUIN_ADDR = 0x193456;
    public static final int ART_NEM_SQUIRREL_ADDR = 0x1935A8;
    public static final int ART_NEM_RABBIT_ADDR = 0x193706;

    // VRAM tile index for SpikesSprings shared art (spikes start at +8)
    public static final int ARTTILE_SPIKES_SPRINGS = 0x0494;
    // VRAM tile index for diagonal spring art (separate from SpikesSprings)
    public static final int ARTTILE_DIAGONAL_SPRING = 0x043A;

    // ArtUnc_CutsceneKnux - Cutscene Knuckles sprite art (uncompressed, DPLC-driven)
    // 0x4EE0 bytes = 631 tiles
    public static final int ART_UNC_CUTSCENE_KNUX_ADDR = 0x382DC6;
    public static final int ART_UNC_CUTSCENE_KNUX_SIZE = 0x4EE0;
    // ArtNem_LBZKnuxBomb - PLC_60 child art for CutsceneKnux_LBZ1 thrown bomb.
    public static final int ART_NEM_LBZ_KNUX_BOMB_ADDR = 0x3791DE;

    // --- Mapping addresses ---
    // Map_AIZIntroPlane - Tornado biplane sprite mappings (0xF2 bytes, 11 frames)
    public static final int MAP_AIZ_INTRO_PLANE_ADDR = 0x364470;

    // Map_AIZIntroEmeralds - Emerald sprite mappings (0x46 bytes, 7 frames)
    public static final int MAP_AIZ_INTRO_EMERALDS_ADDR = 0x364562;

    // Map_AIZIntroWaves - Water wave/spray sprite mappings (0x3750 bytes, 6 frames)
    public static final int MAP_AIZ_INTRO_WAVES_ADDR = 0x22119A;

    // Map_CutsceneKnux - Cutscene Knuckles sprite mappings (0x2F8 bytes)
    public static final int MAP_CUTSCENE_KNUX_ADDR = 0x364016;

    // Map_LBZKnuxBomb - one-frame mapping immediately before Map_CutsceneKnux.
    public static final int MAP_LBZ_KNUX_BOMB_ADDR = 0x36400C;

    // DPLC_CutsceneKnux - Cutscene Knuckles dynamic pattern load cues (0x162 bytes)
    public static final int DPLC_CUTSCENE_KNUX_ADDR = 0x36430E;

    // --- Palette addresses ---
    // Pal_AIZIntro - AIZ intro zone palette (96 bytes, palette index 10)
    // Loaded via PalPoint table at PAL_POINTERS_ADDR + 10*8
    public static final int PAL_AIZ_INTRO_ADDR = 0x0A8B1C;
    public static final int PAL_AIZ_INTRO_INDEX = 10;
    public static final int PAL_AIZ_INTRO_SIZE = 96;

    // Pal_CutsceneKnux - Knuckles cutscene palette (32 bytes = 16 colors)
    public static final int PAL_CUTSCENE_KNUX_ADDR = 0x066912;

    // PalPointers index for HCZ2 main palette (Pal_HCZ2 → palette lines 1-3)
    // Used by CutsceneKnux_HCZ2 to restore normal palette after cutscene.
    public static final int PAL_POINTERS_HCZ2_INDEX = 13;

    // PalPointers index for ICZ2 main palette (Pal_ICZ2 → palette lines 1-3).
    // AfterBoss_ICZ2 reloads the first line via PalLoad_Line1 after the miniboss.
    public static final int PAL_POINTERS_ICZ2_INDEX = 21;

    // PalPointers index for LBZ1 main palette (Pal_LBZ1, palette lines 1-3).
    public static final int PAL_POINTERS_LBZ1_INDEX = 22;

    // PalPointers index for MHZ2 main palette (Pal_MHZ2). AfterBoss_MHZ — also
    // reached by AfterBoss_LBZ, an original-game bug — loads its first line via
    // PalLoad_Line1 after the miniboss results begin.
    public static final int PAL_POINTERS_MHZ2_INDEX = 25;

    // Pal_AIZIntroEmeralds - Emerald palette (32 bytes = 16 colors)
    public static final int PAL_AIZ_INTRO_EMERALDS_ADDR = 0x067AAA;

    // PalCycle_SuperSonic - Super Sonic palette cycle data
    // 10 entries x 3 words (6 bytes each) = 60 bytes total
    // Entries 0-5: fade-in, entries 6-9: cycling loop (loop starts at offset $24)
    public static final int PAL_CYCLE_SUPER_SONIC_ADDR = 0x00398E;
    public static final int PAL_CYCLE_SUPER_SONIC_ENTRY_COUNT = 10;
    public static final int PAL_CYCLE_SUPER_SONIC_ENTRY_SIZE = 6; // 3 words

    // PalCycle_SuperSonicUnderwaterAIZICZ / PalCycle_SuperSonicUnderwaterHCZCNZLBZ
    // (sonic3k.asm:4867 / 4879) - same layout as PalCycle_SuperSonic, written to the
    // water palette while Water_flag is set. SuperHyper_PalCycle_SonicApply picks the
    // AIZ/ICZ table for those two zones and the HCZ/CNZ/LBZ table otherwise.
    public static final int PAL_CYCLE_SUPER_SONIC_UNDERWATER_AIZ_ICZ_ADDR = 0x0039CA;
    public static final int PAL_CYCLE_SUPER_SONIC_UNDERWATER_HCZ_CNZ_LBZ_ADDR = 0x003A06;
    /** PalCycle_HyperSonic: 12 alternating color/white frames. */
    public static final int PAL_CYCLE_HYPER_SONIC_ADDR = 0x003A42;
    public static final int PAL_CYCLE_HYPER_SONIC_FRAME_COUNT = 12;
    /** PalCycle_SuperTails: six character-color frames. */
    public static final int PAL_CYCLE_SUPER_TAILS_ADDR = 0x003A8A;
    public static final int PAL_CYCLE_SUPER_TAILS_FRAME_COUNT = 6;
    /** PalCycle_SuperKnuckles: ROM powered-character palette table. */
    public static final int PAL_CYCLE_SUPER_KNUCKLES_ADDR = 0x003AAE;
    public static final int PAL_CYCLE_SUPER_KNUCKLES_FRAME_COUNT = 10;
    public static final int PAL_CYCLE_SUPER_KNUCKLES_REVERT_ADDR = 0x003AEA;

    /** ArtKosM_HyperSonicStars and its exact six-frame mapping table. */
    public static final int ART_KOSM_HYPER_SONIC_STARS_ADDR = 0x14C652;
    public static final int MAP_HYPER_SONIC_STARS_ADDR = 0x01948C;
    public static final int MAP_HYPER_SONIC_STARS_FRAME_COUNT = 6;
    /** ArtKosM_SuperTailsBirds and its exact three-frame mapping table. */
    public static final int ART_KOSM_SUPER_TAILS_BIRDS_ADDR = 0x14C7D4;
    public static final int MAP_SUPER_TAILS_BIRDS_ADDR = 0x01A464;
    public static final int MAP_SUPER_TAILS_BIRDS_FRAME_COUNT = 3;

    // --- VRAM art tile destinations ---
    // VDP tile indices where art is loaded in VRAM during the intro
    public static final int ARTTILE_AIZ_INTRO_SPRITES = 0x03D1;  // ArtTile_AIZIntroSprites
    public static final int ARTTILE_AIZ_INTRO_PLANE = 0x0529;    // ArtTile_AIZIntroPlane
    public static final int ARTTILE_AIZ_INTRO_EMERALDS = 0x05B1; // ArtTile_AIZIntroEmeralds
    public static final int ARTTILE_CUTSCENE_KNUX = 0x04DA;      // ArtTile_CutsceneKnux
    public static final int ARTTILE_AIZ_SLIDE_ROPE = 0x0324;
    public static final int ARTTILE_AIZ_MISC1 = 0x0333;
    public static final int ARTTILE_AIZ_MISC2 = 0x02E9;
    public static final int MAP_AIZ1_TREE_ADDR = 0x21C3E8; // Map_AIZ1Tree (1 frame)
    public static final int MAP_AIZ1_ZIPLINE_PEG_ADDR = 0x21C42A; // Map_AIZ1ZiplinePeg (1 frame)
    public static final int MAP_AIZ_FOREGROUND_PLANT_ADDR = 0x22B8EC; // Map_AIZForegroundPlant (2 frames)
    public static final int ARTTILE_LRZ2_MISC = 0x040D;
    public static final int ARTTILE_AIZ_FALLING_LOG = 0x03CF;
    public static final int ARTTILE_AIZ_SWING_VINE = 0x041B;
    public static final int ARTTILE_BUBBLES = 0x045C;
    public static final int ARTTILE_MONITORS = 0x04C4;
    public static final int ARTTILE_STARPOST = 0x05E4;
    public static final int ARTTILE_RING = 0x06BC;
    public static final int ARTTILE_PLAYER_LIFE_ICON = 0x07D4;

    // ICZ1 snowboard intro data in the locked-on ROM's "Lockon S3" data block.
    // These labels are referenced by Obj_LevelIntroICZ1 in sonic3k.asm.
    public static final int ICZ_SNOWBOARD_SLOPE1_ADDR = 0x344E80;
    public static final int ICZ_SNOWBOARD_SLOPE2_ADDR = 0x344F48;
    public static final int ART_UNC_SONIC_SNOWBOARD_ADDR = 0x345010;
    public static final int ART_UNC_SONIC_SNOWBOARD_SIZE = 10304;
    public static final int ART_UNC_SNOWBOARD_ADDR = 0x347850;
    public static final int ART_UNC_SNOWBOARD_SIZE = 1504;
    public static final int MAP_SONIC_SNOWBOARD_ADDR = 0x347E30;
    public static final int MAP_SONIC_SNOWBOARD_FRAMES = 13;
    public static final int DPLC_SONIC_SNOWBOARD_ADDR = 0x347F8A;
    public static final int MAP_SNOWBOARD_ADDR = 0x348020;
    public static final int MAP_SNOWBOARD_FRAMES = 12;
    public static final int DPLC_SNOWBOARD_ADDR = 0x348128;
    public static final int MAP_SNOWBOARD_DUST_ADDR = 0x0399D8;
    public static final int MAP_SNOWBOARD_DUST_FRAMES = 4;
    public static final int ARTTILE_SNOWBOARD_DUST = 0x06B8;

    // Map_StarPost - StarPost sprite mappings (5 frames)
    // Frame 0: pole + red ball (idle), 1: pole only, 2: star ball, 3: head, 4: pole + blue ball
    public static final int MAP_STARPOST_ADDR = 0x2D348;

    // Map_StarpostStars - StarPost bonus star mappings (3 frames)
    public static final int MAP_STARPOST_STARS_ADDR = 0x2D3AA;

    // Map_Animals1-5 - Animal sprite mappings (3 frames each, 6-byte pieces)
    // Each set covers a different body shape: 1=A(Chicken/Eagle/Flicky), 2=B(Squirrel/Mouse/Monkey/Turtle/Bear),
    // 3=C(Pig), 4=D(Seal), 5=E(Rabbit/Penguin)
    public static final int MAP_ANIMALS1_ADDR = 0x02CEBA;
    public static final int MAP_ANIMALS2_ADDR = 0x02CED8;
    public static final int MAP_ANIMALS3_ADDR = 0x02CEF6;
    public static final int MAP_ANIMALS4_ADDR = 0x02CF14;
    public static final int MAP_ANIMALS5_ADDR = 0x02CF32;

    // Map_EnemyScore - Enemy points popup mappings (7 frames: 10,20,50,100,1,200,500)
    public static final int MAP_ENEMY_SCORE_ADDR = 0x02CF50;

    // VRAM tile offsets for animal art
    // ArtTile_Animals1 = $0580, ArtTile_Animals2 = $0592, difference = 18 tiles
    public static final int S3K_ANIMAL_TILE_OFFSET = 0x12; // 18 tiles between animal banks

    // --- Animation scripts (inline data in S3 code space) ---
    // Knuckles cutscene animation scripts
    // Format: dc.b duration, frame0, frame1, ..., $FC (loop) or $F4 (end)
    public static final int ANIM_CUTSCENE_KNUX_WALK_ADDR = 0x0666A9;   // byte_666A9
    public static final int ANIM_CUTSCENE_KNUX_REACT_ADDR = 0x0666AF;  // byte_666AF
    public static final int ANIM_CUTSCENE_KNUX_LOOK_ADDR = 0x0666B9;   // byte_666B9

    // Wave animation script (used for water spray child objects)
    public static final int ANIM_AIZ_INTRO_WAVES_ADDR = 0x067A9B;      // byte_67A9B

    // Emerald sparkle/glow animation scripts
    public static final int ANIM_EMERALD_SPARKLE_ADDR = 0x067A84;      // byte_67A84
    public static final int ANIM_EMERALD_GLOW_ADDR = 0x067A8F;         // byte_67A8F

    // --- Object data tables ---
    // ObjDat3_67A4E - Emerald object attributes (Map ptr, art_tile, size, render_flags)
    public static final int OBJDAT_AIZ_INTRO_EMERALDS_ADDR = 0x067A4E;

    // ChildObjDat tables for creating child objects during intro
    public static final int CHILD_OBJDAT_SUPER_GLOW_ADDR = 0x067A5A;   // ChildObjDat_67A5A
    public static final int CHILD_OBJDAT_PLANE_CHILDREN_ADDR = 0x067A62; // ChildObjDat_67A62
    public static final int CHILD_OBJDAT_WAKE_SPLASH_ADDR = 0x067A70;  // ChildObjDat_67A70
    public static final int CHILD_OBJDAT_CUTSCENE_KNUX_ADDR = 0x067A78; // ChildObjDat_67A78
    public static final int CHILD_OBJDAT_EMERALDS_ADDR = 0x067A7E;     // ChildObjDat_67A7E (7 children)

    // --- Velocity data ---
    // Obj_VelocityIndex - Shared velocity table for object scatter effects
    // Each entry is 4 bytes: dc.w x_vel, y_vel
    // Emerald scatter uses entries at offset $40 (subtypes 0-6 with stride 4 bytes each)
    public static final int OBJ_VELOCITY_INDEX_ADDR = 0x0852F4;
    public static final int EMERALD_SCATTER_VELOCITY_OFFSET = 0x40;

    // ===== Player Sprite Art =====
    // ArtUnc_Sonic - Main Sonic sprite art (uncompressed)
    // 131,296 bytes = 4103 tiles x 32 bytes per tile
    public static final int ART_UNC_SONIC_ADDR = 0x100000;
    public static final int ART_UNC_SONIC_SIZE = 131296;
    public static final int ART_UNC_SONIC_TILE_COUNT = 4103;

    // ArtUnc_Sonic_Extra - Super Sonic / extra art frames (uncompressed)
    // 15,520 bytes = 485 tiles x 32 bytes per tile
    // Used for mapping frames >= SONIC_EXTRA_ART_FRAME_THRESHOLD
    public static final int ART_UNC_SONIC_EXTRA_ADDR = 0x140060;
    public static final int ART_UNC_SONIC_EXTRA_SIZE = 15520;
    public static final int ART_UNC_SONIC_EXTRA_TILE_COUNT = 485;

    // Map_Sonic - Sonic sprite mappings (6-byte pieces, no 2P tile word)
    // Combined 1P+2P offset table: 502 entries (first 251 = 1P, second 251 = 2P).
    // Machine code at ROM 0x010B16: move.l #$146620, mappings(a0)
    public static final int MAP_SONIC_ADDR = 0x146620;

    // PLC_Sonic - Sonic dynamic pattern load cues
    // Combined 1P+2P offset table: 502 entries (first 251 = 1P, second 251 = 2P).
    // Same DPLC format as S2 (offset table + per-frame tile load request lists)
    public static final int DPLC_SONIC_ADDR = 0x148182;

    // ArtTile_Player_1 - VRAM base tile index for Sonic
    public static final int ART_TILE_SONIC = 0x0680;

    // AniSonic_ - Animation script table (36 entries: 0x00-0x23)
    public static final int SONIC_ANIM_DATA_ADDR = 0x012AA6;
    public static final int SONIC_ANIM_SCRIPT_COUNT = 36;

    // AniSuperSonic - Super Sonic animation table (32 entries: 0x00-0x1F)
    // Entries with offsets >= 0x8000 are back-references to regular AniSonic scripts
    public static final int SUPER_SONIC_ANIM_DATA_ADDR = 0x012C3A;
    public static final int SUPER_SONIC_ANIM_SCRIPT_COUNT = 32;

    // Extra art frame threshold - mapping frames >= this use Extra art tiles
    // ROM: LoadSonicDynPLC checks frame >= $DA for Extra art
    public static final int SONIC_EXTRA_ART_FRAME_THRESHOLD = 0xDA;

    // Map_SuperSonic - Super Sonic sprite mappings (standalone 251-entry table)
    // Immediately follows Map_Sonic_'s 251 1P offset entries in ROM.
    // First word = 0x01F6 (251 entries), standard parser works without trimming.
    public static final int MAP_SUPER_SONIC_ADDR = 0x146816; // MAP_SONIC_ADDR + 251*2

    // PLC_SuperSonic - Super Sonic dynamic pattern load cues (standalone 251-entry table)
    // Immediately follows PLC_Sonic_'s 251 1P offset entries in ROM.
    // First word is a frame data offset (NOT entry count), so explicit count is required.
    public static final int DPLC_SUPER_SONIC_ADDR = 0x148378; // DPLC_SONIC_ADDR + 251*2

    // Super Sonic table entry count (same frame indexing as normal Sonic)
    public static final int SUPER_SONIC_FRAME_COUNT = 251;

    // Super Sonic constants
    public static final int SUPER_SONIC_RING_DRAIN_INTERVAL = 60;
    public static final int SUPER_SONIC_MIN_RINGS = 50;

    // ===== Tails Player Sprite Art =====
    // ArtUnc_Tails - Main Tails body art (uncompressed, S3 ROM portion)
    public static final int ART_UNC_TAILS_ADDR = 0x3200E0;
    public static final int ART_UNC_TAILS_SIZE = 0x16540;    // 91,456 bytes = 2858 tiles

    // ArtUnc_Tails_Extra - Super Tails / extra art frames (uncompressed, S&K portion)
    public static final int ART_UNC_TAILS_EXTRA_ADDR = 0x143D00;
    public static final int ART_UNC_TAILS_EXTRA_SIZE = 0x2920;  // 10,528 bytes = 329 tiles

    // Map_Tails - Tails body mappings (6-byte pieces)
    // Combined 1P+2P offset table: 502 entries (first 251 = 1P, second 251 = 2P).
    public static final int MAP_TAILS_ADDR = 0x148EB8;

    // PLC_Tails - Tails body dynamic pattern load cues
    // Combined 1P+2P offset table: 502 entries (first 251 = 1P, second 251 = 2P).
    public static final int DPLC_TAILS_ADDR = 0x14A08A;

    // ArtTile_Player_2 - VRAM base tile for Tails (from sonic3k.constants.asm)
    public static final int ART_TILE_TAILS = 0x06A0;

    // AniTails - Animation script table (42 entries: 0x00-0x29)
    // Verified by ROM pattern search + lea ($15AB0).l,a1 instruction at 0x015864
    public static final int TAILS_ANIM_DATA_ADDR = 0x015AB0;
    public static final int TAILS_ANIM_SCRIPT_COUNT = 42;

    // Extra art frame threshold - frames >= this use Extra art tiles
    // ROM: Tails_Load_PLC checks frame index >= $D1
    public static final int TAILS_EXTRA_ART_FRAME_THRESHOLD = 0xD1;

    // ArtTile_Player_2_Tail - VRAM base tile for Tails tail appendage (Obj05)
    // From sonic3k.constants.asm line 1409
    public static final int ART_TILE_TAILS_TAIL = 0x06B0;

    // ===== Tails Tail Appendage (separate sprite object) =====
    public static final int ART_UNC_TAILS_TAIL_ADDR = 0x336620;
    public static final int ART_UNC_TAILS_TAIL_SIZE = 0x1160;   // 4,448 bytes = 139 tiles
    public static final int MAP_TAILS_TAIL_ADDR = 0x344BB8;
    public static final int DPLC_TAILS_TAIL_ADDR = 0x344D74;

    // ===== Knuckles Player Sprite Art =====
    // ArtUnc_Knux - Main Knuckles body art (uncompressed, S&K ROM portion)
    // Split/sk.txt: 0x1200E0 to 0x140060
    public static final int ART_UNC_KNUCKLES_ADDR = 0x1200E0;
    public static final int ART_UNC_KNUCKLES_SIZE = 0x1FF80;    // 130,944 bytes = 4092 tiles

    // Map_Knuckles - Knuckles body mappings (6-byte pieces, standalone 251-entry table)
    // Unlike Sonic/Tails, Knuckles maps are NOT combined 1P+2P.
    public static final int MAP_KNUCKLES_ADDR = 0x14A8D6;

    // PLC_Knuckles - Knuckles body dynamic pattern load cues (standalone 251-entry table)
    public static final int DPLC_KNUCKLES_ADDR = 0x14BD0A;

    // ArtTile_Player_1 - VRAM base tile for Knuckles (same as Sonic — single-player uses one slot)
    public static final int ART_TILE_KNUCKLES = ART_TILE_SONIC; // 0x0680

    // AniKnuckles_ - Animation script table (37 entries: 0x00-0x24)
    // Separate from AniSonic_ — Knuckles has his own animation frame data
    public static final int KNUCKLES_ANIM_DATA_ADDR = 0x017EF4;
    public static final int KNUCKLES_ANIM_SCRIPT_COUNT = 37;

    // ===== Animated palette cycling data (AnPal tables) =====
    // AIZ1 waterfall (palette 2, colors 11-14): 4 frames x 8 bytes = 32 bytes
    public static final int ANPAL_AIZ1_1_ADDR = 0x002AF6;
    public static final int ANPAL_AIZ1_1_SIZE = 32;
    // AIZ1 secondary water (palette 3, colors 12-14): 8 frames x 6 bytes = 48 bytes
    public static final int ANPAL_AIZ1_2_ADDR = 0x002BF6;
    public static final int ANPAL_AIZ1_2_SIZE = 48;
    // AIZ1 fire mode (palette 3, colors 2-5): 10 frames x 8 bytes = 80 bytes
    public static final int ANPAL_AIZ1_3_ADDR = 0x002B16;
    public static final int ANPAL_AIZ1_3_SIZE = 80;
    // AIZ1 fire mode (palette 3, colors 13-15): 10 frames x 6 bytes = 60 bytes
    public static final int ANPAL_AIZ1_4_ADDR = 0x002B96;
    public static final int ANPAL_AIZ1_4_SIZE = 60;
    // AIZ2 water (palette 3, colors 12-15): 4 frames x 8 bytes = 32 bytes
    public static final int ANPAL_AIZ2_1_ADDR = 0x002C26;
    public static final int ANPAL_AIZ2_1_SIZE = 32;
    // AIZ2 water trickle pre-fire (pal 2: colors 4,8; pal 3: color 11): 8 frames x 6 bytes = 48 bytes
    public static final int ANPAL_AIZ2_2_ADDR = 0x002C46;
    public static final int ANPAL_AIZ2_2_SIZE = 48;
    // AIZ2 water trickle post-fire (same targets): 8 frames x 6 bytes = 48 bytes
    public static final int ANPAL_AIZ2_3_ADDR = 0x002C76;
    public static final int ANPAL_AIZ2_3_SIZE = 48;
    // AIZ2 torch glow pre-fire (palette 3, color 1): 26 frames x 2 bytes = 52 bytes
    public static final int ANPAL_AIZ2_4_ADDR = 0x002CA6;
    public static final int ANPAL_AIZ2_4_SIZE = 52;
    // AIZ2 torch glow post-fire (palette 3, color 1): 26 frames x 2 bytes = 52 bytes
    public static final int ANPAL_AIZ2_5_ADDR = 0x002CDA;
    public static final int ANPAL_AIZ2_5_SIZE = 52;
    // HCZ1 water animation (palette 2, colors 3-6): 4 frames x 8 bytes = 32 bytes
    // ROM: AnPal_PalHCZ1 — verified by ROM binary search for 0EC8 0EC0 0EA0 0E80 full sequence
    public static final int ANPAL_HCZ1_ADDR = 0x002D0E;
    public static final int ANPAL_HCZ1_SIZE = 32;
    // CNZ bumpers/teacups (palette 3, colors 9-11): 16 frames x 6 bytes = 96 bytes
    // Verified by ROM binary search (pattern: 00 00 00 66 00 EE)
    public static final int ANPAL_CNZ_1_ADDR = 0x002D2E;
    public static final int ANPAL_CNZ_1_SIZE = 96;
    // CNZ bumpers water table (palette 3, colors 9-11, unused): 16 frames x 6 bytes = 96 bytes
    public static final int ANPAL_CNZ_2_ADDR = 0x002E82;
    public static final int ANPAL_CNZ_2_SIZE = 96;
    // CNZ background (palette 2, colors 9-11): 30 frames x 6 bytes = 180 bytes
    // Verified by ROM binary search (pattern: 0E 20 00 8A 0C 0E)
    public static final int ANPAL_CNZ_3_ADDR = 0x002D8E;
    public static final int ANPAL_CNZ_3_SIZE = 180;
    // CNZ background water table (palette 2, colors 9-11, unused): 30 frames x 6 bytes = 180 bytes
    public static final int ANPAL_CNZ_4_ADDR = 0x002EE2;
    public static final int ANPAL_CNZ_4_SIZE = 180;
    // CNZ tertiary (palette 2, colors 7-8): 16 frames x 4 bytes = 64 bytes
    // Verified by ROM binary search (pattern: 02 E0 0E CE 04 E2 0E AC)
    public static final int ANPAL_CNZ_5_ADDR = 0x002E42;
    public static final int ANPAL_CNZ_5_SIZE = 64;
    // ICZ geyser/ice (palette 2, colors 14-15): 16 frames x 4 bytes = 64 bytes
    // ROM: AnPal_PalICZ_1 — verified 0x002FD6 by binary search (0E 62 0E 20 ...)
    public static final int ANPAL_ICZ_1_ADDR = 0x002FD6;
    public static final int ANPAL_ICZ_1_SIZE = 64;
    // ICZ conditional ch2 (palette 3, colors 14-15): 18 frames x 4 bytes = 72 bytes
    // ROM: AnPal_PalICZ_2 — verified 0x003016 by binary search (0E 06 0E 08 ...)
    public static final int ANPAL_ICZ_2_ADDR = 0x003016;
    public static final int ANPAL_ICZ_2_SIZE = 72;
    // ICZ conditional ch3 (palette 3, colors 12-13): 6 frames x 4 bytes = 24 bytes
    // ROM: AnPal_PalICZ_3 — verified 0x00305E by binary search (08 40 0E EA ...)
    public static final int ANPAL_ICZ_3_ADDR = 0x00305E;
    public static final int ANPAL_ICZ_3_SIZE = 24;
    // ICZ always-on ch4 (palette 2, colors 12-13): 16 frames x 4 bytes = 64 bytes
    // ROM: AnPal_PalICZ_4 — verified 0x003076 by binary search (00 E8 0C EC ...)
    public static final int ANPAL_ICZ_4_ADDR = 0x003076;
    public static final int ANPAL_ICZ_4_SIZE = 64;
    // LBZ Act 1 (palette 2, colors 8-10): 3 frames x 6 bytes = 18 bytes
    // AnPal_PalLBZ1 — verified by ROM binary search (0x0030B6)
    public static final int ANPAL_LBZ1_ADDR = 0x0030B6;
    public static final int ANPAL_LBZ1_SIZE = 18;
    // LBZ Act 2 (palette 2, colors 8-10): 3 frames x 6 bytes = 18 bytes
    // AnPal_PalLBZ2 — immediately follows LBZ1 at 0x0030C8
    public static final int ANPAL_LBZ2_ADDR = 0x0030C8;
    public static final int ANPAL_LBZ2_SIZE = 18;
    // LRZ shared (both acts) channel A: palette 2 colors 1-4 (2 longwords), 16 frames x 8 bytes = 128 bytes
    // counter step +8, wraps at 0x80. Verified by ROM binary search (sonic3k.asm AnPal_PalLRZ12_1).
    public static final int ANPAL_LRZ12_1_ADDR = 0x00327E;
    public static final int ANPAL_LRZ12_1_SIZE = 128;
    // LRZ shared (both acts) channel B: palette 3 colors 1-2 (1 longword), 7 frames x 4 bytes = 28 bytes
    // counter step +4, wraps at 0x1C. Verified by ROM binary search (sonic3k.asm AnPal_PalLRZ12_2).
    public static final int ANPAL_LRZ12_2_ADDR = 0x0032FE;
    public static final int ANPAL_LRZ12_2_SIZE = 28;
    // LRZ Act 1 channel C: palette 2 color 11 (1 word), 17 frames x 2 bytes = 34 bytes
    // counter step +2, wraps at 0x22. Verified by ROM binary search (sonic3k.asm AnPal_PalLRZ1_3).
    public static final int ANPAL_LRZ1_3_ADDR = 0x003322;
    public static final int ANPAL_LRZ1_3_SIZE = 34;
    // LRZ Act 2 channel D: palette 3 colors 11-14 (2 longwords), 32 frames x 8 bytes = 256 bytes
    // counter step +8, wraps at 0x100. Verified by ROM binary search (sonic3k.asm AnPal_PalLRZ2_3).
    // ROM bug: writes same 2 colors twice (uses (a0,d0.w) twice instead of 4(a0,d0.w)) — replicated faithfully.
    public static final int ANPAL_LRZ2_3_ADDR = 0x003344;
    public static final int ANPAL_LRZ2_3_SIZE = 256;
    // BPZ balloons (palette 2, colors 13-15): 3 frames x 6 bytes = 18 bytes
    // Verified by ROM binary search for pattern 00EE 00AE 006C 00AE 006E 00EE 006E 00EE 00AE
    public static final int ANPAL_BPZ_1_ADDR = 0x0034CC;
    public static final int ANPAL_BPZ_1_SIZE = 18;
    // BPZ background (palette 3, colors 2-4): 21 frames x 6 bytes = 126 bytes
    // Immediately follows ANPAL_BPZ_1 in ROM
    public static final int ANPAL_BPZ_2_ADDR = 0x0034DE;
    public static final int ANPAL_BPZ_2_SIZE = 126;
    // CGZ light animation (palette 2, colors 2-5): 10 frames x 8 bytes = 80 bytes
    // AnPal_PalCGZ — verified by ROM binary search at 0x00355C
    public static final int ANPAL_CGZ_ADDR = 0x00355C;
    public static final int ANPAL_CGZ_SIZE = 80;
    // EMZ emerald glow (palette 2, color 14): 30 frames x 2 bytes = 60 bytes
    // ROM reads as 4(a0,d0.w); counter steps +2, wraps at 0x3C. Slice 64 bytes to cover full range.
    public static final int ANPAL_EMZ1_ADDR = 0x0035AC;
    public static final int ANPAL_EMZ1_SIZE = 64;
    // EMZ background (palette 3, colors 9-10): 13 frames x 4 bytes = 52 bytes
    public static final int ANPAL_EMZ2_ADDR = 0x0035E8;
    public static final int ANPAL_EMZ2_SIZE = 52;

    // ===== Animated pattern scripts (AniPLC tables) =====
    // AniPLC_AIZ1: 3 scripts (waterfall cascade, waterfall offset, wave ripple)
    // Verified by ROM binary search for frame data pattern (5CC0 090C 3C4F 3005)
    public static final int ANIPLC_AIZ1_ADDR = 0x028750;

    // AniPLC_AIZ2: 5 scripts (fire/explosion, waterfall cascade, waterfall offset, fire small, fire large)
    // Verified by ROM binary search for frame data pattern (1660 0417 0017 2E45)
    public static final int ANIPLC_AIZ2_ADDR = 0x02879C;

    // AniPLC_HCZ1: 2 scripts (background bubble column, water shimmer)
    // Verified against S&K ROM bytes immediately following AniPLC_AIZ2 at 0x0287F4.
    public static final int ANIPLC_HCZ1_ADDR = 0x0287F4;

    // AniPLC_HCZ2: 2 scripts (waterfall stream, water shimmer)
    // Verified against S&K ROM bytes immediately following AniPLC_HCZ1 at 0x02882C.
    public static final int ANIPLC_HCZ2_ADDR = 0x02882C;

    // AniPLC_MGZ: 2 scripts (shared MGZ background tiles for both acts)
    // Verified against S&K ROM bytes at 0x028862 and skdisasm AniPLC_MGZ.
    public static final int ANIPLC_MGZ_ADDR = 0x028862;

    // AniPLC_CNZ: 7 scripts (both acts share the same script table)
    // Verified by S&K ROM search for the first inline record:
    // 00 06 03 2A F8 00 56 40 10 09 ...
    public static final int ANIPLC_CNZ_ADDR = 0x028882;
    /** AniPLC_FBZ1, S&K-side listing address (sonic3k.lst:66267). */
    public static final int ANIPLC_FBZ1_ADDR = 0x028906;
    /** AniPLC_FBZ2, S&K-side listing address (sonic3k.lst:66330). */
    public static final int ANIPLC_FBZ2_ADDR = 0x028948;

    // AniPLC_ICZ: 1 script (indoor ice background shimmer, both acts)
    // Verified by S&K ROM search for:
    // 00 00 03 2B 99 40 23 C0 08 04 00 04 08 0C 10 14 18 1C
    public static final int ANIPLC_ICZ_ADDR = 0x028990;

    // AniPLC_LBZ1: 1 script (act-1 foreground/background machinery tiles)
    // Verified by S&K ROM search for:
    // 00 00 02 2B 9D 40 6C A0 04 08 00 08 10 18
    public static final int ANIPLC_LBZ1_ADDR = 0x0289A2;

    // AniPLC_LBZSpec: 2 scripts (shared LBZ special machinery tiles)
    // AnimateTiles_LBZ1 invokes this table in addition to AniPLC_LBZ1.
    public static final int ANIPLC_LBZ_SPEC_ADDR = 0x0289B0;

    // AniPLC_LBZ2: 2 scripts (act-2 shared machinery tiles)
    // Same payload as AniPLC_LBZSpec, but referenced by the act-2 table entry.
    public static final int ANIPLC_LBZ2_ADDR = 0x0289CC;

    // AniPLC_MHZ: 4 scripts (mushroom caps and foreground foliage, both acts)
    // Verified by table position immediately before AniPLC_LRZ1 at 0x028A6A.
    public static final int ANIPLC_MHZ_ADDR = 0x0289E8;

    // ArtUnc_AniAIZ2_FirstTree: Static tree art for AIZ2 near-spawn area (camera X < 0x1C0)
    // 0x460 bytes = 35 tiles, loaded to VRAM tile $0CA
    // Verified by move.l #addr,d1 instruction at ROM 0x02786A
    public static final int ART_UNC_AIZ2_FIRST_TREE_ADDR = 0x2A5880;
    public static final int ART_UNC_AIZ2_FIRST_TREE_SIZE = 0x460;
    public static final int ART_UNC_AIZ2_FIRST_TREE_DEST_TILE = 0x0CA;

    // HCZ1 startup background repair strips.
    // ROM startup with Events_bg+$10 == 0 calls AniHCZ_FixLowerBG, DMAing these
    // two 12-tile rows into VRAM $2F4 and $300 before HCZ background rendering.
    public static final int HCZ_WATERLINE_SCROLL_DATA_ADDR = 0x26D000;
    public static final int HCZ_WATERLINE_SCROLL_DATA_SIZE = 0x2460;
    // LBZ2 waterline lookup data (Levels/LBZ/Misc/LBZ Waterline Scroll Data.bin).
    // Lock-on S3 data, verified by ROM binary search at 0x26F460.
    public static final int LBZ_WATERLINE_SCROLL_DATA_ADDR = 0x26F460;
    public static final int LBZ_WATERLINE_SCROLL_DATA_SIZE = 0x1040;
    // loc_549A4 LEA resolves to LBZ_WaterWaveArray2=$4F778. Its predecrement
    // loop also reads 96 preceding words (including the adjacent AIZ table).
    public static final int LBZ_DEATH_EGG_WAVE_DATA_ADDR = 0x4F778 - 96 * 2;
    public static final int LBZ_DEATH_EGG_WAVE_DATA_SIZE = (96 + 64) * 2;
    public static final int ART_UNC_HCZ1_WATERLINE_BELOW1_ADDR = 0x2A6A60;
    public static final int ART_UNC_FIX_HCZ1_UPPER_BG1_ADDR = 0x2A6BE0;
    public static final int ART_UNC_HCZ1_WATERLINE_ABOVE1_ADDR = 0x2A6D60;
    public static final int ART_UNC_FIX_HCZ1_LOWER_BG1_ADDR = 0x2A6EE0;
    public static final int ART_UNC_HCZ1_WATERLINE_BELOW2_ADDR = 0x2A7060;
    public static final int ART_UNC_FIX_HCZ1_UPPER_BG2_ADDR = 0x2A71E0;
    public static final int ART_UNC_HCZ1_WATERLINE_ABOVE2_ADDR = 0x2A7360;
    public static final int ART_UNC_FIX_HCZ1_LOWER_BG2_ADDR = 0x2A74E0;
    public static final int ART_UNC_FIX_HCZ1_BG_STRIP_SIZE = 0x180;

    // HCZ2 direct background DMA sources, driven by HCZ2_Deform deltas.
    public static final int ART_UNC_HCZ2_SMALL_BG_LINE_ADDR = 0x2A87A0;
    public static final int ART_UNC_HCZ2_SMALL_BG_LINE_SIZE = 0x400;
    public static final int ART_UNC_HCZ2_2_ADDR = 0x2A8BA0;
    public static final int ART_UNC_HCZ2_2_SIZE = 0x800;
    public static final int ART_UNC_HCZ2_3_ADDR = 0x2A93A0;
    public static final int ART_UNC_HCZ2_3_SIZE = 0x1000;
    public static final int ART_UNC_HCZ2_4_ADDR = 0x2AA3A0;
    public static final int ART_UNC_HCZ2_4_SIZE = 0x3000;

    // CNZ direct-DMA source art used by AnimateTiles_CNZ for the background
    // strip uploads into VRAM tile $308+.
    public static final int ART_UNC_ANI_CNZ_6_ADDR = 0x2B5B80;
    public static final int ART_UNC_ANI_CNZ_6_SIZE = 0x2000;

    // MHZ direct-DMA background art used by AnimateTiles_MHZ before the
    // regular AniPLC scripts run. Source labels are ArtUnc_AniMHZ__BG and
    // ArtUnc_AniMHZ__BG2 in the S&K-side disassembly.
    public static final int ART_UNC_ANI_MHZ_BG_ADDR = 0x0BA1C0;
    public static final int ART_UNC_ANI_MHZ_BG_SIZE = 0x0800;
    public static final int ART_UNC_ANI_MHZ_BG2_ADDR = 0x0BA9C0;
    public static final int ART_UNC_ANI_MHZ_BG2_SIZE = 0x2000;

    // MHZ2 season palette blocks copied by MHZ2_ScreenInit / sub_55008 into
    // Normal_palette_line_3. Each source is 0x40 bytes, covering engine
    // palette indices 2 and 3. Verified with --game s3k search-rom against
    // the S&K-side ROM. Pal_MHZ2Ship is a single 0x20-byte line copied into
    // Normal_palette_line_2 during the ship transition.
    public static final int PAL_MHZ1_LINE3_ADDR = 0x0A945C;
    public static final int PAL_MHZ2_LINE3_ADDR = 0x0A94BC;
    public static final int PAL_MHZ2_SHIP_ADDR = 0x0550DE;
    public static final int PAL_MHZ2_GOLD_ADDR = 0x0550FE;
    public static final int MHZ_CUSTOM_LAYOUT_ADDR = 0x0A8044;
    public static final int MHZ_CUSTOM_BLOCKS_16X16_KOS_ADDR = 0x1A1B36;
    public static final int MHZ_CUSTOM_ART_KOSM_ADDR = 0x1A1EB6;
    public static final int MHZ_CUSTOM_CHUNKS_128X128_KOS_ADDR = 0x1A30E8;
    public static final int MHZ_CUSTOM_BLOCK_TABLE_DEST_OFFSET = 0x0B28;
    public static final int MHZ_CUSTOM_CHUNK_TABLE_DEST_OFFSET = 0x2280;
    public static final int MHZ_CUSTOM_ART_TILE = 0x0222;

      // MHZ2 ship sequence propeller art. The ROM queues ArtKosM_MHZShipPropeller
      // to ArtTile_MHZShipPropeller ($500), and loc_55814 renders
      // Map_MHZEndBossMisc frames 5-7 via Ani_MHZEndPropellers.
      public static final int ART_KOSM_MHZ_END_BOSS_PILLAR_ADDR = 0x159DFE;
      public static final int ART_KOSM_MHZ_SHIP_PROPELLER_ADDR = 0x159F10;
      public static final int MAP_MHZ_END_BOSS_MISC_ADDR = 0x055908;
      public static final int ART_TILE_MHZ_END_BOSS_PILLAR = 0x0580;
      public static final int ARTTILE_MHZ_SHIP_PROPELLER = 0x0500;

    // MHZ Act 1 miniboss and Act 2 end-boss art. All addresses are S&K-side
    // lock-on ROM offsets verified from sonic3k.asm labels and exact ROM byte
    // matches for the include mapping tables.
    public static final int ART_KOSM_MHZ_MINIBOSS_ADDR = 0x1680CA;
    public static final int ART_KOSM_MHZ_MINIBOSS_LOG_ADDR = 0x16908C;
    public static final int ART_KOSM_MHZ_END_BOSS_SPIKES_ADDR = 0x16942E;
    public static final int ART_KOSM_MHZ_KNUX_PEER_ADDR = 0x1695C0;
    public static final int ART_UNC_MHZ_KNUX_PRESS_ADDR = 0x169812;
    public static final int ART_UNC_MHZ_KNUX_PRESS_SIZE = 0x0860;
    public static final int ART_KOSM_MHZ_KNUX_SWITCH_ADDR = 0x16A072;
    public static final int ART_KOSM_MHZ_END_BOSS_ADDR = 0x16A104;
    public static final int ART_UNC_KNUX_INTRO_LAYING_ADDR = 0x1649A0;
    public static final int ART_UNC_KNUX_INTRO_LAYING_SIZE = 0x0660;
    public static final int MAP_KNUX_INTRO_LAYING_ADDR = 0x067352;
    public static final int DPLC_KNUX_INTRO_LAYING_ADDR = 0x0673D8;
    public static final int MAP_MHZ_END_BOSS_ADDR = 0x185F1C;
    public static final int MAP_MHZ_MINIBOSS_ADDR = 0x186168;
    public static final int MAP_MHZ_MINIBOSS_TREE_ADDR = 0x186A88;
    public static final int MAP_MHZ_MINIBOSS_LOG_ADDR = 0x186B18;
    public static final int MAP_MHZ_KNUX_PEER_ADDR = 0x066A52;
    // MHZ1CutsceneButton_LoadKnucklesPeer queues ArtKosM_MHZKnuxPeer to
    // ArtTile_MHZKnuxPeer (sonic3k.asm:130077-130081;
    // sonic3k.constants.asm:1272).
    public static final int ARTTILE_MHZ_KNUX_PEER = 0x0500;

    // Shared badnik-explosion art. Obj_SSEntryRing draws over ArtTile_Explosion
    // through its own DPLC, so SSEntryRing_Display re-queues this archive when
    // the ring retires (sonic3k.asm:128448-128490;
    // sonic3k.constants.asm:1404). Offset verified with RomOffsetFinder:
    // ArtKosM_BadnikExplosion -> 0xDB406, 2176 decompressed bytes.
    public static final int ART_KOSM_BADNIK_EXPLOSION_ADDR = 0x0DB406;
    public static final int ARTTILE_EXPLOSION = 0x05A0;
    public static final int MAP_MHZ_KNUX_DOOR_ADDR = 0x066A9C;
    public static final int MAP_MHZ_KNUX_PULL_SWITCH_ADDR = 0x066AD0;
    public static final int DPLC_MHZ_KNUX_PRESS_ADDR = 0x066B10;
    public static final int MAP_MHZ_KNUX_SWITCH_ADDR = 0x066B30;
    public static final int MAP_MHZ_KNUX_LEAVES_ADDR = 0x066B44;
    public static final int PAL_MHZ_END_BOSS_ADDR = 0x0769D4;
    public static final int PAL_MHZ_MINIBOSS_ADDR = 0x075F28;
    public static final int ARTTILE_MHZ_MINIBOSS_TREE = 0x0001;

    // ICZ direct-DMA source art used by AnimateTiles_ICZ. These assets live in
    // the lock-on S3 data block; LockOn Pointers.asm gives the sizes and ROM
    // search anchors ArtUnc_AniICZ__1 at 0x2B8580.
    public static final int ART_UNC_ANI_ICZ_1_ADDR = 0x2B8580;
    public static final int ART_UNC_ANI_ICZ_1_SIZE = 0x1000;
    public static final int ART_UNC_ANI_ICZ_2_ADDR = 0x2B9580;
    public static final int ART_UNC_ANI_ICZ_2_SIZE = 0x0200;
    public static final int ART_UNC_ANI_ICZ_3_ADDR = 0x2B9780;
    public static final int ART_UNC_ANI_ICZ_3_SIZE = 0x0100;
    public static final int ART_UNC_ANI_ICZ_4_ADDR = 0x2B9880;
    public static final int ART_UNC_ANI_ICZ_4_SIZE = 0x0080;
    public static final int ART_UNC_ANI_ICZ_5_ADDR = 0x2B9900;
    public static final int ART_UNC_ANI_ICZ_5_SIZE = 0x0040;

    // LBZ direct-DMA animated art sources from lock-on S3 split data.
    public static final int ART_UNC_ANI_LBZ1_1_ADDR = 0x2BA240;
    public static final int ART_UNC_ANI_LBZ1_1_SIZE = 0x1400;
    public static final int ART_UNC_ANI_LBZ1_2_ADDR = 0x2BB640;
    public static final int ART_UNC_ANI_LBZ1_2_SIZE = 0x0100;
    public static final int ART_UNC_ANI_LBZ2_2_ADDR = 0x2BB740;
    public static final int ART_UNC_ANI_LBZ2_2_SIZE = 0x0400;
    public static final int ART_UNC_ANI_LBZ2_WATERLINE_BELOW_ADDR = 0x2BBB40;
    public static final int ART_UNC_ANI_LBZ2_WATERLINE_BELOW_SIZE = 0x0200;
    public static final int ART_UNC_ANI_LBZ2_LOWER_BG_ADDR = 0x2BBD40;
    public static final int ART_UNC_ANI_LBZ2_LOWER_BG_SIZE = 0x0200;
    public static final int ART_UNC_ANI_LBZ2_WATERLINE_ABOVE_ADDR = 0x2BBF40;
    public static final int ART_UNC_ANI_LBZ2_WATERLINE_ABOVE_SIZE = 0x0200;
    public static final int ART_UNC_ANI_LBZ2_UPPER_BG_ADDR = 0x2BC140;
    public static final int ART_UNC_ANI_LBZ2_UPPER_BG_SIZE = 0x0200;
    public static final int ART_UNC_ANI_LBZ_SHARED_ADDR = 0x2BC340;
    public static final int ART_UNC_ANI_LBZ_SHARED_SIZE = 0x2000;

    // ===== Title Screen Art (Kosinski compressed, S3 lock-on data) =====
    // Sonic animation frames — frames 1-7 share Sonic1 art with different palettes/mappings
    public static final int ART_KOS_TITLE_SONIC1_ADDR = 0x350D26;   // ArtKos_S3TitleSonic1 (frames 1-7)
    public static final int ART_KOS_TITLE_SONIC8_ADDR = 0x351C86;   // ArtKos_S3TitleSonic8
    public static final int ART_KOS_TITLE_SONIC9_ADDR = 0x3542E6;   // ArtKos_S3TitleSonic9
    public static final int ART_KOS_TITLE_SONIC_A_ADDR = 0x3565E6;  // ArtKos_S3TitleSonicA
    public static final int ART_KOS_TITLE_SONIC_B_ADDR = 0x357AC6;  // ArtKos_S3TitleSonicB
    public static final int ART_KOS_TITLE_SONIC_C_ADDR = 0x358DE6;  // ArtKos_S3TitleSonicC
    public static final int ART_KOS_TITLE_SONIC_D_ADDR = 0x359FC6;  // ArtKos_S3TitleSonicD (final frame)

    // Title screen sprite art (Nemesis compressed)
    public static final int ART_NEM_TITLE_BANNER_ADDR = 0x35026C;       // ArtNem_Title_S3Banner (VRAM $A000, tile $500)
    public static final int ART_NEM_TITLE_SCREEN_TEXT_ADDR = 0x4D2A;     // ArtNem_TitleScreenText (VRAM $D000, tile $680)
    public static final int ART_NEM_TITLE_SONIC_SPRITES_ADDR = 0x2C49CC; // ArtNem_Title_SonicSprites (VRAM $8000, tile $400)
    public static final int ART_NEM_TITLE_AND_KNUCKLES_ADDR = 0xD6498;   // ArtNem_Title_ANDKnuckles (VRAM $9800, tile $4C0)

    // Title screen Enigma plane mappings (S3 lock-on data)
    public static final int MAP_ENI_TITLE_SONIC1_ADDR = 0x34F6A0;  // MapEni_S3TitleSonic1
    public static final int MAP_ENI_TITLE_SONIC2_ADDR = 0x34F75C;  // MapEni_S3TitleSonic2
    public static final int MAP_ENI_TITLE_SONIC3_ADDR = 0x34F81E;  // MapEni_S3TitleSonic3
    public static final int MAP_ENI_TITLE_SONIC4_ADDR = 0x34F8E2;  // MapEni_S3TitleSonic4
    public static final int MAP_ENI_TITLE_SONIC5_ADDR = 0x34F9A6;  // MapEni_S3TitleSonic5
    public static final int MAP_ENI_TITLE_SONIC6_ADDR = 0x34FA6A;  // MapEni_S3TitleSonic6
    public static final int MAP_ENI_TITLE_SONIC7_ADDR = 0x34FB30;  // MapEni_S3TitleSonic7
    public static final int MAP_ENI_TITLE_SONIC8_ADDR = 0x34FC2E;  // MapEni_S3TitleSonic8
    public static final int MAP_ENI_TITLE_SONIC9_ADDR = 0x34FD18;  // MapEni_S3TitleSonic9
    public static final int MAP_ENI_TITLE_SONIC_A_ADDR = 0x34FDE6; // MapEni_S3TitleSonicA
    public static final int MAP_ENI_TITLE_SONIC_B_ADDR = 0x34FEAA; // MapEni_S3TitleSonicB
    public static final int MAP_ENI_TITLE_SONIC_C_ADDR = 0x34FF48; // MapEni_S3TitleSonicC
    public static final int MAP_ENI_TITLE_SONIC_D_ADDR = 0x350018; // MapEni_S3TitleSonicD (final frame)
    public static final int MAP_ENI_TITLE_BG_ADDR = 0x350112;      // MapEni_S3TitleBg (Plane B background)

    // Title screen palettes (uncompressed, in S&K code section)
    public static final int PAL_TITLE_TRANSITION_ADDR = 0x459C;     // Pal_Title (112 bytes, 7 colors x 8 steps)
    public static final int PAL_TITLE_TRANSITION_SIZE = 112;
    public static final int PAL_TITLE_SONIC1_ADDR = 0x460C;         // Pal_TitleSonic1 (read 64 bytes for 2 palette lines)
    // Palettes 2-B are contiguous at 32-byte intervals: 0x462C, 0x464C, ...
    public static final int PAL_TITLE_SONIC_D_ADDR = 0x47AC;        // Pal_TitleSonicD (128 bytes = 4 palette lines)
    public static final int PAL_TITLE_SONIC_D_SIZE = 128;
    public static final int PAL_TITLE_WATER_ROT_ADDR = 0x4904;      // Pal_TitleWaterRot (32 bytes, banner palette cycling)
    public static final int PAL_TITLE_WATER_ROT_SIZE = 32;

    // Title screen Enigma mapping sizes (for read buffer allocation)
    public static final int MAP_ENI_TITLE_READ_SIZE = 1024;

    // Title screen VRAM tile constants (from sonic3k.constants.asm)
    public static final int VRAM_TITLE_BUFFER = 0x0300;          // ArtTile_Title_Buffer (double-buffer)
    public static final int VRAM_TITLE_MISC = 0x0400;            // ArtTile_Title_Misc (Sonic sprites)
    public static final int VRAM_TITLE_AND_KNUCKLES = 0x04C0;    // ArtTile_Title_ANDKnuckles
    public static final int VRAM_TITLE_BANNER = 0x0500;          // ArtTile_Title_Banner
    public static final int VRAM_TITLE_MENU = 0x0680;            // ArtTile_Title_Menu

    // ===== Title Card Art (KosinskiM compressed) =====
    // Shared art loaded to VRAM $500+
    public static final int ART_KOSM_TITLE_CARD_RED_ACT_ADDR = 0x0D6F28;   // Red banner + ACT text
    public static final int ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR = 0x15C3A2;  // "ZONE" shared letters
    public static final int ART_KOSM_TITLE_CARD_NUM1_ADDR = 0x0D6D84;      // Act 1 number art
    public static final int ART_KOSM_TITLE_CARD_NUM2_ADDR = 0x0D6E46;      // Act 2 number art

    // Zone-specific title card letter art, indexed by zone (0=AIZ, 1=HCZ, ...)
    public static final int[] TITLE_CARD_ZONE_ART_ADDRS = {
            0x39BDC8,  // 0  AIZ - ArtKosM_AIZTitleCard (960 bytes)
            0x39BEDA,  // 1  HCZ - ArtKosM_HCZTitleCard (1248 bytes)
            0x39C02C,  // 2  MGZ - ArtKosM_MGZTitleCard (1344 bytes)
            0x39C1EE,  // 3  CNZ - ArtKosM_CNZTitleCard (1536 bytes)
            0x0D710A,  // 4  FBZ - ArtKosM_FBZTitleCard (1536 bytes)
            0x39C4E2,  // 5  ICZ - ArtKosM_ICZTitleCard (672 bytes)
            0x39C5B4,  // 6  LBZ - ArtKosM_LBZTitleCard (1248 bytes)
            0x15C454,  // 7  MHZ - ArtKosM_MHZTitleCard (1248 bytes)
            0x15C5D6,  // 8  SOZ - ArtKosM_SOZTitleCard (960 bytes)
            0x15C6E8,  // 9  LRZ - ArtKosM_LRZTitleCard (864 bytes)
            0x15C7FA,  // 10 SSZ - ArtKosM_SSZTitleCard (1536 bytes)
            0x15C9BC,  // 11 DEZ - ArtKosM_DEZTitleCard (960 bytes)
            0x15CA9E,  // 12 DDZ - ArtKosM_DDZTitleCard (1440 bytes)
            0x15CC30,  // 13 HPZ - ArtKosM_HPZTitleCard (1152 bytes)
    };

    // Bonus stage title card letter art (KosinskiM, 354 bytes → 42 tiles)
    // Loaded to VRAM $54D in place of zone-specific letters.
    // ROM verified by binary match at 0x0D726C (follows ArtKosM_FBZTitleCard).
    public static final int ART_KOSM_BONUS_TITLE_CARD_ADDR = 0x0D726C;

    // VRAM tile destinations for title card art blocks
    public static final int VRAM_TITLE_CARD_BASE = 0x500;       // RedAct base
    public static final int VRAM_TITLE_CARD_ZONE_TEXT = 0x510;   // S3KZone text overwrites
    public static final int VRAM_TITLE_CARD_ACT_NUM = 0x53D;     // Act number art
    public static final int VRAM_TITLE_CARD_ZONE_ART = 0x54D;    // Zone-specific letters

    // ===== Results Screen Art (KosinskiM) =====
    public static final int ART_KOSM_RESULTS_GENERAL_ADDR = 0x0D6A62;    // "GOT THROUGH", bonus labels
    public static final int ART_KOSM_RESULTS_SONIC_ADDR = 0x15B95C;      // "SONIC" name art (S&K version; S3 version at 0x39A786 shows "SUPER")
    public static final int ART_KOSM_RESULTS_MILES_ADDR = 0x39AA18;      // "MILES" name art
    public static final int ART_KOSM_RESULTS_TAILS_ADDR = 0x39AB6A;      // "TAILS" name art
    public static final int ART_KOSM_RESULTS_KNUCKLES_ADDR = 0x0D67F0;   // "KNUCKLES" name art

    // ===== Results Screen Palette & Mappings =====
    public static final int PAL_RESULTS_ADDR = 0x22D39E;                 // 128 bytes, full palette
    public static final int MAP_RESULTS_ADDR = 0x0002F26A;               // Mapping frames (59 entries)

    // ===== Results Screen VRAM Layout =====
    public static final int VRAM_RESULTS_BASE = 0x520;                   // General art destination
    public static final int VRAM_RESULTS_NUMBERS = 0x568;                // Digit tile destination
    public static final int VRAM_RESULTS_CHAR_NAME_ACT1 = 0x578;         // Character name (act 1)
    public static final int VRAM_RESULTS_CHAR_NAME_ACT2 = 0x5A0;         // Character name (act 2)
    public static final int VRAM_RESULTS_ARRAY_SIZE = 0x200;             // Total tile range $520-$71F (includes HUD text tiles at $6xx)

    // ===== Special Stage Results Art (KosinskiM) =====
    // ROM: SpecialStage_Results (sonic3k.asm lines 63054-63094)
    public static final int ART_KOSM_SS_RESULTS_ADDR = 0x15BABE;          // SS results text art (149 tiles, 4768 bytes decompressed)
    public static final int ART_KOSM_SS_RESULTS_SUPER_ADDR = 0x15B374;    // Super form art (Sonic)
    public static final int ART_KOSM_SS_RESULTS_SUPER_K_ADDR = 0x15B4F6;  // Super form art (Knuckles)

    // ===== Special Stage Results VRAM Layout =====
    // Different from level results: character name at $4F1, Super art at $50F, text at $523, general at $5B8
    public static final int VRAM_SS_RESULTS_CHAR_NAME = 0x4F1;   // Character name art dest
    public static final int VRAM_SS_RESULTS_SUPER = 0x50F;        // Super form art dest
    public static final int VRAM_SS_RESULTS_TEXT = 0x523;          // SS results specific text dest
    public static final int VRAM_SS_RESULTS_GENERAL = 0x5B8;      // General results art dest (same art, different VRAM)
    public static final int VRAM_SS_RESULTS_HUD_TEXT = 0x6BC;       // Ring/HUD text art dest (Nemesis)
    public static final int VRAM_SS_RESULTS_HUD_INITIAL = 0x6E2;  // HUD_DrawInitial overlay dest
    public static final int VRAM_SS_RESULTS_BASE = 0x4F1;         // Lowest VRAM address used
    public static final int VRAM_SS_RESULTS_ARRAY_SIZE = 0x300;   // Tile range $4F1-$7F0

    // HUD text art (Nemesis) — loaded by HUD_DrawInitial to VRAM $6E2 in SS results
    public static final int ART_NEM_HUD_TEXT_ADDR = 0x35CDBA;   // 24 tiles (768 bytes)

    // ===== Shield Art (uncompressed binclude in S3 data region) =====
    // Verified by binary pattern match against skdisasm .bin files, 2026-02-17

    // ArtUnc_FireShield - Fire Shield.bin (269 tiles)
    public static final int ART_UNC_FIRE_SHIELD_ADDR = 0x18C704;
    public static final int ART_UNC_FIRE_SHIELD_SIZE = 8608;

    // ArtUnc_LightningShield - Lightning Shield.bin (130 tiles)
    public static final int ART_UNC_LIGHTNING_SHIELD_ADDR = 0x18E8A4;
    public static final int ART_UNC_LIGHTNING_SHIELD_SIZE = 4160;

    // ArtUnc_LightningShield_Sparks - Sparks.bin (5 tiles)
    public static final int ART_UNC_LIGHTNING_SHIELD_SPARKS_ADDR = 0x18F8E4;
    public static final int ART_UNC_LIGHTNING_SHIELD_SPARKS_SIZE = 160;

    // ArtUnc_BubbleShield - Bubble Shield.bin (138 tiles)
    public static final int ART_UNC_BUBBLE_SHIELD_ADDR = 0x18F984;
    public static final int ART_UNC_BUBBLE_SHIELD_SIZE = 4416;

    // ===== Shield Mappings, DPLCs, Animations =====
    // Verified by ROM binary search for offset table patterns, 2026-02-17

    // Fire Shield: 25 mapping frames, 25 DPLC frames, 2 animations
    public static final int ANI_FIRE_SHIELD_ADDR = 0x019A02;
    public static final int ANI_FIRE_SHIELD_COUNT = 2;
    public static final int MAP_FIRE_SHIELD_ADDR = 0x019AC6;
    public static final int DPLC_FIRE_SHIELD_ADDR = 0x019CE6;

    // Lightning Shield: 24 mapping frames, 23 DPLC frames, 3 animations
    public static final int ANI_LIGHTNING_SHIELD_ADDR = 0x019A2A;
    public static final int ANI_LIGHTNING_SHIELD_COUNT = 3;
    public static final int MAP_LIGHTNING_SHIELD_ADDR = 0x019DC8;
    public static final int DPLC_LIGHTNING_SHIELD_ADDR = 0x019EFA;

    // Bubble Shield: 13 mapping frames, 13 DPLC frames, 3 animations
    public static final int ANI_BUBBLE_SHIELD_ADDR = 0x019A7A;
    public static final int ANI_BUBBLE_SHIELD_COUNT = 3;
    public static final int MAP_BUBBLE_SHIELD_ADDR = 0x019F82;
    public static final int DPLC_BUBBLE_SHIELD_ADDR = 0x01A076;

    // Dash Dust / Splash / Drown: shared art for all characters
    // Verified by ROM binary search (offset table fingerprint + art data match), 2026-04-03
    public static final int ART_UNC_DASH_DUST_ADDR = 0x18A604;
    public static final int ART_UNC_DASH_DUST_SIZE = 5952;  // 186 tiles x 32 bytes
    public static final int MAP_DASH_DUST_ADDR = 0x018DF4;
    public static final int DPLC_DASH_DUST_ADDR = 0x018EE2;

    // Splash/Drown art (ArtUnc_SplashDrown). Shares Map_DashDust + DPLC_DashSplashDrown
    // (== DPLC_DASH_DUST_ADDR) with the dash dust; only the art source differs.
    // Used by Obj_DashDust anim 4 (Ani_DashSplashDrown frames $16-$1D), e.g. the LBZ1
    // surface emerge splash. This art exists ONLY in the lock-on (S3-half) data — there
    // is no S&K-side equivalent (sonic3k.asm only bincludes ArtUnc_DashDust); the runtime
    // resolves ArtUnc_SplashDrown to this lock-on address. ROM: General/Sprites/Dash Dust/
    // Splash Drown.bin (Lockon S3 data). Verified via RomOffsetFinder --game s3k.
    public static final int ART_UNC_SPLASH_DROWN_ADDR = 0x2C2280;
    public static final int ART_UNC_SPLASH_DROWN_SIZE = 3968;  // 124 tiles x 32 bytes

    // Insta-Shield: 8 mapping frames, 8 DPLC frames, 2 animations
    // Verified by ROM binary search, 2026-03-18
    public static final int ART_UNC_INSTA_SHIELD_ADDR = 0x18C084;
    public static final int ART_UNC_INSTA_SHIELD_SIZE = 1664;  // 52 tiles x 32 bytes
    public static final int ANI_INSTA_SHIELD_ADDR = 0x0199EA;
    public static final int ANI_INSTA_SHIELD_COUNT = 2;
    public static final int MAP_INSTA_SHIELD_ADDR = 0x01A0D0;
    public static final int DPLC_INSTA_SHIELD_ADDR = 0x01A154;

    // ArtUnc_Invincibility - Invincibility Stars art (32 tiles, uncompressed)
    // ROM: move.w #$200,d3 — DMA size in words ($200 words = 0x400 bytes = 32 tiles)
    // Verified by RomOffsetFinder, 2026-04-03
    public static final int ART_UNC_INVINCIBILITY_ADDR = 0x18A204;
    public static final int ART_UNC_INVINCIBILITY_SIZE = 0x400;     // 32 tiles × 32 bytes

    // Map_Invincibility - 9 mapping frames for invincibility star sprites
    // Verified by RomOffsetFinder, 2026-04-03
    public static final int MAP_INVINCIBILITY_ADDR = 0x018AEA;

    // ===== Collapsing Platform Mappings (Object 0x04) =====
    // Verified by ROM binary pattern search for offset table fingerprints, 2026-02-17

    // Map_AIZCollapsingPlatform - AIZ Act 1 collapsing platform mappings (4 frames)
    // Frames 0,1 = intact variants, frames 2,3 = fragment variants
    public static final int MAP_AIZ_COLLAPSING_PLATFORM_ADDR = 0x21E6C8;

    // Map_AIZCollapsingPlatform2 - AIZ Act 2 collapsing platform mappings (4 frames)
    public static final int MAP_AIZ_COLLAPSING_PLATFORM2_ADDR = 0x21E7AC;

    // Map_ICZCollapsingBridge - ICZ collapsing platform mappings (6 frames)
    public static final int MAP_ICZ_COLLAPSING_BRIDGE_ADDR = 0x21F2F2;
    // Map_ICZPlatforms - shared ICZ platform/freezer/debris mappings.
    // Address derived from Frame_363C94 label minus the 40-frame offset table.
    public static final int MAP_ICZ_PLATFORMS_ADDR = 0x363C44;
    // Map_ICZWallAndColumn - ICZ wall/column mappings, including Obj_ICZSegmentColumn frames $0A/$03.
    // Address derived from Frame_3639DC label minus the 14-frame offset table.
    public static final int MAP_ICZ_WALL_AND_COLUMN_ADDR = 0x3639C0;

    // ===== Collapsing Bridge Mappings (Object 0x0F) =====
    // Multi-zone bridge that collapses when the player stands on it.
    // S3 LockOn region addresses (>= 0x200000) for S3KL zones.

    // Map_LBZCollapsingBridge - LBZ bridge variant (3 frames: intact + 2 fragment directions)
    public static final int MAP_LBZ_COLLAPSING_BRIDGE_ADDR = 0x21E896;

    // Map_LBZCollapsingLedge - LBZ ledge variant (3 frames)
    public static final int MAP_LBZ_COLLAPSING_LEDGE_ADDR = 0x21E992;

    // Map_HCZCollapsingBridge - HCZ bridge (12 frames: 4 subtypes × 3 frames each)
    public static final int MAP_HCZ_COLLAPSING_BRIDGE_ADDR = 0x21EA1A;

    // Map_MGZCollapsingBridge - MGZ bridge (9 frames: 3 subtypes × 3 frames each)
    public static final int MAP_MGZ_COLLAPSING_BRIDGE_ADDR = 0x21EE68;

    // Map_ICZCollapsingBridge is shared with Object 0x04 (above): 0x21F2F2
    // Object 0x0F uses frames 3-5, Object 0x04 uses frames 0-3.

    // S&K side addresses (< 0x200000) for SKL zones and HPZ.

    // Map_HPZCollapsingBridge - HPZ bridge (3 frames)
    public static final int MAP_HPZ_COLLAPSING_BRIDGE_ADDR = 0x020FCE;

    // Map_LRZCollapsingPlatform - LRZ bridge via Object 0x0F (3 frames)
    public static final int MAP_LRZ_COLLAPSING_BRIDGE_0F_ADDR = 0x020F0E;

    // Map_FBZCollapsingBridge - FBZ bridge (3 frames)
    public static final int MAP_FBZ_COLLAPSING_BRIDGE_ADDR = 0x02108E;

    // Map_SOZCollapsingBridge - SOZ bridge (3 frames)
    public static final int MAP_SOZ_COLLAPSING_BRIDGE_ADDR = 0x02127A;

    // ===== AIZ Disappearing Floor Mappings (Object 0x29) =====
    // Map_AIZDisappearingFloor - 6 frames: parent visual overlay (frame 0 = invisible, 1-5 = crumbling)
    // In LockOn data region (S3 half). Interleaved with Map_AIZDisappearingFloor2 offset table.
    public static final int MAP_AIZ_DISAPPEARING_FLOOR_ADDR = 0x2294B4;

    // Map_AIZDisappearingFloor2 - 4 frames: water border effect rendered around the platform
    public static final int MAP_AIZ_DISAPPEARING_FLOOR_BORDER_ADDR = 0x2294C0;

    // ===== AIZ Flipping Bridge Mappings (Object 0x2B) =====
    // Map_AIZFlippingBridge - 32 frames: frames 0-4 = flipping animation, frames 5-31 = flat walkable segment.
    // In LockOn data region (S3 half). Verified by ROM binary pattern search, 2026-03-30.
    // Note: first pointer entry != table size, so auto-detect frame count fails; use explicit count 32.
    public static final int MAP_AIZ_FLIPPING_BRIDGE_ADDR = 0x22A310;

    // ===== AIZ Collapsing Log Bridge Mappings (Object 0x2C) =====
    // Verified by ROM binary pattern search, 2026-03-30

    // Map_AIZCollapsingLogBridge - 3 frames: frame 0/1 = log segment, frame 2 = end segment with debris
    public static final int MAP_AIZ_COLLAPSING_LOG_BRIDGE_ADDR = 0x02B070;

    // Map_AIZDrawBridge - 2 frames: frame 0 = empty, frame 1 = single 2x2 bridge segment
    // ROM: Obj_AIZDrawBridge uses make_art_tile(ArtTile_AIZMisc2, 2, 1)
    public static final int MAP_AIZ_DRAW_BRIDGE_ADDR = 0x02B558;

    // Map_AIZDrawBridgeFire - 8 frames: frames 0-2 = bridge pieces, frames 3-7 = fire animation
    public static final int MAP_AIZ_DRAW_BRIDGE_FIRE_ADDR = 0x02B092;
    public static final int ART_NEM_EGG_CAPSULE_ADDR = 0x0DD990;
    public static final int MAP_EGG_CAPSULE_ADDR = 0x086BFC;
    public static final int ARTTILE_EGG_CAPSULE = 0x0494;

    // ===== Level Object Mappings (parsed at runtime by S3kSpriteDataLoader) =====
    // Verified by ROM binary pattern search for offset table fingerprints, 2026-02-17

    // Map_AIZRock - AIZ Act 1 rock mappings (7 frames: 3 intact + 4 debris)
    // Referenced at s3.asm:36232: move.l #Map_AIZRock,mappings(a0)
    public static final int MAP_AIZ_ROCK_ADDR = 0x21DCDC;

    // Map_AIZRock2 - AIZ Act 2 rock mappings (7 frames: 3 intact + 4 debris)
    // Referenced at s3.asm:36240: move.l #Map_AIZRock2,mappings(a0)
    public static final int MAP_AIZ_ROCK2_ADDR = 0x21DD64;

    // Map_AIZMHZRideVine - AIZ/MHZ ride-vine mappings (36 frames).
    // Referenced at sonic3k.asm:46152 and 46802.
    // Base address derived from map include: first frame at 0x22BE6 with 0x48-byte offset table.
    public static final int MAP_AIZ_MHZ_RIDE_VINE_ADDR = 0x022B9E;

    // Map_LRZBreakableRock - LRZ Act 1 breakable rock mappings (11 frames)
    // Referenced at sonic3k.asm:43871: move.l #Map_LRZBreakableRock,mappings(a0)
    // Map_LRZCollapsingBridge (9 frames, sonic3k.asm:77581); the label sits
    // immediately after word_39E20 ($39E20 + 2 + 22*4 = $39E7A), confirmed by the
    // frame pointer table read from the ROM at that address (0012 001A 0022 ...).
    public static final int MAP_LRZ_COLLAPSING_BRIDGE_ADDR = 0x039E7A;
    public static final int MAP_LRZ_BREAKABLE_ROCK_ADDR = 0x0203D8;

    // Map_LRZBreakableRock2 - LRZ Act 2 breakable rock mappings (12 frames)
    // Referenced at sonic3k.asm:43876: move.l #Map_LRZBreakableRock2,mappings(a0)
    public static final int MAP_LRZ_BREAKABLE_ROCK2_ADDR = 0x02047A;

    // ===== AIZ Badnik mappings (SK side, verified from LockOn Pointers) =====
    // Map_Rhinobot - 8 mapping frames (DPLC-driven)
    public static final int MAP_RHINOBOT_ADDR = 0x3615A8;
    // DPLC_Rhinobot - object DPLC table (startTile in upper 12 bits)
    public static final int DPLC_RHINOBOT_ADDR = 0x36156E;
    // Map_Bloominator - 5 mapping frames (frame 4 is projectile seed)
    public static final int MAP_BLOOMINATOR_ADDR = 0x3616C0;
    // Map_MonkeyDude - 7 mapping frames (frame 6 is coconut projectile)
    public static final int MAP_MONKEY_DUDE_ADDR = 0x361776;

    // ===== AIZ Badnik dedicated art (SK side, verified from LockOn Pointers) =====
    // ArtUnc_AIZRhinobot - uncompressed source art used with DPLC_Rhinobot.
    public static final int ART_UNC_AIZ_RHINOBOT_ADDR = 0x36732A;
    public static final int ART_UNC_AIZ_RHINOBOT_SIZE = 0x0AA0;
    // ArtKosM_AIZ_Bloominator - Kosinski Moduled compressed art.
    public static final int ART_KOSM_AIZ_BLOOMINATOR_ADDR = 0x367DCA;
    // ArtKosM_AIZ_MonkeyDude - Kosinski Moduled compressed art.
    public static final int ART_KOSM_AIZ_MONKEY_DUDE_ADDR = 0x36800C;
    public static final int ARTTILE_AIZ_BLOOMINATOR = 0x052A;
    public static final int ARTTILE_AIZ_MONKEY_DUDE = 0x0548;
    public static final int ARTTILE_AIZ_CATERKILLER_JR = 0x055F;
    // Map_CaterKillerJr - 6 mapping frames (head, tall body, thin body, coconut large/med/small)
    public static final int MAP_CATERKILLER_JR_ADDR = 0x361A18;
    // ArtKosM_AIZ_CaterkillerJr - Kosinski Moduled compressed art ($202 bytes).
    public static final int ART_KOSM_AIZ_CATERKILLER_JR_ADDR = 0x3681FE;
    // AIZ1_8x8_Flames_KosM - fire overlay tiles loaded at x >= $2E00 (Act 1).
    public static final int ART_KOSM_AIZ1_FIRE_OVERLAY_ADDR = 0x3AF5D0;

    // ===== HCZ Badnik mappings (SK side, verified from LockOn Pointers) =====
    public static final int MAP_BUGGERNAUT_ADDR = 0x360EB4;           // Map_Buggernaut (6 frames)
    public static final int ART_NEM_BUGGERNAUT_ADDR = 0x36A3E0;      // ArtNem_HCZDragonfly (Nemesis, 16 tiles)
    public static final int MAP_BLASTOID_ADDR = 0x360DD0;
    public static final int MAP_TURBO_SPIKER_ADDR = 0x361212;
    public static final int MAP_TURBO_SPIKER_HIDDEN_ADDR = 0x087F40;  // Map_TurboSpikerHidden (S&K-side)
    public static final int MAP_MEGA_CHOPPER_ADDR = 0x360F26;
    public static final int MAP_POINTDEXTER_ADDR = 0x360E72;
    public static final int MAP_JAWZ_ADDR = 0x361364;
    public static final int ART_KOSM_HCZ_BLASTOID_ADDR = 0x36A7C6;
    public static final int ART_KOSM_HCZ_TURBO_SPIKER_ADDR = 0x36A968;
    public static final int ART_KOSM_HCZ_MEGA_CHOPPER_ADDR = 0x36A6C4;
    public static final int ART_KOSM_HCZ_POINTDEXTER_ADDR = 0x36AD8A;
    public static final int ART_KOSM_HCZ_JAWZ_ADDR = 0x36A552;
    public static final int ARTTILE_HCZ_TURBO_SPIKER = 0x0500;
    public static final int ARTTILE_HCZ_BLASTOID_JAWZ = 0x0539;
    public static final int ARTTILE_HCZ_MEGA_CHOPPER = 0x054D;
    public static final int ARTTILE_HCZ_POINTDEXTER = 0x0559;

    // ===== HCZ Water Wall / Geyser (Object 0x3B) =====
    // LockOn data (assembled into S3 half of combined ROM — no S&K-side copy exists).
    // Verified by ROM hex search: these byte patterns are absent from 0x000000-0x200000.
    public static final int ART_KOSM_HCZ_GEYSER_HORZ_ADDR = 0x390C02; // ArtKosM_HCZGeyserHorz
    public static final int ART_KOSM_HCZ_GEYSER_VERT_ADDR = 0x391394; // ArtKosM_HCZGeyserVert
    // Locked-on S3 data has no S&K-half duplicate; HCZ1BGE_Normal references
    // this archive through LockOn Data.asm.
    public static final int ART_KOSM_HCZ2_SECONDARY_ADDR = 0x3BFA6C;
    public static final int KOS_HCZ2_SECONDARY_BLOCK_ADDR = 0x3BF17C;
    public static final int KOS_HCZ2_SECONDARY_CHUNK_ADDR = 0x3C18EE;
    // MGZ_8x8_Primary_KosM queued by Obj_MGZ2DrillingRobotnik's flee tail.
    public static final int ART_KOSM_MGZ_PRIMARY_ADDR = 0x3C3EBE;
    public static final int KOS_MGZ2_SECONDARY_BLOCK_ADDR = 0x3C9CD2;
    public static final int KOSM_MGZ2_SECONDARY_ART_ADDR = 0x3CA132;
    public static final int KOS_MGZ2_SECONDARY_CHUNK_ADDR = 0x3CB1C4;
    public static final int KOS_LBZ2_SECONDARY_BLOCK_ADDR = 0x3E5F70;
    public static final int KOSM_LBZ2_SECONDARY_ART_ADDR = 0x3E77D0;
    public static final int KOS_LBZ2_CHUNK_ADDR = 0x3EAF04;
    // ArtTile_HCZGeyser - VRAM tile base for geyser art (both variants)
    public static final int ARTTILE_HCZ_GEYSER = 0x0500;

    // ===== MGZ Badnik Art =====
    public static final int ART_KOSM_MGZ_SPIKER_ADDR = 0x36E0C4;
    public static final int ART_KOSM_SPIKER_ADDR = ART_KOSM_MGZ_SPIKER_ADDR;
    public static final int MAP_SPIKER_ADDR = 0x361CB8;
    public static final int ART_KOSM_MGZ_MANTIS_ADDR = 0x36E2D6;
    public static final int ART_KOSM_MANTIS_ADDR = ART_KOSM_MGZ_MANTIS_ADDR;
    public static final int MAP_MANTIS_ADDR = 0x361D26;
    public static final int ART_UNC_BUBBLES_BADNIK_ADDR = 0x36D6A4;
    public static final int ART_UNC_BUBBLES_BADNIK_SIZE = 0x0A20;
    public static final int MAP_BUBBLES_BADNIK_ADDR = 0x361C68;
    public static final int DPLC_BUBBLES_BADNIK_ADDR = 0x361C40;
    public static final int ART_KOSM_MGZ_MINIBOSS_ADDR = 0x36B02C;
    public static final int MAP_MGZ_MINIBOSS_ADDR = 0x361972;
    public static final int ART_NEM_MGZ_SPIRE_ADDR = 0x36B2CE;
    public static final int MAP_MGZ_MINIBOSS_SPIRE_ADDR = 0x088B7E;
    public static final int PAL_MGZ_ADDR = 0x0A8F5C;
    public static final int ART_KOSM_MGZ_ENDBOSS_ADDR = 0x36B340;
    public static final int ART_UNC_MGZ_ENDBOSS_SCALED_ADDR = 0x36C572;
    public static final int ART_UNC_MGZ_ENDBOSS_SCALED_SIZE = 0x1000;
    public static final int MAP_SCALED_ART_ADDR = 0x024BE8;
    public static final int ARTTILE_MGZ_ENDBOSS_SCALED = 0x0469;
    // ROM ArtTile_MGZEndBoss / ArtTile_MGZEndBossDebris.
    public static final int ART_TILE_MGZ_END_BOSS = 0x033F;
    public static final int ART_TILE_MGZ_END_BOSS_DEBRIS = 0x045E;
    public static final int MAP_MGZ_ENDBOSS_ADDR = 0x362608;
    public static final int PAL_MGZ_ENDBOSS_ADDR = 0x06D97C;
    public static final int PAL_MGZ_FADE_CNZ_ADDR = 0x364896;
    public static final int PAL_MGZ_FADE_CNZ_ROW_SIZE = 32;
    public static final int PAL_MGZ_FADE_CNZ_ROWS = 16;
    public static final int ART_KOSM_MGZ_ENDBOSS_DEBRIS_ADDR = 0x36D572;
    public static final int MAP_MGZ_ENDBOSS_DEBRIS_ADDR = 0x3637D6;
    public static final int ARTTILE_MGZ_SPIKER = 0x0530;
    public static final int ARTTILE_MGZ_MINIBOSS = 0x054F;
    public static final int ARTTILE_MGZ_MANTIS = 0x054F;
    public static final int ARTTILE_MGZ_ENDBOSS_DEBRIS = 0x0570;

    // ===== CNZ Badnik Art =====
    public static final int ART_KOSM_CNZ_SPARKLE_ADDR = 0x3700CA;
    public static final int ART_KOSM_SPARKLE_ADDR = ART_KOSM_CNZ_SPARKLE_ADDR;
    public static final int MAP_SPARKLE_ADDR = 0x361B34;
    public static final int ART_KOSM_CNZ_BATBOT_ADDR = 0x3703EC;
    public static final int ART_KOSM_BATBOT_ADDR = ART_KOSM_CNZ_BATBOT_ADDR;
    public static final int MAP_BATBOT_ADDR = 0x361BD0;
    public static final int ART_UNC_CLAMER_ADDR = 0x36EF18;
    public static final int ART_UNC_CLAMER_SIZE = 0x1140;
    public static final int DPLC_CLAMER_ADDR = 0x361A78;
    public static final int MAP_CLAMER_ADDR = 0x361ABC;
    public static final int ART_KOSM_CLAMER_SHOT_ADDR = 0x370058;
    public static final int ART_KOSM_CNZ_BALLOON_ADDR = 0x37060E;
    public static final int ARTTILE_CNZ_SPARKLE = 0x0524;
    public static final int ARTTILE_CNZ_BATBOT = 0x0552;
    public static final int ARTTILE_CNZ_CLAMER_SHOT = 0x0570;
    // CNZ traversal object sheets live in the LockOn S3 half of the combined ROM.
    // Keep these addresses paired with the S3K disassembly labels; do not shift
    // them to raw Sonic 3 source offsets.
    public static final int MAP_CNZ_BALLOON_ADDR = 0x230502; // Map_CNZBalloon (25 frames)
    public static final int MAP_CNZ_CANNON_ADDR = 0x230A32; // Map_CNZCannon (10 frames)
    public static final int MAP_CNZ_RISING_PLATFORM_ADDR = 0x230CDC; // Map_CNZRisingPlatform (3 frames)
    public static final int MAP_CNZ_TRAP_DOOR_ADDR = 0x230DCC; // Map_CNZTrapDoor (3 frames)
    public static final int MAP_CNZ_LIGHT_BULB_ADDR = 0x230E52; // Map_CNZLightBulb (2 frames)
    public static final int MAP_CNZ_HOVER_FAN_ADDR = 0x231010; // Map_CNZHoverFan (8 frames)
    public static final int MAP_CNZ_CYLINDER_ADDR = 0x2317B0; // Map_CNZCylinder (4 frames)
    public static final int MAP_CNZ_BUMPER_ADDR = 0x2322CE; // Map_Bumper (2 frames)

    // Verified final lock-on offsets for the dedicated CNZ cannon art block.
    // The Cannon.bin data lives at the S3-side lock-on offset below; the DPLC
    // table remains S&K-side.
    public static final int ART_UNC_CNZ_CANNON_ADDR = 0x28CE74;
    public static final int ART_UNC_CNZ_CANNON_SIZE = 0x2AE6;
    // DPLC_CNZCannon is the S&K-side inline table used by Obj_CNZCannon in
    // sonic3k.asm. A byte-signature scan finds this same DPLC data at 0x031B72
    // and at the S3-side duplicate 0x230BB0; keep the S&K runtime address here.
    // Do not add a naive S3 half offset: 0x231B72 is code, not DPLC data.
    public static final int DPLC_CNZ_CANNON_ADDR = 0x031B72;

    // ArtTile_CNZMisc-derived VRAM tile bases used by the CNZ traversal objects.
    public static final int ARTTILE_CNZ_BALLOON = ARTTILE_CNZ_MISC;
    public static final int ARTTILE_CNZ_BALLOON_PLC = 0x0574; // ArtTile_CNZBalloon
    public static final int ARTTILE_CNZ_CANNON = ARTTILE_CNZ_MISC + 0x23;
    public static final int ARTTILE_CNZ_CANNON_DPLC_DEST = 0x0448;
    public static final int ARTTILE_CNZ_RISING_PLATFORM = ARTTILE_CNZ_MISC + 0x6D;
    public static final int ARTTILE_CNZ_TRAP_DOOR = ARTTILE_CNZ_MISC + 0x9F;
    public static final int ARTTILE_CNZ_LIGHT_BULB = ARTTILE_CNZ_MISC + 0xB3;
    public static final int ARTTILE_CNZ_HOVER_FAN = ARTTILE_CNZ_MISC + 0x97;
    public static final int ARTTILE_CNZ_CYLINDER = ARTTILE_CNZ_MISC + 0x3D;
    public static final int ARTTILE_CNZ_BUMPER = ARTTILE_CNZ_MISC + 0x13;

    // ===== FBZ Badnik Art =====
    public static final int ART_KOSM_FBZ_BLASTER_ADDR = 0x0DC6C2;
    public static final int MAP_BLASTER_ADDR = 0x08977C;
    public static final int ART_KOSM_FBZ_TECHNOSQUEEK_ADDR = 0x0DC9C4;
    public static final int MAP_TECHNOSQUEEK_ADDR = 0x089B78;
    public static final int ART_KOSM_FBZ_BUTTON_ADDR = 0x165E80;
    public static final int MAP_BUTTON_ADDR = 0x02C71E;
    public static final int MAP_LRZ_BUTTON_ADDR = 0x02C748;
    public static final int MAP_HCZ_BUTTON_ADDR = 0x22BD1A;
    public static final int MAP_CNZ_BUTTON_ADDR = 0x22BD4A;
    public static final int ARTTILE_MHZ1_CUTSCENE_BUTTON = 0x0341;
    public static final int ARTTILE_GRAY_BUTTON = 0x0456;
    public static final int ART_NEM_GRAY_BUTTON_ADDR = 0x190AC4;
    public static final int ARTTILE_HCZ_BUTTON = 0x0426;
    public static final int ARTTILE_CNZ_BUTTON = 0x041A; // ArtTile_CNZMisc + $C9
    public static final int ARTTILE_LRZ_MISC = 0x03A1;
    public static final int ARTTILE_LRZ2_BUTTON = 0x0429; // ArtTile_LRZ2Misc + $1C
    public static final int ARTTILE_FBZ_SPIKES = 0x0200;
    public static final int ARTTILE_FBZ_BUTTON = 0x0500;
    // FBZ consumer art/mappings, verified from the S&K-side sonic3k.lst symbol table.
    public static final int ART_KOSM_FBZ_MINIBOSS_ADDR = 0x1652B4;
    /** Pal_FBZMiniboss, locked-on S&K-side bytes (32-byte palette line). */
    public static final int PAL_FBZ_MINIBOSS_ADDR = 0x06FAC0;
    public static final int PAL_FBZ2_SUBBOSS_ADDR = 0x070420;
    public static final int PAL_FBZ_END_BOSS_ADDR = 0x070F94;
    public static final int ART_NEM_FBZ2_SUBBOSS_ADDR = 0x0DBDDE;
    public static final int ART_NEM_FBZ2_SUBBOSS_SIZE = 2368;
    public static final int ART_KOSM_FBZ_CLOUD_ADDR = 0x165826;
    public static final int ART_KOSM_FBZ_BOSS_PILLAR_ADDR = 0x165AB8;
    public static final int ART_NEM_FBZ_END_BOSS_ADDR = 0x0DC2E0;
    public static final int ART_NEM_FBZ_END_BOSS_SIZE = 1536;
    public static final int ART_KOSM_FBZ_EXIT_DOOR_ADDR = 0x165BCA;
    public static final int ART_KOSM_FBZ_EXIT_HALL_ADDR = 0x1651D2;
    public static final int ART_NEM_FBZ_EGG_CAPSULE_ADDR = 0x165C6C;
    public static final int ART_TILE_FBZ_CLOUD = 0x03A3;
    public static final int ART_TILE_FBZ_BOSS_PILLAR = 0x03D5;
    public static final int ART_TILE_FBZ_EXIT_DOOR = 0x03E5;
    public static final int ART_TILE_FBZ_EXIT_HALL = 0x03F4;
    public static final int MAP_FBZ_MINIBOSS_ADDR = 0x06FAF8;
    public static final int MAP_FBZ2_SUBBOSS_ADDR = 0x070440;
    public static final int MAP_SPRITE_MASK_ADDR = 0x18595E;
    public static final int MAP_FBZ2_PREBOSS_ADDR = 0x053518;
    public static final int MAP_FBZ_END_BOSS_ADDR = 0x070FB4;
    public static final int MAP_FBZ_EXIT_DOOR_ADDR = 0x070F7E;
    public static final int MAP_FBZ_EXIT_HALL_ADDR = 0x086D2A;
    public static final int MAP_FBZ_EGG_CAPSULE_ADDR = 0x1871E8;
    public static final int ARTTILE_BLASTER = 0x0506;
    public static final int ARTTILE_TECHNOSQUEEK = 0x052E;

    // ===== ICZ Badnik Art =====
    public static final int ART_KOSM_ICZ_SNOWDUST_ADDR = 0x375134;
    public static final int ARTTILE_ICZ_SNOWDUST = 0x0558;
    public static final int MAP_ICZ_SNOWDUST_ADDR = 0x361F0E;
    public static final int ART_KOSM_ICZ_STAR_POINTER_ADDR = 0x3751C6;
    public static final int ARTTILE_ICZ_STAR_POINTER = 0x0548;
    public static final int MAP_STAR_POINTER_ADDR = 0x361FAE;
    public static final int ART_UNC_ICZ_PENGUINATOR_ADDR = 0x374154;
    public static final int ART_UNC_ICZ_PENGUINATOR_SIZE = 4064;
    public static final int MAP_PENGUINATOR_ADDR = 0x361E90;
    public static final int DPLC_PENGUINATOR_ADDR = 0x361E4E;

    // ===== LBZ Badnik Art =====
    public static final int ART_KOSM_SNALE_BLASTER_ADDR = 0x377996;
    public static final int MAP_SNALE_BLASTER_ADDR = 0x360400;
    public static final int ART_KOSM_ORBINAUT_ADDR = 0x377D1A;
    public static final int MAP_ORBINAUT_ADDR = 0x3604A4;
    public static final int ART_KOSM_RIBOT_ADDR = 0x377BE8;
    public static final int MAP_RIBOT_ADDR = 0x3604B8;
    public static final int ART_KOSM_CORKEY_ADDR = 0x377DFC;
    public static final int MAP_CORKEY_ADDR = 0x3605C2;
    // PLCKosM_LBZ destination tiles (sonic3k.asm:64397-64402,
    // sonic3k.constants.asm:1259-1262)
    public static final int ARTTILE_SNALE_BLASTER = 0x0524;
    public static final int ARTTILE_ORBINAUT = 0x056E;
    public static final int ARTTILE_RIBOT = 0x0547;
    public static final int ARTTILE_CORKEY = 0x0558;
    // Obj_Flybot767 (sonic3k.asm:191981), LockOn data include.
    // Map, DPLC, and ArtUnc_Flybot767 verified by S&K-side ROM byte search.
    public static final int ART_UNC_FLYBOT_767_ADDR = 0x377EBE;
    public static final int ART_UNC_FLYBOT_767_SIZE = 4896;
    public static final int MAP_FLYBOT_767_ADDR = 0x36065A;
    public static final int DPLC_FLYBOT_767_ADDR = 0x3607EC;

    // ===== MHZ Badnik Art =====
    public static final int ART_KOSM_MADMOLE_ADDR = 0x165F02;
    public static final int MAP_MADMOLE_ADDR = 0x08D9F2;
    public static final int ART_KOSM_MUSHMEANIE_ADDR = 0x166234;
    public static final int MAP_MUSHMEANIE_ADDR = 0x08DCF8;
    public static final int ART_KOSM_DRAGONFLY_ADDR = 0x166386;
    public static final int MAP_DRAGONFLY_ADDR = 0x08DFDA;
    public static final int ART_UNC_BUTTERDROID_ADDR = 0x16652A;
    public static final int ART_UNC_BUTTERDROID_SIZE = 1376;
    public static final int MAP_BUTTERDROID_ADDR = 0x08E12E;
    public static final int DPLC_BUTTERDROID_ADDR = 0x08E160;
    public static final int ART_KOSM_CLUCKOID_ARROW_ADDR = 0x1664C8;
    public static final int ART_UNC_CLUCKOID_ADDR = 0x166A8A;
    public static final int ART_UNC_CLUCKOID_SIZE = 5696;
    public static final int MAP_CLUCKOID_ARROW_ADDR = 0x08E536;
    public static final int MAP_CLUCKOID_ADDR = 0x08E546;
    public static final int DPLC_CLUCKOID_ADDR = 0x08E4B8;

    // LoadEnemyArt destinations for PLCKosM_MHZ1 / PLCKosM_MHZ2
    // (sonic3k.constants.asm:1270,1276-1278; sonic3k.asm:64404-64415).
    public static final int ARTTILE_CLUCKOID = 0x0500;
    public static final int ARTTILE_CLUCKOID_ARROW = ARTTILE_CLUCKOID + 0x22;
    public static final int ARTTILE_DRAGONFLY = 0x0538;
    public static final int ARTTILE_MADMOLE = 0x0545;
    public static final int ARTTILE_MUSHMEANIE = 0x056D;

    public static final int MAP_MHZ_PULLEY_LIFT_ADDR = 0x03E720;
    public static final int MAP_MHZ_CURLED_VINE_ADDR = 0x03EA4C;
    public static final int MAP_MHZ_STICKY_VINE_ADDR = 0x03ED10;
    public static final int MAP_MHZ_SWING_BAR_HORIZONTAL_ADDR = 0x03F04A;
    public static final int MAP_MHZ_SWING_BAR_VERTICAL_ADDR = 0x03F360;
    public static final int MAP_MHZ_MUSHROOM_CAP_ADDR = 0x03E1FE;
    public static final int MAP_MHZ_POLLEN_ADDR = 0x03DC5C;
    public static final int MAP_MHZ_BIG_LEAVES_ADDR = 0x03DC74;
    public static final int MAP_MHZ_MUSHROOM_PLATFORM_ADDR = 0x03F44A;
    public static final int MAP_MHZ_MUSHROOM_PARACHUTE_ADDR = 0x03F852;
    public static final int MAP_MHZ_MUSHROOM_CATAPULT_ADDR = 0x03FB70;
    public static final int ARTTILE_MGZ_MHZ_DIAGONAL_SPRING = 0x0478;

    // ===== SOZ Badnik Art =====
    public static final int ART_KOSM_SKORP_ADDR = 0x16ADC6;
    public static final int MAP_SKORP_ADDR = 0x186C84;
    public static final int ART_KOSM_SANDWORM_ADDR = 0x16B038;
    public static final int MAP_SANDWORM_ADDR = 0x186D10;
    public static final int ART_KOSM_ROCKN_ADDR = 0x16B2BA;
    public static final int MAP_ROCKN_ADDR = 0x08F086;

    // ===== LRZ Badnik Art =====
    public static final int ART_UNC_FIREWORM_ADDR = 0x16EFB2;
    public static final int ART_UNC_FIREWORM_SIZE = 0x380;
    public static final int MAP_FIREWORM_ADDR = 0x8FABE;
    public static final int DPLC_FIREWORM_ADDR = 0x8FAA6;
    public static final int ART_KOSM_FIREWORM_SEGMENTS_ADDR = 0x16F332;
    public static final int MAP_FIREWORM_SEGMENTS_ADDR = 0x8FA5C;
    public static final int ART_KOSM_IWAMODOKI_ADDR = 0x16F4E4;
    public static final int MAP_IWAMODOKI_ADDR = 0x8FC90;
    public static final int ART_KOSM_TOXOMISTER_ADDR = 0x16F7E6;
    public static final int MAP_TOXOMISTER_ADDR = 0x9008E;

    // ===== SSZ/DDZ Badnik Art =====
    public static final int ART_KOSM_EGG_ROBO_BADNIK_ADDR = 0x17B17E;
    public static final int MAP_EGG_ROBO_ADDR = 0x184F34;

    // ===== DEZ Badnik Art =====
    public static final int ART_KOSM_SPIKEBONKER_ADDR = 0x18008C;
    public static final int MAP_SPIKEBONKER_ADDR = 0x184E5C;
    public static final int ART_KOSM_CHAINSPIKE_ADDR = 0x1803EE;
    public static final int MAP_CHAINSPIKE_ADDR = 0x184E8A;

    // ===== StillSprite / AnimatedStillSprite =====
    // Mapping tables (ROM addresses verified via binary search)
    public static final int MAP_STILL_SPRITES_ADDR = 0x02BA9A;
    public static final int MAP_ANIMATED_STILL_SPRITES_ADDR = 0x02BFDA;

    // VRAM tile destinations for shields
    public static final int ART_TILE_SHIELD = 0x079C;
    public static final int ART_TILE_SHIELD_SPARKS = 0x07BB;

    // ===== Known pattern data for ROM scanning =====
    // AIZ1 LevelSizes first entry: $1308, $6000, $0000, $0390
    public static final byte[] LEVEL_SIZES_AIZ1_PATTERN = {
        0x13, 0x08, 0x60, 0x00, 0x00, 0x00, 0x03, (byte) 0x90
    };

    // AIZ1 Sonic start location: X=$13A0, Y=$041A
    public static final byte[] START_LOC_AIZ1_PATTERN = {
        0x13, (byte) 0xA0, 0x04, 0x1A
    };

    // ===== Scanning state =====
    // ===== AIZ Miniboss (Object 0x90/0x91) =====
    // PLC 0x5A loads: ArtNem_AIZMiniboss, ArtNem_AIZMinibossSmall,
    //                 ArtNem_AIZBossFire, ArtNem_BossExplosion
    // Art addresses and VRAM tile indices are derived from PLC entries at runtime.
    public static final int PLC_AIZ_MINIBOSS = 0x5A;
    // Pal_AIZMiniboss - Boss palette (32 bytes = 16 colors)
    public static final int PAL_AIZ_MINIBOSS_ADDR = 0x6917C;
    // Map_AIZMiniboss - Boss sprite mappings (18 frames, 0x11A bytes)
    public static final int MAP_AIZ_MINIBOSS_ADDR = 0x3624D0;
    // Map_AIZMinibossFlame - Flame sprite mappings (5 frames, 0x64 bytes)
    public static final int MAP_AIZ_MINIBOSS_FLAME_ADDR = 0x36165C;
    // Map_AIZMinibossSmall - Small debris mappings (3 frames, 0x1E bytes)
    public static final int MAP_AIZ_MINIBOSS_SMALL_ADDR = 0x3625EA;
    // Map_BossExplosion - Boss explosion mappings (6 frames, shared with S2)
    public static final int MAP_BOSS_EXPLOSION_ADDR = 0x083FFC;

    // ===== HCZ Miniboss (Object 0x99) =====
    // PLC 0x5B loads ArtNem_HCZMiniboss.
    public static final int PLC_HCZ_MINIBOSS = 0x5B;
    // Pal_HCZMiniboss / Pal_HCZMinibossWater - normal + underwater palette variants.
    public static final int PAL_HCZ_MINIBOSS_ADDR = 0x06AE56;
    public static final int PAL_HCZ_MINIBOSS_WATER_ADDR = 0x06AE76;
    /**
     * Map_HCZMiniboss — table base for HCZ miniboss sprite mappings (36 frames).
     *
     * <p>ROM disasm: {@code Lockon S3/LockOn Data.asm:838} ({@code Map_HCZMiniboss:})
     * which {@code include}s {@code Levels/HCZ/Misc Object Data/Map - Miniboss.asm}.
     * The include file's first non-{@code Frame_} entry is
     * {@code dc.w Frame_362A28-Map_HCZMiniboss_} and there are 36 dc.w offset
     * entries (0x48 bytes), so the table base is at {@code 0x362A28 - 0x48 = 0x3629E0}.
     *
     * <p>Verified by reading the ROM at {@code 0x3629E0}: the first word reads
     * back as {@code 0x0048} (matches expected offset-table size), and the
     * first frame at {@code 0x3629E0 + 0x48 = 0x362A28} reports piece-count 4,
     * matching the source. Address lives in lock-on data ({@code >= 0x200000});
     * this label only exists in the lock-on / S3-half ROM space.
     */
    public static final int MAP_HCZ_MINIBOSS_ADDR = 0x3629E0;
    // ArtTile_HCZMiniboss - VRAM destination tile index from sonic3k.constants.asm.
    public static final int ART_TILE_HCZ_MINIBOSS = 0x0304;

    // ===== HCZ End Boss (Obj_HCZEndBoss, Object 0x9A) =====
    // PLC 0x6C loads boss body, Robotnik ship, boss explosion, and egg capsule art.
    public static final int PLC_HCZ_END_BOSS = 0x6C;
    // Pal_HCZEndBoss - end boss palette (palette line 1).
    public static final int PAL_HCZ_END_BOSS_ADDR = 0x06BF0A;
    // ArtTile_HCZEndBoss - VRAM destination tile index from sonic3k.constants.asm.
    public static final int ARTTILE_HCZ_END_BOSS = 0x0320;
    /**
     * Map_HCZEndBoss — table base for HCZ end boss sprite mappings (50 frames).
     *
     * <p>ROM disasm: {@code Lockon S3/LockOn Data.asm:856} ({@code Map_HCZEndBoss:})
     * which {@code include}s {@code Levels/HCZ/Misc Object Data/Map - End Boss.asm}.
     * The include file's first entry is {@code dc.w Frame_363538-Map_HCZEndBoss_}
     * and the table has 50 dc.w offset entries (0x64 bytes), so the table base is
     * {@code 0x363538 - 0x64 = 0x3634D4}.
     *
     * <p>Verified by reading the ROM at {@code 0x3634D4}: first word = {@code 0x0064}
     * (matches expected offset-table size), first frame at {@code 0x363538}
     * reports piece-count 4 matching the source. Address lives in lock-on data
     * ({@code >= 0x200000}); this label only exists in the lock-on / S3-half
     * ROM space.
     */
    public static final int MAP_HCZ_END_BOSS_ADDR = 0x3634D4;

    // HCZ Geyser Cutscene Art (ArtTile_HCZCutsceneGeyser, from sonic3k.constants.asm)
    public static final int ARTTILE_HCZ_CUTSCENE_GEYSER = 0x036B;
    /**
     * Map_HCZWaterWall — table base for HCZ waterwall / geyser sprite mappings
     * (11 frames).
     *
     * <p>ROM disasm: {@code Lockon S3/LockOn Data.asm:192} ({@code Map_HCZWaterWall:})
     * which {@code include}s {@code Levels/HCZ/Misc Object Data/Map - Waterfall.asm}.
     * The include file's first entry is {@code dc.w Frame_22EE26-Map_HCZWaterWall_}
     * and the table has 11 dc.w offset entries (0x16 bytes), so the table base
     * is {@code 0x22EE26 - 0x16 = 0x22EE10}.
     *
     * <p>Verified by reading the ROM at {@code 0x22EE10}: first word = {@code 0x0016}
     * (matches expected offset-table size), first frame at {@code 0x22EE26}
     * reports piece-count 14 matching the source. Address lives in lock-on data
     * ({@code >= 0x200000}); this label only exists in the lock-on / S3-half
     * ROM space.
     */
    public static final int MAP_HCZ_WATERWALL_ADDR = 0x22EE10;
    // Map_HCZWaterWallDebris: 8 debris frames, table base from Frame_22EF1E - 0x10.
    public static final int MAP_HCZ_WATERWALL_DEBRIS_ADDR = 0x22EF0E;

    // ===== CNZ Teleporter / Miniboss / End Boss art and PLC metadata =====
    // The CNZ teleporter route is split across Obj_CNZTeleporter and the shared
    // Obj_TeleporterBeam routines in sonic3k.asm. These constants back the
    // concrete CNZ teleporter, miniboss, and end-boss wrappers while keeping
    // art/mapping/PLC provenance separate from route scripting.

    // ArtKosM_CNZTeleport - dedicated Kosinski Moduled art queued by Obj_CNZTeleporter.
    // Verified with RomOffsetFinder against the S&K-side label:
    //   ArtKosM_CNZTeleport -> 0x159CAE, 512-byte decompressed payload.
    public static final int ART_KOSM_CNZ_TELEPORT_ADDR = 0x159CAE;

    // Map_SSZHPZTeleporter - shared mapping table used by both Obj_CNZTeleporterMain
    // and Obj_TeleporterBeam for the CNZ teleporter route. The include file exposes
    // 11 dc.w entries before the first frame label word_46B52, so the table starts
    // 22 bytes earlier at 0x046B3C.
    public static final int MAP_SSZ_HPZ_TELEPORTER_ADDR = 0x046B3C;

    /**
     * CNZ Act 1 miniboss PLC id.
     *
     * <p>ROM: {@code sonic3k.asm:144844} — {@code moveq #$5D,d0} then
     * {@code jsr (Load_PLC).l}. The engine previously held {@code 0x5C}
     * (off-by-one); corrected in workstream D.
     */
    public static final int PLC_CNZ_MINIBOSS = 0x5D;

    /**
     * CNZ Act 1 miniboss palette ROM offset (S&K-side).
     *
     * <p>ROM: {@code sonic3k.asm:144846} — {@code lea Pal_CNZMiniboss(pc),a1}
     * then {@code jmp (PalLoad_Line1).l}, loading 32 bytes (one VDP palette
     * line) into palette line 1. Verified via {@code RomOffsetFinder
     * search-rom} on the binary signature
     * {@code 00 00 0E EE 06 E0 02 80 00 40} from
     * {@code Levels/CNZ/Palettes/Miniboss.bin}: the S&K-side match is
     * {@code 0x06E370}; the {@code 0x24BF70} sibling lives in the S3 half and
     * must not be referenced from the engine.
     */
    public static final int PAL_CNZ_MINIBOSS_ADDR = 0x06E370;

    // Map_CNZMiniboss - CNZ miniboss mappings. The include file has 22 dc.w entries
    // before Frame_362F00, so the table base is 44 bytes earlier at 0x362ED4.
    public static final int MAP_CNZ_MINIBOSS_ADDR = 0x362ED4;

    // ===== CNZ Act 2 lights-off / water flash (loc_62480) =====
    /**
     * Pal_CNZFlash (Levels/CNZ/Palettes/Flash.bin) — 128 bytes = two 64-byte
     * flash variants. Variant A at +0, variant B at +0x40. Each variant is two
     * VDP palette lines (32 bytes -> engine palette index 2 "Normal_palette_line_3",
     * next 32 bytes -> engine palette index 3 "Normal_palette_line_4").
     *
     * <p>S&amp;K-side match at 0x66932; the 0x28C7B4 sibling is the S3 half and
     * must not be referenced from the engine.
     */
    public static final int PAL_CNZ_FLASH_ADDR = 0x66932;
    public static final int PAL_CNZ_FLASH_SIZE = 128;
    /**
     * Pal_CNZ (Levels/CNZ/Palettes/Main.bin), S&amp;K-side at 0xA8FBC (96 bytes).
     * loc_62480's restore branch copies {@code Pal_CNZ+$20} (64 bytes) back into
     * Normal_palette_line_3 to turn the lights back on.
     */
    public static final int PAL_CNZ_ADDR = 0xA8FBC;

    // ===== ICZ Miniboss (Object 0xBC) =====
    // ROM: Obj_ICZMiniboss loads PLC $5F (sonic3k.asm:149653).
    // PLC_5F loads ArtNem_ICZMiniboss to ArtTile_ICZMiniboss.
    public static final int PLC_ICZ_MINIBOSS = 0x5F;
    // Pal_ICZMiniboss - loaded by Obj_ICZMiniboss through PalLoad_Line1.
    // Verified from the locked-on S&K side by searching the 32-byte palette data:
    // 00 00 0E EE 0E CC 08 6C ... at ROM offset 0x0719DA.
    public static final int PAL_ICZ_MINIBOSS_ADDR = 0x0719DA;
    // Map_ICZMiniboss - include file has 15 dc.w entries before Frame_363362,
    // so the table base is 0x363362 - 0x1E = 0x363344.
    public static final int MAP_ICZ_MINIBOSS_ADDR = 0x363344;

    // ===== ICZ End Boss (Object 0xBD) =====
    // ROM: Obj_ICZEndBoss loads PLC $70 (sonic3k.asm:150580).
    // PLC_70 loads ArtNem_ICZEndBoss to ArtTile_ICZEndBoss plus shared assets.
    public static final int PLC_ICZ_END_BOSS = 0x70;
    // Pal_ICZEndBoss - loaded through PalLoad_Line1 after the PLC request.
    // The 32-byte palette binclude immediately precedes byte_723D0.
    public static final int PAL_ICZ_END_BOSS_ADDR = 0x0723B0;
    // Map_ICZEndBoss - include file has 25 dc.w entries before Frame_362CE6,
    // so the table base is 0x362CE6 - 0x32 = 0x362CB4.
    public static final int MAP_ICZ_END_BOSS_ADDR = 0x362CB4;

    // =====================================================================
    // CNZ Act 1 miniboss state machine
    // ROM refs (all sonic3k.asm, S&K-side):
    //   Obj_CNZMiniboss        line 144823  outer gate
    //   loc_6D9A8              line 144830  arena setup
    //   CNZMiniboss_Index      line 144874  routine dispatch table:
    //     Obj_CNZMinibossInit    line 144885  routine 0
    //     Obj_CNZMinibossLower   line 144898  routine 2
    //     Obj_CNZMinibossMove    line 144912  routine 4
    //     Obj_CNZMinibossMove    line 144912  routine 6 (same handler as routine 4)
    //     Obj_CNZMinibossOpening line 144941  routine 8
    //     Obj_CNZMinibossWaitHit line 144954  routine A
    //     Obj_CNZMinibossClosing line 144968  routine C
    //     Obj_CNZMinibossLower2  line 144972  routine E
    //   Obj_CNZMinibossEnd     line 144984  defeat handler — NOT in the dispatch
    //                                       table; invoked via the $34(a0) "next
    //                                       handler" pointer from
    //                                       CNZMiniboss_CheckPlayerHit when the
    //                                       hit counter reaches zero.
    // =====================================================================

    /** Arena camera X minimum. ROM: loc_6D9A8 `move.w d0,(Camera_min_X_pos).w`
     *  after `move.w #$31E0,d0`. */
    public static final int CNZ_MINIBOSS_ARENA_MIN_X = 0x31E0;

    /** Arena camera X maximum. ROM: loc_6D9A8 `addi.w #$80,d0`. */
    public static final int CNZ_MINIBOSS_ARENA_MAX_X = 0x3260;

    /** Arena camera Y minimum. ROM: loc_6D9A8 `move.w #$1C0,(Camera_min_Y_pos).w`. */
    public static final int CNZ_MINIBOSS_ARENA_MIN_Y = 0x01C0;

    /** Arena camera Y maximum / target max Y. ROM: loc_6D9A8 `move.w #$2B8,...`. */
    public static final int CNZ_MINIBOSS_ARENA_MAX_Y = 0x02B8;

    /** Boss collision property. ROM: Obj_CNZMinibossInit `move.b #6,collision_property(a0)`. */
    public static final int CNZ_MINIBOSS_COLLISION_PROPERTY = 0x06;

    /** Real boss damage counter. ROM: Obj_CNZMinibossInit `move.b #4,$45(a0)`. */
    public static final int CNZ_MINIBOSS_REAL_HITS = 0x04;

    /** Boss hit count used by shared boss state. */
    public static final int CNZ_MINIBOSS_HIT_COUNT = CNZ_MINIBOSS_REAL_HITS;

    /** Initial descent y_vel. ROM: Obj_CNZMinibossInit `move.w #$80,y_vel(a0)`. */
    public static final short CNZ_MINIBOSS_INIT_Y_VEL = (short) 0x0080;

    /** Swing x_vel magnitude. ROM: Obj_CNZMinibossGo3 `move.w #$100,x_vel(a0)`. */
    public static final short CNZ_MINIBOSS_SWING_X_VEL = (short) 0x0100;

    /** Init wait timer. ROM: Obj_CNZMinibossInit `move.w #$11F,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_INIT_WAIT = 0x11F;

    /** Go2 wait timer. ROM: Obj_CNZMinibossGo2 `move.w #$90,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_GO2_WAIT = 0x90;

    /** Swing (Go3) wait timer. ROM: Obj_CNZMinibossGo3 `move.w #$9F,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_SWING_WAIT = 0x9F;

    /** Direction-change wait. ROM: Obj_CNZMinibossChangeDir `move.w #$13F,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_CHANGEDIR_WAIT = 0x13F;

    // =====================================================================
    // CNZ Act 1 miniboss top piece (bouncing-ball).
    // ROM refs (all sonic3k.asm, S&K-side):
    //   Obj_CNZMinibossTop          line 145004
    //   CNZMinibossTop_Index        line 145011 (routine 0/2/4/6 dispatch)
    //     Obj_CNZMinibossTopInit    line 145018 (routine 0)
    //     Obj_CNZMinibossTopWait    line 145026 (routine 2)
    //     Obj_CNZMinibossTopWait2   line 145040 (routine 4)
    //     Obj_CNZMinibossTopMain    line 145053 (routine 6)
    //   Obj_CNZMinibossTopGo        line 145045 ($34 post-wait handler
    //                                            installed by TopWait)
    //   CNZMiniboss_BlockExplosion  line 145204 (snaps impact coords to
    //                                            the 0x20 block grid)
    // =====================================================================

    /** ROM: Obj_CNZMinibossTopGo `move.w #$200,x_vel(a0)` (sonic3k.asm:145048). */
    public static final short CNZ_MINIBOSS_TOP_INIT_X_VEL = (short) 0x0200;

    /** ROM: Obj_CNZMinibossTopGo `move.w #$200,y_vel(a0)` (sonic3k.asm:145049). */
    public static final short CNZ_MINIBOSS_TOP_INIT_Y_VEL = (short) 0x0200;

    /** Arena right-wall screen-edge limit.
     *  ROM: Obj_CNZMinibossTopMain `cmpi.w #$3380,d0` (sonic3k.asm:145073). */
    public static final int CNZ_MINIBOSS_TOP_ARENA_RIGHT = 0x3380;

    /** Arena left-wall screen-edge limit.
     *  ROM: Obj_CNZMinibossTopMain `cmpi.w #$3200,d0` (sonic3k.asm:145088). */
    public static final int CNZ_MINIBOSS_TOP_ARENA_LEFT = 0x3200;

    /** Arena floor lower bound.
     *  ROM: Obj_CNZMinibossTopMain `cmpi.w #$380,d1` (sonic3k.asm:145109). */
    public static final int CNZ_MINIBOSS_TOP_ARENA_BOTTOM = 0x0380;

    /** Arena ceiling upper bound.
     *  ROM: Obj_CNZMinibossTopMain `cmpi.w #$240,d1` (sonic3k.asm:145125). */
    public static final int CNZ_MINIBOSS_TOP_ARENA_TOP = 0x0240;

    /** Half-width used when probing the next-frame X edge vs the arena wall.
     *  ROM: Obj_CNZMinibossTopMain `addi.w #$10,d0` / `subi.w #$10,d0`
     *  (sonic3k.asm:145072, 145087). */
    public static final int CNZ_MINIBOSS_TOP_WALL_PROBE_DX = 0x10;

    /** Half-height used when probing the next-frame Y edge vs the arena floor/ceiling.
     *  ROM: Obj_CNZMinibossTopMain `addq.w #8,d1` / `subq.w #8,d1`
     *  (sonic3k.asm:145104, 145122). */
    public static final int CNZ_MINIBOSS_TOP_FLOOR_PROBE_DY = 0x08;

    // PLC 0x6E loads ArtNem_CNZEndBoss, ArtNem_RobotnikShip, ArtNem_BossExplosion,
    // and ArtNem_EggCapsule for Obj_CNZEndBoss and its post-defeat handoff.
    public static final int PLC_CNZ_END_BOSS = 0x6E;
    /** Pal_CNZEndBoss, S&K-side inline palette loaded by Obj_CNZEndBoss. */
    public static final int PAL_CNZ_END_BOSS_ADDR = 0x06EE48;

    // Map_CNZEndBoss - CNZ end-boss mappings. The include file has 13 dc.w entries
    // before Frame_3609C4, so the table base is 26 bytes earlier at 0x3609AA.
    public static final int MAP_CNZ_END_BOSS_ADDR = 0x3609AA;

    // ===== AIZ End Boss (Object 0x92) =====
    // ArtKosM_AIZEndBoss - Main boss art (Kosinski Moduled, 15712 bytes)
    public static final int ART_KOSM_AIZ_END_BOSS_ADDR = 0x365260;
    // ArtTile_AIZEndBoss - VRAM destination tile index
    public static final int ART_TILE_AIZ_END_BOSS = 0x0180;
    // PLC 0x6B loads: ArtNem_RobotnikShip + ArtNem_BossExplosion (shared Eggman ship/explosion art)
    public static final int PLC_AIZ_END_BOSS = 0x6B;
    // Pal_AIZEndBoss - End boss palette (32 bytes = 16 colors, palette line 2)
    public static final int PAL_AIZ_END_BOSS_ADDR = 0x69E80;
    // Map_AIZEndBoss - Boss sprite mappings (56 frames)
    public static final int MAP_AIZ_END_BOSS_ADDR = 0x361FD6;
    // Map_RobotnikShip - Robotnik ship sprite mappings (13 frames, shared)
    public static final int MAP_ROBOTNIK_SHIP_ADDR = 0x06820C;
    /** AniRaw_EggRoboHead / Map_EggRoboHead in the S&K-side shared ship block. */
    public static final int ANI_RAW_ROBOTNIK_HEAD_ADDR = 0x0681CC;
    public static final int ANI_RAW_EGG_ROBO_HEAD_ADDR = 0x0681D0;
    public static final int MAP_EGG_ROBO_HEAD_ADDR = 0x0681D4;
    public static final int MAP_EGG_ROBO_HEAD_SIZE = 0x28;
    public static final int ART_KOSM_EGG_ROBO_HEAD_ADDR = 0x15FDDC;
    public static final int ART_KOSM_EGG_ROBO_HEAD_SIZE = 0x1E2;
    // ArtTile_RobotnikShip - VRAM tile for shared Robotnik ship
    public static final int ART_TILE_ROBOTNIK_SHIP = 0x052E;
    // Map_LBZMinibossBox - LBZ1 carried yellow-box mappings.
    // The locked-on object code references this S3-half include through Lockon S3 data.
    public static final int MAP_LBZ_MINIBOSS_BOX_ADDR = 0x36036A;
    // Map_LBZMiniboss - LBZ1 miniboss mappings.
    // Like the box mapping, the locked-on object code references this Lockon S3 include.
    public static final int MAP_LBZ_MINIBOSS_ADDR = 0x3602C8;
    // ArtKosM_LBZMiniboss - Kosinski Moduled miniboss art queued by Obj_LBZ1Robotnik/sub_8D0EA.
    public static final int ART_KOSM_LBZ_MINIBOSS_ADDR = 0x375358;
    // Pal_LBZMiniboss - loaded into palette line 1 by Obj_LBZMiniboss init.
    public static final int PAL_LBZ_MINIBOSS_ADDR = 0x07299A;
    // ArtTile_LBZMiniboss - VRAM tile for the miniboss body.
    public static final int ART_TILE_LBZ_MINIBOSS = 0x04D6;
    // ArtKosM_LBZMinibossBox - Kosinski Moduled box art queued by Obj_LBZ1Robotnik.
    public static final int ART_KOSM_LBZ_MINIBOSS_BOX_ADDR = 0x37567A;
    // ArtTile_LBZMinibossBox - VRAM tile for the carried yellow box.
    public static final int ART_TILE_LBZ_MINIBOSS_BOX = 0x0456;
    // ArtTile_BossExplosion2 - VRAM tile for boss explosion (PLC_6B)
    public static final int ART_TILE_BOSS_EXPLOSION_2 = 0x04D2;
    // ArtTile_BossExplosion - shared boss explosion tile base for LBZ2 PLCs.
    public static final int ART_TILE_BOSS_EXPLOSION = 0x0500;

    // ===== LBZ2 End Sequence (Objects 0xC6, 0xC8, 0xCA, 0xCB) =====
    // LBZ end-sequence asset addresses legitimately point into the locked-on S3 half.
    // ArtKosM_LBZEndBoss - spike-ball launcher boss art (51 tiles).
    public static final int ART_KOSM_LBZ_END_BOSS_ADDR = 0x376542;
    public static final int ART_KOSM_LBZ_END_BOSS_SIZE = 1632;
    public static final int MAP_LBZ_END_BOSS_ADDR = 0x360896; // Map_LBZEndBoss (15 frames)
    public static final int PAL_LBZ_END_BOSS_ADDR = 0x0741FE;
    public static final int ART_TILE_LBZ_END_BOSS = 0x0425;
    // ArtNem_LBZFinalBoss1 - Robotnik ship + laser-turret column (174 tiles).
    public static final int ART_NEM_LBZ_FINAL_BOSS_1_ADDR = 0x37599C;
    public static final int ART_NEM_LBZ_FINAL_BOSS_1_SIZE = 5568;
    public static final int MAP_LBZ_FINAL_BOSS_1_ADDR = 0x3645A8; // Map_LBZFinalBoss1 (46 frames)
    public static final int PAL_LBZ_FINAL_BOSS_1_ADDR = 0x073886;
    public static final int ART_TILE_LBZ_FINAL_BOSS_1 = 0x03AA;
    // Obj_LBZFinalBoss2 code is in the S&K half but directly references these
    // locked-on S3-half art/motion labels. The inline behavior tables remain
    // S&K-side addresses alongside the object routine.
    public static final int ART_KOSM_LBZ_FINAL_BOSS_2_ADDR = 0x376874;
    public static final int ART_KOSM_LBZ_FINAL_BOSS_2_SIZE = 0x1122;
    public static final int MAP_LBZ_FINAL_BOSS_2_ADDR = 0x364A96;
    public static final int MAP_LBZ_FINAL_BOSS_2_SIZE = 0x15C;
    public static final int PAL_LBZ_FINAL_BOSS_2_ADDR = 0x0751AA;
    public static final int ART_TILE_LBZ_FINAL_BOSS_2 = 0x03D9;
    public static final int LBZ_FINAL_BOSS_2_CIRCLE_TABLE_ADDR = 0x360B08;
    public static final int LBZ_FINAL_BOSS_2_CIRCLE_TABLE_2_ADDR = 0x3629A0;
    public static final int LBZ_FINAL_BOSS_2_MOTION_TABLE_ADDR = 0x074F72;
    public static final int LBZ_FINAL_BOSS_2_MOTION_TABLE_2_ADDR = 0x074F7A;
    public static final int LBZ_FINAL_BOSS_2_ESCAPE_POSITIONS_ADDR = 0x074E7C;
    public static final int LBZ_FINAL_BOSS_2_FLASH_OFFSETS_ADDR = 0x075092;
    public static final int LBZ_FINAL_BOSS_2_FLASH_WORDS_ADDR = 0x07509E;
    public static final int LBZ_FINAL_BOSS_2_SEGMENT_ANIM_ADDR = 0x075194;
    public static final int LBZ_FINAL_BOSS_2_SEGMENT_ANIM_2_ADDR = 0x07519C;
    public static final int LBZ_FINAL_BOSS_2_INITIAL_CHILD_TABLE_ADDR = 0x075122;
    public static final int LBZ_FINAL_BOSS_2_LANDING_CHILD_TABLE_ADDR = 0x07513C;
    public static final int LBZ_FINAL_BOSS_2_ARM_CHILD_TABLE_ADDR = 0x075144;
    public static final int LBZ_FINAL_BOSS_2_DEBRIS_CHILD_TABLE_ADDR = 0x07515E;
    public static final int LBZ_FINAL_BOSS_2_FLOOR_CHILD_TABLE_ADDR = 0x07517E;
    public static final int LBZ_FINAL_BOSS_2_FOLLOW_CHILD_TABLE_ADDR = 0x075186;
    public static final int LBZ_FINAL_BOSS_2_EMITTER_CHILD_TABLE_ADDR = 0x07518E;
    public static final int ANI_RAW_BOSS_EXPLOSION_ADDR = 0x083FCC;
    public static final int SCREEN_SHAKE_ARRAY_ADDR = 0x04F424;
    public static final int BOSS_EXPLOSION_HITBOX_CHILD_TABLE_ADDR = 0x0690D8;
    /** First word of {@code word_72FEA}; copied to {@code _unkFAB0} on Knuckles' LBZ route. */
    public static final int LBZ_FINAL_BOSS_KNUX_BOUNDS_ADDR = 0x072FEA;
    public static final int OBJECT_VELOCITY_INDEX_ADDR = 0x0852F4;
    // ArtKosM_LBZ2DeathEggSmall - ending/launch miniature Death Egg art (82 tiles).
    public static final int ART_KOSM_LBZ2_DEATH_EGG_SMALL_ADDR = 0x37921C;
    public static final int ART_KOSM_LBZ2_DEATH_EGG_SMALL_SIZE = 2624;
    public static final int MAP_LBZ_DEATH_EGG_SMALL_ADDR = 0x36480C; // Map_LBZDeathEggSmall (12 frames)
    public static final int PAL_LBZ_ENDING_ADDR = 0x0738A6;
    public static final int ART_TILE_LBZ2_DEATH_EGG_SMALL = 0x04AE;
    // Obj_LBZKnuxPillar uses the Death Egg 2 art loaded by the terrain swap.
    public static final int MAP_LBZ_KNUX_PILLAR_ADDR = 0x062AFC; // Map_LBZKnuxPillar (2 frames)
    public static final int ART_TILE_LBZ_KNUX_PILLAR = 0x05A0;
    // FBZ Robotnik running frames reused by the LBZ end boss intro.
    public static final int ART_NEM_FBZ_ROBOTNIK_RUN_ADDR = 0x0D8302;
    public static final int ART_NEM_FBZ_ROBOTNIK_RUN_SIZE = 2784;
    public static final int MAP_FBZ_ROBOTNIK_RUN_ADDR = 0x06837E;
    public static final int ART_TILE_FBZ_ROBOTNIK_RUN = 0x04A9;
    public static final int ART_NEM_FBZ_ROBOTNIK_HEAD_ADDR = 0x0D7C7A;
    public static final int ART_NEM_FBZ_ROBOTNIK_HEAD_SIZE = 1024;
    public static final int MAP_FBZ_ROBOTNIK_HEAD_ADDR = 0x068454;
    public static final int ART_TILE_FBZ_ROBOTNIK_HEAD = 0x0430;
    public static final int ART_KOSM_FBZ_EGGROBO_HEAD_ADDR = 0x15FDDC;
    public static final int ART_KOSM_FBZ_EGGROBO_HEAD_SIZE = 1024;
    public static final int MAP_FBZ_EGGROBO_HEAD_ADDR = 0x0681D4;
    public static final int ART_NEM_FBZ_ROBOTNIK_STAND_ADDR = 0x0D7EEC;
    public static final int ART_NEM_FBZ_ROBOTNIK_STAND_SIZE = 2144;
    public static final int MAP_FBZ_ROBOTNIK_STAND_ADDR = 0x06847C;
    public static final int ART_TILE_FBZ_ROBOTNIK_STAND = 0x0466;
    public static final int ART_NEM_FBZ_EGGROBO_RUN_ADDR = 0x15FFBE;
    public static final int ART_NEM_FBZ_EGGROBO_RUN_SIZE = 2208;
    public static final int MAP_FBZ_EGGROBO_RUN_ADDR = 0x186C20;
    public static final int ART_NEM_FBZ_EGGROBO_STAND_ADDR = 0x160340;
    public static final int ART_NEM_FBZ_EGGROBO_STAND_SIZE = 1984;
    public static final int MAP_FBZ_EGGROBO_STAND_ADDR = 0x186BB0;
    public static final int ART_NEM_FBZ_END_BOSS_FLAME_ADDR = 0x0DDFE6;
    public static final int ART_NEM_FBZ_END_BOSS_FLAME_SIZE = 2176;
    public static final int MAP_FBZ_END_BOSS_FLAME_ADDR = 0x071090;
    public static final int ART_TILE_FBZ_END_BOSS_FLAME = 0x0450;
    public static final int ART_NEM_ROBOTNIK_SHIP_SIZE = 2624;
    public static final int ART_NEM_BOSS_EXPLOSION_SIZE = 1472;
    public static final int ART_NEM_EGG_CAPSULE_SIZE = 2240;
    public static final int PLC_LBZ2_FINAL_BOSS_1 = 0x71;
    public static final int PLC_LBZ2_EGGMAN = 0x77;
    // LBZ2 Death Egg terrain swap data queued by Dynamic_resize_routine.
    public static final int LBZ2_16X16_DEATH_EGG_KOS_ADDR = 0x3E69B0;
    public static final int LBZ2_16X16_DEATH_EGG_OUTPUT_SIZE = 0x1610;
    public static final int LBZ2_16X16_DEATH_EGG_DEST_BLOCK = 0x0000;
    public static final int LBZ2_128X128_DEATH_EGG_KOS_ADDR = 0x3ED3D4;
    public static final int LBZ2_128X128_DEATH_EGG_OUTPUT_SIZE = 0x7C00;
    public static final int LBZ2_128X128_DEATH_EGG_DEST_CHUNK = 0x0000;
    public static final int LBZ2_8X8_DEATH_EGG_KOSM_ADDR = 0x3E8F72;
    public static final int LBZ2_8X8_DEATH_EGG_OUTPUT_SIZE = 0x64A0;
    public static final int LBZ2_8X8_DEATH_EGG_DEST_TILE = 0x0000;
    public static final int ART_KOSM_LBZ2_DEATH_EGG_2_8X8_ADDR = 0x37F6EE;
    public static final int ART_KOSM_LBZ2_DEATH_EGG_2_8X8_OUTPUT_SIZE = 0x0200;
    public static final int ART_TILE_LBZ2_DEATH_EGG_2 = 0x05A0;

    // ===== AIZ2 Battleship / Bombing Sequence =====
    // AIZ2_16x16_BomberShip_Kos - Kosinski-compressed 16x16 ship blocks (S3 half 0x1B1372 + 0x200000)
    public static final int AIZ2_16X16_BOMBERSHIP_ADDR = 0x3B1372;
    // AIZ2_16x16_BomberShip_Kos is queued to Block_table+$AB8 in the ROM.
    // In engine terminology this patches Chunk data (16x16 tiles).
    public static final int AIZ2_16X16_BOMBERSHIP_DEST_OFFSET = 0x0AB8;
    // AIZ2_8x8_BomberShip_KosM - Kosinski Moduled 8x8 ship tiles (S3 half 0x1B48C6 + 0x200000)
    public static final int AIZ2_8X8_BOMBERSHIP_ADDR = 0x3B48C6;
    // Queue_Kos_Module(AIZ2_8x8_BomberShip_KosM, tile $1FC)
    public static final int AIZ2_8X8_BOMBERSHIP_DEST_TILE = 0x01FC;
    public static final int AIZ2_8X8_BOMBERSHIP_DEST_BYTES =
            AIZ2_8X8_BOMBERSHIP_DEST_TILE * Pattern.PATTERN_SIZE_IN_ROM;
    // ArtKosM_AIZ2Bombership2_8x8 - KosinskiModuled art (176 tiles, covers all mapping indices)
    public static final int ART_KOSM_AIZ2_BOMBERSHIP_ADDR = 0x399CC4;
    // ArtTile_AIZ2Bombership - VRAM tile for bombership/bomb art
    public static final int ART_TILE_AIZ2_BOMBERSHIP = 0x0500;
    // Map_AIZ2BombExplode - Bomb explosion mappings (12 frames)
    public static final int MAP_AIZ2_BOMB_EXPLODE_ADDR = 0x23C1B2;
    // Map_AIZShipPropeller - Ship propeller mappings (4 frames)
    public static final int MAP_AIZ_SHIP_PROPELLER_ADDR = 0x23C182;
    // Map_AIZ2BossSmall - Small Eggman craft mappings (1 frame)
    public static final int MAP_AIZ2_BOSS_SMALL_ADDR = 0x23C264;
    // ArtNem_AIZBackgroundTree - Nemesis art for parallax trees (15 tiles)
    public static final int ART_NEM_AIZ_BG_TREE_ADDR = 0x38DB46;
    // ArtTile_AIZBackgroundTree - VRAM tile base
    public static final int ART_TILE_AIZ_BG_TREE = 0x0438;
    // Map_AIZ2BGTree - Tree sprite mapping (1 frame, 4 pieces)
    public static final int MAP_AIZ2_BG_TREE_ADDR = 0x23C248;
    // Pal_AIZBattleship - Battleship palette (32 bytes, palette line 2)
    public static final int PAL_AIZ_BATTLESHIP_ADDR = 0x23C05A;
    // Pal_AIZBossSmall - Small boss/bombing palette (28 bytes, palette line 2)
    public static final int PAL_AIZ_BOSS_SMALL_ADDR = 0x23C07A;
    // ArtNem_RobotnikShip - Shared Robotnik ship art (Nemesis)
    public static final int ART_NEM_ROBOTNIK_SHIP_ADDR = 0x0D771E;
    // ArtNem_BossExplosion - Shared boss explosion art (Nemesis)
    public static final int ART_NEM_BOSS_EXPLOSION_ADDR = 0x0D73CE;

    // ===== Signpost (Obj_EndSign) - End of act signpost =====
    // ArtUnc_EndSigns - Uncompressed end-of-act signpost art (3328 bytes)
    public static final int ART_UNC_END_SIGNS_ADDR = 0x0DCC76;
    public static final int ART_UNC_END_SIGNS_SIZE = 3328;
    // ArtNem_SignpostStub - Nemesis-compressed signpost pole/stub art
    public static final int ART_NEM_SIGNPOST_STUB_ADDR = 0x0DD976;
    // Map_EndSigns - Signpost face sprite mappings
    public static final int MAP_END_SIGNS_ADDR = 0x083B9E;
    // DPLC_EndSigns - Signpost DPLC table
    public static final int DPLC_END_SIGNS_ADDR = 0x083B6C;
    // Map_SignpostStub - Signpost pole/stub mappings
    public static final int MAP_SIGNPOST_STUB_ADDR = 0x083BFC;
    // VRAM tile indices for signpost art
    public static final int ART_TILE_END_SIGNS = 0x04AC;
    public static final int ART_TILE_SIGNPOST_STUB = 0x069E;

    // ===== SS Entry Ring (Obj_SSEntryRing) - Special Stage big ring =====
    // ArtUnc_SSEntryRing - Uncompressed art (9984 bytes = 312 tiles)
    public static final int ART_UNC_SS_ENTRY_RING_ADDR = 0x0D8766;
    public static final int ART_UNC_SS_ENTRY_RING_SIZE = 9984;
    // Map_SSEntryRing - 12 mapping frames
    public static final int MAP_SS_ENTRY_RING_ADDR = 0x0619E0;
    // DPLC_SSEntryRing - 12 DPLC frames
    public static final int DPLC_SS_ENTRY_RING_ADDR = 0x061ABE;

    // ===== SS Entry Flash (Obj_SSEntryFlash) - Big ring collection flash effect =====
    // ArtUnc_SSEntryFlash - Uncompressed art (1440 bytes = 45 tiles)
    public static final int ART_UNC_SS_ENTRY_FLASH_ADDR = 0x0DAE66;
    public static final int ART_UNC_SS_ENTRY_FLASH_SIZE = 1440;
    // Map_SSEntryFlash - 4 mapping frames (+ 1 extra embedded frame not in offset table)
    public static final int MAP_SS_ENTRY_FLASH_ADDR = 0x061B28;
    // DPLC_SSEntryFlash - 4 DPLC frames
    public static final int DPLC_SS_ENTRY_FLASH_ADDR = 0x061BFA;
    /** PalSPtr_SSEntry: two-color Run_PalRotationScript entry for line 2 colors 5-6. */
    public static final int PAL_SCRIPT_SS_ENTRY_ADDR = 0x061C28;
    /** PalSPtr_SSEntry2: one-color Run_PalRotationScript entry for line 2 color 15. */
    public static final int PAL_SCRIPT_SS_ENTRY_2_ADDR = 0x061CA4;
    /** loc_61928: immediate pair restored to normal-palette line 2 colors 5-6. */
    public static final int PAL_SS_ENTRY_NORMAL_PAIR_ADDR = 0x061958;
    /** loc_61928: immediate word restored to normal-palette line 2 color 15. */
    public static final int PAL_SS_ENTRY_NORMAL_FINAL_ADDR = 0x061960;

    // ===== Pal_AIZ - Main AIZ palette (for AfterBoss_Cleanup) =====
    public static final int PAL_AIZ_ADDR = 0x0A8B7C;
    public static final int PAL_AIZ_SIZE = 96;

    // Pal_AIZFire - AIZ fire/post-transition palette (96 bytes = 3 lines)
    // AfterBoss_AIZ2 loads first 32 bytes into palette line 1 via PalLoad_Line1
    public static final int PAL_AIZ_FIRE_ADDR = 0x0A8BDC;

    // ===== Data Select / Save Screen =====
    // Addresses verified against the combined lock-on ROM using the save-menu assets from
    // sonic3k.asm and LockOn Data.asm. This task only models the original assets and layout.
    public static final int MAP_ENI_SAVE_SCREEN_LAYOUT_ADDR = 0x3A2020;
    public static final int MAP_UNC_SAVE_SCREEN_NEW_ADDR = 0x3A20DE;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_1_ADDR = 0x3A217A;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_2_ADDR = 0x3A2206;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_3_ADDR = 0x3A2292;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_4_ADDR = 0x3A231E;
    public static final int ART_KOS_SAVE_SCREEN_MISC_ADDR = 0x3A23AA;
    public static final int ART_KOS_SAVE_SCREEN_EXTRA_ADDR = 0x15A774;
    public static final int ART_KOS_SAVE_SCREEN_SK_ZONE_ADDR = 0x15CD62;
    public static final int ART_KOS_SAVE_SCREEN_PORTRAIT_ADDR = 0x15EDB2;
    public static final int ART_KOS_SAVE_SCREEN_S3_ZONE_ADDR = 0x20C7E0;

    public static final int MAP_ENI_S3_MENU_BG_ADDR = 0x39D2A2;
    public static final int ART_KOS_S3_MENU_BG_ADDR = 0x39D4A4;
    public static final int ARTTILE_S3_MENU_BG = 0x0001;
    public static final int ARTTILE_SAVE_MISC = 0x029F;
    public static final int ARTTILE_SAVE_EXTRA = 0x0454;
    public static final int ARTTILE_SAVE_TEXT = 0x0562;
    public static final int ENIGMA_BASE_S3_MENU_BG = ARTTILE_S3_MENU_BG;
    public static final int ENIGMA_BASE_SAVE_SCREEN_LAYOUT = ARTTILE_SAVE_MISC | 0x8000;
    public static final int PAL_SAVE_MENU_BG_ADDR = 0x39D262;
    public static final int PAL_SAVE_CHARS_ADDR = 0x00CA78;
    public static final int PAL_SAVE_EMERALDS_ADDR = 0x00CA9A;
    public static final int PAL_SAVE_FINISH_CARD_1_ADDR = 0x00CAB8;
    public static final int PAL_SAVE_FINISH_CARD_2_ADDR = 0x00CAD8;
    public static final int PAL_SAVE_FINISH_CARD_3_ADDR = 0x00CAF8;
    public static final int PAL_SAVE_ZONE_CARD_BASE_ADDR = 0x00CB18;
    public static final int PAL_SAVE_S3_ZONE_CARD_8_ADDR = 0x20BCB6;

    public static final int MAP_SAVE_SCREEN_GENERAL_ADDR = 0x00CE0E;
    public static final int MAP_SAVE_SCREEN_GENERAL_FRAME_COUNT = 36;
    public static final int MAP_DATA_SELECT_PLAYER_LIVES_CONTINUES_ADDR = 0x00DA8A;
    public static final int OBJ_DAT_SAVE_SCREEN_ADDR = 0x00D13E;
    public static final int OBJ_DAT_SAVE_SCREEN_ENTRY_SIZE = 10;
    public static final int OBJ_DAT_SAVE_SCREEN_OBJECT_COUNT = 12;
    public static final int OBJ_DAT_SAVE_SCREEN_SLOT_COUNT = 8;

    // NEW.bin is followed by a 16-byte pointer table before Static 1 begins.
    public static final int MAP_UNC_SAVE_SCREEN_NEW_SIZE = 0x8C;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_1_SIZE =
            MAP_UNC_SAVE_SCREEN_STATIC_2_ADDR - MAP_UNC_SAVE_SCREEN_STATIC_1_ADDR;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_2_SIZE =
            MAP_UNC_SAVE_SCREEN_STATIC_3_ADDR - MAP_UNC_SAVE_SCREEN_STATIC_2_ADDR;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_3_SIZE =
            MAP_UNC_SAVE_SCREEN_STATIC_4_ADDR - MAP_UNC_SAVE_SCREEN_STATIC_3_ADDR;
    public static final int MAP_UNC_SAVE_SCREEN_STATIC_4_SIZE =
            ART_KOS_SAVE_SCREEN_MISC_ADDR - MAP_UNC_SAVE_SCREEN_STATIC_4_ADDR;

    public static final int PAL_SAVE_MENU_BG_SIZE = 0x20;
    public static final int PAL_SAVE_CHARS_SIZE = PAL_SAVE_EMERALDS_ADDR - PAL_SAVE_CHARS_ADDR;
    public static final int PAL_SAVE_EMERALDS_SIZE = PAL_SAVE_FINISH_CARD_1_ADDR - PAL_SAVE_EMERALDS_ADDR;
    public static final int PAL_SAVE_FINISH_CARD_SIZE = 0x20;
    public static final int PAL_SAVE_FINISH_CARD_COUNT = 3;
    public static final int PAL_SAVE_ZONE_CARD_SIZE = 0x20;
    public static final int PAL_SAVE_ZONE_CARD_COUNT = 15;
    public static final int PAL_SAVE_S3_ZONE_CARD_8_SIZE = 0x20;
    public static final int SAVE_SCREEN_STATIC_LAYOUT_COUNT = 4;

    // ===== Level Select Screen =====
    // Art (Nemesis compressed, reuses S2 menu infrastructure at S3K ROM offsets)
    public static final int ART_NEM_S22P_OPTIONS_ADDR = 0xCA5E0;   // Font art (Nemesis)
    public static final int ART_NEM_S2_MENU_BOX_ADDR = 0x2C3AF2;   // Menu box borders (Nemesis)
    public static final int ART_NEM_S2_LEVEL_SELECT_PICS_ADDR = 0x2C3B72; // Zone preview icons (Nemesis)
    public static final int ART_UNC_SONICMILES_ADDR = 0xAA57C;     // SONICMILES background anim (Uncompressed)
    public static final int ART_UNC_SONICMILES_SIZE = 1280;         // 40 tiles x 32 bytes

    // Mappings (Enigma compressed)
    public static final int MAP_ENI_S2_LEV_SEL_ADDR = 0x20731A;    // Main screen layout
    public static final int MAP_ENI_S22P_OPTIONS_ADDR = 0xCAB54;    // Background layout (Plane B, 2P Options)
    public static final int MAP_ENI_S2_LEV_SEL_ICON_ADDR = 0x20746E; // Icon box mappings

    // Palettes (uncompressed)
    public static final int PAL_S2_MENU_ADDR = 0xA8A7C;            // Menu palette (4 lines, 128B)
    public static final int PAL_S2_MENU_SIZE = 128;
    public static final int PAL_S2_LEVEL_ICONS_ADDR = 0x2070BC;    // 15 icon palettes (480B)
    public static final int PAL_S2_LEVEL_ICONS_SIZE = 480;

    // ===== Gumball Bonus Stage =====
    // Level tile data (same structure as regular zones)
    public static final int GUMBALL_8X8_KOSM_ADDR = 0x3FEE2E;       // Gumball_8x8_KosM (KosinskiM)
    public static final int GUMBALL_16X16_KOS_ADDR = 0x3FEA0E;      // Gumball_16x16_Kos (Kosinski)
    public static final int GUMBALL_128X128_KOS_ADDR = 0x3FFB80;    // Gumball_128x128_Kos (Kosinski)

    // Level layout and collision
    public static final int GUMBALL_LAYOUT_ADDR = 0x28BC2E;         // Layout_Gumball_Special (uncompressed, 184 bytes)
    public static final int GUMBALL_COLLISION_ADDR = 0x280760;      // Solid_Gumball_Special (uncompressed, 3072 bytes)

    // Palette
    public static final int GUMBALL_PALETTE_ADDR = 0x0A9BBC;        // Pal_Gumball_Special (uncompressed, 96 bytes = 3 palette lines)

    // Object and ring positions
    public static final int GUMBALL_SPRITES_ADDR = 0x1FC75E;        // Gumball_Sprites (uncompressed, 192 bytes)
    public static final int GUMBALL_RINGS_ADDR = 0x01835B;          // Gumball_Rings (uncompressed, 6 bytes)

    // Sprite art and mappings
    public static final int GUMBALL_ART_NEM_ADDR = 0x19385A;        // ArtNem_BonusStage (Nemesis, 2862B -> 8576B)
    public static final int GUMBALL_MAP_ADDR = 0x06148A;            // Map_GumballBonus (24 frames, S3K mapping format)
    public static final int ARTTILE_BONUS_STAGE = 0x015B;           // ArtTile_BonusStage (VRAM tile destination)
    public static final int GUMBALL_ANI_TILES_ADDR = 0x2C2180;      // ArtUnc_AniGumball (uncompressed, 256 bytes)

    // ===== Slot Machine Bonus Stage =====
    public static final int ARTTILE_SLOTS_BLOCKS = 0x033B;          // ArtTile_SlotsBlocks
    public static final int ART_UNC_SLOT_OPTIONS_ADDR = 0x158CAE;
    public static final int ART_UNC_SLOT_OPTIONS_SIZE = 0x1000;
    public static final int ANPAL_SLOTS_1_ADDR = 0x003620;
    public static final int ANPAL_SLOTS_1_SIZE = 0x40;
    public static final int ANPAL_SLOTS_2_ADDR = 0x003658;
    public static final int ANPAL_SLOTS_2_SIZE = 0x80;
    public static final int ANPAL_SLOTS_3_ADDR = 0x0036E0;
    public static final int ANPAL_SLOTS_3_SIZE = 0x0C;
    public static final int SLOTS_REWARD_VALUES_ADDR = 0x04C8A4;
    public static final int SLOTS_TARGET_ROWS_ADDR = 0x04C8B4;
    public static final int SLOTS_REEL_SEQUENCE_A_ADDR = 0x04C8CC;
    public static final int SLOTS_REEL_SEQUENCE_B_ADDR = 0x04C8D4;
    public static final int SLOTS_REEL_SEQUENCE_C_ADDR = 0x04C8DC;
    public static final int SLOTS_CAGE_ROUTINE_ADDR = 0x04BF62;
    public static final int SLOTS_BOOTSTRAP_ROUTINE_ADDR = 0x04B6AA;
    public static final int MAP_SLOT_MACHINE_FACE_ADDR = 0x04B794;  // Map_SB_Slot
    public static final int MAP_SLOT_R_AND_PEPPERMINT_ADDR = 0x04B844; // Map_SB_R_and_Peppermint
    public static final int MAP_SLOT_GOAL_ADDR = 0x04B864;          // Map_SB_Goal
    public static final int MAP_SLOT_BUMPER_ADDR = 0x04B87C;        // Map_SB_Bumper
    public static final int MAP_SLOT_RING_STAGE_ADDR = 0x04B894;    // Map_SB_Ring
    public static final int MAP_SLOT_COLORED_WALL_ADDR = 0x04B8D8;  // Map_SB_ColoredWall
    public static final int MAP_SLOT_BONUS_CAGE_ADDR = 0x04C2A0;    // Map_SlotBonusCage
    public static final int MAP_SLOT_SPIKE_REWARD_ADDR = 0x04C3E6;  // Map_SlotSpike

    // ===== Pachinko / Glowing Spheres Bonus Stage =====
    public static final int ARTTILE_PACHINKO_MAIN = 0x02CD;         // ArtTile_PachinkoMain
    public static final int ARTTILE_PACHINKO_GUMBALLS = 0x0388;     // ArtTile_PachinkoGumballs
    public static final int ANIPLC_PACHINKO_ADDR = 0x028C2C;        // AniPLC_Pachinko
    public static final int ART_UNC_ANI_PACHINKO_ADDR = 0x0C8E20;   // ArtUnc_AniPachinko
    public static final int ART_KOS_PACHINKO_BG1_ADDR = 0x156C08;   // ArtKos_PachinkoBG1
    public static final int ART_KOS_PACHINKO_BG1_SIZE = 2272;       // compressed bytes
    public static final int ART_KOS_PACHINKO_BG2_ADDR = 0x1574E8;   // ArtKos_PachinkoBG2
    public static final int ART_KOS_PACHINKO_BG2_SIZE = 1040;       // compressed bytes
    public static final int PAL_KOS_PACHINKO_ADDR = 0x1578F8;       // PalKos_Pachinko
    public static final int PAL_KOS_PACHINKO_SIZE = 496;            // compressed bytes

    // S3K inline mapping data. These addresses are derived from the first frame label
    // in each included mapping asm file minus the 2-byte offset words in the table.
    public static final int MAP_PACHINKO_BUMPER_ADDR = 0x0330A2;    // Map_PachinkoBumper
    public static final int MAP_PACHINKO_TRIANGLE_BUMPER_ADDR = 0x049C0A; // Map_PachinkoTriangleBumper
    public static final int MAP_PACHINKO_FLIPPER_ADDR = 0x049E8C;   // Map_PachinkoFlipper
    public static final int MAP_PACHINKO_ENERGY_TRAP_ADDR = 0x04A08A; // Map_PachinkoEnergyTrap
    public static final int MAP_PACHINKO_INVISIBLE_UNKNOWN_ADDR = 0x04A118; // Map_PachinkoInvisibleUnknown
    public static final int MAP_PACHINKO_PLATFORM_ADDR = 0x04A1D6;  // Map_PachinkoPlatform
    public static final int MAP_PACHINKO_ITEM_ORB_ADDR = 0x04A294;  // Map_PachinkoItemOrb
    public static final int MAP_PACHINKO_F_ITEM_ADDR = 0x04A3D2;    // Map_PachinkoFItem

    // Spring child object mappings (shared, used across all zones)
    public static final int MAP_SPIKES_ADDR = 0x024456;             // Map_Spikes (8 frames)
    public static final int MAP_SPRING_ADDR = 0x02375C;             // Map_Spring (11 frames, S3K mapping format)
    public static final int MAP_SPRING2_ADDR = 0x023772;            // Map_Spring2 (yellow spring frames)

    // ArtNem_VerticalSpring — standalone red vertical spring art used by gumball bonus springs.
    // ROM: s3.asm:118453, 325 compressed bytes -> 512 bytes (8 tiles).
    public static final int ART_NEM_VERTICAL_SPRING_ADDR = 0x35C988;

    // =====================================================================
    // Tails-carry-Sonic intro (CNZ1/MHZ1)
    // ROM refs: sonic3k.asm loc_13A32 (CNZ/MHZ triggers), loc_13FC2/loc_13FFA
    // (carry init + body), sub_1459E (Sonic pickup), Tails_Carry_Sonic
    // (per-frame parentage). All addresses < 0x200000 (S&K-side only).
    // =====================================================================

    /** Zone-and-act word value that triggers the CNZ1 Tails-carry intro. */
    public static final int CARRY_TRIGGER_ZONE_ACT_WORD = 0x0300;

    /** Zone-and-act word value that triggers the MHZ1 Tails-carry intro. */
    public static final int CARRY_TRIGGER_MHZ_ZONE_ACT_WORD = 0x0700;

    /** Tails's spawn X after the CNZ1 trigger. ROM: loc_13A32. */
    public static final int CARRY_INIT_TAILS_X = 0x0018;

    /** Tails's spawn Y after the CNZ1 trigger. ROM: loc_13A32. */
    public static final int CARRY_INIT_TAILS_Y = 0x0600;

    /** Tails's spawn X after the MHZ1 trigger. ROM: loc_13A8E. */
    public static final int CARRY_INIT_MHZ_TAILS_X = 0x00D8;

    /** Tails's spawn Y after the MHZ1 trigger. ROM: loc_13A8E. */
    public static final int CARRY_INIT_MHZ_TAILS_Y = 0x0500;

    /** ROM first INIT tick leaves Tails airborne with standard ObjectMoveAndFall gravity. */
    public static final short CARRY_INIT_PREROLLED_TAILS_Y_VEL = (short) 0x0038;

    /** Constant horizontal flight velocity while carrying. ROM: loc_13FC2 x_vel write. */
    public static final short CARRY_INIT_TAILS_X_VEL = (short) 0x0100;

    /** Sonic hangs this many pixels below Tails's centre. ROM: sub_1459E y_pos + 0x1C. */
    public static final int CARRY_DESCEND_OFFSET_Y = 0x1C;

    /** Level_frame_counter mask that gates synthetic right-press injection.
     *  Every 32 frames: (Level_frame_counter + 1) & 0x1F == 0. ROM: loc_13FFA. */
    public static final int CARRY_INPUT_INJECT_MASK = 0x1F;

    /** Cooldown frames after A/B/C jump release. ROM: Tails_Carry_Sonic line 27241. */
    public static final int CARRY_COOLDOWN_JUMP_RELEASE = 0x12;

    /** Cooldown frames after external-vel latch-mismatch release. ROM: loc_14466. */
    public static final int CARRY_COOLDOWN_LATCH_RELEASE = 0x3C;

    /** Post-A/B/C-release y_vel (jump impulse). ROM: Tails_Carry_Sonic line ~27248. */
    public static final short CARRY_RELEASE_JUMP_Y_VEL = (short) -0x0380;

    /** Post-A/B/C-release x_vel magnitude (sign applied from face direction). */
    public static final short CARRY_RELEASE_JUMP_X_VEL = (short) 0x0200;

    /** Sonic's `anim` byte while carried. ROM: sub_1459E writes 0x2200 word (high byte 0x22). */
    public static final int CARRY_SONIC_ANIM_BYTE = 0x22;

    // =====================================================================
    // S3K Tails CPU flight/catch-up constants
    // sonic3k.asm:26474+ (Tails_Catch_Up_Flying) and 26534+ (Tails_FlySwim_Unknown)
    // =====================================================================

    /** Y offset applied when Tails teleports above Sonic on catch-up entry.
     *  ROM sonic3k.asm:26494 (`subi.w #$C0, d0`). */
    public static final int TAILS_CATCH_UP_Y_OFFSET = 0xC0;

    /** Auto-land timeout for Tails_FlySwim_Unknown; after 5 seconds off-screen
     *  Tails falls back to CATCH_UP_FLIGHT so the teleport re-runs.
     *  ROM sonic3k.asm:26538 (`cmpi.w #5*60, (Tails_CPU_flight_timer).w`). */
    public static final int TAILS_FLIGHT_AUTO_LAND_FRAMES = 5 * 60;

    /** Horizontal steer step clamp for Tails_FlySwim_Unknown: the normalized
     *  |dx| >> 4 is capped at 0xC, producing a max of 12 px/frame X movement.
     *  ROM sonic3k.asm:26576 (`cmpi.w #$C, d2`). */
    public static final int TAILS_FLIGHT_MAX_X_STEP = 0xC;

    /** Vertical steer step for Tails_FlySwim_Unknown: always +/-1 px per frame
     *  toward the target Y.  ROM sonic3k.asm:26612 (`moveq #1, d2`). */
    public static final int TAILS_FLIGHT_Y_STEP = 1;

    /** The "ahead of Sonic" leading offset applied to Sonic's delayed X when
     *  he is not on an object and his ground speed is < 0x400.
     *  ROM sonic3k.asm:26694 (`subi.w #$20, d2`). */
    public static final int TAILS_FLIGHT_LEAD_X_OFFSET = 0x20;

    /** The ground-speed threshold Sonic must exceed for the lead offset to be
     *  suppressed.  ROM sonic3k.asm:26692 (`cmpi.w #$400, ground_vel(a1)`). */
    public static final int TAILS_FLIGHT_LEAD_SUPPRESS_GSPEED = 0x400;

    /** ROM sub_13ECA off-screen marker X for despawned Tails. sonic3k.asm:26806. */
    public static final int TAILS_CPU_DESPAWN_X = 0x7F00;

    private static boolean scanned = false;

    public static boolean isScanned() {
        return scanned;
    }

    public static void setScanned(boolean value) {
        scanned = value;
    }
}
