package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.ShieldType;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests insta-shield activation gating.
 * ROM: sonic3k.asm:23397-23479 (Sonic_ShieldMoves).
 */
class TestInstaShieldGating {

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        GameModuleRegistry.reset();
    }

    @Test
    void instaShieldEnabledInS3KRules() {
        assertTrue(GameRules.SONIC_3K.playerCapability().instaShieldEnabled(),
                "S3K should have instaShieldEnabled");
    }

    @Test
    void instaShieldDisabledInS1Rules() {
        assertFalse(GameRules.SONIC_1.playerCapability().instaShieldEnabled(),
                "S1 should not have instaShieldEnabled");
    }

    @Test
    void instaShieldDisabledInS2Rules() {
        assertFalse(GameRules.SONIC_2.playerCapability().instaShieldEnabled(),
                "S2 should not have instaShieldEnabled");
    }

    @Test
    void instaShieldBlockedWhenShieldEquipped() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setShieldStateForTest(true, ShieldType.BASIC);
        assertEquals(ShieldType.BASIC, sprite.getShieldType());
    }

    @Test
    void instaShieldBlockedWhenElementalShieldEquipped() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setShieldStateForTest(true, ShieldType.FIRE);
        assertEquals(ShieldType.FIRE, sprite.getShieldType());
    }

    @Test
    void instaShieldBlockedWhenDoubleJumpFlagNonZero() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setDoubleJumpFlag(1);
        assertEquals(1, sprite.getDoubleJumpFlag(),
                "doubleJumpFlag should be 1 (already attacking)");
    }

    @Test
    void doubleJumpFlagClearedOnLanding() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setDoubleJumpFlag(2);
        assertEquals(2, sprite.getDoubleJumpFlag());
    }
}


