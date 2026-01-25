package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

import java.util.Arrays;

/**
 * YM2612 Emulator
 * <p>
 * Ported from SMPSPlay's libvgm/GPGX YM2612 core (ym2612.c).
 */
public class Ym2612Chip {
    private static final double CLOCK = 7670453.0;
    private static final double OUTPUT_RATE = 44100.0;
    // GPGX: Internal rate is CLOCK/144 (~53267 Hz)
    private static final double INTERNAL_RATE = CLOCK / 144.0; // 53267.034...
    // Resampling ratio for converting internal to output rate
    private static final double RESAMPLE_RATIO = INTERNAL_RATE / OUTPUT_RATE; // ~1.208

    // Constants from ym2612.c
    private static final int SIN_HBITS = 10;
    private static final int SIN_LBITS = 16; // 26 - SIN_HBITS
    // Genesis-Plus-GX SIN_BITS for feedback calculation
    private static final int SIN_BITS = 10;
    private static final int ENV_BITS = 10;
    private static final int ENV_HBITS = 10;
    // Legacy envelope counter fractional bits (kept for compatibility)
    private static final int ENV_LBITS = 16;
    // GPGX: 128-step inverted triangle LFO instead of 1024-step sine
    private static final int LFO_HBITS = 7; // 128 = 2^7

    private static final int SIN_LEN = 1 << SIN_HBITS; // 1024
    private static final int ENV_LEN = 1 << ENV_HBITS; // 1024
    private static final int LFO_LEN = 1 << LFO_HBITS; // 128 (GPGX-style)

    private static final int TL_RES_LEN = 256;
    private static final int TL_TAB_LEN = 13 * 2 * TL_RES_LEN;

    private static final int SIN_MASK = SIN_LEN - 1;
    private static final int LFO_MASK = LFO_LEN - 1;

    // Phase generator detune overflow (GPGX: ym2612.c:166-169)
    // Implements frequency phase overflow verified by Nemesis on real hardware
    private static final int DT_BITS = 17;
    private static final int DT_LEN = 1 << DT_BITS;
    private static final int DT_MASK = DT_LEN - 1;

    // envelope step in dB (GPGX: 128.0 / ENV_LEN)
    private static final double ENV_STEP = 128.0 / (1 << ENV_BITS);

    private static final int ENV_ATTACK = 0;
    private static final int ENV_DECAY = ENV_LEN << ENV_LBITS;
    private static final int ENV_END = (ENV_LEN * 2) << ENV_LBITS;
    private static final int MAX_ATT_INDEX = ENV_LEN - 1;
    private static final int SSG_THRESHOLD = 0x200;

    // Output bits logic
    private static final int OUT_BITS = 14;
    private static final int OUT_SHIFT = 0;
    // GPGX-style output clipping: ±8191 (asymmetric: +8191 / -8192)
    // Max pre-clip output is ~16383; peaks above 8191 will clip.
    private static final int LIMIT_CH_OUT_POS = 8191;
    private static final int LIMIT_CH_OUT_NEG = -8192;

    // GPGX ENV_QUIET threshold: when envelope exceeds this, operator output is
    // forced to 0.
    // This causes feedback buffer to naturally decay when notes fade out.
    private static final int ENV_QUIET = TL_TAB_LEN >> 3;

    // Rate constants
    private static final int AR_RATE = 399128;
    private static final int DR_RATE = 5514396;

    // LFO constants

    // Tables
    private static final int[] SIN_TAB = new int[SIN_LEN]; // indices into TL_TAB (includes sign bit)
    private static final int[] TL_TAB = new int[TL_TAB_LEN]; // signed 14-bit output values
    private static final int[] ENV_TAB = new int[2 * ENV_LEN + 8];
    private static final int[] AR_TAB = new int[128];
    private static final int[] DR_TAB = new int[128];
    private static final int[] SL_TAB = new int[16];
    private static final int[][] DT_TAB = new int[8][32];

    // GPGX: number of samples per LFO step (at internal rate)
    private static final int[] LFO_SAMPLES_PER_STEP = { 108, 77, 71, 67, 62, 44, 8, 5 };

    // GPGX EG (Envelope Generator) tables for 3-sample stepping
    // EG_INC: Envelope increment table - 19 rows of 8 values each
    // Indexed by eg_rate_select[rate] + ((egCnt >> eg_rate_shift[rate]) & 7)
    // Copied directly from GPGX ym2612.c
    private static final int[] EG_INC = {
            // Row 0: rates 00..11 with (rate&3)==0 - increment by 0 or 1
            0, 1, 0, 1, 0, 1, 0, 1,
            // Row 1: rates 00..11 with (rate&3)==1
            0, 1, 0, 1, 1, 1, 0, 1,
            // Row 2: rates 00..11 with (rate&3)==2
            0, 1, 1, 1, 0, 1, 1, 1,
            // Row 3: rates 00..11 with (rate&3)==3
            0, 1, 1, 1, 1, 1, 1, 1,
            // Row 4: rate 12 with (rate&3)==0 - increment by 1
            1, 1, 1, 1, 1, 1, 1, 1,
            // Row 5: rate 12 with (rate&3)==1
            1, 1, 1, 2, 1, 1, 1, 2,
            // Row 6: rate 12 with (rate&3)==2
            1, 2, 1, 2, 1, 2, 1, 2,
            // Row 7: rate 12 with (rate&3)==3
            1, 2, 2, 2, 1, 2, 2, 2,
            // Row 8: rate 13 with (rate&3)==0 - increment by 2
            2, 2, 2, 2, 2, 2, 2, 2,
            // Row 9: rate 13 with (rate&3)==1
            2, 2, 2, 4, 2, 2, 2, 4,
            // Row 10: rate 13 with (rate&3)==2
            2, 4, 2, 4, 2, 4, 2, 4,
            // Row 11: rate 13 with (rate&3)==3
            2, 4, 4, 4, 2, 4, 4, 4,
            // Row 12: rate 14 with (rate&3)==0 - increment by 4
            4, 4, 4, 4, 4, 4, 4, 4,
            // Row 13: rate 14 with (rate&3)==1
            4, 4, 4, 8, 4, 4, 4, 8,
            // Row 14: rate 14 with (rate&3)==2
            4, 8, 4, 8, 4, 8, 4, 8,
            // Row 15: rate 14 with (rate&3)==3
            4, 8, 8, 8, 4, 8, 8, 8,
            // Row 16: rate 15 (all) - increment by 8
            8, 8, 8, 8, 8, 8, 8, 8,
            // Row 17: rate 15 for attack (16x increment) - NOT USED in our implementation
            16, 16, 16, 16, 16, 16, 16, 16,
            // Row 18: infinity/zero increment (dummy rates 0-1)
            0, 0, 0, 0, 0, 0, 0, 0
    };

    // GPGX EG rate tables (32 dummy + 64 rates + 32 dummy), copied from ym2612.c
    private static final int[] EG_RATE_SELECT = {
            144, 144, 144, 144, 144, 144, 144, 144,
            144, 144, 144, 144, 144, 144, 144, 144,
            144, 144, 144, 144, 144, 144, 144, 144,
            144, 144, 144, 144, 144, 144, 144, 144,
            144, 144, 16, 24, 0, 8, 16, 24,
            0, 8, 16, 24, 0, 8, 16, 24,
            0, 8, 16, 24, 0, 8, 16, 24,
            0, 8, 16, 24, 0, 8, 16, 24,
            0, 8, 16, 24, 0, 8, 16, 24,
            0, 8, 16, 24, 0, 8, 16, 24,
            32, 40, 48, 56, 64, 72, 80, 88,
            96, 104, 112, 120, 128, 128, 128, 128,
            128, 128, 128, 128, 128, 128, 128, 128,
            128, 128, 128, 128, 128, 128, 128, 128,
            128, 128, 128, 128, 128, 128, 128, 128,
            128, 128, 128, 128, 128, 128, 128, 128
    };
    private static final int[] EG_RATE_SHIFT = {
            11, 11, 11, 11, 11, 11, 11, 11,
            11, 11, 11, 11, 11, 11, 11, 11,
            11, 11, 11, 11, 11, 11, 11, 11,
            11, 11, 11, 11, 11, 11, 11, 11,
            11, 11, 11, 11, 10, 10, 10, 10,
            9, 9, 9, 9, 8, 8, 8, 8,
            7, 7, 7, 7, 6, 6, 6, 6,
            5, 5, 5, 5, 4, 4, 4, 4,
            3, 3, 3, 3, 2, 2, 2, 2,
            1, 1, 1, 1, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
    };

