package com.openggf.game.sonic1.levelselect;

import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.graphics.PatternAtlasRange;

/**
 * Constants and data tables for the Sonic 1 Level Select screen.
 *
 * <p>Data extracted from the Sonic 1 disassembly (sonic.asm):
 * <ul>
 *   <li>LevSel_Ptrs - Zone/act byte pairs for each menu entry (Rev01)</li>
 *   <li>LevelMenuText - 24-character text strings for each menu line</li>
 *   <li>Character encoding from charset directives</li>
 * </ul>
 *
 * <p>The Sonic 1 level select is a single-column vertical list of 21 items,
 * unlike Sonic 2's two-column layout.
 */
public final class Sonic1LevelSelectConstants {

    /** Pattern base ID for level select art (high ID to avoid conflicts) */
    public static final int PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base();

    /** Screen dimensions */
    public static final int SCREEN_WIDTH = 320;
    public static final int SCREEN_HEIGHT = 224;

    /** Number of entries in the level select menu (0-20 inclusive) */
    public static final int MENU_ENTRY_COUNT = 21;

    /** Special zone/act values */
    public static final int SPECIAL_STAGE_VALUE = 0x0700; // id_SS=7, act=0
    public static final int SOUND_TEST_VALUE = 0x8000;

    /** Hold timer before input repeat starts (in frames) - from LevSelControls delay counter */
    public static final int HOLD_REPEAT_DELAY = 11;

    /** Input repeat rate (in frames) */
    public static final int HOLD_REPEAT_RATE = 4;

    /** Text rendering start position (from textpos = vram_bg + 0x210) */
    public static final int TEXT_START_X = 64;  // column 8 * 8px
    public static final int TEXT_START_Y = 32;  // row 4 * 8px

    /** Characters per text line */
    public static final int CHARS_PER_LINE = 24;

    /** Line spacing in pixels */
    public static final int LINE_SPACING = 8;

    /** Sound test value display position (from vram_bg + 0xC30 = row 24, col 24) */
    public static final int SOUND_TEST_X = 192; // column 24 * 8px
    public static final int SOUND_TEST_Y = 192; // row 24 * 8px (same row as SOUND SELECT)

    /** Sound test value range (0x80-0x9F in original, stored as 0-31 internally) */
    public static final int SOUND_TEST_MIN = 0;
    public static final int SOUND_TEST_MAX = 31;
    public static final int SOUND_TEST_OFFSET = 0x80; // added to internal value for display/playback

    /** Palette indices (matching VDP palette line assignment) */
    public static final int NORMAL_PALETTE_INDEX = 3;    // VDP palette 3 (0xE)
    public static final int HIGHLIGHT_PALETTE_INDEX = 2;  // VDP palette 2 (0xC)

    /**
     * Level order table - maps menu index to zone/act word (Rev01).
     * High byte = zone registry index, Low byte = act number (0-based).
     *
     * <p>Uses {@link Sonic1ZoneConstants} (gameplay progression order) since
     * {@code LevelManager.loadZoneAndAct()} expects registry indices.
     *
     * <pre>
     * From sonic.asm LevSel_Ptrs (Revision!=0):
     *   0-2:   GHZ Acts 1-3
     *   3-5:   MZ Acts 1-3
     *   6-8:   SYZ Acts 1-3
     *   9-11:  LZ Acts 1-3
     *   12-14: SLZ Acts 1-3
     *   15-16: SBZ Acts 1-2
     *   17:    SBZ Act 3 (mapped to LZ Act 4 in ROM)
     *   18:    Final Zone (mapped to SBZ Act 3 in ROM)
     *   19:    Special Stage
     *   20:    Sound Test
     * </pre>
     */
    public static final int[] LEVEL_ORDER = {
            (Sonic1ZoneConstants.ZONE_GHZ << 8) | 0,  // 0  - Green Hill Act 1
            (Sonic1ZoneConstants.ZONE_GHZ << 8) | 1,  // 1  - Green Hill Act 2
            (Sonic1ZoneConstants.ZONE_GHZ << 8) | 2,  // 2  - Green Hill Act 3
            (Sonic1ZoneConstants.ZONE_MZ << 8) | 0,   // 3  - Marble Act 1
            (Sonic1ZoneConstants.ZONE_MZ << 8) | 1,   // 4  - Marble Act 2
            (Sonic1ZoneConstants.ZONE_MZ << 8) | 2,   // 5  - Marble Act 3
            (Sonic1ZoneConstants.ZONE_SYZ << 8) | 0,  // 6  - Spring Yard Act 1
            (Sonic1ZoneConstants.ZONE_SYZ << 8) | 1,  // 7  - Spring Yard Act 2
            (Sonic1ZoneConstants.ZONE_SYZ << 8) | 2,  // 8  - Spring Yard Act 3
            (Sonic1ZoneConstants.ZONE_LZ << 8) | 0,   // 9  - Labyrinth Act 1
            (Sonic1ZoneConstants.ZONE_LZ << 8) | 1,   // 10 - Labyrinth Act 2
            (Sonic1ZoneConstants.ZONE_LZ << 8) | 2,   // 11 - Labyrinth Act 3
            (Sonic1ZoneConstants.ZONE_SLZ << 8) | 0,  // 12 - Star Light Act 1
            (Sonic1ZoneConstants.ZONE_SLZ << 8) | 1,  // 13 - Star Light Act 2
            (Sonic1ZoneConstants.ZONE_SLZ << 8) | 2,  // 14 - Star Light Act 3
            (Sonic1ZoneConstants.ZONE_SBZ << 8) | 0,  // 15 - Scrap Brain Act 1
            (Sonic1ZoneConstants.ZONE_SBZ << 8) | 1,  // 16 - Scrap Brain Act 2
            (Sonic1ZoneConstants.ZONE_SBZ << 8) | 2,  // 17 - Scrap Brain Act 3 (LZ act 3 slot in ROM data)
            (Sonic1ZoneConstants.ZONE_FZ << 8) | 0,   // 18 - Final Zone (SBZ act 2 in ROM data)
            SPECIAL_STAGE_VALUE,                       // 19 - Special Stage
            SOUND_TEST_VALUE                           // 20 - Sound Test
    };

    /**
     * Menu text lines (Rev01 order).
     * Each line is exactly 24 characters, matching the original LevelMenuText data.
     */
    public static final String[] MENU_TEXT = {
            "GREEN HILL ZONE  STAGE 1",  // 0
            "                 STAGE 2",  // 1
            "                 STAGE 3",  // 2
            "MARBLE ZONE      STAGE 1",  // 3
            "                 STAGE 2",  // 4
            "                 STAGE 3",  // 5
            "SPRING YARD ZONE STAGE 1",  // 6
            "                 STAGE 2",  // 7
            "                 STAGE 3",  // 8
            "LABYRINTH ZONE   STAGE 1",  // 9
            "                 STAGE 2",  // 10
            "                 STAGE 3",  // 11
            "STAR LIGHT ZONE  STAGE 1",  // 12
            "                 STAGE 2",  // 13
            "                 STAGE 3",  // 14
            "SCRAP BRAIN ZONE STAGE 1",  // 15
            "                 STAGE 2",  // 16
            "                 STAGE 3",  // 17
            "FINAL ZONE              ",  // 18
            "SPECIAL STAGE           ",  // 19
            "SOUND SELECT            "   // 20
    };

    private Sonic1LevelSelectConstants() {
    }
}
