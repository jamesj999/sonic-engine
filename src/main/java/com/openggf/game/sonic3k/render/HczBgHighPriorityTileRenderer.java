package com.openggf.game.sonic3k.render;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glGetIntegerv;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.level.LevelManager;
import com.openggf.level.LevelTilemapManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.Pattern;
import com.openggf.level.WaterSystem;
import com.openggf.level.render.BackgroundRenderer;
import com.openggf.level.scroll.M68KMath;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Shared HCZ BG-high replay used when hardware BG priority must appear above
 * lower-priority foreground pixels. HCZ deformation varies by scanline, so the
 * replay must consume the same full HScroll table as the main background pass;
 * flattening it to one scanline reintroduces the opening wall during normal
 * HCZ2 parallax.
 *
 * <p>The replay must also sample exactly like the main tile-pass FBO plus the
 * parallax compositing pass: the compositor subtracts the BG window base
 * ({@code ScrollMidpoint = -bgTilemapBaseX}) and wraps at the FBO render width
 * (the 512px plane period). The replay reproduces that by biasing each
 * scanline's BG scroll word by the window base and forwarding the period as the
 * shader's VDP wrap width — otherwise a BG tilemap wider or offset relative to
 * the plane period (HCZ2's 2560px layout, or the chase window following the
 * wall BG camera) samples the wrong 512px strip in per-band blocks.
 */
final class HczBgHighPriorityTileRenderer {
    private static final ArrayDeque<OverlayCommand> COMMAND_POOL = new ArrayDeque<>();
    private HczBgHighPriorityTileRenderer() {
    }

