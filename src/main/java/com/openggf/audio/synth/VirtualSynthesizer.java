package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLogicalWriteTarget;
import com.openggf.audio.rewind.SmpsSourceDescriptor;

import java.util.Arrays;
import java.util.Objects;

public class VirtualSynthesizer implements SmpsLogicalWriteTarget {
    public enum Initialization {
        LEGACY_SILENCE,
        DEFERRED
    }

    private final PsgChip psg;
    private final FmChip ym;
    private final FmCoreSelection fmCore;
    private double outputSampleRate = Ym2612Chip.getDefaultOutputRate();
    private boolean chipWriteObserverEnabled;

    // Output headroom: reduce overall level so 6 FM channels + PSG don't clip 16-bit output.
    private static final int MASTER_GAIN_SHIFT = 1; // -6 dB

    /**
     * PSG output-stage preamp, in percent, applied through
     * {@link PsgChip#configure(int, int)}. Both chips now emit hardware-relative
     * levels (one full-scale PSG channel 8191, one full-scale FM channel 6144 =
     * {@code Ym2612Chip.OUTPUT_SHIFT} over the 768 pin sum), and this is the
     * only FM:PSG balance constant in the engine. It reproduces the balance the
     * engine had before either core was rewritten: the previous PSG core put
     * one full-scale channel at 2800 x 150 % = 4200 against a previous FM full
     * scale of 8191, an FM:PSG ratio of 1.950 (+5.80 dB), so
     * {@code 6144 x 4200 / 8191^2 = 38.46 %} restores it (38 %: 1.974,
     * +5.90 dB, residual +0.10 dB from percent granularity). It is pre-rewrite
     * parity, not a hardware calibration: no two-chip capture exists to
     * calibrate against. Derivation and headroom numbers in
     * docs/architecture/validation/2026-08-29-audio-mix-calibration.md.
     */
    public static final int PSG_PREAMP_PERCENT = 38;
    /** SN76489 stereo byte with every channel on both sides; the Mega Drive part has no stereo register. */
    private static final int PSG_PANNING_BOTH = 0xFF;

    // Scratch buffers for render() to avoid per-call allocations.
    private int[] scratchLeft = new int[0];
    private int[] scratchRight = new int[0];

    public VirtualSynthesizer() {
        this(Ym2612Chip.getDefaultOutputRate(), ChipWriteObserver.NONE,
                Initialization.LEGACY_SILENCE);
    }

    public VirtualSynthesizer(double outputSampleRate) {
        this(outputSampleRate, ChipWriteObserver.NONE,
                Initialization.LEGACY_SILENCE);
    }

    public VirtualSynthesizer(
            double outputSampleRate, ChipWriteObserver observer) {
        this(outputSampleRate, observer, Initialization.LEGACY_SILENCE);
    }

    public VirtualSynthesizer(
            double outputSampleRate,
            ChipWriteObserver observer,
            Initialization initialization) {
        this(outputSampleRate, observer, initialization, FmCoreSelection.ACCURATE);
    }

    /**
     * @param fmCore which FM core renders: the cycle-exact Nuked-OPN2 port or
     *               the register-level fast core (see {@code audio.fmCore})
     */
    public VirtualSynthesizer(
            double outputSampleRate,
            ChipWriteObserver observer,
            Initialization initialization,
            FmCoreSelection fmCore) {
        this.fmCore = Objects.requireNonNull(fmCore, "fmCore");
        // Clean-room SN76489 core; the FM:PSG balance is set here, not inside the chip.
        this.psg = new PsgChip(outputSampleRate, PsgChip.ChipType.INTEGRATED);
        this.psg.configure(PSG_PREAMP_PERCENT, PSG_PANNING_BOTH);
        this.ym = fmCore == FmCoreSelection.FAST
                ? new FastYm2612Chip(com.openggf.audio.synth.fast.FastFmCores.newDsp())
                : new Ym2612Chip();
        setChipWriteObserver(observer);
        setOutputSampleRate(outputSampleRate);
        if (Objects.requireNonNull(initialization, "initialization")
                == Initialization.LEGACY_SILENCE) {
            // Match typical driver init: silence chips on startup to avoid power-on noise.
            silenceAll();
        }
    }

