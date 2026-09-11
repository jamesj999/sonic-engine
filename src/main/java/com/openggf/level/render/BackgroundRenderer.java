package com.openggf.level.render;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.HScrollBuffer;
import com.openggf.graphics.ParallaxShaderProgram;
import com.openggf.graphics.QuadRenderer;
import com.openggf.graphics.VScrollBuffer;
import com.openggf.util.FboHelper;
import com.openggf.util.IntIndexedView;
import com.openggf.util.ShortIndexedView;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;

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

    private FboHelper.FboHandle fboHandle;
    private int fboId = -1;
    private int fboTextureId = -1;
    private int fboDepthId = -1;

    // GPU allocation dimensions (grow-only, may be larger than current render area)
    private int fboAllocWidth = DEFAULT_FBO_WIDTH;
    private int fboAllocHeight = DEFAULT_FBO_HEIGHT;

    // Current render viewport dimensions (set per-frame via beginTilePass)
    private int renderWidth = DEFAULT_FBO_WIDTH;
    private int renderHeight = DEFAULT_FBO_HEIGHT;

    private HScrollBuffer hScrollBuffer;
    private VScrollBuffer vScrollBuffer;
    private VScrollBuffer vScrollColumnBuffer;
    private ParallaxShaderProgram parallaxShader;
    private final QuadRenderer quadRenderer = new QuadRenderer();
    private final GraphicsManager graphicsManager;

    private boolean initialized = false;
    private final int[] savedViewport = new int[4];
    private float backdropR = 0.0f;
    private float backdropG = 0.0f;
    private float backdropB = 0.0f;

    // Shimmer state for parallax compositing pass
    private int shimmerFrameCounter = 0;
    private int shimmerStyle = 0;
    private float shimmerWaterlineScreenY = 9999.0f;

    public BackgroundRenderer() {
        this(GameServices.graphics());
    }

    public BackgroundRenderer(GraphicsManager graphicsManager) {
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
    }

    /**
     * Initialize the background renderer with FBO and shader.
     *
     * @param shaderPath Path to the parallax fragment shader
     */
    public void init(String shaderPath) throws IOException {
        if (initialized) {
            return;
        }

        // Initialize HScroll buffer
        hScrollBuffer = new HScrollBuffer();
        hScrollBuffer.init();
        vScrollBuffer = new VScrollBuffer();
        vScrollBuffer.init();
        vScrollColumnBuffer = new VScrollBuffer(columnCount(
                GameServices.configuration().getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)));
        vScrollColumnBuffer.init();

        // Load parallax shader
        parallaxShader = new ParallaxShaderProgram(shaderPath);
        parallaxShader.cacheUniformLocations();
        quadRenderer.init();

        // Create FBO for background tile rendering.
        // Size the initial allocation to the configured viewport width so that at
        // native (320) the FBO starts at 320px and at widescreen presets it starts
        // at the wider configured width. ensureCapacity() (called every frame) will
        // grow the FBO further if a zone's BG period exceeds this initial size.
        fboAllocWidth = GameServices.configuration().getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        createFBO(fboAllocWidth, fboAllocHeight);

        initialized = true;
        LOGGER.info("BackgroundRenderer initialized with FBO " + fboAllocWidth + "x" + fboAllocHeight);
    }

    /**
     * Set shimmer state for the parallax compositing pass.
     * Called once per frame before renderWithScroll/renderWithScrollWide.
     */
    public void setShimmerState(int frameCounter, int shimmerStyle, float waterlineScreenY) {
        this.shimmerFrameCounter = frameCounter;
        this.shimmerStyle = shimmerStyle;
        this.shimmerWaterlineScreenY = waterlineScreenY;
    }

    public ParallaxShaderProgram getParallaxShader() {
        return parallaxShader;
    }

    /**
     * Sets the backdrop color used when transparent background pixels are resolved.
     */
    public void setBackdropColor(float r, float g, float b) {
        this.backdropR = r;
        this.backdropG = g;
        this.backdropB = b;
    }

    /**
     * Create the framebuffer object and its attachments.
     */
    private void createFBO(int width, int height) {
        fboHandle = FboHelper.createWithDepth(width, height, GL_REPEAT);
        fboId = fboHandle.fboId();
        fboTextureId = fboHandle.textureId();
        fboDepthId = fboHandle.depthId();
    }

    /**
     * Begin the tile rendering pass - binds FBO and clears it.
     * After calling this, render background tiles using GPU tilemap.
     *
     * @param width      The rendering viewport width (may be smaller than FBO allocation)
     * @param height     The rendering viewport height
     * @param gpuTilemap True (always, GPU tilemap is the only supported path)
     */
    public void beginTilePass(int width, int height, boolean gpuTilemap) {
        if (!initialized)
            return;

        this.renderWidth = width;
        this.renderHeight = height;

        // Save current viewport to restore later
        GraphicsManager gm = this.graphicsManager;
        savedViewport[0] = gm.getViewportX();
        savedViewport[1] = gm.getViewportY();
        savedViewport[2] = gm.getViewportWidth();
        savedViewport[3] = gm.getViewportHeight();

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, renderWidth, renderHeight);

        // Note: The tilemap shader uses gl_FragCoord for positioning,
        // so no projection matrix uniform is needed. The viewport setup above
        // is sufficient for gl_FragCoord to work correctly.

        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    }

    /**
     * End the tile rendering pass - unbinds FBO.
     */
    public void endTilePass() {
        if (!initialized)
            return;

        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // Restore viewport
        glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
    }

    /**
     * Upload HScroll data to GPU early (before the tile pass), so the tilemap
     * shader can sample per-scanline scroll values when PerLineScroll is active.
     */
    public void uploadHScroll(int[] hScroll) {
        if (initialized && hScrollBuffer != null) {
            hScrollBuffer.upload(hScroll);
        }
    }

    public void uploadHScroll(IntIndexedView hScroll) {
        if (initialized && hScrollBuffer != null) {
            hScrollBuffer.upload(hScroll);
        }
    }

    /**
     * Get the HScroll texture ID for binding in the tilemap shader.
     */
    public int getHScrollTextureId() {
        return hScrollBuffer != null ? hScrollBuffer.getTextureId() : 0;
    }

    /**
     * Execute the scroll pass with wider FBO for per-scanline scrolling.
     *
     * @param hScroll          Packed horizontal scroll array from ParallaxManager
     * @param scrollMidpoint   The midpoint of the scroll range (hScroll values are
     *                         relative to this)
     * @param extraBuffer      Extra pixels on each side of the FBO
     * @param fboVScroll       Vertical scroll offset for background
     */
    public void renderWithScrollWide(int[] hScroll, int scrollMidpoint, int extraBuffer,
            int fboVScroll) {
        renderWithScrollWide(hScroll, null, null, scrollMidpoint, extraBuffer, fboVScroll, false);
    }

    public void renderWithScrollWide(int[] hScroll, short[] vScrollPerLine, int scrollMidpoint, int extraBuffer,
            int fboVScroll) {
        renderWithScrollWide(hScroll, vScrollPerLine, null, scrollMidpoint, extraBuffer, fboVScroll, false);
    }

    public void renderWithScrollWide(int[] hScroll, short[] vScrollPerLine, short[] vScrollPerColumn,
            int scrollMidpoint, int extraBuffer, int fboVScroll) {
        renderWithScrollWide(hScroll, vScrollPerLine, vScrollPerColumn, scrollMidpoint, extraBuffer, fboVScroll, false);
    }

    /**
     * Execute the scroll pass, optionally skipping per-line HScroll sampling.
     *
     * @param noHScroll When true, the parallax shader skips HScroll sampling
     *                  (used when per-line scroll was already applied in the tile pass)
     */
    public void renderWithScrollWide(int[] hScroll, int scrollMidpoint, int extraBuffer,
            int fboVScroll, boolean noHScroll) {
        renderWithScrollWide(hScroll, null, null, scrollMidpoint, extraBuffer, fboVScroll, noHScroll);
    }

    public void renderWithScrollWide(int[] hScroll, short[] vScrollPerLine, int scrollMidpoint, int extraBuffer,
            int fboVScroll, boolean noHScroll) {
        renderWithScrollWide(hScroll, vScrollPerLine, null, scrollMidpoint, extraBuffer, fboVScroll, noHScroll);
    }

    public void renderWithScrollWide(int[] hScroll, short[] vScrollPerLine, short[] vScrollPerColumn,
            int scrollMidpoint, int extraBuffer, int fboVScroll, boolean noHScroll) {
        if (!initialized)
            return;

        // Upload scroll data to GPU
        hScrollBuffer.upload(hScroll);
        if (vScrollPerLine != null && vScrollBuffer != null) {
            vScrollBuffer.upload(vScrollPerLine);
        }
        if (vScrollPerColumn != null && vScrollColumnBuffer != null) {
            vScrollColumnBuffer.upload(vScrollPerColumn);
        }

        renderUploadedScroll(scrollMidpoint, extraBuffer, fboVScroll, noHScroll,
                vScrollPerLine != null, vScrollPerColumn != null);
    }

    public void renderWithScrollWide(IntIndexedView hScroll, ShortIndexedView vScrollPerLine,
            ShortIndexedView vScrollPerColumn, int scrollMidpoint, int extraBuffer,
            int fboVScroll, boolean noHScroll) {
        if (!initialized) {
            return;
        }
        hScrollBuffer.upload(hScroll);
        if (vScrollPerLine != null && vScrollBuffer != null) {
            vScrollBuffer.upload(vScrollPerLine);
        }
        if (vScrollPerColumn != null && vScrollColumnBuffer != null) {
            vScrollColumnBuffer.upload(vScrollPerColumn);
        }
        renderUploadedScroll(scrollMidpoint, extraBuffer, fboVScroll, noHScroll,
                vScrollPerLine != null, vScrollPerColumn != null);
    }

    private void renderUploadedScroll(int scrollMidpoint, int extraBuffer, int fboVScroll,
            boolean noHScroll, boolean usePerLineVScroll, boolean usePerColumnVScroll) {

        // Bind 1D sampler textures before shader use; macOS may validate samplers
        // at program-use time.
        hScrollBuffer.bind(1);
        if (vScrollBuffer != null) {
            vScrollBuffer.bind(2);
        }
        if (vScrollColumnBuffer != null) {
            vScrollColumnBuffer.bind(3);
        }

        // Bind shader and set uniforms
        parallaxShader.use();
        parallaxShader.cacheUniformLocations();

        // Set texture units
        parallaxShader.setBackgroundTexture(0);
        parallaxShader.setHScrollTexture(1);
        parallaxShader.setVScrollTexture(2);
        parallaxShader.setVScrollColumnTexture(3);

        // Get viewport for resolution independence
        GraphicsManager gm = this.graphicsManager;
        float viewportX = gm.getViewportX();
        float viewportY = gm.getViewportY();
        float realWidth = gm.getViewportWidth();
        float realHeight = gm.getViewportHeight();

        // Set dimensions and scroll
        // BGTextureWidth = renderWidth (wrap period), FBOAllocationWidth = fboAllocWidth (UV mapping)
        parallaxShader.setScreenDimensions(realWidth, realHeight);
        parallaxShader.setActiveDisplayWidth((float) GameServices.configuration()
                .getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        parallaxShader.setBGTextureDimensions(renderWidth, renderHeight);
        parallaxShader.setFBOAllocationWidth(fboAllocWidth);

        // Pass scroll midpoint and extra buffer to shader
        parallaxShader.setScrollMidpoint(scrollMidpoint);
        parallaxShader.setExtraBuffer(extraBuffer);
        parallaxShader.setVScroll((float) fboVScroll);
        parallaxShader.setViewportOffset(viewportX, viewportY);
        parallaxShader.setBackdropColor(backdropR, backdropG, backdropB);
        parallaxShader.setFillTransparentWithBackdrop(true);
        parallaxShader.setNoHScroll(noHScroll);
        parallaxShader.setUsePerLineVScroll(usePerLineVScroll);
        parallaxShader.setUsePerColumnVScroll(usePerColumnVScroll);
        parallaxShader.setShimmerParams(shimmerFrameCounter, shimmerStyle, shimmerWaterlineScreenY);

        // Bind textures
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboTextureId);

        // Draw fullscreen quad
        drawFullscreenQuad();

        // Cleanup
        parallaxShader.stop();
        hScrollBuffer.unbind(1);
        if (vScrollBuffer != null) {
            vScrollBuffer.unbind(2);
        }
        if (vScrollColumnBuffer != null) {
            vScrollColumnBuffer.unbind(3);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    private static int columnCount(int width) {
        return (width + 15) / 16;
    }

    /**
     * Draw a fullscreen quad for the parallax pass.
     * Note: The parallax shader uses gl_FragCoord for positioning,
     * so no projection matrix is needed. The quad coordinates are
     * arbitrary since the shader only cares about fragment screen position.
     */
    private void drawFullscreenQuad() {
        quadRenderer.draw(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
    }

    /**
     * Ensure the FBO is at least the given size. Grow-only — never shrinks.
     * Call once at level load time to pre-allocate for the maximum BG width,
     * avoiding mid-frame GPU reallocation hitches.
     */
    public void ensureCapacity(int width, int height) {
        if (width <= fboAllocWidth && height <= fboAllocHeight) {
            return;
        }

        int newWidth = Math.max(width, fboAllocWidth);
        int newHeight = Math.max(height, fboAllocHeight);

        // Delete old FBO resources
        FboHelper.destroy(fboHandle);

        fboAllocWidth = newWidth;
        fboAllocHeight = newHeight;
        createFBO(newWidth, newHeight);

        LOGGER.info("BackgroundRenderer FBO grown to " + newWidth + "x" + newHeight);
    }

    /**
     * Get the FBO texture ID (for debugging or external use).
     */
    public int getFBOTextureId() {
        return fboTextureId;
    }

    /**
     * Get the FBO allocation width (for callers that need the actual GPU texture size).
     */
    public int getFBOAllocWidth() {
        return fboAllocWidth;
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
    public void cleanup() {
        if (hScrollBuffer != null) {
            hScrollBuffer.cleanup();
        }
        if (vScrollBuffer != null) {
            vScrollBuffer.cleanup();
        }
        if (vScrollColumnBuffer != null) {
            vScrollColumnBuffer.cleanup();
        }
        if (parallaxShader != null) {
            parallaxShader.cleanup();
        }
        quadRenderer.cleanup();
        FboHelper.destroy(fboHandle);
        fboHandle = null;
        fboId = -1;
        fboTextureId = -1;
        fboDepthId = -1;
        initialized = false;
    }
}
