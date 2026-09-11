package com.openggf.game.render;

import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;

import java.util.Objects;

/**
 * Frame-local context passed to advanced render modes during resolution.
 *
 * <p>This record exposes only the state needed to decide frame render
 * overrides, keeping contributors independent from broader engine globals.
 */
public record AdvancedRenderModeContext(Camera camera,
                                        int frameCounter,
                                        LevelManager levelManager,
                                        int zoneIndex,
                                        int actIndex,
                                        int cameraX) {
    public AdvancedRenderModeContext {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(levelManager, "levelManager");
    }
}
