package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

/**
 * YM2612 Emulator
 * <p>
 * Ported from SMPSPlay's libvgm/GPGX YM2612 core (ym2612.c).
 */
public class Ym2612Chip {
    private static final double CLOCK = 7670453.0;
    private static final double OUTPUT_RATE = 44100.0;
    // GPGX: Internal rate is CLOCK/144 (~53267 Hz)
    private static final double INTERNAL_RATE = CLOCK / 144.0;  // 53267.034...
    // Resampling ratio for converting internal to output rate
    private static final double RESAMPLE_RATIO = INTERNAL_RATE / OUTPUT_RATE;  // ~1.208

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
    private static final int LFO_HBITS = 7;  // 128 = 2^7
    private static final int LFO_LBITS = 21; // 28 - LFO_HBITS

    private static final int SIN_LEN = 1 << SIN_HBITS; // 1024
    private static final int ENV_LEN = 1 << ENV_HBITS; // 1024
    private static final int LFO_LEN = 1 << LFO_HBITS; // 128 (GPGX-style)

    private static final int TL_RES_LEN = 256;
    private static final int TL_TAB_LEN = 13 * 2 * TL_RES_LEN;

    private static final int SIN_MASK = SIN_LEN - 1;
    private static final int ENV_MASK = ENV_LEN - 1;
    private static final int LFO_MASK = LFO_LEN - 1;

    // envelope step in dB (GPGX: 128.0 / ENV_LEN)
    private static final double ENV_STEP = 128.0 / (1 << ENV_BITS);

    private static final int ENV_ATTACK = 0;
    private static final int ENV_DECAY = ENV_LEN << ENV_LBITS;
    private static final int ENV_END = (ENV_LEN * 2) << ENV_LBITS;

    // Output bits logic
    private static final int OUT_BITS = 14;
    private static final int OUT_SHIFT = 0;
    // GPGX-style output clipping: ±8191 (asymmetric: +8191 / -8192)
    // Max pre-clip output is ~16383; peaks above 8191 will clip.
    private static final int LIMIT_CH_OUT_POS = 8191;
    private static final int LIMIT_CH_OUT_NEG = -8192;

    // GPGX ENV_QUIET threshold: when envelope exceeds this, operator output is forced to 0.
    // This causes feedback buffer to naturally decay when notes fade out.
    private static final int ENV_QUIET = TL_TAB_LEN >> 3;

    // Rate constants
    private static final int AR_RATE = 399128;
    private static final int DR_RATE = 5514396;

    // LFO constants
    private static final int LFO_FMS_LBITS = 9;
    private static final int LFO_FMS_BASE = (int) (0.05946309436 * 0.0338 * (double) (1 << LFO_FMS_LBITS));

    // Tables
    private static final int[] SIN_TAB = new int[SIN_LEN]; // indices into TL_TAB (includes sign bit)
    private static final int[] TL_TAB = new int[TL_TAB_LEN]; // signed 14-bit output values
    private static final int[] ENV_TAB = new int[2 * ENV_LEN + 8];
    private static final int[] DECAY_TO_ATTACK = new int[ENV_LEN];
    private static final int[] FINC_TAB = new int[2048];
    private static final int[] AR_TAB = new int[128];
    private static final int[] DR_TAB = new int[96];
    private static final int[] SL_TAB = new int[16];
    private static final int[][] DT_TAB = new int[8][32];

    private static final int[] LFO_ENV_TAB = new int[LFO_LEN];
    private static final int[] LFO_FREQ_TAB = new int[LFO_LEN];
    private static final int[] LFO_INC_TAB = new int[8];

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

    // EG_RATE_SELECT: Maps rate value (0-63) to base index in EG_INC
    // Pattern from GPGX: rates cycle through rows 0-3, then 4-7, 8-11, 12-15, 16
    private static final int[] EG_RATE_SELECT = new int[64];
    // EG_RATE_SHIFT: Maps rate value (0-63) to shift for egCnt gating
    // Shift decreases as rate increases (faster rates update more often)
    private static final int[] EG_RATE_SHIFT = new int[64];

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

    private static final int[] LFO_AMS_TAB = {31, 4, 1, 0};
    private static final int[] LFO_FMS_TAB = {
            LFO_FMS_BASE * 0, LFO_FMS_BASE * 1,
            LFO_FMS_BASE * 2, LFO_FMS_BASE * 3,
            LFO_FMS_BASE * 4, LFO_FMS_BASE * 6,
            LFO_FMS_BASE * 12, LFO_FMS_BASE * 24
    };

    // GPGX: When running at internal rate, frequency multiplier is 1.0
    private static final double YM2612_FREQUENCY = 1.0;
    // Operator slot order matches ym2612.c (S0,S1,S2,S3) mapping to ops[0,2,1,3]
    private static final int[] OP_TO_SLOT = {0, 2, 1, 3};

