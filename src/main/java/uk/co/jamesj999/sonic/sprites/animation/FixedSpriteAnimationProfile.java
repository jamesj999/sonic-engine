package uk.co.jamesj999.sonic.sprites.animation;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Always returns a fixed mapping frame.
 */
public class FixedSpriteAnimationProfile implements SpriteAnimationProfile {
    private final int frame;

    public FixedSpriteAnimationProfile(int frame) {
        this.frame = Math.max(0, frame);
    }

    @Override
    public int resolveFrame(AbstractPlayableSprite sprite, int frameCounter, int frameCount) {
        if (frameCount <= 0) {
            return 0;
        }
        return Math.min(frame, frameCount - 1);
    }
}
