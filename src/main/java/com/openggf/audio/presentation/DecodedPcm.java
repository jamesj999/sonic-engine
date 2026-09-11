package com.openggf.audio.presentation;

import java.util.Objects;

/**
 * Immutable, interleaved signed PCM samples prepared outside the audio render loop.
 */
public final class DecodedPcm {
    private final String assetId;
    private final int channels;
    private final int sampleRate;
    private final short[] samples;

    public DecodedPcm(String assetId, int channels, int sampleRate, short[] samples) {
        this.assetId = requireAssetId(assetId);
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        this.samples = Objects.requireNonNull(samples, "samples").clone();
        if (this.samples.length % channels != 0) {
            throw new IllegalArgumentException("samples must contain complete source frames");
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    public String assetId() {
        return assetId;
    }

    public int channels() {
        return channels;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int sourceFrames() {
        return samples.length / channels;
    }

    public short sample(int frame, int channel) {
        if (frame < 0 || frame >= sourceFrames()) {
            throw new IndexOutOfBoundsException("frame: " + frame);
        }
        if (channel < 0 || channel >= channels) {
            throw new IndexOutOfBoundsException("channel: " + channel);
        }
        return samples[frame * channels + channel];
    }

    public short[] copySamples() {
        return samples.clone();
    }

    private static String requireAssetId(String assetId) {
        String value = Objects.requireNonNull(assetId, "assetId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("assetId must not be blank");
        }
        return value;
    }
}