    public void setOutputSampleRate(double outputSampleRate) {
        if (outputSampleRate <= 0.0) {
            return;
        }
        this.outputSampleRate = outputSampleRate;
        ym.setOutputSampleRate(outputSampleRate);
        psg.setSampleRate(outputSampleRate);
    }

    /** The FM core this synthesizer was built with. */
    public FmCoreSelection fmCore() {
        return fmCore;
    }

    public double getOutputSampleRate() {
        return outputSampleRate;
    }

    /**
     * Installs one diagnostic observer at both resolved chip-write boundaries.
     * Passing {@code null} restores the disabled no-op observer.
     */
    public void setChipWriteObserver(ChipWriteObserver observer) {
        ym.setWriteObserver(observer);
        psg.setWriteObserver(observer);
        chipWriteObserverEnabled = observer != null
                && observer != ChipWriteObserver.NONE;
    }

    /** Returns whether chip writes can currently invoke user code. */
    public final boolean hasChipWriteObserver() {
        return chipWriteObserverEnabled;
    }

    /** Opaque mutable rollback storage, separate from immutable rewind snapshots. */
    public static final class MutationBackup {
        private final VirtualSynthesizer owner;
        private final FmChip.MutationBackup ym;
        private final PsgChip.MutationBackup psg = new PsgChip.MutationBackup();
        private double outputSampleRate;

        private MutationBackup(VirtualSynthesizer owner) {
            this.owner = owner;
            this.ym = owner.ym.createMutationBackup();
        }
    }

    public MutationBackup createMutationBackup() {
        return new MutationBackup(this);
    }

    public void captureMutation(MutationBackup backup) {
        if (backup.owner != this) throw new IllegalArgumentException("foreign synth backup");
        backup.outputSampleRate = outputSampleRate;
        ym.captureMutation(backup.ym);
        psg.captureMutation(backup.psg);
    }

    public void restoreMutation(MutationBackup backup) {
        if (backup.owner != this) throw new IllegalArgumentException("foreign synth backup");
        outputSampleRate = backup.outputSampleRate;
        ym.restoreMutation(backup.ym);
        psg.restoreMutation(backup.psg);
    }

    public Snapshot captureSynthSnapshot() {
        return new Snapshot(outputSampleRate, ym.captureSnapshot(), psg.captureSnapshot());
    }

    public void restoreSynthSnapshot(Snapshot snapshot) {
        outputSampleRate = snapshot.outputSampleRate();
        ym.restoreSnapshot(snapshot.ym());
        psg.restoreSnapshot(snapshot.psg());
    }

    /**
     * Delimits a diagnostic raw-bus segment without mutating either chip.
     * Used after a live transaction restores and discards unpublished writes.
     */
    public void reportPhysicalTimelineBoundary(
            ChipWriteObserver.PhysicalTimelineBoundary boundary) {
        ym.reportPhysicalTimelineBoundary(boundary);
        psg.reportPhysicalTimelineBoundary(boundary);
    }

    /** Channel-bounded rollback state for one prepared SFX admission. */
    public static final class SfxAdmissionState {
        private final FmChip.SfxAdmissionState ym;
        private final PsgChip.SfxAdmissionState psg;

        private SfxAdmissionState(
                FmChip.SfxAdmissionState ym,
                PsgChip.SfxAdmissionState psg) {
            this.ym = ym;
            this.psg = psg;
        }
    }

    public SfxAdmissionState captureSfxAdmissionState(
            int affectedFmMask, int affectedPsgMask) {
        return new SfxAdmissionState(
                ym.captureSfxAdmissionState(affectedFmMask),
                psg.captureSfxAdmissionState(affectedPsgMask));
    }

    public void restoreSfxAdmissionState(SfxAdmissionState state) {
        ym.restoreSfxAdmissionState(state.ym);
        psg.restoreSfxAdmissionState(state.psg);
    }

    public void setDacData(DacData data) {
        ym.setDacData(data);
    }

    @Override
    public void selectDac(SmpsSourceDescriptor source, DacData data) {
        // Standalone synthesis has one physical DAC bank, so source
        // provenance does not affect the selected data.
        setDacData(data);
    }

    /**
     * Returns the identity-bearing DAC bank needed by the combined legacy
     * driver snapshot without exposing this physical synthesizer itself.
     */
    public DacData selectedDacDataForSnapshot() {
        return ym.liveDacDataReference();
    }

