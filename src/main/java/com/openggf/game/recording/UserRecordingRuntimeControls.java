package com.openggf.game.recording;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;

import java.util.Objects;

/**
 * Testable runtime control policy for user recording and playback.
 */
public final class UserRecordingRuntimeControls {
    public static final int RECORD_HOLD_FRAMES = 60;

    private final Runtime runtime;
    private int recordHoldFrames;
    private boolean recordHoldTriggered;

    public UserRecordingRuntimeControls(Runtime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void updateLevelControlInput(InputHandler input) {
        if (input == null
                || runtime.currentGameMode() != GameMode.LEVEL
                || runtime.traceOrDebugSurfaceOwnsRecordingInput()) {
            resetRecordHold();
            return;
        }

        int recordKey = runtime.recordKey();
        if (runtime.hasActiveRecording()
                && input.isKeyPressed(recordKey)
                && !input.isShiftDown()) {
            resetRecordHold();
            runtime.stopActiveRecording(UserRecordingStopReason.USER_STOPPED);
            return;
        }

        if (runtime.hasActiveRecording()) {
            resetRecordHold();
            return;
        }

        if (input.isShiftDown() && input.isKeyDown(recordKey)) {
            if (recordHoldFrames < RECORD_HOLD_FRAMES) {
                recordHoldFrames++;
            }
            if (recordHoldFrames == RECORD_HOLD_FRAMES && !recordHoldTriggered) {
                recordHoldTriggered = true;
                runtime.beginRecordingFromCurrentLevel();
            }
            return;
        }

        resetRecordHold();
    }

    public void beforeLevelFrame(InputHandler input) {
        if (runtime.hasActiveRecording()) {
            runtime.beforeActiveRecordingLevelFrame(input);
        }
    }

    public void afterLevelFrame() {
        if (runtime.hasActiveRecording()) {
            runtime.afterActiveRecordingLevelFrame();
        }
    }

    public void stopActiveRecording(UserRecordingStopReason reason) {
        if (runtime.hasActiveRecording()) {
            runtime.stopActiveRecording(reason);
        }
    }

    public void afterPlaybackFrame(int currentMovieFrame, boolean levelEnded, boolean movieEnded) {
        UserRecordingPlaybackOptions options = runtime.activePlaybackOptions();
        if (options == null || runtime.activePlaybackState() != UserRecordingPlaybackState.PLAYING) {
            return;
        }

        UserRecordingPlaybackState next = new UserRecordingPlaybackController(options)
                .afterFrame(currentMovieFrame, runtime.playbackHasDesynced(), levelEnded, movieEnded);
        if (next == UserRecordingPlaybackState.PLAYING) {
            return;
        }

        runtime.updatePlaybackState(next);
        runtime.pauseEngineForPlayback();
    }

    public boolean handlePlaybackTakeoverRequest() {
        UserRecordingPlaybackState state = runtime.activePlaybackState();
        if (state != UserRecordingPlaybackState.PAUSED_AT_TARGET
                && state != UserRecordingPlaybackState.PAUSED_ON_DESYNC
                && state != UserRecordingPlaybackState.PAUSED_AT_COMPLETION) {
            return false;
        }
        runtime.endPlaybackDebugSession();
        runtime.updatePlaybackState(UserRecordingPlaybackState.STOPPED);
        return true;
    }

    public boolean shouldSuppressSceneRendering() {
        UserRecordingPlaybackOptions options = runtime.activePlaybackOptions();
        return options != null
                && options.fastForward()
                && runtime.activePlaybackState() == UserRecordingPlaybackState.PLAYING;
    }

    public boolean shouldPumpFastForward() {
        return shouldSuppressSceneRendering() && runtime.currentGameMode() == GameMode.LEVEL;
    }

    public UserRecordingHudState hudState() {
        if (recordHoldFrames > 0 && !runtime.hasActiveRecording()) {
            return UserRecordingHud.holdPromptState(recordHoldFrames, RECORD_HOLD_FRAMES);
        }
        UserRecordingHudState activeHud = runtime.activeRecordingHudState();
        if (activeHud != null) {
            return activeHud;
        }
        UserRecordingPlaybackOptions options = runtime.activePlaybackOptions();
        UserRecordingPlaybackState playbackState = runtime.activePlaybackState();
        if (options != null
                && (playbackState == UserRecordingPlaybackState.PLAYING
                || playbackState == UserRecordingPlaybackState.PAUSED_ON_DESYNC)) {
            int frame = runtime.currentPlaybackFrame();
            UserRecordingVerificationResult verification = runtime.activePlaybackVerificationResult();
            return new UserRecordingHudState(
                    true,
                    options.fastForward()
                            ? "PLAYBACK FF " + frame + "/" + runtime.playbackFrameCount()
                            : "PLAYBACK " + frame + "/" + runtime.playbackFrameCount(),
                    verification == null ? "" : "VERIFY " + verification.status(),
                    frame,
                    verification != null && !verification.clean(),
                    false);
        }
        return UserRecordingHudState.hidden();
    }

    int recordHoldFrames() {
        return recordHoldFrames;
    }

    private void resetRecordHold() {
        recordHoldFrames = 0;
        recordHoldTriggered = false;
    }

    public interface Runtime {
        int recordKey();

        GameMode currentGameMode();

        boolean traceOrDebugSurfaceOwnsRecordingInput();

        boolean hasActiveRecording();

        void beginRecordingFromCurrentLevel();

        void stopActiveRecording(UserRecordingStopReason reason);

        default void beforeActiveRecordingLevelFrame(InputHandler input) {
        }

        default void afterActiveRecordingLevelFrame() {
        }

        default UserRecordingHudState activeRecordingHudState() {
            return null;
        }

        default UserRecordingPlaybackOptions activePlaybackOptions() {
            return null;
        }

        default UserRecordingPlaybackState activePlaybackState() {
            return UserRecordingPlaybackState.STOPPED;
        }

        default boolean playbackHasDesynced() {
            return false;
        }

        default UserRecordingVerificationResult activePlaybackVerificationResult() {
            return null;
        }

        default int currentPlaybackFrame() {
            return 0;
        }

        default int playbackFrameCount() {
            return 0;
        }

        default void updatePlaybackState(UserRecordingPlaybackState state) {
        }

        default void pauseEngineForPlayback() {
        }

        default void endPlaybackDebugSession() {
        }
    }
}
