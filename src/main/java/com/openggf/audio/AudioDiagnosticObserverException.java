package com.openggf.audio;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Marks a diagnostic observer failure so presentation mixing cannot demote it
 * to an ordinary failed voice. Complete-run capture must abort loudly.
 */
public final class AudioDiagnosticObserverException
        extends RuntimeException {
    public AudioDiagnosticObserverException(RuntimeException cause) {
        super("audio diagnostic observer failed", cause);
    }

    public static void invoke(Runnable callback) {
        try {
            callback.run();
        } catch (AudioDiagnosticObserverException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AudioDiagnosticObserverException(failure);
        }
    }

    /**
     * Preserves observer failures across cache, rollback, and restore wrappers.
     * Those paths intentionally translate ordinary runtime failures, but a
     * tooling capture failure is a terminal diagnostic result.
     */
    public static void rethrowIfPresent(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        AudioDiagnosticObserverException diagnostic =
                find(failure, visited);
        if (diagnostic != null) {
            throw diagnostic;
        }
    }

    private static AudioDiagnosticObserverException find(
            Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) {
            return null;
        }
        if (failure instanceof AudioDiagnosticObserverException diagnostic) {
            return diagnostic;
        }
        AudioDiagnosticObserverException inCause =
                find(failure.getCause(), visited);
        if (inCause != null) {
            return inCause;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            AudioDiagnosticObserverException nested =
                    find(suppressed, visited);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
