package com.openggf.game;

/**
 * Immutable snapshot of game state saved before entering a bonus stage.
 * Restored on exit so the player resumes at the checkpoint with correct
 * event routine state, camera position, collision path, and water height.
 * Mirrors ROM Saved_* variables plus the BigRingReturnState pattern.
 */
public record BonusStageState(
        int savedZoneAndAct,
        int savedApparentZoneAndAct,
        int savedRingCount,
        int savedExtraLifeFlags,
        int savedLastStarPostHit,
        int savedStatusSecondary,
        int dynamicResizeRoutineFg,
        int dynamicResizeRoutineBg,
        int playerX,
        int playerY,
        int cameraX,
        int cameraY,
        byte topSolidBit,
        byte lrbSolidBit,
        int cameraMaxY,
        long savedTimerFrames,
        int meanWaterLevel
) {
    public BonusStageState(
            int savedZoneAndAct,
            int savedApparentZoneAndAct,
            int savedRingCount,
            int savedExtraLifeFlags,
            int savedLastStarPostHit,
            int savedStatusSecondary,
            int dynamicResizeRoutineFg,
            int dynamicResizeRoutineBg,
            int playerX,
            int playerY,
            int cameraX,
            int cameraY,
            byte topSolidBit,
            byte lrbSolidBit,
            int cameraMaxY,
            long savedTimerFrames
    ) {
        this(savedZoneAndAct, savedApparentZoneAndAct, savedRingCount, savedExtraLifeFlags,
                savedLastStarPostHit, savedStatusSecondary, dynamicResizeRoutineFg,
                dynamicResizeRoutineBg, playerX, playerY, cameraX, cameraY, topSolidBit,
                lrbSolidBit, cameraMaxY, savedTimerFrames, 0);
    }
}
