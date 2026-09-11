package com.openggf.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBenchmarkReportIo {

    @Test
    void reportSurvivesAJsonRoundTrip(@TempDir Path tempDir) throws Exception {
        BenchmarkReport original = sampleReport();
        Path file = tempDir.resolve("nested").resolve("report.json");

        BenchmarkReportIo.write(original, file);
        BenchmarkReport restored = BenchmarkReportIo.read(file);

        assertEquals(original, restored);
    }

    @Test
    void writtenJsonIsReadableInADiff(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("report.json");

        BenchmarkReportIo.write(sampleReport(), file);

        String json = java.nio.file.Files.readString(file);
        assertTrue(json.contains("\n  \""), "report JSON should be indented for review");
        assertTrue(json.contains("trajectoryDigest"));
    }

    @Test
    void representativeIterationPrefersTheFastestWarmPass() {
        BenchmarkReport report = new BenchmarkReport("test", "aiz1", "sonic3k", 0, 0, "update",
                100, 200, JvmEnvironment.capture(),
                List.of(iteration(0, true, 9_000_000),
                        iteration(1, false, 2_000_000),
                        iteration(2, false, 1_000_000)));

        assertEquals(2, report.representativeIteration().orElseThrow().index());
        assertEquals(0, report.coldIteration().orElseThrow().index());
    }

    @Test
    void representativeIterationFallsBackToTheColdPassWhenItIsTheOnlyOne() {
        BenchmarkReport report = new BenchmarkReport("test", "aiz1", "sonic3k", 0, 0, "update",
                100, 200, JvmEnvironment.capture(),
                List.of(iteration(0, true, 9_000_000)));

        assertEquals(0, report.representativeIteration().orElseThrow().index());
    }

    @Test
    void throughputIsFramesOverMeasuredWallTime() {
        BenchmarkReport.Iteration iteration = iteration(0, false, 1_000_000);

        // 600 frames in 1 second.
        assertEquals(600.0, iteration.throughputFps(), 0.001);
    }

    private static BenchmarkReport sampleReport() {
        return new BenchmarkReport("Temurin 21 (G1)", "aiz1", "sonic3k", 0, 0, "update",
                2000, 10000, JvmEnvironment.capture(),
                List.of(iteration(0, true, 3_000_000), iteration(1, false, 1_500_000)));
    }

    private static BenchmarkReport.Iteration iteration(int index, boolean cold, long p50Nanos) {
        long[] samples = new long[600];
        java.util.Arrays.fill(samples, p50Nanos);
        SectionTiming frame = SectionTiming.of("frame", samples, samples.length);
        SectionTiming physics = SectionTiming.of("physics", samples, samples.length);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("G1 Young Generation", 4L);
        Map<String, Long> times = new LinkedHashMap<>();
        times.put("G1 Young Generation", 12L);

        return new BenchmarkReport.Iteration(index, cold, 600, 1_000_000_000L,
                "0123456789abcdef", cold ? 1500 : -1, frame, List.of(physics),
                new GcSnapshot(counts, times), 128L * 1024 * 1024, false);
    }
}
