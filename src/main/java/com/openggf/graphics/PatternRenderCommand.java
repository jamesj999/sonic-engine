package com.openggf.graphics;

import com.openggf.Engine;
import org.lwjgl.system.MemoryUtil;
import com.openggf.game.GameServices;
import com.openggf.level.PatternDesc;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/**
 * Optimized pattern render command that minimizes redundant GL state changes.
 * Uses modern OpenGL (VAOs, vertex attributes) for core profile compatibility.
 *
 * Optimizations applied:
 * 1. Uses cached uniform locations (eliminates string hash lookups)
 * 2. Uses static singleton for shader program reference
 * 3. Tracks last-used textures to avoid redundant binds
 * 4. Pre-computes transformed vertices instead of using matrix operations
 * 5. Object pooling to avoid per-pattern allocation
 */
public class PatternRenderCommand implements GLCommandable {

    // Object pool for command reuse
    private static final ArrayDeque<PatternRenderCommand> pool = new ArrayDeque<>(256);

    private int paletteTextureId;
    private float u0;
    private float v0;
    private float u1;
    private float v1;
    private int atlasIndex;
    private int paletteIndex;
    private boolean hFlip;
    private boolean vFlip;
    private boolean textureCoordinatesResolved;
    private boolean piecePriority; // VDP per-tile priority from PatternDesc bit 15
    private int capturedTileOcclusionPaletteMask;
    private boolean ghostEffectActive;
    private float ghostAlpha;
    private boolean leased;
    private float x;
    private float y;
    private float width;
    private float height;
    private GraphicsManager graphicsManager;

    // Static state tracking for batch optimization
    private static int lastAtlasTextureId = -1;
    private static int lastPaletteTextureId = -1;
    private static int lastPaletteIndex = -1;
    private static int lastPriorityShaderProgramId = -1;
    private static int lastPriorityTileTextureId = -1;
    private static int lastPriorityUnderwaterPaletteId = -1;
    private static int lastPriorityViewportX = Integer.MIN_VALUE;
    private static int lastPriorityViewportY = Integer.MIN_VALUE;
    private static int lastPriorityViewportWidth = Integer.MIN_VALUE;
    private static int lastPriorityViewportHeight = Integer.MIN_VALUE;
    private static boolean lastPriorityWaterEnabled;
    private static boolean lastGhostEffectActive;
    private static float lastGhostAlpha = Float.NaN;
    private static float lastPriorityWaterlineScreenY = Float.NaN;
    private static float lastPriorityWindowHeight = Float.NaN;
    private static float lastPriorityScreenHeight = Float.NaN;
    private static boolean stateInitialized = false;

    // Pre-allocated vertex buffers for transformed coordinates
    private static FloatBuffer vertexBuffer;
    private static FloatBuffer texCoordBuffer;
    private static FloatBuffer paletteBuffer;
    private static final Map<Integer, int[]> TRANSFORM_UNIFORM_LOCATIONS = new HashMap<>();

    @FunctionalInterface
    interface UniformLookup {
        int find(int programId, String name);
    }

    static int pooledCommandCount() {
        return pool.size();
    }

    static boolean hasNativeScratch() {
        return vertexBuffer != null || texCoordBuffer != null || paletteBuffer != null;
    }

    static int[] transformUniformLocations(int programId, UniformLookup lookup) {
        return TRANSFORM_UNIFORM_LOCATIONS.computeIfAbsent(programId,
                id -> new int[] {lookup.find(id, "ProjectionMatrix"), lookup.find(id, "CameraOffset")});
    }

    static void clearUniformLocationCache() {
        TRANSFORM_UNIFORM_LOCATIONS.clear();
    }

    // VAO and VBOs for modern OpenGL (shared across all instances)
    private static int vaoId = 0;
    private static int vertexVboId = 0;
    private static int texCoordVboId = 0;
    private static int paletteVboId = 0;

    // Vertex attribute locations (standard layout matching shader_basic.vert)
    private static final int ATTRIB_POSITION = 0;
    private static final int ATTRIB_TEXCOORD = 1;
    private static final int ATTRIB_PALETTE = 2;

