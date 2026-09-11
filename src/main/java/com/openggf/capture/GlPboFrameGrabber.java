package com.openggf.capture;

import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;

/**
 * Two ordered pixel-pack buffers allow the GPU to read frame N while the game
 * renders frame N+1. Mapping the oldest buffer still waits if the GPU falls
 * behind; no frame is discarded. All methods, including close, require the GL thread.
 */
public final class GlPboFrameGrabber implements VideoFrameGrabber {
    private final CaptureViewport viewport;
    private final Backend backend;
    private final int[] buffers = new int[2];
    private int oldest;
    private int pending;
    private boolean closed;

    public static VideoFrameGrabber create(CaptureViewport viewport) {
        if (!GL.getCapabilities().OpenGL21) {
            return new GlReadPixelsGrabber(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        }
        return new GlPboFrameGrabber(viewport, new GlBackend());
    }

    GlPboFrameGrabber(CaptureViewport viewport, Backend backend) {
        this.viewport = viewport;
        this.backend = backend;
        try {
            for (int i = 0; i < buffers.length; i++) buffers[i] = backend.create(viewport.rgbaByteSize());
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    @Override public int width() { return viewport.width(); }
    @Override public int height() { return viewport.height(); }
    @Override public boolean deferredReadback() { return true; }

    @Override public void beginReadback() {
        if (closed) throw new IllegalStateException("grabber closed");
        if (pending == buffers.length) throw new IllegalStateException("readback buffers full");
        backend.read(buffers[(oldest + pending) % buffers.length], viewport);
        pending++;
    }

    /** Synchronous convenience API for callers outside the deferred live pipeline. */
    @Override public byte[] grab() {
        if (pending != 0) throw new IllegalStateException("deferred readback pending");
        beginReadback();
        byte[] pixels = new byte[viewport.rgbaByteSize()];
        grabInto(pixels);
        return pixels;
    }

    @Override public void grabInto(byte[] target) {
        if (closed) throw new IllegalStateException("grabber closed");
        if (pending == 0) throw new IllegalStateException("no pending readback");
        if (target.length != viewport.rgbaByteSize()) throw new IllegalArgumentException("wrong pixel buffer size");
        backend.copy(buffers[oldest], target);
        oldest = (oldest + 1) % buffers.length;
        pending--;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (int buffer : buffers) if (buffer != 0) backend.delete(buffer);
        pending = 0;
    }

    interface Backend {
        int create(int bytes);
        void read(int buffer, CaptureViewport viewport);
        void copy(int buffer, byte[] target);
        void delete(int buffer);
    }

    private static final class GlBackend implements Backend {
        @Override public int create(int bytes) {
            int previous = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            int buffer = glGenBuffers();
            try {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
                glBufferData(GL_PIXEL_PACK_BUFFER, bytes, GL_STREAM_READ);
                return buffer;
            } catch (Throwable failure) {
                glDeleteBuffers(buffer);
                throw failure;
            } finally {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, previous);
            }
        }

        @Override public void read(int buffer, CaptureViewport viewport) {
            int previous = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            int previousRead = glGetInteger(GL_READ_BUFFER);
            try {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
                glReadBuffer(GL_BACK);
                glReadPixels(viewport.x(), viewport.y(), viewport.width(), viewport.height(),
                        GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            } finally {
                glReadBuffer(previousRead);
                glBindBuffer(GL_PIXEL_PACK_BUFFER, previous);
            }
        }

        @Override public void copy(int buffer, byte[] target) {
            int previous = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
                ByteBuffer mapped = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY, target.length, null);
                if (mapped == null) throw new IllegalStateException("pixel-pack buffer mapping failed");
                boolean intact;
                try {
                    mapped.get(target);
                } finally {
                    intact = glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                }
                if (!intact) throw new IllegalStateException("pixel-pack buffer contents lost");
            } finally {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, previous);
            }
        }

        @Override public void delete(int buffer) { glDeleteBuffers(buffer); }
    }
}
