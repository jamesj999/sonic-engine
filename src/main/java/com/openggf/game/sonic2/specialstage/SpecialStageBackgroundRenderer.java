package com.openggf.game.sonic2.specialstage;

import com.openggf.Engine;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.HScrollBuffer;
import com.openggf.graphics.ParallaxShaderProgram;
import com.openggf.graphics.QuadRenderer;
import com.openggf.util.FboHelper;

import java.io.IOException;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * GPU-based background renderer for Special Stage.
 *
 * Implements the two-pass rendering approach:
 * 1. Render background tiles to an FBO (256x256 texture)
 * 2. Draw fullscreen quad with shader applying per-scanline H-scroll and H32
 * clipping
 *
 * This emulates the Mega Drive VDP behavior where:
 * - H-scroll table provides per-scanline horizontal scroll offsets
 * - H32 mode displays only 256 pixels centered on the 320-pixel screen
 * - V-scroll provides vertical parallax during rise/drop animations
 */
public class SpecialStageBackgroundRenderer {

    private static final Logger LOGGER = Logger.getLogger(SpecialStageBackgroundRenderer.class.getName());

    // FBO dimensions - background is 32x32 tiles = 256x256 pixels
    private static final int FBO_WIDTH = 256;
    private static final int FBO_HEIGHT = 256;

    // Screen dimensions
    public static final int SCREEN_WIDTH = 320;
    public static final int SCREEN_HEIGHT = 224;
    public static final int H32_WIDTH = 256;
    public static final int H32_OFFSET = (SCREEN_WIDTH - H32_WIDTH) / 2; // 32 pixels

    // OpenGL resources
    private FboHelper.FboHandle fboHandle;
    private int fboId = -1;
    private int fboTextureId = -1;
    private int fboDepthId = -1;

    // Shader and scroll buffer
    private ParallaxShaderProgram shader;
    private HScrollBuffer hScrollBuffer;
    private final QuadRenderer quadRenderer = new QuadRenderer();

    // Per-scanline scroll data (224 entries)
    private final int[] hScrollData = new int[SCREEN_HEIGHT];

    // State
    private boolean initialized = false;
    private final int[] savedViewport = new int[4];
    private final int[] shaderViewport = new int[4];
    private final GraphicsManager graphicsManager;

    public SpecialStageBackgroundRenderer(GraphicsManager graphicsManager) {
        this.graphicsManager = java.util.Objects.requireNonNull(graphicsManager, "graphicsManager");
    }

    /**
     * Initialize the renderer with FBO and shader.
     *
     * @throws IOException if shader loading fails
     */
    public void init() throws IOException {
        if (initialized) {
            return;
        }
        // Headless mode has no GL context: skip all GL resource creation and
        // leave the renderer un-initialised. Every draw entry point below already
        // guards on `initialized`, so the shared special-stage runtime can boot
        // and run its game logic (physics, object state) without rendering.
        // Mirrors Sonic1SpecialStageManager's headless renderer skip.
        if (graphicsManager.isHeadlessMode()) {
            LOGGER.fine("Skipping SpecialStageBackgroundRenderer GL init in headless mode");
            return;
        }

        // Create FBO for background tile rendering
        createFBO();

        // Initialize H-scroll buffer
        hScrollBuffer = new HScrollBuffer();
        hScrollBuffer.init();

        // Load special stage background shader
        shader = new ParallaxShaderProgram("shaders/shader_ss_background.glsl");
        shader.cacheUniformLocations();
        quadRenderer.init();

        // Initialize H-scroll data to zero
        for (int i = 0; i < SCREEN_HEIGHT; i++) {
            hScrollData[i] = 0;
        }

        initialized = true;
        LOGGER.info("SpecialStageBackgroundRenderer initialized");
    }

    /**
     * Create the framebuffer object for tile rendering.
     */
    private void createFBO() {
        fboHandle = FboHelper.createWithDepth(FBO_WIDTH, FBO_HEIGHT, GL_REPEAT);
        fboId = fboHandle.fboId();
        fboTextureId = fboHandle.textureId();
        fboDepthId = fboHandle.depthId();
        LOGGER.fine("Created FBO " + FBO_WIDTH + "x" + FBO_HEIGHT + " for special stage background");
    }

