package com.openggf.audio.synth;

/**
 * DAC streaming cadence shared by both FM facades.
 *
 * <p>The ROM's Z80 playback loop delivers one DAC byte every
 * {@code baseCycles + 2 * 13 * (loops - 1)} Z80 cycles (a {@code djnz} delay of
 * {@code rate} iterations around two samples per byte), and the FM chip runs
 * {@code 5 / 14} internal cycles per Z80 cycle. Periods are kept in units of
 * {@code 1/28} internal cycle so the ratio stays integral.
 */
public final class FmDacTiming {
    static final int Z80_DJNZ_TAKEN_CYCLES = 13;
    static final int DAC_SAMPLES_PER_BYTE = 2;
    static final int Z80_DJNZ_ZERO_COUNT = 256;
    static final int FM_CYCLES_PER_Z80_CYCLE_NUMERATOR = 5;
    static final int FM_CYCLES_PER_Z80_CYCLE_DENOMINATOR = 14;
    /** Period units advanced per internal chip cycle. */
    public static final int TICK_UNITS_PER_CYCLE =
            FM_CYCLES_PER_Z80_CYCLE_DENOMINATOR * DAC_SAMPLES_PER_BYTE;
    /** Internal chip cycles per output frame. */
    public static final int CYCLES_PER_FRAME = 24;
    /** Period units advanced per internal output frame. */
    public static final int TICK_UNITS_PER_FRAME = TICK_UNITS_PER_CYCLE * CYCLES_PER_FRAME;

    private FmDacTiming() {
    }

    /** DAC byte period in tick units for a game's base cycles and a sample rate byte. */
    public static int period(int baseCycles, int rate) {
        int loops = (rate & 0xff) == 0 ? Z80_DJNZ_ZERO_COUNT : rate & 0xff;
        int z80CyclesPerByte = baseCycles
                + DAC_SAMPLES_PER_BYTE * Z80_DJNZ_TAKEN_CYCLES * (loops - 1);
        return z80CyclesPerByte * FM_CYCLES_PER_Z80_CYCLE_NUMERATOR;
    }
}
