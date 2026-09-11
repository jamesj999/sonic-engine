package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.game.LevelState;
import com.openggf.game.ShieldType;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Immutable snapshot of player/camera state saved when entering a special
 * stage via a big ring (ROM: Save_Level_Data2 -> Saved2_* variables).
 * Restored on return so the player resumes at the ring location with
 * correct collision path, camera boundaries, and water height.
 */
public record BigRingReturnState(
        int playerX,
        int playerY,
        int cameraX,
        int cameraY,
        int rings,
        byte topSolidBit,
        byte lrbSolidBit,
        int cameraMaxY,
        int dynamicResizeRoutine,
        int meanWaterLevel,
        ShieldType savedShieldType,
        /**
         * ROM {@code Saved2_timer}: the whole {@code Timer} longword copied by
         * {@code Save_Level_Data2} (docs/skdisasm/sonic3k.asm:61745) when the
         * giant ring's entry flash hands off to the special stage. Held as the
         * engine's level-timer frame count.
         */
        long savedTimerFrames
) {
    public BigRingReturnState(
            int playerX,
            int playerY,
            int cameraX,
            int cameraY,
            int rings,
            byte topSolidBit,
            byte lrbSolidBit,
            int cameraMaxY,
            int dynamicResizeRoutine
    ) {
        this(playerX, playerY, cameraX, cameraY, rings, topSolidBit, lrbSolidBit,
                cameraMaxY, dynamicResizeRoutine, 0, null, 0L);
    }

    public BigRingReturnState(
            int playerX,
            int playerY,
            int cameraX,
            int cameraY,
            int rings,
            byte topSolidBit,
            byte lrbSolidBit,
            int cameraMaxY,
            int dynamicResizeRoutine,
            int meanWaterLevel
    ) {
        this(playerX, playerY, cameraX, cameraY, rings, topSolidBit, lrbSolidBit,
                cameraMaxY, dynamicResizeRoutine, meanWaterLevel, null, 0L);
    }

    /**
     * The full S3K {@code Save_Level_Data2} snapshot, including the level timer
     * (docs/skdisasm/sonic3k.asm:61732-61752).
     */
    public BigRingReturnState(
            int playerX,
            int playerY,
            int cameraX,
            int cameraY,
            int rings,
            byte topSolidBit,
            byte lrbSolidBit,
            int cameraMaxY,
            int dynamicResizeRoutine,
            int meanWaterLevel,
            long savedTimerFrames
    ) {
        this(playerX, playerY, cameraX, cameraY, rings, topSolidBit, lrbSolidBit,
                cameraMaxY, dynamicResizeRoutine, meanWaterLevel, null,
                savedTimerFrames);
    }

    /**
     * Returns a copy with the engine's semantic representation of the ROM
     * {@code Saved2_status_secondary} shield bits attached.
     */
    public BigRingReturnState withSavedShieldType(ShieldType shieldType) {
        if (savedShieldType == shieldType) {
            return this;
        }
        return new BigRingReturnState(
                playerX, playerY, cameraX, cameraY, rings,
                topSolidBit, lrbSolidBit, cameraMaxY, dynamicResizeRoutine,
                meanWaterLevel, shieldType, savedTimerFrames);
    }

    /**
     * Restores all saved state onto the player, camera, and game state.
     * Mirrors ROM Load_Starpost_Settings2 (s3.asm:22082-22087).
     *
     * <p>Note: {@link #dynamicResizeRoutine} must be restored separately
     * by the caller via the level event manager, since this record does
     * not have access to the event system.
     */
    public void restoreToPlayer(AbstractPlayableSprite player, Camera camera, LevelState levelState) {
        player.setCentreX((short) playerX);
        player.setCentreY((short) playerY);
        camera.setX((short) cameraX);
        camera.setY((short) cameraY);
        // ROM: Load_Starpost_Settings2 writes Saved2_camera_max_Y_pos before
        // Get_LevelSizeStart computes Camera_X/Y from the restored player
        // position (skdisasm/sonic3k.asm:61834-61837, 38172-38178). Applying
        // maxY after the forced calculation leaves a return below the level's
        // initial boundary one camera update behind the ROM.
        camera.setMaxY((short) cameraMaxY);
        camera.updatePosition(true);
        if (levelState != null) {
            levelState.setRings(rings);
            levelState.setTimerFrames(restoredTimerFrames());
        }
        player.setTopSolidBit(topSolidBit);
        player.setLrbSolidBit(lrbSolidBit);
        if (savedShieldType != null && savedShieldType != ShieldType.BASIC) {
            // SpawnLevelMainSprites_SpawnPowerup restores only the elemental
            // shield bits from Saved2_status_secondary (skdisasm/sonic3k.asm:
            // 8303-8345). BASIC is not part of this S3K return contract.
            player.giveShield(savedShieldType);
        }
    }

    /**
     * The level-timer frame count {@code loc_2D2C2} leaves behind
     * (docs/skdisasm/sonic3k.asm:61803-61805).
     *
     * <pre>
     *   move.l  (Saved2_timer).w,(Timer).w
     *   move.b  #60-1,(Timer_frame).w
     *   subq.b  #1,(Timer_second).w
     * </pre>
     *
     * <p>The restore keeps the saved minute and second, then discards the saved
     * sub-second phase: {@code Timer_frame} is forced to 59 and the second is
     * stepped back one, so the very next timer tick rolls {@code Timer_frame}
     * over and puts the second straight back where it was saved. Expressed
     * against the engine's frame count that is {@code savedSeconds * 60 - 1}.
     *
     * <p>Clamped at zero. The ROM's {@code subq.b} on a zero second wraps that
     * byte to {@code $FF} for the single frame before the roll-over restores
     * it, which an unsigned frame count cannot represent; the frame after --
     * the only one anything reads -- is identical either way.
     */
    private long restoredTimerFrames() {
        long savedSeconds = savedTimerFrames / 60;
        return Math.max(0L, savedSeconds * 60L - 1L);
    }

    public void restoreToPlayer(AbstractPlayableSprite player, Camera camera, LevelState levelState,
                                WaterSystem waterSystem, int zoneId, int actId) {
        restoreToPlayer(player, camera, levelState);
        if (meanWaterLevel > 0 && waterSystem != null && waterSystem.hasWater(zoneId, actId)) {
            waterSystem.setWaterLevelDirect(zoneId, actId, meanWaterLevel);
            waterSystem.setWaterLevelTarget(zoneId, actId, meanWaterLevel);
        }
    }
}
