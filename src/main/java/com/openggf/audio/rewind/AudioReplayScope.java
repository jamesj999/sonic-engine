package com.openggf.audio.rewind;

public interface AudioReplayScope extends AutoCloseable {
    @Override
    void close();
}
