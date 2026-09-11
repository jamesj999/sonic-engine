package com.openggf.tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;

/**
 * Temporary files and directories for tests that cannot use JUnit's
 * {@code @TempDir} — helpers called from a static context, or several
 * independent roots inside one test method.
 *
 * <p>Prefer {@code @TempDir}. It is per-test and needs no bookkeeping. Reach
 * for this only where the injection point does not exist.
 *
 * <p>Everything created here is deleted when the forked JVM exits. Tests used
 * to call {@link Files#createTempDirectory(String, FileAttribute...)} directly
 * and never remove the result, so a full suite run left roughly 1,800
 * directories behind. Where the system temp directory is a RAM-backed tmpfs
 * that accumulated until the filesystem was full, which then broke unrelated
 * features that legitimately need temporary space — including live recording,
 * whose ffmpeg process died with ENOSPC.
 *
 * <p>Surefire also points {@code java.io.tmpdir} at {@code target/test-tmp}, so
 * anything still missed is contained and removed by {@code mvn clean}. That is
 * a backstop for forgetting, not a substitute for cleaning up: this class is
 * how a test cleans up after itself.
 */
public final class TestTempFiles {

    private static final Deque<Path> PENDING = new ArrayDeque<>();

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(TestTempFiles::deleteAll, "test-temp-cleanup"));
    }

    private TestTempFiles() {
    }

    /** As {@link Files#createTempDirectory(String, FileAttribute...)}, but removed on exit. */
    public static Path createTempDirectory(String prefix) throws IOException {
        return register(Files.createTempDirectory(prefix));
    }

    /** As {@link Files#createTempFile(String, String, FileAttribute...)}, but removed on exit. */
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        return register(Files.createTempFile(prefix, suffix));
    }

    private static Path register(Path path) {
        synchronized (PENDING) {
            PENDING.push(path);
        }
        return path;
    }

    private static void deleteAll() {
        Deque<Path> paths;
        synchronized (PENDING) {
            paths = new ArrayDeque<>(PENDING);
            PENDING.clear();
        }
        for (Path path : paths) {
            deleteRecursively(path);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on shutdown.
                }
            });
        } catch (IOException ignored) {
            // Best effort on shutdown.
        }
    }
}
