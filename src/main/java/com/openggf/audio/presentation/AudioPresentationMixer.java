package com.openggf.audio.presentation;

import com.openggf.audio.AudioDiagnosticObserverException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

public final class AudioPresentationMixer {
    private final int maxStereoFrames;
    private final long[] accumulation;
    private final long[] voiceScratch;
    private final short[] output;
    private final Consumer<PresentationVoice> failedVoice;

    public AudioPresentationMixer(int maxStereoFrames) {
        this(maxStereoFrames, voice -> {
        });
    }

    public AudioPresentationMixer(int maxStereoFrames, Consumer<PresentationVoice> failedVoice) {
        if (maxStereoFrames < 0 || maxStereoFrames > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("maxStereoFrames must fit an interleaved stereo buffer");
        }
        this.maxStereoFrames = maxStereoFrames;
        int samples = maxStereoFrames * 2;
        accumulation = new long[samples];
        voiceScratch = new long[samples];
        output = new short[samples];
        this.failedVoice = Objects.requireNonNull(failedVoice, "failedVoice");
    }

    public short[] mix(PresentationVoiceSource voices, int stereoFrames) {
        return mixInternal(voices, stereoFrames, null, 0, false);
    }

    /**
     * Seeds the mix with the session's sole physical packet and then mixes
     * only PCM voices. {@code initialOffsetSamples} is an interleaved-sample
     * offset into the session packet.
     */
    public short[] mixPcmVoices(
            PresentationVoiceSource voices,
            int stereoFrames,
            short[] initialPcm,
            int initialOffsetSamples) {
        return mixInternal(voices, stereoFrames,
                Objects.requireNonNull(initialPcm, "initialPcm"),
                initialOffsetSamples, true);
    }

    private short[] mixInternal(
            PresentationVoiceSource voices,
            int stereoFrames,
            short[] initialPcm,
            int initialOffsetSamples,
            boolean pcmOnly) {
        if (stereoFrames < 0 || stereoFrames > maxStereoFrames) {
            throw new IllegalArgumentException("stereoFrames outside declared capacity");
        }
        Objects.requireNonNull(voices, "voices");
        int samples = stereoFrames * 2;
        if (initialPcm == null) {
            Arrays.fill(accumulation, 0, samples, 0L);
        } else {
            if (initialOffsetSamples < 0
                    || initialOffsetSamples + (long) samples
                            > initialPcm.length) {
                throw new IllegalArgumentException(
                        "initial PCM does not contain the requested range");
            }
            for (int sample = 0; sample < samples; sample++) {
                accumulation[sample] =
                        initialPcm[initialOffsetSamples + sample];
            }
        }

        int voiceCount = voices.orderedVoiceCount();
        for (int voiceIndex = 0; voiceIndex < voiceCount; voiceIndex++) {
            PresentationVoice voice = voices.orderedVoiceAt(voiceIndex);
            if (pcmOnly && !(voice instanceof PcmPresentationVoice)) {
                continue;
            }
            Arrays.fill(voiceScratch, 0, samples, 0L);
            try {
                voice.mixInto(voiceScratch, stereoFrames);
            } catch (RuntimeException failure) {
                AudioDiagnosticObserverException.rethrowIfPresent(failure);
                failedVoice.accept(voice);
                continue;
            }
            for (int sample = 0; sample < samples; sample++) {
                accumulation[sample] += voiceScratch[sample];
            }
        }

        for (int sample = 0; sample < samples; sample++) {
            output[sample] = saturate(accumulation[sample]);
        }
        return output;
    }

    public int maxStereoFrames() {
        return maxStereoFrames;
    }

    private static short saturate(long sample) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
    }
}
