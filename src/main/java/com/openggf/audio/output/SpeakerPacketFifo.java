package com.openggf.audio.output;

/**
 * Owner-thread, allocation-free FIFO for final stereo speaker PCM.
 *
 * <p>The queue holds exactly two seconds. When an offer would overflow it,
 * the oldest PCM is discarded until at least one second is free. This queue
 * owns speaker latency only; presentation history and recording taps live
 * upstream and are never affected by its overflow policy.</p>
 */
public final class SpeakerPacketFifo {
    private static final int CHANNELS = 2;

    private final int oneSecondFrames;
    private final int capacityFrames;
    private final short[] samples;
    private int readFrame;
    private int queuedFrames;
    private long droppedFrames;

    public SpeakerPacketFifo(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        oneSecondFrames = sampleRate;
        capacityFrames = Math.multiplyExact(sampleRate, 2);
        samples = new short[Math.multiplyExact(capacityFrames, CHANNELS)];
    }

    public void offer(short[] source, int stereoFrames) {
        if (stereoFrames < 0 || stereoFrames * CHANNELS > source.length) {
            throw new IllegalArgumentException("invalid stereo frame count");
        }
        int sourceFrame = 0;
        if (stereoFrames > capacityFrames) {
            int skipped = stereoFrames - capacityFrames;
            sourceFrame = skipped;
            stereoFrames = capacityFrames;
            droppedFrames += queuedFrames + skipped;
            readFrame = 0;
            queuedFrames = 0;
        } else if (capacityFrames - queuedFrames < stereoFrames) {
            int discard = Math.min(queuedFrames, oneSecondFrames);
            readFrame = (readFrame + discard) % capacityFrames;
            queuedFrames -= discard;
            droppedFrames += discard;
        }
        while (capacityFrames - queuedFrames < stereoFrames) {
            int discard = Math.min(queuedFrames,
                    stereoFrames - (capacityFrames - queuedFrames));
            readFrame = (readFrame + discard) % capacityFrames;
            queuedFrames -= discard;
            droppedFrames += discard;
        }
        int writeFrame = (readFrame + queuedFrames) % capacityFrames;
        copyIntoRing(source, sourceFrame, writeFrame, stereoFrames);
        queuedFrames += stereoFrames;
    }

    public int drain(short[] target, int maxStereoFrames) {
        if (maxStereoFrames < 0
                || maxStereoFrames * CHANNELS > target.length) {
            throw new IllegalArgumentException("invalid stereo frame count");
        }
        int drained = Math.min(maxStereoFrames, queuedFrames);
        copyFromRing(target, readFrame, drained);
        readFrame = (readFrame + drained) % capacityFrames;
        queuedFrames -= drained;
        return drained;
    }

    public void flush() {
        readFrame = 0;
        queuedFrames = 0;
    }

    public int queuedStereoFrames() {
        return queuedFrames;
    }

    public long droppedStereoFrames() {
        return droppedFrames;
    }

    private void copyIntoRing(
            short[] source, int sourceFrame, int targetFrame, int frames) {
        int first = Math.min(frames, capacityFrames - targetFrame);
        System.arraycopy(source, sourceFrame * CHANNELS, samples,
                targetFrame * CHANNELS, first * CHANNELS);
        int remaining = frames - first;
        if (remaining > 0) {
            System.arraycopy(source, (sourceFrame + first) * CHANNELS,
                    samples, 0, remaining * CHANNELS);
        }
    }

    private void copyFromRing(short[] target, int sourceFrame, int frames) {
        int first = Math.min(frames, capacityFrames - sourceFrame);
        System.arraycopy(samples, sourceFrame * CHANNELS, target, 0,
                first * CHANNELS);
        int remaining = frames - first;
        if (remaining > 0) {
            System.arraycopy(samples, 0, target, first * CHANNELS,
                    remaining * CHANNELS);
        }
    }
}
