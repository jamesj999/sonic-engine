package com.openggf.game;

import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;

/**
 * Per-game owner of the work the player's death routine does on the frame the
 * life subtraction reaches zero or a time over is already flagged: load the
 * GAME OVER / TIME OVER card object pair, play the game over music and queue
 * the card's art.
 *
 * <p>S1 {@code Sonic_HandleDeath} (docs/s1disasm/_incObj/01 Sonic.asm:2019-2049),
 * S2 {@code CheckGameOver} (docs/s2disasm/s2.asm:38284-38316) and S3K
 * {@code loc_12432} (docs/skdisasm/sonic3k.asm:24588-24616). The shared death
 * code decides <em>whether</em> this frame is a game over or time over; the
 * provider owns the per-game object slots, PLC/queue and music ids.
 */
public interface GameOverFlowProvider {

    /**
     * Spawns the card pair for the current level.
     *
     * @param services the level's object services
     * @param timeOver {@code true} for the TIME OVER pair (lives remain but the
     *                 time-over flag was set), {@code false} for GAME OVER
     */
    void beginGameOverCard(ObjectServices services, boolean timeOver);

    /**
     * The death routine's call site: resolves the level's game module provider
     * and object services.
     *
     * @return {@code false} when the game has no game-over flow, in which case
     *         the corpse is merely held as before
     */
    static boolean begin(LevelManager levelManager, boolean timeOver) {
        GameModule module = levelManager != null ? levelManager.getGameModule() : null;
        GameOverFlowProvider provider = module != null ? module.getGameOverFlowProvider() : null;
        ObjectManager objects = levelManager != null ? levelManager.getObjectManager() : null;
        if (provider == null || objects == null || objects.getObjectServices() == null) {
            return false;
        }
        provider.beginGameOverCard(objects.getObjectServices(), timeOver);
        return true;
    }
}