    // Reference tables from ym2612.c
    private static final int[] DT_DEF_TAB = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2,
            2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 8, 8,
            1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5,
            5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13, 14, 16, 16, 16, 16,
            2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7,
            8, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 20, 22, 22, 22, 22
    };

    private static final int[] FKEY_TAB = {
            0, 0, 0, 0,
            0, 0, 0, 1,
            2, 3, 3, 3,
            3, 3, 3, 3
    };

    private static final int[] LFO_AMS_DEPTH_SHIFT = { 8, 3, 1, 0 };

    private static final int[][] LFO_PM_OUTPUT = {
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 1, 1, 1, 1 },

            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 1, 1, 1, 1 },
            { 0, 0, 1, 1, 2, 2, 2, 3 },

            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 1 },
            { 0, 0, 0, 0, 1, 1, 1, 1 },
            { 0, 0, 1, 1, 2, 2, 2, 3 },
            { 0, 0, 2, 3, 4, 4, 5, 6 },

            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 1, 1 },
            { 0, 0, 0, 0, 1, 1, 1, 1 },
            { 0, 0, 0, 1, 1, 1, 1, 2 },
            { 0, 0, 1, 1, 2, 2, 2, 3 },
            { 0, 0, 2, 3, 4, 4, 5, 6 },
            { 0, 0, 4, 6, 8, 8, 10, 12 },

            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 1, 1, 1, 1 },
            { 0, 0, 0, 1, 1, 1, 2, 2 },
            { 0, 0, 1, 1, 2, 2, 3, 3 },
            { 0, 0, 1, 2, 2, 2, 3, 4 },
            { 0, 0, 2, 3, 4, 4, 5, 6 },
            { 0, 0, 4, 6, 8, 8, 10, 12 },
            { 0, 0, 8, 12, 16, 16, 20, 24 },

            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 2, 2, 2, 2 },
            { 0, 0, 0, 2, 2, 2, 4, 4 },
            { 0, 0, 2, 2, 4, 4, 6, 6 },
            { 0, 0, 2, 4, 4, 4, 6, 8 },
            { 0, 0, 4, 6, 8, 8, 10, 12 },
            { 0, 0, 8, 12, 16, 16, 20, 24 },
            { 0, 0, 16, 24, 32, 32, 40, 48 },

            { 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 4, 4, 4, 4 },
            { 0, 0, 0, 4, 4, 4, 8, 8 },
            { 0, 0, 4, 4, 8, 8, 12, 12 },
            { 0, 0, 4, 8, 8, 8, 12, 16 },
            { 0, 0, 8, 12, 16, 16, 20, 24 },
            { 0, 0, 16, 24, 32, 32, 40, 48 },
            { 0, 0, 32, 48, 64, 64, 80, 96 },
    };

    private static final int[] LFO_PM_TABLE = new int[128 * 8 * 32];

    private static final int YM2612_DISCRETE = 0;
    private static final int YM2612_INTEGRATED = 1;
    private static final int YM2612_ENHANCED = 2;

    private static final int EG_RATE_ZERO = 18 * 8;

    // GPGX: When running at internal rate, frequency multiplier is 1.0
    private static final double YM2612_FREQUENCY = 1.0;
    // Operator slot order matches ym2612.c (S0,S1,S2,S3) mapping to ops[0,2,1,3]
    private static final int[] OP_TO_SLOT = { 0, 2, 1, 3 };
    // Inverse mapping: slot -> op index (S0,S1,S2,S3 -> ops[0,2,1,3])
    private static final int[] SLOT_TO_OP = { 0, 2, 1, 3 };

    // Debug tracing: set to true to log key on/off envelope state.
    private static final boolean TRACE_KEY_EVENTS = false;
    private static final int TRACE_CHANNEL = 4; // -1 = all channels, otherwise 0..5
    private static final int TRACE_EVENT_LIMIT = 64;
    // Instrument write tracing removed; use external tools for debugging.

    static {
        // TL_TAB generation (GPGX linear power table)
        for (int x = 0; x < TL_RES_LEN; x++) {
            double m = (1 << 16) / StrictMath.pow(2.0, (x + 1) * (ENV_STEP / 4.0) / 8.0);
            m = StrictMath.floor(m);
            int n = (int) m;
            n >>= 4;
            if ((n & 1) != 0)
                n = (n >> 1) + 1;
            else
                n >>= 1;
            n <<= 2;

            TL_TAB[x * 2] = n;
            TL_TAB[x * 2 + 1] = -n;

            for (int i = 1; i < 13; i++) {
                int idx = x * 2 + i * 2 * TL_RES_LEN;
                TL_TAB[idx] = TL_TAB[x * 2] >> i;
                TL_TAB[idx + 1] = -TL_TAB[idx];
            }
        }

        // SIN_TAB generation (GPGX logarithmic sine table)
        for (int i = 0; i < SIN_LEN; i++) {
            double m = StrictMath.sin(((i * 2) + 1) * StrictMath.PI / SIN_LEN);
            double o = 8.0 * StrictMath.log(1.0 / StrictMath.abs(m)) / StrictMath.log(2.0);
            o = o / (ENV_STEP / 4.0);
            int n = (int) (2.0 * o);
            if ((n & 1) != 0)
                n = (n >> 1) + 1;
            else
                n >>= 1;
            SIN_TAB[i] = n * 2 + (m >= 0.0 ? 0 : 1);
        }

        // Envelope Table
        for (int i = 0; i < ENV_LEN; i++) {
            // Attack curve (x^8)
            double x = StrictMath.pow(((double) (ENV_LEN - 1 - i) / ENV_LEN), 8.0);
            x *= ENV_LEN;
            ENV_TAB[i] = (int) x;

            // Decay curve (x^1)
            x = StrictMath.pow(((double) i / ENV_LEN), 1.0);
            x *= ENV_LEN;
            ENV_TAB[ENV_LEN + i] = (int) x;
        }
        ENV_TAB[ENV_END >> ENV_LBITS] = ENV_LEN - 1;

        // Sustain Level Table
        for (int i = 0; i < 15; i++) {
            double x = i * 3.0;
            x /= ENV_STEP;
            int j = (int) x;
            j <<= ENV_LBITS;
            SL_TAB[i] = j + ENV_DECAY;
        }
        int j = ENV_LEN - 1;
        j <<= ENV_LBITS;
        SL_TAB[15] = j + ENV_DECAY;

        // AR/DR Tables
        for (int i = 0; i < 60; i++) {
            double x = YM2612_FREQUENCY;
            x *= 1.0 + ((i & 3) * 0.25);
            x *= (double) (1 << (i >> 2));
            x *= (double) (ENV_LEN << ENV_LBITS);
            AR_TAB[i + 4] = (int) (x / AR_RATE);
            DR_TAB[i + 4] = (int) (x / DR_RATE);
        }
        for (int i = 64; i < 128; i++) {
            AR_TAB[i] = AR_TAB[63];
            DR_TAB[i] = DR_TAB[63];
        }
        for (int i = 0; i < 4; i++) {
            AR_TAB[i] = 0;
            DR_TAB[i] = 0;
        }

        // Detune Table - GPGX-style: use raw values without scaling
        // These values are added directly to the 17-bit fc before the DT_MASK is
        // applied
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 32; k++) {
                DT_TAB[i][k] = DT_DEF_TAB[(i << 5) + k];
                DT_TAB[i + 4][k] = -DT_TAB[i][k];
            }
        }

        // LFO PM table generation (GPGX)
        for (int depth = 0; depth < 8; depth++) {
            for (int fnum = 0; fnum < 128; fnum++) {
                for (int step = 0; step < 8; step++) {
                    int value = 0;
                    for (int bit = 0; bit < 7; bit++) {
                        if ((fnum & (1 << bit)) != 0) {
                            value += LFO_PM_OUTPUT[bit * 8 + depth][step];
                        }
                    }
                    int base = (fnum * 32 * 8) + (depth * 32);
                    LFO_PM_TABLE[base + step] = value;
                    LFO_PM_TABLE[base + (step ^ 7) + 8] = value;
                    LFO_PM_TABLE[base + step + 16] = -value;
                    LFO_PM_TABLE[base + (step ^ 7) + 24] = -value;
                }
            }
        }

    }

    private DacData dacData;
    private int currentDacSampleId = -1;
    private int dacLatchedValue;
    private double dacPos;
    private double dacStep = 1.0;
    private boolean dacEnabled;
    private boolean dacHasLatched;
    // DAC timing from s2.sounddriver.asm:314,727-728:
    // "295 cycles for two samples. dpcmLoopCounter should use 295 divided by 2."
    // Base overhead = 295 cycles per 2 samples, djnz loops add 13*2=26 cycles per
    // rateByte
    private static final double DAC_BASE_CYCLES = 295.0;
    private static final double DAC_LOOP_CYCLES = 26.0;
    private static final double DAC_LOOP_SAMPLES = 2.0;
    private static final double Z80_CLOCK = 3579545.0;
    private static final double DAC_GAIN = 64.0;
    private boolean dacInterpolate = true;
    private boolean dacHighpassEnabled = false;
    private int dac_highpass;
    private static final int HIGHPASS_FRACT = 15;
    private static final int HIGHPASS_SHIFT = 9;

    // Resampling state (internal 53kHz -> output 44.1kHz)
    private double resampleAccum = 0.0;
    private int lastLeft = 0, lastRight = 0;
    private int prevLeft = 0, prevRight = 0;
    private final int[] channelOut = new int[6];

    // Band-limited resampler (replaces simple linear interpolation)
    private final BlipResampler blipResampler = new BlipResampler(INTERNAL_RATE, OUTPUT_RATE);
    private boolean useBlipResampler = false;  // Disabled for testing - set true to enable band-limited resampling

    private int status;
    private int mode;
    private int csmKeyFlag;
    private int addressLatch;
    private int chipType = YM2612_DISCRETE;
    private double busyCycles;
    private final int[][] opMask = new int[8][4];
    private static final int FM_STATUS_BUSY_BIT_MASK = 0x80;
    private static final int FM_STATUS_TIMERA_BIT_MASK = 0x01;
    private static final int FM_STATUS_TIMERB_BIT_MASK = 0x02;
    private static final int BUSY_CYCLES_DATA = 47;
    private static final double YM_CYCLES_PER_SAMPLE = (CLOCK / 6.0) / INTERNAL_RATE;

    private int timerACount;
    private int timerBCount;
    private int timerALoad;
    private int timerBLoad;
    private int timerAPeriod;
    private int timerBPeriod;

    private int lfoCnt;
    private int lfoTimer;
    private int lfoTimerOverflow;
    private int lfoAm = 126;
    private int lfoPm;

    // GPGX EG counter: 12-bit, cycles 1-4095, skips 0
    // EG only advances every 3 samples (frequency = chipclock/144/3)
    private int egCnt = 1;
    private int egTimer = 0; // Counts 0, 1, 2, then triggers egCnt increment

    private boolean channel3SpecialMode;

    private enum EnvState {
        ATTACK, DECAY1, DECAY2, RELEASE, IDLE
    }

    private static class Operator {
        int dt1;
        int mul; // GPGX: 1 for reg=0, or reg*2 for reg=1-15
        int tl;
        int tll;
        int rs, ar;
        int am, d1r;
        int d2r;
        int d1l, rr;
        int ssgEg;
        int ssgn;
        int ksr;

        int fCnt;
        int fInc;
        int eCnt;
        int eInc;
        int eCmp;

        // GPGX EG rate cache - precomputed shift/select for each envelope phase
        int egShAr, egSelAr; // Attack rate shift/select
        int egShD1r, egSelD1r; // Decay1 rate shift/select
        int egShD2r, egSelD2r; // Decay2/Sustain rate shift/select
        int egShRr, egSelRr; // Release rate shift/select
        int volume; // GPGX-style volume (0 = max, 1023 = silent)
        int volOut; // GPGX-style cached vol_out = volume + tll
        int slReg; // Raw 4-bit sustain level register value (0-15)
        EnvState curEnv = EnvState.RELEASE;

        int eIncA, eIncD, eIncS, eIncR;
        boolean ssgEnabled;

        int chgEnM = 0;
        int amMask;

        // GPGX-style key flag: tracks whether key is currently pressed,
        // separate from envelope state. This fixes edge cases where
        // envelope state alone doesn't properly gate key-on/key-off.
        boolean key = false;
    }

    private static class Channel {
        int fNum;
        int block;
        int kCode;

        final int[] slotFnum = new int[4];
        final int[] slotBlock = new int[4];
        final int[] slotKCode = new int[4];
        int fc; // GPGX: base frequency = (fNum << block) >> 1
        final int[] slotFc = new int[4]; // CH3 special mode per-slot fc
        int blockFnum; // GPGX: (block << 11) | fNum for LFO PM
        final int[] slotBlockFnum = new int[4]; // CH3 special mode

        int feedback;
        int algo;
        int ams, pms;
        int pan;
        int leftMask = 0;
        int rightMask = 0;

        final int[] opOut = new int[4];
        int memValue;
        int out;

        final Operator[] ops = new Operator[4];

        Channel() {
            for (int i = 0; i < 4; i++) {
                ops[i] = new Operator();
                ops[i].eCnt = ENV_END;
                ops[i].curEnv = EnvState.RELEASE;
            }
        }
    }

    private final Channel[] channels = new Channel[6];
    private final boolean[] mutes = new boolean[6];

    private int in0, in1, in2, in3;
    private int en0, en1, en2, en3;
    private int traceEvents;

    public Ym2612Chip() {
        for (int i = 0; i < 6; i++) {
            channels[i] = new Channel();
        }
        reset();
        setChipType(YM2612_DISCRETE);
    }

    public void reset() {
        status = 0;
        mode = 0;
        csmKeyFlag = 0;
        addressLatch = 0;
        busyCycles = 0;
        channel3SpecialMode = false;

        timerACount = 0;
        timerBCount = 0;
        timerALoad = 1024;
        timerBLoad = 256 << 4;
        timerAPeriod = 0;
        timerBPeriod = 0;

        lfoCnt = 0;
        lfoTimer = 0;
        lfoTimerOverflow = 0;
        lfoAm = 126;
        lfoPm = 0;

        // Reset GPGX EG counter and timer
        egCnt = 1;
        egTimer = 0;

        dacEnabled = false;
        dac_highpass = 0;

        // Reset resampling state
        resampleAccum = 0.0;
        lastLeft = lastRight = 0;
        prevLeft = prevRight = 0;
        blipResampler.reset();

        for (Channel ch : channels) {
            ch.fNum = 0;
            ch.block = 0;
            ch.kCode = 0;
            ch.feedback = 31; // fb=0 means no feedback (large shift effectively disables it)
            ch.algo = 0;
            ch.ams = LFO_AMS_DEPTH_SHIFT[0];
            ch.pms = 0;
            ch.leftMask = 0xFFFFFFFF;
            ch.rightMask = 0xFFFFFFFF;
            ch.memValue = 0;

            for (int i = 0; i < 4; i++) {
                ch.opOut[i] = 0;
                Operator o = ch.ops[i];
                o.dt1 = 0;
                o.mul = 1; // GPGX: mul=1 when reg=0
                o.tl = 0;
                o.tll = 0;
                o.ksr = 0;
                o.ar = 0;
                o.am = 0;
                o.d1r = 0;
                o.d2r = 0;
                o.d1l = 0;
                o.rr = 0;
                o.ssgEg = 0;
                o.ssgn = 0;
                o.fCnt = 0;
                o.eCnt = ENV_END;
                o.eInc = 0;
                o.eCmp = 0;
                o.curEnv = EnvState.RELEASE;
                o.chgEnM = 0;
                o.key = false;
                // GPGX EG state
                o.volume = 1023; // Start silent
                o.volOut = 1023 + o.tll; // Cache vol_out
                o.slReg = 0; // Sustain level register
                o.egShAr = o.egSelAr = 0;
                o.egShD1r = o.egSelD1r = 0;
                o.egShD2r = o.egSelD2r = 0;
                o.egShRr = o.egSelRr = 0;
                o.amMask = 0;
            }
        }
    }

    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 6)
            mutes[ch] = mute;
    }

    /**
     * Silence all FM channels (ROM: zFMSilenceAll).
     * Key-off all channels, then write 0xFF to registers 0x30-0x8F on both ports.
     */
    public void silenceAll() {
        // Key-off all 6 channels (reg 0x28, value = channel with no operator keys)
        for (int ch = 0; ch < 3; ch++) {
            write(0, 0x28, ch); // Part I channels 0-2
            write(0, 0x28, ch | 0x04); // Part II channels 3-5
        }
        // Write 0xFF to registers 0x30-0x8F on both ports to kill all operator params
        for (int reg = 0x30; reg < 0x90; reg++) {
            write(0, reg, 0xFF);
            write(1, reg, 0xFF);
        }
        // Stop DAC playback
        stopDac();
    }

    /**
     * Force-silence a channel by directly resetting envelope and feedback state.
     * This is used when SFX steals a channel from music to prevent artifacts
     * caused by state persisting across notes.
     * <p>
     * Unlike register writes, this takes effect immediately without needing
     * audio samples to be rendered.
     * <p>
     * The feedback buffer (opOut) is reset to ensure multi-channel SFX
     * (like the Signpost which uses FM4+FM5) start with identical state.
     * Without this reset, residual feedback from different music channels
     * causes the SFX channels to have different waveforms, creating
     * a phaser/chorus effect.
     */
    public void forceSilenceChannel(int chIdx) {
        if (chIdx < 0 || chIdx >= 6)
            return;
        Channel ch = channels[chIdx];

        // Reset feedback buffer to ensure clean, identical state for multi-channel SFX
        ch.opOut[0] = 0;
        ch.opOut[1] = 0;
        ch.memValue = 0;

        for (int op = 0; op < 4; op++) {
            Operator sl = ch.ops[op];
            // Fully reset envelope to silent state
            sl.eCnt = ENV_END;
            sl.eInc = 0;
            sl.eCmp = ENV_END + 1;
            sl.curEnv = EnvState.IDLE;
            sl.chgEnM = 0xFFFFFFFF; // Allow next keyOn to proceed
            sl.key = false; // Reset key flag for GPGX-style key handling
            sl.volume = 1023; // GPGX: silent
            sl.volOut = 1023 + sl.tll; // Update cache
            sl.ssgn = 0;
        }
    }

    public void setDacInterpolate(boolean interpolate) {
        this.dacInterpolate = interpolate;
    }

    public void setDacHighpassEnabled(boolean enabled) {
        this.dacHighpassEnabled = enabled;
    }

    public void setDacData(DacData data) {
        this.dacData = data;
    }

    public int readStatus() {
        if (busyCycles > 0)
            status |= FM_STATUS_BUSY_BIT_MASK;
        else
            status &= ~FM_STATUS_BUSY_BIT_MASK;
        return status;
    }

    public void write(int port, int reg, int val) {
        int resolvedPort = port & 1;
        int resolvedReg = reg & 0x1FF;
        if ((resolvedReg & 0x100) != 0) {
            resolvedPort = 1;
            resolvedReg &= 0xFF;
        }
        writeAddress(resolvedPort, resolvedReg);
        writeData(resolvedPort, val);
    }

    public void writeAddress(int port, int reg) {
        addressLatch = (reg & 0xFF) | ((port & 1) != 0 ? 0x100 : 0);
    }

    public void writeData(int port, int val) {
        busyCycles = BUSY_CYCLES_DATA;
        int addr = addressLatch;

        if (addr < 0x30) {
            writeYm(addr, val);
        } else {
            if ((addr & 0xFF) < 0xA0) {
                writeSlot(addr, val);
            } else {
                writeChannel(addr, val);
            }
        }
    }

    public void setChipType(int type) {
        chipType = type;
        resetOpMask();
        if (chipType < YM2612_ENHANCED) {
            // GPGX op_mask is defined in slot order (S0,S1,S2,S3). Map to ops[] order.
            setOpMaskSlot(0, 3);
            setOpMaskSlot(1, 3);
            setOpMaskSlot(2, 3);
            setOpMaskSlot(3, 3);
            setOpMaskSlot(4, 1);
            setOpMaskSlot(4, 3);
            setOpMaskSlot(5, 1);
            setOpMaskSlot(5, 2);
            setOpMaskSlot(5, 3);
            setOpMaskSlot(6, 1);
            setOpMaskSlot(6, 2);
            setOpMaskSlot(6, 3);
            setOpMaskSlot(7, 0);
            setOpMaskSlot(7, 1);
            setOpMaskSlot(7, 2);
            setOpMaskSlot(7, 3);
        }
    }

    private void resetOpMask() {
        for (int algo = 0; algo < 8; algo++) {
            for (int op = 0; op < 4; op++) {
                opMask[algo][op] = 0xFFFFFFFF;
            }
        }
    }

    private void setOpMaskSlot(int algo, int slot) {
        int op = SLOT_TO_OP[slot & 3];
        opMask[algo][op] = -32;
    }

    private void writeYm(int addr, int val) {
        switch (addr) {
            case 0x22:
                if ((val & 0x08) != 0) {
                    lfoTimerOverflow = LFO_SAMPLES_PER_STEP[val & 7];
                } else {
                    lfoTimerOverflow = 0;
                    lfoTimer = 0;
                    lfoAm = 126;
                    lfoPm = 0;
                }
                break;
            case 0x24:
                timerAPeriod = (timerAPeriod & 0x03) | (val << 2);
                timerALoad = 1024 - timerAPeriod;
                break;
            case 0x25:
                timerAPeriod = (timerAPeriod & 0x3FC) | (val & 0x03);
                timerALoad = 1024 - timerAPeriod;
                break;
            case 0x26:
                timerBPeriod = val & 0xFF;
                timerBLoad = (256 - timerBPeriod) << 4;
                break;
            case 0x27:
                setTimers(val);
                break;
            case 0x28:
                int chIdx = val & 0x03;
                if (chIdx == 3)
                    return;
                if ((val & 0x04) != 0)
                    chIdx += 3;
                Channel ch = channels[chIdx];
                int mask = (val >> 4) & 0xF;
                if ((mask & 1) != 0)
                    keyOn(ch, 0);
                else
                    keyOff(ch, 0);
                if ((mask & 2) != 0)
                    keyOn(ch, 2);
                else
                    keyOff(ch, 2);
                if ((mask & 4) != 0)
                    keyOn(ch, 1);
                else
                    keyOff(ch, 1);
                if ((mask & 8) != 0)
                    keyOn(ch, 3);
                else
                    keyOff(ch, 3);
                break;
            case 0x2A:
                dacLatchedValue = (val & 0xFF) - 128;
                dacHasLatched = true;
                currentDacSampleId = -1;
                break;
            case 0x2B:
                dacEnabled = (val & 0x80) != 0;
                if (!dacEnabled)
                    stopDac();
                break;
        }
    }

    private void setTimers(int val) {
        if (((mode ^ val) & 0xC0) != 0) {
            channels[2].ops[0].fInc = -1;
            if (((val & 0xC0) != 0x80) && csmKeyFlag != 0) {
                csmKeyOff();
            }
        }

        if ((val & 0x01) != 0 && (mode & 0x01) == 0) {
            timerACount = timerALoad;
        }
        if ((val & 0x02) != 0 && (mode & 0x02) == 0) {
            timerBCount = timerBLoad;
        }

        status &= (~val >> 4);

        mode = val;
        channel3SpecialMode = (val & 0x40) != 0;
    }

    private void writeSlot(int addr, int val) {
        int nch = addr & 3;
        if (nch == 3)
            return;
        if ((addr & 0x100) != 0)
            nch += 3;

        int[] regToOp = { 0, 2, 1, 3 };
        int regSlot = (addr >> 2) & 3;
        int opIdx = regToOp[regSlot];

        Channel ch = channels[nch];
        Operator sl = ch.ops[opIdx];

        int ar = (val & 0x1F) != 0 ? 32 + ((val & 0x1F) << 1) : 0;
        switch (addr & 0xF0) {
            case 0x30:
                int mulVal = val & 0x0F;
                // GPGX: mul = 1 when register is 0, otherwise reg * 2
                sl.mul = (mulVal == 0) ? 1 : mulVal * 2;
                sl.dt1 = (val >> 4) & 7;
                ch.ops[0].fInc = -1;
                break;
            case 0x40:
                sl.tl = val & 0x7F;
                sl.tll = sl.tl << (ENV_HBITS - 7);
                // GPGX: update vol_out cache when TL changes
                updateVolOut(sl);
                break;
            case 0x50:
                sl.ar = ar;
                sl.rs = 3 - (val >> 6);
                ch.ops[0].fInc = -1;
                sl.eIncA = AR_TAB[sl.ar + sl.ksr];
                if (sl.curEnv == EnvState.ATTACK)
                    sl.eInc = sl.eIncA;
                updateEgRateCache(sl);
                break;
            case 0x60:
                sl.amMask = (val & 0x80) != 0 ? 0xFFFFFFFF : 0;
                sl.d1r = ar;
                sl.eIncD = DR_TAB[sl.d1r + sl.ksr];
                if (sl.curEnv == EnvState.DECAY1)
                    sl.eInc = sl.eIncD;
                updateEgRateCache(sl);
                break;
            case 0x70:
                sl.d2r = ar;
                sl.eIncS = DR_TAB[sl.d2r + sl.ksr];
                if (sl.curEnv == EnvState.DECAY2)
                    sl.eInc = sl.eIncS;
                updateEgRateCache(sl);
                break;
            case 0x80:
                sl.slReg = (val >> 4) & 0x0F; // Store raw 4-bit SL value
                sl.d1l = SL_TAB[sl.slReg];
                sl.rr = 34 + ((val & 0xF) << 2);
                sl.eIncR = DR_TAB[sl.rr + sl.ksr];
                if (sl.curEnv == EnvState.RELEASE)
                    sl.eInc = sl.eIncR;
                if (sl.curEnv == EnvState.DECAY1) {
                    int sustainLevel = SL_VOL_TAB[sl.slReg];
                    if (sl.volume >= sustainLevel) {
                        sl.curEnv = EnvState.DECAY2;
                        sl.eCnt = sl.d1l;
                    }
                }
                updateEgRateCache(sl);
                break;
            case 0x90:
                sl.ssgEg = val & 0x0F;
                sl.ssgEnabled = (val & 0x08) != 0;
                if (sl.curEnv != EnvState.RELEASE && sl.curEnv != EnvState.IDLE) {
                    updateVolOut(sl);
                }
                break;
        }
    }

    private void writeChannel(int addr, int val) {
        int nch = addr & 3;
        if (nch == 3)
            return;
        if ((addr & 0x100) != 0)
            nch += 3;
        Channel ch = channels[nch];

        switch (addr & 0xFC) {
            case 0xA0:
                ch.fNum = (ch.fNum & 0x700) | (val & 0xFF);
                ch.kCode = (ch.block << 2) | FKEY_TAB[ch.fNum >> 7];
                // GPGX: fc = (fNum << block) >> 1
                ch.fc = (ch.fNum << ch.block) >> 1;
                ch.blockFnum = (ch.block << 11) | ch.fNum;
                ch.ops[0].fInc = -1;
                break;
            case 0xA4:
                ch.fNum = (ch.fNum & 0xFF) | ((val & 0x07) << 8);
                ch.block = (val >> 3) & 7;
                ch.kCode = (ch.block << 2) | FKEY_TAB[ch.fNum >> 7];
                ch.fc = (ch.fNum << ch.block) >> 1;
                ch.blockFnum = (ch.block << 11) | ch.fNum;
                ch.ops[0].fInc = -1;
                break;
            case 0xA8:
                if (nch == 2) {
                    int slot = (addr & 3) + 1;
                    ch.slotFnum[slot] = (ch.slotFnum[slot] & 0x700) | (val & 0xFF);
                    ch.slotKCode[slot] = (ch.slotBlock[slot] << 2) | FKEY_TAB[ch.slotFnum[slot] >> 7];
                    ch.slotFc[slot] = (ch.slotFnum[slot] << ch.slotBlock[slot]) >> 1;
                    ch.slotBlockFnum[slot] = (ch.slotBlock[slot] << 11) | ch.slotFnum[slot];
                    ch.ops[0].fInc = -1;
                }
                break;
            case 0xAC:
                if (nch == 2) {
                    int slot = (addr & 3) + 1;
                    ch.slotFnum[slot] = (ch.slotFnum[slot] & 0xFF) | ((val & 0x07) << 8);
                    ch.slotBlock[slot] = (val >> 3) & 7;
                    ch.slotKCode[slot] = (ch.slotBlock[slot] << 2) | FKEY_TAB[ch.slotFnum[slot] >> 7];
                    ch.slotFc[slot] = (ch.slotFnum[slot] << ch.slotBlock[slot]) >> 1;
                    ch.slotBlockFnum[slot] = (ch.slotBlock[slot] << 11) | ch.slotFnum[slot];
                    ch.ops[0].fInc = -1;
                }
                break;
            case 0xB0:
                ch.algo = val & 7;
                int fb = (val >> 3) & 7;
                // GPGX feedback formula: SIN_BITS - fb where SIN_BITS=10
                // For fb=7 (max): shift by 3 (strongest feedback)
                // For fb=1 (min): shift by 9 (weakest non-zero feedback)
                // For fb=0: disabled (use large shift to zero result)
                ch.feedback = (fb != 0) ? (SIN_BITS - fb) : 31;
                break;
            case 0xB4:
                ch.pan = (val >> 6) & 3;
                ch.leftMask = (val & 0x80) != 0 ? 0xFFFFFFFF : 0;
                ch.rightMask = (val & 0x40) != 0 ? 0xFFFFFFFF : 0;
                ch.ams = LFO_AMS_DEPTH_SHIFT[(val >> 4) & 3];
                ch.pms = (val & 7) * 32;
                break;
        }
    }

    private void calcFIncSlot(Operator sl, int fc, int kc) {
        // GPGX refresh_fc_eg_slot: add detune, apply overflow mask, then multiply
        fc = (fc + DT_TAB[sl.dt1][kc]) & DT_MASK;
        // GPGX: Incr = (fc * mul) >> 1 (because mul is 1 or reg*2)
        sl.fInc = (fc * sl.mul) >> 1;
        int ksr = kc >> sl.rs;
        if (sl.ksr != ksr) {
            sl.ksr = ksr;
            sl.eIncA = AR_TAB[sl.ar + ksr];
            sl.eIncD = DR_TAB[sl.d1r + ksr];
            sl.eIncS = DR_TAB[sl.d2r + ksr];
            sl.eIncR = DR_TAB[sl.rr + ksr];

            if (sl.curEnv == EnvState.ATTACK)
                sl.eInc = sl.eIncA;
            else if (sl.curEnv == EnvState.DECAY1)
                sl.eInc = sl.eIncD;
            else if (sl.eCnt < ENV_END) {
                if (sl.curEnv == EnvState.DECAY2)
                    sl.eInc = sl.eIncS;
                else if (sl.curEnv == EnvState.RELEASE)
                    sl.eInc = sl.eIncR;
            }

            // Update GPGX EG rate cache
            updateEgRateCache(sl);
        }
    }

    /**
     * Update GPGX-style EG rate cache for an operator.
     * Called when ar, d1r, d2r, rr, or ksr changes.
     */
    private void updateEgRateCache(Operator sl) {
        // Attack rate
        int rateArRaw = sl.ar + sl.ksr;
        if (rateArRaw >= 94) {
            sl.egShAr = 0;
            sl.egSelAr = EG_RATE_ZERO;
        } else {
            sl.egShAr = EG_RATE_SHIFT[rateArRaw];
            sl.egSelAr = EG_RATE_SELECT[rateArRaw];
        }

        // Decay1 rate
        int rateD1r = sl.d1r + sl.ksr;
        sl.egShD1r = EG_RATE_SHIFT[rateD1r];
        sl.egSelD1r = EG_RATE_SELECT[rateD1r];

        // Decay2/Sustain rate
        int rateD2r = sl.d2r + sl.ksr;
        sl.egShD2r = EG_RATE_SHIFT[rateD2r];
        sl.egSelD2r = EG_RATE_SELECT[rateD2r];

        // Release rate
        int rateRr = sl.rr + sl.ksr;
        sl.egShRr = EG_RATE_SHIFT[rateRr];
        sl.egSelRr = EG_RATE_SELECT[rateRr];
    }

    private void calcFIncChannel(Channel ch) {
        // GPGX: refresh_fc_eg_chan uses pre-calculated fc from register write
        if (channel3SpecialMode && ch == channels[2]) {
            for (int i = 0; i < 4; i++) {
                // Slot 0 uses channel fc, slots 1-3 use slotFc
                int fc = (i == 0) ? ch.fc : ch.slotFc[i];
                int kc = (i == 0) ? ch.kCode : ch.slotKCode[i];
                calcFIncSlot(ch.ops[i], fc, kc);
            }
        } else {
            calcFIncSlot(ch.ops[0], ch.fc, ch.kCode);
            calcFIncSlot(ch.ops[1], ch.fc, ch.kCode);
            calcFIncSlot(ch.ops[2], ch.fc, ch.kCode);
            calcFIncSlot(ch.ops[3], ch.fc, ch.kCode);
        }
    }

    private void keyOn(Channel ch, int idx) {
        // Ensure ksr/EG rate cache is up to date before key-on state decisions.
        // This avoids stale ksr causing attack to be chosen when it should be blocked.
        if (ch.ops[0].fInc == -1) {
            calcFIncChannel(ch);
        }
        Operator sl = ch.ops[idx];
        // GPGX-style: use separate key flag instead of checking envelope state.
        // This properly gates key-on to only trigger on 0->1 transitions.
        if (!sl.key && csmKeyFlag == 0) {
            // Restart phase generator (GPGX: SLOT->phase = 0)
            sl.fCnt = 0;

            // Reset SSG-EG inversion (GPGX: SLOT->ssgn = 0)
            // Our SSG-EG uses different approach but reset is still needed
            sl.ssgn = 0;

            if ((sl.ar + sl.ksr) < 94) {
                sl.curEnv = (sl.volume <= 0)
                        ? ((sl.slReg == 0) ? EnvState.DECAY2 : EnvState.DECAY1)
                        : EnvState.ATTACK;
            } else {
                sl.volume = 0;
                sl.curEnv = (sl.slReg == 0) ? EnvState.DECAY2 : EnvState.DECAY1;
            }
            updateVolOut(sl);

            // Keep legacy eCnt in sync for any code that still uses it
            sl.eCnt = ENV_ATTACK;
            sl.chgEnM = 0xFFFFFFFF;
            sl.eInc = sl.eIncA;
            sl.eCmp = ENV_DECAY;
            // Note: Do NOT reset opOut (feedback history) here.
            // libvgm does not clear feedback on keyOn.
        }
        sl.key = true;
        traceKeyEvent("KEY_ON", ch, idx, sl);
    }

    private void keyOff(Channel ch, int idx) {
        Operator sl = ch.ops[idx];
        // GPGX-style: only transition to RELEASE if key was on and not already
        // releasing
        if (sl.key && csmKeyFlag == 0) {
            if (sl.curEnv != EnvState.RELEASE) {
                sl.curEnv = EnvState.RELEASE;
                if (sl.eCnt < ENV_DECAY) {
                    sl.eCnt = (ENV_TAB[sl.eCnt >> ENV_LBITS] << ENV_LBITS) + ENV_DECAY;
                }
                sl.eInc = sl.eIncR;
                sl.eCmp = ENV_END;
                if (sl.ssgEnabled) {
                    if (((sl.ssgn ^ (sl.ssgEg & 0x04)) != 0)) {
                        sl.volume = (SSG_THRESHOLD - sl.volume) & MAX_ATT_INDEX;
                    }
                    if (sl.volume >= SSG_THRESHOLD) {
                        sl.volume = MAX_ATT_INDEX;
                        sl.curEnv = EnvState.IDLE;
                    }
                }
                updateVolOut(sl);
            }
        }
        sl.key = false;
        traceKeyEvent("KEY_OFF", ch, idx, sl);
    }

    // Sustain level lookup table: maps 4-bit register value to GPGX volume scale.
    // 3 dB steps: 0..14 map to 0..448 in 32-step increments, SL=15 maps to 992.
    private static final int[] SL_VOL_TAB = {
            0, 32, 64, 96, 128, 160, 192, 224,
            256, 288, 320, 352, 384, 416, 448, 992
    };

    /**
     * GPGX-style envelope advancement with rate-gated 3-sample stepping.
     * Uses global egCnt to determine when to update each operator's envelope.
     */
    private void advanceEgOperator(Operator sl) {
        switch (sl.curEnv) {
            case ATTACK:
                // Rate-gated check: only update if counter passes the gate
                if ((egCnt & ((1 << sl.egShAr) - 1)) == 0) {
                    int inc = EG_INC[sl.egSelAr + ((egCnt >> sl.egShAr) & 7)];
                    // Exponential attack: volume += ((~volume) * inc) >> 4
                    sl.volume += ((~sl.volume) * inc) >> 4;
                    if (sl.volume <= 0) {
                        sl.volume = 0;
                        // Transition to decay phase using stored slReg directly
                        sl.curEnv = (sl.slReg == 0) ? EnvState.DECAY2 : EnvState.DECAY1;
                        sl.eCnt = ENV_DECAY; // Keep legacy eCnt in sync
                    }
                    updateVolOut(sl);
                }
                break;

            case DECAY1:
                if ((egCnt & ((1 << sl.egShD1r) - 1)) == 0) {
                    int inc = EG_INC[sl.egSelD1r + ((egCnt >> sl.egShD1r) & 7)];
                    if (sl.ssgEnabled) {
                        if (sl.volume < SSG_THRESHOLD) {
                            sl.volume += 4 * inc;
                            updateVolOut(sl);
                        }
                    } else {
                        sl.volume += inc;
                        updateVolOut(sl);
                    }
                    int sustainLevel = SL_VOL_TAB[sl.slReg];
                    if (sl.volume >= sustainLevel) {
                        sl.volume = sustainLevel;
                        sl.curEnv = EnvState.DECAY2;
                        sl.eCnt = sl.d1l; // Keep legacy eCnt in sync
                        updateVolOut(sl);
                    }
                }
                break;

            case DECAY2:
                if ((egCnt & ((1 << sl.egShD2r) - 1)) == 0) {
                    int inc = EG_INC[sl.egSelD2r + ((egCnt >> sl.egShD2r) & 7)];
                    if (sl.ssgEnabled) {
                        if (sl.volume < SSG_THRESHOLD) {
                            sl.volume += 4 * inc;
                            updateVolOut(sl);
                        }
                    } else {
                        sl.volume += inc;
                        if (sl.volume >= MAX_ATT_INDEX) {
                            sl.volume = MAX_ATT_INDEX;
                            updateVolOut(sl);
                        } else {
                            updateVolOut(sl);
                        }
                    }
                }
                break;

            case RELEASE:
                if ((egCnt & ((1 << sl.egShRr) - 1)) == 0) {
                    int inc = EG_INC[sl.egSelRr + ((egCnt >> sl.egShRr) & 7)];
                    if (sl.ssgEnabled) {
                        if (sl.volume < SSG_THRESHOLD) {
                            sl.volume += 4 * inc;
                        }
                        if (sl.volume >= SSG_THRESHOLD) {
                            sl.volume = MAX_ATT_INDEX;
                            sl.curEnv = EnvState.IDLE;
                            sl.eCnt = ENV_END;
                        }
                    } else {
                        sl.volume += inc;
                        if (sl.volume >= MAX_ATT_INDEX) {
                            sl.volume = MAX_ATT_INDEX;
                            sl.curEnv = EnvState.IDLE;
                            sl.eCnt = ENV_END;
                        }
                    }
                    updateVolOut(sl);
                }
                break;

            case IDLE:
                // Do nothing
                break;
        }
    }

    /**
     * Render stereo output at 44.1kHz by generating at internal rate (~53kHz) and
     * resampling.
     * This matches GPGX's timing accuracy while maintaining standard audio output
     * rate.
     */
    public void renderStereo(int[] leftBuf, int[] rightBuf) {
        int outputLen = Math.min(leftBuf.length, rightBuf.length);

        if (useBlipResampler) {
            // Band-limited resampling using windowed-sinc filter
            // This properly prevents aliasing artifacts (metallic/ringing sounds)
            for (int outIdx = 0; outIdx < outputLen; outIdx++) {
                // Generate internal samples until we have enough for next output
                while (!blipResampler.hasOutputSample()) {
                    renderOneSample();
                    blipResampler.addInputSample(lastLeft, lastRight);
                    blipResampler.advanceInput();
                }

                // Get the band-limited interpolated output
                leftBuf[outIdx] += blipResampler.getOutputLeft();
                rightBuf[outIdx] += blipResampler.getOutputRight();
                blipResampler.advanceOutput();
            }
        } else {
            // Legacy linear interpolation (kept for comparison/debugging)
            for (int outIdx = 0; outIdx < outputLen; outIdx++) {
                while (resampleAccum < 1.0) {
                    prevLeft = lastLeft;
                    prevRight = lastRight;
                    renderOneSample();
                    resampleAccum += 1.0 / RESAMPLE_RATIO;
                }
                resampleAccum -= 1.0;

                double t = resampleAccum * RESAMPLE_RATIO;
                int left = (int) (prevLeft + t * (lastLeft - prevLeft));
                int right = (int) (prevRight + t * (lastRight - prevRight));

                leftBuf[outIdx] += left;
                rightBuf[outIdx] += right;
            }
        }
    }

    /**
     * Enable or disable the band-limited resampler.
     * When disabled, falls back to simple linear interpolation.
     */
    public void setUseBlipResampler(boolean use) {
        this.useBlipResampler = use;
    }

    public boolean isUseBlipResampler() {
        return useBlipResampler;
    }

    /**
     * Generate one internal sample at ~53kHz. Updates lastLeft/lastRight.
     */
    private void renderOneSample() {
        // GPGX: LFO values are read BEFORE update (for use in channel calc)
        // and then updated AFTER channel calculation
        int pmLfo = lfoPm;
        int envLfo = lfoAm; // GPGX: 126 (max AM) when disabled

        Arrays.fill(channelOut, 0);

        updateSsgEg();

        // DAC output
        int dacOut = renderDac();
        if (dacOut > LIMIT_CH_OUT_POS)
            dacOut = LIMIT_CH_OUT_POS;
        else if (dacOut < LIMIT_CH_OUT_NEG)
            dacOut = LIMIT_CH_OUT_NEG;
        if (dacEnabled && !mutes[5]) {
            channelOut[5] = dacOut;
        }

        // FM channels
        for (int ch = 0; ch < 6; ch++) {
            if (mutes[ch])
                continue;
            if (ch == 5 && dacEnabled)
                continue;
            channelOut[ch] = renderChannel(ch, envLfo, pmLfo);
        }

        int leftSum = 0;
        int rightSum = 0;
        for (int ch = 0; ch < 6; ch++) {
            int out = channelOut[ch];
            Channel chan = channels[ch];
            if (chan.leftMask != 0)
                leftSum += out;
            if (chan.rightMask != 0)
                rightSum += out;
        }

        if (chipType == YM2612_DISCRETE) {
            for (int ch = 0; ch < 6; ch++) {
                int out = channelOut[ch];
                Channel chan = channels[ch];
                if (out < 0) {
                    leftSum -= ((4 - (chan.leftMask & 1)) << 5);
                    rightSum -= ((4 - (chan.rightMask & 1)) << 5);
                } else {
                    leftSum += (4 << 5);
                    rightSum += (4 << 5);
                }
            }
        }

        lastLeft = leftSum;
        lastRight = rightSum;

        // GPGX: LFO updated AFTER channel calculation
        advanceLfo();

        // GPGX EG timer: only advance egCnt and envelopes every 3 samples
        // This matches hardware where EG runs at chipclock/144/3
        egTimer++;
        if (egTimer >= 3) {
            egTimer = 0;
            // Simple increment, wrap from 4096 to 1 (skip 0)
            egCnt++;
            if (egCnt >= 4096)
                egCnt = 1;

            // Advance envelope generators for all channels (GPGX: advance_eg_channels)
            for (int ch = 0; ch < 6; ch++) {
                Channel c = channels[ch];
                for (int op = 0; op < 4; op++) {
                    advanceEgOperator(c.ops[op]);
                }
            }
        }

        tickTimers();
    }

    // DEBUG: Set to true to mute FM4 (channel 3) for Signpost SFX debugging
    private static final boolean DEBUG_MUTE_FM4 = false;

    private int renderChannel(int chIdx, int envLfo, int pmLfo) {
        Channel ch = channels[chIdx];

        // DEBUG: Mute FM4 to test if two-channel interference causes the reverb
        if (DEBUG_MUTE_FM4 && chIdx == 3) {
            return 0;
        }

        // Note: Do NOT early-exit for silent channels. Even when all operators are
        // at ENV_END, we must continue updating phase (fCnt) and feedback (opOut).
        // Early-exit was preventing proper feedback accumulation, causing artifacts
        // in high-feedback instruments like Signpost SFX (0xCF).

        if (ch.ops[0].fInc == -1)
            calcFIncChannel(ch);

        // GET_CURRENT_PHASE - capture fCnt BEFORE incrementing (like libvgm)
        // Slot order matches ym2612.c: S0=op0, S1=op2, S2=op1, S3=op3
        in0 = ch.ops[0].fCnt;
        in1 = ch.ops[2].fCnt;
        in2 = ch.ops[1].fCnt;
        in3 = ch.ops[3].fCnt;

        // UPDATE_PHASE - increment fCnt AFTER capturing
        boolean lfoPmActive = ch.pms != 0;
        if (lfoPmActive) {
            if (channel3SpecialMode && chIdx == 2) {
                int kc = ch.kCode; // GPGX: keyscale code is not modified by LFO
                for (int i = 0; i < 4; i++) {
                    Operator op = ch.ops[i];
                    int blockFnum = (i == 0) ? ch.blockFnum : ch.slotBlockFnum[i];
                    int pm = ch.pms + pmLfo;
                    int lfoOffset = LFO_PM_TABLE[((blockFnum & 0x7F0) << 4) + pm];
                    if (lfoOffset != 0) {
                        // GPGX: LFO works with one more bit of precision (12-bit)
                        int blk = blockFnum >> 11;
                        int fc = ((blockFnum << 1) + lfoOffset) & 0xFFF;
                        fc = (fc << blk) >> 2; // GPGX formula
                        fc = (fc + DT_TAB[op.dt1][kc]) & DT_MASK;
                        op.fCnt += (fc * op.mul) >> 1;
                    } else {
                        op.fCnt += op.fInc;
                    }
                }
            } else {
                int blockFnum = ch.blockFnum;
                int pm = ch.pms + pmLfo;
                int lfoOffset = LFO_PM_TABLE[((blockFnum & 0x7F0) << 4) + pm];
                if (lfoOffset != 0) {
                    int blk = blockFnum >> 11;
                    int kc = ch.kCode;
                    int fcBase = ((blockFnum << 1) + lfoOffset) & 0xFFF;
                    fcBase = (fcBase << blk) >> 2;
                    for (Operator op : ch.ops) {
                        int fc = (fcBase + DT_TAB[op.dt1][kc]) & DT_MASK;
                        op.fCnt += (fc * op.mul) >> 1;
                    }
                } else {
                    for (Operator op : ch.ops) {
                        op.fCnt += op.fInc;
                    }
                }
            }
        } else {
            for (Operator op : ch.ops) {
                op.fCnt += op.fInc;
            }
        }

        // GET_CURRENT_ENV - read envelope values for this sample
        // Note: EG advancement happens separately every 3 samples in renderOneSample
        GET_CURRENT_ENV(ch, 0, envLfo);
        GET_CURRENT_ENV(ch, 1, envLfo);
        GET_CURRENT_ENV(ch, 2, envLfo);
        GET_CURRENT_ENV(ch, 3, envLfo);

        doAlgo(ch);

        // GPGX-style asymmetric clipping
        if (ch.out > LIMIT_CH_OUT_POS)
            ch.out = LIMIT_CH_OUT_POS;
        else if (ch.out < LIMIT_CH_OUT_NEG)
            ch.out = LIMIT_CH_OUT_NEG;

        return ch.out;
    }

    private void GET_CURRENT_ENV(Channel ch, int slot, int envLfo) {
        Operator sl = ch.ops[slot];
        // GPGX: Use cached vol_out (= volume + tll)
        // This avoids per-sample recalculation
        int env = sl.volOut;
        int am = envLfo >> ch.ams;
        env += (am & sl.amMask);

        switch (slot) {
            case 0 -> en0 = env;
            case 1 -> en1 = env;
            case 2 -> en2 = env;
            case 3 -> en3 = env;
        }
    }

    /**
     * Bounds-checked TL_TAB lookup for cross-modulation between operators.
     * Returns 0 (silence) when index exceeds table bounds.
     * This ensures proper fade-out when envelope/TL gets very high.
     * <p>
     * Phase modulation: modulation is added AFTER phase shift.
     * GPGX uses pm >> 1 for cross-modulation (op_calc).
     */
    private static int opCalc(int phase, int env, int pm) {
        // GPGX op_calc(): (phase >> SIN_BITS) + (pm >> 1)
        int idx = ((phase >> SIN_BITS) + (pm >> 1)) & SIN_MASK;
        int p = (env << 3) + SIN_TAB[idx];
        if (p >= TL_TAB_LEN)
            return 0;
        return TL_TAB[p];
    }

    /**
     * Feedback calculation for Slot 1 - uses pm directly without >> 1 shift.
     * GPGX op_calc1() (ym2612.c:1427) applies feedback modulation at full strength.
     */
    private static int opCalc1(int phase, int env, int pm) {
        // GPGX op_calc1(): (phase >> SIN_BITS) + pm (no >> 1)
        int idx = ((phase >> SIN_BITS) + pm) & SIN_MASK;
        int p = (env << 3) + SIN_TAB[idx];
        if (p >= TL_TAB_LEN)
            return 0;
        return TL_TAB[p];
    }

    private static int opCalc1(int phase, int env, int pm, int mask) {
        int out = opCalc1(phase, env, pm);
        return out & mask;
    }

    /**
     * opCalc without modulation (for carriers with no input modulation)
     */
    private static int opCalc(int phase, int env) {
        // GPGX: (phase >> SIN_BITS) & SIN_MASK
        int p = (env << 3) + SIN_TAB[(phase >> SIN_BITS) & SIN_MASK];
        if (p >= TL_TAB_LEN)
            return 0;
        return TL_TAB[p];
    }

    private static int opCalc(int phase, int env, int pm, int mask) {
        int out = opCalc(phase, env, pm);
        return out & mask;
    }

    private static int opCalcNoModMasked(int phase, int env, int mask) {
        int out = opCalc(phase, env);
        return out & mask;
    }

    private void doAlgo(Channel ch) {
        // Phase values (in0..in3) are already set by renderChannel's GET_CURRENT_PHASE
        // step.
        // in0..in3 are in slot order S0,S1,S2,S3. Our GET_CURRENT_ENV stores:
        // en0=S0, en1=S2, en2=S1, en3=S3 (because ops[] is [S0,S2,S1,S3]).
        // Reorder here to match libvgm's S0,S1,S2,S3 expectations.
        final int env0 = en0;
        final int env1 = en2;
        final int env2 = en1;
        final int env3 = en3;

        // GPGX-style: Modulation is now passed separately to opCalc() instead of
        // being added to the phase directly. This allows GPGX-accurate scaling.

        // GPGX ENV_QUIET check: when envelope is quiet, operator output is forced to 0.
        // This causes feedback buffer to naturally decay when notes fade out.
        boolean s0Quiet = env0 >= ENV_QUIET;

        // GPGX mask indices follow SLOT numbers: SLOT1=mask[0], SLOT2=mask[1], SLOT3=mask[2], SLOT4=mask[3]
        // Our operators: M1=SLOT1 (in0), C1=SLOT2 (in1), M2=SLOT3 (in2), C2=SLOT4 (in3)
        int[] mask = opMask[ch.algo];
        int maskM1 = mask[0];  // SLOT1
        int maskC1 = mask[1];  // SLOT2 - for in1/env1
        int maskM2 = mask[2];  // SLOT3 - for in2/env2
        int maskC2 = mask[3];  // SLOT4

        // GPGX: feedback uses opCalc1() which applies pm directly (no >> 1 shift)
        // Previous code used opCalc() with fb << 1 to compensate, but this creates subtle phase differences
        int fb = (ch.feedback < SIN_BITS) ? (ch.opOut[0] + ch.opOut[1]) >> ch.feedback : 0;
        ch.opOut[1] = ch.opOut[0];
        int s0_out = s0Quiet ? 0 : opCalc1(in0, env0, fb, maskM1);
        ch.opOut[0] = s0_out; // ALWAYS update - quiet means 0 propagates

        switch (ch.algo) {
            case 0: {
                // M1 -> C1 -> MEM -> M2 -> C2 (carrier)
                int m2 = ch.memValue;
                int mem = opCalc(in1, env1, s0_out, maskC1);
                int m2_out = opCalc(in2, env2, m2, maskM2);
                ch.out = opCalc(in3, env3, m2_out, maskC2) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 1: {
                // (M1 + C1) -> MEM -> M2 -> C2 (carrier)
                int m2 = ch.memValue;
                int c1_out = opCalcNoModMasked(in1, env1, maskC1); // C1 no modulation
                int mem = s0_out + c1_out;
                int m2_out = opCalc(in2, env2, m2, maskM2);
                ch.out = opCalc(in3, env3, m2_out, maskC2) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 2: {
                // M1 + (C1 -> MEM -> M2) -> C2 (carrier)
                int m2 = ch.memValue;
                // C1 no modulation
                int mem = opCalcNoModMasked(in1, env1, maskC1);
                int m2_out = opCalc(in2, env2, m2, maskM2);
                ch.out = opCalc(in3, env3, s0_out + m2_out, maskC2) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 3: {
                // M1 -> C1 -> MEM + M2 -> C2 (carrier)
                int c2 = ch.memValue;
                int mem = opCalc(in1, env1, s0_out, maskC1);
                int m2_out = opCalcNoModMasked(in2, env2, maskM2); // M2 no modulation
                c2 += m2_out;
                ch.out = opCalc(in3, env3, c2, maskC2) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 4: {
                // M1 -> C1 (carrier) + M2 -> C2 (carrier)
                int c1_out = opCalc(in1, env1, s0_out, maskC1);
                int m2_out = opCalcNoModMasked(in2, env2, maskM2); // M2 no modulation
                int c2_out = opCalc(in3, env3, m2_out, maskC2);
                ch.out = (c1_out + c2_out) >> OUT_SHIFT;
                break;
            }
            case 5: {
                // GPGX: M1 -> (C1 + M2 + C2) all carriers, M2 uses delayed MEM
                // connect1 = NULL special: mem = c1 = c2 = M1_out
                // C1 (SLOT2, in1) uses c1 = this M1
                // M2 (SLOT3, in2) uses m2 = old mem (1-sample delayed M1)
                // C2 (SLOT4, in3) uses c2 = this M1
                int m2 = ch.memValue;
                int c1_out = opCalc(in1, env1, s0_out, maskC1);  // C1 uses this M1
                int m2_out = opCalc(in2, env2, m2, maskM2);      // M2 uses old mem
                int c2_out = opCalc(in3, env3, s0_out, maskC2);  // C2 uses this M1
                ch.out = (c1_out + m2_out + c2_out) >> OUT_SHIFT;
                ch.memValue = s0_out;
                break;
            }
            case 6: {
                // M1 -> C1 (carrier) + M2 (carrier) + C2 (carrier)
                int c1_out = opCalc(in1, env1, s0_out, maskC1);
                int m2_out = opCalcNoModMasked(in2, env2, maskM2); // M2 no modulation
                int c2_out = opCalcNoModMasked(in3, env3, maskC2); // C2 no modulation
                ch.out = (c1_out + m2_out + c2_out) >> OUT_SHIFT;
                break;
            }
            case 7: {
                // All carriers, no modulation
                int c1_out = opCalcNoModMasked(in1, env1, maskC1);
                int m2_out = opCalcNoModMasked(in2, env2, maskM2);
                int c2_out = opCalcNoModMasked(in3, env3, maskC2);
                ch.out = (s0_out + c1_out + m2_out + c2_out) >> OUT_SHIFT;
                break;
            }
        }

    }

    public void setInstrument(int chIdx, byte[] voice) {
        if (chIdx < 0 || chIdx >= 6 || voice.length < 1)
            return;
        Channel ch = channels[chIdx];

        int port = (chIdx < 3) ? 0 : 1;
        int hwCh = chIdx % 3;
        int chVal = (port == 0) ? hwCh : (hwCh + 4);

        // Key off all operators before loading new voice (like Z80 zFMSilenceChannel).
        // This ensures any residual sound from previous notes is silenced.
        write(0, 0x28, chVal);

        // Minimal reset: only mark frequency for recalculation.
        // Do NOT reset opOut (feedback history) or full envelope state here.
        // libvgm does not perform comprehensive resets on voice load - it relies
        // on the register writes to set up the channel. Aggressive resets were
        // causing Signpost SFX (0xCF) to have altered timbre due to feedback
        // state being cleared when it should persist naturally.
        ch.ops[0].fInc = -1;

        boolean hasTl = voice.length >= 25;
        int expectedLen = hasTl ? 25 : 21;
        if (voice.length < expectedLen) {
            byte[] padded = new byte[expectedLen];
            System.arraycopy(voice, 0, padded, 0, voice.length);
            voice = padded;
        }

        final byte[] v = voice;
        java.util.function.IntUnaryOperator get = (idx) -> (idx >= 0 && idx < v.length) ? (v[idx] & 0xFF) : 0;

        int val00 = get.applyAsInt(0);
        int feedback = (val00 >> 3) & 7;
        int algo = val00 & 7;

        write(port, 0xB0 + hwCh, (feedback << 3) | algo);

        int tlIdxBase = 21;
        int rsArBase = 5;
        int amD1rBase = 9;
        int d2rBase = 13;
        int d1lRrBase = 17;
        // Reorder SMPS voice (Op1, Op3, Op2, Op4) into YM operator order (Op1, Op2, Op3, Op4).
        int[] dtIdx = { 1, 3, 2, 4 };
        int[] tlIdx = { 21, 23, 22, 24 };
        int[] rsArIdx = { 5, 7, 6, 8 };
        int[] amIdx = { 9, 11, 10, 12 };
        int[] d2rIdx = { 13, 15, 14, 16 };
        int[] d1lRrIdx = { 17, 19, 18, 20 };

        // Map to YM order into temporary arrays for clarity
        int[] dt = new int[4];
        int[] tl = new int[4];
        int[] rsar = new int[4];
        int[] amd1 = new int[4];
        int[] d2r = new int[4];
        int[] d1lrr = new int[4];
        for (int i = 0; i < 4; i++) {
            dt[i] = get.applyAsInt(dtIdx[i]);
            tl[i] = hasTl ? get.applyAsInt(tlIdx[i]) : 0;
            rsar[i] = get.applyAsInt(rsArIdx[i]);
            amd1[i] = get.applyAsInt(amIdx[i]);
            d2r[i] = get.applyAsInt(d2rIdx[i]);
            d1lrr[i] = get.applyAsInt(d1lRrIdx[i]);
        }

        for (int slot = 0; slot < 4; slot++) {
            write(port, 0x30 + slot * 4 + hwCh, dt[slot]);
            write(port, 0x40 + slot * 4 + hwCh, tl[slot]);
            write(port, 0x50 + slot * 4 + hwCh, rsar[slot]);
            write(port, 0x60 + slot * 4 + hwCh, amd1[slot]);
            write(port, 0x70 + slot * 4 + hwCh, d2r[slot]);
            write(port, 0x80 + slot * 4 + hwCh, d1lrr[slot]);
            write(port, 0x90 + slot * 4 + hwCh, 0);
        }
    }

    public void playDac(int note) {
        if (dacData == null)
            return;
        DacData.DacEntry entry = dacData.mapping.get(note);
        if (entry != null) {
            this.currentDacSampleId = entry.sampleId;
            this.dacPos = 0;
            int rateByte = entry.rate & 0xFF;
            double cyclesPerBlock = DAC_BASE_CYCLES + (DAC_LOOP_CYCLES * rateByte);
            double cyclesPerSample = cyclesPerBlock / DAC_LOOP_SAMPLES;
            double rateHz = Z80_CLOCK / cyclesPerSample;
            // DAC step is now relative to internal rate since renderDac() is called at
            // ~53kHz
            this.dacStep = Math.max(0.0001, rateHz / INTERNAL_RATE);
        }
    }

    public void stopDac() {
        currentDacSampleId = -1;
        dacHasLatched = false;
        dacPos = 0;
    }

    private int renderDac() {
        if (!dacEnabled) {
            return 0;
        }
        int sample = 0;
        if (currentDacSampleId != -1 && dacData != null) {
            byte[] data = dacData.samples.get(currentDacSampleId);
            if (data != null && dacPos < data.length) {
                int idx = (int) dacPos;
                double frac = dacPos - idx;
                int s1 = (data[idx] & 0xFF) - 128;
                if (dacInterpolate) {
                    int s2 = (idx + 1 < data.length) ? ((data[idx + 1] & 0xFF) - 128) : s1;
                    double lerp = s1 * (1.0 - frac) + s2 * frac;
                    sample = (int) Math.round(lerp);
                } else {
                    sample = s1;
                }
                dacPos += dacStep;
                if (dacPos >= data.length) {
                    currentDacSampleId = -1;
                    dacPos = 0;
                }
            }
        } else if (dacHasLatched) {
            sample = dacLatchedValue;
        } else {
            return 0;
        }

        sample = (int) (sample * DAC_GAIN);

        if (dacHighpassEnabled) {
            // Highpass Filter
            sample = (sample << HIGHPASS_FRACT) - dac_highpass;
            dac_highpass += sample >> HIGHPASS_SHIFT;
            sample >>= HIGHPASS_FRACT;
        }

        return sample;
    }

    /**
     * Legacy helper for unit tests (TestYm2612AlgorithmRouting).
     * Assumes MEM = 0 (pre-GPGX) and does not model output masking.
     */
    public static double computeModulationInput(int algo, int opIndex, double[] opOut, double feedback) {
        return computeModulationInputWithMem(algo, opIndex, opOut, feedback, 0.0);
    }

    /**
     * Helper for unit tests (TestYm2612AlgorithmRouting).
     * Models the GPGX MEM path for algorithms 0-3 and 5. Output masking is not
     * modeled.
     */
    public static double computeModulationInputWithMem(int algo, int opIndex, double[] opOut, double feedback,
            double memValue) {
        if (opIndex == 0)
            return feedback;
        if (opIndex < 0 || opIndex >= OP_TO_SLOT.length)
            return 0;
        // Map op outputs into slot order (S0,S1,S2,S3) used by ym2612.c algorithms.
        double slot0 = opOut[0]; // Op1 (feedback operator)
        double slot1 = opOut[2]; // Op3
        double slot2 = opOut[1]; // Op2
        double slot3 = opOut[3]; // Op4
        int slotIndex = OP_TO_SLOT[opIndex];

        final double v = slotIndex == 2 ? memValue : slotIndex == 3 ? slot2 : 0;
        return switch (algo) {
            case 0 -> (slotIndex == 1 ? slot0 : v);
            case 1 -> v;
            case 2 -> (slotIndex == 2 ? memValue : slotIndex == 3 ? slot0 + slot2 : 0);
            case 3 -> (slotIndex == 1 ? slot0 : slotIndex == 3 ? memValue + slot2 : 0);
            case 4 -> (slotIndex == 1 ? slot0 : slotIndex == 3 ? slot2 : 0);
            case 5 -> slotIndex == 1 ? memValue : slot0;
            case 6 -> (slotIndex == 1 ? slot0 : 0);
            default -> 0;
        };
    }

    /**
     * Legacy helper for unit tests (TestYm2612AlgorithmRouting).
     */
    public static double computeCarrierSum(int algo, double[] opOut) {
        double slot0 = opOut[0];
        double slot1 = opOut[2];
        double slot2 = opOut[1];
        double slot3 = opOut[3];
        return switch (algo) {
            case 4 -> slot1 + slot3;
            case 5, 6 -> slot1 + slot2 + slot3;
            case 7 -> slot0 + slot1 + slot2 + slot3;
            default -> slot3;
        };
    }

    private void csmKeyOn() {
        Channel ch = channels[2];
        keyOnCsm(ch, 0);
        keyOnCsm(ch, 1);
        keyOnCsm(ch, 2);
        keyOnCsm(ch, 3);
        csmKeyFlag = 1;
    }

    private void csmKeyOff() {
        Channel ch = channels[2];
        keyOffCsm(ch, 0);
        keyOffCsm(ch, 1);
        keyOffCsm(ch, 2);
        keyOffCsm(ch, 3);
        csmKeyFlag = 0;
    }

    private void keyOnCsm(Channel ch, int idx) {
        // Ensure ksr/EG rate cache is up to date before key-on state decisions.
        if (ch.ops[0].fInc == -1) {
            calcFIncChannel(ch);
        }
        Operator sl = ch.ops[idx];
        if (!sl.key && csmKeyFlag == 0) {
            sl.fCnt = 0;
            sl.ssgn = 0;
            if ((sl.ar + sl.ksr) < 94) {
                sl.curEnv = (sl.volume <= 0)
                        ? ((sl.slReg == 0) ? EnvState.DECAY2 : EnvState.DECAY1)
                        : EnvState.ATTACK;
            } else {
                sl.volume = 0;
                sl.curEnv = (sl.slReg == 0) ? EnvState.DECAY2 : EnvState.DECAY1;
            }
            updateVolOut(sl);
        }
        traceKeyEvent("CSM_KEY_ON", ch, idx, sl);
    }

    private void updateVolOut(Operator sl) {
        // GPGX behavior (ym2612.c:1095-1098): Always calculate vol_out based on
        // SSG-EG inversion state regardless of envelope phase. This allows the
        // feedback buffer (opOut[]) to naturally decay when operators go quiet.
        // Previous code had extra guards: && sl.curEnv != EnvState.RELEASE && sl.curEnv != EnvState.IDLE
        // These are NOT in GPGX and caused metallic echo artifacts on ring sounds.
        boolean invert = sl.ssgEnabled
                && ((sl.ssgn ^ (sl.ssgEg & 0x04)) != 0);
        if (invert) {
            sl.volOut = ((SSG_THRESHOLD - sl.volume) & MAX_ATT_INDEX) + sl.tll;
        } else {
            sl.volOut = sl.volume + sl.tll;
        }
    }

    private void updateSsgEg() {
        for (int ch = 0; ch < 6; ch++) {
            Channel channel = channels[ch];
            for (int op = 0; op < 4; op++) {
                Operator sl = channel.ops[op];
                if ((sl.ssgEg & 0x08) != 0 && sl.volume >= SSG_THRESHOLD
                        && sl.curEnv != EnvState.RELEASE && sl.curEnv != EnvState.IDLE) {
                    if ((sl.ssgEg & 0x01) != 0) {
                        if ((sl.ssgEg & 0x02) != 0) {
                            sl.ssgn = 4;
                        }
                        if (sl.curEnv != EnvState.ATTACK && ((sl.ssgn ^ (sl.ssgEg & 0x04)) == 0)) {
                            sl.volume = MAX_ATT_INDEX;
                        }
                    } else {
                        if ((sl.ssgEg & 0x02) != 0) {
                            sl.ssgn ^= 4;
                        } else {
                            sl.fCnt = 0;
                        }
                        if (sl.curEnv != EnvState.ATTACK) {
                            if ((sl.ar + sl.ksr) < 94) {
                                sl.curEnv = EnvState.ATTACK;
                            } else {
                                sl.volume = 0;
                                sl.curEnv = (sl.slReg == 0) ? EnvState.DECAY2 : EnvState.DECAY1;
                            }
                        }
                    }
                    updateVolOut(sl);
                }
            }
        }
    }

    private void advanceLfo() {
        if (lfoTimerOverflow != 0) {
            lfoTimer++;
            if (lfoTimer >= lfoTimerOverflow) {
                lfoTimer = 0;
                lfoCnt = (lfoCnt + 1) & LFO_MASK;
                if (lfoCnt < 64) {
                    lfoAm = (lfoCnt ^ 63) << 1;
                } else {
                    lfoAm = (lfoCnt & 63) << 1;
                }
                lfoPm = lfoCnt >> 2;
            }
        }
    }

    private void keyOffCsm(Channel ch, int idx) {
        Operator sl = ch.ops[idx];
        if (!sl.key && sl.curEnv != EnvState.RELEASE && sl.curEnv != EnvState.IDLE) {
            sl.curEnv = EnvState.RELEASE;
            if (sl.ssgEnabled) {
                if (((sl.ssgn ^ (sl.ssgEg & 0x04)) != 0)) {
                    sl.volume = (SSG_THRESHOLD - sl.volume) & MAX_ATT_INDEX;
                }
                if (sl.volume >= SSG_THRESHOLD) {
                    sl.volume = MAX_ATT_INDEX;
                    sl.curEnv = EnvState.IDLE;
                }
            }
            updateVolOut(sl);
        }
        traceKeyEvent("CSM_KEY_OFF", ch, idx, sl);
    }

    private void traceKeyEvent(String event, Channel ch, int opIdx, Operator sl) {
        if (!TRACE_KEY_EVENTS || traceEvents >= TRACE_EVENT_LIMIT) {
            return;
        }
        int chIdx = channelIndex(ch);
        if (TRACE_CHANNEL >= 0 && TRACE_CHANNEL != chIdx) {
            return;
        }
        traceEvents++;
        System.out.println("YM2612 " + event
                + " ch=" + chIdx
                + " op=" + opIdx
                + " key=" + sl.key
                + " env=" + sl.curEnv
                + " vol=" + sl.volume
                + " volOut=" + sl.volOut
                + " tl=" + sl.tl
                + " ssg=0x" + Integer.toHexString(sl.ssgEg)
                + " ar=" + sl.ar
                + " ksr=" + sl.ksr);
    }

    private int channelIndex(Channel ch) {
        for (int i = 0; i < channels.length; i++) {
            if (channels[i] == ch) {
                return i;
            }
        }
        return -1;
    }

    private void tickTimers() {
        for (int i = 0; i < 1; i++) {
            csmKeyFlag <<= 1;
            if ((mode & 0x01) != 0) {
                timerACount--;
                if (timerACount <= 0) {
                    if ((mode & 0x04) != 0) {
                        status |= FM_STATUS_TIMERA_BIT_MASK;
                    }
                    timerACount = timerALoad;
                    if ((mode & 0xC0) == 0x80) {
                        csmKeyOn();
                    }
                }
            }
            if ((csmKeyFlag & 2) != 0) {
                csmKeyOff();
            }
        }
        if ((mode & 0x02) != 0) {
            timerBCount -= 1;
            if (timerBCount <= 0) {
                if ((mode & 0x08) != 0) {
                    status |= FM_STATUS_TIMERB_BIT_MASK;
                }
                do {
                    timerBCount += timerBLoad;
                } while (timerBCount <= 0);
            }
        }
        if (busyCycles > 0) {
            busyCycles = Math.max(0, busyCycles - (YM_CYCLES_PER_SAMPLE * 1));
        }
    }
}
