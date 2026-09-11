package com.openggf.audio.session;

import com.openggf.audio.AudioStream;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.audio.smps.DacData;

import java.util.Objects;

/** Isolated one-session direct-read adapter for tools and compatibility backends. */
public final class OwnedSmpsAudioStream
        implements AudioStream, AutoCloseable {
    private final SmpsDriverSession session;
    private final double outputSampleRate;

    public OwnedSmpsAudioStream(
            String baseGameId,
            long sourceGeneration,
            SmpsPhysicalDevice.Settings settings,
            SmpsPhysicalPolicy physicalPolicy,
            ChipWriteObserver observer) {
        this(baseGameId, sourceGeneration, settings, physicalPolicy, observer,
                SmpsDriverSessionConfiguration.DEFAULT);
    }

    /** Creates a direct-read session with its host-owned command policy. */
    public OwnedSmpsAudioStream(
            String baseGameId,
            long sourceGeneration,
            SmpsPhysicalDevice.Settings settings,
            SmpsPhysicalPolicy physicalPolicy,
            ChipWriteObserver observer,
            SmpsDriverSessionConfiguration configuration) {
        SmpsPhysicalDevice.Settings resolvedSettings =
                Objects.requireNonNull(settings, "settings");
        outputSampleRate = resolvedSettings.outputSampleRate();
        SmpsPhysicalPolicy resolvedPolicy =
                Objects.requireNonNull(physicalPolicy, "physicalPolicy");
        SmpsDriverSessionConfiguration resolvedConfiguration =
                Objects.requireNonNull(configuration, "configuration");
        session = new SmpsDriverSession(
                resolvedSettings,
                resolvedPolicy,
                Objects.requireNonNull(observer, "observer"),
                new SmpsSessionProfileFingerprint(
                        Objects.requireNonNull(baseGameId, "baseGameId"),
                        sourceGeneration,
                        resolvedPolicy.identity(),
                        resolvedSettings,
                        resolvedConfiguration.statefulCommandPolicy().identity()),
                resolvedConfiguration);
        session.install();
    }

    public SmpsDriver logicalDriver() {
        return session.logicalDriverForTesting();
    }

    public void applyChannelMasks(int fmMask, int psgMask) {
        session.applyChannelMasks(fmMask, psgMask);
    }

    public double outputSampleRate() {
        return outputSampleRate;
    }

    public void setChipWriteObserver(ChipWriteObserver observer) {
        session.setChipWriteObserver(observer);
    }

    /**
     * Runs the physical chip for {@code stereoFrames} of output time without
     * servicing the driver, modelling the Z80 sitting in
     * {@code zPlayDigitalAudio} between two V-ints. See
     * {@link SmpsDriverSession#advanceDacIdleLoop(short[], int)}.
     */
    public void advanceDacIdleLoop(short[] scratch, int stereoFrames) {
        session.advanceDacIdleLoop(scratch, stereoFrames);
    }

    /** Applies the owning session's exact host-policy global stop. */
    public void stopAll() {
        session.applyGlobalStopNow();
    }

    /** Applies the owning session's host-policy {@code zStopSFX} boundary. */
    public void stopSmpsSfx() {
        session.applyCommand(new SmpsSessionCommand.StopSmpsSfx());
    }

    /** Runs the same host fade command used by live presentation. */
    public void fadeOutMusic(int steps, int delay) {
        session.applyCommand(new SmpsSessionCommand.FadeMusic(steps, delay));
    }

    public void prepareFadeOut() {
        session.prepareFadeOut();
    }

    public void armFadeOut(int steps, int delay) {
        session.armFadeOut(steps, delay);
    }

    public VirtualSynthesizer.Snapshot captureSynthSnapshotForTesting() {
        return session.capturePhysicalSnapshotForTesting().synth();
    }

    public void restoreSynthSnapshotForTesting(
            VirtualSynthesizer.Snapshot snapshot, DacData selectedDac) {
        SmpsPhysicalDevice.Snapshot physical =
                session.capturePhysicalSnapshotForTesting();
        session.restorePhysicalSnapshotForTesting(
                new SmpsPhysicalDevice.Snapshot(
                        Objects.requireNonNull(snapshot, "snapshot"),
                        physical.settings(), physical.outputSilenced()),
                selectedDac);
    }

    @Override
    public int read(short[] buffer) {
        return read(buffer, Objects.requireNonNull(buffer, "buffer").length);
    }

    @Override
    public int read(short[] buffer, int length) {
        return session.readDirect(buffer, length);
    }

    @Override
    public boolean isComplete() {
        return logicalDriver().isComplete();
    }

    @Override
    public void close() {
        session.close();
    }
}
