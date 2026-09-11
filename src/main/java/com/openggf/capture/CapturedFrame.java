package com.openggf.capture;

/**
 * One captured game frame: RGBA pixels (top-left origin, row-major, 4 bytes/px)
 * plus the stereo PCM produced by that same frame's audio step.
 *
 * <p><b>Ownership:</b> the constructor defensively copies {@code rgba} and
 * {@code pcm}, so a producer may immediately reuse its grab/drain buffers after
 * constructing a frame. Frames are handed to an async encoder thread, so this
 * copy is what makes per-frame buffer reuse safe. (Accessors return the internal
 * copies; the encoder only reads them.)
 *
 */
public final class CapturedFrame {
    private final Runnable recycler;
    private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
    private final byte[] rgba;
    private final int width;
    private final int height;
    private final short[] pcm;
    private final int sampleCount;
    private final long frameIndex;

    public CapturedFrame(byte[] rgba, int width, int height,
                         short[] pcm, int sampleCount, long frameIndex) {
        this(rgba, width, height, pcm, sampleCount, frameIndex, null);
    }

    static CapturedFrame ownedPixels(byte[] rgba, int width, int height,
                                     short[] pcm, int sampleCount, long frameIndex, Runnable recycler) {
        return new CapturedFrame(rgba, width, height, pcm, sampleCount, frameIndex, recycler);
    }

    private CapturedFrame(byte[] rgba, int width, int height,
                          short[] pcm, int sampleCount, long frameIndex, Runnable recycler) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative dimensions");
        }
        if (rgba.length != width * height * 4) {
            throw new IllegalArgumentException(
                    "rgba length " + rgba.length + " != width*height*4 ("
                    + (width * height * 4) + ")");
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("negative sampleCount");
        }
        if (pcm.length < sampleCount * 2) {
            throw new IllegalArgumentException(
                    "pcm holds " + (pcm.length / 2) + " stereo frames, need "
                    + sampleCount);
        }
        // Defensive copy: the producer reuses its grab/drain buffers each frame,
        // but frames live on an async encoder queue. Copy so they can't be
        // mutated out from under the encoder.
        this.recycler = recycler;
        this.rgba = recycler != null ? rgba : rgba.clone();
        this.pcm = pcm.clone();
        this.width = width;
        this.height = height;
        this.sampleCount = sampleCount;
        this.frameIndex = frameIndex;
    }

    // Owned pixel storage is valid only through CaptureEncoder.encode(). Public
    // constructor frames have no recycler and retain their original lifetime.
    void release() {
        if (recycler != null && released.compareAndSet(false, true)) recycler.run();
    }

    public byte[] rgba() { return rgba; }
    public int width() { return width; }
    public int height() { return height; }
    public short[] pcm() { return pcm; }
    public int sampleCount() { return sampleCount; }
    public long frameIndex() { return frameIndex; }
}
