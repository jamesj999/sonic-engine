package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.InvincibilityStarsObjectInstance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestInvincibilityStarsObjectInstance {
    @Test
    public void s1TrailFramesBehindMatchesDisassemblyFormula() {
        assertEquals(1, InvincibilityStarsObjectInstance.s1FramesBehindForStar(0, 0));
        assertEquals(7, InvincibilityStarsObjectInstance.s1FramesBehindForStar(1, 0));
        assertEquals(13, InvincibilityStarsObjectInstance.s1FramesBehindForStar(2, 0));
        assertEquals(19, InvincibilityStarsObjectInstance.s1FramesBehindForStar(3, 0));

        assertEquals(6, InvincibilityStarsObjectInstance.s1FramesBehindForStar(0, 5));
        assertEquals(12, InvincibilityStarsObjectInstance.s1FramesBehindForStar(1, 5));
        assertEquals(18, InvincibilityStarsObjectInstance.s1FramesBehindForStar(2, 5));
        assertEquals(24, InvincibilityStarsObjectInstance.s1FramesBehindForStar(3, 5));
    }

    @Test
    public void s1TrailFramesBehindWrapsAndClampsInputs() {
        // Star index clamps to [0..3], trail phase wraps mod 6
        assertEquals(1, InvincibilityStarsObjectInstance.s1FramesBehindForStar(-1, 0));
        assertEquals(24, InvincibilityStarsObjectInstance.s1FramesBehindForStar(99, 5));
        assertEquals(2, InvincibilityStarsObjectInstance.s1FramesBehindForStar(0, 7));
        assertEquals(6, InvincibilityStarsObjectInstance.s1FramesBehindForStar(0, -1));
    }
}


