package com.openggf.audio.presentation;

@FunctionalInterface
public interface AudioPresentationListener {
    /**
     * The view is valid only for the duration of this synchronous callback.
     * Consumers retaining PCM must copy the active range before returning.
     */
    void onPresentationFrame(AudioPresentationFrameView frame);
}
