package com.openggf.graphics.pipeline;

/**
 * Enumeration of render phases in order of execution.
 * Used for testing render order compliance.
 */
public enum RenderPhase {
    SCENE,      // Level, sprites, objects
    OVERLAY,    // HUD, debug overlay
    FADE_PASS,  // Screen fade effects; final pass in the normal UI pipeline
    POST_FADE_DIAGNOSTIC // Explicit Engine-owned diagnostics and documented exceptions
}
