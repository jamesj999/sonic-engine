package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.level.render.SpriteFrame;

import java.util.List;

/**
 * A single ring animation frame.
 */
public record RingFrame(List<RingFramePiece> pieces) implements SpriteFrame<RingFramePiece> {
}
