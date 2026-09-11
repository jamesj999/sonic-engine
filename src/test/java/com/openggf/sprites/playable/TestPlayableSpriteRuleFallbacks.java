package com.openggf.sprites.playable;

import com.openggf.game.DamageCause;
import com.openggf.game.ShieldType;
import com.openggf.game.rules.GameRules;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPlayableSpriteRuleFallbacks {

    @Test
    public void playerCapabilityRuleUsesDefaultWhenTypedGroupMissing() throws Exception {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setShieldStateForTest(true, ShieldType.FIRE);
        GameRules base = GameRules.SONIC_3K;
        setGameRulesForTest(sprite, new GameRules(
                base.playerMovement(),
                null,
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                base.drowningBubble()));

        assertTrue(sprite.applyHurt(0, DamageCause.FIRE),
                "A null PlayerCapabilityRules group should not recreate removed feature-set shield rules");
        assertFalse(sprite.hasShield(), "Fire shield should be consumed when no typed capability rules block fire damage");
    }

    private static void setGameRulesForTest(TestablePlayableSprite sprite, GameRules rules) throws Exception {
        Field field = AbstractPlayableSprite.class.getDeclaredField("gameRules");
        field.setAccessible(true);
        field.set(sprite, rules);
    }
}
