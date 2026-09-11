package com.openggf.sprites.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestGhostOpacityCalculator {
    @Test
    void alphaIsTransparentAtExactRealPosition() {
        assertEquals(0.0f, GhostOpacityCalculator.alphaForDistance(0, 0, 32), 0.0001f);
    }

    @Test
    void alphaRampsLinearlyToFullOpacityAtThreshold() {
        assertEquals(0.5f, GhostOpacityCalculator.alphaForDistance(16, 0, 32), 0.0001f);
        assertEquals(1.0f, GhostOpacityCalculator.alphaForDistance(32, 0, 32), 0.0001f);
    }

    @Test
    void alphaClampsToFullOpacityBeyondThreshold() {
        assertEquals(1.0f, GhostOpacityCalculator.alphaForDistance(96, 0, 32), 0.0001f);
    }
}
