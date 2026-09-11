package com.openggf.audio;

/**
 * Disabled-by-default observation seam at the public numeric audio-request boundary.
 * It is deliberately absent from logical snapshots and has no authority over playback.
 */
@FunctionalInterface
public interface AudioRequestObserver {
    AudioRequestObserver NONE = (requestClass, rawSoundId) -> { };

    enum RequestClass { MUSIC, SFX, SPECIAL_SFX, COMMAND }

    void onRequested(RequestClass requestClass, int rawSoundId);
}
