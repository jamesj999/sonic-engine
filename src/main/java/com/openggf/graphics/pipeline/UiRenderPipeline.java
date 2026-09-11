package com.openggf.graphics.pipeline;

import com.openggf.game.LevelState;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.HudRenderManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Unified UI render pipeline that ensures correct ordering:
 * 1. Scene (rendered by LevelManager/GraphicsManager before this)
 * 2. Overlay (HUD, debug info)
 * 3. Fade pass (screen transitions; final pass within the normal scene/UI pipeline)
 *
 * Engine-owned post-fade diagnostic overlays may render after this pipeline so trace,
 * debug, and alignment status stays readable during fade-to-black teardown.
 *
 * This consolidates FadeManager and HudRenderManager into a single
 * orchestration point to prevent render order bugs.
 */
public class UiRenderPipeline {
    private final GraphicsManager graphicsManager;
    private RenderOrderRecorder renderOrderRecorder = new RenderOrderRecorder();
    private HudRenderManager hudRenderManager;
    private FadeManager fadeManager;

    // Configuration flags
    private boolean hudEnabled = true;
    private boolean fadeEnabled = true;

    public UiRenderPipeline(GraphicsManager graphicsManager) {
        this.graphicsManager = graphicsManager;
    }

    public void setHudRenderManager(HudRenderManager hudRenderManager) {
        this.hudRenderManager = hudRenderManager;
    }

    public void setFadeManager(FadeManager fadeManager) {
        this.fadeManager = fadeManager;
    }

    public void setRenderOrderRecorder(RenderOrderRecorder renderOrderRecorder) {
        this.renderOrderRecorder = renderOrderRecorder != null ? renderOrderRecorder : new RenderOrderRecorder();
    }

    public void setHudEnabled(boolean enabled) {
        this.hudEnabled = enabled;
    }

    public void setFadeEnabled(boolean enabled) {
        this.fadeEnabled = enabled;
    }

    /**
     * Begin a centered-320 safe-area projection scope for UI drawing.
     * At native width (320) this is a no-op. At wider viewports it pillarboxes UI
     * to the central 320-pixel column.
     * <p>
     * Callers MUST call {@link #endSafeArea()} BEFORE {@link #renderFadePass()} so the
     * fade pass runs at the full viewport projection, not the safe-area.
     *
     * @param viewportWidth        physical viewport width in pixels
     * @param viewportHeightPixels physical viewport height in pixels
     */
    public void beginSafeArea(int viewportWidth, int viewportHeightPixels) {
        graphicsManager.beginSafeAreaProjection(viewportWidth, viewportHeightPixels);
    }

    /**
     * End the safe-area projection scope, restoring the engine's scene projection.
     * Must be called after all safe-area UI drawing and BEFORE {@link #renderFadePass()}.
     */
    public void endSafeArea() {
        graphicsManager.endSafeAreaProjection();
    }

    /**
     * Render the overlay phase (HUD and similar elements).
     * Call after scene rendering but before fade.
     *
     * @param levelState Current level state for HUD data
     * @param player Current player sprite (may be null)
     */
    public void renderOverlay(LevelState levelState, AbstractPlayableSprite player) {
        if (hudEnabled && hudRenderManager != null && levelState != null) {
            renderOrderRecorder.record(RenderPhase.OVERLAY, "HUD");
            hudRenderManager.draw(levelState, player);
        }
    }

    /**
     * Render the fade pass. Must be called after normal scene/HUD rendering.
     * Explicit diagnostic overlays owned by Engine may render after this pass.
     */
    public void renderFadePass() {
        if (fadeEnabled && fadeManager != null && fadeManager.isActive()) {
            renderOrderRecorder.record(RenderPhase.FADE_PASS, "Fade");
            fadeManager.render();
        }
    }

    /**
     * Update fade state. Call once per frame during update phase.
     */
    public void updateFade() {
        if (fadeManager != null) {
            fadeManager.update();
        }
    }

    /**
     * Check if a fade is currently active.
     */
    public boolean isFadeActive() {
        return fadeManager != null && fadeManager.isActive();
    }

    /**
     * Get the fade manager for starting/controlling fades.
     */
    public FadeManager getFadeManager() {
        return fadeManager;
    }

    /**
     * Get the HUD render manager.
     */
    public HudRenderManager getHudRenderManager() {
        return hudRenderManager;
    }

    public RenderOrderRecorder getRenderOrderRecorder() {
        return renderOrderRecorder;
    }
}
