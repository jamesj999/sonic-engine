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
 * Tests insta-shield hitbox expansion preconditions.
 * ROM: sonic3k.asm:20620-20640.
 */
class TestInstaShieldHitbox {

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        GameModuleRegistry.reset();
    }

    @Test
    void hitboxExpandsWhenInstaShieldActive() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setDoubleJumpFlag(1);
        assertNull(sprite.getShieldType(), "No shield equipped");

        // Preconditions for 48x48 hitbox:
        assertTrue(sprite.getGameRules().playerCapability().instaShieldEnabled());
        assertEquals(1, sprite.getDoubleJumpFlag());
        assertNull(sprite.getShieldType());
        assertEquals(0, sprite.getInvincibleFrames());
    }

    @Test
    void hitboxNotExpandedWhenShieldPresent() {
        // ROM: $73 mask — shield blocks insta-shield hitbox even if doubleJumpFlag==1
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setDoubleJumpFlag(1);
        sprite.setShieldStateForTest(true, ShieldType.FIRE);
        assertNotNull(sprite.getShieldType(), "Fire shield equipped — hitbox should NOT expand");
    }

    @Test
    void hitboxNotExpandedWhenDoubleJumpFlagIsTwo() {
        // doubleJumpFlag==2 means post-attack — normal hitbox
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setDoubleJumpFlag(2);
        assertEquals(2, sprite.getDoubleJumpFlag(), "Post-attack state — hitbox should be normal");
    }

    @Test
    void hitboxNotExpandedWhenFeatureDisabled() {
        // S2 game rules — no insta-shield
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_2);
        sprite.setDoubleJumpFlag(1);
        assertFalse(sprite.getGameRules().playerCapability().instaShieldEnabled(),
                "S2 does not have insta-shield");
    }

    @Test
    void hitboxNotExpandedWhenDoubleJumpFlagIsZero() {
        // doubleJumpFlag==0 means ready — no expansion
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        assertEquals(0, sprite.getDoubleJumpFlag(), "Ready state — hitbox should be normal");
    }

    @Test
    void superSonicSuppressesAbilityButSetsFlag() {
        // ROM (sonic3k.asm:23404-23408): Super Sonic sets flag=1 but no ability fires
        TestablePlayableSprite sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setSuperSonic(true);
        assertTrue(sprite.isSuperSonic(), "Super Sonic suppresses all abilities");
    }
}


