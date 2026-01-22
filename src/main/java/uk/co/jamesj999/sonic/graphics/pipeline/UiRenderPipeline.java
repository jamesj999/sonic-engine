package uk.co.jamesj999.sonic.graphics.pipeline;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.game.LevelState;
import uk.co.jamesj999.sonic.graphics.FadeManager;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.objects.HudRenderManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Unified UI render pipeline that ensures correct ordering:
 * 1. Scene (rendered by LevelManager/GraphicsManager before this)
 * 2. Overlay (HUD, debug info)
 * 3. Fade pass (screen transitions - always last)
 *
 * This consolidates FadeManager and HudRenderManager into a single
 * orchestration point to prevent render order bugs.
 */
public class UiRenderPipeline {
    private final GraphicsManager graphicsManager;
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

    public void setHudEnabled(boolean enabled) {
        this.hudEnabled = enabled;
    }

    public void setFadeEnabled(boolean enabled) {
        this.fadeEnabled = enabled;
    }

    /**
     * Render the overlay phase (HUD and similar elements).
     * Call after scene rendering but before fade.
     *
     * @param levelState Current level state for HUD data
     * @param player Current player sprite (may be null)
     */
    public void renderOverlay(LevelState levelState, AbstractPlayableSprite player) {
        RenderOrderRecorder recorder = RenderOrderRecorder.getInstance();

        if (hudEnabled && hudRenderManager != null && levelState != null) {
            recorder.record(RenderPhase.OVERLAY, "HUD");
            hudRenderManager.draw(levelState, player);
        }
    }

    /**
     * Render the fade pass. Must be called after all other rendering.
     *
     * @param gl OpenGL context
     */
    public void renderFadePass(GL2 gl) {
        RenderOrderRecorder recorder = RenderOrderRecorder.getInstance();

        if (fadeEnabled && fadeManager != null && fadeManager.isActive()) {
            recorder.record(RenderPhase.FADE_PASS, "Fade");
            fadeManager.render(gl);
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
}
