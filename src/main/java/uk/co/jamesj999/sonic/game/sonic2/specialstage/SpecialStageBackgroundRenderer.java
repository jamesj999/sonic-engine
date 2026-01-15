package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.graphics.HScrollBuffer;
import uk.co.jamesj999.sonic.graphics.ParallaxShaderProgram;

import java.io.IOException;
import java.nio.ShortBuffer;
import java.util.logging.Logger;

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
    private int fboId = -1;
    private int fboTextureId = -1;
    private int fboDepthId = -1;

    // Shader and scroll buffer
    private ParallaxShaderProgram shader;
    private HScrollBuffer hScrollBuffer;

    // Per-scanline scroll data (224 entries)
    private final int[] hScrollData = new int[SCREEN_HEIGHT];

    // State
    private boolean initialized = false;
    private final int[] savedViewport = new int[4];

    /**
     * Initialize the renderer with FBO and shader.
     *
     * @param gl OpenGL context
     * @throws IOException if shader loading fails
     */
    public void init(GL2 gl) throws IOException {
        if (initialized) {
            return;
        }

        // Create FBO for background tile rendering
        createFBO(gl);

        // Initialize H-scroll buffer
        hScrollBuffer = new HScrollBuffer();
        hScrollBuffer.init(gl);

        // Load special stage background shader
        shader = new ParallaxShaderProgram(gl, "shaders/shader_ss_background.glsl");
        shader.cacheUniformLocations(gl);

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
    private void createFBO(GL2 gl) {
        // Generate FBO
        int[] fbos = new int[1];
        gl.glGenFramebuffers(1, fbos, 0);
        fboId = fbos[0];

        // Generate texture for color attachment
        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        fboTextureId = textures[0];

        gl.glBindTexture(GL2.GL_TEXTURE_2D, fboTextureId);
        gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA8, FBO_WIDTH, FBO_HEIGHT, 0,
                GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, null);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
        // Use REPEAT for seamless horizontal wrapping
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_REPEAT);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_REPEAT);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);

        // Generate depth buffer (optional, but good practice)
        int[] depths = new int[1];
        gl.glGenRenderbuffers(1, depths, 0);
        fboDepthId = depths[0];

        gl.glBindRenderbuffer(GL2.GL_RENDERBUFFER, fboDepthId);
        gl.glRenderbufferStorage(GL2.GL_RENDERBUFFER, GL2.GL_DEPTH_COMPONENT16, FBO_WIDTH, FBO_HEIGHT);
        gl.glBindRenderbuffer(GL2.GL_RENDERBUFFER, 0);

        // Attach to FBO
        gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, fboId);
        gl.glFramebufferTexture2D(GL2.GL_FRAMEBUFFER, GL2.GL_COLOR_ATTACHMENT0,
                GL2.GL_TEXTURE_2D, fboTextureId, 0);
        gl.glFramebufferRenderbuffer(GL2.GL_FRAMEBUFFER, GL2.GL_DEPTH_ATTACHMENT,
                GL2.GL_RENDERBUFFER, fboDepthId);

        // Check FBO completeness
        int status = gl.glCheckFramebufferStatus(GL2.GL_FRAMEBUFFER);
        if (status != GL2.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.severe("Special stage background FBO creation failed with status: " + status);
        }

        gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, 0);
        LOGGER.fine("Created FBO " + FBO_WIDTH + "x" + FBO_HEIGHT + " for special stage background");
    }

    /**
     * Begin the tile rendering pass - bind FBO and set up projection.
     * After calling this, render background tiles using the normal tile renderer.
     *
     * @param gl            OpenGL context
     * @param displayHeight The display height used by pattern renderer for Y-flip
     */
    public void beginTilePass(GL2 gl, int displayHeight) {
        if (!initialized)
            return;

        // Save current viewport
        gl.glGetIntegerv(GL2.GL_VIEWPORT, savedViewport, 0);

        // Bind FBO
        gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, fboId);
        gl.glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);

        // Set up projection matrix
        // The pattern renderer places tiles at OpenGL Y = screenHeight - genesisY - 8.
        // For a 32-tile (256 pixel) tall background with screenHeight=224:
        // - Genesis Y=0 (top row) -> OpenGL Y = 224 - 0 - 8 = 216 (tile spans 216..224)
        // - Genesis Y=248 (bottom row) -> OpenGL Y = 224 - 248 - 8 = -32 (tile spans -32..-24)
        //
        // The full tilemap spans exactly -32..224 (256 pixels). Match that 1:1.
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        // Capture exactly 256 world units (matching Genesis VDP plane height)
        // to fit into the 256x256 FBO texture with 1:1 pixel mapping.
        int top = displayHeight; // 224: top boundary includes row 0's top edge
        int bottom = top - FBO_HEIGHT; // -32: bottom boundary includes row 31's bottom edge
        gl.glOrtho(0, FBO_WIDTH, bottom, top, -1, 1);

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        // Clear FBO with transparent black
        gl.glClearColor(0, 0, 0, 0);
        gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * End the tile rendering pass - unbind FBO and restore state.
     */
    public void endTilePass(GL2 gl) {
        if (!initialized)
            return;

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPopMatrix();

        gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, 0);

        // Restore viewport
        gl.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
    }

    /**
     * Render the background with per-scanline scrolling using the shader.
     *
     * @param gl        OpenGL context
     * @param vScrollBG Vertical scroll offset for parallax
     */
    public void renderWithShader(GL2 gl, float vScrollBG) {
        if (!initialized)
            return;

        // Upload H-scroll data to GPU
        hScrollBuffer.upload(gl, hScrollData);

        // Bind shader
        shader.use(gl);
        shader.cacheUniformLocations(gl);

        // Set texture units
        shader.setBackgroundTexture(gl, 0); // FBO texture
        shader.setHScrollTexture(gl, 1); // H-scroll table

        // Get actual viewport dimensions for resolution independence
        int[] viewport = new int[4];
        gl.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
        float realWidth = (float) viewport[2];
        float realHeight = (float) viewport[3];

        // Set shader uniforms
        shader.setScreenDimensions(gl, realWidth, realHeight);
        shader.setBGTextureDimensions(gl, FBO_WIDTH, FBO_HEIGHT);
        shader.setVScrollBG(gl, vScrollBG);
        shader.setViewportOffset(gl, (float) viewport[0], (float) viewport[1]);

        // Bind textures
        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, fboTextureId);

        hScrollBuffer.bind(gl, 1);

        // Draw fullscreen quad
        drawFullscreenQuad(gl);

        // Cleanup
        shader.stop(gl);
        hScrollBuffer.unbind(gl, 1);
        gl.glActiveTexture(GL2.GL_TEXTURE0);
    }

    /**
     * Draw a fullscreen quad covering the entire screen.
     * The shader handles H32 clipping internally.
     */
    private void drawFullscreenQuad(GL2 gl) {
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        // Use Genesis screen coordinates (320x224)
        gl.glOrtho(0, SCREEN_WIDTH, SCREEN_HEIGHT, 0, -1, 1);

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        // Draw quad covering full screen - shader will clip to H32 viewport
        gl.glBegin(GL2.GL_QUADS);
        gl.glTexCoord2f(0, 0);
        gl.glVertex2f(0, 0);
        gl.glTexCoord2f(1, 0);
        gl.glVertex2f(SCREEN_WIDTH, 0);
        gl.glTexCoord2f(1, 1);
        gl.glVertex2f(SCREEN_WIDTH, SCREEN_HEIGHT);
        gl.glTexCoord2f(0, 1);
        gl.glVertex2f(0, SCREEN_HEIGHT);
        gl.glEnd();

        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_MODELVIEW);
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
     * Get the H-scroll data array for direct manipulation.
     * Useful for bulk updates based on segment animation.
     */
    public int[] getHScrollData() {
        return hScrollData;
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
    public void cleanup(GL2 gl) {
        if (hScrollBuffer != null) {
            hScrollBuffer.cleanup(gl);
            hScrollBuffer = null;
        }
        if (shader != null) {
            shader.cleanup(gl);
            shader = null;
        }
        if (fboId > 0) {
            gl.glDeleteFramebuffers(1, new int[] { fboId }, 0);
            fboId = -1;
        }
        if (fboTextureId > 0) {
            gl.glDeleteTextures(1, new int[] { fboTextureId }, 0);
            fboTextureId = -1;
        }
        if (fboDepthId > 0) {
            gl.glDeleteRenderbuffers(1, new int[] { fboDepthId }, 0);
            fboDepthId = -1;
        }
        initialized = false;
        LOGGER.info("SpecialStageBackgroundRenderer cleaned up");
    }
}
