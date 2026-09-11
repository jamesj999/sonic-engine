package com.openggf.game.sonic1.specialstage;

import com.openggf.Engine;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.HScrollBuffer;
import com.openggf.graphics.ParallaxShaderProgram;
import com.openggf.graphics.QuadRenderer;
import com.openggf.level.PatternDesc;
import com.openggf.util.FboHelper;

import java.io.IOException;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.*;
import static com.openggf.game.sonic1.constants.Sonic1Constants.ARTTILE_SS_BG_FISH;

/**
 * GPU-based background renderer for Sonic 1 Special Stage.
 *
 * Renders the active SS namespace tilemap to an FBO (512x512, matching a 64x64 tile
 * VDP plane), then draws a fullscreen quad with the SS background shader
 * applying per-scanline H-scroll across the active game viewport.
 *
 * Modeled on the S2 {@code SpecialStageBackgroundRenderer} but with:
 * <ul>
 *   <li>512x512 FBO (64x64 tiles) instead of 256x256 (32x32 tiles)</li>
 *   <li>Split tile remapping for dual art sets (clouds + fish) in one atlas</li>
 *   <li>FBO caching - tiles only re-rendered when tilemap changes</li>
 * </ul>
 */
public class Sonic1SpecialStageBackgroundRenderer {

    private static final Logger LOGGER = Logger.getLogger(Sonic1SpecialStageBackgroundRenderer.class.getName());

    // FBO dimensions - 64x64 tiles = 512x512 pixels
    private static final int FBO_WIDTH = 512;
    private static final int FBO_HEIGHT = 512;

    // Tile map dimensions
    private static final int MAP_WIDTH = 64;
    private static final int MAP_HEIGHT = 64;
    private static final int TILE_SIZE = 8;

    // Screen dimensions
    public static final int SCREEN_WIDTH = 320;
    public static final int SCREEN_HEIGHT = 224;

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

    // Tile remapping bases
    private int bgCloudPatternBase;
    private int bgFishPatternBase;

    // Tilemap data and caching
    private byte[] tilemapData;
    private boolean fboNeedsRedraw = true;

    // Reusable PatternDesc to avoid allocation in render loop
    private final PatternDesc reusableDesc = new PatternDesc();

    // State
    private boolean initialized = false;
    private final int[] savedViewport = new int[4];
    private final int[] shaderViewport = new int[4];
    private float backdropR;
    private float backdropG;
    private float backdropB;
    private boolean fillTransparentWithBackdrop;
    private final GraphicsManager graphicsManager;

    public Sonic1SpecialStageBackgroundRenderer(GraphicsManager graphicsManager) {
        this.graphicsManager = java.util.Objects.requireNonNull(graphicsManager, "graphicsManager");
    }

    /**
     * Initialize the renderer with FBO and shader.
     */
    public void init() throws IOException {
        if (initialized) {
            return;
        }

        createFBO();

        hScrollBuffer = new HScrollBuffer();
        hScrollBuffer.init();

        shader = new ParallaxShaderProgram("shaders/shader_ss_background.glsl");
        shader.cacheUniformLocations();
        quadRenderer.init();

        for (int i = 0; i < SCREEN_HEIGHT; i++) {
            hScrollData[i] = 0;
        }

        initialized = true;
        LOGGER.info("Sonic1SpecialStageBackgroundRenderer initialized");
    }

    private void createFBO() {
        fboHandle = FboHelper.createWithDepth(FBO_WIDTH, FBO_HEIGHT, GL_REPEAT);
        fboId = fboHandle.fboId();
        fboTextureId = fboHandle.textureId();
        fboDepthId = fboHandle.depthId();
        LOGGER.fine("Created FBO " + FBO_WIDTH + "x" + FBO_HEIGHT + " for S1 SS namespace renderer");
    }

    /**
     * Store atlas offsets for tile remapping.
     */
    public void setPatternBases(int cloudBase, int fishBase) {
        this.bgCloudPatternBase = cloudBase;
        this.bgFishPatternBase = fishBase;
    }

    /**
     * Set active Enigma-decoded tilemap and mark FBO for redraw.
     */
    public void setTilemap(byte[] data) {
        this.tilemapData = data;
        this.fboNeedsRedraw = true;
    }

    public void setBackdropColor(float r, float g, float b) {
        this.backdropR = r;
        this.backdropG = g;
        this.backdropB = b;
    }

    public void setFillTransparentWithBackdrop(boolean fill) {
        this.fillTransparentWithBackdrop = fill;
    }

    /**
     * Returns true if the FBO needs to be redrawn (tilemap changed).
     */
    public boolean needsRedraw() {
        return fboNeedsRedraw;
    }

