package com.openggf.audio;

import com.openggf.audio.runtime.AudioFrameClock;

import java.util.Objects;

/**
 * Development-only failure injection around a recording tap.
 */
public final class DebugFailAfterFramesAudioHandle implements LiveCaptureAudioHandle {
    private final LiveCaptureAudioHandle delegate;
    private final long failAfterFrames;
    private long successfulDrains;

    private DebugFailAfterFramesAudioHandle(LiveCaptureAudioHandle delegate, long failAfterFrames) {
        this.delegate = Objects.requireNonNull(delegate);
        this.failAfterFrames = failAfterFrames;
    }

    public static LiveCaptureAudioHandle maybeWrap(
            LiveCaptureAudioHandle delegate, int failAfterFrames) {
        Objects.requireNonNull(delegate);
        return failAfterFrames < 0
                ? delegate
                : new DebugFailAfterFramesAudioHandle(delegate, failAfterFrames);
    }

    @Override public int sampleRate() {
        return delegate.sampleRate();
    }

    @Override public int frameRate() {
        return delegate.frameRate();
    }

    @Override public int maxStereoFramesPerPacket() {
        return delegate.maxStereoFramesPerPacket();
    }

    @Override public int drainPresentationFrame(short[] target) {
        if (successfulDrains >= failAfterFrames) {
            throw new IllegalStateException(
                    "Injected live-capture audio failure after " + failAfterFrames + " frames");
        }
        int frames = delegate.drainPresentationFrame(target);
        successfulDrains++;
        return frames;
    }

    @Override public long totalStereoFrames() {
        return delegate.totalStereoFrames();
    }

    @Override public AudioFrameClock.Snapshot clockSnapshot() {
        return delegate.clockSnapshot();
    }

    @Override public void close() {
        delegate.close();
    }
}
