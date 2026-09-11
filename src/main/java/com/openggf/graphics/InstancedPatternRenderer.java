package com.openggf.graphics;

import com.openggf.Engine;
import org.lwjgl.system.MemoryUtil;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.level.PatternDesc;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL33.*;

/**
 * Instanced renderer for batched 8x8 pattern draws.
 * Uses a single quad VBO with per-instance attributes.
 */
public class InstancedPatternRenderer {
    private static final Logger LOGGER = Logger.getLogger(InstancedPatternRenderer.class.getName());

    private static final int MAX_PATTERNS_PER_BATCH = 4096;
    private static final int FLOATS_PER_INSTANCE = 10; // x,y,w,h,u0,v0,u1,v1,palette,highPriority
    // Flushes per frame roughly track render-priority boundaries, so 8 pooled
    // commands cover typical frames; pooled commands hold native FloatBuffers,
    // hence the hard cap rather than an unbounded pool.
    private static final int COMMAND_POOL_LIMIT = 8;
    private static final String PRIORITY_FRAGMENT_SHADER_PATH = "shaders/shader_instanced_priority.glsl";

    private final GraphicsManager graphicsManager;
    private final int screenHeight;
    private final float[] instanceData;
    private final boolean drainGlErrors;

    private int instanceCount;
    private boolean batchActive;
    private boolean supported;
    private boolean initialized;

    private ShaderProgram instancedShader;
    private WaterShaderProgram instancedWaterShader;
    private ShaderProgram instancedPriorityShader;  // Priority-aware instanced shader

    private int vaoId;
    private int quadVboId;
    private int instanceVboId;

    private AttribLocations defaultAttribs;
    private AttribLocations waterAttribs;
    private AttribLocations priorityAttribs;

    // Cached uniform locations for projection and camera offset (shared by all shaders)
    private int cachedDefaultProjectionLoc = -1;
    private int cachedDefaultCameraOffsetLoc = -1;
    private int cachedWaterProjectionLoc = -1;
    private int cachedWaterCameraOffsetLoc = -1;
    private int cachedPriorityProjectionLoc = -1;
    private int cachedPriorityCameraOffsetLoc = -1;

    // Cached uniform locations for priority shader to avoid glGetUniformLocation calls
    private int cachedTilePriorityTexLoc = -1;
    private int cachedScreenSizeLoc = -1;
    private int cachedViewportOffsetLoc = -1;

    // Cached uniform locations for underwater palette support in priority shader
    private int cachedUnderwaterPaletteLoc = -1;
    private int cachedWaterlineScreenYLoc = -1;
    private int cachedWindowHeightLoc = -1;
    private int cachedScreenHeightLoc = -1;
    private int cachedWaterEnabledLoc = -1;

    private final ArrayDeque<InstancedBatchCommand> commandPool = new ArrayDeque<>();

    public InstancedPatternRenderer() {
        this(GameServices.graphics(), GameServices.configuration());
    }

    public InstancedPatternRenderer(GraphicsManager graphicsManager, SonicConfigurationService configService) {
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        Objects.requireNonNull(configService, "configService");
        this.screenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        this.instanceData = new float[MAX_PATTERNS_PER_BATCH * FLOATS_PER_INSTANCE];
        this.drainGlErrors = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
    }

    // Display height resolved once per batch in beginBatch() and reused by every
    // addPattern/addStripPattern call (thousands per frame). Safe because FBO
    // projection state never changes between beginBatch() and endBatch(): call
    // sites (e.g. special-stage background renderers) set up FBO projection
    // BEFORE creating the batch and restore it after the batch is flushed.
    private int batchDisplayHeight;
    private int batchAtlasIndex;
    private boolean batchUsePriorityShader;
    private boolean batchUseWaterShader;
    private boolean batchUnderwaterPalette;
    private boolean batchGhostEffectActive;
    private float batchGhostAlpha;

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

