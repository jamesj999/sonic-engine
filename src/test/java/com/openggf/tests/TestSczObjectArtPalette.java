package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2ObjectArt;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-backed regression tests for SCZ object art palette line selection.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestSczObjectArtPalette {
    @Test
    public void sczProviderRegistersTornadoRendererForGameplayStartup() throws Exception {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        Sonic2ObjectArtProvider provider = new Sonic2ObjectArtProvider(rom, RomByteReader.fromRom(rom));

        provider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_SCZ);

        assertNotNull(provider.getRenderer(Sonic2ObjectArtKeys.TORNADO),
                "SCZ startup art must register ObjB2 Tornado so the plane and pilot render");
    }

    @Test
    public void tornadoAndSczBadnikSheetsUseExpectedPaletteLines() throws Exception {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        Sonic2ObjectArt art = new Sonic2ObjectArt(rom, RomByteReader.fromRom(rom));

        ObjectSpriteSheet tornado = art.loadTornadoSheet();
        assertNotNull(tornado, "Tornado sheet should load from ROM");
        assertEquals(0, tornado.getPaletteIndex(), "Tornado sheet palette should be line 0");

        ObjectSpriteSheet balkiry = art.loadBalkirySheet();
        assertNotNull(balkiry, "Balkiry sheet should load from ROM");
        assertEquals(0, balkiry.getPaletteIndex(), "Balkiry sheet palette should be line 0");
        assertTrue(balkiry.getFrame(0).pieces().stream().allMatch(piece -> piece.priority()), "Balkiry frame 0 pieces should keep priority flag set from objAC mappings");
        assertTrue(balkiry.getFrame(1).pieces().stream().allMatch(piece -> piece.priority()), "Balkiry frame 1 pieces should keep priority flag set from objAC mappings");

        ObjectSpriteSheet nebula = art.loadNebulaSheet();
        assertNotNull(nebula, "Nebula sheet should load from ROM");
        assertEquals(1, nebula.getPaletteIndex(), "Nebula sheet palette should be line 1");

        ObjectSpriteSheet turtloid = art.loadTurtloidSheet();
        assertNotNull(turtloid, "Turtloid sheet should load from ROM");
        assertEquals(0, turtloid.getPaletteIndex(), "Turtloid base sheet should use line 0 (piece palettes add on top)");
        assertTrue(turtloid.getFrame(0).pieces().stream().allMatch(piece -> piece.paletteIndex() == 1), "Turtloid body frame pieces should use palette line +1 from mappings");
        assertTrue(turtloid.getFrame(0).pieces().stream().allMatch(piece -> piece.priority()), "Turtloid body frame pieces should preserve priority flag from mappings");
        assertTrue(turtloid.getFrame(4).pieces().stream().allMatch(piece -> piece.paletteIndex() == 0), "Turtloid shot frame pieces should use palette line +0 from mappings");
        assertTrue(turtloid.getFrame(4).pieces().stream().allMatch(piece -> piece.priority()), "Turtloid shot frame pieces should preserve priority flag from mappings");
    }

    @Test
    public void tornadoSheetsUseRendererSafeTileRanges() throws Exception {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        Sonic2ObjectArt art = new Sonic2ObjectArt(rom, RomByteReader.fromRom(rom));

        ObjectSpriteSheet tornado = art.loadTornadoSheet();
        assertFrameTileRangesFitSheet(tornado, "Tornado");
        assertPatternRangeHasVisiblePixels(tornado,
                Sonic2Constants.ART_TILE_SONIC - Sonic2Constants.ART_TILE_ENDING_TORNADO, 6,
                "Tornado Sonic rider");
        assertPatternRangeHasVisiblePixels(tornado,
                Sonic2Constants.ART_TILE_TAILS - Sonic2Constants.ART_TILE_ENDING_TORNADO, 6,
                "Tornado Tails rider");
        assertFrameTileRangesFitSheet(art.loadTornadoThrusterSheet(), "Tornado thruster");
    }

    private static void assertFrameTileRangesFitSheet(ObjectSpriteSheet sheet, String label) {
        assertNotNull(sheet, label + " sheet should load from ROM");
        int patternCount = sheet.getPatterns().length;
        for (int frameIndex = 0; frameIndex < sheet.getFrameCount(); frameIndex++) {
            SpriteMappingFrame frame = sheet.getFrame(frameIndex);
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tileCount = piece.widthTiles() * piece.heightTiles();
                int endExclusive = piece.tileIndex() + tileCount;
                assertTrue(piece.tileIndex() >= 0 && endExclusive <= patternCount,
                        label + " frame " + frameIndex + " piece references tiles ["
                                + piece.tileIndex() + "," + endExclusive
                                + ") outside patternCount=" + patternCount);
            }
        }
    }

    private static void assertPatternRangeHasVisiblePixels(ObjectSpriteSheet sheet, int start, int count, String label) {
        assertNotNull(sheet, label + " sheet should load from ROM");
        Pattern[] patterns = sheet.getPatterns();
        boolean anyVisible = false;
        for (int index = start; index < start + count; index++) {
            assertTrue(index >= 0 && index < patterns.length,
                    label + " tile " + index + " should be present in the combined sheet");
            anyVisible |= hasVisiblePixel(patterns[index]);
        }
        assertTrue(anyVisible, label + " tile range should contain ROM art");
    }

    private static boolean hasVisiblePixel(Pattern pattern) {
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                if (pattern.getPixel(x, y) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}


