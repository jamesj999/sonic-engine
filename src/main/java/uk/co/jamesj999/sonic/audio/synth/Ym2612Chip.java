package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.smps.DacData;

public class Ym2612Chip {
    private static final double CLOCK = 7670453.0;
    private static final double SAMPLE_RATE = 44100.0;

    private static final double TWO_PI = Math.PI * 2.0;
    private static final int PHASE_BITS = 12; // match example core (12-bit phase)
    private static final int SINE_STEPS = 1 << PHASE_BITS; // 4096
    private static final int LOG_SINE_LEN = SINE_STEPS / 4; // quarter-wave attenuation indices
    private static final double ATT_STEP_DB = 0.1875; // 3/16 dB per hardware step
    private static final double[] SINE_TABLE = new double[SINE_STEPS];
    private static final int[] LOG_SINE = new int[LOG_SINE_LEN]; // attenuation indices (0..8191)
    private static final double[] EXP_OUT = new double[8192]; // attenuation index -> linear
    private static final double[] ATTACK_RATE = new double[64];
    private static final double[] DECAY_RATE = new double[64];
    private static final int ENV_LEN = 1 << 12; // 12-bit envelope counter resolution
    private static final int ENV_MSK = ENV_LEN - 1;
    private static final double ENV_STEP_DB = 96.0 / ENV_LEN; // 96 dB range over 4096 steps
    private static final int ENV_ATTACK = 0;
    private static final int ENV_DECAY = ENV_LEN;
    private static final int ENV_END = ENV_LEN * 2;
    private static final int[] ENV_TAB = new int[ENV_END + 2]; // envelope curve indices
    private static final int[] DECAY_TO_ATTACK = new int[ENV_LEN];
    private static final int[] SL_TAB = new int[16];
    private static final double AR_RATE = 399128.0;
    private static final double DR_RATE = 5514396.0;
    // Envelope generator reference clock (~10.4 kHz derived from YM core clock / 6 / 144)
    private static final double EG_CLOCK_HZ = (CLOCK / 144.0) / 6.0;
    // Reference AMS/FMS tables from the example core
    private static final int[] LFO_FMS_STEPS = {0, 1, 2, 3, 4, 6, 12, 24};
    private static final double[] LFO_FMS_SCALE = new double[8];
    // Vibrato depths (cents) converted to ratio applied to frequency increment
    private static final double[] FMS_MULT = new double[8];
    private static final int LFO_TABLE_LEN = 256;
    private static final double[] LFO_TABLE = new double[LFO_TABLE_LEN];
    static {
        for (int i = 0; i < SINE_STEPS; i++) {
            SINE_TABLE[i] = Math.sin((TWO_PI * i) / SINE_STEPS);
        }
        // Quarter sine attenuation table using the same dB-derived approach as the reference YM2612 core
        for (int i = 0; i < LOG_SINE.length; i++) {
            double angle = (i + 0.5) * (Math.PI / 2.0) / LOG_SINE.length;
            double s = Math.sin(angle);
            double attDb = -20.0 * Math.log10(Math.max(1e-12, s));
            int idx = (int) Math.round(attDb / ATT_STEP_DB);
            if (idx < 0) idx = 0;
            if (idx >= EXP_OUT.length) idx = EXP_OUT.length - 1;
            LOG_SINE[i] = idx;
        }
        for (int i = 0; i < EXP_OUT.length; i++) {
            double db = i * ATT_STEP_DB;
            EXP_OUT[i] = Math.pow(10.0, -db / 20.0);
        }
        // Envelope curves (attack/decay) similar to reference core
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
            int val = (int) ((db / ENV_STEP_DB) + ENV_DECAY);
            SL_TAB[i] = val;
        }
        SL_TAB[15] = (ENV_LEN - 1) + ENV_DECAY;
        // Precompute EG step rates per effective rate code (0..63) using the example's rate math
        final double envStepCount = 1024.0; // envelope resolution used by the example core
        for (int r = 0; r < 64; r++) {
            double rateMul = 1.0 + ((r & 3) * 0.25); // bits 0-1
            rateMul *= (1 << (r >> 2)); // bits 2-5 shift
            double stepsPerSec = (EG_CLOCK_HZ * rateMul);
            double stepPerSample = (stepsPerSec / AR_RATE);
            ATTACK_RATE[r] = stepPerSample / envStepCount;
            stepPerSample = (stepsPerSec / DR_RATE);
            DECAY_RATE[r] = stepPerSample / envStepCount;
        }
        // Convert vibrato depths (cents) to frequency multipliers
        double[] fmsCents = {0, 3.4, 6.7, 10.0, 14.0, 20.0, 40.0, 80.0};
        for (int i = 0; i < fmsCents.length; i++) {
            FMS_MULT[i] = Math.pow(2.0, fmsCents[i] / 1200.0) - 1.0;
        }
        // Reference FMS scaling from the example core (scaled down to a ratio)
        final double lfoFmsBase = 0.05946309436 * 0.0338;
        final double lfoFmsDiv = 1 << 9; // LFO_FMS_LBITS
        for (int i = 0; i < LFO_FMS_STEPS.length; i++) {
            LFO_FMS_SCALE[i] = (LFO_FMS_STEPS[i] * lfoFmsBase) / lfoFmsDiv;
        }
        for (int i = 0; i < LFO_TABLE_LEN; i++) {
            LFO_TABLE[i] = Math.sin((TWO_PI * i) / LFO_TABLE_LEN);
        }
    }

    /**
     * SSG-EG peak handling
     */
    private void handleSsgPeak(Operator o, boolean atTop) {
        if (!o.ssgEnabled) {
            return;
        }

        if (o.ssgAlternate) {
            o.ssgInverted = !o.ssgInverted;
        }

        if (o.ssgHoldMode) {
            o.ssgHold = true;
            o.envCounter = ENV_END;
            o.envState = EnvState.IDLE;
            return;
        }

        // Loop: Reset to Attack
        o.envCounter = ENV_ATTACK;
        o.envState = EnvState.ATTACK;
    }

    // LFO frequency table (Hz) from YM2612 docs
    private static final double[] LFO_FREQ = {3.98, 5.56, 6.02, 6.37, 6.88, 9.63, 48.1, 72.2};
    private static final int[] LFO_AMS_TAB = {31, 4, 1, 0};
    private static final int[] FKEY_TAB = {
            0, 0, 0, 0,
            0, 0, 0, 1,
            2, 3, 3, 3,
            3, 3, 3, 3
    };
    private static final int[] DT_DEF_TAB = {
            // FD = 0
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,

            // FD = 1
            0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2,
            2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 8, 8,

            // FD = 2
            1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5,
            5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13, 14, 16, 16, 16, 16,

            // FD = 3
            2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7,
            8, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 20, 22, 22, 22, 22
    };
    // Tremolo depths (approx dB) applied as attenuation
    private static final double[] AMS_DEPTH = {0.0, 1.4, 5.9, 11.8};
    // Detune tables (approximate semitone offsets as multipliers)
    private static final double[] DETUNE = {0.0, 0.004, 0.008, 0.012, -0.012, -0.008, -0.004, 0.0};

    // Output gain reduced to improve headroom; log domain summation allows high peaks.
    // Hardware dynamic range is ~53dB (9 bits DAC + shifts).
    // We target a safe range.
    private static final double OUTPUT_GAIN = 480.0;

    // Optional one-pole low-pass to approximate analog output smoothing; can be tuned/disabled
    private static final double LPF_CUTOFF_HZ = 22000.0; // Bumped up slightly to retain brightness
    private static final double LPF_ALPHA = LPF_CUTOFF_HZ / (LPF_CUTOFF_HZ + SAMPLE_RATE);
    // Timer clock: (Clock / Rate) / 144 * 4096 like reference; keep as double for fractional accumulation
    private static final double TIMER_BASE = (CLOCK / SAMPLE_RATE) * (4096.0 / 144.0);
    // YM internal cycles per output sample (Clock / 6) / sampleRate
    private static final double YM_CYCLES_PER_SAMPLE = (CLOCK / 6.0) / SAMPLE_RATE;
    private static final double DAC_BASE_RATE = 275350.0;
    private static final double DAC_RATE_DIV = 10.08;
    private static final int FM_STATUS_BUSY_BIT_MASK = 0x80;
    private static final int FM_STATUS_TIMERA_BIT_MASK = 0x01;
    private static final int FM_STATUS_TIMERB_BIT_MASK = 0x02;

    // Busy cycle durations (approximate based on YM2612 manual/hardware tests)
    // Most address writes take ~32 cycles, data writes take more.
    private static final int BUSY_CYCLES_ADDR = 32;
    private static final int BUSY_CYCLES_DATA = 47;

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
    private int mode; // Added

    // LFO state
    private double lfoPos;
    private double lfoStep;
    private boolean lfoEnabled;
    private int lfoFreqIdx;
    private double lpfStateL;
    private double lpfStateR;

    private enum EnvState { ATTACK, DECAY1, DECAY2, RELEASE, IDLE }

    private static class Operator {
        int dt1, mul;
        int tl;
        int rs, ar;
        int am, d1r;
        int d2r;
        int d1l, rr;
        int ssgEg;

        EnvState envState = EnvState.IDLE;
        int envCounter = ENV_END; // envelope position in ENV_TAB domain
        double phase;
        double lastOutput;
        boolean ssgInverted;
        boolean ssgHold;
        boolean ssgEnabled;
        boolean ssgAlternate;
        boolean ssgHoldMode;
    }

    private static class Channel {
        int fNum;
        int block;
        boolean specialMode;
        final int[] slotFnum = new int[4];
        final int[] slotBlock = new int[4];

        int feedback, algo;
        int ams, fms;
        int pan; // bits: L/R
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

    private final Channel[] channels = new Channel[6];
    private boolean channel3SpecialMode;

    public Ym2612Chip() {
        for (int i = 0; i < 6; i++) {
            channels[i] = new Channel();
        }
        reset();
    }

    /**
     * Resets chip state to power-on defaults so sequencing code can reinitialise safely.
     */
    public void reset() {
        status = 0;
        mode = 0; // Added
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
            ch.pan = 0x3; // default both speakers
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
                o.envCounter = ENV_END;
                o.phase = 0;
                o.lastOutput = 0;
                o.ssgInverted = false;
                o.ssgHold = false;
                o.ssgEnabled = false;
                o.ssgAlternate = false;
                o.ssgHoldMode = false;
            }
        }
    }

    private double sine(double phase) {
        int idx = (int) ((phase / TWO_PI) * SINE_STEPS) & (SINE_STEPS - 1);
        return SINE_TABLE[idx];
    }

    public int readStatus() {
        // Busy flag could be clocked down with CPU cycles; we approximate with a simple decrement per render step
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
            double rateHz = DAC_BASE_RATE / (DAC_RATE_DIV * Math.max(1, rateByte));
            this.dacStep = Math.max(0.0001, rateHz / SAMPLE_RATE);
        }
    }

    // New Helper methods
    private void keyOn(Channel ch, int opIdx) {
        Operator o = ch.ops[opIdx];
        if (o.ar == 0) {
            o.envState = EnvState.IDLE;
            o.envCounter = ENV_END;
            return;
        }
        ch.attackRamp = 0.0;
        if (o.envState == EnvState.RELEASE || o.envState == EnvState.IDLE) {
            int atten = ENV_TAB[o.envCounter <= ENV_ATTACK ? ENV_ATTACK : Math.min(o.envCounter, ENV_END)];
            if (atten >= ENV_LEN) atten = ENV_LEN - 1;
            o.envCounter = DECAY_TO_ATTACK[atten];
            o.envState = EnvState.ATTACK;
            o.ssgHold = false;
            o.ssgInverted = (o.ssgEg & 0x04) != 0;
        }
    }

    private void keyOff(Channel ch, int opIdx) {
        Operator o = ch.ops[opIdx];
        if (o.envState != EnvState.RELEASE) {
            if (o.envCounter < ENV_DECAY) {
                int volume = ENV_TAB[o.envCounter];
                o.envCounter = ENV_DECAY + volume;
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

    /**
     * Loads a Sonic 2 format voice (19 bytes) into the specified channel.
     */
    public void setInstrument(int chIdx, byte[] voice) {
        if (chIdx < 0 || chIdx >= 6 || voice.length < 1) return;
        Channel ch = channels[chIdx];

        // Allow both 19-byte (Sonic 2 TL-less) and 25-byte (full TL) voices.
        boolean hasTl = voice.length >= 25;
        int expectedLen = hasTl ? 25 : 19;
        if (voice.length < expectedLen) {
            // Pad shorter voices to expected length to avoid bounds issues
            byte[] padded = new byte[expectedLen];
            System.arraycopy(voice, 0, padded, 0, voice.length);
            voice = padded;
        }

        // Helper to safely read
        final byte[] v = voice;
        java.util.function.IntUnaryOperator get = (idx) -> (idx >= 0 && idx < v.length) ? (v[idx] & 0xFF) : 0;

        int val00 = get.applyAsInt(0);
        ch.feedback = (val00 >> 3) & 7;
        ch.algo = val00 & 7;

        for (int op = 0; op < 4; op++) {
            Operator o = ch.ops[op];
            int dtmul = get.applyAsInt(1 + op);
            o.dt1 = (dtmul >> 4) & 7;
            o.mul = dtmul & 0xF;

            int tlIdxBase = 5;
            int rsArBase = hasTl ? 9 : 5;
            int amD1rBase = rsArBase + 4;
            int d2rBase = amD1rBase + 4;
            int d1lRrBase = d2rBase + 4;

            int tlVal = hasTl ? get.applyAsInt(tlIdxBase + op) : 0;
            o.tl = tlVal & 0x7F;

            int rsar = get.applyAsInt(rsArBase + op);
            o.rs = (rsar >> 6) & 3;
            o.ar = rsar & 0x1F;

            int amd1r = get.applyAsInt(amD1rBase + op);
            o.am = (amd1r >> 7) & 1;
            o.d1r = amd1r & 0x1F;

            int d2r = get.applyAsInt(d2rBase + op);
            o.d2r = d2r & 0x1F;

            int d1lrr = get.applyAsInt(d1lRrBase + op);
            o.d1l = (d1lrr >> 4) & 0xF;
            o.rr = d1lrr & 0xF;

            o.envState = EnvState.IDLE;
            o.envCounter = ENV_END;
            o.phase = 0;
            o.lastOutput = 0;
            o.ssgInverted = false;
            o.ssgHold = false;
            o.ssgEnabled = false;
            o.ssgAlternate = false;
            o.ssgHoldMode = false;
        }
    }

    public void write(int port, int reg, int val) {
        // Address writes are fast, Data writes are slow.
        // The 'write' method here is abstracting the two-step process (address write, then data write).
        // But in reality, typical emulation interfaces just call 'write(port, reg, val)' to write 'val' to 'reg'.
        // So this is a Data write.
        // We should add the data write penalty.
        busyCycles = BUSY_CYCLES_DATA;

        // DAC enable
        if (port == 0 && reg == 0x2B) {
            dacEnabled = (val & 0x80) != 0;
            return;
        }

        // LFO freq (0x22)
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

        // Timer A/B and mode/status control
        if (port == 0 && reg == 0x24) { // Timer A high
            timerAPeriod = (timerAPeriod & 0x03) | (val << 2);
            timerALoad = (1024 - timerAPeriod) << 12;
            if (timerAEnabled) timerACount = timerALoad;
            return;
        }
        if (port == 0 && reg == 0x25) { // Timer A low
            timerAPeriod = (timerAPeriod & 0x3FC) | (val & 0x03);
            timerALoad = (1024 - timerAPeriod) << 12;
            if (timerAEnabled) timerACount = timerALoad;
            return;
        }
        if (port == 0 && reg == 0x26) { // Timer B
            timerBPeriod = val & 0xFF;
            timerBLoad = (256 - timerBPeriod) << (4 + 12);
            if (timerBEnabled) timerBCount = timerBLoad;
            return;
        }
        if (port == 0 && reg == 0x2A) { // DAC data port (unsigned PCM -> convert to signed)
            dacLatchedValue = (val & 0xFF) - 128;
            dacHasLatched = true;
            currentDacSampleId = -1; // streamed sample overrides note-based DAC
            return;
        }
        if (port == 0 && reg == 0x27) { // Timer control/reset
            if (((mode ^ val) & 0x40) != 0) {
                // Phase reset logic when CT3 mode toggles (fix for SoR2 punch sound)
                // The reference clears Finc to force recalculation.
                // Since we calculate phase increment on the fly, we should ensure
                // that any accumulated phase or state dependent on the old mode is reset or handled.
                // However, the reference "Finc = -1" suggests it just wants to pick up the new freq immediately.
                // Our renderChannel does that every sample.
                // But some docs suggest a phase reset or specific behavior on transition.
                // Let's explicitly reset the phase of Channel 2 operators to be safe and match "reset" intent.
                for (Operator op : channels[2].ops) {
                    op.phase = 0;
                }
            }
            mode = val; // Store mode
            // Bit 6 controls channel 3 special mode (CT3)
            channel3SpecialMode = (val & 0x40) != 0;
            channels[2].specialMode = channel3SpecialMode;
            timerAEnabled = (val & 0x01) != 0;
            timerBEnabled = (val & 0x02) != 0;
            if (timerAEnabled) timerACount = timerALoad;
            if (timerBEnabled) timerBCount = timerBLoad;
            if ((val & 0x10) != 0) status &= ~FM_STATUS_TIMERA_BIT_MASK; // reset A flag
            if ((val & 0x20) != 0) status &= ~FM_STATUS_TIMERB_BIT_MASK; // reset B flag
            return;
        }

        // Key on/off
        if (port == 0 && reg == 0x28) {
            int chIdx = val & 0x07;
            if (chIdx == 3) return; // invalid channel index
            if (chIdx >= 4) chIdx -= 1; // skip shadow channel 3

            // Allow channel 2 (index 2) to be processed normally.
            // If in CT3 mode, the slots are controlled individually but the register write is same format.
            // But wait, key on/off for CT3 might be different?
            // "Channel 3 special mode uses per-slot FNUM/BLOCK; individual slots handled below."
            // But Key On is still via 0x28.

            if (chIdx < 0 || chIdx > 5) return;

            int opMask = (val >> 4) & 0x0F; // bits 4-7
            Channel ch = channels[chIdx];
            for (int i = 0; i < 4; i++) {
                boolean on = ((opMask >> i) & 1) != 0;
                if (on) keyOn(ch, i);
                else keyOff(ch, i);
            }
            return;
        }

        // Frequency registers
        if (reg >= 0xA0 && reg <= 0xA2) {
            int ch = (port * 3) + (reg - 0xA0);
            if (ch < 6) {
                channels[ch].fNum = ((channels[ch].fNum & 0x700) | (val & 0xFF)) & 0x7FF;
                // mirror to per-slot defaults
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
        // Channel 3 special mode per-slot frequency (port 0 only, channel index 2)
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

        // Channel feedback/algo (B0-B2 per port)
        if (reg >= 0xB0 && reg <= 0xB2) {
            int ch = (port * 3) + (reg - 0xB0);
            if (ch < 6) {
                Channel c = channels[ch];
                c.feedback = (val >> 3) & 0x7;
                c.algo = val & 0x7;
            }
            return;
        }
        // Channel pan/AMS/FMS (B4-B6 per port)
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

        // Operator parameter writes
        if ((reg >= 0x30 && reg <= 0x9F)) {
            int slot = (reg & 0x0C) >> 2;
            // Slot mapping: 0->op1, 1->op3, 2->op2, 3->op4
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
                case 0x30: // DT/MUL
                    o.dt1 = (val >> 4) & 7;
                    o.mul = val & 0xF;
                    break;
                case 0x40: // TL
                    o.tl = val & 0x7F;
                    break;
                case 0x50: // RS/AR
                    o.rs = (val >> 6) & 3;
                    o.ar = val & 0x1F;
                    break;
                case 0x60: // AM/D1R
                    o.am = (val >> 7) & 1;
                    o.d1r = val & 0x1F;
                    break;
                case 0x70: // D2R
                    o.d2r = val & 0x1F;
                    break;
                case 0x80: // D1L/RR
                    o.d1l = (val >> 4) & 0xF;
                    o.rr = val & 0xF;
                    break;
                case 0x90: // SSG-EG (not fully implemented)
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
        // Legacy mono render; averages stereo mix down
        for (int i = 0; i < buffer.length; i++) {
            double lfoVal = stepLfo();

            double mixL = 0;
            double mixR = 0;

            double dacOut = renderDac();
            Channel dacCh = channels[5];
            boolean dacLeft = (dacCh.pan & 0x2) != 0;
            boolean dacRight = (dacCh.pan & 0x1) != 0;
            if (dacLeft || (!dacLeft && !dacRight)) mixL += dacOut;
            if (dacRight || (!dacLeft && !dacRight)) mixR += dacOut;

            // FM
            for (int ch = 0; ch < 6; ch++) {
                if (ch == 5 && dacEnabled) {
                    continue; // DAC occupies channel 5 when enabled
                }
                double out = renderChannel(ch, lfoVal);
                boolean left = (channels[ch].pan & 0x2) != 0;
                boolean right = (channels[ch].pan & 0x1) != 0;
                if (left) mixL += out;
                if (right) mixR += out;
                if (!left && !right) {
                    mixL += out * 0.5;
                    mixR += out * 0.5;
                }
            }

            // Optional analog-ish low-pass smoothing
            if (LPF_ALPHA > 0) {
                lpfStateL += (mixL - lpfStateL) * LPF_ALPHA;
                lpfStateR += (mixR - lpfStateR) * LPF_ALPHA;
                mixL = lpfStateL;
                mixR = lpfStateR;
            }

            // mono buffer: average L/R
            double mono = (mixL + mixR) * 0.5;

            int s = (int) Math.max(-32768, Math.min(32767, mono));
            buffer[i] = (short) (buffer[i] + s);
            tickTimers(1);
        }
    }

    /**
     * Stereo render: fills left/right buffers with mixed output respecting per-channel pan.
     * Buffer lengths must match; only the overlapped region is written.
     */
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
            if (dacLeft || (!dacLeft && !dacRight)) mixL += dacOut;
            if (dacRight || (!dacLeft && !dacRight)) mixR += dacOut;

            for (int ch = 0; ch < 6; ch++) {
                if (ch == 5 && dacEnabled) continue;
                double out = renderChannel(ch, lfoVal);
                boolean left = (channels[ch].pan & 0x2) != 0;
                boolean right = (channels[ch].pan & 0x1) != 0;
                if (left) mixL += out;
                if (right) mixR += out;
                if (!left && !right) {
                    mixL += out * 0.5;
                    mixR += out * 0.5;
                }
            }

            if (LPF_ALPHA > 0) {
                lpfStateL += (mixL - lpfStateL) * LPF_ALPHA;
                lpfStateR += (mixR - lpfStateR) * LPF_ALPHA;
                mixL = lpfStateL;
                mixR = lpfStateR;
            }

            int outL = (int) Math.max(-32768, Math.min(32767, mixL));
            int outR = (int) Math.max(-32768, Math.min(32767, mixR));
            leftBuf[i] = (short) (leftBuf[i] + outL);
            rightBuf[i] = (short) (rightBuf[i] + outR);
            tickTimers(1);
        }
    }

    private double renderDac() {
        if (!dacEnabled) {
            return 0;
        }
        Channel dacCh = channels[5];
        int sample = 0;
        if (currentDacSampleId != -1 && dacData != null) {
            byte[] data = dacData.samples.get(currentDacSampleId);
            if (data != null && dacPos < data.length) {
                int idx = (int) dacPos;
                double frac = dacPos - idx;
                int s1 = (data[idx] & 0xFF) - 128;
                int s2 = (idx + 1 < data.length) ? ((data[idx + 1] & 0xFF) - 128) : s1;
                double lerp = s1 * (1.0 - frac) + s2 * frac;
                sample = (int) Math.round(lerp); // already signed
                dacPos += dacStep;
            }
        } else if (dacHasLatched) {
            sample = dacLatchedValue;
        } else {
            return 0;
        }
        // Output linear signed sample (skip ladder-effect quantization for now to reduce distortion).
        return sample * 256.0;
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
        if (!hasActiveOperator) {
            return 0;
        }
        if (ch.attackRamp < 1.0) {
            ch.attackRamp = Math.min(1.0, ch.attackRamp + 0.003);
        }

        // Precompute frequency in Hz
        int fnum = ch.fNum & 0x7FF;
        int block = ch.block & 0x7;
        double baseFreq = (fnum * CLOCK) / (144.0 * (1 << (20 - block)));
        // Channel 3 special mode uses per-slot FNUM/BLOCK; individual slots handled below.
        // Apply vibrato using reference FMS scale, fall back to cents table if missing
        double fms = LFO_FMS_SCALE[Math.min(ch.fms, LFO_FMS_SCALE.length - 1)];
        if (fms == 0) {
            fms = FMS_MULT[Math.min(ch.fms, FMS_MULT.length - 1)];
        }
        if (lfoEnabled) {
            baseFreq *= (1.0 + (fms * lfoVal));
        }
        if (baseFreq <= 0) return 0;

        double[] opOut = new double[4];
        // Feedback uses a right-shift style gain (reference core maps FB bits to shifts)
        int fbShift = (ch.feedback == 0) ? 9 : Math.max(2, 9 - ch.feedback);
        double feedback = 1.0 / (1 << fbShift);

        // Operator 0 feedback
        Operator op0 = ch.ops[0];
        double fb = (op0.lastOutput + ch.feedbackHist1) * feedback;
        ch.feedbackHist1 = ch.feedbackHist2;
        ch.feedbackHist2 = op0.lastOutput;

        // Render operators
        // Evaluate operators with simple modulation routing based on algo
        // Order ops to ensure modulators computed before carriers
        int[][] algoRoutes = {
                {0, 1, 2, 3}, // 0: 1->2->3->4
                {0, 1, 2, 3}, // 1: (1+2)->3->4
                {0, 2, 1, 3}, // 2: (1)+(2->3)->4
                {0, 1, 2, 3}, // 3: (1->2)+(3)->4 (2 and 3 feed 4)
                {0, 1, 2, 3}, // 4: (1->2) + (3->4)
                {0, 1, 2, 3}, // 5: 1 modulates 2/3/4
                {0, 1, 2, 3}, // 6: 1->2 plus 3 and 4 carriers
                {0, 1, 2, 3}  // 7: all carriers
        };

        // Precompute modulation inputs
        for (int idx : algoRoutes[Math.min(ch.algo, 7)]) {
            Operator o = ch.ops[idx];

            int fnumUse = (chIdx == 2 && channel3SpecialMode) ? ch.slotFnum[idx] : fnum;
            int blockUse = (chIdx == 2 && channel3SpecialMode) ? ch.slotBlock[idx] : block;
            fnumUse &= 0x7FF;
            blockUse &= 0x7;
            double opBaseHz = (fnumUse * CLOCK) / (144.0 * (1 << (20 - blockUse)));
            opBaseHz *= (1.0 + (fms * lfoVal));
            double freq = opBaseHz * ((o.mul == 0) ? 0.5 : o.mul);
            double inc = (freq * TWO_PI) / SAMPLE_RATE;

            // Apply detune as an additive increment scaled similarly to the reference DT table
            int kc = Math.min(31, (blockUse << 2) + FKEY_TAB[(fnumUse >> 7) & 0x0F]);
            int dtEntry = DT_DEF_TAB[(o.dt1 & 3) * 32 + kc];
            if (dtEntry != 0) {
                int sign = (o.dt1 >= 4) ? -1 : 1;
                double dtHz = (dtEntry * CLOCK) / (144.0 * (1 << (20 - blockUse)));
                inc += sign * ((dtHz * TWO_PI) / SAMPLE_RATE);
            }

            double modIn = computeModulationInput(ch.algo, idx, opOut, fb);

            o.phase += inc + modIn;
            if (o.phase > TWO_PI) o.phase -= TWO_PI;
            if (o.phase < 0) o.phase += TWO_PI;

            stepEnvelope(o, ch, idx, blockUse, fnumUse);

            double env = envelopeToLinear(o, ch.ams, lfoVal, idx);
            // Convert phase to quarter-sine index and use log/exp approximation
            double phaseNorm = (o.phase / TWO_PI) * SINE_STEPS;
            int p = ((int) phaseNorm) & (SINE_STEPS - 1);
            int quadrant = p >> (PHASE_BITS - 2);
            int qIndex = p & (LOG_SINE_LEN - 1);
            if ((quadrant & 1) != 0) {
                qIndex = (LOG_SINE_LEN - 1) - qIndex; // mirror for quadrants 1/3
            }
            int attenIdx = LOG_SINE[qIndex];
            double mag = EXP_OUT[attenIdx];
            double sample = mag * env * ((quadrant >= 2) ? -1.0 : 1.0);
            o.lastOutput = sample;
            opOut[idx] = sample;
        }

        double carrier = computeCarrierSum(ch.algo, opOut);
        carrier *= ch.attackRamp;

        // Apply simple pan scaling (L/R bits)
        // pan bits: D7 = Left, D6 = Right (we stored two-bit value)
        boolean left = (ch.pan & 0x2) != 0;
        boolean right = (ch.pan & 0x1) != 0;
        double panGain = (left && right) ? 1.0 : 0.7; // crude stereo spread
        return carrier * OUTPUT_GAIN * panGain;
    }

    // Package-private for test visibility; mirrors YM2612 algorithm routing.
    public static double computeModulationInput(int algo, int opIndex, double[] opOut, double feedback) {
        if (opIndex == 0) {
            return feedback;
        }
        return switch (algo) {
            case 0 -> (opIndex == 1 ? opOut[0] : opIndex == 2 ? opOut[1] : opOut[2]);              // 1->2->3->4
            case 1 -> (opIndex == 2 ? opOut[0] + opOut[1] : opIndex == 3 ? opOut[2] : 0);          // (1+2)->3->4
            case 2 -> (opIndex == 2 ? opOut[1] : opIndex == 3 ? opOut[2] + opOut[0] : 0);          // (1)+(2->3)->4
            case 3 -> (opIndex == 1 ? opOut[0] : opIndex == 3 ? opOut[1] + opOut[2] : 0);          // (1->2)+(3)->4
            case 4 -> (opIndex == 1 ? opOut[0] : opIndex == 3 ? opOut[2] : 0);                     // (1->2) and (3->4)
            case 5 -> (opIndex == 1 || opIndex == 2 || opIndex == 3 ? opOut[0] : 0);               // 1 modulates 2/3/4
            case 6 -> (opIndex == 1 ? opOut[0] : 0);                                               // 1->2 plus 3/4 carriers
            case 7 -> 0;                                                                           // all carriers
            default -> 0;
        };
    }

    // Package-private for test visibility; returns carrier mix for the given algo.
    public static double computeCarrierSum(int algo, double[] opOut) {
        return switch (algo) {
            case 0 -> opOut[3];                                   // 1->2->3->4
            case 1 -> opOut[3];                                   // (1+2)->3->4
            case 2 -> opOut[3];                                   // (1)+(2->3)->4
            case 3 -> opOut[3];                                   // (1->2)+(3)->4
            case 4 -> opOut[1] + opOut[3];                        // (1->2) + (3->4)
            case 5 -> opOut[1] + opOut[2] + opOut[3];             // 1 modulates 2/3/4 (carriers 2,3,4)
            case 6 -> opOut[1] + opOut[2] + opOut[3];             // 1->2 plus 3 and 4 carriers
            case 7 -> opOut[0] + opOut[1] + opOut[2] + opOut[3];  // all carriers
            default -> opOut[3];
        };
    }

    private void stepEnvelope(Operator o, Channel ch, int opIndex, int block, int fnum) {
        boolean ssgEnabled = o.ssgEnabled;
        if (o.ssgHold) {
            return;
        }
        switch (o.envState) {
            case ATTACK -> {
                int step = egStep(o.ar, o.rs, block, fnum, true);
                if (step <= 0) {
                    if (o.ar == 0) {
                        o.envState = EnvState.IDLE;
                        o.envCounter = ENV_END;
                        return;
                    }
                    step = 1;
                }
                // Slow down low AR values so the envelope audibly rises over the first few frames.
                if (o.ar < 16) {
                    step = Math.max(1, step / 16);
                } else if (o.ar < 24) {
                    step = Math.max(1, step / 8);
                }
                o.envCounter += step;
                if (o.envCounter >= ENV_DECAY || o.ar == 31) {
                    o.envCounter = ENV_DECAY;
                    o.envState = EnvState.DECAY1;
                    if (ssgEnabled) handleSsgPeak(o, true);
                }
            }
            case DECAY1 -> {
                int step = egStep(o.d1r, o.rs, block, fnum, false);
                o.envCounter += step;
                int sustain = SL_TAB[Math.min(15, o.d1l)];
                if (!ssgEnabled && o.envCounter >= sustain) {
                    o.envCounter = sustain;
                    o.envState = EnvState.DECAY2;
                } else if (ssgEnabled && o.envCounter >= ENV_END) {
                    handleSsgPeak(o, false);
                }
            }
            case DECAY2 -> {
                int step = egStep(o.d2r, o.rs, block, fnum, false);
                o.envCounter += step;
                if (o.envCounter >= ENV_END) {
                    if (ssgEnabled) {
                        handleSsgPeak(o, false);
                    } else {
                        o.envCounter = ENV_END;
                        o.envState = EnvState.IDLE;
                    }
                }
            }
            case RELEASE -> {
                int step = egStep(Math.max(1, o.rr), o.rs, block, fnum, false);
                o.envCounter += step;
                if (o.envCounter >= ENV_END) {
                    if (ssgEnabled) {
                        handleSsgPeak(o, false);
                    } else {
                        o.envCounter = ENV_END;
                        o.envState = EnvState.IDLE;
                    }
                }
            }
            case IDLE -> o.envCounter = ENV_END;
        }
    }

    private int egStep(int rate, int rs, int block, int fnum, boolean attack) {
        if (rate <= 0) return 0;
        int keyCode = ((block & 0x7) << 2) | (FKEY_TAB[(fnum >> 7) & 0x0F]);
        int ks = rs == 0 ? 0 : keyCode >> (3 - rs);
        // Hardware uses 5-bit rate value (0-31) expanded with key scaling
        int effectiveRate = Math.min(63, (rate << 1) + ks);
        if (effectiveRate <= 0) return 0;
        double step = attack ? ATTACK_RATE[effectiveRate] : DECAY_RATE[effectiveRate];
        int envStep = (int) Math.max(1, Math.round(step * ENV_LEN));
        if (attack && rate < 31) {
            // Attack curves accelerate as they approach peak; approximate using decay->attack remap
            envStep = Math.max(1, DECAY_TO_ATTACK[Math.min(envStep, DECAY_TO_ATTACK.length - 1)]);
        }
        return envStep;
    }

    private double envelopeToLinear(Operator o, int ams, double lfoVal, int opIndex) {
        int envIdx = Math.max(0, Math.min(ENV_END, o.envCounter));
        int envVal = ENV_TAB[envIdx <= ENV_ATTACK ? ENV_ATTACK : Math.min(envIdx, ENV_END)];
        double level = 1.0 - (envVal * ENV_STEP_DB / 96.0);
        level = Math.max(0.0, Math.min(1.0, level));
        if (o.ssgInverted) {
            level = 1.0 - level;
        }
        // Apply total level attenuation using TL (~0.75dB steps)
        double tlDb = (o.tl & 0x7F) * 0.75;

        // AMS (Tremolo)
        double amsDb = 0.0;
        if (lfoEnabled && o.am != 0 && ams > 0) {
            double lfoUnipolar = (lfoVal + 1.0) * 0.5;
            amsDb = AMS_DEPTH[Math.min(ams, AMS_DEPTH.length - 1)] * lfoUnipolar;
        }

        double linear = level * Math.pow(10.0, -(tlDb + amsDb) / 20.0);
        return linear;
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
