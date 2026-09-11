package com.openggf.game.sonic2.levelselect;

import com.openggf.graphics.PatternAtlasRange;

/**
 * Constants and data tables for the Sonic 2 Level Select screen.
 *
 * <p>Data extracted from the Sonic 2 disassembly (s2.asm):
 * <ul>
 *   <li>LevelSelect_Order - Zone/act word values for each menu entry</li>
 *   <li>LevSel_IconTable - Icon index for each menu entry</li>
 *   <li>LevelSelect_SwitchTable - Left/right column switching</li>
 *   <li>LevSel_MarkTable - Screen positions for selection highlight</li>
 * </ul>
 */
public final class LevelSelectConstants {

    /** Pattern base ID for level select art (high ID to avoid conflicts) */
    public static final int PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base();

    /** Pattern offset for menu background art (separate from menu box/font/icon art) */
    public static final int MENU_BACK_OFFSET = 0x500;

    /** Screen dimensions */
    public static final int SCREEN_WIDTH = 320;
    public static final int SCREEN_HEIGHT = 224;

    /** Number of entries in the level select menu */
    public static final int MENU_ENTRY_COUNT = 22;

    /** Special zone/act values */
    public static final int SPECIAL_STAGE_VALUE = 0x4000;
    public static final int SOUND_TEST_VALUE = 0xFFFF;

    /** Hold timer before input repeat starts (in frames) */
    public static final int HOLD_REPEAT_DELAY = 11;

    /** Input repeat rate (in frames) */
    public static final int HOLD_REPEAT_RATE = 4;

    // Internal zone IDs (matching Sonic2ZoneRegistry order)
    public static final int ZONE_EHZ = 0;   // Emerald Hill
    public static final int ZONE_CPZ = 1;   // Chemical Plant
    public static final int ZONE_ARZ = 2;   // Aquatic Ruin
    public static final int ZONE_CNZ = 3;   // Casino Night
    public static final int ZONE_HTZ = 4;   // Hill Top
    public static final int ZONE_MCZ = 5;   // Mystic Cave
    public static final int ZONE_OOZ = 6;   // Oil Ocean
    public static final int ZONE_MTZ = 7;   // Metropolis
    public static final int ZONE_SCZ = 8;   // Sky Chase
    public static final int ZONE_WFZ = 9;   // Wing Fortress
    public static final int ZONE_DEZ = 10;  // Death Egg

    /**
     * Level order table - maps menu index to zone/act word.
     * High byte = zone ID, Low byte = act number (0-based).
     *
     * <pre>
     * From s2.asm LevelSelect_Order:
     *   0: EHZ Act 1    (0x0000)
     *   1: EHZ Act 2    (0x0001)
     *   2: CPZ Act 1    (0x0D00)
     *   ...
     *  20: Special Stage (0x4000)
     *  21: Sound Test    (0xFFFF)
     * </pre>
     */
    public static final int[] LEVEL_ORDER = {
            (ZONE_EHZ << 8) | 0,  // 0  - Emerald Hill Act 1
            (ZONE_EHZ << 8) | 1,  // 1  - Emerald Hill Act 2
            (ZONE_CPZ << 8) | 0,  // 2  - Chemical Plant Act 1
            (ZONE_CPZ << 8) | 1,  // 3  - Chemical Plant Act 2
            (ZONE_ARZ << 8) | 0,  // 4  - Aquatic Ruin Act 1
            (ZONE_ARZ << 8) | 1,  // 5  - Aquatic Ruin Act 2
            (ZONE_CNZ << 8) | 0,  // 6  - Casino Night Act 1
            (ZONE_CNZ << 8) | 1,  // 7  - Casino Night Act 2
            (ZONE_HTZ << 8) | 0,  // 8  - Hill Top Act 1
            (ZONE_HTZ << 8) | 1,  // 9  - Hill Top Act 2
            (ZONE_MCZ << 8) | 0,  // 10 - Mystic Cave Act 1
            (ZONE_MCZ << 8) | 1,  // 11 - Mystic Cave Act 2
            (ZONE_OOZ << 8) | 0,  // 12 - Oil Ocean Act 1
            (ZONE_OOZ << 8) | 1,  // 13 - Oil Ocean Act 2
            (ZONE_MTZ << 8) | 0,  // 14 - Metropolis Act 1
            (ZONE_MTZ << 8) | 1,  // 15 - Metropolis Act 2
            (ZONE_MTZ << 8) | 2,  // 16 - Metropolis Act 3
            (ZONE_SCZ << 8) | 0,  // 17 - Sky Chase Act 1
            (ZONE_WFZ << 8) | 0,  // 18 - Wing Fortress Act 1
            (ZONE_DEZ << 8) | 0,  // 19 - Death Egg Act 1
            SPECIAL_STAGE_VALUE,  // 20 - Special Stage
            SOUND_TEST_VALUE      // 21 - Sound Test
    };

