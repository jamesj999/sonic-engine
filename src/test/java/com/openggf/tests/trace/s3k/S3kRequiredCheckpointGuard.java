package com.openggf.tests.trace.s3k;

import com.openggf.trace.TraceEvent;

import java.util.Set;

public final class S3kRequiredCheckpointGuard {

    private static final Set<String> STRICT_ENTRY_CHECKPOINTS =
            Set.of("intro_begin", "aiz1_fire_transition_begin");

    public void validateStrictEntry(int traceFrame, TraceEvent.Checkpoint traceCheckpoint,
                                    TraceEvent.Checkpoint engineCheckpoint) {
        validateStrictEntry(traceFrame, traceCheckpoint, engineCheckpoint, Set.of());
    }

    public void validateStrictEntry(int traceFrame, TraceEvent.Checkpoint traceCheckpoint,
                                    TraceEvent.Checkpoint engineCheckpoint,
                                    Set<String> reachedRequiredCheckpointNames) {
        if (traceCheckpoint == null || !STRICT_ENTRY_CHECKPOINTS.contains(traceCheckpoint.name())) {
            return;
        }
        if (reachedRequiredCheckpointNames.contains(traceCheckpoint.name())) {
            return;
        }
        if (engineCheckpoint == null) {
            throw new IllegalStateException("Required engine checkpoint missing at trace frame "
                    + traceFrame + ": expected " + traceCheckpoint.name());
        }
        if (!traceCheckpoint.name().equals(engineCheckpoint.name())) {
            throw new IllegalStateException("Required engine checkpoint mismatch at trace frame "
                    + traceFrame + ": expected " + traceCheckpoint.name()
                    + " but saw " + engineCheckpoint.name());
        }
    }
}
