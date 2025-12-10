package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

/**
 * YM2612 Emulator
 * <p>
 * Ported from SMPSPlay's libvgm/GPGX YM2612 core (ym2612.c).
 */
public class Ym2612Chip {
    private static final double CLOCK = 7670453.0;
    private static final double SAMPLE_RATE = 44100.0;

    // Constants from ym2612.c
    private static final int SIN_HBITS = 12;
    private static final int SIN_LBITS = 14; // 26 - SIN_HBITS
    private static final int ENV_HBITS = 12;
    private static final int ENV_LBITS = 16; // 28 - ENV_HBITS
    private static final int LFO_HBITS = 10;
    private static final int LFO_LBITS = 18; // 28 - LFO_HBITS

    private static final int SIN_LEN = 1 << SIN_HBITS; // 4096
    private static final int ENV_LEN = 1 << ENV_HBITS; // 4096
    private static final int LFO_LEN = 1 << LFO_HBITS; // 1024

    private static final int TL_LEN = ENV_LEN * 3;

    private static final int SIN_MASK = SIN_LEN - 1;
    private static final int ENV_MASK = ENV_LEN - 1;
    private static final int LFO_MASK = LFO_LEN - 1;

    // envelope step in dB (approx 0.0234375 dB per step)
    private static final double ENV_STEP = 96.0 / ENV_LEN;

    private static final int ENV_ATTACK = 0;
    private static final int ENV_DECAY = ENV_LEN << ENV_LBITS;
    private static final int ENV_END = (ENV_LEN * 2) << ENV_LBITS;

    // Output bits logic
    private static final int MAX_OUT_BITS = SIN_HBITS + SIN_LBITS + 2; // 28
    private static final int OUT_BITS = 14; // OUTPUT_BITS - 2 = 16 - 2 = 14
    private static final int OUT_SHIFT = MAX_OUT_BITS - OUT_BITS; // 14
    private static final int LIMIT_CH_OUT = (int) ((1 << OUT_BITS) * 1.5) - 1;

    private static final int PG_CUT_OFF = (int) (78.0 / ENV_STEP);

    // Rate constants
    private static final int AR_RATE = 399128;
    private static final int DR_RATE = 5514396;

    // LFO constants
    private static final int LFO_FMS_LBITS = 9;
    private static final int LFO_FMS_BASE = (int) (0.05946309436 * 0.0338 * (double) (1 << LFO_FMS_LBITS));

    // Tables
    private static final int[] SIN_TAB = new int[SIN_LEN]; // stores indices into TL_TAB
    private static final int[] TL_TAB = new int[TL_LEN * 2]; // signed 16-bit output values
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

    private static final double YM2612_FREQUENCY = (CLOCK / SAMPLE_RATE) / 144.0;
    private static final int MAX_OUT = (1 << MAX_OUT_BITS) - 1;