    /**
     * Icon table - maps menu index to icon index (0-14).
     * Each icon is a 4x3 tile (32x24 pixel) image.
     *
     * <pre>
     * From s2.asm LevSel_IconTable:
     * Icon indices map to zone preview images:
     *   0: EHZ, 1: MTZ, 2: HTZ, 3: ?, 4: OOZ, 5: MCZ, 6: CNZ,
     *   7: CPZ, 8: ARZ, 9: SCZ, 10: WFZ, 11: DEZ, 12: Special Stage,
     *   13: ?, 14: Sound Test
     * </pre>
     */
    public static final int[] ICON_TABLE = {
            0, 0,    // 0-1   EHZ Act 1-2
            7, 7,    // 2-3   CPZ Act 1-2
            8, 8,    // 4-5   ARZ Act 1-2
            6, 6,    // 6-7   CNZ Act 1-2
            2, 2,    // 8-9   HTZ Act 1-2
            5, 5,    // 10-11 MCZ Act 1-2
            4, 4,    // 12-13 OOZ Act 1-2
            1, 1, 1, // 14-16 MTZ Act 1-3
            9,       // 17    SCZ
            10,      // 18    WFZ
            11,      // 19    DEZ
            12,      // 20    Special Stage
            14       // 21    Sound Test
    };

    /**
     * Switch table - maps menu index to target index for left/right column switching.
     *
     * <p>The level select has two columns:
     * <ul>
     *   <li>Left column: EHZ through OOZ (indices 0-13)</li>
     *   <li>Right column: MTZ through Sound Test (indices 14-21)</li>
     * </ul>
     *
     * <p>When pressing left/right on the right column, jump to left column.
     * When pressing left/right on the left column, jump to right column.
     */
    public static final int[] SWITCH_TABLE = {
            14,  // 0  EHZ1 -> MTZ1
            15,  // 1  EHZ2 -> MTZ2
            17,  // 2  CPZ1 -> SCZ
            17,  // 3  CPZ2 -> SCZ
            18,  // 4  ARZ1 -> WFZ
            18,  // 5  ARZ2 -> WFZ
            19,  // 6  CNZ1 -> DEZ
            19,  // 7  CNZ2 -> DEZ
            20,  // 8  HTZ1 -> Special Stage
            20,  // 9  HTZ2 -> Special Stage
            21,  // 10 MCZ1 -> Sound Test
            21,  // 11 MCZ2 -> Sound Test
            12,  // 12 OOZ1 -> OOZ1 (same row)
            13,  // 13 OOZ2 -> OOZ2 (same row)
            0,   // 14 MTZ1 -> EHZ1
            1,   // 15 MTZ2 -> EHZ2
            1,   // 16 MTZ3 -> EHZ2
            2,   // 17 SCZ  -> CPZ1
            4,   // 18 WFZ  -> ARZ1
            6,   // 19 DEZ  -> CNZ1
            8,   // 20 SS   -> HTZ1
            10   // 21 ST   -> MCZ1
    };

