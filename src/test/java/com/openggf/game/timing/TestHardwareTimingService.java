package com.openggf.game.timing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.openggf.game.timing.HardwareServiceBoundary.POST_OBJECTS;
import static com.openggf.game.timing.HardwareServiceBoundary.PRE_MAIN_LOOP;
import static com.openggf.game.timing.HardwareServiceBoundary.VINT_SERVICE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHardwareTimingService {

    @Test
    void completedLiveEpochCanBecomeRecordedWithANonzeroOrdinalBase() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle live = service.submit(
                submission(1, new byte[] {31}));
        service.service(POST_OBJECTS);
        service.claim(live);

        RecordedCompletionAuthority authority =
                service.beginRecordedAdmissionAfterLiveEpoch();
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                service.admissionPolicyFor(HardwareWorkKind.KOS_MODULE_QUEUE));
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                service.admissionPolicyFor(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE));
        authority.initializeOrdinalBases(
                Map.of(HardwareWorkKind.KOS_MODULE_QUEUE, 37L));
        HardwareWorkHandle recorded = service.submit(
                submission(1, new byte[] {32}));
        service.service(POST_OBJECTS);

        assertEquals(37, recorded.ordinal());
        assertFalse(service.isReady(recorded));
        authority.admitRecordedCompletion(
                POST_OBJECTS, recorded.kind(), recorded.ordinal(),
                recorded.submissionFingerprint());
        assertTrue(service.isReady(recorded));
    }

    @Test
    void liveToRecordedEpochRejectsPendingWorkAndInvalidPolicyWithoutMutation() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle pending = service.submit(
                submission(1, new byte[] {33}));
        HardwareTimingSnapshot beforePendingRejection = service.capture();

        assertThrows(IllegalStateException.class,
                service::beginRecordedAdmissionAfterLiveEpoch);
        assertEquals(beforePendingRejection.nextOrdinals(),
                service.capture().nextOrdinals());
        assertEquals(beforePendingRejection.admissionPolicies(),
                service.capture().admissionPolicies());
        assertEquals(List.of(pending), service.pendingHandles());

        service.service(POST_OBJECTS);
        service.claim(pending);
        HardwareTimingSnapshot beforePolicyRejection = service.capture();
        assertThrows(IllegalArgumentException.class,
                () -> service.beginRecordedAdmissionAfterLiveEpoch(Map.of()));
        assertEquals(beforePolicyRejection.nextOrdinals(),
                service.capture().nextOrdinals());
        assertEquals(beforePolicyRejection.jobs().size(),
                service.capture().jobs().size());
        assertEquals(beforePolicyRejection.admissionPolicies(),
                service.capture().admissionPolicies());
        assertFalse(service.capture().recordedAdmissionActive());
    }

    @Test
    void liveToRecordedEpochCannotBeActivatedTwice() {
        HardwareTimingService service = new HardwareTimingService();
        service.beginRecordedAdmissionAfterLiveEpoch();

        assertThrows(IllegalStateException.class,
                service::beginRecordedAdmissionAfterLiveEpoch);
    }

    @Test
    void liveReadinessWaitsForPreparationAndProfileCountdown() {
        LoadTimeProfile profile = (submission, handle) -> new LoadTimeDecision(
                2,
                Set.of(POST_OBJECTS),
                LoadTimeDecisionSource.MEASURED,
                "test-v1");
        HardwareTimingService service = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(POST_OBJECTS), profile);
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {42}));

        service.service(POST_OBJECTS);
        assertFalse(service.isReady(handle));
        service.service(POST_OBJECTS);
        assertTrue(service.isReady(handle));
    }

    @Test
    void readyButUnclaimedJobDoesNotBlockNextPhysicalHead() {
        HardwareTimingService service = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(POST_OBJECTS),
                LoadTimeProfile.IMMEDIATE);
        HardwareWorkHandle first = service.submit(submission(1, new byte[] {43}));
        HardwareWorkHandle second = service.submit(submission(1, new byte[] {44}));

        service.service(POST_OBJECTS);
        assertTrue(service.isReady(first));
        service.service(POST_OBJECTS);

        assertTrue(service.isReady(first));
        assertTrue(service.isReady(second));
    }

    @Test
    void recordedKindNeverConsultsNormalPlayProfile() {
        AtomicInteger assignments = new AtomicInteger();
        LoadTimeProfile profile = (submission, handle) -> {
            assignments.incrementAndGet();
            return LoadTimeProfile.IMMEDIATE.assign(submission, handle);
        };
        HardwareTimingService service = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(POST_OBJECTS), profile);
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {45}));

        service.service(POST_OBJECTS);
        assertEquals(0, assignments.get());
        authority.admitRecordedCompletion(
                POST_OBJECTS, handle.kind(), handle.ordinal(),
                handle.submissionFingerprint());
        assertTrue(service.isReady(handle));
    }

    @Test
    void ordinalsAreMonotonicPerKind() {
        HardwareTimingService service = new HardwareTimingService();

        HardwareWorkHandle first = service.submit(submission(1, new byte[] {1}));
        HardwareWorkHandle second = service.submit(submission(1, new byte[] {2}));

        assertEquals(0, first.ordinal());
        assertEquals(1, second.ordinal());
        assertEquals(first.kind(), second.kind());
    }

    @Test
    void fifoServiceNeverReleasesLaterJobFirst() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle first = service.submit(submission(2, new byte[] {1}));
        HardwareWorkHandle second = service.submit(submission(1, new byte[] {2}));

        service.service(POST_OBJECTS);
        assertFalse(service.isReady(first));
        assertFalse(service.isReady(second));

        service.service(POST_OBJECTS);
        assertTrue(service.isReady(first));
        assertFalse(service.isReady(second));

        service.service(POST_OBJECTS);
        assertTrue(service.isReady(second));
        assertEquals(List.of(first, second), service.pendingHandles());
    }

    @Test
    void configuredBoundaryOwnsIntegerWorkBudget() {
        HardwareTimingService service = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(POST_OBJECTS));
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {7}));

        service.service(VINT_SERVICE);
        assertFalse(service.isReady(handle));

        service.service(POST_OBJECTS);
        assertTrue(service.isReady(handle));
    }

    @Test
    void claimBeforeReadinessAndSecondClaimFail() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {3}));

        assertThrows(IllegalStateException.class, () -> service.claim(handle));

        service.service(POST_OBJECTS);
        assertArrayEquals(new byte[] {3}, service.claim(handle));
        assertFalse(service.isPending(handle));
        assertThrows(IllegalStateException.class, () -> service.claim(handle));
    }

    @Test
    void hostElapsedTimeCannotAdvancePreparation() throws InterruptedException {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {4}));

        Thread.sleep(10);

        assertTrue(service.isPending(handle));
        assertFalse(service.isReady(handle));
    }

    @Test
    void claimReturnsDefensiveCopyOfPreparedPayload() {
        byte[] sourcePayload = new byte[] {5, 6, 7};
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle handle = service.submit(submission(1, sourcePayload));
        service.service(POST_OBJECTS);
        HardwareTimingSnapshot readySnapshot = service.capture();

        sourcePayload[0] = 99;
        byte[] claimed = service.claim(handle);
        claimed[1] = 98;

        service.restore(readySnapshot);
        assertArrayEquals(new byte[] {5, 6, 7}, service.claim(handle));
    }

    @Test
    void claimedPayloadLookupIsReadOnlyAndRejectsNonClaimedWork() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle handle = service.submit(
                submission(1, new byte[] {12, 13}));

        assertThrows(IllegalStateException.class,
                () -> service.claimedPayload(handle.kind(), handle.ordinal()));
        service.service(POST_OBJECTS);
        assertThrows(IllegalStateException.class,
                () -> service.claimedPayload(handle.kind(), handle.ordinal()));
        service.claim(handle);

        byte[] firstLookup =
                service.claimedPayload(handle.kind(), handle.ordinal());
        firstLookup[0] = 99;
        assertArrayEquals(new byte[] {12, 13},
                service.claimedPayload(handle.kind(), handle.ordinal()));
        assertThrows(IllegalArgumentException.class,
                () -> service.claimedPayload(handle.kind(), handle.ordinal() + 1));
    }

    @Test
    void recordedAdmissionHoldsPreparedJobUntilMatchingEdge() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {8}));

        service.service(POST_OBJECTS);

        assertTrue(service.isPending(handle));
        assertFalse(service.isReady(handle));
        authority.admitRecordedCompletion(
                POST_OBJECTS, handle.kind(), handle.ordinal(),
                handle.submissionFingerprint());
        assertTrue(service.isReady(handle));
    }

    @Test
    void recordedAdmissionCannotPrepareAJob() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {9}));

        service.service(VINT_SERVICE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> authority.admitRecordedCompletion(
                        VINT_SERVICE, handle.kind(), handle.ordinal(),
                        handle.submissionFingerprint()));
        assertTrue(error.getMessage().contains("not prepared"), error::getMessage);
        assertFalse(service.isReady(handle));
    }

    @Test
    void suppressedRowAdmissionAcceptsOnlyPreparedPreMainLoopWorkAfterVint() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {46}));
        service.service(POST_OBJECTS);
        service.service(VINT_SERVICE);

        authority.admitRecordedSuppressedRowCompletion(
                PRE_MAIN_LOOP, handle.kind(), handle.ordinal(),
                handle.submissionFingerprint());

        assertTrue(service.isReady(handle));
    }

    @Test
    void suppressedRowAdmissionRejectsNonPreMainLoopBoundaries() {
        for (HardwareServiceBoundary boundary : List.of(VINT_SERVICE, POST_OBJECTS)) {
            HardwareTimingService service = new HardwareTimingService();
            RecordedCompletionAuthority authority = service.beginRecordedAdmission();
            HardwareWorkHandle handle = service.submit(submission(1, new byte[] {47}));
            service.service(POST_OBJECTS);
            service.service(VINT_SERVICE);

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> authority.admitRecordedSuppressedRowCompletion(
                            boundary, handle.kind(), handle.ordinal(),
                            handle.submissionFingerprint()));

            assertTrue(error.getMessage().contains("PRE_MAIN_LOOP"), error::getMessage);
            assertFalse(service.isReady(handle));
        }
    }

    @Test
    void ordinaryAdmissionStillRequiresTheProductionServiceBoundary() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {48}));
        service.service(POST_OBJECTS);
        service.service(VINT_SERVICE);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> authority.admitRecordedCompletion(
                        PRE_MAIN_LOOP, handle.kind(), handle.ordinal(),
                        handle.submissionFingerprint()));

        assertTrue(error.getMessage().contains(
                "expected PRE_MAIN_LOOP, production serviced VINT_SERVICE"),
                error::getMessage);
        assertFalse(service.isReady(handle));
    }

    @Test
    void recordedAdmissionStartsOnlyBeforeTheFirstSubmission() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        assertThrows(IllegalStateException.class, service::beginRecordedAdmission);

        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {10}));
        assertEquals(List.of(new PendingRecordedSubmission(handle, false)),
                authority.pendingSubmissions());

        service.service(POST_OBJECTS);
        authority.admitRecordedCompletion(
                POST_OBJECTS, handle.kind(), handle.ordinal(),
                handle.submissionFingerprint());
        service.claim(handle);
        authority.endRecordedAdmission();

        HardwareWorkHandle liveHandle = service.submit(submission(1, new byte[] {11}));
        service.service(POST_OBJECTS);
        assertTrue(service.isReady(liveHandle));
        assertThrows(IllegalStateException.class, service::beginRecordedAdmission);
    }

    /**
     * Closing with a leftover submission reports it and still ends recorded
     * admission. {@code 52b0f2859} made the close describe the leftover rather
     * than abort on it, so the ledger really is live again afterwards -- the
     * clause this replaces asserted the older abort-and-stay-open behaviour and
     * had been red since that commit.
     */
    @Test
    void recordedAdmissionEndsEvenWhenASubmissionIsStillOutstanding() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();

        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {12}));
        PendingRecordedSubmissionsException leftover = assertThrows(
                PendingRecordedSubmissionsException.class,
                authority::endRecordedAdmission);
        assertEquals(List.of(new PendingRecordedSubmission(handle, false)),
                leftover.pending());

        // Admission is over, so the authority no longer answers at all...
        assertThrows(IllegalStateException.class, authority::pendingSubmissions);
        // ...and the leftover was described, never admitted or released.
        assertFalse(service.isReady(handle));
        assertTrue(service.isPending(handle));

        // The ledger is live again: the leftover is released by ordinary
        // servicing, with no recorded edge and no authority left to supply one.
        service.service(POST_OBJECTS);
        assertTrue(service.isReady(handle));
    }

    @Test
    void v5RecordedAdmissionAuthorizesBothQueueKindsOnlyAtRecordedEdges() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle direct = service.submit(submission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 1, new byte[] {21}));
        HardwareWorkHandle module = service.submit(submission(
                HardwareWorkKind.KOS_MODULE_QUEUE, 1, new byte[] {22}));

        service.service(POST_OBJECTS);

        assertFalse(service.isReady(direct));
        assertFalse(service.isReady(module));
        authority.admitRecordedCompletion(
                POST_OBJECTS, direct.kind(), direct.ordinal(),
                direct.submissionFingerprint());
        assertTrue(service.isReady(direct));

        authority.admitRecordedCompletion(
                POST_OBJECTS, module.kind(), module.ordinal(),
                module.submissionFingerprint());
        assertTrue(service.isReady(module));
    }

    @Test
    void recordedPolicyMapIsCompleteAndCannotLeaveAStreamFullyLive() {
        HardwareTimingService service = new HardwareTimingService();

        assertThrows(IllegalArgumentException.class,
                () -> service.beginRecordedAdmission(Map.of()));
    }

    /**
     * The identity return moves the allocator, so it is bounded by the same
     * invariant {@code advanceOrdinalCursorAcrossRecordedSpan}'s guard states:
     * it may only move while production holds nothing unclaimed that the move
     * would renumber, and only the most recently allocated identity may go
     * back. Each rejection leaves the cursor and the ledger untouched.
     */
    @Test
    void returningAnUnrepresentedIdentityIsRefusedUnlessTheAxisIsProvablyFree() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        authority.setRecordedRowRepresentation(false);

        HardwareWorkHandle first = service.submit(submission(1, new byte[] {41}));
        service.service(POST_OBJECTS);
        service.admitUnrepresentedReadiness(first);

        // Unclaimed: production still owns the result behind this identity.
        assertThrows(IllegalStateException.class,
                () -> service.releaseUnrepresentedIdentity(first));
        assertEquals(1L, service.capture().nextOrdinals()
                .get(HardwareWorkKind.KOS_MODULE_QUEUE));

        HardwareWorkHandle second = service.submit(submission(1, new byte[] {42}));
        service.service(POST_OBJECTS);
        service.admitUnrepresentedReadiness(second);
        service.claim(second);

        // Claimed, and the most recent -- but `first` is still unclaimed and
        // would be stranded on the old axis by the move.
        assertThrows(IllegalStateException.class,
                () -> service.releaseUnrepresentedIdentity(second));
        assertEquals(2L, service.capture().nextOrdinals()
                .get(HardwareWorkKind.KOS_MODULE_QUEUE));

        service.claim(first);
        // Claimed and nothing pending -- but not the most recently allocated.
        assertThrows(IllegalStateException.class,
                () -> service.releaseUnrepresentedIdentity(first));
        assertEquals(2L, service.capture().nextOrdinals()
                .get(HardwareWorkKind.KOS_MODULE_QUEUE));

        service.releaseUnrepresentedIdentity(second);
        assertEquals(1L, service.capture().nextOrdinals()
                .get(HardwareWorkKind.KOS_MODULE_QUEUE));
        // The spent record is retired, so the reissued ordinal is unambiguous.
        HardwareWorkHandle reissued = service.submit(submission(1, new byte[] {43}));
        assertEquals(1L, reissued.ordinal());
        assertEquals(List.of(reissued), service.pendingHandles());
    }

    /**
     * Membership is fixed at submission, not at the moment of release: an
     * identity borrowed while row authority was deactivated is still returned
     * once production claims it, even though the run has since re-entered
     * coverage. Production cannot choose when it claims, so the opposite rule
     * would strand the borrowed ordinal whenever a claim landed on a
     * represented row.
     */
    @Test
    void anIdentityBorrowedOutsideCoverageIsReturnedAfterReenteringIt() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        authority.setRecordedRowRepresentation(false);

        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {44}));
        service.service(POST_OBJECTS);
        service.admitUnrepresentedReadiness(handle);
        service.claim(handle);

        authority.setRecordedRowRepresentation(true);
        service.releaseUnrepresentedIdentity(handle);
        assertEquals(0L, service.capture().nextOrdinals()
                .get(HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    /**
     * The other half of the same rule, and the clause that keeps the escape
     * narrow: work the recorder <em>did</em> count is on the recording's axis,
     * so its identity can never be returned while row authority represents a
     * row.
     */
    @Test
    void anIdentitySubmittedInsideCoverageIsNeverReturned() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        authority.setRecordedRowRepresentation(true);

        HardwareWorkHandle handle = service.submit(submission(1, new byte[] {45}));
        service.service(POST_OBJECTS);
        authority.admitRecordedCompletion(
                POST_OBJECTS, handle.kind(), handle.ordinal(),
                handle.submissionFingerprint());
        service.claim(handle);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.releaseUnrepresentedIdentity(handle));
        assertTrue(error.getMessage().contains(
                "only returned while recorded row authority is deactivated"),
                error::getMessage);
        assertEquals(1L, service.capture().nextOrdinals()
                .get(HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    private static HardwareWorkSubmission submission(int workUnits, byte[] payload) {
        return submission(HardwareWorkKind.KOS_MODULE_QUEUE, workUnits, payload);
    }

    private static HardwareWorkSubmission submission(
            HardwareWorkKind kind, int workUnits, byte[] payload) {
        return new HardwareWorkSubmission(
                kind,
                0x1000 + payload[0],
                0x100,
                0x4000,
                payload.length,
                "KosM",
                1,
                false,
                new TestPreparation(workUnits, payload));
    }

    private record PreparationSnapshot(int remainingUnits, byte[] payload)
            implements HardwareWorkPreparationSnapshot {
        private PreparationSnapshot {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparation recreatePreparation() {
            return new TestPreparation(remainingUnits, payload);
        }
    }

    private static final class TestPreparation implements HardwareWorkPreparation {
        private int remainingUnits;
        private final byte[] payload;

        private TestPreparation(int remainingUnits, byte[] payload) {
            this.remainingUnits = remainingUnits;
            this.payload = payload.clone();
        }

        @Override
        public boolean stepOneWorkUnit() {
            if (remainingUnits == 0) {
                return false;
            }
            remainingUnits--;
            return true;
        }

        @Override
        public boolean isPrepared() {
            return remainingUnits == 0;
        }

        @Override
        public byte[] preparedPayload() {
            if (!isPrepared()) {
                throw new IllegalStateException("payload requested before preparation");
            }
            return payload;
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new PreparationSnapshot(remainingUnits, payload);
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            remainingUnits = ((PreparationSnapshot) snapshot).remainingUnits();
        }
    }
}
