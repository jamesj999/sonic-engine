package com.openggf;

import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.GameMode;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.recording.UserRecordingRuntimeControls;
import com.openggf.game.recording.UserRecordingStopReason;
import com.openggf.game.recording.menu.UserRecordingMenu;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.SeamlessLevelTransitionRequest;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Owns level/title admission and deferred seamless-boundary completion. */
final class LevelIterationAdmissionController {
    private boolean seamlessBoundaryCompletionPending;
    private int lastAppliedPlaybackFrame = -1;

    LevelFrameResult admit(
            GameMode mode,
            BooleanSupplier updateTitleCard,
            Supplier<LevelFrameResult> titleReleaseResult,
            LevelManager levelManager,
            GameplayModeContext gameplayMode,
            boolean startEdge,
            UserRecordingRuntimeControls recordingControls,
            Runnable startPendingTitleCard,
            Runnable activateRepresentedHardwareTiming,
            Runnable deactivateHardwareTimingGap) {
        if (mode == GameMode.TITLE_CARD) {
            deactivateHardwareTimingGap.run();
            if (!updateTitleCard.getAsBoolean()) {
                return LevelFrameResult.SETUP_ONLY;
            }
            LevelFrameResult releaseResult = titleReleaseResult.get();
            if (TraceSessionLauncher.claimTitleCardControlReleaseBarrierIfActive()) {
                return LevelFrameResult.SETUP_ONLY;
            }
            if (releaseResult == LevelFrameResult.GAMEPLAY_FRAME) {
                activateRepresentedHardwareTiming.run();
            }
            return releaseResult;
        }
        if (mode != GameMode.LEVEL) {
            activateRepresentedHardwareTiming.run();
            return LevelFrameResult.GAMEPLAY_FRAME;
        }
        SeamlessLevelTransitionRequest request =
                levelManager.consumeSeamlessTransitionRequest();
        if (request != null) {
            deactivateHardwareTimingGap.run();
            recordingControls.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
            TraceSessionLauncher.markNextRunLevelLoadCause(
                    com.openggf.trace.replay.runs.RunLevelLoadCause.LEVEL_ADVANCE);
            levelManager.applySeamlessTransition(request);
            startPendingTitleCard.run();
            seamlessBoundaryCompletionPending = true;
        } else {
            activateRepresentedHardwareTiming.run();
        }
        return LevelFrameStep.admit(
                LevelFrameContext.from(gameplayMode), levelManager, startEdge).result();
    }

