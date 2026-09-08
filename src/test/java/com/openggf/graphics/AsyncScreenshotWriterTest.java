package com.openggf.graphics;

import com.openggf.tests.TestTempFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncScreenshotWriterTest {
    private static final Path FIRST = Path.of("first.png");
    private static final Path SECOND = Path.of("second.png");

    @Test
    void capturesOnCallerTransfersOwnedImageAndBoundsActivePlusQueuedWrites() throws Exception {
        Thread caller = Thread.currentThread();
        RgbaImage first = image(0x12345678);
        RgbaImage second = image(0xABCDEF01);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<RgbaImage> images = new CopyOnWriteArrayList<>();
        List<Path> paths = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AsyncScreenshotWriter writer = new AsyncScreenshotWriter(2, Duration.ofSeconds(2), (image, path) -> {
            worker.set(Thread.currentThread());
            started.countDown();
            await(release);
            images.add(image);
            paths.add(path);
        });
        try {
            assertTrue(writer.captureAndSubmit(FIRST, () -> {
                assertSame(caller, Thread.currentThread());
                return first;
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(writer.captureAndSubmit(SECOND, () -> second));
            assertFalse(writer.captureAndSubmit(Path.of("full.png"), () -> {
                fail("A saturated writer must not allocate or read the framebuffer");
                return first;
            }));
        } finally {
            release.countDown();
            writer.close();
        }
        assertNotSame(caller, worker.get());
        assertTrue(worker.get().isDaemon());
        assertEquals(List.of(FIRST, SECOND), paths, "Close must drain accepted images in order");
        assertSame(first, images.get(0), "The owned image transfers without another pixel copy");
        assertSame(second, images.get(1));
        assertEquals(0x12345678, images.get(0).argb(0, 0));
        assertFalse(writer.captureAndSubmit(FIRST, () -> {
            fail("Closed writer must not capture");
            return first;
        }));
        writer.close();
    }

    @Test
    void captureFailureReturnsCapacityToNextRequest() {
        List<Path> saved = new CopyOnWriteArrayList<>();
        try (AsyncScreenshotWriter writer = new AsyncScreenshotWriter(1, Duration.ofSeconds(2),
                (image, path) -> saved.add(path))) {
            assertThrows(IllegalStateException.class, () -> writer.captureAndSubmit(FIRST, () -> {
                throw new IllegalStateException("readback failed");
            }));
            assertTrue(writer.captureAndSubmit(SECOND, () -> image(1)));
        }
        assertEquals(List.of(SECOND), saved);
    }

    @Test
    void failedWriteIsReportedAndDoesNotPreventQueuedWrite() {
        IOException failure = new IOException("disk failed");
        List<Path> saved = new CopyOnWriteArrayList<>();
        try (RecordedLogs logs = new RecordedLogs();
             AsyncScreenshotWriter writer = new AsyncScreenshotWriter(2, Duration.ofSeconds(2), (image, path) -> {
                 if (path.equals(FIRST)) {
                     throw failure;
                 }
                 saved.add(path);
             })) {
            assertTrue(writer.captureAndSubmit(FIRST, () -> image(1)));
            assertTrue(writer.captureAndSubmit(SECOND, () -> image(2)));
            writer.close();
            assertEquals(List.of(SECOND), saved);
            assertTrue(logs.records.stream().anyMatch(record -> record.getThrown() == failure
                    && record.getMessage().contains(FIRST.toString())));
        }
    }

    @Test
    void stuckWriteHasBoundedShutdownAndReportsDiscardedQueue() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        List<Path> saved = new CopyOnWriteArrayList<>();
        try (RecordedLogs logs = new RecordedLogs()) {
            AsyncScreenshotWriter writer = new AsyncScreenshotWriter(2, Duration.ofMillis(20), (image, path) -> {
                started.countDown();
                // Model a native/filesystem operation which does not respond to interruption.
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // Deliberately uninterruptible for this lifecycle test.
                    }
                }
                saved.add(path);
                finished.countDown();
            });
            try {
                assertTrue(writer.captureAndSubmit(FIRST, () -> image(1)));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(writer.captureAndSubmit(SECOND, () -> image(2)));
                assertTimeoutPreemptively(Duration.ofSeconds(2), writer::close);
                assertTrue(logs.records.stream().anyMatch(record ->
                        record.getMessage().contains("did not finish")));
                assertTrue(logs.records.stream().anyMatch(record ->
                        record.getMessage().contains("not saved")
                                && record.getMessage().contains(SECOND.toString())));
                assertFalse(writer.captureAndSubmit(SECOND, () -> image(2)));
            } finally {
                release.countDown();
                assertTrue(finished.await(2, TimeUnit.SECONDS));
                writer.close();
            }
            assertEquals(List.of(FIRST), saved);
        }
    }

    @Test
    void asynchronousPngPreservesRowsAlphaAndDestination() throws Exception {
        Path path = TestTempFiles.createTempFile("async-screenshot", ".png");
        RgbaImage image = new RgbaImage(2, 2, new int[]{
                0xFFFF0000, 0x8000FF00,
                0xFF0000FF, 0x00123456
        });
        try (AsyncScreenshotWriter writer = new AsyncScreenshotWriter()) {
            assertTrue(writer.captureAndSubmit(path, () -> image));
        }
        RgbaImage loaded = ScreenshotCapture.loadPNG(path);
        assertEquals(2, loaded.width());
        assertEquals(2, loaded.height());
        assertArrayEquals(image.pixels(), loaded.pixels());
    }

    private static RgbaImage image(int pixel) {
        return new RgbaImage(1, 1, new int[]{pixel});
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IOException("Test writer release timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static final class RecordedLogs extends Handler implements AutoCloseable {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getLogger(AsyncScreenshotWriter.class.getName());

        private RecordedLogs() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
