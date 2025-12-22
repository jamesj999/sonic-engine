package uk.co.jamesj999.sonic.level.render;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;

/**
 * Renders sprite sheets built from level patterns and caches frame bounds.
 */
public class PatternSpriteRenderer {
    private final SpriteSheet<? extends SpriteFrame<? extends SpriteFramePiece>> spriteSheet;
    private int patternBase = -1;
    private final FrameBounds[] frameBoundsCache;
    private final PatternBounds[] patternBoundsCache;

    public PatternSpriteRenderer(SpriteSheet<? extends SpriteFrame<? extends SpriteFramePiece>> spriteSheet) {
        this.spriteSheet = spriteSheet;
        this.frameBoundsCache = new FrameBounds[spriteSheet.getFrameCount()];
        this.patternBoundsCache = new PatternBounds[spriteSheet.getPatterns().length];
    }

    public void ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        if (patternBase == basePatternIndex) {
            return;
        }
        cachePatterns(graphicsManager, basePatternIndex);
        patternBase = basePatternIndex;
    }

    public boolean isReady() {
        return patternBase >= 0;
    }

    public FrameBounds getFrameBoundsForIndex(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frameBoundsCache.length) {
            return new FrameBounds(0, 0, 0, 0);
        }
        FrameBounds cached = frameBoundsCache[frameIndex];
        if (cached != null) {
            return cached;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        FrameBounds bounds = computeFrameBounds(frame);
        frameBoundsCache[frameIndex] = bounds;
        return bounds;
    }

    public void drawFrameIndex(int frameIndex, int originX, int originY) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0) {
            return;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        drawFrame(frame, originX, originY);
    }

    private void cachePatterns(GraphicsManager graphicsManager, int basePatternIndex) {
        if (graphicsManager.getGraphics() == null) {
            return;
        }
        Pattern[] patterns = spriteSheet.getPatterns();
        for (int i = 0; i < patterns.length; i++) {
            graphicsManager.cachePatternTexture(patterns[i], basePatternIndex + i);
        }
    }

    private void drawFrame(SpriteFrame<? extends SpriteFramePiece> frame, int originX, int originY) {
        for (SpriteFramePiece piece : frame.pieces()) {
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

    private FrameBounds computeFrameBounds(SpriteFrame<? extends SpriteFramePiece> frame) {
        Pattern[] patterns = spriteSheet.getPatterns();
        boolean first = true;
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;

        for (SpriteFramePiece piece : frame.pieces()) {
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
            return new FrameBounds(0, 0, 0, 0);
        }
        return new FrameBounds(minX, minY, maxX, maxY);
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

    public record FrameBounds(int minX, int minY, int maxX, int maxY) {
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
