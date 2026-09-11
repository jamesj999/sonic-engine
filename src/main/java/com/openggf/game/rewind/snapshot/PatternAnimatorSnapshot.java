package com.openggf.game.rewind.snapshot;

/**
 * Snapshot of a {@link com.openggf.level.animation.AnimatedPatternManager}'s
 * playback counter state.
 *
 * <p>{@code scriptCounters} — one entry per AniPLC script in registration order;
 * each entry holds the script's {@code timer} and {@code frameIndex}. Empty when
 * the manager has no AniPLC scripts (e.g. Sonic 1).
 *
 * <p>{@code handlerCounters} — one entry per opaque animation handler (used by
 * Sonic 1's inner-class handlers); each entry is a two-element array
 * {@code [timer, frameCounter]}. Empty when the manager is script-based (S2/S3K).
 *
 * <p>{@code extra} — optional opaque blob for manager-specific scalar state
 * (e.g. Sonic 3&K pachinko phase, HCZ waterline deltas). {@code null} when unused.
 */
public record PatternAnimatorSnapshot(
        ScriptCounter[] scriptCounters,
        HandlerCounter[] handlerCounters,
        byte[] extra
) {
    /** Per-AniPLC-script playback position. */
    public record ScriptCounter(int timer, int frameIndex) {}

    /**
     * Per-handler counter tuple: up to three opaque int slots.
     * Handlers that need fewer slots leave extras at 0; handlers that
     * need more pack additional data into slot2 via bit-packing.
     */
    public record HandlerCounter(int slot0, int slot1, int slot2) {}
}
