package com.openggf.game;

import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that CanonicalAnimation contains the full cross-game animation vocabulary
 * covering Sonic 1, Sonic 2, and Sonic 3&K player animations.
 */
public class TestCanonicalAnimationMapping {

    @Test
    void s1AnimationsRoundTripThroughCanonical() {
        for (Sonic1AnimationIds s1Anim : Sonic1AnimationIds.values()) {
            CanonicalAnimation canonical = s1Anim.toCanonical();
            assertNotNull(canonical, "S1 " + s1Anim + " should map to a canonical animation");
            int resolved = Sonic1AnimationIds.fromCanonical(canonical);
            assertEquals(s1Anim.id(), resolved,
                    "S1 " + s1Anim + " -> " + canonical + " should round-trip back to " + s1Anim.id());
        }
    }

    @Test
    void s1FloatNameBridge() {
        assertEquals(CanonicalAnimation.FLOAT, Sonic1AnimationIds.FLOAT1.toCanonical());
        assertEquals(Sonic1AnimationIds.FLOAT1.id(), Sonic1AnimationIds.fromCanonical(CanonicalAnimation.FLOAT));
    }

    @Test
    void s2AnimationsRoundTripThroughCanonical() {
        for (Sonic2AnimationIds s2Anim : Sonic2AnimationIds.values()) {
            if (s2Anim.name().startsWith("SUPER_")) continue;
            CanonicalAnimation canonical = s2Anim.toCanonical();
            assertNotNull(canonical, "S2 " + s2Anim + " should map to a canonical animation");
            int resolved = Sonic2AnimationIds.fromCanonical(canonical);
            assertEquals(s2Anim.id(), resolved,
                    "S2 " + s2Anim + " -> " + canonical + " should round-trip back to " + s2Anim.id());
        }
    }

    @Test
    void s3kAnimationsRoundTripThroughCanonical() {
        for (Sonic3kAnimationIds s3kAnim : Sonic3kAnimationIds.values()) {
            if (s3kAnim.name().startsWith("SUPER_")) continue;
            CanonicalAnimation canonical = s3kAnim.toCanonical();
            assertNotNull(canonical, "S3K " + s3kAnim + " should map to a canonical animation");
            int resolved = Sonic3kAnimationIds.fromCanonical(canonical);
            assertEquals(s3kAnim.id(), resolved,
                    "S3K " + s3kAnim + " -> " + canonical + " should round-trip back to " + s3kAnim.id());
        }
    }

    @Test
    void unsupportedAnimationsReturnMinusOne() {
        assertEquals(-1, Sonic1AnimationIds.fromCanonical(CanonicalAnimation.SPINDASH));
        assertEquals(-1, Sonic2AnimationIds.fromCanonical(CanonicalAnimation.WARP1));
        assertEquals(-1, Sonic1AnimationIds.fromCanonical(CanonicalAnimation.BLINK));
        assertEquals(-1, Sonic3kAnimationIds.fromCanonical(CanonicalAnimation.WARP1));
    }

    @Test
    void s2CanonicalHurtUsesHurtSidekickAnimationByte() {
        assertEquals(0x1A, Sonic2AnimationIds.fromCanonical(CanonicalAnimation.HURT));
        assertEquals(CanonicalAnimation.HURT, Sonic2AnimationIds.HURT2.toCanonical());
        assertEquals(CanonicalAnimation.HURT2, Sonic2AnimationIds.HURT.toCanonical());
    }

    @Test
    void s2SuperVariantsReturnNullFromToCanonical() {
        // SUPER_WALK, SUPER_RUN etc. index a separate table — toCanonical() returns null
        // Only SUPER_TRANSFORM maps to a canonical entry
        assertNull(Sonic2AnimationIds.SUPER_WALK.toCanonical());
        assertNull(Sonic2AnimationIds.SUPER_RUN.toCanonical());
        assertNotNull(Sonic2AnimationIds.SUPER_TRANSFORM.toCanonical());
        assertEquals(CanonicalAnimation.SUPER_TRANSFORM, Sonic2AnimationIds.SUPER_TRANSFORM.toCanonical());
    }
}