    public void init(String vertexShaderPath, String fragmentShaderPath, String waterFragmentPath)
            throws IOException {
        if (initialized) {
            return;
        }
        supported = isInstancingSupported();
        if (!supported) {
            LOGGER.info("Instanced rendering not supported; falling back to non-instanced batching.");
            return;
        }
        instancedShader = new ShaderProgram(vertexShaderPath, fragmentShaderPath);
        instancedShader.cacheUniformLocations();
        instancedWaterShader = new WaterShaderProgram(vertexShaderPath, waterFragmentPath);
        instancedWaterShader.cacheUniformLocations();
        instancedPriorityShader = new ShaderProgram(vertexShaderPath, PRIORITY_FRAGMENT_SHADER_PATH);
        instancedPriorityShader.cacheUniformLocations();

        defaultAttribs = queryAttribLocations(instancedShader);
        waterAttribs = queryAttribLocations(instancedWaterShader);
        priorityAttribs = queryAttribLocations(instancedPriorityShader);

        // Cache projection and camera offset uniform locations for all shaders
        int defaultProgramId = instancedShader.getProgramId();
        cachedDefaultProjectionLoc = glGetUniformLocation(defaultProgramId, "ProjectionMatrix");
        cachedDefaultCameraOffsetLoc = glGetUniformLocation(defaultProgramId, "CameraOffset");

        int waterProgramId = instancedWaterShader.getProgramId();
        cachedWaterProjectionLoc = glGetUniformLocation(waterProgramId, "ProjectionMatrix");
        cachedWaterCameraOffsetLoc = glGetUniformLocation(waterProgramId, "CameraOffset");

        int priorityProgramId = instancedPriorityShader.getProgramId();
        cachedPriorityProjectionLoc = glGetUniformLocation(priorityProgramId, "ProjectionMatrix");
        cachedPriorityCameraOffsetLoc = glGetUniformLocation(priorityProgramId, "CameraOffset");

        // Cache uniform locations for priority shader
        cachedTilePriorityTexLoc = glGetUniformLocation(priorityProgramId, "TilePriorityTexture");
        cachedScreenSizeLoc = glGetUniformLocation(priorityProgramId, "ScreenSize");
        cachedViewportOffsetLoc = glGetUniformLocation(priorityProgramId, "ViewportOffset");

        // Cache underwater palette uniform locations for priority shader
        cachedUnderwaterPaletteLoc = glGetUniformLocation(priorityProgramId, "UnderwaterPalette");
        cachedWaterlineScreenYLoc = glGetUniformLocation(priorityProgramId, "WaterlineScreenY");
        cachedWindowHeightLoc = glGetUniformLocation(priorityProgramId, "WindowHeight");
        cachedScreenHeightLoc = glGetUniformLocation(priorityProgramId, "ScreenHeight");
        cachedWaterEnabledLoc = glGetUniformLocation(priorityProgramId, "WaterEnabled");

        initBuffers();
        configureVertexArray();
        initialized = true;
        LOGGER.info("Instanced pattern renderer initialized.");
    }

    public boolean isSupported() {
        return supported;
    }

    public ShaderProgram getInstancedShaderProgram() {
        return instancedShader;
    }

    public WaterShaderProgram getInstancedWaterShaderProgram() {
        return instancedWaterShader;
    }

    public void beginBatch() {
        beginBatch(0);
    }

    public void beginBatch(int atlasIndex) {
        instanceCount = 0;
        batchDisplayHeight = resolveDisplayHeight();
        batchAtlasIndex = atlasIndex;
        batchUsePriorityShader = graphicsManager.isUseSpritePriorityShader() && instancedPriorityShader != null;
        batchUseWaterShader = graphicsManager.getShaderProgram() instanceof WaterShaderProgram;
        batchUnderwaterPalette = graphicsManager.isUseUnderwaterPaletteForBackground();
        batchGhostEffectActive = graphicsManager.isGhostRenderEffectActive();
        batchGhostAlpha = graphicsManager.getGhostRenderAlpha();
        batchActive = true;
    }

    public boolean isBatchActive() {
        return batchActive;
    }

    /** Cancels a partially built batch without creating or executing a draw command. */
    public void cancelBatch() {
        instanceCount = 0;
        batchDisplayHeight = 0;
        batchAtlasIndex = 0;
        batchActive = false;
    }

