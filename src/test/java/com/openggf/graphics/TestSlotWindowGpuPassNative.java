package com.openggf.graphics;

import com.openggf.data.Rom;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineManager;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineRenderer;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotMachineDisplayState;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotMachineRenderer;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.mockito.Mockito.*;

/** Opt-in native ROM rendering: requires OpenGL 4.1 and absolute S2/S3K ROM properties. */
@EnabledIfSystemProperty(named = "openggf.slotNative", matches = "true")
class TestSlotWindowGpuPassNative {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pixelsAndDrawStateSurviveResizeCleanupAndContextRecreation(boolean s3k) throws Exception {
        var cnz = new CNZSlotMachineRenderer();
        var slots = new S3kSlotMachineRenderer();
        var manager = mock(CNZSlotMachineManager.class);
        for (int i = 0; i < 3; i++) {
            when(manager.getSlotFace(i)).thenReturn(i);
            when(manager.getSlotNextFace(i)).thenReturn(i + 3);
            when(manager.getSlotOffset(i)).thenReturn(i * 96);
        }
        var state = new S3kSlotMachineDisplayState(176, 120,
                new int[] {0, 1, 2}, new int[] {3, 4, 5}, new float[] {0, .375f, .75f});
        String[] firstContext = new String[2];
        try (Rom rom = new Rom()) {
            String path = System.getProperty(s3k ? "s3k.rom.path" : "sonic2.rom.path");
            assertNotNull(path, "native verification requires an explicit ROM path");
            assertTrue(rom.open(path));
            for (int cycle = 0; cycle < 2; cycle++) {
                assertTrue(glfwInit(), "native GLFW initialization");
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
                long window = glfwCreateWindow(64, 64, "SlotWindowNative", 0, 0);
                assertNotEquals(0, window, "OpenGL 4.1 core context");
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                int framebuffer = glGenFramebuffers();
                int color = texture(WIDTH, HEIGHT, null);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
                assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
                int palette = palette();
                ShaderProgram shader = null;
                try {
                    if (s3k) {
                        slots.init(rom);
                        assertTrue(slots.isInitialized());
                    } else {
                        shader = new ShaderProgram(ShaderProgram.FULLSCREEN_VERTEX_SHADER,
                                "shaders/shader_cnz_slots.glsl");
                        cnz.setShader(shader);
                        cnz.init(rom);
                        assertTrue(cnz.isInitialized());
                    }
                    int[][] viewports = {{0, 0, 320, 224}, {31, 23, 640, 448}};
                    for (int v = 0; v < viewports.length; v++) {
                        int[] vp = viewports[v];
                        glViewport(vp[0], vp[1], vp[2], vp[3]);
                        glClearColor(.01f, .02f, .03f, 1);
                        glClear(GL_COLOR_BUFFER_BIT);
                        glDisable(GL_BLEND);
                        glEnable(GL_DEPTH_TEST);
                        GLCommandable command = s3k
                                ? slots.createRenderCommand(state, 0, 0, palette)
                                : cnz.createRenderCommand(manager, 64, 64, palette, 0, 0);
                        assertNotNull(command);
                        command.execute(0, 0, 320, 224);
                        assertFalse(glIsEnabled(GL_BLEND));
                        assertTrue(glIsEnabled(GL_DEPTH_TEST));
                        assertEquals(GL_TEXTURE0, glGetInteger(GL_ACTIVE_TEXTURE));
                        assertEquals(0, glGetInteger(GL_CURRENT_PROGRAM));
                        assertEquals(0, glGetInteger(GL_VERTEX_ARRAY_BINDING));
                        assertEquals(0, glGetInteger(GL_ARRAY_BUFFER_BINDING));
                        assertEquals(GL_NO_ERROR, glGetError(), "draw after context recreation");
                        String hash = pixels();
                        if (cycle == 0) firstContext[v] = hash;
                        else assertEquals(firstContext[v], hash, "same pixels in the recreated context");
                        String dump = System.getProperty("openggf.slotNative.dumpDirectory");
                        if (dump != null) {
                            Path output = Path.of(dump);
                            Files.createDirectories(output);
                            Files.writeString(output.resolve((s3k ? "s3k" : "cnz") + "-" + cycle + "-" + v + ".sha256"), hash);
                        }
                    }
                } finally {
                    if (s3k) slots.cleanup(); else cnz.cleanup();
                    if (shader != null) shader.cleanup();
                    glDeleteTextures(palette);
                    glDeleteTextures(color);
                    glDeleteFramebuffers(framebuffer);
                    glfwMakeContextCurrent(0);
                    GL.setCapabilities(null);
                    glfwDestroyWindow(window);
                    glfwTerminate();
                }
            }
        }
    }

    private static int texture(int width, int height, ByteBuffer pixels) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glBindTexture(GL_TEXTURE_2D, 0);
        return texture;
    }

    private static int palette() {
        ByteBuffer pixels = MemoryUtil.memAlloc(16 * 4 * 4);
        try {
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 16; column++) {
                    pixels.put((byte) (column * 13)).put((byte) (255 - column * 11))
                            .put((byte) (column * 7)).put((byte) 255);
                }
            }
            pixels.flip();
            return texture(16, 4, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static String pixels() throws Exception {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] bytes = new byte[pixels.remaining()];
            pixels.get(bytes);
            int colored = 0;
            for (int i = 4; i < bytes.length; i += 4) {
                if (bytes[i] != bytes[0] || bytes[i + 1] != bytes[1] || bytes[i + 2] != bytes[2]) colored++;
            }
            assertTrue(colored > 100, "ROM slot faces must draw visible pixels");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
