package com.openggf.trace.live;

import com.openggf.trace.FrameComparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Captures every {@link FrameComparison} emitted by a
 * {@link LiveTraceComparator} into an in-memory list. Intended for use by
 * the rewind framework's keystone parity test: capture two engine runs as
 * two observers, then call {@link #diff(RecordingFrameObserver)} to find
 * the first frame at which the runs diverge.
 *
 * <p>Pass a fresh instance into the {@code perFrameObserver} parameter of
 * {@link LiveTraceComparator}. After the playback completes, call
 * {@link #frames()} to inspect captures.
 */
public final class RecordingFrameObserver implements Consumer<FrameComparison> {

    private final List<FrameComparison> frames = new ArrayList<>();

    @Override
    public void accept(FrameComparison frame) {
        Objects.requireNonNull(frame, "frame");
        frames.add(frame);
    }

    /** Captured frames in arrival order. Returned list is unmodifiable. */
    public List<FrameComparison> frames() {
        return List.copyOf(frames);
    }

    /**
     * Returns the first frame from THIS observer that differs from
     * {@code other}, or empty if both observers captured identical
     * sequences (including length).
     *
     * <p>Length mismatch surfaces as a diff at the index where one side
     * ran out of frames; the returned {@code FrameComparison} comes from
     * whichever observer still has a frame at that index.
     *
     * <p>Frame equality is determined by {@link Object#equals(Object)},
     * which for {@code FrameComparison} records compares all fields
     * (frame, fields map, diagnostics).
     */
    public Optional<FrameComparison> diff(RecordingFrameObserver other) {
        Objects.requireNonNull(other, "other");
        int n = Math.min(frames.size(), other.frames.size());
        for (int i = 0; i < n; i++) {
            FrameComparison lhs = frames.get(i);
            FrameComparison rhs = other.frames.get(i);
            if (!Objects.equals(lhs, rhs)) {
                return Optional.of(lhs);
            }
        }
        if (frames.size() != other.frames.size()) {
            // Length mismatch: surface the first "extra" frame.
            return Optional.of(frames.size() > n ? frames.get(n) : other.frames.get(n));
        }
        return Optional.empty();
    }
}
