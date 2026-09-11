package com.openggf.game;

/**
 * Tracks the global oscillating values used by multiple Sonic 1/2 objects.
 * Ported from OscillateNumInit/OscillateNumDo in the disassembly.
 * <p>
 * Supports per-game initialization: oscillators 0-7 are identical between
 * Sonic 1 and Sonic 2, but oscillators 8-15 differ in initial values,
 * speeds, and amplitudes. Call {@link #resetForSonic1()} or {@link #reset()}
 * (Sonic 2 default) at level load time.
 */
public final class OscillationManager {
    private static final int OSC_COUNT = 16;

    // ---- Sonic 2 defaults (also used as base for shared oscillators 0-7) ----

    // Osc_Data (control + 16 value/delta pairs)
    private static final int S2_INITIAL_CONTROL = 0x007D;
    private static final int[] S2_INITIAL_VALUES = {
            0x0080, 0x0080, 0x0080, 0x0080,
            0x0080, 0x0080, 0x0080, 0x0080,
            0x0080, 0x3848, 0x2080, 0x3080,
            0x5080, 0x7080, 0x0080, 0x4000
    };
    private static final int[] S2_INITIAL_DELTAS = {
            0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x00EE, 0x00B4, 0x010E,
            0x01C2, 0x0276, 0x0000, 0x00FE
    };

    // Osc_Data2 (speed, limit) — Sonic 2
    private static final int[] S2_SPEEDS = {
            2, 2, 2, 2,
            4, 8, 8, 4,
            2, 2, 2, 3,
            5, 7, 2, 2
    };
    private static final int[] S2_LIMITS = {
            0x10, 0x18, 0x20, 0x30,
            0x20, 0x08, 0x40, 0x40,
            0x38, 0x38, 0x20, 0x30,
            0x50, 0x70, 0x40, 0x40
    };

    // ---- Sonic 1 overrides (from docs/s1disasm/_inc/Oscillatory Routines.asm) ----

    // S1 control bitfield: %0000000001111100 = $007C
    private static final int S1_INITIAL_CONTROL = 0x007C;
    private static final int[] S1_INITIAL_VALUES = {
            0x0080, 0x0080, 0x0080, 0x0080,
            0x0080, 0x0080, 0x0080, 0x0080,
            0x0080, 0x50F0, 0x2080, 0x3080,
            0x5080, 0x7080, 0x0080, 0x0080
    };
    private static final int[] S1_INITIAL_DELTAS = {
            0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x011E, 0x00B4, 0x010E,
            0x01C2, 0x0276, 0x0000, 0x0000
    };
    private static final int[] S1_SPEEDS = {
            2, 2, 2, 2,
            4, 8, 8, 4,
            2, 2, 2, 3,
            5, 7, 2, 2
    };
    private static final int[] S1_LIMITS = {
            0x10, 0x18, 0x20, 0x30,
            0x20, 0x08, 0x40, 0x40,
            0x50, 0x50, 0x20, 0x30,
            0x50, 0x70, 0x10, 0x10
    };

    private static final int[] values = new int[OSC_COUNT];
    private static final int[] deltas = new int[OSC_COUNT];
    // Active speed/limit tables (swapped on reset)
    private static int[] activeSpeeds = S2_SPEEDS;
    private static int[] activeLimits = S2_LIMITS;
    private static int control = S2_INITIAL_CONTROL;
    private static int lastFrame = Integer.MIN_VALUE;
    // Number of incoming update() calls to swallow before resuming normal
    // OscillateNumDo ticking. Used by trace replay fixtures: the ROM only
    // runs OscillateNumDo inside LevelLoop (gamemode 0x0C), but a headless
    // fixture loads the level directly in gamemode 0x0C and starts ticking
    // from BK2 frame 0 — 289 frames earlier than the recorder. Skipping
    // that prefix keeps the ROM and engine oscillators phase-aligned.
    private static int suppressedUpdates = 0;

