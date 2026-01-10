package uk.co.jamesj999.sonic.level.render;

import java.util.List;

/**
 * Tile streaming plan for a single sprite frame.
 */
public record SpriteDplcFrame(List<TileLoadRequest> requests) {
}
