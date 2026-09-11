package com.openggf.game;

import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.PersistentRespawnState;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Captures and restores the level-owned state that crosses a bonus-stage
 * boundary. Loading, provider lifecycle, presentation, audio, and game-mode
 * changes remain owned by the game loop.
 */
public final class BonusStageTransitionCoordinator {
    private PersistentRespawnState pendingRespawnState;

    public record EntryCapture(BonusStageState savedState,
                               int pendingStarPostActivationMark) {
    }

    public EntryCapture captureEntry(LevelManager levelManager,
                                     Camera camera,
                                     WaterSystem waterSystem,
                                     AbstractPlayableSprite playable,
                                     LevelEventProvider eventProvider,
                                     int savedShieldStatus) {
        int playerX = playable != null ? playable.getCentreX() : 0;
        int playerY = playable != null ? playable.getCentreY() : 0;
        byte topSolidBit = playable != null ? playable.getTopSolidBit() : 0;
        byte lrbSolidBit = playable != null ? playable.getLrbSolidBit() : 0;

        RespawnState checkpoint = levelManager.getCheckpointState();
        boolean checkpointActive = checkpoint != null && checkpoint.isActive();
        if (checkpointActive) {
            // ROM bonus return uses the star post's saved x_pos/y_pos rather
            // than the player's live centre when the collision fired.
            playerX = checkpoint.getSavedX();
            playerY = checkpoint.getSavedY();
        }

        int resizeFg = 0;
        int resizeBg = 0;
        if (eventProvider instanceof AbstractLevelEventManager eventManager) {
            resizeFg = eventManager.getEventRoutineFg();
            resizeBg = eventManager.getEventRoutineBg();
        }

        LevelState levelState = levelManager.getLevelGamestate();
        int ringCount = levelState != null ? levelState.getRings() : 0;
        long timerFrames = levelState != null ? levelState.getTimerFrames() : 0;
        int zoneAndAct = (levelManager.getCurrentZone() << 8) | levelManager.getCurrentAct();
        int apparentZoneAndAct =
                (levelManager.getCurrentZone() << 8) | levelManager.getApparentAct();

        BonusStageState savedState = new BonusStageState(
                zoneAndAct,
                apparentZoneAndAct,
                ringCount,
                0,
                // Obj_StarPost's committed bonus entry zeroes
                // Last_star_post_hit while retaining the respawn activation bit.
                0,
                savedShieldStatus,
                resizeFg,
                resizeBg,
                playerX,
                playerY,
                camera.getX(),
                camera.getY(),
                topSolidBit,
                lrbSolidBit,
                camera.getMaxY(),
                timerFrames,
                captureWaterLevel(levelManager, waterSystem));
        int activationMark = checkpointActive
                ? checkpoint.getStarPostActivationMark()
                : -1;
        ObjectManager objectManager = levelManager.getObjectManager();
        pendingRespawnState = objectManager != null
                ? objectManager.capturePersistentRespawn()
                : null;
        return new EntryCapture(savedState, activationMark);
    }

    public int captureInteriorExitRingCount(LevelManager levelManager,
                                            BonusStageState savedState) {
        LevelState levelState = levelManager.getLevelGamestate();
        if (levelState != null) {
            // ROM copies the interior's live Ring_count to Saved_ring_count at
            // exit; provider reward-ring bookkeeping must not be added again.
            return levelState.getRings();
        }
        return savedState != null ? savedState.savedRingCount() : 0;
    }

    /**
     * Transfers the captured respawn table to the level-load lifecycle before
     * the return level is loaded. The level manager consumes it during the new
     * object manager's reset, before initial placement materialization.
     */
    public void prepareReturnLoad(LevelManager levelManager) {
        levelManager.restorePersistentRespawnOnNextObjectReset(pendingRespawnState);
        pendingRespawnState = null;
    }

    public void restoreReturnState(LevelManager levelManager,
                                   Camera camera,
                                   WaterSystem waterSystem,
                                   AbstractPlayableSprite playable,
                                   LevelEventProvider eventProvider,
                                   BonusStageState savedState,
                                   int pendingStarPostActivationMark,
                                   int interiorExitRingCount,
                                   ShieldType shieldToRestore,
                                   BonusStageProvider.BonusStageRewards rewards,
                                   Runnable lifeAward) {
        if (savedState.savedLastStarPostHit() >= 0) {
            RespawnState checkpoint = levelManager.getCheckpointState();
            if (checkpoint != null) {
                checkpoint.restoreFromSaved(
                        savedState.playerX(), savedState.playerY(),
                        savedState.cameraX(), savedState.cameraY(),
                        savedState.savedLastStarPostHit());
                checkpoint.restoreStarPostActivationMark(pendingStarPostActivationMark);
            }
        }

        if (eventProvider instanceof AbstractLevelEventManager eventManager) {
            eventManager.restoreEventRoutineState(
                    savedState.dynamicResizeRoutineFg(),
                    savedState.dynamicResizeRoutineBg());
        }

        if (playable != null) {
            playable.setCentreX((short) savedState.playerX());
            playable.setCentreY((short) savedState.playerY());
            playable.setTopSolidBit(savedState.topSolidBit());
            playable.setLrbSolidBit(savedState.lrbSolidBit());
            playable.setXSpeed((short) 0);
            playable.setYSpeed((short) 0);
            playable.setGSpeed((short) 0);
            if (shieldToRestore != null) {
                playable.giveShield(shieldToRestore);
            }
            playable.setHighPriority(false);
            playable.setPriorityBucket(2);
        }

        camera.setX((short) savedState.cameraX());
        camera.setY((short) savedState.cameraY());
        camera.setMaxY((short) savedState.cameraMaxY());
        camera.updatePosition(true);
        restoreWaterLevel(levelManager, waterSystem, savedState.meanWaterLevel());

        LevelState levelState = levelManager.getLevelGamestate();
        if (levelState != null) {
            levelState.setRings(interiorExitRingCount);
            levelState.setTimerFrames(savedState.savedTimerFrames());
            levelState.resumeTimer();
        }

        if (rewards != null && lifeAward != null) {
            for (int i = 0; i < rewards.lives(); i++) {
                lifeAward.run();
            }
        }
    }

    private static int captureWaterLevel(LevelManager levelManager,
                                         WaterSystem waterSystem) {
        if (waterSystem == null) {
            return 0;
        }
        int zone = levelManager.getFeatureZoneId();
        int act = levelManager.getFeatureActId();
        return waterSystem.hasWater(zone, act)
                ? waterSystem.getWaterLevelY(zone, act)
                : 0;
    }

    private static void restoreWaterLevel(LevelManager levelManager,
                                          WaterSystem waterSystem,
                                          int meanWaterLevel) {
        if (meanWaterLevel <= 0 || waterSystem == null) {
            return;
        }
        int zone = levelManager.getFeatureZoneId();
        int act = levelManager.getFeatureActId();
        if (!waterSystem.hasWater(zone, act)) {
            return;
        }
        waterSystem.setWaterLevelDirect(zone, act, meanWaterLevel);
        waterSystem.setWaterLevelTarget(zone, act, meanWaterLevel);
    }
}
