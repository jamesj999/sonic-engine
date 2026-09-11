package com.openggf.bench;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestGcSnapshot {

    @Test
    void deltaReportsOnlyTheActivityBetweenTwoCaptures() {
        GcSnapshot before = snapshot(Map.of("G1 Young Generation", 10L), Map.of("G1 Young Generation", 100L));
        GcSnapshot after = snapshot(Map.of("G1 Young Generation", 14L), Map.of("G1 Young Generation", 160L));

        GcSnapshot delta = after.since(before);

        assertEquals(4, delta.totalCollections());
        assertEquals(60, delta.totalTimeMs());
    }

    @Test
    void aCollectorAbsentFromTheBaselineCountsInFull() {
        // A collector whose first collection happens inside the measured window
        // has no baseline entry; its whole count belongs to the window.
        GcSnapshot before = snapshot(Map.of("G1 Young Generation", 10L),
                Map.of("G1 Young Generation", 100L));
        Map<String, Long> afterCounts = new LinkedHashMap<>();
        afterCounts.put("G1 Young Generation", 10L);
        afterCounts.put("G1 Old Generation", 2L);
        Map<String, Long> afterTimes = new LinkedHashMap<>();
        afterTimes.put("G1 Young Generation", 100L);
        afterTimes.put("G1 Old Generation", 300L);

        GcSnapshot delta = new GcSnapshot(afterCounts, afterTimes).since(before);

        assertEquals(2, delta.totalCollections());
        assertEquals(300, delta.totalTimeMs());
    }

    @Test
    void captureClampsUnsupportedCountersToZero() {
        GcSnapshot captured = GcSnapshot.capture();

        assertFalse(captured.collectionCounts().isEmpty(), "a JVM always exposes some collector");
        assertFalse(captured.collectionCounts().values().stream().anyMatch(count -> count < 0),
                "a bean reporting -1 for an unsupported counter must not go negative");
        assertFalse(captured.collectionTimeMs().values().stream().anyMatch(time -> time < 0));
    }

    private static GcSnapshot snapshot(Map<String, Long> counts, Map<String, Long> times) {
        return new GcSnapshot(new LinkedHashMap<>(counts), new LinkedHashMap<>(times));
    }
}
