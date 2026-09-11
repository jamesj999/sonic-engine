package com.openggf.audio.presentation;

import com.openggf.audio.runtime.AudioFrameClock;

/**
 * Read-only accounting for the transitional inaudible presentation shadow.
 */
public final class AudioPresentationParityProbe {
    public record Snapshot(long presentedFrames, long forwardFrames,
            long silentFrames, long reverseFrames, long totalStereoFrames,
            long commandCount, long historyEpoch) {
    }

    private final AudioFrameClock clock;
    private long presentedFrames;
    private long forwardFrames;
    private long silentFrames;
    private long reverseFrames;
    private long totalStereoFrames;
    private long commandCount;
    private long historyEpoch;

    public AudioPresentationParityProbe(int sampleRate, int frameRate) {
        clock = new AudioFrameClock(sampleRate, frameRate);
    }

    public void commandSubmitted() {
        commandCount++;
    }

    public Runnable prepareCommandSubmissions(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        return () -> commandCount += count;
    }

    public void presented(PresentationMode mode) {
        presentedFrames++;
        totalStereoFrames += clock.samplesForNextFrame();
        switch (mode) {
            case FORWARD -> forwardFrames++;
            case SILENT -> silentFrames++;
            case REVERSE -> reverseFrames++;
        }
    }

    public void historyBoundary() {
        historyEpoch++;
    }

    public Snapshot snapshot() {
        return new Snapshot(presentedFrames, forwardFrames, silentFrames,
                reverseFrames, totalStereoFrames, commandCount, historyEpoch);
    }
}
