package com.openggf.level.render;

import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.SpriteMaskReplayRole;
import com.openggf.level.PatternDesc;
import com.openggf.level.Pattern;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Renders sprite sheets built from level patterns and caches frame bounds.
 */
@com.openggf.game.ModApi
public class PatternSpriteRenderer {
    private static final Logger LOG = Logger.getLogger(PatternSpriteRenderer.class.getName());
    private static final int MAX_FRAME_PIECES = 80;
    private static final int MAX_FRAME_TILES = 512;
    private static final int MAX_FRAME_SPAN_PIXELS = 1024;
    private static final int MAX_ABS_PIECE_OFFSET_PIXELS = 2048;

    private SpriteSheet<? extends SpriteFrame<? extends SpriteFramePiece>> spriteSheet;
    private final GraphicsManager graphicsManager;
    private int patternBase = -1;
    private final FrameBounds[] frameBoundsCache;
    private final PatternBounds[] patternBoundsCache;
    private final byte[] frameValidationCache;
    private final PatternDesc reusableDesc = new PatternDesc();

    public PatternSpriteRenderer(SpriteSheet<? extends SpriteFrame<? extends SpriteFramePiece>> spriteSheet) {
        this(spriteSheet, GameServices.graphics());
    }

    public PatternSpriteRenderer(SpriteSheet<? extends SpriteFrame<? extends SpriteFramePiece>> spriteSheet,
            GraphicsManager graphicsManager) {
        this.spriteSheet = spriteSheet;
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.frameBoundsCache = new FrameBounds[spriteSheet.getFrameCount()];
        this.patternBoundsCache = new PatternBounds[spriteSheet.getPatterns().length];
        this.frameValidationCache = new byte[spriteSheet.getFrameCount()];
    }

