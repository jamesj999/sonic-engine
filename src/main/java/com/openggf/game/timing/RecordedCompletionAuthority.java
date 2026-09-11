package com.openggf.game.timing;

import java.util.List;
import java.util.Map;

/**
 * Narrow capability that may admit final readiness for already-submitted,
 * already-prepared production work.
 */
public interface RecordedCompletionAuthority {
    /**
     * Selects the kinds for which this recorded stream owns final readiness.
     * The selection is fixed before production submits any work.
     */
    void configureAdmissionPolicies(
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> admissionPolicies);

    /**
     * Establishes the hardware-relative identity base for kinds whose
     * production ledger has not yet submitted work.
     *
     * <p>This is a one-time structural-session bootstrap for standalone
     * segments. It cannot renumber an existing production submission.
     */
    void initializeOrdinalBases(Map<HardwareWorkKind, Long> firstOrdinals);

    /**
     * Advances the hardware-relative identity cursor across a recorded span
     * that production does not reproduce as submissions.
     *
     * <p>This releases nothing. It neither creates, prepares, completes nor
     * retires a job: it only moves the number the next production submission
     * will be allocated, so that a later completion can be matched on the same
     * ordinal axis the recording used. Every release still has to satisfy the
     * full kind, ordinal, fingerprint and boundary match afterwards.
     *
     * <p>The move is proved on both sides. Each span must begin exactly where
     * the production ledger currently stands, and production must hold nothing
     * pending, so no existing submission is renumbered and a skew that is not
     * exactly the recorded span fails instead of being absorbed.
     */
    void advanceOrdinalCursorAcrossRecordedSpan(
            Map<HardwareWorkKind, RecordedOrdinalSpan> spans);

    void admitRecordedCompletion(
            HardwareServiceBoundary boundary,
            HardwareWorkKind kind,
            long ordinal,
            String submissionFingerprint);

    /**
     * Admits a loop-tail completion whose visibility was recorded on a
     * suppressed held-counter row after that row's VInt service.
     *
     * <p>This capability bypasses only the ordinary last-serviced-boundary
     * equality. The replay port must prove a compiled current-row
     * {@link HardwareServiceBoundary#PRE_MAIN_LOOP} edge before invoking it.
     */
    void admitRecordedSuppressedRowCompletion(
            HardwareServiceBoundary boundary,
            HardwareWorkKind kind,
            long ordinal,
            String submissionFingerprint);

    List<PendingRecordedSubmission> pendingSubmissions();

    void endRecordedAdmission();

    /**
     * Declares whether recorded row authority currently represents a trace row.
     *
     * <p>{@code HardwareTimingReplayPort.enterUnrepresentedGap} deactivates row
     * authority while production crosses a movie frame with no represented row,
     * and its contract is that "production hardware work may continue, but no
     * recorded completion edge may be applied until the next beginRawFrame"
     * (HardwareTimingReplayPort:120-126). Work submitted inside such a span can
     * never be matched -- the recorder discards anything observed before a
     * segment's first row, so a level load's own arming reaches no trace file
     * (tools/tracechaser/bizhawk-headless/src/Recording/S1PlcHardwareTimingObserver.cs:80-83)
     * -- so holding it against recorded readiness deadlocks by construction.
     * Default {@code true} keeps every existing implementer unchanged.
     */
    default void setRecordedRowRepresentation(boolean representingRecordedRow) {
    }
}