    boolean completePendingBoundary(
            boolean doFrameStep,
            java.util.function.Consumer<Boolean> updateAudio,
            Runnable finishPlayback,
            Supplier<GameplayModeContext> gameplayContext) {
        if (!seamlessBoundaryCompletionPending) {
            return false;
        }
        seamlessBoundaryCompletionPending = false;
        updateAudio.accept(doFrameStep);
        finishPlayback.run();
        TraceSessionLauncher traceSession = TraceSessionLauncher.active();
        if (traceSession != null) {
            traceSession.recordExternalRewindFrameAtBoundary();
        } else {
            GameplayModeContext context = gameplayContext.get();
            if (context != null) {
                context.markRewindBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);
            }
        }
        return true;
    }

    void reset() {
        seamlessBoundaryCompletionPending = false;
    }

    void finishPlaybackBoundary(
            boolean advance,
            PlaybackDebugManager playback,
            UserRecordingRuntimeControls recordingControls) {
        if (TraceSessionLauncher.isRunFrameDriverActive()) {
            lastAppliedPlaybackFrame = playback.getCursorFrame();
            return;
        }
        int appliedFrame;
        if (advance) {
            appliedFrame = playback.getCursorFrame();
            lastAppliedPlaybackFrame = appliedFrame;
            playback.onLevelFrameAdvanced();
        } else {
            appliedFrame = lastAppliedPlaybackFrame;
            if (appliedFrame < 0) {
                return;
            }
        }
        recordingControls.afterPlaybackFrame(
                appliedFrame,
                true,
                isPlaybackMovieEnd(
                        appliedFrame,
                        playback.getMovieFrameCount(),
                        playback.isSessionPlaying()));
    }

    /**
     * Consumes one recorded row for a frame frozen by a level-to-level
     * transition fade. Every game's fade-out is {@code move.w #$15,d4} over a
     * {@code dbf} around a V-blank wait -- S3K {@code Pal_FadeToBlack}
     * (docs/skdisasm/sonic3k.asm:5042-5052), S2 {@code Pal_FadeToBlack}
     * (docs/s2disasm/s2.asm:3370-3382), S1 {@code PaletteFadeOut}
     * (docs/s1disasm/_inc/Palette Fading.asm:134-145, which spells the count
     * {@code 22-1}) -- so V_int, the recorder's row source, keeps ticking for
     * all 22 while gameplay is frozen, exactly as the bonus-exit hold in
     * {@code GameLoop.updateBonusStageMode} already models.
     *
     * <p>The rows are only ours to consume while the span being compared still
     * holds rows the cursor has not reached. Where a recorder cut the segment
     * on its last live gameplay row, the fade rows fall in the driver-owned gap
     * between segments and consuming them would double-count. That question is
     * answered from each run's own recorded data by the row observer, never
     * from a game name, zone, route, or frame index -- and no fade length is
     * written down anywhere here.
     */
    void consumeTransitionFreezeRow(
            PlaybackDebugManager playback,
            ObjectManager objects,
            LevelFrameContext context,
            com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame frame) {
        if (!playback.observerHasUnconsumedRecordedRows()) {
            return;
        }
        if (objects != null) {
            LevelFrameStep.serviceHardwareVBlankOnly(context, frame);
            // V-blank-only row: see the exactly-one-tick-per-serviced-V-blank
            // invariant on ObjectManager.vblaCounter.
            objects.advanceVblaCounter();
        }
        playback.onLevelFrameAdvanced();
    }

    void advanceTraceRunPhysicalRow(
            PlaybackDebugManager playback,
            UserRecordingRuntimeControls recordingControls,
            TraceSessionLauncher session) {
        int appliedFrame = playback.getCursorFrame();
        lastAppliedPlaybackFrame = appliedFrame;
        playback.onLevelFrameAdvanced();
        recordingControls.afterPlaybackFrame(
                appliedFrame, false,
                isPlaybackMovieEnd(
                        appliedFrame, playback.getMovieFrameCount(),
                        playback.isSessionPlaying()));
        if (session != null) {
            session.recordExternalRewindFrame();
        }
    }

    void setLastAppliedPlaybackFrame(int frame) {
        lastAppliedPlaybackFrame = frame;
    }

    void resetLastAppliedPlaybackFrame() {
        lastAppliedPlaybackFrame = -1;
    }

    UserRecordingMenu.PlaybackStarter withAppliedPlaybackFrameReset(
            UserRecordingMenu.PlaybackStarter starter) {
        Objects.requireNonNull(starter, "starter");
        return (entry, options) -> {
            resetLastAppliedPlaybackFrame();
            starter.start(entry, options);
        };
    }

    static void driveTraceRunSession(GameMode mode, int cursorFrame) {
        TraceSessionLauncher session = TraceSessionLauncher.active();
        if (session != null) {
            session.runAdvanceTickIfActive(mode, cursorFrame);
        }
    }

    /** Runs one host step and guarantees all-mode run observation afterward. */
    static void runTraceObservedStep(
            Runnable step, Supplier<GameMode> mode, IntSupplier cursorFrame) {
        try {
            step.run();
        } finally {
            driveTraceRunSession(mode.get(), cursorFrame.getAsInt());
        }
    }

    static boolean shouldVisualTraceOwnEscape(
            GameMode mode, TraceSessionLauncher session, boolean escapePressed) {
        return session != null && escapePressed
                && (mode == GameMode.LEVEL || session.isRunSession()
                        || session.isPresentingTitleCard());
    }

    static void prepareTraceHardwareTimingForAdmission(GameMode mode) {
        TraceSessionLauncher session = TraceSessionLauncher.active();
        if (session != null) {
            session.prepareHardwareTimingForAdmission(mode);
        }
    }

    static void refreshTraceInputSnapshot(com.openggf.control.InputHandler input) {
        input.refreshLogicalSnapshot();
    }

    private static void admitTraceRunDestination(GameMode mode) {
        TraceSessionLauncher.admitRunDestinationBeforeProductionIfActive(mode);
    }

    static void prepareTraceRunAdmissionAndHardwareTiming(
            GameMode mode, Runnable syncPlaybackInput) {
        admitTraceRunDestination(mode);
        syncPlaybackInput.run();
        prepareTraceHardwareTimingForAdmission(mode);
    }

    static void deactivateTraceHardwareTimingForAdmission() {
        TraceSessionLauncher session = TraceSessionLauncher.active();
        if (session != null) {
            session.deactivateHardwareTimingForAdmission();
        }
    }

    static boolean isPlaybackMovieEnd(
            int appliedFrame, int movieFrameCount, boolean sessionPlaying) {
        return movieFrameCount > 0
                && !sessionPlaying
                && appliedFrame >= movieFrameCount - 1;
    }

    static SpecialStageStartupPolicy specialStageStartupPolicy(boolean playbackActive) {
        return playbackActive
                ? SpecialStageStartupPolicy.TRACE_ACCURATE
                : SpecialStageStartupPolicy.FAST;
    }
}
