package uk.co.jamesj999.sonic.level.rings;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.util.Collection;

/**
 * Renders animated rings using cached patterns.
 */
public class RingRenderManager {
    private final RingSpriteSheet spriteSheet;
    private int patternBase = -1;
    private final RingFrameBounds[] frameBoundsCache;
    private final PatternBounds[] patternBoundsCache;
    private RingFrameBounds spinBoundsCache;

    public RingRenderManager(RingSpriteSheet spriteSheet) {
        this.spriteSheet = spriteSheet;
        this.frameBoundsCache = new RingFrameBounds[spriteSheet.getFrameCount()];
        this.patternBoundsCache = new PatternBounds[spriteSheet.getPatterns().length];
    }

    public void ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        if (patternBase == basePatternIndex) {
            return;
        }
        spriteSheet.cachePatterns(graphicsManager, basePatternIndex);
        patternBase = basePatternIndex;
    }

    public void draw(Collection<RingSpawn> rings, int frameCounter) {
        if (rings == null || rings.isEmpty() || patternBase < 0) {
            return;
        }
        int frameIndex = getSpinFrameIndex(frameCounter);
        RingFrame frame = spriteSheet.getFrame(frameIndex);
        for (RingSpawn ring : rings) {
            drawFrame(frame, ring.x(), ring.y());
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

    public RingFrameBounds getFrameBounds(int frameCounter) {
        return getFrameBoundsForIndex(getSpinFrameIndex(frameCounter));
    }

    public RingFrameBounds getFrameBoundsForIndex(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frameBoundsCache.length) {
            return new RingFrameBounds(0, 0, 0, 0);
        }
        RingFrameBounds cached = frameBoundsCache[frameIndex];
        if (cached != null) {
            return cached;
        }
        RingFrame frame = spriteSheet.getFrame(frameIndex);
        RingFrameBounds bounds = computeFrameBounds(frame);
        frameBoundsCache[frameIndex] = bounds;
        return bounds;
    }

    public RingFrameBounds getSpinBounds() {
        if (spinBoundsCache != null) {
            return spinBoundsCache;
        }
        int spinCount = spriteSheet.getSpinFrameCount();
        if (spinCount <= 0) {
            spinCount = spriteSheet.getFrameCount();
        }
        if (spinCount <= 0) {
            spinBoundsCache = new RingFrameBounds(0, 0, 0, 0);
            return spinBoundsCache;
        }
        boolean first = true;
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;
        for (int i = 0; i < spinCount; i++) {
            RingFrameBounds bounds = getFrameBoundsForIndex(i);
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
        spinBoundsCache = first ? new RingFrameBounds(0, 0, 0, 0) : new RingFrameBounds(minX, minY, maxX, maxY);
        return spinBoundsCache;
    }

    public void drawFrameIndex(int frameIndex, int originX, int originY) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0) {
            return;
        }
        RingFrame frame = spriteSheet.getFrame(frameIndex);
        drawFrame(frame, originX, originY);
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

    private void drawFrame(RingFrame frame, int originX, int originY) {
        for (RingFramePiece piece : frame.pieces()) {
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            int pieceX = originX + piece.xOffset();
            // PatternRenderCommand treats drawY as the bottom of a tile; mapping offsets are top-based.
            int pieceY = originY + piece.yOffset() + Pattern.PATTERN_HEIGHT;
            int paletteIndex = piece.paletteIndex() != 0 ? piece.paletteIndex() : spriteSheet.getPaletteIndex();

            for (int ty = 0; ty < heightTiles; ty++) {
                for (int tx = 0; tx < widthTiles; tx++) {
                    int srcX = piece.hFlip() ? (widthTiles - 1 - tx) : tx;
                    int srcY = piece.vFlip() ? (heightTiles - 1 - ty) : ty;
                    int tileOffset = (tx * heightTiles) + ty;
                    int patternIndex = patternBase + piece.tileIndex() + tileOffset;

                    int drawX = pieceX + (srcX * Pattern.PATTERN_WIDTH);
                    int drawY = pieceY + (srcY * Pattern.PATTERN_HEIGHT);

                    int descIndex = patternIndex & 0x7FF;
                    if (piece.hFlip()) {
                        descIndex |= 0x800;
                    }
                    if (piece.vFlip()) {
                        descIndex |= 0x1000;
                    }
                    descIndex |= (paletteIndex & 0x3) << 13;

                    PatternDesc desc = new PatternDesc(descIndex);
                    GraphicsManager.getInstance().renderPattern(desc, drawX, drawY);
                }
            }
        }
    }

    private RingFrameBounds computeFrameBounds(RingFrame frame) {
        Pattern[] patterns = spriteSheet.getPatterns();
        boolean first = true;
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;

        for (RingFramePiece piece : frame.pieces()) {
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            for (int ty = 0; ty < heightTiles; ty++) {
                for (int tx = 0; tx < widthTiles; tx++) {
                    int srcX = piece.hFlip() ? (widthTiles - 1 - tx) : tx;
                    int srcY = piece.vFlip() ? (heightTiles - 1 - ty) : ty;
                    int tileOffset = (tx * heightTiles) + ty;
                    int tileIndex = piece.tileIndex() + tileOffset;
                    if (tileIndex < 0 || tileIndex >= patterns.length) {
                        continue;
                    }
                    PatternBounds patternBounds = getPatternBounds(tileIndex);
                    if (patternBounds == null) {
                        continue;
                    }

                    int drawX = piece.xOffset() + (srcX * Pattern.PATTERN_WIDTH);
                    int drawY = piece.yOffset() + Pattern.PATTERN_HEIGHT + (srcY * Pattern.PATTERN_HEIGHT);

                    int tileMinX = patternBounds.minX();
                    int tileMaxX = patternBounds.maxX();
                    int tileMinY = patternBounds.minY();
                    int tileMaxY = patternBounds.maxY();
                    if (piece.hFlip()) {
                        int flippedMinX = Pattern.PATTERN_WIDTH - 1 - tileMaxX;
                        int flippedMaxX = Pattern.PATTERN_WIDTH - 1 - tileMinX;
                        tileMinX = flippedMinX;
                        tileMaxX = flippedMaxX;
                    }

                    int pieceMinX = drawX + tileMinX;
                    int pieceMaxX = drawX + tileMaxX;
                    int pieceMinY;
                    int pieceMaxY;
                    if (piece.vFlip()) {
                        pieceMinY = drawY - (Pattern.PATTERN_HEIGHT - 1) + tileMinY;
                        pieceMaxY = drawY - (Pattern.PATTERN_HEIGHT - 1) + tileMaxY;
                    } else {
                        pieceMinY = drawY - tileMaxY;
                        pieceMaxY = drawY - tileMinY;
                    }

                    if (first) {
                        minX = pieceMinX;
                        minY = pieceMinY;
                        maxX = pieceMaxX;
                        maxY = pieceMaxY;
                        first = false;
                    } else {
                        minX = Math.min(minX, pieceMinX);
                        minY = Math.min(minY, pieceMinY);
                        maxX = Math.max(maxX, pieceMaxX);
                        maxY = Math.max(maxY, pieceMaxY);
                    }
                }
            }
        }

        if (first) {
            return new RingFrameBounds(0, 0, 0, 0);
        }
        return new RingFrameBounds(minX, minY, maxX, maxY);
    }

    private PatternBounds getPatternBounds(int index) {
        if (index < 0 || index >= patternBoundsCache.length) {
            return null;
        }
        PatternBounds cached = patternBoundsCache[index];
        if (cached != null) {
            return cached;
        }
        Pattern pattern = spriteSheet.getPatterns()[index];
        int minX = Pattern.PATTERN_WIDTH;
        int minY = Pattern.PATTERN_HEIGHT;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                if ((pattern.getPixel(x, y) & 0xFF) != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return null;
        }
        PatternBounds bounds = new PatternBounds(minX, minY, maxX, maxY);
        patternBoundsCache[index] = bounds;
        return bounds;
    }

    public record RingFrameBounds(int minX, int minY, int maxX, int maxY) {
        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }
    }

    private record PatternBounds(int minX, int minY, int maxX, int maxY) {
    }
}
