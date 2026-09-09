package com.openggf.level;

import com.openggf.trace.replay.TraceGhostHook;
import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteWriteSupport;
import com.openggf.game.render.AdvancedRenderFrameState;
import com.openggf.game.render.AdvancedRenderModeContext;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternRenderCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.graphics.TilePriorityFBO;
import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.graphics.WaterShaderProgram;
import com.openggf.level.objects.HudRenderManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.BackgroundRenderer;
import com.openggf.level.rings.RingManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.testmode.TraceRenderVisibility;
import com.openggf.util.ShortIndexedView;
import com.openggf.util.IntIndexedView;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE2;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL14.GL_MAX;
import static org.lwjgl.opengl.GL14.glBlendEquation;
import static org.lwjgl.opengl.GL20.glUniform1i;

/**
 * Owns the per-frame rendering pipeline previously inlined in {@link LevelManager}.
 *
 * <p>This is a pure extraction — the {@code draw()} / {@code drawWithRenderOptions()}
 * sequence, the pre-allocated {@link GLCommand} lambdas they enqueue, and the helper
 * methods that produce them all live here. {@link LevelManager} retains ownership of
 * level state, lifecycle, and frame counters, but delegates the rendering pass to
 * this class.
 *
 * <p>Behavioural parity is required: the order of {@code GLCommand} registrations
 * and the values written into shader uniforms must match the previous in-place
 * implementation pixel-for-pixel.
 */
public final class LevelRenderer {

    /**
     * Reference to the parent {@link LevelManager}. Captured by the
     * {@link GLCommand} field initializers below; non-final because Java's
     * definite-assignment rules flag the lambda captures otherwise.
     */
    private LevelManager lm;

    // Pre-allocated viewport buffer to avoid per-frame int[4] allocations inside GL commands.
    private final int[] viewportBuffer = new int[4];
    private final FrameCommandPool frameCommandPool = new FrameCommandPool();
    private int pendingFrameCounter;
    private int[] pendingFgHScrollData;
    private short[] pendingFgDefaultVScrollData;
    private IntIndexedView pendingFgHScrollView;
    private ShortIndexedView pendingFgDefaultVScrollView;
    private boolean pendingFgVerticalWrap;

    // Mutable state for the pre-allocated water shader setup command.
    private float pendingWaterlineScreenY;
    private int pendingWaterShimmerStyle;
    private boolean pendingSuppressUnderwaterPalette;

    // Mutable state for the pre-allocated BG ensureCapacity command.
    private int pendingBgRenderWidth;
    private int pendingBgRenderHeight;

    // Mutable state for the pre-allocated BG renderWithScrollWide command.
    private int[] pendingBgHScrollData;
    private short[] pendingBgVScrollData;
    private short[] pendingBgVScrollColumnData;
    private int pendingBgShaderScrollMidpoint;
    private int pendingBgShaderExtraBuffer;
    private int pendingBgVOffset;
    private boolean pendingBgPerLineScroll;
    private IntIndexedView pendingBgHScrollView;
    private ShortIndexedView pendingBgVScrollView;
    private ShortIndexedView pendingBgVScrollColumnView;

    // Mutable state for the pre-allocated FG tilemap pass commands (low + high priority).
    private float pendingFgWorldOffsetX_low;
    private float pendingFgWorldOffsetY_low;
    private int pendingFgScreenW_low;
    private int pendingFgScreenH_low;
    private int pendingFgPriorityPass_low;
    private boolean pendingFgUseUnderwater_low;
    private float pendingFgWaterlineScreenY_low;
    private Integer pendingFgAtlasId_low;
    private Integer pendingFgPaletteId_low;
    private Integer pendingFgUnderwaterPaletteId_low;

    private float pendingFgWorldOffsetX_high;
    private float pendingFgWorldOffsetY_high;
    private int pendingFgScreenW_high;
    private int pendingFgScreenH_high;
    private int pendingFgPriorityPass_high;
    private boolean pendingFgUseUnderwater_high;
    private float pendingFgWaterlineScreenY_high;
    private Integer pendingFgAtlasId_high;
    private Integer pendingFgPaletteId_high;
    private Integer pendingFgUnderwaterPaletteId_high;

    // Mutable state for the pre-allocated high-priority FBO command.
    private int pendingFboScreenW;
    private int pendingFboScreenH;
    private float pendingFboFgWorldOffsetX;
    private float pendingFboFgWorldOffsetY;
    private Integer pendingFboAtlasId;
    private Integer pendingFboPaletteId;

    // Mutable state for the pre-allocated BG tile pass command.
    private int pendingBgTilePassRenderWidth;
    private int pendingBgTilePassRenderHeight;
    private int pendingBgTilePassRingBaseXTiles;
    private int pendingBgTilePassRingBaseYTiles;
    private int pendingBgTilePassContentGeneration;
    private int completedBgTilePassFrame = Integer.MIN_VALUE;
    private int completedBgTilePassSourceGeneration = -1;
    private int completedBgTilePassCurrentGeneration = -1;
    private boolean pendingBgTilePassHasWater;
    private float pendingBgTilePassFboWaterlineY;
    private int pendingBgTilePassAlignedBgY;
    private float pendingBgTilePassBgTilemapWorldOffsetX;
    private boolean pendingBgTilePassPerLineScroll;
    private int[] pendingBgTilePassHScrollData;
    private float pendingBgTilePassVdpWrapWidth;
    private float pendingBgTilePassNametableBase;
    private float pendingBgTilePassPerLineScrollSampleYOffsetPx;
    private float pendingBgTilePassUpperBandWrapHeightPx;
    private float pendingBgTilePassUpperBandWrapWidthTiles;
    private IntIndexedView pendingBgTilePassHScrollView;

    // Render-frame state (resolved each frame from the AdvancedRenderModeController).
    private AdvancedRenderFrameState currentAdvancedRenderFrameState = AdvancedRenderFrameState.disabled();

    // Trace render-visibility gates (resolved once per frame at the top of
    // drawWithRenderOptions). Honored by both live Trace Test Mode and the headless
    // capture recorder. The ghost site is reached via deferred lambdas inside
    // renderSpriteObjectPass, so the resolved value is stashed here for that site.
    private TraceRenderVisibility currentTraceVisibility = TraceRenderVisibility.defaults();

    // Shimmer style flag, sampled by background tile pass.
    private int currentShimmerStyle = 0;

    // Pre-allocated GLCommand instances. Safe to reuse — the command list is cleared
    // each frame in flushWithCamera().

