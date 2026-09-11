package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestSegmentCache {

    private static volatile CompositeSnapshot allocationSink;

    private static CompositeSnapshot snap(int marker) {
        var e = new LinkedHashMap<String, Object>();
        e.put("marker", marker);
        return new CompositeSnapshot(e);
    }

    @Test
    void firstAccessExpandsSegmentAndCachesIt() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        // K=0, request frame 5: expand segment [0, 60) up to offset 5
        var got = cache.snapshotAt(5, snap(0), 0,
                restores::incrementAndGet, stepper);
        assertEquals(1, restores.get(), "must restore keyframe on cold expand");
        assertEquals(5, steps.get(), "must step 5 frames forward (1..5)");
        assertEquals(5, got.get("marker"));
    }

    @Test
    void secondAccessSameSegmentIsCacheHit() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        cache.snapshotAt(5, snap(0), 0, restores::incrementAndGet, stepper);
        // request frame 3 — already cached, no re-expansion
        var got = cache.snapshotAt(3, snap(0), 0, restores::incrementAndGet, stepper);
        assertEquals(1, restores.get(), "no re-expand on cached frame");
        assertEquals(5, steps.get(), "no extra steps");
        assertEquals(3, got.get("marker"));
    }

    @Test
    void crossingSegmentBoundaryRebuilds() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        // Segment 1 [0, 60): expand up to 30
        cache.snapshotAt(30, snap(0), 0, restores::incrementAndGet, stepper);
        int stepsAfterSeg1 = steps.get();
        // Segment 2 [60, 120): expand up to 75
        cache.snapshotAt(75, snap(60), 60, restores::incrementAndGet, stepper);
        assertEquals(2, restores.get(), "second segment requires keyframe restore");
        assertEquals(stepsAfterSeg1 + 15, steps.get(), "stepped forward to offset 15");
    }

    @Test
    void invalidateForcesNextAccessToRebuild() {
        SegmentCache cache = new SegmentCache(60);
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        SegmentCache.Stepper stepper = () -> snap(steps.incrementAndGet());

        cache.snapshotAt(5, snap(0), 0, restores::incrementAndGet, stepper);
        cache.invalidate();
        cache.snapshotAt(5, snap(0), 0, restores::incrementAndGet, stepper);
        assertEquals(2, restores.get());
    }

    @Test
    void cachedSnapshotProbeRequiresExactBaseAndExpandedOffset() {
        SegmentCache cache = new SegmentCache(60);
        cache.snapshotAt(5, snap(0), 0, () -> {}, () -> snap(1));

        assertNotNull(cache.cachedSnapshotAtOrNull(0, 0), "base frame zero is a valid cached offset");
        assertNotNull(cache.cachedSnapshotAtOrNull(5, 0));
        assertNull(cache.cachedSnapshotAtOrNull(-1, 0), "frames below the base are never cached");
        assertNull(cache.cachedSnapshotAtOrNull(6, 0), "unexpanded offsets are never cached");
        assertNull(cache.cachedSnapshotAtOrNull(5, 60), "a snapshot from another base must not leak");

        cache.invalidate();
        assertNull(cache.cachedSnapshotAtOrNull(5, 0));
    }

    @Test
    void cachedSnapshotProbeHasZeroAllocationSlopeAfterWarmup() {
        var bean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean,
                "JVM does not expose per-thread allocation accounting");
        var allocationBean = (com.sun.management.ThreadMXBean) bean;
        Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemorySupported(),
                "JVM does not support per-thread allocation accounting");
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemoryEnabled(),
                "per-thread allocation accounting could not be enabled");
        Assumptions.assumeTrue(
                allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0,
                "per-thread allocation reads are unavailable");
        SegmentCache cache = new SegmentCache(60);
        cache.snapshotAt(5, snap(0), 0, () -> {}, () -> snap(1));
        for (int i = 0; i < 20_000; i++) {
            allocationSink = cache.cachedSnapshotAtOrNull(5, 0);
        }

        long shortRun = allocatedByProbes(allocationBean, cache, 100_000);
        long longRun = allocatedByProbes(allocationBean, cache, 200_000);
        System.out.printf("SEGMENT_CACHE_PROBE_ALLOC short100k=%d long200k=%d slope=%d%n",
                shortRun, longRun, longRun - shortRun);

        assertTrue(longRun - shortRun <= 1_024,
                () -> "cached probe allocation must have zero slope: short=" + shortRun + ", long=" + longRun);
    }

    @Test
    void failedExpansionInvalidatesPartialCache() {
        SegmentCache cache = new SegmentCache(60);

        assertThrows(IllegalStateException.class, () -> cache.snapshotAt(
                5, snap(0), 0, () -> {}, () -> { throw new IllegalStateException("step failed"); }));

        assertNull(cache.cachedSnapshotAtOrNull(0, 0), "failed expansion must not expose even its base");
        assertNull(cache.cachedSnapshotAtOrNull(1, 0), "failed expansion must not expose partial frames");
    }

    @Test
    void nullExpansionInputsAndResultsAreRejectedWithoutCaching() {
        SegmentCache cache = new SegmentCache(60);

        assertThrows(NullPointerException.class,
                () -> cache.snapshotAt(0, null, 0, () -> {}, () -> snap(1)));
        assertThrows(NullPointerException.class,
                () -> cache.snapshotAt(1, snap(0), 0, () -> {}, () -> null));
        assertNull(cache.cachedSnapshotAtOrNull(0, 0));
    }

    @Test
    void expansionRejectsNegativeBaseAndOffsetAtIntervalBoundary() {
        SegmentCache cache = new SegmentCache(60);

        assertThrows(IllegalArgumentException.class,
                () -> cache.snapshotAt(0, snap(-1), -1, () -> {}, () -> snap(0)));
        assertThrows(IllegalArgumentException.class,
                () -> cache.snapshotAt(60, snap(0), 0, () -> {}, () -> snap(1)));
        assertNull(cache.cachedSnapshotAtOrNull(0));
    }

    @Test
    void restoreCallbackFailureInvalidatesPartialCache() {
        SegmentCache cache = new SegmentCache(60);

        assertThrows(IllegalStateException.class, () -> cache.snapshotAt(
                5, snap(0), 0, () -> { throw new IllegalStateException("restore failed"); }, () -> snap(1)));

        assertNull(cache.cachedSnapshotAtOrNull(0));
    }

    private static long allocatedByProbes(com.sun.management.ThreadMXBean bean,
                                          SegmentCache cache,
                                          int count) {
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < count; i++) {
            allocationSink = cache.cachedSnapshotAtOrNull(5, 0);
        }
        return bean.getThreadAllocatedBytes(threadId) - before;
    }
}
