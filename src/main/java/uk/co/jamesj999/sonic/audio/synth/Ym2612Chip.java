package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class Ym2612Chip {
    private static final double CLOCK = 7670453.0;
    private static final double SAMPLE_RATE = 44100.0;

    private static final double TWO_PI = Math.PI * 2.0;

    // Integer Math Constants
    private static final int SIN_HBITS = 12;
    private static final int SIN_LBITS = 14;
    private static final int SINE_STEPS = 1 << SIN_HBITS; // 4096

    // Attenuation / Envelope Constants
    private static final int ENV_HBITS = 12;
    private static final int ENV_LBITS = 16;
    private static final int ENV_LEN = 1 << ENV_HBITS; // 4096

    private static final int ENV_ATTACK = 0;
    private static final int ENV_DECAY = ENV_LEN;
    private static final int ENV_END = ENV_LEN * 2;

    // Fixed Point Envelope Constants (shifted)
    private static final int FP_ENV_DECAY = ENV_DECAY << ENV_LBITS;
    private static final int FP_ENV_END = ENV_END << ENV_LBITS;

    // Tables
    private static final int[] FINC_TAB = new int[2048];
    private static final int[] AR_TAB = new int[96];
    private static final int[] DR_TAB = new int[96];
    private static final int[] SIN_TAB = new int[SINE_STEPS];
    private static final int[] ENV_TAB = new int[ENV_END + 2];
    private static final int[] DECAY_TO_ATTACK = new int[ENV_LEN];
    private static final int[] SL_TAB = new int[16];
    private static final int[][] DT_TAB = new int[8][32];

    private static final double ATT_STEP_DB = 0.1875;
    private static final double[] EXP_OUT = new double[8192];
    private static final int LOG_SINE_LEN = SINE_STEPS / 4;
    private static final int[] LOG_SINE = new int[LOG_SINE_LEN];

    private static final double YM2612_FREQUENCY = (CLOCK / SAMPLE_RATE) / 144.0;
    private static final double AR_RATE = 399128.0;
    private static final double DR_RATE = 5514396.0;

    // LFO
    private static final int[] LFO_FMS_STEPS = {0, 1, 2, 3, 4, 6, 12, 24};
    private static final double[] LFO_FMS_SCALE = new double[8];
    private static final double[] FMS_MULT = new double[8];
    private static final int LFO_TABLE_LEN = 256;
    private static final double[] LFO_TABLE = new double[LFO_TABLE_LEN];

    // Constants used in static init must be declared before use
    private void handleSsgEnd(Operator o) {
        if (!o.ssgEnabled) {
            o.envState = EnvState.IDLE;
            o.envCounter = FP_ENV_END;
            return;
        }

        if (o.ssgHoldMode) {
            o.envCounter = FP_ENV_END;
            o.envState = EnvState.IDLE;
            o.ssgHold = true;
        } else {
            o.envCounter = 0; // Attack Start (0)
            o.envState = EnvState.ATTACK;
        }

        if (o.ssgAlternate) {
            o.ssgInverted = !o.ssgInverted;
        }
    }

    private static final double[] LFO_FREQ = {3.98, 5.56, 6.02, 6.37, 6.88, 9.63, 48.1, 72.2};
    private static final int[] LFO_AMS_TAB = {31, 4, 1, 0};
    private static final int[] FKEY_TAB = {
            0, 0, 0, 0,
            0, 0, 0, 1,
            2, 3, 3, 3,
            3, 3, 3, 3
    };
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
    private static final double[] AMS_DEPTH = {0.0, 1.4, 5.9, 11.8};

    private static final double OUTPUT_GAIN = 3000.0;
    private static final double LPF_CUTOFF_HZ = 22000.0;
    private static final double LPF_ALPHA = 0.0;
    private static final double TIMER_BASE = (CLOCK / SAMPLE_RATE) * (4096.0 / 144.0);
    private static final double YM_CYCLES_PER_SAMPLE = (CLOCK / 6.0) / SAMPLE_RATE;
    private static final double DAC_GAIN = 64.0;
    private static final double Z80_CLOCK = 3579545.0;
    private static final double DAC_BASE_CYCLES = 288.0;
    private static final double DAC_LOOP_CYCLES = 26.0;
    private static final double DAC_LOOP_SAMPLES = 2.0;
    private static final boolean DAC_INTERPOLATE = true;

    private static final int FM_STATUS_BUSY_BIT_MASK = 0x80;
    private static final int FM_STATUS_TIMERA_BIT_MASK = 0x01;
    private static final int FM_STATUS_TIMERB_BIT_MASK = 0x02;
    private static final int BUSY_CYCLES_DATA = 47;

    static {
        for (int i = 0; i < LOG_SINE.length; i++) {
            double angle = (i + 0.5) * (Math.PI / 2.0) / LOG_SINE.length;
            double s = Math.sin(angle);
            double attDb = -20.0 * Math.log10(Math.max(1e-12, s));
            int idx = (int) Math.round(attDb / ATT_STEP_DB);
            if (idx < 0) idx = 0;
            if (idx >= EXP_OUT.length) idx = EXP_OUT.length - 1;
            LOG_SINE[i] = idx;
        }

        for (int i = 0; i < SINE_STEPS; i++) {
             int p = i & (SINE_STEPS - 1);
             int q = p >> (SIN_HBITS - 2);
             int idx = p & (LOG_SINE_LEN - 1);
             if ((q & 1) != 0) idx = (LOG_SINE_LEN - 1) - idx;
             SIN_TAB[i] = LOG_SINE[idx];
        }

        for (int i = 0; i < EXP_OUT.length; i++) {
            double db = i * ATT_STEP_DB;
            EXP_OUT[i] = Math.pow(10.0, -db / 20.0);
        }

        for (int i = 0; i < ENV_LEN; i++) {
            double a = Math.pow(((double) (ENV_LEN - 1 - i) / ENV_LEN), 8.0);
            ENV_TAB[i] = (int) Math.round(a * ENV_LEN);
            double d = Math.pow(((double) i / ENV_LEN), 1.0);
            ENV_TAB[ENV_LEN + i] = (int) Math.round(d * ENV_LEN);
        }
        ENV_TAB[ENV_END] = ENV_LEN - 1;

        for (int i = 0, j = ENV_LEN - 1; i < ENV_LEN; i++) {
            while (j > 0 && ENV_TAB[j] < i) j--;
            DECAY_TO_ATTACK[i] = j;
        }

        for (int i = 0; i < 15; i++) {
            double db = i * 3.0;
            int val = (int) ((db * ENV_LEN) / 96.0) + ENV_DECAY;
            SL_TAB[i] = val;
        }
        SL_TAB[15] = (ENV_LEN - 1) + ENV_DECAY;

        for (int i = 0; i < 2048; i++) {
            double x = (double) i * YM2612_FREQUENCY;
            x *= (double) (1 << 12); // (26 - 14)
            x /= 2.0;
            FINC_TAB[i] = (int) x;
        }

        for (int i = 0; i < 60; i++) {
            double x = YM2612_FREQUENCY;
            x *= 1.0 + ((i & 3) * 0.25);
            x *= (double) (1 << (i >> 2));
            x *= (double) (ENV_LEN << ENV_LBITS);
            AR_TAB[i + 4] = (int) (x / AR_RATE);
            DR_TAB[i + 4] = (int) (x / DR_RATE);
        }
        // Populate low rates to prevent stalling
        for (int i = 0; i < 4; i++) {
            AR_TAB[i] = AR_TAB[4];
            DR_TAB[i] = DR_TAB[4];
        }
        for (int i = 64; i < 96; i++) {
            AR_TAB[i] = AR_TAB[63];
            DR_TAB[i] = DR_TAB[63];
        }

        // Detune Table Init
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 32; j++) {
                // Scale factor: 1 << (12 - 7) = 1 << 5.
                // See logic in analysis.
                double x = (double) DT_DEF_TAB[(i << 5) + j] * YM2612_FREQUENCY * (double) (1 << 5);
                DT_TAB[i + 0][j] = (int) x;
                DT_TAB[i + 4][j] = (int) -x;
            }
        }

        double[] fmsCents = {0, 3.4, 6.7, 10.0, 14.0, 20.0, 40.0, 80.0};
        for (int i = 0; i < fmsCents.length; i++) {
            FMS_MULT[i] = Math.pow(2.0, fmsCents[i] / 1200.0) - 1.0;
        }
        final double lfoFmsBase = 0.05946309436 * 0.0338;
        final double lfoFmsDiv = 1 << 9;
        for (int i = 0; i < LFO_FMS_STEPS.length; i++) {
            LFO_FMS_SCALE[i] = (LFO_FMS_STEPS[i] * lfoFmsBase) / lfoFmsDiv;
        }
        for (int i = 0; i < LFO_TABLE_LEN; i++) {
            LFO_TABLE[i] = Math.sin((TWO_PI * i) / LFO_TABLE_LEN);
        }
    }

    private DacData dacData;
    private int currentDacSampleId = -1;
    private int dacLatchedValue;
    private double dacPos;
    private double dacStep = 1.0;
    private boolean dacEnabled;
    private boolean dacHasLatched;
    private int status;
    private double timerACount;
    private double timerBCount;
    private int timerAPeriod;
    private int timerBPeriod;
    private int timerALoad;
    private int timerBLoad;
    private boolean timerAEnabled;
    private boolean timerBEnabled;
    private double busyCycles;
    private int mode;

    private double lfoPos;
    private double lfoStep;
    private boolean lfoEnabled;
    private int lfoFreqIdx;
    private double lpfStateL;
    private double lpfStateR;

    private enum EnvState { ATTACK, DECAY1, DECAY2, RELEASE, IDLE }

    static class Operator {
        int dt1, mul;
        int tl;
        int rs, ar;
        int am, d1r;
        int d2r;
        int d1l, rr;
        int ssgEg;

        EnvState envState = EnvState.IDLE;
        int envCounter = FP_ENV_END;
        int phase;
        double lastOutput;
        boolean ssgInverted;
        boolean ssgEnabled;
        boolean ssgAlternate;
        boolean ssgHoldMode;
        boolean ssgHold;
    }

    static class Channel {
        int fNum;
        int block;
        boolean specialMode;
        final int[] slotFnum = new int[4];
        final int[] slotBlock = new int[4];

        int feedback, algo;
        int ams, fms;
        int pan;
        double attackRamp;

        double feedbackHist1;
        double feedbackHist2;

        final Operator[] ops = new Operator[4];

        Channel() {
            for (int i = 0; i < 4; i++) {
                ops[i] = new Operator();
                slotFnum[i] = 0;
                slotBlock[i] = 0;
            }
        }
    }

    final Channel[] channels = new Channel[6];
    private final boolean[] mutes = new boolean[6];
    private boolean channel3SpecialMode;

    public Ym2612Chip() {
        for (int i = 0; i < 6; i++) {
            channels[i] = new Channel();
        }
        reset();
    }

    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 6) {
            mutes[ch] = mute;
        }
    }

    public void reset() {
        status = 0;
        mode = 0;
        busyCycles = 0;
        channel3SpecialMode = false;
        timerACount = timerBCount = 0;
        timerAPeriod = timerBPeriod = 0;
        timerALoad = timerBLoad = 0;
        timerAEnabled = timerBEnabled = false;
        dacEnabled = false;
        dacHasLatched = false;
        dacLatchedValue = 0;
        currentDacSampleId = -1;
        dacPos = 0;
        lfoPos = 0;
        lfoStep = 0;
        lfoEnabled = false;
        lfoFreqIdx = 0;
        lpfStateL = lpfStateR = 0;
        for (Channel ch : channels) {
            ch.fNum = 0;
            ch.block = 0;
            ch.feedback = 0;
            ch.algo = 0;
            ch.ams = 0;
            ch.fms = 0;
            ch.pan = 0x3;
            ch.attackRamp = 1.0;
            ch.feedbackHist1 = 0;
            ch.feedbackHist2 = 0;
            ch.specialMode = false;
            for (int i = 0; i < 4; i++) {
                ch.slotFnum[i] = 0;
                ch.slotBlock[i] = 0;
                Operator o = ch.ops[i];
                o.dt1 = o.mul = o.tl = 0;
                o.rs = o.ar = 0;
                o.am = o.d1r = 0;
                o.d2r = 0;
                o.d1l = o.rr = 0;
                o.ssgEg = 0;
                o.envState = EnvState.IDLE;
                o.envCounter = FP_ENV_END;
                o.phase = 0;
                o.lastOutput = 0;
                o.ssgInverted = false;
                o.ssgEnabled = false;
                o.ssgAlternate = false;
                o.ssgHoldMode = false;
                o.ssgHold = false;
            }
        }
    }

    public int readStatus() {
        if (busyCycles > 0) {
            status |= FM_STATUS_BUSY_BIT_MASK;
        } else {
            status &= ~FM_STATUS_BUSY_BIT_MASK;
        }
        return status;
    }

    public void setDacData(DacData data) {
        this.dacData = data;
    }

    private double stepLfo() {
        if (!lfoEnabled) {
            return 0.0;
        }
        lfoPos += lfoStep;
        if (lfoPos >= LFO_TABLE_LEN) {
            lfoPos -= LFO_TABLE_LEN;
        }
        int idx = (int) lfoPos & (LFO_TABLE_LEN - 1);
        return LFO_TABLE[idx];
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

    private void keyOn(Channel ch, int opIdx) {
        Operator o = ch.ops[opIdx];
        if (o.ar == 0) {
            o.envState = EnvState.IDLE;
            o.envCounter = FP_ENV_END;
            return;
        }
        ch.attackRamp = 0.0;
        if (o.envState == EnvState.RELEASE || o.envState == EnvState.IDLE) {
            // Unshift, map, shift back
            int currentIdx = o.envCounter >> ENV_LBITS;
            int atten = ENV_TAB[currentIdx <= ENV_ATTACK ? ENV_ATTACK : Math.min(currentIdx, ENV_END)];
            if (atten >= ENV_LEN) atten = ENV_LEN - 1;
            o.envCounter = DECAY_TO_ATTACK[atten] << ENV_LBITS;
            o.envState = EnvState.ATTACK;

            o.ssgInverted = (o.ssgEg & 0x04) != 0;
            o.ssgHold = false;
        }
    }

    private void keyOff(Channel ch, int opIdx) {
        Operator o = ch.ops[opIdx];
        if (o.envState != EnvState.RELEASE) {
            if (o.envCounter < FP_ENV_DECAY) {
                int currentIdx = o.envCounter >> ENV_LBITS;
                int atten = ENV_TAB[currentIdx];
                o.envCounter = (ENV_DECAY + atten) << ENV_LBITS;
            }
            o.envState = EnvState.RELEASE;
        }
    }

    private void csmKeyControl() {
        keyOn(channels[2], 0);
        keyOn(channels[2], 1);
        keyOn(channels[2], 2);
        keyOn(channels[2], 3);
    }

    public void setInstrument(int chIdx, byte[] voice) {
        if (chIdx < 0 || chIdx >= 6 || voice.length < 1) return;
        Channel ch = channels[chIdx];

        // Ensure we have enough bytes (padding if necessary)
        // Standard length is 25 bytes (1 header + 4 * 6 regs).
        // If shorter, missing values (like TL) default to 0.
        int expectedLen = 25;
        if (voice.length < expectedLen) {
            byte[] padded = new byte[expectedLen];
            System.arraycopy(voice, 0, padded, 0, voice.length);
            voice = padded;
        }

        final byte[] v = voice;
        java.util.function.IntUnaryOperator get = (idx) -> (idx >= 0 && idx < v.length) ? (v[idx] & 0xFF) : 0;

        int val00 = get.applyAsInt(0);
        ch.feedback = (val00 >> 3) & 7;
        ch.algo = val00 & 7;

        // Map input (Linear Order: Op1, Op2, Op3, Op4) to Internal Op Index (0=Op1, 1=Op2, 2=Op3, 3=Op4)
        // Index 0 (Op 1) -> ops[0]
        // Index 1 (Op 2) -> ops[1]
        // Index 2 (Op 3) -> ops[2]
        // Index 3 (Op 4) -> ops[3]
        int[] opOrder = {0, 1, 2, 3};

        int rsArBase = 5;
        int amD1rBase = 9;
        int d2rBase = 13;
        int d1lRrBase = 17;
        int tlIdxBase = 21;

        for (int orderIdx = 0; orderIdx < 4; orderIdx++) {
            int op = opOrder[orderIdx];
            Operator o = ch.ops[op];
            int dtmul = get.applyAsInt(1 + orderIdx);
            o.dt1 = (dtmul >> 4) & 7;
            o.mul = dtmul & 0xF;

            int tlVal = get.applyAsInt(tlIdxBase + orderIdx);
            o.tl = tlVal & 0x7F;

            int rsar = get.applyAsInt(rsArBase + orderIdx);
            o.rs = (rsar >> 6) & 3;
            o.ar = rsar & 0x1F;

            int amd1r = get.applyAsInt(amD1rBase + orderIdx);
            o.am = (amd1r >> 7) & 1;
            o.d1r = amd1r & 0x1F;

            int d2r = get.applyAsInt(d2rBase + orderIdx);
            o.d2r = d2r & 0x1F;

            int d1lrr = get.applyAsInt(d1lRrBase + orderIdx);
            o.d1l = (d1lrr >> 4) & 0xF;
            o.rr = d1lrr & 0xF;

            o.envState = EnvState.IDLE;
            o.envCounter = FP_ENV_END;
            o.phase = 0;
            o.lastOutput = 0;
            o.ssgInverted = false;
            o.ssgEnabled = false;
            o.ssgAlternate = false;
            o.ssgHoldMode = false;
            o.ssgHold = false;
        }

        // Push voice parameters into YM registers immediately so the next key-on uses them.
        int port = (chIdx < 3) ? 0 : 1;
        int hwCh = chIdx % 3;
        // opIdx -> slot code used by the YM register map (0,1,2,3 correspond to op1, op3, op2, op4)
        // Voice Data (ch.ops) is in Logical Order (1, 2, 3, 4).
        // YM Registers use Slot Order (0=Op1, 1=Op3, 2=Op2, 3=Op4).
        // Map: OpIdx 0(Op1)->Slot0, OpIdx 1(Op2)->Slot2, OpIdx 2(Op3)->Slot1, OpIdx 3(Op4)->Slot3.
        int[] slotCode = {0, 2, 1, 3};
        for (int opIdx = 0; opIdx < 4; opIdx++) {
            int slot = slotCode[opIdx];
            Operator o = ch.ops[opIdx];
            // DT/MUL
            write(port, 0x30 + slot * 4 + hwCh, ((o.dt1 & 7) << 4) | (o.mul & 0x0F));
            // TL
            write(port, 0x40 + slot * 4 + hwCh, o.tl & 0x7F);
            // RS/AR
            write(port, 0x50 + slot * 4 + hwCh, ((o.rs & 3) << 6) | (o.ar & 0x1F));
            // AM/D1R
            write(port, 0x60 + slot * 4 + hwCh, ((o.am & 1) << 7) | (o.d1r & 0x1F));
            // D2R
            write(port, 0x70 + slot * 4 + hwCh, o.d2r & 0x1F);
            // D1L/RR
            write(port, 0x80 + slot * 4 + hwCh, ((o.d1l & 0x0F) << 4) | (o.rr & 0x0F));
            // SSG-EG
            write(port, 0x90 + slot * 4 + hwCh, o.ssgEg & 0x0F);
        }
    }

    public void write(int port, int reg, int val) {
        busyCycles = BUSY_CYCLES_DATA;

        if (port == 0 && reg == 0x2B) {
            dacEnabled = (val & 0x80) != 0;
            if (!dacEnabled) {
                stopDac();
            }
            return;
        }
        if (port == 0 && reg == 0x22) {
            lfoEnabled = (val & 0x08) != 0;
            lfoFreqIdx = val & 0x07;
            double lfoFreq = LFO_FREQ[Math.min(lfoFreqIdx, LFO_FREQ.length - 1)];
            lfoStep = (lfoFreq / SAMPLE_RATE) * LFO_TABLE_LEN;
            if (!lfoEnabled) {
                lfoPos = 0;
                lfoStep = 0;
            }
            return;
        }
        if (port == 0 && reg == 0x24) {
            timerAPeriod = (timerAPeriod & 0x03) | (val << 2);
            timerALoad = (1024 - timerAPeriod) << 12;
            if (timerAEnabled) timerACount = timerALoad;
            return;
        }
        if (port == 0 && reg == 0x25) {
            timerAPeriod = (timerAPeriod & 0x3FC) | (val & 0x03);
            timerALoad = (1024 - timerAPeriod) << 12;
            if (timerAEnabled) timerACount = timerALoad;
            return;
        }
        if (port == 0 && reg == 0x26) {
            timerBPeriod = val & 0xFF;
            timerBLoad = (256 - timerBPeriod) << (4 + 12);
            if (timerBEnabled) timerBCount = timerBLoad;
            return;
        }
        if (port == 0 && reg == 0x2A) {
            dacLatchedValue = (val & 0xFF) - 128;
            dacHasLatched = true;
            currentDacSampleId = -1;
            return;
        }
        if (port == 0 && reg == 0x27) {
            if (((mode ^ val) & 0x40) != 0) {
                for (Operator op : channels[2].ops) {
                    op.phase = 0;
                }
            }
            mode = val;
            channel3SpecialMode = (val & 0x40) != 0;
            channels[2].specialMode = channel3SpecialMode;
            timerAEnabled = (val & 0x01) != 0;
            timerBEnabled = (val & 0x02) != 0;
            if (timerAEnabled) timerACount = timerALoad;
            if (timerBEnabled) timerBCount = timerBLoad;
            if ((val & 0x10) != 0) status &= ~FM_STATUS_TIMERA_BIT_MASK;
            if ((val & 0x20) != 0) status &= ~FM_STATUS_TIMERB_BIT_MASK;
            return;
        }
        if (port == 0 && reg == 0x28) {
            int chIdx = val & 0x07;
            if (chIdx == 3) return;
            if (chIdx >= 4) chIdx -= 1;

            if (chIdx < 0 || chIdx > 5) return;

            int opMask = (val >> 4) & 0x0F;
            Channel ch = channels[chIdx];
            for (int i = 0; i < 4; i++) {
                boolean on = ((opMask >> i) & 1) != 0;
                if (on) keyOn(ch, i);
                else keyOff(ch, i);
            }
            return;
        }
        if (reg >= 0xA0 && reg <= 0xA2) {
            int ch = (port * 3) + (reg - 0xA0);
            if (ch < 6) {
                channels[ch].fNum = ((channels[ch].fNum & 0x700) | (val & 0xFF)) & 0x7FF;
                for (int s = 0; s < 4; s++) {
                    channels[ch].slotFnum[s] = (channels[ch].slotFnum[s] & 0x700) | (val & 0xFF);
                }
            }
            return;
        }
        if (reg >= 0xA4 && reg <= 0xA6) {
            int ch = (port * 3) + (reg - 0xA4);
            if (ch < 6) {
                channels[ch].fNum = ((channels[ch].fNum & 0xFF) | ((val & 0x07) << 8)) & 0x7FF;
                channels[ch].block = (val >> 3) & 0x7;
                for (int s = 0; s < 4; s++) {
                    channels[ch].slotFnum[s] = ((channels[ch].slotFnum[s] & 0xFF) | ((val & 0x07) << 8)) & 0x7FF;
                    channels[ch].slotBlock[s] = channels[ch].block;
                }
            }
            return;
        }
        if (port == 0 && reg >= 0xA8 && reg <= 0xAB) {
            int rawSlot = reg - 0xA8;
            int opIdx = switch (rawSlot) {
                case 0 -> 0;
                case 1 -> 2;
                case 2 -> 1;
                case 3 -> 3;
                default -> -1;
            };
            if (opIdx >= 0) {
                Channel ch = channels[2];
                ch.slotFnum[opIdx] = ((ch.slotFnum[opIdx] & 0x700) | (val & 0xFF)) & 0x7FF;
            }
            return;
        }
        if (port == 0 && reg >= 0xAC && reg <= 0xAF) {
            int rawSlot = reg - 0xAC;
            int opIdx = switch (rawSlot) {
                case 0 -> 0;
                case 1 -> 2;
                case 2 -> 1;
                case 3 -> 3;
                default -> -1;
            };
            if (opIdx >= 0) {
                Channel ch = channels[2];
                ch.slotFnum[opIdx] = ((ch.slotFnum[opIdx] & 0xFF) | ((val & 0x07) << 8)) & 0x7FF;
                ch.slotBlock[opIdx] = (val >> 3) & 0x7;
            }
            return;
        }
        if (reg >= 0xB0 && reg <= 0xB2) {
            int ch = (port * 3) + (reg - 0xB0);
            if (ch < 6) {
                Channel c = channels[ch];
                c.feedback = (val >> 3) & 0x7;
                c.algo = val & 0x7;
            }
            return;
        }
        if (reg >= 0xB4 && reg <= 0xB6) {
            int ch = (port * 3) + (reg - 0xB4);
            if (ch < 6) {
                Channel c = channels[ch];
                c.pan = (val >> 6) & 0x3;
                c.ams = (val >> 4) & 0x3;
                c.fms = val & 0x7;
            }
            return;
        }
        if ((reg >= 0x30 && reg <= 0x9F)) {
            int slot = (reg & 0x0C) >> 2;
            int opIdx = switch (slot) {
                case 0 -> 0;
                case 1 -> 2;
                case 2 -> 1;
                case 3 -> 3;
                default -> 0;
            };
            int ch = (port * 3) + (reg & 0x03);
            if (ch >= 6 || opIdx >= 4) return;
            Operator o = channels[ch].ops[opIdx];

            int base = reg & 0xF0;
            switch (base) {
                case 0x30:
                    o.dt1 = (val >> 4) & 7;
                    o.mul = val & 0xF;
                    break;
                case 0x40:
                    o.tl = val & 0x7F;
                    break;
                case 0x50:
                    o.rs = (val >> 6) & 3;
                    o.ar = val & 0x1F;
                    break;
                case 0x60:
                    o.am = (val >> 7) & 1;
                    o.d1r = val & 0x1F;
                    break;
                case 0x70:
                    o.d2r = val & 0x1F;
                    break;
                case 0x80:
                    o.d1l = (val >> 4) & 0xF;
                    o.rr = val & 0xF;
                    break;
                case 0x90:
                    o.ssgEg = val & 0x0F;
                    o.ssgEnabled = (val & 0x08) != 0;
                    o.ssgHoldMode = (val & 0x01) != 0;
                    o.ssgAlternate = (val & 0x02) != 0;
                    break;
                default:
                    break;
            }
        }
    }

    public void render(short[] buffer) {
        for (int i = 0; i < buffer.length; i++) {
            double lfoVal = stepLfo();
            double mixL = 0;
            double mixR = 0;
            double dacOut = renderDac();
            Channel dacCh = channels[5];
            boolean dacLeft = (dacCh.pan & 0x2) != 0;
            boolean dacRight = (dacCh.pan & 0x1) != 0;
            if (!mutes[5]) {
                if (dacLeft || (!dacLeft && !dacRight)) mixL += dacOut;
                if (dacRight || (!dacLeft && !dacRight)) mixR += dacOut;
            }

            for (int ch = 0; ch < 6; ch++) {
                if (mutes[ch]) continue;
                if (ch == 5 && dacEnabled) continue;
                double out = renderChannel(ch, lfoVal);
                boolean left = (channels[ch].pan & 0x2) != 0;
                boolean right = (channels[ch].pan & 0x1) != 0;
                if (left) mixL += out;
                if (right) mixR += out;
            }
            if (LPF_ALPHA > 0) {
                lpfStateL += (mixL - lpfStateL) * LPF_ALPHA;
                lpfStateR += (mixR - lpfStateR) * LPF_ALPHA;
                mixL = lpfStateL;
                mixR = lpfStateR;
            }
            double mono = (mixL + mixR) * 0.5;
            double soft = softClip(mono);
            int s = (int) Math.max(-32768, Math.min(32767, soft));
            buffer[i] = (short) (buffer[i] + s);
            tickTimers(1);
        }
    }

    public void renderStereo(short[] leftBuf, short[] rightBuf) {
        int len = Math.min(leftBuf.length, rightBuf.length);
        for (int i = 0; i < len; i++) {
            double lfoVal = stepLfo();
            double mixL = 0;
            double mixR = 0;
            double dacOut = renderDac();
            Channel dacCh = channels[5];
            boolean dacLeft = (dacCh.pan & 0x2) != 0;
            boolean dacRight = (dacCh.pan & 0x1) != 0;
            if (!mutes[5]) {
                if (dacLeft || (!dacLeft && !dacRight)) mixL += dacOut;
                if (dacRight || (!dacLeft && !dacRight)) mixR += dacOut;
            }

            for (int ch = 0; ch < 6; ch++) {
                if (mutes[ch]) continue;
                if (ch == 5 && dacEnabled) continue;
                double out = renderChannel(ch, lfoVal);
                boolean left = (channels[ch].pan & 0x2) != 0;
                boolean right = (channels[ch].pan & 0x1) != 0;
                if (left) mixL += out;
                if (right) mixR += out;
            }

            if (LPF_ALPHA > 0) {
                lpfStateL += (mixL - lpfStateL) * LPF_ALPHA;
                lpfStateR += (mixR - lpfStateR) * LPF_ALPHA;
                mixL = lpfStateL;
                mixR = lpfStateR;
            }

            double softL = softClip(mixL);
            double softR = softClip(mixR);
            int outL = (int) Math.max(-32768, Math.min(32767, softL));
            int outR = (int) Math.max(-32768, Math.min(32767, softR));
            leftBuf[i] = (short) (leftBuf[i] + outL);
            rightBuf[i] = (short) (rightBuf[i] + outR);
            tickTimers(1);
        }
    }

    private double softClip(double x) {
        double norm = x / 32768.0;
        double clipped = Math.tanh(norm);
        return clipped * 32767.0;
    }

    private double renderDac() {
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
                if (DAC_INTERPOLATE) {
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
        return sample * DAC_GAIN;
    }

    private double renderChannel(int chIdx, double lfoVal) {
        Channel ch = channels[chIdx];
        boolean hasActiveOperator = false;
        for (Operator op : ch.ops) {
            if (op.envState != EnvState.IDLE) {
                hasActiveOperator = true;
                break;
            }
        }
        if (!hasActiveOperator) return 0;

        if (ch.attackRamp < 1.0) {
            ch.attackRamp = Math.min(1.0, ch.attackRamp + 0.003);
        }

        int fnum = ch.fNum & 0x7FF;
        int block = ch.block & 0x7;

        double fms = LFO_FMS_SCALE[Math.min(ch.fms, LFO_FMS_SCALE.length - 1)];
        if (fms == 0) {
            fms = FMS_MULT[Math.min(ch.fms, FMS_MULT.length - 1)];
        }
        double fmsFactor = 1.0;
        if (lfoEnabled) {
            fmsFactor = 1.0 + (fms * lfoVal);
        }

        double[] opOut = new double[4];

        int fbShift = (ch.feedback == 0) ? 9 : Math.max(2, 9 - ch.feedback);
        double feedback = 1.0 / (1 << fbShift);
        Operator op0 = ch.ops[0];
        double fb = (op0.lastOutput + ch.feedbackHist1) * feedback;
        ch.feedbackHist1 = ch.feedbackHist2;
        ch.feedbackHist2 = op0.lastOutput;

        int[][] algoRoutes = {
                {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 2, 1, 3}, {0, 1, 2, 3},
                {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}
        };

        for (int idx : algoRoutes[Math.min(ch.algo, 7)]) {
            Operator o = ch.ops[idx];
            int fnumUse = (chIdx == 2 && channel3SpecialMode) ? ch.slotFnum[idx] : fnum;
            int blockUse = (chIdx == 2 && channel3SpecialMode) ? ch.slotBlock[idx] : block;

            int finc = FINC_TAB[fnumUse] >> (7 - blockUse);

            int kc = Math.min(31, (blockUse << 2) + FKEY_TAB[(fnumUse >> 7) & 0x0F]);
            finc += DT_TAB[o.dt1][kc];

            if (o.mul == 0) finc /= 2;
            else finc *= o.mul;

            finc = (int) (finc * fmsFactor);

            double modIn = computeModulationInput(ch.algo, idx, opOut, fb);

            int modPhase = (int) (modIn * (SINE_STEPS / TWO_PI) * (1 << (SIN_LBITS)));

            o.phase += finc;
            int p = o.phase + modPhase;

            int sineIdx = (p >> SIN_LBITS) & (SINE_STEPS - 1);
            int atten = SIN_TAB[sineIdx];
            int quadrant = (sineIdx >> (SIN_HBITS - 2)) & 3;
            double sign = (quadrant >= 2) ? -1.0 : 1.0;

            stepEnvelope(o, ch, idx, blockUse, fnumUse);

            double env = envelopeToLinear(o, ch.ams, lfoVal);

            double sample = EXP_OUT[atten] * sign * env;
            o.lastOutput = sample;
            opOut[idx] = sample;
        }

        double carrier = computeCarrierSum(ch.algo, opOut);
        carrier *= ch.attackRamp;

        return carrier * OUTPUT_GAIN;
    }

    public static double computeModulationInput(int algo, int opIndex, double[] opOut, double feedback) {
        if (opIndex == 0) return feedback;
        return switch (algo) {
            case 0 -> (opIndex == 1 ? opOut[0] : opIndex == 2 ? opOut[1] : opOut[2]);
            case 1 -> (opIndex == 2 ? opOut[0] + opOut[1] : opIndex == 3 ? opOut[2] : 0);
            case 2 -> (opIndex == 2 ? opOut[1] : opIndex == 3 ? opOut[2] + opOut[0] : 0);
            case 3 -> (opIndex == 1 ? opOut[0] : opIndex == 3 ? opOut[1] + opOut[2] : 0);
            case 4 -> (opIndex == 1 ? opOut[0] : opIndex == 3 ? opOut[2] : 0);
            case 5 -> (opIndex == 1 || opIndex == 2 || opIndex == 3 ? opOut[0] : 0);
            case 6 -> (opIndex == 1 ? opOut[0] : 0);
            case 7 -> 0;
            default -> 0;
        };
    }

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

    private void stepEnvelope(Operator o, Channel ch, int opIndex, int block, int fnum) {
        if (o.ssgHold) return;

        switch (o.envState) {
            case ATTACK -> {
                int step = egStep(o.ar, o.rs, block, fnum, true);
                o.envCounter += step;
                if (o.envCounter >= FP_ENV_DECAY || o.ar == 31) {
                    if (o.ssgEnabled) {
                        handleSsgEnd(o);
                    } else {
                        o.envCounter = FP_ENV_DECAY;
                        o.envState = EnvState.DECAY1;
                    }
                }
            }
            case DECAY1 -> {
                int step = egStep(o.d1r, o.rs, block, fnum, false);
                o.envCounter += step;
                int sustain = SL_TAB[Math.min(15, o.d1l)] << ENV_LBITS;
                if (o.envCounter >= sustain) {
                    o.envCounter = sustain;
                    o.envState = EnvState.DECAY2;
                }
            }
            case DECAY2 -> {
                int step = egStep(o.d2r, o.rs, block, fnum, false);
                o.envCounter += step;
                if (o.envCounter >= FP_ENV_END) {
                    handleSsgEnd(o);
                }
            }
            case RELEASE -> {
                int step = egStep(Math.max(1, o.rr), o.rs, block, fnum, false);
                o.envCounter += step;
                if (o.envCounter >= FP_ENV_END) {
                    handleSsgEnd(o);
                }
            }
            case IDLE -> o.envCounter = FP_ENV_END;
        }
    }

    private int egStep(int rate, int rs, int block, int fnum, boolean attack) {
        if (rate == 0) return 0;
        int keyCode = ((block & 0x7) << 2) | (FKEY_TAB[(fnum >> 7) & 0x0F]);
        int ks = rs == 0 ? 0 : keyCode >> (3 - rs);
        int effectiveRate = (rate << 1) + ks;
        if (effectiveRate >= 96) effectiveRate = 95;

        int step = attack ? AR_TAB[effectiveRate] : DR_TAB[effectiveRate];
        return step;
    }

    private double envelopeToLinear(Operator o, int ams, double lfoVal) {
        int envIdx = o.envCounter >> ENV_LBITS;

        int envVal;
        if (envIdx <= ENV_ATTACK) {
             envVal = ENV_TAB[ENV_ATTACK];
        } else if (envIdx >= ENV_END) {
             envVal = ENV_TAB[ENV_END];
        } else {
             envVal = ENV_TAB[envIdx];
        }

        // Convert envVal (0..4096, 96dB range) to EXP_OUT index (step 0.1875dB)
        // Ratio: (96/4096) / 0.1875 = 1/8.
        int attenIdx = envVal >> 3;
        if (attenIdx >= EXP_OUT.length) attenIdx = EXP_OUT.length - 1;
        double level = EXP_OUT[attenIdx];

        if (o.ssgInverted) level = 1.0 - level;
        if (o.ssgEnabled) level = Math.max(level, 0.5);

        int tlIdx = (o.tl & 0x7F) << 2; // TL step 0.75dB = 4 * 0.1875dB
        int amsIdx = 0;
        if (lfoEnabled && o.am != 0 && ams > 0) {
            double lfoUnipolar = (lfoVal + 1.0) * 0.5;
            double amsDb = AMS_DEPTH[Math.min(ams, AMS_DEPTH.length - 1)] * lfoUnipolar;
            amsIdx = (int) (amsDb / ATT_STEP_DB);
        }

        int totalExtraIdx = tlIdx + amsIdx;
        if (totalExtraIdx >= EXP_OUT.length) totalExtraIdx = EXP_OUT.length - 1;

        return level * EXP_OUT[totalExtraIdx];
    }

    private void tickTimers(int samples) {
        if (samples <= 0) return;
        double ticks = TIMER_BASE * samples;
        if (timerAEnabled && ticks > 0) {
            timerACount -= ticks;
            if (timerACount <= 0) {
                status |= FM_STATUS_TIMERA_BIT_MASK;
                timerACount += timerALoad;
                if ((mode & 0x80) != 0) {
                    csmKeyControl();
                }
            }
        }
        if (timerBEnabled && ticks > 0) {
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