    static void render(SpecialRenderEffectContext context) {
        GraphicsManager graphicsManager = context.graphicsManager();
        if (graphicsManager.isHeadlessMode() || !graphicsManager.isGlInitialized()) {
            return;
        }
        TilemapGpuRenderer renderer = graphicsManager.getTilemapGpuRenderer();
        BackgroundRenderer backgroundRenderer = graphicsManager.getBackgroundRenderer();
        if (renderer == null || backgroundRenderer == null) {
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

        float bgWorldOffsetY = parallaxManager.getVscrollFactorBG();

        SonicConfigurationService configService = GameServices.configuration();
        int screenW = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        int screenH = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

        LevelManager levelManager = context.levelManager();
        Camera camera = context.camera();
        WaterSystem waterSystem = GameServices.water();
        int featureZone = levelManager.getFeatureZoneId();
        int featureAct = levelManager.getFeatureActId();
        boolean hasWater = waterSystem.hasWater(featureZone, featureAct);
        ZoneFeatureProvider zoneFeatureProvider = levelManager.getZoneFeatureProvider();
        boolean suppressUnderwaterPalette = zoneFeatureProvider != null
                && zoneFeatureProvider.shouldSuppressUnderwaterPalette(featureZone, featureAct);
        Integer underwaterPaletteId = graphicsManager.getUnderwaterPaletteTextureId();
        boolean useUnderwaterPalette = hasWater && !suppressUnderwaterPalette && underwaterPaletteId != null;
        int waterLevel = hasWater ? waterSystem.getVisualWaterLevelY(featureZone, featureAct) : 0;
        float waterlineScreenY = (float) (waterLevel - camera.getYWithShake());
        int uwPalId = useUnderwaterPalette ? underwaterPaletteId : 0;

        LevelTilemapManager tilemapManager = levelManager.getTilemapManager();
        int bgScrollBias = 0;
        int planePeriodWrapTiles = 0;
        if (tilemapManager != null) {
            bgScrollBias = tilemapManager.getBgTilemapBaseX();
            planePeriodWrapTiles = computePlanePeriodWrapTiles(screenW,
                    tilemapManager.getBackgroundTilemapWidthTiles(),
                    parallaxManager.getBgPeriodWidth());
        }

        OverlayCommand command = COMMAND_POOL.pollFirst();
        if (command == null) command = new OverlayCommand();
        graphicsManager.registerCommand(command.configure(renderer, backgroundRenderer, screenW, screenH,
                hScrollData, bgScrollBias, planePeriodWrapTiles, bgWorldOffsetY,
                graphicsManager.getPatternAtlasWidth(),
                graphicsManager.getPatternAtlasHeight(), atlasId, paletteId, uwPalId,
                useUnderwaterPalette, waterlineScreenY));
    }

    /**
     * Mirrors the main tile pass's FBO render width: the BG plane period capped
     * by the tilemap's own width, but never narrower than the screen.
     */
    static int computePlanePeriodWrapTiles(int screenWidthPx, int tilemapWidthTiles, int bgPeriodWidthPx) {
        int tilemapWidthPx = tilemapWidthTiles * Pattern.PATTERN_WIDTH;
        int periodPx = Math.min(tilemapWidthPx, bgPeriodWidthPx);
        return Math.max(screenWidthPx, periodPx) / Pattern.PATTERN_WIDTH;
    }

    static OverlayCommand acquireCaptured(TilemapGpuRenderer renderer, int[] viewport, int marker) {
        OverlayCommand command = COMMAND_POOL.pollFirst();
        if (command == null) command = new OverlayCommand();
        int[] hScroll = new int[224];
        hScroll[0] = marker;
        return command.configureCaptured(renderer, new BackgroundRenderer(), 320, 224,
                hScroll, 0, 0, 0, 1, 1, 2, 3, 0, false, 0, viewport);
    }

    static final class OverlayCommand implements GLCommandable {
        private final int[] viewport = new int[4];
        private final int[] hScroll = new int[224];
        private TilemapGpuRenderer renderer;
        private BackgroundRenderer backgroundRenderer;
        private int screenW, screenH, atlasWidth, atlasHeight, atlasId, paletteId, underwaterPaletteId;
        private float offsetY, waterlineY, planePeriodWrapTiles;
        private boolean underwater, leased;

        OverlayCommand configure(TilemapGpuRenderer renderer, BackgroundRenderer backgroundRenderer,
                int screenW, int screenH, int[] hScroll, int bgScrollBias, int planePeriodWrapTiles,
                float offsetY, int atlasWidth, int atlasHeight,
                int atlasId, int paletteId, int underwaterPaletteId,
                boolean underwater, float waterlineY) {
            try {
                glGetIntegerv(GL_VIEWPORT, viewport);
            } catch (RuntimeException | Error failure) {
                COMMAND_POOL.addFirst(this);
                throw failure;
            }
            return configureCaptured(renderer, backgroundRenderer, screenW, screenH, hScroll,
                    bgScrollBias, planePeriodWrapTiles, offsetY,
                    atlasWidth, atlasHeight, atlasId, paletteId, underwaterPaletteId,
                    underwater, waterlineY, viewport);
        }

        OverlayCommand configureCaptured(TilemapGpuRenderer renderer, BackgroundRenderer backgroundRenderer,
                int screenW, int screenH, int[] hScroll, int bgScrollBias, int planePeriodWrapTiles,
                float offsetY, int atlasWidth, int atlasHeight,
                int atlasId, int paletteId, int underwaterPaletteId,
                boolean underwater, float waterlineY, int[] capturedViewport) {
            this.renderer = renderer; this.backgroundRenderer = backgroundRenderer;
            this.screenW = screenW; this.screenH = screenH;
            this.offsetY = offsetY; this.atlasId = atlasId;
            this.atlasWidth = atlasWidth; this.atlasHeight = atlasHeight;
            this.paletteId = paletteId; this.underwaterPaletteId = underwaterPaletteId;
            this.planePeriodWrapTiles = planePeriodWrapTiles;
            System.arraycopy(capturedViewport, 0, viewport, 0, 4);
            int copyLength = Math.min(this.hScroll.length, hScroll != null ? hScroll.length : 0);
            for (int i = 0; i < copyLength; i++) {
                // Bias the BG (low) scroll word into window-local coordinates,
                // mirroring the compositing pass's ScrollMidpoint = -bgTilemapBaseX.
                int packed = hScroll[i];
                this.hScroll[i] = M68KMath.packScrollWords(M68KMath.unpackFG(packed),
                        (short) (M68KMath.unpackBG(packed) + bgScrollBias));
            }
            Arrays.fill(this.hScroll, copyLength, this.hScroll.length, 0);
            this.underwater = underwater; this.waterlineY = waterlineY; leased = true;
            return this;
        }

        @Override public void execute(int cx, int cy, int cw, int ch) {
            try {
                if (renderer == null || backgroundRenderer == null) return;
                backgroundRenderer.uploadHScroll(hScroll);
                renderer.enablePerLineScroll(backgroundRenderer.getHScrollTextureId(),
                        hScroll.length, planePeriodWrapTiles, 0.0f, 0.0f);
                renderer.render(TilemapGpuRenderer.Layer.BACKGROUND, screenW, screenH,
                        viewport[0], viewport[1], viewport[2], viewport[3], 0.0f, offsetY,
                        atlasWidth, atlasHeight, atlasId,
                        paletteId, underwaterPaletteId, 1, false, false, underwater, waterlineY);
            } finally { release(); }
        }

        @Override public void discard() { release(); }
        private void release() {
            if (!leased) return;
            leased = false; renderer = null; backgroundRenderer = null; COMMAND_POOL.addFirst(this);
        }
    }
}
