package com.openggf.game.sonic3k.levelselect;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.PatternAtlasRange;

/**
 * Constants and data tables for the Sonic 3&amp;K Level Select screen.
 *
 * <p>Data extracted from the S3K disassembly (s3.asm:8504-8920).
 * The S3 standalone level select disabled FBZ/MHZ/SOZ ($5555) and omitted
 * LRZ-DDZ (those zones weren't in S3 alone). This version enables all zones
 * and replaces competition zone entries with the S&amp;K zones.
 */
public final class Sonic3kLevelSelectConstants {

    /** Pattern base ID for level select art (high ID to avoid conflicts) */
    public static final int PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base();

    /** Pattern offset for font art (ArtTile $010 = ArtNem_S22POptions) */
    public static final int FONT_OFFSET = 0x10;

    /** Pattern offset for menu box art (ArtTile $070 = ArtNem_S2MenuBox) */
    public static final int MENU_BOX_OFFSET = 0x70;

    /** Pattern offset for level select pics (ArtTile $090 = ArtNem_S2LevelSelectPics) */
    public static final int LEVEL_SELECT_PICS_OFFSET = 0x90;

    /** Screen dimensions */
    public static final int SCREEN_WIDTH = 320;
    public static final int SCREEN_HEIGHT = 224;

    /** Plane dimensions in tiles */
    public static final int PLANE_WIDTH = 40;
    public static final int PLANE_HEIGHT = 28;

    /** Number of entries in the level select menu */
    public static final int MENU_ENTRY_COUNT = 29;

    /** Index of the sound test entry */
    public static final int SOUND_TEST_INDEX = 28;

    /** Special zone/act values */
    public static final int SPECIAL_STAGE_VALUE = 0x4000;
    public static final int SOUND_TEST_VALUE = 0xFFFF;
    public static final int DISABLED_ENTRY = 0x5555;

    /** Hold timer before input repeat starts (in frames) — from disasm $B */
    public static final int HOLD_REPEAT_DELAY = 0x0B;

    /** Input repeat rate (in frames) */
    public static final int HOLD_REPEAT_RATE = 4;

    /** Highlight palette line index */
    public static final int HIGHLIGHT_PALETTE_INDEX = 3;

    /** Icon palette line index */
    public static final int ICON_PALETTE_INDEX = 3;

    /** Number of tiles in highlight span (from disasm $F-1 via dbf = 15 iterations) */
    public static final int HIGHLIGHT_SPAN_LENGTH = 15;

