package com.openggf.trace.replay.runs;

import com.openggf.trace.TraceData;

import java.util.Objects;

/**
 * One active run segment's loaded comparison payload.
 *
 * <p>The lease deliberately exposes payload only while it is open. Closing it
 * breaks the references from a replay session to its parsed trace data before
 * the next segment is loaded.
 */
public final class ActiveSegmentPayload implements AutoCloseable {
    private final TraceRunSegmentDescriptor descriptor;
    private TraceData trace;
    private TraceRunSpecialStageRows specialStageRows;
    private boolean closed;

    ActiveSegmentPayload(TraceRunSegmentDescriptor descriptor,
            TraceData trace, TraceRunSpecialStageRows specialStageRows) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.specialStageRows = specialStageRows;
        boolean special = "special_stage".equals(descriptor.segment().kind());
        if (special != (specialStageRows != null)) {
            throw new IllegalArgumentException(
                    "special-stage payload shape does not match descriptor");
        }
    }

    public TraceRunSegmentDescriptor descriptor() {
        requireOpen();
        return descriptor;
    }

    public TraceData trace() {
        requireOpen();
        return trace;
    }

    public TraceRunSpecialStageRows specialStageRows() {
        requireOpen();
        return specialStageRows;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        specialStageRows = null;
        trace = null;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("segment payload is closed");
        }
    }
}
