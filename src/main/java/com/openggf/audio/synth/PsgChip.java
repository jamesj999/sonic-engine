package com.openggf.audio.synth;

import java.util.Arrays;
import java.util.Objects;

/**
 * SN76489-family programmable sound generator, modelled as the Sega-integrated
 * variant found in the Mega Drive VDP (315-5313) with the discrete Texas
 * Instruments part available as {@link ChipType#DISCRETE}.
 *
 * <p><b>Provenance.</b> This class is a clean-room implementation written from
 * the public hardware specification at
 * {@code docs/architecture/research/audio/2026-08-29-sn76489-clean-room-spec.md}
 * (section numbers below refer to it) and the engine contract at
 * {@code docs/architecture/designs/2026-08-29-psg-clean-room-contract.md}.
 * No emulator source was consulted while writing it: not the previous body of
 * this class, not Genesis Plus GX, libvgm, MAME or BizHawk. Where the
 * specification records an ambiguity the choice taken is stated at the point
 * of use.
 *
 * <p><b>Model.</b> Everything the generators do happens on the chip's internal
 * tick, the 3,579,545 Hz input clock divided by 16 (§1). Each tone channel is a
 * ten-bit down-counter that reloads with its period register and flips its
 * output polarity on expiry (§3.1); the noise channel is the same counter
 * clocked from a fixed rate or tone 2's period, feeding a 16-bit (Sega) or
 * 15-bit (TI) shift register on every rising edge of its own square wave
 * (§4). Channel outputs are unipolar — {@code polarity ? level[A] : 0}
 * (§3.4) — and are summed linearly (§6); the DC that leaves is removed by the
 * band-limited delta buffer's high-pass, exactly as the console's AC coupling
 * removes it. Every polarity flip, shift-register step and attenuation change
 * is placed at its exact tick position inside the output sample stream through
 * {@link BlipDeltaBuffer} (§8) rather than rounded to a sample boundary.
 *
 * <p><b>Time.</b> The chip has no timestamp input. A write is applied at the
 * position reached by the previous {@link #renderStereo}, which always ends on
 * a tick boundary, so the generators see it from the next tick (§2.4). Writes
 * only touch registers; their audible consequences are emitted by the next
 * render, which is what makes the masked SFX-admission rollback a pure
 * register restore.
 *
 * <p><b>Levels.</b> Output is hardware-relative: attenuation {@code A} yields
 * {@code round(8191 × 10^(−A/10))} for {@code A < 15} and exactly 0 for
 * {@code A = 15} (§5). There is no built-in gain; FM/PSG balance belongs to the
 * mixer, which may call {@link #configure(int, int)}.
 */
public class PsgChip {

    /** Which SN76489 the generators imitate; see spec §3.2 and §4.3. */
    public enum ChipType {
        /** Sega VDP-integrated part (SMS, Mega Drive). */
        INTEGRATED,
        /** Discrete TI SN76489 / SN76489A / SN76496. */
        DISCRETE
    }

    /** NTSC PSG input clock: 53,693,175 Hz master ÷ 15 (§1). */
    public static final double INPUT_CLOCK_HZ = 53_693_175.0 / 15.0;

    /** Internal generator tick: input clock ÷ 16 = 223,721.5625 Hz (§1). */
    public static final double TICK_RATE_HZ = INPUT_CLOCK_HZ / 16.0;

    /** Full-scale level of one channel at attenuation 0 (§5, the ×8191 column). */
    public static final int FULL_SCALE = 8191;

    /** Attenuation 0xF switches the attenuator out entirely (§5). */
    public static final int ATTENUATION_OFF = 0xF;

    private static final int TONE_CHANNELS = 3;
    private static final int NOISE_CHANNEL = 3;
    private static final int CHANNELS = 4;
    private static final int PERIOD_MASK = 0x3FF;

    /** Attenuator ladder in 2 dB steps; index 15 is a true mute (§5). */
    private static final int[] LEVEL = new int[16];

