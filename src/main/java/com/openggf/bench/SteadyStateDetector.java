package com.openggf.bench;

import java.util.Arrays;

/**
 * Finds how many frames a run takes to reach steady-state frame times.
 *
 * <p>For a game this is arguably the more interesting cross-JVM number than peak
 * throughput. Every runtime gets to the same place eventually; what separates
 * them in practice is how long the player spends watching the interpreter and
 * the tiered-compilation churn before it settles. A JVM that is 3% slower at
 * steady state but settles in a third of the frames is the better choice for a
 * program someone launches, plays for ten minutes, and quits.
 *
 * <p>Method: take the median of the final window as the run's settled cost, then
 * find the earliest window after which <em>every</em> later sampled window stays
 * within tolerance of it. Requiring all later windows to hold — rather than just
 * the first qualifying one — stops a transient dip during warmup from being read
 * as convergence.
 */
public final class SteadyStateDetector {

    /** Frames per window. ~5 seconds of gameplay at 60Hz. */
    public static final int DEFAULT_WINDOW = 300;

    /** Fraction of the settled median a window may deviate and still count. */
    public static final double DEFAULT_TOLERANCE = 0.02;

    /** Windows are sampled every this many frames rather than at every offset. */
    public static final int DEFAULT_STRIDE = 10;

    private SteadyStateDetector() {
    }

    /**
     * Returns the frame index at which the run reaches steady state, or
     * {@code -1} when {@code count} is too short to hold a single window.
     *
     * <p>A result close to {@code count} means the run was still getting faster
     * when it ended. That is an honest answer rather than a defect, but it is
     * not a useful comparison between runtimes — it says the measured series was
     * too short to contain a settled region, so give the run more frames rather
     * than quoting the number.
     */
    public static int framesToSteadyState(long[] frameNanos, int count) {
        return framesToSteadyState(frameNanos, count, DEFAULT_WINDOW, DEFAULT_TOLERANCE,
                DEFAULT_STRIDE);
    }

    public static int framesToSteadyState(long[] frameNanos, int count, int window,
                                          double tolerance, int stride) {
        if (window <= 0 || stride <= 0) {
            throw new IllegalArgumentException("window and stride must be positive");
        }
        if (count < window) {
            return -1;
        }

        long settled = medianOf(frameNanos, count - window, window);
        if (settled <= 0) {
            return 0;
        }
        double allowed = settled * tolerance;

        int lastStart = count - window;
        // Walk backwards from the settled window: the answer is the earliest
        // start from which no later sampled window has yet drifted out.
        int earliestHolding = lastStart;
        for (int start = lastStart - stride; start >= 0; start -= stride) {
            long median = medianOf(frameNanos, start, window);
            if (Math.abs(median - settled) > allowed) {
                break;
            }
            earliestHolding = start;
        }
        return earliestHolding;
    }

    private static long medianOf(long[] values, int from, int length) {
        long[] slice = Arrays.copyOfRange(values, from, from + length);
        Arrays.sort(slice);
        return SectionTiming.percentile(slice, 50.0);
    }
}
