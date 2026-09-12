package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;

/** Shared animation and rendering operations for CPZ boss pipe-family children. */
final class CpzBossPresentation {

    private CpzBossPresentation() {
    }

    static int advanceAnimation(
            ObjectAnimationState animationState, int animationId, int mappingFrame) {
        if (animationState == null) {
            return mappingFrame;
        }
        animationState.setAnimId(animationId);
        animationState.update();
        return animationState.getMappingFrame();
    }

    static void drawFrame(
            ObjectRenderManager renderManager,
            int mappingFrame,
            int x,
            int y,
            int renderFlags) {
        if (renderManager == null || mappingFrame < 0) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, x, y, (renderFlags & 1) != 0, false);
    }
}
