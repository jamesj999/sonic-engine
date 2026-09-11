package com.openggf.sprites.ghost;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TestDynamicArtGhostIsolation {

    @Test
    void renderOnlySonicAndTailsNeverReceiveProductionDecisionCapability()
            throws Exception {
        Method factory = GhostTraceRenderer.class.getDeclaredMethod(
                "createVisualSprite", String.class);
        factory.setAccessible(true);

        AbstractPlayableSprite sonic =
                (AbstractPlayableSprite) factory.invoke(null, "sonic");
        AbstractPlayableSprite tails =
                (AbstractPlayableSprite) factory.invoke(null, "tails");

        assertFalse(sonic.getAnimationManager().hasDynamicArtDecisionOwner());
        assertFalse(tails.getAnimationManager().hasDynamicArtDecisionOwner());
    }
}
