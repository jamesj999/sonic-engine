package com.openggf.graphics;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

import com.openggf.Engine;
import org.lwjgl.system.MemoryUtil;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.level.PatternDesc;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * High-performance batched pattern renderer.
 *
 * Instead of issuing one draw call per 8x8 pattern (with full state setup each
 * time),
 * this class collects all patterns to render and issues them in batched draw
 * calls.
 *
 * Performance gains:
 * - Eliminates per-pattern glPushMatrix/glPopMatrix calls
 * - Eliminates per-pattern shader bind/unbind
 * - Eliminates per-pattern uniform location lookups
 * - Reduces texture binding to minimal state changes
 * - Uses vertex arrays for efficient geometry transfer
 */
public class BatchedPatternRenderer {

    private static final Logger LOGGER = Logger.getLogger(BatchedPatternRenderer.class.getName());

    // Maximum patterns per batch
    private static final int MAX_PATTERNS_PER_BATCH = 4096;
    private static final int COMMAND_POOL_LIMIT = 8;

    // 6 vertices per pattern (2 triangles), 2 floats (x,y) per vertex
    private static final int FLOATS_PER_PATTERN_VERTS = 6 * 2;
    // 6 vertices per pattern (2 triangles), 2 floats (u,v) per vertex
    private static final int FLOATS_PER_PATTERN_TEXCOORDS = 6 * 2;
    private static BatchedPatternRenderer instance;
    private final GraphicsManager graphicsManager;

    // Pre-allocated buffers - reused each frame
    private final float[] vertexData;
    private final float[] texCoordData;
    private final float[] paletteCoordData;
    private int patternCount = 0;

    // Screen height for Y coordinate flipping
    private final int screenHeight;

    // Track whether a batch is currently active
    private boolean batchActive = false;

    // Track whether a shadow batch is active (uses different shader and blend mode)
    private boolean shadowBatchActive = false;
    private int shadowAtlasIndex;

    public static synchronized BatchedPatternRenderer getInstance() {
        if (instance == null) {
            instance = new BatchedPatternRenderer();
        }
        return instance;
    }

    public static synchronized BatchedPatternRenderer getInstanceIfInitialized() {
        return instance;
    }

    public BatchedPatternRenderer() {
        this(GameServices.graphics(), GameServices.configuration());
    }

    public BatchedPatternRenderer(GraphicsManager graphicsManager, SonicConfigurationService configService) {
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        Objects.requireNonNull(configService, "configService");
        this.screenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        this.vertexData = new float[MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_VERTS];
        this.texCoordData = new float[MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_TEXCOORDS];
        this.paletteCoordData = new float[MAX_PATTERNS_PER_BATCH * 6];
    }

    // Display height resolved once per batch in beginBatch()/beginShadowBatch()
    // and reused by every addPattern call (thousands per frame). Safe because FBO
    // projection state never changes between batch begin and end: call sites
    // (e.g. special-stage background renderers) set up FBO projection BEFORE
    // creating the batch and restore it after the batch is flushed.
    private int batchDisplayHeight;

    /**
     * Resolves the current display height for Y coordinate calculations.
     * When rendering to an FBO, this returns the FBO height.
     * Otherwise returns the normal screen height.
     */
    private int resolveDisplayHeight() {
        Engine engine = graphicsManager.getEngine();
        if (engine != null && engine.isFBOProjectionActive()) {
            return engine.getCurrentDisplayHeight();
        }
        return screenHeight;
    }

    private final ArrayDeque<BatchRenderCommand> batchCommandPool = new ArrayDeque<>();
    private final ArrayDeque<ShadowBatchRenderCommand> shadowCommandPool = new ArrayDeque<>();

    /**
     * Begin a new rendering batch.
     */
    public void beginBatch() {
        patternCount = 0;
        batchDisplayHeight = resolveDisplayHeight();
        batchActive = true;
    }

    /**
     * Check if a batch is currently active.
     */
    public boolean isBatchActive() {
        return batchActive;
    }

