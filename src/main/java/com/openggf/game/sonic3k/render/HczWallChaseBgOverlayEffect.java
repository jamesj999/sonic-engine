package com.openggf.game.sonic3k.render;

import com.openggf.game.GameServices;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.sonic3k.runtime.HczZoneRuntimeState;

/**
 * Renders the BG high-priority wall-chase overlay used by Hydrocity Zone Act 2.
 *
 * <p>On real hardware the VDP layer order is BG-low -&gt; FG-low -&gt; BG-high -&gt;
 * FG-high. The engine's main BG pass renders all priorities behind FG, so this
 * effect draws only BG high-priority tiles after the sprite pass to match
 * hardware layering — the approaching water wall must appear in front of FG
 * terrain, sprites (doors, platforms), and other gameplay objects, while still
 * being covered by the HUD.
 *
 * <p>Activation is driven entirely by the typed
 * {@link HczZoneRuntimeState#wallChaseBgOverlayActive()} runtime state — there
 * is no HCZ-specific flag on global game state.
 */
public final class HczWallChaseBgOverlayEffect implements SpecialRenderEffect {

    @Override
    public SpecialRenderEffectStage stage() {
        return SpecialRenderEffectStage.AFTER_SPRITES;
    }

    @Override
    public void render(SpecialRenderEffectContext context) {
        if (!isWallChaseBgOverlayActive()) {
            return;
        }

        HczBgHighPriorityTileRenderer.render(context);
    }

    private static boolean isWallChaseBgOverlayActive() {
        return GameServices.zoneRuntimeRegistry()
                .currentAs(HczZoneRuntimeState.class)
                .map(HczZoneRuntimeState::wallChaseBgOverlayActive)
                .orElse(false);
    }
}