    public boolean addPattern(PatternAtlas.Entry entry, int paletteIndex, PatternDesc desc, int x, int y) {
        if (!batchActive || instanceCount >= MAX_PATTERNS_PER_BATCH
                || entry.atlasIndex() != batchAtlasIndex
                || batchUsePriorityShader != (graphicsManager.isUseSpritePriorityShader() && instancedPriorityShader != null)
                || batchUseWaterShader != (graphicsManager.getShaderProgram() instanceof WaterShaderProgram)
                || batchUnderwaterPalette != graphicsManager.isUseUnderwaterPaletteForBackground()
                || batchGhostEffectActive != graphicsManager.isGhostRenderEffectActive()
                || batchGhostAlpha != graphicsManager.getGhostRenderAlpha()) {
            return false;
        }
        // Display height resolved once per batch in beginBatch() (FBO-aware)
        int screenY = batchDisplayHeight - y - 8;
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

        // Per-piece VDP priority: use the ROM's per-tile priority bit from the
        // PatternDesc (bit 15), OR'd with the global override for backward compat
        // (lost rings, hurt state, bonus stage player override).
        GraphicsManager gm = graphicsManager;
        float highPriority = desc.getPriority()
                ? 0.0f : gm.getCurrentSpriteTileOcclusionPaletteMask();

        int offset = instanceCount * FLOATS_PER_INSTANCE;
        instanceData[offset] = x;
        instanceData[offset + 1] = screenY;
        instanceData[offset + 2] = 8f;
        instanceData[offset + 3] = 8f;
        instanceData[offset + 4] = u0;
        instanceData[offset + 5] = v0;
        instanceData[offset + 6] = u1;
        instanceData[offset + 7] = v1;
        instanceData[offset + 8] = paletteIndex;
        instanceData[offset + 9] = highPriority;
        instanceCount++;
        return true;
    }

    public boolean addStripPattern(PatternAtlas.Entry entry, int paletteIndex, PatternDesc desc,
            int x, int y, int stripIndex) {
        if (!batchActive || instanceCount >= MAX_PATTERNS_PER_BATCH
                || entry.atlasIndex() != batchAtlasIndex
                || batchUsePriorityShader != (graphicsManager.isUseSpritePriorityShader() && instancedPriorityShader != null)
                || batchUseWaterShader != (graphicsManager.getShaderProgram() instanceof WaterShaderProgram)
                || batchUnderwaterPalette != graphicsManager.isUseUnderwaterPaletteForBackground()
                || batchGhostEffectActive != graphicsManager.isGhostRenderEffectActive()
                || batchGhostAlpha != graphicsManager.getGhostRenderAlpha()) {
            return false;
        }
        // Display height resolved once per batch in beginBatch() (FBO-aware)
        int screenY = batchDisplayHeight - y - 2;

        int rowTop = stripIndex * 2;
        int rowBottom = stripIndex * 2 + 1;
        float rowStep = (entry.v1() - entry.v0()) / 8.0f;
        float stripTop = entry.v0() + rowStep * ((7 - rowTop) + 0.5f);
        float stripBottom = entry.v0() + rowStep * ((7 - rowBottom) + 0.5f);

        float u0;
        float u1;
        if (desc.getHFlip()) {
            u0 = entry.u1();
            u1 = entry.u0();
        } else {
            u0 = entry.u0();
            u1 = entry.u1();
        }

        float v0;
        float v1;
        if (desc.getVFlip()) {
            v0 = stripTop;
            v1 = stripBottom;
        } else {
            v0 = stripBottom;
            v1 = stripTop;
        }

        // Per-piece VDP priority (same OR logic as addPattern)
        GraphicsManager gm = graphicsManager;
        float highPriority = desc.getPriority()
                ? 0.0f : gm.getCurrentSpriteTileOcclusionPaletteMask();

        int offset = instanceCount * FLOATS_PER_INSTANCE;
        instanceData[offset] = x;
        instanceData[offset + 1] = screenY;
        instanceData[offset + 2] = 8f;
        instanceData[offset + 3] = 2f;
        instanceData[offset + 4] = u0;
        instanceData[offset + 5] = v0;
        instanceData[offset + 6] = u1;
        instanceData[offset + 7] = v1;
        instanceData[offset + 8] = paletteIndex;
        instanceData[offset + 9] = highPriority;
        instanceCount++;
        return true;
    }

