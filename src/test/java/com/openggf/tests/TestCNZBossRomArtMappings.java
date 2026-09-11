package com.openggf.tests;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2ObjectArt;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-backed regression coverage for Obj51's art tile fudge base.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestCNZBossRomArtMappings {

    @Test
    public void cnzBossSheetPreservesObj51NullFrameAndFudgeBaseMappings() throws Exception {
        Rom rom = TestEnvironment.currentRom();
        Sonic2ObjectArt art = new Sonic2ObjectArt(rom, RomByteReader.fromRom(rom));

        ObjectSpriteSheet sheet = art.loadCNZBossSheet();

        assertNotNull(sheet, "CNZ boss sheet should load from ROM");
        assertEquals(21, sheet.getFrameCount(), "Obj51 mapping table has frame 0 plus 20 real frames");
        assertTrue(sheet.getFrame(0).pieces().isEmpty(), "Obj51 frame 0 is the ROM's no-overlay/null frame");
        assertFalse(sheet.getFrame(1).pieces().isEmpty(), "Obj51 frame 1 is the main Eggman ship body");
        assertFrameUsesTileRange(sheet.getFrame(1), 0, 0x0C, "main body upper CNZ boss art");
        assertFrameUsesTileRange(sheet.getFrame(1), 0x0C, 0x10, "main body lower-left CNZ boss art");
        assertFrameUsesTileRange(sheet.getFrame(1), 0x1C, 0x10, "main body lower-center CNZ boss art");
        assertFrameUsesTileRange(sheet.getFrame(1), 0x2C, 0x06, "main body lower-right CNZ boss art");
        assertFrameUsesTileRange(sheet.getFrame(1), 0x11D, 4, "main body Eggpod art");
        assertFrameUsesTileRange(sheet.getFrame(0x12), 0x7A, 9, "electric ball");
        assertFrameUsesTileRange(sheet.getFrame(0x13), 0x83, 1, "split electric orb 1");
        assertFrameUsesTileRange(sheet.getFrame(0x14), 0x84, 1, "split electric orb 2");
    }

    private static void assertFrameUsesTileRange(SpriteMappingFrame frame, int start, int tileCount, String label) {
        boolean found = frame.pieces().stream().anyMatch(piece ->
                piece.tileIndex() == start && piece.widthTiles() * piece.heightTiles() == tileCount);
        assertTrue(found, label + " should use tile range [" + start + "," + (start + tileCount) + ")");
    }
}