    /**
     * Mark table - screen positions for selection highlight.
     * Each entry has 4 bytes: [line1, col1*2, line2, col2*2]
     *
     * <p>The highlight is drawn by writing yellow text to the plane map.
     * Positions are in plane coordinates (line = row, col*2 = column*2).
     *
     * <p>Single-act zones have line2/col2 set to 0 (only primary marker).
     */
    public static final int[][] MARK_TABLE = {
            {3, 6, 3, 0x24},    // 0  EHZ1
            {3, 6, 4, 0x24},    // 1  EHZ2
            {6, 6, 6, 0x24},    // 2  CPZ1
            {6, 6, 7, 0x24},    // 3  CPZ2
            {9, 6, 9, 0x24},    // 4  ARZ1
            {9, 6, 10, 0x24},   // 5  ARZ2
            {12, 6, 12, 0x24},  // 6  CNZ1
            {12, 6, 13, 0x24},  // 7  CNZ2
            {15, 6, 15, 0x24},  // 8  HTZ1
            {15, 6, 16, 0x24},  // 9  HTZ2
            {18, 6, 18, 0x24},  // 10 MCZ1
            {18, 6, 19, 0x24},  // 11 MCZ2
            {21, 6, 21, 0x24},  // 12 OOZ1
            {21, 6, 22, 0x24},  // 13 OOZ2
            // Right column
            {3, 0x2C, 3, 0x48},   // 14 MTZ1
            {3, 0x2C, 4, 0x48},   // 15 MTZ2
            {3, 0x2C, 5, 0x48},   // 16 MTZ3
            {6, 0x2C, 0, 0},      // 17 SCZ (single act)
            {9, 0x2C, 0, 0},      // 18 WFZ (single act)
            {12, 0x2C, 0, 0},     // 19 DEZ (single act)
            {15, 0x2C, 0, 0},     // 20 Special Stage
            {18, 0x2C, 18, 0x48}  // 21 Sound Test
    };

    /**
     * Zone names for display (match internal zone order 0-10).
     * These are used by the level manager, not directly by level select.
     */
    public static final String[] ZONE_NAMES = {
            "EMERALD HILL",     // 0 - EHZ
            "CHEMICAL PLANT",   // 1 - CPZ
            "AQUATIC RUIN",     // 2 - ARZ
            "CASINO NIGHT",     // 3 - CNZ
            "HILL TOP",         // 4 - HTZ
            "MYSTIC CAVE",      // 5 - MCZ
            "OIL OCEAN",        // 6 - OOZ
            "METROPOLIS",       // 7 - MTZ
            "SKY CHASE",        // 8 - SCZ
            "WING FORTRESS",    // 9 - WFZ
            "DEATH EGG"         // 10 - DEZ
    };

    /**
     * Menu entry names for display.
     */
    public static final String[] MENU_ENTRY_NAMES = {
            "EMERALD HILL 1",
            "EMERALD HILL 2",
            "CHEMICAL PLANT 1",
            "CHEMICAL PLANT 2",
            "AQUATIC RUIN 1",
            "AQUATIC RUIN 2",
            "CASINO NIGHT 1",
            "CASINO NIGHT 2",
            "HILL TOP 1",
            "HILL TOP 2",
            "MYSTIC CAVE 1",
            "MYSTIC CAVE 2",
            "OIL OCEAN 1",
            "OIL OCEAN 2",
            "METROPOLIS 1",
            "METROPOLIS 2",
            "METROPOLIS 3",
            "SKY CHASE",
            "WING FORTRESS",
            "DEATH EGG",
            "SPECIAL STAGE",
            "SOUND TEST"
    };

    private LevelSelectConstants() {
        // Prevent instantiation
    }
}
