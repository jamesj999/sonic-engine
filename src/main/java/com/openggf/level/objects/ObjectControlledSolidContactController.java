package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

/**
 * Narrow seam for carry states that need selected solid callbacks while the player is
 * otherwise object-controlled.
 */
public interface ObjectControlledSolidContactController {
    boolean allowsObjectControlledSolidContact(PlayableEntity player, ObjectInstance candidate);

    /**
     * Receives a permitted solid checkpoint in native object-slot order.
     * Controllers that consume shared player contact flags can use this hook
     * before their own later slot executes.
     */
    default void onObjectControlledSolidContact(
            PlayableEntity player, ObjectInstance candidate, SolidContact contact) {
    }

    /**
     * Pending native X step that an earlier solid slot should use for entry
     * geometry while this controller owns the player's movement.
     * Returning {@code null} keeps ordinary grounded movement projection.
     */
    default Short projectedSolidContactXSpeed(
            PlayableEntity player, ObjectInstance candidate) {
        return null;
    }

    /** Cancels deferred feedback when the contacted solid removes itself. */
    default void onObjectControlledSolidContactInvalidated(
            PlayableEntity player, ObjectInstance candidate) {
    }

    /**
     * Returns whether this controller currently owns {@code player} through an
     * active carry state. The rewind post-restore pass uses this to relink the
     * player's carry-owner reference to the restored controller instance (the
     * live reference is not part of any snapshot and goes stale when the
     * restore recreates the owning object).
     */
    default boolean ownsCarriedPlayerForRewind(PlayableEntity player) {
        return false;
    }
}
