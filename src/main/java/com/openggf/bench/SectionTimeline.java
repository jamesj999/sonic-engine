package com.openggf.bench;

import com.openggf.debug.FrameSampleSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-capacity recorder for raw per-frame section timings.
 *
 * <p>Every frame's numbers are kept, not averaged: percentiles are computed
 * afterwards from the full sample set. Storage is a preallocated
 * {@code long[capacity]} per section, so the recording path does no allocation
 * once a section has been seen once — the harness must not perturb the workload
 * it is measuring.
 *
 * <p>A section discovered mid-run gets a zero-filled array for the frames before
 * its first appearance, which is the correct reading: it contributed no time.
 *
 * <p>Not thread-safe; it is driven from the profiler's owner thread.
 */
public final class SectionTimeline implements FrameSampleSink {

    private final int capacity;
    private final Map<String, long[]> sections = new LinkedHashMap<>();
    private final long[] frameNanos;

    private int frameIndex;
    private boolean overflowed;

    public SectionTimeline(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.frameNanos = new long[capacity];
    }

    @Override
    public void frameSample(String section, long nanos) {
        if (frameIndex >= capacity) {
            return;
        }
        // merge rather than assign: the profiler reports one total per section
        // per frame, but a caller driving several simulation steps into a single
        // recorded frame would legitimately report the same name twice.
        series(section)[frameIndex] += nanos;
    }

    @Override
    public void frameComplete(long totalNanos) {
        if (frameIndex >= capacity) {
            overflowed = true;
            return;
        }
        frameNanos[frameIndex++] = totalNanos;
    }

    private long[] series(String section) {
        return sections.computeIfAbsent(section, unused -> new long[capacity]);
    }

    /** Frames recorded so far, never exceeding the capacity. */
    public int frameCount() {
        return frameIndex;
    }

    /**
     * True when more frames were driven than the timeline could hold, so the
     * recorded window is a prefix of the run. Callers must surface this rather
     * than silently reporting a truncated sample as the whole run.
     */
    public boolean overflowed() {
        return overflowed;
    }

    /** Section names in first-seen order. */
    public List<String> sectionNames() {
        return new ArrayList<>(sections.keySet());
    }

    /**
     * Raw per-frame nanos for one section. The returned array is the live
     * backing store of length {@code capacity}; only the first
     * {@link #frameCount()} entries are meaningful.
     */
    public long[] rawSection(String section) {
        long[] series = sections.get(section);
        if (series == null) {
            throw new IllegalArgumentException("No samples recorded for section '" + section + "'");
        }
        return series;
    }

    /** Raw per-frame whole-frame work nanos, same length/validity rules as {@link #rawSection}. */
    public long[] rawFrameNanos() {
        return frameNanos;
    }

    /** Percentile summary of one section over the recorded frames. */
    public SectionTiming timing(String section) {
        return SectionTiming.of(section, rawSection(section), frameIndex);
    }

    /** Percentile summary of whole-frame work time over the recorded frames. */
    public SectionTiming frameTiming() {
        return SectionTiming.of("frame", frameNanos, frameIndex);
    }

    /** Percentile summaries for every recorded section, heaviest total first. */
    public List<SectionTiming> allTimings() {
        List<SectionTiming> timings = new ArrayList<>(sections.size());
        for (String name : sections.keySet()) {
            timings.add(timing(name));
        }
        timings.sort((a, b) -> Long.compare(b.totalNanos(), a.totalNanos()));
        return timings;
    }
}
