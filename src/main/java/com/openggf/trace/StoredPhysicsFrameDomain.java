package com.openggf.trace;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema-independent stored-row frame domain read from the first physics CSV
 * column. It deliberately does not interpret any game-specific state column.
 */
public record StoredPhysicsFrameDomain(List<Integer> frames) {

    public enum FrameEncoding {
        HEXADECIMAL(16),
        DECIMAL(10);

        private final int radix;

        FrameEncoding(int radix) {
            this.radix = radix;
        }
    }

    public StoredPhysicsFrameDomain {
        frames = List.copyOf(frames);
        for (int index = 0; index < frames.size(); index++) {
            if (frames.get(index) != index) {
                throw new IllegalArgumentException(
                        "physics frame domain must be contiguous and zero-based:"
                                + " expected " + index + " but found "
                                + frames.get(index));
            }
        }
    }

    public static StoredPhysicsFrameDomain scan(Path physicsPath)
            throws IOException {
        return scan(physicsPath, FrameEncoding.HEXADECIMAL);
    }

    public static StoredPhysicsFrameDomain scan(
            Path physicsPath,
            FrameEncoding encoding) throws IOException {
        List<Integer> frames = new ArrayList<>();
        try (BufferedReader reader = TraceFiles.openReader(physicsPath)) {
            boolean firstMeaningfulLine = true;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (firstMeaningfulLine && TraceFiles.isCsvHeader(trimmed)) {
                    firstMeaningfulLine = false;
                    continue;
                }
                firstMeaningfulLine = false;
                int comma = trimmed.indexOf(',');
                String rawFrame = comma >= 0
                        ? trimmed.substring(0, comma).trim()
                        : trimmed;
                try {
                    frames.add(Integer.parseInt(rawFrame, encoding.radix));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "invalid physics frame index '" + rawFrame + "'", e);
                }
            }
        }
        return new StoredPhysicsFrameDomain(frames);
    }

    public static StoredPhysicsFrameDomain fromTraceFrames(
            List<TraceFrame> traceFrames) {
        return new StoredPhysicsFrameDomain(
                traceFrames.stream().map(TraceFrame::frame).toList());
    }

    public int lastFrame() {
        return frames.isEmpty() ? -1 : frames.getLast();
    }
}
