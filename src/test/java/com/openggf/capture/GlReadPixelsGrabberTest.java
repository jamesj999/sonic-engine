package com.openggf.capture;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.junit.jupiter.api.Assertions.*;

class GlReadPixelsGrabberTest {
    @Test
    void reportsConfiguredDimensions() {
        GlReadPixelsGrabber g = new GlReadPixelsGrabber(320, 224);
        assertEquals(320, g.width());
        assertEquals(224, g.height());
    }

    @Test
    void framesAreSizedAsRgba8888() {
        GlReadPixelsGrabber g = new GlReadPixelsGrabber(320, 224);
        // RGBA8888 -> 4 bytes per pixel; grab() allocates both the read buffer
        // and the returned array from exactly this size.
        assertEquals(320 * 224 * 4, g.frameByteSize());

        // Sizing must scale with the configured dimensions, not a fixed buffer.
        GlReadPixelsGrabber small = new GlReadPixelsGrabber(8, 4);
        assertEquals(8 * 4 * 4, small.frameByteSize());
        assertEquals(small.width() * small.height() * 4, small.frameByteSize());
    }

    @Test
    void forwardsConfiguredRegionUnchanged() {
        int[] forwarded = new int[4];
        GlReadPixelsGrabber g = new GlReadPixelsGrabber(13, 17, 320, 224,
                (x, y, width, height, target) -> {
                    forwarded[0] = x;
                    forwarded[1] = y;
                    forwarded[2] = width;
                    forwarded[3] = height;
                });

        assertEquals(320 * 224 * 4, g.grab().length);
        assertArrayEquals(new int[]{13, 17, 320, 224}, forwarded);
    }

    @Test
    void repeatedGrabsReuseOneBufferAndRefreshItsContents() {
        byte[] fill = {1};
        GlReadPixelsGrabber g = new GlReadPixelsGrabber(0, 0, 2, 1,
                (x, y, width, height, target) -> {
                    target.clear();
                    for (int i = 0; i < width * height * 4; i++) {
                        target.put(fill[0]);
                    }
                });

        byte[] first = g.grab();
        assertEquals(1, first[0]);

        fill[0] = 2;
        byte[] second = g.grab();

        assertSame(first, second,
                "the grab buffer is reused; CapturedFrame's copy is what makes that safe");
        assertEquals(2, second[0], "a reused buffer must still be refilled each grab");
        g.close();
    }

    @Test
    void closeIsIdempotent() {
        GlReadPixelsGrabber g = new GlReadPixelsGrabber(0, 0, 2, 1,
                (x, y, width, height, target) -> { });
        g.close();
        assertDoesNotThrow(g::close, "a double free of the native buffer would crash the JVM");
    }

    @Test
    void grabsOnlyNonZeroOriginViewportFromOffscreenBackBuffer() {
        long window = createOffscreenContextOrAbort();
        try {
            glDisable(GL_SCISSOR_TEST);
            glClearColor(1f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);

            glEnable(GL_SCISSOR_TEST);
            glScissor(5, 4, 16, 12);
            glClearColor(0f, 1f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
            glDisable(GL_SCISSOR_TEST);
            glFinish();

            byte[] pixels = new GlReadPixelsGrabber(5, 4, 16, 12).grab();
            assertRgba(pixels, 0, 0, 255, 0, 255);
            assertRgba(pixels, pixels.length - 4, 0, 255, 0, 255);
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    @Test
    void pboReadbackPreservesBothFramesAndExternalPackBinding() {
        long window = createOffscreenContextOrAbort();
        try {
            Assumptions.assumeTrue(org.lwjgl.opengl.GL.getCapabilities().OpenGL21);
            int external = org.lwjgl.opengl.GL15.glGenBuffers();
            try (VideoFrameGrabber grabber = GlPboFrameGrabber.create(new CaptureViewport(5, 4, 16, 12))) {
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, external);
                glClearColor(1f, 0f, 0f, 1f);
                glClear(GL_COLOR_BUFFER_BIT);
                grabber.beginReadback();
                glClearColor(0f, 1f, 0f, 1f);
                glClear(GL_COLOR_BUFFER_BIT);
                grabber.beginReadback();
                byte[] pixels = new byte[16 * 12 * 4];
                grabber.grabInto(pixels);
                assertRgba(pixels, 0, 255, 0, 0, 255);
                assertRgba(pixels, pixels.length - 4, 255, 0, 0, 255);
                grabber.grabInto(pixels);
                assertRgba(pixels, 0, 0, 255, 0, 255);
                assertEquals(external, glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING));
            } finally {
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
                org.lwjgl.opengl.GL15.glDeleteBuffers(external);
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static long createOffscreenContextOrAbort() {
        boolean initialized;
        try {
            initialized = glfwInit();
        } catch (Throwable unavailable) {
            Assumptions.abort("GLFW is unavailable: " + unavailable.getMessage());
            return NULL;
        }
        Assumptions.assumeTrue(initialized, "GLFW could not initialize");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(32, 24, "GlReadPixelsGrabberTest", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            Assumptions.abort("An offscreen OpenGL context could not be created");
        }
        try {
            glfwMakeContextCurrent(window);
            org.lwjgl.opengl.GL.createCapabilities();
            return window;
        } catch (Throwable unavailable) {
            glfwDestroyWindow(window);
            glfwTerminate();
            Assumptions.abort("OpenGL is unavailable: " + unavailable.getMessage());
            return NULL;
        }
    }

    private static void assertRgba(byte[] pixels, int offset,
                                   int red, int green, int blue, int alpha) {
        assertEquals(red, Byte.toUnsignedInt(pixels[offset]));
        assertEquals(green, Byte.toUnsignedInt(pixels[offset + 1]));
        assertEquals(blue, Byte.toUnsignedInt(pixels[offset + 2]));
        assertEquals(alpha, Byte.toUnsignedInt(pixels[offset + 3]));
    }
}
