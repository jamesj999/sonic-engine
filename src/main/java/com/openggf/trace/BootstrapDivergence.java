package com.openggf.trace;

/**
 * A divergence detected at frame 0 when the engine's natural simulation state
 * does not match the recorded ROM snapshot for that field.
 *
 * <p>Bootstrap divergences are produced by
 * {@link TraceBinder#compareBootstrapFrame0(TraceData, EngineSnapshot)} and
 * surface ahead of per-frame divergences in {@link DivergenceReport}. They
 * are an assertion that the engine arrived at the correct frame-0 state by
 * running the same simulation as the ROM (title card, level init, etc.) and
 * not via state hydration from the trace.
 *
 * <p>This is an additive type that lives alongside the existing per-frame
 * {@link Severity} enum. Bootstrap-specific severity values are deliberately
 * kept on their own nested enum so the bootstrap and per-frame code paths
 * can evolve independently.
 *
 * @param field   recorder-side field label, e.g. {@code "player_history.x[12]"}
 *                or {@code "tails_cpu.routine"} or {@code "object_slot[5].x_pos"}
 * @param severity {@link Severity#ERROR} for hard mismatches,
 *                 {@link Severity#WARNING} for missing or unverifiable input
 * @param expected recorded ROM value, stringified for display (typically hex)
 * @param actual   engine value, stringified for display (typically hex)
 * @param context  human-readable description of the divergence (never blank)
 */
public record BootstrapDivergence(
        String field,
        Severity severity,
        String expected,
        String actual,
        String context) {

    /**
     * Severity classification for bootstrap divergences. Kept as a nested
     * enum so callers explicitly request bootstrap severity and we can
     * extend it later without polluting the per-frame {@link com.openggf.trace.Severity}
     * enum.
     */
    public enum Severity {
        /** Mismatched recorded value. */
        ERROR,
        /** Snapshot missing from the trace or otherwise unverifiable. */
        WARNING
    }
}
