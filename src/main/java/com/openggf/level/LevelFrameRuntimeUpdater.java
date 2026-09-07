package com.openggf.level;

/**
 * Advances level-owned runtime state that must tick during logic frames, even
 * when no renderer is active.
 */
final class LevelFrameRuntimeUpdater {
    private final LevelManager levelManager;

    LevelFrameRuntimeUpdater(LevelManager levelManager) {
        this.levelManager = levelManager;
    }

    void updateParallaxAndAnimatedContent() {
        if (levelManager.parallaxManager != null) {
            levelManager.parallaxManager.update(
                    levelManager.currentZone,
                    levelManager.currentAct,
                    levelManager.camera,
                    levelManager.frameCounter,
                    levelManager.level);
        }
        if (levelManager.animatedPatternManager != null) {
            levelManager.animatedPatternManager.update();
        }
        if (levelManager.animatedPaletteManager != null
                && levelManager.animatedPaletteManager != levelManager.animatedPatternManager) {
            levelManager.animatedPaletteManager.update();
        }
        if (levelManager.camera != null && levelManager.parallaxManager != null) {
            levelManager.camera.setShakeOffsets(
                    levelManager.parallaxManager.getShakeOffsetX(),
                    levelManager.parallaxManager.getShakeOffsetY());
        }
    }

    void refreshParallaxState() {
        if (levelManager.parallaxManager == null || levelManager.camera == null) {
            return;
        }
        levelManager.parallaxManager.update(
                levelManager.currentZone,
                levelManager.currentAct,
                levelManager.camera,
                levelManager.frameCounter,
                levelManager.level);
        levelManager.camera.setShakeOffsets(
                levelManager.parallaxManager.getShakeOffsetX(),
                levelManager.parallaxManager.getShakeOffsetY());
    }
}
