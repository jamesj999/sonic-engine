package com.openggf.game.timing;

import java.util.List;

/**
 * The recorded run ended while the engine still held production submissions
 * the recorded stream never completed.
 *
 * <p>Like {@link UnmatchedRecordedCompletionException} this is the mirror of a
 * genuinely ambiguous event, and for the same reason. In a converged run a
 * leftover submission is a real contract concern: the ROM drained that work and
 * the recorded stream says so, so the engine holding it open means the timing
 * port disagrees with the stream. In a diverged run it is a pure downstream
 * symptom — the engine went somewhere the ROM never went, so it never reached
 * the drain point that would have retired the job. One message cannot carry
 * both verdicts, so it must not carry verdict authority.
 *
 * <p>The release side is untouched. A leftover submission is <em>not</em>
 * admitted, prepared, released or retired by this path; it is only described.
 * Recorded admission is ended before this is thrown, so the run really is over
 * either way, and a caller that catches it can report the leftovers but can
 * never turn them into work.
 *
 * <p>Every other recorded-admission failure remains a plain
 * {@link IllegalStateException} and still aborts.
 */
public final class PendingRecordedSubmissionsException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient List<PendingRecordedSubmission> pending;

    public PendingRecordedSubmissionsException(
            String message, List<PendingRecordedSubmission> pending) {
        super(message);
        this.pending = List.copyOf(pending);
    }

    /** The submissions the engine still held, for reporting only. */
    public List<PendingRecordedSubmission> pending() {
        return pending;
    }
}
