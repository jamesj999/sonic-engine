package com.openggf.audio.runtime;

public final class AudioFrameClock {
    private final int sampleRate;
    private final int frameRate;
    private long totalSamplesProduced;
    private int remainder;

    public AudioFrameClock(int sampleRate, int frameRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (frameRate <= 0) {
            throw new IllegalArgumentException("frameRate must be positive");
        }
        this.sampleRate = sampleRate;
        this.frameRate = frameRate;
    }

    public int samplesForNextFrame() {
        int numerator = sampleRate + remainder;
        int samples = numerator / frameRate;
        remainder = numerator % frameRate;
        totalSamplesProduced += samples;
        return samples;
    }

    /**
     * The sample count {@link #samplesForNextFrame()} would return next, without
     * advancing the clock. Mirrors the same numerator/remainder arithmetic but
     * mutates nothing — use this to size a per-frame PCM drain.
     */
    public int peekSamplesForNextFrame() {
        return (sampleRate + remainder) / frameRate;
    }

    public Snapshot captureSnapshot() {
        return new Snapshot(sampleRate, frameRate, totalSamplesProduced, remainder);
    }

    public void restoreSnapshot(Snapshot snapshot) {
        if (snapshot.sampleRate() != sampleRate || snapshot.frameRate() != frameRate) {
            throw new IllegalArgumentException("snapshot clock rate does not match this clock");
        }
        totalSamplesProduced = snapshot.totalSamplesProduced();
        remainder = snapshot.remainder();
    }

    public long totalSamplesProduced() {
        return totalSamplesProduced;
    }

    public int remainder() {
        return remainder;
    }

    public record Snapshot(int sampleRate, int frameRate, long totalSamplesProduced, int remainder) {
    }
}
