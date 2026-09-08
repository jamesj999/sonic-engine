package com.openggf.capture;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link FrameSink} that hands frames to a {@link CaptureEncoder} on a single
 * background thread via a bounded queue. Queue-full behavior follows the
 * configured {@link BackpressurePolicy}. {@code BLOCK} guarantees no drops.
 */
public final class EncoderSink implements FrameSink {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(EncoderSink.class.getName());
    private static final CapturedFrame POISON =
            new CapturedFrame(new byte[0], 0, 0, new short[0], 0, -1L);
    private static final long DEFAULT_STOP_JOIN_TIMEOUT_MILLIS = 5_000L;
    /** A stalled encoder stalls every frame; warn periodically, not per frame. */
    private static final long WARNING_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(5);
    private static final long NEVER_WARNED = Long.MIN_VALUE;

    private final CaptureEncoder encoder;
    private final BackpressurePolicy policy;
    private final BlockingQueue<CapturedFrame> queue;
    private final long stopJoinTimeoutMillis;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong exhaustedFrames = new AtomicLong();
    private final AtomicLong blockedNanos = new AtomicLong();
    private final AtomicLong worstBlockedNanos = new AtomicLong();
    private final AtomicLong lastWarningNanos = new AtomicLong(NEVER_WARNED);
    private Thread worker;
    private volatile CaptureException workerFailure;
    private volatile Path output;
    private final Object lifecycleLock = new Object();
    private volatile boolean terminal;
    private volatile boolean stopping;
    private volatile boolean abortRequested;

    public EncoderSink(CaptureEncoder encoder, BackpressurePolicy policy, int capacity) {
        this(encoder, policy, capacity, DEFAULT_STOP_JOIN_TIMEOUT_MILLIS);
    }

