package com.openggf.game.sonic3k.objects;

import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizTransitionFloorObjectInstance {

    @Test
    void highFixedPointPhaseAcceptsAfterTwentyZeroDistancePasses() {
        AizTransitionFloorObjectInstance floor = new AizTransitionFloorObjectInstance();
        TestablePlayableSprite player = playerWithYSubpixel(0xF700);

        rejectZeroDistancePasses(floor, player, 20);

        assertFalse(floor.rejectsZeroDistanceTopSolidLanding(player));
    }

    @Test
    void lowFixedPointPhaseAcceptsAfterTwentyOneZeroDistancePasses() {
        AizTransitionFloorObjectInstance floor = new AizTransitionFloorObjectInstance();
        TestablePlayableSprite player = playerWithYSubpixel(0x0100);

        rejectZeroDistancePasses(floor, player, 20);
        assertTrue(floor.rejectsZeroDistanceTopSolidLanding(player));

        floor.onRejectedZeroDistanceTopSolidLanding(player);
        assertFalse(floor.rejectsZeroDistanceTopSolidLanding(player));
    }

    private static TestablePlayableSprite playerWithYSubpixel(int ySubpixel) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setSubpixelRaw(0, ySubpixel);
        return player;
    }

    private static void rejectZeroDistancePasses(AizTransitionFloorObjectInstance floor,
                                                 TestablePlayableSprite player,
                                                 int count) {
        for (int i = 0; i < count; i++) {
            assertTrue(floor.rejectsZeroDistanceTopSolidLanding(player));
            floor.onRejectedZeroDistanceTopSolidLanding(player);
        }
    }
}
