package com.openggf.audio.presentation;

import com.openggf.audio.ChannelType;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.session.PreparedSmpsMusicActivation;
import com.openggf.audio.session.PreparedSmpsSfxProgram;

import java.util.Objects;

/**
 * Immutable, fully resolved mutations applied at an audio frame boundary.
 */
public sealed interface AudioPresentationCommand
        permits AudioPresentationCommand.ReplaceMusic,
        AudioPresentationCommand.PushMusicOverride,
        AudioPresentationCommand.RestoreMusicOverride,
        AudioPresentationCommand.EndMusicOverride,
        AudioPresentationCommand.AddSmpsSfx,
        AudioPresentationCommand.StartSampleSfx,
        AudioPresentationCommand.ReplaceRawPcm,
        AudioPresentationCommand.StopRawPcm,
        AudioPresentationCommand.StopRawPcmAndRetainGlobalStop,
        AudioPresentationCommand.RetainGlobalStop,
        AudioPresentationCommand.StopMusic,
        AudioPresentationCommand.StopAllSfx,
        AudioPresentationCommand.StopSmpsSfx,
        AudioPresentationCommand.SilencePsg,
        AudioPresentationCommand.ReferenceLimitation,
        AudioPresentationCommand.FadeMusic,
        AudioPresentationCommand.SetVoiceGain,
        AudioPresentationCommand.SetVoicePitch,
        AudioPresentationCommand.SetSpeedShoes,
        AudioPresentationCommand.SetSpeedMultiplier,
        AudioPresentationCommand.ChangeMusicTempo,
        AudioPresentationCommand.ResetRingAlternation,
        AudioPresentationCommand.ToggleMute,
        AudioPresentationCommand.ToggleSolo,
        AudioPresentationCommand.RewindBoundary,
        AudioPresentationCommand.HardReset {

    default boolean structural() {
        return !(this instanceof StartSampleSfx
                || this instanceof SetVoiceGain
                || this instanceof SetVoicePitch
                || this instanceof SetSpeedShoes
                || this instanceof SetSpeedMultiplier);
    }

    default boolean droppableSampleStart() {
        return this instanceof StartSampleSfx;
    }

    default Object coalescingKey() {
        if (this instanceof SetVoiceGain command) {
            return command.voiceId();
        }
        if (this instanceof SetVoicePitch command) {
            return command.voiceId();
        }
        if (this instanceof SetSpeedShoes) {
            return SetSpeedShoes.class;
        }
        if (this instanceof SetSpeedMultiplier) {
            return SetSpeedMultiplier.class;
        }
        return null;
    }

    sealed interface VoiceDescriptor
            permits SampleVoiceDescriptor, SmpsVoiceDescriptor {
        long voiceId();

        int priority();
    }

    record SampleVoiceDescriptor(PresentationVoiceSnapshot.Sample snapshot)
            implements VoiceDescriptor {
        public SampleVoiceDescriptor {
            snapshot = copySample(Objects.requireNonNull(snapshot, "snapshot"));
        }

        public static SampleVoiceDescriptor fromVoice(SampleBackedVoice voice) {
            Objects.requireNonNull(voice, "voice");
            return new SampleVoiceDescriptor(
                    (PresentationVoiceSnapshot.Sample) voice.snapshot());
        }

        @Override
        public long voiceId() {
            return snapshot.voiceId();
        }

        @Override
        public int priority() {
            return snapshot.priority();
        }
    }

    record SmpsVoiceDescriptor(
            long voiceId,
            int priority,
            Integer musicId,
            AudioSourceDescriptor sourceDescriptor,
            int maxStereoFrames,
            PreparedSmpsMusicActivation activation)
            implements VoiceDescriptor {
        public SmpsVoiceDescriptor {
            Objects.requireNonNull(sourceDescriptor, "sourceDescriptor");
            if (maxStereoFrames < 0) {
                throw new IllegalArgumentException(
                        "maxStereoFrames must be non-negative");
            }
        }

    }

    record MusicVoiceEntry(int musicId, AudioSourceDescriptor sourceDescriptor,
                           VoiceDescriptor voiceDescriptor) {
        public MusicVoiceEntry {
            Objects.requireNonNull(sourceDescriptor, "sourceDescriptor");
            Objects.requireNonNull(voiceDescriptor, "voiceDescriptor");
        }

        public static MusicVoiceEntry fromVoice(
                int musicId,
                AudioSourceDescriptor sourceDescriptor,
                PresentationVoice voice) {
            Objects.requireNonNull(voice, "voice");
            VoiceDescriptor descriptor;
            if (voice instanceof SampleBackedVoice sample) {
                PresentationVoiceSnapshot.Sample snapshot =
                        (PresentationVoiceSnapshot.Sample) sample.snapshot();
                descriptor = new SampleVoiceDescriptor(
                        new PresentationVoiceSnapshot.Sample(
                                snapshot.voiceId(), snapshot.priority(),
                                snapshot.assetId(), musicId, sourceDescriptor,
                                snapshot.sourcePositionQ32(),
                                snapshot.sourceStepQ32(), snapshot.gainQ16(),
                                snapshot.looping(), snapshot.stopped()));
            } else {
                throw new IllegalArgumentException(
                        "unsupported music presentation voice "
                                + voice.getClass().getName());
            }
            return new MusicVoiceEntry(
                    musicId, Objects.requireNonNull(sourceDescriptor,
                    "sourceDescriptor"), descriptor);
        }
    }

    record ReplaceMusic(MusicVoiceEntry music) implements AudioPresentationCommand {
        public ReplaceMusic {
            Objects.requireNonNull(music, "music");
        }
    }

    record PushMusicOverride(MusicVoiceEntry music) implements AudioPresentationCommand {
        public PushMusicOverride {
            Objects.requireNonNull(music, "music");
        }
    }

    record RestoreMusicOverride() implements AudioPresentationCommand {
    }

    record EndMusicOverride(int musicId) implements AudioPresentationCommand {
    }

    record AddSmpsSfx(
            ResolvedSmpsSfxSource source,
            PreparedSmpsSfxProgram program)
            implements AudioPresentationCommand {
        public AddSmpsSfx {
            Objects.requireNonNull(source, "source");
        }

        /** Task-6 standalone/tool compatibility constructor. */
        @Deprecated(forRemoval = true)
        public AddSmpsSfx(ResolvedSmpsSfxSource source) {
            this(source, null);
        }
    }

    record StartSampleSfx(SampleVoiceDescriptor voice)
            implements AudioPresentationCommand {
        public StartSampleSfx {
            Objects.requireNonNull(voice, "voice");
        }

        public static StartSampleSfx fromVoice(SampleBackedVoice voice) {
            return new StartSampleSfx(SampleVoiceDescriptor.fromVoice(voice));
        }
    }

    /**
     * The SEGA chant, carried in both of its realisations.
     *
     * <p>A driver whose physical policy owns the ROM's blocking PCM
     * transport plays {@code pcm} through the chip's DAC; one that does not
     * plays the prepared presentation voice. The owner that holds the driver
     * session picks, so the resolver stays free of a backend reference.</p>
     */
    record ReplaceRawPcm(SampleVoiceDescriptor voice, byte[] pcm)
            implements AudioPresentationCommand {
        public ReplaceRawPcm {
            Objects.requireNonNull(voice, "voice");
            pcm = Objects.requireNonNull(pcm, "pcm").clone();
        }

        @Override
        public byte[] pcm() {
            return pcm.clone();
        }

        public static ReplaceRawPcm fromVoice(
                SampleBackedVoice voice, byte[] pcm) {
            return new ReplaceRawPcm(
                    SampleVoiceDescriptor.fromVoice(voice), pcm);
        }
    }

    record StopRawPcm() implements AudioPresentationCommand {
    }

    record StopRawPcmAndRetainGlobalStop(int sourceCommandId)
            implements AudioPresentationCommand {
    }

    record RetainGlobalStop(int sourceCommandId)
            implements AudioPresentationCommand {
    }

    record StopMusic() implements AudioPresentationCommand {
    }

    record StopAllSfx() implements AudioPresentationCommand {
    }

    record StopSmpsSfx(int sourceCommandId)
            implements AudioPresentationCommand {
    }

    record SilencePsg(int sourceCommandId)
            implements AudioPresentationCommand {
    }

    record ReferenceLimitation(int sourceCommandId, String reason)
            implements AudioPresentationCommand {
        public ReferenceLimitation {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "reference limitation reason is required");
            }
        }
    }

    record FadeMusic(int steps, int delay) implements AudioPresentationCommand {
    }

    record SetVoiceGain(long voiceId, int gainQ16) implements AudioPresentationCommand {
    }

    record SetVoicePitch(long voiceId, long sourceStepQ32) implements AudioPresentationCommand {
    }

    record SetSpeedShoes(boolean enabled) implements AudioPresentationCommand {
    }

    record SetSpeedMultiplier(int multiplier) implements AudioPresentationCommand {
    }

    record ChangeMusicTempo(int dividingTiming) implements AudioPresentationCommand {
    }

    record ResetRingAlternation(boolean ringLeft) implements AudioPresentationCommand {
    }

    record ToggleMute(ChannelType type, int channel) implements AudioPresentationCommand {
        public ToggleMute {
            Objects.requireNonNull(type, "type");
        }
    }

    record ToggleSolo(ChannelType type, int channel) implements AudioPresentationCommand {
        public ToggleSolo {
            Objects.requireNonNull(type, "type");
        }
    }

    record RewindBoundary() implements AudioPresentationCommand {
    }

    record HardReset() implements AudioPresentationCommand {
    }

    private static PresentationVoiceSnapshot.Sample copySample(
            PresentationVoiceSnapshot.Sample sample) {
        return new PresentationVoiceSnapshot.Sample(
                sample.voiceId(), sample.priority(), sample.assetId(),
                sample.musicId(), sample.sourceDescriptor(),
                sample.sourcePositionQ32(), sample.sourceStepQ32(),
                sample.gainQ16(), sample.looping(), sample.stopped());
    }
}