    /**
     * Writes a 2-triangle quad (6 vertices) into the vertex and texcoord arrays.
     */
    private void writeQuad(int vertOffset, int texOffset,
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1) {
        // Triangle 1: bottom-left, bottom-right, top-right
        vertexData[vertOffset]      = x0;
        vertexData[vertOffset + 1]  = y0;
        vertexData[vertOffset + 2]  = x1;
        vertexData[vertOffset + 3]  = y0;
        vertexData[vertOffset + 4]  = x1;
        vertexData[vertOffset + 5]  = y1;
        // Triangle 2: bottom-left, top-right, top-left
        vertexData[vertOffset + 6]  = x0;
        vertexData[vertOffset + 7]  = y0;
        vertexData[vertOffset + 8]  = x1;
        vertexData[vertOffset + 9]  = y1;
        vertexData[vertOffset + 10] = x0;
        vertexData[vertOffset + 11] = y1;

        // Triangle 1 texture coords
        texCoordData[texOffset]      = u0;
        texCoordData[texOffset + 1]  = v0;
        texCoordData[texOffset + 2]  = u1;
        texCoordData[texOffset + 3]  = v0;
        texCoordData[texOffset + 4]  = u1;
        texCoordData[texOffset + 5]  = v1;
        // Triangle 2 texture coords
        texCoordData[texOffset + 6]  = u0;
        texCoordData[texOffset + 7]  = v0;
        texCoordData[texOffset + 8]  = u1;
        texCoordData[texOffset + 9]  = v1;
        texCoordData[texOffset + 10] = u0;
        texCoordData[texOffset + 11] = v1;
    }

    /**
     * Add a pattern to the current batch.
     *
     * @return true if the pattern was added, false if batch is full or not active
     */
    public boolean addPattern(PatternAtlas.Entry entry, int paletteIndex, PatternDesc desc, int x, int y) {
        if (!batchActive || patternCount >= MAX_PATTERNS_PER_BATCH) {
            return false;
        }

        // Convert Y to screen coordinates (flip Y axis)
        // Genesis Y refers to the TOP of the pattern, so we subtract the pattern height
        // (8)
        // to get the OpenGL Y coordinate for the bottom of the quad
        // Display height resolved once per batch in beginBatch() (FBO-aware)
        int screenY = batchDisplayHeight - y - 8;

        // Compute the 4 corners of the quad
        float x0 = x;
        float y0 = screenY;
        float x1 = x + 8;
        float y1 = screenY + 8;

        // Handle flips by adjusting texture coordinates
        // Note: VFlip=false means apply vertical flip (this is the default)
        float u0 = entry.u0();
        float u1 = entry.u1();
        float v0 = entry.v0();
        float v1 = entry.v1();
        if (desc.getHFlip()) {
            float tmp = u0;
            u0 = u1;
            u1 = tmp;
        }
        if (!desc.getVFlip()) {
            float tmp = v0;
            v0 = v1;
            v1 = tmp;
        }

        int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
        int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
        writeQuad(vertOffset, texOffset, x0, y0, x1, y1, u0, v0, u1, v1);

        int paletteOffset = patternCount * 6;
        paletteCoordData[paletteOffset + 0] = paletteIndex;
        paletteCoordData[paletteOffset + 1] = paletteIndex;
        paletteCoordData[paletteOffset + 2] = paletteIndex;
        paletteCoordData[paletteOffset + 3] = paletteIndex;
        paletteCoordData[paletteOffset + 4] = paletteIndex;
        paletteCoordData[paletteOffset + 5] = paletteIndex;
        patternCount++;

        return true;
    }

