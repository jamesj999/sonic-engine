package com.openggf.audio.presentation;

public interface PresentationVoiceSource {
    int orderedVoiceCount();

    PresentationVoice orderedVoiceAt(int index);
}
