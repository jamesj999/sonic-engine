package com.openggf.sprites.playable;

import com.openggf.game.rules.GameRules;

/**
 * The radius-and-position half of the ROM's reset-on-floor tail, shared by the
 * two routines that call it outside a landing: the hurt response and the kill.
 * Clearing the roll shape restores the taller standing radii, and the ROM lifts
 * y_pos so the feet stay put (S1 {@code Sonic_ResetOnFloor}
 * docs/s1disasm/_incObj/01 Sonic.asm:1858-1866; S2
 * {@code Sonic_ResetOnFloor_Part2} docs/s2disasm/s2.asm:38127-38140; S3K
 * {@code Player_TouchFloor} docs/skdisasm/sonic3k.asm:24335-24363).
 */
final class PlayableResetOnFloorRadiusTransition {
    private PlayableResetOnFloorRadiusTransition() {
    }

    /**
     * ROM {@code HurtCharacter}'s call into the tail. S2's 1P sidekick hurt
     * branches to {@code Hurt_Sidekick} instead and preserves a split
     * status/radius state, so the standing-radius restore is rule-gated here.
     */
    static void applyForHurt(AbstractPlayableSprite sprite) {
        apply(sprite, restoresSplitSidekickRadii(sprite));
    }

    /**
     * ROM {@code KillSonic} / {@code KillCharacter} / {@code Kill_Character}
     * call the same tail unconditionally, for either character
     * (docs/s1disasm/_incObj/Sonic ReactToItem.asm:454-459;
     * docs/s2disasm/s2.asm:85544-85551; docs/skdisasm/sonic3k.asm:21136-21151),
     * so the hurt-only sidekick split does not apply.
     */
    static void applyForDeath(AbstractPlayableSprite sprite) {
        apply(sprite, true);
    }

    private static void apply(AbstractPlayableSprite sprite, boolean restoresRadiiWithoutRoll) {
        boolean wasRolling = sprite.getRolling();
        int nativeXBeforeRadiusChange = sprite.getCentreX();
        int nativeYBeforeRadiusChange = sprite.getCentreY();
        int oldYRadius = sprite.getYRadius();
        sprite.setRolling(false);
        if (wasRolling) {
            sprite.setCentreXPreserveSubpixel((short) nativeXBeforeRadiusChange);
        }
        GameRules rules = sprite.getGameRules();
        if (restoresRadiiWithoutRoll) {
            sprite.applyStandingRadii(false);
        }
        if (wasRolling) {
            boolean usesCurrentRadiusDelta = rules != null
                    && rules.playerMovement() != null
                    && rules.playerMovement().landing().landingRollClearUsesCurrentYRadiusDelta();
            if (usesCurrentRadiusDelta) {
                int radiusDelta = oldYRadius - sprite.getStandYRadius();
                var gameState = sprite.currentGameStateOrNull();
                if (gameState != null && gameState.isReverseGravityActive()) {
                    radiusDelta = -radiusDelta;
                }
                int anglePlusQuarterTurn = ((sprite.getAngle() & 0xFF) + 0x40) & 0xFF;
                if ((anglePlusQuarterTurn & 0x80) != 0) {
                    radiusDelta = -radiusDelta;
                }
                sprite.setCentreYPreserveSubpixel((short) (nativeYBeforeRadiusChange + radiusDelta));
            } else {
                sprite.setY((short) (sprite.getY() - sprite.getRollHeightAdjustment()));
            }
        }
    }

    private static boolean restoresSplitSidekickRadii(AbstractPlayableSprite sprite) {
        GameRules rules = sprite.getGameRules();
        return !(sprite instanceof Tails)
                || rules == null || rules.sidekickCpu() == null
                || rules.sidekickCpu().sidekickHurtRestoresRadiiWithoutRoll();
    }
}
