package com.openggf.game.sonic3k.specialstage;

/**
 * One frame of a Sonic 3&K special-stage BizHawk trace recording
 * ({@code trace_profile: "s3k_special_stage"}). Mirrors the
 * {@code physics.csv} column layout emitted by the special-stage recorder:
 *
 * <pre>
 * frame,input,input_p2,lag,anim_frame,x_pos,y_pos,angle,velocity,turning,
 * jumping,fade_timer,spheres_left,ring_count,rings_left,rate,rate_timer,
 * clear_timer,clear_routine,started
 * </pre>
 *
 * <p>{@code frame} is decimal; {@code lag} is {@code 0}/{@code 1};
 * {@code started} is {@code 0}/{@code 1}; every other column is lowercase hex
 * without a {@code 0x} prefix.
 *
 * <p><strong>Column order note:</strong> The table order (field-grouped, not
 * address-ordered) is the canonical sequence. The recorder and parser must both
 * follow it exactly; {@code rate_timer} (0xE43E) appears after {@code rate}
 * (0xE444) in the table order, not address order.
 */
public record S3kSpecialStageTraceFrame(
        int frame, int input, int inputP2, boolean lag,
        int animFrame, int xPos, int yPos, int angle, int velocity, int turning,
        int jumping, int fadeTimer, int spheresLeft, int ringCount, int ringsLeft,
        int rate, int rateTimer, int clearTimer, int clearRoutine, boolean started) {

    private static final int COL_FRAME = 0;
    private static final int COL_INPUT = 1;
    private static final int COL_INPUT_P2 = 2;
    private static final int COL_LAG = 3;
    private static final int COL_ANIM_FRAME = 4;
    private static final int COL_X_POS = 5;
    private static final int COL_Y_POS = 6;
    private static final int COL_ANGLE = 7;
    private static final int COL_VELOCITY = 8;
    private static final int COL_TURNING = 9;
    private static final int COL_JUMPING = 10;
    private static final int COL_FADE_TIMER = 11;
    private static final int COL_SPHERES_LEFT = 12;
    private static final int COL_RING_COUNT = 13;
    private static final int COL_RINGS_LEFT = 14;
    private static final int COL_RATE = 15;
    private static final int COL_RATE_TIMER = 16;
    private static final int COL_CLEAR_TIMER = 17;
    private static final int COL_CLEAR_ROUTINE = 18;
    private static final int COL_STARTED = 19;

    private static final int COLUMN_COUNT = 20;

    /** Parses a single {@code physics.csv} data row (header already skipped). */
    public static S3kSpecialStageTraceFrame parseCsvRow(String row) {
        String[] parts = row.split(",", -1);
        if (parts.length != COLUMN_COUNT) {
            throw new IllegalArgumentException(
                "Expected " + COLUMN_COUNT + " CSV columns, got " + parts.length + ": " + row);
        }

        int frame = Integer.parseInt(parts[COL_FRAME].trim(), 10);
        int input = hex(parts, COL_INPUT);
        int inputP2 = hex(parts, COL_INPUT_P2);
        boolean lag = !parts[COL_LAG].trim().equals("0");
        int animFrame = hex(parts, COL_ANIM_FRAME);
        int xPos = hex(parts, COL_X_POS);
        int yPos = hex(parts, COL_Y_POS);
        int angle = hex(parts, COL_ANGLE);
        int velocity = hex(parts, COL_VELOCITY);
        int turning = hex(parts, COL_TURNING);
        int jumping = hex(parts, COL_JUMPING);
        int fadeTimer = hex(parts, COL_FADE_TIMER);
        int spheresLeft = hex(parts, COL_SPHERES_LEFT);
        int ringCount = hex(parts, COL_RING_COUNT);
        int ringsLeft = hex(parts, COL_RINGS_LEFT);
        int rate = hex(parts, COL_RATE);
        int rateTimer = hex(parts, COL_RATE_TIMER);
        int clearTimer = hex(parts, COL_CLEAR_TIMER);
        int clearRoutine = hex(parts, COL_CLEAR_ROUTINE);
        boolean started = !parts[COL_STARTED].trim().equals("0");

        return new S3kSpecialStageTraceFrame(
            frame, input, inputP2, lag,
            animFrame, xPos, yPos, angle, velocity, turning,
            jumping, fadeTimer, spheresLeft, ringCount, ringsLeft,
            rate, rateTimer, clearTimer, clearRoutine, started);
    }

    private static int hex(String[] parts, int column) {
        return Integer.parseInt(parts[column].trim(), 16);
    }
}