    /**
     * Sets up FBO projection mode for coordinate calculations.
     * Call BEFORE creating the pattern batch.
     */
    public void beginFBOProjection() {
        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.beginFBOProjection(FBO_WIDTH, FBO_HEIGHT);
        }
    }

    /**
     * Restores normal screen projection after FBO pattern batch creation.
     * Call AFTER flushing the pattern batch.
     */
    public void endFBOProjection() {
        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.endFBOProjection();
        }
    }

    /**
     * Begin the tile rendering pass - bind FBO and set up viewport.
     *
     * @param displayHeight The display height used by pattern renderer for Y-flip
     */
    public void beginTilePass(int displayHeight) {
        if (!initialized) return;

        glGetIntegerv(GL_VIEWPORT, savedViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);

        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.beginFBOProjection(FBO_WIDTH, FBO_HEIGHT);
        }

        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Render background tiles to the FBO.
     * Iterates up to 64x64 tiles, remaps VDP tile indices to atlas IDs, renders each.
     */
    public void renderTilesToFBO(GraphicsManager gm) {
        if (tilemapData == null || tilemapData.length < 2) {
            return;
        }

        int wordCount = tilemapData.length / 2;
        int mapHeight = Math.min(wordCount / MAP_WIDTH, MAP_HEIGHT);

        for (int ty = 0; ty < mapHeight; ty++) {
            for (int tx = 0; tx < MAP_WIDTH; tx++) {
                int wordIndex = ty * MAP_WIDTH + tx;
                int idx = wordIndex * 2;
                if (idx + 1 >= tilemapData.length) continue;

                int word = ((tilemapData[idx] & 0xFF) << 8) | (tilemapData[idx + 1] & 0xFF);
                if (word == 0) continue;

                reusableDesc.set(word);
                int vdpTile = reusableDesc.getPatternIndex();

                // Split remap: fish art starts at ARTTILE_SS_BG_FISH (0x051)
                int atlasId;
                if (vdpTile >= ARTTILE_SS_BG_FISH) {
                    atlasId = bgFishPatternBase + (vdpTile - ARTTILE_SS_BG_FISH);
                } else {
                    atlasId = bgCloudPatternBase + vdpTile;
                }

                int screenX = tx * TILE_SIZE;
                int screenY = ty * TILE_SIZE;

                gm.renderPatternWithId(atlasId, reusableDesc, screenX, screenY);
            }
        }

        fboNeedsRedraw = false;
    }

    /**
     * End the tile rendering pass - unbind FBO and restore viewport.
     */
    public void endTilePass() {
        if (!initialized) return;

        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        Engine engine = graphicsManager.getEngine();
        if (engine != null) {
            engine.endFBOProjection();
        }

        FboHelper.restoreViewport(savedViewport);
    }

    /**
     * Render the background with per-scanline scrolling using the shader.
     *
     * @param vScroll Vertical scroll offset for parallax
     */
    public void renderWithShader(float vScroll) {
        if (!initialized) return;

        hScrollBuffer.upload(hScrollData);

        // Bind 1D sampler texture before shader use; macOS may validate samplers
        // at program-use time.
        hScrollBuffer.bind(1);

        shader.use();
        shader.cacheUniformLocations();

        shader.setBackgroundTexture(0);
        shader.setHScrollTexture(1);

        glGetIntegerv(GL_VIEWPORT, shaderViewport);
        int fullViewportX = shaderViewport[0];
        int fullViewportY = shaderViewport[1];
        int fullViewportWidth = shaderViewport[2];
        int fullViewportHeight = shaderViewport[3];

        glViewport(fullViewportX, fullViewportY, fullViewportWidth, fullViewportHeight);

        shader.setScreenDimensions((float) fullViewportWidth, (float) fullViewportHeight);
        shader.setActiveDisplayWidth((float) SCREEN_WIDTH);
        shader.setBGTextureDimensions(FBO_WIDTH, FBO_HEIGHT);
        shader.setVScrollBG(vScroll);
        shader.setViewportOffset((float) fullViewportX, (float) fullViewportY);
        shader.setBackdropColor(backdropR, backdropG, backdropB);
        shader.setFillTransparentWithBackdrop(fillTransparentWithBackdrop);

        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        int prevBlendSrc = glGetInteger(GL_BLEND_SRC_ALPHA);
        int prevBlendDst = glGetInteger(GL_BLEND_DST_ALPHA);
        int prevBlendEquation = glGetInteger(GL_BLEND_EQUATION_RGB);
        glDisable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboTextureId);

        quadRenderer.draw(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        shader.stop();
        hScrollBuffer.unbind(1);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);

        glBlendEquation(prevBlendEquation);
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
        } else {
            glDisable(GL_BLEND);
        }
        glBlendFunc(prevBlendSrc, prevBlendDst);
        glViewport(fullViewportX, fullViewportY, fullViewportWidth, fullViewportHeight);
    }

    /**
     * Set all 224 scanlines to the same horizontal scroll value.
     */
    public void setUniformHScroll(int scroll) {
        for (int i = 0; i < SCREEN_HEIGHT; i++) {
            hScrollData[i] = scroll;
        }
    }

    /**
     * Set per-scanline H-scroll data from an external array.
     * The source array should have at least SCREEN_HEIGHT (224) entries.
     */
    public void setHScrollData(int[] scrollData) {
        System.arraycopy(scrollData, 0, hScrollData, 0, Math.min(scrollData.length, SCREEN_HEIGHT));
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Marks the cached FBO as stale. Useful when palette colors change.
     */
    public void markDirty() {
        fboNeedsRedraw = true;
    }

    /**
     * Release GL resources.
     */
    public void cleanup() {
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
        LOGGER.info("Sonic1SpecialStageBackgroundRenderer cleaned up");
    }
}