    /**
     * Add a strip pattern to the current batch for special stage track rendering.
     *
     * The Sonic 2 special stage track uses per-scanline horizontal scroll to create
     * a pseudo-3D halfpipe effect. Each 8x8 tile is shown as 4 strips of 2
     * scanlines
     * each. This method renders a single strip (8 wide × 2 high).
     *
     * @param entry            Atlas entry for the pattern
     * @param paletteIndex     The palette line to use
     * @param desc             The pattern descriptor (handles H/V flip)
     * @param x                Screen X position
     * @param y                Screen Y position (of the strip, not the full tile)
     * @param stripIndex       Which 2-scanline strip to render (0-3, where 0 is top
     *                         of tile)
     * @return true if the pattern was added, false if batch is full or not active
     */
    public boolean addStripPattern(PatternAtlas.Entry entry, int paletteIndex, PatternDesc desc,
            int x, int y, int stripIndex) {
        if (!batchActive || patternCount >= MAX_PATTERNS_PER_BATCH) {
            return false;
        }

        // Convert Y to screen coordinates (flip Y axis)
        // Genesis Y=0 is top of screen, OpenGL Y=0 is bottom
        // For a 2-pixel strip at Genesis Y, the OpenGL bottom should be:
        // currentHeight - y - stripHeight
        // This ensures Genesis Y=0 maps to OpenGL Y at top of screen
        // Display height resolved once per batch in beginBatch() (FBO-aware)
        int screenY = batchDisplayHeight - y - 2;

        // Compute the 4 corners of the quad (8 wide × 2 high)
        float x0 = x;
        float y0 = screenY; // Bottom of quad in OpenGL
        float x1 = x + 8;
        float y1 = screenY + 2; // Top of quad in OpenGL

        // Calculate texture V coordinates for this strip using PIXEL-CENTER
        // coordinates.
        // Strip 0 = rows 0-1 (top), Strip 3 = rows 6-7 (bottom)
        //
        // CRITICAL: With GL_NEAREST, we must sample at pixel centers, not edges!
        // Using edge coordinates (0.0, 0.25, 0.5, 0.75) causes boundary pixels to
        // potentially sample the wrong texture row due to floating-point precision.
        //
        // For an 8-pixel tall texture:
        // Row 0 center: v = 0.5/8 = 0.0625
        // Row 1 center: v = 1.5/8 = 0.1875
        // Row 2 center: v = 2.5/8 = 0.3125
        // ...etc
        //
        // Each strip shows 2 rows. We sample at the center of each row:
        // Strip 0 (rows 0-1): top=0.0625, bottom=0.1875
        // Strip 1 (rows 2-3): top=0.3125, bottom=0.4375
        // Strip 2 (rows 4-5): top=0.5625, bottom=0.6875
        // Strip 3 (rows 6-7): top=0.8125, bottom=0.9375
        int rowTop = stripIndex * 2;
        int rowBottom = stripIndex * 2 + 1;
        float rowStep = (entry.v1() - entry.v0()) / 8.0f;
        float stripTop = entry.v0() + rowStep * ((7 - rowTop) + 0.5f);
        float stripBottom = entry.v0() + rowStep * ((7 - rowBottom) + 0.5f);

        // Handle flips by adjusting texture coordinates
        float u0, u1, v0, v1;
        if (desc.getHFlip()) {
            u0 = entry.u1();
            u1 = entry.u0();
        } else {
            u0 = entry.u0();
            u1 = entry.u1();
        }

        // V coordinates for the strip
        // Default (VFlip=false): flip texture so row 0 is at top of quad
        // Bottom of quad gets stripBottom, top gets stripTop
        // VFlip=true: don't flip, so row 0 is at bottom of quad
        // Bottom of quad gets stripTop, top gets stripBottom
        if (desc.getVFlip()) {
            v0 = stripTop; // Bottom of quad
            v1 = stripBottom; // Top of quad
        } else {
            v0 = stripBottom; // Bottom of quad
            v1 = stripTop; // Top of quad
        }

        int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
        int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
        writeQuad(vertOffset, texOffset, x0, y0, x1, y1, u0, v0, u1, v1);

        int paletteOffset = patternCount * 6;
        paletteCoordData[paletteOffset + 0] = paletteIndex;
        paletteCoordData[paletteOffset + 1] = paletteIndex;
        paletteCoordData[paletteOffset + 2] = paletteIndex;
        paletteCoordData[paletteOffset + 3] = paletteIndex;
        paletteCoordData[paletteOffset + 4] = paletteIndex;
        paletteCoordData[paletteOffset + 5] = paletteIndex;
        patternCount++;

        return true;
    }

    /**
     * Check if the batch has any patterns to render.
     */
    public boolean isEmpty() {
        return patternCount == 0;
    }

    /**
     * Get the number of patterns in the current batch.
     */
    public int getPatternCount() {
        return patternCount;
    }

    /**
     * End the current batch and return a command that can be queued.
     * This creates a snapshot of the batch data so it can be rendered later in the
     * correct order.
     */
    public GLCommandable endBatch() {
        if (patternCount == 0) {
            batchActive = false;
            return null;
        }

        GraphicsManager gm = graphicsManager;
        boolean usePriority = gm.isUseSpritePriorityShader();
        int tileOcclusionPaletteMask = gm.getCurrentSpriteTileOcclusionPaletteMask();
        boolean ghostEffectActive = gm.isGhostRenderEffectActive();
        float ghostAlpha = gm.getGhostRenderAlpha();
        BatchRenderCommand command = obtainBatchCommand();
        command.load(vertexData, texCoordData, paletteCoordData, patternCount,
                usePriority, tileOcclusionPaletteMask, ghostEffectActive, ghostAlpha);

        // Reset for next batch
        patternCount = 0;
        batchActive = false;

        return command;
    }

    // =====================================================================
    // Shadow Batch Methods - for VDP shadow/highlight mode
    // =====================================================================

    /**
     * Begin a new shadow rendering batch.
     * Shadow batches use multiplicative blending to darken the background
     * where shadow pixels are rendered (VDP shadow/highlight mode).
     */
    public void beginShadowBatch() {
        beginShadowBatch(0);
    }

