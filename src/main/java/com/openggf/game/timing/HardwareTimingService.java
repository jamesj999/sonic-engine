package com.openggf.game.timing;

import com.openggf.game.rewind.RewindSnapshottable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Session-owned FIFO for deterministic preparation and observable hardware readiness.
 */
public final class HardwareTimingService
        implements RewindSnapshottable<HardwareTimingSnapshot> {
    public static final String REWIND_KEY = "hardware-timing";

    private final RomWorkBudgetScheduler scheduler;
    private final LoadTimeProfile loadTimeProfile;
    private final RecordedAuthority recordedAuthority = new RecordedAuthority();
    private boolean recordedRowRepresented = true;
    private final EnumMap<HardwareWorkKind, Long> nextOrdinals =
            new EnumMap<>(HardwareWorkKind.class);
    private final List<HardwareTimingJob> jobs = new ArrayList<>();
    /**
     * Handles submitted while recorded row authority was deactivated. The
     * recorder discards anything observed outside a segment's rows, so no
     * recorded completion edge for these can ever arrive; they are released on
     * the same native work budget a live run would give them. Membership is a
     * property of the submission, not of the moment of release, so work
     * submitted inside coverage keeps waiting for its recorded edge even when
     * the run later leaves coverage.
     */
    private final Set<HardwareWorkHandle> unrepresentedSubmissions =
            new LinkedHashSet<>();
    /**
     * Every handle ever submitted unrepresented, including claimed ones. The
     * ordinal an unrepresented submission borrowed is returned after the claim,
     * so the fact has to outlive the pending entry.
     */
    private final Set<HardwareWorkHandle> wasSubmittedUnrepresented =
            new LinkedHashSet<>();

    private final EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy>
            admissionPolicies = liveAdmissionPolicies();
    private boolean recordedAdmissionActive;
    private boolean hasSubmitted;
    private HardwareServiceBoundary lastServicedBoundary;

    public HardwareTimingService() {
        this(RomWorkBudgetScheduler.oneWorkUnitAt(
                HardwareServiceBoundary.POST_OBJECTS));
    }

    public HardwareTimingService(RomWorkBudgetScheduler scheduler) {
        this(scheduler, LoadTimeProfile.IMMEDIATE);
    }

    public HardwareTimingService(
            RomWorkBudgetScheduler scheduler,
            LoadTimeProfile loadTimeProfile) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.loadTimeProfile = Objects.requireNonNull(loadTimeProfile, "loadTimeProfile");
    }

    public HardwareWorkHandle submit(HardwareWorkSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        long ordinal = nextOrdinals.getOrDefault(submission.kind(), 0L);
        if (ordinal == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "hardware work ordinal exhausted for " + submission.kind());
        }
        HardwareWorkHandle handle = new HardwareWorkHandle(
                submission.kind(),
                ordinal,
                HardwareSubmissionFingerprint.compute(submission));
        nextOrdinals.put(submission.kind(), ordinal + 1);
        jobs.add(new HardwareTimingJob(submission, handle));
        if (!recordedAuthorityRepresentsRow()) {
            unrepresentedSubmissions.add(handle);
            wasSubmittedUnrepresented.add(handle);
        }
        hasSubmitted = true;
        return handle;
    }

    public void service(HardwareServiceBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        for (HardwareWorkKind kind : HardwareWorkKind.values()) {
            boolean live = admissionPolicyFor(kind)
                    == HardwareReadinessAdmissionPolicy.LIVE;
            if (live) {
                activateAndAdvanceHead(boundary, kind, job -> true);
            } else {
                // HardwareTimingReplayPort.enterUnrepresentedGap contracts that
                // production hardware work may continue while row authority is
                // deactivated, but no recorded edge may be applied. Work
                // submitted in such a span has no edge to wait for, so it is
                // serviced and released on the native work budget instead --
                // the same rule the S1 Nemesis PLC arm gate applies
                // (Sonic1PlcArmTiming.releaseArm), moved to the ledger so every
                // recorded kind obeys it and so the release still costs the ROM
                // service frames rather than completing instantly.
                activateAndAdvanceHead(boundary, kind, this::isUnrepresented);
            }
            serviceBoundaryDrivenHead(boundary, kind);
            scheduler.service(boundary, jobsOfKind(kind));
            if (live) {
                releasePreparedInFifoOrder(kind, job -> true);
            } else {
                releasePreparedInFifoOrder(kind, this::isUnrepresented);
            }
        }
        lastServicedBoundary = boundary;
    }

    public boolean isPending(HardwareWorkHandle handle) {
        HardwareTimingJob job = find(handle);
        return job != null && !job.isClaimed();
    }

    /**
     * Whether recorded row authority currently represents a trace row. While it
     * does not, no recorded edge can be applied, so pending work in that span
     * must fall back to native readiness rather than wait for a match that
     * cannot arrive. See {@code HardwareTimingReplayPort#enterUnrepresentedGap}.
     */
    public boolean recordedAuthorityRepresentsRow() {
        return !recordedAdmissionActive || recordedRowRepresented;
    }

    /**
     * Admits readiness natively for work submitted where the recorded stream
     * has no authority. Only legal while row authority is deactivated; it
     * changes WHEN engine-created work becomes ready and never what the work
     * is, matching the hardware-timing exception's scope.
     */
    public void admitUnrepresentedReadiness(HardwareWorkHandle handle) {
        if (recordedAuthorityRepresentsRow()
                && !unrepresentedSubmissions.contains(handle)) {
            throw new IllegalStateException(
                    "native readiness is only available while recorded row"
                            + " authority is deactivated: " + handle);
        }
        HardwareTimingJob job = requireKnown(handle);
        if (!job.isReady()) {
            job.admitReadiness();
        }
    }

    /**
     * Returns the identity an unrepresented submission borrowed, once
     * production has claimed its result.
     *
     * <p>The recorder discards everything it observes before a segment's first
     * arm (tools/tracechaser/bizhawk-headless/src/Recording/S1PlcHardwareTimingObserver.cs:80-83),
     * so work released through {@link #admitUnrepresentedReadiness} appears
     * nowhere in the stream. Ordinals are the only counter the engine and the
     * recording share, so an arm the recorder never counted must not occupy a
     * place in the shared numbering either: the next represented submission has
     * to be allocated the ordinal the recording gives it. This is the mirror of
     * {@code advanceOrdinalCursorAcrossRecordedSpan}, which skips the cursor
     * across recorded ordinals the engine never submits into; here the engine
     * submitted an ordinal the recording never carried.
     *
     * <p>Nothing is undone and no work is discarded: the job was submitted,
     * prepared, admitted and claimed, and its result is already in production's
     * hands. Only its spent ledger record is retired, so no two entries ever
     * share an identity once the ordinal is reissued. This changes which number
     * later work carries, never whether that work happens.
     *
     * <p>The move is proved before it is made, on the same invariant the
     * cursor-advance guard states: the cursor is the allocator for the next
     * handle, so it may only move while production holds nothing unclaimed that
     * the move would renumber. The handle must be the most recently allocated
     * of its kind and must already be claimed; anything else throws rather than
     * silently renumbering a live submission.
     */
    public void releaseUnrepresentedIdentity(HardwareWorkHandle handle) {
        if (recordedAuthorityRepresentsRow()
                && !wasSubmittedUnrepresented.contains(handle)) {
            throw new IllegalStateException(
                    "an unrepresented identity is only returned while recorded row"
                            + " authority is deactivated: " + handle);
        }
        HardwareTimingJob job = requireKnown(handle);
        HardwareWorkKind kind = handle.kind();
        if (admissionPolicyFor(kind) != HardwareReadinessAdmissionPolicy.RECORDED) {
            throw new IllegalStateException(
                    "unrepresented identity only applies to recorded-admission kinds: "
                            + kind);
        }
        if (!job.isClaimed()) {
            throw new IllegalStateException(
                    "an unrepresented identity is returned only after production has"
                            + " claimed its result: " + HardwareTimingJob.describe(handle));
        }
        long cursor = nextOrdinals.getOrDefault(kind, 0L);
        if (handle.ordinal() != cursor - 1) {
            throw new IllegalStateException(
                    "only the most recently allocated identity may be returned for "
                            + kind + ": production next=" + cursor
                            + ", returning=" + handle.ordinal());
        }
        List<HardwareWorkHandle> renumbered = jobs.stream()
                .filter(candidate -> !candidate.isClaimed()
                        && candidate.handle().kind() == kind)
                .map(HardwareTimingJob::handle)
                .toList();
        if (!renumbered.isEmpty()) {
            // Same invariant as advanceOrdinalCursorAcrossRecordedSpan's guard:
            // moving the allocator while production still holds an unclaimed
            // handle would leave that handle numbered on the old axis with no
            // completion able to reach it.
            throw new IllegalStateException(
                    "cannot return a hardware identity while production holds pending "
                            + "submissions of the same kind: " + renumbered.stream()
                            .map(HardwareTimingJob::describe)
                            .toList());
        }
        jobs.remove(job);
        wasSubmittedUnrepresented.remove(handle);
        nextOrdinals.put(kind, handle.ordinal());
    }

    public boolean isReady(HardwareWorkHandle handle) {
        HardwareTimingJob job = find(handle);
        return job != null && !job.isClaimed() && job.isReady();
    }

    /** Returns the production ownership contract attached to a pending job. */
    public boolean isExportableAcrossSegment(HardwareWorkHandle handle) {
        return requireKnown(handle).submission().exportableAcrossSegment();
    }

    public byte[] claim(HardwareWorkHandle handle) {
        HardwareTimingJob job = requireKnown(handle);
        byte[] payload = job.claim();
        unrepresentedSubmissions.remove(handle);
        return payload;
    }

    /**
     * Reads the preserved result of already-claimed work during rewind-owner
     * reconstruction. This does not change the job lifecycle.
     */
    public byte[] claimedPayload(HardwareWorkKind kind, long ordinal) {
        Objects.requireNonNull(kind, "kind");
        HardwareTimingJob job = jobs.stream()
                .filter(candidate -> candidate.handle().kind() == kind
                        && candidate.handle().ordinal() == ordinal)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown hardware work identity: " + kind + "#" + ordinal));
        return job.claimedPayload();
    }

    /**
     * Captures payload prepared by a production coordinator at the boundary
     * just serviced. Recorded authority still controls final readiness.
     */
    public void captureCoordinatorPreparation(
            HardwareWorkHandle handle,
            HardwareServiceBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        if (lastServicedBoundary != boundary) {
            throw new IllegalStateException(
                    "coordinator boundary mismatch: expected " + boundary
                            + ", production serviced " + lastServicedBoundary);
        }
        HardwareTimingJob job = requireKnown(handle);
        job.capturePreparedPayload();
        if (admissionPolicyFor(handle.kind())
                == HardwareReadinessAdmissionPolicy.LIVE) {
            releasePreparedInFifoOrder(handle.kind(), job2 -> true);
        } else {
            releasePreparedInFifoOrder(handle.kind(), this::isUnrepresented);
        }
    }

    /**
     * Whether this handle's submission fell outside the recorded stream's rows.
     * The recorder never counted it, so its borrowed ordinal has to be returned
     * once production claims the result -- see
     * {@link #releaseUnrepresentedIdentity}.
     */
    public boolean wasSubmittedUnrepresented(HardwareWorkHandle handle) {
        return wasSubmittedUnrepresented.contains(handle);
    }

    /** Returns a pending preparation to its production-owned coordinator. */
    public HardwareWorkPreparation coordinatorPreparation(
            HardwareWorkHandle handle) {
        HardwareTimingJob job = requireKnown(handle);
        if (job.isClaimed()) {
            throw new IllegalStateException(
                    "hardware work was already claimed: "
                            + HardwareTimingJob.describe(handle));
        }
        return job.preparation();
    }

    public List<HardwareWorkHandle> pendingHandles() {
        return jobs.stream()
                .filter(job -> !job.isClaimed())
                .map(HardwareTimingJob::handle)
                .toList();
    }

    /**
     * Resolves an unclaimed handle by its session-stable identity.
     *
     * <p>Rewind owners use this after the timing ledger has been restored so
     * their transient queue facade can bind to the original job without
     * submitting replacement work.
     */
    public Optional<HardwareWorkHandle> pendingHandle(
            HardwareWorkKind kind,
            long ordinal) {
        Objects.requireNonNull(kind, "kind");
        if (ordinal < 0) {
            return Optional.empty();
        }
        return jobs.stream()
                .filter(job -> !job.isClaimed())
                .map(HardwareTimingJob::handle)
                .filter(handle -> handle.kind() == kind && handle.ordinal() == ordinal)
                .findFirst();
    }

    public int incompleteCount(HardwareWorkKind kind) {
        Objects.requireNonNull(kind, "kind");
        int count = 0;
        for (HardwareTimingJob job : jobs) {
            if (job.handle().kind() == kind
                    && !job.isClaimed()
                    && !job.isReady()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Switches only the final prepared-to-ready admission to recorded authority.
     *
     * <p>The returned capability cannot submit work, advance preparation, or
     * provide payload bytes.
     */
    public RecordedCompletionAuthority beginRecordedAdmission() {
        return beginRecordedAdmission(recordedAdmissionPolicies());
    }

    /** Begins recorded readiness with one complete policy for every known work kind. */
    public RecordedCompletionAuthority beginRecordedAdmission(
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
        if (recordedAdmissionActive) {
            throw new IllegalStateException("recorded hardware admission is already active");
        }
        if (hasSubmitted) {
            throw new IllegalStateException(
                    "recorded hardware admission must begin before the first submission");
        }
        installAdmissionPolicies(policies);
        recordedAdmissionActive = true;
        return recordedAuthority;
    }

    /**
     * Crosses from a completed live prelude into recorded readiness on this
     * same session-owned service. Only claimed diagnostic history is retired;
     * any live job still owned by production rejects the transition.
     */
    public RecordedCompletionAuthority beginRecordedAdmissionAfterLiveEpoch() {
        return beginRecordedAdmissionAfterLiveEpoch(
                recordedAdmissionPolicies());
    }

    public RecordedCompletionAuthority beginRecordedAdmissionAfterLiveEpoch(
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
        if (recordedAdmissionActive) {
            throw new IllegalStateException(
                    "recorded hardware admission is already active");
        }
        List<HardwareWorkHandle> pending = pendingHandles();
        if (!pending.isEmpty()) {
            throw new IllegalStateException(
                    "live hardware admission still owns pending work: "
                            + pendingDescription());
        }
        EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy> checked =
                validateAdmissionPolicies(policies);

        jobs.clear();
        nextOrdinals.clear();
        admissionPolicies.clear();
        admissionPolicies.putAll(checked);
        recordedAdmissionActive = true;
        hasSubmitted = false;
        lastServicedBoundary = null;
        return recordedAuthority;
    }

    public HardwareReadinessAdmissionPolicy admissionPolicy() {
        return recordedAdmissionActive
                ? HardwareReadinessAdmissionPolicy.RECORDED
                : HardwareReadinessAdmissionPolicy.LIVE;
    }

    public HardwareReadinessAdmissionPolicy admissionPolicyFor(HardwareWorkKind kind) {
        return admissionPolicies.get(Objects.requireNonNull(kind, "kind"));
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public HardwareTimingSnapshot capture() {
        EnumMap<HardwareWorkKind, Long> ordinalSnapshot =
                new EnumMap<>(HardwareWorkKind.class);
        ordinalSnapshot.putAll(nextOrdinals);
        List<HardwareTimingJob.Snapshot> jobSnapshots =
                jobs.stream().map(HardwareTimingJob::snapshot).toList();
        return new HardwareTimingSnapshot(
                ordinalSnapshot,
                jobSnapshots,
                admissionPolicies,
                recordedAdmissionActive,
                hasSubmitted,
                lastServicedBoundary,
                Set.copyOf(unrepresentedSubmissions));
    }

    @Override
    public void restore(HardwareTimingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        HardwareTimingSnapshot.validateAdmissionPolicies(
                snapshot.admissionPolicies(), snapshot.recordedAdmissionActive());
        nextOrdinals.clear();
        nextOrdinals.putAll(snapshot.nextOrdinals());
        // Keep live jobs whose memoized snapshot is the exact instance being
        // restored: they are already in that state, so recreating them would
        // only re-clone payload and preparation bytes. Order follows the snapshot.
        List<HardwareTimingJob> previous = new ArrayList<>(jobs);
        jobs.clear();
        for (HardwareTimingJob.Snapshot jobSnapshot : snapshot.jobs()) {
            HardwareTimingJob kept = null;
            for (HardwareTimingJob candidate : previous) {
                if (candidate.isUnchangedSince(jobSnapshot)) {
                    kept = candidate;
                    break;
                }
            }
            jobs.add(kept != null ? kept : HardwareTimingJob.restore(jobSnapshot));
        }
        admissionPolicies.clear();
        admissionPolicies.putAll(snapshot.admissionPolicies());
        recordedAdmissionActive = snapshot.recordedAdmissionActive();
        hasSubmitted = snapshot.hasSubmitted();
        lastServicedBoundary = snapshot.lastServicedBoundary();
        unrepresentedSubmissions.clear();
        unrepresentedSubmissions.addAll(snapshot.unrepresentedSubmissions());
        wasSubmittedUnrepresented.retainAll(unrepresentedSubmissions);
        wasSubmittedUnrepresented.addAll(unrepresentedSubmissions);
    }

    @Override
    public void resetForMissingSnapshot() {
        nextOrdinals.clear();
        jobs.clear();
        unrepresentedSubmissions.clear();
        wasSubmittedUnrepresented.clear();
        admissionPolicies.clear();
        admissionPolicies.putAll(liveAdmissionPolicies());
        recordedAdmissionActive = false;
        hasSubmitted = false;
        lastServicedBoundary = null;
    }

    private boolean isUnrepresented(HardwareTimingJob job) {
        return unrepresentedSubmissions.contains(job.handle());
    }

    private void releasePreparedInFifoOrder(
            HardwareWorkKind kind, Predicate<HardwareTimingJob> scope) {
        for (HardwareTimingJob job : jobs) {
            if (job.handle().kind() != kind || !scope.test(job)) {
                continue;
            }
            if (job.isClaimed() || job.isReady()) {
                continue;
            }
            if (!job.hasPreparedPayload()) {
                return;
            }
            if (!job.isProfileActive()) {
                job.activateProfile(loadTimeProfile.assign(
                        job.submission(), job.handle()));
            }
            if (!job.isProfileComplete()) {
                return;
            }
            job.admitReadiness();
        }
    }

    private void activateAndAdvanceHead(
            HardwareServiceBoundary boundary, HardwareWorkKind kind,
            Predicate<HardwareTimingJob> scope) {
        for (HardwareTimingJob job : jobs) {
            if (job.handle().kind() != kind || job.isPhysicallyRetired()
                    || !scope.test(job)) {
                continue;
            }
            if (!job.isProfileActive()) {
                job.activateProfile(loadTimeProfile.assign(
                        job.submission(), job.handle()));
            }
            job.advanceProfile(boundary);
            return;
        }
    }

    private void serviceBoundaryDrivenHead(
            HardwareServiceBoundary boundary, HardwareWorkKind kind) {
        for (HardwareTimingJob job : jobs) {
            if (job.handle().kind() != kind) {
                continue;
            }
            if (job.isClaimed() || job.hasPreparedPayload()) {
                continue;
            }
            HardwareWorkPreparation preparation = job.preparation();
            if (!preparation.isBoundaryDriven()) {
                return;
            }
            preparation.serviceBoundary(boundary);
            if (preparation.isPrepared()) {
                job.capturePreparedPayload();
            }
            return;
        }
    }

    private HardwareTimingJob firstAwaitingAdmission(HardwareWorkKind kind) {
        for (HardwareTimingJob job : jobs) {
            if (job.handle().kind() == kind
                    && !job.isClaimed()
                    && !job.isReady()) {
                return job;
            }
        }
        return null;
    }

    private HardwareTimingJob find(HardwareWorkHandle handle) {
        if (handle == null) {
            return null;
        }
        for (HardwareTimingJob job : jobs) {
            if (job.handle().equals(handle)) {
                return job;
            }
        }
        return null;
    }

    private HardwareTimingJob requireKnown(HardwareWorkHandle handle) {
        Objects.requireNonNull(handle, "handle");
        HardwareTimingJob job = find(handle);
        if (job == null) {
            throw new IllegalArgumentException(
                    "unknown hardware work handle: " + HardwareTimingJob.describe(handle));
        }
        return job;
    }

    private List<PendingRecordedSubmission> recordedPendingSubmissions() {
        return jobs.stream()
                .filter(job -> !job.isClaimed()
                        && admissionPolicyFor(job.handle().kind())
                        == HardwareReadinessAdmissionPolicy.RECORDED)
                .map(job -> new PendingRecordedSubmission(
                        job.handle(),
                        job.submission().exportableAcrossSegment()))
                .toList();
    }

    private void requireRecordedAdmission() {
        if (!recordedAdmissionActive) {
            throw new IllegalStateException("recorded hardware admission is not active");
        }
    }

    private String pendingDescription() {
        List<HardwareWorkHandle> pending = pendingHandles();
        if (pending.isEmpty()) {
            return "<none>";
        }
        return pending.stream()
                .map(HardwareTimingJob::describe)
                .toList()
                .toString();
    }

    private final class RecordedAuthority implements RecordedCompletionAuthority {
        @Override
        public void setRecordedRowRepresentation(boolean representingRecordedRow) {
            recordedRowRepresented = representingRecordedRow;
        }

        @Override
        public void configureAdmissionPolicies(
                Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
            requireRecordedAdmission();
            if (hasSubmitted && !admissionPolicies.equals(policies)) {
                throw new IllegalStateException(
                        "recorded hardware admission policy must be configured before the first submission");
            }
            if (hasSubmitted) {
                return;
            }
            installAdmissionPolicies(policies);
        }

        @Override
        public void initializeOrdinalBases(
                Map<HardwareWorkKind, Long> firstOrdinals) {
            requireRecordedAdmission();
            Objects.requireNonNull(firstOrdinals, "firstOrdinals");
            for (Map.Entry<HardwareWorkKind, Long> entry
                    : firstOrdinals.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "hardware work kind");
                Long ordinal = Objects.requireNonNull(
                        entry.getValue(), "first hardware work ordinal");
                if (ordinal < 0) {
                    throw new IllegalArgumentException(
                            "hardware work ordinal base must be non-negative: "
                                    + entry.getKey() + "#" + ordinal);
                }
            }
            for (Map.Entry<HardwareWorkKind, Long> entry
                    : firstOrdinals.entrySet()) {
                HardwareWorkKind kind = entry.getKey();
                if (!nextOrdinals.containsKey(kind)) {
                    nextOrdinals.put(kind, entry.getValue());
                }
            }
        }

        @Override
        public void advanceOrdinalCursorAcrossRecordedSpan(
                Map<HardwareWorkKind, RecordedOrdinalSpan> spans) {
            requireRecordedAdmission();
            Objects.requireNonNull(spans, "spans");
            if (spans.isEmpty()) {
                return;
            }
            List<PendingRecordedSubmission> pending = recordedPendingSubmissions();
            if (!pending.isEmpty()) {
                // The cursor is the allocator for the *next* handle. Moving it
                // while production still holds an unclaimed one would leave that
                // handle numbered on the old axis with no completion able to
                // reach it, which is the silent desync this whole path exists
                // to prevent.
                throw new IllegalStateException(
                        "cannot advance the hardware identity cursor while production "
                                + "holds pending submissions: " + pending.stream()
                                .map(PendingRecordedSubmission::handle)
                                .map(HardwareTimingJob::describe)
                                .toList());
            }
            for (Map.Entry<HardwareWorkKind, RecordedOrdinalSpan> entry : spans.entrySet()) {
                HardwareWorkKind kind = Objects.requireNonNull(entry.getKey(), "hardware work kind");
                RecordedOrdinalSpan span = Objects.requireNonNull(entry.getValue(), "recorded ordinal span");
                if (admissionPolicyFor(kind) != HardwareReadinessAdmissionPolicy.RECORDED) {
                    throw new IllegalStateException(
                            "recorded ordinal spans only apply to recorded-admission kinds: " + kind);
                }
                long cursor = nextOrdinals.getOrDefault(kind, 0L);
                if (cursor != span.firstOrdinal()) {
                    throw new IllegalStateException(
                            "recorded ordinal span does not begin at the production cursor for "
                                    + kind + ": production next=" + cursor
                                    + ", recorded span=" + span.firstOrdinal()
                                    + ".." + span.lastOrdinal());
                }
                nextOrdinals.put(kind, span.nextOrdinal());
            }
        }

        @Override
        public void admitRecordedCompletion(
                HardwareServiceBoundary boundary,
                HardwareWorkKind kind,
                long ordinal,
                String submissionFingerprint) {
            admitRecordedCompletion(
                    boundary, kind, ordinal, submissionFingerprint, true);
        }

        @Override
        public void admitRecordedSuppressedRowCompletion(
                HardwareServiceBoundary boundary,
                HardwareWorkKind kind,
                long ordinal,
                String submissionFingerprint) {
            if (boundary != HardwareServiceBoundary.PRE_MAIN_LOOP) {
                throw new IllegalArgumentException(
                        "suppressed-row completion requires PRE_MAIN_LOOP: "
                                + boundary);
            }
            admitRecordedCompletion(
                    boundary, kind, ordinal, submissionFingerprint, false);
        }

        private void admitRecordedCompletion(
                HardwareServiceBoundary boundary,
                HardwareWorkKind kind,
                long ordinal,
                String submissionFingerprint,
                boolean requireServicedBoundary) {
            requireRecordedAdmission();
            Objects.requireNonNull(boundary, "boundary");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(submissionFingerprint, "submissionFingerprint");
            if (admissionPolicyFor(kind) != HardwareReadinessAdmissionPolicy.RECORDED) {
                throw new IllegalStateException(
                        "recorded completion kind is not recorded by this stream: " + kind);
            }
            if (requireServicedBoundary && lastServicedBoundary != boundary) {
                throw new IllegalStateException(
                        "recorded completion boundary mismatch: expected " + boundary
                                + ", production serviced " + lastServicedBoundary);
            }

            HardwareTimingJob engineHead = firstAwaitingAdmission(kind);
            String expected = kind + "#" + ordinal + " " + submissionFingerprint;
            if (engineHead == null) {
                throw new UnmatchedRecordedCompletionException(
                        "expected completion: " + expected
                                + "; engine pending: " + pendingDescription());
            }
            if (engineHead.handle().ordinal() != ordinal
                    || !engineHead.handle().submissionFingerprint()
                    .equals(submissionFingerprint)) {
                throw new UnmatchedRecordedCompletionException(
                        "expected completion: " + expected
                                + "; engine pending: "
                                + HardwareTimingJob.describe(engineHead.handle()));
            }
            if (!engineHead.hasPreparedPayload()
                    || !engineHead.preparation().isPrepared()) {
                throw new UnmatchedRecordedCompletionException(
                        "expected completion: " + expected
                                + "; engine job is not prepared");
            }
            engineHead.admitReadiness();
        }

        @Override
        public List<PendingRecordedSubmission> pendingSubmissions() {
            requireRecordedAdmission();
            return recordedPendingSubmissions();
        }

        @Override
        public void endRecordedAdmission() {
            requireRecordedAdmission();
            List<PendingRecordedSubmission> pending = recordedPendingSubmissions();
            // Recorded admission ends either way. A leftover submission is
            // described, never admitted, prepared, released or retired, so the
            // run is genuinely over whether or not the caller demotes the
            // complaint into a comparison error.
            admissionPolicies.clear();
            admissionPolicies.putAll(liveAdmissionPolicies());
            recordedAdmissionActive = false;
            lastServicedBoundary = null;
            if (!pending.isEmpty()) {
                throw new PendingRecordedSubmissionsException(
                        "unexpected pending hardware submissions at final run: " + pending,
                        pending);
            }
        }
    }

    private List<HardwareTimingJob> jobsOfKind(HardwareWorkKind kind) {
        return jobs.stream().filter(job -> job.handle().kind() == kind).toList();
    }

    private void installAdmissionPolicies(
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
        EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy> checked =
                validateAdmissionPolicies(policies);
        admissionPolicies.clear();
        admissionPolicies.putAll(checked);
    }

    private static EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy>
            validateAdmissionPolicies(
                    Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
        Objects.requireNonNull(policies, "admissionPolicies");
        EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy> checked =
                new EnumMap<>(HardwareWorkKind.class);
        for (HardwareWorkKind kind : HardwareWorkKind.values()) {
            HardwareReadinessAdmissionPolicy policy = policies.get(kind);
            if (policy == null) {
                throw new IllegalArgumentException(
                        "recorded admission policy is missing kind " + kind);
            }
            checked.put(kind, policy);
        }
        if (checked.values().stream().noneMatch(
                policy -> policy == HardwareReadinessAdmissionPolicy.RECORDED)) {
            throw new IllegalArgumentException(
                    "recorded admission policy cannot leave every kind live");
        }
        return checked;
    }

    private static EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy>
            liveAdmissionPolicies() {
        EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies =
                new EnumMap<>(HardwareWorkKind.class);
        for (HardwareWorkKind kind : HardwareWorkKind.values()) {
            policies.put(kind, HardwareReadinessAdmissionPolicy.LIVE);
        }
        return policies;
    }

    private static Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy>
            recordedAdmissionPolicies() {
        EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies =
                new EnumMap<>(HardwareWorkKind.class);
        for (HardwareWorkKind kind : HardwareWorkKind.values()) {
            policies.put(kind, HardwareReadinessAdmissionPolicy.RECORDED);
        }
        return Map.copyOf(policies);
    }
}
