package com.openggf.capture;

/**
 * The physical framebuffer rectangle captured for the lifetime of a recording.
 */
public record CaptureViewport(int x, int y, int width, int height) {

    public CaptureViewport {
        if (width <= 0) {
            throw new IllegalArgumentException("Capture viewport width must be positive: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("Capture viewport height must be positive: " + height);
        }
    }

    /** Number of bytes required for one tightly packed RGBA8888 frame. */
    public int rgbaByteSize() {
        return Math.multiplyExact(Math.multiplyExact(width, height),
                GlReadPixelsGrabber.BYTES_PER_PIXEL);
    }
}
