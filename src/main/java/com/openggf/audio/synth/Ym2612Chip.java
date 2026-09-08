package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.synth.nuked.NukedOpn2;
import com.openggf.audio.synth.nuked.NukedOpn2State;

import java.util.Arrays;
import java.util.Objects;

/**
 * YM2612 FM synthesiser: a facade over the Nuked-OPN2 port
 * ({@link NukedOpn2}, LGPL-2.1-or-later); no other emulator source consulted.
 *
 * <p>The chip model itself lives in {@code com.openggf.audio.synth.nuked} and
 * is clocked one internal cycle at a time. Everything in this class is engine
 * glue written from the port contract
 * ({@code docs/architecture/designs/2026-08-29-nuked-opn2-port-contract.md}):
 * the register-write queue and its bus pacing, the per-frame pin sum and the
 * output scale, the internal-rate to host-rate resampler, the SMPS voice
 * unpack, the Z80-driver DAC streaming, the output-stage mutes, and the
 * rewind snapshot. None of it is chip behaviour, and the pinned upstream
 * revision ships no equivalent ({@code OPN2_GenerateResampled},
 * {@code OPN2_WriteBuffered}, a write queue and a ladder switch do not exist
 * there).
 *
 * <h2>Clocking and output</h2>
 * One internal cycle is six master clocks; the 24-cycle slot sweep is one
 * output frame, so the native frame rate is {@code 7670453 / 144 = 53267.03}
 * Hz ({@link #getInternalRate()}). {@code OPN2_Clock} leaves the
 * time-multiplexed MOL/MOR pin values on every cycle
 * ({@code chOutput}, {@code ym3438.c:947}); the analogue output of the real
 * part is the RC-averaged pin, so the frame sample is the sum of the 24 pin
 * values of the frame. In that sum a channel at full scale contributes
 * {@code 3 * 256 = 768} in both output stages (the YM2612 stage places one
 * amplified pin cycle plus three sign cycles per channel, the YM3438 stage
 * three plain pin cycles); the sum is shifted left by {@link #OUTPUT_SHIFT}
 * so one full-scale channel sits at 6144, the largest power-of-two scaling
 * that keeps six full-scale channels inside 16 bits after the mixer's
 * {@code MASTER_GAIN_SHIFT}. The previous core's nominal per-channel scale
 * was 8191; the mixer's {@code VirtualSynthesizer.PSG_PREAMP_PERCENT} carries
 * the FM:PSG balance (see
 * {@code docs/architecture/validation/2026-08-29-audio-mix-calibration.md}).
 * In YM2612 mode a silent channel still contributes {@code +3} per cycle
 * ({@code sign} cycles of {@code chOutput}), so a silenced chip rests at
 * {@code 72 << OUTPUT_SHIFT = 576} per side at chip scale; that is the model's
 * own resting level and is not adjusted here.
 *
 * <h2>Register writes</h2>
 * {@link #write(int, int, int)} resolves the port, masks the register and
 * value to eight bits, reports the resolved write to the
 * {@link ChipWriteObserver} and queues it. Queued writes are applied, in
 * order, before the next rendered sample (and before a status read or a
 * forced channel silence, so ordering with those is preserved), each with
 * the bus pacing of a driver that honours the chip's busy flag: the address
 * strobe is consumed on the clock after it is presented ({@code doIo},
 * {@code ym3438.c:224}) and does not raise busy, so the data strobe follows
 * one clock later; the data strobe raises busy ({@code write_busy},
 * {@code doIo}) for the 32-cycle window of {@code write_busy_cnt}, which a
 * status read sees from the second clock after the strobe, so the next
 * strobe is presented on the first clock at which a status poll returns
 * not-busy ({@code DATA_SETTLE_CYCLES}). Every data strobe, the DAC's
 * {@code 0x2A} stream included, holds the bus for that window; nothing keys
 * on the register. This is what makes writes never drop: a {@code 0x28}
 * latch ({@code mode_kon_channel}) is consumed only when the sequencer reaches
 * its channel, up to 23 cycles later, and the next {@code 0x28} data strobe
 * overwrites it, so a shorter hold loses key-ons that the ROM drivers, which
 * wait on busy, never lose on hardware. The cycles spent draining writes are
 * real chip time and their frames go to the resampler like any other, so the
 * following render simply needs fewer new frames; pitch and tempo are
 * unaffected.
 *
 * <h2>DAC</h2>
 * {@link #playDac(int)} streams a {@link DacData} sample as {@code 0x2A}
 * writes at the cadence of the ROM's Z80 playback loop (see
 * {@link #dacPeriod(int, int)}). When {@link #setDacInterpolate(boolean)} is
 * on, the staircase between two PCM samples is additionally refined by a
 * linearly interpolated {@code 0x2A} write once per output frame; this is a
 * presentation option with no hardware counterpart, and it never alters the
 * sample cadence (see {@link #serviceDac(int)}). Each real sample write is
 * reported to the observer exactly as a sequencer write is, because the ROM's
 * playback loop sends every decoded sample byte over the same address/data
 * pair (Sound/Z80 Sound Driver.asm:4299-4351); the synthetic interpolated
 * write has no ROM counterpart and is never reported. Exhausting a sample
 * raises a sample-end edge for the session to consume
 * ({@link #consumeDacSampleEnded()}), the point at which the ROM clears
 * {@code zDACIndex} and re-enters {@code zPlayDigitalAudio}, whose entry
 * disables the DAC (:4352-4355, :4256-4260). {@code 0x2B} (DAC enable) is
 * left to the sequencer, the session's physical policy and the core.
 *
 * <h2>Snapshot</h2>
 * {@link #captureSnapshot()} is pure and {@link #restoreSnapshot(Snapshot)}
 * is total: the record carries the complete {@link NukedOpn2State}, the
 * pending write queue, the DAC streaming state, the output-stage state and the
 * resampler tail, so restoring into any chip and clocking on is bit-identical
 * to never having stopped.
 */
public class Ym2612Chip implements FmChip {

    private static final double MASTER_CLOCK_HZ = 7670453.0;
    /** Six master clocks per internal cycle, 24 cycles per frame. */
    private static final int MASTER_CLOCKS_PER_FRAME = 6 * NukedOpn2.CYCLES_PER_FRAME;
    private static final double INTERNAL_RATE = MASTER_CLOCK_HZ / MASTER_CLOCKS_PER_FRAME;
    private static final double DEFAULT_OUTPUT_RATE = 44100.0;

