package com.openggf.sprites.render;

import com.openggf.game.GameServices;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderContext;
import com.openggf.level.PatternDesc;
import com.openggf.level.render.DynamicPatternBank;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.art.SpriteArtSet;

import java.util.Objects;

/**
 * Renders playable sprites using mapping frames and DPLC-driven tile updates.
 */
public class PlayerSpriteRenderer {
    private final SpriteArtSet artSet;
    private final DynamicPatternBank patternBank;
    private final GraphicsManager graphicsManager;
    private final PatternDesc reusableDesc = new PatternDesc();
    private RenderContext renderContext;
    private int lastFrame = -1;

    public PlayerSpriteRenderer(SpriteArtSet artSet) {
        this(artSet, GameServices.graphics());
    }

    public PlayerSpriteRenderer(SpriteArtSet artSet, GraphicsManager graphicsManager) {
        this.artSet = artSet;
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        int capacity = Math.max(0, artSet.bankSize());
        this.patternBank = new DynamicPatternBank(artSet.basePatternIndex(), capacity);
    }

    public void setRenderContext(RenderContext ctx) {
        this.renderContext = ctx;
    }

    public void ensureCached(GraphicsManager graphicsManager) {
        patternBank.ensureCached(graphicsManager);
    }

    /**
     * Invalidates the DPLC frame cache, forcing tile re-upload on the next draw.
     * Must be called after a seamless level transition that reloads the pattern
     * buffer — the old VRAM tiles are gone but {@code lastFrame} would otherwise
     * prevent re-upload.
     */
    public void invalidateDplcCache() {
        lastFrame = -1;
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
            boolean loaded = applyDplc(frameIndex);
            if (loaded) {
                lastFrame = frameIndex;
            } else if (lastFrame == -1) {
                // First draw and DPLC was empty - try to find the first non-empty DPLC
                // to initialize the pattern bank (ROM initializes with frame 0 tiles
                // before the main loop, but we need to do it here)
                loaded = forceInitialDplc();
                if (loaded) {
                    lastFrame = frameIndex;
                }
            }
        }

        SpriteMappingFrame frame = artSet.mappingFrames().get(frameIndex);
        if (graphicsManager.isSpriteSatCollectionActive()) {
            int satPaletteIndex = resolveRenderPaletteIndex(artSet.paletteIndex());
            for (int i = 0; i < frame.pieces().size(); i++) {
                SpritePieceRenderer.preparePiece(
                        frame.pieces().get(i),
                        originX,
                        originY,
                        patternBank.getBasePatternIndex(),
                        satPaletteIndex,
                        hFlip,
                        vFlip,
                        graphicsManager.getCurrentSpriteHighPriority(),
                        graphicsManager::submitSpriteSatPiece);
            }
            return;
        }
        SpritePieceRenderer.renderPieces(
                frame.pieces(),
                originX,
                originY,
                patternBank.getBasePatternIndex(),
                artSet.paletteIndex(),
                hFlip,
                vFlip,
                (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                    // Build a PatternDesc for flip/palette, but use renderPatternWithId()
                    // to pass the full pattern index — bypassing PatternDesc's 11-bit
                    // VDP limit so sidekick banks above 0x800 resolve correctly.
                    int descIndex = patternIndex & 0x7FF;
                    if (pieceHFlip) {
                        descIndex |= 0x800;
                    }
                    if (pieceVFlip) {
                        descIndex |= 0x1000;
                    }
                    descIndex |= (paletteIndex & 0x3) << 13;

                    reusableDesc.set(descIndex);
                    if (renderContext != null) {
                        reusableDesc.setPaletteIndex(
                                renderContext.getEffectivePaletteLine(paletteIndex));
                    }
                    graphicsManager.renderPatternWithId(patternIndex, reusableDesc, drawX, drawY);
                }
        );
    }

    public SpriteDplcFrame dplcFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= artSet.dplcFrames().size()) {
            return null;
        }
        return artSet.dplcFrames().get(frameIndex);
    }

    /**
     * Consumes a production-owned art decision. Duplicate suppression and
     * diagnostic lifecycle identity have already been decided by the owner.
     */
    public void applyRuntimeArtUpdate(
            int mappingFrame,
            DynamicArtLifecycleService.ArtUpdate update) {
        if (update == null || !update.mappingChanged()) {
            return;
        }
        if (update.submitted()) {
            patternBank.consumeRuntimeArtState(
                    update.tileRequests(), artSet.artTiles());
            lastFrame = mappingFrame;
        } else if (lastFrame == -1 && forceInitialDplc()) {
            lastFrame = mappingFrame;
        }
    }

    public SpritePieceRenderer.FrameBounds getFrameBounds(int frameIndex, boolean hFlip, boolean vFlip) {
        if (frameIndex < 0 || frameIndex >= artSet.mappingFrames().size()) {
            return new SpritePieceRenderer.FrameBounds(0, 0, -1, -1);
        }
        SpriteMappingFrame frame = artSet.mappingFrames().get(frameIndex);
        return SpritePieceRenderer.computeFrameBounds(frame.pieces(), hFlip, vFlip);
    }

    int resolveRenderPaletteIndex(int logicalPaletteIndex) {
        if (renderContext == null) {
            return logicalPaletteIndex;
        }
        return renderContext.getEffectivePaletteLine(logicalPaletteIndex);
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

    /**
     * Force-load the first non-empty DPLC to initialize the pattern bank.
     * This handles the case where frame 0 has an empty DPLC expecting tiles
     * to already be loaded (as the ROM does during initialization).
     */
    private boolean forceInitialDplc() {
        // Search for the first non-empty DPLC
        for (int i = 0; i < artSet.dplcFrames().size(); i++) {
            SpriteDplcFrame dplcFrame = artSet.dplcFrames().get(i);
            if (dplcFrame != null && !dplcFrame.requests().isEmpty()) {
                patternBank.applyRequests(dplcFrame.requests(), artSet.artTiles());
                return true;
            }
        }
        return false;
    }
}
