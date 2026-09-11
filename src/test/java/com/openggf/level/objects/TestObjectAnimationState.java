package com.openggf.level.objects;

import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestObjectAnimationState {
    @Test
    void switchEndActionKeepsPreviousMappingForMarkerUpdate() {
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(
                0, List.of(3), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(
                0, List.of(7), SpriteAnimationEndAction.SWITCH, 0));

        ObjectAnimationState state = new ObjectAnimationState(animations, 1, 0);

        state.update();
        assertEquals(7, state.getMappingFrame());
        assertEquals(1, state.getAnimId());

        state.update();
        assertEquals(7, state.getMappingFrame());
        assertEquals(0, state.getAnimId());

        state.update();
        assertEquals(3, state.getMappingFrame());
        assertEquals(0, state.getAnimId());
    }

    @Test
    void rewindCopyPreservesPendingSwitchMarkerState() {
        SpriteAnimationSet animations = new SpriteAnimationSet();
        animations.addScript(0, new SpriteAnimationScript(
                0, List.of(3), SpriteAnimationEndAction.LOOP, 0));
        animations.addScript(1, new SpriteAnimationScript(
                0, List.of(7), SpriteAnimationEndAction.SWITCH, 0));

        ObjectAnimationState state = new ObjectAnimationState(animations, 1, 0);
        state.update();

        ObjectAnimationState copy = state.copyForRewind();
        state.update();
        copy.update();

        assertEquals(state, copy);
        assertEquals(7, copy.getMappingFrame());
        assertEquals(0, copy.getAnimId());
    }
}
