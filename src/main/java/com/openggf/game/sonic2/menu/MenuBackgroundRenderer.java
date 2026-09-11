package com.openggf.game.sonic2.menu;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;

/**
 * Renders the Sonic/Miles menu background map (MapEng_MenuBack).
 */
public class MenuBackgroundRenderer {
    private final PatternDesc reusableDesc = new PatternDesc();

    public void render(GraphicsManager graphicsManager, int[] mappings, int width, int height,
                       int patternBase, int patternOffset) {
        render(graphicsManager, mappings, width, height, patternBase, patternOffset, 0);
    }

    /**
     * Renders the menu background map with an optional horizontal pixel offset.
     *
     * <p>The {@code xOffset} parameter is used for widescreen centering: at native
     * width 320 the offset is 0 and all tile positions are unchanged.
     */
    public void render(GraphicsManager graphicsManager, int[] mappings, int width, int height,
                       int patternBase, int patternOffset, int xOffset) {
        if (graphicsManager == null || mappings == null || mappings.length == 0) {
            return;
        }

        for (int ty = 0; ty < height; ty++) {
            for (int tx = 0; tx < width; tx++) {
                int idx = ty * width + tx;
                if (idx < 0 || idx >= mappings.length) {
                    continue;
                }
                int word = mappings[idx];
                if (word == 0) {
                    continue;
                }
                int flags = word & 0xF800;
                int patternIndex = (word & 0x7FF) + patternOffset;
                int adjusted = flags | (patternIndex & 0x7FF);
                reusableDesc.set(adjusted);
                int patternId = patternBase + patternIndex;
                graphicsManager.renderPatternWithId(patternId, reusableDesc, xOffset + tx * 8, ty * 8);
            }
        }
    }

    /**
     * Renders the menu background tiled across the full viewport width.
     *
     * <p>The background pattern has period {@code width} tiles (= 320 px at native).
     * At widescreen widths the pattern is wrapped/repeated horizontally so that the
     * full viewport is filled from {@code x=0} to {@code x=viewportWidth}.  No
     * {@code xOffset} is applied — the background expands to fill all available space.
     *
     * <p>Native parity: when {@code viewportWidth == width * 8} (i.e. 320 at native)
     * this method draws exactly {@code width} tile columns starting at {@code x=0},
     * identical to calling {@link #render} with {@code xOffset=0}.
     *
     * @param graphicsManager renderer
     * @param mappings        tilemap words (width × height)
     * @param width           tilemap width in tiles (= the horizontal period)
     * @param height          tilemap height in tiles
     * @param patternBase     virtual pattern-ID base
     * @param patternOffset   pattern index offset applied to each tile word
     * @param viewportWidth   full projection-space width to fill (pixels)
     */
    public void renderTiled(GraphicsManager graphicsManager, int[] mappings, int width, int height,
                            int patternBase, int patternOffset, int viewportWidth) {
        if (graphicsManager == null || mappings == null || mappings.length == 0
                || width <= 0 || height <= 0) {
            return;
        }

        // Number of tile columns needed to cover the full viewport width (round up).
        int tileColumns = (viewportWidth + 7) / 8;

        for (int ty = 0; ty < height; ty++) {
            for (int col = 0; col < tileColumns; col++) {
                // Wrap the source column index to tile the pattern horizontally.
                int tx = col % width;
                int idx = ty * width + tx;
                if (idx < 0 || idx >= mappings.length) {
                    continue;
                }
                int word = mappings[idx];
                if (word == 0) {
                    continue;
                }
                int flags = word & 0xF800;
                int patternIndex = (word & 0x7FF) + patternOffset;
                int adjusted = flags | (patternIndex & 0x7FF);
                reusableDesc.set(adjusted);
                int patternId = patternBase + patternIndex;
                graphicsManager.renderPatternWithId(patternId, reusableDesc, col * 8, ty * 8);
            }
        }
    }
}