    /**
     * Level order table — maps menu index to zone/act word.
     * High byte = zone ID, Low byte = act number (0-based).
     *
     * <p>From s3.asm LS_Level_Order (line 8504), with S&amp;K zones enabled
     * and competition zone entries replaced with LRZ-DDZ.
     */
    public static final int[] LEVEL_ORDER = {
            // Left column: 9 zones x 2 acts (indices 0-17)
            (Sonic3kZoneIds.ZONE_AIZ << 8) | 0,  // 0  - Angel Island Act 1
            (Sonic3kZoneIds.ZONE_AIZ << 8) | 1,  // 1  - Angel Island Act 2
            (Sonic3kZoneIds.ZONE_HCZ << 8) | 0,  // 2  - Hydrocity Act 1
            (Sonic3kZoneIds.ZONE_HCZ << 8) | 1,  // 3  - Hydrocity Act 2
            (Sonic3kZoneIds.ZONE_MGZ << 8) | 0,  // 4  - Marble Garden Act 1
            (Sonic3kZoneIds.ZONE_MGZ << 8) | 1,  // 5  - Marble Garden Act 2
            (Sonic3kZoneIds.ZONE_CNZ << 8) | 0,  // 6  - Carnival Night Act 1
            (Sonic3kZoneIds.ZONE_CNZ << 8) | 1,  // 7  - Carnival Night Act 2
            (Sonic3kZoneIds.ZONE_FBZ << 8) | 0,  // 8  - Flying Battery Act 1 (was $5555)
            (Sonic3kZoneIds.ZONE_FBZ << 8) | 1,  // 9  - Flying Battery Act 2 (was $5555)
            (Sonic3kZoneIds.ZONE_ICZ << 8) | 0,  // 10 - IceCap Act 1
            (Sonic3kZoneIds.ZONE_ICZ << 8) | 1,  // 11 - IceCap Act 2
            (Sonic3kZoneIds.ZONE_LBZ << 8) | 0,  // 12 - Launch Base Act 1
            (Sonic3kZoneIds.ZONE_LBZ << 8) | 1,  // 13 - Launch Base Act 2
            (Sonic3kZoneIds.ZONE_MHZ << 8) | 0,  // 14 - Mushroom Hill Act 1 (was $5555)
            (Sonic3kZoneIds.ZONE_MHZ << 8) | 1,  // 15 - Mushroom Hill Act 2 (was $5555)
            (Sonic3kZoneIds.ZONE_SOZ << 8) | 0,  // 16 - Sandopolis Act 1 (was $5555)
            (Sonic3kZoneIds.ZONE_SOZ << 8) | 1,  // 17 - Sandopolis Act 2 (was $5555)
            // Right column (indices 18-28): S&K zones + special + sound test
            (Sonic3kZoneIds.ZONE_LRZ << 8) | 0,  // 18 - Lava Reef Act 1 (was ALZ)
            (Sonic3kZoneIds.ZONE_LRZ << 8) | 1,  // 19 - Lava Reef Act 2 (was BPZ)
            (Sonic3kZoneIds.ZONE_SSZ << 8) | 0,  // 20 - Sky Sanctuary Act 1 (was DPZ)
            (Sonic3kZoneIds.ZONE_SSZ << 8) | 1,  // 21 - Sky Sanctuary Act 2 (was CGZ)
            (Sonic3kZoneIds.ZONE_DEZ << 8) | 0,  // 22 - Death Egg Act 1 (was EMZ)
            (Sonic3kZoneIds.ZONE_DEZ << 8) | 1,  // 23 - Death Egg Act 2 (was 2P VS)
            (Sonic3kZoneIds.ZONE_DDZ << 8) | 0,  // 24 - Doomsday (was disabled Bonus)
            DISABLED_ENTRY,                        // 25 - (disabled)
            SPECIAL_STAGE_VALUE,                   // 26 - Special Stage
            DISABLED_ENTRY,                        // 27 - (disabled)
            SOUND_TEST_VALUE                       // 28 - Sound Test
    };

    /**
     * Zone name text for the level select screen.
     * Written programmatically to the plane map at load time.
     *
     * <p>9 entries for the left column + 6 for the right column = 15 total.
     * From s3.asm LevelSelectText (line 9045), with right column replaced
     * for S&amp;K zones.
     */
    public static final String[] ZONE_TEXT = {
            // Left column (9 zones)
            "ANGEL ISLAND",
            "HYDROCITY",
            "MARBLE GARDEN",
            "CARNIVAL NIGHT",
            "FLYING BATTERY",
            "ICECAP",
            "LAUNCH BASE",
            "MUSHROOM HILL",
            "SANDOPOLIS",
            // Right column (6 entries)
            "LAVA REEF",
            "SKY SANCTUARY",
            "DEATH EGG",
            "DOOMSDAY",
            "SPECIAL STAGE",
            "SOUND TEST  *"
    };

    /** Number of zones in the left column */
    public static final int LEFT_COLUMN_ZONE_COUNT = 9;

    /**
     * Icon table — maps menu index to icon index (0-14).
     * Each icon is a 4x3 tile (32x24 pixel) zone preview image.
     *
     * <p>From s3.asm LevSel_IconTable (line 8872), with S&amp;K zones
     * reusing existing icon indices.
     */
    public static final int[] ICON_TABLE = {
            0, 0,    // 0-1   AIZ Act 1-2
            7, 7,    // 2-3   HCZ Act 1-2
            8, 8,    // 4-5   MGZ Act 1-2
            6, 6,    // 6-7   CNZ Act 1-2
            2, 2,    // 8-9   FBZ Act 1-2
            5, 5,    // 10-11 ICZ Act 1-2
            4, 4,    // 12-13 LBZ Act 1-2
            1, 1,    // 14-15 MHZ Act 1-2
            9, 9,    // 16-17 SOZ Act 1-2
            // Right column
            0xA, 0xA, // 18-19 LRZ Act 1-2
            3, 3,      // 20-21 SSZ Act 1-2
            0xB, 0xB,  // 22-23 DEZ Act 1-2
            0xB, 0xB,  // 24-25 DDZ / (disabled)
            0xC, 0xC,  // 26-27 Special Stage / (disabled)
            0xE         // 28    Sound Test
    };