    /**
     * Obtain a PatternRenderCommand from the pool or create a new one.
     */
    public static PatternRenderCommand obtain(PatternAtlas.Entry entry, int paletteTextureId, PatternDesc desc, int x, int y) {
        return obtain(entry, paletteTextureId, desc, x, y, GameServices.graphics());
    }

    public static PatternRenderCommand obtain(PatternAtlas.Entry entry, int paletteTextureId, PatternDesc desc,
            int x, int y, GraphicsManager graphicsManager) {
        return obtain(entry, paletteTextureId, desc, (float) x, (float) y, 8f, 8f, graphicsManager);
    }

    public static PatternRenderCommand obtain(PatternAtlas.Entry entry, int paletteTextureId, PatternDesc desc,
            float x, float y, float width, float height, GraphicsManager graphicsManager) {
        PatternRenderCommand cmd = pool.pollFirst();
        if (cmd == null) {
            cmd = new PatternRenderCommand();
        }
        cmd.init(entry, paletteTextureId, desc, x, y, width, height, graphicsManager);
        return cmd;
    }

    void resolveStripTextureCoordinates(PatternAtlas.Entry entry, int stripIndex) {
        int rowTop = stripIndex * 2;
        int rowBottom = rowTop + 1;
        float rowStep = (entry.v1() - entry.v0()) / 8.0f;
        float stripTop = entry.v0() + rowStep * ((7 - rowTop) + 0.5f);
        float stripBottom = entry.v0() + rowStep * ((7 - rowBottom) + 0.5f);
        u0 = hFlip ? entry.u1() : entry.u0();
        u1 = hFlip ? entry.u0() : entry.u1();
        v0 = vFlip ? stripTop : stripBottom;
        v1 = vFlip ? stripBottom : stripTop;
        textureCoordinatesResolved = true;
    }

    private PatternRenderCommand() {
        // Private constructor for pooling
    }

    private void init(PatternAtlas.Entry entry, int paletteTextureId, PatternDesc desc, float x, float y,
            float width, float height,
            GraphicsManager graphicsManager) {
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.paletteTextureId = paletteTextureId;
        this.u0 = entry.u0();
        this.v0 = entry.v0();
        this.u1 = entry.u1();
        this.v1 = entry.v1();
        this.atlasIndex = entry.atlasIndex();
        this.paletteIndex = desc.getPaletteIndex();
        this.hFlip = desc.getHFlip();
        this.vFlip = desc.getVFlip();
        this.textureCoordinatesResolved = false;
        this.piecePriority = desc.getPriority();
        this.capturedTileOcclusionPaletteMask = graphicsManager.getCurrentSpriteTileOcclusionPaletteMask();
        this.ghostEffectActive = graphicsManager.isGhostRenderEffectActive();
        this.ghostAlpha = graphicsManager.getGhostRenderAlpha();
        this.x = x;
        this.width = width;
        this.height = height;
        this.leased = true;
        // Genesis Y refers to the TOP of the pattern, so we subtract the pattern height
        // to get the OpenGL Y coordinate for the bottom of the quad
        // width/height are allowed to vary for scaled host preview rendering.
        this.y = resolveDisplayHeight(graphicsManager) - y - height;
    }

    /**
     * Return this command to the pool for reuse.
     */
    public void recycle() {
        if (!leased) {
            return;
        }
        leased = false;
        graphicsManager = null;
        if (pool.size() < 512) { // Cap pool size to prevent unbounded growth
            pool.offerFirst(this);
        }
    }

    @Override
    public void discard() {
        recycle();
    }

    /**
     * Reset static state at the start of each frame.
     * Call this before beginning a new frame of rendering.
     */
    public static void resetFrameState() {
        lastAtlasTextureId = -1;
        lastPaletteTextureId = -1;
        lastPaletteIndex = -1;
        lastPriorityShaderProgramId = -1;
        lastPriorityTileTextureId = -1;
        lastPriorityUnderwaterPaletteId = -1;
        lastPriorityViewportX = Integer.MIN_VALUE;
        lastPriorityViewportY = Integer.MIN_VALUE;
        lastPriorityViewportWidth = Integer.MIN_VALUE;
        lastPriorityViewportHeight = Integer.MIN_VALUE;
        lastPriorityWaterEnabled = false;
        lastGhostEffectActive = false;
        lastGhostAlpha = Float.NaN;
        lastPriorityWaterlineScreenY = Float.NaN;
        lastPriorityWindowHeight = Float.NaN;
        lastPriorityScreenHeight = Float.NaN;
        stateInitialized = false;
    }

