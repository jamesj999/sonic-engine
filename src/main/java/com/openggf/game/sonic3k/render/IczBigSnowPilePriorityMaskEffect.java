package com.openggf.game.sonic3k.render;

import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.sonic3k.objects.IczBigSnowPileInstance;
import com.openggf.level.objects.ObjectInstance;

/**
 * Adds ICZ1's big snow pile to the sprite priority mask.
 */
public final class IczBigSnowPilePriorityMaskEffect implements SpecialRenderEffect {

    @Override
    public SpecialRenderEffectStage stage() {
        return SpecialRenderEffectStage.SPRITE_PRIORITY_MASK;
    }

    @Override
    public void render(SpecialRenderEffectContext context) {
        for (ObjectInstance object : context.levelManager().getObjectManager().getActiveObjects()) {
            if (object instanceof IczBigSnowPileInstance pile) {
                pile.renderBackgroundLayer(context);
            }
        }
    }
}