    public void beginShadowBatch(int atlasIndex) {
        patternCount = 0;
        batchDisplayHeight = resolveDisplayHeight();
        shadowAtlasIndex = atlasIndex;
        shadowBatchActive = true;
        batchActive = false; // Ensure normal batch is not active
    }

    /**
     * Check if a shadow batch is currently active.
     */
    public boolean isShadowBatchActive() {
        return shadowBatchActive;
    }

    /**
     * Add a pattern to the current shadow batch.
     * Uses the same buffer management as normal batches.
     */
    public boolean addShadowPattern(PatternAtlas.Entry entry, PatternDesc desc, int x, int y) {
        if (!shadowBatchActive || patternCount >= MAX_PATTERNS_PER_BATCH
                || entry.atlasIndex() != shadowAtlasIndex) {
            return false;
        }

        // Convert Y to screen coordinates (flip Y axis)
        // Display height resolved once per batch in beginShadowBatch() (FBO-aware)
        int screenY = batchDisplayHeight - y - 8;

        // Compute the 4 corners of the quad
        float x0 = x;
        float y0 = screenY;
        float x1 = x + 8;
        float y1 = screenY + 8;

        // Handle flips by adjusting texture coordinates
        float u0 = entry.u0();
        float u1 = entry.u1();
        float v0 = entry.v0();
        float v1 = entry.v1();
        if (desc.getHFlip()) {
            float tmp = u0;
            u0 = u1;
            u1 = tmp;
        }
        if (!desc.getVFlip()) {
            float tmp = v0;
            v0 = v1;
            v1 = tmp;
        }

        int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
        int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
        writeQuad(vertOffset, texOffset, x0, y0, x1, y1, u0, v0, u1, v1);

        patternCount++;

        return true;
    }

    /**
     * End the current shadow batch and return a command that can be queued.
     */
    public GLCommandable endShadowBatch() {
        if (patternCount == 0) {
            shadowBatchActive = false;
            return null;
        }

        ShadowBatchRenderCommand command = obtainShadowCommand();
        command.load(vertexData, texCoordData, patternCount, shadowAtlasIndex);

        // Reset for next batch
        patternCount = 0;
        shadowBatchActive = false;

        return command;
    }

    private BatchRenderCommand obtainBatchCommand() {
        BatchRenderCommand command = batchCommandPool.pollFirst();
        if (command == null) {
            command = new BatchRenderCommand();
        }
        command.leased = true;
        return command;
    }

    private ShadowBatchRenderCommand obtainShadowCommand() {
        ShadowBatchRenderCommand command = shadowCommandPool.pollFirst();
        if (command == null) {
            command = new ShadowBatchRenderCommand();
        }
        command.leased = true;
        return command;
    }

    private void recycleBatchCommand(BatchRenderCommand command) {
        if (batchCommandPool.size() < COMMAND_POOL_LIMIT) {
            batchCommandPool.addLast(command);
        } else {
            command.dispose();
        }
    }

    private void recycleShadowCommand(ShadowBatchRenderCommand command) {
        if (shadowCommandPool.size() < COMMAND_POOL_LIMIT) {
            shadowCommandPool.addLast(command);
        } else {
            command.dispose();
        }
    }

    public void cleanup() {
        for (BatchRenderCommand command : batchCommandPool) {
            command.dispose();
        }
        batchCommandPool.clear();
        for (ShadowBatchRenderCommand command : shadowCommandPool) {
            command.dispose();
        }
        shadowCommandPool.clear();
    }

    /**
     * Cleanup for headless mode (no GL context available).
     * Clears internal state without making GL calls.
     */
    public void cleanupHeadless() {
        for (BatchRenderCommand command : batchCommandPool) {
            command.releaseNativeBuffers();
        }
        for (ShadowBatchRenderCommand command : shadowCommandPool) {
            command.releaseNativeBuffers();
        }
        batchCommandPool.clear();
        shadowCommandPool.clear();
        patternCount = 0;
        batchActive = false;
        shadowBatchActive = false;
    }

    /**
     * Command that renders a batch of patterns.
     * This is a snapshot of batch data that can be queued for later execution.
     */
    private class BatchRenderCommand implements GLCommandable {
        private int patternCount;
        private int vertexFloatCount;
        private int texCoordFloatCount;
        private int paletteFloatCount;
        private boolean usePriorityShader;
        private int capturedTileOcclusionPaletteMask;
        private boolean capturedGhostEffectActive;
        private float capturedGhostAlpha;
        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;
        private FloatBuffer paletteCoordBuffer;
        private boolean leased;

        private int vaoId;
        private int vertexVboId;
        private int texCoordVboId;
        private int paletteVboId;

