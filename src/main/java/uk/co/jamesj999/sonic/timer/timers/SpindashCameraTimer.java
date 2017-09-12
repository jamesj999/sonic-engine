package uk.co.jamesj999.sonic.timer.timers;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.timer.AbstractTimer;

/**
 * Created by Jamesjohnstone on 26/03/15.
 */
public class SpindashCameraTimer extends AbstractTimer {

    public SpindashCameraTimer(String code, int ticks) {
        super(code, ticks);
    }

    public boolean perform() {
        Camera camera = Camera.getInstance();
        if (camera != null) {
            camera.setFrozen(false);
            return true;
        } else {
            return false;
        }
    }
}
