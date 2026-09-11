package com.openggf.game.render;

/**
 * Runtime-owned contributor for frame-local render-mode state.
 *
 * <p>Modes do not render directly. Instead, they contribute flags and optional
 * per-frame overrides that {@link com.openggf.level.LevelManager} consumes when
 * drawing the standard scene passes.
 */
public interface AdvancedRenderMode {
    /**
     * Stable identifier for debugging and tests.
     */
    String id();

    /** Contributes render-mode state for the current frame. */
    void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder);
}
