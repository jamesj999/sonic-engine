package com.openggf.tools;

import com.openggf.LevelFrameResult;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glFinish;

/**
 * The per-frame drive shared by the headless CLI replay drivers — trace capture
 * and trace benchmarking.
 *
 * <p>This exists because the capture tool has already been burned once by
 * reimplementing the frame drive: routing captures through the live
 * {@code GameLoop} playback path silently desynced them from the recorded
 * trajectory (AIZ rings 19 instead of 97 at the battleship loop). A second tool
 * hand-rolling the same phase rules would eventually drift the same way, and a
 * benchmark that quietly simulates a shorter route than it claims produces
 * numbers that look fine and mean nothing. One implementation, both callers.
 */
final class TraceReplayDrive {

    private TraceReplayDrive() {
    }

    /**
     * What one drive attempt did with the trace row it was given.
     *
     * @param consumedRow   false when the row must be retried (the engine spent
     *                      the frame on setup and never looked at it)
     * @param gameplayFrame true when a gameplay tick actually executed
     */
    record DriveOutcome(boolean consumedRow, boolean gameplayFrame) {
        static final DriveOutcome RETRY = new DriveOutcome(false, false);
        static final DriveOutcome CONSUMED_WITHOUT_GAMEPLAY = new DriveOutcome(true, false);
        static final DriveOutcome CONSUMED_GAMEPLAY = new DriveOutcome(true, true);
    }

    /**
     * Drives one trace frame using the same phase rules as the test S3K loop.
     * Reports whether the row was consumed and whether gameplay actually ran.
     */
    static DriveOutcome driveOneFrame(TraceData trace, RecordingFrameDriver frameDriver,
                                      TraceReplayBootstrap.ReplayStartState replayStart,
                                      TraceExecutionPhase phase, int driveTraceIndex) {
        frameDriver.beginTraceRow(
                driveTraceIndex, trace.getFrame(driveTraceIndex).frame());
        // The admitted-gameplay callback below begins palette ownership after
        // SETUP_ONLY classification but before the ordinary level step. The
        // headless replay driver omits this because trace tests never render;
        // capture needs it to prevent writes accumulating across rendered frames.
        if (phase == TraceExecutionPhase.VBLANK_ONLY
                || phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
            frameDriver.skipFrameFromRecording();
            if (phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
                frameDriver.advancePlayableAnimationsOnly();
            }
            // Mirror AbstractTraceReplayTest: the S3K complete-run handoff row
            // is skipped for comparison, but ROM ran a full LevelLoop on it and
            // incremented Level_frame_counter before Process_Sprites. Apply the
            // single counter tick so S3K Tails-CPU gates read ROM-visible
            // Level_frame_counter natively.
            if (driveTraceIndex == replayStart.startingTraceIndex()
                    && TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(trace)) {
                SpriteManager handoffSprites = GameServices.spritesOrNull();
                if (handoffSprites != null) {
                    handoffSprites.setFrameCounter(handoffSprites.getFrameCounter() + 1);
                }
            }
            return DriveOutcome.CONSUMED_WITHOUT_GAMEPLAY;
        }
        if (phase == TraceExecutionPhase.ADVANCE_ONLY) {
            frameDriver.consumeRecordingFrameInputOnly();
            return DriveOutcome.CONSUMED_WITHOUT_GAMEPLAY;
        }
        if (phase == TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD) {
            frameDriver.suppressFirstSidekickAnimationOnce();
        }
        if (TraceReplayBootstrap.shouldUsePreviousRecordingInputForTraceReplay(trace)) {
            frameDriver.stepFrameFromRecordingUsingPreviousInput(
                    TraceReplayDrive::beginPaletteFrame);
        } else {
            frameDriver.stepFrameFromRecording(TraceReplayDrive::beginPaletteFrame);
        }
        if (frameDriver.getLastFrameResult() == LevelFrameResult.SETUP_ONLY) {
            return DriveOutcome.RETRY;
        }
        return frameDriver.didLastFrameRunGameplay()
                ? DriveOutcome.CONSUMED_GAMEPLAY
                : DriveOutcome.CONSUMED_WITHOUT_GAMEPLAY;
    }

    private static void beginPaletteFrame() {
        PaletteOwnershipRegistry paletteRegistry =
                GameServices.paletteOwnershipRegistryOrNull();
        if (paletteRegistry != null) {
            paletteRegistry.beginFrame();
        }
    }

