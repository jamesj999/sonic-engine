package com.openggf.game.rules;

import com.openggf.game.resources.PlcLifecyclePhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDynamicArtDmaServiceModel {
    @Test
    void sonic2ServicesOnlyProcessDmaQueueEquivalentClaims() {
        var model = DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE;

        assertFalse(model.services(null));
        assertFalse(model.services(PlcLifecyclePhase.PALETTE_FADE));
        assertTrue(model.services(PlcLifecyclePhase.LEVEL_TITLE_CARD));
        assertTrue(model.services(PlcLifecyclePhase.ORDINARY_LEVEL));
        assertTrue(model.services(PlcLifecyclePhase.SPECIAL_STAGE));
    }

    @Test
    void neutralModelServicesEveryClaim() {
        for (PlcLifecyclePhase phase : PlcLifecyclePhase.values()) {
            assertTrue(DynamicArtDmaServiceModel.EVERY_CLAIM.services(phase));
        }
    }

    /**
     * VBlank branches to VBlank_Lag before reaching any per-mode handler
     * (docs/s1disasm/sonic.asm:652-655) and VBlank_Lag only runs the sound
     * driver (sonic.asm:709-715 -> VBlank_Music, sonic.asm:678-684), so the
     * f_sonframechg-gated Sonic gfx write in VBlank_Levels (sonic.asm:829-833),
     * VBlank_SpecialStage (890-894), VBlank_TitleCards (927-931) and
     * VBlank_Paused (985-989) never runs on a lag frame.
     */
    @Test
    void sonic1ServicesEveryVBlankClaimExceptLag() {
        var model = DynamicArtDmaServiceModel.SONIC_1_VBLANK_SONIC_GFX;

        assertFalse(model.services(null));
        assertFalse(model.services(PlcLifecyclePhase.LAG));
        assertTrue(model.services(PlcLifecyclePhase.ORDINARY_LEVEL));
        assertTrue(model.services(PlcLifecyclePhase.SPECIAL_STAGE));
        assertTrue(model.services(PlcLifecyclePhase.LEVEL_TITLE_CARD));
        assertTrue(model.services(PlcLifecyclePhase.NORMAL_PAUSE));
    }

    @Test
    void sonic1UsesTheVBlankSonicGfxServiceModel() {
        assertTrue(GameRules.SONIC_1.dynamicArtDmaService()
                == DynamicArtDmaServiceModel.SONIC_1_VBLANK_SONIC_GFX);
    }

    @Test
    void playerDynamicArtAuditEligibilityIsOwnedByTheTypedServiceModel() {
        assertTrue(GameRules.SONIC_1.dynamicArtDmaService()
                .supportsPlayerDynamicArtAudit());
        assertTrue(GameRules.SONIC_2.dynamicArtDmaService()
                .supportsPlayerDynamicArtAudit());
        assertFalse(GameRules.SONIC_3K.dynamicArtDmaService()
                .supportsPlayerDynamicArtAudit());
    }
}
