package com.openggf.debug;


/**
 * Singleton performance profiler that tracks timing for named sections of each frame.
 * Uses System.nanoTime() for high-precision measurement.
 * Maintains rolling averages over a configurable number of frames.
 *
 * <p>This collector is intentionally single-threaded. The first mutating call
 * claims the owner thread; later calls from any other thread fail fast instead
 * of corrupting section maps or reusable snapshots.
 */
public class PerformanceProfiler implements SectionProfiler {
    private static PerformanceProfiler instance;

    /** Number of frames to average over for smoothing */
    private static final int AVERAGING_FRAMES = 60;

    /** Number of frames to keep in the history buffer */
    private static final int HISTORY_SIZE = 120;

    private final SectionMeasurements sections = new SectionMeasurements(AVERAGING_FRAMES);

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

    /** Whether profiling is currently enabled. */
    private boolean enabled = true;

    /** Thread allowed to mutate/read profiler frame state. Claimed lazily. */
    private long ownerThreadId;

    /** Reusable snapshot — populated in-place each frame to avoid allocation */
    private final ProfileSnapshot reusableSnapshot = new ProfileSnapshot();
    private final MemoryStats memoryStats = new MemoryStats();

    /** Optional raw-sample consumer (offline benchmarking). Null in normal runs. */
    private FrameSampleSink sampleSink;

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
        assertOwnerThread();
        if (!enabled) {
            sections.clearFrame();
            activeSection = null;
            return;
        }

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
        sections.clearFrame();
        activeSection = null;
    }

    /**
     * Marks the end of the current frame.
     * Updates rolling averages and frame history.
     */
    public void endFrame() {
        assertOwnerThread();
        if (!enabled) {
            return;
        }

        long frameEndNanos = System.nanoTime();
        long frameDurationNanos = frameEndNanos - frameStartNanos;

        // Hand the raw samples to any offline consumer before they are folded
        // into the rolling means below — the means are lossy by design.
        if (sampleSink != null) {
            sections.emit(sampleSink);
            sampleSink.frameComplete(frameDurationNanos);
        }

        // Update memory stats tracking
        memoryStats.update();

        // Convert to milliseconds for history
        float frameTimeMs = frameDurationNanos / 1_000_000f;
        frameHistory[historyIndex] = frameTimeMs;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;

        // Update rolling sums for each section
        int historySlot = frameCount % AVERAGING_FRAMES;

        sections.finishFrame(historySlot);

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
        assertOwnerThread();
        if (!enabled) {
            return;
        }

        if (activeSection != null) {
            // Implicitly end the previous section
            endSection(activeSection);
        }
        activeSection = name;
        sectionStartNanos = System.nanoTime();
        memoryStats.beginSection(name);
    }

    /**
     * Ends timing for the named section and records the duration.
     *
     * @param name The name of the section (must match the most recent beginSection call)
     */
    public void endSection(String name) {
        assertOwnerThread();
        if (!enabled) {
            return;
        }

        if (activeSection == null || !activeSection.equals(name)) {
            return; // Ignore mismatched end calls
        }
        long endNanos = System.nanoTime();
        long duration = endNanos - sectionStartNanos;
        sections.add(name, duration);
        activeSection = null;
        memoryStats.endSection(name);
    }

    /**
     * Credits {@code elapsedNanos} to {@code name} without disturbing the currently
     * active section. The active section's start timestamp is shifted forward by
     * {@code elapsedNanos} so the carved-out interval is not double-counted when
     * the active section eventually ends.
     *
     * <p>Use this when a piece of work runs inside an existing section and you want
     * to measure it as its own bucket without truncating the outer section
     * (e.g. {@code render.atlas_upload} firing inside {@code render.sprites} on DPLC
     * bursts). The caller measures the interval itself with {@link System#nanoTime()}.
     *
     * <p>Non-overlapping invariant is preserved: total time across sections still
     * sums to the frame duration when a parent section is active, because that
     * active section gives up the elapsed nanos in exchange for the credited
     * section gaining them. When no section is active, the method records an
     * independent bucket without shifting another section.
     */
    public void recordSectionTime(String name, long elapsedNanos) {
        assertOwnerThread();
        if (!enabled || elapsedNanos <= 0) {
            return;
        }
        sections.add(name, elapsedNanos);
        if (activeSection != null) {
            sectionStartNanos += elapsedNanos;
        }
    }

    /**
     * Returns a reusable snapshot of the current profiling data.
     * Safe to call from rendering code.
     *
     * @return ProfileSnapshot containing averaged timing data
     */
    public ProfileSnapshot getSnapshot() {
        assertOwnerThread();
        int effectiveFrames = Math.min(frameCount, AVERAGING_FRAMES);
        if (effectiveFrames == 0) {
            return reusableSnapshot;
        }

        reusableSnapshot.populate(sections, effectiveFrames,
                frameHistory, historyIndex, frameCount, actualFrameTimeSum);
        return reusableSnapshot;
    }

    /**
     * Returns the number of frames in the history buffer.
     */
    public int getHistorySize() {
        return HISTORY_SIZE;
    }

    public MemoryStats memoryStats() {
        assertOwnerThread();
        return memoryStats;
    }

    /**
     * Installs (or clears, with {@code null}) the raw per-frame sample consumer.
     * At most one sink is active; offline harnesses own it for the length of a
     * measured run and clear it afterwards.
     */
    public void setSampleSink(FrameSampleSink sink) {
        assertOwnerThread();
        this.sampleSink = sink;
    }

    /**
     * Enables or disables per-section allocation tracking independently of
     * section timing.
     *
     * <p>Allocation tracking reads {@code ThreadMXBean.getThreadAllocatedBytes}
     * once inside {@code beginSection} — after the section's start timestamp is
     * taken — so its cost lands inside every section's measured time. That is
     * acceptable for the live overlay, where both numbers are wanted together,
     * but it is a systematic and <em>JVM-dependent</em> bias: the call is cheap
     * on HotSpot and comparatively expensive elsewhere, which would show up as a
     * fake timing difference in a cross-JVM comparison. Offline timing runs turn
     * it off; frame-level heap/GC/allocation-rate tracking is unaffected.
     */
    public void setAllocationTrackingEnabled(boolean allocationTrackingEnabled) {
        assertOwnerThread();
        memoryStats.setEnabled(allocationTrackingEnabled);
    }

    /**
     * Enables or disables profiling collection.
     */
    public void setEnabled(boolean enabled) {
        assertOwnerThread();
        this.enabled = enabled;
        memoryStats.setEnabled(enabled);
        if (!enabled) {
            sections.clearFrame();
            activeSection = null;
            frameStartNanos = 0;
            previousFrameStartNanos = 0;
            sectionStartNanos = 0;
        }
    }

    /**
     * Resets all profiling data.
     */
    public void reset() {
        assertOwnerThread();
        sections.clearFrame();
        sections.reset();
        reusableSnapshot.clear();
        frameCount = 0;
        historyIndex = 0;
        previousFrameStartNanos = 0;
        actualFrameTimeSum = 0;
        activeSection = null;
        frameStartNanos = 0;
        sectionStartNanos = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            frameHistory[i] = 0;
        }
        for (int i = 0; i < AVERAGING_FRAMES; i++) {
            actualFrameTimes[i] = 0;
        }
        memoryStats.reset();
    }

    private void assertOwnerThread() {
        long currentThreadId = Thread.currentThread().threadId();
        if (ownerThreadId == 0) {
            ownerThreadId = currentThreadId;
            return;
        }
        if (ownerThreadId != currentThreadId) {
            throw new IllegalStateException("PerformanceProfiler is single-threaded; owner thread "
                    + ownerThreadId + " but called from " + currentThreadId);
        }
    }
}
