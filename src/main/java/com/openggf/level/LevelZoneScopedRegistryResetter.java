package com.openggf.level;

import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;

final class LevelZoneScopedRegistryResetter {
    private LevelZoneScopedRegistryResetter() {
    }

    static void reset() {
        PaletteOwnershipRegistry paletteOwnershipRegistry =
                GameServices.paletteOwnershipRegistryOrNull();
        SpecialRenderEffectRegistry specialRenderEffectRegistry =
                GameServices.specialRenderEffectRegistryOrNull();
        AdvancedRenderModeController advancedRenderModeController =
                GameServices.advancedRenderModeControllerOrNull();
        if (paletteOwnershipRegistry != null) {
            paletteOwnershipRegistry.clear();
        }
        if (specialRenderEffectRegistry != null) {
            specialRenderEffectRegistry.clear();
        }
        if (advancedRenderModeController != null) {
            advancedRenderModeController.clear();
        }
    }
}
