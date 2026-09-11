package com.openggf.level;

import com.openggf.level.resources.PreparedLevelBuild;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Holds at most one level build running ahead of its install.
 *
 * <p>The build runs on a shared daemon thread and is a pure function of the
 * ROM, so it needs no rewind capture: a rewind that re-runs the transition
 * simply finds the slot empty and loads synchronously. The install side
 * always joins the build, so the frame on which a level is installed is
 * decided by the transition owner, never by host thread timing.
 */
final class LevelLoadPreparer {
    private static final Logger LOGGER = Logger.getLogger(LevelLoadPreparer.class.getName());

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "level-load-preparer");
        thread.setDaemon(true);
        return thread;
    });

    /** Test seam: when false, {@link #prepare} is a no-op and installs load synchronously. */
    private static volatile boolean enabled = true;

    private record Pending(Object owner, int levelIndex, String mutationKey,
                           Future<PreparedLevelBuild> future) {
    }

    private Pending pending;
    private int installedFromPreparedCount;

    static void setEnabledForTests(boolean value) {
        enabled = value;
    }

    /** Starts (or restarts) preparation of {@code levelIndex}, discarding any other pending build. */
    synchronized void prepare(Object owner, int levelIndex, String mutationKey,
                              Callable<PreparedLevelBuild> task) {
        discard();
        if (!enabled) {
            return;
        }
        pending = new Pending(Objects.requireNonNull(owner), levelIndex, mutationKey,
                EXECUTOR.submit(task));
    }

    /**
     * Takes only the owning loader's matching transition build, joining it if still running.
     * Returns {@code null} when nothing was prepared for that index or the
     * build failed; the caller then loads synchronously.
     */
    synchronized PreparedLevelBuild take(Object owner, int levelIndex, String mutationKey) {
        if (pending == null) {
            return null;
        }
        if (pending.owner() != owner || pending.levelIndex() != levelIndex
                || !Objects.equals(pending.mutationKey(), mutationKey)) {
            discard();
            return null;
        }
        Pending taken = pending;
        pending = null;
        try {
            PreparedLevelBuild prepared = taken.future().get();
            installedFromPreparedCount++;
            return prepared;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Prepared level build interrupted; loading synchronously");
            return null;
        } catch (ExecutionException e) {
            LOGGER.warning("Prepared level build failed; loading synchronously: " + e.getCause());
            return null;
        }
    }

    /** Drops any pending build. Safe to call when nothing is pending. */
    synchronized void discard() {
        if (pending != null) {
            pending.future().cancel(false);
            pending = null;
        }
    }

    /** Number of installs served from a prepared build (test observability). */
    synchronized int installedFromPreparedCount() {
        return installedFromPreparedCount;
    }
}
