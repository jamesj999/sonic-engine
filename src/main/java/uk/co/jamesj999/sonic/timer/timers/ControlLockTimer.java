package uk.co.jamesj999.sonic.timer.timers;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.timer.AbstractTimer;

/**
 * Timer to lock controls when slipping down a slope.
 */
public class ControlLockTimer extends AbstractTimer {
    private final AbstractPlayableSprite sprite;

    public ControlLockTimer(String code, int ticks, AbstractPlayableSprite sprite) {
        super(code, ticks);
        this.sprite = sprite;
    }

    @Override
    public void decrementTick() {
        // Only count down if the sprite is grounded.
        if (!sprite.getAir()) {
            super.decrementTick();
        }
    }

    @Override
    public boolean perform() {
        // Nothing special to do upon completion, just return true to be removed.
        return true;
    }
}
