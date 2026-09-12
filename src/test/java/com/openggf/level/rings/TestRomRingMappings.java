package com.openggf.level.rings;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.Sonic1RingArt;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic3k.Sonic3kRingArt;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestRomRingMappings {
    @Test
    @RequiresRom(SonicGame.SONIC_1)
    void rev01MappingsUseThePlacedAndLostRingObjectsRomPointer() throws Exception {
        var rom = TestEnvironment.currentRom();
        var reader = RomByteReader.fromRom(rom);
        // Rings.asm Ring_Main / RLoss_Main: move.l #Map_Ring,obMap(a1).
        assertEquals(0x237C, reader.readU16BE(0xA250));
        assertEquals(Sonic1Constants.MAP_RING_ADDR, reader.readU32BE(0xA252));
        assertEquals(Sonic1Constants.MAP_RING_ADDR, reader.readU32BE(0xA390));
        var art = new Sonic1RingArt(rom);
        var sheet = art.load();
        assertSame(sheet, art.load());
        verifyFrames(sheet);
        assertEquals(6, sheet.getSparkleFrameDelay());
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void lockedOnMappingsUseTheOwningSkHalfAndRetainThePatternCap() throws Exception {
        var rom = TestEnvironment.currentRom();
        var reader = RomByteReader.fromRom(rom);
        // sonic3k.asm Obj_Ring / ring-spill setup: move.l #Map_Ring,mappings(a0/a1).
        assertEquals(0x217C, reader.readU16BE(0x1A536));
        assertEquals(Sonic3kConstants.MAP_RING_ADDR, reader.readU32BE(0x1A538));
        assertEquals(Sonic3kConstants.MAP_RING_ADDR, reader.readU32BE(0x1A6D6));
        var art = new Sonic3kRingArt(rom);
        var sheet = art.load();
        assertSame(sheet, art.load());
        verifyFrames(sheet);
        assertEquals(14, sheet.getPatterns().length);
        assertEquals(5, sheet.getSparkleFrameDelay());
    }

    private void verifyFrames(RingSpriteSheet sheet) {
        assertEquals(9, sheet.getFrameCount());
        assertEquals(4, sheet.getSpinFrameCount());
        assertEquals(4, sheet.getSparkleFrameCount());
        assertEquals(8, sheet.getFrameDelay());
        assertEquals(1, sheet.getPaletteIndex());
        int[] tiles = {0, 4, 8, 4, 10, 10, 10, 10};
        boolean[] horizontal = {false, false, false, true, false, true, true, false};
        boolean[] vertical = {false, false, false, false, false, true, false, true};
        for (int frame = 0; frame < 8; frame++) {
            var pieces = sheet.getFrame(frame).pieces();
            assertEquals(1, pieces.size());
            var piece = pieces.get(0);
            assertEquals(frame == 2 ? -4 : -8, piece.xOffset());
            assertEquals(-8, piece.yOffset());
            assertEquals(frame == 2 ? 1 : 2, piece.widthTiles());
            assertEquals(2, piece.heightTiles());
            assertEquals(tiles[frame], piece.tileIndex());
            assertEquals(horizontal[frame], piece.hFlip(), "ROM horizontal flip frame " + frame);
            assertEquals(vertical[frame], piece.vFlip(), "ROM vertical flip frame " + frame);
            assertEquals(0, piece.paletteIndex());
            assertEquals(1, (sheet.getPaletteIndex() + piece.paletteIndex()) & 3);
            assertTrue(piece.tileIndex() + piece.widthTiles() * piece.heightTiles() <= sheet.getPatterns().length);
        }
        assertTrue(sheet.getFrame(8).pieces().isEmpty(), "ROM blank frame remains available");
    }
}
