package com.openggf.trace.timing;

import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareSubmissionFingerprint;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkSubmission;
import com.openggf.game.timing.RecordedCompletionAuthority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openggf.game.timing.HardwareServiceBoundary.POST_OBJECTS;
import static com.openggf.game.timing.HardwareServiceBoundary.PRE_MAIN_LOOP;
import static com.openggf.game.timing.HardwareServiceBoundary.VINT_SERVICE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TestHardwareTimingReplayPort {

    @Test
    void prefixCloseAcceptsOnlyFutureEdgesWhenProductionLedgerIsEmpty() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                101, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                fingerprint('a')));

        port.verifyPrefixComplete(100);

        assertTrue(port.capture().runComplete());
    }

    @Test
    void prefixCloseRejectsUnconsumedEdgeWithinInclusiveBoundary() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                100, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                fingerprint('b')));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> port.verifyPrefixComplete(100));

        assertTrue(error.getMessage().contains("raw_frame=100"), error::getMessage);
    }

    @Test
    void prefixCloseRejectsPendingProductionSubmission() {
        ReplayHarness harness = harness(false, 1, 101);
        HardwareTimingReplayPort port = port(
                harness.authority, HardwareTimingSchedule.empty());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> port.verifyPrefixComplete(100));

        assertTrue(error.getMessage().contains(describe(harness.handle)), error::getMessage);
    }

    @Test
    void ordinaryRunCloseRemainsStrictForFutureEdge() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                101, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                fingerprint('c')));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                port::verifyRunComplete);

        // Demoted mid-run, still strict at close: nothing follows the closing
        // row that could carry the edge, so it may not be dropped silently.
        assertTrue(error.getMessage().contains("raw_frame=101"), error::getMessage);
        assertTrue(error.getMessage().contains("were never reported"),
                error::getMessage);
    }

    @Test
    void leftoverSubmissionAtCloseStillAbortsWithoutAnOptedInReporter() {
        ReplayHarness harness = harness(false, 0, 40);
        HardwareTimingReplayPort port = port(
                harness.authority, HardwareTimingSchedule.empty());

        IllegalStateException error = assertThrows(
                IllegalStateException.class, port::verifyRunComplete);

        assertTrue(error.getMessage().contains(
                        "unexpected pending hardware submissions at final run"),
                error::getMessage);
        assertFalse(harness.service.isReady(harness.handle),
                "an aborted close releases nothing");
    }

    @Test
    void leftoverSubmissionAtCloseIsReportedExactlyOnceAndReleasesNothing() {
        ReplayHarness harness = harness(false, 0, 41);
        HardwareTimingReplayPort port = port(
                harness.authority, HardwareTimingSchedule.empty());
        port.reportPendingRecordedSubmissionsAtClose();

        port.verifyRunComplete();

        assertFalse(harness.service.isReady(harness.handle),
                "a reported leftover submission is never admitted or released");
        List<String> reported = port.drainPendingRecordedSubmissions();
        assertEquals(1, reported.size(), reported::toString);
        assertTrue(reported.get(0).contains(describe(harness.handle)),
                () -> reported.get(0));
        assertTrue(port.drainPendingRecordedSubmissions().isEmpty(),
                "draining reports a leftover submission exactly once");
    }

    @Test
    void undrainedLeftoverSubmissionStillFailsTheNextInstall() {
        ReplayHarness harness = harness(false, 0, 42);
        HardwareTimingReplayPort port = port(
                harness.authority, HardwareTimingSchedule.empty());
        port.reportPendingRecordedSubmissionsAtClose();
        port.verifyRunComplete();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> port.install(HardwareTimingSchedule.empty()));

        assertTrue(error.getMessage().contains(
                        "pending recorded hardware submissions were never reported"),
                error::getMessage);
    }

    @Test
    void v5ScheduleRejectsDirectEdgesOutsidePreMainLoop() {
        for (HardwareServiceBoundary boundary : List.of(VINT_SERVICE, POST_OBJECTS)) {
            HardwareCompletionEdge edge = new HardwareCompletionEdge(
                    0, boundary, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 0,
                    fingerprint('a'));

            assertThrows(IllegalArgumentException.class,
                    () -> new HardwareTimingSchedule(List.of(edge)));
        }
    }

    @Test
    void v5AdmitsModulePostEdgeBeforeDirectLoopTailEdge() {
        HardwareTimingService service = new HardwareTimingService(
                com.openggf.game.timing.RomWorkBudgetScheduler.oneWorkUnitAt(POST_OBJECTS));
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkSubmission directSubmission = submission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, false, 1, 70);
        HardwareWorkSubmission moduleSubmission = submission(
                HardwareWorkKind.KOS_MODULE_QUEUE, false, 1, 71);
        HardwareCompletionEdge directEdge = new HardwareCompletionEdge(
                0, PRE_MAIN_LOOP, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 0,
                HardwareSubmissionFingerprint.compute(directSubmission));
        HardwareCompletionEdge moduleEdge = new HardwareCompletionEdge(
                0, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                HardwareSubmissionFingerprint.compute(moduleSubmission));
        HardwareTimingReplayPort port = port(authority,
                new HardwareTimingSchedule(List.of(moduleEdge, directEdge)));
        HardwareWorkHandle direct = service.submit(directSubmission);
        HardwareWorkHandle module = service.submit(moduleSubmission);

        port.beginRawFrame(0);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);

        assertFalse(service.isReady(direct));
        assertTrue(service.isReady(module));

        service.service(PRE_MAIN_LOOP);
        port.apply(PRE_MAIN_LOOP);

        assertTrue(service.isReady(direct));
    }

    @Test
    void standaloneLaterFirstSchedulesSeedAizAndHczOrdinals() {
        for (long firstOrdinal : List.of(2L, 43L)) {
            HardwareTimingService service = new HardwareTimingService();
            RecordedCompletionAuthority authority =
                    service.beginRecordedAdmission();
            HardwareWorkSubmission submission =
                    submission(false, 0, Math.toIntExact(firstOrdinal));
            HardwareCompletionEdge edge = new HardwareCompletionEdge(
                    17,
                    POST_OBJECTS,
                    submission.kind(),
                    firstOrdinal,
                    HardwareSubmissionFingerprint.compute(submission));
            HardwareTimingReplayPort port = port(authority, edge);

            HardwareWorkHandle handle = service.submit(submission);
            assertEquals(firstOrdinal, handle.ordinal());
            port.beginRawFrame(17);
            service.service(POST_OBJECTS);
            port.apply(POST_OBJECTS);

            assertTrue(service.isReady(handle));
        }
    }

    @Test
    void chainedScheduleKeepsGlobalOrdinalSequence() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkSubmission firstSubmission = submission(false, 0, 31);
        HardwareCompletionEdge firstEdge = new HardwareCompletionEdge(
                20,
                POST_OBJECTS,
                firstSubmission.kind(),
                2,
                HardwareSubmissionFingerprint.compute(firstSubmission));
        HardwareTimingReplayPort port = port(authority, firstEdge);
        HardwareWorkHandle first = service.submit(firstSubmission);

        port.beginRawFrame(20);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);
        service.claim(first);

        HardwareWorkSubmission nextSubmission = submission(false, 0, 32);
        HardwareCompletionEdge nextEdge = new HardwareCompletionEdge(
                21,
                POST_OBJECTS,
                nextSubmission.kind(),
                3,
                HardwareSubmissionFingerprint.compute(nextSubmission));
        port.handoffTo(new HardwareTimingSchedule(List.of(nextEdge)));
        HardwareWorkHandle next = service.submit(nextSubmission);

        assertEquals(2, first.ordinal());
        assertEquals(3, next.ordinal(),
                "a run-chain handoff must continue the production ledger");
    }

    @Test
    void emptyInitialSegmentDoesNotSeedAFirstOrdinalAtHandoff() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(HardwareTimingSchedule.empty());
        HardwareWorkSubmission submission = submission(false, 0, 44);
        HardwareCompletionEdge laterEdge = new HardwareCompletionEdge(
                44,
                POST_OBJECTS,
                submission.kind(),
                4,
                HardwareSubmissionFingerprint.compute(submission));

        port.handoffTo(new HardwareTimingSchedule(List.of(laterEdge)));
        HardwareWorkHandle production = service.submit(submission);

        assertEquals(0, production.ordinal(),
                "a later segment must not reconstruct missing run-start work");
        port.beginRawFrame(44);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);
        String mismatch = singleUnmatchedReport(port);
        assertFalse(service.isReady(production));
        assertTrue(mismatch.contains(
                "expected completion: KOS_MODULE_QUEUE#4"), () -> mismatch);
        assertTrue(mismatch.contains(
                "engine pending: KOS_MODULE_QUEUE#0"), () -> mismatch);
    }

    @Test
    void explicitRunBaseEstablishedAtInstallSurvivesEmptySegmentHandoff() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(
                HardwareTimingSchedule.empty(),
                Map.of(HardwareWorkKind.KOS_MODULE_QUEUE, 4L));
        HardwareWorkSubmission submission = submission(false, 0, 45);
        HardwareCompletionEdge laterEdge = new HardwareCompletionEdge(
                45,
                POST_OBJECTS,
                submission.kind(),
                4,
                HardwareSubmissionFingerprint.compute(submission));

        port.handoffTo(new HardwareTimingSchedule(List.of(laterEdge)));
        HardwareTimingSnapshot serviceBeforeSubmission = service.capture();
        HardwareTimingReplaySnapshot portBeforeSubmission = port.capture();
        HardwareWorkHandle production = service.submit(submission);
        port.beginRawFrame(45);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);

        assertEquals(4, production.ordinal());
        assertTrue(service.isReady(production));

        service.restore(serviceBeforeSubmission);
        port.restore(portBeforeSubmission);
        HardwareWorkHandle replayed = service.submit(submission);
        port.beginRawFrame(45);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);
        assertEquals(production, replayed);
        assertTrue(service.isReady(replayed));
    }

    @Test
    void rewindRestoresStandaloneBaseAndReplayProgress() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkSubmission submission = submission(false, 0, 43);
        HardwareCompletionEdge edge = new HardwareCompletionEdge(
                30,
                POST_OBJECTS,
                submission.kind(),
                43,
                HardwareSubmissionFingerprint.compute(submission));
        HardwareTimingReplayPort port = port(authority, edge);
        HardwareTimingSnapshot serviceAtBase = service.capture();
        HardwareTimingReplaySnapshot portAtBase = port.capture();

        HardwareWorkHandle first = service.submit(submission);
        port.beginRawFrame(30);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);
        assertTrue(service.isReady(first));

        service.restore(serviceAtBase);
        port.restore(portAtBase);
        HardwareWorkHandle replayed = service.submit(submission);
        port.beginRawFrame(30);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);

        assertEquals(first, replayed);
        assertTrue(service.isReady(replayed));
        assertEquals(1, port.capture().consumedIdentities().size());
    }

    @Test
    void noncontiguousOrdinalsWithinOneKindAreRejected() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingSchedule schedule = new HardwareTimingSchedule(List.of(
                new HardwareCompletionEdge(
                        1, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE,
                        2, fingerprint('a')),
                new HardwareCompletionEdge(
                        2, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE,
                        4, fingerprint('b'))));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new HardwareTimingReplayPort(authority).install(schedule));

        assertTrue(error.getMessage().contains("noncontiguous"),
                error::getMessage);
        assertTrue(error.getMessage().contains("expected ordinal 3"),
                error::getMessage);
    }

    @Test
    void nativeUnexportedWorkMayAdvanceOrdinalBetweenSegmentSchedules() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkSubmission represented = submission(false, 0, 35);
        HardwareCompletionEdge first = new HardwareCompletionEdge(
                1,
                POST_OBJECTS,
                represented.kind(),
                2,
                HardwareSubmissionFingerprint.compute(represented));
        HardwareTimingReplayPort port = port(authority, first);
        HardwareWorkHandle firstHandle = service.submit(represented);
        port.beginRawFrame(1);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);
        service.claim(firstHandle);

        // Structural phase work advances the same production ledger but is
        // intentionally absent from the completion stream. Drive its real
        // submissions through preparation/admission/claim without adding
        // replay-port edges, mirroring the recorder's null-writer phase.
        completeUnexportedPhaseJob(
                service, authority, submission(false, 0, 36));
        completeUnexportedPhaseJob(
                service, authority, submission(false, 0, 37));

        HardwareWorkSubmission later = submission(false, 0, 38);
        HardwareCompletionEdge laterEdge = new HardwareCompletionEdge(
                2,
                POST_OBJECTS,
                later.kind(),
                5,
                HardwareSubmissionFingerprint.compute(later));
        port.handoffTo(new HardwareTimingSchedule(List.of(laterEdge)));
        HardwareWorkHandle laterHandle = service.submit(later);
        port.beginRawFrame(2);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);

        assertEquals(5, laterHandle.ordinal());
        assertTrue(service.isReady(laterHandle));
    }

    @Test
    void ordinalGapAcrossHandoffWithoutNativeSubmissionsFailsAtAdmission() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkSubmission represented = submission(false, 0, 39);
        HardwareCompletionEdge first = new HardwareCompletionEdge(
                1,
                POST_OBJECTS,
                represented.kind(),
                2,
                HardwareSubmissionFingerprint.compute(represented));
        HardwareTimingReplayPort port = port(authority, first);
        HardwareWorkHandle firstHandle = service.submit(represented);
        port.beginRawFrame(1);
        service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);
        service.claim(firstHandle);

        HardwareWorkSubmission later = submission(false, 0, 40);
        HardwareCompletionEdge laterEdge = new HardwareCompletionEdge(
                2,
                POST_OBJECTS,
                later.kind(),
                5,
                HardwareSubmissionFingerprint.compute(later));
        port.handoffTo(new HardwareTimingSchedule(List.of(laterEdge)));
        HardwareWorkHandle engine = service.submit(later);
        port.beginRawFrame(2);
        service.service(POST_OBJECTS);

        assertEquals(3, engine.ordinal());
        port.apply(POST_OBJECTS);
        String mismatch = singleUnmatchedReport(port);
        assertFalse(service.isReady(engine));
        assertTrue(mismatch.contains(
                "expected completion: KOS_MODULE_QUEUE#5"), () -> mismatch);
        assertTrue(mismatch.contains(
                "engine pending: KOS_MODULE_QUEUE#3"), () -> mismatch);
    }

    @Test
    void matchingPreparedHeadReleasesAtBoundary() {
        ReplayHarness harness = harness(false, 1, 11);
        harness.service.service(POST_OBJECTS);
        HardwareTimingReplayPort port = port(
                harness.authority, edge(17, POST_OBJECTS, harness.handle));

        port.beginRawFrame(17);
        port.apply(POST_OBJECTS);

        assertTrue(harness.service.isReady(harness.handle));
    }

    @Test
    void suppressedRowCompletionReleasesOnlyCurrentPreMainLoopHead() {
        ReplayHarness harness = harness(false, 1, 49);
        harness.service.service(POST_OBJECTS);
        HardwareTimingReplayPort port = port(
                harness.authority, edge(18, PRE_MAIN_LOOP, harness.handle));
        port.beginRawFrame(18);
        harness.service.service(VINT_SERVICE);
        port.apply(VINT_SERVICE);

        port.applySuppressedRowCompletion();

        assertTrue(harness.service.isReady(harness.handle));
        assertEquals(1, port.capture().consumedIdentities().size());
    }

    @Test
    void suppressedRowCompletionWithoutScheduledEdgeIsNoOp() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(
                authority, HardwareTimingSchedule.empty());
        port.beginRawFrame(18);
        service.service(VINT_SERVICE);
        port.apply(VINT_SERVICE);

        port.applySuppressedRowCompletion();

        assertEquals(0, port.capture().edgeCursor());
        assertTrue(port.capture().consumedIdentities().isEmpty());
    }

    @Test
    void suppressedRowCompletionFailsClosedForMissingUnpreparedAndMismatchedHeads() {
        HardwareTimingService missingService = new HardwareTimingService();
        RecordedCompletionAuthority missingAuthority =
                missingService.beginRecordedAdmission();
        HardwareCompletionEdge missingEdge = new HardwareCompletionEdge(
                19, PRE_MAIN_LOOP, HardwareWorkKind.KOS_MODULE_QUEUE,
                0, fingerprint('1'));
        HardwareTimingReplayPort missingPort = port(missingAuthority, missingEdge);
        missingPort.beginRawFrame(19);
        missingService.service(VINT_SERVICE);
        missingPort.apply(VINT_SERVICE);
        missingPort.applySuppressedRowCompletion();
        assertTrue(singleUnmatchedReport(missingPort).contains(
                "engine pending"));

        ReplayHarness unprepared = harness(false, 1, 50);
        HardwareTimingReplayPort unpreparedPort = port(
                unprepared.authority, edge(20, PRE_MAIN_LOOP, unprepared.handle));
        unpreparedPort.beginRawFrame(20);
        unprepared.service.service(VINT_SERVICE);
        unpreparedPort.apply(VINT_SERVICE);
        unpreparedPort.applySuppressedRowCompletion();
        String unpreparedReport = singleUnmatchedReport(unpreparedPort);
        assertFalse(unprepared.service.isReady(unprepared.handle));
        assertTrue(unpreparedReport.contains("not prepared"),
                () -> unpreparedReport);

        ReplayHarness mismatched = harness(false, 1, 51);
        mismatched.service.service(POST_OBJECTS);
        HardwareCompletionEdge wrongFingerprint = new HardwareCompletionEdge(
                21, PRE_MAIN_LOOP, mismatched.handle.kind(),
                mismatched.handle.ordinal(), fingerprint('2'));
        HardwareTimingReplayPort mismatchPort = port(
                mismatched.authority, wrongFingerprint);
        mismatchPort.beginRawFrame(21);
        mismatched.service.service(VINT_SERVICE);
        mismatchPort.apply(VINT_SERVICE);
        mismatchPort.applySuppressedRowCompletion();
        String mismatchReport = singleUnmatchedReport(mismatchPort);
        assertFalse(mismatched.service.isReady(mismatched.handle));
        assertExpectedAndEngineIdentities(
                mismatchReport, wrongFingerprint, mismatched.handle);

        ReplayHarness wrongOrdinalHarness = harness(false, 1, 56);
        wrongOrdinalHarness.service.service(POST_OBJECTS);
        HardwareCompletionEdge wrongOrdinal = new HardwareCompletionEdge(
                21, PRE_MAIN_LOOP, wrongOrdinalHarness.handle.kind(),
                wrongOrdinalHarness.handle.ordinal() + 1,
                wrongOrdinalHarness.handle.submissionFingerprint());
        HardwareTimingReplayPort wrongOrdinalPort = port(
                wrongOrdinalHarness.authority, wrongOrdinal);
        wrongOrdinalPort.beginRawFrame(21);
        wrongOrdinalHarness.service.service(VINT_SERVICE);
        wrongOrdinalPort.apply(VINT_SERVICE);
        wrongOrdinalPort.applySuppressedRowCompletion();
        String ordinalReport = singleUnmatchedReport(wrongOrdinalPort);
        assertFalse(wrongOrdinalHarness.service.isReady(
                wrongOrdinalHarness.handle));
        assertExpectedAndEngineIdentities(
                ordinalReport, wrongOrdinal, wrongOrdinalHarness.handle);

        HardwareTimingService wrongKindService = new HardwareTimingService();
        RecordedCompletionAuthority wrongKindAuthority =
                wrongKindService.beginRecordedAdmission();
        wrongKindAuthority.configureAdmissionPolicies(Map.of(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED,
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED,
                HardwareWorkKind.NEMESIS_PLC_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED));
        HardwareWorkHandle moduleHandle = wrongKindService.submit(submission(
                HardwareWorkKind.KOS_MODULE_QUEUE, false, 1, 58));
        wrongKindService.service(POST_OBJECTS);
        HardwareCompletionEdge wrongKind = new HardwareCompletionEdge(
                22,
                PRE_MAIN_LOOP,
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                moduleHandle.ordinal(),
                moduleHandle.submissionFingerprint());
        HardwareTimingReplayPort wrongKindPort = port(
                wrongKindAuthority,
                new HardwareTimingSchedule(List.of(wrongKind)));
        wrongKindPort.beginRawFrame(22);
        wrongKindService.service(VINT_SERVICE);
        wrongKindPort.apply(VINT_SERVICE);

        wrongKindPort.applySuppressedRowCompletion();

        String kindReport = singleUnmatchedReport(wrongKindPort);
        assertTrue(kindReport.contains(
                "expected completion: " + wrongKind.kind() + "#"
                        + wrongKind.ordinal()), () -> kindReport);
        assertTrue(kindReport.contains(describe(moduleHandle)), () -> kindReport);
        assertFalse(wrongKindService.isReady(moduleHandle));
        // The dropped edge is consumed exactly once and released nothing.
        assertEquals(1, wrongKindPort.capture().edgeCursor());
        assertEquals(1, wrongKindPort.capture().consumedIdentities().size());
    }

    @Test
    void suppressedRowCompletionRejectsWrongBoundaryStaleRowAndGap() {
        ReplayHarness noCurrentService = harness(false, 1, 57);
        noCurrentService.service.service(POST_OBJECTS);
        HardwareTimingReplayPort noCurrentServicePort = port(
                noCurrentService.authority,
                edge(22, PRE_MAIN_LOOP, noCurrentService.handle));
        noCurrentServicePort.beginRawFrame(22);
        assertThrows(IllegalStateException.class,
                noCurrentServicePort::applySuppressedRowCompletion);

        ReplayHarness wrongBoundary = harness(false, 1, 52);
        wrongBoundary.service.service(POST_OBJECTS);
        HardwareTimingReplayPort wrongBoundaryPort = port(
                wrongBoundary.authority,
                edge(22, POST_OBJECTS, wrongBoundary.handle));
        wrongBoundaryPort.beginRawFrame(22);
        wrongBoundary.service.service(VINT_SERVICE);
        wrongBoundaryPort.apply(VINT_SERVICE);
        assertThrows(IllegalStateException.class,
                wrongBoundaryPort::applySuppressedRowCompletion);

        ReplayHarness stale = harness(false, 1, 53);
        stale.service.service(POST_OBJECTS);
        HardwareTimingReplayPort stalePort = port(
                stale.authority, edge(23, PRE_MAIN_LOOP, stale.handle));
        stalePort.beginRawFrame(23);
        stale.service.service(VINT_SERVICE);
        stalePort.apply(VINT_SERVICE);
        stalePort.beginRawFrame(24);
        assertFalse(stale.service.isReady(stale.handle),
                "a stale row must not release its recorded edge");
        assertEquals(1, stalePort.drainUnmatchedRecordedCompletions().size());

        ReplayHarness gap = harness(false, 1, 54);
        gap.service.service(POST_OBJECTS);
        HardwareTimingReplayPort gapPort = port(
                gap.authority, edge(24, PRE_MAIN_LOOP, gap.handle));
        gapPort.beginRawFrame(24);
        gapPort.enterUnrepresentedGap();
        gapPort.applySuppressedRowCompletion();
        assertFalse(gap.service.isReady(gap.handle));
        gapPort.verifySegmentEdges();
        assertEquals(1, gapPort.drainUnmatchedRecordedCompletions().size());
        assertFalse(gap.service.isReady(gap.handle));
    }

    @Test
    void rewindRestoresSuppressedRowCompletionForExactOnceReadmission() {
        ReplayHarness harness = harness(false, 1, 55);
        harness.service.service(POST_OBJECTS);
        HardwareTimingReplayPort port = port(
                harness.authority, edge(25, PRE_MAIN_LOOP, harness.handle));
        port.beginRawFrame(25);
        harness.service.service(VINT_SERVICE);
        port.apply(VINT_SERVICE);
        HardwareTimingSnapshot serviceBeforeEdge = harness.service.capture();
        HardwareTimingReplaySnapshot portBeforeEdge = port.capture();

        port.applySuppressedRowCompletion();
        assertTrue(harness.service.isReady(harness.handle));

        harness.service.restore(serviceBeforeEdge);
        port.restore(portBeforeEdge);
        port.applySuppressedRowCompletion();

        assertTrue(harness.service.isReady(harness.handle));
        assertEquals(1, port.capture().consumedIdentities().size());
    }

    @Test
    void earlyPreparedJobIsHeldUntilEdge() {
        ReplayHarness harness = harness(false, 1, 12);
        HardwareTimingReplayPort port = port(
                harness.authority, edge(23, POST_OBJECTS, harness.handle));

        harness.service.service(POST_OBJECTS);
        port.beginRawFrame(22);

        assertTrue(harness.service.isPending(harness.handle));
        assertFalse(harness.service.isReady(harness.handle));
    }

    @Test
    void unrepresentedGapDeactivatesThePreviousRawFrameLatch() {
        ReplayHarness harness = harness(false, 0, 12);
        HardwareTimingReplayPort port = port(
                harness.authority, edge(23, POST_OBJECTS, harness.handle));

        port.beginRawFrame(23);
        port.enterUnrepresentedGap();
        harness.service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);

        assertFalse(harness.service.isReady(harness.handle),
                "production may keep servicing during a BK2 gap, but the stale row "
                        + "must not release its recorded edge");
        assertEquals(null, port.capture().rawFrameLatch());
        port.verifySegmentEdges();
        assertEquals(1, port.drainUnmatchedRecordedCompletions().size());
        assertFalse(harness.service.isReady(harness.handle));
    }

    @Test
    void edgeCannotPrepareAJob() {
        ReplayHarness harness = harness(false, 1, 13);
        HardwareTimingReplayPort port = port(
                harness.authority, edge(4, PRE_MAIN_LOOP, harness.handle));
        harness.service.service(PRE_MAIN_LOOP);
        port.beginRawFrame(4);

        port.apply(PRE_MAIN_LOOP);

        String report = singleUnmatchedReport(port);
        assertTrue(report.contains("not prepared"), () -> report);
        assertFalse(harness.service.isReady(harness.handle));
    }

    @Test
    void heldRowPostAuthorityCannotPrepareParentOrReachRuntimeArtCoordinator() {
        ReplayHarness harness = harness(false, 2, 131);
        HardwareTimingReplayPort port = port(
                harness.authority,
                edge(6351, POST_OBJECTS, harness.handle));
        RuntimeArtCoordinator coordinator = mock(RuntimeArtCoordinator.class);

        port.beginRawFrame(6351);
        harness.service.service(POST_OBJECTS);

        port.apply(POST_OBJECTS);

        String report = singleUnmatchedReport(port);
        assertTrue(report.contains("not prepared"), () -> report);
        assertTrue(harness.service.isPending(harness.handle));
        assertFalse(harness.service.isReady(harness.handle));
        verifyNoInteractions(coordinator);
    }

    @Test
    void wrongKindOrdinalFingerprintOrBoundaryFails() {
        ReplayHarness ordinalHarness = harness(false, 1, 14);
        ordinalHarness.service.service(POST_OBJECTS);
        HardwareCompletionEdge wrongOrdinal = new HardwareCompletionEdge(
                8,
                POST_OBJECTS,
                ordinalHarness.handle.kind(),
                ordinalHarness.handle.ordinal() + 1,
                ordinalHarness.handle.submissionFingerprint());
        String ordinalError =
                applyUnmatched(ordinalHarness, wrongOrdinal, POST_OBJECTS);
        assertExpectedAndEngineIdentities(
                ordinalError, wrongOrdinal, ordinalHarness.handle);

        ReplayHarness fingerprintHarness = harness(false, 1, 15);
        fingerprintHarness.service.service(POST_OBJECTS);
        HardwareCompletionEdge wrongFingerprint = new HardwareCompletionEdge(
                9,
                POST_OBJECTS,
                fingerprintHarness.handle.kind(),
                fingerprintHarness.handle.ordinal(),
                fingerprint('f'));
        String fingerprintError =
                applyUnmatched(fingerprintHarness, wrongFingerprint, POST_OBJECTS);
        assertExpectedAndEngineIdentities(
                fingerprintError, wrongFingerprint, fingerprintHarness.handle);

        ReplayHarness boundaryHarness = harness(false, 0, 16);
        boundaryHarness.service.service(VINT_SERVICE);
        HardwareCompletionEdge wrongBoundary =
                edge(10, POST_OBJECTS, boundaryHarness.handle);
        HardwareTimingReplayPort boundaryPort =
                port(boundaryHarness.authority, wrongBoundary);
        boundaryPort.beginRawFrame(wrongBoundary.rawFrame());
        // A boundary the production loop did not service is still a contract
        // violation, not an unmatched completion: it keeps aborting.
        IllegalStateException boundaryError = assertThrows(
                IllegalStateException.class,
                () -> boundaryPort.apply(POST_OBJECTS));
        assertTrue(boundaryError.getMessage().contains(
                "expected POST_OBJECTS, production serviced VINT_SERVICE"),
                boundaryError::getMessage);
    }

    @Test
    void duplicateAndReorderedEdgesFail() {
        ReplayHarness harness = harness(false, 0, 17);
        HardwareCompletionEdge first = edge(2, PRE_MAIN_LOOP, harness.handle);
        HardwareCompletionEdge duplicate = edge(3, POST_OBJECTS, harness.handle);

        IllegalArgumentException duplicateError = assertThrows(
                IllegalArgumentException.class,
                () -> new HardwareTimingReplayPort(harness.authority)
                        .install(new HardwareTimingSchedule(List.of(first, duplicate))));
        assertTrue(duplicateError.getMessage().contains("duplicate"),
                duplicateError::getMessage);
        assertTrue(duplicateError.getMessage().contains(describe(harness.handle)),
                duplicateError::getMessage);

        HardwareCompletionEdge later = new HardwareCompletionEdge(
                5, VINT_SERVICE, harness.handle.kind(), 1, fingerprint('a'));
        HardwareCompletionEdge earlier = new HardwareCompletionEdge(
                4, POST_OBJECTS, harness.handle.kind(), 2, fingerprint('b'));
        IllegalArgumentException reorderedError = assertThrows(
                IllegalArgumentException.class,
                () -> new HardwareTimingReplayPort(harness.authority)
                        .install(new HardwareTimingSchedule(List.of(later, earlier))));
        assertTrue(reorderedError.getMessage().contains("canonical order"),
                reorderedError::getMessage);
        assertTrue(reorderedError.getMessage().contains("raw_frame=4"),
                reorderedError::getMessage);
    }

    @Test
    void nonExportablePendingSubmissionFailsAtSegmentEnd() {
        ReplayHarness harness = harness(false, 1, 18);
        harness.service.service(POST_OBJECTS);
        HardwareTimingReplayPort port =
                port(harness.authority, new HardwareTimingSchedule(List.of()));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> port.handoffTo(HardwareTimingSchedule.empty()));

        assertTrue(error.getMessage().contains("non-exportable"),
                error::getMessage);
        assertTrue(error.getMessage().contains(describe(harness.handle)),
                error::getMessage);
    }

    @Test
    void exportablePendingSubmissionRequiresMatchingNextSegmentEdge() {
        ReplayHarness harness = harness(true, 1, 19);
        harness.service.service(POST_OBJECTS);
        HardwareTimingReplayPort port =
                port(harness.authority, HardwareTimingSchedule.empty());
        HardwareCompletionEdge mismatch = new HardwareCompletionEdge(
                0,
                POST_OBJECTS,
                harness.handle.kind(),
                harness.handle.ordinal(),
                fingerprint('c'));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> port.handoffTo(new HardwareTimingSchedule(List.of(mismatch))));

        assertTrue(error.getMessage().contains("matching next-segment edge"),
                error::getMessage);
        assertTrue(error.getMessage().contains(describe(harness.handle)),
                error::getMessage);
        assertTrue(error.getMessage().contains(mismatch.submissionFingerprint()),
                error::getMessage);
    }

    @Test
    void validExportPreservesOrdinalAndPreparationAcrossHandoff() {
        ReplayHarness harness = harness(true, 1, 20);
        harness.service.service(POST_OBJECTS);
        HardwareTimingReplayPort port =
                port(harness.authority, HardwareTimingSchedule.empty());
        HardwareTimingSchedule next = new HardwareTimingSchedule(
                List.of(edge(0, POST_OBJECTS, harness.handle)));

        port.handoffTo(next);
        port.beginRawFrame(0);
        harness.service.service(POST_OBJECTS);
        port.apply(POST_OBJECTS);

        assertTrue(harness.service.isReady(harness.handle));
        assertArrayEquals(new byte[] {20}, harness.service.claim(harness.handle));
        HardwareWorkHandle nextHandle =
                harness.service.submit(submission(false, 1, 21));
        assertEquals(harness.handle.ordinal() + 1, nextHandle.ordinal());
    }

    /**
     * The demotion: an edge with no engine submission behind it is engine
     * inaccuracy the comparator reports, not a contract breach that aborts.
     */
    @Test
    void unconsumedEdgeIsReportedAtSegmentEnd() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                6, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                fingerprint('c')));

        port.handoffTo(HardwareTimingSchedule.empty());

        List<String> reported = port.drainUnmatchedRecordedCompletions();
        assertEquals(1, reported.size(), reported::toString);
        String only = reported.get(0);
        assertTrue(only.contains("unconsumed hardware completion edge"), () -> only);
        assertTrue(only.contains("at segment end"), () -> only);
        assertTrue(only.contains("raw_frame=6"), () -> only);
        assertTrue(only.contains("boundary=" + POST_OBJECTS), () -> only);
        assertTrue(only.contains("KOS_MODULE_QUEUE#0"), () -> only);
        assertTrue(only.contains(fingerprint('c')), () -> only);
    }

    /**
     * The dropped-edge report must describe what production actually holds,
     * never assert that no matching work was submitted. The previous wording
     * did assert exactly that, and it was measured false on the S1
     * complete-emeralds run: the engine submits the recorded ordinals and
     * fingerprints and the edges go unconsumed anyway, so the message routed
     * the reader into the wrong subsystem.
     */
    @Test
    void unconsumedEdgeReportNamesAdmissionWhenProductionHoldsTheWork() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareWorkHandle held = service.submit(submission(false, 1, 20));
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                6, POST_OBJECTS, held.kind(), held.ordinal(),
                held.submissionFingerprint()));

        port.verifySegmentEdges();

        String only = port.drainUnmatchedRecordedCompletions().get(0);
        assertTrue(only.contains("production holds a matching unclaimed submission"),
                () -> only);
        assertTrue(only.contains("admission failure rather than missing work"),
                () -> only);
        assertFalse(only.contains("submitted no matching work"), () -> only);
    }

    /**
     * With nothing pending the report states only what is observable, and
     * explicitly declines to distinguish "never submitted" from "already
     * admitted and claimed" -- the port cannot tell those apart.
     */
    @Test
    void unconsumedEdgeReportDoesNotClaimWorkWasNeverSubmitted() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                6, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                fingerprint('c')));

        port.verifySegmentEdges();

        String only = port.drainUnmatchedRecordedCompletions().get(0);
        assertTrue(only.contains("production holds no unclaimed submission"), () -> only);
        assertTrue(only.contains("already admitted and claimed"), () -> only);
        assertFalse(only.contains("submitted no matching work"), () -> only);
    }

    /** Reporting one of N would be a quieter kind of hiding. */
    @Test
    void everyUnconsumedEdgeIsReportedOnceNotJustTheFirst() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority,
                new HardwareCompletionEdge(6, POST_OBJECTS,
                        HardwareWorkKind.KOS_MODULE_QUEUE, 0, fingerprint('c')),
                new HardwareCompletionEdge(7, POST_OBJECTS,
                        HardwareWorkKind.KOS_MODULE_QUEUE, 1, fingerprint('d')),
                new HardwareCompletionEdge(8, POST_OBJECTS,
                        HardwareWorkKind.KOS_MODULE_QUEUE, 2, fingerprint('e')));

        port.handoffTo(HardwareTimingSchedule.empty());

        List<String> reported = port.drainUnmatchedRecordedCompletions();
        assertEquals(3, reported.size(), reported::toString);
        assertTrue(reported.get(0).contains("raw_frame=6"), reported::toString);
        assertTrue(reported.get(1).contains("raw_frame=7"), reported::toString);
        assertTrue(reported.get(2).contains("raw_frame=8"), reported::toString);
    }

    @Test
    void anEdgeTheRunWalkedPastIsReportedNotSwallowed() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                6, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                fingerprint('c')));

        port.beginRawFrame(9);

        List<String> reported = port.drainUnmatchedRecordedCompletions();
        assertEquals(1, reported.size(), reported::toString);
        assertTrue(reported.get(0).contains("before raw_frame=9"), reported::toString);
    }

    /** A driver with nowhere to record a dropped edge still fails the run. */
    @Test
    void aDroppedEdgeNeverDrainedStillFailsTheRun() {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        HardwareTimingReplayPort port = port(authority, new HardwareCompletionEdge(
                6, POST_OBJECTS, HardwareWorkKind.KOS_MODULE_QUEUE, 0,
                fingerprint('c')));

        port.beginRawFrame(9);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, port::verifyRunComplete);
        assertTrue(error.getMessage().contains("were never reported"),
                error::getMessage);
    }

    @Test
    void rewindRestoresEdgeCursorAndConsumedLedger() {
        ReplayHarness harness = harness(false, 1, 23);
        HardwareTimingReplayPort port = port(
                harness.authority, edge(31, POST_OBJECTS, harness.handle));
        harness.service.service(POST_OBJECTS);
        port.beginRawFrame(31);
        HardwareTimingSnapshot serviceBeforeEdge = harness.service.capture();
        HardwareTimingReplaySnapshot portBeforeEdge = port.capture();

        port.apply(POST_OBJECTS);
        assertTrue(harness.service.isReady(harness.handle));

        harness.service.restore(serviceBeforeEdge);
        port.restore(portBeforeEdge);
        port.apply(POST_OBJECTS);

        assertTrue(harness.service.isReady(harness.handle));
        assertArrayEquals(new byte[] {23}, harness.service.claim(harness.handle));
        assertEquals(1, port.capture().consumedIdentities().size());
    }

    /**
     * Drives a boundary whose recorded edge has no engine-pending, prepared
     * counterpart. The edge is dropped and reported rather than aborting the
     * comparison; the caller still asserts that nothing was released.
     */
    private static String applyUnmatched(
            ReplayHarness harness,
            HardwareCompletionEdge edge,
            HardwareServiceBoundary boundary) {
        HardwareTimingReplayPort port = port(harness.authority, edge);
        port.beginRawFrame(edge.rawFrame());
        port.apply(boundary);
        assertFalse(harness.service.isReady(harness.handle));
        return singleUnmatchedReport(port);
    }

    private static String singleUnmatchedReport(HardwareTimingReplayPort port) {
        List<String> reported = port.drainUnmatchedRecordedCompletions();
        assertEquals(1, reported.size(), reported::toString);
        assertTrue(port.drainUnmatchedRecordedCompletions().isEmpty(),
                "draining reports an unmatched edge exactly once");
        return reported.get(0);
    }

    private static void assertExpectedAndEngineIdentities(
            String report,
            HardwareCompletionEdge expected,
            HardwareWorkHandle engine) {
        assertTrue(report.contains(
                expected.kind() + "#" + expected.ordinal()
                        + " " + expected.submissionFingerprint()),
                () -> report);
        assertTrue(report.contains(describe(engine)), () -> report);
    }

    private static HardwareTimingReplayPort port(
            RecordedCompletionAuthority authority,
            HardwareCompletionEdge... edges) {
        return port(authority, new HardwareTimingSchedule(List.of(edges)));
    }

    private static HardwareTimingReplayPort port(
            RecordedCompletionAuthority authority,
            HardwareTimingSchedule schedule) {
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(schedule);
        return port;
    }

    private static ReplayHarness harness(
            boolean exportable, int workUnits, int payloadByte) {
        HardwareTimingService service = new HardwareTimingService();
        RecordedCompletionAuthority authority = service.beginRecordedAdmission();
        authority.configureAdmissionPolicies(
                new HardwareTimingSchedule(List.of()).admissionPolicies());
        HardwareWorkHandle handle =
                service.submit(submission(exportable, workUnits, payloadByte));
        return new ReplayHarness(service, authority, handle);
    }

    private static void completeUnexportedPhaseJob(
            HardwareTimingService service,
            RecordedCompletionAuthority authority,
            HardwareWorkSubmission submission) {
        HardwareWorkHandle handle = service.submit(submission);
        service.service(POST_OBJECTS);
        authority.admitRecordedCompletion(
                POST_OBJECTS,
                handle.kind(),
                handle.ordinal(),
                handle.submissionFingerprint());
        service.claim(handle);
    }

    private static HardwareCompletionEdge edge(
            int rawFrame,
            HardwareServiceBoundary boundary,
            HardwareWorkHandle handle) {
        return new HardwareCompletionEdge(
                rawFrame,
                boundary,
                handle.kind(),
                handle.ordinal(),
                handle.submissionFingerprint());
    }

    private static HardwareWorkSubmission submission(
            boolean exportable, int workUnits, int payloadByte) {
        return submission(HardwareWorkKind.KOS_MODULE_QUEUE, exportable, workUnits, payloadByte);
    }

    private static HardwareWorkSubmission submission(
            HardwareWorkKind kind, boolean exportable, int workUnits, int payloadByte) {
        return new HardwareWorkSubmission(
                kind,
                0x3000 + payloadByte,
                0x100,
                0x5000,
                1,
                "KosM",
                1,
                exportable,
                new TestPreparation(workUnits, new byte[] {(byte) payloadByte}));
    }

    private static String describe(HardwareWorkHandle handle) {
        return handle.kind() + "#" + handle.ordinal()
                + " " + handle.submissionFingerprint();
    }

    private static String fingerprint(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private record ReplayHarness(
            HardwareTimingService service,
            RecordedCompletionAuthority authority,
            HardwareWorkHandle handle) {
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
}
