package com.openggf.trace;

/**
 * A run of consecutive frames where the same field diverged at the same severity.
 */
public record DivergenceGroup(
    String field,
    Severity severity,
    int startFrame,
    int endFrame,
    String expectedAtStart,
    String actualAtStart,
    boolean cascading,
    VerificationGroup verificationGroup
) {
    public DivergenceGroup(String field, Severity severity, int startFrame, int endFrame,
            String expectedAtStart, String actualAtStart, boolean cascading) {
        this(field, severity, startFrame, endFrame, expectedAtStart, actualAtStart,
                cascading, VerificationGroup.PHYSICS);
    }

    public int frameSpan() {
        return endFrame - startFrame + 1;
    }
}

