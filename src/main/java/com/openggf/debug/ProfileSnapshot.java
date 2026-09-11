package com.openggf.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable snapshot of profiling data, designed for reuse across frames.
 * The profiler populates this in-place via {@link #populate} each frame,
 * and the renderer reads from it synchronously on the same thread.
 */
public class ProfileSnapshot {

    private static final Comparator<SectionStats> BY_TIME_DESC =
            (a, b) -> Double.compare(b.timeMs(), a.timeMs());
    private static final Comparator<SectionStats> BY_NAME =
            Comparator.comparing(SectionStats::name);

    private final Map<String, SectionStats> sections = new LinkedHashMap<>();
    private double totalFrameTimeMs;
    private double fps;
    private float[] frameHistory;
    private int historyIndex;
    private int frameCount;

    // Cached sorted list — invalidated on populate()
    private final List<SectionStats> sortedByTimeCache = new ArrayList<>();
    private final List<SectionStats> sortedByNameCache = new ArrayList<>();
    private boolean sortedByTimeDirty = true;
    private boolean sortedByNameDirty = true;

    public ProfileSnapshot() {
        this.frameHistory = new float[120];
    }

    public void clear() {
        sections.clear();
        totalFrameTimeMs = 0;
        fps = 0;
        historyIndex = 0;
        frameCount = 0;
        for (int i = 0; i < frameHistory.length; i++) {
            frameHistory[i] = 0;
        }
        sortedByTimeCache.clear();
        sortedByNameCache.clear();
        sortedByTimeDirty = true;
        sortedByNameDirty = true;
    }

    /**
     * Updates this snapshot in-place with new profiling data.
     * Reuses the history array; section values remain immutable records.
     */
    public void populate(Map<String, Long> rollingSums, int effectiveFrames,
                         float[] sourceHistory, int historyIndex, int frameCount,
                         long actualFrameTimeSum) {
        sections.clear();

        long totalSectionNanos = 0;
        for (long sumNanos : rollingSums.values()) {
            totalSectionNanos += sumNanos;
        }
        double totalMs = (double) totalSectionNanos / effectiveFrames / 1_000_000.0;
        for (Map.Entry<String, Long> entry : rollingSums.entrySet()) {
            putSection(entry.getKey(), entry.getValue(), effectiveFrames, totalMs);
        }
        populateFrame(effectiveFrames, sourceHistory, historyIndex, frameCount,
                actualFrameTimeSum, totalMs);
    }

    /** Internal primitive path retains map nodes when the section order is unchanged. */
    void populate(SectionMeasurements measurements, int effectiveFrames,
                  float[] sourceHistory, int historyIndex, int frameCount,
                  long actualFrameTimeSum) {
        int index = 0;
        boolean compatible = sections.size() <= measurements.size();
        if (compatible) {
            for (String name : sections.keySet()) {
                if (!java.util.Objects.equals(name, measurements.get(index++).name)) {
                    compatible = false;
                    break;
                }
            }
        }
        if (!compatible) {
            sections.clear();
        }
        long totalSectionNanos = 0;
        for (int i = 0; i < measurements.size(); i++) {
            totalSectionNanos += measurements.get(i).sum;
        }
        double totalMs = (double) totalSectionNanos / effectiveFrames / 1_000_000.0;
        for (int i = 0; i < measurements.size(); i++) {
            SectionMeasurements.Section section = measurements.get(i);
            putSection(section.name, section.sum, effectiveFrames, totalMs);
        }
        populateFrame(effectiveFrames, sourceHistory, historyIndex, frameCount,
                actualFrameTimeSum, totalMs);
    }

    private void putSection(String name, long sumNanos, int effectiveFrames, double totalMs) {
        double avgMs = (double) sumNanos / effectiveFrames / 1_000_000.0;
        double percentage = totalMs > 0 ? avgMs / totalMs * 100.0 : 0;
        sections.put(name, new SectionStats(name, avgMs, percentage));
    }

    private void populateFrame(int effectiveFrames, float[] sourceHistory, int historyIndex,
                               int frameCount, long actualFrameTimeSum, double totalMs) {
        this.totalFrameTimeMs = totalMs;

        // Reuse or resize the history array
        if (this.frameHistory.length != sourceHistory.length) {
            this.frameHistory = new float[sourceHistory.length];
        }
        System.arraycopy(sourceHistory, 0, this.frameHistory, 0, sourceHistory.length);
        this.historyIndex = historyIndex;
        this.frameCount = frameCount;

        // FPS from actual frame-to-frame time
        if (effectiveFrames > 0 && actualFrameTimeSum > 0) {
            double avgActualFrameNanos = (double) actualFrameTimeSum / effectiveFrames;
            this.fps = 1_000_000_000.0 / avgActualFrameNanos;
        } else {
            this.fps = 0;
        }

        sortedByTimeDirty = true;
        sortedByNameDirty = true;
    }

    // Direct accessors — no defensive copies needed since this is consumed
    // synchronously by the renderer on the same thread.

    public Map<String, SectionStats> sections() {
        return sections;
    }

    public double totalFrameTimeMs() {
        return totalFrameTimeMs;
    }

    public double fps() {
        return fps;
    }

    public float[] frameHistory() {
        return frameHistory;
    }

    public int historyIndex() {
        return historyIndex;
    }

    public int frameCount() {
        return frameCount;
    }

    /**
     * Returns sections sorted by time descending. The returned list is cached
     * and reused across calls within the same frame.
     */
    public List<SectionStats> getSectionsSortedByTime() {
        if (sortedByTimeDirty) {
            sortedByTimeCache.clear();
            sortedByTimeCache.addAll(sections.values());
            sortedByTimeCache.sort(BY_TIME_DESC);
            sortedByTimeDirty = false;
        }
        return sortedByTimeCache;
    }

    /**
     * Returns sections sorted alphabetically by name. The returned list is cached
     * and reused across calls within the same frame.
     */
    public List<SectionStats> getSectionsSortedByName() {
        if (sortedByNameDirty) {
            sortedByNameCache.clear();
            sortedByNameCache.addAll(sections.values());
            sortedByNameCache.sort(BY_NAME);
            sortedByNameDirty = false;
        }
        return sortedByNameCache;
    }

    public boolean hasData() {
        return frameCount > 0 && !sections.isEmpty();
    }
}
