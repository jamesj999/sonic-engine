package com.openggf.graphics;

import com.openggf.util.FboHelper;

import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Framebuffer object for rendering tile priority information.
 *
 * This FBO captures high-priority foreground tiles to a texture that can be
 * sampled by the sprite priority shader. The shader uses this texture to
 * determine if low-priority sprites should be hidden behind high-priority tiles.
 *
 * The FBO stores priority in the red channel:
 * - R = 0.0: No high-priority tile at this pixel
 * - R = 1.0: High-priority tile present at this pixel
 */
public class TilePriorityFBO {

    private static final Logger LOGGER = Logger.getLogger(TilePriorityFBO.class.getName());

    private FboHelper.FboHandle fboHandle;
    private int fboId = -1;
    private int textureId = -1;
    private int width;
    private int height;
    private boolean initialized = false;
    private int[] savedViewport;

    /**
     * Initialize the tile priority FBO.
     *
     * @param width  FBO width in pixels (should match screen width)
     * @param height FBO height in pixels (should match screen height)
     */
    public void init(int width, int height) {
        if (initialized) {
            cleanup();
        }

        this.width = width;
        this.height = height;

        // Create colour-only FBO via shared helper
        fboHandle = FboHelper.createColorOnly(width, height, GL_CLAMP_TO_EDGE);
        if (fboHandle == null) {
            fboId = -1;
            textureId = -1;
            initialized = false;
            LOGGER.warning("TilePriorityFBO initialization failed; priority rendering disabled");
            return;
        }
        fboId = fboHandle.fboId();
        textureId = fboHandle.textureId();

        LOGGER.info("TilePriorityFBO FBO is complete. FBO ID=" + fboId + ", Texture ID=" + textureId);

        // Ensure texture is fully complete for macOS driver compatibility
        // by doing a sync and verifying the texture state
        glFinish();

        initialized = true;

        LOGGER.info("TilePriorityFBO initialized: " + width + "x" + height);
    }

    /**
     * Begin rendering to the tile priority FBO.
     * Call this before rendering high-priority foreground tiles.
     */
    public void begin() {
        if (!initialized) {
            return;
        }

        // Save current viewport
        savedViewport = FboHelper.saveViewport();

        // Bind FBO and set viewport
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, width, height);

        // Clear to 0 (no high-priority tiles)
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    /**
     * End rendering to the tile priority FBO.
     * Call this after rendering high-priority foreground tiles.
     */
    public void end() {
        if (!initialized) {
            return;
        }

        // Restore framebuffer and viewport
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        FboHelper.restoreViewport(savedViewport);
    }

    /**
     * Get the texture ID for sampling the priority buffer.
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Check if the FBO is initialized and ready for use.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the FBO width in pixels.
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get the FBO height in pixels.
     */
    public int getHeight() {
        return height;
    }

    /**
     * Resize the FBO if screen dimensions change.
     */
    public void resize(int width, int height) {
        if (this.width == width && this.height == height) {
            return;
        }

        // Re-initialize with new dimensions
        init(width, height);
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        FboHelper.destroy(fboHandle);
        fboHandle = null;
        fboId = -1;
        textureId = -1;
        initialized = false;
    }
}
