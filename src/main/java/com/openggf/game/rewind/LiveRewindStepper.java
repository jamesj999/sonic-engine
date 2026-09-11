package com.openggf.game.rewind;

import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameResult;
import com.openggf.LevelFrameStep;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.GameServices;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.session.GameplayModeContext;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Replays one recorded live-input row through the normal level frame step.
 */
final class LiveRewindStepper implements RewindSeekAwareEngineStepper {

    private final LiveRewindInputSource inputs;
    private final Supplier<InputHandler> inputHandlerSupplier;
    private final Supplier<LevelFrameContext> frameContextSupplier;
    private final Supplier<GameplayModeContext> gameplayModeSupplier;

    LiveRewindStepper(LiveRewindInputSource inputs,
                      Supplier<InputHandler> inputHandlerSupplier,
                      Supplier<LevelFrameContext> frameContextSupplier,
                      Supplier<GameplayModeContext> gameplayModeSupplier) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.inputHandlerSupplier = Objects.requireNonNull(inputHandlerSupplier, "inputHandlerSupplier");
        this.frameContextSupplier = Objects.requireNonNull(frameContextSupplier, "frameContextSupplier");
        this.gameplayModeSupplier = Objects.requireNonNull(gameplayModeSupplier, "gameplayModeSupplier");
    }

    @Override
    public LevelFrameResult step(Bk2FrameInput input) {
        var sprites = GameServices.spritesOrNull();
        var level = GameServices.levelOrNull();
        var camera = GameServices.cameraOrNull();
        if (sprites == null || level == null || camera == null) {
            return LevelFrameResult.PAUSED;
        }
        InputHandler liveInput = inputHandlerSupplier.get();
        if (liveInput == null) {
            return LevelFrameResult.PAUSED;
        }
        var gameplayMode = gameplayModeSupplier.get();
        if (gameplayMode == null) {
            return LevelFrameResult.PAUSED;
        }
        return gameplayMode.plcFrameLifecycle().runReplayedLogicalIteration(
                gameplayMode.getFadeManager()::update,
                frame -> step(input, sprites, level, camera, liveInput, frame));
    }

    LevelFrameResult step(
            Bk2FrameInput input,
            com.openggf.sprites.managers.SpriteManager sprites,
            com.openggf.level.LevelManager level,
            com.openggf.camera.Camera camera,
            InputHandler liveInput,
            PlcLifecycleFrame lifecycleFrame) {
        LevelFrameContext context = frameContextSupplier.get();
        var admission = LevelFrameStep.admit(
                context, level, input.p1StartPressed());
        if (admission.result() == LevelFrameResult.PAUSED) {
            LevelFrameStep.serviceVBlankOnly(
                    context, lifecycleFrame, PlcLifecyclePhase.NORMAL_PAUSE);
            return admission.result();
        }
        if (admission.result() == LevelFrameResult.SETUP_ONLY) {
            return admission.result();
        }
        Bk2FrameInput previous =
                inputs.read(Math.max(inputs.earliestFrame(), input.frameIndex() - 1));
        liveInput.setLogicalOverride(RecordedInputSnapshots.fromBk2(input, previous));
        try {
            sprites.publishHeldInputForLevelEvents(liveInput);
            return LevelFrameStep.execute(
                    context, lifecycleFrame, PlcLifecyclePhase.ORDINARY_LEVEL,
                    level, camera, () -> sprites.update(liveInput),
                    LevelFrameStep.DIRECT_WRAPPER);
        } finally {
            liveInput.clearLogicalOverride();
        }
    }

    @Override
    public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
        // Live replay is fully driven by logical override snapshots, so no
        // persistent forced-input bridge needs priming after restore.
    }
}
