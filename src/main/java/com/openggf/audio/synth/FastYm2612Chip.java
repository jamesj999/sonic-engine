package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.synth.fast.FmDsp;

import java.util.Arrays;
import java.util.Objects;

/**
 * Register-level FM facade over a clean-room {@link FmDsp}.
 *
 * <p>Presents the same {@link FmChip} contract as the cycle-exact
 * {@link Ym2612Chip}: writes are queued and applied at the start of the next
 * render (so SFX admission can withdraw them), DAC samples are streamed as
 * {@code 0x2A} writes at the ROM's byte cadence, channels are panned from the
 * {@code 0xB4..0xB6} registers and muted per channel, and output is scaled to
 * the facade scale the mixer expects (one full-scale channel is 6144, the
 * accurate facade's 24-cycle pin sum shifted by {@code OUTPUT_SHIFT}; the DSP's
 * native range is {@code -8192..8191}, so samples are scaled by {@code 3/4}).
 *
 * <p>What this facade does not model, by design: bus settle cycles, write
 * placement within a frame, the busy flag, and the DAC interpolation branch
 * (the flag is retained in snapshots so state comparisons stay meaningful).
 * Everything applied here lands at frame granularity.
 */
public final class FastYm2612Chip implements FmChip {
    private static final double MASTER_CLOCK_HZ = 7670453.0;
    private static final double INTERNAL_RATE = MASTER_CLOCK_HZ / 144.0;
    private static final int NO_DAC_VALUE = -1;
    private static final int DAC_REGISTER = 0x2A;

    private static final int OP_STRIDE = 5;
    private static final int OP_WRITE = 0;
    private static final int OP_ADDRESS = 1;
    private static final int OP_DATA = 2;
    private static final int OP_FORCE_SILENCE = 3;
    private static final int OP_DAC_PLAY = 4;
    private static final int OP_DAC_STOP = 5;
    private static final int NO_CHANNEL = -1;
    /** Both outputs enabled: the chip resets its L/R bits set. */
    private static final int PAN_BOTH = 3;
    /**
     * Bus pacing, matching the accurate facade: an address write settles in 1
     * internal cycle, a data write in 34, so one register write lands every 35
     * cycles (about 1.5 frames). Without this the fast core plays a burst of
     * writes 40-50 frames earlier than the accurate core does.
     */
    private static final int ADDRESS_SETTLE_CYCLES = 1;
    private static final int DATA_SETTLE_CYCLES = 2 + 32;
    private static final int CYCLES_PER_FRAME = 24;

    private static final int[] VOICE_DT_MUL = {1, 3, 2, 4};
    private static final int[] VOICE_TL = {21, 23, 22, 24};
    private static final int[] VOICE_RS_AR = {5, 7, 6, 8};
    private static final int[] VOICE_AM_D1R = {9, 11, 10, 12};
    private static final int[] VOICE_D2R = {13, 15, 14, 16};
    private static final int[] VOICE_D1L_RR = {17, 19, 18, 20};
    private static final int VOICE_LENGTH_WITH_TL = 25;

    private final FmDsp dsp;
    private final int[] channelOut = new int[FmDsp.CHANNELS];
    private final BlipResampler resampler = new BlipResampler(INTERNAL_RATE, Ym2612Chip.getDefaultOutputRate());
    private final boolean[] mutes = new boolean[6];
    /** Bit 0 = left enabled, bit 1 = right enabled, from register 0xB4+ch bits 7/6. */
    private final int[] pan = new int[6];
    private final int[] latchedRegister = new int[2];
    private ChipWriteObserver writeObserver = ChipWriteObserver.NONE;
    private DacData dacData;
    private int chipType;
    private double outputRate = Ym2612Chip.getDefaultOutputRate();
    private boolean dacInterpolate;

    private int[] directFrames = new int[2 * 256];
    private int directFrameHead;
    private int directFrameCount;

    private int[] pendingOps = new int[OP_STRIDE * 256];
    private int pendingCount;
    private long flushedOps;
    /** Elapsed YM internal cycles, quantized to complete 24-cycle FM frames. */
    private long internalCycles;
    private int queuedAddress;

    private int dacSampleId = NO_DAC_VALUE;
    /** Resolved {@link #dacSampleId} in {@link #dacData}; derived, never snapshotted. */
    private DacData.Sample dacSample;
    private DacData dacSampleData;
    private int dacSampleDataId = NO_DAC_VALUE;
    private int dacPeriod;
    private int dacIndex;
    private int dacAccumulator;
    private int dacSampleEndPending;
    /** Internal cycles until the bus is free again; the first write of an idle bus lands at once. */
    private int busCredit;

