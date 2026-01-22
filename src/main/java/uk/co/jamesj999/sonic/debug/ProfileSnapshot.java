package uk.co.jamesj999.sonic.debug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of profiling data at a point in time.
 * Contains averaged timing data for all tracked sections.
 *
 * @param sections Map of section name to statistics (preserves insertion order)
 * @param totalFrameTimeMs Total frame time in milliseconds (sum of all sections)
 * @param fps Current frames per second
 * @param frameHistory Circular buffer of recent frame times in milliseconds
 * @param historyIndex Current write index in the frame history buffer
 * @param frameCount Total number of frames profiled
 */
public record ProfileSnapshot(
        Map<String, SectionStats> sections,
        double totalFrameTimeMs,
        double fps,
        float[] frameHistory,
        int historyIndex,
        int frameCount
) {
    /**
     * Returns an empty snapshot for use before profiling has started.
     */
    public static ProfileSnapshot empty() {
        return new ProfileSnapshot(
                Collections.emptyMap(),
                0,
                0,
                new float[120],
                0,
                0
        );
    }

    /**
     * Returns a defensive copy of the sections map.
     */
    @Override
    public Map<String, SectionStats> sections() {
        return new LinkedHashMap<>(sections);
    }

    /**
     * Returns a defensive copy of the frame history.
     */
    @Override
    public float[] frameHistory() {
        return frameHistory.clone();
    }

    /**
     * Returns the section stats sorted by time descending.
     */
    public java.util.List<SectionStats> getSectionsSortedByTime() {
        return sections.values().stream()
                .sorted((a, b) -> Double.compare(b.timeMs(), a.timeMs()))
                .toList();
    }

    /**
     * Returns true if there is meaningful data to display.
     */
    public boolean hasData() {
        return frameCount > 0 && !sections.isEmpty();
    }
}
