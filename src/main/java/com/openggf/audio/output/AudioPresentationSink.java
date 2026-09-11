package com.openggf.audio.output;

import com.openggf.audio.presentation.AudioPresentationFrameView;

public interface AudioPresentationSink extends AutoCloseable {
    int sampleRate();

    void accept(AudioPresentationFrameView frame);

    void onReverseBoundary();

    @Override
    void close();
}
