package com.openggf.game.animation;

/** Cache policy controlling when a channel reapplies its tile output. */
public enum AnimatedTileCachePolicy {
    /** Apply on every graph update, even if the resolved phase is unchanged. */
    ALWAYS,
    /** Apply only when the resolved phase differs from the previous frame. */
    ON_PHASE_CHANGE
}
