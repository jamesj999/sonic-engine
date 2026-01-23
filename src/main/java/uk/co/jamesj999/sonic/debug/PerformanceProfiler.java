package uk.co.jamesj999.sonic.debug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton performance profiler that tracks timing for named sections of each frame.
 * Uses System.nanoTime() for high-precision measurement.
 * Maintains rolling averages over a configurable number of frames.
 */
public class PerformanceProfiler {
    private static PerformanceProfiler instance;

    /** Number of frames to average over for smoothing */
    private static final int AVERAGING_FRAMES = 60;

    /** Number of frames to keep in the history buffer */
    private static final int HISTORY_SIZE = 120;

    /** Current frame's section timings (name -> accumulated nanos) */
    private final Map<String, Long> currentFrameSections = new LinkedHashMap<>();

    /** Rolling sum of section timings over AVERAGING_FRAMES (name -> nanos sum) */
    private final Map<String, Long> rollingSums = new LinkedHashMap<>();

    /** Circular buffer of frame timings for history graph */
    private final float[] frameHistory = new float[HISTORY_SIZE];

    /** Current index in the history buffer */
    private int historyIndex = 0;

    /** Number of frames recorded so far (for warmup) */
    private int frameCount = 0;

    /** Frame start time in nanos (for measuring work time) */
    private long frameStartNanos;

    /** Previous frame start time in nanos (for measuring actual frame rate) */
    private long previousFrameStartNanos;

    /** Current section start time in nanos */
    private long sectionStartNanos;

    /** Rolling sum of actual frame-to-frame times for FPS calculation */
    private long actualFrameTimeSum;

    /** Circular buffer of actual frame times */
    private final long[] actualFrameTimes = new long[AVERAGING_FRAMES];

    /** Name of currently active section (for validation) */
    private String activeSection;

    /** Circular buffer of per-section timing per frame (for rolling average) */
    private final Map<String, long[]> sectionHistories = new LinkedHashMap<>();

    private PerformanceProfiler() {
    }

    public static synchronized PerformanceProfiler getInstance() {
        if (instance == null) {
            instance = new PerformanceProfiler();
        }
        return instance;
    }

    /**
     * Marks the beginning of a new frame.
     * Call this at the start of the main display loop.
     */
    public void beginFrame() {
        long now = System.nanoTime();

        // Track actual frame-to-frame time (for real FPS)
        if (previousFrameStartNanos > 0) {
            long actualFrameTime = now - previousFrameStartNanos;
            int slot = frameCount % AVERAGING_FRAMES;
            actualFrameTimeSum -= actualFrameTimes[slot];
            actualFrameTimes[slot] = actualFrameTime;
            actualFrameTimeSum += actualFrameTime;
        }
        previousFrameStartNanos = now;

        frameStartNanos = now;
        currentFrameSections.clear();
        activeSection = null;
    }

    /**
     * Marks the end of the current frame.
     * Updates rolling averages and frame history.
     */
    public void endFrame() {
        long frameEndNanos = System.nanoTime();
        long frameDurationNanos = frameEndNanos - frameStartNanos;

        // Update memory stats tracking
        MemoryStats.getInstance().update();

        // Convert to milliseconds for history
        float frameTimeMs = frameDurationNanos / 1_000_000f;
        frameHistory[historyIndex] = frameTimeMs;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;

        // Update rolling sums for each section
        int historySlot = frameCount % AVERAGING_FRAMES;

        for (Map.Entry<String, Long> entry : currentFrameSections.entrySet()) {
            String name = entry.getKey();
            long nanos = entry.getValue();

            // Ensure history array exists for this section
            long[] history = sectionHistories.computeIfAbsent(name, k -> new long[AVERAGING_FRAMES]);

            // Subtract old value, add new value
            long oldValue = history[historySlot];
            rollingSums.merge(name, nanos - oldValue, Long::sum);
            history[historySlot] = nanos;
        }

        // Handle sections that weren't recorded this frame (set to 0)
        for (Map.Entry<String, long[]> entry : sectionHistories.entrySet()) {
            String name = entry.getKey();
            if (!currentFrameSections.containsKey(name)) {
                long[] history = entry.getValue();
                long oldValue = history[historySlot];
                rollingSums.merge(name, -oldValue, Long::sum);
                history[historySlot] = 0;
            }
        }

        frameCount++;
    }

