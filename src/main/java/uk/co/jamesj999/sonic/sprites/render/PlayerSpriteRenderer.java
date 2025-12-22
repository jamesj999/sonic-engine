package uk.co.jamesj999.sonic.sprites.render;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.level.render.DynamicPatternBank;
import uk.co.jamesj999.sonic.level.render.SpriteDplcFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;

/**
 * Renders playable sprites using mapping frames and DPLC-driven tile updates.
 */
public class PlayerSpriteRenderer {
    private final SpriteArtSet artSet;
    private final DynamicPatternBank patternBank;
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private int lastFrame = -1;

    public PlayerSpriteRenderer(SpriteArtSet artSet) {
        this.artSet = artSet;
        int capacity = Math.max(0, artSet.bankSize());
        this.patternBank = new DynamicPatternBank(artSet.basePatternIndex(), capacity);
    }

    public void ensureCached(GraphicsManager graphicsManager) {
        patternBank.ensureCached(graphicsManager);
    }

    public void drawFrame(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
        patternBank.ensureCached(graphicsManager);
        if (frameIndex < 0 || frameIndex >= artSet.mappingFrames().size()) {
            return;
        }
        if (frameIndex != lastFrame) {
            applyDplc(frameIndex);
            lastFrame = frameIndex;
        }

        SpriteMappingFrame frame = artSet.mappingFrames().get(frameIndex);
        for (SpriteMappingPiece piece : frame.pieces()) {
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            int widthPixels = widthTiles * Pattern.PATTERN_WIDTH;
            int heightPixels = heightTiles * Pattern.PATTERN_HEIGHT;

            int pieceXOffset = piece.xOffset();
            int pieceYOffset = piece.yOffset();
            boolean pieceHFlip = piece.hFlip();
            boolean pieceVFlip = piece.vFlip();

            if (hFlip) {
                pieceXOffset = -pieceXOffset - widthPixels;
                pieceHFlip = !pieceHFlip;
            }
            if (vFlip) {
                pieceYOffset = -pieceYOffset - heightPixels;
                pieceVFlip = !pieceVFlip;
            }

            int pieceX = originX + pieceXOffset;
            // PatternRenderCommand treats drawY as the bottom of a tile; mapping offsets are top-based.
            int pieceY = originY + pieceYOffset + Pattern.PATTERN_HEIGHT;
            int paletteIndex = piece.paletteIndex() != 0 ? piece.paletteIndex() : artSet.paletteIndex();

            for (int ty = 0; ty < heightTiles; ty++) {
                for (int tx = 0; tx < widthTiles; tx++) {
                    int srcX = pieceHFlip ? (widthTiles - 1 - tx) : tx;
                    int srcY = pieceVFlip ? (heightTiles - 1 - ty) : ty;
                    int tileOffset = (tx * heightTiles) + ty;
                    int patternIndex = patternBank.getBasePatternIndex() + piece.tileIndex() + tileOffset;

                    int drawX = pieceX + (srcX * Pattern.PATTERN_WIDTH);
                    int drawY = pieceY + (srcY * Pattern.PATTERN_HEIGHT);

                    int descIndex = patternIndex & 0x7FF;
                    if (pieceHFlip) {
                        descIndex |= 0x800;
                    }
                    if (pieceVFlip) {
                        descIndex |= 0x1000;
                    }
                    descIndex |= (paletteIndex & 0x3) << 13;

                    PatternDesc desc = new PatternDesc(descIndex);
                    graphicsManager.renderPattern(desc, drawX, drawY);
                }
            }
        }
    }

    private void applyDplc(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= artSet.dplcFrames().size()) {
            return;
        }
        SpriteDplcFrame dplcFrame = artSet.dplcFrames().get(frameIndex);
        if (dplcFrame == null || dplcFrame.requests().isEmpty()) {
            return;
        }
        patternBank.applyRequests(dplcFrame.requests(), artSet.artTiles());
    }
}
