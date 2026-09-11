package com.openggf.debug;

import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestMemoryStats {
    private static volatile byte[] allocated;

    @Test
    void repeatedAllocationsAccumulateAndAbsentSectionsExpire() {
        requireAllocationCounter();
        MemoryStats stats = new MemoryStats();
        allocate(stats, "work");
        allocate(stats, "work");
        stats.update();
        assertEquals("work", stats.getTopAllocators(1).getFirst().name());
        assertTrue(stats.getTopAllocators(1).getFirst().bytesPerFrame() >= 8192);
        for (int frame = 0; frame < 300; frame++) {
            stats.update();
        }
        assertTrue(stats.getTopAllocators(1).isEmpty());
        allocate(stats, "again");
        stats.update();
        assertFalse(stats.getTopAllocators(1).isEmpty());
        stats.reset();
        assertTrue(stats.getTopAllocators(1).isEmpty());
    }

    @Test
    void disablingTrackingDiscardsPendingAndActiveAllocations() {
        requireAllocationCounter();
        MemoryStats stats = new MemoryStats();
        allocate(stats, "pending");
        stats.beginSection("active");
        allocated = new byte[4096];
        stats.setEnabled(false);
        stats.endSection("active");
        allocate(stats, "disabled");
        stats.update();
        assertTrue(stats.getTopAllocators(5).isEmpty());
        stats.setEnabled(true);
        allocate(stats, "enabled");
        stats.update();
        assertEquals("enabled", stats.getTopAllocators(1).getFirst().name());
    }

    private static void allocate(MemoryStats stats, String name) {
        stats.beginSection(name);
        allocated = new byte[4096];
        stats.endSection(name);
    }

    private static void requireAllocationCounter() {
        assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled());
    }
}
