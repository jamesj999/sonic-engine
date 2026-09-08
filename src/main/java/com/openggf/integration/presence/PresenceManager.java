package com.openggf.integration.presence;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PresenceManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PresenceManager.class.getName());
    private static final long TIMER_UPDATE_INTERVAL_MS = 15_000L;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(1);

    private final boolean configuredEnabled;
    private final boolean showTimer;
    private final boolean showZone;
    private final PresenceSnapshotProvider snapshotProvider;
    private final PresenceFormatter formatter;
    private final PresenceClient client;
    private final LongSupplier clockMillis;
    private final ExecutorService worker;
    private final AtomicReference<PresencePayload> pendingPayload = new AtomicReference<>();
    private final AtomicBoolean workerScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object clientCloseMonitor = new Object();

    private volatile boolean enabledForRun;
    private volatile Thread clientCloseThread;
    private PresenceSnapshot lastSnapshot;
    private long lastPublishMillis = Long.MIN_VALUE;
    private boolean workerConnected;

    public PresenceManager(boolean configuredEnabled,
                           boolean showTimer,
                           boolean showZone,
                           PresenceSnapshotProvider snapshotProvider,
                           PresenceFormatter formatter,
                           PresenceClient client,
                           LongSupplier clockMillis) {
        this.configuredEnabled = configuredEnabled;
        this.showTimer = showTimer;
        this.showZone = showZone;
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.client = Objects.requireNonNull(client, "client");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.enabledForRun = configuredEnabled;
        this.worker = newWorker();
    }

    public void tick() {
        if (!configuredEnabled || !enabledForRun || closed.get()) {
            return;
        }
        try {
            // RuntimePresenceSnapshotProvider reads gameplay-owned state here,
            // on the game thread. Only the immutable payload crosses to the
            // worker; the worker never reaches back into gameplay owners.
            PresenceSnapshot snapshot = snapshotProvider.capture();
            if (!shouldPublish(snapshot)) {
                return;
            }
            PresencePayload payload = formatter.format(snapshot, showTimer, showZone);
            enqueueLatest(payload);
            lastSnapshot = snapshot;
            lastPublishMillis = clockMillis.getAsLong();
        } catch (Exception e) {
            LOGGER.log(Level.FINE,
                    "Discord Rich Presence disabled for this run after a client failure.", e);
            disableForRun();
        }
    }

    public boolean isEnabledForRun() {
        return enabledForRun;
    }

    private boolean shouldPublish(PresenceSnapshot snapshot) {
        if (lastSnapshot == null) {
            return true;
        }
        if (!lastSnapshot.sameExceptTimer(snapshot)) {
            return true;
        }
        if (Objects.equals(lastSnapshot, snapshot)) {
            return false;
        }
        return clockMillis.getAsLong() - lastPublishMillis >= TIMER_UPDATE_INTERVAL_MS;
    }

    private void enqueueLatest(PresencePayload payload) {
        pendingPayload.set(payload);
        if (!workerScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::drainPending);
        } catch (RejectedExecutionException rejected) {
            workerScheduled.set(false);
            pendingPayload.set(null);
            throw rejected;
        }
    }

    private void drainPending() {
        try {
            while (!closed.get() && enabledForRun) {
                PresencePayload payload = pendingPayload.getAndSet(null);
                if (payload == null) {
                    return;
                }
                if (!workerConnected) {
                    client.connect();
                    workerConnected = true;
                }
                client.update(payload);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE,
                    "Discord Rich Presence disabled for this run after a client failure.", e);
            disableForRun();
        } finally {
            workerScheduled.set(false);
            if (!closed.get() && enabledForRun && pendingPayload.get() != null) {
                try {
                    enqueueWorkerAfterDrain();
                } catch (RejectedExecutionException rejected) {
                    disableForRun();
                }
            }
        }
    }

    private void enqueueWorkerAfterDrain() {
        if (!workerScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::drainPending);
        } catch (RejectedExecutionException rejected) {
            workerScheduled.set(false);
            throw rejected;
        }
    }

    private void disableForRun() {
        enabledForRun = false;
        pendingPayload.set(null);
        startClientClose();
    }

    private void startClientClose() {
        synchronized (clientCloseMonitor) {
            if (clientCloseThread != null) {
                return;
            }
            Thread closer = new Thread(() -> {
                try {
                    client.close();
                } catch (Exception closeFailure) {
                    LOGGER.log(Level.FINE,
                            "Failed to close Discord Rich Presence client.", closeFailure);
                }
            }, "discord-presence-close");
            closer.setDaemon(true);
            clientCloseThread = closer;
            closer.start();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        enabledForRun = false;
        pendingPayload.set(null);
        long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        startClientClose();
        awaitThread(clientCloseThread, deadline);
        worker.shutdownNow();
        awaitWorker(deadline);
    }

    private void awaitWorker(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            worker.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitThread(Thread thread, long deadline) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            thread.join(TimeUnit.NANOSECONDS.toMillis(remaining));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService newWorker() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "discord-presence-worker");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
