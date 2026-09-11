package com.openggf.game.rewind;

import java.util.Map;

/**
 * Per-phase benchmark measurement results. JSON-serialisable record.
 */
public record BenchmarkResults(
        String phaseName,
        PhaseStats overall,
        long totalWallTimeNs,
        Map<String, PhaseStats> perSubsystem,
        Map<String, Long> counters
) {
    public BenchmarkResults(String phaseName, PhaseStats overall, long totalWallTimeNs,
                            Map<String, PhaseStats> perSubsystem) {
        this(phaseName, overall, totalWallTimeNs, perSubsystem, Map.of());
    }

    public BenchmarkResults {
        perSubsystem = perSubsystem == null ? Map.of() : Map.copyOf(perSubsystem);
        counters = counters == null ? Map.of() : Map.copyOf(counters);
    }

    public record PhaseStats(
            long sampleCount,
            long meanNs,
            long p50Ns,
            long p95Ns,
            long p99Ns,
            long maxNs
    ) {}
}
