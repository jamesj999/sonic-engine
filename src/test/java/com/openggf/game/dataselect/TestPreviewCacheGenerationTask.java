package com.openggf.game.dataselect;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TestPreviewCacheGenerationTask {
    @Test
    void coalescesConcurrentRequestsAndRetriesAfterFailure() throws Exception {
        PreviewCacheGenerationTask task = new PreviewCacheGenerationTask(
                Logger.getLogger(getClass().getName()), "Expected preview test failure");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        IOException failure = new IOException("capture failed");
        task.startIfNeeded(() -> true, () -> false, () -> {
            calls.incrementAndGet();
            entered.countDown();
            release.join();
            throw failure;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(task.isRunning());
            task.startIfNeeded(() -> true, () -> false, () -> calls.incrementAndGet());
            assertEquals(1, calls.get());
        } finally {
            release.complete(null);
        }
        task.awaitIfRunning();
        assertFalse(task.isRunning());
        assertSame(failure, task.lastFailure().getCause());

        task.startIfNeeded(() -> true, () -> false, () -> calls.incrementAndGet());
        task.awaitIfRunning();
        assertEquals(2, calls.get());
        assertNull(task.lastFailure());
    }

    @Test
    void eligibilityAndValidCachePreventGeneration() {
        PreviewCacheGenerationTask task = new PreviewCacheGenerationTask(
                Logger.getLogger(getClass().getName()), "Unexpected failure");
        AtomicInteger calls = new AtomicInteger();
        task.startIfNeeded(() -> false, () -> false, () -> calls.incrementAndGet());
        task.startIfNeeded(() -> true, () -> true, () -> calls.incrementAndGet());
        task.awaitIfRunning();
        assertEquals(0, calls.get());
        assertFalse(task.isRunning());
        assertNull(task.lastFailure());
    }
}