    public FastYm2612Chip(FmDsp dsp) {
        this.dsp = Objects.requireNonNull(dsp, "dsp");
        this.dsp.reset();
        Arrays.fill(pan, PAN_BOTH);
    }

    @Override
    public double getOutputSampleRate() {
        return outputRate;
    }

    @Override
    public void setOutputSampleRate(double rate) {
        if (rate <= 0.0 || rate == outputRate) {
            return;
        }
        outputRate = rate;
        resampler.reset(INTERNAL_RATE, rate);
        directFrameHead = 0;
        directFrameCount = 0;
    }

    @Override
    public void setChipType(int type) {
        chipType = type;
    }

    @Override
    public void setDacInterpolate(boolean interpolate) {
        dacInterpolate = interpolate;
    }

    @Override
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
    public void reportPhysicalTimelineBoundary(ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        emitBoundary(boundary);
    }

    private void emitBoundary(ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        if (writeObserver.observesPhysicalWrites()) {
            writeObserver.onPhysicalTimelineBoundary(
                    ChipWriteObserver.ChipClockDomain.YM2612_INTERNAL_CYCLE, internalCycles, boundary);
        }
    }

    @Override
    public void reset() {
        dsp.reset();
        Arrays.fill(pan, PAN_BOTH);
        Arrays.fill(latchedRegister, 0);
        pendingCount = 0;
        internalCycles = 0;
        busCredit = 0;
        queuedAddress = 0;
        directFrameHead = 0;
        directFrameCount = 0;
        dacSampleId = NO_DAC_VALUE;
        dacPeriod = 0;
        dacIndex = 0;
        dacAccumulator = 0;
        dacSampleEndPending = 0;
        resampler.reset(INTERNAL_RATE, outputRate);
        emitBoundary(ChipWriteObserver.PhysicalTimelineBoundary.RESET);
    }

    // ---------------------------------------------------------------- writes

    @Override
    public void write(int port, int reg, int val) {
        int resolvedPort = (port | (reg >> 8)) & 1;
        reg &= 0xff;
        val &= 0xff;
        queuedAddress = (resolvedPort << 8) | reg;
        enqueue(OP_WRITE, resolvedPort, reg, val, targetChannel(resolvedPort, reg, val));
        writeObserver.onYm2612Write(resolvedPort, reg, val);
    }

    @Override
    public void writeAddress(int port, int reg) {
        int resolvedPort = (port | (reg >> 8)) & 1;
        reg &= 0xff;
        queuedAddress = (resolvedPort << 8) | reg;
        enqueue(OP_ADDRESS, resolvedPort, reg, 0, NO_CHANNEL);
    }

    @Override
    public void writeData(int port, int val) {
        val &= 0xff;
        int latchedPort = queuedAddress >> 8;
        int latchedReg = queuedAddress & 0xff;
        enqueue(OP_DATA, port & 1, val, 0, targetChannel(latchedPort, latchedReg, val));
    }

    @Override
    public int readStatus() {
        flushPendingOps();
        return dsp.readStatus();
    }

    @Override
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

    @Override
    public void silenceAll() {
        for (int key : new int[] {0x00, 0x04, 0x01, 0x05, 0x02, 0x06}) {
            write(0, 0x28, key);
        }
        for (int register = 0x30; register < 0x90; register++) {
            write(0, register, 0xFF);
            write(1, register, 0xFF);
        }
    }

