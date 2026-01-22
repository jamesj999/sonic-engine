package uk.co.jamesj999.sonic.graphics.pipeline;

/**
 * Enumeration of render phases in order of execution.
 * Used for testing render order compliance.
 */
public enum RenderPhase {
    SCENE,      // Level, sprites, objects
    OVERLAY,    // HUD, debug overlay
    FADE_PASS   // Screen fade effects (must be last)
}
