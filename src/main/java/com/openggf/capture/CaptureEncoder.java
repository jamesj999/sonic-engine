package com.openggf.capture;

import java.nio.file.Path;

/**
 * Encodes a stream of {@link CapturedFrame}s to {@code output}. Lifecycle:
 * {@code open} once (receives the destination path), {@code encode} per frame
 * (in order), then {@code finish} (success) or {@code abort} (failure).
 * Implementations are driven from a single encoder thread by {@link EncoderSink}.
 */
public interface CaptureEncoder {
    /** @param output the file the encoder must write (owned by the recorder). */
    void open(Path output, int width, int height, int fps, int sampleRate) throws CaptureException;

    /** Consume synchronously: frame pixel storage must not be retained after return. */
    void encode(CapturedFrame frame) throws CaptureException;

    /** Flush and finalize; returns the written output file (normally {@code output}). */
    Path finish() throws CaptureException;

    /** Best-effort cleanup after a failure. Must not throw. */
    void abort();
}
