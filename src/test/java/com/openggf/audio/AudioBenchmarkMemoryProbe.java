package com.openggf.audio;

import com.sun.management.ThreadMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

public final class AudioBenchmarkMemoryProbe {

    private final MemoryMXBean memoryBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final ThreadMXBean threadBean;
    private final long threadId;
    private final boolean allocatedBytesSupported;

    private AudioBenchmarkMemoryProbe() {
        memoryBean = ManagementFactory.getMemoryMXBean();
        gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        java.lang.management.ThreadMXBean rawThreadBean = ManagementFactory.getThreadMXBean();
        ThreadMXBean sunThreadBean = rawThreadBean instanceof ThreadMXBean ? (ThreadMXBean) rawThreadBean : null;
        if (sunThreadBean != null && sunThreadBean.isThreadAllocatedMemorySupported()) {
            try {
                if (!sunThreadBean.isThreadAllocatedMemoryEnabled()) {
                    sunThreadBean.setThreadAllocatedMemoryEnabled(true);
                }
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // Allocation metrics remain available only if the JVM permits them.
            }
        }

        threadBean = sunThreadBean;
        threadId = Thread.currentThread().getId();
        allocatedBytesSupported = threadBean != null
                && threadBean.isThreadAllocatedMemorySupported()
                && threadBean.isThreadAllocatedMemoryEnabled();
    }

    public static AudioBenchmarkMemoryProbe create() {
        return new AudioBenchmarkMemoryProbe();
    }

    public RunResult measureTimedRun(Runnable workload) {
        long heapBefore = readHeapUsedBytes();
        long gcCountBefore = readTotalGcCount();
        long gcTimeBefore = readTotalGcTimeMs();
        long allocatedBefore = readThreadAllocatedBytes();
        long start = System.nanoTime();
        workload.run();
        long elapsed = System.nanoTime() - start;
        long allocatedAfter = readThreadAllocatedBytes();
        long heapAfter = readHeapUsedBytes();
        long gcCountAfter = readTotalGcCount();
        long gcTimeAfter = readTotalGcTimeMs();

        long allocatedBytes = -1;
        if (allocatedBytesSupported && allocatedBefore >= 0 && allocatedAfter >= 0) {
            allocatedBytes = Math.max(0L, allocatedAfter - allocatedBefore);
        }

        return new RunResult(
                elapsed,
                allocatedBytes,
                allocatedBytesSupported && allocatedBefore >= 0 && allocatedAfter >= 0,
                heapBefore,
                heapAfter,
                heapAfter - heapBefore,
                Math.max(0L, gcCountAfter - gcCountBefore),
                Math.max(0L, gcTimeAfter - gcTimeBefore)
        );
    }

    public PeakHeapResult measurePeakHeapBytes(Runnable replayStep, int iterations) {
        long baselineHeapBytes = currentHeapUsedBytes();
        long peakHeapBytes = baselineHeapBytes;
        for (int i = 0; i < iterations; i++) {
            replayStep.run();
            peakHeapBytes = Math.max(peakHeapBytes, currentHeapUsedBytes());
        }
        return new PeakHeapResult(peakHeapBytes, Math.max(0L, peakHeapBytes - baselineHeapBytes));
    }

    public Snapshot snapshot() {
        return new Snapshot(
                readHeapUsedBytes(),
                readTotalGcCount(),
                readTotalGcTimeMs(),
                readThreadAllocatedBytes(),
                allocatedBytesSupported
        );
    }

    public long currentHeapUsedBytes() {
        return readRuntimeHeapUsedBytes();
    }

    private long readHeapUsedBytes() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return heapUsage != null ? heapUsage.getUsed() : 0L;
    }

    private long readRuntimeHeapUsedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
    }

    private long readTotalGcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long count = gcBean.getCollectionCount();
            if (count >= 0) {
                total += count;
            }
        }
        return total;
    }

    private long readTotalGcTimeMs() {
        long total = 0L;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long time = gcBean.getCollectionTime();
            if (time >= 0) {
                total += time;
            }
        }
        return total;
    }

    private long readThreadAllocatedBytes() {
        if (!allocatedBytesSupported) {
            return -1L;
        }
        return Math.max(0L, threadBean.getThreadAllocatedBytes(threadId));
    }

    public record Snapshot(
            long heapUsedBytes,
            long gcCount,
            long gcTimeMs,
            long allocatedBytes,
            boolean allocatedBytesSupported
    ) {
    }

    public record RunResult(
            long elapsedNanos,
            long allocatedBytes,
            boolean allocatedBytesSupported,
            long heapUsedBeforeBytes,
            long heapUsedAfterBytes,
            long heapUsedDeltaBytes,
            long gcCountDelta,
            long gcTimeDeltaMs
    ) {
    }

    public record PeakHeapResult(
            long peakHeapBytes,
            long peakHeapDeltaBytes
    ) {
    }
}
