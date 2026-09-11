package com.openggf.bench;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBenchmarkComparison {

    @Test
    void quotesEveryRuntimeAgainstTheFirstAsBaseline() {
        String markdown = BenchmarkComparison.render(List.of(
                report("baseline", 2_000_000, "aaaa"),
                report("challenger", 1_000_000, "aaaa")));

        assertTrue(markdown.contains("Baseline: **baseline**"));
        assertTrue(markdown.contains("-50.0%"), "a twice-as-fast runtime should read -50%");
    }

    @Test
    void divergingTrajectoriesInvalidateTheComparisonLoudly() {
        String markdown = BenchmarkComparison.render(List.of(
                report("a", 2_000_000, "aaaa"),
                report("b", 1_000_000, "bbbb")));

        assertTrue(markdown.contains("**The runtimes diverged.**"),
                "a digest mismatch must be stated, not left for the reader to notice");
        assertTrue(markdown.contains("must not be"));
    }

    @Test
    void matchingTrajectoriesAreConfirmedAsComparable() {
        String markdown = BenchmarkComparison.render(List.of(
                report("a", 2_000_000, "aaaa"),
                report("b", 1_000_000, "aaaa")));

        assertFalse(markdown.contains("**The runtimes diverged.**"));
        assertTrue(markdown.contains("identical trajectory"));
    }

    @Test
    void mismatchedRunParametersAreFlaggedBeforeTheTables() {
        BenchmarkReport other = new BenchmarkReport("b", "hcz1", "sonic3k", 1, 0, "full",
                2000, 5000, JvmEnvironment.capture(),
                List.of(iteration(0, false, 1_000_000, "aaaa", false)));

        String markdown = BenchmarkComparison.render(List.of(report("a", 2_000_000, "aaaa"), other));

        assertTrue(markdown.contains("not directly comparable"));
        assertTrue(markdown.contains("ran a different trace"));
        assertTrue(markdown.contains("ran in full mode"));
        assertTrue(markdown.contains("different frame window"));
        assertTrue(markdown.indexOf("not directly comparable")
                        < markdown.indexOf("## Steady-state frame time"),
                "the warning belongs above the tables it invalidates");
    }

    @Test
    void truncatedSamplesAreFlagged() {
        BenchmarkReport truncated = new BenchmarkReport("b", "aiz1", "sonic3k", 0, 0, "update",
                2000, 10000, JvmEnvironment.capture(),
                List.of(iteration(0, false, 1_000_000, "aaaa", true)));

        String markdown = BenchmarkComparison.render(List.of(report("a", 2_000_000, "aaaa"), truncated));

        assertTrue(markdown.contains("truncated prefix"));
    }

    @Test
    void perSectionTableListsEverySectionSeenInAnyRun() {
        String markdown = BenchmarkComparison.render(List.of(
                report("a", 2_000_000, "aaaa"),
                report("b", 1_000_000, "aaaa")));

        assertTrue(markdown.contains("## Per-section median"));
        assertTrue(markdown.contains("`physics`"));
    }

    @Test
    void emptyInputIsHandled() {
        assertTrue(BenchmarkComparison.render(List.of()).contains("No benchmark reports"));
    }

    private static BenchmarkReport report(String label, long p50Nanos, String digest) {
        return new BenchmarkReport(label, "aiz1", "sonic3k", 0, 0, "update",
                2000, 10000, JvmEnvironment.capture(),
                List.of(iteration(0, true, p50Nanos * 3, digest, false),
                        iteration(1, false, p50Nanos, digest, false)));
    }

    private static BenchmarkReport.Iteration iteration(int index, boolean cold, long p50Nanos,
                                                       String digest, boolean truncated) {
        long[] samples = new long[600];
        java.util.Arrays.fill(samples, p50Nanos);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("G1 Young Generation", 3L);
        Map<String, Long> times = new LinkedHashMap<>();
        times.put("G1 Young Generation", 9L);

        return new BenchmarkReport.Iteration(index, cold, 600, 1_000_000_000L, digest,
                cold ? 1200 : -1,
                SectionTiming.of("frame", samples, samples.length),
                List.of(SectionTiming.of("physics", samples, samples.length)),
                new GcSnapshot(counts, times), 64L * 1024 * 1024, truncated);
    }
}
