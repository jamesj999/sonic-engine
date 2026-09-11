package com.openggf.game.sonic3k;

import com.openggf.level.objects.HudStaticArt;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kLivesHudPaletteOverride {

    @Test
    void loadArtForZone_exposesHudStaticArtWithMixedLivesPalettes() throws Exception {
        Sonic3kObjectArtProvider provider = new Sonic3kObjectArtProvider();

        assertNull(provider.getHudStaticArt());

        provider.loadArtForZone(0x00);

        HudStaticArt art = provider.getHudStaticArt();

        assertNotNull(art);
        assertEquals(provider.getHudTextPatterns().length + provider.getHudLivesPatterns().length,
                art.patterns().length);
        assertTrue(art.livesFrame().pieces().stream().anyMatch(piece -> piece.paletteIndex() == 0));
        assertTrue(art.livesFrame().pieces().stream().anyMatch(piece -> piece.paletteIndex() == 1));
    }
}
