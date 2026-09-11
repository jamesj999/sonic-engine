package com.openggf.game.sonic2.render;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glGetIntegerv;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.sonic2.runtime.HtzRuntimeState;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.level.ParallaxManager;
import java.util.ArrayDeque;

/**
 * Renders the BG high-priority cave-ceiling overlay used by Hill Top Zone's
 * earthquake mode.
 *
 * <p>On real hardware the VDP layer order is BG-low -&gt; FG-low -&gt; BG-high -&gt;
 * FG-high. The engine's main BG pass renders all priorities behind FG, so this
 * effect draws only BG high-priority tiles between FG-low and FG-high to match
 * hardware layering. In earthquake mode the HTZ horizontal scroll is flat, so
 * a single tilemap render call with the BG scroll offset suffices.
 *
 * <p>Activation is driven entirely by the typed
 * {@link HtzRuntimeState#earthquakeActive()} runtime state — there is no
 * HTZ-specific flag on global game state.
 */
public final class HtzEarthquakeBgOverlayEffect implements SpecialRenderEffect {
    private final ArrayDeque<OverlayCommand> commandPool = new ArrayDeque<>();

    @Override
    public SpecialRenderEffectStage stage() {
        return SpecialRenderEffectStage.AFTER_FOREGROUND;
    }

    @Override
    public void render(SpecialRenderEffectContext context) {
        if (!isEarthquakeActive()) {
            return;
        }

        GraphicsManager graphicsManager = context.graphicsManager();
        if (graphicsManager.isHeadlessMode() || !graphicsManager.isGlInitialized()) {
            return;
        }
        TilemapGpuRenderer renderer = graphicsManager.getTilemapGpuRenderer();
        if (renderer == null) {
            return;
        }

        Integer atlasId = graphicsManager.getPatternAtlasTextureId();
        Integer paletteId = graphicsManager.getCombinedPaletteTextureId();
        if (atlasId == null || paletteId == null) {
            return;
        }

        ParallaxManager parallaxManager = GameServices.parallax();
        int[] hScrollData = parallaxManager.getHScrollForShader();
        if (hScrollData == null || hScrollData.length == 0) {
            return;
        }

        short bgScroll = (short) (hScrollData[hScrollData.length - 1] & 0xFFFF);
        float bgWorldOffsetX = -bgScroll;
        float bgWorldOffsetY = parallaxManager.getVscrollFactorBG();

        SonicConfigurationService configService = GameServices.configuration();
        int screenW = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        int screenH = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

        OverlayCommand command = commandPool.pollFirst();
        if (command == null) command = new OverlayCommand();
        graphicsManager.registerCommand(command.configure(renderer, screenW, screenH,
                bgWorldOffsetX, bgWorldOffsetY, graphicsManager.getPatternAtlasWidth(),
                graphicsManager.getPatternAtlasHeight(), atlasId, paletteId));
    }

    OverlayCommand acquireCaptured(TilemapGpuRenderer renderer, int[] viewport, int marker) {
        OverlayCommand command = commandPool.pollFirst();
        if (command == null) command = new OverlayCommand();
        return command.configureCaptured(renderer, 320, 224, marker, 0, 1, 1, 2, 3, viewport);
    }

    final class OverlayCommand implements GLCommandable {
        private final int[] viewport = new int[4];
        private TilemapGpuRenderer renderer;
        private int screenW, screenH, atlasWidth, atlasHeight, atlasId, paletteId;
        private float offsetX, offsetY;
        private boolean leased;

        OverlayCommand configure(TilemapGpuRenderer renderer, int screenW, int screenH,
                float offsetX, float offsetY, int atlasWidth, int atlasHeight, int atlasId, int paletteId) {
            try {
                glGetIntegerv(GL_VIEWPORT, viewport);
            } catch (RuntimeException | Error failure) {
                commandPool.addFirst(this);
                throw failure;
            }
            return configureCaptured(renderer, screenW, screenH, offsetX, offsetY,
                    atlasWidth, atlasHeight, atlasId, paletteId, viewport);
        }

        OverlayCommand configureCaptured(TilemapGpuRenderer renderer, int screenW, int screenH,
                float offsetX, float offsetY, int atlasWidth, int atlasHeight, int atlasId, int paletteId,
                int[] capturedViewport) {
            this.renderer = renderer; this.screenW = screenW; this.screenH = screenH;
            this.offsetX = offsetX; this.offsetY = offsetY;
            this.atlasWidth = atlasWidth; this.atlasHeight = atlasHeight;
            System.arraycopy(capturedViewport, 0, viewport, 0, 4);
            this.atlasId = atlasId; this.paletteId = paletteId; leased = true;
            return this;
        }

        @Override public void execute(int cx, int cy, int cw, int ch) {
            try {
                if (renderer == null) return;
                float savedWrapHeight = renderer.getBgVdpWrapHeight();
                renderer.setBgVdpWrapHeight(0.0f);
                try {
                    renderer.render(TilemapGpuRenderer.Layer.BACKGROUND, screenW, screenH,
                            viewport[0], viewport[1], viewport[2], viewport[3], offsetX, offsetY,
                            atlasWidth, atlasHeight,
                            atlasId, paletteId, 0, 1, false, false, false, 0.0f);
                } finally {
                    renderer.setBgVdpWrapHeight(savedWrapHeight);
                }
            } finally { release(); }
        }

        @Override public void discard() { release(); }
        private void release() {
            if (!leased) return;
            leased = false; renderer = null; commandPool.addFirst(this);
        }
    }

    private static boolean isEarthquakeActive() {
        return GameServices.zoneRuntimeRegistry()
                .currentAs(HtzRuntimeState.class)
                .map(HtzRuntimeState::earthquakeActive)
                .orElse(false);
    }
}
