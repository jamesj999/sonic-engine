package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.RespawnState;
import uk.co.jamesj999.sonic.game.sonic2.objects.CheckpointObjectInstance;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

/**
 * Stores checkpoint state for save/restore on player death.
 * <p>
 * Based on the Sonic 2 disassembly's Saved_* variables.
 * </p>
 */
public class CheckpointState implements RespawnState {
    private static final Logger LOGGER = Logger.getLogger(CheckpointState.class.getName());

    private int lastCheckpointIndex = -1;
    private int savedX;
    private int savedY;
    private int savedCameraX;
    private int savedCameraY;
    private boolean cameraLock;

    /**
     * Clear checkpoint state (called on level start/change).
     */
    public void clear() {
        lastCheckpointIndex = -1;
        savedX = 0;
        savedY = 0;
        savedCameraX = 0;
        savedCameraY = 0;
        cameraLock = false;
    }

    /**
     * Save state from a checkpoint activation.
     */
    public void saveFromCheckpoint(CheckpointObjectInstance checkpoint, AbstractPlayableSprite player) {
        this.lastCheckpointIndex = checkpoint.getCheckpointIndex();
        this.savedX = checkpoint.getCenterX();
        this.savedY = checkpoint.getCenterY();
        this.cameraLock = checkpoint.hasCameraLockFlag();

        // Save camera position
        Camera camera = Camera.getInstance();
        this.savedCameraX = camera.getX();
        this.savedCameraY = camera.getY();

        LOGGER.fine("Saved checkpoint " + lastCheckpointIndex + " at (" + savedX + ", " + savedY + ")");
    }

    /**
     * Restore state after player death.
     * ROM behavior: restores position, camera, clears rings.
     */
    public void restoreToPlayer(AbstractPlayableSprite player, Camera camera) {
        if (!isActive()) {
            return;
        }

        // Restore player position to checkpoint location
        player.setX((short) savedX);
        player.setY((short) savedY);

        // Clear player state for fresh start
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setAir(false);
        player.setRolling(false);

        // Clear rings (ROM behavior)
        player.setRingCount(0);

        // Restore camera position directly from saved values (ROM-accurate)
        if (camera != null) {
            camera.setX((short) savedCameraX);
            camera.setY((short) savedCameraY);
            camera.setFocusedSprite(player);

            // Apply camera min X lock if subtype bit 7 was set
            if (cameraLock) {
                int minX = savedX - 0xA0;
                camera.setMinX((short) Math.max(0, minX));
            }
        }

        LOGGER.info("Restored from checkpoint " + lastCheckpointIndex);
    }

    public boolean isActive() {
        return lastCheckpointIndex >= 0;
    }

    public int getLastCheckpointIndex() {
        return lastCheckpointIndex;
    }

    public int getSavedX() {
        return savedX;
    }

    public int getSavedY() {
        return savedY;
    }

    public int getSavedCameraX() {
        return savedCameraX;
    }

    public int getSavedCameraY() {
        return savedCameraY;
    }

    /**
     * Restore checkpoint state from previously saved values.
     * Called after loadLevel() clears the state but we still need checkpoint for
     * respawn.
     */
    public void restoreFromSaved(int x, int y, int cameraX, int cameraY, int checkpointIndex) {
        this.lastCheckpointIndex = checkpointIndex;
        this.savedX = x;
        this.savedY = y;
        this.savedCameraX = cameraX;
        this.savedCameraY = cameraY;
        LOGGER.fine("Restored checkpoint " + checkpointIndex + " state at (" + x + ", " + y + ")");
    }
}