    static {
        reset();
    }

    private OscillationManager() {
    }

    /**
     * Resets oscillation state to Sonic 2 defaults.
     */
    public static void reset() {
        control = S2_INITIAL_CONTROL;
        activeSpeeds = S2_SPEEDS;
        activeLimits = S2_LIMITS;
        for (int i = 0; i < OSC_COUNT; i++) {
            values[i] = S2_INITIAL_VALUES[i] & 0xFFFF;
            deltas[i] = S2_INITIAL_DELTAS[i] & 0xFFFF;
        }
        lastFrame = Integer.MIN_VALUE;
        suppressedUpdates = 0;
    }

    /**
     * Resets oscillation state to Sonic 1 values.
     * <p>
     * Sonic 1 differs from Sonic 2 in oscillators 8-15:
     * <ul>
     *   <li>Osc 8-9: amplitude $50 (vs S2's $38) — used by SLZ circling platforms</li>
     *   <li>Osc 9: initial value $50F0/$011E (vs S2's $3848/$00EE)</li>
     *   <li>Osc 14-15: amplitude $10, initial value/delta $80/$00 (vs S2's $40, $80/$00 and $4000/$00FE)</li>
     * </ul>
     * Reference: docs/s1disasm/_inc/Oscillatory Routines.asm
     */
    public static void resetForSonic1() {
        control = S1_INITIAL_CONTROL;
        activeSpeeds = S1_SPEEDS;
        activeLimits = S1_LIMITS;
        for (int i = 0; i < OSC_COUNT; i++) {
            values[i] = S1_INITIAL_VALUES[i] & 0xFFFF;
            deltas[i] = S1_INITIAL_DELTAS[i] & 0xFFFF;
        }
        lastFrame = Integer.MIN_VALUE;
        suppressedUpdates = 0;
    }

    /**
     * Swallows the next {@code n} calls to {@link #update(int)} without
     * advancing the oscillator values. Calls clamp at zero. Used by trace
     * replay fixtures to skip the ROM's pre-LevelLoop frames (SEGA/title/
     * level-load gamemodes 0x00/0x04/0x8C) that the headless fixture
     * collapses into zero real ticks but where the replay driver still
     * calls {@link com.openggf.level.LevelManager#advanceGlobalOscillation}
     * once per stepped trace frame.
     */
    public static void suppressNextFrames(int n) {
        suppressedUpdates = Math.max(0, n);
    }

    /** Advances the inherited table for the destination act's transition dispatch. */
    public static void advanceForSeamlessTransition() {
        int savedLastFrame = lastFrame;
        int savedSuppressedUpdates = suppressedUpdates;
        suppressedUpdates = 0;
        update(savedLastFrame == Integer.MAX_VALUE ? Integer.MIN_VALUE : savedLastFrame + 1);
        lastFrame = savedLastFrame;
        suppressedUpdates = savedSuppressedUpdates;
    }

    public static void update(int frameCounter) {
        if (frameCounter == lastFrame) {
            return;
        }
        lastFrame = frameCounter;

        if (suppressedUpdates > 0) {
            suppressedUpdates--;
            return;
        }

        for (int i = 0; i < OSC_COUNT; i++) {
            int bit = OSC_COUNT - 1 - i;
            boolean decreasing = (control & (1 << bit)) != 0;
            int speed = activeSpeeds[i];
            int limit = activeLimits[i];

            int value = values[i];
            int delta = deltas[i];

            if (!decreasing) {
                delta = (delta + speed) & 0xFFFF;
                value = (value + delta) & 0xFFFF;
                int highByte = (value >> 8) & 0xFF;
                if (highByte >= limit) {
                    control |= (1 << bit);
                }
            } else {
                delta = (delta - speed) & 0xFFFF;
                value = (value + delta) & 0xFFFF;
                int highByte = (value >> 8) & 0xFF;
                if (highByte < limit) {
                    control &= ~(1 << bit);
                }
            }

            values[i] = value;
            deltas[i] = delta;
        }
    }

