package com.openggf.level;

import com.openggf.game.OscillationManager;

/**
 * Advances level-owned runtime state that must tick during logic frames, even
 * when no renderer is active.
 */
final class LevelFrameRuntimeUpdater {
    private final LevelManager levelManager;

    LevelFrameRuntimeUpdater(LevelManager levelManager) {
        this.levelManager = levelManager;
    }

    void advanceGlobalOscillation() {
        if (OscillationManager.consumeSuppressedUpdate(levelManager.frameCounter)) {
            return;
        }
        int featureZone = levelManager.getFeatureZoneId();
        int featureAct = levelManager.getFeatureActId();
        if (levelManager.zoneFeatureProvider != null
                && !levelManager.zoneFeatureProvider.shouldAdvanceGlobalOscillation(
                        featureZone, featureAct)) {
            return;
        }
        OscillationManager.update(levelManager.frameCounter);
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

    int computeBackgroundScrollY() {
        int bgScrollY = levelManager.camera != null ? (int) (levelManager.camera.getY() * 0.1f) : 0;
        if (levelManager.camera == null
                || levelManager.game == null
                || levelManager.currentZone < 0
                || levelManager.currentZone >= levelManager.levels.size()
                || levelManager.currentAct < 0
                || levelManager.currentAct >= levelManager.levels.get(levelManager.currentZone).size()) {
            return bgScrollY;
        }
        int levelIdx = levelManager.levels
                .get(levelManager.currentZone)
                .get(levelManager.currentAct)
                .levelIndex();
        int[] scroll = levelManager.gameModule.getBackgroundScrollOverride(
                levelIdx, levelManager.camera.getX(), levelManager.camera.getY());
        if (scroll == null) {
            scroll = levelManager.game.getBackgroundScroll(
                    levelIdx, levelManager.camera.getX(), levelManager.camera.getY());
        }
        return scroll[1];
    }

    void recomputeParallaxAfterRewindRestore() {
        recomputeParallaxOnlyForCurrentFrame();
    }

    void recomputeParallaxOnlyForCurrentFrame() {
        refreshParallaxState();
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