    static {
        for (int a = 0; a < ATTENUATION_OFF; a++) {
            LEVEL[a] = (int) Math.round(FULL_SCALE * Math.pow(10.0, -2.0 * a / 20.0));
        }
        LEVEL[ATTENUATION_OFF] = 0;
    }

    /** Noise counter reload for rate bits {@code 00}, {@code 01}, {@code 10} (§4.1). */
    private static final int[] NOISE_RELOAD = {0x10, 0x20, 0x40};

    // --- LFSR geometry per chip type (§4.3) -------------------------------

    private static final int SEGA_LFSR_WIDTH = 16;
    private static final int SEGA_LFSR_TAPS = 0x0009;
    private static final int SEGA_LFSR_RESET = 0x8000;
    private static final int TI_LFSR_WIDTH = 15;
    private static final int TI_LFSR_TAPS = 0x0003;
    private static final int TI_LFSR_RESET = 0x4000;

    /**
     * Discrete-part period 0: the ten-bit counter wraps, so the channel runs
     * at 0x400 (§3.2). Never used on the integrated part.
     */
    private static final int DISCRETE_ZERO_PERIOD = 0x400;

    // --- Configuration (survives reset()) ---------------------------------

    private double sampleRate;
    private ChipType chipType;
    private int preamp = 100;
    private int panning = 0xFF;
    private final boolean[] mutes = new boolean[CHANNELS];
    private ChipWriteObserver writeObserver = ChipWriteObserver.NONE;
    /** Diagnostic-only native generator ticks; snapshots deliberately do not restore it. */
    private long physicalTick;

    // --- Registers (§2, §3, §4) --------------------------------------------

    private final int[] tonePeriod = new int[TONE_CHANNELS];
    private final int[] attenuation = new int[CHANNELS];
    /** {@code m rr}: bit 2 white/periodic, bits 1-0 shift rate. */
    private int noiseControl;
    /** {@code (channel << 1) | type}, bits 6-4 of the last latch byte (§2.1). */
    private int latch;

    // --- Generator state ---------------------------------------------------

    /** Down-counters; index 3 is the noise counter. */
    private final int[] counter = new int[CHANNELS];
    /** Square-wave polarity; index 3 is the noise clock's own polarity. */
    private final boolean[] polarity = new boolean[CHANNELS];
    private int lfsr;

    // --- Output stage ------------------------------------------------------

    /** Attenuator output per channel with preamp, panning and mute applied. */
    private final int[] ampLeft = new int[CHANNELS];
    private final int[] ampRight = new int[CHANNELS];
    /** Level last emitted into the delta buffer per channel and side. */
    private final int[] emittedLeft = new int[CHANNELS];
    private final int[] emittedRight = new int[CHANNELS];
    private final BlipDeltaBuffer blip;

    public PsgChip() {
        this(44100.0, ChipType.INTEGRATED);
    }

    public PsgChip(double sampleRate) {
        this(sampleRate, ChipType.INTEGRATED);
    }

    public PsgChip(double sampleRate, ChipType type) {
        this.sampleRate = sampleRate > 0.0 ? sampleRate : 44100.0;
        this.chipType = type == null ? ChipType.INTEGRATED : type;
        this.blip = new BlipDeltaBuffer(TICK_RATE_HZ, this.sampleRate);
        reset();
    }

    // --- Configuration -----------------------------------------------------

