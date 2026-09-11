package com.openggf.level;

import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.CheckpointState;
import com.openggf.game.GameModule;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.RespawnState;

/**
 * Owns checkpoint/respawn state and its post-load restore hooks.
 */
final class LevelCheckpointCoordinator {
    private final LevelManager levelManager;
    private RespawnState checkpointState;

    LevelCheckpointCoordinator(LevelManager levelManager) {
        this.levelManager = levelManager;
    }

    void prepareForLevelStart() {
        if (checkpointState == null) {
            checkpointState = levelManager.gameModule.createRespawnState();
        }
        checkpointState.clear();
    }

    void clear() {
        if (checkpointState != null) {
            checkpointState.clear();
        }
    }

    RespawnState state() {
        return checkpointState;
    }

    void resetState() {
        checkpointState = null;
    }

    CheckpointState.RewindState captureRewindState() {
        return checkpointState instanceof CheckpointState cs ? cs.captureRewindState() : null;
    }

    void restoreRewindState(CheckpointState.RewindState checkpointRewindState) {
        if (checkpointRewindState == null) {
            return;
        }
        if (checkpointState == null && levelManager.gameModule != null) {
            checkpointState = levelManager.gameModule.createRespawnState();
        }
        if (checkpointState instanceof CheckpointState cs) {
            cs.restoreRewindState(checkpointRewindState);
        }
    }

    /**
     * ROM: S1 Lamp_LoadInfo, S2 Obj79_LoadData, S3K Saved_zone_and_act restore.
     */
    void restoreCheckpointState(LevelLoadContext ctx) {
        if (!ctx.hasCheckpoint() || checkpointState == null) {
            return;
        }
        checkpointState.restoreFromSaved(
                ctx.getCheckpointX(), ctx.getCheckpointY(),
                ctx.getCheckpointCameraX(), ctx.getCheckpointCameraY(),
                ctx.getCheckpointIndex());
        if (checkpointState instanceof CheckpointState cs) {
            if (ctx.hasWaterState()) {
                cs.saveWaterState(ctx.getCheckpointWaterLevel(), ctx.getCheckpointWaterRoutine());
            }
            if (ctx.hasCheckpointS3kRuntimeState()) {
                cs.saveS3kRuntimeState(
                        ctx.getCheckpointCameraMaxY(),
                        ctx.getCheckpointDynamicResizeRoutine());
            }
            if (ctx.hasCheckpointSolidBits()) {
                cs.saveSolidBits(ctx.getCheckpointTopSolidBit(), ctx.getCheckpointLrbSolidBit());
            }
            if (ctx.hasCheckpointTimer()) {
                cs.saveActTimer(ctx.getCheckpointTimerFrames());
            }
        }
        levelManager.waterCoordinator.restoreWaterFromCheckpoint(ctx);
    }

    void restoreRuntimeState(LevelLoadContext ctx) {
        if (!ctx.hasCheckpoint() || !ctx.hasCheckpointS3kRuntimeState()) {
            return;
        }

        levelManager.camera.setMaxY((short) ctx.getCheckpointCameraMaxY());
        levelManager.camera.setMaxYTarget((short) ctx.getCheckpointCameraMaxY());

        GameModule module = levelManager.activeGameModule();
        LevelEventProvider levelEvents = module.getLevelEventProvider();
        if (levelEvents instanceof AbstractLevelEventManager eventManager) {
            eventManager.restoreEventRoutineState(
                    ctx.getCheckpointDynamicResizeRoutine(),
                    eventManager.getEventRoutineBg());
        }
    }
}
