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
        int screenY = screenHeight - y;

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
        }
    }
}
