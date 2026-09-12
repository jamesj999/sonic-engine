package com.openggf.sprites.playable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSidekickNormalStepDiagnosticRecorder {

    @Test
    void recordsScalarPreCpuCpuAndPostPhysicsObservations() {
        SidekickCpuController.NormalStepDiagnostics pre = SidekickNormalStepDiagnosticRecorder.begin(
                0x1234, SidekickCpuController.State.NORMAL, "entry",
                0x26, 0xC0, (short) 0x1111, (short) -2, (short) 3,
                (short) 0x4567, (short) 0x89AB, (byte) 0x3C);

        assertEquals(-1, pre.followDelayFrames());
        assertEquals(-1, pre.followHistorySlot());
        assertEquals(-1, pre.dx());
        assertEquals(-1, pre.dy());
        assertEquals(0x26, pre.postCpuStatus());
        assertEquals(0xC0, pre.postCpuObjectControl());
        assertFalse(pre.postPhysicsRecorded());

        SidekickCpuController.NormalStepDiagnostics cpu = SidekickNormalStepDiagnosticRecorder.recordCpuResult(
                pre, "follow_steering", 0x1F, 0x0A, 0x1234, 0x56, 0x78, -8, 9, 0x12, 0x10,
                0x22, 0x80, (short) 4, (short) 5, (short) 6,
                (short) 0x0102, (short) 0x0304, (byte) 0x05, -1, true, true);
        SidekickCpuController.NormalStepDiagnostics post = SidekickNormalStepDiagnosticRecorder.recordPostPhysics(
                cpu, 0x1234, 0x02, 0x40, (short) 7, (short) 8, (short) 9,
                (short) 0x0A0B, (short) 0x0C0D, (byte) 0x0E);

        assertEquals("entry", pre.followBranch(), "pre-CPU record remains immutable");
        assertEquals("follow_steering", post.followBranch());
        assertEquals(0x1F, post.followDelayFrames());
        assertEquals(0x0A, post.followHistorySlot());
        assertEquals(0x1234, post.recordedInput());
        assertEquals(0x12, post.generatedInput());
        assertTrue(post.inputJumpPress());
        assertTrue(post.skipFollowSteering());
        assertEquals((short) 0x0A0B, post.postPhysicsX());
        assertEquals((short) 0x0C0D, post.postPhysicsXSubpixel());
        assertEquals((byte) 0x0E, post.postPhysicsAngle());
        assertTrue(post.postPhysicsRecorded());
    }

    @Test
    void ignoresPostPhysicsSampleFromAnotherControllerFrame() {
        SidekickCpuController.NormalStepDiagnostics current = SidekickNormalStepDiagnosticRecorder.begin(
                17, SidekickCpuController.State.NORMAL, "entry",
                0, 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (byte) 0);

        assertSame(current, SidekickNormalStepDiagnosticRecorder.recordPostPhysics(
                current, 18, 1, 2, (short) 3, (short) 4, (short) 5,
                (short) 6, (short) 7, (byte) 8));
    }

    @Test
    void formatsExistingDiagnosticContractAndHoldsNoGameplayReferences() {
        SidekickCpuController.NormalStepDiagnostics diagnostic = SidekickNormalStepDiagnosticRecorder.begin(
                1, SidekickCpuController.State.NORMAL, "entry",
                2, 3, (short) 4, (short) 5, (short) 6, (short) 7, (short) 8, (byte) 9);

        assertEquals("eng-tails-cpu f=1 state=NORMAL branch=entry hist=-1/-1 in=0000 stat=00 push=00 "
                        + "pre=obj03 st02 x=0007.0008 xv0004 yv0005 gv0006 a=09 gen=0000 jp=false "
                        + "postCpu=obj03 st02 xv0004 yv0005 gv0006 x=0007.0008 a=09 nudge=0 "
                        + "postPhys=missing obj00 st00 xv0000 yv0000 gv0000 x=0000.0000 a=00 "
                        + "dx=FFFF dy=FFFF skip=false grace=3",
                SidekickNormalStepDiagnosticRecorder.format(diagnostic, 3));
        assertEquals("eng-tails-cpu none", SidekickNormalStepDiagnosticRecorder.format(null, 3));
        assertEquals(0, SidekickNormalStepDiagnosticRecorder.class.getDeclaredFields().length,
                "diagnostic recorder must not retain gameplay references or snapshot state");
    }
}
