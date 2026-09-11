package com.openggf.tests.trace;

import com.openggf.game.sonic2.slotmachine.CNZSlotMachineManager;
import com.openggf.trace.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestTraceBinder {

    @Test
    void cnzSlotMismatchesAreErrorsAfterTheFrontierIsGreen() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceEvent.CnzSlotMachineState expected = new TraceEvent.CnzSlotMachineState(
                7, 0x1234, false, 0x04, 1, 0, 0,
                java.util.List.of(0, 0, 0), java.util.List.of(8, 8, 8),
                java.util.List.of(0, 0, 0));
        CNZSlotMachineManager.Snapshot actual = new CNZSlotMachineManager.Snapshot(
                false, 0x08, 1, 0, 0,
                java.util.List.of(0, 0, 0), java.util.List.of(8, 8, 8),
                java.util.List.of(0, 0, 0));

        binder.compareCnzSlotMachine(7, expected, actual);

        DivergenceReport report = binder.buildReport();
        assertEquals(1, report.errors().size());
        assertEquals(0, report.warnings().size());
    }

    @Test
    void tornadoMismatchesRemainWarningsUntilThatCoverageBoundaryCloses() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceEvent.S2TornadoState expected = new TraceEvent.S2TornadoState(
                7, 3, 0x100, 0x200, 0, 0, 2, 0, 0, 0, 0, 0, 0);
        com.openggf.game.sonic2.objects.TornadoObjectInstance.Snapshot actual =
                new com.openggf.game.sonic2.objects.TornadoObjectInstance.Snapshot(
                        0x100, 0x200, 0, 0, 4, 0, 0, 0, 0, 0, 0);

        binder.compareS2Tornado(7, expected, actual);

        DivergenceReport report = binder.buildReport();
        assertEquals(0, report.errors().size());
        assertEquals(1, report.warnings().size());
    }

    @Test
    void playerAndSidekickAnimationMismatchesUseIndependentGate() {
        TraceCharacterState expectedSidekick = new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0, (short) 0x0010,
                (byte) 0, false, false, 0,
                0, 0, 0x02, 0, 0, 0x11, 0x42);
        TraceFrame frame = new TraceFrame(0, 0,
                (short) 0x0050, (short) 0x03B0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0,
                0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                0x05, 0x23, expectedSidekick);
        TraceCharacterState actualSidekick = new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0, (short) 0x0010,
                (byte) 0, false, false, 0,
                0, 0, 0x02, 0, 0, 0x12, 0x43);
        EngineDiagnostics diagnostics = EngineDiagnostics.formattedWithCameraAndAnimation(
                -1, -1, 0x06, 0x24, "");

        FrameComparison result = new TraceBinder(ToleranceConfig.DEFAULT).compareFrame(frame,
                frame.x(), frame.y(), frame.xSpeed(), frame.ySpeed(), frame.gSpeed(),
                frame.angle(), frame.air(), frame.rolling(), frame.groundMode(),
                null, diagnostics, "tails", actualSidekick);

        assertTrue(result.hasError(TraceVerificationScope.ANIMATION));
        assertFalse(result.hasError(TraceVerificationScope.PHYSICS));
        assertEquals(VerificationGroup.ANIMATION,
                result.fields().get("player_animation_id").verificationGroup());
        assertEquals(Severity.ERROR, result.fields().get("player_mapping_frame").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_animation_id").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_mapping_frame").severity());
    }

    @Test
    public void testExactMatchReturnsNoError() {
        TraceFrame frame = TraceFrame.of(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        assertFalse(result.hasDivergence());
        assertFalse(result.hasError());
    }

    @Test
    public void testDefaultPositionDivergenceIsError() {
        TraceFrame frame = TraceFrame.of(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0051, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        assertTrue(result.hasDivergence());
        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("x").severity());
    }

    @Test
    public void testPositionDivergenceError() {
        TraceFrame frame = TraceFrame.of(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0150, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("x").severity());
    }

    @Test
    public void testAirFlagMismatchIsError() {
        TraceFrame frame = TraceFrame.of(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, true, false, 0);

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("air").severity());
    }

    @Test
    public void testSpeedSignChangeIsError() {
        TraceFrame frame = TraceFrame.of(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0010, (short) 0x0000, (short) 0x0010,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) -0x0010, (short) 0x0000, (short) -0x0010,
            (byte) 0x00, false, false, 0);

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("x_speed").severity());
    }

    @Test
    void testPrimaryRoutineMismatchIsErrorWhenDiagnosticsCarryRoutine() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, -1, -1, -1, 0x00, -1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x04, -1, -1, -1, 0x00, -1, -1,
                -1, -1, -1, -1, "", -1, -1, -1, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("routine").severity());
    }

    @Test
    void testPrimaryStatusByteMismatchIsErrorWhenDiagnosticsCarryStatus() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, -1, -1, -1, 0x04, -1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, -1, 0x00, -1, -1,
                -1, -1, -1, -1, "", -1, -1, -1, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("status_byte").severity());
    }

    @Test
    public void testInputValidationMatch() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceFrame frame = TraceFrame.of(0, 0x0008,
            (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
            (byte) 0, false, false, 0);
        assertTrue(binder.validateInput(frame, 0x0008));
    }

    @Test
    public void testInputValidationMismatch() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceFrame frame = TraceFrame.of(0, 0x0008,
            (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
            (byte) 0, false, false, 0);
        assertFalse(binder.validateInput(frame, 0x0004));
    }

    @Test
    void testSidekickStateMismatchIsReported() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x00, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null,
            new TraceCharacterState(true,
                (short) 0x0142, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x00, 0x00));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("sidekick_x").severity());
    }

    @Test
    void testSidekickStatusByteMismatchIsReported() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x04, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x00, 0x00));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
    }

    @Test
    void testSidekickHurtOnObjectStatusOnlyMismatchIsIgnored() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, true, false, 0,
                0, 0, 0x04, 0x0B, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, true, false, 0,
                0, 0, 0x04, 0x03, 0x00));

        assertFalse(result.hasError());
        assertEquals(Severity.MATCH, result.fields().get("tails_status_byte").severity());
        assertTrue(result.fields().get("tails_status_byte").observedMismatch(),
                "Ignored push-only status mismatches should still be visible in trace context.");
    }

    @Test
    void testSidekickHurtOnObjectStatusMismatchStillReportsWithMotionDelta() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, true, false, 0,
                0, 0, 0x04, 0x0B, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0041, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, true, false, 0,
                0, 0, 0x04, 0x03, 0x00));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x").severity());
    }

    @Test
    void testGroundedSidekickOnObjectPushOnlyStatusMismatchIsIgnoredWhenMotionMatches() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x08E3, (short) 0x0674,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x0D00, 0xD900, 0x02, 0x29, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x08E3, (short) 0x0674,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x0D00, 0xD900, 0x02, 0x09, 0x00));

        assertFalse(result.hasError());
        assertEquals(Severity.MATCH, result.fields().get("tails_status_byte").severity());
    }

    @Test
    void testGroundedSidekickOnObjectPushStatusMismatchStillReportsWithMotionDelta() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x08E3, (short) 0x0674,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x0D00, 0xD900, 0x02, 0x29, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x08E3, (short) 0x0674,
                (short) 0x0018, (short) 0x0000, (short) 0x0018,
                (byte) 0x00, false, false, 0,
                0x0D00, 0xD900, 0x02, 0x09, 0x00));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x_speed").severity());
    }

    @Test
    void testStationaryReleasedSidekickPushOnlyStatusMismatchIsIgnored() {
        TraceFrame frame = new TraceFrame(3691, 0x0000,
            (short) 0x1914, (short) 0x036F,
            (short) -0x0100, (short) 0x0000, (short) -0x0100,
            (byte) 0x00, false, true, 0,
            0x7000, 0x1E00, 0x0D, 0x0D, 0x0E6C, 0x21, 0x3DD8, 0,
            0x1884, 0x030A, -1,
            new TraceCharacterState(true,
                (short) 0x158A, (short) 0x0470,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x9D00, 0xA800, 0x02, 0x20, 0x1E));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x1914, (short) 0x036F,
            (short) -0x0100, (short) 0x0000, (short) -0x0100,
            (byte) 0x00, false, true, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x158A, (short) 0x0470,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x9D00, 0xA800, 0x02, 0x00, -1));

        assertFalse(result.hasError());
        assertEquals(Severity.MATCH, result.fields().get("tails_status_byte").severity());
    }

    @Test
    void testStationaryReleasedSidekickPushStatusMismatchStillReportsWithMotionDelta() {
        TraceFrame frame = new TraceFrame(3691, 0x0000,
            (short) 0x1914, (short) 0x036F,
            (short) -0x0100, (short) 0x0000, (short) -0x0100,
            (byte) 0x00, false, true, 0,
            0x7000, 0x1E00, 0x0D, 0x0D, 0x0E6C, 0x21, 0x3DD8, 0,
            0x1884, 0x030A, -1,
            new TraceCharacterState(true,
                (short) 0x158A, (short) 0x0470,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x9D00, 0xA800, 0x02, 0x20, 0x1E));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x1914, (short) 0x036F,
            (short) -0x0100, (short) 0x0000, (short) -0x0100,
            (byte) 0x00, false, true, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x158A, (short) 0x0470,
                (short) 0x000C, (short) 0x0000, (short) 0x000C,
                (byte) 0x00, false, false, 0,
                0x9D00, 0xA800, 0x02, 0x00, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x_speed").severity());
    }

    @Test
    void testMovingGroundedSidekickPushOnlyStatusMismatchIsIgnoredWhenMotionMatches() {
        TraceCharacterState expectedTails = new TraceCharacterState(true,
                (short) 0x13CB, (short) 0x03F5,
                (short) 0xFFB8, (short) 0x0000, (short) 0xFFB8,
                (byte) 0x00, false, false, 0,
                0x6300, 0xFF00, 0x02, 0x21, 0x1E);
        TraceFrame frame = frameWithSidekick(4494, expectedTails);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                null, null, "tails",
                new TraceCharacterState(true,
                        (short) 0x13CB, (short) 0x03F5,
                        (short) 0xFFB8, (short) 0x0000, (short) 0xFFB8,
                        (byte) 0x00, false, false, 0,
                        0x6300, 0xFF00, 0x02, 0x01, 0x1E));

        assertFalse(result.hasError());
        assertEquals(Severity.MATCH, result.fields().get("tails_status_byte").severity());
    }

    @Test
    void testMovingGroundedSidekickPushStatusMismatchStillReportsWithMotionDelta() {
        TraceCharacterState expectedTails = new TraceCharacterState(true,
                (short) 0x13CB, (short) 0x03F5,
                (short) 0xFFB8, (short) 0x0000, (short) 0xFFB8,
                (byte) 0x00, false, false, 0,
                0x6300, 0xFF00, 0x02, 0x21, 0x1E);
        TraceFrame frame = frameWithSidekick(4494, expectedTails);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                null, null, "tails",
                new TraceCharacterState(true,
                        (short) 0x13CB, (short) 0x03F6,
                        (short) 0xFFB8, (short) 0x0000, (short) 0xFFB8,
                        (byte) 0x00, false, false, 0,
                        0x6300, 0xFF00, 0x02, 0x01, 0x1E));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_y").severity());
    }

    @Test
    void testInactiveSidekickDespawnMarkerFacingOnlyStatusMismatchIsIgnored() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0000, (short) 0x0000,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0xB500, 0x1E00, 0x02, 0x02, 0x07));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0000, (short) 0x0000,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0xB500, 0x1E00, 0x02, 0x03, -1));

        assertFalse(result.hasError());
        assertEquals(Severity.MATCH, result.fields().get("tails_status_byte").severity());
    }

    @Test
    void testInactiveSidekickDespawnMarkerFacingMismatchStillReportsWithMotionDelta() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0000, (short) 0x0000,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0xB500, 0x1E00, 0x02, 0x02, 0x07));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0001, (short) 0x0000,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0xB500, 0x1E00, 0x02, 0x03, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x").severity());
    }

    @Test
    void testStationarySidekickOnObjectFacingOnlyStatusMismatchIsIgnored() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0753, (short) 0x02EC,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x6A00, 0x0B00, 0x02, 0x08, 0x27));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0753, (short) 0x02EC,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x6A00, 0x0B00, 0x02, 0x09, 0x22));

        assertFalse(result.hasError());
        assertEquals(Severity.MATCH, result.fields().get("tails_status_byte").severity());
    }

    @Test
    void testStationarySidekickOnObjectFacingMismatchStillReportsWithMotionDelta() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0753, (short) 0x02EC,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0x6A00, 0x0B00, 0x02, 0x08, 0x27));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0753, (short) 0x02EC,
                (short) 0x000C, (short) 0x0000, (short) 0x000C,
                (byte) 0x00, false, false, 0,
                0x6A00, 0x0B00, 0x02, 0x09, 0x22));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x_speed").severity());
    }

    @Test
    void testAirborneSidekickZeroHorizontalSpeedFacingOnlyStatusMismatchIsIgnored() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x13B0, (short) 0x03ED,
            (short) 0x00F0, (short) -0x0530, (short) 0x0000,
            (byte) 0x00, true, true, 0,
            0x4000, 0x5300, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x13A0, (short) 0x0410,
                (short) 0x0000, (short) 0x0038, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0x8100, 0x9700, 0x02, 0x03, -1));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x13B0, (short) 0x03ED,
            (short) 0x00F0, (short) -0x0530, (short) 0x0000,
            (byte) 0x00, true, true, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x13A0, (short) 0x0410,
                (short) 0x0000, (short) 0x0038, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0x8100, 0x9700, 0x02, 0x02, -1));

        assertFalse(result.hasError());
        assertEquals(Severity.MATCH, result.fields().get("tails_status_byte").severity());
    }

    @Test
    void testAirborneSidekickFacingMismatchStillReportsWithHorizontalMotionDelta() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x13B0, (short) 0x03ED,
            (short) 0x00F0, (short) -0x0530, (short) 0x0000,
            (byte) 0x00, true, true, 0,
            0x4000, 0x5300, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x13A0, (short) 0x0410,
                (short) 0x0000, (short) 0x0038, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0x8100, 0x9700, 0x02, 0x03, -1));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x13B0, (short) 0x03ED,
            (short) 0x00F0, (short) -0x0530, (short) 0x0000,
            (byte) 0x00, true, true, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x13A0, (short) 0x0410,
                (short) 0x0018, (short) 0x0038, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0x8100, 0x9700, 0x02, 0x02, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_status_byte").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x_speed").severity());
    }

    @Test
    void testSidekickRoutineMismatchIsReported() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x00, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x04, 0x00, 0x00));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_routine").severity());
    }

    @Test
    void testNamedCharacterLabelIsUsedForSecondaryComparisons() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            new TraceCharacterState(true,
                (short) 0x0040, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x00, 0x00));

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails",
            new TraceCharacterState(true,
                (short) 0x0142, (short) 0x03A0,
                (short) 0x0010, (short) 0x0000, (short) 0x0010,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x00, 0x00));

        assertTrue(result.hasError());
        assertTrue(result.fields().containsKey("tails_x"));
        assertEquals(Severity.ERROR, result.fields().get("tails_x").severity());
    }

    @Test
    void testSidekickCpuStateMismatchIsReportedBeforePositionFields() {
        TraceFrame frame = TraceFrame.of(3906, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                3906, "tails", 0x11, 7, 299, 0x06,
                (short) 0x0613, (short) 0x0264, 0,
                1, 0x08, 0x10, 0, 0x4000,
                0x44, 0x00, (short) 0x0500, (short) 0x0200,
                0x0800, 0x08, 0x02, 0x11, 0x000C);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                7, 299, 0x11, 0x08, 0x0613, 0x0264,
                0x08, 0x10, 0x00, 1);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, expectedCpu, actualCpu);

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_routine").severity());
    }

    @Test
    void testInactiveSidekickCpuInteractMismatchDoesNotOwnFrontier() {
        TraceCharacterState tails = sidekickStateWithStatus(0x00);
        TraceFrame frame = frameWithSidekick(tails);
        TraceEvent.CpuState expectedCpu = cpuStateWithInteract(0x6E);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x00, 0x06, 0x0000, 0x0000,
                0x00, 0x00, 0x00, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                null, null, "tails", tails, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_interact").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testActiveSidekickCpuInteractMismatchStillReports() {
        TraceCharacterState tails = sidekickStateWithStatus(0x08);
        TraceFrame frame = frameWithSidekick(tails);
        TraceEvent.CpuState expectedCpu = cpuStateWithInteract(0x6E);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x00, 0x06, 0x0000, 0x0000,
                0x00, 0x00, 0x00, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                null, null, "tails", tails, expectedCpu, actualCpu);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_interact").severity());
        assertTrue(result.hasError());
    }

    @Test
    void testLandingFrameSidekickCpuInteractMirrorLagDoesNotOwnFrontier() {
        TraceCharacterState tails = sidekickStateWithStatus(0x08);
        TraceFrame frame = frameWithSidekick(tails);
        TraceEvent.CpuState expectedCpu = cpuStateWithInteractAndTailsInteract(0x00, 0x19);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x24, 0x06, 0x0000, 0x0000,
                0x00, 0x00, 0x00, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                null, null, "tails", tails, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_interact").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testLandingFrameSidekickCpuInteractMirrorLagStillReportsWithMotionDelta() {
        TraceCharacterState expectedTails = sidekickStateWithStatus(0x08);
        TraceCharacterState actualTails = new TraceCharacterState(true,
                (short) 0x0142, (short) 0x03A0,
                (short) 0x0018, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, 0x08, 0x16);
        TraceFrame frame = frameWithSidekick(expectedTails);
        TraceEvent.CpuState expectedCpu = cpuStateWithInteractAndTailsInteract(0x00, 0x19);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x24, 0x06, 0x0000, 0x0000,
                0x00, 0x00, 0x00, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                null, null, "tails", actualTails, expectedCpu, actualCpu);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_interact").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x_speed").severity());
        assertTrue(result.hasError());
    }

    @Test
    void testLandingFrameStaleSidekickCpuInteractIdDoesNotOwnFrontier() {
        TraceCharacterState landedTails = new TraceCharacterState(true,
                (short) 0x1537, (short) 0x041A,
                (short) 0xFF61, (short) 0x0000, (short) 0xFF61,
                (byte) 0x00, false, false, 0,
                0x9200, 0x7F00, 0x02, 0x09, 0x16);
        TraceFrame frame = frameWithSidekick(4229, landedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                4229, "tails", 0x36, 0, 0, 0x06,
                (short) 0x06DD, (short) 0x03F0, 0,
                0, 0x04, 0x00, 0, 0x0400,
                0x6C, 0x28, (short) 0x1538, (short) 0x0418,
                0x0400, 0x09, 0x09, 0x16, 0xFF61);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x14, 0x06, 0x06DD, 0x03F0,
                0x04, 0x00, 0x0A, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                null, null, "tails", landedTails, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_interact").severity());
        assertFalse(result.hasError(),
                "A one-frame ROM Tails_interact_ID refresh lag on landing is a diagnostic-only frontier");
    }

    @Test
    void testLandingFrameClearedEngineSidekickCpuInteractIdDoesNotOwnFrontier() {
        TraceCharacterState landedTails = new TraceCharacterState(true,
                (short) 0x0640, (short) 0x01B8,
                (short) 0x0168, (short) 0x0000, (short) 0x0168,
                (byte) 0x00, false, false, 0,
                0x9800, 0xE300, 0x02, 0x08, 0x13);
        TraceFrame frame = frameWithSidekick(3055, landedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                3055, "tails", 0x66, 0, 0, 0x06,
                (short) 0x05D4, (short) 0x01CC, 0,
                0, 0x08, 0x00, 0, 0x0800,
                0x80, 0x3C, (short) 0x0645, (short) 0x01B7,
                0x0800, 0x13, 0x08, 0x06, 0x0168);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x00, 0x06, 0x05D4, 0x01CC,
                0x08, 0x00, 15, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                null, null, "tails", landedTails, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_interact").severity());
        assertFalse(result.hasError(),
                "MTZ2 f3055 lands with ROM Tails_interact_ID still holding the old Obj66 "
                        + "snapshot until the next TailsCPU_UpdateObjInteract pass "
                        + "(docs/s2disasm/s2.asm:39435-39446)");
    }

    @Test
    void testPanicLandingFrameStaleSidekickCpuInteractIdDoesNotOwnFrontier() {
        TraceCharacterState landedTails = new TraceCharacterState(true,
                (short) 0x1A38, (short) 0x014A,
                (short) 0x0180, (short) 0x0000, (short) 0x0180,
                (byte) 0x00, false, false, 0,
                0x4C00, 0x0300, 0x02, 0x09, 0x45);
        TraceFrame frame = frameWithSidekick(7853, landedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                7853, "tails", 0x65, 0, 0, 0x08,
                (short) 0x19F0, (short) 0x012C, 0,
                0, 0x00, 0x00, 0, 0x0800,
                0xFC, 0xB8, (short) 0x1A23, (short) 0x0132,
                0x0800, 0x00, 0x09, 0x45, 0x0180);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x70, 0x08, 0x19F0, 0x012C,
                0x00, 0x00, 0x43, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                null, null, "tails", landedTails, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_interact").severity());
        assertFalse(result.hasError(),
                "MTZ3 f7853 lands while TailsCPU_Panic has already run "
                        + "TailsCPU_CheckDespawn for the frame; ROM refreshes "
                        + "Tails_interact_ID on the next CPU pass "
                        + "(docs/s2disasm/s2.asm:39458-39459,39441-39451)");
    }

    @Test
    void testLandingFrameClearedEngineSidekickCpuInteractIdStillReportsWithMotionDelta() {
        TraceCharacterState expectedTails = new TraceCharacterState(true,
                (short) 0x0640, (short) 0x01B8,
                (short) 0x0168, (short) 0x0000, (short) 0x0168,
                (byte) 0x00, false, false, 0,
                0x9800, 0xE300, 0x02, 0x08, 0x13);
        TraceCharacterState actualTails = new TraceCharacterState(true,
                (short) 0x0641, (short) 0x01B8,
                (short) 0x0168, (short) 0x0000, (short) 0x0168,
                (byte) 0x00, false, false, 0,
                0x9800, 0xE300, 0x02, 0x08, 0x13);
        TraceFrame frame = frameWithSidekick(3055, expectedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                3055, "tails", 0x66, 0, 0, 0x06,
                (short) 0x05D4, (short) 0x01CC, 0,
                0, 0x08, 0x00, 0, 0x0800,
                0x80, 0x3C, (short) 0x0645, (short) 0x01B7,
                0x0800, 0x13, 0x08, 0x06, 0x0168);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x00, 0x06, 0x05D4, 0x01CC,
                0x08, 0x00, 15, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                null, null, "tails", actualTails, expectedCpu, actualCpu);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_interact").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_x").severity());
        assertTrue(result.hasError());
    }

    @Test
    void testLandingFrameStaleSidekickCpuInteractIdStillReportsWithMotionDelta() {
        TraceCharacterState expectedTails = new TraceCharacterState(true,
                (short) 0x1537, (short) 0x041A,
                (short) 0xFF61, (short) 0x0000, (short) 0xFF61,
                (byte) 0x00, false, false, 0,
                0x9200, 0x7F00, 0x02, 0x09, 0x16);
        TraceCharacterState actualTails = new TraceCharacterState(true,
                (short) 0x1537, (short) 0x041B,
                (short) 0xFF61, (short) 0x0000, (short) 0xFF61,
                (byte) 0x00, false, false, 0,
                0x9200, 0x7F00, 0x02, 0x09, 0x16);
        TraceFrame frame = frameWithSidekick(4229, expectedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                4229, "tails", 0x36, 0, 0, 0x06,
                (short) 0x06DD, (short) 0x03F0, 0,
                0, 0x04, 0x00, 0, 0x0400,
                0x6C, 0x28, (short) 0x1538, (short) 0x0418,
                0x0400, 0x09, 0x09, 0x16, 0xFF61);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x14, 0x06, 0x06DD, 0x03F0,
                0x04, 0x00, 0x0A, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                null, null, "tails", actualTails, expectedCpu, actualCpu);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_interact").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_y").severity());
        assertTrue(result.hasError());
    }

    @Test
    void testSidekickCpuComparisonSkippedWhenTraceDoesNotCarryCpuState() {
        TraceFrame frame = TraceFrame.of(117, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x11, 0x06, 0x0000, 0x0000,
                0x10, 0x10, 0x0A, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, null, actualCpu);

        assertFalse(result.fields().containsKey("tails_cpu_present"));
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuPresentReportsEngineAbsenceWhenTraceCarriesCpuState() {
        TraceCharacterState recordedTails = new TraceCharacterState(
                true, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, 0x02, 0, 0);
        TraceFrame frame = frameWithSidekick(117, recordedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                117, "tails", 0x11, 0, 0, 0x06,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x40, 0x40, 0, 0x0000,
                0x68, 0x28, (short) 0x0000, (short) 0x0000,
                0x0000, 0x00, 0x00, 0x00, 0x0000);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, expectedCpu, null);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_present").severity());
        assertTrue(result.hasError());
    }

    @Test
    void testSidekickCpuComparisonSkippedWhenRecordedSidekickIsAbsent() {
        TraceCharacterState absentTails = new TraceCharacterState(
                false, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, 0, 0, 0);
        TraceFrame frame = frameWithSidekick(117, absentTails);
        TraceEvent.CpuState dormantCpuGlobals = new TraceEvent.CpuState(
                117, "tails", 0, 0, 0, 0,
                (short) 0, (short) 0, 0,
                0, 0, 0, 0, 0,
                0, 0, (short) 0, (short) 0,
                0, 0, 0, 0, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0,
                null, null, "tails", absentTails, dormantCpuGlobals, null);

        assertFalse(result.fields().containsKey("tails_cpu_present"));
        assertTrue(result.fields().keySet().stream().noneMatch(name -> name.startsWith("tails_cpu_")));
    }

    @Test
    void testSidekickCpuFollowRingConvertsRomByteOffsetToEngineSlot() {
        TraceCharacterState tailsControl = new TraceCharacterState(
                true, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, 0x02, 0, 0);
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0, 0, 0, 0, 0, 0, 0, 0,
            tailsControl);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                0, "tails", 0x11, 7, 299, 0x06,
                (short) 0x0613, (short) 0x0264, 0,
                1, 0x08, 0x10, 0, 0x4000,
                0x68, 0x28, (short) 0x0500, (short) 0x0200,
                0x0800, 0x08, 0x02, 0x11, 0x000C);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                7, 299, 0x11, 0x06, 0x0613, 0x0264,
                0x08, 0x10, 0x0A, 1);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", tailsControl, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_follow_ring").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuFollowRingSkippedWhenObjectRoutineBypassesCpuControl() {
        TraceFrame frame = new TraceFrame(312, 0x0000,
            (short) 0x030D, (short) 0x03BC,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x04, false, false, 0,
            0, 0, 0x02, 0, 0, 2, 0, 0x139, 0, 0x0AAB, 0,
            new TraceCharacterState(true,
                (short) 0x0323, (short) 0x0364,
                (short) 0x0200, (short) 0xFC30, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0xAF00, 0x4700, 0x04, 0x03, 0x11));
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                312, "tails", 0x2B, 0, 0, 0x06,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x44, 0x04, 0, 0x0000,
                0x4C, 0x08, (short) 0x0000, (short) 0x0000,
                0x0800, 0x06, 0x03, 0x11, 0x0000);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x2B, 0x06, 0, 0,
                0x14, 0, -1, 0);
        TraceCharacterState actualTailsHurt = new TraceCharacterState(
                true, (short) 0x0323, (short) 0x0364,
                (short) 0x0200, (short) 0xFC30, (short) 0x0000,
                (byte) 0x00, true, false, 0,
                0xAF00, 0x4700, 0x04, 0x03, -1);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x030D, (short) 0x03BC,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x04, false, false, 0,
            null, null, "tails", actualTailsHurt, expectedCpu, actualCpu);

        assertFalse(result.fields().containsKey("tails_cpu_follow_ring"),
                "The recorder derives delayed_index every frame; compare it only when Obj02_Control runs");
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuFollowRingSkippedWhenHurtStopRestoresRoutineAfterCpuBypass() {
        TraceCharacterState tailsControlAfterHurtStop = new TraceCharacterState(
                true, (short) 0x0399, (short) 0x03C2,
                (short) 0, (short) 0, (short) 0,
                (byte) 0x04, false, false, 0,
                0xAF00, 0x2700, 0x02, 0x01, 0x11);
        TraceFrame frame = new TraceFrame(371, 0x0000,
            (short) 0x035D, (short) 0x03BD,
            (short) 0x02C7, (short) 0x0046, (short) 0x02CD,
            (byte) 0x04, false, false, 0,
            0, 0, 0x02, 0, 0, 2, 0, 0x174, 0, 0x0AE6, 0,
            tailsControlAfterHurtStop);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                371, "tails", 0x2B, 0, 0, 0x06,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x44, 0x04, 0, 0x0000,
                0x38, 0xF4, (short) 0x0000, (short) 0x0000,
                0x0800, 0x00, 0x01, 0x11, 0x0000);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x2B, 0x06, 0, 0,
                0x14, 0, -1, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x035D, (short) 0x03BD,
            (short) 0x02C7, (short) 0x0046, (short) 0x02CD,
            (byte) 0x04, false, false, 0,
            null, null, "tails", tailsControlAfterHurtStop, expectedCpu, actualCpu);

        assertFalse(result.fields().containsKey("tails_cpu_follow_ring"),
                "A final routine byte of 2 is not enough; compare only when the engine CPU recorded a table read");
    }

    @Test
    void testSidekickCpuCtrl2LogicalNormalizesRomJumpButtons() {
        TraceFrame frame = TraceFrame.of(117, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                117, "tails", 0x11, 0, 0, 0x06,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x40, 0x40, 0, 0x0000,
                0x68, 0x28, (short) 0x0000, (short) 0x0000,
                0x0000, 0x00, 0x00, 0x00, 0x0000);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x11, 0x06, 0x0000, 0x0000,
                0x10, 0x10, 0x0A, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2PressedComparisonIgnoresDirectionalBits() {
        TraceFrame frame = TraceFrame.of(16, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                16, "tails", 0x11, 0, 0, 0x06,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x08, 0x00, 0, 0x0000,
                0x68, 0x28, (short) 0x0000, (short) 0x0000,
                0x0800, 0x00, 0x00, 0x00, 0x0000);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x11, 0x06, 0x0000, 0x0000,
                0x08, 0x08, 0x0A, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2UsesNormalStepWhenRecorderHasDecisionTimeInput() {
        TraceFrame frame = TraceFrame.of(20315, 0x0000,
            (short) 0x49DA, (short) 0x01FD,
            (short) 0x00CC, (short) 0x0000, (short) 0x00CC,
            (byte) 0x00, false, false, 0);
        TraceEvent.CpuState snapshotCpu = new TraceEvent.CpuState(
                20315, "tails", 0x02, 0, 0, 0x06,
                (short) 0x49A1, (short) 0x01FD, 0,
                0, 0x00, 0x00, 0, 0x0068,
                0x98, 0x00, (short) 0x49B3, (short) 0x01FD,
                0x0808, 0x00, 0x00, 0x00, 0x0000);
        TraceEvent.TailsCpuNormalStep normalStep = new TraceEvent.TailsCpuNormalStep(
                20315, "tails", 0x00, 0x00, 0x0000, 0x0000,
                0xFF, 0x0808, 0x98, 0x49B3, 0x01FD, 0xFFE1, 0xFFFC,
                "fallthrough_sub20", 0x0808, 0x08,
                0x0000, 0x0000, 0x00, 0x0006, 0x0006, 0x00);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x02, 0x06, 0x49A1, 0x01FD,
                0x08, 0x00, 0x16, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x49DA, (short) 0x01FD,
            (short) 0x00CC, (short) 0x0000, (short) 0x00CC,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, snapshotCpu, actualCpu, normalStep);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2UsesDecisionSampleWhenLaterObjectClearsLiveLatch() {
        TraceFrame frame = TraceFrame.of(25039, 0x0000,
            (short) 0x4AA0, (short) 0x01FD,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);
        TraceEvent.CpuState snapshotCpu = new TraceEvent.CpuState(
                25039, "tails", 0x02, 0, 0, 0x06,
                (short) 0x49AA, (short) 0x01FD, 0,
                0, 0x18, 0x00, 0, 0x0058,
                0x00, 0x00, (short) 0x0000, (short) 0x0000,
                0x1800, 0x18, 0x00, 0x00, 0x0000);
        TraceEvent.TailsCpuNormalStep normalStep = new TraceEvent.TailsCpuNormalStep(
                25039, "tails", 0x08, 0x00, 0x0024, 0x0024,
                0x00, 0x0000, 0x00, 0x0000, 0x0000, 0x0000, 0x0000,
                "not_seen", 0x1800, 0x18,
                0x0024, 0x0024, 0x08, 0x0030, 0x0030, 0x08);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x02, 0x06, 0x49AA, 0x01FD,
                0x00, 0x00, 0x18, 0x00, -1, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x4AA0, (short) 0x01FD,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, snapshotCpu, actualCpu, normalStep);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2IgnoresLatchedPanicInputWhileCoasting() {
        TraceCharacterState expectedTails = traceSidekickWithGroundSpeed((short) 0x09AB);
        TraceFrame frame = frameWithSidekick(936, expectedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                936, "tails", 0x41, 0, 0x18, 0x08,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x02, 0x02, 0, 0x0800,
                0x0C, 0xC8, (short) 0x08D1, (short) 0x06F8,
                0x0200, 0x04, 0x04, 0x12, 0x09AB);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0x18, 0x41, 0x08, 0x0000, 0x0000,
                0x00, 0x00, -1, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x08D3, (short) 0x0693,
            (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
            (byte) 0xCC, false, true, 3,
            null, null, "tails", expectedTails, expectedCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError(),
                "Routine-8 Ctrl_2 latch differences while inertia is nonzero are not an actionable frontier");
    }

    @Test
    void testSidekickCpuCtrl2StillFailsForStationaryPanicInputMismatch() {
        TraceCharacterState expectedTails = traceSidekickWithGroundSpeed((short) 0x0000);
        TraceFrame frame = frameWithSidekick(937, expectedTails);
        TraceEvent.CpuState expectedCpu = new TraceEvent.CpuState(
                937, "tails", 0x41, 0, 0x18, 0x08,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x02, 0x02, 0, 0x0800,
                0x0C, 0xC8, (short) 0x08D1, (short) 0x06F8,
                0x0200, 0x04, 0x04, 0x12, 0x0000);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0x18, 0x41, 0x08, 0x0000, 0x0000,
                0x00, 0x00, -1, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x08D3, (short) 0x0693,
            (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
            (byte) 0xCC, false, true, 3,
            null, null, "tails", expectedTails, expectedCpu, actualCpu);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertTrue(result.hasError(),
                "Once routine 8 can write DOWN, Ctrl_2 remains a strict frontier signal");
    }

    @Test
    void testSidekickCpuCtrl2NormalStepAcceptsHighByteHeldInput() {
        TraceFrame frame = TraceFrame.of(218, 0x0000,
            (short) 0x0371, (short) 0x036C,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);
        TraceEvent.CpuState snapshotCpu = new TraceEvent.CpuState(
                218, "tails", 0x02, 0, 0, 0x06,
                (short) 0x0301, (short) 0x035C, 0,
                0, 0x00, 0x00, 0, 0x0068,
                0x98, 0x00, (short) 0x0351, (short) 0x036C,
                0x0000, 0x00, 0x00, 0x00, 0x0000);
        TraceEvent.TailsCpuNormalStep normalStep = new TraceEvent.TailsCpuNormalStep(
                218, "tails", 0x00, 0x00, 0x0000, 0x0000,
                0xFF, 0x0000, 0x98, 0x0351, 0x036C, 0x0050, 0x0000,
                "leader_fast", 0x0800, 0x08,
                0x0000, 0x0000, 0x00, 0x0000, 0x0000, 0x00);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x02, 0x06, 0x0301, 0x035C,
                0x08, 0x00, 0x16, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0371, (short) 0x036C,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, null, "tails", null, snapshotCpu, actualCpu, normalStep);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2StillAcceptsCpuStateWhenNormalStepTapIsZero() {
        TraceFrame frame = TraceFrame.of(1726, 0x0000,
            (short) 0x182F, (short) 0x0417,
            (short) 0x001F, (short) 0xFE00, (short) 0xFF74,
            (byte) 0x00, true, true, 0);
        TraceEvent.CpuState snapshotCpu = new TraceEvent.CpuState(
                1726, "tails", 0x00, 0, 0, 0x06,
                (short) 0x138A, (short) 0x041B, 0,
                0, 0x40, 0x40, 0, 0x0000,
                0x74, 0x00, (short) 0x1815, (short) 0x0461,
                0x4040, 0xFF, 0x07, 0x00, 0x0000);
        TraceEvent.TailsCpuNormalStep normalStep = new TraceEvent.TailsCpuNormalStep(
                1726, "tails", 0x01, 0x00, 0xFF74, 0xFF74,
                0x07, 0x4040, 0x74, 0x1815, 0x0461, 0xFFE2, 0x0001,
                "fallthrough_sub20", 0x0000, 0x00,
                0xFF74, 0xFF74, 0x07, 0x0000, 0x0000, 0x00);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x00, 0x06, 0x138A, 0x041B,
                0x10, 0x10, 0x13, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x182F, (short) 0x0417,
            (short) 0x001F, (short) 0xFE00, (short) 0xFF74,
            (byte) 0x00, true, true, 0,
            null, null, "tails", null, snapshotCpu, actualCpu, normalStep);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2UsesGeneratedNormalStepNotDelayedHeldInput() {
        TraceFrame frame = TraceFrame.of(2894, 0x0011,
            (short) 0x172E, (short) 0x0A5A,
            (short) 0x0400, (short) 0xFFC0, (short) 0x0300,
            (byte) 0x00, true, false, 0);
        TraceEvent.CpuState snapshotCpu = new TraceEvent.CpuState(
                2894, "tails", 0x00, 0, 0, 0x06,
                (short) 0x14F5, (short) 0x086C, 0,
                0, 0x15, 0x04, 0, 0x0000,
                0x00, 0xFF, (short) 0x0000, (short) 0x0000,
                0xFFFF, 0xFF, 0x00, 0x00, 0x0000);
        TraceEvent.TailsCpuNormalStep normalStep = new TraceEvent.TailsCpuNormalStep(
                2894, "tails", 0x43, 0x00, 0x0300, 0x0400,
                0x42, 0x1504, 0xFF, 0x168C, 0x0A74, 0xFFAD, 0xFFFF,
                "fallthrough_sub20", 0x0000, 0x00,
                0x0000, 0x0000, 0x00, 0x0000, 0x0000, 0x00);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x00, 0x06, 0x14F5, 0x086C,
                0x15, 0x10, 0x30, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x172E, (short) 0x0A5A,
            (short) 0x0400, (short) 0xFFC0, (short) 0x0300,
            (byte) 0x00, true, false, 0,
            null, null, "tails", null, snapshotCpu, actualCpu, normalStep);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertTrue(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2HeldMirrorOnObjectDoesNotOwnFrontier() {
        TraceCharacterState landedTails = traceSidekickOnObjectAtRest();
        TraceFrame frame = frameWithSidekick(3675, landedTails);
        TraceEvent.CpuState snapshotCpu = cpuStateWithCtrl2HeldMirror(3675);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x01, 0x06, 0x1070, 0x06CA,
                0x00, 0x00, 0x25, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x08D3, (short) 0x0693,
            (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
            (byte) 0xCC, false, true, 3,
            null, null, "tails", landedTails, snapshotCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError(),
                "Ctrl_2 held can mirror Ctrl_1 on an on-object stationary frame without owning the frontier");
    }

    @Test
    void testSidekickCpuCtrl2HeldMirrorStillReportsWithMotionDelta() {
        TraceCharacterState expectedTails = traceSidekickOnObjectAtRest();
        TraceCharacterState actualTails = new TraceCharacterState(true,
                (short) 0x1070, (short) 0x06CF,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, true, 0,
                0xE000, 0x0C00, 0x02, 0x0C, 0x25);
        TraceFrame frame = frameWithSidekick(3675, expectedTails);
        TraceEvent.CpuState snapshotCpu = cpuStateWithCtrl2HeldMirror(3675);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 0, 0x01, 0x06, 0x1070, 0x06CA,
                0x00, 0x00, 0x25, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x08D3, (short) 0x0693,
            (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
            (byte) 0xCC, false, true, 3,
            null, null, "tails", actualTails, snapshotCpu, actualCpu);

        assertEquals(Severity.ERROR, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.ERROR, result.fields().get("tails_y").severity());
        assertTrue(result.hasError());
    }

    @Test
    void testSidekickCpuCtrl2HeldJumpOnlyNoopDoesNotOwnFrontier() {
        TraceCharacterState tails = new TraceCharacterState(true,
                (short) 0x1012, (short) 0x0580,
                (short) 0x03C8, (short) 0x0933, (short) 0x09FB,
                (byte) 0x30, false, true, 0,
                0x3000, 0xEF00, 0x02, 0x05, 0x2E);
        TraceFrame frame = frameWithSidekick(3876, tails);
        TraceEvent.CpuState snapshotCpu = new TraceEvent.CpuState(
                3876, "tails", 0x85, 0, 79, 0x06,
                (short) 0x1070, (short) 0x06CA, 0,
                0, 0x74, 0x04, 0, 0x0000,
                0xFC, 0xB8, (short) 0x0F71, (short) 0x03EC,
                0x0000, 0x02, 0x05, 0x33, 0x09FB);
        EngineSidekickCpuState actualCpu = new EngineSidekickCpuState(
                0, 79, 0x85, 0x06, 0x1070, 0x06CA,
                0x04, 0x04, 0x2E, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x08D3, (short) 0x0693,
            (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
            (byte) 0xCC, false, true, 3,
            null, null, "tails", tails, snapshotCpu, actualCpu);

        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_held").severity());
        assertEquals(Severity.MATCH, result.fields().get("tails_cpu_ctrl2_pressed").severity());
        assertFalse(result.hasError());
    }

    private static TraceCharacterState traceSidekickWithGroundSpeed(short gSpeed) {
        return new TraceCharacterState(true,
                (short) 0x08AF, (short) 0x07BF,
                (short) 0x08E9, (short) 0xFC56, gSpeed,
                (byte) 0xE8, false, true, 0,
                0x0700, 0xF600, 0x02, 0x04, 0x12);
    }

    private static TraceFrame frameWithSidekick(int frame, TraceCharacterState sidekick) {
        return new TraceFrame(frame, 0x0000,
                (short) 0x08D3, (short) 0x0693,
                (short) 0x0090, (short) 0xFB1D, (short) 0x0633,
                (byte) 0xCC, false, true, 3,
                0xCA00, 0xAD00, 0x02, 0x0833, 0x064E, 15, 0x06,
                0x03A9, 0x12, 0x30DF, 0, sidekick);
    }

    private static TraceCharacterState traceSidekickOnObjectAtRest() {
        return new TraceCharacterState(true,
                (short) 0x1070, (short) 0x06CE,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, true, 0,
                0xE000, 0x0C00, 0x02, 0x0C, 0x25);
    }

    private static TraceEvent.CpuState cpuStateWithCtrl2HeldMirror(int frame) {
        return new TraceEvent.CpuState(
                frame, "tails", 0x01, 0, 0, 0x06,
                (short) 0x1070, (short) 0x06CA, 0,
                0, 0x10, 0x00, 0, 0x1000,
                0xD8, 0x94, (short) 0x1070, (short) 0x06CB,
                0x1000, 0x0C, 0x0C, 0x33, 0x0000);
    }

    @Test
    void testRingCountMismatchIsWarningWhenConfiguredWarnOnly() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0, 0, 7, 0, 1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(new ToleranceConfig(
            1, 1, 1, 1, true, 1, 1, 1, 1, ToleranceConfig.RingCountMode.WARN_ONLY));
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, 8, 0, 0, 0,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertTrue(result.hasDivergence());
        assertFalse(result.hasError());
        assertEquals(Severity.WARNING, result.fields().get("rings").severity());
    }

    @Test
    void testDefaultRingCountMismatchIsError() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0, 0, 7, 0, 1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, 8, 0, 0, 0,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("rings").severity());
    }

    @Test
    void testWithRingCountModeFactoryDowngradesToWarning() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0, 0, 7, 0, 1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(
            ToleranceConfig.DEFAULT.withRingCountMode(ToleranceConfig.RingCountMode.WARN_ONLY));
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, 8, 0, 0, 0,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertFalse(result.hasError());
        assertEquals(Severity.WARNING, result.fields().get("rings").severity());
    }

    @Test
    void testRingCountMismatchIsErrorWhenConfiguredForceError() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0, 0, 7, 0, 1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(new ToleranceConfig(
            1, 1, 1, 1, true, 1, 1, ToleranceConfig.RingCountMode.FORCE_ERROR));
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, 8, 0, 0, 0,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("rings").severity());
    }

    @Test
    void testCameraMatchProducesNoCameraDivergence() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0x0140, 0x00C0, -1, 0, 1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, -1, 0, 0x0140, 0x00C0,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertEquals(Severity.MATCH, result.fields().get("camera_x").severity());
        assertEquals(Severity.MATCH, result.fields().get("camera_y").severity());
        assertFalse(result.hasError());
    }

    @Test
    void testCameraMismatchIsErrorByDefault() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0x0140, 0x00C0, -1, 0, 1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, -1, 0, 0x0142, 0x00C0,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("camera_x").severity());
        assertEquals(Severity.MATCH, result.fields().get("camera_y").severity());
    }

    @Test
    void testCameraComparisonSkippedWhenTraceCameraAbsent() {
        TraceFrame frame = TraceFrame.of(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, -1, 0, 0x0140, 0x00C0,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertFalse(result.fields().containsKey("camera_x"));
        assertFalse(result.fields().containsKey("camera_y"));
    }

    @Test
    void testObjectNearSemanticMatchStillReportsSlotMismatch() {
        TraceFrame frame = TraceFrame.of(1217, 0xA0E0,
            (short) 0x0935, (short) 0x044C,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        binder.compareFrame(frame,
            (short) 0x0935, (short) 0x044C,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        binder.compareObjectNear(1217,
            java.util.List.of(new TraceEvent.ObjectNear(
                1217, "sonic", 72, "0x5F", (short) 0x0960, (short) 0x03D0, "0x02", "0x00", "", "", "", "", "", "", "")),
            java.util.List.of(new EngineNearbyObject(
                74, 0x5F, "Bomb", 0x0960, 0x03D0, 0x0960, 0x03D0, true,
                0x9A, 0x9A, 0x0960, 0x03D0, false, false, true)));

        DivergenceReport report = binder.buildReport();
        assertFalse(report.errors().isEmpty(), "slot mismatch should be reported");
        DivergenceGroup firstError = report.errors().getFirst();
        assertEquals("obj_s48_slot", firstError.field());
        assertEquals("0x48", firstError.expectedAtStart());
        assertEquals("0x4A", firstError.actualAtStart());
    }

    @Test
    void testCameraComparisonHandlesU16WraparoundOnEngineSide() {
        // Capture sites mask Camera.getX() with & 0xFFFF so engine values are
        // always stored as unsigned 16-bit. TraceBinder additionally masks both
        // sides before comparing, guaranteeing no spurious wraparound deltas.
        // 0xFFE0 (= 65504) on both sides must compare as MATCH.
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            0, 0, 0x02, 0xFFE0, 0x0040, -1, 0, 1, -1, -1, -1, null);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0,
            null, new EngineDiagnostics(0x02, -1, -1, -1, 0, 0xFFE0, 0x0040,
                -1, -1, -1, -1, "", 0, 0, -1, -1));

        assertEquals(Severity.MATCH, result.fields().get("camera_x").severity());
    }

    private static TraceFrame frameWithSidekick(TraceCharacterState sidekick) {
        return new TraceFrame(1775, 0x0000,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, -1, -1, -1, 0x00,
                -1, -1, -1, -1, sidekick);
    }

    private static TraceCharacterState sidekickStateWithStatus(int statusByte) {
        return new TraceCharacterState(true,
                (short) 0x0142, (short) 0x03A0,
                (short) 0x0000, (short) 0x0000, (short) 0x0000,
                (byte) 0x00, false, false, 0,
                0, 0, 0x02, statusByte, (statusByte & 0x08) != 0 ? 0x16 : -1);
    }

    private static TraceEvent.CpuState cpuStateWithInteract(int interact) {
        return cpuStateWithInteractAndTailsInteract(interact, interact);
    }

    private static TraceEvent.CpuState cpuStateWithInteractAndTailsInteract(
            int interact, int tailsInteract) {
        return new TraceEvent.CpuState(
                1775, "tails", interact, 0, 0, 0x06,
                (short) 0x0000, (short) 0x0000, 0,
                0, 0x00, 0x00, 0, 0x0000,
                0x00, 0x00, (short) 0x0000, (short) 0x0000,
                0x0000, 0x00, 0x00, tailsInteract, 0x0000);
    }
}
