package com.openggf.tests.trace;

import com.openggf.trace.FieldComparison;
import com.openggf.trace.Severity;

import java.util.Objects;

/**
 * Shared field-comparison helpers for the special-stage trace replay bases
 * (S1/S2/S3K). These were byte-identical private static helpers in each base;
 * hoisting them here removes the cross-base copy-paste without changing any
 * comparison predicate, severity, or emitted value.
 *
 * <p>Read-only: every method derives a {@link FieldComparison} or formats a
 * value; none writes recorded trace state back into engine state.
 */
public final class TraceFieldComparisons {

    private TraceFieldComparisons() {
    }

    public static FieldComparison cmp(String name, String expected, String actual,
                                      Severity mismatchSeverity) {
        boolean match = Objects.equals(expected, actual);
        Severity severity = match ? Severity.MATCH : mismatchSeverity;
        return new FieldComparison(name, expected, actual, severity, numericDelta(expected, actual));
    }

    public static int numericDelta(String expected, String actual) {
        try {
            return Integer.parseInt(actual) - Integer.parseInt(expected);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String str(int value) {
        return Integer.toString(value);
    }

    public static String bool(boolean value) {
        return Boolean.toString(value);
    }
}
