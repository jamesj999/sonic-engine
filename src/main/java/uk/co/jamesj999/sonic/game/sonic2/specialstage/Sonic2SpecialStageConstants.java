package uk.co.jamesj999.sonic.game.sonic2.specialstage;

/**
 * ROM offsets and constants for Sonic 2 REV01 Special Stage data.
 * All offsets are validated against ROM SHA-256: 193bc4064ce0daf27ea9e908ed246d87ec576cc294833badebb590b6ad8e8f6b
 *
 * This class is specific to Sonic the Hedgehog 2. Other Sonic games have different
 * special stage implementations and data formats.
 */
public final class Sonic2SpecialStageConstants {

    private Sonic2SpecialStageConstants() {}

    // ========== Core stage data (Kosinski/Nemesis compressed) ==========

    /** Object perspective data - Kosinski compressed, 4080 bytes */
    public static final long PERSPECTIVE_DATA_OFFSET = 0x0E24FE;
    public static final int PERSPECTIVE_DATA_SIZE = 4080;

    /** Level layouts - Nemesis compressed, 260 bytes */
    public static final long LEVEL_LAYOUTS_OFFSET = 0x0E34EE;
    public static final int LEVEL_LAYOUTS_SIZE = 260;

    /** Object location lists - Kosinski compressed, 3216 bytes */
    public static final long OBJECT_LOCATIONS_OFFSET = 0x0E35F2;
    public static final int OBJECT_LOCATIONS_SIZE = 3216;

    // ========== Track mapping frames (raw, contiguous) ==========

    /** Start of 56 track mapping BIN frames */
    public static final long TRACK_FRAMES_START = 0x0CA904;
    /** End of track mapping frames (exclusive) */
    public static final long TRACK_FRAMES_END = 0x0DCA38;
    /** Total size of all 56 track frames */
    public static final int TRACK_FRAMES_TOTAL_SIZE = (int)(TRACK_FRAMES_END - TRACK_FRAMES_START);
    /** Number of track mapping frames */
    public static final int TRACK_FRAME_COUNT = 56;

    // ========== Art assets (Kosinski/Nemesis compressed) ==========

    /** Special stage track art - Kosinski compressed */
    public static final long TRACK_ART_OFFSET = 0x0DCA38;
    public static final int TRACK_ART_SIZE = 816;

    /** Ring art - Nemesis compressed */
    public static final long RING_ART_OFFSET = 0x0DDA7E;
    public static final int RING_ART_SIZE = 1318;

    /** Bomb art - Nemesis compressed */
    public static final long BOMB_ART_OFFSET = 0x0DE4BC;
    public static final int BOMB_ART_SIZE = 1008;

    /** Emerald art - Nemesis compressed */
    public static final long EMERALD_ART_OFFSET = 0x0DE8AC;
    public static final int EMERALD_ART_SIZE = 583;

    /** Messages and icons art - Nemesis compressed */
    public static final long MESSAGES_ART_OFFSET = 0x0DEAF4;
    public static final int MESSAGES_ART_SIZE = 953;

    /** Sonic and Tails animation frames - Nemesis compressed */
    public static final long PLAYER_ART_OFFSET = 0x0DEEAE;
    public static final int PLAYER_ART_SIZE = 13775;

    /** Horizontal shadow art - Nemesis compressed */
    public static final long SHADOW_HORIZ_ART_OFFSET = 0x0DDFA4;
    public static final int SHADOW_HORIZ_ART_SIZE = 181;

    /** Diagonal shadow art - Nemesis compressed */
    public static final long SHADOW_DIAG_ART_OFFSET = 0x0DE05A;
    public static final int SHADOW_DIAG_ART_SIZE = 198;

    /** Vertical shadow art - Nemesis compressed */
    public static final long SHADOW_VERT_ART_OFFSET = 0x0DE120;
    public static final int SHADOW_VERT_ART_SIZE = 103;

    /** Background art - Nemesis compressed */
    public static final long BACKGROUND_ART_OFFSET = 0x0DCD68;
    public static final int BACKGROUND_ART_SIZE = 1141;

    // ========== Background mappings (Enigma compressed) ==========

    /** Main background mappings - Enigma compressed */
    public static final long BACKGROUND_MAIN_MAPPINGS_OFFSET = 0x0DD1DE;
    public static final int BACKGROUND_MAIN_MAPPINGS_SIZE = 302;

    /** Lower background mappings - Enigma compressed */
    public static final long BACKGROUND_LOWER_MAPPINGS_OFFSET = 0x0DD30C;
    public static final int BACKGROUND_LOWER_MAPPINGS_SIZE = 382;

    // ========== Small tables (raw bytes) ==========

    /** Skydome horizontal scroll delta table (pointer table + 11 rows of 5 bytes) */
    public static final long SKYDOME_SCROLL_TABLE_OFFSET = 0x006DEE;
    public static final int SKYDOME_SCROLL_TABLE_SIZE = 77;

    /** Animation base duration table (8 bytes) */
    public static final long ANIM_DURATION_TABLE_OFFSET = 0x000B46;
    public static final int ANIM_DURATION_TABLE_SIZE = 8;

