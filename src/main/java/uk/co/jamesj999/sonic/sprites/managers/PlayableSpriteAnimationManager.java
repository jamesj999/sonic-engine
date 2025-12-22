package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Updates a playable sprite's mapping frame based on its animation profile.
 */
public class PlayableSpriteAnimationManager {
    private final AbstractPlayableSprite sprite;

    public PlayableSpriteAnimationManager(AbstractPlayableSprite sprite) {
        this.sprite = sprite;
    }

    public void update(int frameCounter) {
        if (sprite == null) {
            return;
        }

        SpriteAnimationProfile profile = sprite.getAnimationProfile();
        if (sprite.getAnimationSet() != null && !sprite.getAnimationSet().getAllScripts().isEmpty()) {
            Integer desiredAnimId = profile != null
                    ? profile.resolveAnimationId(sprite, frameCounter, sprite.getAnimationSet().getScriptCount())
                    : null;
            if (desiredAnimId != null && desiredAnimId != sprite.getAnimationId()) {
                sprite.setAnimationId(desiredAnimId);
                sprite.setAnimationFrameIndex(0);
                sprite.setAnimationTick(0);
            }
            updateScriptedAnimation(frameCounter);
            return;
        }

        if (profile == null) {
            return;
        }
        int frameCount = sprite.getAnimationFrameCount();
        if (frameCount <= 0) {
            return;
        }
        int frame = profile.resolveFrame(sprite, frameCounter, frameCount);
        sprite.setMappingFrame(frame);
    }

    private void updateScriptedAnimation(int frameCounter) {
        var animationSet = sprite.getAnimationSet();
        if (animationSet == null) {
            return;
        }
        var script = animationSet.getScript(sprite.getAnimationId());
        if (script == null || script.frames().isEmpty()) {
            return;
        }

        int delay = resolveDelay(script.delay(), sprite, frameCounter);
        int tick = sprite.getAnimationTick() + 1;
        if (tick >= delay) {
            tick = 0;
            int frameIndex = sprite.getAnimationFrameIndex() + 1;
            if (frameIndex >= script.frames().size()) {
                switch (script.endAction()) {
                    case HOLD -> frameIndex = script.frames().size() - 1;
                    case SWITCH -> {
                        sprite.setAnimationId(script.nextAnimationId());
                        sprite.setAnimationFrameIndex(0);
                        sprite.setAnimationTick(0);
                        updateScriptedAnimation(frameCounter);
                        return;
                    }
                    case LOOP -> frameIndex = 0;
                    default -> frameIndex = 0;
                }
            }
            sprite.setAnimationFrameIndex(frameIndex);
        }
        sprite.setAnimationTick(tick);

        int frameIndex = sprite.getAnimationFrameIndex();
        if (frameIndex < 0 || frameIndex >= script.frames().size()) {
            frameIndex = 0;
        }
        sprite.setMappingFrame(script.frames().get(frameIndex));
    }

    private int resolveDelay(int rawDelay, AbstractPlayableSprite sprite, int frameCounter) {
        if (rawDelay <= 0) {
            return 1;
        }
        if (rawDelay >= 0xFD) {
            return 1;
        }
        return rawDelay;
    }
}
