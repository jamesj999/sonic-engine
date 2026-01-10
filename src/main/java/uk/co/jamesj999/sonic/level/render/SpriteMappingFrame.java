package uk.co.jamesj999.sonic.level.render;

import java.util.List;

/**
 * A sprite mapping frame composed of multiple pieces.
 */
public record SpriteMappingFrame(List<SpriteMappingPiece> pieces) implements SpriteFrame<SpriteMappingPiece> {
}
