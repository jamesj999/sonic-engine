package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

/**
 * Interface for solid objects that consist of multiple collision pieces.
 * Each piece has its own position and collision box, but they are all
 * part of the same logical object (e.g., CPZ Staircase with 4 platforms).
 *
 * The default SolidObjectProvider methods (getSolidParams, isTopSolidOnly, etc.)
 * apply to ALL pieces uniformly. Use the piece-specific methods for individual
 * piece positions and optional per-piece callbacks.
 */
public interface MultiPieceSolidProvider extends SolidObjectProvider {

    /**
     * Returns the number of collision pieces this object has.
     */
    int getPieceCount();

    /**
     * Returns the world X position of the specified piece.
     * @param pieceIndex 0-based index of the piece
     */
    int getPieceX(int pieceIndex);

    /**
     * Returns the world Y position of the specified piece.
     * @param pieceIndex 0-based index of the piece
     */
    int getPieceY(int pieceIndex);

    /**
     * X anchor used by a piece's fresh-contact {@code SolidObject} geometry.
     *
     * <p>Folded providers normally publish their current piece position. A
     * provider that represents a later native SST child can retain that child's
     * slot-phase X here without changing the current position used for riding,
     * rendering, or touch regions.
     */
    default int getPieceFreshContactX(int pieceIndex, PlayableEntity player) {
        return getPieceX(pieceIndex);
    }

    /** Fresh-contact Y counterpart to {@link #getPieceFreshContactX(int, PlayableEntity)}. */
    default int getPieceFreshContactY(int pieceIndex, PlayableEntity player) {
        return getPieceY(pieceIndex);
    }

    /**
     * Returns collision parameters for the specified piece.
     * Default implementation returns the same params for all pieces.
     * Override if pieces have different sizes.
     *
     * @param pieceIndex 0-based index of the piece
     */
    default SolidObjectParams getPieceParams(int pieceIndex) {
        return getSolidParams();
    }

    /**
     * Whether sibling pieces before the currently ridden piece should resolve
     * contact before the continued-riding exit/carry branch.
     * <p>
     * Most aggregate objects are native engine groupings and keep the historical
     * "ride first, siblings after" order. Objects that compress several ROM SST
     * slots into one engine instance can opt in so earlier ROM slots can apply
     * side/push contact before a later slot runs its standing-bit exit check.
     */
    default boolean resolvesEarlierPiecesBeforeRidingPiece() {
        return false;
    }

    /**
     * Whether each piece owns an independent ROM standing bit.
     * <p>
     * Most engine multi-piece providers are aggregate collision shapes and keep
     * one latch for the logical object. Providers that compress separate ROM
     * child slots into one instance can opt in so a standing bit set by one
     * child does not suppress another child's fresh SolidObject contact path.
     */
    default boolean usesPieceScopedStandingBits() {
        return false;
    }

    /**
     * Half-width of a piece's narrow Solid_Landed top-landing window, used to
     * decide which piece owns the player's standing when several pieces' wider
     * collision boxes overlap. ROM Solid_Landed gates the landing/standonobject
     * assignment on {@code obActWid} (docs/s1disasm/_incObj/sub SolidObject.asm:307-315),
     * which for spaced multi-piece objects is half the piece spacing — narrower
     * than the full collision half-width used for side/push contact. Defaults to
     * the full collision half-width (single-box objects have no distinction).
     *
     * @param pieceIndex 0-based index of the piece
     */
    default int getPieceLandingHalfWidth(int pieceIndex) {
        return getPieceParams(pieceIndex).halfWidth();
    }

    /**
     * Whether {@link #getPieceLandingHalfWidth(int)} publishes the native
     * per-piece top width directly, rather than relying on the shared
     * {@code collisionHalfWidth - $B} SolidObject heuristic.
     */
    default boolean usesPieceSpecificLandingHalfWidths() {
        return false;
    }

    /**
     * Called when a piece makes contact with the player.
     * Allows the object to track which pieces are being touched.
     *
     * @param pieceIndex the piece that made contact
     * @param player the player sprite
     * @param contact the contact details (standing, side, ceiling, etc.)
     * @param frameCounter current frame number
     */
    default void onPieceContact(int pieceIndex, PlayableEntity player,
                                SolidContact contact, int frameCounter) {
        // Default no-op - objects can override to track piece-specific contact
    }

    /**
     * Piece-contact callback with the native child SST's standing-bit state at
     * routine entry. Folded child objects can use this when their post-contact
     * behavior distinguishes a continued ride from a fresh landing.
     */
    default void onPieceContact(int pieceIndex, PlayableEntity player,
                                SolidContact contact, int frameCounter,
                                boolean standingBitWasSetAtEntry) {
        onPieceContact(pieceIndex, player, contact, frameCounter);
    }
}
