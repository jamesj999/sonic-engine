package com.openggf.game.timing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static com.openggf.game.timing.HardwareServiceBoundary.POST_OBJECTS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestHardwareTimingRewind {

    @Test
    void returnedSnapshotCannotExposeOrAdvanceTheLivePreparation() throws Exception {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle handle = service.submit(submission(2, 11));
        HardwareTimingSnapshot snapshot = service.capture();

        boolean exposedLivePreparation = false;
        Object jobSnapshot = snapshot.jobs().getFirst();
        for (Method accessor : jobSnapshot.getClass().getMethods()) {
            if (accessor.getParameterCount() != 0) {
                continue;
            }
            Object exposed;
            if (accessor.getReturnType() == HardwareWorkSubmission.class) {
                exposed = accessor.invoke(jobSnapshot);
                ((HardwareWorkSubmission) exposed).preparation().stepOneWorkUnit();
                exposedLivePreparation = true;
            } else if (accessor.getReturnType() == HardwareWorkPreparation.class) {
                exposed = accessor.invoke(jobSnapshot);
                ((HardwareWorkPreparation) exposed).stepOneWorkUnit();
                exposedLivePreparation = true;
            }
        }

        service.service(POST_OBJECTS);

        assertFalse(exposedLivePreparation,
                "a rewind snapshot must not publish the live submission or preparation");
        assertFalse(service.isReady(handle),
                "inspecting the returned snapshot must not advance live preparation");
        service.service(POST_OBJECTS);
        assertTrue(service.isReady(handle));
        byte[] exposedPayload =
                service.capture().jobs().getFirst().preparedPayload();
        exposedPayload[0] = 99;
        assertArrayEquals(new byte[] {11}, service.claim(handle),
                "mutating snapshot output must not mutate the service payload");
    }

    @Test
    void historicalSnapshotRecreatesItsOwnPreparationAfterIdenticalHandleResubmission() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareTimingSnapshot beforeSubmission = service.capture();
        HardwareWorkHandle historicalHandle = service.submit(canonicalSubmission(
                new TestPreparation(2, new byte[] {31})));
        HardwareTimingSnapshot historicalBranch = service.capture();
        HardwareWorkPreparation inspectedCopy = historicalBranch.jobs().getFirst()
                .preparationSnapshot().recreatePreparation();
        inspectedCopy.stepOneWorkUnit();

        service.restore(beforeSubmission);
        HardwareWorkHandle replacementHandle = service.submit(canonicalSubmission(
                new IncompatiblePreparation(new byte[] {99})));
        assertEquals(historicalHandle, replacementHandle,
                "the adversarial replacement must collide on canonical handle identity");

        service.restore(historicalBranch);
        service.service(POST_OBJECTS);
        assertFalse(service.isReady(historicalHandle));
        service.service(POST_OBJECTS);

        assertArrayEquals(new byte[] {31}, service.claim(historicalHandle),
                "restoring the historical branch must recreate its original preparation");
    }

    @Test
    void restoreImmediatelyBeforeCompletionRepeatsServiceClaimAndOrdinalAllocation() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle first = service.submit(submission(1, 12));
        HardwareTimingSnapshot beforeCompletion = service.capture();

        service.service(POST_OBJECTS);
        assertTrue(service.isReady(first));
        assertArrayEquals(new byte[] {12}, service.claim(first));
        HardwareWorkHandle future = service.submit(submission(1, 13));
        assertEquals(1, future.ordinal());

        service.restore(beforeCompletion);

        assertTrue(service.isPending(first));
        assertFalse(service.isReady(first));
        service.service(POST_OBJECTS);
        assertArrayEquals(new byte[] {12}, service.claim(first));
        assertEquals(future.ordinal(), service.submit(submission(1, 13)).ordinal());
    }

    @Test
    void preActivationRewindRepeatsFeatureBasedEstimatedSelection() {
        LoadTimeProfile estimator = (submission, handle) ->
                new LoadTimeDecision(
                        (submission.features().shortCopyCommands() + 3) / 4,
                        java.util.Set.of(POST_OBJECTS),
                        LoadTimeDecisionSource.ESTIMATED,
                        "test-estimator");
        HardwareTimingService service = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(POST_OBJECTS), estimator);
        HardwareWorkSubmission submission = new HardwareWorkSubmission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                0x2000, 8, 0x5000, 1, "kosinski", 1, false,
                new HardwareWorkFeatures(1, 9, 0, 0, 8, 1, 1, 1, 0),
                new TestPreparation(1, new byte[] {42}));
        service.submit(submission);
        HardwareTimingSnapshot beforeActivation = service.capture();

        service.service(POST_OBJECTS);
        HardwareTimingJob.Snapshot first = service.capture().jobs().getFirst();

        service.restore(beforeActivation);
        service.service(POST_OBJECTS);
        HardwareTimingJob.Snapshot repeated =
                service.capture().jobs().getFirst();

        assertEquals(3, first.assignedServiceFrames());
        assertEquals(first.assignedServiceFrames(),
                repeated.assignedServiceFrames());
        assertEquals(first.decisionSource(), repeated.decisionSource());
        assertEquals(first.features(), repeated.features());
    }

    @Test
    void restoreOnCompletionRepeatsExactlyOneClaimAndOrdinalAllocation() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle first = service.submit(submission(1, 14));
        service.service(POST_OBJECTS);
        HardwareTimingSnapshot onCompletion = service.capture();

        assertArrayEquals(new byte[] {14}, service.claim(first));
        HardwareWorkHandle future = service.submit(submission(1, 15));

        service.restore(onCompletion);

        assertTrue(service.isReady(first));
        assertArrayEquals(new byte[] {14}, service.claim(first));
        assertThrows(IllegalStateException.class, () -> service.claim(first));
        assertEquals(future.ordinal(), service.submit(submission(1, 15)).ordinal());
    }

    @Test
    void restoreAfterCompletionPreservesClaimAndReplaysEarlierSnapshot() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle first = service.submit(submission(1, 16));
        HardwareTimingSnapshot beforeCompletion = service.capture();
        service.service(POST_OBJECTS);
        service.claim(first);
        HardwareTimingSnapshot afterCompletion = service.capture();
        HardwareWorkHandle future = service.submit(submission(1, 17));

        service.restore(beforeCompletion);
        service.service(POST_OBJECTS);
        assertArrayEquals(new byte[] {16}, service.claim(first));

        service.restore(afterCompletion);
        assertFalse(service.isPending(first));
        assertFalse(service.isReady(first));
        assertThrows(IllegalStateException.class, () -> service.claim(first));
        assertEquals(future.ordinal(), service.submit(submission(1, 17)).ordinal());
    }

    @Test
    void recordedPreparationAndAdmissionStateAreRewindSafe() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle handle = service.submit(submission(1, 18));
        HardwareTimingSnapshot beforePreparation = service.capture();

        service.service(POST_OBJECTS);
        HardwareTimingSnapshot preparedHeld = service.capture();
        authority.admitRecordedCompletion(
                POST_OBJECTS, handle.kind(), handle.ordinal(),
                handle.submissionFingerprint());
        HardwareTimingSnapshot admitted = service.capture();

        service.restore(beforePreparation);
        service.service(POST_OBJECTS);
        assertFalse(service.isReady(handle));

        service.restore(preparedHeld);
        assertFalse(service.isReady(handle));
        authority.admitRecordedCompletion(
                POST_OBJECTS, handle.kind(), handle.ordinal(),
                handle.submissionFingerprint());
        assertTrue(service.isReady(handle));

        service.restore(admitted);
        assertTrue(service.isReady(handle));
        assertArrayEquals(new byte[] {18}, service.claim(handle));
    }

    @Test
    void perKindAdmissionPoliciesAndOrdinalsRoundTripThroughRewind() {
        HardwareTimingService service = new HardwareTimingService();
        service.beginRecordedAdmission(Map.of(
                HardwareWorkKind.KOS_MODULE_QUEUE, HardwareReadinessAdmissionPolicy.RECORDED,
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, HardwareReadinessAdmissionPolicy.LIVE,
                HardwareWorkKind.NEMESIS_PLC_QUEUE, HardwareReadinessAdmissionPolicy.LIVE));
        HardwareWorkHandle direct = service.submit(submission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 1, 19));
        HardwareTimingSnapshot snapshot = service.capture();

        service.submit(submission(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 1, 20));
        service.restore(snapshot);

        assertEquals(HardwareReadinessAdmissionPolicy.LIVE,
                service.admissionPolicyFor(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE));
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                service.admissionPolicyFor(HardwareWorkKind.KOS_MODULE_QUEUE));
        assertEquals(1, service.submit(submission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 1, 20)).ordinal());
    }

    @Test
    void snapshotRejectsIncompleteOrAllLiveRecordedPolicyMaps() {
        assertThrows(IllegalArgumentException.class, () -> new HardwareTimingSnapshot(
                Map.of(), java.util.List.of(),
                Map.of(HardwareWorkKind.KOS_MODULE_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED),
                true, false, null));
        assertThrows(IllegalArgumentException.class, () -> new HardwareTimingSnapshot(
                Map.of(), java.util.List.of(),
                Map.of(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        HardwareReadinessAdmissionPolicy.LIVE,
                        HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                        HardwareReadinessAdmissionPolicy.LIVE),
                true, false, null));
    }

    @Test
    void claimedJobSnapshotsAreSharedUntilTheJobMutatesAndRestoreKeepsUnchangedJobs() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle handle = service.submit(submission(2, 11));
        HardwareTimingJob.Snapshot inFlight = service.capture().jobs().getFirst();
        assertNotSame(inFlight, service.capture().jobs().getFirst(),
                "an in-flight preparation must be re-snapshotted every capture");

        service.service(POST_OBJECTS);
        service.service(POST_OBJECTS);
        assertTrue(service.isReady(handle));
        HardwareTimingSnapshot ready = service.capture();
        HardwareTimingJob.Snapshot readyJob = ready.jobs().getFirst();
        assertNotSame(readyJob, service.capture().jobs().getFirst(),
                "a ready but unclaimed job is still exposed to its coordinator, so no memo");

        assertArrayEquals(new byte[] {11}, service.claim(handle));
        HardwareTimingSnapshot claimed = service.capture();
        HardwareTimingJob.Snapshot claimedJob = claimed.jobs().getFirst();
        assertTrue(claimedJob.claimed());
        assertSame(claimedJob, service.capture().jobs().getFirst(),
                "a claimed job shares one immutable snapshot across captures");
        assertArrayEquals(new byte[] {11}, claimedJob.preparedPayload());

        service.restore(ready);
        assertTrue(service.isReady(handle), "restoring the ready snapshot must un-claim the job");
        assertNotSame(claimedJob, service.capture().jobs().getFirst(),
                "an un-claimed job drops the memo");
        assertArrayEquals(new byte[] {11}, service.claim(handle));

        service.restore(claimed);
        assertFalse(service.isReady(handle));
        assertSame(claimedJob, service.capture().jobs().getFirst(),
                "a restored claimed job is already in the snapshot's state and memoizes that instance");
        assertArrayEquals(new byte[] {11}, service.claimedPayload(claimedJob.kind(), handle.ordinal()));
        service.restore(ready);
        assertTrue(service.isReady(handle), "A -> B -> A restoration must land on A");
        assertArrayEquals(new byte[] {11}, claimedJob.preparedPayload(),
                "historical snapshot bytes survive live claims and restores");
    }

    @Test
    void coordinatorRestoringAnUnclaimedPreparationIsSeenByTheNextCapture() {
        // CS356/CS358 regression: the coordinator alias may rewind a live,
        // unclaimed preparation between captures; the next capture must
        // reflect that, not a memo taken while it was prepared.
        HardwareTimingService service = new HardwareTimingService();
        HardwareWorkHandle handle = service.submit(submission(2, 11));
        HardwareTimingSnapshot pending = service.capture();
        service.service(POST_OBJECTS);
        service.service(POST_OBJECTS);
        assertTrue(service.isReady(handle));
        HardwareTimingSnapshot ready = service.capture();
        assertTrue(ready.jobs().getFirst().preparationSnapshot()
                .recreatePreparation().isPrepared());

        service.coordinatorPreparation(handle)
                .restore(pending.jobs().getFirst().preparationSnapshot());
        assertFalse(service.coordinatorPreparation(handle).isPrepared());
        HardwareTimingSnapshot after = service.capture();
        assertNotSame(ready.jobs().getFirst(), after.jobs().getFirst(),
                "a mutated unclaimed preparation must not reuse the ready snapshot");
        assertFalse(after.jobs().getFirst().preparationSnapshot()
                .recreatePreparation().isPrepared(),
                "capture must see the preparation the coordinator rewound");
    }

    private static HardwareWorkSubmission submission(int workUnits, int payloadByte) {
        return submission(HardwareWorkKind.KOS_MODULE_QUEUE, workUnits, payloadByte);
    }

    private static HardwareWorkSubmission submission(
            HardwareWorkKind kind, int workUnits, int payloadByte) {
        return new HardwareWorkSubmission(
                kind,
                0x2000 + payloadByte,
                0x80,
                0x5000,
                1,
                "KosM",
                1,
                false,
                new TestPreparation(workUnits, new byte[] {(byte) payloadByte}));
    }

    private static HardwareWorkSubmission canonicalSubmission(
            HardwareWorkPreparation preparation) {
        return new HardwareWorkSubmission(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                0x2800,
                0x80,
                0x5000,
                1,
                "KosM",
                1,
                false,
                preparation);
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
            return payload.clone();
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

    private static final class IncompatiblePreparation
            implements HardwareWorkPreparation {
        private final byte[] payload;

        private IncompatiblePreparation(byte[] payload) {
            this.payload = payload.clone();
        }

        @Override
        public boolean stepOneWorkUnit() {
            return true;
        }

        @Override
        public boolean isPrepared() {
            return true;
        }

        @Override
        public byte[] preparedPayload() {
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new IncompatibleSnapshot(payload);
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            throw new AssertionError(
                    "historical snapshots must not restore into replacement preparations");
        }
    }

    private record IncompatibleSnapshot(byte[] payload)
            implements HardwareWorkPreparationSnapshot {
        private IncompatibleSnapshot {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparation recreatePreparation() {
            return new IncompatiblePreparation(payload);
        }
    }
}
