package com.openggf.game.timing;

import java.util.Objects;

/** Stable runtime identity allocated independently for submitted hardware work. */
public record HardwareWorkHandle(
        HardwareWorkKind kind,
        long ordinal,
        String submissionFingerprint) {

    public HardwareWorkHandle {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(submissionFingerprint, "submissionFingerprint");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
    }
}
