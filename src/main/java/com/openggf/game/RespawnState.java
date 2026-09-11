package com.openggf.game;

import com.openggf.camera.Camera;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Interface for managing respawn state after player death.
 * Implementations store checkpoint data (position, camera state)
 * and restore it when the player respawns.
 */
public interface RespawnState {
    /**
     * Clear checkpoint state (called on level start/change).
     */
    void clear();

    /**
     * Returns true if a checkpoint has been activated.
     */
    boolean isActive();

    /**
     * Restore state after player death.
     * Restores player position, camera position, and clears rings.
     *
     * @param player The player sprite to restore
     * @param camera The camera to restore
     */
    void restoreToPlayer(AbstractPlayableSprite player, Camera camera);

    /**
     * Gets the last activated checkpoint index.
     * @return checkpoint index, or -1 if none active
     */
    int getLastCheckpointIndex();

    /**
     * The persistent star-post ACTIVATION mark: the highest star-post subtype
     * ever activated in the current life, independent of the mutable checkpoint
     * index ({@link #getLastCheckpointIndex()}).
     *
     * <p>This models the ROM's per-object star-post respawn bit
     * ({@code Obj_StarPost} loc_2CFC0 {@code btst #0,(a2)}, sonic3k.asm:61582),
     * which a star post consults to decide it is already activated. It is
     * separate from {@code Last_star_post_hit} because a star-post BONUS entry
     * zeroes {@code Last_star_post_hit} (loc_2D4CA {@code move.b #0,...},
     * sonic3k.asm:61924) yet keeps the star post used (its respawn bit stays set
     * across the reload via {@code Respawn_table_keep}). Star-post activation
     * guards must consult THIS, not the checkpoint index, so a bonus return does
     * not let the return star post re-trigger.
     *
     * <p>Default: equal to the checkpoint index (correct for every impl whose
     * checkpoint index is not independently zeroed, and for normal non-bonus
     * play where the two always coincide).
     *
     * @return the activation mark, or -1 if no star post has been activated
     */
    /**
     * ROM {@code clr.l (v_lamp_time).w} / {@code clr.l (Saved_Timer).w}: the
     * TIME OVER card zeroes the banked star-post timer before restarting the
     * level so the checkpoint reload reinstates 0:00 rather than the late time
     * that produced the time over (docs/s1disasm/_incObj/39 Game
     * Over.asm:82-87 (REV01), docs/s2disasm/s2.asm:27749-27751,
     * docs/skdisasm/sonic3k.asm:62098-62100). A no-op while no star post has
     * been passed, exactly as the ROM's clear of an unused word is.
     */
    default void clearSavedActTimer() {
        // no-op by default
    }

    default int getStarPostActivationMark() {
        return getLastCheckpointIndex();
    }

    /**
     * Restores the star-post activation mark after a level reload cleared it
     * (see {@link #getStarPostActivationMark()}). Used by the bonus-stage return
     * path to re-establish the pre-entry activation high-water so the return
     * star post stays used even though the checkpoint index was reset to 0.
     * Default: no-op for impls that do not track the mark separately.
     */
    default void restoreStarPostActivationMark(int mark) {
        // no-op by default
    }

    /**
     * Gets the saved X position.
     * @return saved X coordinate
     */
    int getSavedX();

    /**
     * Gets the saved Y position.
     * @return saved Y coordinate
     */
    int getSavedY();

    /**
     * Gets the saved camera X position.
     * @return saved camera X coordinate
     */
    int getSavedCameraX();

    /**
     * Gets the saved camera Y position.
     * @return saved camera Y coordinate
     */
    int getSavedCameraY();

    /**
     * Restore checkpoint state from previously saved values.
     * Called after loadLevel() clears the state but we still need checkpoint for respawn.
     *
     * @param x saved X position
     * @param y saved Y position
     * @param cameraX saved camera X position
     * @param cameraY saved camera Y position
     * @param checkpointIndex the checkpoint index
     */
    void restoreFromSaved(int x, int y, int cameraX, int cameraY, int checkpointIndex);
}
