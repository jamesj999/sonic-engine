package com.openggf.capture;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * {@link VideoFrameGrabber} that reads the back buffer via {@code glReadPixels}.
 * Returns RGBA bytes in OpenGL bottom-up order (no Java-side flip) — ffmpeg's
 * {@code vflip} corrects orientation. MUST be called on the GL thread.
 */
public final class GlReadPixelsGrabber implements VideoFrameGrabber {

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final GlReadRegion glReadRegion;
    private final ByteBuffer readBuffer;
    private byte[] frameBytes;
    private boolean released;

    public GlReadPixelsGrabber(int width, int height) {
        this(0, 0, width, height);
    }

    public GlReadPixelsGrabber(int x, int y, int width, int height) {
        this(x, y, width, height, GlReadPixelsGrabber::readBackBufferRegion);
    }

    GlReadPixelsGrabber(int x, int y, int width, int height, GlReadRegion glReadRegion) {
        CaptureViewport viewport = new CaptureViewport(x, y, width, height);
        this.x = viewport.x();
        this.y = viewport.y();
        this.width = width;
        this.height = height;
        this.glReadRegion = glReadRegion;
        this.readBuffer = MemoryUtil.memAlloc(viewport.rgbaByteSize());
    }

    /** RGBA8888 — 4 bytes per pixel. */
    static final int BYTES_PER_PIXEL = 4;

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    /**
     * The exact RGBA byte size of one grabbed frame at the given dimensions.
     * Both the read buffer and the returned array are sized from this, so it
     * is the single source of truth for the {@code grab()} byte contract.
     */
    static int frameByteSize(int width, int height) {
        return new CaptureViewport(0, 0, width, height).rgbaByteSize();
    }

    /** Byte size of a frame at this grabber's configured dimensions. */
    int frameByteSize() {
        return frameByteSize(width, height);
    }

    /**
     * Grabs into this grabber's own reusable buffers, which is exactly the
     * producer-side reuse {@link CapturedFrame}'s defensive copy exists to make
     * safe — the returned array is valid only until the next {@code grab()}.
     * <p>
     * Allocating both buffers per call instead cost a native malloc/free plus a
     * full-frame heap array every frame, on top of the copy {@code CapturedFrame}
     * already makes: at a 1280x896 window that was ~9MB of garbage per frame,
     * roughly half a gigabyte a second at 60fps, and it grew with the window.
     * The buffers are sized once from the fixed viewport this grabber was built
     * for; a viewport change builds a new grabber.
     */
    @Override
    public byte[] grab() {
        if (frameBytes == null) frameBytes = new byte[frameByteSize()];
        grabInto(frameBytes);
        return frameBytes;
    }

    @Override
    public void grabInto(byte[] target) {
        if (released) throw new IllegalStateException("grabber closed");
        if (target.length != frameByteSize()) throw new IllegalArgumentException("wrong pixel buffer size");
        glReadRegion.read(x, y, width, height, readBuffer);
        readBuffer.clear();
        readBuffer.get(target);  // tight copy, bottom-up as GL provides
        readBuffer.clear();
    }

    /** Releases the native read buffer. Safe to call more than once. */
    @Override
    public void close() {
        if (!released) {
            released = true;
            MemoryUtil.memFree(readBuffer);
        }
    }

    private static void readBackBufferRegion(int x, int y, int width, int height,
                                             ByteBuffer target) {
        glReadBuffer(GL_BACK);
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, target);
    }

    @FunctionalInterface
    interface GlReadRegion {
        void read(int x, int y, int width, int height, ByteBuffer target);
    }
}
