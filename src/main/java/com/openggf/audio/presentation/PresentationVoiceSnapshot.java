package com.openggf.audio.presentation;

import com.openggf.audio.rewind.AudioSourceDescriptor;
public sealed interface PresentationVoiceSnapshot
        permits PresentationVoiceSnapshot.Sample {

    record Sample(long voiceId, int priority, String assetId, Integer musicId,
                  AudioSourceDescriptor sourceDescriptor, long sourcePositionQ32,
                  long sourceStepQ32, int gainQ16, boolean looping,
                  boolean stopped) implements PresentationVoiceSnapshot {
    }
}
