package com.openggf.level.objects;

/**
 * Marker for player-bound shield visuals recreated by the post-restore player
 * refresh rather than by directly constructing an object during the dynamic
 * object restore pass.
 */
public interface PlayerBoundShieldRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ctx.enqueuePendingPlayerBoundEntry(ShieldObjectInstance.class);
        return null;
    }
}
