package com.openggf.capture;

import java.nio.file.Path;

/** Consumes captured frames. {@code stop} drains and finalizes, returning the file. */
public interface FrameSink {
    void submit(CapturedFrame frame) throws CaptureException;

    Path stop() throws CaptureException;
}
