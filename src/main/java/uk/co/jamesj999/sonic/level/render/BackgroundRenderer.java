package uk.co.jamesj999.sonic.level.render;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.graphics.HScrollBuffer;
import uk.co.jamesj999.sonic.graphics.ParallaxShaderProgram;
import uk.co.jamesj999.sonic.graphics.QuadRenderer;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Two-pass background renderer implementing GPU-based per-scanline scrolling.
 * 
 * Emulates Mega Drive VDP horizontal interrupt behavior by:
 * 1. Rendering the background tilemap to an offscreen framebuffer (FBO)
 * 2. Drawing a fullscreen quad with a shader that samples per-line scroll
 * values
 * 
 * This allows true per-scanline horizontal scrolling rather than the per-tile
 * approximation used in the CPU-based approach.
 */
public class BackgroundRenderer {

    private static final Logger LOGGER = Logger.getLogger(BackgroundRenderer.class.getName());

    // Default FBO size - can be adjusted per zone
    private static final int DEFAULT_FBO_WIDTH = 1024;
    private static final int DEFAULT_FBO_HEIGHT = 256;

    // Visible screen dimensions (Mega Drive resolution)
    public static final int SCREEN_WIDTH = 320;
    public static final int SCREEN_HEIGHT = 224;

    private int fboId = -1;
    private int fboTextureId = -1;
    private int fboDepthId = -1;

    private int fboWidth = DEFAULT_FBO_WIDTH;
    private int fboHeight = DEFAULT_FBO_HEIGHT;

    private HScrollBuffer hScrollBuffer;
    private ParallaxShaderProgram parallaxShader;
    private final QuadRenderer quadRenderer = new QuadRenderer();

    private boolean initialized = false;
    private final int[] savedViewport = new int[4];

    /**
     * Initialize the background renderer with FBO and shader.
     * 
     * @param gl         OpenGL context
     * @param shaderPath Path to the parallax fragment shader
     */
    public void init(GL2 gl, String shaderPath) throws IOException {
        if (initialized) {
            return;
        }

        // Initialize HScroll buffer
        hScrollBuffer = new HScrollBuffer();
        hScrollBuffer.init(gl);

        // Load parallax shader
        parallaxShader = new ParallaxShaderProgram(gl, shaderPath);
        parallaxShader.cacheUniformLocations(gl);
        quadRenderer.init(gl);

        // Create FBO for background tile rendering
        createFBO(gl, fboWidth, fboHeight);

        initialized = true;
        LOGGER.info("BackgroundRenderer initialized with FBO " + fboWidth + "x" + fboHeight);
    }

    public ParallaxShaderProgram getParallaxShader() {
        return parallaxShader;
    }

