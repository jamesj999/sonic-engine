package com.openggf.game.sonic3k.events;

import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SecondaryAbility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kIczSlideTerrain {

    @Test
    void findsIcz1SlideBlocksByRomTableOrder() {
        assertEquals(0, Sonic3kICZEvents.findIcz1SlideTableIndex(0x2E));
        assertEquals(9, Sonic3kICZEvents.findIcz1SlideTableIndex(0x2B));
        assertEquals(-1, Sonic3kICZEvents.findIcz1SlideTableIndex(0x00));
    }

    @Test
    void matchingBlockMovesGroundSpeedTowardNegativeSlideTarget() {
        AbstractPlayableSprite player = testPlayer();
        player.setAir(false);
        player.setOnObject(false);
        player.setGSpeed((short) -0x0A73);

        Sonic3kICZEvents.applyIcz1SlideTerrainForBlock(player, 0x24);

        assertTrue(player.isSliding(), "ICZ slide terrain sets status_secondary bit 7");
        assertEquals((short) -0x0AB3, player.getGSpeed(),
                "ROM loc_723E subtracts $40 until ground_vel high byte reaches the signed table target");
        assertEquals(Direction.LEFT, player.getDirection(),
                "ROM faces from the pre-adjustment ground_vel high byte sign");
        assertEquals(0x19, player.getAnimationId(), "ICZ overrides the shared slide animation to raw ID 0x19");
    }

    @Test
    void matchingBlockMovesGroundSpeedTowardPositiveSlideTarget() {
        AbstractPlayableSprite player = testPlayer();
        player.setAir(false);
        player.setOnObject(false);
        player.setSliding(true);
        player.setDirection(Direction.RIGHT);
        player.setGSpeed((short) 0x0369);

        Sonic3kICZEvents.applyIcz1SlideTerrainForBlock(player, 0x33);

        assertTrue(player.isSliding(), "directional ICZ slide terrain keeps the slide bit set");
        assertEquals((short) 0x03A9, player.getGSpeed(),
                "ROM loc_7254 adds $40 until ground_vel high byte reaches the signed table target");
        assertEquals(Direction.RIGHT, player.getDirection(),
                "ROM refreshes Status_Facing from the current ground_vel high byte every directional slide frame");
        assertEquals(0x19, player.getAnimationId(), "directional slide terrain refreshes the raw slide animation");
    }

    @Test
    void matchingBlockDoesNotOvershootDirectionalSlideTarget() {
        AbstractPlayableSprite player = testPlayer();
        player.setAir(false);
        player.setOnObject(false);
        player.setGSpeed((short) 0x0810);

        Sonic3kICZEvents.applyIcz1SlideTerrainForBlock(player, 0x33);

        assertEquals((short) 0x0810, player.getGSpeed(),
                "ROM compares only the signed high byte before applying the $40 directional step");
        assertTrue(player.isSliding(), "directional ICZ slide terrain still publishes the slide bit");
    }

    @Test
    void leavingSlideBlockClearsSlideAndAppliesMoveLock() {
        AbstractPlayableSprite player = testPlayer();
        player.setSliding(true);
        player.setAir(false);
        player.setOnObject(false);

        Sonic3kICZEvents.applyIcz1SlideTerrainForBlock(player, 0x00);

        assertFalse(player.isSliding(), "leaving ICZ slide terrain clears status_secondary bit 7");
        assertEquals(5, player.getMoveLockTimer(), "ROM writes move_lock=5 when slide terrain ends");
    }

    private static AbstractPlayableSprite testPlayer() {
        return new AbstractPlayableSprite("sonic", (short) 0, (short) 0) {
            @Override
            protected void defineSpeeds() {
            }

            @Override
            protected void createSensorLines() {
            }

            @Override
            public void draw() {
            }

            @Override
            public SecondaryAbility getSecondaryAbility() {
                return SecondaryAbility.INSTA_SHIELD;
            }
        };
    }
}
