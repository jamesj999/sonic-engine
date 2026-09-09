package com.openggf.game.sonic2.slotmachine;

import com.openggf.data.Rom;
import com.openggf.graphics.ShaderProgram;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class TestCNZSlotMachineViewport {
    @Test
    void reelsStayAtGameCoordinatesAcrossViewportChanges() throws Exception {
        assumeTrue(glfwInit(), "GLFW unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(960, 800, "CNZ viewport regression", 0, 0);
        try {
            assumeTrue(window != 0, "OpenGL 4.1 unavailable");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            ShaderProgram shader = new ShaderProgram(ShaderProgram.FULLSCREEN_VERTEX_SHADER,
                    "shaders/shader_cnz_slots.glsl");
            CNZSlotMachineRenderer renderer = new CNZSlotMachineRenderer();
            renderer.setShader(shader);
            Rom rom = mock(Rom.class);
            when(rom.readBytes(anyLong(), anyInt())).thenAnswer(call -> {
                byte[] pixels = new byte[call.getArgument(1, Integer.class)];
                Arrays.fill(pixels, (byte) 0x11);
                return pixels;
            });
            renderer.init(rom);
            int palette = glGenTextures();
            ByteBuffer white = MemoryUtil.memAlloc(4);
            white.putInt(-1).flip();
            glBindTexture(GL_TEXTURE_2D, palette);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
            MemoryUtil.memFree(white);
            ByteBuffer pixels = MemoryUtil.memAlloc(960 * 800 * 4);
            try {
                // Native, pillarboxed, letterboxed, fractional scale, then back to native.
                int[][] viewports = {{0, 0, 320, 224}, {160, 0, 640, 448},
                        {0, 150, 640, 448}, {103, 57, 704, 493}, {0, 0, 320, 224}};
                for (int[] v : viewports) {
                    glViewport(v[0], v[1], v[2], v[3]);
                    glClearColor(0, 0, 0, 1);
                    glClear(GL_COLOR_BUFFER_BIT);
                    renderer.createRenderCommand(new CNZSlotMachineManager(), 80, 60,
                            palette, 0, 0).execute(0, 0, 320, 224);
                    glReadPixels(0, 0, 960, 800, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    // Check well inside and outside each display edge, avoiding rounding boundaries.
                    for (int y : new int[]{58, 62, 90, 94}) {
                        for (int x : new int[]{78, 82, 110, 114, 142, 146, 174, 178}) {
                            int px = v[0] + (int) ((x + 0.5) * v[2] / 320);
                            int py = v[1] + (int) ((224 - y - 0.5) * v[3] / 224);
                            int expected = x >= 80 && x < 176 && y >= 60 && y < 92 ? 255 : 0;
                            assertEquals(expected, Byte.toUnsignedInt(pixels.get((py * 960 + px) * 4)),
                                    "viewport=" + Arrays.toString(v) + " game pixel=" + x + "," + y);
                        }
                    }
                }
            } finally {
                MemoryUtil.memFree(pixels);
                renderer.cleanup();
                glDeleteTextures(palette);
                glDeleteProgram(shader.getProgramId());
            }
        } finally {
            glfwMakeContextCurrent(0);
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
}
