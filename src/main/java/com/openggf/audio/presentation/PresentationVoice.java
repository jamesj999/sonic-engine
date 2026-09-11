package com.openggf.audio.presentation;

public interface PresentationVoice {
    long voiceId();

    int priority();

    void mixInto(long[] accumulation, int stereoFrames);

    boolean isComplete();

    void stop();

    PresentationVoiceSnapshot snapshot();
}
