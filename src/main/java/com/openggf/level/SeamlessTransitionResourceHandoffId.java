package com.openggf.level;

/** Opaque session-owned resource-handoff identity carried by a request. */
public record SeamlessTransitionResourceHandoffId(long value) {
    public SeamlessTransitionResourceHandoffId {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "resource handoff id must be non-negative");
        }
    }
}
