package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.nio.FloatBuffer;

/**
 * Optimized pattern render command that minimizes redundant GL state changes.
 * 
 * Optimizations applied:
 * 1. Uses cached uniform locations (eliminates string hash lookups)
 * 2. Uses static singleton for shader program reference
 * 3. Tracks last-used textures to avoid redundant binds
 * 4. Pre-computes transformed vertices instead of using matrix operations
 */
public class PatternRenderCommand implements GLCommandable {

    private final int patternTextureId;
    private final int paletteTextureId;
    private final PatternDesc desc;
    private final int x;
    private final int y;

    // Static state tracking for batch optimization
    private static int lastPatternTextureId = -1;
    private static int lastPaletteTextureId = -1;
    private static int lastPaletteIndex = -1;
    private static boolean stateInitialized = false;

    // Pre-allocated vertex buffer for transformed coordinates
    private static final FloatBuffer VERTEX_BUFFER = GLBuffers.newDirectFloatBuffer(8);
    private static final FloatBuffer TEX_COORD_BUFFER = GLBuffers.newDirectFloatBuffer(8);

    // Screen height for Y coordinate transformation
    private static final int SCREEN_HEIGHT = SonicConfigurationService.getInstance()
            .getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

    // Cached GraphicsManager reference to avoid synchronized getInstance() calls
    private static GraphicsManager graphicsManager;

    private static GraphicsManager getGraphicsManager() {
        if (graphicsManager == null) {
            graphicsManager = GraphicsManager.getInstance();
        }
        return graphicsManager;
    }

    public PatternRenderCommand(int patternTextureId, int paletteTextureId, PatternDesc desc, int x, int y) {
        this.patternTextureId = patternTextureId;
        this.paletteTextureId = paletteTextureId;
        this.desc = desc;
        this.x = x;
        this.y = SCREEN_HEIGHT - y;
    }

    /**
     * Reset static state at the start of each frame.
     * Call this before beginning a new frame of rendering.
     */
    public static void resetFrameState() {
        lastPatternTextureId = -1;
        lastPaletteTextureId = -1;
        lastPaletteIndex = -1;
        stateInitialized = false;
    }

    @Override
    public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
        ShaderProgram shaderProgram = getGraphicsManager().getShaderProgram();

        // Initialize persistent state once per batch of patterns
        if (!stateInitialized) {
            gl.glEnable(GL2.GL_BLEND);
            gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
            shaderProgram.use(gl);
            shaderProgram.cacheUniformLocations(gl);
            gl.glUniform1i(shaderProgram.getPaletteLocation(), 0);
            gl.glUniform1i(shaderProgram.getIndexedColorTextureLocation(), 1);
            gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            stateInitialized = true;
        }

        // Only bind palette texture if it changed
        if (paletteTextureId != lastPaletteTextureId) {
            gl.glActiveTexture(GL2.GL_TEXTURE0);
            gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);
            lastPaletteTextureId = paletteTextureId;
        }

        // Only bind pattern texture if it changed
        if (patternTextureId != lastPatternTextureId) {
            gl.glActiveTexture(GL2.GL_TEXTURE1);
            gl.glBindTexture(GL2.GL_TEXTURE_2D, patternTextureId);
            lastPatternTextureId = patternTextureId;
        }

        // Only update palette line uniform if it changed
        int paletteIndex = desc.getPaletteIndex();
        if (paletteIndex != lastPaletteIndex) {
            shaderProgram.setPaletteLine(gl, paletteIndex);
            lastPaletteIndex = paletteIndex;
        }

        // Compute transformed vertices directly (avoids push/pop/translate/scale)
        float screenX = x - cameraX;
        float screenY = y + cameraY;

        // Bottom-left, bottom-right, top-right, top-left
        float x0 = screenX;
        float x1 = screenX + 8;
        float y0 = screenY;
        float y1 = screenY + 8;

        // Apply horizontal flip by swapping left/right
        if (desc.getHFlip()) {
            float temp = x0;
            x0 = x1;
            x1 = temp;
        }

        // Apply vertical flip by swapping top/bottom
        // Note: VFlip=false means apply flip (original VDP behavior)
        if (!desc.getVFlip()) {
            float temp = y0;
            y0 = y1;
            y1 = temp;
        }

        // Compute texture coordinates based on flips
        float u0 = 0.0f, u1 = 1.0f;
        float v0 = 0.0f, v1 = 1.0f;

        // Fill vertex buffer
        VERTEX_BUFFER.clear();
        VERTEX_BUFFER.put(x0).put(y0); // Bottom-left
        VERTEX_BUFFER.put(x1).put(y0); // Bottom-right
        VERTEX_BUFFER.put(x1).put(y1); // Top-right
        VERTEX_BUFFER.put(x0).put(y1); // Top-left
        VERTEX_BUFFER.flip();

        // Fill texture coordinate buffer (always the same, no flip needed here since we
        // flipped vertices)
        TEX_COORD_BUFFER.clear();
        TEX_COORD_BUFFER.put(u0).put(v0);
        TEX_COORD_BUFFER.put(u1).put(v0);
        TEX_COORD_BUFFER.put(u1).put(v1);
        TEX_COORD_BUFFER.put(u0).put(v1);
        TEX_COORD_BUFFER.flip();

        gl.glVertexPointer(2, GL2.GL_FLOAT, 0, VERTEX_BUFFER);
        gl.glTexCoordPointer(2, GL2.GL_FLOAT, 0, TEX_COORD_BUFFER);
        gl.glDrawArrays(GL2.GL_QUADS, 0, 4);
    }

    /**
     * Clean up GL state after all patterns are rendered.
     * Call this after the last pattern command in a frame.
     */
    public static void cleanupFrameState(GL2 gl) {
        if (stateInitialized) {
            gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
            ShaderProgram shaderProgram = getGraphicsManager().getShaderProgram();
            if (shaderProgram != null) {
                shaderProgram.stop(gl);
            }
            gl.glDisable(GL2.GL_BLEND);
            stateInitialized = false;
        }
    }
}
