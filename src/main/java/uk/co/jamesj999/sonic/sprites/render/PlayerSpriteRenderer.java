package uk.co.jamesj999.sonic.sprites.render;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.level.render.DynamicPatternBank;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.level.render.SpriteDplcFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
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
        SpritePieceRenderer.renderPieces(
                frame.pieces(),
                originX,
                originY,
                patternBank.getBasePatternIndex(),
                artSet.paletteIndex(),
                hFlip,
                vFlip,
                (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
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
        );
    }

    public SpritePieceRenderer.FrameBounds getFrameBounds(int frameIndex, boolean hFlip, boolean vFlip) {
        if (frameIndex < 0 || frameIndex >= artSet.mappingFrames().size()) {
            return new SpritePieceRenderer.FrameBounds(0, 0, -1, -1);
        }
        SpriteMappingFrame frame = artSet.mappingFrames().get(frameIndex);
        return SpritePieceRenderer.computeFrameBounds(frame.pieces(), hFlip, vFlip);
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
