package com.openggf.game.rewind;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindRegistrySnapshotLayoutPerformance {

    private static volatile CompositeSnapshot allocationSink;

    @Test
    void reportsCaptureAllocationAndRetainedContainerBaseline() {
        ThreadMXBean bean = allocationBeanOrSkip();
        RewindRegistry registry = registryWithStableValues(24);

        for (int i = 0; i < 20_000; i++) {
            allocationSink = registry.capture();
        }

        long[] allocatedPerCapture = new long[3];
        for (int repetition = 0; repetition < allocatedPerCapture.length; repetition++) {
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 100_000; i++) {
                allocationSink = registry.capture();
            }
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
            allocatedPerCapture[repetition] = allocated / 100_000L;
        }
        Arrays.sort(allocatedPerCapture);

        List<CompositeSnapshot> retained = new ArrayList<>(1_000);
        for (int i = 0; i < 1_000; i++) {
            retained.add(registry.capture());
        }
        IdentityHashMap<Object, Boolean> totalSeen = new IdentityHashMap<>();
        IdentityHashMap<Object, Boolean> containerSeen = new IdentityHashMap<>();
        long retainedBytes = 0L;
        long retainedContainerBytes = 0L;
        for (CompositeSnapshot snapshot : retained) {
            retainedBytes += RewindBenchmark.estimateStructuralSizeShared(snapshot, totalSeen);
            retainedContainerBytes += RewindBenchmark.estimateCompositeContainerSizeShared(
                    snapshot, containerSeen);
        }

        System.out.printf(
                "rewind snapshot container: captureAllocatedBytes=%d retainedBytes=%d "
                        + "retainedContainerBytes=%d retainedContainerBytesPerCapture=%d%n",
                allocatedPerCapture[1], retainedBytes, retainedContainerBytes,
                retainedContainerBytes / retained.size());
        assertTrue(allocatedPerCapture[1] < 700L,
                "layout-backed capture should stay below 700 allocated bytes; measured "
                        + allocatedPerCapture[1]);
        assertTrue(retainedBytes < 1_000_000L,
                "1,000 layout-backed snapshots should retain below 1 MB; estimated "
                        + retainedBytes);
        assertTrue(retainedContainerBytes < 400_000L,
                "1,000 layout-backed containers should retain below 400 KB; estimated "
                        + retainedContainerBytes);
    }

    private static RewindRegistry registryWithStableValues(int count) {
        RewindRegistry registry = new RewindRegistry();
        for (int i = 0; i < count; i++) {
            String key = "key-" + i;
            Integer value = i;
            registry.register(new RewindSnapshottable<Integer>() {
                @Override
                public String key() {
                    return key;
                }

                @Override
                public Integer capture() {
                    return value;
                }

                @Override
                public void restore(Integer snapshot) {
                    // Measurement fixture: restore work is intentionally empty.
                }
            });
        }
        return registry;
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "Thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(
                bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0L,
                "Thread allocation accounting unavailable for current thread");
        return bean;
    }
}
