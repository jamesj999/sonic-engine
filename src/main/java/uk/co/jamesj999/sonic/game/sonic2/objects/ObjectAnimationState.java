package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationScript;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

/**
 * Lightweight animation runner for object mappings (AnimateSprite-style).
 */
public class ObjectAnimationState {
    private final SpriteAnimationSet animationSet;
    private int animId;
    private int lastAnimId = -1;
    private int frameIndex;
    private int frameTick;
    private int mappingFrame;

    public ObjectAnimationState(SpriteAnimationSet animationSet, int animId, int initialMappingFrame) {
        this.animationSet = animationSet;
        this.animId = animId;
        this.mappingFrame = initialMappingFrame;
    }

    public void setAnimId(int animId) {
        this.animId = animId;
    }

    public int getAnimId() {
        return animId;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    public void update() {
        if (animationSet == null) {
            return;
        }

        SpriteAnimationScript script = animationSet.getScript(animId);
        if (script == null || script.frames().isEmpty()) {
            return;
        }

        if (animId != lastAnimId) {
            frameIndex = 0;
            frameTick = script.delay() & 0xFF;
            lastAnimId = animId;
        }

        int delay = script.delay() & 0xFF;
        int duration = frameTick - 1;
        boolean advanceFrame = duration < 0;
        if (advanceFrame) {
            duration = delay;
        }
        frameTick = duration;

        if (frameIndex < 0 || frameIndex >= script.frames().size()) {
            frameIndex = 0;
        }
        mappingFrame = script.frames().get(frameIndex);

        if (advanceFrame) {
            advanceFrameIndex(script);
        }
    }

    private void advanceFrameIndex(SpriteAnimationScript script) {
        int next = frameIndex + 1;
        if (next < script.frames().size()) {
            frameIndex = next;
            return;
        }
        switch (script.endAction()) {
            case HOLD -> frameIndex = script.frames().size() - 1;
            case LOOP_BACK -> frameIndex = resolveLoopBackIndex(script);
            case SWITCH -> {
                int nextAnimId = script.endParam();
                if (nextAnimId == animId) {
                    frameIndex = 0;
                    return;
                }
                animId = nextAnimId;
                lastAnimId = -1;
            }
            case LOOP -> frameIndex = 0;
            default -> frameIndex = 0;
        }
    }

    private int resolveLoopBackIndex(SpriteAnimationScript script) {
        int loopBack = script.endParam();
        if (loopBack <= 0) {
            return 0;
        }
        int target = script.frames().size() - loopBack;
        if (target < 0) {
            return 0;
        }
        return target;
    }
}