    EncoderSink(CaptureEncoder encoder, BackpressurePolicy policy, int capacity, long stopJoinTimeoutMillis) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (stopJoinTimeoutMillis < 1) {
            throw new IllegalArgumentException("stopJoinTimeoutMillis must be >= 1");
        }
        this.encoder = encoder;
        this.policy = policy;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.stopJoinTimeoutMillis = stopJoinTimeoutMillis;
    }

    public void open(Path output, int width, int height, int fps, int sampleRate) throws CaptureException {
        encoder.open(output, width, height, fps, sampleRate);
        worker = new Thread(this::runWorker, "capture-encoder");
        worker.start();
    }

    @Override
    public void submit(CapturedFrame frame) throws CaptureException {
        boolean accepted = false;
        try {
            checkWorker();
            switch (policy) {
                case BLOCK -> {
                    if (offer(frame)) { accepted = true; break; }
                    long blockStartNanos = System.nanoTime();
                    try {
                        while (!offer(frame)) {
                            checkWorker();
                            // Wake promptly for capacity or failure, including waits shorter
                            // than the health-check interval. Insertion is lifecycle-locked.
                            synchronized (lifecycleLock) {
                                checkWorker();
                                if (queue.remainingCapacity() == 0) lifecycleLock.wait(50);
                            }
                        }
                        accepted = true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CaptureException("interrupted while submitting", e);
                    } finally {
                        recordExhaustion(System.nanoTime() - blockStartNanos);
                    }
                }
                case DROP_OLDEST -> {
                    synchronized (lifecycleLock) {
                        // stop marks its boundary under this same lock before
                        // inserting POISON. A dropping submit must never consume it.
                        checkWorker();
                        while (!queue.offer(frame)) {
                            CapturedFrame removed = queue.poll();
                            if (removed != null) {
                                removed.release();
                                dropped.incrementAndGet();
                            }
                        }
                        accepted = true;
                    }
                }
                case FAIL -> {
                    if (!offer(frame)) throw new CaptureException("capture queue full (FAIL policy)");
                    accepted = true;
                }
            }
        } finally {
            if (!accepted) frame.release();
        }
    }

    private void checkWorker() throws CaptureException {
        if (workerFailure != null) throw new CaptureException("encoder thread failed", workerFailure);
        if (abortRequested || terminal || stopping) throw new CaptureException("capture aborted or stopped");
        if (worker == null || !worker.isAlive()) throw new CaptureException("encoder thread exited unexpectedly");
    }

    private boolean offer(CapturedFrame frame) throws CaptureException {
        synchronized (lifecycleLock) {
            checkWorker();
            return queue.offer(frame);
        }
    }

    @Override
    public Path stop() throws CaptureException {
        synchronized (lifecycleLock) {
            if (terminal) {
                if (abortRequested) throw new CaptureException("capture aborted", workerFailure);
                return output;
            }
            stopping = true;
            lifecycleLock.notifyAll();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopJoinTimeoutMillis);
        try {
            // Deliver the poison pill without hanging: if the worker has already
            // died (e.g. encoder failure) the queue may be full and a blocking
            // put would never return. Offer with a timeout while the worker is
            // alive; bail out as soon as it has exited or recorded a failure.
            while (worker.isAlive() && !queue.offer(POISON, 50, TimeUnit.MILLISECONDS)) {
                if (workerFailure != null || System.nanoTime() >= deadline) {
                    break;
                }
            }
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
            if (worker.isAlive()) {
                abort();
                throw new CaptureException("encoder thread did not stop within "
                        + stopJoinTimeoutMillis + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort();
            throw new CaptureException("interrupted while stopping", e);
        }
        if (abortRequested) {
            terminal = true;
            throw new CaptureException("capture aborted", workerFailure);
        }
        if (workerFailure != null) {
            terminal = true;
            throw new CaptureException("encoder thread failed", workerFailure);
        }
        terminal = true;
        return output;
    }

    public void abort() {
        abort(Duration.ofMillis(stopJoinTimeoutMillis));
    }

    void abort(Duration timeout) {
        long timeoutNanos = Math.max(0, timeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (lifecycleLock) {
            if (terminal || abortRequested) return;
            abortRequested = true;
            lifecycleLock.notifyAll();
        }
        if (encoder instanceof FfmpegEncoder ffmpegEncoder) {
            ffmpegEncoder.abortUntil(deadline);
        } else {
            encoder.abort();
        }
        releaseQueuedFrames();
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        terminal = true;
    }

    public long droppedCount() {
        return dropped.get();
    }

    /** Frames whose submit had to wait for a full queue. */
    public long exhaustedFrameCount() {
        return exhaustedFrames.get();
    }

    /** Total wall-clock time the submitting thread spent waiting on a full queue. */
    public long totalBlockedNanos() {
        return blockedNanos.get();
    }

    /** Longest single wait on a full queue. */
    public long worstBlockedNanos() {
        return worstBlockedNanos.get();
    }

    /**
     * Records one exhausted submit and logs a rate-limited warning.
     * <p>
     * Under {@code BLOCK} this wait happens on the caller's thread — for live
     * recording that is the game thread, so an exhausted queue is directly
     * visible as a stutter. It is worth a warning rather than a silent stat:
     * the queue is sized to absorb bursts, so reaching the bottom of it means
     * the encoder is not keeping up with the content being recorded.
     */
    private void recordExhaustion(long blockedNanos) {
        exhaustedFrames.incrementAndGet();
        this.blockedNanos.addAndGet(blockedNanos);
        worstBlockedNanos.accumulateAndGet(blockedNanos, Math::max);
        long now = System.nanoTime();
        long previous = lastWarningNanos.get();
        // NEVER_WARNED is a sentinel, not a timestamp: nanoTime() may be
        // negative, so subtracting it from `now` would overflow rather than
        // reliably exceed the interval.
        if ((previous != NEVER_WARNED && now - previous < WARNING_INTERVAL_NANOS)
                || !lastWarningNanos.compareAndSet(previous, now)) {
            return;
        }
        LOGGER.warning(String.format(
                "Capture encoder queue exhausted: all %d frames full, submit blocked %d ms"
                        + " (%d exhausted frames, %d ms total, %d ms worst). The encoder is"
                        + " not keeping up; lower the capture resolution, use a faster codec"
                        + " or preset, or raise capture.queueBudgetMb.",
                capacity(), TimeUnit.NANOSECONDS.toMillis(blockedNanos),
                exhaustedFrames.get(),
                TimeUnit.NANOSECONDS.toMillis(this.blockedNanos.get()),
                TimeUnit.NANOSECONDS.toMillis(worstBlockedNanos.get())));
    }

    /** One-line summary for the end of a recording; null when nothing stalled. */
    String exhaustionSummary() {
        long frames = exhaustedFrames.get();
        if (frames == 0) {
            return null;
        }
        return String.format(
                "capture encoder queue (%d frames) was exhausted on %d submits,"
                        + " blocking the submitting thread for %d ms total, %d ms worst",
                capacity(), frames,
                TimeUnit.NANOSECONDS.toMillis(blockedNanos.get()),
                TimeUnit.NANOSECONDS.toMillis(worstBlockedNanos.get()));
    }

    private int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    private void runWorker() {
        try {
            while (!abortRequested) {
                CapturedFrame frame = queue.take();
                synchronized (lifecycleLock) { lifecycleLock.notifyAll(); }
                if (frame == POISON) {
                    output = encoder.finish();
                    return;
                }
                try {
                    encoder.encode(frame);
                } finally {
                    frame.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerFailure = new CaptureException("encoder thread interrupted", e);
            abort();
        } catch (CaptureException e) {
            workerFailure = e;
            abort();
        } catch (Throwable e) {
            workerFailure = new CaptureException("encoder thread failed", e);
            abort();
        } finally {
            releaseQueuedFrames();
        }
    }

    private void releaseQueuedFrames() {
        synchronized (lifecycleLock) {
            CapturedFrame frame;
            while ((frame = queue.poll()) != null) frame.release();
            lifecycleLock.notifyAll();
        }
    }
}
