package uk.co.jamesj999.sonic.level.render;

import uk.co.jamesj999.sonic.level.Pattern;

/**
 * Common sprite sheet contract for pattern-based renderers.
 */
public interface SpriteSheet<F extends SpriteFrame<? extends SpriteFramePiece>> {
    Pattern[] getPatterns();
    int getFrameCount();
    F getFrame(int index);
    int getPaletteIndex();
    int getFrameDelay();
}