    /**
     * Sets up FBO projection mode for coordinate calculations.
     * Call this BEFORE creating the pattern batch so that Y coordinates
     * are calculated correctly for the 256x256 FBO.
     *
     * This method only updates the projection state - it does not perform
     * any GL operations. Call beginTilePassGL() for the actual GL setup.
     */
    public void beginFBOProjection() {
        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.beginFBOProjection(FBO_WIDTH, FBO_HEIGHT);
        }
    }

    /**
     * Restores normal screen projection after FBO pattern batch creation.
     * Call this AFTER flushing the pattern batch.
     */
    public void endFBOProjection() {
        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.endFBOProjection();
        }
    }

    /**
     * Begin the tile rendering pass - bind FBO and set up viewport.
     * After calling this, render background tiles using the normal tile renderer.
     *
     * Note: Call beginFBOProjection() BEFORE creating the pattern batch to ensure
     * correct coordinate calculations. This method handles GL state setup.
     *
     * @param displayHeight The display height used by pattern renderer for Y-flip
     */
    public void beginTilePass(int displayHeight) {
        if (!initialized)
            return;

        // Save current viewport to restore later (must query actual GL state)
        glGetIntegerv(GL_VIEWPORT, savedViewport);

        // Bind FBO
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);

        // Re-enable FBO projection for the batch command execution
        // (The batch command reads the projection matrix when it executes)
        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.beginFBOProjection(FBO_WIDTH, FBO_HEIGHT);
        }

        // Clear FBO with transparent black
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    /**
     * End the tile rendering pass - unbind FBO and restore viewport.
     */
    public void endTilePass() {
        if (!initialized)
            return;

        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // Restore normal projection mode
        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.endFBOProjection();
        }

        // Restore viewport
        FboHelper.restoreViewport(savedViewport);
    }

    /**
     * Render the background with per-scanline scrolling using the shader.
     *
     * @param vScrollBG Vertical scroll offset for parallax
     */
    public void renderWithShader(float vScrollBG) {
        if (!initialized)
            return;

        // Upload H-scroll data to GPU
        hScrollBuffer.upload(hScrollData);

        // Bind 1D sampler texture before shader use; macOS may validate samplers
        // at program-use time.
        hScrollBuffer.bind(1);

        // Bind shader
        shader.use();
        shader.cacheUniformLocations();

        // Set texture units
        shader.setBackgroundTexture(0); // FBO texture
        shader.setHScrollTexture(1); // H-scroll table

        // Get actual viewport dimensions for resolution independence
        glGetIntegerv(GL_VIEWPORT, shaderViewport);
        float realWidth = (float) shaderViewport[2];
        float realHeight = (float) shaderViewport[3];

        // Set shader uniforms
        shader.setScreenDimensions(realWidth, realHeight);
        shader.setActiveDisplayWidth((float) H32_WIDTH);
        shader.setBGTextureDimensions(FBO_WIDTH, FBO_HEIGHT);
        shader.setVScrollBG(vScrollBG);
        shader.setViewportOffset((float) shaderViewport[0], (float) shaderViewport[1]);

        // Bind textures
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboTextureId);

        // Draw fullscreen quad
        drawFullscreenQuad();

        // Cleanup
        shader.stop();
        hScrollBuffer.unbind(1);
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * Draw a fullscreen quad covering the entire screen.
     * The shader handles H32 clipping internally.
     * Note: The shader uses gl_FragCoord for positioning,
     * so no projection matrix is needed.
     */
    private void drawFullscreenQuad() {
        quadRenderer.draw(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
    }

    /**
     * Set the horizontal scroll value for all scanlines uniformly.
     *
     * @param scroll The scroll value in pixels
     */
    public void setUniformHScroll(int scroll) {
        for (int i = 0; i < SCREEN_HEIGHT; i++) {
            hScrollData[i] = scroll;
        }
    }

    /**
     * Set the horizontal scroll value for a specific scanline.
     *
     * @param scanline The scanline index (0-223)
     * @param scroll   The scroll value in pixels
     */
    public void setHScroll(int scanline, int scroll) {
        if (scanline >= 0 && scanline < SCREEN_HEIGHT) {
            hScrollData[scanline] = scroll;
        }
    }

    /**
     * Apply a delta to all scanlines' H-scroll values.
     * Used for the per-frame parallax scroll update.
     *
     * @param delta The value to add to each scanline's scroll
     */
    public void addHScrollDelta(int delta) {
        for (int i = 0; i < SCREEN_HEIGHT; i++) {
            hScrollData[i] += delta;
        }
    }

    /**
     * Check if renderer is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the FBO texture ID (for debugging).
     */
    public int getFBOTextureId() {
        return fboTextureId;
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        // Nothing was allocated in headless mode (init() short-circuits), so
        // there are no GL resources to release and no GL context to call into.
        if (graphicsManager.isHeadlessMode()) {
            initialized = false;
            return;
        }
        if (hScrollBuffer != null) {
            hScrollBuffer.cleanup();
            hScrollBuffer = null;
        }
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
        quadRenderer.cleanup();
        FboHelper.destroy(fboHandle);
        fboHandle = null;
        fboId = -1;
        fboTextureId = -1;
        fboDepthId = -1;
        initialized = false;
        LOGGER.info("SpecialStageBackgroundRenderer cleaned up");
    }
}
