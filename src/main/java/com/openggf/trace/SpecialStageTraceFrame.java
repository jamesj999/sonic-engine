package com.openggf.trace;

/**
 * One frame of a Sonic 2 special-stage BizHawk trace recording
 * ({@code trace_profile: "s2_special_stage"}). Mirrors the
 * {@code physics.csv} column layout emitted by the special-stage recorder:
 *
 * <pre>
 * frame,input,input_p2,lag,speed_factor,track_anim,track_anim_frame,
 * track_drawing_index,track_orientation,track_duration_timer,current_segment,
 * player_anim_frame_timer,rings_togo_bcd,check_rings_flag,tails_control_counter,
 * swap_positions_flag,sonic_present,sonic_ss_x,sonic_ss_x_sub,sonic_ss_y,
 * sonic_ss_y_sub,sonic_ss_z,sonic_angle,sonic_routine,sonic_routine_secondary,
 * sonic_status,sonic_anim,sonic_anim_frame,sonic_rings_bcd,sonic_hurt_timer,
 * sonic_slide_timer,sonic_flip_timer,tails_present,tails_ss_x,tails_ss_x_sub,
 * tails_ss_y,tails_ss_y_sub,tails_ss_z,tails_angle,tails_routine,
 * tails_routine_secondary,tails_status,tails_anim,tails_anim_frame,
 * tails_rings_bcd,tails_hurt_timer,tails_slide_timer,tails_flip_timer
 * </pre>
 *
 * <p>{@code frame} is decimal; {@code lag} is {@code 0}/{@code 1}; every other
 * column (including the {@code *_present} object-id bytes) is lowercase hex
 * without a {@code 0x} prefix.
 */
