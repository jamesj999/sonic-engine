package com.openggf.game;

import java.util.Objects;

/** Scoped host feedback: child menus remain ROM-independent and creator APIs stay unchanged. */
public final class MenuFeedback {
    public enum Cue { NAVIGATE, CONFIRM, CANCEL, ERROR }
    @FunctionalInterface
    public interface Sink { void play(Cue cue); }
    private static final ThreadLocal<Sink> CURRENT = new ThreadLocal<>();
    private MenuFeedback() { }

    public static void withSink(Sink sink, Runnable update) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(update, "update");
        Sink previous = CURRENT.get();
        CURRENT.set(sink);
        try { update.run(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
    public static void emit(Cue cue) {
        Sink sink = CURRENT.get();
        if (sink != null) sink.play(cue);
    }
}
