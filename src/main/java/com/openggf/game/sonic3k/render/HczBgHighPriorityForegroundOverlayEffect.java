package com.openggf.game.sonic3k.render;

import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;

/**
 * Replays HCZ BG high-priority tiles after foreground-low rendering.
 *
 * <p>Hydrocity waterfalls use BG priority to appear over parts of Plane A. The
 * main background pass renders behind foreground, so without this replay the
 * foreground looks cut away instead of covered by water.
 */
public final class HczBgHighPriorityForegroundOverlayEffect implements SpecialRenderEffect {
    @Override
    public SpecialRenderEffectStage stage() {
        return SpecialRenderEffectStage.AFTER_FOREGROUND;
    }

    @Override
    public void render(SpecialRenderEffectContext context) {
        HczBgHighPriorityTileRenderer.render(context);
    }
}
