package com.openggf.audio.debug;

import com.openggf.audio.AudioManager;
import com.openggf.audio.ChannelType;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.output.NoDeviceAudioSink;
import com.openggf.audio.output.OpenAlPcmSink;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.PerformanceProfiler;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Isolated final-PCM presentation session used by the sound-test tools.
 */
public final class StandaloneAudioPresentationHost
        implements AutoCloseable {
    private static final long OWNER_CALL_TIMEOUT_SECONDS = 5;
    private static final long CLOSE_TIMEOUT_SECONDS = 2;
    private static final Logger LOGGER = Logger.getLogger(
            StandaloneAudioPresentationHost.class.getName());

    private final String gameId;
    private final AudioManager manager;
    private final Thread ownerThread;
    /**
     * The executor that owns {@link #manager}'s presentation producer. It is
     * present for production hosts and absent for the direct manager fixture
     * used by package tests.
     */
    private final ExecutorService ownerExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean closeRequested;
    private CompletableFuture<Void> closeCompletion =
            new CompletableFuture<>();

    private StandaloneAudioPresentationHost(
            String gameId, AudioManager manager,
            ExecutorService ownerExecutor) {
        this.gameId = gameId;
        this.manager = manager;
        ownerThread = Thread.currentThread();
        this.ownerExecutor = ownerExecutor;
    }

    static StandaloneAudioPresentationHost fromManagerForTesting(
            String gameId, AudioManager manager) {
        return new StandaloneAudioPresentationHost(
                normalizeGameId(gameId), manager, null);
    }

    static StandaloneAudioPresentationHost fromManagerForTesting(
            String gameId, AudioManager manager, ExecutorService ownerExecutor) {
        return new StandaloneAudioPresentationHost(
                normalizeGameId(gameId), manager, ownerExecutor);
    }

    AudioManager managerForTesting() {
        return manager;
    }

    public static StandaloneAudioPresentationHost open(
            String gameId,
            SonicConfigurationService config,
            PerformanceProfiler profiler,
            boolean noDevice) {
        ExecutorService ownerExecutor = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "sound-test-audio-owner");
                    thread.setDaemon(false);
                    return thread;
                });
        Future<StandaloneAudioPresentationHost> creation = ownerExecutor.submit(
                () -> create(gameId, config, profiler, noDevice, ownerExecutor));
        try {
            return creation.get(OWNER_CALL_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            finishLateOpen(ownerExecutor, creation);
            throw new IllegalStateException(
                    "timed out opening standalone audio host", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finishLateOpen(ownerExecutor, creation);
            throw new IllegalStateException(
                    "interrupted opening standalone audio host", interrupted);
        } catch (ExecutionException failure) {
            ownerExecutor.shutdownNow();
            rethrow(failure.getCause());
            throw new AssertionError("unreachable");
        }
    }

    /**
     * Builds a standalone host on its owner executor. The public host methods
     * synchronously marshal callers to that executor, so a Swing or scheduled
     * command cannot touch the producer from the wrong thread.
     */
    private static StandaloneAudioPresentationHost create(
            String gameId,
            SonicConfigurationService config,
            PerformanceProfiler profiler,
            boolean noDevice,
            ExecutorService ownerExecutor) {
        String boundGameId = normalizeGameId(gameId);
        GameAudioProfile profile = profile(boundGameId);
        AudioPresentationSink sink;
        AtomicReference<AudioManager> managerRef = new AtomicReference<>();
        if (noDevice) {
            sink = new NoDeviceAudioSink(48_000);
        } else {
            try {
                sink = OpenAlPcmSink.openDefault(
                        failure -> {
                            AudioManager manager = managerRef.get();
                            if (manager != null) {
                                try {
                                    ownerExecutor.execute(() ->
                                            manager.replaceFailedPresentationSink(failure));
                                } catch (RejectedExecutionException rejected) {
                                    LOGGER.log(Level.FINE,
                                            "Sound-test host is already closing",
                                            rejected);
                                }
                            } else {
                                LOGGER.log(Level.WARNING,
                                        "Sound-test speaker failed", failure);
                            }
                        },
                        warning -> LOGGER.warning(
                                "Sound-test speaker: " + warning));
            } catch (Throwable failure) {
                LOGGER.log(Level.WARNING,
                        "Sound-test device unavailable; using silent output",
                        failure);
                sink = new NoDeviceAudioSink(48_000);
            }
        }
        SmpsCoordFlagHandlerOwner owner =
                new SmpsCoordFlagHandlerOwner(
                        new SmpsCoordFlagRuntimeState());
        AudioManager manager;
        try {
            manager = AudioManager.createStandalonePresentation(
                    boundGameId, profile, config, profiler, sink, owner);
        } catch (Throwable failure) {
            try {
                sink.close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        managerRef.set(manager);
        return new StandaloneAudioPresentationHost(
                boundGameId, manager, ownerExecutor);
    }

    public void playMusic(AbstractSmpsData data, DacData dac) {
        runOnOwner(() -> manager.playStandaloneMusic(data, dac));
    }

    public void playSfx(
            AbstractSmpsData data, DacData dac, float pitch) {
        runOnOwner(() -> manager.playStandaloneSfx(data, dac, pitch));
    }

    public void stopPlayback() {
        runOnOwner(manager::stopStandalonePlayback);
    }

    public void toggleMute(ChannelType type, int channel) {
        runOnOwner(() -> manager.toggleMute(type, channel));
    }

    public void toggleSolo(ChannelType type, int channel) {
        runOnOwner(() -> manager.toggleSolo(type, channel));
    }

    public boolean isMuted(ChannelType type, int channel) {
        return callOnOwner(() -> manager.isMuted(type, channel));
    }

    public boolean isSoloed(ChannelType type, int channel) {
        return callOnOwner(() -> manager.isSoloed(type, channel));
    }

    public void setSpeedShoes(boolean enabled) {
        runOnOwner(() -> manager.setSpeedShoes(enabled));
    }

    public void presentFrame() {
        runOnOwner(() -> {
            manager.presentFrame(PresentationMode.FORWARD);
            manager.update();
        });
    }

    String boundGameId() {
        return gameId;
    }

    @Override
    public void close() {
        if (closed.get()) {
            return;
        }
        if (Thread.currentThread() == ownerThread) {
            synchronized (this) {
                if (closed.get()) {
                    return;
                }
                closeRequested = true;
            }
            closeOnOwner();
            return;
        }
        if (ownerExecutor == null) {
            // Test fixtures intentionally retain the producer's direct owner
            // thread. Rejecting here must leave close retryable by that owner.
            throw new IllegalStateException(
                    "standalone audio host must close on its owner thread");
        }
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            if (!closeRequested) {
                closeRequested = true;
                closeCompletion = new CompletableFuture<>();
                try {
                    ownerExecutor.execute(this::closeOnOwner);
                } catch (RejectedExecutionException rejected) {
                    closeRequested = false;
                    closeCompletion.completeExceptionally(rejected);
                    throw new IllegalStateException(
                            "standalone audio host owner is unavailable",
                            rejected);
                }
            }
            completion = closeCompletion;
        }
        // A cleanup request remains queued after this bounded caller wait. It
        // must never be cancelled here, or a shutdown hook could strand the
        // producer after the host facade has rejected future work.
        await(completion, CLOSE_TIMEOUT_SECONDS, false);
    }

    private void closeOnOwner() {
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            completion = closeCompletion;
        }
        try {
            manager.destroy();
            // Publish completion only after the producer accepted destruction.
            // An owner-thread rejection/failure therefore remains retryable.
            closed.set(true);
            completion.complete(null);
        } catch (Throwable failure) {
            synchronized (this) {
                closeRequested = false;
            }
            completion.completeExceptionally(failure);
            if (ownerExecutor == null) {
                rethrow(failure);
            }
            LOGGER.log(Level.WARNING,
                    "Standalone audio host cleanup failed; it remains retryable",
                    failure);
        } finally {
            if (closed.get() && ownerExecutor != null) {
                ownerExecutor.shutdown();
            }
        }
    }

    private void runOnOwner(Runnable action) {
        callOnOwner(() -> {
            managerForOpenAction(action);
            return null;
        });
    }

    private <T> T callOnOwner(Callable<T> action) {
        if (Thread.currentThread() == ownerThread || ownerExecutor == null) {
            assertOpen();
            return invoke(action);
        }
        try {
            return await(ownerExecutor.submit(() -> {
                assertOpen();
                return action.call();
            }), OWNER_CALL_TIMEOUT_SECONDS);
        } catch (RejectedExecutionException failure) {
            throw new IllegalStateException(
                    "standalone audio host owner is unavailable", failure);
        }
    }

    private void managerForOpenAction(Runnable action) {
        assertOpen();
        action.run();
    }

    private void assertOpen() {
        if (closed.get() || closeRequested) {
            throw new IllegalStateException(
                    closed.get()
                            ? "standalone audio presentation host is closed"
                            : "standalone audio presentation host is closing");
        }
    }

    private static <T> T invoke(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "standalone audio owner action failed", failure);
        }
    }

    private static <T> T await(Future<T> future, long timeoutSeconds) {
        return await(future, timeoutSeconds, true);
    }

    private static <T> T await(
            Future<T> future, long timeoutSeconds, boolean cancelOnTimeout) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted waiting for standalone audio owner", interrupted);
        } catch (TimeoutException timeout) {
            if (cancelOnTimeout) {
                future.cancel(true);
            }
            throw new IllegalStateException(
                    "timed out waiting for standalone audio owner", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "standalone audio owner action failed", cause);
        }
    }

    private static void finishLateOpen(
            ExecutorService ownerExecutor,
            Future<StandaloneAudioPresentationHost> creation) {
        try {
            ownerExecutor.execute(() -> {
                try {
                    StandaloneAudioPresentationHost host = creation.get();
                    host.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.WARNING,
                            "Interrupted cleaning up a late sound-test host",
                            interrupted);
                } catch (ExecutionException failure) {
                    LOGGER.log(Level.WARNING,
                            "Late sound-test host initialization failed",
                            failure.getCause());
                } catch (RuntimeException failure) {
                    LOGGER.log(Level.WARNING,
                            "Late sound-test host cleanup failed", failure);
                } finally {
                    ownerExecutor.shutdown();
                }
            });
        } catch (RejectedExecutionException rejected) {
            // The owner executor is private to this host and should still be
            // alive here; if it was concurrently terminated, interruption is
            // the only remaining cleanup signal available to initialization.
            ownerExecutor.shutdownNow();
            LOGGER.log(Level.WARNING,
                    "Could not queue late sound-test host cleanup", rejected);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(
                "standalone audio host initialization failed", failure);
    }

    private static String normalizeGameId(String gameId) {
        if (gameId == null) {
            throw new IllegalArgumentException("gameId must not be null");
        }
        String normalized = gameId.toLowerCase(Locale.ROOT);
        if (!normalized.equals("s1")
                && !normalized.equals("s2")
                && !normalized.equals("s3k")) {
            throw new IllegalArgumentException(
                    "Unsupported game id: " + gameId);
        }
        return normalized;
    }

    private static GameAudioProfile profile(String gameId) {
        return SoundTestApp.createProfileForGame(gameId);
    }
}
