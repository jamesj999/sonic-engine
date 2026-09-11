package com.openggf.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cumulative garbage-collection counters, captured before and after a measured
 * window so a run can report the collections it actually caused rather than the
 * process total.
 *
 * <p>Collector names differ between runtimes (G1 Young Generation, ZGC Cycles,
 * scavenge, …), so the counters are kept per named collector and only summed for
 * display. A comparison that presented one runtime's total against another's
 * would be comparing different things.
 *
 * @param collectionCounts collections per collector name
 * @param collectionTimeMs collection wall time per collector name
 */
public record GcSnapshot(Map<String, Long> collectionCounts, Map<String, Long> collectionTimeMs) {

    public static GcSnapshot capture() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> times = new LinkedHashMap<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            // A bean reports -1 for a counter it does not maintain; clamp so the
            // deltas below stay monotonic.
            counts.put(gc.getName(), Math.max(gc.getCollectionCount(), 0));
            times.put(gc.getName(), Math.max(gc.getCollectionTime(), 0));
        }
        return new GcSnapshot(counts, times);
    }

    /** This snapshot minus an earlier one — the activity in between. */
    public GcSnapshot since(GcSnapshot before) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> times = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : collectionCounts.entrySet()) {
            String name = entry.getKey();
            counts.put(name, entry.getValue() - before.collectionCounts.getOrDefault(name, 0L));
            times.put(name, collectionTimeMs.getOrDefault(name, 0L)
                    - before.collectionTimeMs.getOrDefault(name, 0L));
        }
        return new GcSnapshot(counts, times);
    }

    public long totalCollections() {
        return collectionCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    public long totalTimeMs() {
        return collectionTimeMs.values().stream().mapToLong(Long::longValue).sum();
    }
}
