package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2ObjectArt;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ROM-backed regression tests for HTZ boss art palette selection.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestHTZBossArtPalette {
    @Test
    public void htzBossAndSmokeSheetsUsePaletteZero() throws Exception {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        Sonic2ObjectArt art = new Sonic2ObjectArt(rom, RomByteReader.fromRom(rom));

        ObjectSpriteSheet bossSheet = art.loadHTZBossSheet();
        assertNotNull(bossSheet, "HTZ boss sheet should load from ROM");
        assertEquals(0, bossSheet.getPaletteIndex(), "HTZ boss sheet palette should be 0");

        ObjectSpriteSheet smokeSheet = art.loadHTZBossSmokeSheet();
        assertNotNull(smokeSheet, "HTZ boss smoke sheet should load from ROM");
        assertEquals(0, smokeSheet.getPaletteIndex(), "HTZ boss smoke palette should be 0");
    }
}