    /**
     * Switch table — maps menu index to target index for left/right column switching.
     *
     * <p>From s3.asm LevelSelect_SwitchTable (line 8670), extended for 29 entries.
     * Left column items switch to corresponding right column items and vice versa.
     */
    public static final int[] SWITCH_TABLE = {
            // Left column (0-17) → right column
            18,  // 0  AIZ1 → LRZ1
            19,  // 1  AIZ2 → LRZ2
            20,  // 2  HCZ1 → SSZ1
            21,  // 3  HCZ2 → SSZ2
            22,  // 4  MGZ1 → DEZ1
            23,  // 5  MGZ2 → DEZ2
            24,  // 6  CNZ1 → DDZ
            24,  // 7  CNZ2 → DDZ
            26,  // 8  FBZ1 → Special Stage
            26,  // 9  FBZ2 → Special Stage
            28,  // 10 ICZ1 → Sound Test
            28,  // 11 ICZ2 → Sound Test
            28,  // 12 LBZ1 → Sound Test
            28,  // 13 LBZ2 → Sound Test
            28,  // 14 MHZ1 → Sound Test
            28,  // 15 MHZ2 → Sound Test
            28,  // 16 SOZ1 → Sound Test
            28,  // 17 SOZ2 → Sound Test
            // Right column (18-28) → left column
            0,   // 18 LRZ1 → AIZ1
            1,   // 19 LRZ2 → AIZ2
            2,   // 20 SSZ1 → HCZ1
            3,   // 21 SSZ2 → HCZ2
            4,   // 22 DEZ1 → MGZ1
            5,   // 23 DEZ2 → MGZ2
            6,   // 24 DDZ  → CNZ1
            6,   // 25 (disabled) → CNZ1
            8,   // 26 Special Stage → FBZ1
            8,   // 27 (disabled) → FBZ1
            10   // 28 Sound Test → ICZ1
    };

    /**
     * Mark table — screen positions for selection highlight.
     * Each entry has 4 values: {line1, col1*2, line2, col2*2}.
     *
     * <p>From s3.asm LevSel_MarkTable (line 8888).
     * The highlight is drawn by re-rendering tiles with the highlight palette line.
     * Primary mark: zone name span (14 tiles from line1,col1).
     * Secondary mark: act number (single tile at line2,col2). 0,0 = no secondary.
     */
    public static final int[][] MARK_TABLE = {
            // Left column (indices 0-17): col1=6, col2=$24
            {1, 6, 1, 0x24},     // 0  AIZ1
            {1, 6, 2, 0x24},     // 1  AIZ2
            {4, 6, 4, 0x24},     // 2  HCZ1
            {4, 6, 5, 0x24},     // 3  HCZ2
            {7, 6, 7, 0x24},     // 4  MGZ1
            {7, 6, 8, 0x24},     // 5  MGZ2
            {0xA, 6, 0xA, 0x24}, // 6  CNZ1
            {0xA, 6, 0xB, 0x24}, // 7  CNZ2
            {0xD, 6, 0xD, 0x24}, // 8  FBZ1
            {0xD, 6, 0xE, 0x24}, // 9  FBZ2
            {0x10, 6, 0x10, 0x24}, // 10 ICZ1
            {0x10, 6, 0x11, 0x24}, // 11 ICZ2
            {0x13, 6, 0x13, 0x24}, // 12 LBZ1
            {0x13, 6, 0x14, 0x24}, // 13 LBZ2
            {0x16, 6, 0x16, 0x24}, // 14 MHZ1
            {0x16, 6, 0x17, 0x24}, // 15 MHZ2
            {0x19, 6, 0x19, 0x24}, // 16 SOZ1
            {0x19, 6, 0x1A, 0x24}, // 17 SOZ2
            // Right column (indices 18-28): col1=$2C, col2=$4A
            {1, 0x2C, 1, 0x4A},   // 18 LRZ1
            {1, 0x2C, 2, 0x4A},   // 19 LRZ2
            {4, 0x2C, 4, 0x4A},   // 20 SSZ1
            {4, 0x2C, 5, 0x4A},   // 21 SSZ2
            {7, 0x2C, 7, 0x4A},   // 22 DEZ1
            {7, 0x2C, 8, 0x4A},   // 23 DEZ2
            {0xA, 0x2C, 0, 0},    // 24 DDZ (single act, no secondary)
            {0xA, 0x2C, 0, 0},    // 25 (disabled)
            {0xD, 0x2C, 0, 0},    // 26 Special Stage
            {0xD, 0x2C, 0, 0},    // 27 (disabled)
            {0x10, 0x2C, 0x10, 0x4A} // 28 Sound Test
    };

