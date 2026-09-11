package com.openggf.game.rewind;

import java.util.Arrays;

/**
 * Fixed-capacity sample collector. Records per-frame timings via
 * {@link #record(long)}; produces a {@link BenchmarkResults.PhaseStats}
 * summary on {@link #summarize()}. Samples beyond capacity are dropped.
 */
public final class BenchmarkTiming {

    private final long[] samples;
    private int count;

    public BenchmarkTiming(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.samples = new long[capacity];
        this.count = 0;
    }

    public void record(long ns) {
        if (count < samples.length) {
            samples[count++] = ns;
        }
    }

    public BenchmarkResults.PhaseStats summarize() {
        if (count == 0) {
            return new BenchmarkResults.PhaseStats(0, 0L, 0L, 0L, 0L, 0L);
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        long sum = 0;
        for (long s : sorted) sum += s;
        long mean = sum / count;
        long p50 = sorted[Math.min(count - 1, (int) (count * 0.50))];
        long p95 = sorted[Math.min(count - 1, (int) (count * 0.95))];
        long p99 = sorted[Math.min(count - 1, (int) (count * 0.99))];
        long max = sorted[count - 1];
        return new BenchmarkResults.PhaseStats(count, mean, p50, p95, p99, max);
    }
}