    // Debug counter to limit log spam (enable for debugging sprite rendering issues)
    private static int debugInstancedFrameCounter = 0;
    private static boolean logAfterTitleCard = false;
    public static void enablePostTitleCardLogging() {
        logAfterTitleCard = true;
        debugInstancedFrameCounter = 0;
    }

    public GLCommandable endBatch() {

        if (instanceCount == 0) {
            batchActive = false;
            return null;
        }
        GraphicsManager gm = graphicsManager;
        InstancedBatchCommand command = obtainCommand();
        command.load(instanceData, instanceCount, batchAtlasIndex, batchUsePriorityShader,
                batchUseWaterShader, batchUnderwaterPalette, batchGhostEffectActive, batchGhostAlpha);
        instanceCount = 0;
        batchActive = false;
        return command;
    }

    public void cleanup() {
        cancelBatch();
        if (vaoId != 0) {
            glDeleteVertexArrays(vaoId);
        }
        if (quadVboId != 0) {
            glDeleteBuffers(quadVboId);
        }
        if (instanceVboId != 0) {
            glDeleteBuffers(instanceVboId);
        }
        vaoId = 0;
        quadVboId = 0;
        instanceVboId = 0;
        if (instancedShader != null) {
            instancedShader.cleanup();
        }
        if (instancedWaterShader != null) {
            instancedWaterShader.cleanup();
        }
        if (instancedPriorityShader != null) {
            instancedPriorityShader.cleanup();
        }
        instancedShader = null;
        instancedWaterShader = null;
        instancedPriorityShader = null;
        defaultAttribs = null;
        waterAttribs = null;
        priorityAttribs = null;
        initialized = false;
        supported = false;
        drainCommandPool();
    }

    /**
     * Cleanup for headless mode (no GL context available).
     * Resets internal state without making GL calls.
     */
    public void cleanupHeadless() {
        cancelBatch();
        vaoId = 0;
        quadVboId = 0;
        instanceVboId = 0;
        instancedShader = null;
        instancedWaterShader = null;
        instancedPriorityShader = null;
        defaultAttribs = null;
        waterAttribs = null;
        priorityAttribs = null;
        initialized = false;
        supported = false;
        drainCommandPool();
    }

    /** Frees each pooled command's native instance buffer before discarding it. */
    private void drainCommandPool() {
        InstancedBatchCommand command;
        while ((command = commandPool.pollFirst()) != null) {
            command.release();
        }
    }

