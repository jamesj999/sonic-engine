package com.openggf.game.sonic3k.events;

import com.openggf.physics.Direction;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kHczSlideTerrain {

    @Test
    void recognizesEveryRomSlideLayoutByte() {
        int[] blocks = {0x1C, 0x72, 0x83, 0x84, 0x8B, 0x91, 0x9F, 0xA0, 0xA5, 0xA6};
        for (int block : blocks) {
            assertTrue(Sonic3kHCZEvents.isHcz2SlideBlock(block));
        }
        assertFalse(Sonic3kHCZEvents.isHcz2SlideBlock(0x00));
    }

    @Test
    void slideBlockPublishesInfiniteInertiaWithoutOvershootingTarget() {
        TestablePlayableSprite player = groundedSlideCandidate();
        player.setGSpeed((short) 0xF6BF);

        Sonic3kHCZEvents.applyHcz2SlideTerrainForBlock(player, 0x91);

        assertEquals((short) 0xF6BF, player.getGSpeed());
        assertEquals(Direction.LEFT, player.getDirection());
        assertEquals(0x1B, player.getAnimationId());
        assertTrue(player.isSliding());
    }

    @Test
    void slideBlockAcceleratesGroundSpeedTowardNegativeEightHighByte() {
        TestablePlayableSprite player = groundedSlideCandidate();
        player.setGSpeed((short) 0xF900);

        Sonic3kHCZEvents.applyHcz2SlideTerrainForBlock(player, 0xA0);

        assertEquals((short) 0xF8C0, player.getGSpeed());
        assertEquals(Direction.LEFT, player.getDirection());
        assertTrue(player.isSliding());
    }

    @Test
    void leavingSlideTerrainClearsBitAndAppliesMoveLock() {
        TestablePlayableSprite player = groundedSlideCandidate();
        player.setSliding(true);

        Sonic3kHCZEvents.applyHcz2SlideTerrainForBlock(player, 0x00);

        assertFalse(player.isSliding());
        assertEquals(5, player.getMoveLockTimer());
    }

    @Test
    void airborneDetachLeavesPublishedSlideAnimationForPlayableDispatch() {
        TestablePlayableSprite player = groundedSlideCandidate();
        player.setSliding(true);
        player.setRolling(true);
        player.setAnimationId(0x1B);
        player.setAir(true);

        Sonic3kHCZEvents.applyHcz2SlideTerrainForBlock(player, 0x91);

        assertFalse(player.isSliding());
        assertEquals(0x1B, player.getAnimationId(),
                "sub_717C clears slide state after the player animation dispatch");
    }

    private static TestablePlayableSprite groundedSlideCandidate() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setAir(false);
        player.setTopSolidBit((byte) 0x0E);
        return player;
    }
}
