package com.openggf.game.rewind;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindBenchmarkAudioExtensions {
    private String originalAudioBudgets;
    private String originalMaxCaptureMeanNs;

    @org.junit.jupiter.api.BeforeEach
    void rememberProperties() {
        originalAudioBudgets = System.getProperty("openggf.rewind.benchmark.audioBudgets");
        originalMaxCaptureMeanNs = System.getProperty("openggf.rewind.benchmark.audio.maxCaptureMeanNs");
        System.clearProperty("openggf.rewind.benchmark.audioBudgets");
        System.clearProperty("openggf.rewind.benchmark.audio.maxCaptureMeanNs");
    }

    @AfterEach
    void restoreProperties() {
        restoreProperty("openggf.rewind.benchmark.audioBudgets", originalAudioBudgets);
        restoreProperty("openggf.rewind.benchmark.audio.maxCaptureMeanNs", originalMaxCaptureMeanNs);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void jsonOmitsCountersWhenPhaseHasNone() {
        Map<String, BenchmarkResults> results = new LinkedHashMap<>();
        results.put("phase1.forward.on", new BenchmarkResults(
                "phase1.forward.on",
                new BenchmarkResults.PhaseStats(1, 2, 3, 4, 5, 6),
                7,
                Map.of()));

        String json = RewindBenchmark.toJsonForTests("trace-dir", 60, results);

        assertTrue(!json.contains("\"counters\""));
    }

    @Test
    void jsonEmitsCountersBesidePerSubsystemWhenPresent() {
        Map<String, BenchmarkResults.PhaseStats> perSubsystem = Map.of(
                "audio", new BenchmarkResults.PhaseStats(1, 2, 3, 4, 5, 6));
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("timelineEntries", 12L);
        counters.put("replayedCommands", 8L);
        Map<String, BenchmarkResults> results = new LinkedHashMap<>();
        results.put("phase7.audio.replay-logical", new BenchmarkResults(
                "phase7.audio.replay-logical",
                new BenchmarkResults.PhaseStats(3, 4, 5, 6, 7, 8),
                9,
                perSubsystem,
                counters));

        String json = RewindBenchmark.toJsonForTests("trace-dir", 60, results);

        assertTrue(json.contains("\"perSubsystem\""));
        assertTrue(json.contains("\"counters\""));
        assertTrue(json.contains("\"timelineEntries\": 12"));
        assertTrue(json.contains("\"replayedCommands\": 8"));
    }

    @Test
    void audioBudgetsAreDisabledUnlessOptedIn() {
        Map<String, BenchmarkResults> results = Map.of(
                "phase7.audio.capture-logical", new BenchmarkResults(
                        "phase7.audio.capture-logical",
                        new BenchmarkResults.PhaseStats(1, Long.MAX_VALUE, 0, 0, 0, 0),
                        0,
                        Map.of()));

        assertDoesNotThrow(() -> RewindBenchmark.assertAudioBenchmarkBoundsForTests(results));
    }

    @Test
    void audioBudgetOverrideIsEnforcedWhenOptedIn() {
        System.setProperty("openggf.rewind.benchmark.audioBudgets", "true");
        System.setProperty("openggf.rewind.benchmark.audio.maxCaptureMeanNs", "5");
        Map<String, BenchmarkResults> results = Map.of(
                "phase7.audio.capture-logical", new BenchmarkResults(
                        "phase7.audio.capture-logical",
                        new BenchmarkResults.PhaseStats(1, 6, 0, 0, 0, 0),
                        0,
                        Map.of()),
                "phase7.audio.restore-logical", new BenchmarkResults(
                        "phase7.audio.restore-logical",
                        new BenchmarkResults.PhaseStats(1, 1, 0, 0, 0, 0),
                        0,
                        Map.of()),
                "phase7.audio.replay-logical", new BenchmarkResults(
                        "phase7.audio.replay-logical",
                        new BenchmarkResults.PhaseStats(1, 1, 0, 0, 0, 0),
                        0,
                        Map.of(),
                        Map.of("allocatedBytesSupported", 0L, "allocatedBytes", -1L)));

        assertThrows(AssertionError.class,
                () -> RewindBenchmark.assertAudioBenchmarkBoundsForTests(results));
    }
}
