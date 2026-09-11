package com.openggf.graphics;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;

/** Pixel checks for the shared VDP window path, using Mesa's display-free EGL platform when available. */
class TestForegroundWindowRendering {
    @Test
    void windowReplacesScrolledForegroundAndItsPriorityMask() throws Exception {
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err);
        GLFWErrorCallback previous = glfwSetErrorCallback(callback);
        long context = 0;
        boolean initialized = false;
        TilemapGpuRenderer renderer = null;
        int atlas = 0;
        int palette = 0;
        try {
            boolean nativeDisplay = Boolean.getBoolean("openggf.test.gl.native");
            glfwInitHint(GLFW_PLATFORM, nativeDisplay ? GLFW_ANY_PLATFORM : GLFW_PLATFORM_NULL);
            initialized = glfwInit();
            assumeTrue(initialized, "GLFW null platform unavailable");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            if (!nativeDisplay) glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
            glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            context = glfwCreateWindow(64, 64, "window parity", 0, 0);
            assumeTrue(context != 0, "Surfaceless EGL unavailable (try EGL_PLATFORM=surfaceless)");
            glfwMakeContextCurrent(context);
            GL.createCapabilities();
            glViewport(0, 0, 64, 64);
            renderer = new TilemapGpuRenderer();
            renderer.init("shaders/shader_tilemap.glsl");
            int[] plane = new int[64];
            Arrays.fill(plane, 1);
            Arrays.fill(plane, 5 * 8, 6 * 8, 3);
            renderer.setTilemapData(TilemapGpuRenderer.Layer.FOREGROUND,
                    TilemapGpuRenderer.packWindowDescriptors(plane), 8, 8);
            renderer.setPatternLookupData(new byte[]{0, 0, 0, -1, 1, 0, 0, -1,
                    2, 0, 0, -1, 3, 0, 0, -1}, 4);
            byte[] atlasPixels = new byte[32 * 8 * 4];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 32; x++) {
                    atlasPixels[(y * 32 + x) * 4] = (byte) (x / 8);
                    atlasPixels[(y * 32 + x) * 4 + 3] = -1;
                }
            }
            atlas = texture(atlasPixels, 32, 8);
            byte[] colors = new byte[16 * 4 * 4];
            for (int line = 0; line < 4; line++) {
                colors[(line * 16 + 1) * 4] = -1;
                colors[(line * 16 + 2) * 4 + 1] = -1;
                colors[(line * 16 + 3) * 4 + 2] = -1;
                for (int index = 0; index < 16; index++) {
                    colors[(line * 16 + index) * 4 + 3] = -1;
                }
            }
            palette = texture(colors, 16, 4);
            int[] descriptors = new int[64];
            Arrays.fill(descriptors, 0x8002);
            descriptors[5 * 8] = 0; // Transparent window cell must reveal the backdrop, not Plane A.
            ForegroundWindow window = new ForegroundWindow(8, 8, 40, descriptors);
            for (int offset : new int[]{0, 8, 32}) {
                glClearColor(0, 0, 0, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                renderer.setForegroundWindow(window);
                draw(renderer, atlas, palette, offset, -1, false);
                assertPixel(12, 44, 0, 255, 0);
                assertPixel(4, 44, 0, 0, 0);
                if (offset == 8) {
                    assertPixel(12, 36, 0, 0, 255);
                } else {
                    assertPixel(12, 36, 255, 0, 0);
                }
            }
            glClear(GL_COLOR_BUFFER_BIT);
            renderer.setForegroundWindow(window);
            draw(renderer, atlas, palette, 32, 1, true);
            assertPixel(12, 44, 255, 0, 0);
            assertPixel(4, 44, 0, 0, 0);
            assertPixel(12, 36, 0, 0, 0);

            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_SCISSOR_TEST);
            glScissor(8, 8, 16, 48);
            renderer.setForegroundWindow(window);
            draw(renderer, atlas, palette, 8, -1, false);
            assertPixel(12, 44, 0, 255, 0);
            assertPixel(28, 44, 0, 0, 0);
            int[] box = new int[4];
            glGetIntegerv(GL_SCISSOR_BOX, box);
            assertArrayEquals(new int[]{8, 8, 16, 48}, box);
            assertTrue(glIsEnabled(GL_SCISSOR_TEST));
            glDisable(GL_SCISSOR_TEST);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            if (renderer != null) renderer.cleanup();
            if (atlas != 0) glDeleteTextures(atlas);
            if (palette != 0) glDeleteTextures(palette);
            if (context != 0) {
                GL.setCapabilities(null);
                glfwDestroyWindow(context);
            }
            if (initialized) glfwTerminate();
            glfwInitHint(GLFW_PLATFORM, GLFW_ANY_PLATFORM);
            glfwSetErrorCallback(previous);
            callback.free();
        }
    }

    private static void draw(TilemapGpuRenderer renderer, int atlas, int palette, int y, int priority, boolean mask) {
        renderer.render(TilemapGpuRenderer.Layer.FOREGROUND, 64, 64, 0, 0, 64, 64,
                0, y, 32, 8, atlas, palette, palette, priority, true, mask, false, 64);
    }

    private static int texture(byte[] pixels, int width, int height) {
        glActiveTexture(GL_TEXTURE0);
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        ByteBuffer data = MemoryUtil.memAlloc(pixels.length);
        try {
            data.put(pixels).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        } finally {
            MemoryUtil.memFree(data);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    private static void assertPixel(int x, int yFromTop, int red, int green, int blue) {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            glReadPixels(x, 63 - yFromTop, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            assertArrayEquals(new int[]{red, green, blue},
                    new int[]{pixel.get(0) & 255, pixel.get(1) & 255, pixel.get(2) & 255},
                    "pixel (" + x + "," + yFromTop + ")");
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }
}
