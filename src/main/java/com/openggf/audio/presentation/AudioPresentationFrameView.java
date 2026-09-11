package com.openggf.audio.presentation;

import java.util.Objects;

/**
 * Reusable synchronous view of one final interleaved stereo PCM packet.
 *
 * <p>The producer mutates this object for every presentation. Consumers that
 * retain PCM beyond their callback must copy the active range before returning.
 */
public final class AudioPresentationFrameView {
    private short[] samples;
    private int stereoFrames;
    private long presentationFrame;
    private PresentationMode mode;

    AudioPresentationFrameView(short[] samples) {
        this.samples = Objects.requireNonNull(samples, "samples");
        mode = PresentationMode.SILENT;
    }

    void update(
            short[] samples,
            int stereoFrames,
            long presentationFrame,
            PresentationMode mode) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(mode, "mode");
        if (stereoFrames < 0 || samples.length < stereoFrames * 2) {
            throw new IllegalArgumentException(
                    "samples are too small for the active stereo frame count");
        }
        this.samples = samples;
        this.stereoFrames = stereoFrames;
        this.presentationFrame = presentationFrame;
        this.mode = mode;
    }

    public int stereoFrames() {
        return stereoFrames;
    }

    public long presentationFrame() {
        return presentationFrame;
    }

    public PresentationMode mode() {
        return mode;
    }

    public short sampleAt(int stereoFrame, int channel) {
        if (stereoFrame < 0 || stereoFrame >= stereoFrames) {
            throw new IndexOutOfBoundsException(stereoFrame);
        }
        if (channel < 0 || channel >= 2) {
            throw new IndexOutOfBoundsException(channel);
        }
        return samples[stereoFrame * 2 + channel];
    }

    public void copyTo(short[] target, int targetOffsetStereoFrames) {
        Objects.requireNonNull(target, "target");
        if (targetOffsetStereoFrames < 0) {
            throw new IllegalArgumentException(
                    "targetOffsetStereoFrames must be non-negative");
        }
        int targetOffset = Math.multiplyExact(targetOffsetStereoFrames, 2);
        int sampleCount = Math.multiplyExact(stereoFrames, 2);
        if (targetOffset > target.length
                || sampleCount > target.length - targetOffset) {
            throw new IllegalArgumentException(
                    "target is too small for the active stereo frame range");
        }
        System.arraycopy(samples, 0, target, targetOffset, sampleCount);
    }
}
