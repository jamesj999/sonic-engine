package com.openggf.audio.rewind;

public enum AudioPresentationPolicy {
    SUPPRESSED_INTERNAL_RESTORE,
    /**
     * Stops transient SFX from the reverse-presentation window and force-pops
     * the music override stack. Reserved for cleanup paths that did NOT land
     * a committed logical restore first (e.g. a level/act boundary, where the
     * new level's own init already established fresh audio state and any
     * pending held-rewind restore was intentionally dropped rather than
     * committed) — the pop is what discards a stale pre-boundary override.
     * Using this after a committed {@code commitDeferredAudioRestore()} would
     * incorrectly end an override the just-restored logical state says should
     * still be active (e.g. invincibility music cut short mid-duration).
     */
    STOP_TRANSIENT_SFX_RESYNC_MUSIC,
    /**
     * Stops transient SFX from the reverse-presentation window only. Use
     * after a cleanup path that already landed a committed logical restore
     * (via {@code commitDeferredAudioRestore()}) — that restore already
     * rebuilt the correct music/override state for the committed frame, so
     * forcing an additional music-stack pop here would end an override that
     * is legitimately still active.
     */
    STOP_TRANSIENT_SFX,
    STOP_ALL_PRESENTATION
}
