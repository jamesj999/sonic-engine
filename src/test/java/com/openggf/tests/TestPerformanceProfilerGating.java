package com.openggf.tests;

import com.openggf.debug.MemoryStats;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.ProfileSnapshot;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TestPerformanceProfilerGating {

    @AfterEach
    void resetProfiler() {
        PerformanceProfiler profiler = PerformanceProfiler.getInstance();
        setEnabled(profiler, true);
        profiler.reset();
    }

    @Test
    void disabledProfilingSkipsSectionTimingAndAllocatorRecording() {
        PerformanceProfiler profiler = PerformanceProfiler.getInstance();
        profiler.reset();

        setEnabled(profiler, false);

        profiler.beginFrame();
        profiler.beginSection("render");
        profiler.endSection("render");
        profiler.endFrame();

        setEnabled(profiler, true);
        profiler.beginFrame();
        profiler.endFrame();

        ProfileSnapshot snapshot = profiler.getSnapshot();
        MemoryStats.Snapshot memorySnapshot = profiler.memoryStats().snapshot();

        assertTrue(snapshot.sections().isEmpty(),
                "Disabled profiling should not record section timing");
        assertTrue(memorySnapshot.topAllocators().isEmpty(),
                "Disabled profiling should not record allocator data");
    }

    @Test
    void profilerRejectsCrossThreadMutationAfterOwnerThreadIsClaimed() throws Exception {
        PerformanceProfiler profiler = PerformanceProfiler.getInstance();
        profiler.reset();
        profiler.beginFrame();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                profiler.beginSection("audio.callback");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        worker.join();

        assertInstanceOf(IllegalStateException.class, thrown.get());
        profiler.endFrame();
    }

    private static void setEnabled(PerformanceProfiler profiler, boolean enabled) {
        try {
            Method method = PerformanceProfiler.class.getDeclaredMethod("setEnabled", boolean.class);
            method.setAccessible(true);
            method.invoke(profiler, enabled);
        } catch (NoSuchMethodException e) {
            fail("PerformanceProfiler should expose setEnabled(boolean) for profiler gating");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to toggle PerformanceProfiler enabled state", e);
        }
    }
}
