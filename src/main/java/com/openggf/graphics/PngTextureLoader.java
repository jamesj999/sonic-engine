package com.openggf.graphics;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Utility for loading PNG images from the classpath into OpenGL textures.
 * Uses STBImage without any AWT dependency, with RGBA format and GL_NEAREST filtering.
 */
public class PngTextureLoader {

    private static int lastWidth;
    private static int lastHeight;

    /**
     * Loads a PNG from the classpath and creates an OpenGL texture.
     *
     * @param resourcePath classpath path (e.g. "titlescreen/background.png")
     * @return OpenGL texture ID
     * @throws IOException if the resource cannot be found or read
     */
    public static int loadTexture(String resourcePath) throws IOException {
        // Read the entire resource into a direct ByteBuffer for STBImage
        byte[] bytes;
        try (InputStream is = PngTextureLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("PNG resource not found: " + resourcePath);
            }
            bytes = is.readAllBytes();
        }

        ByteBuffer rawBuf = MemoryUtil.memAlloc(bytes.length);
        rawBuf.put(bytes).flip();

        int width, height;
        ByteBuffer pixels;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            IntBuffer pChannels = stack.mallocInt(1);

            // STBImage loads with origin at top-left; we flip for OpenGL below
            STBImage.stbi_set_flip_vertically_on_load(true);
            pixels = STBImage.stbi_load_from_memory(rawBuf, pWidth, pHeight, pChannels, 4);
            if (pixels == null) {
                MemoryUtil.memFree(rawBuf);
                throw new IOException("Failed to decode PNG: " + resourcePath
                        + " (" + STBImage.stbi_failure_reason() + ")");
            }
            width = pWidth.get(0);
            height = pHeight.get(0);
        }
        MemoryUtil.memFree(rawBuf);

        lastWidth = width;
        lastHeight = height;

        // pixels is already RGBA with Y-flipped — upload directly
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        STBImage.stbi_image_free(pixels);

        return textureId;
    }

    public static int getLastWidth() {
        return lastWidth;
    }

    public static int getLastHeight() {
        return lastHeight;
    }

    public static void deleteTexture(int textureId) {
        if (textureId != 0) {
            glDeleteTextures(textureId);
        }
    }
}
