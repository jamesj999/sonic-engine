package com.openggf.tests.game;

import com.openggf.game.CollisionModel;
import com.openggf.game.GameModule;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PlayerCapabilityRules;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGameRulesConstants {

    @Test
    void angledLandingAnimationPublicationIsOwnedByAnimationRules() {
        assertFalse(GameRules.SONIC_1.playerAnimation().angledLandingPublishesWalk());
        assertTrue(GameRules.SONIC_2.playerAnimation().angledLandingPublishesWalk());
        assertTrue(GameRules.SONIC_3K.playerAnimation().angledLandingPublishesWalk());
    }

    @Test
    void directConstantsExposeCoreGameDifferences() {
        assertFalse(GameRules.SONIC_1.playerCapability().spindashEnabled());
        assertFalse(GameRules.SONIC_1.playerCapability().tailsFlightEnabled());
        assertEquals(CollisionModel.UNIFIED, GameRules.SONIC_1.collision().collisionModel());
        assertTrue(GameRules.SONIC_1.camera().waterShimmerEnabled());

        assertTrue(GameRules.SONIC_2.playerCapability().spindashEnabled());
        assertFalse(GameRules.SONIC_2.playerCapability().tailsFlightEnabled());
        assertEquals(CollisionModel.DUAL_PATH, GameRules.SONIC_2.collision().collisionModel());
        assertEquals(0, GameRules.SONIC_2.drowningBubble().initialDrowningCountdownFrameTimer());

        assertTrue(GameRules.SONIC_3K.playerCapability().elementalShieldsEnabled());
        assertTrue(GameRules.SONIC_3K.playerCapability().instaShieldEnabled());
        assertTrue(GameRules.SONIC_3K.playerCapability().tailsFlightEnabled());
        assertTrue(GameRules.SONIC_3K.playerAnimation().singleFacingBalanceAnimationSet());
        assertTrue(GameRules.SONIC_3K.objectInteraction().bossHitNegatesGroundSpeed());
    }

    @Test
    void modulesExposeDirectProviderRules() {
        assertModuleRules(new Sonic1GameModule(), GameRules.SONIC_1);
        assertModuleRules(new Sonic2GameModule(), GameRules.SONIC_2);
        assertModuleRules(new Sonic3kGameModule(), GameRules.SONIC_3K);
    }

    @Test
    void testHelperGameRulesSetterControlsSpriteRules() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        sprite.setGameRulesForTest(GameRules.SONIC_3K);

        assertSame(GameRules.SONIC_3K, sprite.getGameRules());

        sprite.setGameRulesForTest(null);

        assertNull(sprite.getGameRules());
    }

    @Test
    void capabilityRulesDefensivelyCopySpeedTables() {
        short[] spindashTable = new short[]{1, 2, 3};
        short[] superTable = new short[]{4, 5, 6};

        PlayerCapabilityRules rules = new PlayerCapabilityRules(true, spindashTable, true, true,
                false, true, true, superTable);

        spindashTable[0] = 99;
        superTable[0] = 99;

        assertArrayEquals(new short[]{1, 2, 3}, rules.spindashSpeedTable());
        assertArrayEquals(new short[]{4, 5, 6}, rules.superSpindashSpeedTable());

        short[] accessorResult = rules.spindashSpeedTable();
        assertNotSame(accessorResult, rules.spindashSpeedTable());
        accessorResult[0] = 42;

        assertArrayEquals(new short[]{1, 2, 3}, rules.spindashSpeedTable());
    }

    @Test
    void capabilityRulesUseValueSemanticsForArrayFields() {
        PlayerCapabilityRules first = GameRules.SONIC_3K.playerCapability();
        PlayerCapabilityRules second = new PlayerCapabilityRules(
                first.spindashEnabled(),
                first.spindashSpeedTable(),
                first.elementalShieldsEnabled(),
                first.instaShieldEnabled(),
                first.tailsFlightEnabled(),
                first.jumpRepressClearsRollJumpBeforeAbility(),
                first.lightningShieldEnabled(),
                first.superSpindashSpeedTable());

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.toString().contains("tailsFlightEnabled=true"));
        assertTrue(first.toString().contains("superSpindashSpeedTable=[2816, 2944, 3072"));
    }

    private static void assertModuleRules(GameModule module, GameRules expected) {
        assertSame(expected, module.getPhysicsProvider().getRules());
        assertSame(expected, module.getRules());
    }
}
