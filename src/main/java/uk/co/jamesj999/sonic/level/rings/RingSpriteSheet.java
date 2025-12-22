package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;

import java.util.List;

/**
 * Holds ring patterns and frame mappings.
 */
public class RingSpriteSheet {
    private final Pattern[] patterns;
    private final List<RingFrame> frames;
    private final int paletteIndex;
    private final int frameDelay;
    private final int spinFrameCount;
    private final int sparkleFrameCount;

    public RingSpriteSheet(Pattern[] patterns, List<RingFrame> frames, int paletteIndex, int frameDelay,
                           int spinFrameCount, int sparkleFrameCount) {
        this.patterns = patterns;
        this.frames = frames;
        this.paletteIndex = paletteIndex;
        this.frameDelay = frameDelay;
        int totalFrames = frames != null ? frames.size() : 0;
        int safeSpin = Math.max(0, Math.min(spinFrameCount, totalFrames));
        int safeSparkle = Math.max(0, Math.min(sparkleFrameCount, totalFrames - safeSpin));
        this.spinFrameCount = safeSpin;
        this.sparkleFrameCount = safeSparkle;
    }

    public Pattern[] getPatterns() {
        return patterns;
    }

    public int getFrameCount() {
        return frames.size();
    }

    public int getSpinFrameCount() {
        return spinFrameCount;
    }

    public int getSparkleFrameCount() {
        return sparkleFrameCount;
    }

    public int getSparkleStartIndex() {
        return spinFrameCount;
    }

    public RingFrame getFrame(int index) {
        return frames.get(index);
    }

    public int getPaletteIndex() {
        return paletteIndex;
    }

    public int getFrameDelay() {
        return frameDelay;
    }

    public void cachePatterns(GraphicsManager graphicsManager, int basePatternIndex) {
        if (graphicsManager.getGraphics() == null) {
            return;
        }
        for (int i = 0; i < patterns.length; i++) {
            graphicsManager.cachePatternTexture(patterns[i], basePatternIndex + i);
        }
    }
}
