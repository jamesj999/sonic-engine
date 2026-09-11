package com.openggf.audio.presentation;

import com.openggf.audio.rewind.AudioSourceDescriptor;

import java.util.Objects;

/**
 * A deterministic, decoded PCM presentation voice with a Q32.32 source cursor.
 */
public final class SampleBackedVoice implements PcmPresentationVoice {
    private static final long Q32_ONE = 1L << 32;
    private static final int YM_DAC_GAIN_Q16 = 1 << 14;

    private final long voiceId;
    private final int priority;
    private final DecodedPcm pcm;
    private Integer musicId;
    private AudioSourceDescriptor sourceDescriptor;
    private long sourceStepQ32;
    private int gainQ16;
    private boolean looping;
    private final long endPositionQ32;
    private long sourcePositionQ32;
    private boolean stopped;

    public SampleBackedVoice(long voiceId, int priority, DecodedPcm pcm, int outputSampleRate,
                             double pitch, int gainQ16, boolean looping) {
        this(voiceId, priority, pcm, null, null, 0L,
                calculateSourceStepQ32(pcm, outputSampleRate, pitch), gainQ16, looping, false);
    }

    private SampleBackedVoice(long voiceId, int priority, DecodedPcm pcm, Integer musicId,
                              AudioSourceDescriptor sourceDescriptor, long sourcePositionQ32,
                              long sourceStepQ32, int gainQ16, boolean looping, boolean stopped) {
        this.voiceId = voiceId;
        this.priority = priority;
        this.pcm = Objects.requireNonNull(pcm, "pcm");
        this.musicId = musicId;
        this.sourceDescriptor = sourceDescriptor;
        this.endPositionQ32 = ((long) pcm.sourceFrames()) << 32;
        if (sourceStepQ32 <= 0) {
            throw new IllegalArgumentException("sourceStepQ32 must be positive");
        }
        this.sourcePositionQ32 = normalizeSourcePosition(sourcePositionQ32, looping);
        this.sourceStepQ32 = sourceStepQ32;
        this.gainQ16 = gainQ16;
        this.looping = looping;
        this.stopped = stopped;
    }

    public static SampleBackedVoice rawSegaPcm(long voiceId, int priority, DecodedPcm pcm,
                                                int outputSampleRate) {
        return oneShot(voiceId, priority, pcm, outputSampleRate, 1.0f,
                YM_DAC_GAIN_Q16 / (float) (1 << 16));
    }

    public static SampleBackedVoice oneShot(long id, int priority, DecodedPcm pcm, int outputRate,
                                             float pitch, float gain) {
        return new SampleBackedVoice(id, priority, pcm, outputRate, pitch, toGainQ16(gain), false);
    }

    public static SampleBackedVoice loopingMusic(long id, DecodedPcm pcm, int outputRate, float gain) {
        return new SampleBackedVoice(id, 0, pcm, outputRate, 1.0f, toGainQ16(gain), true);
    }

    public static SampleBackedVoice unsigned8Mono(long id, int priority, String assetId, byte[] source,
                                                   int sourceRate, int outputRate, float gain) {
        Objects.requireNonNull(source, "source");
        short[] samples = new short[source.length];
        for (int index = 0; index < source.length; index++) {
            samples[index] = (short) (((source[index] & 0xFF) - 128) << 8);
        }
        return oneShot(id, priority, new DecodedPcm(assetId, 1, sourceRate, samples), outputRate, 1.0f, gain);
    }

