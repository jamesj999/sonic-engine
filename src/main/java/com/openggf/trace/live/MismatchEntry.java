package com.openggf.trace.live;

import com.openggf.trace.Severity;

/**
 * One row in the live divergence HUD. {@code repeatCount} aggregates
 * runs of identical mismatches so a single stuck field does not flood
 * the ring buffer.
 */
public record MismatchEntry(
        int frame,
        String field,
        String romValue,
        String engineValue,
        String delta,
        Severity severity,
        int repeatCount) {
    public MismatchEntry withIncrementedRepeat() {
        return new MismatchEntry(frame, field, romValue, engineValue, delta,
                severity, repeatCount + 1);
    }
}
