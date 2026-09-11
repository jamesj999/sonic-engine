package com.openggf.game.timing;

import java.util.Objects;

/** Engine-owned pending identity visible through the narrow recorded capability. */
public record PendingRecordedSubmission(
        HardwareWorkHandle handle,
        boolean exportableAcrossSegment) {

    public PendingRecordedSubmission {
        Objects.requireNonNull(handle, "handle");
    }
}
