package com.openggf.audio.presentation;

import java.util.Objects;

public record ResolvedSmpsSfxSource(
        long standaloneVoiceId,
        SmpsAssetKey assetKey,
        long dependencyGeneration,
        int pitchQ16,
        int priority,
        int continuousSfxId,
        int trackCount,
        int maxStereoFrames,
        int resolvedSoundId,
        boolean specialSfx) {

    public ResolvedSmpsSfxSource(
            long standaloneVoiceId,
            SmpsAssetKey assetKey,
            int pitchQ16,
            int priority,
            int continuousSfxId,
            int trackCount,
            int maxStereoFrames) {
        this(standaloneVoiceId, assetKey, 0, pitchQ16, priority,
                continuousSfxId, trackCount, maxStereoFrames,
                Objects.requireNonNull(assetKey, "assetKey").sfxId(), false);
    }

    public ResolvedSmpsSfxSource {
        Objects.requireNonNull(assetKey, "assetKey");
        if (dependencyGeneration < 0) {
            throw new IllegalArgumentException(
                    "dependencyGeneration must be non-negative");
        }
        if (trackCount < 0) {
            throw new IllegalArgumentException("trackCount must be non-negative");
        }
        if (maxStereoFrames < 0) {
            throw new IllegalArgumentException("maxStereoFrames must be non-negative");
        }
    }

}
