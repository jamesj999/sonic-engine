package com.openggf.audio;

import com.openggf.audio.runtime.AudioFrameClock;

import java.util.Arrays;
import java.util.Objects;

/**
 * Capture audio handle that preserves the negotiated sample clock while
 * yielding stereo silence.
 */
public class ClockedSilenceAudioHandle implements LiveCaptureAudioHandle {
    private final AudioFrameClock clock;
    private final int sampleRate;
    private final int frameRate;
    private final int maxStereoFramesPerPacket;

    public ClockedSilenceAudioHandle(int sampleRate, int frameRate) {
        this(new AudioFrameClock(sampleRate, frameRate));
    }

    private ClockedSilenceAudioHandle(AudioFrameClock clock) {
        this.clock = Objects.requireNonNull(clock);
        AudioFrameClock.Snapshot initial = clock.captureSnapshot();
        sampleRate = initial.sampleRate();
        frameRate = initial.frameRate();
        maxStereoFramesPerPacket = Math.floorDiv(sampleRate + frameRate - 1, frameRate);
    }

    public static ClockedSilenceAudioHandle atPhase(AudioFrameClock.Snapshot phase) {
        Objects.requireNonNull(phase);
        AudioFrameClock clock = new AudioFrameClock(phase.sampleRate(), phase.frameRate());
        clock.restoreSnapshot(phase);
        return new ClockedSilenceAudioHandle(clock);
    }

    @Override public int sampleRate() {
        return sampleRate;
    }

    @Override public int frameRate() {
        return frameRate;
    }

    @Override public int maxStereoFramesPerPacket() {
        return maxStereoFramesPerPacket;
    }

    @Override public int drainPresentationFrame(short[] target) {
        Objects.requireNonNull(target);
        int stereoFrames = clock.peekSamplesForNextFrame();
        int sampleCount = Math.multiplyExact(stereoFrames, 2);
        if (target.length < sampleCount) {
            throw new IllegalArgumentException("target is too small for the next stereo packet");
        }
        Arrays.fill(target, 0, sampleCount, (short) 0);
        int advancedFrames = clock.samplesForNextFrame();
        if (advancedFrames != stereoFrames) {
            throw new IllegalStateException("audio frame clock changed while draining silence");
        }
        return stereoFrames;
    }

    @Override public long totalStereoFrames() {
        return clock.totalSamplesProduced();
    }

    @Override public AudioFrameClock.Snapshot clockSnapshot() {
        return clock.captureSnapshot();
    }

    /**
     * Silence has no external resource. Closing is deliberately idempotent and
     * does not stop its clock, which keeps repeated failure cleanup harmless.
     */
    @Override public void close() {
    }
}
