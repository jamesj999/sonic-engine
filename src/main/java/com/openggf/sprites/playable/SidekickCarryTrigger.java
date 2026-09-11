package com.openggf.sprites.playable;

import com.openggf.game.PlayerCharacter;

/**
 * Game-agnostic hook for the S3K-style Tails-carry-Sonic intro mechanic.
 *
 * <p>When supplied to {@link SidekickCpuController#setCarryTrigger}, the driver
 * polls {@link #shouldEnterCarry} on each INIT tick and, on a true return,
 * transitions to its CARRY_INIT state after calling {@link #applyInitialPlacement}.
 *
 * <p>All ROM references are {@code sonic3k.asm} (S&K-side, address < 0x200000).
 * Games that do not use the Tails-carry mechanic (S1, S2) return {@code null}
 * from {@code GameModule.getSidekickCarryTrigger()} and the driver's behaviour
 * is unchanged.
 *
 * <p>Interface uses primitives + {@link PlayerCharacter} + types already in
 * {@code com.openggf.sprites.playable} — no new dependency types are introduced.
 */
public interface SidekickCarryTrigger {

    /**
     * Invoked each INIT tick. If this returns {@code true} the driver transitions
     * to CARRY_INIT on the current frame.
     *
     * @param zoneId     canonical zone id (S3K: 0=AIZ, 1=HCZ, 2=MGZ, 3=CNZ, ...)
     * @param actId      zero-based act id
     * @param playerMode main player's character; ROM CNZ trigger gates on SONIC_AND_TAILS
     */
    boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter playerMode);

    /**
     * Secondary gate: when the ROM intro carry normally fires, confirm that
     * the leader is still parked at the zone's ROM spawn coordinates. Headless
     * tests teleport the leader (e.g., onto a CNZ cannon or cylinder) before
     * the first sidekick tick; without this guard, the INIT branch would
     * unconditionally steal object-control of the leader and defeat the object
     * the test is exercising. The default implementation always returns
     * {@code true}, preserving pre-existing trigger behaviour.
     */
    default boolean isLeaderAtIntroPosition(AbstractPlayableSprite leader) {
        return true;
    }

    /**
     * Positions the carrier (sidekick, typically Tails) and cargo (leader,
     * typically Sonic) for the first CARRY_INIT tick. The driver will then
     * clamp velocities via {@link #carryInitXVel()} etc.
     */
    void applyInitialPlacement(AbstractPlayableSprite carrier, AbstractPlayableSprite cargo);

    /** Cargo's descend offset below carrier's centre, in pixels. ROM CNZ: {@code 0x1C}. */
    int carryDescendOffsetY();

    /** Constant horizontal velocity held while carrying, in subpixel units.
     *  ROM CNZ: {@code 0x0100}. */
    short carryInitXVel();

    /** Level_frame_counter cadence mask for synthetic-right-press injection.
     *  Engine injects when {@code (frameCounter & mask) == 0}. ROM CNZ: {@code 0x1F}. */
    int carryInputInjectMask();

    /**
     * Returns true when the carry body should pulse A/B/C instead of Right on
     * the cadence from {@link #carryInputInjectMask()}. ROM MGZ rescue routine
     * $16 pulses A/B/C every 8 frames so Tails keeps flying upward; CNZ keeps
     * the default synthetic-right pulse.
     */
    default boolean carryInjectsJump() {
        return false;
    }

    /**
     * MGZ2 boss-transition carry uses ROM routines $16/$18 instead of the CNZ
     * routine-$20 body: first Tails pulses A/B/C every 8 frames until he reaches
     * Camera_Y+$90, then P1 left/right steer Tails and up/down alter the flap
     * cadence.
     */
    default boolean usesMgzBossTransitionControl() {
        return false;
    }

    /** Cooldown frames after A/B/C jump release. ROM CNZ: {@code 0x12} (~18 frames). */
    int carryJumpReleaseCooldownFrames();

    /** Cooldown frames after external-vel (latch mismatch) release.
     *  ROM CNZ: {@code 0x3C} (~60 frames). */
    int carryLatchReleaseCooldownFrames();

    /** Post-A/B/C-release y_vel (jump impulse). ROM CNZ: {@code -0x0380}. */
    short carryReleaseJumpYVel();

    /** Post-A/B/C-release x_vel magnitude; sign applied by driver from cargo face direction.
     *  ROM CNZ: {@code 0x0200}. */
    short carryReleaseJumpXVel();
}
