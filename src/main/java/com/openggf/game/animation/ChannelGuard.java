package com.openggf.game.animation;

/** Predicate deciding whether an animated tile channel should run this frame. */
@FunctionalInterface
public interface ChannelGuard {
    /** Returns {@code true} when the channel is active for the current runtime state. */
    boolean allows();
}
