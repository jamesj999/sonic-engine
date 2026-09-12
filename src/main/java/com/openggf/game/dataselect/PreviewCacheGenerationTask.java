package com.openggf.game.dataselect;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns one asynchronous preview generation job, including failure reporting and retries. */
public final class PreviewCacheGenerationTask {
    @FunctionalInterface
    public interface Generation {
        void run() throws IOException;
    }

    private final Logger logger;
    private final String failureMessage;
    private volatile CompletableFuture<Void> inFlight;
    private volatile Throwable lastFailure;
    private long generationId;

    public PreviewCacheGenerationTask(Logger logger, String failureMessage) {
        this.logger = logger;
        this.failureMessage = failureMessage;
    }

    public synchronized void startIfNeeded(BooleanSupplier eligible, BooleanSupplier cacheValid,
                                           Generation generation) {
        if (!eligible.getAsBoolean() || isRunning() || cacheValid.getAsBoolean()) {
            return;
        }
        generationId++;
        lastFailure = null;
        CompletableFuture<Void> next = CompletableFuture.runAsync(() -> {
            try {
                generation.run();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        inFlight = next;
        next.whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (inFlight == next) {
                    if (failure != null) {
                        recordFailure(failure);
                    }
                    inFlight = null;
                }
            }
        });
    }

    public void awaitIfRunning() {
        CompletableFuture<Void> future;
        long awaitedGeneration;
        synchronized (this) {
            future = inFlight;
            awaitedGeneration = generationId;
        }
        if (future != null) {
            try {
                future.join();
            } catch (CompletionException e) {
                synchronized (this) {
                    // A completed older job must not overwrite a retry's result.
                    if (generationId == awaitedGeneration) {
                        recordFailure(e);
                    }
                }
            }
        }
    }

    public boolean isRunning() {
        CompletableFuture<Void> future = inFlight;
        return future != null && !future.isDone();
    }

    public Throwable lastFailure() {
        return lastFailure;
    }

    private void recordFailure(Throwable throwable) {
        Throwable failure = throwable;
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (lastFailure != failure) {
            lastFailure = failure;
            logger.log(Level.WARNING, failureMessage, failure);
        }
    }
}
