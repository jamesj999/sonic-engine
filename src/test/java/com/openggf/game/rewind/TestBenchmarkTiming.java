package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestBenchmarkTiming {

    @Test
    void summarizeReportsCorrectStats() {
        BenchmarkTiming t = new BenchmarkTiming(100);
        for (int i = 1; i <= 100; i++) {
            t.record(i * 1000L); // 1000 ns to 100000 ns
        }
        BenchmarkResults.PhaseStats stats = t.summarize();
        assertEquals(100, stats.sampleCount());
        assertEquals(50_500L, stats.meanNs(), 100L); // (1+100)/2 * 1000
        assertEquals(50_000L, stats.p50Ns(), 1000L);
        assertEquals(95_000L, stats.p95Ns(), 1000L);
        assertEquals(99_000L, stats.p99Ns(), 1000L);
        assertEquals(100_000L, stats.maxNs());
    }

    @Test
    void summarizeOnEmptyReturnsZeros() {
        BenchmarkTiming t = new BenchmarkTiming(10);
        BenchmarkResults.PhaseStats stats = t.summarize();
        assertEquals(0, stats.sampleCount());
        assertEquals(0L, stats.meanNs());
    }

    @Test
    void recordRespectsCapacityAndOverwritesNothing() {
        BenchmarkTiming t = new BenchmarkTiming(3);
        t.record(100); t.record(200); t.record(300);
        // Recording a fourth sample is silently dropped (or wraps — pick one).
        // We pick: dropped, to keep summarize semantics simple.
        t.record(400);
        BenchmarkResults.PhaseStats stats = t.summarize();
        assertEquals(3, stats.sampleCount());
        assertEquals(300L, stats.maxNs());
    }
}
