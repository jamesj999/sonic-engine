package com.openggf.audio;

/** Immutable SFX classification captured with a registered SMPS playback. */
public record SmpsSfxPlaybackPolicy(
        int priority,
        boolean special,
        boolean continuous) {

    public static SmpsSfxPlaybackPolicy defaults(boolean special) {
        return new SmpsSfxPlaybackPolicy(0x70, special, false);
    }
}