    /**
     * Re-derives the tick-to-sample timebase. Registers, generator state,
     * mutes and modes survive; the delta buffer keeps its pending tail so the
     * output stays continuous across a device-rate change.
     */
    public void setSampleRate(double sampleRate) {
        if (!(sampleRate > 0.0) || Double.isInfinite(sampleRate)) {
            return;
        }
        this.sampleRate = sampleRate;
        blip.setRates(TICK_RATE_HZ, sampleRate);
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    /**
     * Selects the shift-register geometry and period-0/1 rule (§3.2, §4.3).
     * The LFSR is reseeded with the new type's reset value because a 16-bit
     * state is not a valid 15-bit one.
     */
    public void setChipType(ChipType type) {
        if (type == null || type == chipType) {
            return;
        }
        chipType = type;
        lfsr = lfsrReset();
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    /**
     * Output-stage configuration. {@code preamp} is a percentage (100 =
     * unity); {@code panning} is the SN76489 stereo byte, bits 7..4 enabling
     * channels 3..0 on the left and bits 3..0 on the right. The constructor
     * default is {@code configure(100, 0xFF)}.
     */
    public void configure(int preamp, int panning) {
        this.preamp = Math.max(0, preamp);
        this.panning = panning & 0xFF;
        for (int ch = 0; ch < CHANNELS; ch++) {
            updateAmplitude(ch);
        }
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    /** Ignores channels outside 0..3. A muted channel keeps advancing. */
    public void setMute(int ch, boolean mute) {
        if (ch < 0 || ch >= CHANNELS) {
            return;
        }
        mutes[ch] = mute;
        updateAmplitude(ch);
        emitPhysicalBoundary(ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    /** Installs the diagnostic write sink; {@code null} means none. */
    void setWriteObserver(ChipWriteObserver observer) {
        this.writeObserver = observer == null ? ChipWriteObserver.NONE : observer;
    }

    void reportPhysicalTimelineBoundary(
            ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        emitPhysicalBoundary(boundary);
    }

    /**
     * Power-on state (§7): all attenuators off, tone periods 0, noise control
     * 0 (periodic, ÷16), latch = tone 0 period, LFSR seeded, counters 0 and
     * polarities high. The timebase restarts. Mutes, modes, preamp and
     * panning are untouched and no write is emitted.
     */
    public void reset() {
        Arrays.fill(tonePeriod, 0);
        Arrays.fill(attenuation, ATTENUATION_OFF);
        noiseControl = 0;
        latch = 0;
        Arrays.fill(counter, 0);
        Arrays.fill(polarity, true);
        lfsr = lfsrReset();
        Arrays.fill(emittedLeft, 0);
        Arrays.fill(emittedRight, 0);
        blip.reset(TICK_RATE_HZ, sampleRate);
        for (int ch = 0; ch < CHANNELS; ch++) {
            updateAmplitude(ch);
        }
        emitPhysicalBoundary(ChipWriteObserver.PhysicalTimelineBoundary.RESET);
    }

    /** The ROM's silence-all sequence: attenuation 0xF to every channel (§7). */
    public void silenceAll() {
        write(0x9F);
        write(0xBF);
        write(0xDF);
        write(0xFF);
    }

    // --- Write protocol (§2) -----------------------------------------------

    /**
     * Accepts one data-bus byte; only the low eight bits are significant.
     * Applied at the chip's current time, which is the tick boundary the last
     * render stopped on.
     */
    public void write(int value) {
        value &= 0xFF;
        writeObserver.onPsgWrite(value);

        boolean latchByte = (value & 0x80) != 0;
        int data;
        if (latchByte) {
            latch = (value >> 4) & 0x7;
            data = value & 0x0F;
        } else {
            data = value & 0x3F;
        }
        int channel = latch >> 1;
        boolean volume = (latch & 1) != 0;

        if (volume) {
            // §2.1/§2.2: all four low bits replace the attenuation register.
            attenuation[channel] = data & 0x0F;
            updateAmplitude(channel);
        } else if (channel < TONE_CHANNELS) {
            if (latchByte) {
                // Low nibble of the ten-bit period; bits 9-4 unchanged.
                tonePeriod[channel] = (tonePeriod[channel] & 0x3F0) | data;
            } else {
                // Data byte carries bits 9-4; bits 3-0 keep the latch's nibble.
                tonePeriod[channel] = (tonePeriod[channel] & 0x00F) | (data << 4);
            }
        } else {
            // §2.1/§2.2/§4.5: bits 2-0 replace the noise control and any write
            // that lands here resets the LFSR, whether or not the value changed.
            // The noise down-counter is deliberately left alone (§10.14).
            noiseControl = data & 0x7;
            lfsr = lfsrReset();
        }
        if (writeObserver.observesPhysicalWrites()) {
            writeObserver.onPsgBusWrite(physicalTick, value);
        }
    }

    // --- Rendering ---------------------------------------------------------

    public void renderStereo(int[] left, int[] right) {
        renderStereo(left, right, Math.min(left.length, right.length));
    }

    /**
     * Accumulates {@code len} stereo samples into the caller's arrays,
     * advancing the chip by exactly the ticks that fall inside them.
     */
    public void renderStereo(int[] left, int[] right, int len) {
        len = Math.min(len, Math.min(left.length, right.length));
        if (len <= 0) {
            return;
        }
        int ticks = blip.clocksNeeded(len);
        blip.ensureCapacity(len);
        for (int t = 0; t < ticks; t++) {
            emitLevelChanges(t);
            tick();
        }
        physicalTick += ticks;
        blip.endFrame(ticks);
        blip.readSamples(left, right, len);
    }

    /**
     * Compares each channel's present output with what the delta buffer has
     * already been told and places the difference at tick {@code time} of the
     * current frame. Attenuation writes, mutes and configuration changes reach
     * the output here at the boundary the write landed on (§5: attenuation is
     * not synchronised to the polarity flip).
     */
    private void emitLevelChanges(int time) {
        for (int ch = 0; ch < CHANNELS; ch++) {
            boolean high = ch == NOISE_CHANNEL ? (lfsr & 1) != 0 : polarity[ch];
            int outL = high ? ampLeft[ch] : 0;
            int outR = high ? ampRight[ch] : 0;
            int dL = outL - emittedLeft[ch];
            int dR = outR - emittedRight[ch];
            if ((dL | dR) != 0) {
                blip.addDelta(time, dL, dR);
                emittedLeft[ch] = outL;
                emittedRight[ch] = outR;
            }
        }
    }

    /**
     * One ÷16 tick for all four generators (§3.1, §4.1). A counter at zero is
     * the reset condition "reload on the first tick" (§7): it takes its period
     * without flipping, so a channel written while its counter sits at zero
     * first flips one full period later, as the §9.3 vectors state. From
     * there each decrement that reaches zero reloads and flips.
     */
    private void tick() {
        for (int ch = 0; ch < TONE_CHANNELS; ch++) {
            int period = tonePeriod[ch];
            if (counter[ch] == 0) {
                counter[ch] = reloadFor(period);
                if (holdsHigh(period)) {
                    polarity[ch] = true;
                }
            } else if (--counter[ch] == 0) {
                counter[ch] = reloadFor(period);
                // §3.2: on the Sega variant N <= 1 is a constant "high" level.
                polarity[ch] = holdsHigh(period) || !polarity[ch];
            }
        }
        tickNoise();
    }

    /**
     * The noise clock is a fourth counter of the same shape. Rate {@code 11}
     * is modelled as Maxim describes it — a separate counter reloaded with
     * tone 2's period — rather than the TI datasheet's shared flip-flop; the
     * two differ only in phase (§4.1, §10.13). What that counter borrows from
     * tone 2 is its <em>reload value</em>, not its held output: with
     * N2 &lt;= 1 the integrated part's constant-high rule pins tone 2's
     * polarity latch (§3.2) while the ten-bit counter underneath still
     * expires every tick, and that expiry is what clocks the noise. So the
     * linked noise square wave keeps flipping once per tick, the LFSR shifts
     * every second tick, and SMPS's top-of-table noise notes are the highest
     * noise pitch rather than silence (§4.1, §10.5, resolved by the capture
     * comparison of 2026-08-29).
     */
    private void tickNoise() {
        int rate = noiseControl & 0x3;
        int reload = rate == 3 ? linkedNoiseReload(tonePeriod[2]) : NOISE_RELOAD[rate];
        if (counter[NOISE_CHANNEL] == 0) {
            counter[NOISE_CHANNEL] = reload;
        } else if (--counter[NOISE_CHANNEL] == 0) {
            counter[NOISE_CHANNEL] = reload;
            boolean rising = !polarity[NOISE_CHANNEL];
            polarity[NOISE_CHANNEL] = rising;
            // libvgm sn76489: the LFSR shifts once per rising edge of the
            // noise square wave (§4.1), never on the falling edge.
            if (rising) {
                shiftLfsr();
            }
        }
    }

    /**
     * Ticks between noise-clock edges when linked to tone 2 (§4.1). The
     * discrete part's period 0 wraps to 0x400 like its tone counter; on the
     * integrated part periods 0 and 1 both expire every tick (§10.5).
     */
    private int linkedNoiseReload(int tonePeriodTwo) {
        if (chipType == ChipType.DISCRETE) {
            return reloadFor(tonePeriodTwo);
        }
        return Math.max(tonePeriodTwo, 1);
    }

    /** Ticks until the next expiry for a period register value. */
    private int reloadFor(int period) {
        if (period == 0 && chipType == ChipType.DISCRETE) {
            return DISCRETE_ZERO_PERIOD;
        }
        return period;
    }

    /** §3.2: on the Sega part periods 0 and 1 never flip. */
    private boolean holdsHigh(int period) {
        return period <= 1 && chipType == ChipType.INTEGRATED;
    }

    /** One shift-register step (§4.2, §4.3); the output bit is bit 0 after it. */
    private void shiftLfsr() {
        boolean white = (noiseControl & 0x4) != 0;
        int feedback;
        if (white) {
            feedback = Integer.bitCount(lfsr & lfsrTaps()) & 1;
        } else {
            feedback = lfsr & 1;
        }
        lfsr = (lfsr >>> 1) | (feedback << (lfsrWidth() - 1));
    }

    private int lfsrWidth() {
        return chipType == ChipType.DISCRETE ? TI_LFSR_WIDTH : SEGA_LFSR_WIDTH;
    }

    private int lfsrTaps() {
        return chipType == ChipType.DISCRETE ? TI_LFSR_TAPS : SEGA_LFSR_TAPS;
    }

    private int lfsrReset() {
        return chipType == ChipType.DISCRETE ? TI_LFSR_RESET : SEGA_LFSR_RESET;
    }

    private void updateAmplitude(int ch) {
        int level = mutes[ch] ? 0 : LEVEL[attenuation[ch]] * preamp / 100;
        ampLeft[ch] = (panning & (0x10 << ch)) != 0 ? level : 0;
        ampRight[ch] = (panning & (0x01 << ch)) != 0 ? level : 0;
    }

    /** Hardware-relative attenuator output for {@code a} in 0..15 (§5). */
    public static int attenuationLevel(int a) {
        return LEVEL[a & 0xF];
    }

    // --- Snapshot and rewind -----------------------------------------------

    /**
     * Complete state for bit-exact continuation. Arrays are copied on
     * construction and on access; the record is Jackson-serialisable as is.
     */
    public record Snapshot(
            double sampleRate,
            ChipType chipType,
            int preamp,
            int panning,
            boolean[] mutes,
            int[] tonePeriods,
            int[] attenuations,
            int noiseControl,
            int latch,
            int[] counters,
            boolean[] polarities,
            int lfsr,
            int[] emittedLeft,
            int[] emittedRight,
            BlipDeltaBuffer.Snapshot blip) {
        public Snapshot {
            mutes = mutes.clone();
            tonePeriods = tonePeriods.clone();
            attenuations = attenuations.clone();
            counters = counters.clone();
            polarities = polarities.clone();
            emittedLeft = emittedLeft.clone();
            emittedRight = emittedRight.clone();
        }

        @Override
        public boolean[] mutes() {
            return mutes.clone();
        }

        @Override
        public int[] tonePeriods() {
            return tonePeriods.clone();
        }

        @Override
        public int[] attenuations() {
            return attenuations.clone();
        }

        @Override
        public int[] counters() {
            return counters.clone();
        }

        @Override
        public boolean[] polarities() {
            return polarities.clone();
        }

        @Override
        public int[] emittedLeft() {
            return emittedLeft.clone();
        }

        @Override
        public int[] emittedRight() {
            return emittedRight.clone();
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof Snapshot other
                    && Double.compare(sampleRate, other.sampleRate) == 0
                    && chipType == other.chipType
                    && preamp == other.preamp
                    && panning == other.panning
                    && Arrays.equals(mutes, other.mutes)
                    && Arrays.equals(tonePeriods, other.tonePeriods)
                    && Arrays.equals(attenuations, other.attenuations)
                    && noiseControl == other.noiseControl
                    && latch == other.latch
                    && Arrays.equals(counters, other.counters)
                    && Arrays.equals(polarities, other.polarities)
                    && lfsr == other.lfsr
                    && Arrays.equals(emittedLeft, other.emittedLeft)
                    && Arrays.equals(emittedRight, other.emittedRight)
                    && Objects.equals(blip, other.blip);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(sampleRate, chipType,
                    preamp, panning, noiseControl,
                    latch, lfsr, blip);
            result = 31 * result + Arrays.hashCode(mutes);
            result = 31 * result + Arrays.hashCode(tonePeriods);
            result = 31 * result + Arrays.hashCode(attenuations);
            result = 31 * result + Arrays.hashCode(counters);
            result = 31 * result + Arrays.hashCode(polarities);
            result = 31 * result + Arrays.hashCode(emittedLeft);
            return 31 * result + Arrays.hashCode(emittedRight);
        }
    }

    /** Mutable owner-private transaction storage; never used by durable snapshots. */
    static final class MutationBackup {
        private double sampleRate;
        private ChipType chipType;
        private int preamp;
        private int panning;
        private int noiseControl;
        private int latch;
        private int lfsr;
        private boolean[] mutes = new boolean[CHANNELS];
        private int[] tonePeriod = new int[TONE_CHANNELS];
        private int[] attenuation = new int[CHANNELS];
        private int[] counter = new int[CHANNELS];
        private boolean[] polarity = new boolean[CHANNELS];
        private int[] emittedLeft = new int[CHANNELS];
        private int[] emittedRight = new int[CHANNELS];
        private final BlipDeltaBuffer.MutationBackup blip = new BlipDeltaBuffer.MutationBackup();
    }

    void captureMutation(MutationBackup backup) {
        backup.sampleRate = sampleRate;
        backup.chipType = chipType;
        backup.preamp = preamp;
        backup.panning = panning;
        backup.noiseControl = noiseControl;
        backup.latch = latch;
        backup.lfsr = lfsr;
        if (backup.mutes.length < mutes.length) backup.mutes = new boolean[mutes.length];
        System.arraycopy(mutes, 0, backup.mutes, 0, mutes.length);
        if (backup.tonePeriod.length < tonePeriod.length) backup.tonePeriod = new int[tonePeriod.length];
        System.arraycopy(tonePeriod, 0, backup.tonePeriod, 0, tonePeriod.length);
        if (backup.attenuation.length < attenuation.length) backup.attenuation = new int[attenuation.length];
        System.arraycopy(attenuation, 0, backup.attenuation, 0, attenuation.length);
        if (backup.counter.length < counter.length) backup.counter = new int[counter.length];
        System.arraycopy(counter, 0, backup.counter, 0, counter.length);
        if (backup.polarity.length < polarity.length) backup.polarity = new boolean[polarity.length];
        System.arraycopy(polarity, 0, backup.polarity, 0, polarity.length);
        if (backup.emittedLeft.length < emittedLeft.length) backup.emittedLeft = new int[emittedLeft.length];
        System.arraycopy(emittedLeft, 0, backup.emittedLeft, 0, emittedLeft.length);
        if (backup.emittedRight.length < emittedRight.length) backup.emittedRight = new int[emittedRight.length];
        System.arraycopy(emittedRight, 0, backup.emittedRight, 0, emittedRight.length);
        blip.captureMutation(backup.blip);
    }

    void restoreMutation(MutationBackup backup) {
        sampleRate = backup.sampleRate;
        chipType = backup.chipType;
        preamp = backup.preamp;
        panning = backup.panning;
        noiseControl = backup.noiseControl;
        latch = backup.latch;
        lfsr = backup.lfsr;
        System.arraycopy(backup.mutes, 0, mutes, 0, mutes.length);
        System.arraycopy(backup.tonePeriod, 0, tonePeriod, 0, tonePeriod.length);
        System.arraycopy(backup.attenuation, 0, attenuation, 0, attenuation.length);
        System.arraycopy(backup.counter, 0, counter, 0, counter.length);
        System.arraycopy(backup.polarity, 0, polarity, 0, polarity.length);
        System.arraycopy(backup.emittedLeft, 0, emittedLeft, 0, emittedLeft.length);
        System.arraycopy(backup.emittedRight, 0, emittedRight, 0, emittedRight.length);
        blip.restoreMutation(backup.blip);
        for (int ch = 0; ch < CHANNELS; ch++) updateAmplitude(ch);
        emitPhysicalBoundary(ChipWriteObserver.PhysicalTimelineBoundary.SNAPSHOT_RESTORE);
    }

    public Snapshot captureSnapshot() {
        return new Snapshot(
                sampleRate,
                chipType,
                preamp,
                panning,
                mutes,
                tonePeriod,
                attenuation,
                noiseControl,
                latch,
                counter,
                polarity,
                lfsr,
                emittedLeft,
                emittedRight,
                blip.captureSnapshot());
    }

    /** The snapshot, not the target chip, is authoritative for rate and modes. */
    public void restoreSnapshot(Snapshot snapshot) {
        sampleRate = snapshot.sampleRate();
        chipType = snapshot.chipType();
        preamp = snapshot.preamp();
        panning = snapshot.panning();
        copyInto(mutes, snapshot.mutes());
        copyInto(tonePeriod, snapshot.tonePeriods());
        copyInto(attenuation, snapshot.attenuations());
        noiseControl = snapshot.noiseControl();
        latch = snapshot.latch();
        copyInto(counter, snapshot.counters());
        copyInto(polarity, snapshot.polarities());
        lfsr = snapshot.lfsr();
        copyInto(emittedLeft, snapshot.emittedLeft());
        copyInto(emittedRight, snapshot.emittedRight());
        blip.restoreSnapshot(snapshot.blip());
        for (int ch = 0; ch < CHANNELS; ch++) {
            updateAmplitude(ch);
        }
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.SNAPSHOT_RESTORE);
    }

    private void emitPhysicalBoundary(
            ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        if (writeObserver.observesPhysicalWrites()) {
            writeObserver.onPhysicalTimelineBoundary(
                    ChipWriteObserver.ChipClockDomain.PSG_GENERATOR_TICK,
                    physicalTick, boundary);
        }
    }

    private static void copyInto(int[] target, int[] source) {
        System.arraycopy(source, 0, target, 0, Math.min(target.length, source.length));
    }

    private static void copyInto(boolean[] target, boolean[] source) {
        System.arraycopy(source, 0, target, 0, Math.min(target.length, source.length));
    }

    /**
     * Register-only rollback state for the channels in {@code mask}. Writes
     * touch nothing but registers and the latch (their audible effect is
     * emitted by the next render), so undoing a burst of writes between two
     * renders is a matter of restoring these.
     */
    record SfxAdmissionState(
            int channelMask,
            int latch,
            int[] tonePeriods,
            int[] attenuations,
            int noiseControl,
            int lfsr) {
        SfxAdmissionState {
            tonePeriods = tonePeriods.clone();
            attenuations = attenuations.clone();
        }
    }

    SfxAdmissionState captureSfxAdmissionState(int affectedChannelMask) {
        return new SfxAdmissionState(
                affectedChannelMask & 0xF,
                latch,
                tonePeriod,
                attenuation,
                noiseControl,
                lfsr);
    }

    void restoreSfxAdmissionState(SfxAdmissionState state) {
        latch = state.latch();
        for (int ch = 0; ch < CHANNELS; ch++) {
            if ((state.channelMask() & (1 << ch)) == 0) {
                continue;
            }
            attenuation[ch] = state.attenuations()[ch];
            if (ch < TONE_CHANNELS) {
                tonePeriod[ch] = state.tonePeriods()[ch];
            } else {
                noiseControl = state.noiseControl();
                lfsr = state.lfsr();
            }
            updateAmplitude(ch);
        }
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }
}
