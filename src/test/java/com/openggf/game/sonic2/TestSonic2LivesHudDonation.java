package com.openggf.game.sonic2;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2LivesHudDonation {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void loadArtForZone_rebuildsAndExposesDonorLivesFrameThroughHudStaticArt() throws Exception {
        Assumptions.assumeTrue(RomTestUtils.ensureSonic3kRomAvailable() != null,
                "S3K donor ROM required for Sonic 2 donor HUD mapping test");

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        SessionManager.openGameplaySession(GameModuleRegistry.getCurrent(),
                SaveSessionContext.noSave("s2", new SelectedTeam("knuckles", List.of()), 0, 0));

        Sonic2ObjectArtProvider nativeProvider = new Sonic2ObjectArtProvider();
        try (MockedStatic<CrossGameFeatureProvider> donor = mockStatic(CrossGameFeatureProvider.class)) {
            donor.when(CrossGameFeatureProvider::isS3kDonorActive).thenReturn(false);
            nativeProvider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_EHZ);
        }

        Sonic2ObjectArtProvider donorProvider = new Sonic2ObjectArtProvider();
        try (MockedStatic<CrossGameFeatureProvider> donor = mockStatic(CrossGameFeatureProvider.class)) {
            donor.when(CrossGameFeatureProvider::isS3kDonorActive).thenReturn(true);
            donorProvider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_EHZ);
        }

        HudStaticArt nativeArt = nativeProvider.getHudStaticArt();
        HudStaticArt donorArt = donorProvider.getHudStaticArt();

        assertEquals(2, nativeArt.livesFrame().pieces().size());
        assertEquals(2, donorArt.livesFrame().pieces().size());
        assertEquals(16, nativeArt.livesFrame().pieces().get(1).xOffset());
        assertEquals(0, nativeArt.livesFrame().pieces().get(1).yOffset());
        assertEquals(4, nativeArt.livesFrame().pieces().get(1).widthTiles());
        assertEquals(2, nativeArt.livesFrame().pieces().get(1).heightTiles());
        assertEquals(1, nativeArt.livesFrame().pieces().get(1).paletteIndex());
        assertEquals(16, donorArt.livesFrame().pieces().get(1).xOffset());
        assertEquals(0, donorArt.livesFrame().pieces().get(1).yOffset());
        assertEquals(4, donorArt.livesFrame().pieces().get(1).widthTiles());
        assertEquals(2, donorArt.livesFrame().pieces().get(1).heightTiles());
        assertEquals(0, donorArt.livesFrame().pieces().get(1).paletteIndex());
        assertEquals(donorProvider.getHudTextPatterns().length + donorProvider.getHudLivesPatterns().length,
                donorArt.patterns().length);
        assertTrue(donorArt.livesFrame().pieces().stream()
                .anyMatch(piece -> piece.xOffset() == 0 && piece.yOffset() == 0 && piece.paletteIndex() == 0));
    }
}