    /**
     * Create the framebuffer object and its attachments.
     */
    private void createFBO(GL2 gl, int width, int height) {
        // Generate FBO
        int[] fbos = new int[1];
        gl.glGenFramebuffers(1, fbos, 0);
        fboId = fbos[0];

        // Generate texture for color attachment
        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        fboTextureId = textures[0];

        gl.glBindTexture(GL2.GL_TEXTURE_2D, fboTextureId);
        gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA8, width, height, 0,
                GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, null);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_REPEAT);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_REPEAT);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);

        // Generate depth buffer
        int[] depths = new int[1];
        gl.glGenRenderbuffers(1, depths, 0);
        fboDepthId = depths[0];

        gl.glBindRenderbuffer(GL2.GL_RENDERBUFFER, fboDepthId);
        gl.glRenderbufferStorage(GL2.GL_RENDERBUFFER, GL2.GL_DEPTH_COMPONENT16, width, height);
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
            LOGGER.severe("FBO creation failed with status: " + status);
        }

        gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, 0);
    }

    /**
     * Begin the tile rendering pass - binds FBO and clears it.
     * After calling this, render background tiles using GPU tilemap.
     *
     * @param gl            OpenGL context
     * @param displayHeight The display pixel height (unused, kept for API compatibility)
     * @param gpuTilemap    True (always, GPU tilemap is the only supported path)
     */
    public void beginTilePass(GL2 gl, int displayHeight, boolean gpuTilemap) {
        if (!initialized)
            return;

        // Save current viewport to restore later (must query actual GL state)
        gl.glGetIntegerv(GL2.GL_VIEWPORT, savedViewport, 0);

        gl.glBindFramebuffer(GL2.GL_FRAMEBUFFER, fboId);
        gl.glViewport(0, 0, fboWidth, fboHeight);

        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        // GPU tilemap renderer draws a quad from (0,0) to (fboWidth, fboHeight).
        // Set up projection to cover the full quad coordinate space.
        gl.glOrtho(0, fboWidth, 0, fboHeight, -1, 1);

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        gl.glClearColor(0, 0, 0, 0);
        gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);

    }

    /**
     * End the tile rendering pass - unbinds FBO.
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
     * Execute the scroll pass - renders the FBO with per-scanline scrolling.
     * 
     * @param gl               OpenGL context
     * @param hScroll          Packed horizontal scroll array from ParallaxManager
     * @param vScrollBG        Vertical scroll offset for background
     * @param paletteTextureId ID of the combined palette texture
     * @param screenWidth      UNUSED (legacy)
     * @param screenHeight     UNUSED (legacy)
     */
    public void renderWithScroll(GL2 gl, int[] hScroll, float vScrollBG,
            int paletteTextureId, int screenWidth, int screenHeight) {
        if (!initialized)
            return;

        // Upload scroll data to GPU
        hScrollBuffer.upload(gl, hScroll);

        // Bind shader and set uniforms
        parallaxShader.use(gl);
        parallaxShader.cacheUniformLocations(gl);

        // Set texture units
        parallaxShader.setBackgroundTexture(gl, 0);
        parallaxShader.setHScrollTexture(gl, 1);
        parallaxShader.setPalette(gl, 2);

        // Get viewport for resolution independence
        int[] viewport = new int[4];
        gl.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
        float realWidth = (float) viewport[2];
        float realHeight = (float) viewport[3];

        // Set dimensions and scroll
        parallaxShader.setScreenDimensions(gl, realWidth, realHeight);
        parallaxShader.setBGTextureDimensions(gl, fboWidth, fboHeight);
        parallaxShader.setVScrollBG(gl, vScrollBG);
        parallaxShader.setViewportOffset(gl, (float) viewport[0], (float) viewport[1]);

        // Bind textures
        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, fboTextureId);

        hScrollBuffer.bind(gl, 1);

        gl.glActiveTexture(GL2.GL_TEXTURE2);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);

        // Draw fullscreen quad
        drawFullscreenQuad(gl);

        // Cleanup
        parallaxShader.stop(gl);
        hScrollBuffer.unbind(gl, 1);
        gl.glActiveTexture(GL2.GL_TEXTURE0);
    }

    /**
     * Execute the scroll pass with wider FBO for per-scanline scrolling.
     * 
     * @param gl               OpenGL context
     * @param hScroll          Packed horizontal scroll array from ParallaxManager
     * @param scrollMidpoint   The midpoint of the scroll range (hScroll values are
     *                         relative to this)
     * @param extraBuffer      Extra pixels on each side of the FBO
     * @param paletteTextureId ID of the combined palette texture
     * @param screenWidth      Display width in pixels
     * @param screenHeight     Display height in pixels
     */
    public void renderWithScrollWide(GL2 gl, int[] hScroll, int scrollMidpoint, int extraBuffer,
            int fboVScroll, int paletteTextureId, int screenWidth, int screenHeight) {
        if (!initialized)
            return;

        // Upload scroll data to GPU
        hScrollBuffer.upload(gl, hScroll);

        // Bind shader and set uniforms
        parallaxShader.use(gl);
        parallaxShader.cacheUniformLocations(gl);

        // Set texture units
        parallaxShader.setBackgroundTexture(gl, 0);
        parallaxShader.setHScrollTexture(gl, 1);
        parallaxShader.setPalette(gl, 2);

        // Get viewport for resolution independence
        int[] viewport = new int[4];
        gl.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
        float realWidth = (float) viewport[2];
        float realHeight = (float) viewport[3];

        // Set dimensions and scroll
        parallaxShader.setScreenDimensions(gl, realWidth, realHeight);
        parallaxShader.setBGTextureDimensions(gl, fboWidth, fboHeight);

        // Pass scroll midpoint and extra buffer to shader
        parallaxShader.setScrollMidpoint(gl, scrollMidpoint);
        parallaxShader.setExtraBuffer(gl, extraBuffer);
        parallaxShader.setVScroll(gl, (float) fboVScroll);
        parallaxShader.setViewportOffset(gl, (float) viewport[0], (float) viewport[1]);

        // Bind textures
        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, fboTextureId);

        hScrollBuffer.bind(gl, 1);

        gl.glActiveTexture(GL2.GL_TEXTURE2);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);

        // Draw fullscreen quad
        drawFullscreenQuad(gl);

        // Cleanup
        parallaxShader.stop(gl);
        hScrollBuffer.unbind(gl, 1);
        gl.glActiveTexture(GL2.GL_TEXTURE0);
    }

    /**
     * Draw a fullscreen quad for the parallax pass.
     */
    private void drawFullscreenQuad(GL2 gl) {
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        gl.glOrtho(0, SCREEN_WIDTH, SCREEN_HEIGHT, 0, -1, 1);

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        quadRenderer.draw(gl, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL2.GL_MODELVIEW);
    }

    /**
     * Resize the FBO for zones with larger backgrounds.
     */
    public void resizeFBO(GL2 gl, int width, int height) {
        if (width == fboWidth && height == fboHeight) {
            return;
        }

        // Delete old FBO resources
        if (fboId > 0) {
            gl.glDeleteFramebuffers(1, new int[] { fboId }, 0);
        }
        if (fboTextureId > 0) {
            gl.glDeleteTextures(1, new int[] { fboTextureId }, 0);
        }
        if (fboDepthId > 0) {
            gl.glDeleteRenderbuffers(1, new int[] { fboDepthId }, 0);
        }

        fboWidth = width;
        fboHeight = height;
        createFBO(gl, width, height);

        LOGGER.info("BackgroundRenderer FBO resized to " + width + "x" + height);
    }

    /**
     * Get the FBO texture ID (for debugging or external use).
     */
    public int getFBOTextureId() {
        return fboTextureId;
    }

    /**
     * Check if renderer is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Clean up all OpenGL resources.
     */
    public void cleanup(GL2 gl) {
        if (hScrollBuffer != null) {
            hScrollBuffer.cleanup(gl);
        }
        if (parallaxShader != null) {
            parallaxShader.cleanup(gl);
        }
        quadRenderer.cleanup(gl);
        if (fboId > 0) {
            gl.glDeleteFramebuffers(1, new int[] { fboId }, 0);
        }
        if (fboTextureId > 0) {
            gl.glDeleteTextures(1, new int[] { fboTextureId }, 0);
        }
        if (fboDepthId > 0) {
            gl.glDeleteRenderbuffers(1, new int[] { fboDepthId }, 0);
        }
        initialized = false;
    }
}
