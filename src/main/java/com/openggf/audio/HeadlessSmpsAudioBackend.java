package com.openggf.audio;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.PerformanceProfiler;

/**
 * No-device SMPS audio backend for headless capture/replay.
 *
 * <p>This subclass runs the {@link AbstractSmpsAudioBackend} sequencer,
 * music-stack and SFX-lifecycle machinery but never touches an audio device:
 * every device hook is a no-op. Presentation PCM comes solely from
 * {@code AudioPresentationProducer}, which offline capture leases through
 * {@code AudioManager.beginCaptureMode}.
 *
 * <p>The device-facing fallback rate defaults to a deterministic 48000 Hz,
 * with no OpenAL negotiation. Tests may supply another positive rate to prove
 * fractional presentation-clock behavior. Internal-rate output still uses
 * the synthesizer's effective rate.
 */
public final class HeadlessSmpsAudioBackend extends AbstractSmpsAudioBackend {

    private static final int HEADLESS_SAMPLE_RATE = 48_000;
    private final int sampleRate;

    public HeadlessSmpsAudioBackend(SonicConfigurationService configService, PerformanceProfiler profiler) {
        this(configService, profiler, HEADLESS_SAMPLE_RATE);
    }

    HeadlessSmpsAudioBackend(
            SonicConfigurationService configService,
            PerformanceProfiler profiler,
            int sampleRate) {
        super(configService, profiler);
        if (sampleRate <= 0) {
            throw new IllegalArgumentException(
                    "sampleRate must be positive");
        }
        this.sampleRate = sampleRate;
    }

    @Override
    protected int getDeviceSampleRate() {
        return sampleRate;
    }

    @Override
    protected void hookInitDevice() {
        // No device. The fallback sample rate is fixed by getDeviceSampleRate().
    }

    @Override
    protected void hookDestroyDevice() {
        // No device.
    }

    @Override
    protected void hookStartStream() {
        // No device: the presentation producer owns all audible output.
    }

    @Override
    protected void hookStopStreamSource() {
        // No device.
    }

    @Override
    protected void hookUpdateStream() {
        // No device.
    }

    @Override
    protected void hookStopAndClearMusicSource() {
        // No device.
    }

    @Override
    protected void hookStopAndUnqueueAllMusicBuffers() {
        // No device.
    }

    @Override
    protected void hookStopAndClearAllMusicBuffers() {
        // No device.
    }

    @Override
    protected void hookRestartStreamIfDry() {
        // No device.
    }

    @Override
    protected void hookPlayWavSfx(String sfxName, float pitch) {
        // No device.
    }

    @Override
    protected void hookStopAndDeleteWavSfxSources() {
        // No device.
    }

    @Override
    protected void hookCleanupStoppedWavSfx() {
        // No device.
    }

    @Override
    protected void hookPause() {
        // No device.
    }

    @Override
    protected void hookResume() {
        // No device.
    }
}
