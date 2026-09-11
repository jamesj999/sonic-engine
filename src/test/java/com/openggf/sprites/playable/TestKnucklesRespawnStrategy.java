package com.openggf.sprites.playable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KnucklesRespawnStrategy} state-aware physics gating.
 * Uses a minimal stub controller — no ROM, no OpenGL required.
 */
class TestKnucklesRespawnStrategy {

    @Test
    void requiresPhysics_falseBeforeDrop() {
        // Strategy starts in glide phase — physics should be skipped
        KnucklesRespawnStrategy strategy = new KnucklesRespawnStrategy(null);
        assertFalse(strategy.requiresPhysics(),
                "Glide phase should not require physics (manual positioning)");
    }

    @Test
    void requiresPhysics_trueDuringDrop() {
        // After drop is triggered, physics must run so gravity applies
        KnucklesRespawnStrategy strategy = new KnucklesRespawnStrategy(null);
        strategy.triggerDrop();
        assertTrue(strategy.requiresPhysics(),
                "Drop phase must require physics for gravity to apply");
    }
}