    /**
     * Returns the byte at the given offset into Oscillating_Data.
     * Offsets follow the ROM layout: value word then delta word per oscillator.
     */
    public static int getByte(int offset) {
        if (offset < 0 || offset >= OSC_COUNT * 4) {
            return 0;
        }
        int index = offset / 4;
        int within = offset % 4;
        int word = (within < 2) ? values[index] : deltas[index];
        return ((within & 1) == 0) ? ((word >> 8) & 0xFF) : (word & 0xFF);
    }

    /**
     * Returns the word at the given offset into Oscillating_Data.
     * Offsets follow the ROM layout: value word then delta word per oscillator.
     * Used by circular motion platforms (Obj6B types 8-11) to detect delta zero crossings.
     *
     * @param offset byte offset into oscillating data (must be word-aligned: 0, 2, 4, ...)
     * @return the 16-bit word at that offset, sign-extended to int
     */
    public static int getWord(int offset) {
        if (offset < 0 || offset >= OSC_COUNT * 4) {
            return 0;
        }
        int index = offset / 4;
        int within = offset % 4;
        int word = (within < 2) ? values[index] : deltas[index];
        return (short) word; // Sign-extend to int
    }

    /**
     * Returns the full ROM-format {@code Oscillating_table} bytes for
     * diagnostic comparison against trace data. Layout matches ROM
     * sonic3k.constants.asm:853 — control word followed by 16x (value word,
     * delta word). Total 66 bytes ($42).
     *
     * <p>Used by trace replay diagnostics to ROM-verify engine oscillator
     * phase. <strong>Do not use for hydration:</strong> the engine must
     * produce the correct oscillator phase natively.
     */
    public static byte[] snapshotRomFormatBytes() {
        byte[] out = new byte[2 + OSC_COUNT * 4];
        out[0] = (byte) ((control >> 8) & 0xFF);
        out[1] = (byte) (control & 0xFF);
        for (int i = 0; i < OSC_COUNT; i++) {
            out[2 + i * 4] = (byte) ((values[i] >> 8) & 0xFF);
            out[2 + i * 4 + 1] = (byte) (values[i] & 0xFF);
            out[2 + i * 4 + 2] = (byte) ((deltas[i] >> 8) & 0xFF);
            out[2 + i * 4 + 3] = (byte) (deltas[i] & 0xFF);
        }
        return out;
    }

    /** Diagnostic-only access to the control bitfield (S1/S2/S3K shared). */
    public static int controlForTest() {
        return control;
    }

    /** Diagnostic-only access to oscillator value words (post-tick). */
    public static int[] valuesForTest() {
        return values.clone();
    }

    /** Diagnostic-only access to oscillator delta words (post-tick). */
    public static int[] deltasForTest() {
        return deltas.clone();
    }

    /** Captures the current oscillator phase for rewind snapshots. */
    public static OscillationSnapshot snapshot() {
        return new OscillationSnapshot(
                values, deltas, activeSpeeds, activeLimits,
                control, lastFrame, suppressedUpdates);
    }

    /** Restores oscillator phase from a previously captured snapshot. */
    public static void restore(OscillationSnapshot snap) {
        java.util.Objects.requireNonNull(snap, "snap");
        int[] sv = snap.values();
        int[] sd = snap.deltas();
        int[] ss = snap.activeSpeeds();
        int[] sl = snap.activeLimits();
        for (int i = 0; i < OSC_COUNT; i++) {
            values[i] = sv[i] & 0xFFFF;
            deltas[i] = sd[i] & 0xFFFF;
        }
        activeSpeeds = ss;
        activeLimits = sl;
        control = snap.control() & 0xFFFF;
        lastFrame = snap.lastFrame();
        suppressedUpdates = Math.max(0, snap.suppressedUpdates());
    }
}