    private void initBuffers() {
        if (quadVboId != 0) {
            return;
        }
        // Create VAO (required for OpenGL 3.2+ core profile)
        vaoId = glGenVertexArrays();
        quadVboId = glGenBuffers();
        instanceVboId = glGenBuffers();

        FloatBuffer quadBuffer = MemoryUtil.memAllocFloat(8);
        quadBuffer.put(0f).put(0f);
        quadBuffer.put(1f).put(0f);
        quadBuffer.put(0f).put(1f);
        quadBuffer.put(1f).put(1f);
        quadBuffer.flip();

        glBindBuffer(GL_ARRAY_BUFFER, quadVboId);
        glBufferData(GL_ARRAY_BUFFER, quadBuffer, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        MemoryUtil.memFree(quadBuffer);
    }

    private void configureVertexArray() {
        if (vaoId == 0 || quadVboId == 0 || instanceVboId == 0) {
            return;
        }

        glBindVertexArray(vaoId);

        glBindBuffer(GL_ARRAY_BUFFER, quadVboId);
        configureVertexAttributes(defaultAttribs);
        configureVertexAttributes(waterAttribs);
        configureVertexAttributes(priorityAttribs);

        glBindBuffer(GL_ARRAY_BUFFER, instanceVboId);
        configureInstanceAttributes(defaultAttribs);
        configureInstanceAttributes(waterAttribs);
        configureInstanceAttributes(priorityAttribs);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void configureVertexAttributes(AttribLocations attribs) {
        if (attribs == null || attribs.vertexPos < 0) {
            return;
        }
        glEnableVertexAttribArray(attribs.vertexPos);
        glVertexAttribPointer(attribs.vertexPos, 2, GL_FLOAT, false, 0, 0L);
        glVertexAttribDivisor(attribs.vertexPos, 0);
    }

    private void configureInstanceAttributes(AttribLocations attribs) {
        if (attribs == null) {
            return;
        }
        int stride = FLOATS_PER_INSTANCE * Float.BYTES;
        enableInstanceAttrib(attribs.instancePos, 2, stride, 0L);
        enableInstanceAttrib(attribs.instanceSize, 2, stride, 2L * Float.BYTES);
        enableInstanceAttrib(attribs.instanceUv0, 2, stride, 4L * Float.BYTES);
        enableInstanceAttrib(attribs.instanceUv1, 2, stride, 6L * Float.BYTES);
        enableInstanceAttrib(attribs.instancePalette, 1, stride, 8L * Float.BYTES);
        enableInstanceAttrib(attribs.instanceHighPriority, 1, stride, 9L * Float.BYTES);
    }

    private void enableInstanceAttrib(int location, int size, int stride, long offset) {
        if (location < 0) {
            return;
        }
        glEnableVertexAttribArray(location);
        glVertexAttribPointer(location, size, GL_FLOAT, false, stride, offset);
        glVertexAttribDivisor(location, 1);
    }

    private boolean isInstancingSupported() {
        // LWJGL with GL3.1+ context always supports instancing
        // Check OpenGL version - instancing requires GL 3.1+ or extensions
        String version = glGetString(GL_VERSION);
        if (version != null) {
            try {
                // Parse major.minor from version string (e.g., "4.1 INTEL-..." or "3.3.0")
                String[] parts = version.split("[^0-9]+");
                if (parts.length >= 2) {
                    int major = Integer.parseInt(parts[0]);
                    int minor = Integer.parseInt(parts[1]);
                    // GL 3.3+ supports glVertexAttribDivisor (GL 3.1 only has glDrawArraysInstanced)
                    return major > 3 || (major == 3 && minor >= 3);
                }
            } catch (NumberFormatException e) {
                // Fall through to extension check
            }
        }
        // Fallback: assume support if we got this far with LWJGL
        return true;
    }

    private AttribLocations queryAttribLocations(ShaderProgram program) {
        int programId = program.getProgramId();
        return new AttribLocations(
                glGetAttribLocation(programId, "VertexPos"),
                glGetAttribLocation(programId, "InstancePos"),
                glGetAttribLocation(programId, "InstanceSize"),
                glGetAttribLocation(programId, "InstanceUv0"),
                glGetAttribLocation(programId, "InstanceUv1"),
                glGetAttribLocation(programId, "InstancePalette"),
                glGetAttribLocation(programId, "InstanceHighPriority"));
    }

    private InstancedBatchCommand obtainCommand() {
        InstancedBatchCommand command = commandPool.pollFirst();
        if (command == null) {
            command = new InstancedBatchCommand();
        }
        command.leased = true;
        return command;
    }

    private void recycleCommand(InstancedBatchCommand command) {
        if (commandPool.size() < COMMAND_POOL_LIMIT) {
            commandPool.addLast(command);
        } else {
            command.release();
        }
    }

    private static class AttribLocations {
        private final int vertexPos;
        private final int instancePos;
        private final int instanceSize;
        private final int instanceUv0;
        private final int instanceUv1;
        private final int instancePalette;
        private final int instanceHighPriority;

        private AttribLocations(int vertexPos, int instancePos, int instanceSize,
                int instanceUv0, int instanceUv1, int instancePalette, int instanceHighPriority) {
            this.vertexPos = vertexPos;
            this.instancePos = instancePos;
            this.instanceSize = instanceSize;
            this.instanceUv0 = instanceUv0;
            this.instanceUv1 = instanceUv1;
            this.instancePalette = instancePalette;
            this.instanceHighPriority = instanceHighPriority;
        }
    }

    private class InstancedBatchCommand implements GLCommandable {
        private FloatBuffer instanceBuffer;
        private int instanceCount;
        private int floatCount;
        private boolean usePriorityShader;
        private boolean useWaterShader;
        private boolean useUnderwaterPalette;
        private int atlasIndex;
        private boolean capturedGhostEffectActive;
        private float capturedGhostAlpha;
        private boolean leased;

        private void load(float[] data, int instanceCount, int atlasIndex, boolean usePriorityShader,
                          boolean useWaterShader, boolean useUnderwaterPalette,
                          boolean ghostEffectActive, float ghostAlpha) {
            this.instanceCount = instanceCount;
            this.floatCount = instanceCount * FLOATS_PER_INSTANCE;
            this.usePriorityShader = usePriorityShader;
            this.useWaterShader = useWaterShader;
            this.useUnderwaterPalette = useUnderwaterPalette;
            this.atlasIndex = atlasIndex;
            this.capturedGhostEffectActive = ghostEffectActive;
            this.capturedGhostAlpha = ghostAlpha;
            instanceBuffer = ensureBuffer(instanceBuffer, floatCount);
            instanceBuffer.clear();
            instanceBuffer.put(data, 0, floatCount);
            instanceBuffer.flip();
        }

        /** Frees the native instance buffer. Call only when discarding the command. */
        private void release() {
            if (instanceBuffer != null) {
                MemoryUtil.memFree(instanceBuffer);
                instanceBuffer = null;
            }
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
            if (instanceCount == 0 || instancedShader == null) {
                return;
            }

            // Only drain accumulated GL errors in debug view builds.
            if (drainGlErrors) {
                while (glGetError() != GL_NO_ERROR) { /* drain errors */ }
            }
            GraphicsManager gm = graphicsManager;
            boolean useWaterShader = this.useWaterShader;
            // Use captured priority shader state from batch creation time
            boolean usePriorityShader = this.usePriorityShader;

            // Select the appropriate shader based on mode
            ShaderProgram shader;
            if (usePriorityShader) {
                shader = instancedPriorityShader;
            } else if (useWaterShader) {
                shader = instancedWaterShader;
            } else {
                shader = instancedShader;
            }
            if (shader == null) {
                return;
            }

            shader.use();
            shader.cacheUniformLocations();
            glUniform1i(shader.getPaletteLocation(), 0);
            glUniform1i(shader.getIndexedColorTextureLocation(), 1);
            shader.setPaletteLine(-1.0f);
            shader.setTotalPaletteLines((float) RenderContext.getTotalPaletteLines());
            shader.setGhostEffect(capturedGhostEffectActive, capturedGhostAlpha);

            // Set priority uniforms if using the priority shader
            // Priority is now per-instance via InstanceHighPriority attribute,
            // but we still need to bind the tile priority FBO texture and screen size
            if (usePriorityShader) {
                TilePriorityFBO fbo = gm.getTilePriorityFBO();
                if (fbo != null && fbo.isInitialized()) {
                    // Use cached uniform locations instead of glGetUniformLocation every batch
                    if (cachedTilePriorityTexLoc != -1) {
                        int fboTexId = fbo.getTextureId();
                        // Use texture unit 5 for TilePriorityFBO to avoid conflicts with TilemapGpuRenderer (0-4)
                        glActiveTexture(GL_TEXTURE5);
                        glBindTexture(GL_TEXTURE_2D, fboTexId);
                        glUniform1i(cachedTilePriorityTexLoc, 5);

                        // Use cached viewport dimensions from GraphicsManager
                        if (cachedScreenSizeLoc != -1) {
                            glUniform2f(cachedScreenSizeLoc, gm.getViewportWidth(), gm.getViewportHeight());
                        }

                        if (cachedViewportOffsetLoc != -1) {
                            glUniform2f(cachedViewportOffsetLoc, gm.getViewportX(), gm.getViewportY());
                        }
                        glActiveTexture(GL_TEXTURE0);
                    }
                }

                // Bind underwater palette for per-scanline palette switching
                Integer underwaterPaletteId = gm.getUnderwaterPaletteTextureId();
                if (underwaterPaletteId != null && cachedUnderwaterPaletteLoc != -1) {
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D, underwaterPaletteId);
                    glUniform1i(cachedUnderwaterPaletteLoc, 2);
                    glActiveTexture(GL_TEXTURE0);
                }

                // Set water uniforms from cached values in GraphicsManager
                if (cachedWaterEnabledLoc != -1) {
                    glUniform1i(cachedWaterEnabledLoc, gm.isWaterEnabled() ? 1 : 0);
                }
                if (cachedWaterlineScreenYLoc != -1) {
                    glUniform1f(cachedWaterlineScreenYLoc, gm.getWaterlineScreenY());
                }
                if (cachedWindowHeightLoc != -1) {
                    glUniform1f(cachedWindowHeightLoc, gm.getWindowHeight());
                }
                if (cachedScreenHeightLoc != -1) {
                    glUniform1f(cachedScreenHeightLoc, gm.getScreenHeight());
                }
            }

            Integer paletteTextureId;
            if (useUnderwaterPalette) {
                paletteTextureId = gm.getUnderwaterPaletteTextureId();
                if (paletteTextureId == null) {
                    paletteTextureId = gm.getCombinedPaletteTextureId();
                }
            } else {
                paletteTextureId = gm.getCombinedPaletteTextureId();
            }
            if (paletteTextureId != null) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, paletteTextureId);
            }

            Integer atlasTextureId = gm.getPatternAtlasTextureId(atlasIndex);
            if (atlasTextureId != null) {
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, atlasTextureId);
            }

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

