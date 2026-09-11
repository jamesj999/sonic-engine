package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Sonic2SpecialStageTailsSpriteMappingsTest {
    @Test
    void exposesNamedRomArtSourceGroups() {
        assertEquals(0x183, Sonic2SpecialStageSpriteMappings.TAILS_UPRIGHT_ART_BASE);
        assertEquals(0x1C0, Sonic2SpecialStageSpriteMappings.TAILS_DIAGONAL_ART_BASE);
        assertEquals(0x264, Sonic2SpecialStageSpriteMappings.TAILS_HORIZONTAL_ART_BASE);
        assertEquals(0x29E, Sonic2SpecialStageSpriteMappings.TAILS_BALL_ART_BASE);
        assertEquals(0x2AE, Sonic2SpecialStageSpriteMappings.TAILS_TAILS_UPRIGHT_ART_BASE);
        assertEquals(0x2E3, Sonic2SpecialStageSpriteMappings.TAILS_TAILS_DIAGONAL_ART_BASE);
        assertEquals(0x31E, Sonic2SpecialStageSpriteMappings.TAILS_TAILS_HORIZONTAL_ART_BASE);
        assertEquals(0x183, Sonic2SpecialStageSpriteMappings.getTailsArtBase(0));
        assertEquals(0x1C0, Sonic2SpecialStageSpriteMappings.getTailsArtBase(4));
        assertEquals(0x264, Sonic2SpecialStageSpriteMappings.getTailsArtBase(12));
        assertEquals(0x29E, Sonic2SpecialStageSpriteMappings.getTailsArtBase(16));
        assertEquals(0x2AE, Sonic2SpecialStageSpriteMappings.getTailsTailsArtBase(0));
        assertEquals(0x2E3, Sonic2SpecialStageSpriteMappings.getTailsTailsArtBase(7));
        assertEquals(0x31E, Sonic2SpecialStageSpriteMappings.getTailsTailsArtBase(14));
    }

    @Test
    void translatesTailsDestinationTilesThroughTheFrameSpecificDplcRuns() {
        assertEquals(0x183 + 9, Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(0, 9));
        assertEquals(0x183 + 0xF, Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(0, 0xF));
        assertEquals(0x1C0 + 0x16, Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(5, 2));
        assertEquals(0x1C0 + 0x1E, Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(5, 10));
        assertEquals(0x264 + 0x15, Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(13, 4));
        assertEquals(0x264 + 0x16, Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(13, 5));
        assertEquals(0x29E + 8, Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(17, 0));
    }

    @Test
    void translatesTailsTailsFramesUsingTheirThreeArtGroups() {
        assertEquals(0x2AE + 0x23, Sonic2SpecialStageSpriteMappings.translateTailsTailsTileIndex(5, 0));
        assertEquals(0x2E3 + 0x15, Sonic2SpecialStageSpriteMappings.translateTailsTailsTileIndex(10, 0));
        assertEquals(0x31E + 0x2C, Sonic2SpecialStageSpriteMappings.translateTailsTailsTileIndex(20, 0));
    }

    @Test
    void rejectsNegativeAndUnmappedDestinationSlotsForBothObjectTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Sonic2SpecialStageSpriteMappings.translateTailsTileIndex(0, 16));
        assertThrows(IllegalArgumentException.class,
                () -> Sonic2SpecialStageSpriteMappings.translateTailsTailsTileIndex(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Sonic2SpecialStageSpriteMappings.translateTailsTailsTileIndex(0, 6));
    }

    @Test
    void exposesRomDerivedObj10AndObj88MappingPieces() {
        var upright = Sonic2SpecialStageSpriteMappings.getTailsFrame(0);
        assertEquals(3, upright.pieces.length);
        assertPiece(upright.pieces[1], -12, 0, 3, 2, 0x183 + 9, false);

        var body = Sonic2SpecialStageSpriteMappings.getTailsFrame(3);
        assertEquals(5, body.pieces.length);
        assertPiece(body.pieces[0], -0x10, -0x18, 4, 1, 0x183 + 0x10, true);
        assertPiece(body.pieces[4], 8, 0x10, 1, 1, 0x183 + 0x3C, false);

        var horizontal = Sonic2SpecialStageSpriteMappings.getTailsFrame(12);
        assertEquals(2, horizontal.pieces.length);
        assertPiece(horizontal.pieces[1], 4, -16, 2, 4, 0x264 + 9, false);

        var uprightTails = Sonic2SpecialStageSpriteMappings.getTailsTailsFrame(0);
        assertPiece(uprightTails.pieces[0], -6, -6, 2, 3, 0x2AE, false);

        var horizontalTails = Sonic2SpecialStageSpriteMappings.getTailsTailsFrame(14);
        assertPiece(horizontalTails.pieces[0], -22, -4, 3, 2, 0x31E, false);

        var tails = Sonic2SpecialStageSpriteMappings.getTailsTailsFrame(18);
        assertEquals(1, tails.pieces.length);
        assertPiece(tails.pieces[0], -0x1B, -0xB, 4, 2, 0x31E + 0x1B, false);
    }

    private static void assertPiece(Sonic2SpecialStageSpriteMappings.SpritePiece piece,
                                    int x, int y, int width, int height, int tile, boolean hFlip) {
        assertEquals(x, piece.xOffset);
        assertEquals(y, piece.yOffset);
        assertEquals(width, piece.widthTiles);
        assertEquals(height, piece.heightTiles);
        assertEquals(tile, piece.tileIndex);
        assertEquals(hFlip, piece.hFlip);
    }
}