    public static SampleBackedVoice restore(PresentationVoiceSnapshot.Sample snapshot, DecodedPcm pcm) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.assetId().equals(Objects.requireNonNull(pcm, "pcm").assetId())) {
            throw new IllegalArgumentException("snapshot assetId does not match PCM");
        }
        return new SampleBackedVoice(snapshot.voiceId(), snapshot.priority(), pcm, snapshot.musicId(),
                snapshot.sourceDescriptor(), snapshot.sourcePositionQ32(), snapshot.sourceStepQ32(),
                snapshot.gainQ16(), snapshot.looping(), snapshot.stopped());
    }

    public static SampleBackedVoice restore(PresentationVoiceSnapshot.Sample snapshot, DecodedPcmCache cache) {
        Objects.requireNonNull(snapshot, "snapshot");
        DecodedPcm pcm = Objects.requireNonNull(cache, "cache").get(snapshot.assetId());
        if (pcm == null) {
            throw new IllegalArgumentException("no cached PCM for " + snapshot.assetId());
        }
        return restore(snapshot, pcm);
    }

    public void restore(PresentationVoiceSnapshot.Sample snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.voiceId() != voiceId || snapshot.priority() != priority
                || !snapshot.assetId().equals(pcm.assetId())) {
            throw new IllegalArgumentException("snapshot identity does not match voice");
        }
        if (snapshot.sourceStepQ32() <= 0) {
            throw new IllegalArgumentException("sourceStepQ32 must be positive");
        }
        this.musicId = snapshot.musicId();
        this.sourceDescriptor = snapshot.sourceDescriptor();
        this.sourcePositionQ32 = normalizeSourcePosition(snapshot.sourcePositionQ32(), snapshot.looping());
        this.sourceStepQ32 = snapshot.sourceStepQ32();
        this.gainQ16 = snapshot.gainQ16();
        this.looping = snapshot.looping();
        this.stopped = snapshot.stopped();
    }

    public void setPitch(float pitch, int outputRate) {
        sourceStepQ32 = calculateSourceStepQ32(pcm, outputRate, pitch);
    }

    public void setGain(float gain) {
        gainQ16 = toGainQ16(gain);
    }

    @Override
    public long voiceId() {
        return voiceId;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public void mixInto(long[] accumulation, int stereoFrames) {
        Objects.requireNonNull(accumulation, "accumulation");
        if (stereoFrames < 0 || accumulation.length < (long) stereoFrames * 2) {
            throw new IllegalArgumentException("accumulation cannot hold requested stereo frames");
        }
        for (int frame = 0; frame < stereoFrames && !isComplete(); frame++) {
            int sourceFrame = (int) (sourcePositionQ32 >>> 32);
            long fraction = sourcePositionQ32 & 0xFFFF_FFFFL;
            int nextFrame = sourceFrame + 1;
            if (nextFrame >= pcm.sourceFrames()) {
                nextFrame = looping ? 0 : sourceFrame;
            }
            int left = interpolate(sourceFrame, nextFrame, 0, fraction);
            int right = pcm.channels() == 1 ? left : interpolate(sourceFrame, nextFrame, 1, fraction);
            int outputIndex = frame * 2;
            accumulation[outputIndex] += ((long) left * gainQ16) >> 16;
            accumulation[outputIndex + 1] += ((long) right * gainQ16) >> 16;
            advance();
        }
    }

    @Override
    public boolean isComplete() {
        return stopped || endPositionQ32 == 0 || (!looping && sourcePositionQ32 >= endPositionQ32);
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public PresentationVoiceSnapshot snapshot() {
        return new PresentationVoiceSnapshot.Sample(voiceId, priority, pcm.assetId(), musicId, sourceDescriptor,
                sourcePositionQ32, sourceStepQ32, gainQ16, looping, stopped);
    }

    private int interpolate(int sourceFrame, int nextFrame, int channel, long fraction) {
        int left = pcm.sample(sourceFrame, channel);
        int right = pcm.sample(nextFrame, channel);
        return left + (int) (((long) (right - left) * fraction) >> 32);
    }

    private void advance() {
        if (looping) {
            if (endPositionQ32 == 0) {
                return;
            }
            long advance = sourceStepQ32 % endPositionQ32;
            long remaining = endPositionQ32 - sourcePositionQ32;
            sourcePositionQ32 = advance >= remaining ? advance - remaining : sourcePositionQ32 + advance;
            return;
        }
        long remaining = endPositionQ32 - sourcePositionQ32;
        if (sourceStepQ32 >= remaining) {
            sourcePositionQ32 = endPositionQ32;
        } else {
            sourcePositionQ32 += sourceStepQ32;
        }
    }

    private static long calculateSourceStepQ32(DecodedPcm pcm, int outputSampleRate, double pitch) {
        Objects.requireNonNull(pcm, "pcm");
        if (outputSampleRate <= 0) {
            throw new IllegalArgumentException("outputSampleRate must be positive");
        }
        if (!Double.isFinite(pitch) || pitch <= 0.0) {
            throw new IllegalArgumentException("pitch must be finite and positive");
        }
        double step = (pcm.sampleRate() * pitch / outputSampleRate) * Q32_ONE;
        if (step > Long.MAX_VALUE) {
            throw new IllegalArgumentException("source step is too large");
        }
        return Math.max(1L, Math.round(step));
    }

    private long normalizeSourcePosition(long sourcePosition, boolean loopingSnapshot) {
        if (sourcePosition < 0 || sourcePosition > endPositionQ32) {
            throw new IllegalArgumentException("sourcePositionQ32 is outside the sample");
        }
        return loopingSnapshot && endPositionQ32 > 0 && sourcePosition == endPositionQ32 ? 0L : sourcePosition;
    }

    private static int toGainQ16(float gain) {
        if (!Float.isFinite(gain) || gain < 0.0f) {
            throw new IllegalArgumentException("gain must be finite and non-negative");
        }
        double scaled = gain * (1 << 16);
        if (scaled > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("gain is too large");
        }
        return (int) Math.round(scaled);
    }
}
