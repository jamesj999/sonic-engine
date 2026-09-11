package com.openggf.game.sonic3k.constants;

import com.openggf.game.CanonicalAnimation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic3kTailsFlightAnimations {

    @Test
    void canonicalTailsFlightAndSwimAnimationsUseExactNativeIds() {
        CanonicalAnimation[] animations = {
                CanonicalAnimation.TAILS_FLY,
                CanonicalAnimation.TAILS_FLY_ASCEND,
                CanonicalAnimation.TAILS_FLY_CARRY,
                CanonicalAnimation.TAILS_FLY_CARRY_ASCEND,
                CanonicalAnimation.TAILS_FLY_TIRED,
                CanonicalAnimation.TAILS_SWIM,
                CanonicalAnimation.TAILS_SWIM_ASCEND,
                CanonicalAnimation.TAILS_SWIM_CARRY,
                CanonicalAnimation.TAILS_SWIM_TIRED
        };
        Sonic3kAnimationIds[] nativeAnimations = {
                Sonic3kAnimationIds.TAILS_FLY,
                Sonic3kAnimationIds.TAILS_FLY_ASCEND,
                Sonic3kAnimationIds.TAILS_FLY_CARRY,
                Sonic3kAnimationIds.TAILS_FLY_CARRY_ASCEND,
                Sonic3kAnimationIds.TAILS_FLY_TIRED,
                Sonic3kAnimationIds.TAILS_SWIM,
                Sonic3kAnimationIds.TAILS_SWIM_ASCEND,
                Sonic3kAnimationIds.TAILS_SWIM_CARRY,
                Sonic3kAnimationIds.TAILS_SWIM_TIRED
        };

        for (int i = 0; i < animations.length; i++) {
            int expectedId = 0x20 + i;
            assertEquals(expectedId, Sonic3kAnimationIds.fromCanonical(animations[i]));
            assertEquals(expectedId, nativeAnimations[i].id());
            assertEquals(animations[i], nativeAnimations[i].toCanonical());
        }
    }

    @Test
    void legacyFlightAndCarriedMeaningsRemainAvailableAsAliases() {
        assertEquals(0x20, Sonic3kAnimationIds.FLY.id());
        assertEquals(CanonicalAnimation.FLY, Sonic3kAnimationIds.FLY.toCanonical());
        assertEquals(0x22, Sonic3kAnimationIds.TAILS_CARRIED.id());
        assertEquals(CanonicalAnimation.TAILS_CARRIED, Sonic3kAnimationIds.TAILS_CARRIED.toCanonical());
    }

}
