package com.openggf.audio.output;

import com.openggf.audio.presentation.AudioPresentationFrameView;

import java.util.Objects;

/**
 * Immediate discard sink used when no speaker device is present.
 */
public final class NoDeviceAudioSink implements AudioPresentationSink {
    private final int sampleRate;

    public NoDeviceAudioSink(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        this.sampleRate = sampleRate;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public void accept(AudioPresentationFrameView frame) {
        Objects.requireNonNull(frame, "frame");
    }

    @Override
    public void onReverseBoundary() {
    }

    @Override
    public void close() {
    }
}
