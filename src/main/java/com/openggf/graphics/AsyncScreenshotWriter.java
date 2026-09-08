package com.openggf.graphics;


import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps PNG encoding and disk writes off the render thread. Admission bounds all
 * images in flight (including capture and the active write), not just the queue.
 */
public final class AsyncScreenshotWriter implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(AsyncScreenshotWriter.class.getName());
    private final Semaphore capacity;
    private final ThreadPoolExecutor executor;
    private final ImageWriter writer;
    private final Duration shutdownTimeout;

    @FunctionalInterface
    interface ImageWriter {
        void write(RgbaImage image, Path path) throws IOException;
    }

    public AsyncScreenshotWriter() {
        this(2, Duration.ofSeconds(5), ScreenshotCapture::savePNG);
    }

    AsyncScreenshotWriter(int maxPending, Duration shutdownTimeout, ImageWriter writer) {
        if (maxPending <= 0 || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("Positive capacity and nonnegative shutdown timeout required");
        }
        this.capacity = new Semaphore(maxPending);
        this.writer = Objects.requireNonNull(writer);
        this.shutdownTimeout = shutdownTimeout;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxPending), runnable -> {
                    Thread thread = new Thread(runnable, "screenshot-writer");
                    // A stuck filesystem/native write must not prevent process exit.
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Calls capture on the calling (GL/render) thread only after nonblocking
     * admission. The supplier must return a newly owned image and must not retain
     * or mutate it after returning; ownership transfers to the worker.
     * Returns false without capturing when full or closed.
     */
    public boolean captureAndSubmit(Path path, Supplier<RgbaImage> capture) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(capture);
        if (executor.isShutdown() || !capacity.tryAcquire()) {
            return false;
        }
        boolean submitted = false;
        try {
            RgbaImage image = Objects.requireNonNull(capture.get());
            try {
                executor.execute(new WriteTask(image, path));
            } catch (RejectedExecutionException e) {
                if (executor.isShutdown()) {
                    return false;
                }
                throw e;
            }
            submitted = true;
            return true;
        } finally {
            if (!submitted) {
                capacity.release();
            }
        }
    }

    private final class WriteTask implements Runnable {
        private final RgbaImage image;
        private final Path path;

        private WriteTask(RgbaImage image, Path path) {
            this.image = image;
            this.path = path;
        }

        @Override
        public void run() {
            try {
                writer.write(image, path);
                LOGGER.info("Screenshot saved: " + path);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Screenshot failed: " + path, e);
            } finally {
                capacity.release();
            }
        }

        private void discard() {
            capacity.release();
            LOGGER.warning("Screenshot not saved before shutdown deadline: " + path);
        }
    }

    /** Drains accepted writes, with a bounded wait if an encoder/filesystem stalls. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return;
            }
            LOGGER.warning("Screenshot writer did not finish before shutdown deadline");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Interrupted while draining screenshots");
        }
        for (Runnable pending : executor.shutdownNow()) {
            ((WriteTask) pending).discard();
        }
    }
}