        // Vertex attribute locations (standard layout)
        private static final int ATTRIB_POSITION = 0;
        private static final int ATTRIB_TEXCOORD = 1;
        private static final int ATTRIB_PALETTE = 2;

        // Cached uniform locations to avoid per-batch glGetUniformLocation calls
        private int cachedProjectionLoc = -2;  // -2 = not yet cached (-1 is valid GL "not found")
        private int cachedCameraOffsetLoc = -2;
        private int cachedShaderProgramId = -1;

        private void load(float[] vertexData, float[] texCoordData, float[] paletteCoordData,
                          int patternCount, boolean usePriorityShader, int tileOcclusionPaletteMask,
                          boolean ghostEffectActive, float ghostAlpha) {
            this.patternCount = patternCount;
            this.usePriorityShader = usePriorityShader;
            this.capturedTileOcclusionPaletteMask = tileOcclusionPaletteMask;
            this.capturedGhostEffectActive = ghostEffectActive;
            this.capturedGhostAlpha = ghostAlpha;
            this.vertexFloatCount = patternCount * FLOATS_PER_PATTERN_VERTS;
            this.texCoordFloatCount = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
            this.paletteFloatCount = patternCount * 6;
            vertexBuffer = ensureBuffer(vertexBuffer, vertexFloatCount);
            texCoordBuffer = ensureBuffer(texCoordBuffer, texCoordFloatCount);
            paletteCoordBuffer = ensureBuffer(paletteCoordBuffer, paletteFloatCount);

            vertexBuffer.clear();
            vertexBuffer.put(vertexData, 0, vertexFloatCount);
            vertexBuffer.flip();

            texCoordBuffer.clear();
            texCoordBuffer.put(texCoordData, 0, texCoordFloatCount);
            texCoordBuffer.flip();

            paletteCoordBuffer.clear();
            paletteCoordBuffer.put(paletteCoordData, 0, paletteFloatCount);
            paletteCoordBuffer.flip();
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            try {
                executeLeased(cameraX, cameraY, cameraWidth, cameraHeight);
            } finally {
                discard();
            }
        }

        private void executeLeased(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (patternCount == 0) {
                return;
            }
            ensureVbos();

            GraphicsManager gm = graphicsManager;
            // Use captured priority shader state from batch creation time
            ShaderProgram shader;
            if (usePriorityShader) {
                SpritePriorityShaderProgram priorityShader = gm.getSpritePriorityShaderProgram();
                shader = (priorityShader != null) ? priorityShader : gm.getShaderProgram();
            } else {
                shader = gm.getShaderProgram();
            }

            // Setup state once for entire batch
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            shader.use();
            shader.cacheUniformLocations();

            // Set texture unit uniforms once
            glUniform1i(shader.getPaletteLocation(), 0);
            glUniform1i(shader.getIndexedColorTextureLocation(), 1);
            shader.setPaletteLine(-1.0f);
            shader.setTotalPaletteLines((float) RenderContext.getTotalPaletteLines());
            shader.setGhostEffect(capturedGhostEffectActive, capturedGhostAlpha);

            // Cache uniform locations per shader program to avoid per-batch string lookups
            int programId = shader.getProgramId();
            if (programId != cachedShaderProgramId) {
                cachedProjectionLoc = glGetUniformLocation(programId, "ProjectionMatrix");
                cachedCameraOffsetLoc = glGetUniformLocation(programId, "CameraOffset");
                cachedShaderProgramId = programId;
            }

            // Set projection matrix uniform - REQUIRED for correct rendering
            if (cachedProjectionLoc != -1) {
                float[] projMatrix = gm.getProjectionMatrixBuffer();
                if (projMatrix != null) {
                    glUniformMatrix4fv(cachedProjectionLoc, false, projMatrix);
                }
            }

            // Set camera offset uniform (replaces glTranslatef)
            // X is negated to scroll objects left when camera moves right
            // Y is NOT negated because vertex Y is already in screen space (flipped from Genesis coords)
            // When camera moves down in Genesis (cameraY increases), objects should move UP on screen
            if (cachedCameraOffsetLoc != -1) {
                glUniform2f(cachedCameraOffsetLoc, -cameraX, cameraY);
            }

            // Set priority uniform if using sprite priority shader.
            // Use the priority captured at batch creation time (not the current global
            // state, which may have changed since the batch was created).
            if (shader instanceof SpritePriorityShaderProgram priorityShader) {
                priorityShader.setTileOcclusionPaletteMask(capturedTileOcclusionPaletteMask);

                // Bind tile priority FBO texture to unit 5 (avoid conflict with TilemapGpuRenderer which uses 0-4)
                TilePriorityFBO fbo = gm.getTilePriorityFBO();
                if (fbo != null && fbo.isInitialized()) {
                    glActiveTexture(GL_TEXTURE5);
                    glBindTexture(GL_TEXTURE_2D, fbo.getTextureId());
                    priorityShader.setTilePriorityTexture(5);

                    // Use cached viewport dimensions from GraphicsManager
                    // instead of expensive glGetIntegerv(GL_VIEWPORT) every batch
                    priorityShader.setScreenSize(gm.getViewportWidth(), gm.getViewportHeight());
                    priorityShader.setViewportOffset(gm.getViewportX(), gm.getViewportY());
                    glActiveTexture(GL_TEXTURE0);
                }

                // Bind underwater palette for per-scanline palette switching
                Integer underwaterPaletteId = gm.getUnderwaterPaletteTextureId();
                if (underwaterPaletteId != null) {
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D, underwaterPaletteId);
                    int loc = priorityShader.getUnderwaterPaletteLocation();
                    if (loc != -1) {
                        glUniform1i(loc, 2);
                    }
                    glActiveTexture(GL_TEXTURE0);
                }

                // Set water uniforms from cached values in GraphicsManager
                priorityShader.setWaterEnabled(gm.isWaterEnabled());
                priorityShader.setWaterlineScreenY(gm.getWaterlineScreenY());
                priorityShader.setWindowHeight(gm.getWindowHeight());
                priorityShader.setScreenHeight(gm.getScreenHeight());
            }

