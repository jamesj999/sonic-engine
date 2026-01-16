package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
