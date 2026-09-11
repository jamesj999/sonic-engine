package com.openggf.timer;

/**
 * A timer whose countdown belongs to a playable character's display step rather
 * than to the level loop's pre-physics timer pass.
 *
 * <p>The ROM runs these countdowns from {@code Sonic_Display}, which the
 * player's control routine calls after it has dispatched the movement modes:
 * {@code jsr Sonic_Modes} then {@code bsr.s Sonic_Display}
 * ({@code docs/s1disasm/_incObj/01 Sonic.asm:76,80}), {@code jsr Obj01_Modes}
 * then {@code bsr.s Sonic_Display} ({@code docs/s2disasm/s2.asm:36242,36248}),
 * and {@code jsr Sonic_Modes} then {@code bsr.s Sonic_Display}
 * ({@code docs/skdisasm/sonic3k.asm:22021,22031}). Every consequence of the
 * countdown reaching zero — the physics restore and the sound-queue write
 * alike — happens there, in that one frame.
 *
 * <p>{@link TimerManager#update()} therefore skips these timers, and the
 * owning character ticks them from its own display step through
 * {@link TimerManager#updateDisplayPhaseTimersFor(Object)}.
 */
public interface DisplayPhaseTimer {

    /**
     * The character whose display step owns this countdown. Only that
     * character's display step ticks it.
     */
    Object displayPhaseOwner();
}