    /**
     * Renders the current LEVEL scene to the back buffer. Mirrors the default
     * LEVEL branch of {@code Engine.draw()}: clear with the level background
     * colour, draw level + sprites by priority, flush, and block until GL is
     * finished so the subsequent {@code glReadPixels} sees the completed frame.
     *
     * <p>The {@code glFinish} gets its own {@code render.gpu_wait} section
     * because it is where the GPU cost actually lands. The other {@code render.*}
     * sections measure the CPU-side work of building and submitting draw calls,
     * which returns as soon as the commands are queued; the blocking wait for the
     * GPU to finish them is a separate, usually larger, number. Left untimed it
     * sat inside the frame total while belonging to no section, so a benchmark's
     * sections visibly failed to add up to its frames.
     */
    static void renderFrame() {
        LevelManager levelManager = GameServices.level();
        GraphicsManager graphicsManager = GameServices.graphics();
        levelManager.setClearColor();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        levelManager.drawWithSpritePriority(GameServices.sprites());
        graphicsManager.flush();
        PerformanceProfiler profiler = GameServices.profiler();
        profiler.beginSection("render.gpu_wait");
        glFinish();
        profiler.endSection("render.gpu_wait");
    }

    /**
     * {@link TraceReplayFixture} view backed by a {@link RecordingFrameDriver},
     * so {@code TraceReplaySessionBootstrap} sees exactly the fixture surface the
     * test's {@code HeadlessTestFixture} provides (sprite, gameplay mode,
     * recording-cursor helpers).
     */
    static final class DriverFixture implements TraceReplayFixture {
        private final RecordingFrameDriver driver;
        private HardwareTimingReplayPort hardwareTimingReplayPort;
        private boolean hardwareTimingReplayClosed;

        DriverFixture(RecordingFrameDriver driver) {
            this.driver = driver;
        }

        @Override
        public com.openggf.sprites.playable.AbstractPlayableSprite sprite() {
            return driver.getSprite();
        }

        @Override
        public com.openggf.game.session.GameplayModeContext gameplayMode() {
            return SessionManager.getCurrentGameplayMode();
        }

        @Override
        public void installHardwareTimingReplay(HardwareTimingReplayPort replayPort) {
            if (hardwareTimingReplayPort != null) {
                throw new IllegalStateException("hardware timing replay is already installed");
            }
            hardwareTimingReplayPort = replayPort;
            hardwareTimingReplayClosed = false;
            TraceHardwareTimingBoundaryObserver observer =
                    new TraceHardwareTimingBoundaryObserver(replayPort);
            gameplayMode().getRewindRegistry().register(replayPort);
            gameplayMode().setHardwareTimingBoundaryObserver(observer);
            driver.installHardwareTimingReplayObserver(observer);
            gameplayMode().setHardwareTimingReplayCloseHook(
                    this::closeHardwareTimingReplayRun);
        }

        @Override
        public void beginTraceRow(int traceIndex, int rawFrame) {
            driver.beginTraceRow(traceIndex, rawFrame);
        }

        @Override
        public void enterHardwareTimingGap() {
            driver.enterHardwareTimingGap();
        }

        @Override
        public void verifyHardwareTimingSegmentEdges() {
            if (hardwareTimingReplayPort != null) {
                hardwareTimingReplayPort.verifySegmentEdges();
            }
        }

        @Override
        public void handoffHardwareTimingReplay(HardwareTimingSchedule nextSchedule) {
            if (hardwareTimingReplayPort != null) {
                hardwareTimingReplayPort.handoffTo(nextSchedule);
            }
        }

        @Override
        public void closeHardwareTimingReplayRun() {
            if (hardwareTimingReplayPort == null || hardwareTimingReplayClosed) {
                return;
            }
            hardwareTimingReplayClosed = true;
            try {
                hardwareTimingReplayPort.verifyRunComplete();
            } finally {
                driver.clearHardwareTimingReplayObserver();
                gameplayMode().setHardwareTimingBoundaryObserver(null);
                gameplayMode().getRewindRegistry()
                        .deregister(HardwareTimingReplayPort.REWIND_KEY);
                gameplayMode().clearHardwareTimingReplayCloseHook();
            }
        }

        @Override
        public void abortHardwareTimingReplayRun() {
            if (hardwareTimingReplayPort == null || hardwareTimingReplayClosed) {
                return;
            }
            hardwareTimingReplayClosed = true;
            driver.clearHardwareTimingReplayObserver();
            gameplayMode().setHardwareTimingBoundaryObserver(null);
            gameplayMode().getRewindRegistry()
                    .deregister(HardwareTimingReplayPort.REWIND_KEY);
            gameplayMode().clearHardwareTimingReplayCloseHook();
        }

        @Override
        public int stepFrameFromRecording() {
            return driver.stepFrameFromRecording();
        }

        @Override
        public int skipFrameFromRecording() {
            return driver.skipFrameFromRecording();
        }

        @Override
        public void suppressFirstSidekickAnimationOnce() {
            driver.suppressFirstSidekickAnimationOnce();
        }

        @Override
        public int consumeRecordingFrameInputOnly() {
            return driver.consumeRecordingFrameInputOnly();
        }

        @Override
        public void advanceRecordingCursor(int frameCount) {
            driver.advanceRecordingCursor(frameCount);
        }

        @Override
        public int peekRecordingInputAt(int offset) {
            return driver.peekRecordingInputAt(offset);
        }
    }
}