    /**
     * Begins timing a named section.
     * Sections cannot be nested - calling this while a section is active will
     * implicitly end the previous section.
     *
     * @param name The name of the section (e.g., "audio", "physics", "render.bg")
     */
    public void beginSection(String name) {
        if (activeSection != null) {
            // Implicitly end the previous section
            endSection(activeSection);
        }
        activeSection = name;
        sectionStartNanos = System.nanoTime();
        MemoryStats.getInstance().beginSection(name);
    }

    /**
     * Ends timing for the named section and records the duration.
     *
     * @param name The name of the section (must match the most recent beginSection call)
     */
    public void endSection(String name) {
        if (activeSection == null || !activeSection.equals(name)) {
            return; // Ignore mismatched end calls
        }
        long endNanos = System.nanoTime();
        long duration = endNanos - sectionStartNanos;
        currentFrameSections.merge(name, duration, Long::sum);
        activeSection = null;
        MemoryStats.getInstance().endSection(name);
    }

    /**
     * Returns an immutable snapshot of the current profiling data.
     * Safe to call from rendering code.
     *
     * @return ProfileSnapshot containing averaged timing data
     */
    public ProfileSnapshot getSnapshot() {
        int effectiveFrames = Math.min(frameCount, AVERAGING_FRAMES);
        if (effectiveFrames == 0) {
            return ProfileSnapshot.empty();
        }

        // Calculate total frame time from all sections
        long totalSectionNanos = 0;
        Map<String, SectionStats> sections = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : rollingSums.entrySet()) {
            String name = entry.getKey();
            long sumNanos = entry.getValue();
            double avgNanos = (double) sumNanos / effectiveFrames;
            double avgMs = avgNanos / 1_000_000.0;
            sections.put(name, new SectionStats(name, avgMs, 0)); // Percentage calculated below
            totalSectionNanos += sumNanos;
        }

        // Calculate percentages
        double totalMs = (double) totalSectionNanos / effectiveFrames / 1_000_000.0;
        if (totalMs > 0) {
            Map<String, SectionStats> withPercentages = new LinkedHashMap<>();
            for (Map.Entry<String, SectionStats> entry : sections.entrySet()) {
                SectionStats stats = entry.getValue();
                double pct = (stats.timeMs() / totalMs) * 100.0;
                withPercentages.put(entry.getKey(), new SectionStats(stats.name(), stats.timeMs(), pct));
            }
            sections = withPercentages;
        }

        // Copy frame history for the snapshot
        float[] historyCopy = new float[HISTORY_SIZE];
        System.arraycopy(frameHistory, 0, historyCopy, 0, HISTORY_SIZE);

        // Calculate actual FPS from frame-to-frame time (not work time)
        double fps = 0;
        if (effectiveFrames > 0 && actualFrameTimeSum > 0) {
            double avgActualFrameNanos = (double) actualFrameTimeSum / effectiveFrames;
            fps = 1_000_000_000.0 / avgActualFrameNanos;
        }

        return new ProfileSnapshot(sections, totalMs, fps, historyCopy, historyIndex, frameCount);
    }

    /**
     * Returns the number of frames in the history buffer.
     */
    public int getHistorySize() {
        return HISTORY_SIZE;
    }

    /**
     * Resets all profiling data.
     */
    public void reset() {
        currentFrameSections.clear();
        rollingSums.clear();
        sectionHistories.clear();
        frameCount = 0;
        historyIndex = 0;
        previousFrameStartNanos = 0;
        actualFrameTimeSum = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            frameHistory[i] = 0;
        }
        for (int i = 0; i < AVERAGING_FRAMES; i++) {
            actualFrameTimes[i] = 0;
        }
    }
}