    public void ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        if (patternBase == basePatternIndex) {
            return;
        }
        cachePatterns(graphicsManager, basePatternIndex);
        patternBase = basePatternIndex;
    }

    public void updatePatternRange(GraphicsManager graphicsManager, int startIndex, int count) {
        if (graphicsManager == null || patternBase < 0 || count <= 0) {
            return;
        }
        Pattern[] patterns = spriteSheet.getPatterns();
        int end = Math.min(patterns.length, startIndex + count);
        for (int i = Math.max(0, startIndex); i < end; i++) {
            graphicsManager.updatePatternTexture(patterns[i], patternBase + i);
            if (i >= 0 && i < patternBoundsCache.length) {
                patternBoundsCache[i] = null;
            }
        }
    }

    public boolean isReady() {
        return patternBase >= 0;
    }

    /**
     * Points this renderer at {@code replacement}, a sheet whose pattern pixels,
     * frames, palette and frame delay equal the current sheet's. The GPU
     * patterns already uploaded for the current sheet therefore stay valid and
     * are not re-uploaded; only the object references change, so later
     * in-place pattern refreshes address the replacement's {@code Pattern}
     * instances. Callers establish the equivalence; this only checks shape.
     */
    public void rebindEquivalentSheet(SpriteSheet<? extends SpriteFrame<? extends SpriteFramePiece>> replacement) {
        if (replacement == null
                || replacement.getPatterns().length != spriteSheet.getPatterns().length
                || replacement.getFrameCount() != spriteSheet.getFrameCount()) {
            throw new IllegalArgumentException("replacement sheet shape differs");
        }
        spriteSheet = replacement;
    }

    /** Returns the allocated virtual pattern base, or {@code -1} before caching. */
    public int getPatternBase() {
        return patternBase;
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
        drawFrameIndex(frameIndex, originX, originY, false, false);
    }

    public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
        drawFrameIndex(frameIndex, originX, originY, hFlip, vFlip, -1);
    }

    /**
     * Draws a frame using the provided palette as a base addition, preserving each
     * mapping piece's palette bits. This matches Genesis art_tile addition semantics.
     */
    public void drawFrameIndexWithPaletteBase(int frameIndex, int originX, int originY,
            boolean hFlip, boolean vFlip, int paletteBase) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0
                || !isFrameRenderSafe(frameIndex)) {
            return;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        drawFramePieces(frame.pieces(), originX, originY, hFlip, vFlip, paletteBase);
    }

    /**
     * Draws a frame with an optional palette override.
     * @param paletteOverride palette index to use, or -1 to use the sprite sheet's default
     */
    public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip, int paletteOverride) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0
                || !isFrameRenderSafe(frameIndex)) {
            return;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        int paletteIndex = paletteOverride >= 0 ? paletteOverride : spriteSheet.getPaletteIndex();
        drawFrame(frame, originX, originY, hFlip, vFlip, paletteIndex);
    }

    public void drawFrameIndexWithSatReplayRole(int frameIndex, int originX, int originY,
            boolean hFlip, boolean vFlip, int paletteOverride, SpriteMaskReplayRole satReplayRole) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0
                || !isFrameRenderSafe(frameIndex)) {
            return;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        int paletteIndex = paletteOverride >= 0 ? paletteOverride : spriteSheet.getPaletteIndex();
        GraphicsManager graphicsManager = this.graphicsManager;
        if (graphicsManager.isSpriteSatCollectionActive()) {
            for (int i = 0; i < frame.pieces().size(); i++) {
                SpritePieceRenderer.preparePiece(
                        frame.pieces().get(i),
                        originX,
                        originY,
                        patternBase,
                        paletteIndex,
                        hFlip,
                        vFlip,
                        graphicsManager.getCurrentSpriteHighPriority(),
                        preparedPiece -> graphicsManager.submitSpriteSatPiece(
                                preparedPiece.withMaskReplayRole(satReplayRole)));
            }
            return;
        }
        drawFrame(frame, originX, originY, hFlip, vFlip, paletteIndex);
    }

    public void drawPieces(List<? extends SpriteFramePiece> pieces,
            int originX,
            int originY,
            boolean hFlip,
            boolean vFlip) {
        if (pieces == null || patternBase < 0 || !arePiecesRenderSafe(pieces, -1)) {
            return;
        }
        drawFramePieces(pieces, originX, originY, hFlip, vFlip, spriteSheet.getPaletteIndex());
    }

    /**
     * Draws a single piece from a frame by its index.
     * Useful for fragment objects that need to draw just their portion.
     */
    public void drawFramePieceByIndex(int frameIndex, int pieceIndex, int originX, int originY,
            boolean hFlip, boolean vFlip) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0
                || !isFrameRenderSafe(frameIndex)) {
            return;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        List<? extends SpriteFramePiece> pieces = frame.pieces();
        if (pieceIndex < 0 || pieceIndex >= pieces.size()) {
            return;
        }
        SpriteFramePiece piece = pieces.get(pieceIndex);
        drawSinglePiece(piece, originX, originY, hFlip, vFlip, spriteSheet.getPaletteIndex());
    }

    public void drawPatternIndex(int patternIndex, int drawX, int drawY, int paletteIndex) {
        if (patternBase < 0) {
            return;
        }
        Pattern[] patterns = spriteSheet.getPatterns();
        if (patternIndex < 0 || patternIndex >= patterns.length) {
            return;
        }
        int palette = paletteIndex >= 0 ? paletteIndex : spriteSheet.getPaletteIndex();
        int fullPatternId = patternBase + patternIndex;
        // Build PatternDesc with masked index (for flip/palette flags)
        int descIndex = fullPatternId & 0x7FF;
        descIndex |= (palette & 0x3) << 13;
        reusableDesc.set(descIndex);
        // Use full pattern ID for texture lookup (avoids 11-bit limit)
        GraphicsManager graphicsManager = this.graphicsManager;
        graphicsManager.renderPatternWithId(fullPatternId, reusableDesc, drawX, drawY);
    }

    private void cachePatterns(GraphicsManager graphicsManager, int basePatternIndex) {
        if (graphicsManager == null) {
            return;
        }
        Pattern[] patterns = spriteSheet.getPatterns();
        for (int i = 0; i < patterns.length; i++) {
            graphicsManager.cachePatternTexture(patterns[i], basePatternIndex + i);
        }
    }

    private void drawFrame(SpriteFrame<? extends SpriteFramePiece> frame,
            int originX,
            int originY,
            boolean hFlip,
            boolean vFlip) {
        drawFramePieces(frame.pieces(), originX, originY, hFlip, vFlip, spriteSheet.getPaletteIndex());
    }

    private void drawFrame(SpriteFrame<? extends SpriteFramePiece> frame,
            int originX,
            int originY,
            boolean hFlip,
            boolean vFlip,
            int paletteIndex) {
        drawFramePieces(frame.pieces(), originX, originY, hFlip, vFlip, paletteIndex);
    }

    private void drawSinglePiece(SpriteFramePiece piece,
            int originX,
            int originY,
            boolean hFlip,
            boolean vFlip,
            int paletteIndex) {
        GraphicsManager graphicsManager = this.graphicsManager;
        if (graphicsManager.isSpriteSatCollectionActive()) {
            SpritePieceRenderer.preparePiece(
                    piece,
                    originX,
                    originY,
                    patternBase,
                    paletteIndex,
                    hFlip,
                    vFlip,
                    graphicsManager.getCurrentSpriteHighPriority(),
                    graphicsManager::submitSpriteSatPiece);
            return;
        }
        boolean piecePriority = piece.priority();
        SpritePieceRenderer.renderPiece(
                piece,
                originX,
                originY,
                patternBase,
                paletteIndex,
                hFlip,
                vFlip,
                (patternIdx, pieceHFlip, pieceVFlip, palIdx, drawX, drawY) -> {
                    int descIndex = patternIdx & 0x7FF;
                    if (piecePriority) {
                        descIndex |= 0x8000;
                    }
                    if (pieceHFlip) {
                        descIndex |= 0x800;
                    }
                    if (pieceVFlip) {
                        descIndex |= 0x1000;
                    }
                    descIndex |= (palIdx & 0x3) << 13;
                    reusableDesc.set(descIndex);
                    graphicsManager.renderPatternWithId(patternIdx, reusableDesc, drawX, drawY);
                });
    }

    private void drawFramePieces(List<? extends SpriteFramePiece> pieces,
            int originX,
            int originY,
            boolean hFlip,
            boolean vFlip,
            int paletteIndex) {
        GraphicsManager graphicsManager = this.graphicsManager;
        if (graphicsManager.isSpriteSatCollectionActive()) {
            for (int i = 0; i < pieces.size(); i++) {
                SpritePieceRenderer.preparePiece(
                        pieces.get(i),
                        originX,
                        originY,
                        patternBase,
                        paletteIndex,
                        hFlip,
                        vFlip,
                        graphicsManager.getCurrentSpriteHighPriority(),
                        graphicsManager::submitSpriteSatPiece);
            }
            return;
        }
        // Draw in reverse order (Painter's Algorithm) so that the first piece in the
        // list (index 0)
        // is drawn LAST, appearing on top. This matches Genesis behavior where lower
        // sprite index = higher priority.
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteFramePiece piece = pieces.get(i);
            boolean piecePriority = piece.priority();
            // VDP priority is independent of palette selection; keep the configured palette.
            SpritePieceRenderer.renderPiece(
                    piece,
                    originX,
                    originY,
                    patternBase,
                    paletteIndex,
                    hFlip,
                    vFlip,
                    (patternIdx, pieceHFlip, pieceVFlip, palIdx, drawX, drawY) -> {
                        // Build PatternDesc with masked index (for flip/palette flags)
                        int descIndex = patternIdx & 0x7FF;
                        if (piecePriority) {
                            descIndex |= 0x8000;
                        }
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (palIdx & 0x3) << 13;
                        reusableDesc.set(descIndex);
                        // Use full patternIndex for texture lookup (avoids 11-bit limit)
                        graphicsManager.renderPatternWithId(patternIdx, reusableDesc, drawX, drawY);
                    });
        }
    }

    /**
     * Draws only the pieces of a frame whose VDP priority matches the filter.
     * Used to split mixed-priority frames (e.g., gumball machine body 0x16)
     * across different render buckets.
     *
     * @param priorityFilter true = draw only HIGH priority pieces, false = only LOW
     */
    public void drawFrameIndexFilteredByPriority(int frameIndex, int originX, int originY,
            boolean hFlip, boolean vFlip, int paletteOverride, boolean priorityFilter) {
        drawFrameIndexFilteredByPriority(frameIndex, originX, originY, hFlip, vFlip,
                paletteOverride, priorityFilter, SpriteMaskReplayRole.NORMAL);
    }

    public void drawFrameIndexFilteredByPriority(int frameIndex, int originX, int originY,
            boolean hFlip, boolean vFlip, int paletteOverride, boolean priorityFilter,
            SpriteMaskReplayRole satReplayRole) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0
                || !isFrameRenderSafe(frameIndex)) {
            return;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        int paletteIndex = paletteOverride >= 0 ? paletteOverride : spriteSheet.getPaletteIndex();
        List<? extends SpriteFramePiece> pieces = frame.pieces();
        GraphicsManager graphicsManager = this.graphicsManager;
        if (graphicsManager.isSpriteSatCollectionActive()) {
            for (int i = 0; i < pieces.size(); i++) {
                SpriteFramePiece piece = pieces.get(i);
                if (piece.priority() != priorityFilter) {
                    continue;
                }
                SpritePieceRenderer.preparePiece(
                        piece, originX, originY, patternBase, paletteIndex, hFlip, vFlip,
                        graphicsManager.getCurrentSpriteHighPriority(),
                        preparedPiece -> graphicsManager.submitSpriteSatPiece(
                                preparedPiece.withMaskReplayRole(satReplayRole)));
            }
            return;
        }
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteFramePiece piece = pieces.get(i);
            if (piece.priority() != priorityFilter) {
                continue; // skip pieces that don't match the filter
            }
            boolean piecePriority = piece.priority();
            SpritePieceRenderer.renderPiece(
                    piece, originX, originY, patternBase, paletteIndex, hFlip, vFlip,
                    (patternIdx, pieceHFlip, pieceVFlip, palIdx, drawX, drawY) -> {
                        int descIndex = patternIdx & 0x7FF;
                        if (piecePriority) descIndex |= 0x8000;
                        if (pieceHFlip) descIndex |= 0x800;
                        if (pieceVFlip) descIndex |= 0x1000;
                        descIndex |= (palIdx & 0x3) << 13;
                        reusableDesc.set(descIndex);
                        graphicsManager.renderPatternWithId(patternIdx, reusableDesc, drawX, drawY);
                    });
        }
    }

    /**
     * Draws a frame while forcing every piece to the same VDP priority bit.
     * Used for objects whose ROM composition relies on re-layering a mixed frame.
     */
    public void drawFrameIndexForcedPriority(int frameIndex, int originX, int originY,
            boolean hFlip, boolean vFlip, int paletteOverride, boolean forcedPriority) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount() || patternBase < 0
                || !isFrameRenderSafe(frameIndex)) {
            return;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        int paletteIndex = paletteOverride >= 0 ? paletteOverride : spriteSheet.getPaletteIndex();
        drawFramePiecesForcedPriority(frame.pieces(), originX, originY, hFlip, vFlip, paletteIndex, forcedPriority);
    }

    private void drawFramePiecesForcedPriority(List<? extends SpriteFramePiece> pieces,
            int originX,
            int originY,
            boolean hFlip,
            boolean vFlip,
            int paletteIndex,
            boolean forcedPriority) {
        GraphicsManager graphicsManager = this.graphicsManager;
        if (graphicsManager.isSpriteSatCollectionActive()) {
            for (int i = 0; i < pieces.size(); i++) {
                SpritePieceRenderer.preparePiece(
                        pieces.get(i),
                        originX,
                        originY,
                        patternBase,
                        paletteIndex,
                        hFlip,
                        vFlip,
                        graphicsManager.getCurrentSpriteHighPriority(),
                        preparedPiece -> graphicsManager.submitSpriteSatPiece(
                                preparedPiece.withPriorityFlags(forcedPriority, forcedPriority || graphicsManager.getCurrentSpriteHighPriority())));
            }
            return;
        }
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteFramePiece piece = pieces.get(i);
            SpritePieceRenderer.renderPiece(
                    piece,
                    originX,
                    originY,
                    patternBase,
                    paletteIndex,
                    hFlip,
                    vFlip,
                    (patternIdx, pieceHFlip, pieceVFlip, palIdx, drawX, drawY) -> {
                        int descIndex = patternIdx & 0x7FF;
                        if (forcedPriority) {
                            descIndex |= 0x8000;
                        }
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (palIdx & 0x3) << 13;
                        reusableDesc.set(descIndex);
                        graphicsManager.renderPatternWithId(patternIdx, reusableDesc, drawX, drawY);
                    });
        }
    }

    private boolean isFrameRenderSafe(int frameIndex) {
        byte cached = frameValidationCache[frameIndex];
        if (cached != 0) {
            return cached == 1;
        }
        SpriteFrame<? extends SpriteFramePiece> frame = spriteSheet.getFrame(frameIndex);
        boolean valid = arePiecesRenderSafe(frame != null ? frame.pieces() : null, frameIndex);
        frameValidationCache[frameIndex] = (byte) (valid ? 1 : 2);
        return valid;
    }

    private boolean arePiecesRenderSafe(List<? extends SpriteFramePiece> pieces, int frameIndex) {
        String reason = validatePieces(pieces);
        if (reason == null) {
            return true;
        }
        LOG.warning("Suppressed suspicious sprite mapping frame: "
                + describeSheet(frameIndex)
                + ", patternBase=" + patternBase
                + ", reason=" + reason);
        return false;
    }

    private String validatePieces(List<? extends SpriteFramePiece> pieces) {
        if (pieces == null) {
            return "null piece list";
        }
        if (pieces.size() > MAX_FRAME_PIECES) {
            return "pieceCount=" + pieces.size() + " exceeds " + MAX_FRAME_PIECES;
        }

        int totalTiles = 0;
        boolean first = true;
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;
        Pattern[] patterns = spriteSheet.getPatterns();
        int patternCount = patterns != null ? patterns.length : 0;

        for (int i = 0; i < pieces.size(); i++) {
            SpriteFramePiece piece = pieces.get(i);
            if (piece == null) {
                return "piece " + i + " is null";
            }
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            if (widthTiles < 1 || widthTiles > 4 || heightTiles < 1 || heightTiles > 4) {
                return "piece " + i + " has invalid size " + widthTiles + "x" + heightTiles;
            }
            if (Math.abs(piece.xOffset()) > MAX_ABS_PIECE_OFFSET_PIXELS
                    || Math.abs(piece.yOffset()) > MAX_ABS_PIECE_OFFSET_PIXELS) {
                return "piece " + i + " has extreme offset (" + piece.xOffset() + "," + piece.yOffset() + ")";
            }
            int pieceTiles = widthTiles * heightTiles;
            totalTiles += pieceTiles;
            if (totalTiles > MAX_FRAME_TILES) {
                return "tileCount=" + totalTiles + " exceeds " + MAX_FRAME_TILES;
            }
            int tileIndex = piece.tileIndex();
            if (tileIndex < 0 || tileIndex + pieceTiles > patternCount) {
                return "piece " + i + " references tiles [" + tileIndex + ","
                        + (tileIndex + pieceTiles) + ") outside patternCount=" + patternCount;
            }

            int left = piece.xOffset();
            int top = piece.yOffset();
            int right = left + widthTiles * Pattern.PATTERN_WIDTH - 1;
            int bottom = top + heightTiles * Pattern.PATTERN_HEIGHT - 1;
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
        if (!first) {
            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            if (width > MAX_FRAME_SPAN_PIXELS || height > MAX_FRAME_SPAN_PIXELS) {
                return "bounds span " + width + "x" + height + " exceeds " + MAX_FRAME_SPAN_PIXELS;
            }
        }
        return null;
    }

    private String describeSheet(int frameIndex) {
        return "sheet=" + spriteSheet.getClass().getName()
                + ", frame=" + frameIndex
                + ", frameCount=" + spriteSheet.getFrameCount()
                + ", patternCount=" + (spriteSheet.getPatterns() != null ? spriteSheet.getPatterns().length : 0)
                + ", palette=" + spriteSheet.getPaletteIndex();
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
                    int drawY = piece.yOffset() + (srcY * Pattern.PATTERN_HEIGHT);

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
                    // Y coordinates are now top-based (drawY is top of pattern)
                    if (piece.vFlip()) {
                        // When flipped, row 0 becomes row 7, etc.
                        int flippedMinY = Pattern.PATTERN_HEIGHT - 1 - tileMaxY;
                        int flippedMaxY = Pattern.PATTERN_HEIGHT - 1 - tileMinY;
                        pieceMinY = drawY + flippedMinY;
                        pieceMaxY = drawY + flippedMaxY;
                    } else {
                        pieceMinY = drawY + tileMinY;
                        pieceMaxY = drawY + tileMaxY;
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
                    if (x < minX)
                        minX = x;
                    if (x > maxX)
                        maxX = x;
                    if (y < minY)
                        minY = y;
                    if (y > maxY)
                        maxY = y;
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

    @com.openggf.game.ModApi
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
