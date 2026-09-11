package com.openggf.trace.timing;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.PendingRecordedSubmission;
import com.openggf.game.timing.PendingRecordedSubmissionsException;
import com.openggf.game.timing.RecordedCompletionAuthority;
import com.openggf.game.timing.RecordedOrdinalSpan;
import com.openggf.game.timing.UnmatchedRecordedCompletionException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded adapter from recorded completion edges to the narrow production
 * readiness-admission capability.
 *
 * <p>This adapter can neither submit nor prepare work. Production independently
 * owns the FIFO identity and payload; a recorded edge can only release its
 * already-prepared matching head at the represented service boundary.
 */
public final class HardwareTimingReplayPort
        implements RewindSnapshottable<HardwareTimingReplaySnapshot> {
    public static final String REWIND_KEY = "hardware-timing-replay";

    private final RecordedCompletionAuthority authority;
    private final Set<String> consumedIdentities = new LinkedHashSet<>();
    /**
     * Recorded edges that had no engine-pending counterpart. Such an edge is
     * DROPPED, never admitted: nothing is released and no production work is
     * created. It is retained here only so the driving comparison can record
     * it as an error on its own row, and so a driver that never drains it
     * still fails the run at {@link #verifyRunComplete()}.
     */
    private final List<String> unmatchedCompletions = new ArrayList<>();
    /**
     * Production submissions the engine still held when the recorded run
     * closed. Recorded admission has already ended, so such a submission is
     * never admitted, prepared, released or retired: the list exists only so
     * an opted-in driver can record it as a comparison error, and so a driver
     * that never drains it cannot open another run.
     */
    private final List<String> pendingSubmissionsAtClose = new ArrayList<>();

    private HardwareTimingSchedule schedule = HardwareTimingSchedule.empty();
    private int edgeCursor;
    private Integer rawFrameLatch;
    private HardwareServiceBoundary lastAppliedBoundary;
    private boolean installed;
    private boolean runComplete;
    private boolean reportsPendingSubmissions;

    public HardwareTimingReplayPort(RecordedCompletionAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    public void install(HardwareTimingSchedule schedule) {
        HardwareTimingSchedule checked = validateSchedule(schedule);
        install(checked, firstOrdinals(checked));
    }

    /**
     * Installs the initial structural-run schedule and its one-time hardware
     * identity base. The explicit form is used when the first represented
     * segment is empty but the run's initial state still has a reviewed base.
     * Segment handoffs can never establish or change this base.
     */
    public void install(
            HardwareTimingSchedule schedule,
            Map<HardwareWorkKind, Long> initialOrdinalBases) {
        HardwareTimingSchedule checked = validateSchedule(schedule);
        Objects.requireNonNull(initialOrdinalBases, "initialOrdinalBases");
        if (installed && !runComplete) {
            throw new IllegalStateException(
                    "hardware timing replay is already installed");
        }
        requireNoUndrainedPendingSubmissions();
        this.schedule = checked;
        edgeCursor = 0;
        consumedIdentities.clear();
        authority.configureAdmissionPolicies(checked.admissionPolicies());
        authority.initializeOrdinalBases(initialOrdinalBases);
        rawFrameLatch = null;
        lastAppliedBoundary = null;
        installed = true;
        runComplete = false;
    }

    public void beginRawFrame(int rawFrame) {
        requireActive();
        if (rawFrame < 0) {
            throw new IllegalArgumentException(
                    "hardware timing raw_frame must be non-negative: " + rawFrame);
        }
        if (rawFrameLatch != null) {
            if (rawFrame < rawFrameLatch) {
                throw new IllegalStateException(
                        "hardware timing raw_frame moved backward: previous="
                                + rawFrameLatch + ", current=" + rawFrame);
            }
            if (rawFrame == rawFrameLatch) {
                // A setup/admission retry represents a fresh production drive
                // of the same trace row, so boundary ordering restarts even
                // though the physical raw-frame identity does not advance.
                lastAppliedBoundary = null;
                return;
            }
        }
        dropEdgesBefore(rawFrame);
        rawFrameLatch = rawFrame;
        authority.setRecordedRowRepresentation(true);
        lastAppliedBoundary = null;
    }

    /**
     * Deactivates row authority while production crosses a movie frame that
     * has no represented trace row. Production hardware work may continue,
     * but no recorded completion edge may be applied until the next
     * {@link #beginRawFrame(int)} call.
     */
    public void enterUnrepresentedGap() {
        requireActive();
        rawFrameLatch = null;
        lastAppliedBoundary = null;
        authority.setRecordedRowRepresentation(false);
    }

    public void apply(HardwareServiceBoundary boundary) {
        requireActive();
        Objects.requireNonNull(boundary, "boundary");
        if (rawFrameLatch == null) {
            return;
        }
        if (lastAppliedBoundary != null
                && boundary.ordinal() <= lastAppliedBoundary.ordinal()) {
            throw new IllegalStateException(
                    "duplicate or reordered hardware service boundary at raw_frame="
                            + rawFrameLatch + ": previous=" + lastAppliedBoundary
                            + ", current=" + boundary);
        }

        dropEdgesBefore(rawFrameLatch);
        HardwareCompletionEdge next = nextEdge();
        if (next != null
                && next.rawFrame() == rawFrameLatch
                && next.boundary().ordinal() < boundary.ordinal()) {
            throw new IllegalStateException(
                    "unconsumed hardware completion edge before boundary "
                            + boundary + ": " + describe(next));
        }

        consumeAtBoundary(boundary, false);
        lastAppliedBoundary = boundary;
    }

    /**
     * Exposes an exact loop-tail completion recorded on the current suppressed
     * row after that row has traversed VInt service.
     */
    public boolean applySuppressedRowCompletion() {
        requireActive();
        if (rawFrameLatch == null) {
            return false;
        }
        dropEdgesBefore(rawFrameLatch);
        HardwareCompletionEdge next = nextEdge();
        if (next == null || next.rawFrame() > rawFrameLatch) {
            return false;
        }
        if (lastAppliedBoundary != HardwareServiceBoundary.VINT_SERVICE) {
            throw new IllegalStateException(
                    "suppressed-row completion requires current-row VINT_SERVICE: "
                            + describe(next));
        }
        if (next.boundary() != HardwareServiceBoundary.PRE_MAIN_LOOP) {
            throw new IllegalStateException(
                    "suppressed row cannot expose hardware completion at "
                            + next.boundary() + ": " + describe(next));
        }
        consumeAtBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP, true);
        lastAppliedBoundary = HardwareServiceBoundary.PRE_MAIN_LOOP;
        return true;
    }

    private void consumeAtBoundary(
            HardwareServiceBoundary boundary,
            boolean suppressedRow) {
        HardwareCompletionEdge next;
        while ((next = nextEdge()) != null
                && next.rawFrame() == rawFrameLatch
                && next.boundary() == boundary) {
            String identity = identity(next);
            if (!consumedIdentities.add(identity)) {
                throw new IllegalStateException(
                        "duplicate hardware completion edge consumption: "
                                + describe(next));
            }
            try {
                if (suppressedRow) {
                    authority.admitRecordedSuppressedRowCompletion(
                            boundary,
                            next.kind(),
                            next.ordinal(),
                            next.submissionFingerprint());
                } else {
                    authority.admitRecordedCompletion(
                            boundary,
                            next.kind(),
                            next.ordinal(),
                            next.submissionFingerprint());
                }
            } catch (UnmatchedRecordedCompletionException failure) {
                // Severity demotion only. An unmatched recorded completion is
                // ambiguous: in a converged run it is a contract violation, in
                // a diverged run it is a downstream symptom of the engine
                // never reaching the ROM's submission point. It therefore
                // cannot carry verdict authority, so the edge is dropped and
                // reported instead of aborting the whole comparison. The
                // release side is unchanged: admitReadiness() was not reached,
                // so this edge releases nothing.
                unmatchedCompletions.add(
                        describe(next) + ": " + failure.getMessage());
            } catch (RuntimeException failure) {
                consumedIdentities.remove(identity);
                throw failure;
            }
            edgeCursor++;
        }
    }

    public void handoffTo(HardwareTimingSchedule nextSchedule) {
        handoffTo(nextSchedule, Map.of());
    }

    /**
     * Hands off to the next segment across a recorded interstitial span.
     *
     * <p>The span describes ordinals the recording consumed between the two
     * segments — a special-stage results screen, a level reload, a locked
     * intro — that production never submits, so production's identity cursor
     * would otherwise stay behind and every later completion would miss by
     * exactly the size of the gap.
     *
     * <p>Crossing one releases nothing and creates nothing; it only moves the
     * cursor, and it is proved on both sides before it moves. The span must
     * begin exactly where production's ledger stands (checked in the
     * authority) and must end exactly where the next segment's recorded
     * ordinals begin (checked here). Anything else throws, so a skew that is
     * not the recorded span cannot be absorbed.
     */
    public void handoffTo(
            HardwareTimingSchedule nextSchedule,
            Map<HardwareWorkKind, RecordedOrdinalSpan> interstitialSpans) {
        requireActive();
        Objects.requireNonNull(interstitialSpans, "interstitialSpans");
        verifySegmentEdges();
        HardwareTimingSchedule checkedNext = validateSchedule(nextSchedule);
        if (!checkedNext.admissionPolicies().equals(schedule.admissionPolicies())) {
            throw new IllegalArgumentException(
                    "hardware timing segment changes recorded admission policy");
        }
        for (HardwareCompletionEdge edge : checkedNext.edges()) {
            if (consumedIdentities.contains(identity(edge))) {
                throw new IllegalStateException(
                        "next segment repeats an already-consumed hardware completion edge: "
                                + describe(edge));
            }
        }

        List<PendingRecordedSubmission> pending = authority.pendingSubmissions();
        for (PendingRecordedSubmission submission : pending) {
            HardwareWorkHandle handle = submission.handle();
            if (!submission.exportableAcrossSegment()) {
                throw new IllegalStateException(
                        "non-exportable pending hardware submission at segment end: "
                                + describe(handle));
            }
            List<HardwareCompletionEdge> candidates = checkedNext.edges().stream()
                    .filter(edge -> edge.kind() == handle.kind()
                            && edge.ordinal() == handle.ordinal())
                    .toList();
            boolean exactFutureEdge = candidates.stream().anyMatch(edge ->
                    edge.submissionFingerprint()
                            .equals(handle.submissionFingerprint()));
            if (!exactFutureEdge) {
                throw new IllegalStateException(
                        "exportable pending hardware submission requires a matching "
                                + "next-segment edge: engine pending=" + describe(handle)
                                + "; next-segment candidates="
                                + candidates.stream()
                                .map(HardwareTimingReplayPort::describe)
                                .toList());
            }
        }

        if (!interstitialSpans.isEmpty()) {
            Map<HardwareWorkKind, Long> nextFirstOrdinals = firstOrdinals(checkedNext);
            for (Map.Entry<HardwareWorkKind, RecordedOrdinalSpan> entry
                    : interstitialSpans.entrySet()) {
                Long nextFirst = nextFirstOrdinals.get(entry.getKey());
                if (nextFirst == null) {
                    throw new IllegalStateException(
                            "recorded interstitial span for " + entry.getKey()
                                    + " has no matching next-segment edge to resume at: "
                                    + entry.getValue());
                }
                if (nextFirst != entry.getValue().nextOrdinal()) {
                    throw new IllegalStateException(
                            "recorded interstitial span does not meet the next segment for "
                                    + entry.getKey() + ": span ends at "
                                    + entry.getValue().lastOrdinal()
                                    + ", next segment resumes at " + nextFirst);
                }
            }
            authority.advanceOrdinalCursorAcrossRecordedSpan(interstitialSpans);
        }

        schedule = checkedNext;
        edgeCursor = 0;
        rawFrameLatch = null;
        lastAppliedBoundary = null;
    }

    public void verifySegmentEdges() {
        requireActive();
        for (HardwareCompletionEdge edge = nextEdge(); edge != null;
                edge = nextEdge()) {
            reportUnconsumedEdge("at segment end", edge);
        }
    }

    /**
     * Removes and returns the unmatched recorded completions observed since
     * the last drain, so the caller can record them as comparison errors on
     * the row that produced them. Draining reports them; it never admits
     * them.
     */
    public List<String> drainUnmatchedRecordedCompletions() {
        if (unmatchedCompletions.isEmpty()) {
            return List.of();
        }
        List<String> drained = List.copyOf(unmatchedCompletions);
        unmatchedCompletions.clear();
        return drained;
    }

    /**
     * Declares that this driver drains {@link #drainPendingRecordedSubmissions()}
     * after the run closes and records the result as a comparison error.
     *
     * <p>Without this the close-time leftover-submission complaint keeps its
     * original hard-failure behaviour, so a driver with nowhere to record it
     * still fails the run. Enabling it changes severity and ordering only: a
     * leftover submission is still never admitted, released or retired.
     */
    public void reportPendingRecordedSubmissionsAtClose() {
        reportsPendingSubmissions = true;
    }

    /**
     * Removes and returns the leftover production submissions the recorded
     * stream never completed, so the caller can record them as a comparison
     * error on the closing row. Draining reports them; it never admits them.
     */
    public List<String> drainPendingRecordedSubmissions() {
        if (pendingSubmissionsAtClose.isEmpty()) {
            return List.of();
        }
        List<String> drained = List.copyOf(pendingSubmissionsAtClose);
        pendingSubmissionsAtClose.clear();
        return drained;
    }

    public void verifyRunComplete() {
        if (runComplete) {
            return;
        }
        requireActive();
        requireNoUndrainedUnmatchedCompletions();
        requireNoUndrainedPendingSubmissions();
        verifySegmentEdges();
        // Close is the last chance to report: no comparison row follows it, so
        // an edge dropped here has nowhere to be counted. The demotion exists
        // to stop a mid-run edge from hiding later axes, not to let the run end
        // holding evidence, so leftovers at close still fail the run -- now
        // listing every one of them rather than only the first.
        requireNoUndrainedUnmatchedCompletions();
        try {
            authority.endRecordedAdmission();
        } catch (PendingRecordedSubmissionsException failure) {
            if (!reportsPendingSubmissions) {
                throw failure;
            }
            // Severity demotion only, and for the same reason as the unmatched
            // completion above: a leftover submission is a contract concern in
            // a converged run and a downstream symptom in a diverged one, and
            // this message cannot tell them apart. Recorded admission has
            // already ended inside the authority, nothing was admitted or
            // released, and the driver that opted in must still report it.
            for (PendingRecordedSubmission submission : failure.pending()) {
                pendingSubmissionsAtClose.add(describe(submission.handle()));
            }
        }
        runComplete = true;
    }

    /**
     * Closes a semantically complete trace prefix without admitting future
     * recorded edges. Every edge represented by the prefix and every
     * production submission it created must already be complete.
     */
    public void verifyPrefixComplete(int inclusiveRawFrame) {
        requireActive();
        requireNoUndrainedUnmatchedCompletions();
        requireNoUndrainedPendingSubmissions();
        HardwareCompletionEdge next = nextEdge();
        if (next != null && next.rawFrame() <= inclusiveRawFrame) {
            throw new IllegalStateException(
                    "unconsumed hardware completion edge at prefix end raw_frame="
                            + inclusiveRawFrame + ": " + describe(next));
        }
        List<PendingRecordedSubmission> pendingSubmissions = authority.pendingSubmissions();
        if (!pendingSubmissions.isEmpty()) {
            throw new IllegalStateException(
                    "pending recorded hardware submissions at prefix end raw_frame="
                            + inclusiveRawFrame + ": " + pendingSubmissions.stream()
                            .map(PendingRecordedSubmission::handle)
                            .map(HardwareTimingReplayPort::describe)
                            .toList());
        }
        authority.endRecordedAdmission();
        runComplete = true;
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public HardwareTimingReplaySnapshot capture() {
        return new HardwareTimingReplaySnapshot(
                schedule,
                edgeCursor,
                consumedIdentities,
                rawFrameLatch,
                lastAppliedBoundary,
                installed,
                runComplete,
                unmatchedCompletions,
                pendingSubmissionsAtClose);
    }

    @Override
    public void restore(HardwareTimingReplaySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        schedule = validateSchedule(snapshot.schedule());
        edgeCursor = snapshot.edgeCursor();
        consumedIdentities.clear();
        consumedIdentities.addAll(snapshot.consumedIdentities());
        rawFrameLatch = snapshot.rawFrameLatch();
        lastAppliedBoundary = snapshot.lastAppliedBoundary();
        installed = snapshot.installed();
        runComplete = snapshot.runComplete();
        unmatchedCompletions.clear();
        unmatchedCompletions.addAll(snapshot.unmatchedCompletions());
        pendingSubmissionsAtClose.clear();
        pendingSubmissionsAtClose.addAll(snapshot.pendingSubmissionsAtClose());
    }

    @Override
    public void resetForMissingSnapshot() {
        schedule = HardwareTimingSchedule.empty();
        edgeCursor = 0;
        consumedIdentities.clear();
        rawFrameLatch = null;
        lastAppliedBoundary = null;
        installed = false;
        runComplete = false;
        unmatchedCompletions.clear();
        pendingSubmissionsAtClose.clear();
    }

    private HardwareCompletionEdge nextEdge() {
        return edgeCursor < schedule.edges().size()
                ? schedule.edges().get(edgeCursor)
                : null;
    }

    /**
     * Drops the recorded edges the production run walked past without
     * submitting anything for them, reporting each as a comparison error.
     *
     * <p>Severity demotion, on the same footing as the unmatched-completion
     * demotion in {@link #apply(HardwareServiceBoundary)}. This port's
     * authority is over <em>when</em> work the engine submitted becomes
     * ready; it has no authority to require a submission the engine never
     * made. An edge with no submission behind it is therefore a statement
     * about engine accuracy -- the ROM reached an arm point the engine did
     * not -- which the comparator owns and reports per field, not a contract
     * violation that may abort the comparison and hide every later axis.
     * The release side is unchanged: a dropped edge is never admitted, so it
     * releases nothing.
     */
    private void dropEdgesBefore(int rawFrame) {
        for (HardwareCompletionEdge next = nextEdge();
                next != null && next.rawFrame() < rawFrame;
                next = nextEdge()) {
            reportUnconsumedEdge("before raw_frame=" + rawFrame, next);
        }
    }

    /**
     * Records one dropped edge and advances past it. Every dropped edge lands
     * in the same drain the unmatched completions use, so it is counted by
     * the comparator or, if the driver never drains it, fails the run through
     * {@link #requireNoUndrainedUnmatchedCompletions()}. It cannot vanish.
     */
    private void reportUnconsumedEdge(String where, HardwareCompletionEdge edge) {
        unmatchedCompletions.add(
                describe(edge) + ": unconsumed hardware completion edge "
                        + where + "; " + productionStateFor(edge));
        edgeCursor++;
    }

    /**
     * Describes what production actually holds for a dropped edge's identity,
     * as observed now.
     *
     * <p>This exists because the previous wording asserted "the engine
     * submitted no matching work", which the port never checks and which was
     * measured false: on the S1 complete-emeralds run the engine submits the
     * expected descriptors with exactly the recorded ordinals and
     * fingerprints, and the edges go unconsumed anyway. A diagnostic that
     * names the wrong subsystem is worse than a vague one, because it is
     * acted on.
     *
     * <p>The wording is deliberately limited to what is observable at this
     * point. An identity absent from the pending list may never have been
     * submitted, or may have been submitted, admitted and already claimed --
     * the port cannot distinguish those and so does not claim to.
     */
    private String productionStateFor(HardwareCompletionEdge edge) {
        for (PendingRecordedSubmission submission : authority.pendingSubmissions()) {
            HardwareWorkHandle handle = submission.handle();
            if (handle.kind() != edge.kind() || handle.ordinal() != edge.ordinal()) {
                continue;
            }
            if (handle.submissionFingerprint().equals(edge.submissionFingerprint())) {
                return "production holds a matching unclaimed submission "
                        + describe(handle)
                        + ", so this is an admission failure rather than missing work";
            }
            return "production holds this identity with a different descriptor: "
                    + describe(handle);
        }
        return "production holds no unclaimed submission for this identity"
                + " (it was never submitted, or was already admitted and claimed)";
    }

    /**
     * A driver that never drains the dropped edges has no comparison row
     * carrying them, so the run still fails rather than passing silently.
     */
    private void requireNoUndrainedUnmatchedCompletions() {
        if (!unmatchedCompletions.isEmpty()) {
            throw new IllegalStateException(
                    "unmatched recorded hardware completions were never reported: "
                            + unmatchedCompletions);
        }
    }

    /**
     * A driver that closed one run with leftover submissions and never
     * reported them cannot open another one, so an opted-in driver that
     * forgets to report cannot quietly carry on.
     */
    private void requireNoUndrainedPendingSubmissions() {
        if (!pendingSubmissionsAtClose.isEmpty()) {
            throw new IllegalStateException(
                    "pending recorded hardware submissions were never reported: "
                            + pendingSubmissionsAtClose);
        }
    }

    private void requireActive() {
        if (!installed) {
            throw new IllegalStateException(
                    "hardware timing replay schedule is not installed");
        }
        if (runComplete) {
            throw new IllegalStateException(
                    "hardware timing replay run is already complete");
        }
    }

    private static HardwareTimingSchedule validateSchedule(
            HardwareTimingSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule");
        HardwareCompletionEdge previous = null;
        Set<String> identities = new LinkedHashSet<>();
        EnumMap<HardwareWorkKind, Long> lastOrdinals =
                new EnumMap<>(HardwareWorkKind.class);
        for (HardwareCompletionEdge edge : schedule.edges()) {
            Objects.requireNonNull(edge, "hardware completion edge");
            if (edge.rawFrame() < 0) {
                throw new IllegalArgumentException(
                        "hardware completion edge raw_frame must be non-negative: "
                                + edge.rawFrame());
            }
            Objects.requireNonNull(edge.boundary(), "hardware completion boundary");
            Objects.requireNonNull(edge.kind(), "hardware completion kind");
            Objects.requireNonNull(
                    edge.submissionFingerprint(), "hardware completion fingerprint");
            if (edge.ordinal() < 0) {
                throw new IllegalArgumentException(
                        "hardware completion ordinal must be non-negative: "
                                + edge.ordinal());
            }
            if (previous != null
                    && HardwareTimingSchedule.CANONICAL_ORDER.compare(previous, edge) > 0) {
                throw new IllegalArgumentException(
                        "hardware completion edges are not in canonical order at "
                                + describe(edge));
            }
            if (!identities.add(identity(edge))) {
                throw new IllegalArgumentException(
                        "duplicate hardware completion edge identity: "
                                + describe(edge));
            }
            Long lastOrdinal = lastOrdinals.put(
                    edge.kind(), edge.ordinal());
            if (lastOrdinal != null
                    && (lastOrdinal == Long.MAX_VALUE
                    || edge.ordinal() != lastOrdinal + 1)) {
                throw new IllegalArgumentException(
                        "noncontiguous hardware completion ordinals for "
                                + edge.kind() + ": expected ordinal "
                                + (lastOrdinal == Long.MAX_VALUE
                                ? "<exhausted>" : lastOrdinal + 1)
                                + ", found " + edge.ordinal());
            }
            previous = edge;
        }
        return schedule;
    }

    private static Map<HardwareWorkKind, Long> firstOrdinals(
            HardwareTimingSchedule schedule) {
        EnumMap<HardwareWorkKind, Long> firstOrdinals =
                new EnumMap<>(HardwareWorkKind.class);
        for (HardwareCompletionEdge edge : schedule.edges()) {
            firstOrdinals.putIfAbsent(edge.kind(), edge.ordinal());
        }
        return Map.copyOf(firstOrdinals);
    }

    private static String identity(HardwareCompletionEdge edge) {
        return edge.kind() + "#" + edge.ordinal();
    }

    private static String describe(HardwareCompletionEdge edge) {
        return "raw_frame=" + edge.rawFrame()
                + " boundary=" + edge.boundary()
                + " expected completion=" + edge.kind() + "#" + edge.ordinal()
                + " " + edge.submissionFingerprint();
    }

    private static String describe(HardwareWorkHandle handle) {
        return handle.kind() + "#" + handle.ordinal()
                + " " + handle.submissionFingerprint();
    }
}
