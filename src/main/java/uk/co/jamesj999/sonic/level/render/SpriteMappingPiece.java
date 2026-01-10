package uk.co.jamesj999.sonic.level.render;

/**
 * One sprite piece from a mapping frame.
 */
public record SpriteMappingPiece(
        int xOffset,
        int yOffset,
        int widthTiles,
        int heightTiles,
        int tileIndex,
        boolean hFlip,
        boolean vFlip,
        int paletteIndex
) implements SpriteFramePiece {
}
