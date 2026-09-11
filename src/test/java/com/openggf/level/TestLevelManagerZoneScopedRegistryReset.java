package com.openggf.level;

import com.openggf.game.GameServices;
import com.openggf.game.render.AdvancedRenderMode;
import com.openggf.game.render.AdvancedRenderModeContext;
import com.openggf.game.render.AdvancedRenderFrameState;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLevelManagerZoneScopedRegistryReset {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @Test
    void levelLoadBoundaryClearsPaletteRotationDisableAlongsideRenderRegistries() {
        LevelManager levelManager = GameServices.level();
        GameServices.paletteOwnershipRegistry().setPaletteRotationDisabled(true);
        GameServices.specialRenderEffectRegistry().register(noOpEffect());
        GameServices.advancedRenderModeController().register(noOpMode());

        levelManager.resetZoneScopedRegistriesForLevelLoad();

        assertFalse(GameServices.paletteOwnershipRegistry().isPaletteRotationDisabled(),
                "level-load boundaries must release sticky palette rotation disables from abandoned writers");
        assertTrue(GameServices.specialRenderEffectRegistry().isEmpty(),
                "special render effects are zone-scoped and must be cleared at level load");
        assertTrue(GameServices.advancedRenderModeController().isEmpty(),
                "advanced render modes are zone-scoped and must be cleared at level load");
    }

    private static SpecialRenderEffect noOpEffect() {
        return new SpecialRenderEffect() {
            @Override
            public SpecialRenderEffectStage stage() {
                return SpecialRenderEffectStage.AFTER_BACKGROUND;
            }

            @Override
            public void render(SpecialRenderEffectContext context) {
                // no-op
            }
        };
    }

    private static AdvancedRenderMode noOpMode() {
        return new AdvancedRenderMode() {
            @Override
            public String id() {
                return "test-mode";
            }

            @Override
            public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
                builder.enablePerLineForegroundScroll();
            }
        };
    }
}