            // Select attribute locations based on which shader we're using
            AttribLocations attribs;
            if (usePriorityShader) {
                attribs = priorityAttribs;
            } else if (useWaterShader) {
                attribs = waterAttribs;
            } else {
                attribs = defaultAttribs;
            }
            if (attribs == null || quadVboId == 0 || instanceVboId == 0) {
                shader.stop();
                return;
            }

            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            // Bind VAO (required for OpenGL 3.2+ core profile)
            glBindVertexArray(vaoId);

            // Set projection matrix uniform - REQUIRED for correct rendering
            int projectionLoc;
            if (usePriorityShader) {
                projectionLoc = cachedPriorityProjectionLoc;
            } else if (useWaterShader) {
                projectionLoc = cachedWaterProjectionLoc;
            } else {
                projectionLoc = cachedDefaultProjectionLoc;
            }
            if (projectionLoc != -1) {
                float[] projMatrix = gm.getProjectionMatrixBuffer();
                if (projMatrix != null) {
                    glUniformMatrix4fv(projectionLoc, false, projMatrix);
                }
            }

            // Set camera offset uniform (replaces glTranslatef)
            // X is negated to scroll objects left when camera moves right
            // Y is NOT negated because vertex Y is already in screen space (flipped from Genesis coords)
            int cameraOffsetLoc;
            if (usePriorityShader) {
                cameraOffsetLoc = cachedPriorityCameraOffsetLoc;
            } else if (useWaterShader) {
                cameraOffsetLoc = cachedWaterCameraOffsetLoc;
            } else {
                cameraOffsetLoc = cachedDefaultCameraOffsetLoc;
            }
            if (cameraOffsetLoc != -1) {
                glUniform2f(cameraOffsetLoc, -cameraX, cameraY);
            }

