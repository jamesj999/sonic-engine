package uk.co.jamesj999.sonic.sprites.animation;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Resolves the mapping frame index for a sprite on a given tick.
 */
public interface SpriteAnimationProfile {
    default Integer resolveAnimationId(AbstractPlayableSprite sprite, int frameCounter, int scriptCount) {
        return null;
    }

    int resolveFrame(AbstractPlayableSprite sprite, int frameCounter, int frameCount);
}