    /**
     * Mapping offsets — byte offsets into the 40x28 plane map for each zone name.
     * Computed as {@code row * 80 + col * 2} (40 tiles/row * 2 bytes/tile).
     *
     * <p>From s3.asm LevSel_MappingOffsets (line 8920).
     * 9 entries for left column (col 3) + 7 entries for right column (col 22).
     * Note: only 15 text entries are written (the 16th slot is unused).
     */
    public static final int[] MAPPING_OFFSETS = {
            // Left column: col=3, rows 1,4,7,10,13,16,19,22,25
            1 * 80 + 3 * 2,   // row 1, col 3
            4 * 80 + 3 * 2,   // row 4, col 3
            7 * 80 + 3 * 2,   // row 7, col 3
            10 * 80 + 3 * 2,  // row 10, col 3
            13 * 80 + 3 * 2,  // row 13, col 3
            16 * 80 + 3 * 2,  // row 16, col 3
            19 * 80 + 3 * 2,  // row 19, col 3
            22 * 80 + 3 * 2,  // row 22, col 3
            25 * 80 + 3 * 2,  // row 25, col 3
            // Right column: col=22, rows 1,4,7,10,13,16,19
            1 * 80 + 22 * 2,  // row 1, col 22
            4 * 80 + 22 * 2,  // row 4, col 22
            7 * 80 + 22 * 2,  // row 7, col 22
            10 * 80 + 22 * 2, // row 10, col 22
            13 * 80 + 22 * 2, // row 13, col 22
            16 * 80 + 22 * 2, // row 16, col 22
            19 * 80 + 22 * 2  // row 19, col 22 (unused 16th slot)
    };

    /**
     * Total characters written for each zone name (name + padding).
     * From disasm: strlen + (15 - strlen) = 15 total characters before act number.
     * (levselstr stores strlen-1; writeletter runs strlen; blanks run $D-(strlen-1)+1 = 15-strlen)
     */
    public static final int ZONE_NAME_TOTAL_CHARS = 15;

    /**
     * Converts a character to its LEVELSELECT codepage tile index.
     *
     * <p>From s3.asm codepage LEVELSELECT directive:
     * <ul>
     *   <li>Space → 0</li>
     *   <li>'0'-'9' → 16-25</li>
     *   <li>'*' → 26</li>
     *   <li>'@' → 27</li>
     *   <li>':' → 28</li>
     *   <li>'.' → 29</li>
     *   <li>'A'-'Z' → 30-55</li>
     * </ul>
     *
     * @param c character to convert
     * @return tile index, or 0 for space/unknown
     */
    public static int charToTile(char c) {
        if (c >= '0' && c <= '9') return 16 + (c - '0');
        if (c >= 'A' && c <= 'Z') return 30 + (c - 'A');
        if (c >= 'a' && c <= 'z') return 30 + (c - 'a');
        if (c == '*') return 26;
        if (c == '@') return 27;
        if (c == ':') return 28;
        if (c == '.') return 29;
        return 0; // space or unknown
    }

    /**
     * Gets the font character tile index for use with drawText.
     * Returns the index relative to the font pattern offset.
     *
     * <p>For hex digit rendering (sound test), digits 0-9 map to indices 0-9
     * and letters A-F map to indices 14-19 (matching the font layout).
     */
    public static int getHexDigitTileIndex(int digit) {
        if (digit < 10) {
            return digit; // 0-9 at font indices 0-9
        }
        return 0x0E + (digit - 10); // A-F at font indices 14-19
    }

    private Sonic3kLevelSelectConstants() {
        // Prevent instantiation
    }
}