    /** Restores the identity-bearing DAC bank for a combined legacy snapshot. */
    public void restoreSelectedDacData(DacData data) {
        ym.setDacData(data);
    }

    /**
     * Captures the identity-bearing DAC bank selected by a live command.
     * General synth/rewind snapshots intentionally omit this process-local
     * dependency and continue to resolve it through sequencer descriptors.
     */
    protected final DacData captureLiveDacDataReference() {
        return selectedDacDataForSnapshot();
    }

    /**
     * Restores a live command's exact DAC dependency before restoring YM
     * playback fields that resolve their current sample through that bank.
     */
    protected final void restoreLiveDacDataReference(DacData data) {
        restoreSelectedDacData(data);
    }

    @Override
    public void playDac(Object source, int note) {
        ym.playDac(note);
    }

    @Override
    public void stopDac(Object source) {
        ym.stopDac();
    }

    /**
     * Consumes one DAC sample-end edge from the FM chip. See
     * {@link Ym2612Chip#consumeDacSampleEnded()}.
     */
    public boolean consumeDacSampleEnded() {
        return ym.consumeDacSampleEnded();
    }

    public void render(short[] buffer) {
        renderFrames(buffer, 0, buffer.length / 2);
    }

    public void renderFrames(short[] buffer, int frameOffset, int frames) {
        if (buffer == null || frames <= 0) {
            return;
        }
        // Reuse scratch buffers, resize only when needed.
        if (scratchLeft.length < frames) {
            scratchLeft = new int[frames];
            scratchRight = new int[frames];
        }

        // Both chip renderers accumulate into the provided arrays.
        Arrays.fill(scratchLeft, 0, frames, 0);
        Arrays.fill(scratchRight, 0, frames, 0);

        ym.renderStereo(scratchLeft, scratchRight, frames);

        // FM frames arrive at the facade's scale: one full-scale channel is 6144 (the 24-cycle pin sum,
        // 768 per channel, shifted by Ym2612Chip.OUTPUT_SHIFT), so six channels stay inside 16 bits
        // after MASTER_GAIN_SHIFT. PSG frames arrive already scaled by PSG_PREAMP_PERCENT (one
        // full-scale channel 8191 x 38 % = 3112). No other gain is applied here.
        psg.renderStereo(scratchLeft, scratchRight, frames);

        int sampleOffset = frameOffset * 2;
        for (int i = 0; i < frames; i++) {
            int l = scratchLeft[i];
            int r = scratchRight[i];

            if (MASTER_GAIN_SHIFT > 0) {
                l >>= MASTER_GAIN_SHIFT;
                r >>= MASTER_GAIN_SHIFT;
            }

            if (l > 32767) l = 32767; else if (l < -32768) l = -32768;
            if (r > 32767) r = 32767; else if (r < -32768) r = -32768;

            int sampleIndex = sampleOffset + (i * 2);
            buffer[sampleIndex] = (short) l;
            buffer[sampleIndex + 1] = (short) r;
        }
    }

    @Override
    public void writeFm(Object source, int port, int reg, int val) {
        ym.write(port, reg, val);
    }

    @Override
    public void writePsg(Object source, int val) {
        psg.write(val);
    }

    @Override
    public void setInstrument(Object source, int channelId, byte[] voice) {
        ym.setInstrument(channelId, voice);
    }

    @Override
    public void setFmMute(int channel, boolean mute) {
        ym.setMute(channel, mute);
    }

    @Override
    public void setPsgMute(int channel, boolean mute) {
        psg.setMute(channel, mute);
    }

    @Override
    public void setDacInterpolate(boolean interpolate) {
        ym.setDacInterpolate(interpolate);
    }

    @Override
    public void silenceAll() {
        ym.silenceAll();
        psg.silenceAll();
    }

    /**
     * Force-silence an FM channel by directly resetting envelope state.
     * Used when SFX steals a channel to prevent chirp artifacts.
     */
    public void forceSilenceChannel(int channelId) {
        ym.forceSilenceChannel(channelId);
    }

    public record Snapshot(
            double outputSampleRate,
            FmChip.Snapshot ym,
            PsgChip.Snapshot psg) {
    }
}
