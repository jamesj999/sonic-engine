package com.openggf.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TestModAssetRootConcurrentBudget {
    @Test
    void failedPartialReadCannotRejectConcurrentReadsThatFitAfterRollback() throws Exception {
        var limits = ModInputLimits.loweringBuilder()
                .maxAssetBytes(3).maxModValidationBytes(6).build();
        try (var root = (AbstractModAssetRoot) ModAssetRoot.forTests("concurrent rollback", limits);
             var executor = Executors.newFixedThreadPool(3)) {
            var partialRead = new CountDownLatch(1);
            var releaseFailure = new CountDownLatch(1);
            InputStream oversized = new InputStream() {
                private int reads;
                @Override public int read() { throw new UnsupportedOperationException(); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (reads++ == 1) {
                        // The first two bytes have already been reserved. The next
                        // two exceed the per-asset cap and must roll them back.
                        partialRead.countDown();
                        try {
                            if (!releaseFailure.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("test did not release the partial read");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }
                    bytes[offset] = 1;
                    bytes[offset + 1] = 2;
                    return 2;
                }
            };
            var failure = executor.submit(() -> assertThrows(IOException.class,
                    () -> root.readFullyBounded(oversized, 1, 3)));
            assertTrue(partialRead.await(5, TimeUnit.SECONDS));
            var started = new CountDownLatch(2);
            var completed = new CountDownLatch(2);
            var first = executor.submit(() -> readThreeBytes(root, started, completed));
            var second = executor.submit(() -> readThreeBytes(root, started, completed));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                // Before the fix both reads finish here: one is falsely rejected
                // by the pending reservation. Atomic transactions wait instead.
                completed.await(250, TimeUnit.MILLISECONDS);
            } finally {
                releaseFailure.countDown();
            }
            assertTrue(failure.get(5, TimeUnit.SECONDS).getMessage().contains("Asset stream exceeds limit"));
            assertTrue(first.get(5, TimeUnit.SECONDS), "first complete read must fit after rollback");
            assertTrue(second.get(5, TimeUnit.SECONDS), "second complete read must fit after rollback");
            assertThrows(IOException.class, () -> root.readFullyBounded(
                    new ByteArrayInputStream(new byte[]{1}), 1, 1),
                    "the two successful reads must still consume the entire six-byte budget");
        }
    }

    private static boolean readThreeBytes(AbstractModAssetRoot root, CountDownLatch started,
                                           CountDownLatch completed) throws IOException {
        started.countDown();
        try {
            assertArrayEquals(new byte[]{1, 2, 3}, root.readFullyBounded(
                    new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, 3));
            return true;
        } catch (IOException e) {
            if (!e.getMessage().startsWith("Cumulative mod reads exceed validation budget")) throw e;
            return false;
        } finally {
            completed.countDown();
        }
    }
}
