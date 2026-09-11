package com.openggf.game.timing;

/**
 * A contiguous run of recorded hardware-work ordinals that production does not
 * reproduce as submissions.
 *
 * <p>This is identity bookkeeping, not work. A span names only the first and
 * last recorded ordinal of one kind; it carries no payload, no ROM descriptor
 * and no readiness, and it can neither create nor release a hardware job.
 */
public record RecordedOrdinalSpan(long firstOrdinal, long lastOrdinal) {

    public RecordedOrdinalSpan {
        if (firstOrdinal < 0) {
            throw new IllegalArgumentException(
                    "recorded ordinal span must be non-negative: " + firstOrdinal);
        }
        if (lastOrdinal < firstOrdinal) {
            throw new IllegalArgumentException(
                    "recorded ordinal span must not run backward: "
                            + firstOrdinal + ".." + lastOrdinal);
        }
    }

    /** The ordinal production must allocate next once the span is crossed. */
    public long nextOrdinal() {
        return Math.addExact(lastOrdinal, 1L);
    }
}
