package com.openggf.testmode;

import com.openggf.trace.catalog.TraceEntry;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Process-held diagnostic for a visual trace that failed to launch or replay. */
public final class TraceLaunchStatus {

    public record Failure(String traceLabel, String reason) {
        public Failure {
            traceLabel = requireText(traceLabel, "traceLabel");
            reason = requireText(reason, "reason");
        }
    }

    private static final AtomicReference<Failure> HELD_FAILURE = new AtomicReference<>();

    private TraceLaunchStatus() {
    }

    public static void record(TraceEntry entry, Throwable failure) {
        Objects.requireNonNull(entry, "entry");
        HELD_FAILURE.set(new Failure(catalogLabel(entry), diagnostic(failure)));
    }

    public static void record(TraceEntry entry, String reason) {
        Objects.requireNonNull(entry, "entry");
        HELD_FAILURE.set(new Failure(catalogLabel(entry), reason));
    }

    public static Optional<Failure> current() {
        return Optional.ofNullable(HELD_FAILURE.get());
    }

    public static void clear() {
        HELD_FAILURE.set(null);
    }

    static String catalogLabel(TraceEntry entry) {
        String identity = entry.isRun()
                ? entry.runManifest().runId()
                : entry.dir().getFileName().toString();
        return entry.gameId() + "/" + identity;
    }

    private static String diagnostic(Throwable failure) {
        if (failure == null) {
            return "The trace session ended before launch completed";
        }
        Throwable cursor = failure;
        String message = null;
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().isBlank()) {
                message = cursor.getMessage();
            }
            cursor = cursor.getCause();
        }
        return message != null ? message : failure.getClass().getSimpleName();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