    /** Ring requirement table - Team mode (28 bytes: 7 stages x 4 quarters) */
    public static final long RING_REQ_TEAM_OFFSET = 0x007756;
    public static final int RING_REQ_TABLE_SIZE = 28;

    /** Ring requirement table - Solo mode (28 bytes: 7 stages x 4 quarters) */
    public static final long RING_REQ_SOLO_OFFSET = 0x007772;

    // ========== Track frame offsets and sizes ==========

    /** Individual track frame ROM offsets */
    public static final long[] TRACK_FRAME_OFFSETS = {
        0x0CA904, 0x0CADA8, 0x0CB376, 0x0CB92E, 0x0CBF92, 0x0CC5BE, 0x0CCC7A, 0x0CD282,
        0x0CD7C0, 0x0CDD44, 0x0CE2BE, 0x0CE7DE, 0x0CEC52, 0x0CF0BC, 0x0CF580, 0x0CFA00,
        0x0CFE4A, 0x0D028C, 0x0D090A, 0x0D0EA6, 0x0D1400, 0x0D19FC, 0x0D1EAC, 0x0D23AE,
        0x0D27C6, 0x0D2C14, 0x0D3092, 0x0D3522, 0x0D39EC, 0x0D3F78, 0x0D4660, 0x0D4DA6,
        0x0D53FC, 0x0D5958, 0x0D5F02, 0x0D6596, 0x0D6BAA, 0x0D702E, 0x0D749C, 0x0D7912,
        0x0D7DAA, 0x0D8250, 0x0D85F8, 0x0D89EC, 0x0D8E24, 0x0D92B6, 0x0D9778, 0x0D9B80,
        0x0DA016, 0x0DA4CE, 0x0DAB20, 0x0DB086, 0x0DB5AE, 0x0DBB62, 0x0DC154, 0x0DC5E8
    };

    /** Individual track frame sizes in bytes */
    public static final int[] TRACK_FRAME_SIZES = {
        1188, 1486, 1464, 1636, 1580, 1724, 1544, 1342,
        1412, 1402, 1312, 1140, 1130, 1220, 1152, 1098,
        1090, 1662, 1436, 1370, 1532, 1200, 1282, 1048,
        1102, 1150, 1168, 1226, 1420, 1768, 1862, 1622,
        1372, 1450, 1684, 1556, 1156, 1134, 1142, 1176,
        1190, 936, 1012, 1080, 1170, 1218, 1032, 1174,
        1208, 1618, 1382, 1320, 1460, 1522, 1172, 1104
    };

    // ========== Segment animation types ==========

    public static final int SEGMENT_TURN_THEN_RISE = 0;
    public static final int SEGMENT_TURN_THEN_DROP = 1;
    public static final int SEGMENT_TURN_THEN_STRAIGHT = 2;
    public static final int SEGMENT_STRAIGHT = 3;
    public static final int SEGMENT_STRAIGHT_THEN_TURN = 4;

    /** Frame counts per segment animation type */
    public static final int[] SEGMENT_FRAME_COUNTS = { 24, 24, 12, 16, 11 };

    // ========== Track animation sequences ==========

    /** TurnThenRise: turning (7 frames) + rise (17 frames) */
    public static final int[] ANIM_TURN_THEN_RISE = {
        0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x26,
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
    };

    /** TurnThenDrop: turning (7 frames) + drop (17 frames) */
    public static final int[] ANIM_TURN_THEN_DROP = {
        0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x26,
        0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25
    };

    /** TurnThenStraight: turning (7 frames) + exit curve (5 frames) */
    public static final int[] ANIM_TURN_THEN_STRAIGHT = {
        0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x26,
        0x2C, 0x2D, 0x2E, 0x2F, 0x30
    };

    /** Straight: straight path repeated 4x (16 frames) */
    public static final int[] ANIM_STRAIGHT = {
        0x11, 0x12, 0x13, 0x14, 0x11, 0x12, 0x13, 0x14,
        0x11, 0x12, 0x13, 0x14, 0x11, 0x12, 0x13, 0x14
    };

    /** StraightThenTurn: straight (4 frames) + enter curve (7 frames) */
    public static final int[] ANIM_STRAIGHT_THEN_TURN = {
        0x11, 0x12, 0x13, 0x14,
        0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37
    };

    /** All animation sequences indexed by segment type */
    public static final int[][] SEGMENT_ANIMATIONS = {
        ANIM_TURN_THEN_RISE,
        ANIM_TURN_THEN_DROP,
        ANIM_TURN_THEN_STRAIGHT,
        ANIM_STRAIGHT,
        ANIM_STRAIGHT_THEN_TURN
    };

    // ========== Animation base durations ==========

    /** Duration values indexed by speed factor >> 1 */
    public static final int[] ANIM_BASE_DURATIONS = { 60, 30, 15, 10, 8, 6, 5, 0 };

    // ========== Number of special stages ==========

    public static final int SPECIAL_STAGE_COUNT = 7;
}
