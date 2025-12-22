package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.level.render.SpriteFramePiece;

/**
 * One sprite piece in a ring animation frame.
 */
public record RingFramePiece(
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
