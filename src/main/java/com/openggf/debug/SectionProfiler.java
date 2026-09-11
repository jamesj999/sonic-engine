package com.openggf.debug;

/**
 * Minimal section-timing surface. Implementations record nanosecond and
 * allocation deltas between matched {@link #beginSection(String)} and
 * {@link #endSection(String)} calls; sections do not nest (a new
 * {@code beginSection} implicitly ends the active one).
 */
public interface SectionProfiler {

    /** Opens a section. Implicitly ends any currently-active section. */
    void beginSection(String name);

    /**
     * Closes the named section if it is currently active. No-op when the
     * active section's name does not match — this is normal because nested
     * {@code beginSection} calls implicitly close the outer section.
     */
    void endSection(String name);

    /** Profiler that does nothing — useful when callers want to opt out without null checks. */
    SectionProfiler NOOP = new SectionProfiler() {
        @Override public void beginSection(String name) {}
        @Override public void endSection(String name) {}
    };
}