    static {
        // TL_TAB generation
        for (int i = 0; i < TL_LEN; i++) {
            if (i >= PG_CUT_OFF) {
                TL_TAB[TL_LEN + i] = 0;
                TL_TAB[i] = 0;
            } else {
                double x = MAX_OUT;
                x /= Math.pow(10, (ENV_STEP * i) / 20); // dB -> Voltage
                TL_TAB[i] = (int) x;
                TL_TAB[TL_LEN + i] = -TL_TAB[i];
            }
        }

        // SIN_TAB generation
        for (int i = 1; i <= SIN_LEN / 4; i++) {
            double x = Math.sin(2.0 * Math.PI * i / SIN_LEN);
            x = 20 * Math.log10(1.0 / x); // to dB
            int j = (int) (x / ENV_STEP); // TL range
            if (j > PG_CUT_OFF) j = PG_CUT_OFF;

            SIN_TAB[i] = j;
            SIN_TAB[(SIN_LEN / 2) - i] = j;
            SIN_TAB[(SIN_LEN / 2) + i] = TL_LEN + j;
            SIN_TAB[SIN_LEN - i] = TL_LEN + j;
        }
        SIN_TAB[0] = PG_CUT_OFF;
        SIN_TAB[SIN_LEN / 2] = PG_CUT_OFF;

        // LFO Table
        for (int i = 0; i < LFO_LEN; i++) {
            double x = Math.sin(2.0 * Math.PI * i / LFO_LEN);
            x += 1.0;
            x /= 2.0; // positive only
            x *= 11.8 / ENV_STEP; // adjusted to MAX envelope modulation
            LFO_ENV_TAB[i] = (int) x;

            x = Math.sin(2.0 * Math.PI * i / LFO_LEN);
            x *= (double) ((1 << (LFO_HBITS - 1)) - 1);
            LFO_FREQ_TAB[i] = (int) x;
        }

        // Envelope Table
        for (int i = 0; i < ENV_LEN; i++) {
            // Attack curve (x^8)
            double x = Math.pow(((double) (ENV_LEN - 1 - i) / ENV_LEN), 8.0);
            x *= ENV_LEN;
            ENV_TAB[i] = (int) x;

            // Decay curve (x^1)
            x = Math.pow(((double) i / ENV_LEN), 1.0);
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

        // LFO Inc Table
        double rate = SAMPLE_RATE;
        double lfoBase = (double) (1 << (LFO_HBITS + LFO_LBITS)) / rate;
        LFO_INC_TAB[0] = (int) (3.98 * lfoBase);
        LFO_INC_TAB[1] = (int) (5.56 * lfoBase);
        LFO_INC_TAB[2] = (int) (6.02 * lfoBase);
        LFO_INC_TAB[3] = (int) (6.37 * lfoBase);
        LFO_INC_TAB[4] = (int) (6.88 * lfoBase);
        LFO_INC_TAB[5] = (int) (9.63 * lfoBase);
        LFO_INC_TAB[6] = (int) (48.1 * lfoBase);
        LFO_INC_TAB[7] = (int) (72.2 * lfoBase);
    }

    private DacData dacData;
    private int currentDacSampleId = -1;
    private int dacLatchedValue;
    private double dacPos;
    private double dacStep = 1.0;
    private boolean dacEnabled;
    private boolean dacHasLatched;
    private static final double DAC_BASE_CYCLES = 288.0;
    private static final double DAC_LOOP_CYCLES = 26.0;
    private static final double DAC_LOOP_SAMPLES = 2.0;
    private static final double Z80_CLOCK = 3579545.0;
    private static final double DAC_GAIN = 64.0;
    private boolean dacInterpolate = true;
    private boolean dacHighpassEnabled = false;
    private int dac_highpass;
    private static final int HIGHPASS_FRACT = 15;
    private static final int HIGHPASS_SHIFT = 9;

    private int status;
    private int mode;
    private double busyCycles;
    private static final int FM_STATUS_BUSY_BIT_MASK = 0x80;
    private static final int FM_STATUS_TIMERA_BIT_MASK = 0x01;
    private static final int FM_STATUS_TIMERB_BIT_MASK = 0x02;
    private static final int BUSY_CYCLES_DATA = 47;
    private static final double YM_CYCLES_PER_SAMPLE = (CLOCK / 6.0) / SAMPLE_RATE;

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
        EnvState curEnv = EnvState.RELEASE;

        int eIncA, eIncD, eIncS, eIncR;
        boolean ssgEnabled;

        int chgEnM = 0;
        boolean amsOn;
        int ams;
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

        dacEnabled = false;
        dac_highpass = 0;

        for (Channel ch : channels) {
            ch.fNum = 0;
            ch.block = 0;
            ch.kCode = 0;
            ch.feedback = 31;
            ch.algo = 0;
            ch.ams = 0;
            ch.fms = 0;
            ch.leftMask = 0xFFFFFFFF;
            ch.rightMask = 0xFFFFFFFF;

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
            }
        }
    }

    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 6) mutes[ch] = mute;
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
            if (addr < 0xA0) {
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
                break;
            case 0x50:
                sl.ar = (val & 0x1F) != 0 ? (val & 0x1F) << 1 : 0;
                sl.rs = 3 - (val >> 6);
                ch.ops[0].fInc = -1;
                sl.eIncA = AR_TAB[sl.ar + sl.ksr];
                if (sl.curEnv == EnvState.ATTACK) sl.eInc = sl.eIncA;
                break;
            case 0x60:
                sl.amsOn = (val & 0x80) != 0;
                sl.ams = sl.amsOn ? ch.ams : 31;
                sl.d1r = (val & 0x1F) != 0 ? (val & 0x1F) << 1 : 0;
                sl.eIncD = DR_TAB[sl.d1r + sl.ksr];
                if (sl.curEnv == EnvState.DECAY1) sl.eInc = sl.eIncD;
                break;
            case 0x70:
                sl.d2r = (val & 0x1F) != 0 ? (val & 0x1F) << 1 : 0;
                sl.eIncS = DR_TAB[sl.d2r + sl.ksr];
                if (sl.curEnv == EnvState.DECAY2) sl.eInc = sl.eIncS;
                break;
            case 0x80:
                sl.d1l = SL_TAB[val >> 4];
                sl.rr = ((val & 0xF) << 2) + 2;
                sl.eIncR = DR_TAB[sl.rr + sl.ksr];
                if (sl.curEnv == EnvState.RELEASE) sl.eInc = sl.eIncR;
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
                ch.feedback = 9 - ((val >> 3) & 7);
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
        }
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
        if (sl.curEnv == EnvState.RELEASE) {
            sl.fCnt = 0;
            int decayAtt = DECAY_TO_ATTACK[ENV_TAB[sl.eCnt >> ENV_LBITS]] + ENV_ATTACK;
            sl.eCnt = decayAtt & sl.chgEnM;
            sl.chgEnM = 0xFFFFFFFF;
            sl.eInc = sl.eIncA;
            sl.eCmp = ENV_DECAY;
            sl.curEnv = EnvState.ATTACK;
        }
    }

    private void keyOff(Channel ch, int idx) {
        Operator sl = ch.ops[idx];
        if (sl.curEnv != EnvState.RELEASE) {
            if (sl.eCnt < ENV_DECAY) {
                sl.eCnt = (ENV_TAB[sl.eCnt >> ENV_LBITS] << ENV_LBITS) + ENV_DECAY;
            }
            sl.eInc = sl.eIncR;
            sl.eCmp = ENV_END;
            sl.curEnv = EnvState.RELEASE;
        }
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
            } else {
                sl.eCnt = 0;
                sl.eInc = sl.eIncA;
                sl.eCmp = ENV_DECAY;
                sl.curEnv = EnvState.ATTACK;
            }
            sl.ssgEg ^= (sl.ssgEg & 2) << 1;
        } else {
            sl.eCnt = ENV_END;
            sl.eInc = 0;
            sl.eCmp = ENV_END + 1;
        }
    }


    public void renderStereo(int[] leftBuf, int[] rightBuf) {
        int len = Math.min(leftBuf.length, rightBuf.length);
        for (int i = 0; i < len; i++) {
            int freqLfo = 0;
            int envLfo = 0;
            if (lfoInc != 0) {
                lfoCnt += lfoInc;
                int idx = (lfoCnt >> LFO_LBITS) & LFO_MASK;
                envLfo = LFO_ENV_TAB[idx];
                freqLfo = LFO_FREQ_TAB[idx];
            }

            int dacOut = renderDac();
            Channel dacCh = channels[5];
            boolean dacLeft = dacCh.leftMask != 0;
            boolean dacRight = dacCh.rightMask != 0;
            if (!mutes[5]) {
                if (dacLeft) leftBuf[i] += dacOut;
                if (dacRight) rightBuf[i] += dacOut;
            }

            for (int ch = 0; ch < 6; ch++) {
                if (mutes[ch]) continue;
                if (ch == 5 && dacEnabled) continue;
                int out = renderChannel(ch, envLfo, freqLfo);
                boolean left = channels[ch].leftMask != 0;
                boolean right = channels[ch].rightMask != 0;
                if (left) leftBuf[i] += out;
                if (right) rightBuf[i] += out;
            }
            tickTimers(1);
        }
    }

    private int renderChannel(int chIdx, int envLfo, int freqLfo) {
        Channel ch = channels[chIdx];
        if (ch.ops[0].fInc == -1) calcFIncChannel(ch);

        int fms = ch.fms;
        int lfoShift = fms != 0 ? (fms * freqLfo) >> (LFO_HBITS - 1) : 0;

        for (int i=0; i<4; i++) {
            Operator op = ch.ops[i];
            int finc = op.fInc;
            if (lfoShift != 0) {
                finc += (finc * lfoShift) >> LFO_FMS_LBITS;
            }
            op.fCnt += finc;
        }

        GET_CURRENT_ENV(ch, 0, envLfo);
        GET_CURRENT_ENV(ch, 1, envLfo);
        GET_CURRENT_ENV(ch, 2, envLfo);
        GET_CURRENT_ENV(ch, 3, envLfo);

        for (int i=0; i<4; i++) {
            Operator op = ch.ops[i];
            op.eCnt += op.eInc;
            if (op.eCnt >= op.eCmp) {
                envNextEvent(op, op.curEnv);
            }
        }

        doAlgo(ch);

        if (ch.out > LIMIT_CH_OUT) ch.out = LIMIT_CH_OUT;
        else if (ch.out < -LIMIT_CH_OUT) ch.out = -LIMIT_CH_OUT;


        return ch.out;
    }

    private void GET_CURRENT_ENV(Channel ch, int slot, int envLfo) {
        Operator sl = ch.ops[slot];
        int env = ENV_TAB[sl.eCnt >> ENV_LBITS] + sl.tll;

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

    private void doAlgo(Channel ch) {
        in0 = ch.ops[0].fCnt;
        in1 = ch.ops[1].fCnt;
        in2 = ch.ops[2].fCnt;
        in3 = ch.ops[3].fCnt;

        switch (ch.algo) {
            case 0:
                // DO_FEEDBACK
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                // DO_ALGO_0 (libvgm)
                in1 += ch.opOut[1];
                in2 += TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1];
                in3 += TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2];
                ch.out = TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] >> OUT_SHIFT;
                break;
            case 1:
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                in2 += ch.opOut[1] + TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1];
                in3 += TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2];
                ch.out = TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] >> OUT_SHIFT;
                break;
            case 2:
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                in2 += TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1];
                in3 += ch.opOut[1] + TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2];
                ch.out = TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] >> OUT_SHIFT;
                break;
            case 3:
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                in1 += ch.opOut[1];
                in3 += TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1] +
                       TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2];
                ch.out = TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] >> OUT_SHIFT;
                break;
            case 4: {
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                in1 += ch.opOut[1];
                in3 += TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2];
                ch.out = (TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] +
                          TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1]) >> OUT_SHIFT;
                if (ch.out > LIMIT_CH_OUT) ch.out = LIMIT_CH_OUT;
                else if (ch.out < -LIMIT_CH_OUT) ch.out = -LIMIT_CH_OUT;
                break;
            }
            case 5: {
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                in1 += ch.opOut[1];
                in2 += ch.opOut[1];
                in3 += ch.opOut[1];
                ch.out = (TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] +
                          TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1] +
                          TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2]) >> OUT_SHIFT;
                if (ch.out > LIMIT_CH_OUT) ch.out = LIMIT_CH_OUT;
                else if (ch.out < -LIMIT_CH_OUT) ch.out = -LIMIT_CH_OUT;
                break;
            }
            case 6: {
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                in1 += ch.opOut[1];
                ch.out = (TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] +
                          TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1] +
                          TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2]) >> OUT_SHIFT;
                if (ch.out > LIMIT_CH_OUT) ch.out = LIMIT_CH_OUT;
                else if (ch.out < -LIMIT_CH_OUT) ch.out = -LIMIT_CH_OUT;
                break;
            }
            case 7: {
                in0 += (ch.opOut[0] + ch.opOut[1]) >> ch.feedback;
                ch.opOut[1] = ch.opOut[0];
                ch.opOut[0] = TL_TAB[SIN_TAB[(in0 >> SIN_LBITS) & SIN_MASK] + en0];
                ch.out = (TL_TAB[SIN_TAB[(in3 >> SIN_LBITS) & SIN_MASK] + en3] +
                          TL_TAB[SIN_TAB[(in1 >> SIN_LBITS) & SIN_MASK] + en1] +
                          TL_TAB[SIN_TAB[(in2 >> SIN_LBITS) & SIN_MASK] + en2] +
                          ch.opOut[1]) >> OUT_SHIFT;
                if (ch.out > LIMIT_CH_OUT) ch.out = LIMIT_CH_OUT;
                else if (ch.out < -LIMIT_CH_OUT) ch.out = -LIMIT_CH_OUT;
                break;
            }
        }

    }

    public void setInstrument(int chIdx, byte[] voice) {
        if (chIdx < 0 || chIdx >= 6 || voice.length < 1) return;
        Channel ch = channels[chIdx];

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

        int port = (chIdx < 3) ? 0 : 1;
        int hwCh = chIdx % 3;

        write(port, 0xB0 + hwCh, (feedback << 3) | algo);

        int tlIdxBase = 21;
        int rsArBase = 5;
        int amD1rBase = 9;
        int d2rBase = 13;
        int d1lRrBase = 17;
        // Reorder SMPS voice (Op1, Op3, Op2, Op4) into YM operator order (Op1, Op2, Op3, Op4).
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
            this.dacStep = Math.max(0.0001, rateHz / SAMPLE_RATE);
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
        return switch (algo) {
            case 0 -> (opIndex == 1 ? opOut[0] : opIndex == 2 ? opOut[1] : opIndex == 3 ? opOut[2] : 0);
            case 1 -> (opIndex == 2 ? opOut[0] + opOut[1] : opIndex == 3 ? opOut[2] : 0);
            case 2 -> (opIndex == 2 ? opOut[1] : opIndex == 3 ? opOut[0] + opOut[2] : 0);
            case 3 -> (opIndex == 1 ? opOut[0] : opIndex == 3 ? opOut[1] + opOut[2] : 0);
            case 4 -> (opIndex == 1 ? opOut[0] : opIndex == 3 ? opOut[2] : 0);
            case 5 -> (opIndex == 1 || opIndex == 2 || opIndex == 3 ? opOut[0] : 0);
            case 6 -> (opIndex == 1 ? opOut[0] : 0);
            case 7 -> 0;
            default -> 0;
        };
    }

    /**
     * Legacy helper for unit tests (TestYm2612AlgorithmRouting).
     */
    public static double computeCarrierSum(int algo, double[] opOut) {
        return switch (algo) {
            case 0 -> opOut[3];
            case 1 -> opOut[3];
            case 2 -> opOut[3];
            case 3 -> opOut[3];
            case 4 -> opOut[1] + opOut[3];
            case 5 -> opOut[1] + opOut[2] + opOut[3];
            case 6 -> opOut[1] + opOut[2] + opOut[3];
            case 7 -> opOut[0] + opOut[1] + opOut[2] + opOut[3];
            default -> opOut[3];
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
