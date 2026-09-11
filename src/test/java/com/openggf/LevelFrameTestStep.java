package com.openggf;

import com.openggf.camera.Camera;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.level.LevelManager;

/**
 * Test-only convenience around the canonical phase-aware production API.
 * Tests unrelated to PLC ownership may use the ordinary defaults here without
 * reopening a no-phase production entry point.
 */
public final class LevelFrameTestStep {
    private LevelFrameTestStep() {
    }

    public static LevelFrameResult execute(
            LevelFrameContext context, LevelManager level, Camera camera,
            Runnable spriteUpdate) {
        return execute(context, level, camera, spriteUpdate,
                LevelFrameStep.DIRECT_WRAPPER);
    }

    public static LevelFrameResult execute(
            LevelFrameContext context, LevelManager level, Camera camera,
            Runnable spriteUpdate, LevelFrameStep.StepWrapper wrapper) {
        var frame = frame(context);
        LevelFrameResult result = LevelFrameStep.execute(
                context, frame, PlcLifecyclePhase.ORDINARY_LEVEL,
                level, camera, spriteUpdate, wrapper);
        frame.finish();
        return result;
    }

    public static LevelFrameResult executeWithPause(
            LevelFrameContext context, LevelManager level, Camera camera,
            Runnable spriteUpdate, boolean startEdge,
            LevelFrameStep.StepWrapper wrapper) {
        var frame = frame(context);
        LevelFrameResult result = LevelFrameStep.executeWithPause(
                context, frame, PlcLifecyclePhase.ORDINARY_LEVEL,
                PlcLifecyclePhase.NORMAL_PAUSE, level, camera, spriteUpdate,
                startEdge, wrapper);
        frame.finish();
        return result;
    }

    public static void serviceVBlankOnly(LevelFrameContext context) {
        var frame = frame(context);
        LevelFrameStep.serviceVBlankOnly(
                context, frame, PlcLifecyclePhase.LAG);
        frame.finish();
    }

    public static void executeHardwareTimedObjectScan(
            LevelFrameContext context, Runnable scan) {
        var frame = frame(context);
        LevelFrameStep.executeHardwareTimedObjectScan(
                context, frame, PlcLifecyclePhase.SPECIAL_STAGE, scan);
        frame.finish();
    }

    private static PlcFrameLifecycleCoordinator.PlcLifecycleFrame frame(
            LevelFrameContext context) {
        return new PlcFrameLifecycleCoordinator(context.gameModule())
                .latchBeforeFadeUpdate();
    }
}
