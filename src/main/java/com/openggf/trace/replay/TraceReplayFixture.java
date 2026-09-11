package com.openggf.trace.replay;

import com.openggf.game.session.GameplayModeContext;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;

import java.util.List;

/**
 * Narrow view of a fixture capable of driving trace replay. Implemented
 * by {@code HeadlessTestFixture} in tests and by the live launcher's
 * internal adapter at runtime.
 */
public interface TraceReplayFixture {
    AbstractPlayableSprite sprite();

    GameplayModeContext gameplayMode();

    /** Registers the replay port and its stateless production-boundary observer. */
    void installHardwareTimingReplay(HardwareTimingReplayPort replayPort);

    /**
     * Removes and returns recorded completion edges that had no engine-pending
     * counterpart, so the driving comparison can record them as errors on the
     * row that produced them. A driver that does not override this still fails
     * the run when the replay port is closed.
     */
    default List<String> drainUnmatchedRecordedHardwareCompletions() {
        return List.of();
    }

    /**
     * Declares that this driver drains
     * {@link #drainPendingRecordedHardwareSubmissions()} after the run closes
     * and records the result as a comparison error. A driver that does not
     * declare it keeps the original hard failure at close, so the demotion can
     * never become silence.
     */
    default void reportPendingRecordedHardwareSubmissionsAtClose() {
    }

    /**
     * Removes and returns the production submissions the recorded stream never
     * completed, so the driving comparison can record them as an error on the
     * closing row. The submissions were neither admitted nor released.
     */
    default List<String> drainPendingRecordedHardwareSubmissions() {
        return List.of();
    }

    /** Latches the trace row's physical raw frame before any row work or retry. */
    void beginTraceRow(int traceIndex, int rawFrame);

    /** Deactivates recorded edge authority while no trace row is represented. */
    void enterHardwareTimingGap();

    /** Verifies that the current segment consumed every recorded completion edge. */
    void verifyHardwareTimingSegmentEdges();

    /** Validates and installs the next structural segment's completion schedule. */
    void handoffHardwareTimingReplay(HardwareTimingSchedule nextSchedule);

    /**
     * Hands off across a recorded interstitial span, advancing the hardware
     * identity cursor over ordinals the recording consumed between the two
     * segments and production never submits.
     *
     * <p>Fixtures that predate the run-level interstitial stream inherit the
     * span-free handoff: an empty span map is the only thing an unrecorded
     * fixture ever produces, so this default keeps their behaviour identical.
     */
    default void handoffHardwareTimingReplay(
            HardwareTimingSchedule nextSchedule,
            java.util.Map<com.openggf.game.timing.HardwareWorkKind,
                    com.openggf.game.timing.RecordedOrdinalSpan> interstitialSpans) {
        if (!interstitialSpans.isEmpty()) {
            throw new UnsupportedOperationException(
                    "fixture does not support recorded interstitial spans: "
                            + getClass().getName());
        }
        handoffHardwareTimingReplay(nextSchedule);
    }

    /** Final-run verification, admission closure, observer removal, and deregistration. */
    void closeHardwareTimingReplayRun();

    /**
     * Detaches an incomplete replay without verifying unconsumed edges.
     * The owning gameplay context must be destroyed immediately afterward.
     */
    void abortHardwareTimingReplayRun();

    /**
     * Closes the current production dynamic-art comparison segment at a
     * structural replay boundary. This seam carries no expected trace value.
     */
    default void closeDynamicArtComparisonSegment() {
        gameplayMode().endDynamicArtComparisonSegment();
    }

    /**
     * Runs the one main-loop iteration the ROM executes after the last
     * sampled frame, in {@code Level_MainLoop} order
     * (docs/s2disasm/s2.asm:5088): the V-int art boundary first
     * (docs/s2disasm/s2.asm:5091 WaitForVint with VintID_Level, reaching
     * ProcessDMAQueue at docs/s2disasm/s2.asm:1769), then the object pass
     * and its player display DPLC submissions (docs/s2disasm/s2.asm:5095
     * RunObjects, reaching LoadSonicDynPLC at docs/s2disasm/s2.asm:38828 and
     * LoadTailsDynPLC at docs/s2disasm/s2.asm:41658).
     *
     * <p>No row is published, so the work this iteration produces is
     * forwarded onto the last published row when the segment closes. Reads
     * nothing from the trace: the content is decided entirely by the engine's
     * own pending transfers and its native animation advance. Must only run
     * when the replay reached the end of the trace.
     */
    default void runTerminalDynamicArtIteration() {
        gameplayMode().serviceTerminalDynamicArtVBlank();
        advancePlayableAnimationsOnly();
        // RunObjects also executes the fixed in-level slots that follow the
        // dynamic object RAM, so the trailing iteration must run Tails' tails
        // (Obj05) too: Obj05_Main (docs/s2disasm/s2.asm:41723) reaches
        // .display (docs/s2disasm/s2.asm:41760) and unconditionally runs
        // Tails_Animate_Part2 then LoadTailsTailsDynPLC
        // (docs/s2disasm/s2.asm:41762-41763, subroutine at
        // docs/s2disasm/s2.asm:41637) before DisplaySprite
        // (docs/s2disasm/s2.asm:41764). The playable prefix only covers
        // Obj01/Obj02, so without this the boundary drops Obj05's DPLC
        // submission. Must run AFTER the prefix: Obj05 reads its parent's
        // anim at its own late execution point.
        advancePlayableFixedSlotsOnly();
    }

    /** Run one gameplay tick using the next BK2 input. Returns the mask. */
    int stepFrameFromRecording();

    /** Advance BK2 without stepping gameplay (lag frame). Returns the mask. */
    int skipFrameFromRecording();

    /** Advance only the playable animation slice proven by a native mid-loop trace hook. */
    default void advancePlayableAnimationsOnly() {
        gameplayMode().getSpriteManager().advancePlayableSlotPrefix();
    }

    /**
     * Advance the playable FIXED in-level object slots that ROM
     * {@code RunObjects} executes after every dynamic object — Tails' tails
     * (Obj05), whose routine tail submits its own DPLC
     * (docs/s2disasm/s2.asm:41760-41764).
     */
    default void advancePlayableFixedSlotsOnly() {
        gameplayMode().getSpriteManager()
                .advanceTailsTailsAfterObjectExecution();
    }

    /** Hold the first CPU sidekick's next Animate dispatch while running the full tick. */
    void suppressFirstSidekickAnimationOnce();

    /** Consume one BK2 frame without stepping gameplay or timing counters. Returns the mask. */
    int consumeRecordingFrameInputOnly();

    /** Advance the BK2 cursor by N frames, no gameplay ticks. */
    void advanceRecordingCursor(int frameCount);

    /**
     * Returns the BK2 input mask at the given offset from the current cursor
     * without advancing the cursor or mutating gameplay state. Offset 0 is
     * the next frame {@link #stepFrameFromRecording} would consume; negative
     * offsets read prior BK2 frames (e.g. the last title-card frame at
     * offset -1). Returns -1 when no BK2 movie is loaded or the requested
     * frame is out of range.
     */
    default int peekRecordingInputAt(int offset) {
        return -1;
    }
}
