package com.openggf.audio.synth.fast;

import java.util.Arrays;

/**
 * Register-level YM2612 model: registers in, six channel outputs per internal
 * frame out. Built clean-room from the techniques specification
 * ({@code docs/architecture/designs/2026-09-06-fast-fm-core-design.md}) and
 * public hardware documentation (Yamaha YM2608/YM2612 manuals: register map,
 * detune and LFO tables; Nemesis's SpritesMind envelope/phase research:
 * attenuation scale, rate formula, counter-shift and increment tables, attack
 * formula). The initial author used prose summaries of ymfm and fmgen
 * reproduced as techniques 1–9 in the design document. Subsequent timing
 * corrections use public-facade PCM probes and public pipeline research;
 * see the validation record for the actual source-exposure boundary.
 *
 * <p>Not modelled: operator pipelining within a frame, bus timing, the busy
 * flag, LSI test registers, and the analog output stage. Everything else the
 * SMPS drivers touch — key on/off, DT/MUL, TL, RS/AR, AM/D1R, D2R, SL/RR,
 * SSG-EG, F-number/block including channel-3 special mode, feedback and the
 * eight algorithms, L/R/AMS/PMS, the LFO, timers A/B with CSM, and the DAC —
 * is.
 */
public final class FastYm2612Dsp implements FmDsp {
    // ------------------------------------------------------------ tables

