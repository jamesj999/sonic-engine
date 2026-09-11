package com.openggf.audio.presentation;

import java.util.Locale;
import java.util.Map;

/**
 * Small deterministic presentation sounds for host UI that runs before a game
 * ROM is selected. The samples are synthesized here instead of being loaded
 * from a game profile or a copyrighted runtime asset.
 */
final class HostUiSfx {
    private static final int SAMPLE_RATE = 48_000;
    private static final String ASSET_PREFIX = "host/ui/";
    private static final double TWO_PI = Math.PI * 2.0;
    private static final Map<String, DecodedPcm> CUES = Map.of(
            "UI_NAVIGATE", tone("UI_NAVIGATE", new Note(760, 45)),
            "UI_CONFIRM", tone("UI_CONFIRM", new Note(660, 55), new Note(990, 75)),
            "UI_ERROR", tone("UI_ERROR", new Note(440, 75), new Note(290, 95)));

    private HostUiSfx() {
    }

    static DecodedPcm forCue(String cue) {
        if (cue == null) {
            return null;
        }
        return CUES.get(cue);
    }

    static DecodedPcm forAsset(String assetId) {
        if (assetId == null || !assetId.startsWith(ASSET_PREFIX)) {
            return null;
        }
        String cue = assetId.substring(ASSET_PREFIX.length())
                .toUpperCase(Locale.ROOT);
        return CUES.get(cue);
    }

    private static DecodedPcm tone(String cue, Note... notes) {
        int frameCount = 0;
        for (Note note : notes) {
            frameCount += Math.round(SAMPLE_RATE * note.durationMillis() / 1_000f);
        }
        short[] samples = new short[frameCount];
        int frame = 0;
        for (Note note : notes) {
            int noteFrames = Math.round(SAMPLE_RATE * note.durationMillis() / 1_000f);
            int attackFrames = Math.max(1, Math.round(noteFrames * 0.08f));
            int releaseFrames = Math.max(1, Math.round(noteFrames * 0.16f));
            for (int noteFrame = 0; noteFrame < noteFrames; noteFrame++) {
                double attack = Math.min(1.0, (noteFrame + 1.0) / attackFrames);
                double release = Math.min(1.0,
                        (noteFrames - noteFrame) / (double) releaseFrames);
                double envelope = Math.min(attack, release);
                double value = Math.sin(TWO_PI * note.frequencyHz() * noteFrame
                        / SAMPLE_RATE) * 0.28 * envelope;
                samples[frame++] = (short) Math.round(value * Short.MAX_VALUE);
            }
        }
        return new DecodedPcm(ASSET_PREFIX + cue.toLowerCase(Locale.ROOT),
                1, SAMPLE_RATE, samples);
    }

    private record Note(int frequencyHz, int durationMillis) {
    }
}
