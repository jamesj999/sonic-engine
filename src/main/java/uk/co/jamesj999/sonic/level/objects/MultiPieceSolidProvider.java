package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
     * Called when a piece makes contact with the player.
     * Allows the object to track which pieces are being touched.
     *
     * @param pieceIndex the piece that made contact
     * @param player the player sprite
     * @param contact the contact details (standing, side, ceiling, etc.)
     * @param frameCounter current frame number
     */
    default void onPieceContact(int pieceIndex, AbstractPlayableSprite player,
                                SolidContact contact, int frameCounter) {
        // Default no-op - objects can override to track piece-specific contact
    }
}
