package com.openggf.testmode;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-held presentation state for a failed visual whole-run trace.
 *
 * <p>The value deliberately contains only structural comparison diagnostics.
 * It outlives gameplay teardown so the title-screen trace picker can present
 * the failure, but it cannot read or write gameplay state.</p>
 */
public final class TraceRunFailureStatus {

    public record Failure(
            int segmentIndex,
            String expectedIdentity,
            String actualIdentity,
            String reason,
            long cursor,
            long stepCount) {

        public Failure {
            if (segmentIndex < 0) {
                throw new IllegalArgumentException("segmentIndex must be non-negative");
            }
            if (cursor < 0) {
                throw new IllegalArgumentException("cursor must be non-negative");
            }
            if (stepCount < 0) {
                throw new IllegalArgumentException("stepCount must be non-negative");
            }
            boolean comparison = expectedIdentity != null || actualIdentity != null;
            if (comparison) {
                expectedIdentity = requireText(expectedIdentity, "expectedIdentity");
                actualIdentity = requireText(actualIdentity, "actualIdentity");
                if (reason != null) {
                    throw new IllegalArgumentException(
                            "comparison failure must not also carry a reason");
                }
            } else {
                reason = requireText(reason, "reason");
            }
        }

        public boolean isComparison() {
            return expectedIdentity != null;
        }
    }

    private static final AtomicReference<Failure> HELD_FAILURE = new AtomicReference<>();

    private TraceRunFailureStatus() {
    }

    /** Replaces any previously held failure with an identity mismatch. */
    public static void recordComparison(
            int segmentIndex,
            String expectedIdentity,
            String actualIdentity,
            long cursor,
            long stepCount) {
        HELD_FAILURE.set(new Failure(
                segmentIndex, expectedIdentity, actualIdentity, null, cursor, stepCount));
    }

    /** Replaces any previously held failure with a structural failure reason. */
    public static void recordReason(
            int segmentIndex,
            String reason,
            long cursor,
            long stepCount) {
        HELD_FAILURE.set(new Failure(
                segmentIndex, null, null, reason, cursor, stepCount));
    }

    /** Returns the held immutable diagnostic for presentation or verification. */
    public static Optional<Failure> current() {
        return Optional.ofNullable(HELD_FAILURE.get());
    }

    /** Idempotently acknowledges and removes the held presentation state. */
    public static void clear() {
        HELD_FAILURE.set(null);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
