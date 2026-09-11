package com.openggf.debug;

/**
 * Receives the raw, unaveraged per-frame timings that {@link PerformanceProfiler}
 * has already collected, at the moment the frame closes.
 *
 * <p>The overlay consumes the profiler's rolling 60-frame means, which is the
 * right smoothing for a human reading a live HUD and the wrong input for a
 * benchmark: JVM-to-JVM differences live almost entirely in the tail (JIT
 * deoptimisation, GC pauses, safepoint bias), and a mean erases them. This sink
 * exists so an offline harness can keep every sample and compute percentiles
 * itself, without the profiler taking on any storage policy of its own.
 *
 * <p>Called on the profiler's owner thread inside {@code endFrame()}: one
 * {@link #frameSample} per section that recorded time this frame, followed by
 * exactly one {@link #frameComplete}. Implementations run inside the measured
 * loop and must not allocate per call.
 */
public interface FrameSampleSink {

    /**
     * Reports one section's total time for the frame that is closing. Sections
     * that recorded no time this frame are not reported.
     */
    void frameSample(String section, long nanos);

    /**
     * Closes the frame, reporting the whole frame's work time (the span from
     * {@code beginFrame()} to {@code endFrame()}, excluding any pacing sleep).
     */
    void frameComplete(long frameNanos);
}
