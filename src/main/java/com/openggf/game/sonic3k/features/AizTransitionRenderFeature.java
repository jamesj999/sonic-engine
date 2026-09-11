package com.openggf.game.sonic3k.features;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.render.AdvancedRenderFrameState;
import com.openggf.game.render.AdvancedRenderMode;
import com.openggf.game.render.AdvancedRenderModeContext;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternRenderCommand;
import com.openggf.level.LevelManager;

/**
 * Encapsulates AIZ transition visual behavior:
 * - post-burn foreground heat haze gating
 * - post-sprite fire curtain draw during the AIZ1 miniboss fake-out transition
 */
public final class AizTransitionRenderFeature implements SpecialRenderEffect, AdvancedRenderMode {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(AizTransitionRenderFeature.class.getName());

    private final SonicConfigurationService configService = GameServices.configuration();
    private final AizFireCurtainRenderer fireCurtainRenderer = new AizFireCurtainRenderer();
    private final GLCommand disableWaterShaderForCurtain = new GLCommand(
            GLCommand.CommandType.CUSTOM,
            (cx, cy, cw, ch) -> {
                GameServices.graphics().setUseWaterShader(false);
                PatternRenderCommand.resetFrameState();
            });
    private boolean loggedFirstRender;

    public void onZoneInit(int zoneIndex, int actIndex) {
        if (zoneIndex != Sonic3kZoneIds.ZONE_AIZ || actIndex == 0) {
            fireCurtainRenderer.reset();
        }
    }

    public void reset() {
        loggedFirstRender = false;
    }

    public boolean shouldEnableForegroundHeatHaze(int zoneIndex, int actIndex, int cameraX) {
        if (zoneIndex != Sonic3kZoneIds.ZONE_AIZ) {
            return false;
        }
        AizZoneRuntimeState aizState = getAizState();
        if (aizState != null) {
            return aizState.isPostFireHazeActive();
        }
        return actIndex > 0;
    }

    @Override
    public String id() {
        return "aiz-foreground-heat-haze";
    }

    @Override
    public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
        if (shouldEnableForegroundHeatHaze(context.zoneIndex(), context.actIndex(), context.cameraX())) {
            builder.enableForegroundHeatHaze();
        }
    }

    @Override
    public SpecialRenderEffectStage stage() {
        return SpecialRenderEffectStage.AFTER_SPRITES;
    }

    @Override
    public void render(SpecialRenderEffectContext context) {
        renderFlameOverlay(context.camera(), context.frameCounter());
    }

    public void renderFlameOverlay(Camera camera, int frameCounter) {
        if (camera == null) {
            return;
        }
        LevelManager levelManager = GameServices.level();
        if (levelManager.getCurrentZone() != Sonic3kZoneIds.ZONE_AIZ) {
            return;
        }

        AizZoneRuntimeState aizState = getAizState();
        if (aizState == null) {
            return;
        }

        int screenWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        int screenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        FireCurtainRenderState renderState = aizState.getFireCurtainRenderState(screenHeight);
        if (!renderState.active() || renderState.coverHeightPx() <= 0) {
            return;
        }

        if (!loggedFirstRender) {
            loggedFirstRender = true;
            LOG.info("AIZ fire curtain: FIRST RENDER coverH=" + renderState.coverHeightPx()
                    + " frame=" + frameCounter
                    + " stage=" + renderState.stage());
        }

        // The curtain is a scene overlay, not a water-surface effect. Render it
        // through the normal pattern path so it does not inherit water shader state.
        GameServices.graphics().registerCommand(disableWaterShaderForCurtain);
        fireCurtainRenderer.render(camera, renderState, screenWidth, screenHeight);
    }

    private AizZoneRuntimeState getAizState() {
        return GameServices.hasRuntime()
                ? S3kRuntimeStates.currentAiz(GameServices.zoneRuntimeRegistry()).orElse(null)
                : null;
    }
}
