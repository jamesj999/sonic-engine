package uk.co.jamesj999.sonic.timer.timers;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.timer.AbstractTimer;

/**
 * Created by Jamesjohnstone on 26/03/15.
 */
public class SpindashCameraTimer extends AbstractTimer {

    private Camera camera = Camera.getInstance();

    public SpindashCameraTimer(String code, int ticks) {
        super(code, ticks);
    }

    @Override
    public boolean perform() {
        camera.setFrozen(false);
        return true;
    }
}