    @Override
    public void forceSilenceChannel(int ch) {
        if (ch < 0 || ch >= 6) {
            return;
        }
        enqueue(OP_FORCE_SILENCE, ch, 0, 0, ch);
        emitBoundary(ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }

    @Override
    public void setMute(int ch, boolean mute) {
        if (ch >= 0 && ch < 6) {
            mutes[ch] = mute;
                emitBoundary(ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
        }
    }

    // ------------------------------------------------------------------ DAC

    @Override
    public void playDac(int note) {
        if (dacData == null) {
            return;
        }
        DacData.DacEntry entry = dacData.mappingForNote(note);
        if (entry == null || !dacData.hasSample(entry.sampleId())) {
            return;
        }
        enqueue(OP_DAC_PLAY, entry.sampleId(),
                FmDacTiming.period(dacData.baseCycles(), entry.rate()), 0, 5);
    }

    @Override
    public void stopDac() {
        enqueue(OP_DAC_STOP, 0, 0, 0, 5);
    }

    @Override
    public boolean consumeDacSampleEnded() {
        if (dacSampleEndPending == 0) {
            return false;
        }
        dacSampleEndPending--;
        return true;
    }

    /**
     * Streams at most one DAC byte per internal frame. The accurate facade
     * checks the accumulator every chip cycle and can therefore land a byte
     * up to 23 cycles earlier within the same frame; at the ROM's fastest
     * drum rates (about one byte per 4 frames) that is sub-sample jitter.
     */
    private void serviceDacFrame() {
        if (dacSampleId == NO_DAC_VALUE) {
            return;
        }
        dacAccumulator += FmDacTiming.TICK_UNITS_PER_FRAME;
        if (dacAccumulator < dacPeriod) {
            return;
        }
        dacAccumulator -= dacPeriod;
        int value = dacSampleAt(dacIndex);
        if (value == NO_DAC_VALUE) {
            dacSampleId = NO_DAC_VALUE;
            dacSampleEndPending++;
            return;
        }
        dacIndex++;
        dsp.writeRegister(0, DAC_REGISTER, value);
        // Observable like any other write: the ROM sends it over the same
        // address/data pair the sequencer uses.
        writeObserver.onYm2612Write(0, DAC_REGISTER, value);
        if (writeObserver.observesPhysicalWrites()) {
            writeObserver.onYm2612BusWrite(internalCycles, 0, DAC_REGISTER,
                    ChipWriteObserver.PhysicalWriteOrigin.DAC_STREAM);
            writeObserver.onYm2612BusWrite(internalCycles, 1, value,
                    ChipWriteObserver.PhysicalWriteOrigin.DAC_STREAM);
        }
    }

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

    /** Lands every pending op regardless of pacing (status reads, snapshots of a settled bus). */
    private void flushPendingOps() {
        int debt = busCredit;
        busCredit = Integer.MIN_VALUE / 2;
        landPendingOps();
        busCredit = Math.max(debt, 0);
    }

    /** Lands pending ops in order while the bus credit allows; the rest wait for later frames. */
    private void landPendingOps() {
        if (pendingCount == 0) {
            return;
        }
        int landed = 0;
        for (int i = 0; i < pendingCount; i++) {
            int base = i * OP_STRIDE;
            int cost = switch (pendingOps[base]) {
                case OP_WRITE -> ADDRESS_SETTLE_CYCLES + DATA_SETTLE_CYCLES;
                case OP_ADDRESS -> ADDRESS_SETTLE_CYCLES;
                case OP_DATA -> DATA_SETTLE_CYCLES;
                default -> 0;
            };
            if (busCredit >= CYCLES_PER_FRAME) {
                break; // the bus stays busy past this frame
            }
            int writeCycle = busCredit;
            busCredit += cost;
            landed++;
            switch (pendingOps[base]) {
                case OP_WRITE -> applyWrite(pendingOps[base + 1], pendingOps[base + 2], pendingOps[base + 3],
                        writeCycle + ADDRESS_SETTLE_CYCLES);
                case OP_ADDRESS -> latchedRegister[pendingOps[base + 1]] = pendingOps[base + 2];
                case OP_DATA -> {
                    int port = pendingOps[base + 1];
                    applyWrite(port, latchedRegister[port], pendingOps[base + 2], writeCycle);
                }
                case OP_FORCE_SILENCE -> silenceChannelNow(pendingOps[base + 1]);
                case OP_DAC_PLAY -> {
                    dacSampleId = pendingOps[base + 1];
                    dacPeriod = pendingOps[base + 2];
                    dacIndex = 0;
                    dacAccumulator = 0;
                }
                case OP_DAC_STOP -> {
                    dacSampleId = NO_DAC_VALUE;
                    dacIndex = 0;
                    dacAccumulator = 0;
                }
                default -> throw new IllegalStateException("unknown pending op " + pendingOps[base]);
            }
        }
        if (landed > 0) {
            System.arraycopy(pendingOps, landed * OP_STRIDE, pendingOps, 0, (pendingCount - landed) * OP_STRIDE);
            pendingCount -= landed;
            flushedOps += landed;
        }
    }

    private void applyWrite(int port, int register, int value) {
        applyWrite(port, register, value, -1);
    }

    private void applyWrite(int port, int register, int value, int frameCycle) {
        latchedRegister[port] = register;
        if (register >= 0xB4 && register <= 0xB6) {
            pan[port * 3 + (register - 0xB4)] = ((value >> 7) & 1) | ((value >> 5) & 2);
            }
        // Status-read flushing has no paced frame position; keep its immediate
        // semantics. Ordinary bus writes retain their data-strobe offset.
        if (frameCycle < 0) dsp.writeRegister(port, register, value);
        else dsp.writeRegister(port, register, value, frameCycle);
        if (writeObserver.observesPhysicalWrites()) {
            writeObserver.onYm2612BusWrite(internalCycles, port * 2, register,
                    ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS);
            writeObserver.onYm2612BusWrite(internalCycles, port * 2 + 1, value,
                    ChipWriteObserver.PhysicalWriteOrigin.EXTERNAL_BUS);
        }
    }

    /** Key-off, maximum release and full attenuation on every operator of {@code ch}. */
    private void silenceChannelNow(int ch) {
        int port = ch / 3;
        int hardwareChannel = ch % 3;
        applyWrite(0, 0x28, hardwareChannel + (port == 0 ? 0 : 4));
        for (int slot = 0; slot < 4; slot++) {
            int register = slot * 4 + hardwareChannel;
            applyWrite(port, 0x40 + register, 0x7F);
            applyWrite(port, 0x80 + register, 0x0F);
        }
    }

    // --------------------------------------------------------------- render

    @Override
    public void renderStereo(int[] left, int[] right) {
        renderStereo(left, right, Math.min(left.length, right.length));
    }

    @Override
    public void renderStereo(int[] left, int[] right, int frames) {
        frames = Math.min(frames, Math.min(left.length, right.length));
        if (frames <= 0) {
            return;
        }
        if (isDirectOutput()) {
            for (int i = 0; i < frames; i++) {
                while (directFrameCount == 0) {
                    renderInternalFrame();
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
                renderInternalFrame();
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

    private void renderInternalFrame() {
        busCredit = Math.max(0, busCredit - CYCLES_PER_FRAME);
        landPendingOps();
        serviceDacFrame();
        dsp.renderFrame(channelOut);
        int leftSum = 0;
        int rightSum = 0;
        for (int ch = 0; ch < 6; ch++) {
            if (mutes[ch]) {
                continue;
            }
            int scaled = (channelOut[ch] * 3) >> 2;
            if ((pan[ch] & 1) != 0) {
                leftSum += scaled;
            }
            if ((pan[ch] & 2) != 0) {
                rightSum += scaled;
            }
        }
        emitFrame(leftSum, rightSum);
        internalCycles += CYCLES_PER_FRAME;
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

    // ------------------------------------------------- rollback + snapshots

    static final class FastMutationBackup implements FmChip.MutationBackup {
        private final FastYm2612Chip owner;
        private final FmDsp dsp;
        private final int[] pan = new int[6];
        private final int[] latched = new int[2];
        private final boolean[] mutes = new boolean[6];
        private final BlipResampler.MutationBackup resampler = new BlipResampler.MutationBackup();
        private int chipType;
        private double outputRate;
        private boolean dacInterpolate;
        private int directFrameCount;
        private int pendingCount;
        private long flushedOps;
        private long internalCycles;
        private int queuedAddress;
        private int dacSampleId;
        private int dacPeriod;
        private int dacIndex;
        private int dacAccumulator;
        private int dacSampleEndPending;
        private int busCredit;
        private int[] direct = new int[0];
        private int[] ops = new int[0];

        private FastMutationBackup(FastYm2612Chip owner) {
            this.owner = owner;
            this.dsp = owner.dsp.newInstance();
        }
    }

    @Override
    public FmChip.MutationBackup createMutationBackup() {
        return new FastMutationBackup(this);
    }

    private FastMutationBackup own(FmChip.MutationBackup backup) {
        if (!(backup instanceof FastMutationBackup fast) || fast.owner != this) {
            throw new IllegalArgumentException("foreign FM mutation backup");
        }
        return fast;
    }

    @Override
    public void captureMutation(FmChip.MutationBackup candidate) {
        FastMutationBackup backup = own(candidate);
        dsp.copyStateTo(backup.dsp);
        System.arraycopy(pan, 0, backup.pan, 0, 6);
        System.arraycopy(latchedRegister, 0, backup.latched, 0, 2);
        System.arraycopy(mutes, 0, backup.mutes, 0, 6);
        backup.chipType = chipType;
        backup.outputRate = outputRate;
        backup.dacInterpolate = dacInterpolate;
        backup.directFrameCount = directFrameCount;
        backup.pendingCount = pendingCount;
        backup.flushedOps = flushedOps;
        backup.internalCycles = internalCycles;
        backup.queuedAddress = queuedAddress;
        backup.dacSampleId = dacSampleId;
        backup.dacPeriod = dacPeriod;
        backup.dacIndex = dacIndex;
        backup.dacAccumulator = dacAccumulator;
        backup.dacSampleEndPending = dacSampleEndPending;
        backup.busCredit = busCredit;
        int directSize = directFrameCount * 2;
        if (backup.direct.length < directSize) {
            backup.direct = new int[directSize];
        }
        copyDirectFrames(backup.direct);
        int opSize = pendingCount * OP_STRIDE;
        if (backup.ops.length < opSize) {
            backup.ops = new int[opSize];
        }
        System.arraycopy(pendingOps, 0, backup.ops, 0, opSize);
        resampler.captureMutation(backup.resampler);
    }

    @Override
    public void restoreMutation(FmChip.MutationBackup candidate) {
        FastMutationBackup backup = own(candidate);
        backup.dsp.copyStateTo(dsp);
        System.arraycopy(backup.pan, 0, pan, 0, 6);
        System.arraycopy(backup.latched, 0, latchedRegister, 0, 2);
        System.arraycopy(backup.mutes, 0, mutes, 0, 6);
        chipType = backup.chipType;
        outputRate = backup.outputRate;
        dacInterpolate = backup.dacInterpolate;
        restoreDirectFrames(backup.direct, backup.directFrameCount);
        restorePendingOps(backup.ops, backup.pendingCount);
        flushedOps = backup.flushedOps;
        internalCycles = backup.internalCycles;
        queuedAddress = backup.queuedAddress;
        dacSampleId = backup.dacSampleId;
        dacPeriod = backup.dacPeriod;
        dacIndex = backup.dacIndex;
        dacAccumulator = backup.dacAccumulator;
        dacSampleEndPending = backup.dacSampleEndPending;
        busCredit = backup.busCredit;
        resampler.restoreMutation(backup.resampler);
        emitBoundary(ChipWriteObserver.PhysicalTimelineBoundary.SNAPSHOT_RESTORE);
    }

    private void copyDirectFrames(int[] into) {
        int capacity = directFrames.length / 2;
        for (int i = 0; i < directFrameCount; i++) {
            int from = ((directFrameHead + i) % capacity) * 2;
            into[i * 2] = directFrames[from];
            into[i * 2 + 1] = directFrames[from + 1];
        }
    }

    private void restoreDirectFrames(int[] direct, int count) {
        int size = count * 2;
        if (directFrames.length < size) {
            directFrames = new int[Math.max(size, directFrames.length * 2)];
        }
        System.arraycopy(direct, 0, directFrames, 0, size);
        directFrameHead = 0;
        directFrameCount = count;
    }

    private void restorePendingOps(int[] ops, int count) {
        int size = count * OP_STRIDE;
        if (pendingOps.length < size) {
            pendingOps = new int[Math.max(size, pendingOps.length * 2)];
        }
        System.arraycopy(ops, 0, pendingOps, 0, size);
        pendingCount = count;
    }

    /** Value-comparable state of the fast facade and its DSP. */
    public record Snapshot(
            int chipType,
            double outputRate,
            FmDsp dsp,
            int[] pan,
            int[] latchedRegister,
            int[] directFrames,
            int[] pendingOps,
            long flushedOps,
            long internalCycles,
            int queuedAddress,
            int dacSampleId,
            int dacPeriod,
            int dacIndex,
            int dacAccumulator,
            int dacSampleEndPending,
            int busCredit,
            boolean dacInterpolate,
            boolean[] mutes,
            BlipResampler.Snapshot resampler) implements FmChip.Snapshot {

        public Snapshot {
            Objects.requireNonNull(dsp, "dsp");
            FmDsp copy = dsp.newInstance();
            dsp.copyStateTo(copy);
            dsp = copy;
            pan = pan.clone();
            latchedRegister = latchedRegister.clone();
            directFrames = directFrames.clone();
            pendingOps = pendingOps.clone();
            mutes = mutes.clone();
        }

        @Override
        public FmDsp dsp() {
            FmDsp copy = dsp.newInstance();
            dsp.copyStateTo(copy);
            return copy;
        }

        @Override
        public int[] pan() { return pan.clone(); }

        @Override
        public int[] latchedRegister() { return latchedRegister.clone(); }

        @Override
        public int[] directFrames() { return directFrames.clone(); }

        @Override
        public int[] pendingOps() { return pendingOps.clone(); }

        @Override
        public boolean[] mutes() { return mutes.clone(); }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) {
                return true;
            }
            if (!(candidate instanceof Snapshot other)) {
                return false;
            }
            return chipType == other.chipType
                    && Double.compare(outputRate, other.outputRate) == 0
                    && dsp.equals(other.dsp)
                    && Arrays.equals(pan, other.pan)
                    && Arrays.equals(latchedRegister, other.latchedRegister)
                    && Arrays.equals(directFrames, other.directFrames)
                    && Arrays.equals(pendingOps, other.pendingOps)
                    && flushedOps == other.flushedOps
                    && internalCycles == other.internalCycles
                    && queuedAddress == other.queuedAddress
                    && dacSampleId == other.dacSampleId
                    && dacPeriod == other.dacPeriod
                    && dacIndex == other.dacIndex
                    && dacAccumulator == other.dacAccumulator
                    && dacSampleEndPending == other.dacSampleEndPending
                    && busCredit == other.busCredit
                    && dacInterpolate == other.dacInterpolate
                    && Arrays.equals(mutes, other.mutes)
                    && Objects.equals(resampler, other.resampler);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(chipType, outputRate, dsp, flushedOps, internalCycles, queuedAddress,
                    dacSampleId, dacPeriod, dacIndex, dacAccumulator, dacSampleEndPending, busCredit,
                    dacInterpolate, resampler);
            result = 31 * result + Arrays.hashCode(pan);
            result = 31 * result + Arrays.hashCode(latchedRegister);
            result = 31 * result + Arrays.hashCode(directFrames);
            result = 31 * result + Arrays.hashCode(pendingOps);
            return 31 * result + Arrays.hashCode(mutes);
        }
    }

    @Override
    public FmChip.Snapshot captureSnapshot() {
        int[] direct = new int[directFrameCount * 2];
        copyDirectFrames(direct);
        return new Snapshot(chipType, outputRate, dsp, pan, latchedRegister, direct,
                Arrays.copyOf(pendingOps, pendingCount * OP_STRIDE), flushedOps, internalCycles, queuedAddress,
                dacSampleId, dacPeriod, dacIndex, dacAccumulator, dacSampleEndPending, busCredit,
                dacInterpolate, mutes, resampler.captureSnapshot());
    }

    @Override
    public void restoreSnapshot(FmChip.Snapshot candidate) {
        if (!(candidate instanceof Snapshot snapshot)) {
            throw new IllegalArgumentException(
                    "snapshot was captured by a different FM core: " + candidate.getClass().getSimpleName());
        }
        chipType = snapshot.chipType;
        outputRate = snapshot.outputRate;
        snapshot.dsp.copyStateTo(dsp);
        System.arraycopy(snapshot.pan, 0, pan, 0, 6);
        System.arraycopy(snapshot.latchedRegister, 0, latchedRegister, 0, 2);
        restoreDirectFrames(snapshot.directFrames, snapshot.directFrames.length / 2);
        restorePendingOps(snapshot.pendingOps, snapshot.pendingOps.length / OP_STRIDE);
        flushedOps = snapshot.flushedOps;
        internalCycles = snapshot.internalCycles;
        queuedAddress = snapshot.queuedAddress;
        dacSampleId = snapshot.dacSampleId;
        dacPeriod = snapshot.dacPeriod;
        dacIndex = snapshot.dacIndex;
        dacAccumulator = snapshot.dacAccumulator;
        dacSampleEndPending = snapshot.dacSampleEndPending;
        busCredit = snapshot.busCredit;
        dacInterpolate = snapshot.dacInterpolate;
        System.arraycopy(snapshot.mutes, 0, mutes, 0, 6);
        resampler.restoreSnapshot(snapshot.resampler);
        emitBoundary(ChipWriteObserver.PhysicalTimelineBoundary.SNAPSHOT_RESTORE);
    }

    /** See {@link Ym2612Chip#captureSfxAdmissionState(int)}: same pending-op withdrawal semantics. */
    record SfxAdmissionState(int affectedChannelMask, long opsEnqueuedAtCapture, int queuedAddress)
            implements FmChip.SfxAdmissionState { }

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
        emitBoundary(ChipWriteObserver.PhysicalTimelineBoundary.MODEL_MUTATION);
    }
}
