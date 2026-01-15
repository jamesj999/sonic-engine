package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.nio.FloatBuffer;
import java.util.Arrays;

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

    // 4 vertices per pattern quad, 2 floats (x,y) per vertex
    private static final int FLOATS_PER_PATTERN_VERTS = 4 * 2;
    // 4 vertices per pattern quad, 2 floats (u,v) per vertex
    private static final int FLOATS_PER_PATTERN_TEXCOORDS = 4 * 2;

    // Pre-allocated buffers - reused each frame
    private final float[] vertexData;
    private final float[] texCoordData;
    private final int[] patternTextureIds;
    private final int[] paletteIndices;
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

    private BatchedPatternRenderer() {
        this.screenHeight = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        this.vertexData = new float[MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_VERTS];
        this.texCoordData = new float[MAX_PATTERNS_PER_BATCH * FLOATS_PER_PATTERN_TEXCOORDS];
        this.patternTextureIds = new int[MAX_PATTERNS_PER_BATCH];
        this.paletteIndices = new int[MAX_PATTERNS_PER_BATCH];
    }

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
    public boolean addPattern(int patternTextureId, int paletteIndex, PatternDesc desc, int x, int y) {
        if (!batchActive || patternCount >= MAX_PATTERNS_PER_BATCH) {
            return false;
        }

        // Convert Y to screen coordinates (flip Y axis)
        // Genesis Y refers to the TOP of the pattern, so we subtract the pattern height (8)
        // to get the OpenGL Y coordinate for the bottom of the quad
        int screenY = screenHeight - y - 8;

        // Compute the 4 corners of the quad
        float x0 = x;
        float y0 = screenY;
        float x1 = x + 8;
        float y1 = screenY + 8;

        // Handle flips by adjusting texture coordinates
        // Note: VFlip=false means apply vertical flip (this is the default)
        float u0, u1, v0, v1;
        if (desc.getHFlip()) {
            u0 = 1.0f;
            u1 = 0.0f;
        } else {
            u0 = 0.0f;
            u1 = 1.0f;
        }
        if (desc.getVFlip()) {
            v0 = 0.0f;
            v1 = 1.0f;
        } else {
            v0 = 1.0f;
            v1 = 0.0f;
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

        // Store texture ID and palette for this pattern
        patternTextureIds[patternCount] = patternTextureId;
        paletteIndices[patternCount] = paletteIndex;
        patternCount++;

        return true;
    }

    /**
     * Add a strip pattern to the current batch for special stage track rendering.
     *
     * The Sonic 2 special stage track uses per-scanline horizontal scroll to create
     * a pseudo-3D halfpipe effect. Each 8x8 tile is shown as 4 strips of 2 scanlines
     * each. This method renders a single strip (8 wide × 2 high).
     *
     * @param patternTextureId The pattern texture ID
     * @param paletteIndex The palette line to use
     * @param desc The pattern descriptor (handles H/V flip)
     * @param x Screen X position
     * @param y Screen Y position (of the strip, not the full tile)
     * @param stripIndex Which 2-scanline strip to render (0-3, where 0 is top of tile)
     * @return true if the pattern was added, false if batch is full or not active
     */
    public boolean addStripPattern(int patternTextureId, int paletteIndex, PatternDesc desc,
                                   int x, int y, int stripIndex) {
        if (!batchActive || patternCount >= MAX_PATTERNS_PER_BATCH) {
            return false;
        }

        // Convert Y to screen coordinates (flip Y axis)
        // Genesis Y=0 is top of screen, OpenGL Y=0 is bottom
        // For a 2-pixel strip at Genesis Y, the OpenGL bottom should be:
        //   screenHeight - y - stripHeight = 224 - y - 2
        // This ensures Genesis Y=0 maps to OpenGL Y=222-224 (visible top of screen)
        int screenY = screenHeight - y - 2;

        // Compute the 4 corners of the quad (8 wide × 2 high)
        float x0 = x;
        float y0 = screenY;          // Bottom of quad in OpenGL
        float x1 = x + 8;
        float y1 = screenY + 2;      // Top of quad in OpenGL

        // Calculate texture V coordinates for this strip using PIXEL-CENTER coordinates.
        // Strip 0 = rows 0-1 (top), Strip 3 = rows 6-7 (bottom)
        //
        // CRITICAL: With GL_NEAREST, we must sample at pixel centers, not edges!
        // Using edge coordinates (0.0, 0.25, 0.5, 0.75) causes boundary pixels to
        // potentially sample the wrong texture row due to floating-point precision.
        //
        // For an 8-pixel tall texture:
        //   Row 0 center: v = 0.5/8 = 0.0625
        //   Row 1 center: v = 1.5/8 = 0.1875
        //   Row 2 center: v = 2.5/8 = 0.3125
        //   ...etc
        //
        // Each strip shows 2 rows. We sample at the center of each row:
        // Strip 0 (rows 0-1): top=0.0625, bottom=0.1875
        // Strip 1 (rows 2-3): top=0.3125, bottom=0.4375
        // Strip 2 (rows 4-5): top=0.5625, bottom=0.6875
        // Strip 3 (rows 6-7): top=0.8125, bottom=0.9375
        float firstRowCenter = (stripIndex * 2 + 0.5f) / 8.0f;   // Center of first row of strip
        float secondRowCenter = (stripIndex * 2 + 1.5f) / 8.0f;  // Center of second row of strip
        float stripTop = firstRowCenter;
        float stripBottom = secondRowCenter;

        // Handle flips by adjusting texture coordinates
        float u0, u1, v0, v1;
        if (desc.getHFlip()) {
            u0 = 1.0f;
            u1 = 0.0f;
        } else {
            u0 = 0.0f;
            u1 = 1.0f;
        }

        // V coordinates for the strip
        // Default (VFlip=false): flip texture so row 0 is at top of quad
        //   Bottom of quad gets stripBottom, top gets stripTop
        // VFlip=true: don't flip, so row 0 is at bottom of quad
        //   Bottom of quad gets stripTop, top gets stripBottom
        if (desc.getVFlip()) {
            v0 = stripTop;      // Bottom of quad
            v1 = stripBottom;   // Top of quad
        } else {
            v0 = stripBottom;   // Bottom of quad
            v1 = stripTop;      // Top of quad
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

        // Store texture ID and palette for this pattern
        patternTextureIds[patternCount] = patternTextureId;
        paletteIndices[patternCount] = paletteIndex;
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

        // Create a snapshot command with copied data
        BatchRenderCommand command = new BatchRenderCommand(
                Arrays.copyOf(vertexData, patternCount * FLOATS_PER_PATTERN_VERTS),
                Arrays.copyOf(texCoordData, patternCount * FLOATS_PER_PATTERN_TEXCOORDS),
                Arrays.copyOf(patternTextureIds, patternCount),
                Arrays.copyOf(paletteIndices, patternCount),
                patternCount);

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
        batchActive = false;  // Ensure normal batch is not active
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
    public boolean addShadowPattern(int patternTextureId, PatternDesc desc, int x, int y) {
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
        float u0, u1, v0, v1;
        if (desc.getHFlip()) {
            u0 = 1.0f;
            u1 = 0.0f;
        } else {
            u0 = 0.0f;
            u1 = 1.0f;
        }
        if (desc.getVFlip()) {
            v0 = 0.0f;
            v1 = 1.0f;
        } else {
            v0 = 1.0f;
            v1 = 0.0f;
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

        // Store texture ID (palette is not used for shadow rendering)
        patternTextureIds[patternCount] = patternTextureId;
        paletteIndices[patternCount] = 0;  // Not used for shadows
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

        // Create a shadow-specific command with copied data
        ShadowBatchRenderCommand command = new ShadowBatchRenderCommand(
                Arrays.copyOf(vertexData, patternCount * FLOATS_PER_PATTERN_VERTS),
                Arrays.copyOf(texCoordData, patternCount * FLOATS_PER_PATTERN_TEXCOORDS),
                Arrays.copyOf(patternTextureIds, patternCount),
                patternCount);

        // Reset for next batch
        patternCount = 0;
        shadowBatchActive = false;

        return command;
    }

    /**
     * Command that renders a batch of patterns.
     * This is a snapshot of batch data that can be queued for later execution.
     */
    private static class BatchRenderCommand implements GLCommandable {
        private final float[] vertexData;
        private final float[] texCoordData;
        private final int[] patternTextureIds;
        private final int[] paletteIndices;
        private final int patternCount;

        // Direct buffers for OpenGL - allocated once per command
        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;

        BatchRenderCommand(float[] vertexData, float[] texCoordData,
                int[] patternTextureIds, int[] paletteIndices, int patternCount) {
            this.vertexData = vertexData;
            this.texCoordData = texCoordData;
            this.patternTextureIds = patternTextureIds;
            this.paletteIndices = paletteIndices;
            this.patternCount = patternCount;
        }

        @Override
        public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (patternCount == 0) {
                return;
            }

            // Allocate direct buffers on first use
            if (vertexBuffer == null) {
                vertexBuffer = GLBuffers.newDirectFloatBuffer(vertexData.length);
                texCoordBuffer = GLBuffers.newDirectFloatBuffer(texCoordData.length);
                vertexBuffer.put(vertexData).flip();
                texCoordBuffer.put(texCoordData).flip();
            }

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

            // Enable vertex arrays
            gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);

            // Bind combined palette texture
            gl.glActiveTexture(GL2.GL_TEXTURE0);
            Integer paletteTextureId = gm.getCombinedPaletteTextureId();
            if (paletteTextureId != null) {
                gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);
            }

            int lastTextureId = -1;
            int lastPaletteIndex = -1;

            gl.glPushMatrix();
            gl.glTranslatef(-cameraX, cameraY, 0);

            for (int i = 0; i < patternCount; i++) {
                // Change pattern texture if needed
                if (patternTextureIds[i] != lastTextureId) {
                    gl.glActiveTexture(GL2.GL_TEXTURE1);
                    gl.glBindTexture(GL2.GL_TEXTURE_2D, patternTextureIds[i]);
                    lastTextureId = patternTextureIds[i];
                }

                // Change palette line if needed
                if (paletteIndices[i] != lastPaletteIndex) {
                    shader.setPaletteLine(gl, paletteIndices[i]);
                    lastPaletteIndex = paletteIndices[i];
                }

                // Set buffer position for this pattern's data
                int vertOffset = i * 8; // 4 vertices * 2 floats
                int texOffset = i * 8;

                vertexBuffer.position(vertOffset);
                texCoordBuffer.position(texOffset);

                gl.glVertexPointer(2, GL2.GL_FLOAT, 0, vertexBuffer);
                gl.glTexCoordPointer(2, GL2.GL_FLOAT, 0, texCoordBuffer);

                gl.glDrawArrays(GL2.GL_QUADS, 0, 4);
            }

            gl.glPopMatrix();

            // Cleanup state
            gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            shader.stop(gl);
            gl.glDisable(GL2.GL_BLEND);

            // Reset PatternRenderCommand state tracking so subsequent patterns
            // will properly reinitialize GL state (since we just disabled everything)
            PatternRenderCommand.resetFrameState();
        }
    }

    /**
     * Command that renders a batch of shadow patterns.
     * Uses the shadow shader and multiplicative blending to darken the background.
     * This implements VDP shadow/highlight mode where palette index 14 darkens pixels.
     */
    private static class ShadowBatchRenderCommand implements GLCommandable {
        private final float[] vertexData;
        private final float[] texCoordData;
        private final int[] patternTextureIds;
        private final int patternCount;

        // Direct buffers for OpenGL - allocated once per command
        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;

        ShadowBatchRenderCommand(float[] vertexData, float[] texCoordData,
                int[] patternTextureIds, int patternCount) {
            this.vertexData = vertexData;
            this.texCoordData = texCoordData;
            this.patternTextureIds = patternTextureIds;
            this.patternCount = patternCount;
        }

        @Override
        public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (patternCount == 0) {
                return;
            }

            // Allocate direct buffers on first use
            if (vertexBuffer == null) {
                vertexBuffer = GLBuffers.newDirectFloatBuffer(vertexData.length);
                texCoordBuffer = GLBuffers.newDirectFloatBuffer(texCoordData.length);
                vertexBuffer.put(vertexData).flip();
                texCoordBuffer.put(texCoordData).flip();
            }

            GraphicsManager gm = GraphicsManager.getInstance();
            ShaderProgram shadowShader = gm.getShadowShaderProgram();

            // Setup state for shadow rendering
            gl.glEnable(GL2.GL_BLEND);
            // Multiplicative blending: result = dest * src
            // Shadow shader outputs 0.5 for index 14, which will halve (darken) the background
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

            int lastTextureId = -1;

            gl.glPushMatrix();
            gl.glTranslatef(-cameraX, cameraY, 0);

            for (int i = 0; i < patternCount; i++) {
                // Change pattern texture if needed
                if (patternTextureIds[i] != lastTextureId) {
                    gl.glActiveTexture(GL2.GL_TEXTURE0);
                    gl.glBindTexture(GL2.GL_TEXTURE_2D, patternTextureIds[i]);
                    lastTextureId = patternTextureIds[i];
                }

                // Set buffer position for this pattern's data
                int vertOffset = i * 8;
                int texOffset = i * 8;

                vertexBuffer.position(vertOffset);
                texCoordBuffer.position(texOffset);

                gl.glVertexPointer(2, GL2.GL_FLOAT, 0, vertexBuffer);
                gl.glTexCoordPointer(2, GL2.GL_FLOAT, 0, texCoordBuffer);

                gl.glDrawArrays(GL2.GL_QUADS, 0, 4);
            }

            gl.glPopMatrix();

            // Cleanup state
            gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            shadowShader.stop(gl);
            gl.glDisable(GL2.GL_BLEND);

            PatternRenderCommand.resetFrameState();
        }
    }
}
