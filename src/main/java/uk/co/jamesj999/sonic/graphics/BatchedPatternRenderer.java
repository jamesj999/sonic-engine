package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;

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

    // Maximum patterns per batch
    private static final int MAX_PATTERNS_PER_BATCH = 4096;
    private static final int COMMAND_POOL_LIMIT = 8;

    // 4 vertices per pattern quad, 2 floats (x,y) per vertex
    private static final int FLOATS_PER_PATTERN_VERTS = 4 * 2;
    // 4 vertices per pattern quad, 2 floats (u,v) per vertex
    private static final int FLOATS_PER_PATTERN_TEXCOORDS = 4 * 2;

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

    // Singleton instance
    private static BatchedPatternRenderer instance;

    public static synchronized BatchedPatternRenderer getInstance() {
        if (instance == null) {
            instance = new BatchedPatternRenderer();
        }
        return instance;
    }

    public static synchronized BatchedPatternRenderer getInstanceIfInitialized() {
        return instance;
    }

    private BatchedPatternRenderer() {
        this.screenHeight = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        this.vertexData = new float[MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_VERTS];
        this.texCoordData = new float[MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_TEXCOORDS];
        this.paletteCoordData = new float[MAX_PATTERNS_PER_BATCH * 4];
    }

    private final ArrayDeque<BatchRenderCommand> batchCommandPool = new ArrayDeque<>();
    private final ArrayDeque<ShadowBatchRenderCommand> shadowCommandPool = new ArrayDeque<>();

    /**
     * Begin a new rendering batch.
     */
    public void beginBatch() {
        patternCount = 0;
        batchActive = true;
    }

    /**
     * Check if a batch is currently active.
     */
    public boolean isBatchActive() {
        return batchActive;
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
        int screenY = screenHeight - y - 8;

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

        // Calculate array offsets
        int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
        int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;

        // Add vertices (quad: bottom-left, bottom-right, top-right, top-left)
        vertexData[vertOffset + 0] = x0;
        vertexData[vertOffset + 1] = y0;
        vertexData[vertOffset + 2] = x1;
        vertexData[vertOffset + 3] = y0;
        vertexData[vertOffset + 4] = x1;
        vertexData[vertOffset + 5] = y1;
        vertexData[vertOffset + 6] = x0;
        vertexData[vertOffset + 7] = y1;

        // Add texture coordinates
        texCoordData[texOffset + 0] = u0;
        texCoordData[texOffset + 1] = v0;
        texCoordData[texOffset + 2] = u1;
        texCoordData[texOffset + 3] = v0;
        texCoordData[texOffset + 4] = u1;
        texCoordData[texOffset + 5] = v1;
        texCoordData[texOffset + 6] = u0;
        texCoordData[texOffset + 7] = v1;

        int paletteOffset = patternCount * 4;
        paletteCoordData[paletteOffset + 0] = paletteIndex;
        paletteCoordData[paletteOffset + 1] = paletteIndex;
        paletteCoordData[paletteOffset + 2] = paletteIndex;
        paletteCoordData[paletteOffset + 3] = paletteIndex;
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
        // screenHeight - y - stripHeight = 224 - y - 2
        // This ensures Genesis Y=0 maps to OpenGL Y=222-224 (visible top of screen)
        int screenY = screenHeight - y - 2;

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

        // Calculate array offsets
        int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
        int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;

        // Add vertices (quad: bottom-left, bottom-right, top-right, top-left)
        vertexData[vertOffset + 0] = x0;
        vertexData[vertOffset + 1] = y0;
        vertexData[vertOffset + 2] = x1;
        vertexData[vertOffset + 3] = y0;
        vertexData[vertOffset + 4] = x1;
        vertexData[vertOffset + 5] = y1;
        vertexData[vertOffset + 6] = x0;
        vertexData[vertOffset + 7] = y1;

        // Add texture coordinates
        texCoordData[texOffset + 0] = u0;
        texCoordData[texOffset + 1] = v0;
        texCoordData[texOffset + 2] = u1;
        texCoordData[texOffset + 3] = v0;
        texCoordData[texOffset + 4] = u1;
        texCoordData[texOffset + 5] = v1;
        texCoordData[texOffset + 6] = u0;
        texCoordData[texOffset + 7] = v1;

        int paletteOffset = patternCount * 4;
        paletteCoordData[paletteOffset + 0] = paletteIndex;
        paletteCoordData[paletteOffset + 1] = paletteIndex;
        paletteCoordData[paletteOffset + 2] = paletteIndex;
        paletteCoordData[paletteOffset + 3] = paletteIndex;
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

        BatchRenderCommand command = obtainBatchCommand();
        command.load(vertexData, texCoordData, paletteCoordData, patternCount);

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
        patternCount = 0;
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
        if (!shadowBatchActive || patternCount >= MAX_PATTERNS_PER_BATCH) {
            return false;
        }

        // Convert Y to screen coordinates (flip Y axis)
        int screenY = screenHeight - y - 8;

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

        // Calculate array offsets
        int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
        int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;

        // Add vertices (quad: bottom-left, bottom-right, top-right, top-left)
        vertexData[vertOffset + 0] = x0;
        vertexData[vertOffset + 1] = y0;
        vertexData[vertOffset + 2] = x1;
        vertexData[vertOffset + 3] = y0;
        vertexData[vertOffset + 4] = x1;
        vertexData[vertOffset + 5] = y1;
        vertexData[vertOffset + 6] = x0;
        vertexData[vertOffset + 7] = y1;

        // Add texture coordinates
        texCoordData[texOffset + 0] = u0;
        texCoordData[texOffset + 1] = v0;
        texCoordData[texOffset + 2] = u1;
        texCoordData[texOffset + 3] = v0;
        texCoordData[texOffset + 4] = u1;
        texCoordData[texOffset + 5] = v1;
        texCoordData[texOffset + 6] = u0;
        texCoordData[texOffset + 7] = v1;

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
        command.load(vertexData, texCoordData, patternCount);

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
        return command;
    }

    private ShadowBatchRenderCommand obtainShadowCommand() {
        ShadowBatchRenderCommand command = shadowCommandPool.pollFirst();
        if (command == null) {
            command = new ShadowBatchRenderCommand();
        }
        return command;
    }

    private void recycleBatchCommand(BatchRenderCommand command, GL2 gl) {
        if (batchCommandPool.size() < COMMAND_POOL_LIMIT) {
            batchCommandPool.addLast(command);
        } else {
            command.dispose(gl);
        }
    }

    private void recycleShadowCommand(ShadowBatchRenderCommand command, GL2 gl) {
        if (shadowCommandPool.size() < COMMAND_POOL_LIMIT) {
            shadowCommandPool.addLast(command);
        } else {
            command.dispose(gl);
        }
    }

    public void cleanup(GL2 gl) {
        for (BatchRenderCommand command : batchCommandPool) {
            command.dispose(gl);
        }
        batchCommandPool.clear();
        for (ShadowBatchRenderCommand command : shadowCommandPool) {
            command.dispose(gl);
        }
        shadowCommandPool.clear();
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

        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;
        private FloatBuffer paletteCoordBuffer;

        private int vertexVboId;
        private int texCoordVboId;
        private int paletteVboId;

        private void load(float[] vertexData, float[] texCoordData, float[] paletteCoordData, int patternCount) {
            this.patternCount = patternCount;
            this.vertexFloatCount = patternCount * FLOATS_PER_PATTERN_VERTS;
            this.texCoordFloatCount = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
            this.paletteFloatCount = patternCount * 4;
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
        public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (patternCount == 0) {
                return;
            }
            ensureVbos(gl);

            GraphicsManager gm = GraphicsManager.getInstance();
            ShaderProgram shader = gm.getShaderProgram();

            // Setup state once for entire batch
            gl.glEnable(GL2.GL_BLEND);
            gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
            shader.use(gl);
            shader.cacheUniformLocations(gl);

            // Set texture unit uniforms once
            gl.glUniform1i(shader.getPaletteLocation(), 0);
            gl.glUniform1i(shader.getIndexedColorTextureLocation(), 1);
            shader.setPaletteLine(gl, -1.0f);

            // Enable vertex arrays
            gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glClientActiveTexture(GL2.GL_TEXTURE0);
            gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            gl.glClientActiveTexture(GL2.GL_TEXTURE1);
            gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);

            // Bind palette texture (use underwater palette if flag is set for background
            // rendering)
            gl.glActiveTexture(GL2.GL_TEXTURE0);
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
                gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);
            }

            Integer atlasTextureId = gm.getPatternAtlasTextureId();
            if (atlasTextureId != null) {
                gl.glActiveTexture(GL2.GL_TEXTURE1);
                gl.glBindTexture(GL2.GL_TEXTURE_2D, atlasTextureId);
            }

            // If using water shader, bind underwater palette to texture unit 2
            if (shader instanceof WaterShaderProgram) {
                WaterShaderProgram waterShader = (WaterShaderProgram) shader;
                Integer underwaterPaletteId = gm.getUnderwaterPaletteTextureId();
                if (underwaterPaletteId != null) {
                    gl.glActiveTexture(GL2.GL_TEXTURE2);
                    gl.glBindTexture(GL2.GL_TEXTURE_2D, underwaterPaletteId);
                    int loc = waterShader.getUnderwaterPaletteLocation();
                    if (loc != -1) {
                        gl.glUniform1i(loc, 2);
                    }
                    gl.glActiveTexture(GL2.GL_TEXTURE0);
                }
            }

            gl.glPushMatrix();
            gl.glTranslatef(-cameraX, cameraY, 0);

            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, vertexVboId);
            vertexBuffer.rewind();
            vertexBuffer.limit(vertexFloatCount);
            gl.glBufferData(GL2.GL_ARRAY_BUFFER, (long) vertexFloatCount * Float.BYTES, vertexBuffer,
                    GL2.GL_STREAM_DRAW);
            gl.glVertexPointer(2, GL2.GL_FLOAT, 0, 0L);
            gl.glClientActiveTexture(GL2.GL_TEXTURE0);
            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, texCoordVboId);
            texCoordBuffer.rewind();
            texCoordBuffer.limit(texCoordFloatCount);
            gl.glBufferData(GL2.GL_ARRAY_BUFFER, (long) texCoordFloatCount * Float.BYTES, texCoordBuffer,
                    GL2.GL_STREAM_DRAW);
            gl.glTexCoordPointer(2, GL2.GL_FLOAT, 0, 0L);
            gl.glClientActiveTexture(GL2.GL_TEXTURE1);
            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, paletteVboId);
            paletteCoordBuffer.rewind();
            paletteCoordBuffer.limit(paletteFloatCount);
            gl.glBufferData(GL2.GL_ARRAY_BUFFER, (long) paletteFloatCount * Float.BYTES, paletteCoordBuffer,
                    GL2.GL_STREAM_DRAW);
            gl.glTexCoordPointer(1, GL2.GL_FLOAT, 0, 0L);
            gl.glClientActiveTexture(GL2.GL_TEXTURE0);

            gl.glDrawArrays(GL2.GL_QUADS, 0, patternCount * 4);

            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, 0);
            gl.glPopMatrix();

            // Cleanup state
            gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glClientActiveTexture(GL2.GL_TEXTURE1);
            gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            gl.glClientActiveTexture(GL2.GL_TEXTURE0);
            gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            shader.stop(gl);
            gl.glDisable(GL2.GL_BLEND);

            // Reset PatternRenderCommand state tracking so subsequent patterns
            // will properly reinitialize GL state (since we just disabled everything)
            PatternRenderCommand.resetFrameState();

            recycleBatchCommand(this, gl);
        }

        private void ensureVbos(GL2 gl) {
            if (vertexVboId != 0) {
                return;
            }
            int[] buffers = new int[3];
            gl.glGenBuffers(3, buffers, 0);
            vertexVboId = buffers[0];
            texCoordVboId = buffers[1];
            paletteVboId = buffers[2];
        }

        private FloatBuffer ensureBuffer(FloatBuffer buffer, int required) {
            if (buffer == null || buffer.capacity() < required) {
                return GLBuffers.newDirectFloatBuffer(required);
            }
            return buffer;
        }

        private void dispose(GL2 gl) {
            if (gl == null) {
                return;
            }
            int[] buffers = new int[] { vertexVboId, texCoordVboId, paletteVboId };
            gl.glDeleteBuffers(3, buffers, 0);
            vertexVboId = 0;
            texCoordVboId = 0;
            paletteVboId = 0;
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

        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;

        private int vertexVboId;
        private int texCoordVboId;

        private void load(float[] vertexData, float[] texCoordData, int patternCount) {
            this.patternCount = patternCount;
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
        public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (patternCount == 0) {
                return;
            }
            ensureVbos(gl);

            GraphicsManager gm = GraphicsManager.getInstance();
            ShaderProgram shadowShader = gm.getShadowShaderProgram();

            // Setup state for shadow rendering
            gl.glEnable(GL2.GL_BLEND);
            // Multiplicative blending: result = dest * src
            // Shadow shader outputs 0.5 for index 14, which will halve (darken) the
            // background
            gl.glBlendFunc(GL2.GL_ZERO, GL2.GL_SRC_COLOR);

            shadowShader.use(gl);
            shadowShader.cacheUniformLocations(gl);

            // Set texture unit uniform (shadow shader only needs the indexed texture)
            int indexedTexLoc = shadowShader.getIndexedColorTextureLocation();
            if (indexedTexLoc >= 0) {
                gl.glUniform1i(indexedTexLoc, 0);
            }

            // Enable vertex arrays
            gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);

            Integer atlasTextureId = gm.getPatternAtlasTextureId();
            if (atlasTextureId != null) {
                gl.glActiveTexture(GL2.GL_TEXTURE0);
                gl.glBindTexture(GL2.GL_TEXTURE_2D, atlasTextureId);
            }

            gl.glPushMatrix();
            gl.glTranslatef(-cameraX, cameraY, 0);

            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, vertexVboId);
            vertexBuffer.rewind();
            vertexBuffer.limit(vertexFloatCount);
            gl.glBufferData(GL2.GL_ARRAY_BUFFER, (long) vertexFloatCount * Float.BYTES, vertexBuffer,
                    GL2.GL_STREAM_DRAW);
            gl.glVertexPointer(2, GL2.GL_FLOAT, 0, 0L);

            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, texCoordVboId);
            texCoordBuffer.rewind();
            texCoordBuffer.limit(texCoordFloatCount);
            gl.glBufferData(GL2.GL_ARRAY_BUFFER, (long) texCoordFloatCount * Float.BYTES, texCoordBuffer,
                    GL2.GL_STREAM_DRAW);
            gl.glTexCoordPointer(2, GL2.GL_FLOAT, 0, 0L);
            gl.glDrawArrays(GL2.GL_QUADS, 0, patternCount * 4);

            gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, 0);
            gl.glPopMatrix();

            // Cleanup state
            gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            shadowShader.stop(gl);
            gl.glDisable(GL2.GL_BLEND);

            PatternRenderCommand.resetFrameState();

            recycleShadowCommand(this, gl);
        }

        private void ensureVbos(GL2 gl) {
            if (vertexVboId != 0) {
                return;
            }
            int[] buffers = new int[2];
            gl.glGenBuffers(2, buffers, 0);
            vertexVboId = buffers[0];
            texCoordVboId = buffers[1];
        }

        private FloatBuffer ensureBuffer(FloatBuffer buffer, int required) {
            if (buffer == null || buffer.capacity() < required) {
                return GLBuffers.newDirectFloatBuffer(required);
            }
            return buffer;
        }

        private void dispose(GL2 gl) {
            if (gl == null) {
                return;
            }
            int[] buffers = new int[] { vertexVboId, texCoordVboId };
            gl.glDeleteBuffers(2, buffers, 0);
            vertexVboId = 0;
            texCoordVboId = 0;
        }
    }
}
