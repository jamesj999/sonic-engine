package com.openggf.trace;

/**
 * One frame of primary trace data from a BizHawk recording.
 * All values match the physics.csv format: positions and speeds are
 * 16-bit values as stored in 68K RAM.
 *
 * <p>V5 rows use the fixed 42-column symmetric primary/sidekick layout. All
 * primary and sidekick animation and subpixel fields are always present.
 */
public record TraceFrame(
    int frame,
    int input,
    short x,
    short y,
    short xSpeed,
    short ySpeed,
    short gSpeed,
    byte angle,
    boolean air,
    boolean rolling,
    int groundMode,
    // v2 diagnostic fields; TraceBinder compares the subset with engine parity data.
    int xSub,
    int ySub,
    int routine,
    int cameraX,
    int cameraY,
    int rings,
    int statusByte,
    // v2.1: ROM gameplay frame counter (Level_MainLoop counter)
    int gameplayFrameCounter,
    // v2.2: SST slot index of object Sonic is standing on (0 = none, -1 = absent)
    int standOnObj,
    // v3: ROM VBlank counter and lag-frame counter
    int vblankCounter,
    int lagCounter,
    // v7: primary character animation state (-1 when absent in legacy traces)
    int animationId,
    int mappingFrame,
    // v5: optional first-sidekick state (for Sonic 2 this is Tails)
    TraceCharacterState sidekick,
    // v5.1: ROM Life_count ($FFFFFE12 in all three games). -1 when the recording
    // predates the column. Comparison-only -- never hydrated into engine state.
    int lives
) {

    /** Sentinel for "this recording carries no life counter". */
    public static final int LIVES_ABSENT = -1;

    public TraceFrame(
        int frame,
        int input,
        short x,
        short y,
        short xSpeed,
        short ySpeed,
        short gSpeed,
        byte angle,
        boolean air,
        boolean rolling,
        int groundMode,
        int xSub,
        int ySub,
        int routine,
        int cameraX,
        int cameraY,
        int rings,
        int statusByte,
        int gameplayFrameCounter,
        int standOnObj,
        int vblankCounter,
        int lagCounter
    ) {
        this(frame, input, x, y, xSpeed, ySpeed, gSpeed, angle, air, rolling, groundMode,
            xSub, ySub, routine, cameraX, cameraY, rings, statusByte, gameplayFrameCounter,
            standOnObj, vblankCounter, lagCounter, -1, -1, null);
    }

    /** Backward-compatible canonical-arity constructor for recordings without life_count. */
    public TraceFrame(
            int frame, int input, short x, short y,
            short xSpeed, short ySpeed, short gSpeed, byte angle,
            boolean air, boolean rolling, int groundMode,
            int xSub, int ySub, int routine, int cameraX, int cameraY,
            int rings, int statusByte, int gameplayFrameCounter, int standOnObj,
            int vblankCounter, int lagCounter, int animationId, int mappingFrame,
            TraceCharacterState sidekick) {
        this(frame, input, x, y, xSpeed, ySpeed, gSpeed, angle, air, rolling, groundMode,
                xSub, ySub, routine, cameraX, cameraY, rings, statusByte,
                gameplayFrameCounter, standOnObj, vblankCounter, lagCounter,
                animationId, mappingFrame, sidekick, LIVES_ABSENT);
    }

    /** Backward-compatible full constructor for v5/v6 call sites. */
    public TraceFrame(
            int frame, int input, short x, short y,
            short xSpeed, short ySpeed, short gSpeed, byte angle,
            boolean air, boolean rolling, int groundMode,
            int xSub, int ySub, int routine, int cameraX, int cameraY,
            int rings, int statusByte, int gameplayFrameCounter, int standOnObj,
            int vblankCounter, int lagCounter, TraceCharacterState sidekick) {
        this(frame, input, x, y, xSpeed, ySpeed, gSpeed, angle, air, rolling, groundMode,
                xSub, ySub, routine, cameraX, cameraY, rings, statusByte,
                gameplayFrameCounter, standOnObj, vblankCounter, lagCounter,
                -1, -1, sidekick);
    }

    /**
     * Convenience factory for tests: creates a TraceFrame with only the core 11 fields,
     * setting all v2/v2.1/v2.2/v3 diagnostic fields to defaults (-1 = absent).
     */
    public static TraceFrame of(int frame, int input,
            short x, short y, short xSpeed, short ySpeed, short gSpeed,
            byte angle, boolean air, boolean rolling, int groundMode) {
        return new TraceFrame(frame, input, x, y, xSpeed, ySpeed, gSpeed, angle,
            air, rolling, groundMode, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, null);
    }

    /**
     * Minimal factory for execution-model tests.
     */
    public static TraceFrame executionTestFrame(
            int frame, int vblankCounter, int gameplayFrameCounter, int lagCounter) {
        return new TraceFrame(frame, 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
            (byte) 0, false, false, 0, 0, 0, -1, -1, -1, -1, -1,
            gameplayFrameCounter, -1, vblankCounter, lagCounter, -1, -1, null);
    }

    /**
     * Returns this gameplay row with camera/ring diagnostics copied from the
     * supplied VBlank row. The gameplay fields intentionally stay untouched.
     */
    public TraceFrame withVisualDiagnosticsFrom(TraceFrame visualFrame) {
        if (visualFrame == null) {
            return this;
        }
        return new TraceFrame(frame, input, x, y, xSpeed, ySpeed, gSpeed, angle,
            air, rolling, groundMode, xSub, ySub, routine,
            visualFrame.cameraX(), visualFrame.cameraY(), visualFrame.rings(),
            statusByte, gameplayFrameCounter, standOnObj, vblankCounter,
            lagCounter, animationId, mappingFrame, sidekick, lives);
    }

    /**
     * Returns this gameplay row with camera diagnostics copied from the
     * supplied VBlank row. Ring diagnostics intentionally remain row-strict.
     */
    public TraceFrame withCameraDiagnosticsFrom(TraceFrame visualFrame) {
        if (visualFrame == null) {
            return this;
        }
        return new TraceFrame(frame, input, x, y, xSpeed, ySpeed, gSpeed, angle,
            air, rolling, groundMode, xSub, ySub, routine,
            visualFrame.cameraX(), visualFrame.cameraY(), rings,
            statusByte, gameplayFrameCounter, standOnObj, vblankCounter,
            lagCounter, animationId, mappingFrame, sidekick, lives);
    }

    /** V5 level-row column count (recordings without the life_count column). */
    private static final int V5_COLUMNS = 42;

    /**
     * V5 level-row column count for recordings that carry the trailing
     * {@code life_count} column. Both widths are accepted: fixtures committed
     * before the column was added stay parseable, and report {@link #LIVES_ABSENT}.
     */
    private static final int V5_COLUMNS_WITH_LIVES = 43;

    /**
     * Parse one fixed-width v5 level row (all values in hexadecimal).
     */
    public static TraceFrame parseCsvRow(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length != V5_COLUMNS && parts.length != V5_COLUMNS_WITH_LIVES) {
            throw new IllegalArgumentException(
                    "Trace schema 5 requires " + V5_COLUMNS + " or "
                            + V5_COLUMNS_WITH_LIVES + " CSV columns, got "
                            + parts.length + ": " + line);
        }
        return parseV5Row(parts);
    }

    private static TraceFrame parseV5Row(String[] parts) {
        int frame = Integer.parseInt(parts[0].trim(), 16);
        int input = Integer.parseInt(parts[1].trim(), 16);
        int cameraX = Integer.parseInt(parts[2].trim(), 16);
        int cameraY = Integer.parseInt(parts[3].trim(), 16);
        int rings = Integer.parseInt(parts[4].trim(), 16);
        int gameplayFrameCounter = Integer.parseInt(parts[5].trim(), 16);
        int vblankCounter = Integer.parseInt(parts[6].trim(), 16);
        int lagCounter = Integer.parseInt(parts[7].trim(), 16);

        TraceCharacterState primary = TraceCharacterState.parseV7CsvColumns(parts, 8);
        TraceCharacterState sidekick = TraceCharacterState.parseV7CsvColumns(parts, 25);
        int lives = parts.length == V5_COLUMNS_WITH_LIVES
                ? Integer.parseInt(parts[42].trim(), 16)
                : LIVES_ABSENT;

        return new TraceFrame(frame, input,
                primary.x(), primary.y(), primary.xSpeed(), primary.ySpeed(), primary.gSpeed(),
                primary.angle(), primary.air(), primary.rolling(), primary.groundMode(),
                primary.xSub(), primary.ySub(), primary.routine(), cameraX, cameraY, rings,
                primary.statusByte(), gameplayFrameCounter, primary.standOnObj(),
                vblankCounter, lagCounter, primary.animationId(), primary.mappingFrame(),
                sidekick, lives);
    }

    /** Whether this frame has v2 diagnostic data. */
    public boolean hasExtendedData() {
        return routine >= 0;
    }

    /**
     * Returns true if this frame has identical physics state to another frame.
     * Used to detect lag frames (consecutive frames with no state change).
     * Compares all state fields except frame number and input.
     */
    public boolean stateEquals(TraceFrame other) {
        return this.x == other.x && this.y == other.y
            && this.xSpeed == other.xSpeed && this.ySpeed == other.ySpeed
            && this.gSpeed == other.gSpeed && this.angle == other.angle
            && this.air == other.air && this.rolling == other.rolling
            && this.groundMode == other.groundMode
            && characterPhysicsStateEquals(this.sidekick, other.sidekick);
    }

    private static boolean characterPhysicsStateEquals(
            TraceCharacterState left, TraceCharacterState right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.physicsStateEquals(right);
    }

    /**
     * Returns the primary playable character state carried by the core CSV columns.
     * For Sonic 2 traces this is Sonic.
     */
    public TraceCharacterState primaryCharacterState() {
        return new TraceCharacterState(true,
            x, y, xSpeed, ySpeed, gSpeed, angle, air, rolling, groundMode,
            xSub, ySub, routine, statusByte, standOnObj, animationId, mappingFrame);
    }

    /**
     * Format the v2/v2.1/v2.2/v3 diagnostic fields as a compact string for context windows.
     * Returns empty string if no extended data is available.
     */
    public String formatDiagnostics() {
        if (!hasExtendedData()) return "";
        String base = String.format("sub=(%04X,%04X) rtn=%02X cam=(%04X,%04X) rings=%d status=%02X",
            xSub, ySub, routine, cameraX, cameraY, rings, statusByte);
        if (gameplayFrameCounter >= 0) {
            base += String.format(" gfc=%04X", gameplayFrameCounter);
        }
        if (standOnObj >= 0) {
            base += String.format(" onObj=%02X", standOnObj);
        }
        if (vblankCounter >= 0) {
            base += String.format(" vbc=%04X", vblankCounter);
        }
        if (lagCounter >= 0) {
            base += String.format(" lag=%04X", lagCounter);
        }
        if (animationId >= 0 && mappingFrame >= 0) {
            base += String.format(" anim=%02X map=%02X", animationId, mappingFrame);
        }
        if (lives >= 0) {
            base += String.format(" lives=%d", lives);
        }
        if (sidekick != null) {
            base += " " + sidekick.formatDiagnostics("sidekick");
        }
        return base;
    }

    /**
     * Parse a hex string as a signed 16-bit value.
     * Handles both positive ("0380") and negative ("FC00" -> -1024) values.
     */
    private static short parseSignedShortHex(String hex) {
        int value = Integer.parseInt(hex, 16);
        if (value > 0x7FFF) {
            value -= 0x10000;
        }
        return (short) value;
    }
}
