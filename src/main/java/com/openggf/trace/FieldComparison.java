package com.openggf.trace;

/**
 * Comparison result for a single field on a single frame.
 */
public record FieldComparison(
    String fieldName,
    String expected,
    String actual,
    Severity severity,
    int delta,
    boolean observedMismatch,
    VerificationGroup verificationGroup
) {
    public FieldComparison(String fieldName, String expected, String actual, Severity severity, int delta) {
        this(fieldName, expected, actual, severity, delta, false, VerificationGroup.PHYSICS);
    }

    public FieldComparison(String fieldName, String expected, String actual, Severity severity,
            int delta, boolean observedMismatch) {
        this(fieldName, expected, actual, severity, delta, observedMismatch,
                VerificationGroup.PHYSICS);
    }

    public static FieldComparison animation(
            String fieldName, String expected, String actual, Severity severity, int delta) {
        return new FieldComparison(fieldName, expected, actual, severity, delta, false,
                VerificationGroup.ANIMATION);
    }

    public boolean isDivergent() {
        return severity != Severity.MATCH;
    }

    public boolean isContextRelevant() {
        return isDivergent() || observedMismatch;
    }
}
