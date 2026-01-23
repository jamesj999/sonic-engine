package uk.co.jamesj999.sonic.timer.timers;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.timer.AbstractTimer;

/**
 * Timer to unfreeze camera after spindash.
 *
 * Note: Spindash camera lag is now handled by Camera.setHorizScrollDelay() which
 * auto-decrements each frame (matching ROM behavior). This timer is no longer
 * used for normal spindash lag, but remains for any legacy freeze scenarios.
 */
public class SpindashCameraTimer extends AbstractTimer {

    public SpindashCameraTimer(String code, int ticks) {
        super(code, ticks);
    }

    public boolean perform() {
        Camera camera = Camera.getInstance();
        if (camera != null) {
            // Clear full freeze (if any)
            camera.setFrozen(false);
            // Also explicitly clear any horizontal delay
            camera.setHorizScrollDelay(0);
            return true;
        } else {
            return false;
        }
    }
}