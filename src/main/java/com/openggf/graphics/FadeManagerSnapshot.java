package com.openggf.graphics;

/** Immutable rewind capture of {@link FadeManager} state. */
public record FadeManagerSnapshot(
        FadeManager.FadeState state,
        int frameCount,
        float fadeR,
        float fadeG,
        float fadeB,
        float fadeAlpha,
        FadeManager.FadeType fadeType,
        int holdDuration,
        int holdFrameCount,
        int effectiveFPC,
        float effectiveIncrement,
        int effectiveDuration,
        boolean exactToFadeDuration,
        boolean hadPendingCompletion) {

    /** True when restoring this capture would orphan a live fade callback. */
    public boolean isPoisoned() {
        return state != FadeManager.FadeState.NONE && hadPendingCompletion;
    }
}
