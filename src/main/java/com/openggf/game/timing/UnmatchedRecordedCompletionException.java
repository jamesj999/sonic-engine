package com.openggf.game.timing;

/**
 * A recorded completion edge had no matching engine-pending, prepared
 * production submission.
 *
 * <p>This is the one admission outcome that is <em>not</em> a contract
 * violation by itself: when the engine has already diverged it never reached
 * the ROM's submission point, so the recorded edge describes work this run
 * never created. The readiness release is refused exactly as before — the
 * caller may report the mismatch, but it can never turn it into an admission.
 *
 * <p>Every other admission failure (a boundary the production loop did not
 * service, a kind this stream does not record, admission outside a recorded
 * run) remains a plain {@link IllegalStateException} and still aborts.
 */
public final class UnmatchedRecordedCompletionException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public UnmatchedRecordedCompletionException(String message) {
        super(message);
    }
}
