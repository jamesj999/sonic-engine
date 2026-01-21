package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Shared utility methods for spring objects (Obj40, Obj41, Obj7B).
 */
public final class SpringHelper {

    private SpringHelper() {
        // Utility class - no instantiation
    }

    /**
     * Sets the player's collision layer based on spring subtype bits 2-3.
     * <p>
     * ROM Reference: s2.asm lines 33734-33745 (Obj41), 51919-51931 (Obj40), 55981-55991 (Obj7B)
     * <p>
     * Subtype bits 2-3 (mask 0x0C):
     * <ul>
     *   <li>0x04 - Switch to primary collision layer (bits 0x0C/0x0D)</li>
     *   <li>0x08 - Switch to secondary collision layer (bits 0x0E/0x0F)</li>
     *   <li>0x00 or 0x0C - No change (keep current layer)</li>
     * </ul>
     *
     * @param player  the player sprite to modify
     * @param subtype the spring's subtype byte
     */
    public static void applyCollisionLayerBits(AbstractPlayableSprite player, int subtype) {
        int layerBits = subtype & 0x0C;
        if (layerBits == 0x04) {
            // Primary collision layer
            player.setTopSolidBit((byte) 0x0C);
            player.setLrbSolidBit((byte) 0x0D);
        } else if (layerBits == 0x08) {
            // Secondary collision layer
            player.setTopSolidBit((byte) 0x0E);
            player.setLrbSolidBit((byte) 0x0F);
        }
        // Note: If layerBits == 0x00 or 0x0C, don't change (keep current layer)
    }
}
