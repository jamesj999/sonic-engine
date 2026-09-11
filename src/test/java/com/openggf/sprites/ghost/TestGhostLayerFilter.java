package com.openggf.sprites.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGhostLayerFilter {
    @Test
    void matchesOnlyTheRealSpritesBucketAndTilePriority() {
        assertTrue(GhostLayerFilter.matches(3, true, 3, true));
        assertFalse(GhostLayerFilter.matches(2, true, 3, true));
        assertFalse(GhostLayerFilter.matches(3, false, 3, true));
    }
}