    private final GLCommand disableShimmerCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        WaterShaderProgram waterShader = lm.graphicsManager.getWaterShaderProgram();
        if (waterShader != null) {
            waterShader.use();
            waterShader.setShimmerStyle(0);
        }
        WaterShaderProgram instancedWaterShader = lm.graphicsManager.getInstancedWaterShaderProgram();
        if (instancedWaterShader != null) {
            instancedWaterShader.use();
            instancedWaterShader.setShimmerStyle(0);
        }
        if (waterShader != null) {
            waterShader.use();
        }
        PatternRenderCommand.resetFrameState();
    });

    private final GLCommand disableWaterShaderCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        lm.graphicsManager.setUseWaterShader(false);
        PatternRenderCommand.resetFrameState();
    });

    private final GLCommand waterShaderSetupCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        GraphicsManager graphics = lm.graphicsManager;
        SonicConfigurationService configuration = lm.configService;
        graphics.setUseWaterShader(true);

        Integer underwaterTextureId = resolveUnderwaterPaletteTextureForFrame();

        WaterShaderProgram shader = graphics.getWaterShaderProgram();
        shader.use();

        // viewportBuffer is cached once per frame at the top of drawWithRenderOptions /
        // renderEndingBackground — see cacheViewportForFrame(). Avoids per-site
        // glGetIntegerv pipeline syncs.
        float windowHeight = (float) viewportBuffer[3];
        float screenHeightPixels = (float) configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

        shader.setWindowHeight(windowHeight);
        shader.setWaterlineScreenY(pendingWaterlineScreenY);
        shader.setFrameCounter(pendingFrameCounter);
        shader.setDistortionAmplitude(0.0f);
        shader.setShimmerStyle(pendingWaterShimmerStyle);
        shader.setIndexedTextureWidth(graphics.getPatternAtlasWidth());
        shader.setScreenDimensions((float) configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
                screenHeightPixels);

        graphics.setWaterEnabled(!pendingSuppressUnderwaterPalette);
        graphics.setWaterlineScreenY(pendingWaterlineScreenY);
        graphics.setWindowHeight(windowHeight);
        graphics.setScreenHeight(screenHeightPixels);

        if (underwaterTextureId != null) {
            int loc = shader.getUnderwaterPaletteLocation();

            if (loc != -1) {
                glActiveTexture(GL_TEXTURE2);
                glBindTexture(GL_TEXTURE_2D, underwaterTextureId);
                glUniform1i(loc, 2);
                glActiveTexture(GL_TEXTURE0);
            }
        }

        TilemapGpuRenderer tilemapRenderer = graphics.getTilemapGpuRenderer();
        if (tilemapRenderer != null) {
            tilemapRenderer.setShimmerState(pendingFrameCounter, pendingWaterShimmerStyle);
        }

        WaterShaderProgram instancedShader = graphics.getInstancedWaterShaderProgram();
        if (instancedShader != null) {
            instancedShader.use();
            instancedShader.cacheUniformLocations();
            instancedShader.setWindowHeight(windowHeight);
            instancedShader.setWaterlineScreenY(pendingWaterlineScreenY);
            instancedShader.setFrameCounter(pendingFrameCounter);
            instancedShader.setDistortionAmplitude(0.0f);
            instancedShader.setShimmerStyle(pendingWaterShimmerStyle);
            instancedShader.setIndexedTextureWidth(graphics.getPatternAtlasWidth());
            instancedShader.setScreenDimensions((float) configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
                    (float) configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS));

            if (underwaterTextureId != null) {
                int loc = instancedShader.getUnderwaterPaletteLocation();
                if (loc != -1) {
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D, underwaterTextureId);
                    glUniform1i(loc, 2);
                    glActiveTexture(GL_TEXTURE0);
                }
            }
            shader.use();
        }
    });

    private final GLCommand bgEnsureCapacityCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        BackgroundRenderer bgRenderer = lm.graphicsManager.getBackgroundRenderer();
        if (bgRenderer != null) {
            bgRenderer.ensureCapacity(pendingBgRenderWidth, pendingBgRenderHeight);
        }
    });

    private final GLCommand bgRenderWithScrollCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        BackgroundRenderer bgRenderer = lm.graphicsManager.getBackgroundRenderer();
        TilemapGpuRenderer tilemapRenderer = lm.graphicsManager.getTilemapGpuRenderer();
        if (bgRenderer != null && tilemapRenderer != null
                && isBackgroundCompositeCurrent(tilemapRenderer)) {
            bgRenderer.renderWithScrollWide(pendingBgHScrollView, pendingBgVScrollView, pendingBgVScrollColumnView,
                    pendingBgShaderScrollMidpoint, pendingBgShaderExtraBuffer,
                    pendingBgVOffset, pendingBgPerLineScroll);
        }
    });

    private final GLCommand fgTilemapPassLowCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        TilemapGpuRenderer tilemapRenderer = lm.graphicsManager.getTilemapGpuRenderer();
        if (tilemapRenderer == null) {
            return;
        }
        applyForegroundScrollFeatures(tilemapRenderer);
        enableForegroundPerColumnVScroll(tilemapRenderer);
        // viewportBuffer cached once per frame; see cacheViewportForFrame().
        tilemapRenderer.render(
                TilemapGpuRenderer.Layer.FOREGROUND,
                pendingFgScreenW_low,
                pendingFgScreenH_low,
                viewportBuffer[0],
                viewportBuffer[1],
                viewportBuffer[2],
                viewportBuffer[3],
                pendingFgWorldOffsetX_low,
                pendingFgWorldOffsetY_low,
                lm.graphicsManager.getPatternAtlasWidth(),
                lm.graphicsManager.getPatternAtlasHeight(),
                pendingFgAtlasId_low,
                pendingFgPaletteId_low,
                pendingFgUnderwaterPaletteId_low != null ? pendingFgUnderwaterPaletteId_low : 0,
                pendingFgPriorityPass_low,
                pendingFgVerticalWrap,
                false,
                pendingFgUseUnderwater_low,
                pendingFgWaterlineScreenY_low);
    });

    private final GLCommand fgTilemapPassHighCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        TilemapGpuRenderer tilemapRenderer = lm.graphicsManager.getTilemapGpuRenderer();
        if (tilemapRenderer == null) {
            return;
        }
        applyForegroundScrollFeatures(tilemapRenderer);
        enableForegroundPerColumnVScroll(tilemapRenderer);
        // viewportBuffer cached once per frame; see cacheViewportForFrame().
        tilemapRenderer.render(
                TilemapGpuRenderer.Layer.FOREGROUND,
                pendingFgScreenW_high,
                pendingFgScreenH_high,
                viewportBuffer[0],
                viewportBuffer[1],
                viewportBuffer[2],
                viewportBuffer[3],
                pendingFgWorldOffsetX_high,
                pendingFgWorldOffsetY_high,
                lm.graphicsManager.getPatternAtlasWidth(),
                lm.graphicsManager.getPatternAtlasHeight(),
                pendingFgAtlasId_high,
                pendingFgPaletteId_high,
                pendingFgUnderwaterPaletteId_high != null ? pendingFgUnderwaterPaletteId_high : 0,
                pendingFgPriorityPass_high,
                pendingFgVerticalWrap,
                false,
                pendingFgUseUnderwater_high,
                pendingFgWaterlineScreenY_high);
    });

    private final GLCommand highPriorityFboCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        TilePriorityFBO tileFbo = lm.graphicsManager.getTilePriorityFBO();
        TilemapGpuRenderer tilemapRenderer = lm.graphicsManager.getTilemapGpuRenderer();
        if (tileFbo == null || tilemapRenderer == null) {
            return;
        }

        tileFbo.begin();

        glEnable(GL_BLEND);
        glBlendEquation(GL_MAX);
        glBlendFunc(GL_ONE, GL_ONE);

        applyForegroundScrollFeatures(tilemapRenderer);
        enableForegroundPerColumnVScroll(tilemapRenderer);
        tilemapRenderer.render(
                TilemapGpuRenderer.Layer.FOREGROUND,
                pendingFboScreenW,
                pendingFboScreenH,
                0, 0, pendingFboScreenW, pendingFboScreenH,
                pendingFboFgWorldOffsetX,
                pendingFboFgWorldOffsetY,
                lm.graphicsManager.getPatternAtlasWidth(),
                lm.graphicsManager.getPatternAtlasHeight(),
                pendingFboAtlasId,
                pendingFboPaletteId,
                0, 1, pendingFgVerticalWrap, true, false, 0.0f);
        lm.graphicsManager.executeCapturedCommands(
                () -> dispatchSpecialRenderEffects(SpecialRenderEffectStage.SPRITE_PRIORITY_MASK, pendingFrameCounter),
                Math.round(pendingFboFgWorldOffsetX),
                Math.round(pendingFboFgWorldOffsetY),
                pendingFboScreenW,
                pendingFboScreenH);

        glBlendEquation(GL_FUNC_ADD);
        glDisable(GL_BLEND);

        tileFbo.end();
    });

    private final GLCommand bgTilePassCommand = new GLCommand(GLCommand.CommandType.CUSTOM, (cx, cy, cw, ch) -> {
        BackgroundRenderer bgRenderer = lm.graphicsManager.getBackgroundRenderer();
        TilemapGpuRenderer tilemapRenderer = lm.graphicsManager.getTilemapGpuRenderer();
        if (bgRenderer == null || tilemapRenderer == null
                || !tilemapRenderer.isBackgroundContentGenerationCurrent(
                        pendingBgTilePassContentGeneration)) {
            return;
        }
        bgRenderer.beginTilePass(pendingBgTilePassRenderWidth, pendingBgTilePassRenderHeight, true);
        int savedShimmerStyle = tilemapRenderer.getShimmerStyle();
        try {
            tilemapRenderer.setShimmerState(pendingFrameCounter, 0);
            try {
                Integer atlasId = lm.graphicsManager.getPatternAtlasTextureId();
                Integer paletteId = lm.graphicsManager.getCombinedPaletteTextureId();
                Integer underwaterPaletteId = lm.graphicsManager.getUnderwaterPaletteTextureId();
                boolean useUnderwaterPalette = pendingBgTilePassHasWater && underwaterPaletteId != null;
                if (atlasId != null && paletteId != null) {
                    if (pendingBgTilePassPerLineScroll) {
                        bgRenderer.uploadHScroll(pendingBgTilePassHScrollView);
                        tilemapRenderer.enablePerLineScroll(
                                bgRenderer.getHScrollTextureId(), 224.0f,
                                pendingBgTilePassVdpWrapWidth, pendingBgTilePassNametableBase,
                                pendingBgTilePassPerLineScrollSampleYOffsetPx);
                    }
                    tilemapRenderer.setUpperBandWrap(
                            pendingBgTilePassUpperBandWrapHeightPx,
                            pendingBgTilePassUpperBandWrapWidthTiles);
                    tilemapRenderer.setBackgroundRenderRingBaseOverride(
                            pendingBgTilePassRingBaseXTiles,
                            pendingBgTilePassRingBaseYTiles,
                            pendingBgTilePassContentGeneration);
                    tilemapRenderer.render(
                            TilemapGpuRenderer.Layer.BACKGROUND,
                            pendingBgTilePassRenderWidth,
                            pendingBgTilePassRenderHeight,
                            0, 0,
                            pendingBgTilePassRenderWidth,
                            pendingBgTilePassRenderHeight,
                            pendingBgTilePassBgTilemapWorldOffsetX,
                            (float) pendingBgTilePassAlignedBgY,
                            lm.graphicsManager.getPatternAtlasWidth(),
                            lm.graphicsManager.getPatternAtlasHeight(),
                            atlasId, paletteId,
                            underwaterPaletteId != null ? underwaterPaletteId : 0,
                            -1, true, false, useUnderwaterPalette,
                            pendingBgTilePassFboWaterlineY);
                    completedBgTilePassFrame = pendingFrameCounter;
                    completedBgTilePassSourceGeneration = pendingBgTilePassContentGeneration;
                    completedBgTilePassCurrentGeneration = tilemapRenderer.getBackgroundContentGeneration();
                }
            } finally {
                tilemapRenderer.setShimmerState(pendingFrameCounter, savedShimmerStyle);
            }
        } finally {
            try {
                bgRenderer.endTilePass();
            } finally {
                lm.graphicsManager.setUseUnderwaterPaletteForBackground(false);
            }
        }
    });

    static final class FrameCommandPool {
        private final ArrayDeque<FrameCommand> available = new ArrayDeque<>();
        private final Set<FrameCommand> outstanding = Collections.newSetFromMap(new IdentityHashMap<>());

        FrameCommand obtain(LevelRenderer owner, FrameCommand.Kind kind) {
            FrameCommand command = available.pollLast();
            if (command == null) command = new FrameCommand(this);
            command.leased = true;
            command.cancelled = false;
            outstanding.add(command);
            command.capture(owner, kind);
            return command;
        }

        FrameCommand obtainForTesting(int[] viewport, int[] scroll) {
            return obtainForTesting(viewport, scroll, null);
        }

        FrameCommand obtainForTesting(int[] viewport, int[] scroll, short[] shortScroll) {
            return obtainForTesting(viewport, scroll, shortScroll, (short[]) null);
        }

        FrameCommand obtainForTesting(int[] viewport, int[] scroll, short[] shortScroll,
                                      short[] columnShortScroll) {
            return obtainForTesting(viewport, scroll, shortScroll, columnShortScroll,
                    FrameCommand.Kind.TEST, false);
        }

        FrameCommand obtainForTesting(int[] viewport, int[] scroll, short[] shortScroll, FrameCommand.Kind kind) {
            return obtainForTesting(viewport, scroll, shortScroll, null, kind, false);
        }

        FrameCommand obtainForTesting(int[] viewport, int[] scroll, short[] shortScroll,
                                      FrameCommand.Kind kind, boolean verticalWrap) {
            return obtainForTesting(viewport, scroll, shortScroll, null, kind, verticalWrap);
        }

        private FrameCommand obtainForTesting(int[] viewport, int[] scroll, short[] shortScroll,
                                              short[] columnShortScroll, FrameCommand.Kind kind,
                                              boolean verticalWrap) {
            FrameCommand command = available.pollLast();
            if (command == null) command = new FrameCommand(this);
            command.leased = true;
            command.cancelled = false;
            outstanding.add(command);
            command.owner = null;
            command.kind = kind;
            command.bools[6] = verticalWrap;
            System.arraycopy(viewport, 0, command.viewport, 0, 4);
            command.setScroll0(scroll);
            command.setShort0(shortScroll);
            command.setShort1(columnShortScroll);
            return command;
        }

        void cancelOutstanding() {
            for (FrameCommand command : outstanding) command.cancelForReset();
            outstanding.clear();
        }

        private void onDiscard(FrameCommand command) { outstanding.remove(command); }
        private void release(FrameCommand command) { available.addLast(command); }
    }

    static final class FrameCommand implements GLCommandable {
        enum Kind { WATER, BG_ENSURE, BG_RENDER, FG_LOW, FG_HIGH, HIGH_FBO, BG_TILE, TEST }

        private final FrameCommandPool pool;
        private final int[] viewport = new int[4];
        private final int[] ints = new int[22];
        private final float[] floats = new float[18];
        private final boolean[] bools = new boolean[8];
        private final Object[] refs = new Object[8];
        private int[] scroll0;
        private short[] short0;
        private short[] short1;
        private int scroll0Length;
        private int short0Length;
        private int short1Length;
        private boolean scroll0Present;
        private boolean short0Present;
        private boolean short1Present;
        private final IntIndexedView scroll0View = new IntIndexedView() {
            @Override public int size() { return scroll0Length; }
            @Override public int get(int index) { return scrollAt(index); }
        };
        private final ShortIndexedView short0View = new ShortIndexedView() {
            @Override public int size() { return short0Length; }
            @Override public short get(int index) { return shortScrollAt(index); }
        };
        private final ShortIndexedView short1View = new ShortIndexedView() {
            @Override public int size() { return short1Length; }
            @Override public short get(int index) {
                if (index < 0 || index >= short1Length) throw new IndexOutOfBoundsException(index);
                return short1[index];
            }
        };
        private LevelRenderer owner;
        private Kind kind;
        private boolean leased;
        private boolean cancelled;

        private FrameCommand(FrameCommandPool pool) { this.pool = pool; }

        private void capture(LevelRenderer r, Kind kind) {
            owner = r;
            this.kind = kind;
            System.arraycopy(r.viewportBuffer, 0, viewport, 0, 4);
            ints[0] = r.lm.frameCounter;
            ints[1] = r.pendingWaterShimmerStyle;
            ints[2] = r.pendingBgRenderWidth;
            ints[3] = r.pendingBgRenderHeight;
            ints[4] = r.pendingBgShaderScrollMidpoint;
            ints[5] = r.pendingBgShaderExtraBuffer;
            ints[6] = r.pendingBgVOffset;
            ints[7] = r.pendingFgScreenW_low;
            ints[8] = r.pendingFgScreenH_low;
            ints[9] = r.pendingFgPriorityPass_low;
            ints[10] = r.pendingFgScreenW_high;
            ints[11] = r.pendingFgScreenH_high;
            ints[12] = r.pendingFgPriorityPass_high;
            ints[13] = r.pendingFboScreenW;
            ints[14] = r.pendingFboScreenH;
            ints[15] = r.pendingBgTilePassRenderWidth;
            ints[16] = r.pendingBgTilePassRenderHeight;
            ints[17] = r.pendingBgTilePassAlignedBgY;
            ints[18] = r.currentShimmerStyle;
            ints[19] = r.pendingBgTilePassRingBaseXTiles;
            ints[20] = r.pendingBgTilePassRingBaseYTiles;
            ints[21] = r.pendingBgTilePassContentGeneration;
            floats[0] = r.pendingWaterlineScreenY;
            floats[1] = r.pendingFgWorldOffsetX_low;
            floats[2] = r.pendingFgWorldOffsetY_low;
            floats[3] = r.pendingFgWaterlineScreenY_low;
            floats[4] = r.pendingFgWorldOffsetX_high;
            floats[5] = r.pendingFgWorldOffsetY_high;
            floats[6] = r.pendingFgWaterlineScreenY_high;
            floats[7] = r.pendingFboFgWorldOffsetX;
            floats[8] = r.pendingFboFgWorldOffsetY;
            floats[9] = r.pendingBgTilePassFboWaterlineY;
            floats[10] = r.pendingBgTilePassBgTilemapWorldOffsetX;
            floats[11] = r.pendingBgTilePassVdpWrapWidth;
            floats[12] = r.pendingBgTilePassNametableBase;
            floats[13] = r.pendingBgTilePassPerLineScrollSampleYOffsetPx;
            floats[14] = r.pendingBgTilePassUpperBandWrapHeightPx;
            floats[15] = r.pendingBgTilePassUpperBandWrapWidthTiles;
            bools[0] = r.pendingSuppressUnderwaterPalette;
            bools[1] = r.pendingBgPerLineScroll;
            bools[2] = r.pendingFgUseUnderwater_low;
            bools[3] = r.pendingFgUseUnderwater_high;
            bools[4] = r.pendingBgTilePassHasWater;
            bools[5] = r.pendingBgTilePassPerLineScroll;
            bools[6] = r.lm.verticalWrapEnabled;
            refs[0] = r.pendingFgAtlasId_low;
            refs[1] = r.pendingFgPaletteId_low;
            refs[2] = r.pendingFgUnderwaterPaletteId_low;
            refs[3] = r.pendingFgAtlasId_high;
            refs[4] = r.pendingFgPaletteId_high;
            refs[5] = r.pendingFgUnderwaterPaletteId_high;
            refs[6] = r.pendingFboAtlasId;
            refs[7] = r.pendingFboPaletteId;
            advancedState = r.currentAdvancedRenderFrameState;
            scroll0Length = 0;
            short0Length = 0;
            short1Length = 0;
            scroll0Present = false;
            short0Present = false;
            short1Present = false;

            switch (kind) {
                case BG_RENDER -> {
                    setScroll0(r.pendingBgHScrollData);
                    setShort0(r.pendingBgVScrollData);
                    setShort1(r.pendingBgVScrollColumnData);
                }
                case FG_LOW, FG_HIGH, HIGH_FBO -> {
                    setScroll0(r.lm.parallaxManager.getHScrollForShader());
                    setShort0(r.lm.parallaxManager.getVScrollPerColumnFGForShader());
                }
                case BG_TILE -> setScroll0(r.pendingBgTilePassHScrollData);
                default -> { }
            }
        }

        private AdvancedRenderFrameState advancedState;

        private void load() {
            LevelRenderer r = owner;
            System.arraycopy(viewport, 0, r.viewportBuffer, 0, 4);
            r.pendingFrameCounter = ints[0];
            r.pendingWaterShimmerStyle = ints[1];
            r.pendingBgRenderWidth = ints[2];
            r.pendingBgRenderHeight = ints[3];
            r.pendingBgShaderScrollMidpoint = ints[4];
            r.pendingBgShaderExtraBuffer = ints[5];
            r.pendingBgVOffset = ints[6];
            r.pendingFgScreenW_low = ints[7];
            r.pendingFgScreenH_low = ints[8];
            r.pendingFgPriorityPass_low = ints[9];
            r.pendingFgScreenW_high = ints[10];
            r.pendingFgScreenH_high = ints[11];
            r.pendingFgPriorityPass_high = ints[12];
            r.pendingFboScreenW = ints[13];
            r.pendingFboScreenH = ints[14];
            r.pendingBgTilePassRenderWidth = ints[15];
            r.pendingBgTilePassRenderHeight = ints[16];
            r.pendingBgTilePassAlignedBgY = ints[17];
            r.currentShimmerStyle = ints[18];
            r.pendingBgTilePassRingBaseXTiles = ints[19];
            r.pendingBgTilePassRingBaseYTiles = ints[20];
            r.pendingBgTilePassContentGeneration = ints[21];
            r.pendingWaterlineScreenY = floats[0];
            r.pendingFgWorldOffsetX_low = floats[1];
            r.pendingFgWorldOffsetY_low = floats[2];
            r.pendingFgWaterlineScreenY_low = floats[3];
            r.pendingFgWorldOffsetX_high = floats[4];
            r.pendingFgWorldOffsetY_high = floats[5];
            r.pendingFgWaterlineScreenY_high = floats[6];
            r.pendingFboFgWorldOffsetX = floats[7];
            r.pendingFboFgWorldOffsetY = floats[8];
            r.pendingBgTilePassFboWaterlineY = floats[9];
            r.pendingBgTilePassBgTilemapWorldOffsetX = floats[10];
            r.pendingBgTilePassVdpWrapWidth = floats[11];
            r.pendingBgTilePassNametableBase = floats[12];
            r.pendingBgTilePassPerLineScrollSampleYOffsetPx = floats[13];
            r.pendingBgTilePassUpperBandWrapHeightPx = floats[14];
            r.pendingBgTilePassUpperBandWrapWidthTiles = floats[15];
            r.pendingSuppressUnderwaterPalette = bools[0];
            r.pendingBgPerLineScroll = bools[1];
            r.pendingFgUseUnderwater_low = bools[2];
            r.pendingFgUseUnderwater_high = bools[3];
            r.pendingBgTilePassHasWater = bools[4];
            r.pendingBgTilePassPerLineScroll = bools[5];
            r.pendingFgVerticalWrap = bools[6];
            r.pendingFgAtlasId_low = (Integer) refs[0];
            r.pendingFgPaletteId_low = (Integer) refs[1];
            r.pendingFgUnderwaterPaletteId_low = (Integer) refs[2];
            r.pendingFgAtlasId_high = (Integer) refs[3];
            r.pendingFgPaletteId_high = (Integer) refs[4];
            r.pendingFgUnderwaterPaletteId_high = (Integer) refs[5];
            r.pendingFboAtlasId = (Integer) refs[6];
            r.pendingFboPaletteId = (Integer) refs[7];
            r.currentAdvancedRenderFrameState = advancedState;
            switch (kind) {
                case BG_RENDER -> {
                    r.pendingBgHScrollView = scroll0Present ? scroll0View : null;
                    r.pendingBgVScrollView = short0Present ? short0View : null;
                    r.pendingBgVScrollColumnView = short1Present ? short1View : null;
                }
                case FG_LOW, FG_HIGH, HIGH_FBO -> {
                    r.pendingFgHScrollView = scroll0Present ? scroll0View : null;
                    r.pendingFgDefaultVScrollView = short0Present ? short0View : null;
                }
                case BG_TILE -> r.pendingBgTilePassHScrollView = scroll0Present ? scroll0View : null;
                default -> { }
            }
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            try {
                if (owner == null || kind == Kind.TEST) return;
                load();
                switch (kind) {
                    case WATER -> owner.waterShaderSetupCommand.execute(cameraX, cameraY, cameraWidth, cameraHeight);
                    case BG_ENSURE -> owner.bgEnsureCapacityCommand.execute(cameraX, cameraY, cameraWidth, cameraHeight);
                    case BG_RENDER -> owner.bgRenderWithScrollCommand.execute(cameraX, cameraY, cameraWidth, cameraHeight);
                    case FG_LOW -> owner.fgTilemapPassLowCommand.execute(cameraX, cameraY, cameraWidth, cameraHeight);
                    case FG_HIGH -> owner.fgTilemapPassHighCommand.execute(cameraX, cameraY, cameraWidth, cameraHeight);
                    case HIGH_FBO -> owner.highPriorityFboCommand.execute(cameraX, cameraY, cameraWidth, cameraHeight);
                    case BG_TILE -> owner.bgTilePassCommand.execute(cameraX, cameraY, cameraWidth, cameraHeight);
                    case TEST -> { }
                }
            } finally { discard(); }
        }

        @Override
        public void discard() {
            if (!leased && !cancelled) return;
            if (leased) pool.onDiscard(this);
            leased = false;
            cancelled = false;
            owner = null;
            advancedState = null;
            pool.release(this);
        }

        private void cancelForReset() {
            if (!leased) return;
            leased = false;
            cancelled = true;
            owner = null;
            advancedState = null;
        }

        private void setScroll0(int[] source) {
            scroll0Present = source != null;
            scroll0Length = source == null ? 0 : source.length;
            if (scroll0Length == 0) return;
            if (scroll0 == null || scroll0.length < scroll0Length) {
                int capacity = scroll0 == null ? scroll0Length : Math.max(scroll0Length, scroll0.length * 2);
                scroll0 = new int[capacity];
            }
            System.arraycopy(source, 0, scroll0, 0, scroll0Length);
        }

        private void setShort0(short[] source) {
            short0Present = source != null;
            short0Length = source == null ? 0 : source.length;
            if (short0Length == 0) return;
            if (short0 == null || short0.length < short0Length) {
                int capacity = short0 == null ? short0Length : Math.max(short0Length, short0.length * 2);
                short0 = new short[capacity];
            }
            System.arraycopy(source, 0, short0, 0, short0Length);
        }

        private void setShort1(short[] source) {
            short1Present = source != null;
            short1Length = source == null ? 0 : source.length;
            if (short1Length == 0) return;
            if (short1 == null || short1.length < short1Length) {
                int capacity = short1 == null ? short1Length : Math.max(short1Length, short1.length * 2);
                short1 = new short[capacity];
            }
            System.arraycopy(source, 0, short1, 0, short1Length);
        }

        int viewportAt(int index) { return viewport[index]; }
        int scrollAt(int index) {
            if (index < 0 || index >= scroll0Length) throw new IndexOutOfBoundsException(index);
            return scroll0[index];
        }
        short shortScrollAt(int index) {
            if (index < 0 || index >= short0Length) throw new IndexOutOfBoundsException(index);
            return short0[index];
        }
        short columnShortScrollAt(int index) {
            if (index < 0 || index >= short1Length) throw new IndexOutOfBoundsException(index);
            return short1[index];
        }
        int scrollLength() { return scroll0Length; }
        int shortScrollLength() { return short0Length; }
        int columnShortScrollLength() { return short1Length; }
        int scrollCapacity() { return scroll0 == null ? 0 : scroll0.length; }
        int shortScrollCapacity() { return short0 == null ? 0 : short0.length; }
        int columnShortScrollCapacity() { return short1 == null ? 0 : short1.length; }
        Object viewportBackingIdentity() { return viewport; }
        Object scrollBackingIdentity() { return scroll0; }
        Object shortScrollBackingIdentity() { return short0; }
        Object columnShortScrollBackingIdentity() { return short1; }
        boolean isCancelledForTesting() { return cancelled; }
        boolean isLeasedForTesting() { return leased; }
        boolean verticalWrapForTesting() { return bools[6]; }
    }

    LevelRenderer(LevelManager levelManager) {
        this.lm = levelManager;
    }

    private boolean isBackgroundCompositeCurrent(TilemapGpuRenderer tilemapRenderer) {
        return completedBgTilePassFrame == pendingFrameCounter
                && completedBgTilePassSourceGeneration == pendingBgTilePassContentGeneration
                && tilemapRenderer.isBackgroundContentGenerationCurrent(
                        completedBgTilePassCurrentGeneration);
    }

    void setBackgroundCompositeStateForTesting(int pendingFrame, int pendingSourceGeneration,
            int completedFrame, int completedSourceGeneration, int completedCurrentGeneration) {
        pendingFrameCounter = pendingFrame;
        pendingBgTilePassContentGeneration = pendingSourceGeneration;
        completedBgTilePassFrame = completedFrame;
        completedBgTilePassSourceGeneration = completedSourceGeneration;
        completedBgTilePassCurrentGeneration = completedCurrentGeneration;
    }

    boolean isBackgroundCompositeCurrentForTesting(TilemapGpuRenderer renderer) {
        return isBackgroundCompositeCurrent(renderer);
    }

    /** Returns the AdvancedRenderFrameState resolved for the current frame. */
    public AdvancedRenderFrameState getCurrentAdvancedRenderFrameState() {
        return currentAdvancedRenderFrameState;
    }

    /** Resets per-frame derived state (used when the level is unloaded). */
    void resetState() {
        frameCommandPool.cancelOutstanding();
        currentShimmerStyle = 0;
        currentAdvancedRenderFrameState = AdvancedRenderFrameState.disabled();
        completedBgTilePassFrame = Integer.MIN_VALUE;
        completedBgTilePassSourceGeneration = -1;
        completedBgTilePassCurrentGeneration = -1;
    }

    /** Resolves and content-validates the underwater texture once for this shader setup command. */
    Integer resolveUnderwaterPaletteTextureForFrame() {
        int zoneId = lm.getFeatureZoneId();
        Palette[] underwater = lm.waterSystem.getUnderwaterPalette(zoneId, lm.currentAct);
        if (underwater == null) {
            return null;
        }
        Palette normalLine0 = lm.level != null ? lm.level.getPalette(0) : null;
        return lm.graphicsManager.cacheUnderwaterPaletteTexture(underwater, normalLine0);
    }

    /** Drains the GLCommand to disable water shader (used by callers needing post-pass cleanup). */
    GLCommand getDisableWaterShaderCommand() {
        return disableWaterShaderCommand;
    }

    /** Exposes the deferred water setup command to package-level render integration tests. */
    GLCommand getWaterShaderSetupCommand() {
        pendingFrameCounter = lm.frameCounter;
        return waterShaderSetupCommand;
    }

    /**
     * Reads the current GL viewport into {@link #viewportBuffer} once per frame.
     *
     * <p>The pre-allocated {@link GLCommand} lambdas for water shader setup and
     * FG low/high passes expect the screen-space viewport. The BG tile pass runs
     * inside the background FBO and passes that viewport explicitly.
     * Skipped in headless mode where there is no GL context.
     */
    private void cacheViewportForFrame() {
        if (lm.graphicsManager == null || !lm.graphicsManager.isGlInitialized()) {
            return;
        }
        glGetIntegerv(GL_VIEWPORT, viewportBuffer);
    }

    private void applyForegroundScrollFeatures(TilemapGpuRenderer tilemapRenderer) {
        tilemapRenderer.setForegroundWindow(lm.zoneFeatureProvider == null
                ? null : lm.zoneFeatureProvider.foregroundWindow());
        if (currentAdvancedRenderFrameState.enableForegroundHeatHaze()
                || currentAdvancedRenderFrameState.enablePerLineForegroundScroll()) {
            tilemapRenderer.enablePerLineForegroundScroll(pendingFgHScrollView);
        }
    }

    private void enableForegroundPerColumnVScroll(TilemapGpuRenderer tilemapRenderer) {
        ShortIndexedView override = currentAdvancedRenderFrameState.foregroundPerColumnVScrollView();
        if (override != null) {
            tilemapRenderer.enablePerColumnVScroll(override);
            return;
        }
        ShortIndexedView defaultColumns = pendingFgDefaultVScrollView;
        if (defaultColumns != null) {
            tilemapRenderer.enablePerColumnVScroll(defaultColumns);
        }
    }

    /**
     * Game-agnostic fallback resolution for {@link PaletteOwnershipRegistry}
     * writes. Per-game palette cyclers resolve where they exist (S2 cycling
     * zones, S3K); games and zones without one (S1, S2 SCZ/DEZ) would
     * otherwise silently drop registry-submitted writes such as the shared
     * boss hit-flash. No-op when the registry already resolved this frame.
     */
    private void resolvePendingPaletteOwnershipWrites() {
        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistryOrNull();
        if (registry == null || lm.level == null) {
            return;
        }
        Palette[] underwaterPalettes = lm.waterSystem != null
                ? lm.waterSystem.getUnderwaterPalette(lm.getFeatureZoneId(), lm.getFeatureActId())
                : null;
        PaletteWriteSupport.resolvePendingFrameWrites(registry, lm.level, underwaterPalettes, lm.graphicsManager);
    }

    private void resolveAdvancedRenderFrameState(int frameCounter) {
        AdvancedRenderModeController controller = GameServices.advancedRenderModeControllerOrNull();
        if (controller == null || controller.isEmpty() || lm.camera == null) {
            currentAdvancedRenderFrameState = AdvancedRenderFrameState.disabled();
            return;
        }
        currentAdvancedRenderFrameState = controller.resolve(new AdvancedRenderModeContext(
                lm.camera,
                frameCounter,
                lm,
                lm.getFeatureZoneId(),
                lm.getFeatureActId(),
                lm.camera.getX()));
    }

    void dispatchSpecialRenderEffects(SpecialRenderEffectStage stage, int frameCounter) {
        SpecialRenderEffectRegistry registry = GameServices.specialRenderEffectRegistryOrNull();
        if (registry == null || registry.isEmpty() || lm.camera == null || lm.graphicsManager == null) {
            return;
        }
        registry.dispatch(stage, new SpecialRenderEffectContext(lm.camera, frameCounter, lm, lm.graphicsManager));
    }

    public void drawWithRenderOptions(SpriteManager spriteManager, LevelManager.LevelRenderOptions renderOptions) {
        if (lm.level == null) {
            LevelManager.LOGGER.warning("No level loaded to draw.");
            return;
        }
        LevelManager.LevelRenderOptions options = renderOptions != null ? renderOptions : LevelManager.LevelRenderOptions.gameplay();

        // Resolve trace render-visibility gates once per frame. Honored by both live
        // Trace Test Mode and the headless capture recorder (shared renderer). The
        // ghost site checks traceSession != null as well, so normal gameplay (no active
        // trace session) is unaffected regardless of these flags.
        currentTraceVisibility = TraceRenderVisibility.fromConfig(lm.configService);

        // Cache the GL viewport once per frame so the deferred GL commands below
        // (water shader setup, FG low/high passes) can reuse it
        // instead of issuing redundant glGetIntegerv pipeline syncs.
        cacheViewportForFrame();

        Camera camera = lm.camera;
        resolvePendingPaletteOwnershipWrites();
        resolveAdvancedRenderFrameState(lm.frameCounter);

        List<GLCommand> collisionCommands = lm.debugRenderer != null
                ? lm.debugRenderer.getCollisionCommands() : new ArrayList<>();
        collisionCommands.clear();

        PerformanceProfiler profiler = lm.profiler;
        // Update water shader state before rendering level
        profiler.beginSection("render.water_setup");
        updateWaterShaderState(camera);
        profiler.endSection("render.water_setup");

        // Draw Background (Layer 1)
        profiler.beginSection("render.bg");
        if (lm.useShaderBackground && lm.graphicsManager.getBackgroundRenderer() != null) {
            renderBackgroundShader(collisionCommands);
        }
        profiler.endSection("render.bg");

        if (lm.zoneFeatureProvider != null) {
            lm.zoneFeatureProvider.renderAfterBackground(camera, lm.frameCounter);
        }
        dispatchSpecialRenderEffects(SpecialRenderEffectStage.AFTER_BACKGROUND, lm.frameCounter);

        // Draw Foreground (Layer 0) low-priority pass
        profiler.beginSection("render.fg");
        if (lm.tilemapManager != null && lm.zoneFeatureProvider != null
                && lm.zoneFeatureProvider.foregroundWrapsHorizontally()) {
            lm.tilemapManager.setForegroundRingCamera((int) camera.getXWithShake(), lm.cachedScreenWidth,
                    lm.zoneFeatureProvider.foregroundWorldWrapOffset());
        }
        lm.ensureForegroundTilemapData();
        enqueueForegroundTilemapPass(camera, 0);

        // Generate collision debug overlay commands (independent of GPU/CPU path)
        DebugOverlayManager overlayManager = lm.overlayManager;
        if (lm.debugRenderer != null && overlayManager.isEnabled(DebugOverlayToggle.COLLISION_VIEW)) {
            lm.debugRenderer.generateCollisionDebugCommands(collisionCommands, camera, lm::getBlockAtPosition);
        }

        // Render collision debug overlay on top of foreground tiles
        if (!collisionCommands.isEmpty() && overlayManager.isEnabled(DebugOverlayToggle.COLLISION_VIEW)) {
            for (GLCommand cmd : collisionCommands) {
                lm.graphicsManager.registerCommand(cmd);
            }
        }

        // Generate tile priority debug overlay commands (shows high-priority tiles in red)
        if (lm.debugRenderer != null && overlayManager.isEnabled(DebugOverlayToggle.TILE_PRIORITY_VIEW)) {
            List<GLCommand> priorityDebugCommands = lm.debugRenderer.getPriorityDebugCommands();
            priorityDebugCommands.clear();
            lm.debugRenderer.generateTilePriorityDebugCommands(priorityDebugCommands, camera, lm::getBlockAtPosition);

            // Render tile priority debug overlay on top of foreground tiles
            if (!priorityDebugCommands.isEmpty()) {
                for (GLCommand cmd : priorityDebugCommands) {
                    lm.graphicsManager.registerCommand(cmd);
                }
            }
        }

        profiler.endSection("render.fg");

        // Render zone features that should appear as part of foreground layer (before sprites)
        // (e.g., CNZ slot machine display that covers corrupted tiles but sprites render on top)
        if (lm.zoneFeatureProvider != null) {
            lm.zoneFeatureProvider.renderAfterForeground(camera);
        }
        dispatchSpecialRenderEffects(SpecialRenderEffectStage.AFTER_FOREGROUND, lm.frameCounter);

        // Draw Foreground (Layer 0) high-priority pass to tile priority FBO
        // This captures high-priority tile pixels for the sprite priority shader.
        // Section name reflects that the registered command sets up GL_MAX blend
        // mode and renders into the TilePriorityFBO ("FBO compose").
        profiler.beginSection("render.fbo_compose");
        renderHighPriorityTilesToFBO(camera);
        profiler.endSection("render.fbo_compose");

        // The HTZ earthquake BG high-priority cave-ceiling overlay used to render
        // here. It now runs as a SpecialRenderEffect at AFTER_FOREGROUND stage
        // (registered by Sonic2ZoneFeatureProvider) and dispatches above with the
        // CNZ slot overlay and other AFTER_FOREGROUND effects.

        // Draw Foreground (Layer 0) high-priority pass to screen
        enqueueForegroundTilemapPass(camera, 1);

        if (options.hasGameplayPass()) {
            if (options.includePlayerSprites()
                    && options.includeObjectSprites()
                    && options.includeRings()) {
                renderSpriteObjectPass(spriteManager, options.includeWaterSurface());
            } else {
                renderSpriteObjectPassFiltered(spriteManager, options);
            }
        }
        if (options.includeObjectArtViewer()) {
            overlayManager.getObjectArtViewer().draw(lm.objectRenderManager, camera);
        }

        // The HCZ2 wall-chase BG high-priority overlay used to render here. It now
        // runs as a SpecialRenderEffect at AFTER_SPRITES stage (registered by
        // Sonic3kZoneFeatureProvider) and is dispatched inside renderSpriteObjectPass
        // alongside other AFTER_SPRITES effects.

        if (!options.hasGameplayPass()) {
            // No sprite/object pass this frame; restore the default shader state for
            // any later screen-space rendering after the level tiles.
            lm.graphicsManager.registerCommand(disableWaterShaderCommand);
        }

        profiler.beginSection("render.hud");
        if (options.includeHud() && lm.hudRenderManager != null && !lm.isHudSuppressed()
                && (TraceGhostHook.active() == null || currentTraceVisibility.showGameHud())) {
            AbstractPlayableSprite focusedPlayer = camera.getFocusedSprite();
            lm.hudRenderManager.draw(lm.levelGamestate, focusedPlayer);
        }
        profiler.endSection("render.hud");

        boolean debugViewEnabled = lm.configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
        boolean overlayEnabled = options.includeDebugOverlays()
                && debugViewEnabled
                && overlayManager.isEnabled(DebugOverlayToggle.OVERLAY);
        if (options.includeDebugOverlays() && lm.debugRenderer != null
                && (TraceGhostHook.active() == null || currentTraceVisibility.showDebugHud())) {
            lm.debugRenderer.renderDebugOverlays(overlayEnabled, lm.objectManager, lm.ringManager,
                    spriteManager, lm.gameModule, lm.configService, lm.frameCounter);
        }
        lm.graphicsManager.enqueueDefaultShaderState();
    }

    private void updateWaterShaderState(Camera camera) {
        int zoneId = lm.getFeatureZoneId();
        int actId = lm.getFeatureActId();
        if (lm.waterSystem.hasWater(zoneId, actId)) {
            // Set uniforms via custom command - this also enables the water shader
            // Use visual water level (with oscillation) for rendering effects
            int waterLevel = lm.waterSystem.getVisualWaterLevelY(zoneId, actId);

            // Determine shimmer style from current game module's typed camera rules.
            // 0 = S2/S3K smooth sine wave, 1 = S1 integer-snapped shimmer
            int shimmerStyle = 0;
            boolean waterShimmerEnabled = false;
            GameModule currentModule = lm.activeGameModule();
            if (currentModule != null
                    && currentModule.getRules() != null
                    && currentModule.getRules().camera() != null) {
                waterShimmerEnabled = currentModule.getRules().camera().waterShimmerEnabled();
                if (waterShimmerEnabled) {
                    shimmerStyle = 1;
                }
            }

            // S2/S3K split starts 8px above water level so the surface strip is tinted.
            // S1 uses v_waterpos1 directly as the underwater split (ROM-accurate boundary).
            float waterlineOffset = -8.0f;
            if (waterShimmerEnabled) {
                waterlineOffset = 0.0f;
            }
            // Zone feature provider can override waterline offset (e.g. zones with
            // ROM-driven water surface rendering that conflicts with the -8 split).
            if (lm.zoneFeatureProvider != null) {
                float zoneOffset = lm.zoneFeatureProvider.getWaterlineOffset(zoneId, actId);
                if (zoneOffset != -8.0f) {
                    waterlineOffset = zoneOffset;
                }
            }
            float waterlineScreenY = (float) (waterLevel - camera.getY() + waterlineOffset);
            currentShimmerStyle = shimmerStyle;

            // Set mutable state for pre-allocated water shader setup command
            pendingWaterlineScreenY = waterlineScreenY;
            pendingWaterShimmerStyle = shimmerStyle;
            pendingSuppressUnderwaterPalette = lm.shouldSuppressUnderwaterPalette(zoneId, actId);
            lm.graphicsManager.registerCommand(frameCommandPool.obtain(this, FrameCommand.Kind.WATER));
        } else {
            // No water in this zone - disable underwater palette for sprite priority shader
            currentShimmerStyle = 0;
            lm.graphicsManager.setWaterEnabled(false);
        }
        // Note: We don't disable water shader here - that's done later before HUD
        // rendering
    }

    void renderBackgroundShader(List<GLCommand> commands) {
        if (lm.level == null || lm.level.getMap() == null)
            return;

        BackgroundRenderer bgRenderer = lm.graphicsManager.getBackgroundRenderer();
        if (bgRenderer == null)
            return;

        Palette.Color backdropColor = lm.resolveLevelBackdropColor();
        bgRenderer.setBackdropColor(
                backdropColor.rFloat(),
                backdropColor.gFloat(),
                backdropColor.bFloat());

        int[] hScrollData = lm.parallaxManager.getHScrollForShader();
        short[] vScrollData = lm.parallaxManager.getVScrollPerLineBGForShader();
        short[] vScrollColumnData = lm.parallaxManager.getVScrollPerColumnBGForShader();

        int bgCameraX = lm.parallaxManager.getBgCameraX();
        boolean mgzStateEightPerLineTilemap = lm.applyBackgroundTilemapWindowSelection(bgCameraX);
        // Side-effect-only: tilemap manager has been refreshed.

        lm.ensureBackgroundTilemapData();

        int bgPeriodWidthPixels = lm.tilemapManager.getBackgroundTilemapWidthTiles() * Pattern.PATTERN_WIDTH;
        // Pass the tilemap's source anchor to the shader so it offsets worldX before wrapping.
        // Shader: fboWorldOffsetX = -ScrollMidpoint - ExtraBuffer
        // We want fboWorldOffsetX = sourceX, so ScrollMidpoint = -sourceX.
        int shaderScrollMidpoint = -lm.tilemapManager.getBackgroundTilemapSourceX();
        int shaderExtraBuffer = 0;
        float bgTilemapWorldOffsetX = 0.0f;
        boolean perLineScrollActive = false;
        float vdpWrapWidthTiles = 0.0f;
        float nametableBaseTile = 0.0f;
        float upperBandWrapHeightPx = 0.0f;
        float upperBandWrapWidthTiles = 0.0f;
        Camera camera = lm.camera;
        if (lm.zoneFeatureProvider != null && lm.zoneFeatureProvider.isIntroOceanPhaseActive(lm.currentZone, lm.currentAct)) {
            // Per-scanline HScroll in the tilemap shader, matching VDP behavior.
            // Each pixel computes worldX = pixelX - hScroll[scanline] directly,
            // then looks up the correct tile from the full-width tilemap.
            bgPeriodWidthPixels = lm.cachedScreenWidth;
            bgTilemapWorldOffsetX = 0;
            shaderScrollMidpoint = 0;
            shaderExtraBuffer = 0;
            perLineScrollActive = true;

            // VDP nametable ring buffer: overflow count tracks how many positions
            // have been overwritten with beach tiles as the camera advances.
            // Ocean phase (introScrollOffset < 0): overflow=0 (all ocean).
            // Camera tracking: overflow gradually increases, revealing beach tiles.
            vdpWrapWidthTiles = 64.0f;
            nametableBaseTile = lm.zoneFeatureProvider.getVdpNametableBase(
                    lm.currentZone, lm.currentAct, camera.getX(), lm.tilemapManager.getBackgroundTilemapWidthTiles());
        } else if (mgzStateEightPerLineTilemap) {
            // MGZ2 state 8 still uses Draw_BG on hardware, but the 64-cell plane is
            // refreshed incrementally as the camera advances. Our rebuild-from-scratch
            // renderer cannot represent that with a single wrapped 512px cache window.
            // Instead, render the full contiguous MGZ BG strip with per-line HScroll
            // applied during the tile pass so clouds and the locked floor band can
            // coexist without cache-window seams.
            bgPeriodWidthPixels = lm.cachedScreenWidth;
            bgTilemapWorldOffsetX = 0;
            shaderScrollMidpoint = 0;
            shaderExtraBuffer = 0;
            perLineScrollActive = true;
            // MGZ2 BG layout rows 0-3 only populate cols 0-7 with the "real"
            // cloud background; rows 4-6 hold the wider fake-floor strip. Wrapping
            // the upper rows inside their populated cloud span avoids exposing empty
            // high-X layout columns while preserving the floor rows below.
            upperBandWrapHeightPx = 4.0f * lm.blockPixelSize;
            upperBandWrapWidthTiles = (8.0f * lm.blockPixelSize) / Pattern.PATTERN_WIDTH;
        }
        // Cap BG period at the scroll handler's required width.
        // Zones with a single BG scroll speed cap at VDP nametable width (512px).
        // Zones with multi-speed parallax (e.g., GHZ) need a wider period to
        // avoid a visible wrap seam where slower and faster layers overlap.
        int bgPeriodCap = lm.parallaxManager.getBgPeriodWidth();
        if (!perLineScrollActive && bgPeriodWidthPixels > bgPeriodCap) {
            bgPeriodWidthPixels = bgPeriodCap;
        }
        int renderWidth = Math.max(lm.cachedScreenWidth, bgPeriodWidthPixels);
        // Add CHUNK_HEIGHT (16px) to cover VScroll range
        // This prevents bottom clipping when VScroll > 0 (max VScroll = 15, max gameY = 223, max fboY = 238 < 272)
        int renderHeight = 256 + LevelConstants.CHUNK_HEIGHT;

        // ROM parity: use the full background plane period and direct wrap sampling.
        // The intro path still uses the same VDP hscroll semantics as normal gameplay.
        // Get pattern renderer's screen height for correct Y coordinate handling
        int screenHeightPixels = lm.cachedScreenHeight;

        // Use zone-specific vertical scroll from parallax manager
        // This ensures zones like MCZ use their act-dependent BG Y calculations
        int actualBgScrollY = lm.parallaxManager.getVscrollFactorBG();

        // 1. Ensure FBO capacity (grow-only, no per-frame reallocation)
        pendingBgRenderWidth = renderWidth;
        pendingBgRenderHeight = renderHeight;
        lm.graphicsManager.registerCommand(frameCommandPool.obtain(this, FrameCommand.Kind.BG_ENSURE));

        // 2. Begin Tile Pass (Bind FBO)
        // Use water shader in screen-space mode for FBO, with adjusted waterline
        int featureZone = lm.getFeatureZoneId();
        int featureAct = lm.getFeatureActId();
        boolean hasWater = lm.waterSystem.hasWater(featureZone, featureAct);
        boolean suppressUnderwaterPalette = lm.shouldSuppressUnderwaterPalette(featureZone, featureAct);
        // Use visual water level (with oscillation) for background rendering
        int waterLevelWorldY = hasWater ? lm.waterSystem.getVisualWaterLevelY(featureZone, featureAct) : 9999;

        // Calculate chunk-aligned Y for tilemap rendering
        int chunkHeight = LevelConstants.CHUNK_HEIGHT;
        int alignedBgY = (actualBgScrollY / chunkHeight) * chunkHeight;
        if (actualBgScrollY < 0 && actualBgScrollY % chunkHeight != 0) {
            alignedBgY -= chunkHeight; // Handle negative rounding
        }

        // Calculate waterline for FBO - use SCREEN-SPACE waterline PLUS parallax offset
        // The parallax shader shifts the FBO sampling by (actualBgScrollY - alignedBgY)
        // so we must shift the waterline by the same amount to keep it steady on screen
        int vOffset = actualBgScrollY - alignedBgY;
        int tilePassWorldOffsetY =
                alignedBgY - lm.tilemapManager.getBackgroundTilemapSourceY();
        float fboWaterlineY = (float) ((waterLevelWorldY - camera.getY()) + vOffset);

        // Compute screen-space waterline for BG parallax shimmer
        float bgWaterlineScreenY = (float) (waterLevelWorldY - camera.getY());

        lm.ensureBackgroundTilemapData();
        pendingBgTilePassRenderWidth = renderWidth;
        pendingBgTilePassRenderHeight = renderHeight;
        pendingBgTilePassHasWater = hasWater && !suppressUnderwaterPalette;
        pendingBgTilePassFboWaterlineY = fboWaterlineY;
        pendingBgTilePassAlignedBgY = tilePassWorldOffsetY;
        pendingBgTilePassBgTilemapWorldOffsetX = bgTilemapWorldOffsetX;
        pendingBgTilePassPerLineScroll = perLineScrollActive;
        // Per-column BG VScroll is screen-column VSRAM state, so the parallax
        // compositing pass owns it. Applying it during the FBO tile pass as well
        // doubles AIZ fire-wave offsets.
        pendingBgTilePassHScrollData = hScrollData;
        pendingBgTilePassVdpWrapWidth = vdpWrapWidthTiles;
        pendingBgTilePassNametableBase = nametableBaseTile;
        pendingBgTilePassPerLineScrollSampleYOffsetPx = perLineScrollActive ? (float) vOffset : 0.0f;
        pendingBgTilePassUpperBandWrapHeightPx = upperBandWrapHeightPx;
        pendingBgTilePassUpperBandWrapWidthTiles = upperBandWrapWidthTiles;
        TilemapGpuRenderer tilemapRenderer = lm.graphicsManager.getTilemapGpuRenderer();
        TilemapGpuRenderer.BackgroundRenderState bgRenderState = tilemapRenderer != null
                ? tilemapRenderer.captureBackgroundRenderState()
                : new TilemapGpuRenderer.BackgroundRenderState(0, 0, 0);
        pendingBgTilePassRingBaseXTiles = bgRenderState.ringBaseXTiles();
        pendingBgTilePassRingBaseYTiles = bgRenderState.ringBaseYTiles();
        pendingBgTilePassContentGeneration = bgRenderState.contentGeneration();
        lm.graphicsManager.registerCommand(frameCommandPool.obtain(this, FrameCommand.Kind.BG_TILE));

        // 5. Set shimmer state on BG renderer for parallax compositing pass
        bgRenderer.setShimmerState(lm.frameCounter, currentShimmerStyle, bgWaterlineScreenY);

        // 6. Render the FBO with Parallax Shader
        if (lm.graphicsManager.getCombinedPaletteTextureId() != null) {
            // Calculate vertical scroll offset (sub-chunk) for shader
            // The FBO is rendered aligned to 16-pixel chunk boundaries
            // The shader needs to shift the view by the remaining offset
            int shaderVOffset = actualBgScrollY % LevelConstants.CHUNK_HEIGHT;
            if (shaderVOffset < 0)
                shaderVOffset += LevelConstants.CHUNK_HEIGHT; // Handle negative modulo

            pendingBgHScrollData = hScrollData;
            pendingBgVScrollData = vScrollData;
            pendingBgVScrollColumnData = vScrollColumnData;
            pendingBgShaderScrollMidpoint = shaderScrollMidpoint;
            pendingBgShaderExtraBuffer = shaderExtraBuffer;
            pendingBgVOffset = shaderVOffset;
            pendingBgPerLineScroll = perLineScrollActive;
            lm.graphicsManager.registerCommand(frameCommandPool.obtain(this, FrameCommand.Kind.BG_RENDER));
        }
    }

    record BackgroundTilemapSampling(int compositorWorldAnchorX, int tilePassWorldOffsetY) {
    }

    static BackgroundTilemapSampling backgroundTilemapSampling(
            LevelTilemapManager tilemapManager, int alignedBgY) {
        return new BackgroundTilemapSampling(
                tilemapManager.getBackgroundTilemapSourceX(),
                alignedBgY - tilemapManager.getBackgroundTilemapSourceY());
    }

    /**
     * Renders the shared sprite/object gameplay pass used after tile rendering.
     * This can also be called separately after a full-screen fade to keep
     * sprites/objects visible while the level tiles remain hidden.
     */
    public void renderSpriteObjectPass(SpriteManager spriteManager, boolean includeWaterSurface) {
        // Render ALL sprites in unified bucket order (7→0)
        // Sprite-to-sprite ordering is by bucket number regardless of isHighPriority
        // The sprite priority shader composites sprites with tile priority awareness
        PerformanceProfiler profiler = lm.profiler;
        profiler.beginSection("render.sprites");

        // Priority membership is mutable at runtime (plane switchers, hurt/death,
        // zone event overrides, follower objects mirroring player priority).
        // Re-validate the cached buckets against live state right before drawing
        // the unified pass; they only rebuild when the inputs actually changed.
        ObjectManager objectManager = lm.objectManager;
        RingManager ringManager = lm.ringManager;
        GraphicsManager graphicsManager = lm.graphicsManager;
        ZoneFeatureProvider zoneFeatureProvider = lm.zoneFeatureProvider;
        if (objectManager != null) {
            objectManager.refreshRenderBucketsIfChanged();
        }

        graphicsManager.setUseSpritePriorityShader(true);
        graphicsManager.setCurrentSpriteHighPriority(false);
        graphicsManager.beginPatternBatch();

        boolean bonusStageSpriteSatOrdering = zoneFeatureProvider != null
                && zoneFeatureProvider.useSpriteSatMasking(lm.currentZone);
        boolean useSpriteSatMasking = bonusStageSpriteSatOrdering;
        TraceGhostHook.GhostLayerRenderer ghostLayerHook = selectGhostLayerHook(
                currentTraceVisibility, TraceGhostHook.active());
        if (spriteManager != null) spriteManager.prepareRenderBucketsForPass();
        if (useSpriteSatMasking) {
            graphicsManager.beginSpriteSatCollection();
            // SAT collection must follow sprite-table order, not painter order.
            // Draw_Sprite inserts into Sprite_table_input by ascending priority bucket,
            // and lower sprite slots end up in front later during rasterization.
            // In the Gumball stage the playable sprites must still come after same-bucket
            // machine objects so Sonic/sidekicks remain on top within bucket 2.
            for (int bucket = RenderPriority.MIN; bucket <= RenderPriority.MAX; bucket++) {
                graphicsManager.setCurrentSpriteSatBucket(bucket);
                if (objectManager != null) {
                    objectManager.drawUnifiedBucketWithPriority(bucket, graphicsManager);
                }
                if (spriteManager != null) {
                    spriteManager.drawPreparedUnifiedBucketWithPriority(bucket, graphicsManager, null);
                }
                drawStageRingsForBucket(ringManager, graphicsManager, bucket, true);
            }
            graphicsManager.endSpriteSatCollectionAndReplay();
        } else {
            for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
                if (bonusStageSpriteSatOrdering) {
                    // In the gumball bonus stage, the player and bonus-stage objects share
                    // the same priority buckets. Draw objects first so lower-slot player
                    // sprites remain on top within a shared bucket.
                    if (objectManager != null) {
                        objectManager.drawUnifiedBucketWithPriority(bucket, graphicsManager);
                    }
                    if (spriteManager != null) {
                        spriteManager.drawPreparedUnifiedBucketWithPriority(bucket, graphicsManager, null);
                    }
                    drawStageRingsForBucket(ringManager, graphicsManager, bucket, true);
                } else {
                    if (spriteManager != null) {
                        spriteManager.drawPreparedUnifiedBucketWithPriority(
                                bucket, graphicsManager, ghostLayerHook);
                    }
                    if (objectManager != null) {
                        objectManager.drawUnifiedBucketWithPriority(bucket, graphicsManager);
                    }
                    drawStageRingsForBucket(ringManager, graphicsManager, bucket, true);
                }
            }
        }
        graphicsManager.flushPatternBatch();
        graphicsManager.setUseSpritePriorityShader(false);
        profiler.endSection("render.sprites");

        if (includeWaterSurface) {
            graphicsManager.registerCommand(disableShimmerCommand);
        }
        if (zoneFeatureProvider != null) {
            zoneFeatureProvider.render(lm.camera, lm.frameCounter);
        }
        dispatchSpecialRenderEffects(SpecialRenderEffectStage.AFTER_SPRITES, lm.frameCounter);

        // Revert to default shader for any following HUD/debug/screen-space rendering.
        graphicsManager.registerCommand(disableWaterShaderCommand);
    }

    static TraceGhostHook.GhostLayerRenderer selectGhostLayerHook(
            TraceRenderVisibility visibility, TraceGhostHook.GhostLayerRenderer activeHook) {
        return visibility.showGhosts() ? activeHook : null;
    }

    private void renderSpriteObjectPassFiltered(SpriteManager spriteManager, LevelManager.LevelRenderOptions options) {
        PerformanceProfiler profiler = lm.profiler;
        ObjectManager objectManager = lm.objectManager;
        RingManager ringManager = lm.ringManager;
        GraphicsManager graphicsManager = lm.graphicsManager;
        ZoneFeatureProvider zoneFeatureProvider = lm.zoneFeatureProvider;
        profiler.beginSection("render.sprites");

        if (objectManager != null && options.includeObjectSprites()) {
            objectManager.refreshRenderBucketsIfChanged();
        }

        graphicsManager.setUseSpritePriorityShader(true);
        graphicsManager.setCurrentSpriteHighPriority(false);
        graphicsManager.beginPatternBatch();

        if (spriteManager != null && options.includePlayerSprites()) {
            spriteManager.prepareRenderBucketsForPass();
        }

        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            if (spriteManager != null && options.includePlayerSprites()) {
                spriteManager.drawPreparedUnifiedBucketWithPriority(bucket, graphicsManager, null);
            }
            if (objectManager != null && options.includeObjectSprites()) {
                objectManager.drawUnifiedBucketWithPriority(bucket, graphicsManager);
            }
            drawStageRingsForBucket(ringManager, graphicsManager, bucket, options.includeRings());
        }

        graphicsManager.flushPatternBatch();
        graphicsManager.setUseSpritePriorityShader(false);
        profiler.endSection("render.sprites");

        if (options.includeWaterSurface()) {
            graphicsManager.registerCommand(disableShimmerCommand);
        }
        if (zoneFeatureProvider != null) {
            zoneFeatureProvider.render(lm.camera, lm.frameCounter);
        }
        graphicsManager.registerCommand(disableWaterShaderCommand);
    }

    private void drawStageRingsForBucket(RingManager ringManager,
                                         GraphicsManager graphicsManager,
                                         int bucket,
                                         boolean includeRings) {
        if (ringManager == null || !includeRings || bucket != RenderPriority.PLAYER_DEFAULT) {
            return;
        }

        Camera camera = lm.camera;
        boolean verticalWrapEnabled = camera != null && camera.isVerticalWrapEnabled();
        if (verticalWrapEnabled) {
            graphicsManager.enableVerticalWrapAdjust(camera.getVerticalWrapRange(), camera.getY());
        }
        try {
            graphicsManager.flushPatternBatch();
            graphicsManager.setCurrentSpriteHighPriority(false);
            graphicsManager.beginPatternBatch();
            ringManager.draw(lm.frameCounter);

            // ROM: Lost rings (Ring_LostRing) use art_tile with priority bit set,
            // rendering them in front of both playfield layers (including waterfalls).
            graphicsManager.flushPatternBatch();
            graphicsManager.setCurrentSpriteHighPriority(true);
            graphicsManager.beginPatternBatch();
            ringManager.drawLostRings(lm.frameCounter);

            graphicsManager.flushPatternBatch();
            graphicsManager.setCurrentSpriteHighPriority(false);
            graphicsManager.beginPatternBatch();
        } finally {
            if (verticalWrapEnabled) {
                graphicsManager.disableVerticalWrapAdjust();
            }
        }
    }

    /**
     * Renders the DEZ background during the ending cutscene (1-arg form).
     */
    public void renderEndingBackground(int bgVscroll) {
        renderEndingBackground(bgVscroll, null);
    }

    /**
     * Renders the DEZ star field background for the ending cutscene, with an
     * optional backdrop color override.
     */
    public void renderEndingBackground(int bgVscroll, float[] backdropOverride) {
        if (lm.level == null || lm.level.getMap() == null) {
            return;
        }
        if (!lm.useShaderBackground || lm.graphicsManager.getBackgroundRenderer() == null) {
            return;
        }

        // Refresh the cached viewport for the deferred BG tile pass command. The
        // ending cutscene re-enters the BG render path outside drawWithRenderOptions.
        cacheViewportForFrame();

        // Update parallax with camera=(0,0) and the ending's BG vscroll
        // This drives SwScrlDez TempArray accumulation for star parallax
        lm.frameCounter++;
        lm.parallaxManager.updateForEnding(lm.currentZone, lm.currentAct, lm.frameCounter, bgVscroll);

        // Force background tilemap FBO re-render every frame during the ending.
        // The cutscene fades palette lines 2-3 from white → sky colors; the tilemap
        // FBO bakes palette colors at render time, so it must be rebuilt each frame
        // to reflect the evolving palette state. Without this, the DEZ star field
        // appears at full color instantly instead of fading in with the palette.
        if (lm.tilemapManager != null) {
            lm.tilemapManager.setBackgroundTilemapDirty(true);
        }

        // Render using the existing shader pipeline
        List<GLCommand> endingCollisionCommands = lm.debugRenderer != null
                ? lm.debugRenderer.getCollisionCommands() : new ArrayList<>();
        renderBackgroundShader(endingCollisionCommands);

        // Override backdrop color for ending cutscene palette fade.
        // The deferred commands read bgRenderer fields at execution time, so
        // setting the backdrop AFTER renderBackgroundShader but BEFORE flush()
        // ensures the override takes effect.
        if (backdropOverride != null && backdropOverride.length >= 3) {
            BackgroundRenderer bgRenderer = lm.graphicsManager.getBackgroundRenderer();
            if (bgRenderer != null) {
                bgRenderer.setBackdropColor(
                        backdropOverride[0], backdropOverride[1], backdropOverride[2]);
            }
        }
    }

    private void enqueueForegroundTilemapPass(Camera camera, int priorityPass) {
        TilemapGpuRenderer renderer = lm.graphicsManager.getTilemapGpuRenderer();
        if (renderer == null) {
            return;
        }

        int featureZone = lm.getFeatureZoneId();
        int featureAct = lm.getFeatureActId();
        boolean hasWater = lm.waterSystem.hasWater(featureZone, featureAct);
        boolean suppressUnderwaterPalette = lm.shouldSuppressUnderwaterPalette(featureZone, featureAct);
        int waterLevel = hasWater ? lm.waterSystem.getVisualWaterLevelY(featureZone, featureAct) : 0;

        int screenW = lm.cachedScreenWidth;
        int screenH = lm.cachedScreenHeight;
        // FG tile world offsets.
        // Y: vscrollFactorFG already includes scroll-handler shake (HTZ earthquake,
        //    HCZ2 wall push, MCZ boss, etc.).  Do NOT add getShakeOffsetY() again
        //    — that caused double-amplitude shake on FG tiles.
        float worldOffsetX = camera.getXWithShake();
        float worldOffsetY = lm.parallaxManager.getVscrollFactorFG();
        // Waterline tracks the same Y offset as tile rendering
        float waterlineScreenY = (float) (waterLevel - worldOffsetY);

        Integer atlasId = lm.graphicsManager.getPatternAtlasTextureId();
        Integer paletteId = lm.graphicsManager.getCombinedPaletteTextureId();
        Integer underwaterPaletteId = lm.graphicsManager.getUnderwaterPaletteTextureId();
        boolean useUnderwaterPalette = hasWater && !suppressUnderwaterPalette && underwaterPaletteId != null;

        if (atlasId == null || paletteId == null) {
            return;
        }

        // Use separate pre-allocated commands for low (0) and high (1) priority passes
        // since both are registered in the same frame
        if (priorityPass == 0) {
            pendingFgWorldOffsetX_low = worldOffsetX;
            pendingFgWorldOffsetY_low = worldOffsetY;
            pendingFgScreenW_low = screenW;
            pendingFgScreenH_low = screenH;
            pendingFgPriorityPass_low = priorityPass;
            pendingFgUseUnderwater_low = useUnderwaterPalette;
            pendingFgWaterlineScreenY_low = waterlineScreenY;
            pendingFgAtlasId_low = atlasId;
            pendingFgPaletteId_low = paletteId;
            pendingFgUnderwaterPaletteId_low = underwaterPaletteId;
            lm.graphicsManager.registerCommand(frameCommandPool.obtain(this, FrameCommand.Kind.FG_LOW));
        } else {
            pendingFgWorldOffsetX_high = worldOffsetX;
            pendingFgWorldOffsetY_high = worldOffsetY;
            pendingFgScreenW_high = screenW;
            pendingFgScreenH_high = screenH;
            pendingFgPriorityPass_high = priorityPass;
            pendingFgUseUnderwater_high = useUnderwaterPalette;
            pendingFgWaterlineScreenY_high = waterlineScreenY;
            pendingFgAtlasId_high = atlasId;
            pendingFgPaletteId_high = paletteId;
            pendingFgUnderwaterPaletteId_high = underwaterPaletteId;
            lm.graphicsManager.registerCommand(frameCommandPool.obtain(this, FrameCommand.Kind.FG_HIGH));
        }
    }

    /**
     * Render high-priority foreground tiles to the tile priority FBO.
     * This FBO is sampled by the sprite priority shader to determine
     * if low-priority sprites should be hidden behind high-priority tiles.
     */
    private void renderHighPriorityTilesToFBO(Camera camera) {
        TilePriorityFBO fbo = lm.graphicsManager.getTilePriorityFBO(lm.cachedScreenWidth, lm.cachedScreenHeight);
        if (fbo == null || !fbo.isInitialized()) {
            return;
        }

        TilemapGpuRenderer renderer = lm.graphicsManager.getTilemapGpuRenderer();
        if (renderer == null) {
            return;
        }

        Integer atlasId = lm.graphicsManager.getPatternAtlasTextureId();
        Integer paletteId = lm.graphicsManager.getCombinedPaletteTextureId();
        if (atlasId == null || paletteId == null) {
            return;
        }

        int screenW = lm.cachedScreenWidth;
        int screenH = lm.cachedScreenHeight;
        float fgWorldOffsetX = camera.getXWithShake();
        // The mask must use the same Plane A origin as the visible foreground pass;
        // S3K scroll handlers can decouple foreground VScroll from camera Y.
        float fgWorldOffsetY = lm.parallaxManager.getVscrollFactorFG();

        pendingFboScreenW = screenW;
        pendingFboScreenH = screenH;
        pendingFboFgWorldOffsetX = fgWorldOffsetX;
        pendingFboFgWorldOffsetY = fgWorldOffsetY;
        pendingFboAtlasId = atlasId;
        pendingFboPaletteId = paletteId;
        lm.graphicsManager.registerCommand(frameCommandPool.obtain(this, FrameCommand.Kind.HIGH_FBO));
    }
}
