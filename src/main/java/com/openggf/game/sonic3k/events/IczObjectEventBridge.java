package com.openggf.game.sonic3k.events;

/**
 * Narrow bridge for ICZ object code that needs to mutate level-event state.
 */
public interface IczObjectEventBridge {
    /** ROM: {@code move.w #frames,(Screen_shake_flag).w} — start a timed shake. */
    void triggerScreenShake(int frames);
}
