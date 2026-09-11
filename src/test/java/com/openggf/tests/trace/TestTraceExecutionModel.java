package com.openggf.tests.trace;

import com.openggf.tests.TestTempFiles;
import com.openggf.trace.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceExecutionModel {

    @Test
    void sonic1CounterDelta_fullLevelFrame() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0121, 0x3457, 0);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void sonic1VblankDeltaWithoutGameplayDelta_vblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0121, 0x3456, 0);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void sonic1NoCounterDelta_gameplayPlateauIsVblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0120, 0x3456, 0);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void sonic1LagCounterDeltaWithoutGameplayDelta_vblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0120, 0x3456, 4);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    /**
     * The gameplay counter is bumped in the instruction after
     * {@code WaitForVBlank} returns (docs/s1disasm/sonic.asm:3001-3002), so a
     * V-blank that elapses without it advancing was taken inside the previous
     * row's iteration: that iteration's loop tail is still ahead.
     */
    @Test
    void aVblankWithoutAGameplayDeltaHoldsTheIterationIntoTheNextRow() {
        TraceFrame current = TraceFrame.executionTestFrame(0, 0x2531, 0x006C, 0);
        TraceFrame next = TraceFrame.executionTestFrame(1, 0x2532, 0x006C, 1);

        assertTrue(TraceExecutionModel.isIterationHeldIntoNextRow(current, next));
    }

    @Test
    void anIterationThatCompletedIsNotHeldIntoTheNextRow() {
        TraceFrame current = TraceFrame.executionTestFrame(0, 0x2531, 0x006C, 0);
        TraceFrame next = TraceFrame.executionTestFrame(1, 0x2532, 0x006D, 0);

        assertFalse(TraceExecutionModel.isIterationHeldIntoNextRow(current, next));
    }

    /**
     * A row on which no V-blank ran at all is the starved shape, not a held
     * iteration; it is classified by {@link TraceExecutionModel#isVblankStarvedRow}.
     */
    @Test
    void aStarvedRowIsNotAHeldIteration() {
        TraceFrame current = TraceFrame.executionTestFrame(0, 0x2531, 0x006C, 0);
        TraceFrame next = TraceFrame.executionTestFrame(1, 0x2531, 0x006C, 0);

        assertFalse(TraceExecutionModel.isIterationHeldIntoNextRow(current, next));
        assertTrue(TraceExecutionModel.isVblankStarvedRow(current, next));
    }

    @Test
    void aMissingSuccessorRowIsNotAHeldIteration() {
        TraceFrame current = TraceFrame.executionTestFrame(0, 0x2531, 0x006C, 0);

        assertFalse(TraceExecutionModel.isIterationHeldIntoNextRow(current, null));
    }

    @Test
    void sonic1GameplayCounterDeltaWinsOverLagCounterDelta() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0121, 0x3457, 4);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void sonic2VblankDeltaWithoutGameplayDelta_vblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0220, 0x1456, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0221, 0x1456, 0);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s2").phaseFor(previous, current));
    }

    @Test
    void sonic2NoCounterDelta_gameplayPlateauIsVblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0220, 0x1456, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0220, 0x1456, 0);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s2").phaseFor(previous, current));
    }

    @Test
    void sonic2LagCounterDeltaWithoutGameplayDelta_vblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0220, 0x1456, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0220, 0x1456, 4);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s2").phaseFor(previous, current));
    }

    @Test
    void sonic2GameplayCounterDeltaWinsOverLagCounterDelta() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x0220, 0x1456, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x0221, 0x1457, 4);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s2").phaseFor(previous, current));
    }

    @Test
    void sonic3kLagCounterDelta_vblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x2000, 0x0100, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x2001, 0x0100, 4);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s3k").phaseFor(previous, current));
    }

    @Test
    void sonic3kLagCounterAloneSelectsVblankOnly() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x2000, 0x0100, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x2000, 0x0100, 4);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s3k").phaseFor(previous, current));
    }

    @Test
    void sonic3kGameplayCounterDeltaWinsOverLagCounterDelta() {
        TraceFrame previous = TraceFrame.executionTestFrame(0, 0x2000, 0x0100, 3);
        TraceFrame current = TraceFrame.executionTestFrame(1, 0x2001, 0x0101, 4);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s3k").phaseFor(previous, current));
    }

    @Test
    void sonic3kVblankCounterWithStateChangeIsFullLevelFrame() {
        TraceFrame previous = new TraceFrame(0, 0,
                (short) 0x0FDE, (short) 0x0326,
                (short) 0x0622, (short) 0x0285, (short) 0x06A8,
                (byte) 0x10, false, false, 0,
                0, 0, 2, -1, -1, 99, 0,
                0, 9, 0x0300, 0);
        TraceFrame current = new TraceFrame(1, 0,
                (short) 0x0FE4, (short) 0x0329,
                (short) 0x062D, (short) 0x028A, (short) 0x06B4,
                (byte) 0x10, false, false, 0,
                0, 0, 2, -1, -1, 100, 0,
                0, 9, 0x0400, 0);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s3k").phaseFor(previous, current));
    }

    @Test
    void sonic3kFrozenNormalLevelModeRowsStayFullLevelFrames() throws Exception {
        TraceData trace = twoFrameS3kTraceWithGameMode(0x0C);
        TraceFrame previous = trace.getFrame(0);
        TraceFrame current = trace.getFrame(1);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
    }

    @Test
    void sonic3kFrozenTransitionLevelModeRowsAreVblankOnly() throws Exception {
        TraceData trace = twoFrameS3kTraceWithGameMode(0x8C);
        TraceFrame previous = trace.getFrame(0);
        TraceFrame current = trace.getFrame(1);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
    }

    @Test
    void sonic3kMissingCpuExecutionHookMarksMovingDuplicateAsLag() throws Exception {
        TraceData trace = TraceData.load(TraceV5TestFixture.canonicalizeInstalledTrace(
                Path.of("src/test/resources/traces/s3k/cnz_completerun")));
        TraceFrame previous = trace.getFrame(22347);
        TraceFrame current = trace.getFrame(22348);

        assertEquals(previous.xSpeed(), current.xSpeed());
        assertEquals(previous.ySpeed(), current.ySpeed());
        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
    }

    @Test
    void sonic3kObjectHeldDuplicateStillExecutesItsControllerFrame() throws Exception {
        TraceData trace = TraceData.load(TraceV5TestFixture.canonicalizeInstalledTrace(
                Path.of("src/test/resources/traces/s3k/hcz_completerun")));
        TraceFrame previous = trace.getFrame(17415);
        TraceFrame current = trace.getFrame(17416);

        assertEquals(0x08, current.statusByte() & 0x08);
        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
    }

    @Test
    void preLevelS3kIntroPrefixTicksReplayAsFullFramesBeforeGameplayStart() throws Exception {
        TraceData trace = TraceData.load(TraceV5TestFixture.canonicalizeInstalledTrace(
                Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun")));
        // Frames 500/501 are well into the AIZ1 intro cutscene (past the first
        // in-level frame at 289, before gameplay_start). This section is
        // native level execution, so it must tick as full frames even while
        // player control is still locked by the intro object and the regenerated
        // trace keeps Level_frame_counter at zero.
        TraceFrame previous = trace.getFrame(500);
        TraceFrame current = trace.getFrame(501);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
    }

    @Test
    void s2VblankSplitUsesFollowingVisualDiagnosticsOnly() throws Exception {
        TraceData trace = TraceData.load(TraceV5TestFixture.canonicalizeInstalledTrace(
                Path.of("src/test/resources/traces/s2/mtz3")));
        TraceFrame previous = trace.getFrame(5179);
        TraceFrame current = trace.getFrame(5180);
        TraceFrame next = trace.getFrame(5181);
        TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME, phase);
        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, current, next));
        assertEquals(current.gameplayFrameCounter(), next.gameplayFrameCounter());
        assertEquals(current.x(), next.x());
        assertEquals(current.y(), next.y());
        assertNotEquals(current.cameraY(), next.cameraY());

        TraceFrame comparison = TraceReplayBootstrap.frameForGameplayComparison(
                trace, 5180, previous, current, phase);

        assertEquals(current.x(), comparison.x());
        assertEquals(current.y(), comparison.y());
        assertEquals(next.cameraX(), comparison.cameraX());
        assertEquals(next.cameraY(), comparison.cameraY());
        assertEquals(next.rings(), comparison.rings());
    }

    @Test
    void s3kRingDiagnosticComparisonKeepsCurrentTraceRingCount() throws Exception {
        TraceData trace = TraceData.load(TraceV5TestFixture.canonicalizeInstalledTrace(
                Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun")));
        TraceFrame current = trace.getFrame(6203);
        TraceFrame next = trace.getFrame(6204);
        EngineDiagnostics engineDiag = new EngineDiagnostics(-1, -1, -1, next.rings(),
                -1, current.cameraX(), current.cameraY(), -1, -1, -1, -1,
                "", -1, -1, -1, -1);

        assertEquals(current.rings() + 1, next.rings());

        TraceFrame comparison = TraceReplayBootstrap.s3kFrameForRingDiagnosticComparison(
                trace, 6203, current, engineDiag);

        assertEquals(current.x(), comparison.x());
        assertEquals(current.y(), comparison.y());
        assertEquals(current.cameraX(), comparison.cameraX());
        assertEquals(current.cameraY(), comparison.cameraY());
        assertEquals(current.rings(), comparison.rings());
    }

    @Test
    void s3kRingDiagnosticComparisonKeepsPersistentMismatchVisible() throws Exception {
        TraceData trace = TraceData.load(TraceV5TestFixture.canonicalizeInstalledTrace(
                Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun")));
        TraceFrame current = trace.getFrame(6203);
        TraceFrame next = trace.getFrame(6204);
        EngineDiagnostics engineDiag = new EngineDiagnostics(-1, -1, -1, next.rings() + 1,
                -1, current.cameraX(), current.cameraY(), -1, -1, -1, -1,
                "", -1, -1, -1, -1);

        TraceFrame comparison = TraceReplayBootstrap.s3kFrameForRingDiagnosticComparison(
                trace, 6203, current, engineDiag);

        assertEquals(current.rings(), comparison.rings());
    }

    @Test
    void s3kVblankSplitUsesFollowingCameraButCurrentRingCount() throws Exception {
        TraceData trace = TraceData.load(TraceV5TestFixture.canonicalizeInstalledTrace(
                Path.of("src/test/resources/traces/s3k/aiz_completerun")));
        int currentIndex = -1;
        for (int i = 1; i + 1 < trace.frameCount(); i++) {
            TraceFrame candidate = trace.getFrame(i);
            TraceFrame candidateNext = trace.getFrame(i + 1);
            if (TraceReplayBootstrap.phaseForReplay(
                            trace, trace.getFrame(i - 1), candidate)
                            == TraceExecutionPhase.FULL_LEVEL_FRAME
                    && TraceReplayBootstrap.phaseForReplay(trace, candidate, candidateNext)
                            == TraceExecutionPhase.VBLANK_ONLY
                    && candidate.stateEquals(candidateNext)
                    && candidate.gameplayFrameCounter()
                            == candidateNext.gameplayFrameCounter()
                    && candidate.cameraX() != candidateNext.cameraX()) {
                currentIndex = i;
                break;
            }
        }

        assertNotEquals(-1, currentIndex, "fixture must contain an S3K camera split row");
        TraceFrame previous = trace.getFrame(currentIndex - 1);
        TraceFrame current = trace.getFrame(currentIndex);
        TraceFrame next = trace.getFrame(currentIndex + 1);
        TraceExecutionPhase phase =
                TraceReplayBootstrap.phaseForReplay(trace, previous, current);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME, phase);
        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, current, next));
        assertEquals(current.gameplayFrameCounter(), next.gameplayFrameCounter());
        assertEquals(current.x(), next.x());
        assertEquals(current.y(), next.y());
        assertNotEquals(current.cameraX(), next.cameraX());

        TraceFrame comparison = TraceReplayBootstrap.s3kFrameForGameplayComparison(
                trace, currentIndex, previous, current, phase);

        assertEquals(current.x(), comparison.x());
        assertEquals(current.y(), comparison.y());
        assertEquals(next.cameraX(), comparison.cameraX());
        assertEquals(next.cameraY(), comparison.cameraY());
        assertEquals(current.rings(), comparison.rings());
    }

    @Test
    void firstFrameDefaultsToFullLevelFrame() {
        TraceFrame current = TraceFrame.executionTestFrame(0, 0x0120, 0x3456, 0);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s1").phaseFor(null, current));
    }

    @Test
    void legacyTraceWithoutVblankCounter_fallsBackToStateHeuristic() {
        TraceFrame previous = new TraceFrame(0, 0,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x000C, (short) 0x0000, (short) 0x000C,
                (byte) 0x00, false, false, 0,
                0, 0, -1, -1, -1, -1, -1,
                0, -1, -1, -1);
        TraceFrame current = new TraceFrame(1, 0,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x000C, (short) 0x0000, (short) 0x000C,
                (byte) 0x00, false, false, 0,
                0, 0, -1, -1, -1, -1, -1,
                0, -1, -1, -1);

        assertEquals(TraceExecutionPhase.VBLANK_ONLY,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void legacyTraceWithoutVblankCounter_usesStateChangeForFullFrame() {
        TraceFrame previous = new TraceFrame(0, 0,
                (short) 0x0050, (short) 0x03B0,
                (short) 0x000C, (short) 0x0000, (short) 0x000C,
                (byte) 0x00, false, false, 0,
                0, 0, -1, -1, -1, -1, -1,
                0, -1, -1, -1);
        TraceFrame current = new TraceFrame(1, 0,
                (short) 0x0051, (short) 0x03B0,
                (short) 0x000C, (short) 0x0000, (short) 0x000C,
                (byte) 0x00, false, false, 0,
                0, 0, -1, -1, -1, -1, -1,
                0, -1, -1, -1);

        assertEquals(TraceExecutionPhase.FULL_LEVEL_FRAME,
                TraceExecutionModel.forGame("s1").phaseFor(previous, current));
    }

    @Test
    void unsupportedGameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TraceExecutionModel.forGame("bad"));
    }

    private static TraceData twoFrameS3kTraceWithGameMode(int gameMode) throws Exception {
        Path dir = TestTempFiles.createTempDirectory("s3k-phase-model");
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s3k",
              "zone": "AIZ",
              "zone_id": 0,
              "act": 0,
              "bk2_frame_offset": 0,
              "trace_frame_count": 2,
              "start_x": "0x0000",
              "start_y": "0x0000",
              "recording_date": "2026-06-09",
              "trace_schema": 5,
              "characters": ["sonic", "tails"]
            }
            """);
        Files.writeString(dir.resolve("physics.csv"),
                TraceV5TestFixture.LEVEL_HEADER + "\n"
                        + s3kPhaseRow(0) + "\n"
                        + s3kPhaseRow(1) + "\n");
        Files.writeString(dir.resolve("aux_state.jsonl"), String.format(
                "{\"frame\":0,\"event\":\"zone_act_state\",\"actual_zone_id\":1,"
                        + "\"actual_act\":0,\"apparent_act\":0,\"game_mode\":%d}%n",
                gameMode));
        return TraceData.load(dir);
    }

    private static String s3kPhaseRow(int frame) {
        String[] fields = TraceV5TestFixture.levelRow(frame).split(",", -1);
        fields[2] = "4A38";
        fields[3] = "02B6";
        fields[4] = "0061";
        fields[5] = "0000";
        fields[6] = "0500";
        fields[7] = "0000";
        fields[8] = "1";
        fields[9] = "4AD8";
        fields[10] = "0342";
        fields[12] = "0C08";
        fields[20] = "02";
        fields[22] = "08";
        fields[25] = "1";
        fields[26] = "4AE3";
        fields[27] = "0346";
        fields[29] = "0C08";
        fields[31] = "1";
        fields[36] = "E300";
        fields[37] = "7C00";
        fields[38] = "02";
        fields[39] = "03";
        fields[40] = "08";
        return String.join(",", fields);
    }
}
