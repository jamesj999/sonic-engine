package com.openggf.capture;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Driver-agnostic recording façade. A driver calls {@link #start} once,
 * {@link #submit} per frame, then {@link #stop}. The output filename is
 * {@code capture-<label>-<timestamp>.<container>} under {@code outputDir},
 * where the container defaults to {@code mkv}.
 *
 * <p>The timestamp string is injected so callers control formatting/clock and
 * tests stay deterministic.
 */
public class CaptureRecorder {

    private final EncoderSink sink;
    private final Path outputFile;
    private boolean aborted;
    private final int maxPixelBuffers;
    private final java.util.ArrayDeque<byte[]> pixelBuffers = new java.util.ArrayDeque<>();
    private int allocatedPixelBuffers;

    public CaptureRecorder(CaptureEncoder encoder, BackpressurePolicy policy, int queueCapacity,
                           Path outputDir, String label, String timestamp) {
        this(encoder, policy, queueCapacity, outputDir, label, timestamp, "mkv");
    }

    /**
     * @param container output file extension without the dot, e.g. {@code mkv}
     *        or {@code mp4}. ffmpeg selects its muxer from this, so it must
     *        accept the configured codecs — FFV1 has no MP4 mapping, for
     *        instance. Validated here so a bad value fails before a recording
     *        starts rather than at the first frame.
     */
    public CaptureRecorder(CaptureEncoder encoder, BackpressurePolicy policy, int queueCapacity,
                           Path outputDir, String label, String timestamp, String container) {
        this.maxPixelBuffers = Math.addExact(queueCapacity, 2);
        this.sink = new EncoderSink(encoder, policy, queueCapacity);
        this.outputFile = outputDir.resolve(
                "capture-" + label + "-" + timestamp + "." + normalizeContainer(container));
    }

    private static String normalizeContainer(String container) {
        String normalized = container == null ? "" : container.trim();
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("capture container must not be empty");
        }
        if (!normalized.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "capture container must be a bare file extension such as mkv or mp4,"
                            + " not '" + container + "'");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    public Path outputFile() {
        return outputFile;
    }

    public void start(int width, int height, int fps, int sampleRate) throws CaptureException {
        sink.open(outputFile, width, height, fps, sampleRate);
    }

    public void submit(CapturedFrame frame) throws CaptureException {
        sink.submit(frame);
    }

    /** Capture directly into bounded storage owned until the encoder finishes the frame. */
    CapturedFrame grabFrame(VideoFrameGrabber grabber, short[] pcm, int samples, long index) {
        int size = GlReadPixelsGrabber.frameByteSize(grabber.width(), grabber.height());
        byte[] pixels;
        synchronized (pixelBuffers) {
            pixels = pixelBuffers.pollFirst();
            if (pixels == null) {
                if (allocatedPixelBuffers >= maxPixelBuffers) {
                    throw new IllegalStateException("capture pixel pool exhausted");
                }
                pixels = new byte[size];
                allocatedPixelBuffers++;
            }
        }
        byte[] owned = pixels;
        try {
            grabber.grabInto(owned);
            return CapturedFrame.ownedPixels(owned, grabber.width(), grabber.height(),
                    pcm, samples, index, () -> recycle(owned));
        } catch (Throwable failure) {
            recycle(owned);
            throw failure;
        }
    }

    private void recycle(byte[] pixels) {
        synchronized (pixelBuffers) { pixelBuffers.addLast(pixels); }
    }

    /** Drains and finalizes; returns the encoder's written file. */
    public Path stop() throws CaptureException {
        return sink.stop();
    }

    public synchronized void abort() {
        abort(Duration.ofSeconds(30));
    }

    synchronized void abort(Duration timeout) {
        if (!aborted) {
            aborted = true;
            sink.abort(timeout);
        }
    }

    public long droppedCount() {
        return sink.droppedCount();
    }

    /** Frames whose submit had to wait for a full encoder queue. */
    public long exhaustedFrameCount() {
        return sink.exhaustedFrameCount();
    }

    /**
     * One-line account of encoder-queue exhaustion over this recording, or null
     * if the queue never ran dry. Under {@code BLOCK} that wait lands on the
     * submitting thread, so this is the record of how much the encoder cost the
     * caller.
     */
    public String exhaustionSummary() {
        return sink.exhaustionSummary();
    }
}
