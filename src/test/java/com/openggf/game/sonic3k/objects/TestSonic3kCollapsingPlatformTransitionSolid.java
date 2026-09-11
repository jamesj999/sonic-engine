package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kCollapsingPlatformTransitionSolid {

    @Test
    void fragmentTransitionRetainsExistingRiderButRejectsFreshContact() throws Exception {
        Sonic3kCollapsingPlatformObjectInstance platform = platform();
        setBoolean(platform, "transitionFrameSlopeSkip", true);

        assertTrue(platform.solidForTransitionState(true),
                "the rider's standing bit survives the skipped solid dispatch");
        assertFalse(platform.solidForTransitionState(false),
                "CreateFragments does not run SolidObjectTopSloped2 for a fresh player");
    }

    @Test
    void ordinarySolidStayAcceptsFreshContactOnFollowingDispatch() throws Exception {
        Sonic3kCollapsingPlatformObjectInstance platform = platform();
        setInt(platform, "state", 2);

        assertTrue(platform.solidForTransitionState(false));
    }

    @Test
    void firstRiderReleaseKeepsOnlyAnExistingSecondRiderEligible() throws Exception {
        Sonic3kCollapsingPlatformObjectInstance platform = platform();
        setInt(platform, "state", 3);
        setBoolean(platform, "releasePending", true);

        assertTrue(platform.solidForTransitionState(true),
                "the same native dispatch still has to run sub_205FC for Player 2");
        assertFalse(platform.solidForTransitionState(false),
                "a falling parent cannot accept a fresh rider");
    }

    @Test
    void riderReleasePublishesOnlyTheNativePreviousAnimationByte() {
        Sonic3kCollapsingPlatformObjectInstance platform = platform();
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0, (short) 0);
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        player.setPushing(true);
        player.getAnimationManager().publishPreviousAnimationId(Sonic3kAnimationIds.WALK.id());

        platform.publishReleaseAnimationState(player);

        assertEquals(Sonic3kAnimationIds.WALK.id(), player.getAnimationId(),
                "sub_205FC leaves anim untouched");
        assertEquals(Sonic3kAnimationIds.RUN.id(),
                player.getAnimationManager().captureRewindState().lastAnimationId(),
                "sub_205FC writes prev_anim=Run");
        assertFalse(player.getPushing(), "sub_205FC clears Status_Push");
    }

    private static Sonic3kCollapsingPlatformObjectInstance platform() {
        return new Sonic3kCollapsingPlatformObjectInstance(
                new ObjectSpawn(0x2C70, 0x0427, 0x04, 0, 0, false, 0));
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
