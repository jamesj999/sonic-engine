package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.level.objects.DefaultPowerUpSpawner;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.PersistentRespawnState;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.level.rings.RingManager;
import com.openggf.audio.AudioManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

final class LevelManagerInitializationSupport {
    private LevelManagerInitializationSupport() {
    }

    static void rebindPowerUpSpawners(
            ObjectManager objectManager,
            SpriteManager spriteManager,
            String mainCharacterCode) {
        DefaultPowerUpSpawner spawner = new DefaultPowerUpSpawner(objectManager);
        Sprite player = spriteManager.getSprite(mainCharacterCode);
        if (player instanceof AbstractPlayableSprite playable) {
            playable.setPowerUpSpawner(spawner);
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
            sidekick.setPowerUpSpawner(spawner);
        }
    }

    static void resetCameraBounds(
            Camera camera,
            Level level,
            ObjectManager objectManager,
            PersistentRespawnState persistentRespawnState) {
        camera.setFrozen(false);
        camera.setMinX((short) level.getMinX());
        camera.setMaxX((short) level.getMaxX());
        objectManager.reset(camera.getX(), persistentRespawnState);
    }

    /**
     * @param keptRingStatusTable ROM {@code Ring_status_table} preserved across
     *     a reload the ROM performed with {@code Respawn_table_keep} set, or
     *     {@code null} for an ordinary load that starts with a clean table.
     *     Applied immediately after {@link RingManager#reset(int)} -- which is
     *     the engine's equivalent of {@code sub_EB1A}'s wipe -- and before any
     *     ring can be drawn or touched.
     * @see com.openggf.level.LevelTransitionCoordinator#bigRingReturnRingStatusTable()
     */
    static RingManager initializeRings(
            LevelManager levelManager,
            TouchResponseTable touchResponseTable,
            AudioManager audioManager,
            Camera camera,
            GraphicsManager graphicsManager,
            long[] keptRingStatusTable) {
        Level level = levelManager.getCurrentLevel();
        RingManager rings = new RingManager(
                level.getRings(), level.getRingSpriteSheet(), levelManager, touchResponseTable, audioManager);
        rings.reset(camera.getX());
        rings.restoreRingStatusTable(keptRingStatusTable);
        rings.ensurePatternsCached(graphicsManager, level.getPatternCount());
        var gameplayMode = com.openggf.game.session.SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.registerRingAdapter(rings);
        }
        return rings;
    }
}