    /**
     * Left shift applied to the 24-cycle pin sum (per-channel full scale 768,
     * {@code chOutput}, {@code ym3438.c:947}) to reach the engine's nominal
     * per-channel scale; see the class Javadoc.
     */
    private static final int OUTPUT_SHIFT = 3;

    /**
     * The pin value {@code chOutput} ({@code ym3438.c:947}) produces on every
     * cycle of a silent channel's four-cycle window in YM2612 mode: the
     * {@code sign} of a non-negative output is 1, amplified by 3. A muted
     * channel is replaced by this so it keeps the resting level of a silent
     * one instead of contributing nothing.
     */
    private static final int YM2612_SILENT_PIN_VALUE = 3;

    /**
     * Channel whose output occupies each four-cycle pin window, indexed by
     * {@code cycles >> 2} before the clock: {@code chOutput} takes
     * {@code channel = cycles % 6}, plus one for the first twelve cycles
     * ({@code ym3438.c:947}).
     */
    private static final int[] PIN_WINDOW_CHANNEL = {1, 5, 3, 0, 4, 2};

    /**
     * Clocks that must run after an address strobe before the next strobe:
     * the one on which {@code doIo} consumes it ({@code ym3438.c:224}). An
     * address write does not raise busy in {@code ym3438.c} ({@code write_busy}
     * follows {@code write_d_en} only) and whether it does on silicon is open
     * (behaviour-vectors doc, open question 1), so the hold is the smallest
     * one under which the address is latched before the data strobe arrives.
     */
    private static final int ADDRESS_SETTLE_CYCLES = 1;
    /**
     * Clocks from a data strobe until a status poll first reads not-busy: the
     * strobe is consumed on the first clock ({@code doIo} raises
     * {@code write_busy}), the status byte reflects it from the second
     * ({@code busy = write_busy}), and {@code write_busy_cnt} then holds busy
     * for 32 cycles (behaviour-vectors doc REG-06 and "Address latch
     * behaviour", 32 internal cycles ≈ 1.33 samples; asserted by
     * {@code TestYm2612HardwareBehaviour.reg06BusyFlagLastsThirtyTwoInternalCycles}).
     * A busy-polling driver presents its next strobe no earlier than this.
     */
    private static final int DATA_SETTLE_CYCLES = 2 + 32;

    /** {@code EG_NUM_RELEASE} ({@code ym3438.c:41}). */
    private static final int EG_STATE_RELEASE = 3;
    /** Envelope level of a fully released operator ({@code OPN2_Reset}, {@code ym3438.c:1186}). */
    private static final int EG_LEVEL_SILENT = 0x3ff;

    /* Pending operation queue: OP_STRIDE ints per entry. */
    private static final int OP_STRIDE = 5;
    private static final int OP_WRITE = 0;
    private static final int OP_ADDRESS = 1;
    private static final int OP_DATA = 2;
    private static final int OP_FORCE_SILENCE = 3;
    private static final int OP_DAC_PLAY = 4;
    private static final int OP_DAC_STOP = 5;
    private static final int NO_CHANNEL = -1;

    /*
     * SMPS voice layout (byte index into the 25-byte voice) per slot in the
     * chip's slot order 0..3; TL bytes exist only in 25-byte voices.
     */
    private static final int[] VOICE_DT_MUL = {1, 3, 2, 4};
    private static final int[] VOICE_TL = {21, 23, 22, 24};
    private static final int[] VOICE_RS_AR = {5, 7, 6, 8};
    private static final int[] VOICE_AM_D1R = {9, 11, 10, 12};
    private static final int[] VOICE_D2R = {13, 15, 14, 16};
    private static final int[] VOICE_D1L_RR = {17, 19, 18, 20};
    private static final int VOICE_LENGTH_WITH_TL = 25;

    /*
     * DAC cadence. The Z80 drivers deliver two 4-bit DPCM samples per byte
     * with a fixed instruction path of DacData.baseCycles() Z80 cycles per
     * byte (counted in the disassemblies with the final, not-taken 8-cycle
     * djnz of each half included: s1disasm sound/z80.asm zPlayPCMLoop "301 in
     * total", s2disasm s2.sounddriver.asm zWriteToDAC "295 cycles for two
     * samples", skdisasm Sound/Z80 Sound Driver.asm zPlayDigitalAudio
     * .dac_playback_loop total), plus the pitch loop "djnz $" run once per
     * half with b = rate: the taken branch costs 13 cycles, so each half adds
     * 13 * (rate - 1) cycles and a rate byte of 0 counts 256 times.
     */
    private static final int Z80_DJNZ_TAKEN_CYCLES = 13;
    private static final int DAC_SAMPLES_PER_BYTE = 2;
    private static final int Z80_DJNZ_ZERO_COUNT = 256;
    /**
     * Internal FM cycles per Z80 cycle: {@code (7670453 / 6) / 3579545}. Both
     * clocks divide the 53.693175 MHz master clock (by 7 and by 15), so the
     * ratio is exactly {@code 15 / 42 = 5 / 14}.
     */
    private static final int FM_CYCLES_PER_Z80_CYCLE_NUMERATOR = 5;
    private static final int FM_CYCLES_PER_Z80_CYCLE_DENOMINATOR = 14;
    /** The DAC accumulator counts in {@code 1 / DAC_TICK_UNITS} of an internal cycle. */
    private static final int DAC_TICK_UNITS = FM_CYCLES_PER_Z80_CYCLE_DENOMINATOR * DAC_SAMPLES_PER_BYTE;
    private static final int DAC_REGISTER = 0x2A;
    /**
     * Cadence headroom a synthetic interpolated write needs so that it has
     * fully settled (address strobe, its hold, data strobe, its hold) before
     * the real Z80 cadence can present its next sample.
     */
    private static final int SYNTHETIC_WRITE_UNITS =
            DAC_TICK_UNITS * (ADDRESS_SETTLE_CYCLES + DATA_SETTLE_CYCLES + 2);
    private static final int NO_DAC_VALUE = -1;

    private final NukedOpn2 core = new NukedOpn2();
    private final NukedOpn2State state = core.state();
    private final int[] pinBuffer = new int[2];
    private final BlipResampler resampler = new BlipResampler(INTERNAL_RATE, DEFAULT_OUTPUT_RATE);
    private final boolean[] mutes = new boolean[6];
    private ChipWriteObserver writeObserver = ChipWriteObserver.NONE;
    private DacData dacData;

