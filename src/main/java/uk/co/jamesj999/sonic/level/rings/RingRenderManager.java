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

    public RingRenderManager(RingSpriteSheet spriteSheet) {
        this.spriteSheet = spriteSheet;
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
        int frameIndex = (frameCounter / spriteSheet.getFrameDelay()) % spriteSheet.getFrameCount();
        RingFrame frame = spriteSheet.getFrame(frameIndex);
        for (RingSpawn ring : rings) {
            drawFrame(frame, ring.x(), ring.y());
        }
    }

    private void drawFrame(RingFrame frame, int originX, int originY) {
        for (RingFramePiece piece : frame.pieces()) {
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            int pieceX = originX + piece.xOffset();
            int pieceY = originY + piece.yOffset();
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
}
