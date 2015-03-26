package uk.co.jamesj999.sonic.timer.timers;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.timer.AbstractTimer;

/**
 * Created by Jamesjohnstone on 26/03/15.
 */
public class SpindashCameraTimer extends AbstractTimer {
    private AbstractPlayableSprite sprite;

    public SpindashCameraTimer(String code, int ticks, AbstractPlayableSprite sprite) {
        super(code, ticks);
        this.sprite = sprite;
    }

    @Override
    public boolean perform() {


        return true;
    }
}
