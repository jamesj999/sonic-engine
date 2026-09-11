package com.openggf.level.objects;

import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import java.util.Objects;

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
    private int pendingSwitchAnimId = -1;

    public ObjectAnimationState(SpriteAnimationSet animationSet, int animId, int initialMappingFrame) {
        this.animationSet = animationSet;
        this.animId = animId;
        this.mappingFrame = initialMappingFrame;
    }

    public void setAnimId(int animId) {
        this.animId = animId;
        pendingSwitchAnimId = -1;
    }

    public int getAnimId() {
        return animId;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    /**
     * Number of displayed frames in the given animation script, or 0 if absent.
     * Read-only helper; does not affect animation playback.
     */
    public int frameCount(int queryAnimId) {
        if (animationSet == null) {
            return 0;
        }
        SpriteAnimationScript script = animationSet.getScript(queryAnimId);
        return script == null ? 0 : script.frames().size();
    }

    public ObjectAnimationState copyForRewind() {
        ObjectAnimationState copy = new ObjectAnimationState(animationSet, animId, mappingFrame);
        copy.lastAnimId = lastAnimId;
        copy.frameIndex = frameIndex;
        copy.frameTick = frameTick;
        copy.pendingSwitchAnimId = pendingSwitchAnimId;
        return copy;
    }

    /**
     * Reset the animation frame index to 0.
     * Matches ROM: move.b #0,anim_frame(a0)
     */
    public void resetFrameIndex() {
        frameIndex = 0;
        lastAnimId = -1; // Force re-initialization on next update()
        pendingSwitchAnimId = -1;
    }

    public void update() {
        if (animationSet == null) {
            return;
        }

        if (pendingSwitchAnimId >= 0) {
            animId = pendingSwitchAnimId;
            lastAnimId = -1;
            pendingSwitchAnimId = -1;
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
                frameIndex = script.frames().size() - 1;
                pendingSwitchAnimId = nextAnimId;
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

    @Override
    public boolean equals(Object other) {
        return other instanceof ObjectAnimationState state
                && animId == state.animId
                && lastAnimId == state.lastAnimId
                && frameIndex == state.frameIndex
                && frameTick == state.frameTick
                && mappingFrame == state.mappingFrame
                && pendingSwitchAnimId == state.pendingSwitchAnimId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(animId, lastAnimId, frameIndex, frameTick, mappingFrame, pendingSwitchAnimId);
    }
}
