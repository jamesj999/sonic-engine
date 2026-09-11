package com.openggf.game.sonic1.specialstage;

/**
 * One frame of a Sonic 1 special-stage BizHawk trace recording
 * ({@code trace_profile: "s1_special_stage"}). Mirrors the
 * {@code physics.csv} column layout emitted by the special-stage recorder:
 *
 * <pre>
 * frame,input,lag,x_pos,y_pos,vel_x,vel_y,inertia,status,ss_angle,ss_rotate,
 * bg_anim,rings,emeralds
 * </pre>
 *
 * <p>{@code frame} is decimal; {@code lag} is {@code 0}/{@code 1}; every
 * other column is lowercase hex without a {@code 0x} prefix. {@code x_pos}
 * and {@code y_pos} are the ROM's 32-bit ({@code 16.16}) player-position
 * values, so they are parsed and stored as {@code long} to preserve values
 * above {@code 0x7FFFFFFF}.
 */
public record Sonic1SpecialStageTraceFrame(
        int frame, int input, boolean lag, long xPos, long yPos,
        int velX, int velY, int inertia, int status, int ssAngle, int ssRotate,
        int bgAnim, int rings, int emeralds) {

    private static final int COL_FRAME = 0;
    private static final int COL_INPUT = 1;
    private static final int COL_LAG = 2;
    private static final int COL_X_POS = 3;
    private static final int COL_Y_POS = 4;
    private static final int COL_VEL_X = 5;
    private static final int COL_VEL_Y = 6;
    private static final int COL_INERTIA = 7;
    private static final int COL_STATUS = 8;
    private static final int COL_SS_ANGLE = 9;
    private static final int COL_SS_ROTATE = 10;
    private static final int COL_BG_ANIM = 11;
    private static final int COL_RINGS = 12;
    private static final int COL_EMERALDS = 13;

    private static final int COLUMN_COUNT = 14;

    /** Parses a single {@code physics.csv} data row (header already skipped). */
    public static Sonic1SpecialStageTraceFrame parseCsvRow(String row) {
        String[] parts = row.split(",", -1);
        if (parts.length != COLUMN_COUNT) {
            throw new IllegalArgumentException(
                "Expected " + COLUMN_COUNT + " CSV columns, got " + parts.length + ": " + row);
        }

        int frame = Integer.parseInt(parts[COL_FRAME].trim(), 10);
        int input = hex(parts, COL_INPUT);
        boolean lag = !parts[COL_LAG].trim().equals("0");
        long xPos = Long.parseLong(parts[COL_X_POS].trim(), 16);
        long yPos = Long.parseLong(parts[COL_Y_POS].trim(), 16);
        int velX = hex(parts, COL_VEL_X);
        int velY = hex(parts, COL_VEL_Y);
        int inertia = hex(parts, COL_INERTIA);
        int status = hex(parts, COL_STATUS);
        int ssAngle = hex(parts, COL_SS_ANGLE);
        int ssRotate = hex(parts, COL_SS_ROTATE);
        int bgAnim = hex(parts, COL_BG_ANIM);
        int rings = hex(parts, COL_RINGS);
        int emeralds = hex(parts, COL_EMERALDS);

        return new Sonic1SpecialStageTraceFrame(
            frame, input, lag, xPos, yPos,
            velX, velY, inertia, status, ssAngle, ssRotate,
            bgAnim, rings, emeralds);
    }

    private static int hex(String[] parts, int column) {
        return Integer.parseInt(parts[column].trim(), 16);
    }
}
