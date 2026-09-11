package com.openggf;

import com.openggf.game.BonusStageProvider;
import com.openggf.game.ShieldType;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestBonusStageShieldRestore {

    @Test
    void encodeSavedShieldStatus_tracksElementalShieldBits() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        player.setShieldStateForTest(true, ShieldType.FIRE);
        assertEquals(1 << GameLoop.STATUS_FIRE_SHIELD_BIT, GameLoop.encodeSavedShieldStatus(player));

        player.setShieldStateForTest(true, ShieldType.LIGHTNING);
        assertEquals(1 << GameLoop.STATUS_LIGHTNING_SHIELD_BIT, GameLoop.encodeSavedShieldStatus(player));

        player.setShieldStateForTest(true, ShieldType.BUBBLE);
        assertEquals(1 << GameLoop.STATUS_BUBBLE_SHIELD_BIT, GameLoop.encodeSavedShieldStatus(player));
    }

    @Test
    void encodeSavedShieldStatus_ignoresBasicShieldAndNoShield() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        player.setShieldStateForTest(true, ShieldType.BASIC);
        assertEquals(0, GameLoop.encodeSavedShieldStatus(player));

        player.setShieldStateForTest(false, null);
        assertEquals(0, GameLoop.encodeSavedShieldStatus(player));
    }

    @Test
    void resolveShieldToRestore_prefersBonusRewardOverSavedShield() {
        BonusStageProvider.BonusStageRewards rewards =
                new BonusStageProvider.BonusStageRewards(0, 0, false, false, false, true);
        int savedBubbleMask = 1 << GameLoop.STATUS_FIRE_SHIELD_BIT;

        assertEquals(ShieldType.BUBBLE, GameLoop.resolveShieldToRestore(rewards, savedBubbleMask));
    }

    @Test
    void resolveShieldToRestore_restoresSavedElementalShieldWhenNoRewardWasEarned() {
        BonusStageProvider.BonusStageRewards none = BonusStageProvider.BonusStageRewards.none();

        assertEquals(
                ShieldType.FIRE,
                GameLoop.resolveShieldToRestore(none, 1 << GameLoop.STATUS_FIRE_SHIELD_BIT));
        assertEquals(
                ShieldType.LIGHTNING,
                GameLoop.resolveShieldToRestore(none, 1 << GameLoop.STATUS_LIGHTNING_SHIELD_BIT));
        assertEquals(
                ShieldType.BUBBLE,
                GameLoop.resolveShieldToRestore(none, 1 << GameLoop.STATUS_BUBBLE_SHIELD_BIT));
        assertNull(GameLoop.resolveShieldToRestore(none, 0));
    }

    /**
     * ROM {@code SpawnLevelMainSprites_SpawnPowerup} restores the saved
     * elemental shield on the BONUS zone's own level spawn
     * (docs/skdisasm/sonic3k.asm:8264-8323), so the shield is live for the
     * duration of the bonus stage -- not only after the return to the level.
     */
    @Test
    void applyBonusStageEntryShieldRestore_givesSavedElementalShieldOnEntry() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        GameLoop.applyBonusStageEntryShieldRestore(
                player, 1 << GameLoop.STATUS_LIGHTNING_SHIELD_BIT);

        assertEquals(ShieldType.LIGHTNING, player.getShieldType());
    }

    @Test
    void applyBonusStageEntryShieldRestore_leavesPlayerUnshieldedWhenNothingWasSaved() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        GameLoop.applyBonusStageEntryShieldRestore(player, 0);

        assertNull(player.getShieldType());
    }
}
