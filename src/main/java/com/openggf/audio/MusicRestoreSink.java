package com.openggf.audio;

/** Receives an SMPS request to restore the music beneath an override. */
@FunctionalInterface
public interface MusicRestoreSink {
    void restoreMusic();
}
