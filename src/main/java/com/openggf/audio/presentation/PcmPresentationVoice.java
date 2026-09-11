package com.openggf.audio.presentation;

/** A presentation voice which contributes final PCM to the software mix. */
public interface PcmPresentationVoice extends PresentationVoice {
    @Override
    void mixInto(long[] accumulation, int stereoFrames);
}
