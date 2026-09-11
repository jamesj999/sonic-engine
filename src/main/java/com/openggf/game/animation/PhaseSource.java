package com.openggf.game.animation;

/**
 * Computes the logical animation phase for a channel.
 *
 * <p>The phase value is used both by strategies and by
 * {@link AnimatedTileCachePolicy#ON_PHASE_CHANGE} to skip redundant writes.
 */
@FunctionalInterface
public interface PhaseSource {
    /** Resolves the current phase for the supplied channel context. */
    int resolve(ChannelContext context);
}
