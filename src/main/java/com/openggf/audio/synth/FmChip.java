package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;

/**
 * The FM chip surface {@link VirtualSynthesizer} drives, implemented by the
 * cycle-exact {@link Ym2612Chip} and the register-level {@link FastYm2612Chip}.
 *
 * <p>Both facades share the same write, DAC, mute, rendering, mutation-backup,
 * snapshot and SFX-admission contracts so the SMPS driver session, rewind and
 * the physical device do not know which core is installed. Snapshot and
 * admission types are opaque to callers; a facade rejects foreign instances.
 */
public interface FmChip {

    double getOutputSampleRate();

    void setOutputSampleRate(double rate);

    /** 0 selects YM2612 (discrete) behaviour, otherwise YM3438-style. */
    void setChipType(int type);

    void setDacInterpolate(boolean interpolate);

    void setDacData(DacData data);

    DacData liveDacDataReference();

    void setWriteObserver(ChipWriteObserver observer);

    void reportPhysicalTimelineBoundary(ChipWriteObserver.PhysicalTimelineBoundary boundary);

    void reset();

    void write(int port, int reg, int val);

    void writeAddress(int port, int reg);

    void writeData(int port, int val);

    int readStatus();

    void setInstrument(int ch, byte[] voice);

    void silenceAll();

    void forceSilenceChannel(int ch);

    void setMute(int ch, boolean mute);

    void playDac(int note);

    void stopDac();

    boolean consumeDacSampleEnded();

    void renderStereo(int[] left, int[] right);

    void renderStereo(int[] left, int[] right, int frames);

    MutationBackup createMutationBackup();

    void captureMutation(MutationBackup backup);

    void restoreMutation(MutationBackup backup);

    Snapshot captureSnapshot();

    void restoreSnapshot(Snapshot snapshot);

    SfxAdmissionState captureSfxAdmissionState(int affectedChannelMask);

    void restoreSfxAdmissionState(SfxAdmissionState admission);

    /** Reusable private rollback storage owned by one facade instance. */
    interface MutationBackup {
    }

    /** Immutable, value-comparable chip state. */
    interface Snapshot {
        boolean[] mutes();

        boolean dacInterpolate();
    }

    /** Pending-write admission marker captured before an SFX is applied. */
    interface SfxAdmissionState {
    }
}
