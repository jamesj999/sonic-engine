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
            // ROM behavior: Only update lastFrame when tiles were actually loaded.
            // Empty DPLCs mean "reuse previously loaded tiles", so we shouldn't
            // update lastFrame in that case - otherwise a subsequent frame with
            // a real DPLC would not reload its tiles.
            if (applyDplc(frameIndex)) {
                lastFrame = frameIndex;
            }
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

    /**
     * Applies DPLC (Dynamic Pattern Load Cues) for a frame, loading tiles into VRAM.
     *
     * @param frameIndex The frame index to load DPLC for
     * @return true if tiles were loaded, false if DPLC was empty (tiles unchanged)
     */
    private boolean applyDplc(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= artSet.dplcFrames().size()) {
            return false;
        }
        SpriteDplcFrame dplcFrame = artSet.dplcFrames().get(frameIndex);
        if (dplcFrame == null || dplcFrame.requests().isEmpty()) {
            // ROM behavior: empty DPLC means "reuse previously loaded tiles"
            return false;
        }
        patternBank.applyRequests(dplcFrame.requests(), artSet.artTiles());
        return true;
    }
}
