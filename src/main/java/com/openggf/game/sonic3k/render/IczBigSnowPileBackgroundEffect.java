package com.openggf.game.sonic3k.render;

import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.sonic3k.objects.IczBigSnowPileInstance;
import com.openggf.level.objects.ObjectInstance;

/**
 * Renders ICZ1's big snow pile as a background-plane overlay.
 *
 * <p>The ROM shows the snow by shifting the BG camera copy to a hidden ICZ
 * background source before foreground tiles are drawn. Rendering it here keeps
 * foreground level tiles in front of the pile instead of treating it as a
 * sprite/object.
 */
public final class IczBigSnowPileBackgroundEffect implements SpecialRenderEffect {

    @Override
    public SpecialRenderEffectStage stage() {
        return SpecialRenderEffectStage.AFTER_FOREGROUND;
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
