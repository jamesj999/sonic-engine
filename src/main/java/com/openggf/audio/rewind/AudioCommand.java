package com.openggf.audio.rewind;

public sealed interface AudioCommand {
    enum MusicRoute {
        BASE_SMPS,
        DONOR_SMPS,
        FALLBACK_WAV,
        SYSTEM_COMMAND
    }

    enum SfxRoute {
        BASE_SMPS_ID,
        BASE_SMPS_NAME,
        DONOR_SMPS,
        FALLBACK_NAME,
        RING_RESOLVED
    }

    enum RestoreCause {
        EXPLICIT,
        SMPS_FADE_IN_COMMAND
    }

    record PlayMusic(int musicId, MusicRoute route, boolean override,
                     String donorGameId) implements AudioCommand {}

    record PlaySfx(int sfxId, String sfxName, SfxRoute route, float pitch,
                   String donorGameId) implements AudioCommand {}

    record FadeOutMusic(int steps, int delay) implements AudioCommand {}

    record StopMusic() implements AudioCommand {}

    record StopAllSfx() implements AudioCommand {}

    record StopSmpsSfx(int sourceCommandId) implements AudioCommand {}

    record SilencePsg(int sourceCommandId) implements AudioCommand {}

    record RetainGlobalStop(int sourceCommandId) implements AudioCommand {}

    record PlaySegaPcm(
            int sourceCommandId, byte[] pcm, int sourceRate)
            implements AudioCommand {
        public PlaySegaPcm {
            pcm = pcm.clone();
            if (sourceRate <= 0) {
                throw new IllegalArgumentException(
                        "sourceRate must be positive");
            }
        }

        @Override
        public byte[] pcm() {
            return pcm.clone();
        }
    }

    record StopRawPcm() implements AudioCommand {}

    record StopSegaPcmAndRetainGlobalStop(int sourceCommandId)
            implements AudioCommand {}

    record ReferenceLimitation(int sourceCommandId, String reason)
            implements AudioCommand {
        public ReferenceLimitation {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "reference limitation reason is required");
            }
        }
    }

    record EndMusicOverride(int musicId) implements AudioCommand {}

    record RestoreMusic(RestoreCause cause) implements AudioCommand {}

    record SetSpeedShoes(boolean enabled) implements AudioCommand {}

    record SetSpeedMultiplier(int multiplier) implements AudioCommand {}

    record ChangeMusicTempo(int dividingTiming) implements AudioCommand {}

    record ResetRingAlternation(boolean ringLeft) implements AudioCommand {}
}