    static {
        // TL_TAB generation (GPGX linear power table)
        for (int x = 0; x < TL_RES_LEN; x++) {
            double m = (1 << 16) / StrictMath.pow(2.0, (x + 1) * (ENV_STEP / 4.0) / 8.0);
            m = StrictMath.floor(m);
            int n = (int) m;
            n >>= 4;
            if ((n & 1) != 0) n = (n >> 1) + 1;
            else n >>= 1;
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
            if ((n & 1) != 0) n = (n >> 1) + 1;
            else n >>= 1;
            SIN_TAB[i] = n * 2 + (m >= 0.0 ? 0 : 1);
        }

        // GPGX LFO Table: 128-step inverted triangle
        // LFO_ENV_TAB: AM modulation (0-126, inverted triangle)
        // LFO_FREQ_TAB: PM modulation (0-31, 4x slower than AM)
        for (int i = 0; i < LFO_LEN; i++) {  // LFO_LEN = 128
            // Inverted triangle for AM: 126 -> 0 -> 126
            if (i < 64) {
                LFO_ENV_TAB[i] = (63 - i) << 1;  // 126, 124, ... 2, 0
            } else {
                LFO_ENV_TAB[i] = (i - 64) << 1;  // 0, 2, 4, ... 124, 126
            }

            // PM frequency modulation: triangle, but 4x slower than AM
            // Values range 0-31 for frequency modulation
            if (i < 64) {
                LFO_FREQ_TAB[i] = 32 - (i >> 1);  // 32 -> 0
            } else {
                LFO_FREQ_TAB[i] = (i - 64) >> 1;  // 0 -> 31
            }
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

        // Decay to Attack conversion
        for (int i = 0, j = ENV_LEN - 1; i < ENV_LEN; i++) {
            while (j > 0 && ENV_TAB[j] < i) j--;
            DECAY_TO_ATTACK[i] = j << ENV_LBITS;
        }

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

        // FINC Table
        for (int i = 0; i < 2048; i++) {
            double x = (double) i * YM2612_FREQUENCY;
            x *= (double) (1 << (SIN_LBITS + SIN_HBITS - (21 - 7))); // 12
            x *= 0.5; // MUL = value * 2 in the hardware step, so pre-divide here
            FINC_TAB[i] = (int) x;
        }

        // AR/DR Tables
        for (int i = 0; i < 60; i++) {
            double x = YM2612_FREQUENCY;
            x *= 1.0 + ((i & 3) * 0.25);
            x *= (double) (1 << (i >> 2));
            x *= (double) (ENV_LEN << ENV_LBITS);
            AR_TAB[i + 4] = (int) (x / AR_RATE);
            DR_TAB[i + 4] = (int) (x / DR_RATE);
        }
        for (int i = 64; i < 96; i++) {
            AR_TAB[i] = AR_TAB[63];
            DR_TAB[i] = DR_TAB[63];
        }
        for (int i = 0; i < 4; i++) {
            AR_TAB[i] = 0;
            DR_TAB[i] = 0;
        }

        // Detune Table
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 32; k++) {
                double x;
                x = (double) DT_DEF_TAB[(i << 5) + k] * YM2612_FREQUENCY * (double) (1 << (SIN_LBITS + SIN_HBITS - 21));
                DT_TAB[i + 0][k] = (int) x;
                DT_TAB[i + 4][k] = (int) -x;
            }
        }

        // LFO Inc Table - use internal rate for GPGX compatibility
        double lfoBase = (double) (1 << (LFO_HBITS + LFO_LBITS)) / INTERNAL_RATE;
        LFO_INC_TAB[0] = (int) (3.98 * lfoBase);
        LFO_INC_TAB[1] = (int) (5.56 * lfoBase);
        LFO_INC_TAB[2] = (int) (6.02 * lfoBase);
        LFO_INC_TAB[3] = (int) (6.37 * lfoBase);
        LFO_INC_TAB[4] = (int) (6.88 * lfoBase);
        LFO_INC_TAB[5] = (int) (9.63 * lfoBase);
        LFO_INC_TAB[6] = (int) (48.1 * lfoBase);
        LFO_INC_TAB[7] = (int) (72.2 * lfoBase);

