package uk.co.jamesj999.sonic.level.render;

import uk.co.jamesj999.sonic.level.Pattern;

import java.util.List;

/**
 * Shared sprite-piece renderer for pattern-based sprites.
 */
public final class SpritePieceRenderer {
    private SpritePieceRenderer() {
    }

    @FunctionalInterface
    public interface TileConsumer {
        void render(int patternIndex, boolean hFlip, boolean vFlip, int paletteIndex, int drawX, int drawY);
    }

    public static void renderPieces(
            List<? extends SpriteFramePiece> pieces,
            int originX,
            int originY,
            int basePatternIndex,
            int defaultPaletteIndex,
            boolean frameHFlip,
            boolean frameVFlip,
            TileConsumer consumer
    ) {
        if (pieces == null || consumer == null) {
            return;
        }
        for (SpriteFramePiece piece : pieces) {
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            int widthPixels = widthTiles * Pattern.PATTERN_WIDTH;
            int heightPixels = heightTiles * Pattern.PATTERN_HEIGHT;

            int pieceXOffset = piece.xOffset();
            int pieceYOffset = piece.yOffset();
            boolean pieceHFlip = piece.hFlip();
            boolean pieceVFlip = piece.vFlip();

            if (frameHFlip) {
                pieceXOffset = -pieceXOffset - widthPixels;
                pieceHFlip = !pieceHFlip;
            }
            if (frameVFlip) {
                pieceYOffset = -pieceYOffset - heightPixels;
                pieceVFlip = !pieceVFlip;
            }

            int pieceX = originX + pieceXOffset;
            int pieceY = originY + pieceYOffset;
            int paletteIndex = piece.paletteIndex() != 0 ? piece.paletteIndex() : defaultPaletteIndex;

            for (int ty = 0; ty < heightTiles; ty++) {
                for (int tx = 0; tx < widthTiles; tx++) {
                    int srcX = pieceHFlip ? (widthTiles - 1 - tx) : tx;
                    int srcY = pieceVFlip ? (heightTiles - 1 - ty) : ty;
                    int tileOffset = (tx * heightTiles) + ty;
                    int patternIndex = basePatternIndex + piece.tileIndex() + tileOffset;

                    int drawX = pieceX + (srcX * Pattern.PATTERN_WIDTH);
                    int drawY = pieceY + (srcY * Pattern.PATTERN_HEIGHT);

                    consumer.render(patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY);
                }
            }
        }
    }

    public static FrameBounds computeFrameBounds(
            List<? extends SpriteFramePiece> pieces,
            boolean frameHFlip,
            boolean frameVFlip
    ) {
        if (pieces == null || pieces.isEmpty()) {
            return new FrameBounds(0, 0, -1, -1);
        }
        boolean first = true;
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;
        for (SpriteFramePiece piece : pieces) {
            int widthPixels = piece.widthTiles() * Pattern.PATTERN_WIDTH;
            int heightPixels = piece.heightTiles() * Pattern.PATTERN_HEIGHT;
            int pieceXOffset = piece.xOffset();
            int pieceYOffset = piece.yOffset();

            if (frameHFlip) {
                pieceXOffset = -pieceXOffset - widthPixels;
            }
            if (frameVFlip) {
                pieceYOffset = -pieceYOffset - heightPixels;
            }

            int left = pieceXOffset;
            int top = pieceYOffset;
            int right = pieceXOffset + widthPixels - 1;
            int bottom = pieceYOffset + heightPixels - 1;

            if (first) {
                minX = left;
                minY = top;
                maxX = right;
                maxY = bottom;
                first = false;
            } else {
                minX = Math.min(minX, left);
                minY = Math.min(minY, top);
                maxX = Math.max(maxX, right);
                maxY = Math.max(maxY, bottom);
            }
        }
        if (first) {
            return new FrameBounds(0, 0, -1, -1);
        }
        return new FrameBounds(minX, minY, maxX, maxY);
    }

    public record FrameBounds(int minX, int minY, int maxX, int maxY) {
        public int width() {
            return maxX >= minX ? maxX - minX + 1 : 0;
        }

        public int height() {
            return maxY >= minY ? maxY - minY + 1 : 0;
        }
    }
}
