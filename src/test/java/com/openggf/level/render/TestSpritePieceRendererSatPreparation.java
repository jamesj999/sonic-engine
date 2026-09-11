package com.openggf.level.render;

import com.openggf.graphics.SpriteSatEntry;
import com.openggf.graphics.SpriteSatMaskPostProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSpritePieceRendererSatPreparation {

    @Test
    void preparePiece_preservesFullPaletteLineForSatReplay() {
        List<SpritePieceRenderer.PreparedPiece> prepared = new ArrayList<>();

        SpritePieceRenderer.preparePiece(
                piece(0, 0, 2, 2, 0x120, false, false, 0, false),
                100,
                200,
                0x20000,
                6,
                false,
                false,
                false,
                prepared::add);

        assertEquals(1, prepared.size());
        assertEquals(6, prepared.get(0).paletteIndex());
    }

    @Test
    void satMaskDetection_usesRawTileWordNotAtlasPatternIndex() {
        List<SpriteSatEntry> entries = new ArrayList<>();

        entries.add(SpriteSatEntry.of(40, 0, 2, 2, 0x100, 0, false, false, false, false));

        SpritePieceRenderer.preparePiece(
                piece(8, 24, 2, 2, 0x7C0, false, false, 0, false),
                100,
                0,
                0x20005,
                0,
                false,
                false,
                false,
                prepared -> entries.add(SpriteSatEntry.fromPreparedPiece(prepared, 0)));

        SpritePieceRenderer.preparePiece(
                piece(0, 24, 2, 2, 0x25F, false, false, 0, false),
                100,
                0,
                0x20005,
                0,
                false,
                false,
                false,
                prepared -> entries.add(SpriteSatEntry.fromPreparedPiece(prepared, 0)));

        SpritePieceRenderer.preparePiece(
                piece(0, 16, 4, 4, 0x200, false, false, 0, false),
                100,
                0,
                0x20005,
                0,
                false,
                false,
                false,
                prepared -> entries.add(SpriteSatEntry.fromPreparedPiece(prepared, 0)));

        List<SpriteSatEntry> processed = SpriteSatMaskPostProcessor.process(entries, true);

        assertEquals(3, processed.size());
        assertEquals(0x200, processed.get(1).rawTileWordLow11());
        assertEquals(0, processed.get(1).startColTile());
        assertEquals(4, processed.get(1).colCountTiles());
        assertEquals(0, processed.get(1).startRowTile());
        assertEquals(1, processed.get(1).rowCountTiles());
        assertEquals(3, processed.get(2).startRowTile());
        assertEquals(1, processed.get(2).rowCountTiles());
    }

    private static SpriteFramePiece piece(int xOffset, int yOffset, int widthTiles, int heightTiles,
            int tileIndex, boolean hFlip, boolean vFlip, int paletteIndex, boolean priority) {
        return new SpriteFramePiece() {
            @Override
            public int xOffset() {
                return xOffset;
            }

            @Override
            public int yOffset() {
                return yOffset;
            }

            @Override
            public int widthTiles() {
                return widthTiles;
            }

            @Override
            public int heightTiles() {
                return heightTiles;
            }

            @Override
            public int tileIndex() {
                return tileIndex;
            }

            @Override
            public boolean hFlip() {
                return hFlip;
            }

            @Override
            public boolean vFlip() {
                return vFlip;
            }

            @Override
            public int paletteIndex() {
                return paletteIndex;
            }

            @Override
            public boolean priority() {
                return priority;
            }
        };
    }
}