    /** Quarter-wave log2-sine, 4.8 fixed point (12 bits), indexed by 8-bit phase. */
    private static final int[] LOG_SIN = new int[256];
    /** 2^(x/256) - 1 scaled to 10 bits, indexed by the fractional attenuation. */
    private static final int[] EXP = new int[256];
    /** Half-wave log2-sine: {@code LOG_SIN} with the second-quarter reflection folded in, indexed by 9-bit phase. */
    private static final int[] LOG_SIN_HALF = new int[512];
    /** {@code (EXP[255 - f] + 1024) << 2}: the 14-bit mantissa indexed directly by the attenuation fraction. */
    private static final int[] EXP_MANTISSA = new int[256];
    /**
     * Detune in phase-increment LSB by key code and |DT|. Measured through
     * the accurate public facade: isolated MUL1 carrier, all 32 keycodes,
     * DT0..3, 160,000 samples per tone. Subtract DT0 pitch and convert by
     * 2^20/internalRate; maximum integer-rounding residual was 0.00554 LSB.
     * See the fast-FM validation record and TestFastFmDetune.
     */
    private static final int[][] DETUNE = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 8, 8},
        {1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13, 14, 16, 16, 16, 16},
        {2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 20, 22, 22, 22, 22},
    };
    /** Key-code low bits from the top four F-number bits (manual, "note" bits). */
    private static final int[] FNUM_NOTE = {0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 3, 3, 3, 3, 3};
    /** Envelope increment per rate (64) and counter phase (8); rates 0..47 repeat by rate & 3. */
    private static final int[][] EG_INCREMENT = new int[64][];
    /** Envelope counter shift per rate: how many counter LSBs must be zero before a step. */
    private static final int[] EG_SHIFT = new int[64];
    /**
     * LFO prescaler terminal values per rate. A free-running frame counter
     * (running whether or not the LFO is enabled, never reset by the 0x22
     * write) advances the 128-step LFO and restarts at one when it CONTAINS the
     * value's bits, {@code (counter & value) == value}, yielding steady
     * periods 108, 77, 71, 67, 62, 44, 8 and 5 frames.
     * Measured on the cycle-exact oracle by a global phase fit of a PM-only
     * probe tone (residual 2e-4 cycles): the periods at every rate, and the
     * first-step delay after enabling at different times with a different
     * previous rate (old counter 84 meets 62 at 126, 77 at 93, 5 at 85), which
     * a modulo or equality counter cannot reproduce.
     */
    private static final int[] LFO_STEP_MASK = {108, 77, 71, 67, 62, 44, 8, 5};
    /**
     * PM increment admission, logical OP1..4. Global LFO changes reach the
     * carrier output after three frames: phase lookup adds one, and OP1's
     * carrier history adds another. All-channel high-FNUM wrap probes expose
     * a one-frame error hidden by ordinary small pitch changes.
     */
    private static final int[] LFO_PM_ADMISSION_FRAMES = {1, 2, 2, 2};
    /** Shift of the doubled 0..63 AM triangle: exact public-tone depths 0, 15, 63, 126. */
    private static final int[] AM_SHIFT = {8, 3, 1, 0};
    /**
     * PM depth code per PMS and quarter-cycle position. The waveform is
     * stepped, not a linear triangle, holds two zero positions before each
     * ramp, and mirrors in the second and fourth quarters. The code's three
     * bits select the top seven F-number bits ({@code h = fnum >> 4}) whole,
     * halved and quartered, each truncated before the sum:
     * {@code sum = (q & 4 ? h : 0) + (q & 2 ? h >> 1 : 0) + (q & 1 ? h >> 2 : 0)};
     * the offset in half F-number steps is {@code (sum << PM_SHIFT[pms]) >> 2}.
     * Measured on the cycle-exact oracle by plateau tables at every PMS for
     * single-bit F-numbers and the composites 38, 47, 63 and 90: a floored
     * product agrees except where the halved and quartered terms both truncate
     * (PMS 7 code 3: 47 gives 34 not 35, 63 gives 46 not 47), and a per-bit
     * sum fails at PMS 4 code 3 (63 gives 11 not 10). PMS 7 peaks at +4.62 %,
     * the manual's ±80 cents (2^(80/1200) = 1.0473).
     */
    private static final int[][] PM_QUARTER_CODE = {
        {0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 1, 1},
        {0, 0, 0, 1, 1, 1, 2, 2},
        {0, 0, 1, 1, 2, 2, 3, 3},
        {0, 0, 1, 2, 2, 2, 3, 4},
        {0, 0, 2, 3, 4, 4, 5, 6},
        {0, 0, 2, 3, 4, 4, 5, 6},
        {0, 0, 2, 3, 4, 4, 5, 6},
    };
    /** Left shift of the PM sum before the final quarter: PMS 6 doubles and PMS 7 quadruples PMS 5. */
    private static final int[] PM_SHIFT = {0, 0, 0, 0, 0, 0, 1, 2};
    /** PM depth code per PMS and 32-position LFO cycle, negative in the second half. */
    private static final int[][] PM_CODE = new int[8][32];
    private static final int LFO_STEPS = 128;
    private static final int PM_STEPS = 32;
    /** The envelope clock is master/432: one tick every three internal frames (Nemesis, later digital measurements). */
    private static final int EG_TICK_MASTER_CYCLES = 432;
    private static final int MASTER_CYCLES_PER_FRAME = 144;
    /** SSG-EG multiplies the decay-side increments by four. */
    private static final int SSG_STEP_MULTIPLIER = 4;
    /** Sustain level 15 is the full 5-bit code 31 in 3 dB steps (992), not the attenuation ceiling. */
    private static final int SUSTAIN_LEVEL_15 = 31 << 5;
    // Sega YM2612 manual p12: 18/288 us per timer unit at 8 MHz. With
    // 144 input clocks per FM frame these are 1/16 frames, not EG ticks.
    // https://www.smspower.org/maxim/Documents/YM2612
    private static final int TIMER_A_FRAMES_PER_UNIT = 1;
    private static final int TIMER_B_FRAMES_PER_UNIT = 16;
    /** Register-slot order S1, S3, S2, S4 → operator index 0..3 (OP1, OP2, OP3, OP4). */
    private static final int[] SLOT_TO_OPERATOR = {0, 2, 1, 3};
    /**
     * Phase-sampling coordinate in this operator evaluation order. Sauraen's
     * die tracing describes the 12-cycle operator pipeline and its extra
     * delay stages (SpritesMind t=386, start=780/825). Public-facade isolated
     * pitch-step probes establish these coordinates. The admission delay is
     * three frames minus the coordinate; phase is never changed retroactively.
     * All-channel, two-octave modulation sequences independently verify the
     * mapping in TestFastFmFrequencyTransitions.
     */
    private static final int[] PHASE_LOOKAHEAD_FRAMES = {2, 1, 1, 2};
    /**
     * F-number sampling slots, logical OP1..4, before the channel offset.
     * Isolated public-facade pitch steps at every bus residue establish the
     * 18/6/0/12 ordering; TestFastFmFrequencySampling checks all 24 operators.
     */
    private static final int[] FREQUENCY_SAMPLE_CYCLES = {18, 6, 0, 12};
    /** Key-on register bits 4..7 are S1, S3, S2, S4. */
    private static final int[] KEY_BIT_TO_OPERATOR = {0, 2, 1, 3};
    private static final int EG_ATTACK = 0;
    private static final int EG_DECAY = 1;
    private static final int EG_SUSTAIN = 2;
    private static final int EG_RELEASE = 3;
    private static final int MAX_ATTENUATION = 0x3FF;
    private static final int WRITE_PIPELINE_FRAMES = 5;
    /** TL sample slots, logical OP1..4; add the channel index. Public all-offset PCM probes. */
    private static final int[] TOTAL_LEVEL_SAMPLE_CYCLES = {12, 0, 18, 6};
    private static final int OUTPUT_MAX = 8191;
    private static final int OUTPUT_MIN = -8192;

    static {
        for (int i = 0; i < 256; i++) {
            double sine = Math.sin((i + 0.5) * Math.PI / 512.0);
            LOG_SIN[i] = (int) Math.round(-Math.log(sine) / Math.log(2.0) * 256.0);
            EXP[i] = (int) Math.round((Math.pow(2.0, i / 256.0) - 1.0) * 1024.0);
        }
        for (int i = 0; i < 256; i++) {
            LOG_SIN_HALF[i] = LOG_SIN[i];
            LOG_SIN_HALF[0x1FF - i] = LOG_SIN[i];
            EXP_MANTISSA[i] = (EXP[0xFF - i] + 1024) << 2;
        }
        int[][] slow = {
            {0, 1, 0, 1, 0, 1, 0, 1},
            {0, 1, 0, 1, 1, 1, 0, 1},
            {0, 1, 1, 1, 0, 1, 1, 1},
            {0, 1, 1, 1, 1, 1, 1, 1},
        };
        for (int rate = 0; rate < 64; rate++) {
            if (rate < 48) {
                EG_INCREMENT[rate] = slow[rate & 3];
            } else {
                int base = 1 << ((rate - 48) >> 2);
                int[] pattern = new int[8];
                for (int i = 0; i < 8; i++) {
                    int form = rate & 3;
                    boolean doubled = form == 3 ? (i & 3) != 0
                            : form == 2 ? (i & 1) != 0
                            : form == 1 && (i & 3) == 3;
                    pattern[i] = rate >= 60 ? 8 : (doubled ? base * 2 : base);
                }
                EG_INCREMENT[rate] = pattern;
            }
            EG_SHIFT[rate] = Math.max(0, 11 - (rate >> 2));
        }
        for (int pms = 0; pms < 8; pms++) {
            for (int position = 0; position < PM_STEPS; position++) {
                int quarter = position & 7;
                if ((position & 8) != 0) {
                    quarter = 7 - quarter; // second and fourth quarters mirror
                }
                int code = PM_QUARTER_CODE[pms][quarter];
                PM_CODE[pms][position] = (position & 16) != 0 ? -code : code;
            }
        }
    }

    // ------------------------------------------------------------- state
    // Per-operator arrays are indexed channel * 4 + operator (24 entries).

    private final int[] registers = new int[512];
    private final int[] phase = new int[24];
    private final int[] phaseIncrement = new int[24];
    private final int[] attenuation = new int[24];
    private final int[] egState = new int[24];
    private final int[] keyOn = new int[24];
    private final int[] ssgInvert = new int[24];
    private final int[] ssgHeld = new int[24];
    /** SSG-EG restart effects deferred until the restarted attack completes: bit 0 phase reset, bit 1 ALT toggle. */
    private final int[] ssgPendingRestart = new int[24];
    private final int[] output = new int[24];
    /** OP1 history for the extra memory stage feeding OP3. */
    private final int[] olderOp1 = new int[6];
    /** Previous digital channel sum, retained across the multiplexed output boundary. */
    private final int[] delayedOutput = new int[6];
    /** Pre-tick envelope output sampled by OP2..4 during an envelope update frame. */
    private final int[] sampledEg = new int[24];
    /** Prior AM triangles: OP1 samples one frame back, OP2..4 two frames back. */
    private final int[] amHistory = {63, 63};
    /** Register results awaiting their measured operator sampling boundary; -1 means no write. */
    private final int[] scheduledIncrements = new int[WRITE_PIPELINE_FRAMES * 24];
    private final int[] scheduledLevels = new int[WRITE_PIPELINE_FRAMES * 24];
    /** Key event: bit 0 on/off, bit 1 an earlier phase admission than the envelope admission. */
    private final int[] scheduledKeys = new int[WRITE_PIPELINE_FRAMES * 24];
    /** Per pipeline stage, one bit per slot holding at least one scheduled increment, level or key. */
    private final int[] scheduledMask = new int[WRITE_PIPELINE_FRAMES];
    /** Key requests before pipeline admission, retaining timer/CSM ownership semantics. */
    private final int[] requestedKeyOn = new int[24];
    /** Remaining frames in which instant attack must not take a coincident decay step. */
    private final int[] keyedThisFrame = new int[24];
    /** Three DAC output slots as enable/value pairs; final entry marks a sampled write. */
    private final int[] dacOutputSlots = new int[7];
    private final int[] detune = new int[24];
    private final int[] multiple = new int[24];
    private final int[] totalLevel = new int[24];
    private final int[] rateScaling = new int[24];
    private final int[] attackRate = new int[24];
    private final int[] decayRate = new int[24];
    private final int[] sustainRate = new int[24];
    private final int[] releaseRate = new int[24];
    private final int[] sustainLevel = new int[24];
    private final int[] amEnabled = new int[24];
    private final int[] ssgMode = new int[24];
    private final int[] keyCode = new int[24];
    /** Effective rates (2R + key scaling, capped) cached per slot; refreshed on register writes and key-code changes. */
    private final int[] rateAttack = new int[24];
    private final int[] rateDecay = new int[24];
    private final int[] rateSustain = new int[24];
    private final int[] rateRelease = new int[24];
    private final int[] operatorFnum = new int[24];
    private final int[] operatorBlock = new int[24];
    private final int[] channelFnum = new int[6];
    private final int[] channelBlock = new int[6];
    private final int[] latchedFnumHigh = new int[2];
    private final int[] latchedCh3FnumHigh = new int[3];
    private final int[] ch3Fnum = new int[3];
    private final int[] ch3Block = new int[3];
    private final int[] feedback = new int[6];
    private final int[] algorithm = new int[6];
    private final int[] amSensitivity = new int[6];
    private final int[] pmSensitivity = new int[6];
    private final int[] feedbackHistory = new int[12];
    private final int[] operatorOut = new int[4];
    private final int[] csmKeyed = new int[1];
    private final int[] scalar = new int[22];
    private static final int S_EG_COUNTER = 0;
    private static final int S_EG_FRAME = 1;
    private static final int S_LFO_ENABLED = 2;
    private static final int S_LFO_RATE = 3;
    private static final int S_LFO_COUNTER = 4;
    private static final int S_LFO_STEP = 5;
    private static final int S_TIMER_A_LOAD = 6;
    private static final int S_TIMER_B_LOAD = 7;
    private static final int S_TIMER_A_COUNT = 8;
    private static final int S_TIMER_B_COUNT = 9;
    private static final int S_TIMER_CONTROL = 10;
    private static final int S_STATUS = 11;
    private static final int S_CH3_MODE = 12;
    private static final int S_DAC_ENABLED = 13;
    private static final int S_DAC_VALUE = 14;
    private static final int S_SSG_ENABLED_MASK = 15;
    private static final int S_TIMER_A_RELOAD = 16;
    private static final int S_TIMER_B_RELOAD = 17;
    private static final int S_WRITE_PIPELINE_CURSOR = 18;
    /** One bit per slot whose {@link #keyedThisFrame} hold is still counting down. */
    private static final int S_KEYED_MASK = 19;
    /**
     * One bit per slot whose envelope can still change. A slot leaves the set
     * when an envelope tick finds it keyed off, in release and at full
     * attenuation: from then until key-on {@link #advanceEnvelope} returns
     * without effect and {@link #egOutput} stays at 0x3FF, so the tick loop
     * skips it. Key-on is the only way back in.
     */
    private static final int S_EG_ACTIVE_MASK = 20;
    /**
     * One bit per channel whose last evaluated frame took the silent path
     * (histories, operator outputs and the delayed output all zero). While
     * every operator is also out of the envelope-active set the channel is
     * skipped outright; its state cannot change until a key-on.
     */
    private static final int S_CHANNEL_SILENT_MASK = 21;

    public FastYm2612Dsp() {
        reset();
    }

    @Override
    public void reset() {
        Arrays.fill(olderOp1, 0);
        Arrays.fill(delayedOutput, 0);
        Arrays.fill(sampledEg, 0);
        Arrays.fill(amHistory, 63);
        Arrays.fill(scheduledIncrements, -1);
        Arrays.fill(scheduledLevels, -1);
        Arrays.fill(scheduledKeys, -1);
        Arrays.fill(scheduledMask, 0);
        Arrays.fill(requestedKeyOn, 0);
        Arrays.fill(keyedThisFrame, 0);
        Arrays.fill(dacOutputSlots, 0);
        Arrays.fill(registers, 0);
        for (int[] array : new int[][] {phase, phaseIncrement, egState, keyOn, ssgInvert, ssgHeld, ssgPendingRestart, output, detune,
                multiple, totalLevel, rateScaling, attackRate, decayRate, sustainRate, releaseRate,
                sustainLevel, amEnabled, ssgMode, keyCode, rateAttack, rateDecay, rateSustain, rateRelease,
                operatorFnum, operatorBlock, channelFnum,
                channelBlock, latchedFnumHigh, latchedCh3FnumHigh, ch3Fnum, ch3Block, feedback, algorithm,
                amSensitivity, pmSensitivity, feedbackHistory, operatorOut, csmKeyed, scalar}) {
            Arrays.fill(array, 0);
        }
        Arrays.fill(attenuation, MAX_ATTENUATION);
        Arrays.fill(egState, EG_RELEASE);
        scalar[S_DAC_VALUE] = 0x80;
        scalar[S_EG_ACTIVE_MASK] = 0xFFFFFF;
        scalar[S_TIMER_A_RELOAD] = 1024 * TIMER_A_FRAMES_PER_UNIT;
        scalar[S_TIMER_B_RELOAD] = 256 * TIMER_B_FRAMES_PER_UNIT;
        for (int slot = 0; slot < 24; slot++) {
            refreshRates(slot);
            refreshPhaseIncrement(slot);
        }
    }

    // ---------------------------------------------------------- registers

    @Override
    public void writeRegister(int port, int register, int value) {
        // Untimed DAC streams apply at this frame's start. Other untimed
        // clients retain the original immediate phase-reset convention.
        boolean dac = (port & 1) == 0 && ((register & 0xff) == 0x2a || (register & 0xff) == 0x2b);
        writeRegister(port, register, value, dac ? -1 : 23);
    }

    @Override
    public void writeRegister(int port, int register, int value, int frameCycle) {
        port &= 1;
        register &= 0xff;
        value &= 0xff;
        registers[(port << 8) | register] = value;
        if (port == 0 && register < 0x30) {
            writeGlobal(register, value, frameCycle);
            return;
        }
        int channelInPort = register & 3;
        if (channelInPort == 3) {
            return;
        }
        int channel = port * 3 + channelInPort;
        int group = register & 0xF0;
        if (group >= 0x30 && group <= 0x90) {
            int slot = channel * 4 + SLOT_TO_OPERATOR[(register >> 2) & 3];
            writeOperator(slot, group, value, frameCycle);
            return;
        }
        switch (group) {
            case 0xA0 -> writeFrequency(port, channel, register, value, frameCycle);
            case 0xB0 -> {
                if ((register & 0xC) == 0) {
                    feedback[channel] = (value >> 3) & 7;
                    algorithm[channel] = value & 7;
                } else if ((register & 0xC) == 4) {
                    amSensitivity[channel] = (value >> 4) & 3;
                    pmSensitivity[channel] = value & 7;
                    for (int op = 0; op < 4; op++) {
                        refreshPhaseIncrement(channel * 4 + op);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void writeGlobal(int register, int value, int frameCycle) {
        switch (register) {
            case 0x22 -> {
                boolean enable = (value & 8) != 0;
                scalar[S_LFO_RATE] = value & 7;
                if (!enable && scalar[S_LFO_ENABLED] != 0) {
                    // Disabling holds the step at zero; the prescaler keeps running.
                    scalar[S_LFO_STEP] = 0;
                    refreshAllPhaseIncrements();
                }
                scalar[S_LFO_ENABLED] = enable ? 1 : 0;
            }
            case 0x24 -> {
                scalar[S_TIMER_A_LOAD] = (scalar[S_TIMER_A_LOAD] & 3) | (value << 2);
                scalar[S_TIMER_A_RELOAD] = (1024 - scalar[S_TIMER_A_LOAD]) * TIMER_A_FRAMES_PER_UNIT;
            }
            case 0x25 -> {
                scalar[S_TIMER_A_LOAD] = (scalar[S_TIMER_A_LOAD] & 0x3FC) | (value & 3);
                scalar[S_TIMER_A_RELOAD] = (1024 - scalar[S_TIMER_A_LOAD]) * TIMER_A_FRAMES_PER_UNIT;
            }
            case 0x26 -> {
                scalar[S_TIMER_B_LOAD] = value;
                scalar[S_TIMER_B_RELOAD] = (256 - value) * TIMER_B_FRAMES_PER_UNIT;
            }
            case 0x27 -> {
                int previous = scalar[S_TIMER_CONTROL];
                scalar[S_TIMER_CONTROL] = value;
                scalar[S_CH3_MODE] = (value >> 6) & 3;
                if ((value & 1) != 0 && (previous & 1) == 0) {
                    scalar[S_TIMER_A_COUNT] = scalar[S_TIMER_A_RELOAD];
                }
                if ((value & 2) != 0 && (previous & 2) == 0) {
                    scalar[S_TIMER_B_COUNT] = scalar[S_TIMER_B_RELOAD];
                }
                if ((value & 0x10) != 0) {
                    scalar[S_STATUS] &= ~1;
                }
                if ((value & 0x20) != 0) {
                    scalar[S_STATUS] &= ~2;
                }
                for (int op = 0; op < 4; op++) {
                    refreshPhaseIncrement(8 + op);
                }
            }
            case 0x28 -> {
                int channelBits = value & 3;
                if (channelBits == 3) {
                    return;
                }
                int channel = channelBits + ((value >> 2) & 1) * 3;
                // The phase latch can admit the key one frame before its
                // envelope latch. Keep the instant-attack hold until both have
                // sampled it, rather than consuming a premature decay step.
                int earlyPhase = 1 + Math.floorDiv(channel - 1 - frameCycle, 24);
                for (int bit = 0; bit < 4; bit++) {
                    int slot = channel * 4 + KEY_BIT_TO_OPERATOR[bit];
                    scheduleKey(slot, (value & (0x10 << bit)) != 0,
                            3 - earlyPhase, Math.max(earlyPhase, 0));
                }
            }
            case 0x2A, 0x2B -> writeDac(register, value, frameCycle);
            default -> {
            }
        }
    }

    private void writeDac(int register, int value, int frameCycle) {
        if (dacOutputSlots[6] == 0) {
            for (int sample = 0; sample < 3; sample++) {
                dacOutputSlots[sample * 2] = scalar[S_DAC_ENABLED];
                dacOutputSlots[sample * 2 + 1] = scalar[S_DAC_VALUE];
            }
            dacOutputSlots[6] = 1;
        }
        if (register == 0x2a) scalar[S_DAC_VALUE] = value;
        else scalar[S_DAC_ENABLED] = (value >> 7) & 1;
        // Public bus-strobe/PCM steps over all 24 offsets distinguish three
        // contributions, with new data admitted at cycles 4, 5 and 6. Later
        // writes change next frame's state but cannot rewrite earlier slots.
        for (int sample = 0; sample < 3; sample++) {
            if (frameCycle <= 4 + sample) {
                dacOutputSlots[sample * 2] = scalar[S_DAC_ENABLED];
                dacOutputSlots[sample * 2 + 1] = scalar[S_DAC_VALUE];
            }
        }
    }

    private void writeOperator(int slot, int group, int value, int frameCycle) {
        switch (group) {
            case 0x30 -> {
                detune[slot] = (value >> 4) & 7;
                multiple[slot] = value & 15;
                refreshPhaseIncrement(slot);
            }
            case 0x40 -> {
                int boundary = TOTAL_LEVEL_SAMPLE_CYCLES[slot & 3] + (slot >> 2);
                int baseDelay = (slot & 3) == 2 ? 2 : 1;
                int delay = baseDelay + Math.floorDiv(frameCycle + 24 - boundary, 24);
                scheduledLevels[scheduledIndex(slot, delay)] = (value & 0x7F) << 3;
                markScheduled(slot, delay);
            }
            case 0x50 -> {
                rateScaling[slot] = (value >> 6) & 3;
                attackRate[slot] = value & 0x1F;
                refreshRates(slot);
            }
            case 0x60 -> {
                amEnabled[slot] = (value >> 7) & 1;
                decayRate[slot] = value & 0x1F;
                refreshRates(slot);
            }
            case 0x70 -> {
                sustainRate[slot] = value & 0x1F;
                refreshRates(slot);
            }
            case 0x80 -> {
                int sl = (value >> 4) & 15;
                sustainLevel[slot] = sl == 15 ? SUSTAIN_LEVEL_15 : sl << 5;
                releaseRate[slot] = value & 15;
                refreshRates(slot);
            }
            case 0x90 -> {
                ssgMode[slot] = value & 15;
                if ((value & 8) != 0) {
                    scalar[S_SSG_ENABLED_MASK] |= 1 << slot;
                } else {
                    scalar[S_SSG_ENABLED_MASK] &= ~(1 << slot);
                }
            }
            default -> {
            }
        }
    }

    private void writeFrequency(int port, int channel, int register, int value, int frameCycle) {
        int sub = register & 0xC;
        int channelInPort = register & 3;
        if (sub == 0) {
            channelFnum[channel] = ((latchedFnumHigh[port] & 7) << 8) | value;
            channelBlock[channel] = (latchedFnumHigh[port] >> 3) & 7;
            for (int op = 0; op < 4; op++) {
                refreshFrequencyPhase(channel * 4 + op, frameCycle);
            }
        } else if (sub == 4) {
            latchedFnumHigh[port] = value & 0x3F;
        } else if (port == 0 && sub == 8) {
            // Channel 3 special-mode operator frequencies: A8..AA (S3, S1, S2 order).
            ch3Fnum[channelInPort] = ((latchedCh3FnumHigh[channelInPort] & 7) << 8) | value;
            ch3Block[channelInPort] = (latchedCh3FnumHigh[channelInPort] >> 3) & 7;
            for (int op = 0; op < 4; op++) {
                refreshFrequencyPhase(8 + op, frameCycle);
            }
        } else if (port == 0 && sub == 0xC) {
            latchedCh3FnumHigh[channelInPort] = value & 0x3F;
        }
    }

    // ------------------------------------------------------ derived state

    private void refreshAllPhaseIncrements() {
        for (int slot = 0; slot < 24; slot++) {
            refreshPhaseIncrement(slot);
        }
    }

    /** Channel-3 special mode: OP1..OP3 read their own F-number (A9, AA, A8 map to OP1, OP2, OP3); OP4 uses A2/A6. */
    private void resolveFrequency(int slot) {
        int channel = slot >> 2;
        int op = slot & 3;
        if (channel == 2 && scalar[S_CH3_MODE] != 0 && op < 3) {
            int index = op == 0 ? 1 : op == 1 ? 2 : 0;
            operatorFnum[slot] = ch3Fnum[index];
            operatorBlock[slot] = ch3Block[index];
        } else {
            operatorFnum[slot] = channelFnum[channel];
            operatorBlock[slot] = channelBlock[channel];
        }
    }

    private void refreshFrequencyPhase(int slot, int frameCycle) {
        int boundary = FREQUENCY_SAMPLE_CYCLES[slot & 3] + (slot >> 2);
        // The same register reaches operators at different pipeline positions.
        // Crossing that sampling slot replaces one fewer cached phase step.
        int lookahead = 2 + Math.floorDiv(boundary - 1 - frameCycle, 24);
        refreshPhaseIncrement(slot, lookahead);
    }

    private void refreshPhaseIncrement(int slot) {
        refreshPhaseIncrement(slot, PHASE_LOOKAHEAD_FRAMES[slot & 3]);
    }

    private void refreshPhaseIncrement(int slot, int lookahead) {
        resolveFrequency(slot);
        int fnum = operatorFnum[slot];
        int block = operatorBlock[slot];
        int channel = slot >> 2;
        int pms = pmSensitivity[channel];
        // Key code comes from the raw F-number before LFO modulation (Sauraen's die tracing).
        int newKeyCode = (block << 2) | FNUM_NOTE[(fnum >> 7) & 15];
        int fnum2 = fnum << 1; // F-number with one fractional bit for LFO PM
        if (scalar[S_LFO_ENABLED] != 0 && pms != 0) {
            int position = (scalar[S_LFO_STEP] >> 2) & (PM_STEPS - 1);
            int code = PM_CODE[pms][position];
            int q = Math.abs(code);
            int h = fnum >> 4;
            int sum = ((q & 4) != 0 ? h : 0) + ((q & 2) != 0 ? h >> 1 : 0) + ((q & 1) != 0 ? h >> 2 : 0);
            int halfSteps = (sum << PM_SHIFT[pms]) >> 2; // see PM_QUARTER_CODE
            // The modulated F-number is twelve bits (eleven plus the fraction)
            // and wraps: the oracle's positive PM peaks at 0x7F0 fall to a
            // sub-audio pitch. A negative offset never exceeds the F-number.
            fnum2 = ((fnum << 1) + (code < 0 ? -halfSteps : halfSteps)) & 0xFFF;
        }
        if (newKeyCode != keyCode[slot]) {
            keyCode[slot] = newKeyCode;
            refreshRates(slot);
        }
        int base = (fnum2 << block) >> 2;
        int dt = detune[slot];
        int detuneAmount = DETUNE[dt & 3][newKeyCode & 31];
        if ((dt & 4) != 0) {
            base -= detuneAmount;
            if (base < 0) {
                base += 1 << 17;
            }
        } else {
            base += detuneAmount;
        }
        base &= 0x1FFFF;
        int mul = multiple[slot];
        int nextIncrement = (mul == 0 ? base >> 1 : base * mul) & 0xFFFFF;
        // Admit the new increment at its pipeline boundary. Retrospectively
        // jumping the phase leaves an already-computed feedback sample wrong;
        // a single such sample can alter the entire high-feedback sequence.
        scheduledIncrements[scheduledIndex(slot, 3 - lookahead)] = nextIncrement;
        markScheduled(slot, 3 - lookahead);
    }

    private void refreshRates(int slot) {
        rateAttack[slot] = effectiveRate(slot, attackRate[slot]);
        rateDecay[slot] = effectiveRate(slot, decayRate[slot]);
        rateSustain[slot] = effectiveRate(slot, sustainRate[slot]);
        rateRelease[slot] = effectiveRate(slot, releaseRate[slot] * 2 + 1);
    }

    private int effectiveRate(int slot, int rate) {
        if (rate == 0) {
            return 0;
        }
        int scaled = 2 * rate + (keyCode[slot] >> (3 - rateScaling[slot]));
        return Math.min(63, scaled);
    }

    private void keyOnSlot(int slot) {
        if (keyOn[slot] != 0) {
            return;
        }
        keyOn[slot] = 1;
        scalar[S_EG_ACTIVE_MASK] |= 1 << slot;
        phase[slot] = 0;
        ssgInvert[slot] = 0;
        ssgHeld[slot] = 0;
        // A key-on with a real attack in an ALT mode toggles the inversion when
        // that attack completes (oracle-established; see handleSsgBoundary).
        // Non-ALT modes hold the phase at zero while the attenuation sits at or
        // above the half-way point instead (CS's held-phase measurement).
        ssgPendingRestart[slot] = (ssgMode[slot] & 8) != 0 && rateAttack[slot] < 62
                ? ((ssgMode[slot] & 2) != 0 ? 2 : 0) : 0;
        if (rateAttack[slot] >= 62) {
            keyedThisFrame[slot] = 1;
            scalar[S_KEYED_MASK] |= 1 << slot;
            attenuation[slot] = 0;
            egState[slot] = EG_DECAY;
        } else {
            egState[slot] = EG_ATTACK;
        }
    }

    private void keyOffSlot(int slot) {
        if (keyOn[slot] == 0) {
            return;
        }
        if ((ssgMode[slot] & 8) != 0 && ssgOutputInverted(slot)) {
            // Leaving an inverted SSG-EG pass: release continues from the level heard.
            attenuation[slot] = (0x200 - attenuation[slot]) & MAX_ATTENUATION;
        }
        ssgInvert[slot] = 0;
        ssgHeld[slot] = 0;
        ssgPendingRestart[slot] = 0;
        keyOn[slot] = 0;
        egState[slot] = EG_RELEASE;
    }

    private int scheduledStage(int delay) {
        return (scalar[S_WRITE_PIPELINE_CURSOR] + delay) % WRITE_PIPELINE_FRAMES;
    }

    private int scheduledIndex(int slot, int delay) {
        return scheduledStage(delay) * 24 + slot;
    }

    private void markScheduled(int slot, int delay) {
        scheduledMask[scheduledStage(delay)] |= 1 << slot;
    }

    private void scheduleKey(int slot, boolean on, int delay, int envelopeHold) {
        requestedKeyOn[slot] = on ? 1 : 0;
        scheduledKeys[scheduledIndex(slot, delay)] = on ? 1 | (envelopeHold << 1) : 0;
        markScheduled(slot, delay);
    }

    private void admitScheduledWrites() {
        int stage = scalar[S_WRITE_PIPELINE_CURSOR];
        int mask = scheduledMask[stage];
        if (mask == 0) {
            return;
        }
        scheduledMask[stage] = 0;
        int base = stage * 24;
        while (mask != 0) {
            int slot = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            int index = base + slot;
            if (scheduledIncrements[index] >= 0) {
                phaseIncrement[slot] = scheduledIncrements[index];
                scheduledIncrements[index] = -1;
            }
            if (scheduledLevels[index] >= 0) {
                totalLevel[slot] = scheduledLevels[index];
                scheduledLevels[index] = -1;
            }
            int key = scheduledKeys[index];
            if (key >= 0) {
                scheduledKeys[index] = -1;
                if ((key & 1) == 0) {
                    keyOffSlot(slot);
                } else if (keyOn[slot] == 0) {
                    keyOnSlot(slot);
                    if (keyedThisFrame[slot] != 0) keyedThisFrame[slot] += key >> 1;
                }
            }
        }
    }

    // ------------------------------------------------------------- render

    @Override
    public void renderFrame(int[] out) {
        admitScheduledWrites();
        advanceTimers();
        advanceLfo();
        // SSG-EG's half-way boundary is tested every FM frame, before a coincident envelope update.
        int ssgMask = scalar[S_SSG_ENABLED_MASK];
        while (ssgMask != 0) {
            int slot = Integer.numberOfTrailingZeros(ssgMask);
            ssgMask &= ssgMask - 1;
            if (keyOn[slot] != 0 && (ssgMode[slot] & 3) == 0 && attenuation[slot] >= 0x200) {
                phase[slot] = 0;
            }
            if (keyOn[slot] != 0 && egState[slot] != EG_ATTACK && attenuation[slot] >= 0x200) {
                handleSsgBoundary(slot);
            }
        }
        scalar[S_EG_FRAME] += MASTER_CYCLES_PER_FRAME;
        while (scalar[S_EG_FRAME] >= EG_TICK_MASTER_CYCLES) {
            scalar[S_EG_FRAME] -= EG_TICK_MASTER_CYCLES;
            advanceEnvelopes();
        }
        // A disabled LFO holds its counter at zero, which is the AM triangle's
        // maximum attenuation (Nemesis); the offset is scaled per channel below.
        int amOffset = 63;
        if (scalar[S_LFO_ENABLED] != 0) {
            int step = scalar[S_LFO_STEP];
            amOffset = step < 64 ? 63 - step : step - 64; // both turning points last two steps
        }
        // Frame-wide operator inputs: every store to an int[] forces the JIT to
        // reload them, so read them once here rather than in every operator.
        boolean sampledEgFrame = scalar[S_EG_FRAME] == 0;
        int amTriangle0 = amHistory[0] << 1;
        int amTriangle1 = amHistory[1] << 1;
        int releasedSlots = ~scalar[S_EG_ACTIVE_MASK];
        int silentChannels = scalar[S_CHANNEL_SILENT_MASK];
        for (int channel = 0; channel < 6; channel++) {
            if ((silentChannels & (1 << channel)) != 0 && ((releasedSlots >> (channel * 4)) & 0xF) == 0xF) {
                // The last evaluation zeroed this channel's histories and delayed
                // output and every operator is fully released: renderChannel
                // would rewrite zeros and return zero, so skip it.
                out[channel] = 0;
                continue;
            }
            out[channel] = renderChannel(channel, sampledEgFrame, amTriangle0, amTriangle1);
        }
        if (dacOutputSlots[6] != 0) {
            int sum = 0;
            for (int sample = 0; sample < 3; sample++) {
                sum += dacOutputSlots[sample * 2] != 0
                        ? (dacOutputSlots[sample * 2 + 1] - 0x80) << 6 : out[5];
            }
            out[5] = Math.floorDiv(sum, 3);
            dacOutputSlots[6] = 0;
        } else if (scalar[S_DAC_ENABLED] != 0) {
            out[5] = (scalar[S_DAC_VALUE] - 0x80) << 6;
        }
        int keyed = scalar[S_KEYED_MASK];
        while (keyed != 0) {
            int slot = Integer.numberOfTrailingZeros(keyed);
            keyed &= keyed - 1;
            if (--keyedThisFrame[slot] == 0) {
                scalar[S_KEYED_MASK] &= ~(1 << slot);
            }
        }
        amHistory[1] = amHistory[0];
        amHistory[0] = amOffset;
        scalar[S_WRITE_PIPELINE_CURSOR] = (scalar[S_WRITE_PIPELINE_CURSOR] + 1) % WRITE_PIPELINE_FRAMES;
    }

    private int renderChannel(int channel, boolean sampledEgFrame, int amTriangle0, int amTriangle1) {
        int base = channel * 4;
        if (attenuation[base] == MAX_ATTENUATION && attenuation[base + 1] == MAX_ATTENUATION
                && attenuation[base + 2] == MAX_ATTENUATION && attenuation[base + 3] == MAX_ATTENUATION
                && egState[base] != EG_ATTACK && egState[base + 1] != EG_ATTACK
                && egState[base + 2] != EG_ATTACK && egState[base + 3] != EG_ATTACK) {
            // Silent channel: key-on resets the phase, so it need not advance here.
            scalar[S_CHANNEL_SILENT_MASK] |= 1 << channel;
            olderOp1[channel] = 0;
            feedbackHistory[channel * 2] = 0;
            feedbackHistory[channel * 2 + 1] = 0;
            output[base] = 0;
            output[base + 1] = 0;
            output[base + 2] = 0;
            output[base + 3] = 0;
            return channelOutput(channel, 0);
        }
        scalar[S_CHANNEL_SILENT_MASK] &= ~(1 << channel);
        int fb = feedback[channel];
        int feedbackInput = fb == 0 ? 0
                : (feedbackHistory[channel * 2] + feedbackHistory[channel * 2 + 1]) >> (10 - fb);
        // Sauraen's die tracing establishes a pipeline and modulation buffers:
        // https://gendev.spritesmind.net/forum/viewtopic.php?start=780&t=386
        // https://gendev.spritesmind.net/forum/viewtopic.php?start=825&t=386
        // The exact ages below are oracle-derived in THIS implementation's
        // frame coordinates, not cycle labels taken from that research:
        // OP1 -> OP3 is two frames old, OP3 -> OP4 is current, and the other
        // edges use the preceding frame. Independent algorithm 5 fanout and
        // algorithm 4 two-chain probes distinguish these choices.
        int p1 = output[base];
        int oldP1 = olderOp1[channel];
        olderOp1[channel] = p1;
        int p2 = output[base + 1];
        int amShift = AM_SHIFT[amSensitivity[channel]];
        int am0 = amTriangle0 >> amShift;
        int am1 = amTriangle1 >> amShift;
        int op1 = operatorSample(base, feedbackInput, am0, false);
        feedbackHistory[channel * 2 + 1] = feedbackHistory[channel * 2];
        feedbackHistory[channel * 2] = op1;
        int op2;
        int op3;
        int op4;
        int sum;
        switch (algorithm[channel]) {
            case 0 -> {
                op2 = operatorSample(base + 1, p1 >> 1, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, p2 >> 1, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, op3 >> 1, am1, sampledEgFrame);
                sum = op4;
            }
            case 1 -> {
                op2 = operatorSample(base + 1, 0, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, (oldP1 + p2) >> 1, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, op3 >> 1, am1, sampledEgFrame);
                sum = op4;
            }
            case 2 -> {
                op2 = operatorSample(base + 1, 0, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, p2 >> 1, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, (p1 + op3) >> 1, am1, sampledEgFrame);
                sum = op4;
            }
            case 3 -> {
                op2 = operatorSample(base + 1, p1 >> 1, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, 0, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, (p2 + op3) >> 1, am1, sampledEgFrame);
                sum = op4;
            }
            case 4 -> {
                op2 = operatorSample(base + 1, p1 >> 1, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, 0, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, op3 >> 1, am1, sampledEgFrame);
                sum = op2 + op4;
            }
            case 5 -> {
                op2 = operatorSample(base + 1, p1 >> 1, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, oldP1 >> 1, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, p1 >> 1, am1, sampledEgFrame);
                sum = op2 + op3 + op4;
            }
            case 6 -> {
                op2 = operatorSample(base + 1, p1 >> 1, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, 0, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, 0, am1, sampledEgFrame);
                sum = op2 + op3 + op4;
            }
            default -> {
                op2 = operatorSample(base + 1, 0, am1, sampledEgFrame);
                op3 = operatorSample(base + 2, 0, am1, sampledEgFrame);
                op4 = operatorSample(base + 3, 0, am1, sampledEgFrame);
                // OP1 reaches the carrier accumulator one frame after its
                // other carrier peers in these evaluation coordinates.
                sum = p1 + op2 + op3 + op4;
            }
        }
        output[base] = op1;
        output[base + 1] = op2;
        output[base + 2] = op3;
        output[base + 3] = op4;
        return channelOutput(channel, sum > OUTPUT_MAX ? OUTPUT_MAX : Math.max(sum, OUTPUT_MIN));
    }

    private int channelOutput(int channel, int value) {
        // The time-multiplexed accumulator crosses our frame boundary on
        // channels 1/3/5 (zero-based). These exact ages are oracle-derived:
        // isolated tones and independent carrier/channel mixtures, all six
        // channels and every key-on bus offset. See TestFastFmOutputTiming.
        int previous = delayedOutput[channel];
        delayedOutput[channel] = value;
        return (channel & 1) == 0 ? value : previous;
    }

    /**
     * One operator sample: phase (plus modulation) through log-sine, envelope + TL + AM, exp.
     *
     * @param amAttenuation this channel's AM attenuation for this operator's history age
     *        (the doubled 0..63 triangle shifted by AMS); applied only when the operator's AM bit is set
     * @param useSampledEg whether OP2..4 read the pre-tick envelope on this frame
     */
    private int operatorSample(int slot, int modulation, int amAttenuation, boolean useSampledEg) {
        int currentPhase = phase[slot];
        int phaseIndex = ((currentPhase >> 10) + modulation) & 0x3FF;
        phase[slot] = (currentPhase + phaseIncrement[slot]) & 0xFFFFF;
        // Public-facade held/decaying carrier pairs distinguish the envelope
        // output boundary: OP2..4 consume the pre-tick value on this frame.
        // OP1's carrier already passes through its separate output history.
        int envelope = useSampledEg ? sampledEg[slot] : egOutput(slot);
        int level = envelope + totalLevel[slot];
        if (amEnabled[slot] != 0) {
            // Paired held/AM public tones resolve this operator's output
            // boundary independently of pitch and feedback. Discrete shifts
            // preserve every AM step; scaling a rounded peak does not.
            level += amAttenuation;
        }
        if (level >= MAX_ATTENUATION) {
            return 0;
        }
        // Half-wave table: the second quarter is the first reflected.
        int attenuation12 = LOG_SIN_HALF[phaseIndex & 0x1FF] + (level << 2);
        if (attenuation12 >= 0x1FFF) {
            return 0;
        }
        // 2^(-a/256) = 2^-(I+1) * 2^((256-F)/256): the fraction indexes the table complemented.
        int linear = EXP_MANTISSA[attenuation12 & 0xFF] >> (attenuation12 >> 8);
        return (phaseIndex & 0x200) != 0 ? -linear : linear;
    }

    /** SSG-EG output inversion: the ATT bit XOR the alternate toggle, only while keyed on. */
    private boolean ssgOutputInverted(int slot) {
        return keyOn[slot] != 0 && (((ssgMode[slot] >> 2) & 1) ^ ssgInvert[slot]) != 0;
    }

    /** The 10-bit attenuation the operator sees, honouring SSG-EG inversion. */
    private int egOutput(int slot) {
        int level = attenuation[slot];
        if ((ssgMode[slot] & 8) != 0 && ssgOutputInverted(slot)) {
            level = (0x200 - level) & MAX_ATTENUATION;
        }
        return level;
    }

    private void advanceEnvelopes() {
        // The output-stage envelope sees the counter from before this tick.
        // Register admission supplies the latency previously approximated by
        // delaying already-computed audio, so preserve this counter coordinate.
        int sampledCounter = scalar[S_EG_COUNTER];
        int counter = (sampledCounter + 1) & 0xFFF;
        if (counter == 0) {
            counter = 1;
        }
        scalar[S_EG_COUNTER] = counter;
        int active = scalar[S_EG_ACTIVE_MASK];
        int remaining = active;
        while (remaining != 0) {
            int slot = Integer.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            sampledEg[slot] = egOutput(slot);
            if (keyOn[slot] == 0 && egState[slot] == EG_RELEASE && attenuation[slot] == MAX_ATTENUATION) {
                // Fully released: nothing left to step until the next key-on.
                active &= ~(1 << slot);
                continue;
            }
            advanceEnvelope(slot, sampledCounter);
        }
        scalar[S_EG_ACTIVE_MASK] = active;
    }

    private void advanceEnvelope(int slot, int counter) {
        if (keyedThisFrame[slot] != 0) return;
        int state = egState[slot];
        if (state == EG_DECAY && attenuation[slot] >= sustainLevel[slot]) {
            // The sustain level is a level comparison, not a step event: with a
            // zero decay rate (or a sustain level already reached) the envelope
            // still moves on to the sustain rate. Without this a D1R=0, SL=0
            // voice held at full level for ever (S3K effects were 2-4x too loud).
            state = EG_SUSTAIN;
            egState[slot] = state;
        }
        boolean ssg = (ssgMode[slot] & 8) != 0;
        if (ssg && ssgHeld[slot] != 0) {
            return;
        }
        if (state == EG_RELEASE && attenuation[slot] == MAX_ATTENUATION) {
            return; // fully released: nothing left to step
        }
        int rate;
        switch (state) {
            case EG_ATTACK -> rate = rateAttack[slot];
            case EG_DECAY -> rate = rateDecay[slot];
            case EG_SUSTAIN -> rate = rateSustain[slot];
            default -> rate = rateRelease[slot];
        }
        if (rate == 0) {
            return;
        }
        int shift = EG_SHIFT[rate];
        if ((counter & ((1 << shift) - 1)) != 0) {
            return;
        }
        int increment = EG_INCREMENT[rate][(counter >> shift) & 7];
        if (increment == 0) {
            return;
        }
        if (state == EG_ATTACK) {
            int level = attenuation[slot] + (((~attenuation[slot]) * increment) >> 4);
            if (level <= 0) {
                level = 0;
                egState[slot] = EG_DECAY;
                applySsgRestartEffects(slot);
            }
            attenuation[slot] = level;
            return;
        }
        if (ssg && ssgHeld[slot] != 0) {
            return;
        }
        int step = ssg ? increment * SSG_STEP_MULTIPLIER : increment;
        int level = Math.min(MAX_ATTENUATION, attenuation[slot] + step);
        if (state == EG_DECAY && level >= sustainLevel[slot]) {
            egState[slot] = EG_SUSTAIN;
        }
        attenuation[slot] = level;
    }

    /**
     * SSG-EG at the half-way boundary (attenuation 0x200 and beyond, keyed on).
     * Hold modes hold: an inverted output freezes at the boundary (heard loud),
     * a plain one goes to full attenuation (silent). Repeat modes restart the
     * attack from the attained attenuation; ALT modes toggle the inversion at
     * the boundary and, when the attack is real (rate below 62), again when it
     * completes (oracle-established). Non-ALT repeat modes reset the phase at
     * the boundary and keep it held at zero for every frame the attenuation
     * stays at or above the half-way point, including during the restarted
     * attack (Nemesis's 2009 notes, confirmed by CS against the oracle:
     * ssg08 0.81 to 0.999, ssg12 0.93 to 0.998).
     */
    private void handleSsgBoundary(int slot) {
        if (keyOn[slot] == 0) {
            return;
        }
        int mode = ssgMode[slot];
        boolean hold = (mode & 1) != 0;
        boolean alternate = (mode & 2) != 0;
        boolean instantAttack = rateAttack[slot] >= 62;
        if (hold) {
            if (ssgHeld[slot] == 0) {
                ssgHeld[slot] = 1;
                if (alternate && instantAttack) {
                    ssgInvert[slot] ^= 1;
                }
                attenuation[slot] = ssgOutputInverted(slot) ? 0x200 : MAX_ATTENUATION;
                egState[slot] = EG_SUSTAIN;
            }
            return;
        }
        if (alternate) {
            ssgInvert[slot] ^= 1;
        } else {
            phase[slot] = 0;
        }
        if (instantAttack) {
            attenuation[slot] = 0;
            egState[slot] = EG_DECAY;
            ssgPendingRestart[slot] = 0;
        } else {
            ssgPendingRestart[slot] = alternate ? 2 : 0;
            egState[slot] = EG_ATTACK;
        }
    }

    /** Lands a restart's deferred effects: phase reset (non-ALT) or inversion toggle (ALT). */
    private void applySsgRestartEffects(int slot) {
        int effects = ssgPendingRestart[slot];
        if (effects == 0 || (ssgMode[slot] & 8) == 0) {
            ssgPendingRestart[slot] = 0;
            return;
        }
        if ((effects & 1) != 0) {
            phase[slot] = 0;
        }
        if ((effects & 2) != 0) {
            ssgInvert[slot] ^= 1;
        }
        ssgPendingRestart[slot] = 0;
    }

    private void advanceLfo() {
        // The count is compared under the current control before it advances;
        // a terminal count restarts at one. Enable-time fits at the terminal
        // edge (a count already containing the new mask steps on the write
        // frame; the old mask's terminal with the LFO off only restarts) fix
        // this ordering against the oracle.
        int counter = scalar[S_LFO_COUNTER];
        int mask = LFO_STEP_MASK[scalar[S_LFO_RATE]];
        boolean terminal = (counter & mask) == mask;
        scalar[S_LFO_COUNTER] = terminal ? 1 : counter + 1;
        if (!terminal || scalar[S_LFO_ENABLED] == 0) {
            return;
        }
        {
            int step = (scalar[S_LFO_STEP] + 1) & (LFO_STEPS - 1);
            scalar[S_LFO_STEP] = step;
            if ((step & 3) == 0) {
                for (int channel = 0; channel < 6; channel++) {
                    if (pmSensitivity[channel] != 0) {
                        for (int op = 0; op < 4; op++) {
                            int slot = channel * 4 + op;
                            // Global PM has its own sampling boundary; the
                            // frequency-register slot ordering does not apply.
                            refreshPhaseIncrement(slot, 3 - LFO_PM_ADMISSION_FRAMES[op]);
                        }
                    }
                }
            }
        }
    }

    private void advanceTimers() {
        int control = scalar[S_TIMER_CONTROL];
        if ((control & 3) == 0 && csmKeyed[0] == 0) {
            return;
        }
        if ((control & 1) != 0) {
            if (--scalar[S_TIMER_A_COUNT] <= 0) {
                scalar[S_TIMER_A_COUNT] = scalar[S_TIMER_A_RELOAD];
                if ((control & 4) != 0) {
                    scalar[S_STATUS] |= 1;
                }
                if (scalar[S_CH3_MODE] == 2) {
                    // CSM: key channel 3 on for this frame.
                    for (int op = 0; op < 4; op++) {
                        int slot = 8 + op;
                        if (requestedKeyOn[slot] == 0) {
                            scheduleKey(slot, true, 3, 0);
                            csmKeyed[0] |= 1 << op;
                        }
                    }
                }
            }
        }
        // The key-off half of an admitted CSM pulse still finishes when
        // software stops timer A or leaves CSM before the next FM frame.
        // Otherwise a stopped counter equal to its reload strands the note.
        if (csmKeyed[0] != 0 && ((control & 1) == 0 || scalar[S_CH3_MODE] != 2
                || scalar[S_TIMER_A_COUNT] != scalar[S_TIMER_A_RELOAD])) {
            for (int op = 0; op < 4; op++) {
                if ((csmKeyed[0] & (1 << op)) != 0) {
                    scheduleKey(8 + op, false, 3, 0);
                }
            }
            csmKeyed[0] = 0;
        }
        if ((control & 2) != 0) {
            if (--scalar[S_TIMER_B_COUNT] <= 0) {
                scalar[S_TIMER_B_COUNT] = scalar[S_TIMER_B_RELOAD];
                if ((control & 8) != 0) {
                    scalar[S_STATUS] |= 2;
                }
            }
        }
    }

    @Override
    public int readStatus() {
        return scalar[S_STATUS] & 3;
    }

    // ---------------------------------------------------------- snapshots

    /** Every mutable state array in a fixed order shared by copy, equality and hashing; built once per instance. */
    private final int[][] state = {amHistory, scheduledIncrements, scheduledLevels, scheduledKeys, scheduledMask, requestedKeyOn, keyedThisFrame, dacOutputSlots, sampledEg, delayedOutput, olderOp1, registers, phase, phaseIncrement, attenuation, egState, keyOn, ssgInvert, ssgHeld, ssgPendingRestart, output,
                detune, multiple, totalLevel, rateScaling, attackRate, decayRate, sustainRate, releaseRate,
                sustainLevel, amEnabled, ssgMode, keyCode, rateAttack, rateDecay, rateSustain, rateRelease,
                operatorFnum, operatorBlock, channelFnum,
                channelBlock, latchedFnumHigh, latchedCh3FnumHigh, ch3Fnum, ch3Block, feedback, algorithm,
                amSensitivity, pmSensitivity, feedbackHistory, operatorOut, csmKeyed, scalar};

    private int[][] arrays() {
        return state;
    }

    @Override
    public void copyStateTo(FmDsp target) {
        if (!(target instanceof FastYm2612Dsp other)) {
            throw new IllegalArgumentException("target is not a FastYm2612Dsp");
        }
        int[][] from = arrays();
        int[][] to = other.arrays();
        for (int i = 0; i < from.length; i++) {
            System.arraycopy(from[i], 0, to[i], 0, from[i].length);
        }
    }

    @Override
    public FmDsp newInstance() {
        return new FastYm2612Dsp();
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof FastYm2612Dsp other)) {
            return false;
        }
        int[][] mine = arrays();
        int[][] theirs = other.arrays();
        for (int i = 0; i < mine.length; i++) {
            if (!Arrays.equals(mine[i], theirs[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 17;
        for (int[] array : arrays()) {
            result = 31 * result + Arrays.hashCode(array);
        }
        return result;
    }
}