            glBindBuffer(GL_ARRAY_BUFFER, instanceVboId);
            instanceBuffer.rewind();
            instanceBuffer.limit(floatCount);
            glBufferData(GL_ARRAY_BUFFER, instanceBuffer, GL_DYNAMIC_DRAW);

            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instanceCount);

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            shader.stop();
            glDisable(GL_BLEND);

            PatternRenderCommand.resetFrameState();
        }

        @Override
        public void discard() {
            if (!leased) {
                return;
            }
            leased = false;
            recycleCommand(this);
        }

        /**
         * Ensures buffer has required capacity, pre-allocating at max capacity
         * to avoid mid-frame native memory allocations.
         */
        private FloatBuffer ensureBuffer(FloatBuffer buffer, int required) {
            if (buffer == null) {
                // Pre-allocate at max batch capacity
                return MemoryUtil.memAllocFloat(MAX_PATTERNS_PER_BATCH * FLOATS_PER_INSTANCE);
            }
            if (buffer.capacity() < required) {
                MemoryUtil.memFree(buffer);
                return MemoryUtil.memAllocFloat(MAX_PATTERNS_PER_BATCH * FLOATS_PER_INSTANCE);
            }
            return buffer;
        }

        private void enableAttrib(int location, int size, int type, int stride, long offset) {
            if (location < 0) {
                return;
            }
            glEnableVertexAttribArray(location);
            glVertexAttribPointer(location, size, type, false, stride, offset);
        }
    }
}