    /** Engine-level chip type: 0 discrete YM2612 (default), 1 and 2 the YM3438 output stage. */
    private int chipType;
    private double outputRate = DEFAULT_OUTPUT_RATE;

    /* Frame accumulation and the 1:1 frame queue used when no resampling is needed. */
    private int frameSumLeft;
    private int frameSumRight;
    private int[] directFrames = new int[2 * 256];
    private int directFrameHead;
    private int directFrameCount;

    /* Bus pacing. */
    private int busHold;

    /* Pending operations. */
    private int[] pendingOps = new int[OP_STRIDE * 256];
    private int pendingCount;
    private long flushedOps;
    private int queuedAddress;

    /* DAC streaming. */
    private int dacSampleId = NO_DAC_VALUE;
    /** Resolved {@link #dacSampleId} in {@link #dacData}; derived, never snapshotted. */
    private DacData.Sample dacSample;
    private DacData dacSampleData;
    private int dacSampleDataId = NO_DAC_VALUE;
    private int dacPeriod;
    private int dacIndex;
    private int dacAccumulator;
    private int dacPreviousValue = NO_DAC_VALUE;
    private int dacPendingValue = NO_DAC_VALUE;
    private int dacWritePhase;
    private int dacWriteValue;
    private ChipWriteObserver.PhysicalWriteOrigin dacWriteOrigin =
            ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS;
    private boolean dacInterpolate = false;
    /**
     * Sample-end edges raised but not yet consumed. Raised where the ROM's
     * playback loop runs out of sample bytes and falls through to
     * {@code xor a / ld (zDACIndex), a / jp zPlayDigitalAudio}
     * (Sound/Z80 Sound Driver.asm:4348-4355); a re-trigger or a stop clears
     * the index without reaching that fall-through, so neither raises one.
     */
    private int dacSampleEndPending;
    /** Diagnostic-only clock; it deliberately does not participate in snapshots. */
    private long physicalCycle;

    /** Constructs a reset chip at {@link #getDefaultOutputRate()}; nothing global is touched. */
    public Ym2612Chip() {
        applyChipType(0);
        reset();
    }

    /** Native frame rate, {@code 7670453 / 144} Hz. */
    public static double getInternalRate() {
        return INTERNAL_RATE;
    }

    public static double getDefaultOutputRate() {
        return DEFAULT_OUTPUT_RATE;
    }

    public double getOutputSampleRate() {
        return outputRate;
    }

