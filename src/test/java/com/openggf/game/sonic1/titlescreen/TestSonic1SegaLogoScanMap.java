package com.openggf.game.sonic1.titlescreen;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
class TestSonic1SegaLogoScanMap {
    @Test
    void segaLogoKeepsRomBgScanTilemapAndPlacement() {
        Sonic1TitleScreenDataLoader loader = new Sonic1TitleScreenDataLoader();

        assertTrue(loader.loadData(), "S1 title data should load from the Sonic 1 ROM");

        int[] scanMap = loader.getSegaLogoScanMap();
        assertNotNull(scanMap, "S1 SEGA light scan BG tilemap should be retained");
        assertEquals(24 * 8, scanMap.length, "S1 SEGA light scan map is the first 24x8 decoded tiles");
        assertEquals(24, loader.getSegaLogoScanWidth());
        assertEquals(8, loader.getSegaLogoScanHeight());
        assertEquals(64, Sonic1TitleScreenManager.SEGA_LOGO_SCAN_X,
                "ROM copies BG scan tilemap to vram_bg+$510: column 8");
        assertEquals(80, Sonic1TitleScreenManager.SEGA_LOGO_SCAN_Y,
                "ROM copies BG scan tilemap to vram_bg+$510: row 10");
        assertTrue(nonZeroWords(scanMap) > 0, "S1 SEGA light scan map must contain visible tiles");
    }

    private static int nonZeroWords(int[] words) {
        int count = 0;
        for (int word : words) {
            if (word != 0) {
                count++;
            }
        }
        return count;
    }
}