    private static void ensureVbos() {
        ensureNativeScratch();
        if (vaoId != 0) {
            return;
        }
        vaoId = glGenVertexArrays();
        vertexVboId = glGenBuffers();
        texCoordVboId = glGenBuffers();
        paletteVboId = glGenBuffers();
    }

    static void ensureNativeScratch() {
        if (vertexBuffer == null) {
            vertexBuffer = MemoryUtil.memAllocFloat(8);
            texCoordBuffer = MemoryUtil.memAllocFloat(8);
            paletteBuffer = MemoryUtil.memAllocFloat(4);
        }
    }

    @Override
    public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
        try {
            executeLeased(cameraX, cameraY, cameraWidth, cameraHeight);
        } finally {
            recycle();
        }
    }

    private void executeLeased(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
        GraphicsManager graphicsManager = this.graphicsManager;
        ShaderProgram shaderProgram = graphicsManager.getShaderProgram();

        // Initialize persistent state once per batch of patterns
        if (!stateInitialized) {
            ensureVbos();
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            shaderProgram.use();
            shaderProgram.cacheUniformLocations();
            glUniform1i(shaderProgram.getPaletteLocation(), 0);
            glUniform1i(shaderProgram.getIndexedColorTextureLocation(), 1);
            shaderProgram.setTotalPaletteLines((float) RenderContext.getTotalPaletteLines());

            // Set projection matrix uniform - REQUIRED for correct rendering
            int[] transformLocations = transformUniformLocations(shaderProgram.getProgramId(),
                    (programId, name) -> glGetUniformLocation(programId, name));
            int projectionLoc = transformLocations[0];
            if (projectionLoc != -1) {
                float[] projMatrix = graphicsManager.getProjectionMatrixBuffer();
                if (projMatrix != null) {
                    glUniformMatrix4fv(projectionLoc, false, projMatrix);
                }
            }

            // Set camera offset uniform
            // X is negated to scroll objects left when camera moves right
            // Y is NOT negated because vertex Y is already in screen space (flipped from Genesis coords)
            int cameraOffsetLoc = transformLocations[1];
            if (cameraOffsetLoc != -1) {
                glUniform2f(cameraOffsetLoc, -cameraX, cameraY);
            }

            // Bind VAO
            glBindVertexArray(vaoId);

            // If using water shader, bind underwater palette to texture unit 2
            if (shaderProgram instanceof WaterShaderProgram) {
                WaterShaderProgram waterShader = (WaterShaderProgram) shaderProgram;
                Integer underwaterPaletteId = graphicsManager.getUnderwaterPaletteTextureId();
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

            stateInitialized = true;
        }

        if (shaderProgram instanceof SpritePriorityShaderProgram priorityShader) {
            int programId = shaderProgram.getProgramId();
            TilePriorityFBO fbo = graphicsManager.getTilePriorityFBO();
            int tilePriorityTextureId =
                    fbo != null && fbo.isInitialized() ? fbo.getTextureId() : -1;
            int viewportX = graphicsManager.getViewportX();
            int viewportY = graphicsManager.getViewportY();
            int viewportWidth = graphicsManager.getViewportWidth();
            int viewportHeight = graphicsManager.getViewportHeight();
            Integer underwaterPaletteId = graphicsManager.getUnderwaterPaletteTextureId();
            int underwaterPaletteTextureId = underwaterPaletteId != null ? underwaterPaletteId : -1;
            boolean waterEnabled = graphicsManager.isWaterEnabled();
            float waterlineScreenY = graphicsManager.getWaterlineScreenY();
            float windowHeight = graphicsManager.getWindowHeight();
            float screenHeight = graphicsManager.getScreenHeight();

            if (lastPriorityShaderProgramId != programId
                    || lastPriorityTileTextureId != tilePriorityTextureId
                    || lastPriorityViewportX != viewportX
                    || lastPriorityViewportY != viewportY
                    || lastPriorityViewportWidth != viewportWidth
                    || lastPriorityViewportHeight != viewportHeight
                    || lastPriorityUnderwaterPaletteId != underwaterPaletteTextureId
                    || lastPriorityWaterEnabled != waterEnabled
                    || lastPriorityWaterlineScreenY != waterlineScreenY
                    || lastPriorityWindowHeight != windowHeight
                    || lastPriorityScreenHeight != screenHeight) {
                if (tilePriorityTextureId >= 0) {
                    glActiveTexture(GL_TEXTURE5);
                    glBindTexture(GL_TEXTURE_2D, tilePriorityTextureId);
                    priorityShader.setTilePriorityTexture(5);
                    glActiveTexture(GL_TEXTURE0);
                }

                priorityShader.setScreenSize(viewportWidth, viewportHeight);
                priorityShader.setViewportOffset(viewportX, viewportY);

                if (underwaterPaletteTextureId >= 0) {
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D, underwaterPaletteTextureId);
                    int loc = priorityShader.getUnderwaterPaletteLocation();
                    if (loc != -1) {
                        glUniform1i(loc, 2);
                    }
                    glActiveTexture(GL_TEXTURE0);
                }

                priorityShader.setWaterEnabled(waterEnabled);
                priorityShader.setWaterlineScreenY(waterlineScreenY);
                priorityShader.setWindowHeight(windowHeight);
                priorityShader.setScreenHeight(screenHeight);
                lastPriorityShaderProgramId = programId;
                lastPriorityTileTextureId = tilePriorityTextureId;
                lastPriorityUnderwaterPaletteId = underwaterPaletteTextureId;
                lastPriorityViewportX = viewportX;
                lastPriorityViewportY = viewportY;
                lastPriorityViewportWidth = viewportWidth;
                lastPriorityViewportHeight = viewportHeight;
                lastPriorityWaterEnabled = waterEnabled;
                lastPriorityWaterlineScreenY = waterlineScreenY;
                lastPriorityWindowHeight = windowHeight;
                lastPriorityScreenHeight = screenHeight;
            }

            // A piece carrying the ROM priority bit bypasses terrain masking;
            // otherwise retain the object's palette-specific occlusion mask.
            priorityShader.setTileOcclusionPaletteMask(piecePriority ? 0 : capturedTileOcclusionPaletteMask);
        }

        // Only bind palette texture if it changed
        if (paletteTextureId != lastPaletteTextureId) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, paletteTextureId);
            lastPaletteTextureId = paletteTextureId;
        }

        // Only bind atlas texture if it changed
        Integer atlasTextureId = graphicsManager.getPatternAtlasTextureId(atlasIndex);
        if (atlasTextureId != null && atlasTextureId != lastAtlasTextureId) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, atlasTextureId);
            lastAtlasTextureId = atlasTextureId;
        }

        // Only update palette line uniform if it changed
        if (paletteIndex != lastPaletteIndex) {
            shaderProgram.setPaletteLine(paletteIndex);
            lastPaletteIndex = paletteIndex;
        }

        if (ghostEffectActive != lastGhostEffectActive || ghostAlpha != lastGhostAlpha) {
            shaderProgram.setGhostEffect(ghostEffectActive, ghostAlpha);
            lastGhostEffectActive = ghostEffectActive;
            lastGhostAlpha = ghostAlpha;
        }

        // Compute transformed vertices directly (avoids push/pop/translate/scale)
        // Note: camera offset is now handled via uniform, so vertices are in world space
        float screenX = x;
        float screenY = y;

        // Bottom-left, bottom-right, top-right, top-left
        float x0 = screenX;
        float x1 = screenX + width;
        float y0 = screenY;
        float y1 = screenY + height;

        // Apply horizontal flip by swapping left/right
        if (!textureCoordinatesResolved && hFlip) {
            float temp = x0;
            x0 = x1;
            x1 = temp;
        }

        // Apply vertical flip by swapping top/bottom
        // Note: VFlip=false means apply flip (original VDP behavior)
        if (!textureCoordinatesResolved && !vFlip) {
            float temp = y0;
            y0 = y1;
            y1 = temp;
        }

        // Fill vertex buffer (quad: bottom-left, bottom-right, top-right, top-left)
        vertexBuffer.clear();
        vertexBuffer.put(x0).put(y0); // Bottom-left
        vertexBuffer.put(x1).put(y0); // Bottom-right
        vertexBuffer.put(x1).put(y1); // Top-right
        vertexBuffer.put(x0).put(y1); // Top-left
        vertexBuffer.flip();

        // Fill texture coordinate buffer
        texCoordBuffer.clear();
        texCoordBuffer.put(u0).put(v0);
        texCoordBuffer.put(u1).put(v0);
        texCoordBuffer.put(u1).put(v1);
        texCoordBuffer.put(u0).put(v1);
        texCoordBuffer.flip();

        // Fill palette coordinate buffer (same palette index for all 4 vertices)
        paletteBuffer.clear();
        paletteBuffer.put(paletteIndex).put(paletteIndex).put(paletteIndex).put(paletteIndex);
        paletteBuffer.flip();

        // Upload and bind vertex data
        glBindBuffer(GL_ARRAY_BUFFER, vertexVboId);
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(ATTRIB_POSITION, 2, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(ATTRIB_POSITION);

        glBindBuffer(GL_ARRAY_BUFFER, texCoordVboId);
        glBufferData(GL_ARRAY_BUFFER, texCoordBuffer, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(ATTRIB_TEXCOORD, 2, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(ATTRIB_TEXCOORD);

        glBindBuffer(GL_ARRAY_BUFFER, paletteVboId);
        glBufferData(GL_ARRAY_BUFFER, paletteBuffer, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(ATTRIB_PALETTE, 1, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(ATTRIB_PALETTE);

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

    }

    /**
     * Clean up GL state after all patterns are rendered.
     * Call this after the last pattern command in a frame.
     */
    public static void cleanupFrameState(GraphicsManager graphicsManager) {
        if (stateInitialized) {
            glDisableVertexAttribArray(ATTRIB_POSITION);
            glDisableVertexAttribArray(ATTRIB_TEXCOORD);
            glDisableVertexAttribArray(ATTRIB_PALETTE);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
            ShaderProgram shaderProgram = graphicsManager.getShaderProgram();
            if (shaderProgram != null) {
                shaderProgram.stop();
            }
            glDisable(GL_BLEND);
            stateInitialized = false;
        }
    }

    private static int resolveDisplayHeight(GraphicsManager graphicsManager) {
        Engine engine = graphicsManager.getEngine();
        if (engine != null && engine.isFBOProjectionActive()) {
            return engine.getCurrentDisplayHeight();
        }
        // Cached on the GraphicsManager (invalidated on reshape/resetState) instead
        // of a config-service lookup per obtain() — this runs per tile per frame on
        // the SAT replay path.
        return graphicsManager.getConfiguredScreenHeightPx();
    }

    /**
     * Cleanup VBOs and VAO.
     */
    public static void cleanup() {
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
        cleanupNativeState();
    }

    public static void cleanupHeadless() {
        vaoId = 0;
        vertexVboId = 0;
        texCoordVboId = 0;
        paletteVboId = 0;
        cleanupNativeState();
    }

    private static void cleanupNativeState() {
        if (vertexBuffer != null) {
            MemoryUtil.memFree(vertexBuffer);
            MemoryUtil.memFree(texCoordBuffer);
            MemoryUtil.memFree(paletteBuffer);
            vertexBuffer = null;
            texCoordBuffer = null;
            paletteBuffer = null;
        }
        for (PatternRenderCommand command : pool) {
            command.graphicsManager = null;
            command.leased = false;
        }
        pool.clear();
        clearUniformLocationCache();
        resetFrameState();
    }
}
