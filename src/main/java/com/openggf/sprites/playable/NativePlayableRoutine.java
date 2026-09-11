package com.openggf.sprites.playable;

/** Native S3K player primary-routine contract used by object/event gates. */
public enum NativePlayableRoutine {
    INIT(0x00), CONTROL(0x02), HURT(0x04), DEAD(0x06), RESTART(0x08), DROWN(0x0A), EXIT(0x0C),
    /** DebugMode bypasses the native primary-routine dispatcher entirely. */
    BYPASSED_BY_DEBUG_MODE(-1);

    private final int byteValue;
    NativePlayableRoutine(int byteValue) { this.byteValue = byteValue; }
    public int byteValue() { return byteValue; }

    /** Resolves the semantic equivalent of S3K's primary {@code routine(a0)} byte. */
    public static NativePlayableRoutine resolve(AbstractPlayableSprite player) {
        if (player.isDebugMode()) return BYPASSED_BY_DEBUG_MODE;
        if (player.isDrowningPreDeath()) return DROWN;
        if (player.getDead()) return DEAD;
        if (player.isHurt()) return HURT;
        return CONTROL;
    }
}
