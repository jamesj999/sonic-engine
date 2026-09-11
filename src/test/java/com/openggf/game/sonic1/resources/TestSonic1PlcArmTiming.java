package com.openggf.game.sonic1.resources;

import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.RecordedCompletionAuthority;
import com.openggf.game.timing.UnmatchedRecordedCompletionException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S1 {@code RunPLC} arm as hardware-timing work: live admission arms in
 * the boundary that prepared it, recorded admission arms only on a matching
 * recorded edge, and a mismatched edge is refused rather than absorbed.
 */
class TestSonic1PlcArmTiming {

    private static final int SOURCE = 0x2E9A0;
    private static final int DESTINATION_TILE = 0x6B4;
    private static final int PATTERNS = 24;

    private static NemesisPlcQueueSnapshot armableQueue() {
        return new NemesisPlcQueueSnapshot(null, List.of(
                new NemesisPlcQueueSnapshot.Entry(
                        SOURCE, DESTINATION_TILE, PATTERNS, PATTERNS)));
    }

    /**
     * The identity the recorder writes for this descriptor. The recorder
     * computes the same canonical encoding over
     * {@code (NEMESIS_PLC_QUEUE, source, 0, tile, patterns, "nemesis", 0)}
     * (tools/tracechaser/bizhawk-headless/src/Recording/S1PlcHardwareTimingObserver.cs),
     * so a fixture whose edge disagrees with the engine's submission fails the
     * replay rather than arming something else.
     */
    private static String submittedFingerprint(HardwareTimingService timing) {
        return timing.pendingHandles().get(0).submissionFingerprint();
    }

    @Test
    void liveAdmissionArmsInTheBoundaryThatPreparedTheSubmission() {
        HardwareTimingService timing = new HardwareTimingService();
        Sonic1PlcArmTiming arm = new Sonic1PlcArmTiming(timing);

        arm.submitArmableHead(armableQueue());
        assertFalse(arm.releaseArm(), "an unserviced arm is not yet visible");

        timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertTrue(arm.releaseArm());
        // The job is claimed, so the next loop tail submits afresh.
        assertTrue(arm.releaseArm());
    }

    @Test
    void anEmptyOrBusyQueueSubmitsNothingAndNeverHoldsTheArm() {
        HardwareTimingService timing = new HardwareTimingService();
        Sonic1PlcArmTiming arm = new Sonic1PlcArmTiming(timing);

        arm.submitArmableHead(new NemesisPlcQueueSnapshot(null, List.of()));
        arm.submitArmableHead(new NemesisPlcQueueSnapshot(
                new NemesisPlcQueueSnapshot.Entry(SOURCE, DESTINATION_TILE, PATTERNS, 3),
                List.of(new NemesisPlcQueueSnapshot.Entry(1, 2, 3, 3))));

        assertTrue(arm.releaseArm());
        assertEquals(List.of(), timing.pendingHandles());
    }

    @Test
    void recordedAdmissionHoldsTheArmUntilItsOwnEdgeArrives() {
        HardwareTimingService timing = new HardwareTimingService();
        RecordedCompletionAuthority authority = timing.beginRecordedAdmission();
        Sonic1PlcArmTiming arm = new Sonic1PlcArmTiming(timing);

        arm.submitArmableHead(armableQueue());
        timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertFalse(arm.releaseArm(), "no recorded edge has released this arm");

        // A later row's boundary alone still does not arm it.
        timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertFalse(arm.releaseArm());

        authority.admitRecordedCompletion(
                HardwareServiceBoundary.PRE_MAIN_LOOP,
                HardwareWorkKind.NEMESIS_PLC_QUEUE,
                0L,
                submittedFingerprint(timing));
        assertTrue(arm.releaseArm());
    }

    @Test
    void anEdgeForADifferentDescriptorIsRefusedRatherThanArmingTheHead() {
        HardwareTimingService timing = new HardwareTimingService();
        RecordedCompletionAuthority authority = timing.beginRecordedAdmission();
        Sonic1PlcArmTiming arm = new Sonic1PlcArmTiming(timing);

        arm.submitArmableHead(armableQueue());
        timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);

        assertThrows(UnmatchedRecordedCompletionException.class,
                () -> authority.admitRecordedCompletion(
                        HardwareServiceBoundary.PRE_MAIN_LOOP,
                        HardwareWorkKind.NEMESIS_PLC_QUEUE,
                        0L,
                        "sha256:" + "0".repeat(64)));
        assertFalse(arm.releaseArm());
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                timing.admissionPolicyFor(HardwareWorkKind.NEMESIS_PLC_QUEUE));
    }

    @Test
    void rewindRestoresTheOutstandingArmByIdentityWithoutResubmitting() {
        HardwareTimingService timing = new HardwareTimingService();
        Sonic1PlcArmTiming arm = new Sonic1PlcArmTiming(timing);

        arm.submitArmableHead(armableQueue());
        Sonic1PlcArmTiming.Snapshot outstanding = arm.capture();
        assertEquals(0L, outstanding.outstandingOrdinal());

        arm.resetForMissingSnapshot();
        assertTrue(arm.releaseArm(), "a cleared owner holds nothing");

        arm.restore(outstanding);
        assertFalse(arm.releaseArm(), "the original job is bound again");
        assertEquals(1, timing.pendingHandles().size());
    }

    /**
     * The recorder discards everything observed before a segment's first arm
     * (tools/tracechaser/bizhawk-headless/src/Recording/S1PlcHardwareTimingObserver.cs:80-83),
     * so an arm released in an unrepresented span is absent from the stream.
     * It must therefore not occupy a place in the shared numbering either: the
     * next represented arm has to carry the ordinal the recording gives it.
     */
    @Test
    void anArmReleasedInAnUnrepresentedSpanDoesNotConsumeARecordedOrdinal() {
        HardwareTimingService timing = new HardwareTimingService();
        RecordedCompletionAuthority authority = timing.beginRecordedAdmission();
        Sonic1PlcArmTiming arm = new Sonic1PlcArmTiming(timing);

        authority.setRecordedRowRepresentation(false);
        arm.submitArmableHead(armableQueue());
        timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertTrue(arm.releaseArm(), "the unrepresented span falls back to native readiness");
        assertEquals(List.of(), timing.pendingHandles());

        authority.setRecordedRowRepresentation(true);
        arm.submitArmableHead(armableQueue());
        timing.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertEquals(0L, timing.pendingHandles().get(0).ordinal(),
                "the first arm the recording describes must be its ordinal 0");

        authority.admitRecordedCompletion(
                HardwareServiceBoundary.PRE_MAIN_LOOP,
                HardwareWorkKind.NEMESIS_PLC_QUEUE,
                0L,
                submittedFingerprint(timing));
        assertTrue(arm.releaseArm());
    }
}
