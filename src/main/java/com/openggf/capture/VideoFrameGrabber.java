package com.openggf.capture;

/** Produces RGBA8888 pixels (top-left origin) for the current framebuffer. */
public interface VideoFrameGrabber extends AutoCloseable {
    int width();

    int height();

    /**
     * @return {@code width()*height()*4} bytes of RGBA pixels. An implementation
     *         may return a buffer it reuses, so the array is valid only until
     *         the next {@code grab()} — hand it straight to
     *         {@link CapturedFrame}, whose defensive copy exists for exactly
     *         this reuse, and never retain it.
     */
    byte[] grab();

    /** Reads into caller-owned storage; the caller may retain it until encoding completes. */
    default void grabInto(byte[] target) {
        byte[] pixels = grab();
        if (target.length != pixels.length) throw new IllegalArgumentException("wrong pixel buffer size");
        System.arraycopy(pixels, 0, target, 0, pixels.length);
    }

    /** Whether readback can be queued one presentation before consumption. */
    default boolean deferredReadback() { return false; }

    /** Enqueues the current back buffer; subsequent grabInto consumes the oldest request. */
    default void beginReadback() { throw new UnsupportedOperationException("synchronous grabber"); }

    /** Releases any native buffers. Implementations must tolerate repeat calls. */
    @Override
    default void close() {
    }
}
