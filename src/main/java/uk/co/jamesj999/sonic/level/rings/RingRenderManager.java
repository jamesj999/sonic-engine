package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;

import java.util.Collection;

/**
 * Renders animated rings using cached patterns.
 */
public class RingRenderManager {
    private final RingSpriteSheet spriteSheet;
    private final PatternSpriteRenderer renderer;
    private PatternSpriteRenderer.FrameBounds spinBoundsCache;

    public RingRenderManager(RingSpriteSheet spriteSheet) {
        this.spriteSheet = spriteSheet;
        this.renderer = new PatternSpriteRenderer(spriteSheet);
    }

    public void ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        renderer.ensurePatternsCached(graphicsManager, basePatternIndex);
    }

    public void draw(Collection<RingSpawn> rings, int frameCounter) {
        if (rings == null || rings.isEmpty() || !renderer.isReady()) {
            return;
        }
        int frameIndex = getSpinFrameIndex(frameCounter);
        for (RingSpawn ring : rings) {
            renderer.drawFrameIndex(frameIndex, ring.x(), ring.y());
        }
    }

    public int getFrameIndex(int frameCounter) {
        return getSpinFrameIndex(frameCounter);
    }

    public int getSpinFrameIndex(int frameCounter) {
        int frameCount = spriteSheet.getSpinFrameCount();
        if (frameCount <= 0) {
            frameCount = spriteSheet.getFrameCount();
        }
        if (frameCount <= 0) {
            return 0;
        }
        int delay = Math.max(1, spriteSheet.getFrameDelay());
        return (frameCounter / delay) % frameCount;
    }

    public PatternSpriteRenderer.FrameBounds getFrameBounds(int frameCounter) {
        return renderer.getFrameBoundsForIndex(getSpinFrameIndex(frameCounter));
    }

    public PatternSpriteRenderer.FrameBounds getFrameBoundsForIndex(int frameIndex) {
        return renderer.getFrameBoundsForIndex(frameIndex);
    }

    public PatternSpriteRenderer.FrameBounds getSpinBounds() {
        if (spinBoundsCache != null) {
            return spinBoundsCache;
        }
        int spinCount = spriteSheet.getSpinFrameCount();
        if (spinCount <= 0) {
            spinCount = spriteSheet.getFrameCount();
        }
        if (spinCount <= 0) {
            spinBoundsCache = new PatternSpriteRenderer.FrameBounds(0, 0, 0, 0);
            return spinBoundsCache;
        }
        boolean first = true;
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;
        for (int i = 0; i < spinCount; i++) {
            PatternSpriteRenderer.FrameBounds bounds = renderer.getFrameBoundsForIndex(i);
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                continue;
            }
            if (first) {
                minX = bounds.minX();
                minY = bounds.minY();
                maxX = bounds.maxX();
                maxY = bounds.maxY();
                first = false;
            } else {
                minX = Math.min(minX, bounds.minX());
                minY = Math.min(minY, bounds.minY());
                maxX = Math.max(maxX, bounds.maxX());
                maxY = Math.max(maxY, bounds.maxY());
            }
        }
        spinBoundsCache = first ? new PatternSpriteRenderer.FrameBounds(0, 0, 0, 0)
                : new PatternSpriteRenderer.FrameBounds(minX, minY, maxX, maxY);
        return spinBoundsCache;
    }

    public void drawFrameIndex(int frameIndex, int originX, int originY) {
        renderer.drawFrameIndex(frameIndex, originX, originY);
    }

    public int getSparkleStartIndex() {
        return spriteSheet.getSparkleStartIndex();
    }

    public int getSparkleFrameCount() {
        return spriteSheet.getSparkleFrameCount();
    }

    public int getFrameDelay() {
        return spriteSheet.getFrameDelay();
    }
}
