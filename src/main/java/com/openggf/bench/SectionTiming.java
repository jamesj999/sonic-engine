package com.openggf.bench;

import java.util.Arrays;

/**
 * Distribution summary for one measured section (or the whole frame) over a run.
 *
 * <p>Percentiles rather than a mean, because that is where JVMs actually differ.
 * Two runtimes can post identical mean frame times while one of them stalls for
 * 40ms twice a second — which is the difference between a game that feels smooth
 * and one that does not. p99 and max are the interesting columns; the mean is
 * carried for completeness.
 *
 * @param name        section name, or {@code "frame"} for whole-frame work time
 * @param frames      number of samples the summary was computed from
 * @param meanNanos   arithmetic mean
 * @param p50Nanos    median
 * @param p90Nanos    90th percentile
 * @param p99Nanos    99th percentile
 * @param p999Nanos   99.9th percentile
 * @param maxNanos    worst single frame
 * @param totalNanos  sum across all samples
 */
public record SectionTiming(String name, int frames, double meanNanos,
                            long p50Nanos, long p90Nanos, long p99Nanos, long p999Nanos,
                            long maxNanos, long totalNanos) {

    /**
     * Summarises the first {@code count} entries of {@code samples}. The input
     * is not modified — percentiles sort a copy.
     */
    public static SectionTiming of(String name, long[] samples, int count) {
        if (count <= 0) {
            return new SectionTiming(name, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);

        long total = 0;
        for (int i = 0; i < count; i++) {
            total += sorted[i];
        }
        return new SectionTiming(name, count, (double) total / count,
                percentile(sorted, 50.0), percentile(sorted, 90.0),
                percentile(sorted, 99.0), percentile(sorted, 99.9),
                sorted[count - 1], total);
    }

    /**
     * Nearest-rank percentile over an already-sorted array. Nearest-rank (rather
     * than an interpolating definition) keeps every reported figure an actually
     * observed frame time, so a p99 can always be traced back to a real frame.
     */
    static long percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.min(Math.max(rank, 1), sorted.length) - 1];
    }

    public double meanMillis() {
        return meanNanos / 1_000_000.0;
    }

    public double p50Millis() {
        return p50Nanos / 1_000_000.0;
    }

    public double p99Millis() {
        return p99Nanos / 1_000_000.0;
    }

    public double maxMillis() {
        return maxNanos / 1_000_000.0;
    }

    /**
     * Frames per second this timing would sustain if it were the only work in
     * the frame. Meaningful for the whole-frame summary; for a single section it
     * reads as a headroom ceiling.
     */
    public double effectiveFps() {
        return meanNanos > 0 ? 1_000_000_000.0 / meanNanos : 0;
    }
}