    /** Re-targets the resampler; register state and queued writes are untouched. */
    public void setOutputSampleRate(double rate) {
        if (rate <= 0.0) {
            return;
        }
        outputRate = rate;
        resampler.reset(INTERNAL_RATE, rate);
        directFrameHead = 0;
        directFrameCount = 0;
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    /**
     * 0 selects the discrete YM2612 output stage ({@code ym3438_mode_ym2612 |
     * ym3438_mode_readmode}), the production default; 1 and 2 select the
     * YM3438 output stage ({@code ym3438_mode_readmode} only). Read mode is
     * always on because {@link #readStatus()} reads through a single port.
     */
    public void setChipType(int type) {
        applyChipType(type);
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    private void applyChipType(int type) {
        chipType = type;
        core.setChipType(type == 0
                ? NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE
                : NukedOpn2.MODE_READMODE);
    }

    public void setDacInterpolate(boolean interpolate) {
        dacInterpolate = interpolate;
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    public void setDacData(DacData data) {
        dacData = data;
    }

    @Override
    public DacData liveDacDataReference() {
        return dacData;
    }

    @Override
    public void setWriteObserver(ChipWriteObserver observer) {
        writeObserver = observer == null ? ChipWriteObserver.NONE : observer;
    }

    @Override
    public void reportPhysicalTimelineBoundary(
            ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        emitPhysicalBoundary(boundary);
    }

    /** Hardware reset ({@code OPN2_Reset}); chip type, output rate and mutes are retained. */
    public void reset() {
        core.reset();
        pendingCount = 0;
        queuedAddress = 0;
        busHold = 0;
        frameSumLeft = 0;
        frameSumRight = 0;
        directFrameHead = 0;
        directFrameCount = 0;
        dacSampleId = NO_DAC_VALUE;
        dacPeriod = 0;
        dacIndex = 0;
        dacAccumulator = 0;
        dacPreviousValue = NO_DAC_VALUE;
        dacPendingValue = NO_DAC_VALUE;
        dacWritePhase = 0;
        dacWriteValue = 0;
        dacWriteOrigin = ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS;
        dacSampleEndPending = 0;
        resampler.reset(INTERNAL_RATE, outputRate);
        emitPhysicalBoundary(ChipWriteObserver.PhysicalTimelineBoundary.RESET);
    }

    // ---------------------------------------------------------------- writes

    /**
     * Combined address and data write. The effective port is {@code port} or
     * bit 8 of {@code reg}; register and value are masked to eight bits
     * before anything else sees them.
     */
    public void write(int port, int reg, int val) {
        int resolvedPort = (port | (reg >> 8)) & 1;
        reg &= 0xff;
        val &= 0xff;
        queuedAddress = (resolvedPort << 8) | reg;
        enqueue(OP_WRITE, resolvedPort, reg, val, targetChannel(resolvedPort, reg, val));
        writeObserver.onYm2612Write(resolvedPort, reg, val);
    }

    /** Address strobe only ({@code OPN2_Write(chip, port * 2, reg)} once applied). */
    public void writeAddress(int port, int reg) {
        int resolvedPort = (port | (reg >> 8)) & 1;
        reg &= 0xff;
        queuedAddress = (resolvedPort << 8) | reg;
        enqueue(OP_ADDRESS, resolvedPort, reg, 0, NO_CHANNEL);
    }

    /** Data strobe only ({@code OPN2_Write(chip, port * 2 + 1, val)} once applied). */
    public void writeData(int port, int val) {
        val &= 0xff;
        int latchedPort = queuedAddress >> 8;
        int latchedReg = queuedAddress & 0xff;
        enqueue(OP_DATA, port & 1, val, 0, targetChannel(latchedPort, latchedReg, val));
    }

    /** Status byte: timer A overflow bit 0, timer B bit 1, busy bit 7 ({@code OPN2_Read}). */
    public int readStatus() {
        flushPendingOps();
        return core.read(0);
    }

    /**
     * Loads an SMPS voice: key-off, {@code 0xB0}, then per slot in slot order
     * DT/MUL, TL (25-byte voices only), RS/AR, AM/D1R, D2R, D1L/RR and
     * SSG-EG off. Bytes beyond the voice's length leave their registers alone.
     */
    public void setInstrument(int ch, byte[] voice) {
        if (voice == null || voice.length == 0 || ch < 0 || ch >= 6) {
            return;
        }
        int port = ch / 3;
        int hardwareChannel = ch % 3;
        write(0, 0x28, hardwareChannel + (port == 0 ? 0 : 4));
        write(port, 0xB0 + hardwareChannel, voice[0]);
        boolean hasTl = voice.length >= VOICE_LENGTH_WITH_TL;
        for (int slot = 0; slot < 4; slot++) {
            int register = slot * 4 + hardwareChannel;
            writeVoiceByte(port, 0x30 + register, voice, VOICE_DT_MUL[slot]);
            if (hasTl) {
                writeVoiceByte(port, 0x40 + register, voice, VOICE_TL[slot]);
            }
            writeVoiceByte(port, 0x50 + register, voice, VOICE_RS_AR[slot]);
            writeVoiceByte(port, 0x60 + register, voice, VOICE_AM_D1R[slot]);
            writeVoiceByte(port, 0x70 + register, voice, VOICE_D2R[slot]);
            writeVoiceByte(port, 0x80 + register, voice, VOICE_D1L_RR[slot]);
            write(port, 0x90 + register, 0);
        }
    }

    private void writeVoiceByte(int port, int register, byte[] voice, int index) {
        if (index < voice.length) {
            write(port, register, voice[index]);
        }
    }

    /**
     * Register-level silence (ROM {@code zFMSilenceAll}): key-off for all six
     * channels, then every slot register {@code 0x30..0x8F} on both ports set
     * to {@code 0xFF}. Not a reset.
     */
    public void silenceAll() {
        for (int key : new int[] {0x00, 0x04, 0x01, 0x05, 0x02, 0x06}) {
            write(0, 0x28, key);
        }
        for (int register = 0x30; register < 0x90; register++) {
            write(0, register, 0xFF);
            write(1, register, 0xFF);
        }
    }

    /**
     * Engine policy, not hardware: when an SFX steals a channel the envelope
     * is forced straight to the released, fully attenuated state so the next
     * key-on starts clean instead of chirping from the music note's level.
     * Applied in order with the queued writes.
     */
    public void forceSilenceChannel(int ch) {
        if (ch < 0 || ch >= 6) {
            return;
        }
        enqueue(OP_FORCE_SILENCE, ch, 0, 0, ch);
        emitPhysicalBoundary(ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    /** Output-stage mute: the channel keeps running and contributes its silent resting level. */
    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 6) {
            mutes[ch] = mute;
            emitPhysicalBoundary(ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
        }
    }

    // ------------------------------------------------------------------ DAC

    /** Starts streaming the sample mapped to {@code note} through the live {@link DacData}. */
    public void playDac(int note) {
        if (dacData == null) {
            return;
        }
        DacData.DacEntry entry = dacData.mappingForNote(note);
        if (entry == null || !dacData.hasSample(entry.sampleId())) {
            return;
        }
        enqueue(OP_DAC_PLAY, entry.sampleId(), dacPeriod(dacData.baseCycles(), entry.rate()), 0, 5);
    }

    public void stopDac() {
        enqueue(OP_DAC_STOP, 0, 0, 0, 5);
    }

    /**
     * Consumes one pending sample-end edge, or reports that none is pending.
     * The owning session turns an edge into the ROM's {@code 2Bh = 0}
     * (Sound/Z80 Sound Driver.asm:4256-4260) through its physical policy; the
     * chip states the fact and writes no enable byte of its own.
     */
    public boolean consumeDacSampleEnded() {
        if (dacSampleEndPending == 0) {
            return false;
        }
        dacSampleEndPending--;
        return true;
    }

    /**
     * Z80 cycles per PCM sample, expressed in {@link #DAC_TICK_UNITS}ths of an
     * internal FM cycle: {@code (baseCycles + 2 * 13 * (loops - 1)) / 2} Z80
     * cycles per sample times {@code 5 / 14} FM cycles per Z80 cycle.
     */
    static int dacPeriod(int baseCycles, int rate) {
        int loops = (rate & 0xff) == 0 ? Z80_DJNZ_ZERO_COUNT : rate & 0xff;
        int z80CyclesPerByte = baseCycles + DAC_SAMPLES_PER_BYTE * Z80_DJNZ_TAKEN_CYCLES * (loops - 1);
        return z80CyclesPerByte * FM_CYCLES_PER_Z80_CYCLE_NUMERATOR;
    }

    // --------------------------------------------------------------- render

    public void renderStereo(int[] left, int[] right) {
        renderStereo(left, right, Math.min(left.length, right.length));
    }

    /** Accumulates {@code frames} output-rate stereo samples into the arrays. */
    public void renderStereo(int[] left, int[] right, int frames) {
        frames = Math.min(frames, Math.min(left.length, right.length));
        if (frames <= 0) {
            return;
        }
        flushPendingOps();
        if (isDirectOutput()) {
            for (int i = 0; i < frames; i++) {
                while (directFrameCount == 0) {
                    clockOnce();
                }
                int position = directFrameHead * 2;
                left[i] += directFrames[position];
                right[i] += directFrames[position + 1];
                directFrameHead = (directFrameHead + 1) % (directFrames.length / 2);
                directFrameCount--;
            }
            return;
        }
        for (int i = 0; i < frames; i++) {
            while (!resampler.hasOutputSample()) {
                clockOnce();
            }
            long packed = resampler.getOutputStereoPacked();
            left[i] += (int) (packed >> 32);
            right[i] += (int) packed;
            resampler.advanceOutput();
        }
    }

    private boolean isDirectOutput() {
        return outputRate == INTERNAL_RATE;
    }

    /**
     * One internal cycle: services the DAC stream and its bus strobes, clocks
     * the core, applies the output-stage mutes to the cycle's pin values and
     * closes the frame on the 24th cycle.
     */
    private void clockOnce() {
        int cycle = state.cycles;
        serviceDac(cycle);
        core.clock(pinBuffer);
        int leftPin = pinBuffer[0];
        int rightPin = pinBuffer[1];
        if (mutes[PIN_WINDOW_CHANNEL[cycle >> 2]]) {
            int silent = (core.chipType() & NukedOpn2.MODE_YM2612) != 0 ? YM2612_SILENT_PIN_VALUE : 0;
            leftPin = silent;
            rightPin = silent;
        }
        frameSumLeft += leftPin;
        frameSumRight += rightPin;
        if (busHold > 0) {
            busHold--;
        }
        if (state.cycles == 0) {
            emitFrame(frameSumLeft << OUTPUT_SHIFT, frameSumRight << OUTPUT_SHIFT);
            frameSumLeft = 0;
            frameSumRight = 0;
        }
        physicalCycle++;
    }

    private void emitFrame(int leftSample, int rightSample) {
        if (!isDirectOutput()) {
            resampler.addInputSample(leftSample, rightSample);
            return;
        }
        int capacity = directFrames.length / 2;
        if (directFrameCount == capacity) {
            int[] grown = new int[directFrames.length * 2];
            for (int i = 0; i < directFrameCount; i++) {
                int from = ((directFrameHead + i) % capacity) * 2;
                grown[i * 2] = directFrames[from];
                grown[i * 2 + 1] = directFrames[from + 1];
            }
            directFrames = grown;
            directFrameHead = 0;
            capacity = directFrames.length / 2;
        }
        int position = ((directFrameHead + directFrameCount) % capacity) * 2;
        directFrames[position] = leftSample;
        directFrames[position + 1] = rightSample;
        directFrameCount++;
    }

    /**
     * Advances the DAC stream by one cycle and presents its {@code 0x2A}
     * strobes when the bus is free. While a value is waiting for the bus the
     * cadence pauses, as the Z80 loop pauses while it cannot reach the chip;
     * samples are never dropped.
     *
     * <p>Only real Z80 sample writes may occupy {@link #dacPendingValue}, the
     * slot that gates the cadence. The optional interpolated write is
     * synthetic and is never deferred: it is started only when the bus is
     * idle, no real sample is waiting, and the cadence cannot reach its next
     * sample before the synthetic write has fully settled; otherwise this
     * frame's interpolation is dropped. An earlier implementation queued the
     * synthetic value in the gating slot, which paused the Z80 clock for the
     * bus settle time on every frame and stretched samples ~1.7x (about 9.5
     * semitones flat, measured 2026-09-02).
     */
    private void serviceDac(int cycle) {
        if (dacSampleId != NO_DAC_VALUE && dacPendingValue == NO_DAC_VALUE) {
            dacAccumulator += DAC_TICK_UNITS;
            if (dacAccumulator >= dacPeriod) {
                dacAccumulator -= dacPeriod;
                int value = dacSampleAt(dacIndex);
                if (value == NO_DAC_VALUE) {
                    dacSampleId = NO_DAC_VALUE;
                    dacSampleEndPending++;
                } else {
                    dacPendingValue = value;
                    dacPreviousValue = value;
                    dacIndex++;
                }
            }
        }
        if (busHold > 0) {
            return;
        }
        if (dacWritePhase == 1) {
            core.write(1, dacWriteValue);
            emitPhysicalYmWrite(1, dacWriteValue, dacWriteOrigin);
            dacWritePhase = 0;
            busHold = DATA_SETTLE_CYCLES;
        } else if (dacPendingValue != NO_DAC_VALUE) {
            core.write(0, DAC_REGISTER);
            dacWriteOrigin = ChipWriteObserver.PhysicalWriteOrigin.DAC_STREAM;
            emitPhysicalYmWrite(0, DAC_REGISTER, dacWriteOrigin);
            dacWriteValue = dacPendingValue;
            dacPendingValue = NO_DAC_VALUE;
            dacWritePhase = 1;
            busHold = ADDRESS_SETTLE_CYCLES;
            // The ROM's playback loop sends this byte over the same
            // address/data pair the sequencer uses (:4305-4306, :4318-4319),
            // so it is an observable write like any other. The interpolated
            // branch below is not: it has no ROM counterpart.
            writeObserver.onYm2612Write(0, DAC_REGISTER, dacWriteValue);
        } else if (dacInterpolate && cycle == 0 && dacSampleId != NO_DAC_VALUE
                && dacPreviousValue != NO_DAC_VALUE
                && dacAccumulator + SYNTHETIC_WRITE_UNITS < dacPeriod) {
            int next = dacSampleAt(dacIndex);
            if (next != NO_DAC_VALUE) {
                int interpolated = dacPreviousValue
                        + (int) (((long) (next - dacPreviousValue) * dacAccumulator) / dacPeriod);
                if (interpolated != dacWriteValue) {
                    core.write(0, DAC_REGISTER);
                    dacWriteOrigin = ChipWriteObserver.PhysicalWriteOrigin.DAC_INTERPOLATION;
                    emitPhysicalYmWrite(0, DAC_REGISTER, dacWriteOrigin);
                    dacWriteValue = interpolated;
                    dacWritePhase = 1;
                    busHold = ADDRESS_SETTLE_CYCLES;
                }
            }
        }
    }

    /** Unsigned PCM byte {@code index} of the current sample, or {@link #NO_DAC_VALUE} past its end. */
    private int dacSampleAt(int index) {
        if (dacData == null) {
            return NO_DAC_VALUE;
        }
        // The bank's lookup boxes the id; resolve it once per (bank, id) pair
        // instead of once per streamed byte. The cache is derived state, not
        // snapshot state: any restore that changes either key re-resolves.
        if (dacSample == null || dacSampleData != dacData || dacSampleDataId != dacSampleId) {
            dacSample = dacData.sample(dacSampleId);
            dacSampleData = dacData;
            dacSampleDataId = dacSampleId;
        }
        DacData.Sample sample = dacSample;
        if (sample == null || index >= sample.length()) {
            return NO_DAC_VALUE;
        }
        return sample.byteAt(index) & 0xff;
    }

    // ------------------------------------------------------ pending ops

    private void enqueue(int kind, int a, int b, int c, int channel) {
        int base = pendingCount * OP_STRIDE;
        if (base + OP_STRIDE > pendingOps.length) {
            pendingOps = Arrays.copyOf(pendingOps, pendingOps.length * 2);
        }
        pendingOps[base] = kind;
        pendingOps[base + 1] = a;
        pendingOps[base + 2] = b;
        pendingOps[base + 3] = c;
        pendingOps[base + 4] = channel;
        pendingCount++;
    }

    /**
     * The channel a write touches, for channel-bounded rollback: key on/off
     * by its channel bits ({@code ym3438.c:238}, {@code 0x28}), DAC registers
     * by FM6, slot and channel registers by {@code reg & 3} plus three for
     * port 1 ({@code OP_OFFSET} / {@code CH_OFFSET}); global registers and
     * invalid channel codes are {@link #NO_CHANNEL}.
     */
    private static int targetChannel(int port, int reg, int val) {
        if (reg == 0x28) {
            return (val & 0x03) == 0x03 ? NO_CHANNEL : (val & 0x03) + ((val >> 2) & 1) * 3;
        }
        if (reg == 0x2A || reg == 0x2B) {
            return 5;
        }
        if (reg >= 0x30 && reg < 0xB8) {
            return (reg & 0x03) == 0x03 ? NO_CHANNEL : (reg & 0x03) + port * 3;
        }
        return NO_CHANNEL;
    }

    private void flushPendingOps() {
        if (pendingCount == 0) {
            return;
        }
        for (int i = 0; i < pendingCount; i++) {
            int base = i * OP_STRIDE;
            switch (pendingOps[base]) {
                case OP_WRITE -> {
                    applyAddress(pendingOps[base + 1], pendingOps[base + 2]);
                    applyData(pendingOps[base + 1], pendingOps[base + 3]);
                }
                case OP_ADDRESS -> applyAddress(pendingOps[base + 1], pendingOps[base + 2]);
                case OP_DATA -> applyData(pendingOps[base + 1], pendingOps[base + 2]);
                case OP_FORCE_SILENCE -> silenceChannelNow(pendingOps[base + 1]);
                case OP_DAC_PLAY -> {
                    dacSampleId = pendingOps[base + 1];
                    dacPeriod = pendingOps[base + 2];
                    dacIndex = 0;
                    dacAccumulator = dacPeriod;
                    dacPreviousValue = NO_DAC_VALUE;
                    dacPendingValue = NO_DAC_VALUE;
                }
                case OP_DAC_STOP -> {
                    dacSampleId = NO_DAC_VALUE;
                    dacPendingValue = NO_DAC_VALUE;
                }
                default -> throw new IllegalStateException("unknown pending op " + pendingOps[base]);
            }
        }
        flushedOps += pendingCount;
        pendingCount = 0;
        waitBusIdle();
    }

    private void waitBusIdle() {
        while (busHold > 0 || dacWritePhase != 0) {
            clockOnce();
        }
    }

    private void applyAddress(int port, int reg) {
        waitBusIdle();
        core.write(port * 2, reg);
        emitPhysicalYmWrite(port * 2, reg,
                ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS);
        busHold = ADDRESS_SETTLE_CYCLES;
    }

    private void applyData(int port, int val) {
        waitBusIdle();
        core.write(port * 2 + 1, val);
        emitPhysicalYmWrite(port * 2 + 1, val,
                ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS);
        busHold = DATA_SETTLE_CYCLES;
    }

    private void emitPhysicalYmWrite(int busPort, int value,
            ChipWriteObserver.PhysicalWriteOrigin origin) {
        if (writeObserver.observesPhysicalWrites()) {
            writeObserver.onYm2612BusWrite(physicalCycle, busPort, value,
                    origin);
        }
    }

    private void emitPhysicalBoundary(
            ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        if (writeObserver.observesPhysicalWrites()) {
            writeObserver.onPhysicalTimelineBoundary(
                    ChipWriteObserver.ChipClockDomain.YM2612_INTERNAL_CYCLE,
                    physicalCycle, boundary);
        }
    }

    /**
     * Puts the channel's four operators ({@code keyOn}, {@code ym3438.c:1160}:
     * slots {@code ch}, {@code ch + 6}, {@code ch + 12}, {@code ch + 18}) into
     * the released, fully attenuated state of {@code OPN2_Reset} and clears
     * their key state, including a {@code 0x28} latch still aimed at the
     * channel so the sequencer's next pass cannot re-key it.
     */
    private void silenceChannelNow(int ch) {
        waitBusIdle();
        for (int operator = 0; operator < 4; operator++) {
            int slot = ch + operator * 6;
            state.egLevel[slot] = EG_LEVEL_SILENT;
            state.egOut[slot] = EG_LEVEL_SILENT;
            state.egState[slot] = EG_STATE_RELEASE;
            state.egKon[slot] = 0;
            state.egKonLatch[slot] = 0;
            state.egKonCsm[slot] = 0;
            state.egSsgDir[slot] = 0;
            state.egSsgInv[slot] = 0;
            state.egSsgPgrstLatch[slot] = 0;
            state.egSsgRepeatLatch[slot] = 0;
            state.egSsgHoldUpLatch[slot] = 0;
            state.modeKon[slot] = 0;
        }
        if (state.modeKonChannel == ch) {
            Arrays.fill(state.modeKonOperator, 0);
        }
    }

    // ------------------------------------------------------------ snapshot

    /** Mutable owner-private transaction storage; never used by durable snapshots. */
    static final class MutationBackup implements FmChip.MutationBackup {
        private Ym2612Chip owner;
        private int chipType;
        private int frameSumLeft;
        private int frameSumRight;
        private int directFrameCount;
        private int busHold;
        private int pendingCount;
        private int queuedAddress;
        private int dacSampleId;
        private int dacPeriod;
        private int dacIndex;
        private int dacAccumulator;
        private int dacPreviousValue;
        private int dacPendingValue;
        private int dacWritePhase;
        private int dacWriteValue;
        private int dacSampleEndPending;
        private double outputRate;
        private boolean dacInterpolate;
        private boolean[] mutes = new boolean[6];
        private final NukedOpn2State core = new NukedOpn2State();
        private final BlipResampler.MutationBackup resampler = new BlipResampler.MutationBackup();
        private int[] direct = new int[0], ops = new int[0];
    }

    @Override
    public FmChip.MutationBackup createMutationBackup() {
        MutationBackup backup = new MutationBackup();
        backup.owner = this;
        return backup;
    }

    private MutationBackup own(FmChip.MutationBackup candidate) {
        if (!(candidate instanceof MutationBackup backup) || (backup.owner != null && backup.owner != this)) {
            throw new IllegalArgumentException("foreign FM mutation backup");
        }
        return backup;
    }

    @Override
    public void captureMutation(FmChip.MutationBackup candidate) {
        MutationBackup backup = own(candidate);
        backup.chipType = chipType;
        backup.frameSumLeft = frameSumLeft;
        backup.frameSumRight = frameSumRight;
        backup.directFrameCount = directFrameCount;
        backup.busHold = busHold;
        backup.pendingCount = pendingCount;
        backup.queuedAddress = queuedAddress;
        backup.dacSampleId = dacSampleId;
        backup.dacPeriod = dacPeriod;
        backup.dacIndex = dacIndex;
        backup.dacAccumulator = dacAccumulator;
        backup.dacPreviousValue = dacPreviousValue;
        backup.dacPendingValue = dacPendingValue;
        backup.dacWritePhase = dacWritePhase;
        backup.dacWriteValue = dacWriteValue;
        backup.dacSampleEndPending = dacSampleEndPending;
        backup.outputRate = outputRate;
        backup.dacInterpolate = dacInterpolate;
        if (backup.mutes.length < mutes.length) backup.mutes = new boolean[mutes.length];
        System.arraycopy(mutes, 0, backup.mutes, 0, mutes.length);
        backup.core.copyFrom(state);
        int directSize = directFrameCount * 2;
        if (backup.direct.length < directSize) backup.direct = new int[directSize];
        for (int i = 0; i < directFrameCount; i++) {
            int from = ((directFrameHead + i) % (directFrames.length / 2)) * 2;
            backup.direct[i * 2] = directFrames[from];
            backup.direct[i * 2 + 1] = directFrames[from + 1];
        }
        int opSize = pendingCount * OP_STRIDE;
        if (backup.ops.length < opSize) backup.ops = new int[opSize];
        System.arraycopy(pendingOps, 0, backup.ops, 0, opSize);
        resampler.captureMutation(backup.resampler);
    }

    @Override
    public void restoreMutation(FmChip.MutationBackup candidate) {
        MutationBackup backup = own(candidate);
        chipType = backup.chipType;
        frameSumLeft = backup.frameSumLeft;
        frameSumRight = backup.frameSumRight;
        directFrameCount = backup.directFrameCount;
        busHold = backup.busHold;
        pendingCount = backup.pendingCount;
        queuedAddress = backup.queuedAddress;
        dacSampleId = backup.dacSampleId;
        dacPeriod = backup.dacPeriod;
        dacIndex = backup.dacIndex;
        dacAccumulator = backup.dacAccumulator;
        dacPreviousValue = backup.dacPreviousValue;
        dacPendingValue = backup.dacPendingValue;
        dacWritePhase = backup.dacWritePhase;
        dacWriteValue = backup.dacWriteValue;
        dacSampleEndPending = backup.dacSampleEndPending;
        outputRate = backup.outputRate;
        dacInterpolate = backup.dacInterpolate;
        System.arraycopy(backup.mutes, 0, mutes, 0, mutes.length);
        applyChipType(chipType);
        state.copyFrom(backup.core);
        int directSize = directFrameCount * 2;
        if (directFrames.length < directSize) directFrames = new int[directSize];
        System.arraycopy(backup.direct, 0, directFrames, 0, directSize);
        directFrameHead = 0;
        int opSize = pendingCount * OP_STRIDE;
        if (pendingOps.length < opSize) pendingOps = new int[opSize];
        System.arraycopy(backup.ops, 0, pendingOps, 0, opSize);
        dacWriteOrigin = dacWritePhase == 0
                ? ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS
                : ChipWriteObserver.PhysicalWriteOrigin.RESTORED_UNKNOWN;
        resampler.restoreMutation(backup.resampler);
        emitPhysicalBoundary(ChipWriteObserver.PhysicalTimelineBoundary.SNAPSHOT_RESTORE);
    }

    /** Pure: captures the complete chip, queue, DAC, output-stage and resampler state. */
    public Snapshot captureSnapshot() {
        int[] direct = new int[directFrameCount * 2];
        int capacity = directFrames.length / 2;
        for (int i = 0; i < directFrameCount; i++) {
            int from = ((directFrameHead + i) % capacity) * 2;
            direct[i * 2] = directFrames[from];
            direct[i * 2 + 1] = directFrames[from + 1];
        }
        return new Snapshot(
                chipType,
                outputRate,
                state,
                frameSumLeft,
                frameSumRight,
                direct,
                busHold,
                Arrays.copyOf(pendingOps, pendingCount * OP_STRIDE),
                queuedAddress,
                dacSampleId,
                dacPeriod,
                dacIndex,
                dacAccumulator,
                dacPeriod == 0 ? 0.0 : dacIndex + (double) dacAccumulator / dacPeriod,
                dacPreviousValue,
                dacPendingValue,
                dacWritePhase,
                dacWriteValue,
                dacSampleEndPending,
                dacInterpolate,
                mutes,
                resampler.captureSnapshot());
    }

    /** Total and authoritative: the chip continues bit-exactly from the snapshot. */
    @Override
    public void restoreSnapshot(FmChip.Snapshot candidate) {
        if (!(candidate instanceof Snapshot snapshot)) {
            throw new IllegalArgumentException(
                    "snapshot was captured by a different FM core: " + candidate.getClass().getSimpleName());
        }
        applyChipType(snapshot.chipType());
        outputRate = snapshot.outputRate();
        state.copyFrom(snapshot.coreRef());
        frameSumLeft = snapshot.frameSumLeft();
        frameSumRight = snapshot.frameSumRight();
        int[] direct = snapshot.directFramesRef();
        if (directFrames.length < direct.length) {
            directFrames = new int[Math.max(direct.length, directFrames.length * 2)];
        }
        System.arraycopy(direct, 0, directFrames, 0, direct.length);
        directFrameHead = 0;
        directFrameCount = direct.length / 2;
        busHold = snapshot.busHold();
        int[] ops = snapshot.pendingOpsRef();
        if (pendingOps.length < ops.length) {
            pendingOps = new int[Math.max(ops.length, pendingOps.length * 2)];
        }
        System.arraycopy(ops, 0, pendingOps, 0, ops.length);
        pendingCount = ops.length / OP_STRIDE;
        queuedAddress = snapshot.queuedAddress();
        dacSampleId = snapshot.currentDacSampleId();
        dacPeriod = snapshot.dacPeriod();
        dacIndex = snapshot.dacIndex();
        dacAccumulator = snapshot.dacAccumulator();
        dacPreviousValue = snapshot.dacPreviousValue();
        dacPendingValue = snapshot.dacPendingValue();
        dacWritePhase = snapshot.dacWritePhase();
        dacWriteValue = snapshot.dacWriteValue();
        dacWriteOrigin = dacWritePhase == 0
                ? ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS
                : ChipWriteObserver.PhysicalWriteOrigin.RESTORED_UNKNOWN;
        dacSampleEndPending = snapshot.dacSampleEndPending();
        dacInterpolate = snapshot.dacInterpolate();
        System.arraycopy(snapshot.mutesRef(), 0, mutes, 0, mutes.length);
        resampler.restoreSnapshot(snapshot.resampler());
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.SNAPSHOT_RESTORE);
    }

    /**
     * Channel-bounded rollback token for one SFX admission. Because writes are
     * applied only when the chip next renders, everything an admission does
     * to its channels before it is accepted or rejected is still in the
     * pending queue; the token records where the queue stood, and restoring
     * removes the entries queued since then that target the masked channels.
     */
    @Override
    public FmChip.SfxAdmissionState captureSfxAdmissionState(int affectedChannelMask) {
        return new SfxAdmissionState(affectedChannelMask & 0x3f, flushedOps + pendingCount, queuedAddress);
    }

    @Override
    public void restoreSfxAdmissionState(FmChip.SfxAdmissionState candidate) {
        if (!(candidate instanceof SfxAdmissionState admission)) {
            throw new IllegalArgumentException("foreign FM admission state");
        }
        long firstNewOp = admission.opsEnqueuedAtCapture() - flushedOps;
        int start = (int) Math.max(0, Math.min(pendingCount, firstNewOp));
        int kept = start;
        for (int i = start; i < pendingCount; i++) {
            int channel = pendingOps[i * OP_STRIDE + 4];
            boolean affected = channel != NO_CHANNEL
                    && (admission.affectedChannelMask() & (1 << channel)) != 0;
            if (affected) {
                continue;
            }
            if (kept != i) {
                System.arraycopy(pendingOps, i * OP_STRIDE, pendingOps, kept * OP_STRIDE, OP_STRIDE);
            }
            kept++;
        }
        pendingCount = kept;
        queuedAddress = admission.queuedAddress();
        for (int i = start; i < pendingCount; i++) {
            int kind = pendingOps[i * OP_STRIDE];
            if (kind == OP_WRITE || kind == OP_ADDRESS) {
                queuedAddress = (pendingOps[i * OP_STRIDE + 1] << 8) | pendingOps[i * OP_STRIDE + 2];
            }
        }
        emitPhysicalBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    /**
     * Complete chip state for rewind. {@code core} is the Nuked
     * {@code ym3438_t} state; the other components are the adapter's own.
     * Arrays and the core state are copied on construction and on access.
     */
    public record Snapshot(
            int chipType,
            double outputRate,
            NukedOpn2State core,
            int frameSumLeft,
            int frameSumRight,
            int[] directFrames,
            int busHold,
            int[] pendingOps,
            int queuedAddress,
            int currentDacSampleId,
            int dacPeriod,
            int dacIndex,
            int dacAccumulator,
            double dacPos,
            int dacPreviousValue,
            int dacPendingValue,
            int dacWritePhase,
            int dacWriteValue,
            int dacSampleEndPending,
            boolean dacInterpolate,
            boolean[] mutes,
            BlipResampler.Snapshot resampler) implements FmChip.Snapshot {
        public Snapshot {
            core = core.copy();
            directFrames = Arrays.copyOf(directFrames, directFrames.length);
            pendingOps = Arrays.copyOf(pendingOps, pendingOps.length);
            mutes = Arrays.copyOf(mutes, mutes.length);
        }

        @Override
        public NukedOpn2State core() { return core.copy(); }

        @Override
        public int[] directFrames() { return Arrays.copyOf(directFrames, directFrames.length); }

        @Override
        public int[] pendingOps() { return Arrays.copyOf(pendingOps, pendingOps.length); }

        /** Whether deferred work is reconstructable from the future raw bus strobes alone. */
        public boolean hasOnlyPendingBusWrites() {
            for (int index = 0; index < pendingOps.length; index += OP_STRIDE) {
                int kind = pendingOps[index];
                if (kind != OP_WRITE && kind != OP_ADDRESS && kind != OP_DATA) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean[] mutes() { return Arrays.copyOf(mutes, mutes.length); }

        /** Non-copying view for in-memory restore paths only. Do not mutate. */
        NukedOpn2State coreRef() { return core; }

        /** Non-copying view for in-memory restore paths only. Do not mutate. */
        int[] directFramesRef() { return directFrames; }

        /** Non-copying view for in-memory restore paths only. Do not mutate. */
        int[] pendingOpsRef() { return pendingOps; }

        /** Non-copying view for in-memory restore paths only. Do not mutate. */
        boolean[] mutesRef() { return mutes; }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof Snapshot other
                    && chipType == other.chipType
                    && Double.compare(outputRate, other.outputRate) == 0
                    && Objects.equals(core, other.core)
                    && frameSumLeft == other.frameSumLeft
                    && frameSumRight == other.frameSumRight
                    && Arrays.equals(directFrames, other.directFrames)
                    && busHold == other.busHold
                    && Arrays.equals(pendingOps, other.pendingOps)
                    && queuedAddress == other.queuedAddress
                    && currentDacSampleId == other.currentDacSampleId
                    && dacPeriod == other.dacPeriod
                    && dacIndex == other.dacIndex
                    && dacAccumulator == other.dacAccumulator
                    && Double.compare(dacPos, other.dacPos) == 0
                    && dacPreviousValue == other.dacPreviousValue
                    && dacPendingValue == other.dacPendingValue
                    && dacWritePhase == other.dacWritePhase
                    && dacWriteValue == other.dacWriteValue
                    && dacSampleEndPending == other.dacSampleEndPending
                    && dacInterpolate == other.dacInterpolate
                    && Arrays.equals(mutes, other.mutes)
                    && Objects.equals(resampler, other.resampler);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(chipType, outputRate, core,
                    frameSumLeft, frameSumRight, busHold, queuedAddress,
                    currentDacSampleId, dacPeriod, dacIndex, dacAccumulator,
                    dacPos, dacPreviousValue, dacPendingValue, dacWritePhase,
                    dacWriteValue, dacSampleEndPending, dacInterpolate,
                    resampler);
            result = 31 * result + Arrays.hashCode(directFrames);
            result = 31 * result + Arrays.hashCode(pendingOps);
            return 31 * result + Arrays.hashCode(mutes);
        }
    }

    /** See {@link #captureSfxAdmissionState(int)}. */
    record SfxAdmissionState(int affectedChannelMask, long opsEnqueuedAtCapture, int queuedAddress)
            implements FmChip.SfxAdmissionState { }
}
