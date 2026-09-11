package com.openggf.game.sonic3k.sidekick;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCarryTrigger;

/**
 * S3K Tails-carry-Sonic intro trigger. CNZ1 and MHZ1 both enter the same
 * ROM carry routine ($0C -> $0E), with zone-specific pickup coordinates.
 *
 * <p>ROM trigger: {@code sonic3k.asm loc_13A32} reads
 * {@code (Current_zone_and_act).w}; on {@code 0x0300} it teleports Tails to
 * {@code (0x0018, 0x0600)} and sets {@code Tails_CPU_routine = 0x0C}. On
 * {@code 0x0700}, {@code loc_13A8E} does the same at {@code (0x00D8, 0x0500)}
 * unless the S&K-alone flag is set.
 *
 * <p>Player_mode gating here matches how the trace-recorded BK2 was captured.
 * The ROM's zone check itself is Player_mode-agnostic (see design spec §5.7).
 */
public final class Sonic3kCnzCarryTrigger implements SidekickCarryTrigger {

    /** S3K canonical zone id for Carnival Night. */
    private static final int ZONE_CNZ = 3;
    /** S3K canonical zone id for Mushroom Hill. */
    private static final int ZONE_MHZ = 7;

    @Override
    public boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter playerMode) {
        // ROM loc_13A32 reads only Current_zone_and_act; the carry intro fires for
        // both Sonic+Tails (Player_mode 0) and solo Sonic (Player_mode 1). In the
        // solo case SpawnLevelMainSprites loc_68D8 spawns a throwaway Player_2 Tails
        // whose controller is flagged transient so it flies off after the drop.
        return (zoneId == ZONE_CNZ || zoneId == ZONE_MHZ)
                && actId == 0
                && supportsCarryPlayerMode(playerMode);
    }

    private boolean supportsCarryPlayerMode(PlayerCharacter playerMode) {
        return playerMode == PlayerCharacter.SONIC_AND_TAILS
                || playerMode == PlayerCharacter.SONIC_ALONE;
    }

    private boolean isCnzIntroPosition(AbstractPlayableSprite leader) {
        int dx = Math.abs(leader.getCentreX() - Sonic3kConstants.CARRY_INIT_TAILS_X);
        int dy = Math.abs(leader.getCentreY() - Sonic3kConstants.CARRY_INIT_TAILS_Y);
        return dx <= 0x200 && dy <= 0x80;
    }

    private boolean isMhzIntroPosition(AbstractPlayableSprite leader) {
        int dx = Math.abs(leader.getCentreX() - Sonic3kConstants.CARRY_INIT_MHZ_TAILS_X);
        int dy = Math.abs(leader.getCentreY() - Sonic3kConstants.CARRY_INIT_MHZ_TAILS_Y);
        return dx <= 0x200 && dy <= 0x80;
    }

    private boolean isMhzPlacement(AbstractPlayableSprite cargo) {
        return isMhzIntroPosition(cargo)
                && !isCnzIntroPosition(cargo);
    }

    private int pickupX(AbstractPlayableSprite cargo) {
        return isMhzPlacement(cargo)
                ? Sonic3kConstants.CARRY_INIT_MHZ_TAILS_X
                : Sonic3kConstants.CARRY_INIT_TAILS_X;
    }

    private int pickupY(AbstractPlayableSprite cargo) {
        return isMhzPlacement(cargo)
                ? Sonic3kConstants.CARRY_INIT_MHZ_TAILS_Y
                : Sonic3kConstants.CARRY_INIT_TAILS_Y;
    }

    @Override
    public boolean isLeaderAtIntroPosition(AbstractPlayableSprite leader) {
        // ROM carry intros are one-shot level-start routines. If a focused test
        // has teleported Sonic away from both pickup regions, leave control with
        // the object under test.
        return isCnzIntroPosition(leader) || isMhzIntroPosition(leader);
    }

    @Override
    public void applyInitialPlacement(AbstractPlayableSprite carrier,
                                      AbstractPlayableSprite cargo) {
        carrier.setCentreXPreserveSubpixel((short) pickupX(cargo));
        carrier.setCentreYPreserveSubpixel((short) pickupY(cargo));
    }

    @Override
    public int carryDescendOffsetY() { return Sonic3kConstants.CARRY_DESCEND_OFFSET_Y; }

    @Override
    public short carryInitXVel() { return Sonic3kConstants.CARRY_INIT_TAILS_X_VEL; }

    @Override
    public int carryInputInjectMask() { return Sonic3kConstants.CARRY_INPUT_INJECT_MASK; }

    @Override
    public int carryJumpReleaseCooldownFrames() {
        return Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE;
    }

    @Override
    public int carryLatchReleaseCooldownFrames() {
        return Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE;
    }

    @Override
    public short carryReleaseJumpYVel() { return Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL; }

    @Override
    public short carryReleaseJumpXVel() { return Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL; }
}
