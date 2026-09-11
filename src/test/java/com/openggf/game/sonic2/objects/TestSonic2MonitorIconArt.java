package com.openggf.game.sonic2.objects;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2ObjectArt;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.util.PatternDecompressor;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the 1-up monitor icon art.
 *
 * <p>The Sonic 1-up monitor (obj26 frame 2, anim 1) maps its icon piece to tile
 * {@code $154}. In the ROM that is {@code ArtTile_ArtNem_life_counter}
 * ({@code = ArtTile_ArtNem_Powerups + $154}) — the same VRAM the HUD life counter
 * uses — so {@code PlrList_Std1} loads {@code ArtNem_Sonic_life_counter} there and
 * the standard 1-up monitor shows the main character's face (Sonic by default;
 * Tails-alone and Knuckles override it). See {@code s2.asm:89193} and
 * {@code mappings/sprite/obj26.asm} {@code Map_obj26_0034}.
 *
 * <p>The engine previously hard-loaded Tails life art at this tile, so every
 * standard monitor showed Tails even when Sonic was the main character.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestSonic2MonitorIconArt {

    @Test
    void sonicOneUpMonitorIconUsesSonicLifeArtNotTails() throws IOException {
        Rom rom = TestEnvironment.currentRom();
        Sonic2ObjectArt art = new Sonic2ObjectArt(rom, RomByteReader.fromRom(rom));

        ObjectSpriteSheet monitorSheet = art.loadForZone(0).monitorSheet();
        Pattern iconTile = monitorSheet.getPatterns()[Sonic2ObjectArt.MONITOR_LIFE_ICON_TILE];

        Pattern[] sonicLife = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_SONIC_LIFE_ADDR);
        Pattern[] tailsLife = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_TAILS_LIFE_ADDR);

        assertTrue(pixelsEqual(iconTile, sonicLife[0]),
                "The default 1-up monitor icon tile ($154) must hold Sonic's life-counter art, "
                        + "matching the HUD life-counter VRAM the ROM shares with the monitor icon");
        assertFalse(pixelsEqual(iconTile, tailsLife[0]),
                "Regression: the engine loaded Tails life art at the Sonic 1-up icon tile, so every "
                        + "standard monitor showed Tails when Sonic was the main character");
    }

    private static boolean pixelsEqual(Pattern a, Pattern b) {
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
