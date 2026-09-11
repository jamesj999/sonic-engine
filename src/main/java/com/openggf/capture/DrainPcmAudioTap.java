package com.openggf.capture;

import com.openggf.audio.AudioManager;

/**
 * {@link AudioFrameTap} over {@link AudioManager#drainCaptureFrame(short[])}.
 *
 * <p>The drain is a non-consuming view of the packet the authoritative
 * presentation producer rendered for the current outer frame, so an offline
 * capture reads exactly the final SMPS/WAV/raw-PCM mix the speaker path would
 * play, and neither this tap nor a live-recording tap can destructively drain
 * the other. Draining never presents: the capture driver owns the single
 * {@code AudioManager.presentFrame(FORWARD)} per presented outer frame.
 */
public final class DrainPcmAudioTap implements AudioFrameTap {

    private final AudioManager audio;

    public DrainPcmAudioTap(AudioManager audio) {
        this.audio = audio;
    }

    @Override
    public int drain(short[] target) {
        return audio.drainCaptureFrame(target);
    }
}
