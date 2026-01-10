package uk.co.jamesj999.sonic.level.render;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;

import java.util.List;

/**
 * A fixed-size pattern bank that can be updated via tile load requests.
 */
public class DynamicPatternBank {
    private final Pattern[] patterns;
    private final int basePatternIndex;
    private GraphicsManager graphicsManager;
    private boolean cached;

    public DynamicPatternBank(int basePatternIndex, int capacity) {
        this.basePatternIndex = basePatternIndex;
        this.patterns = new Pattern[Math.max(0, capacity)];
        for (int i = 0; i < this.patterns.length; i++) {
            this.patterns[i] = new Pattern();
        }
    }

    public Pattern[] getPatterns() {
        return patterns;
    }

    public int getBasePatternIndex() {
        return basePatternIndex;
    }

    public void ensureCached(GraphicsManager graphicsManager) {
        if (cached || patterns.length == 0) {
            this.graphicsManager = graphicsManager;
            return;
        }
        if (graphicsManager == null || graphicsManager.getGraphics() == null) {
            this.graphicsManager = graphicsManager;
            return;
        }
        this.graphicsManager = graphicsManager;
        for (int i = 0; i < patterns.length; i++) {
            graphicsManager.cachePatternTexture(patterns[i], basePatternIndex + i);
        }
        cached = true;
    }

    public void applyRequests(List<TileLoadRequest> requests, Pattern[] source) {
        if (requests == null || source == null) {
            return;
        }
        int dstIndex = 0;
        for (TileLoadRequest request : requests) {
            int count = Math.max(0, request.count());
            int startTile = Math.max(0, request.startTile());
            for (int i = 0; i < count; i++) {
                if (dstIndex >= patterns.length) {
                    return;
                }
                int srcIndex = startTile + i;
                if (srcIndex >= 0 && srcIndex < source.length) {
                    patterns[dstIndex].copyFrom(source[srcIndex]);
                    if (cached && graphicsManager != null) {
                        graphicsManager.updatePatternTexture(patterns[dstIndex], basePatternIndex + dstIndex);
                    }
                }
                dstIndex++;
            }
        }
    }
}
