package com.openggf.game.render;

import com.openggf.camera.Camera;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;

import java.util.Objects;

/**
 * Frame-local render context for special effects.
 */
public record SpecialRenderEffectContext(Camera camera,
                                         int frameCounter,
                                         LevelManager levelManager,
                                         GraphicsManager graphicsManager) {
    public SpecialRenderEffectContext {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(levelManager, "levelManager");
        Objects.requireNonNull(graphicsManager, "graphicsManager");
    }
}
