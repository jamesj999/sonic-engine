package com.openggf.game.animation;

/**
 * Writes one channel's resolved frame output into the live level state.
 *
 * <p>Strategies handle the concrete transfer mechanics, such as copying script
 * frames, splitting uploads across multiple destinations, or composing multiple
 * pattern sources into one destination range.
 */
@FunctionalInterface
public interface ApplyStrategy {
    /** Applies the channel's current output using the supplied frame context. */
    void apply(ChannelContext context);
}
