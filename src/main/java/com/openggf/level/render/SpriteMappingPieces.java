package com.openggf.level.render;

/**
 * Helpers for preserving and remapping Mega Drive sprite mapping tile words.
 */
public final class SpriteMappingPieces {

    private SpriteMappingPieces() {
    }

    public static int toTileWord(SpriteMappingPiece piece) {
        int word = piece.tileIndex() & 0x7FF;
        if (piece.hFlip()) {
            word |= 0x0800;
        }
        if (piece.vFlip()) {
            word |= 0x1000;
        }
        word |= (piece.paletteIndex() & 0x3) << 13;
        if (piece.priority()) {
            word |= 0x8000;
        }
        return word;
    }

    public static SpriteMappingPiece withTileIndex(SpriteMappingPiece piece, int tileIndex) {
        return withAttributes(
                piece,
                tileIndex,
                piece.hFlip(),
                piece.vFlip(),
                piece.paletteIndex(),
                piece.priority());
    }

    public static SpriteMappingPiece withTileWord(SpriteMappingPiece piece, int tileWord) {
        return withAttributes(
                piece,
                tileWord & 0x7FF,
                (tileWord & 0x0800) != 0,
                (tileWord & 0x1000) != 0,
                (tileWord >> 13) & 0x3,
                (tileWord & 0x8000) != 0);
    }

    public static SpriteMappingPiece withAttributes(SpriteMappingPiece piece,
                                                    int tileIndex,
                                                    boolean hFlip,
                                                    boolean vFlip,
                                                    int paletteIndex,
                                                    boolean priority) {
        return new SpriteMappingPiece(
                piece.xOffset(),
                piece.yOffset(),
                piece.widthTiles(),
                piece.heightTiles(),
                tileIndex,
                hFlip,
                vFlip,
                paletteIndex,
                priority);
    }
}