public record SpecialStageTraceFrame(
        int frame, int input, int inputP2, boolean lag,
        int speedFactor, int trackAnim, int trackAnimFrame, int trackDrawingIndex,
        int trackOrientation, int trackDurationTimer, int currentSegment,
        int playerAnimFrameTimer, int ringsToGoBcd, int checkRingsFlag,
        int tailsControlCounter, int swapPositionsFlag,
        CharacterState sonic, CharacterState tails) {

    /**
     * Per-character special-stage state: half-pipe track-relative position
     * ({@code ssX}/{@code ssY}, each with a subpixel fraction) plus depth
     * ({@code ssZ}), object routine/status/animation, and ring/hurt/slide/flip
     * timers.
     */
    public record CharacterState(boolean present, int ssX, int ssXSub, int ssY,
            int ssYSub, int ssZ, int angle, int routine, int routineSecondary,
            int status, int anim, int animFrame, int ringsBcd,
            int hurtTimer, int slideTimer, int flipTimer) {

        /**
         * Decodes {@link #ringsBcd} (packed {@code hundreds<<16 | tens<<8 | units}
         * bytes) into a plain binary ring count.
         *
         * <p>Provisional pending Task 4 verification against a real recorded
         * trace: this assumes each byte already holds a plain 0-9 digit value
         * per {@code s2.asm:70771-70789}'s {@code abcd} usage, so the formula
         * is {@code h*100 + t*10 + u} rather than a true packed-BCD unpack.
         */
        public int ringsBinary() {
            return ((ringsBcd >> 16) & 0xFF) * 100
                 + ((ringsBcd >> 8) & 0xFF) * 10
                 + (ringsBcd & 0xFF);
        }
    }

    private static final int COL_FRAME = 0;
    private static final int COL_INPUT = 1;
    private static final int COL_INPUT_P2 = 2;
    private static final int COL_LAG = 3;
    private static final int COL_SPEED_FACTOR = 4;
    private static final int COL_TRACK_ANIM = 5;
    private static final int COL_TRACK_ANIM_FRAME = 6;
    private static final int COL_TRACK_DRAWING_INDEX = 7;
    private static final int COL_TRACK_ORIENTATION = 8;
    private static final int COL_TRACK_DURATION_TIMER = 9;
    private static final int COL_CURRENT_SEGMENT = 10;
    private static final int COL_PLAYER_ANIM_FRAME_TIMER = 11;
    private static final int COL_RINGS_TOGO_BCD = 12;
    private static final int COL_CHECK_RINGS_FLAG = 13;
    private static final int COL_TAILS_CONTROL_COUNTER = 14;
    private static final int COL_SWAP_POSITIONS_FLAG = 15;

    private static final int COL_SONIC_PRESENT = 16;
    private static final int COL_SONIC_SS_X = 17;
    private static final int COL_SONIC_SS_X_SUB = 18;
    private static final int COL_SONIC_SS_Y = 19;
    private static final int COL_SONIC_SS_Y_SUB = 20;
    private static final int COL_SONIC_SS_Z = 21;
    private static final int COL_SONIC_ANGLE = 22;
    private static final int COL_SONIC_ROUTINE = 23;
    private static final int COL_SONIC_ROUTINE_SECONDARY = 24;
    private static final int COL_SONIC_STATUS = 25;
    private static final int COL_SONIC_ANIM = 26;
    private static final int COL_SONIC_ANIM_FRAME = 27;
    private static final int COL_SONIC_RINGS_BCD = 28;
    private static final int COL_SONIC_HURT_TIMER = 29;
    private static final int COL_SONIC_SLIDE_TIMER = 30;
    private static final int COL_SONIC_FLIP_TIMER = 31;

    private static final int COL_TAILS_PRESENT = 32;
    private static final int COL_TAILS_SS_X = 33;
    private static final int COL_TAILS_SS_X_SUB = 34;
    private static final int COL_TAILS_SS_Y = 35;
    private static final int COL_TAILS_SS_Y_SUB = 36;
    private static final int COL_TAILS_SS_Z = 37;
    private static final int COL_TAILS_ANGLE = 38;
    private static final int COL_TAILS_ROUTINE = 39;
    private static final int COL_TAILS_ROUTINE_SECONDARY = 40;
    private static final int COL_TAILS_STATUS = 41;
    private static final int COL_TAILS_ANIM = 42;
    private static final int COL_TAILS_ANIM_FRAME = 43;
    private static final int COL_TAILS_RINGS_BCD = 44;
    private static final int COL_TAILS_HURT_TIMER = 45;
    private static final int COL_TAILS_SLIDE_TIMER = 46;
    private static final int COL_TAILS_FLIP_TIMER = 47;

    private static final int COLUMN_COUNT = 48;

    /** Parses a single {@code physics.csv} data row (header already skipped). */
    public static SpecialStageTraceFrame parseCsvRow(String row) {
        String[] parts = row.split(",", -1);
        if (parts.length != COLUMN_COUNT) {
            throw new IllegalArgumentException(
                "Expected " + COLUMN_COUNT + " CSV columns, got " + parts.length + ": " + row);
        }

        int frame = Integer.parseInt(parts[COL_FRAME].trim(), 10);
        int input = hex(parts, COL_INPUT);
        int inputP2 = hex(parts, COL_INPUT_P2);
        boolean lag = !parts[COL_LAG].trim().equals("0");

        CharacterState sonic = new CharacterState(
            hex(parts, COL_SONIC_PRESENT) != 0,
            hex(parts, COL_SONIC_SS_X),
            hex(parts, COL_SONIC_SS_X_SUB),
            hex(parts, COL_SONIC_SS_Y),
            hex(parts, COL_SONIC_SS_Y_SUB),
            hex(parts, COL_SONIC_SS_Z),
            hex(parts, COL_SONIC_ANGLE),
            hex(parts, COL_SONIC_ROUTINE),
            hex(parts, COL_SONIC_ROUTINE_SECONDARY),
            hex(parts, COL_SONIC_STATUS),
            hex(parts, COL_SONIC_ANIM),
            hex(parts, COL_SONIC_ANIM_FRAME),
            hex(parts, COL_SONIC_RINGS_BCD),
            hex(parts, COL_SONIC_HURT_TIMER),
            hex(parts, COL_SONIC_SLIDE_TIMER),
            hex(parts, COL_SONIC_FLIP_TIMER));

        CharacterState tails = new CharacterState(
            hex(parts, COL_TAILS_PRESENT) != 0,
            hex(parts, COL_TAILS_SS_X),
            hex(parts, COL_TAILS_SS_X_SUB),
            hex(parts, COL_TAILS_SS_Y),
            hex(parts, COL_TAILS_SS_Y_SUB),
            hex(parts, COL_TAILS_SS_Z),
            hex(parts, COL_TAILS_ANGLE),
            hex(parts, COL_TAILS_ROUTINE),
            hex(parts, COL_TAILS_ROUTINE_SECONDARY),
            hex(parts, COL_TAILS_STATUS),
            hex(parts, COL_TAILS_ANIM),
            hex(parts, COL_TAILS_ANIM_FRAME),
            hex(parts, COL_TAILS_RINGS_BCD),
            hex(parts, COL_TAILS_HURT_TIMER),
            hex(parts, COL_TAILS_SLIDE_TIMER),
            hex(parts, COL_TAILS_FLIP_TIMER));

        return new SpecialStageTraceFrame(frame, input, inputP2, lag,
            hex(parts, COL_SPEED_FACTOR), hex(parts, COL_TRACK_ANIM),
            hex(parts, COL_TRACK_ANIM_FRAME), hex(parts, COL_TRACK_DRAWING_INDEX),
            hex(parts, COL_TRACK_ORIENTATION), hex(parts, COL_TRACK_DURATION_TIMER),
            hex(parts, COL_CURRENT_SEGMENT), hex(parts, COL_PLAYER_ANIM_FRAME_TIMER),
            hex(parts, COL_RINGS_TOGO_BCD), hex(parts, COL_CHECK_RINGS_FLAG),
            hex(parts, COL_TAILS_CONTROL_COUNTER), hex(parts, COL_SWAP_POSITIONS_FLAG),
            sonic, tails);
    }

    private static int hex(String[] parts, int column) {
        return Integer.parseInt(parts[column].trim(), 16);
    }
}