            // Bind VAO (required for core profile, encapsulates vertex attribute state)
            glBindVertexArray(vaoId);

            // Bind palette texture (use underwater palette if flag is set for background
            // rendering)
            glActiveTexture(GL_TEXTURE0);
            Integer paletteTextureId;
            if (gm.isUseUnderwaterPaletteForBackground()) {
                // Use underwater palette for entire background when Sonic is underwater
                paletteTextureId = gm.getUnderwaterPaletteTextureId();
                if (paletteTextureId == null) {
                    // Fallback to normal palette if underwater palette not available
                    paletteTextureId = gm.getCombinedPaletteTextureId();
                }
            } else {
                paletteTextureId = gm.getCombinedPaletteTextureId();
            }
            if (paletteTextureId != null) {
                glBindTexture(GL_TEXTURE_2D, paletteTextureId);
            }

            Integer atlasTextureId = gm.getPatternAtlasTextureId();
            if (atlasTextureId != null) {
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, atlasTextureId);
            }

            // If using water shader, bind underwater palette to texture unit 2
            if (shader instanceof WaterShaderProgram) {
                WaterShaderProgram waterShader = (WaterShaderProgram) shader;
                Integer underwaterPaletteId = gm.getUnderwaterPaletteTextureId();
                if (underwaterPaletteId != null) {
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D, underwaterPaletteId);
                    int loc = waterShader.getUnderwaterPaletteLocation();
                    if (loc != -1) {
                        glUniform1i(loc, 2);
                    }
                    glActiveTexture(GL_TEXTURE0);
                }
            }

            // Upload vertex data to VBOs using modern vertex attributes
            glBindBuffer(GL_ARRAY_BUFFER, vertexVboId);
            vertexBuffer.rewind();
            vertexBuffer.limit(vertexFloatCount);
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(ATTRIB_POSITION, 2, GL_FLOAT, false, 0, 0L);
            glEnableVertexAttribArray(ATTRIB_POSITION);

            glBindBuffer(GL_ARRAY_BUFFER, texCoordVboId);
            texCoordBuffer.rewind();
            texCoordBuffer.limit(texCoordFloatCount);
            glBufferData(GL_ARRAY_BUFFER, texCoordBuffer, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(ATTRIB_TEXCOORD, 2, GL_FLOAT, false, 0, 0L);
            glEnableVertexAttribArray(ATTRIB_TEXCOORD);

            glBindBuffer(GL_ARRAY_BUFFER, paletteVboId);
            paletteCoordBuffer.rewind();
            paletteCoordBuffer.limit(paletteFloatCount);
            glBufferData(GL_ARRAY_BUFFER, paletteCoordBuffer, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(ATTRIB_PALETTE, 1, GL_FLOAT, false, 0, 0L);
            glEnableVertexAttribArray(ATTRIB_PALETTE);

            glDrawArrays(GL_TRIANGLES, 0, patternCount * 6);

            // Cleanup state
            glDisableVertexAttribArray(ATTRIB_POSITION);
            glDisableVertexAttribArray(ATTRIB_TEXCOORD);
            glDisableVertexAttribArray(ATTRIB_PALETTE);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
            shader.stop();
            glDisable(GL_BLEND);

            // Reset PatternRenderCommand state tracking so subsequent patterns
            // will properly reinitialize GL state (since we just disabled everything)
            PatternRenderCommand.resetFrameState();

        }

        @Override
        public void discard() {
            if (!leased) {
                return;
            }
            leased = false;
            recycleBatchCommand(this);
        }

        private void ensureVbos() {
            if (vaoId != 0) {
                return;
            }
            // Create VAO (required for OpenGL 3.2+ core profile)
            vaoId = glGenVertexArrays();
            vertexVboId = glGenBuffers();
            texCoordVboId = glGenBuffers();
            paletteVboId = glGenBuffers();
        }

        /**
         * Ensures buffer has required capacity, pre-allocating at max capacity
         * to avoid mid-frame native memory allocations which can be expensive.
         */
        private FloatBuffer ensureBuffer(FloatBuffer buffer, int required) {
            if (buffer == null) {
                // Pre-allocate at max batch capacity to avoid later reallocations
                return MemoryUtil.memAllocFloat(MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_VERTS);
            }
            if (buffer.capacity() < required) {
                MemoryUtil.memFree(buffer);
                return MemoryUtil.memAllocFloat(MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_VERTS);
            }
            return buffer;
        }

        private void releaseNativeBuffers() {
            if (vertexBuffer != null) {
                MemoryUtil.memFree(vertexBuffer);
                vertexBuffer = null;
            }
            if (texCoordBuffer != null) {
                MemoryUtil.memFree(texCoordBuffer);
                texCoordBuffer = null;
            }
            if (paletteCoordBuffer != null) {
                MemoryUtil.memFree(paletteCoordBuffer);
                paletteCoordBuffer = null;
            }
        }

        private void dispose() {
            if (vaoId != 0) {
                glDeleteVertexArrays(vaoId);
                vaoId = 0;
            }
            if (vertexVboId != 0) {
                glDeleteBuffers(vertexVboId);
                vertexVboId = 0;
            }
            if (texCoordVboId != 0) {
                glDeleteBuffers(texCoordVboId);
                texCoordVboId = 0;
            }
            if (paletteVboId != 0) {
                glDeleteBuffers(paletteVboId);
                paletteVboId = 0;
            }
            releaseNativeBuffers();
        }
    }

    /**
     * Command that renders a batch of shadow patterns.
     * Uses the shadow shader and multiplicative blending to darken the background.
     * This implements VDP shadow/highlight mode where palette index 14 darkens
     * pixels.
     */
    private class ShadowBatchRenderCommand implements GLCommandable {
        private int patternCount;
        private int vertexFloatCount;
        private int texCoordFloatCount;
        private int atlasIndex;

        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;
        private boolean leased;

        private int vaoId;
        private int vertexVboId;
        private int texCoordVboId;

        // Vertex attribute locations (standard layout)
        private static final int ATTRIB_POSITION = 0;
        private static final int ATTRIB_TEXCOORD = 1;

        // Cached uniform locations to avoid per-batch glGetUniformLocation calls
        private int cachedProjectionLoc = -2;  // -2 = not yet cached (-1 is valid GL "not found")
        private int cachedCameraOffsetLoc = -2;
        private int cachedShaderProgramId = -1;

        private void load(float[] vertexData, float[] texCoordData, int patternCount, int atlasIndex) {
            this.patternCount = patternCount;
            this.atlasIndex = atlasIndex;
            this.vertexFloatCount = patternCount * FLOATS_PER_PATTERN_VERTS;
            this.texCoordFloatCount = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;

            vertexBuffer = ensureBuffer(vertexBuffer, vertexFloatCount);
            texCoordBuffer = ensureBuffer(texCoordBuffer, texCoordFloatCount);

            vertexBuffer.clear();
            vertexBuffer.put(vertexData, 0, vertexFloatCount);
            vertexBuffer.flip();

            texCoordBuffer.clear();
            texCoordBuffer.put(texCoordData, 0, texCoordFloatCount);
            texCoordBuffer.flip();
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            try {
                executeLeased(cameraX, cameraY, cameraWidth, cameraHeight);
            } finally {
                discard();
            }
        }

        private void executeLeased(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (patternCount == 0) {
                return;
            }
            ensureVbos();

            GraphicsManager gm = graphicsManager;
            ShaderProgram shadowShader = gm.getShadowShaderProgram();

            // Setup state for shadow rendering
            glEnable(GL_BLEND);
            // Multiplicative blending: result = dest * src
            // Shadow shader outputs 0.5 for index 14, which will halve (darken) the
            // background
            glBlendFunc(GL_ZERO, GL_SRC_COLOR);

            shadowShader.use();
            shadowShader.cacheUniformLocations();

            // Set texture unit uniform (shadow shader only needs the indexed texture)
            int indexedTexLoc = shadowShader.getIndexedColorTextureLocation();
            if (indexedTexLoc >= 0) {
                glUniform1i(indexedTexLoc, 0);
            }

            // Cache uniform locations per shader program to avoid per-batch string lookups
            int programId = shadowShader.getProgramId();
            if (programId != cachedShaderProgramId) {
                cachedProjectionLoc = glGetUniformLocation(programId, "ProjectionMatrix");
                cachedCameraOffsetLoc = glGetUniformLocation(programId, "CameraOffset");
                cachedShaderProgramId = programId;
            }

            // Set projection matrix uniform - REQUIRED for correct rendering
            if (cachedProjectionLoc != -1) {
                float[] projMatrix = gm.getProjectionMatrixBuffer();
                if (projMatrix != null) {
                    glUniformMatrix4fv(cachedProjectionLoc, false, projMatrix);
                }
            }

            // Set camera offset uniform (replaces glTranslatef)
            // X is negated to scroll objects left when camera moves right
            // Y is NOT negated because vertex Y is already in screen space (flipped from Genesis coords)
            if (cachedCameraOffsetLoc != -1) {
                glUniform2f(cachedCameraOffsetLoc, -cameraX, cameraY);
            }

            // Bind VAO (required for core profile)
            glBindVertexArray(vaoId);

            Integer atlasTextureId = resolveAtlasTextureId();
            if (atlasTextureId != null) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, atlasTextureId);
            }

            // Upload vertex data to VBOs using modern vertex attributes
            glBindBuffer(GL_ARRAY_BUFFER, vertexVboId);
            vertexBuffer.rewind();
            vertexBuffer.limit(vertexFloatCount);
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(ATTRIB_POSITION, 2, GL_FLOAT, false, 0, 0L);
            glEnableVertexAttribArray(ATTRIB_POSITION);

            glBindBuffer(GL_ARRAY_BUFFER, texCoordVboId);
            texCoordBuffer.rewind();
            texCoordBuffer.limit(texCoordFloatCount);
            glBufferData(GL_ARRAY_BUFFER, texCoordBuffer, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(ATTRIB_TEXCOORD, 2, GL_FLOAT, false, 0, 0L);
            glEnableVertexAttribArray(ATTRIB_TEXCOORD);

            glDrawArrays(GL_TRIANGLES, 0, patternCount * 6);

            // Cleanup state
            glDisableVertexAttribArray(ATTRIB_POSITION);
            glDisableVertexAttribArray(ATTRIB_TEXCOORD);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
            shadowShader.stop();
            glDisable(GL_BLEND);

            PatternRenderCommand.resetFrameState();

        }

        private Integer resolveAtlasTextureId() {
            return graphicsManager.getPatternAtlasTextureId(atlasIndex);
        }

        @Override
        public void discard() {
            if (!leased) {
                return;
            }
            leased = false;
            recycleShadowCommand(this);
        }

        private void ensureVbos() {
            if (vaoId != 0) {
                return;
            }
            // Create VAO (required for OpenGL 3.2+ core profile)
            vaoId = glGenVertexArrays();
            vertexVboId = glGenBuffers();
            texCoordVboId = glGenBuffers();
        }

        /**
         * Ensures buffer has required capacity, pre-allocating at max capacity
         * to avoid mid-frame native memory allocations.
         */
        private FloatBuffer ensureBuffer(FloatBuffer buffer, int required) {
            if (buffer == null) {
                // Pre-allocate at max batch capacity
                return MemoryUtil.memAllocFloat(MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_VERTS);
            }
            if (buffer.capacity() < required) {
                MemoryUtil.memFree(buffer);
                return MemoryUtil.memAllocFloat(MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_VERTS);
            }
            return buffer;
        }

        private void releaseNativeBuffers() {
            if (vertexBuffer != null) {
                MemoryUtil.memFree(vertexBuffer);
                vertexBuffer = null;
            }
            if (texCoordBuffer != null) {
                MemoryUtil.memFree(texCoordBuffer);
                texCoordBuffer = null;
            }
        }

        private void dispose() {
            if (vaoId != 0) {
                glDeleteVertexArrays(vaoId);
                vaoId = 0;
            }
            if (vertexVboId != 0) {
                glDeleteBuffers(vertexVboId);
                vertexVboId = 0;
            }
            if (texCoordVboId != 0) {
                glDeleteBuffers(texCoordVboId);
                texCoordVboId = 0;
            }
            releaseNativeBuffers();
        }
    }
}
