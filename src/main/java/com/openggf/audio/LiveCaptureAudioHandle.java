package com.openggf.audio;

import com.openggf.audio.runtime.AudioFrameClock;

public interface LiveCaptureAudioHandle extends AutoCloseable {
    int sampleRate();

    int frameRate();

    int maxStereoFramesPerPacket();

    int drainPresentationFrame(short[] target);

    long totalStereoFrames();

    AudioFrameClock.Snapshot clockSnapshot();

    @Override
    void close();
}
