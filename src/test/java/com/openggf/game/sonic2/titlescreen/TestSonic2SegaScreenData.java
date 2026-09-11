package com.openggf.game.sonic2.titlescreen;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.Sonic2PlayerArt;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.util.PatternDecompressor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2SegaScreenData {

    @Test
    void segaLogoMapUsesRomPlaneBTileNumbers() {
        TitleScreenDataLoader loader = loaded();
        int[] map = loader.getSegaLogoMap();

        assertEquals(40 * 28, map.length);
        assertEquals(1, map[0] & 0x7FF, "blank border is tile 1, not host-local tile 0");
        assertEquals(127, map[18 * 40] & 0x7FF, "bottom wipe separator uses the final SEGA art tile");
        assertEquals(1120, java.util.Arrays.stream(map).filter(word -> word != 0).count());
    }

    @Test
    void giantSonicUsesObjB1ScaledMappings() {
        TitleScreenDataLoader loader = loaded();

        assertEquals(0x160, loader.getSegaGiantSonicPatternCount(),
                "ROM scales four 0x58-tile Sonic frames into the SEGA screen buffer");

        SpriteMappingFrame frame0 = loader.getSegaGiantSonicMappingFrame(0);
        SpriteMappingFrame frame1 = loader.getSegaGiantSonicMappingFrame(1);
        assertNotNull(frame0);
        assertNotNull(frame1);
        assertEquals(6, frame0.pieces().size());
        assertEquals(6, frame1.pieces().size());
        assertPiece(frame0.pieces().get(0), -0x10, -0x28, 4, 4, 0x00);
        assertPiece(frame0.pieces().get(1), 0x10, -0x28, 2, 4, 0x10);
        assertPiece(frame0.pieces().get(2), -0x20, -0x08, 4, 4, 0x18);
        assertPiece(frame1.pieces().get(0), -0x10, -0x28, 4, 4, 0x58);

        Pattern first = loader.getSegaGiantSonicPatternForTest(0);
        Pattern last = loader.getSegaGiantSonicPatternForTest(0x15F);
        assertNotNull(first);
        assertNotNull(last);
        assertTrue(hasOpaquePixel(first) || hasOpaquePixel(last),
                "scaled Sonic bank should contain real ROM pixels");
    }

    @Test
    void giantSonicScaledTilesAreChunkedForObjB1Pieces() throws IOException {
        TitleScreenDataLoader loader = loaded();
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        Pattern[] sourcePatterns = PatternDecompressor.uncompressed(
                reader, Sonic2Constants.ART_UNC_SONIC_ADDR, Sonic2Constants.ART_UNC_SONIC_SIZE);
        int[] sourceTiles = flattenDplcFrame(
                Sonic2PlayerArt.parseDplcFrames(reader, Sonic2Constants.MAP_R_UNC_SONIC_ADDR).get(45));

        assertScaledQuadrant(loader.getSegaGiantSonicPatternForTest(0x10),
                sourcePatterns[sourceTiles[4]], 0, 0,
                "top 3x2 source piece stores source column 2, row 0 at ObjB1 tile 0x10");
        assertScaledQuadrant(loader.getSegaGiantSonicPatternForTest(0x38),
                sourcePatterns[sourceTiles[14]], 0, 0,
                "bottom 4x4 source piece stores top-right chunk at ObjB1 tile 0x38");
        assertScaledQuadrant(loader.getSegaGiantSonicPatternForTest(0x28),
                sourcePatterns[sourceTiles[8]], 0, 0,
                "bottom 4x4 source piece stores bottom-left chunk at ObjB1 tile 0x28");
        assertScaledQuadrant(loader.getSegaGiantSonicPatternForTest(0x48),
                sourcePatterns[sourceTiles[16]], 0, 0,
                "bottom 4x4 source piece stores bottom-right chunk at ObjB1 tile 0x48");
    }

    @Test
    void segaWipePalettesExposeSevenStepObjB0Scripts() {
        TitleScreenDataLoader loader = loaded();

        assertEquals(7, loader.getSegaWipeBackgroundPalettes().length);
        assertEquals(7, loader.getSegaWipeForegroundPalettes().length);

        Palette base = loader.resolveSegaPaletteLine(0, 0);
        Palette midWipe = loader.resolveSegaPaletteLine(0, 12);
        Palette endWipe = loader.resolveSegaPaletteLine(0, 66);

        assertNotNull(base);
        assertNotNull(midWipe);
        assertNotNull(endWipe);
        assertTrue(colorRangeChanged(base, midWipe, 8, 15),
                "Sega Screen 2 script writes palette line 0 colors 8-15");
        assertTrue(colorRangeChanged(midWipe, endWipe, 0, 7),
                "Sega Screen 3 script writes palette line 0 colors 0-7");
    }

    @Test
    void segaWipePalettesHoldFinalStepAfterScriptCompletes() {
        TitleScreenDataLoader loader = loaded();

        assertColorRangeEquals(loader.resolveSegaPaletteLine(0, 36), loader.resolveSegaPaletteLine(0, 41),
                8, 15, "Sega Screen 2 palette should stay latched after the 7th step");
        assertColorRangeEquals(loader.resolveSegaPaletteLine(0, 90), loader.resolveSegaPaletteLine(0, 95),
                0, 7, "Sega Screen 3 palette should stay latched after the 7th step");
    }

    @Test
    void giantSonicRendererUsesSharedGenesisSpriteColumnMajorTileOrder() {
        SpriteMappingPiece piece = new SpriteMappingPiece(-0x20, -0x08, 4, 4, 0x18, false, false, 0);

        List<Integer> unflippedPatterns = renderedPatterns(piece, false, false);
        assertEquals(0x101C, (int) unflippedPatterns.get(1));
        assertEquals(0x1019, (int) unflippedPatterns.get(4));

        List<Integer> hFlippedPatterns = renderedPatterns(piece, true, false);
        List<Integer> vFlippedPatterns = renderedPatterns(piece, false, true);
        assertEquals(0x1018, (int) hFlippedPatterns.get(0),
                "hflip should move and flip the tile, not reverse the pattern-number lookup");
        assertEquals(0x1018, (int) vFlippedPatterns.get(0),
                "vflip should move and flip the tile, not reverse the pattern-number lookup");
    }

    private static List<Integer> renderedPatterns(SpriteMappingPiece piece, boolean hFlip, boolean vFlip) {
        List<Integer> patterns = new ArrayList<>();
        SpritePieceRenderer.renderPiece(piece, 0, 0, 0x1000, 2, hFlip, vFlip,
                (patternIndex, tileHFlip, tileVFlip, paletteIndex, drawX, drawY) -> patterns.add(patternIndex));
        return patterns;
    }

    private static TitleScreenDataLoader loaded() {
        TitleScreenDataLoader loader = new TitleScreenDataLoader();
        assertTrue(loader.loadData(), "Sonic 2 title screen data should load from ROM");
        return loader;
    }

    private static void assertPiece(SpriteMappingPiece piece, int x, int y, int w, int h, int tile) {
        assertEquals(x, piece.xOffset());
        assertEquals(y, piece.yOffset());
        assertEquals(w, piece.widthTiles());
        assertEquals(h, piece.heightTiles());
        assertEquals(tile, piece.tileIndex());
    }

    private static boolean hasOpaquePixel(Pattern pattern) {
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                if (pattern.getPixel(x, y) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int[] flattenDplcFrame(SpriteDplcFrame frame) {
        int total = 0;
        for (TileLoadRequest request : frame.requests()) {
            total += request.count();
        }
        int[] tiles = new int[total];
        int out = 0;
        for (TileLoadRequest request : frame.requests()) {
            for (int i = 0; i < request.count(); i++) {
                tiles[out++] = request.startTile() + i;
            }
        }
        return tiles;
    }

    private static void assertScaledQuadrant(Pattern dest, Pattern source, int sourceX, int sourceY, String message) {
        assertNotNull(dest);
        assertNotNull(source);
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                assertEquals(source.getPixel(sourceX + x / 2, sourceY + y / 2), dest.getPixel(x, y),
                        message + " at pixel " + x + "," + y);
            }
        }
    }

    private static boolean colorRangeChanged(Palette a, Palette b, int from, int to) {
        for (int i = from; i <= to; i++) {
            Palette.Color ac = a.colors[i];
            Palette.Color bc = b.colors[i];
            if (ac.r != bc.r || ac.g != bc.g || ac.b != bc.b) {
                return true;
            }
        }
        return false;
    }

    private static void assertColorRangeEquals(Palette a, Palette b, int from, int to, String message) {
        assertNotNull(a);
        assertNotNull(b);
        for (int i = from; i <= to; i++) {
            Palette.Color ac = a.colors[i];
            Palette.Color bc = b.colors[i];
            assertEquals(ac.r, bc.r, message + " red " + i);
            assertEquals(ac.g, bc.g, message + " green " + i);
            assertEquals(ac.b, bc.b, message + " blue " + i);
        }
    }
}
