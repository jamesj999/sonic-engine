package uk.co.jamesj999.sonic.level.render;

/**
 * Common sprite frame piece contract for pattern-based renderers.
 */
public interface SpriteFramePiece {
    int xOffset();
    int yOffset();
    int widthTiles();
    int heightTiles();
    int tileIndex();
    boolean hFlip();
    boolean vFlip();
    int paletteIndex();
}
