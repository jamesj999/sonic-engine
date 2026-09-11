package com.openggf.level.objects;

/**
 * Marker for player-bound invincibility-star visuals recreated by the
 * post-restore player refresh rather than by directly constructing an object
 * during the dynamic object restore pass.
 */
public interface PlayerBoundInvincibilityStarsRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ctx.enqueuePendingPlayerBoundEntry(InvincibilityStarsObjectInstance.class);
        return null;
    }
}