        // GPGX EG Rate Tables initialization - copied from ym2612.c
        // The pattern matches GPGX's eg_rate_select and eg_rate_shift tables
        for (int rate = 0; rate < 64; rate++) {
            if (rate < 2) {
                // Rates 0-1: dummy (zero increment from row 18)
                EG_RATE_SELECT[rate] = 18 * 8;  // Row 18 = zero increment
                EG_RATE_SHIFT[rate] = 11;
            } else if (rate < 4) {
                // Rates 2-3: use rows 2-3
                EG_RATE_SELECT[rate] = (rate & 3) * 8;
                EG_RATE_SHIFT[rate] = 11;
            } else if (rate < 48) {
                // Rates 4-47: cycle through rows 0-3 based on (rate & 3)
                EG_RATE_SELECT[rate] = (rate & 3) * 8;
                // Shift: 11 - ((rate - 4) / 4) = 10 for rates 4-7, 9 for 8-11, etc.
                EG_RATE_SHIFT[rate] = 11 - ((rate - 4) >> 2) - 1;
                if (EG_RATE_SHIFT[rate] < 0) EG_RATE_SHIFT[rate] = 0;
            } else if (rate < 52) {
                // Rates 48-51 (rate 12): use rows 4-7
                EG_RATE_SELECT[rate] = (4 + (rate & 3)) * 8;
                EG_RATE_SHIFT[rate] = 0;
            } else if (rate < 56) {
                // Rates 52-55 (rate 13): use rows 8-11
                EG_RATE_SELECT[rate] = (8 + (rate & 3)) * 8;
                EG_RATE_SHIFT[rate] = 0;
            } else if (rate < 60) {
                // Rates 56-59 (rate 14): use rows 12-15
                EG_RATE_SELECT[rate] = (12 + (rate & 3)) * 8;
                EG_RATE_SHIFT[rate] = 0;
            } else {
                // Rates 60-63 (rate 15): use row 16 (max rate)
                EG_RATE_SELECT[rate] = 16 * 8;
                EG_RATE_SHIFT[rate] = 0;
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
    // Base overhead = 295 cycles per 2 samples, djnz loops add 13*2=26 cycles per rateByte
    private static final double DAC_BASE_CYCLES = 295.0;
    private static final double DAC_LOOP_CYCLES = 26.0;
    private static final double DAC_LOOP_SAMPLES = 2.0;
    private static final double Z80_CLOCK = 3579545.0;
    private static final double DAC_GAIN = 128.0;
    private boolean dacInterpolate = true;
    private boolean dacHighpassEnabled = false;
    private int dac_highpass;
    private static final int HIGHPASS_FRACT = 15;
    private static final int HIGHPASS_SHIFT = 9;

    // Resampling state (internal 53kHz -> output 44.1kHz)
    private double resampleAccum = 0.0;
    private int lastLeft = 0, lastRight = 0;
    private int prevLeft = 0, prevRight = 0;

    private int status;
    private int mode;
    private double busyCycles;
    private static final int FM_STATUS_BUSY_BIT_MASK = 0x80;
    private static final int FM_STATUS_TIMERA_BIT_MASK = 0x01;
    private static final int FM_STATUS_TIMERB_BIT_MASK = 0x02;
    private static final int BUSY_CYCLES_DATA = 47;
    private static final double YM_CYCLES_PER_SAMPLE = (CLOCK / 6.0) / INTERNAL_RATE;

    private static final int TIMER_BASE_INT = (int) (YM2612_FREQUENCY * 4096.0);

    private int timerACount;
    private int timerBCount;
    private int timerALoad;
    private int timerBLoad;
    private boolean timerAEnabled;
    private boolean timerBEnabled;
    private int timerAPeriod;
    private int timerBPeriod;

    private int lfoCnt;
    private int lfoInc;

    // GPGX EG counter: 12-bit, cycles 1-4095, skips 0
    // EG only advances every 3 samples (frequency = chipclock/144/3)
    private int egCnt = 1;
    private int egTimer = 0;  // Counts 0, 1, 2, then triggers egCnt increment

    private boolean channel3SpecialMode;

    private enum EnvState { ATTACK, DECAY1, DECAY2, RELEASE, IDLE }

    private static class Operator {
        int dt1;
        double mul;
        int tl;
        int tll;
        int rs, ar;
        int am, d1r;
        int d2r;
        int d1l, rr;
        int ssgEg;
        int ksr;

        int fCnt;
        int fInc;
        int eCnt;
        int eInc;
        int eCmp;

        // GPGX EG rate cache - precomputed shift/select for each envelope phase
        int egShAr, egSelAr;    // Attack rate shift/select
        int egShD1r, egSelD1r;  // Decay1 rate shift/select
        int egShD2r, egSelD2r;  // Decay2/Sustain rate shift/select
        int egShRr, egSelRr;    // Release rate shift/select
        int volume;             // GPGX-style volume (0 = max, 1023 = silent)
        int volOut;             // GPGX-style cached vol_out = volume + tll
        int slReg;              // Raw 4-bit sustain level register value (0-15)
        EnvState curEnv = EnvState.RELEASE;

        int eIncA, eIncD, eIncS, eIncR;
        boolean ssgEnabled;

        int chgEnM = 0;
        boolean amsOn;
        int ams;

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

        int feedback;
        int algo;
        int ams, fms;
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

    public Ym2612Chip() {
        for (int i = 0; i < 6; i++) {
            channels[i] = new Channel();
        }
        reset();
    }

    public void reset() {
        status = 0;
        mode = 0;
        busyCycles = 0;
        channel3SpecialMode = false;

        timerACount = 0;
        timerBCount = 0;
        timerALoad = 0;
        timerBLoad = 0;
        timerAEnabled = false;
        timerBEnabled = false;

        lfoCnt = 0;
        lfoInc = 0;

        // Reset GPGX EG counter and timer
        egCnt = 1;
        egTimer = 0;

        dacEnabled = false;
        dac_highpass = 0;

        // Reset resampling state
        resampleAccum = 0.0;
        lastLeft = lastRight = 0;
        prevLeft = prevRight = 0;

        for (Channel ch : channels) {
            ch.fNum = 0;
            ch.block = 0;
            ch.kCode = 0;
            ch.feedback = 31;  // fb=0 means no feedback (large shift effectively disables it)
            ch.algo = 0;
            ch.ams = 0;
            ch.fms = 0;
            ch.leftMask = 0xFFFFFFFF;
            ch.rightMask = 0xFFFFFFFF;
            ch.memValue = 0;

            for (int i = 0; i < 4; i++) {
                ch.opOut[i] = 0;
                Operator o = ch.ops[i];
                o.dt1 = 0; o.mul = 0.5; o.tl = 0; o.tll = 0;
                o.ksr = 0; o.ar = 0; o.am = 0; o.d1r = 0;
                o.d2r = 0; o.d1l = 0; o.rr = 0;
                o.ssgEg = 0;
                o.fCnt = 0;
                o.eCnt = ENV_END;
                o.eInc = 0;
                o.eCmp = 0;
                o.curEnv = EnvState.RELEASE;
                o.chgEnM = 0;
                o.key = false;
                // GPGX EG state
                o.volume = 1023;  // Start silent
                o.volOut = 1023 + o.tll;  // Cache vol_out
                o.slReg = 0;      // Sustain level register
                o.egShAr = o.egSelAr = 0;
                o.egShD1r = o.egSelD1r = 0;
                o.egShD2r = o.egSelD2r = 0;
                o.egShRr = o.egSelRr = 0;
            }
        }
    }

    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 6) mutes[ch] = mute;
    }

    /**
     * Silence all FM channels (ROM: zFMSilenceAll).
     * Key-off all channels, then write 0xFF to registers 0x30-0x8F on both ports.
     */
    public void silenceAll() {
        // Key-off all 6 channels (reg 0x28, value = channel with no operator keys)
        for (int ch = 0; ch < 3; ch++) {
            write(0, 0x28, ch);         // Part I channels 0-2
            write(0, 0x28, ch | 0x04);  // Part II channels 3-5
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
     *
     * Unlike register writes, this takes effect immediately without needing
     * audio samples to be rendered.
     *
     * The feedback buffer (opOut) is reset to ensure multi-channel SFX
     * (like the Signpost which uses FM4+FM5) start with identical state.
     * Without this reset, residual feedback from different music channels
     * causes the SFX channels to have different waveforms, creating
     * a phaser/chorus effect.
     */
    public void forceSilenceChannel(int chIdx) {
        if (chIdx < 0 || chIdx >= 6) return;
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
            sl.volume = 1023;  // GPGX: silent
            sl.volOut = 1023 + sl.tll;  // Update cache
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
        if (busyCycles > 0) status |= FM_STATUS_BUSY_BIT_MASK;
        else status &= ~FM_STATUS_BUSY_BIT_MASK;
        return status;
    }

    public void write(int port, int reg, int val) {
        busyCycles = BUSY_CYCLES_DATA;
        int addr = reg;
        if (port == 1) addr += 0x100;

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

    private void writeYm(int addr, int val) {
        switch (addr) {
            case 0x22:
                if ((val & 0x08) != 0) {
                    lfoInc = LFO_INC_TAB[val & 7];
                } else {
                    lfoInc = 0;
                    lfoCnt = 0;
                }
                break;
            case 0x24:
                timerAPeriod = (timerAPeriod & 0x03) | (val << 2);
                timerALoad = (1024 - timerAPeriod) << 12;
                if (timerAEnabled) timerACount = timerALoad;
                break;
            case 0x25:
                timerAPeriod = (timerAPeriod & 0x3FC) | (val & 0x03);
                timerALoad = (1024 - timerAPeriod) << 12;
                if (timerAEnabled) timerACount = timerALoad;
                break;
            case 0x26:
                timerBPeriod = val & 0xFF;
                timerBLoad = (256 - timerBPeriod) << 16;
                if (timerBEnabled) timerBCount = timerBLoad;
                break;
            case 0x27:
                if (((mode ^ val) & 0x40) != 0) {
                    channels[2].ops[0].fInc = -1;
                }
                status &= (~val >> 4) & (val >> 2);
                mode = val;
                channel3SpecialMode = (val & 0x40) != 0;
                timerAEnabled = (val & 0x01) != 0;
                timerBEnabled = (val & 0x02) != 0;
                if (timerAEnabled) timerACount = timerALoad;
                if (timerBEnabled) timerBCount = timerBLoad;
                if ((val & 0x80) != 0) {
                    csmKeyControl();
                }
                break;
            case 0x28:
                int chIdx = val & 0x03;
                if (chIdx == 3) return;
                if ((val & 0x04) != 0) chIdx += 3;
                Channel ch = channels[chIdx];
                int mask = (val >> 4) & 0xF;
                if ((mask & 1) != 0) keyOn(ch, 0); else keyOff(ch, 0);
                if ((mask & 2) != 0) keyOn(ch, 2); else keyOff(ch, 2);
                if ((mask & 4) != 0) keyOn(ch, 1); else keyOff(ch, 1);
                if ((mask & 8) != 0) keyOn(ch, 3); else keyOff(ch, 3);
                break;
            case 0x2A:
                dacLatchedValue = (val & 0xFF) - 128;
                dacHasLatched = true;
                currentDacSampleId = -1;
                break;
            case 0x2B:
                dacEnabled = (val & 0x80) != 0;
                if (!dacEnabled) stopDac();
                break;
        }
    }

    private void writeSlot(int addr, int val) {
        int nch = addr & 3;
        if (nch == 3) return;
        if ((addr & 0x100) != 0) nch += 3;

        int[] regToOp = {0, 2, 1, 3};
        int regSlot = (addr >> 2) & 3;
        int opIdx = regToOp[regSlot];

        Channel ch = channels[nch];
        Operator sl = ch.ops[opIdx];

        switch (addr & 0xF0) {
            case 0x30:
                int mulVal = val & 0x0F;
                sl.mul = (mulVal == 0) ? 0.5 : (double) mulVal;
                sl.dt1 = (val >> 4) & 7;
                ch.ops[0].fInc = -1;
                break;
            case 0x40:
                sl.tl = val & 0x7F;
                if ((ENV_HBITS - 7) < 0) sl.tll = sl.tl >> (7 - ENV_HBITS);
                else sl.tll = sl.tl << (ENV_HBITS - 7);
                // GPGX: update vol_out cache when TL changes
                sl.volOut = sl.volume + sl.tll;
                break;
            case 0x50:
                sl.ar = (val & 0x1F) != 0 ? (val & 0x1F) << 1 : 0;
                sl.rs = 3 - (val >> 6);
                ch.ops[0].fInc = -1;
                sl.eIncA = AR_TAB[sl.ar + sl.ksr];
                if (sl.curEnv == EnvState.ATTACK) sl.eInc = sl.eIncA;
                updateEgRateCache(sl);
                break;
            case 0x60:
                sl.amsOn = (val & 0x80) != 0;
                sl.ams = sl.amsOn ? ch.ams : 31;
                sl.d1r = (val & 0x1F) != 0 ? (val & 0x1F) << 1 : 0;
                sl.eIncD = DR_TAB[sl.d1r + sl.ksr];
                if (sl.curEnv == EnvState.DECAY1) sl.eInc = sl.eIncD;
                updateEgRateCache(sl);
                break;
            case 0x70:
                sl.d2r = (val & 0x1F) != 0 ? (val & 0x1F) << 1 : 0;
                sl.eIncS = DR_TAB[sl.d2r + sl.ksr];
                if (sl.curEnv == EnvState.DECAY2) sl.eInc = sl.eIncS;
                updateEgRateCache(sl);
                break;
            case 0x80:
                sl.slReg = (val >> 4) & 0x0F;  // Store raw 4-bit SL value
                sl.d1l = SL_TAB[sl.slReg];
                sl.rr = ((val & 0xF) << 2) + 2;
                sl.eIncR = DR_TAB[sl.rr + sl.ksr];
                if (sl.curEnv == EnvState.RELEASE) sl.eInc = sl.eIncR;
                updateEgRateCache(sl);
                break;
            case 0x90:
                sl.ssgEg = val & 0x0F;
                sl.ssgEnabled = (val & 0x08) != 0;
                break;
        }
    }

    private void writeChannel(int addr, int val) {
        int nch = addr & 3;
        if (nch == 3) return;
        if ((addr & 0x100) != 0) nch += 3;
        Channel ch = channels[nch];

        switch (addr & 0xFC) {
            case 0xA0:
                ch.fNum = (ch.fNum & 0x700) | (val & 0xFF);
                ch.kCode = (ch.block << 2) | FKEY_TAB[ch.fNum >> 7];
                ch.ops[0].fInc = -1;
                break;
            case 0xA4:
                ch.fNum = (ch.fNum & 0xFF) | ((val & 0x07) << 8);
                ch.block = (val >> 3) & 7;
                ch.kCode = (ch.block << 2) | FKEY_TAB[ch.fNum >> 7];
                ch.ops[0].fInc = -1;
                break;
            case 0xA8:
                if (nch == 2) {
                    int slot = (addr & 3) + 1;
                    ch.slotFnum[slot] = (ch.slotFnum[slot] & 0x700) | (val & 0xFF);
                    ch.slotKCode[slot] = (ch.slotBlock[slot] << 2) | FKEY_TAB[ch.slotFnum[slot] >> 7];
                    ch.ops[0].fInc = -1;
                }
                break;
            case 0xAC:
                if (nch == 2) {
                    int slot = (addr & 3) + 1;
                    ch.slotFnum[slot] = (ch.slotFnum[slot] & 0xFF) | ((val & 0x07) << 8);
                    ch.slotBlock[slot] = (val >> 3) & 7;
                    ch.slotKCode[slot] = (ch.slotBlock[slot] << 2) | FKEY_TAB[ch.slotFnum[slot] >> 7];
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
                ch.ams = LFO_AMS_TAB[(val >> 4) & 3];
                ch.fms = LFO_FMS_TAB[val & 7];
                for (Operator op : ch.ops) {
                    op.ams = op.amsOn ? ch.ams : 31;
                }
                break;
        }
    }

    private void calcFIncSlot(Operator sl, int finc, int kc) {
        sl.fInc = (int) ((finc + DT_TAB[sl.dt1][kc]) * sl.mul);
        int ksr = kc >> sl.rs;
        if (sl.ksr != ksr) {
            sl.ksr = ksr;
            sl.eIncA = AR_TAB[sl.ar + ksr];
            sl.eIncD = DR_TAB[sl.d1r + ksr];
            sl.eIncS = DR_TAB[sl.d2r + ksr];
            sl.eIncR = DR_TAB[sl.rr + ksr];

            if (sl.curEnv == EnvState.ATTACK) sl.eInc = sl.eIncA;
            else if (sl.curEnv == EnvState.DECAY1) sl.eInc = sl.eIncD;
            else if (sl.eCnt < ENV_END) {
                if (sl.curEnv == EnvState.DECAY2) sl.eInc = sl.eIncS;
                else if (sl.curEnv == EnvState.RELEASE) sl.eInc = sl.eIncR;
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
        int rateAr = Math.min(63, sl.ar + sl.ksr);
        sl.egShAr = EG_RATE_SHIFT[rateAr];
        sl.egSelAr = EG_RATE_SELECT[rateAr];

        // Decay1 rate
        int rateD1r = Math.min(63, sl.d1r + sl.ksr);
        sl.egShD1r = EG_RATE_SHIFT[rateD1r];
        sl.egSelD1r = EG_RATE_SELECT[rateD1r];

        // Decay2/Sustain rate
        int rateD2r = Math.min(63, sl.d2r + sl.ksr);
        sl.egShD2r = EG_RATE_SHIFT[rateD2r];
        sl.egSelD2r = EG_RATE_SELECT[rateD2r];

        // Release rate
        int rateRr = Math.min(63, sl.rr + sl.ksr);
        sl.egShRr = EG_RATE_SHIFT[rateRr];
        sl.egSelRr = EG_RATE_SELECT[rateRr];
    }

    private void calcFIncChannel(Channel ch) {
        if (channel3SpecialMode && ch == channels[2]) {
            for (int i=0; i<4; i++) {
                int fnum = i==0 ? ch.fNum : ch.slotFnum[i];
                int block = i==0 ? ch.block : ch.slotBlock[i];
                int kc = i==0 ? ch.kCode : ch.slotKCode[i];
                int finc = FINC_TAB[fnum] >> (7 - block);
                calcFIncSlot(ch.ops[i], finc, kc);
            }
        } else {
            int finc = FINC_TAB[ch.fNum] >> (7 - ch.block);
            int kc = ch.kCode;
            calcFIncSlot(ch.ops[0], finc, kc);
            calcFIncSlot(ch.ops[1], finc, kc);
            calcFIncSlot(ch.ops[2], finc, kc);
            calcFIncSlot(ch.ops[3], finc, kc);
        }
    }

    private void keyOn(Channel ch, int idx) {
        Operator sl = ch.ops[idx];
        // GPGX-style: use separate key flag instead of checking envelope state.
        // This properly gates key-on to only trigger on 0->1 transitions.
        if (!sl.key) {
            // Restart phase generator (GPGX: SLOT->phase = 0)
            sl.fCnt = 0;

            // Reset SSG-EG inversion (GPGX: SLOT->ssgn = 0)
            // Our SSG-EG uses different approach but reset is still needed
            sl.ssgEg &= 0x0F; // Keep SSG-EG mode, clear any runtime state

            // GPGX: Start attack from current volume level (decay-to-attack behavior)
            // Volume is already set from previous note, so attack will ramp from there
            sl.curEnv = EnvState.ATTACK;

            // Keep legacy eCnt in sync for any code that still uses it
            sl.eCnt = ENV_ATTACK;
            sl.chgEnM = 0xFFFFFFFF;
            sl.eInc = sl.eIncA;
            sl.eCmp = ENV_DECAY;
            // Note: Do NOT reset opOut (feedback history) here.
            // libvgm does not clear feedback on keyOn.
        }
        sl.key = true;
    }

    private void keyOff(Channel ch, int idx) {
        Operator sl = ch.ops[idx];
        // GPGX-style: only transition to RELEASE if key was on and not already releasing
        if (sl.key) {
            if (sl.curEnv != EnvState.RELEASE) {
                if (sl.eCnt < ENV_DECAY) {
                    sl.eCnt = (ENV_TAB[sl.eCnt >> ENV_LBITS] << ENV_LBITS) + ENV_DECAY;
                }
                sl.eInc = sl.eIncR;
                sl.eCmp = ENV_END;
                sl.curEnv = EnvState.RELEASE;
            }
        }
        sl.key = false;
    }

    private void envNextEvent(Operator sl, EnvState cur) {
        switch (cur) {
            case ATTACK:
                sl.eCnt = ENV_DECAY;
                sl.eInc = sl.eIncD;
                sl.eCmp = sl.d1l;
                sl.curEnv = EnvState.DECAY1;
                break;
            case DECAY1:
                sl.eCnt = sl.d1l;
                sl.eInc = sl.eIncS;
                sl.eCmp = ENV_END;
                sl.curEnv = EnvState.DECAY2;
                break;
            case DECAY2:
                if (sl.ssgEnabled) {
                    handleSsgEnd(sl);
                } else {
                    sl.eCnt = ENV_END;
                    sl.eInc = 0;
                    sl.eCmp = ENV_END + 1;
                }
                break;
            case RELEASE:
                sl.eCnt = ENV_END;
                sl.eInc = 0;
                sl.eCmp = ENV_END + 1;
                break;
            default:
                break;
        }
    }

    private void handleSsgEnd(Operator sl) {
        if ((sl.ssgEg & 8) != 0) {
            if ((sl.ssgEg & 1) != 0) {
                sl.eCnt = ENV_END;
                sl.eInc = 0;
                sl.eCmp = ENV_END + 1;
                sl.volume = 1023;
                sl.volOut = 1023 + sl.tll;  // Update cache
            } else {
                sl.eCnt = 0;
                sl.eInc = sl.eIncA;
                sl.eCmp = ENV_DECAY;
                sl.curEnv = EnvState.ATTACK;
                sl.volume = 0;
                sl.volOut = sl.tll;  // Update cache (volume=0)
            }
            sl.ssgEg ^= (sl.ssgEg & 2) << 1;
        } else {
            sl.eCnt = ENV_END;
            sl.eInc = 0;
            sl.eCmp = ENV_END + 1;
            sl.volume = 1023;
            sl.volOut = 1023 + sl.tll;  // Update cache
        }
    }

    // Sustain level lookup table: maps 4-bit register value to GPGX volume scale (0-1023)
    // SL reg values 0-14 map to 0-960 in 64-step increments, SL=15 maps to 1023 (max/silence)
    private static final int[] SL_VOL_TAB = new int[16];
    static {
        for (int i = 0; i < 15; i++) {
            SL_VOL_TAB[i] = i * 64;  // 0, 64, 128, ... 896
        }
        SL_VOL_TAB[15] = 1023;  // Max attenuation = silence
    }

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
                        sl.eCnt = ENV_DECAY;  // Keep legacy eCnt in sync
                    }
                    // GPGX: update vol_out cache when volume changes
                    sl.volOut = sl.volume + sl.tll;
                }
                break;

            case DECAY1:
                if ((egCnt & ((1 << sl.egShD1r) - 1)) == 0) {
                    int inc = EG_INC[sl.egSelD1r + ((egCnt >> sl.egShD1r) & 7)];
                    // Linear decay
                    sl.volume += inc;
                    // Get sustain level using stored slReg directly
                    int sustainLevel = SL_VOL_TAB[sl.slReg];
                    if (sl.volume >= sustainLevel) {
                        sl.volume = sustainLevel;
                        sl.curEnv = EnvState.DECAY2;
                        sl.eCnt = sl.d1l;  // Keep legacy eCnt in sync
                    }
                    // GPGX: update vol_out cache when volume changes
                    sl.volOut = sl.volume + sl.tll;
                }
                break;

            case DECAY2:
                if ((egCnt & ((1 << sl.egShD2r) - 1)) == 0) {
                    int inc = EG_INC[sl.egSelD2r + ((egCnt >> sl.egShD2r) & 7)];
                    // Linear decay to silence
                    sl.volume += inc;
                    if (sl.volume >= 1023) {
                        sl.volume = 1023;
                        if (sl.ssgEnabled) {
                            handleSsgEnd(sl);
                        } else {
                            sl.curEnv = EnvState.IDLE;
                            sl.eCnt = ENV_END;
                        }
                    }
                    // GPGX: update vol_out cache when volume changes
                    sl.volOut = sl.volume + sl.tll;
                }
                break;

            case RELEASE:
                if ((egCnt & ((1 << sl.egShRr) - 1)) == 0) {
                    int inc = EG_INC[sl.egSelRr + ((egCnt >> sl.egShRr) & 7)];
                    // Linear release
                    sl.volume += inc;
                    if (sl.volume >= 1023) {
                        sl.volume = 1023;
                        sl.curEnv = EnvState.IDLE;
                        sl.eCnt = ENV_END;
                    }
                    // GPGX: update vol_out cache when volume changes
                    sl.volOut = sl.volume + sl.tll;
                }
                break;

            case IDLE:
                // Do nothing
                break;
        }
    }


    /**
     * Render stereo output at 44.1kHz by generating at internal rate (~53kHz) and resampling.
     * This matches GPGX's timing accuracy while maintaining standard audio output rate.
     */
    public void renderStereo(int[] leftBuf, int[] rightBuf) {
        int outputLen = Math.min(leftBuf.length, rightBuf.length);

        for (int outIdx = 0; outIdx < outputLen; outIdx++) {
            // Generate internal samples until we have enough for this output sample
            // resampleAccum tracks our position in internal sample time
            while (resampleAccum < 1.0) {
                prevLeft = lastLeft;
                prevRight = lastRight;
                renderOneSample();  // Updates lastLeft, lastRight
                resampleAccum += 1.0 / RESAMPLE_RATIO;  // ~0.828 per internal sample
            }
            resampleAccum -= 1.0;

            // Linear interpolation between previous and current internal samples
            double t = resampleAccum * RESAMPLE_RATIO;
            int left = (int)(prevLeft + t * (lastLeft - prevLeft));
            int right = (int)(prevRight + t * (lastRight - prevRight));

            leftBuf[outIdx] += left;
            rightBuf[outIdx] += right;
        }
    }

    /**
     * Generate one internal sample at ~53kHz. Updates lastLeft/lastRight.
     */
    private void renderOneSample() {
        // GPGX: LFO values are read BEFORE update (for use in channel calc)
        // and then updated AFTER channel calculation
        int freqLfo = 0;
        int envLfo = 126;  // GPGX: 126 (max AM) when disabled

        if (lfoInc != 0) {
            int idx = (lfoCnt >> LFO_LBITS) & LFO_MASK;
            envLfo = LFO_ENV_TAB[idx];
            freqLfo = LFO_FREQ_TAB[idx];
        }

        int leftSum = 0;
        int rightSum = 0;

        // DAC output
        int dacOut = renderDac();
        Channel dacCh = channels[5];
        if (!mutes[5]) {
            if (dacCh.leftMask != 0) leftSum += dacOut;
            if (dacCh.rightMask != 0) rightSum += dacOut;
        }

        // FM channels
        for (int ch = 0; ch < 6; ch++) {
            if (mutes[ch]) continue;
            if (ch == 5 && dacEnabled) continue;
            int out = renderChannel(ch, envLfo, freqLfo);
            if (channels[ch].leftMask != 0) leftSum += out;
            if (channels[ch].rightMask != 0) rightSum += out;
        }

        lastLeft = leftSum;
        lastRight = rightSum;

        // GPGX: LFO counter updated AFTER channel calculation
        if (lfoInc != 0) {
            lfoCnt += lfoInc;
        }

        // GPGX EG timer: only advance egCnt and envelopes every 3 samples
        // This matches hardware where EG runs at chipclock/144/3
        egTimer++;
        if (egTimer >= 3) {
            egTimer = 0;
            // Simple increment, wrap from 4096 to 1 (skip 0)
            egCnt++;
            if (egCnt >= 4096) egCnt = 1;

            // Advance envelope generators for all channels (GPGX: advance_eg_channels)
            for (int ch = 0; ch < 6; ch++) {
                Channel c = channels[ch];
                for (int op = 0; op < 4; op++) {
                    advanceEgOperator(c.ops[op]);
                }
            }
        }

        tickTimers(1);
    }

    // DEBUG: Set to true to mute FM4 (channel 3) for Signpost SFX debugging
    private static final boolean DEBUG_MUTE_FM4 = false;

    private int renderChannel(int chIdx, int envLfo, int freqLfo) {
        Channel ch = channels[chIdx];

        // DEBUG: Mute FM4 to test if two-channel interference causes the reverb
        if (DEBUG_MUTE_FM4 && chIdx == 3) {
            return 0;
        }

        // Note: Do NOT early-exit for silent channels. Even when all operators are
        // at ENV_END, we must continue updating phase (fCnt) and feedback (opOut).
        // Early-exit was preventing proper feedback accumulation, causing artifacts
        // in high-feedback instruments like Signpost SFX (0xCF).

        if (ch.ops[0].fInc == -1) calcFIncChannel(ch);

        int fms = ch.fms;
        int lfoShift = fms != 0 ? (fms * freqLfo) >> (LFO_HBITS - 1) : 0;

        // GET_CURRENT_PHASE - capture fCnt BEFORE incrementing (like libvgm)
        // Slot order matches ym2612.c: S0=op0, S1=op2, S2=op1, S3=op3
        in0 = ch.ops[0].fCnt;
        in1 = ch.ops[2].fCnt;
        in2 = ch.ops[1].fCnt;
        in3 = ch.ops[3].fCnt;

        // UPDATE_PHASE - increment fCnt AFTER capturing
        for (int i=0; i<4; i++) {
            Operator op = ch.ops[i];
            int finc = op.fInc;
            if (lfoShift != 0) {
                finc += (finc * lfoShift) >> LFO_FMS_LBITS;
            }
            op.fCnt += finc;
        }

        // GET_CURRENT_ENV - read envelope values for this sample
        // Note: EG advancement happens separately every 3 samples in renderOneSample
        GET_CURRENT_ENV(ch, 0, envLfo);
        GET_CURRENT_ENV(ch, 1, envLfo);
        GET_CURRENT_ENV(ch, 2, envLfo);
        GET_CURRENT_ENV(ch, 3, envLfo);

        doAlgo(ch);

        // GPGX-style asymmetric clipping
        if (ch.out > LIMIT_CH_OUT_POS) ch.out = LIMIT_CH_OUT_POS;
        else if (ch.out < LIMIT_CH_OUT_NEG) ch.out = LIMIT_CH_OUT_NEG;


        return ch.out;
    }

    private void GET_CURRENT_ENV(Channel ch, int slot, int envLfo) {
        Operator sl = ch.ops[slot];
        // GPGX: Use cached vol_out (= volume + tll)
        // This avoids per-sample recalculation
        int env = sl.volOut;

        if ((sl.ssgEg & 4) != 0) {
             if (env > ENV_MASK) env = 0;
             else env = (env ^ ENV_MASK) + (lfoInc != 0 ? (envLfo >> sl.ams) : 0);
        } else {
             if (lfoInc != 0) {
                env += (envLfo >> sl.ams);
             }
        }

        switch(slot) {
            case 0 -> en0 = env;
            case 1 -> en1 = env;
            case 2 -> en2 = env;
            case 3 -> en3 = env;
        }
    }

    /**
     * Bounds-checked TL_TAB lookup, matching Genesis-Plus-GX op_calc behavior.
     * Returns 0 (silence) when index exceeds table bounds.
     * This ensures proper fade-out when envelope/TL gets very high.
     *
     * Phase modulation: modulation is added AFTER phase shift.
     * GPGX uses pm >> 1 for op_calc (and pm directly for op_calc1 feedback).
     */
    private static int opCalc(int phase, int env, int pm) {
        // Phase shift first, then add scaled modulation
        int idx = ((phase >> SIN_LBITS) + (pm >> 1)) & SIN_MASK;
        int p = (env << 3) + SIN_TAB[idx];
        if (p >= TL_TAB_LEN) return 0;
        return TL_TAB[p];
    }

    /**
     * opCalc without modulation (for carriers with no input modulation)
     */
    private static int opCalc(int phase, int env) {
        int p = (env << 3) + SIN_TAB[(phase >> SIN_LBITS) & SIN_MASK];
        if (p >= TL_TAB_LEN) return 0;
        return TL_TAB[p];
    }

    private void doAlgo(Channel ch) {
        // Phase values (in0..in3) are already set by renderChannel's GET_CURRENT_PHASE step.
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

        int fb = (ch.feedback < SIN_BITS) ? (ch.opOut[0] + ch.opOut[1]) >> ch.feedback : 0;
        ch.opOut[1] = ch.opOut[0];
        int s0_out = s0Quiet ? 0 : opCalc(in0, env0, fb << 1);
        ch.opOut[0] = s0_out;  // ALWAYS update - quiet means 0 propagates

        switch (ch.algo) {
            case 0: {
                // S0 -> S1 -> MEM -> S2 -> S3 (carrier)
                int m2 = ch.memValue;
                int s1_out = opCalc(in1, env1, s0_out);
                int mem = s1_out;
                int s2_out = opCalc(in2, env2, m2);
                ch.out = opCalc(in3, env3, s2_out) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 1: {
                // (S0 + S1) -> MEM -> S2 -> S3 (carrier)
                int m2 = ch.memValue;
                int s1_out = opCalc(in1, env1);  // S1 no modulation
                int mem = s0_out + s1_out;
                int s2_out = opCalc(in2, env2, m2);
                ch.out = opCalc(in3, env3, s2_out) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 2: {
                // S0 + (S1 -> MEM -> S2) -> S3 (carrier)
                int m2 = ch.memValue;
                int s1_out = opCalc(in1, env1);  // S1 no modulation
                int mem = s1_out;
                int s2_out = opCalc(in2, env2, m2);
                ch.out = opCalc(in3, env3, s0_out + s2_out) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 3: {
                // S0 -> S1 -> MEM + S2 -> S3 (carrier)
                int c2 = ch.memValue;
                int s1_out = opCalc(in1, env1, s0_out);
                int mem = s1_out;
                int s2_out = opCalc(in2, env2);  // S2 no modulation
                c2 += s2_out;
                ch.out = opCalc(in3, env3, c2) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 4: {
                // S0 -> S1 (carrier) + S2 -> S3 (carrier)
                int s1_out = opCalc(in1, env1, s0_out);
                int s2_out = opCalc(in2, env2);  // S2 no modulation
                int s3_out = opCalc(in3, env3, s2_out);
                ch.out = (s1_out + s3_out) >> OUT_SHIFT;
                ch.memValue = 0;
                break;
            }
            case 5: {
                // S0 -> (S1 + MEM -> S2 + S3) all carriers
                int m2 = ch.memValue;
                int mem = s0_out;
                int s1_out = opCalc(in1, env1, s0_out);
                int s2_out = opCalc(in2, env2, m2);
                int s3_out = opCalc(in3, env3, s0_out);
                ch.out = (s1_out + s2_out + s3_out) >> OUT_SHIFT;
                ch.memValue = mem;
                break;
            }
            case 6: {
                // S0 -> S1 (carrier) + S2 (carrier) + S3 (carrier)
                int s1_out = opCalc(in1, env1, s0_out);
                int s2_out = opCalc(in2, env2);  // S2 no modulation
                int s3_out = opCalc(in3, env3);  // S3 no modulation
                ch.out = (s1_out + s2_out + s3_out) >> OUT_SHIFT;
                ch.memValue = 0;
                break;
            }
            case 7: {
                // All carriers, no modulation
                int s1_out = opCalc(in1, env1);
                int s2_out = opCalc(in2, env2);
                int s3_out = opCalc(in3, env3);
                ch.out = (s0_out + s1_out + s2_out + s3_out) >> OUT_SHIFT;
                ch.memValue = 0;
                break;
            }
        }

    }

    public void setInstrument(int chIdx, byte[] voice) {
        if (chIdx < 0 || chIdx >= 6 || voice.length < 1) return;
        Channel ch = channels[chIdx];

        int port = (chIdx < 3) ? 0 : 1;
        int hwCh = chIdx % 3;
        int chVal = (port == 0) ? hwCh : (hwCh + 4);

        // Key off all operators before loading new voice (like Z80 zFMSilenceChannel).
        // This ensures any residual sound from previous notes is silenced.
        write(0, 0x28, 0x00 | chVal);

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
        // SMPS stores parameters as: [Op1, Op3, Op2, Op4] but YM2612 expects [Op1, Op2, Op3, Op4].
        int[] dtIdx   = {1, 3, 2, 4};
        int[] tlIdx   = {21, 23, 22, 24};
        int[] rsArIdx = {5, 7, 6, 8};
        int[] amIdx   = {9, 11, 10, 12};
        int[] d2rIdx  = {13, 15, 14, 16};
        int[] d1lRrIdx= {17, 19, 18, 20};

        // Map to YM order into temporary arrays for clarity
        int[] dt   = new int[4];
        int[] tl   = new int[4];
        int[] rsar = new int[4];
        int[] amd1 = new int[4];
        int[] d2r  = new int[4];
        int[] d1lrr= new int[4];
        for (int i = 0; i < 4; i++) {
            dt[i]    = get.applyAsInt(dtIdx[i]);
            tl[i]    = hasTl ? get.applyAsInt(tlIdx[i]) : 0;
            rsar[i]  = get.applyAsInt(rsArIdx[i]);
            amd1[i]  = get.applyAsInt(amIdx[i]);
            d2r[i]   = get.applyAsInt(d2rIdx[i]);
            d1lrr[i] = get.applyAsInt(d1lRrIdx[i]);
        }

        for (int slot = 0; slot < 4; slot++) {
            int base = hwCh;
            write(port, 0x30 + slot * 4 + base, dt[slot]);
            write(port, 0x40 + slot * 4 + base, tl[slot]);
            write(port, 0x50 + slot * 4 + base, rsar[slot]);
            write(port, 0x60 + slot * 4 + base, amd1[slot]);
            write(port, 0x70 + slot * 4 + base, d2r[slot]);
            write(port, 0x80 + slot * 4 + base, d1lrr[slot]);
            write(port, 0x90 + slot * 4 + base, 0);
        }
    }

    public void playDac(int note) {
        if (dacData == null) return;
        DacData.DacEntry entry = dacData.mapping.get(note);
        if (entry != null) {
            this.currentDacSampleId = entry.sampleId;
            this.dacPos = 0;
            int rateByte = entry.rate & 0xFF;
            double cyclesPerBlock = DAC_BASE_CYCLES + (DAC_LOOP_CYCLES * rateByte);
            double cyclesPerSample = cyclesPerBlock / DAC_LOOP_SAMPLES;
            double rateHz = Z80_CLOCK / cyclesPerSample;
            // DAC step is now relative to internal rate since renderDac() is called at ~53kHz
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
     * Defines the abstract routing for documentation/verification purposes.
     */
    public static double computeModulationInput(int algo, int opIndex, double[] opOut, double feedback) {
        if (opIndex == 0) return feedback;
        if (opIndex < 0 || opIndex >= OP_TO_SLOT.length) return 0;
        // Map op outputs into slot order (S0,S1,S2,S3) used by ym2612.c algorithms.
        double slot0 = opOut[0]; // Op1 (feedback operator)
        double slot1 = opOut[2]; // Op3
        double slot2 = opOut[1]; // Op2
        double slot3 = opOut[3]; // Op4
        int slotIndex = OP_TO_SLOT[opIndex];

        return switch (algo) {
            case 0 -> (slotIndex == 1 ? slot0 : slotIndex == 2 ? slot1 : slotIndex == 3 ? slot2 : 0);
            case 1 -> (slotIndex == 2 ? slot0 + slot1 : slotIndex == 3 ? slot2 : 0);
            case 2 -> (slotIndex == 2 ? slot1 : slotIndex == 3 ? slot0 + slot2 : 0);
            case 3 -> (slotIndex == 1 ? slot0 : slotIndex == 3 ? slot1 + slot2 : 0);
            case 4 -> (slotIndex == 1 ? slot0 : slotIndex == 3 ? slot2 : 0);
            case 5 -> (slotIndex == 1 || slotIndex == 2 || slotIndex == 3 ? slot0 : 0);
            case 6 -> (slotIndex == 1 ? slot0 : 0);
            case 7 -> 0;
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
            case 0, 1, 2, 3 -> slot3;
            case 4 -> slot1 + slot3;
            case 5, 6 -> slot1 + slot2 + slot3;
            case 7 -> slot0 + slot1 + slot2 + slot3;
            default -> slot3;
        };
    }

    private void csmKeyControl() {
        Channel ch = channels[2];
        keyOn(ch, 0);
        keyOn(ch, 1);
        keyOn(ch, 2);
        keyOn(ch, 3);
    }

    private void tickTimers(int samples) {
        if (samples <= 0) return;
        int ticks = TIMER_BASE_INT * samples;
        if (timerAEnabled) {
            timerACount -= ticks;
            if (timerACount <= 0) {
                status |= FM_STATUS_TIMERA_BIT_MASK;
                timerACount += timerALoad;
                if ((mode & 0x80) != 0) {
                    csmKeyControl();
                }
            }
        }
        if (timerBEnabled) {
            timerBCount -= ticks;
            if (timerBCount <= 0) {
                status |= FM_STATUS_TIMERB_BIT_MASK;
                timerBCount += timerBLoad;
            }
        }
        if (busyCycles > 0) {
            busyCycles = Math.max(0, busyCycles - (YM_CYCLES_PER_SAMPLE * samples));
        }
    }
}
