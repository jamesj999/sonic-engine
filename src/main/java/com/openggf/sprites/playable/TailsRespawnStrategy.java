package com.openggf.sprites.playable;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.SidekickCpuRules;
import com.openggf.physics.Direction;

/**
 * Tails-specific respawn strategy: flies in from above the leader's position,
 * homing in horizontally and vertically until reaching the target.
 */
public class TailsRespawnStrategy implements SidekickRespawnStrategy {

    private static final int RESPAWN_Y_OFFSET = 192;
    private static final int MAX_FLY_ACCEL = 12;
    private static final int S2_FLYING_OFFSCREEN_TIMEOUT_FRAMES = 300;
    private final int flyAnimId;
    private final int walkAnimId;
    /** S2 fallback if no typed rules are resolved, matching legacy unit-test sidekicks. */
    private static final int FLY_LAND_BLOCKERS_FALLBACK =
            GameRules.SONIC_2.sidekickCpu().sidekickFlyLandStatusBlockerMask();
    /** Sonic OST routine value at/above which the leader is considered dead/dying.
     *  ROM: {@code cmpi.b #6,(Player_1+routine).w / bhs.s loc_13D42} (sonic3k.asm:26629-26630). */
    private static final int LEADER_DEAD_ROUTINE_THRESHOLD = 6;

    private final SidekickCpuController controller;
    private int offscreenFlightFrames;
    private int diagnosticTargetX;
    private int diagnosticTargetY;
    private boolean approachRunsObjectPhysics;

    public TailsRespawnStrategy(SidekickCpuController controller) {
        this.controller = controller;
        this.flyAnimId = controller.resolveAnimationId(CanonicalAnimation.FLY);
        this.walkAnimId = controller.resolveAnimationId(CanonicalAnimation.WALK);
    }

    private SidekickCpuRules sidekickCpuRulesOrNull(AbstractPlayableSprite sidekick) {
        GameRules rules = sidekick.getGameRules();
        if (rules != null && rules.sidekickCpu() != null) {
            return rules.sidekickCpu();
        }
        return null;
    }

    @Override
    public boolean beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
        offscreenFlightFrames = 0;
        diagnosticTargetX = leader.getCentreX() & 0xFFFF;
        diagnosticTargetY = leader.getCentreY() & 0xFFFF;
        sidekick.setCentreXPreserveSubpixel(leader.getCentreX());
        sidekick.setCentreYPreserveSubpixel((short) (leader.getCentreY() - RESPAWN_Y_OFFSET));
        SidekickCpuRules rules = sidekickCpuRulesOrNull(sidekick);
        boolean catchUpMarker = rules != null && rules.sidekickRespawnEntersCatchUpFlight();
        // Obj02 continues through its normal movement dispatcher after
        // TailsCPU_Respawn returns. S2 therefore runs the spawn-frame physics
        // whenever inherited object_control bit 0 is clear; S3K's catch-up
        // marker writes $81 and remains fully scripted.
        approachRunsObjectPhysics = !catchUpMarker && !sidekick.isObjectControlSuppressesMovement();
        if (catchUpMarker) {
            // S3K Tails_Catch_Up_Flying loc_13B50 (sonic3k.asm:26503-26506)
            // zeroes x_vel, y_vel, and ground_vel immediately after teleporting.
            // S2 TailsCPU_Respawn (s2.asm:39122-39140) only writes routine,
            // position, priority, and spindash fields; TailsCPU_Flying clears
            // velocities later when it enters NORMAL (s2.asm:39229-39245).
            sidekick.setXSpeed((short) 0);
            sidekick.setYSpeed((short) 0);
            sidekick.setGSpeed((short) 0);
        }
        sidekick.setDead(false);
        sidekick.setHurt(false);
        sidekick.setSpindash(false);
        sidekick.setSpindashCounter((short) 0);
        if (catchUpMarker) {
            // S3K's catch-up teleport explicitly calls
            // Tails_Set_Flying_Animation (docs/skdisasm/sonic3k.asm:26474-26500).
            // S2 TailsCPU_Respawn has no animation write at all: it preserves
            // anim/prev_anim while changing routine and position
            // (docs/s2disasm/s2.asm:39122-39140). This distinction is visible
            // when an off-screen Wait Tails respawns: S2 keeps Wait throughout
            // the approach until another native owner changes it.
            sidekick.setForcedAnimationId(flyAnimId);
            sidekick.setAir(true);
            sidekick.setControlLocked(true);
            ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
        } else {
            // S2 TailsCPU_Respawn does not write obj_control (s2.asm:
            // 39122-39140), status, or anim, and ordinary TailsCPU_Flying only
            // writes $81/status=InAir/Fly on the off-screen timeout path
            // (s2.asm:39142-39159). Preserve those native bytes. When bit 0 is
            // clear, Obj02's same-frame ground dispatcher can run AnglePos;
            // losing support publishes prev_anim=Run before Tails_Animate
            // (s2.asm:38973-39003,43108-43110), restarting a preserved Wait
            // script without a synthetic animation write here.
            //
            // The timeout's Fly write is also one-shot ROM state, not a
            // continuous animation owner. Drop the engine-only forced latch
            // when TailsCPU_Respawn re-enters routine 4, but leave anim itself
            // untouched. That lets later native movement writes replace Fly;
            // CNZ1, for example, enters Status_Roll during this approach and
            // publishes TailsAni_Roll while TailsCPU_Flying remains active.
            sidekick.setForcedAnimationId(-1);
        }
        return true;
    }

    /**
     * S2 TailsCPU_Normal's dead-Sonic branch enters TailsCPU_Flying directly:
     * it writes routine 4 and flight state, but does not run TailsCPU_Respawn's
     * teleport-to-above-Sonic setup (docs/s2disasm/s2.asm:39254-39264).
     */
    public void beginDeadLeaderFlight(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
        offscreenFlightFrames = 0;
        diagnosticTargetX = leader.getCentreX() & 0xFFFF;
        diagnosticTargetY = controller.clampTargetYToWater(leader.getCentreY()) & 0xFFFF;
        sidekick.setAir(true);
        sidekick.clearRollingFlagPreserveRadii();
        sidekick.clearUnderwaterStatusPreserveWaterPhysics();
        sidekick.setOnObject(false);
        sidekick.setPushing(false);
        sidekick.setLatchedSolidObjectId(0);
        sidekick.setDirection(Direction.RIGHT);
        sidekick.setSpindash(false);
        sidekick.setSpindashCounter((short) 0);
        sidekick.setForcedAnimationId(flyAnimId);
        sidekick.setControlLocked(true);
        ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
    }

    @Override
    public boolean updateApproaching(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader,
                                     int frameCounter) {
        SidekickCpuRules rules = sidekickCpuRulesOrNull(sidekick);
        boolean catchUpMarker = rules != null && rules.sidekickRespawnEntersCatchUpFlight();
        if (catchUpMarker) {
            // S3K routine 4 owns the flight animation after the catch-up entry;
            // S2 TailsCPU_Flying does not write anim on its ordinary homing path
            // (docs/s2disasm/s2.asm:39142-39228).
            sidekick.setForcedAnimationId(flyAnimId);
        }
        approachRunsObjectPhysics = !catchUpMarker && !sidekick.isObjectControlSuppressesMovement();
        if (catchUpMarker) {
            sidekick.setControlLocked(true);
            ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
        } else {
            // S2 TailsCPU_Flying does not touch obj_control during ordinary
            // fly-in updates. Preserve the live suppression bit and let
            // requiresPhysics() mirror whether Obj02_Control would fall through.
        }

        if (handleS2FlyingOffscreenTimeout(sidekick)) {
            return false;
        }

        int targetX = leader.getCentreX(SidekickCpuController.ROM_FOLLOW_DELAY_FRAMES);
        int targetY = controller.clampTargetYToWater(
                leader.getCentreY(SidekickCpuController.ROM_FOLLOW_DELAY_FRAMES));
        diagnosticTargetX = targetX & 0xFFFF;
        diagnosticTargetY = targetY & 0xFFFF;
        int sidekickX = sidekick.getCentreX();
        int sidekickY = sidekick.getCentreY();

        int dx = targetX - sidekickX;
        int dy = targetY - sidekickY;
        int remainingDx = dx;
        if (dx != 0) {
            int move = Math.abs(dx) / 16;
            move = Math.min(move, MAX_FLY_ACCEL);
            // ROM uses "mvabs.b x_vel(a1),d1" here. On 68000 this reads the first
            // byte of the big-endian word, i.e. the signed high byte of x_vel.
            move += Math.abs(leader.getXSpeed() >> 8);
            move += 1;
            move = Math.min(move, Math.abs(dx));
            if (dx > 0) {
                sidekick.setDirection(Direction.RIGHT);
                sidekick.setX((short) (sidekick.getX() + move));
                remainingDx = dx - move;
            } else {
                sidekick.setDirection(Direction.LEFT);
                sidekick.setX((short) (sidekick.getX() - move));
                remainingDx = dx + move;
            }
        }

        if (dy > 0) {
            sidekick.setY((short) (sidekick.getY() + 1));
        } else if (dy < 0) {
            sidekick.setY((short) (sidekick.getY() - 1));
        }

        byte recordedStatus = leader.getStatusHistory(SidekickCpuController.ROM_FOLLOW_DELAY_FRAMES);
        // ROM TailsCPU_Flying exits when:
        // - the post-horizontal residual d0 is zero (horizontal catch-up may finish
        //   in this frame after applying the x_vel-based speed bonus), and
        // - the pre-vertical residual d1 was already zero (vertical +/-1 movement
        //   does NOT allow same-frame completion because d1 is tested before the move).
        //
        // The status-blocker mask AND leader-alive check differ per game:
        //   * S2 (s2.asm:38872-38873) andi.b #$D2,d2 / bne return — bits 1+4+6+7
        //     (in_air|roll_jump|underwater|bit7). NO leader-routine check; transitions
        //     to NORMAL even if Sonic is hurt or dead.
        //   * S3K (sonic3k.asm:26625, 26629-26630) andi.b #$80,d2 (bit 7 only) AND
        //     cmpi.b #6,(Player_1+routine).w / bhs (skip if Sonic dead).
        // Resolved through SidekickCpuRules so each game's ROM behavior is preserved.
        int statusBlockerMask = rules != null
                ? rules.sidekickFlyLandStatusBlockerMask()
                : FLY_LAND_BLOCKERS_FALLBACK;
        boolean requireLeaderAlive = rules != null && rules.sidekickFlyLandRequiresLeaderAlive();
        if ((recordedStatus & statusBlockerMask) == 0 && remainingDx == 0 && dy == 0) {
            if (requireLeaderAlive && leader.getDead()) {
                return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean handlesApproachDespawn() {
        return true;
    }

    @Override
    public boolean requiresPhysics() {
        return approachRunsObjectPhysics;
    }

    /**
     * The Tails fly-in is always scripted homing: {@code TailsCPU_Flying}
     * nudges x/y toward the recorded leader position and completes on its own
     * residual test (docs/s2disasm/s2.asm:39161-39232). When {@code obj_control}
     * bit 0 is clear, {@code Obj02_MdAir} additionally runs ObjectMoveAndFall
     * ({@link #requiresPhysics()}), but that gravity never re-targets the
     * approach, so the multi-sidekick root-leader-crossing completion shortcut
     * must stay off (the fly-in keeps targeting its chain leader).
     */
    @Override
    public boolean approachMovementUsesPhysics() {
        return false;
    }

    @Override
    public boolean usesS3kCatchUpMarker(AbstractPlayableSprite sidekick) {
        SidekickCpuRules rules = sidekickCpuRulesOrNull(sidekick);
        return rules != null && rules.sidekickRespawnEntersCatchUpFlight();
    }

    @Override
    public void onApproachComplete(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader) {
        // S2 TailsCPU_Flying completion writes anim=Walk and status=$02
        // (Status_InAir only), then inherits priority/solid bits from Sonic
        // (docs/s2disasm/s2.asm:39229-39245).
        sidekick.setAnimationId(walkAnimId);
        sidekick.clearRollingFlagPreserveRadii();
        sidekick.clearUnderwaterStatusPreserveWaterPhysics();
        sidekick.setOnObject(false);
        sidekick.setPushing(false);
        sidekick.setLatchedSolidObjectId(0);
        sidekick.setDirection(Direction.RIGHT);
        sidekick.setAir(true);
        sidekick.setHighPriority(leader.isHighPriority());
        sidekick.setTopSolidBit(leader.getTopSolidBit());
        sidekick.setLrbSolidBit(leader.getLrbSolidBit());
    }

    @Override
    public int consumeApproachDespawnCarryFrames() {
        int frames = offscreenFlightFrames;
        offscreenFlightFrames = 0;
        return frames;
    }

    @Override
    public int diagnosticRespawnCounter(int controllerValue) {
        return offscreenFlightFrames;
    }

    @Override
    public int diagnosticTargetX(int controllerValue) {
        return diagnosticTargetX;
    }

    @Override
    public int diagnosticTargetY(int controllerValue) {
        return diagnosticTargetY;
    }

    private boolean handleS2FlyingOffscreenTimeout(AbstractPlayableSprite sidekick) {
        SidekickCpuRules rules = sidekickCpuRulesOrNull(sidekick);
        if (rules != null && rules.sidekickRespawnEntersCatchUpFlight()) {
            return false;
        }
        boolean onScreen = sidekick.hasRenderFlagOnScreenState()
                ? sidekick.isRenderFlagOnScreen()
                : sidekick.currentCamera() != null && sidekick.currentCamera().isOnScreen(sidekick);
        if (onScreen) {
            offscreenFlightFrames = 0;
            return false;
        }
        offscreenFlightFrames++;
        if (offscreenFlightFrames < S2_FLYING_OFFSCREEN_TIMEOUT_FRAMES) {
            return false;
        }

        // S2 TailsCPU_Flying timeout writes Tails_respawn_counter=0,
        // Tails_CPU_routine=2, obj_control=$81, Status_InAir, x_pos=0,
        // y_pos=0, and fly animation (docs/s2disasm/s2.asm:38795-38806).
        // This is distinct from TailsCPU_CheckDespawn's $4000 marker.
        offscreenFlightFrames = 0;
        sidekick.clearRollingFlagPreserveRadii();
        sidekick.clearUnderwaterStatusPreserveWaterPhysics();
        sidekick.setOnObject(false);
        sidekick.setPushing(false);
        sidekick.setLatchedSolidObjectId(0);
        sidekick.setAir(true);
        sidekick.setDirection(Direction.RIGHT);
        sidekick.setCentreXPreserveSubpixel((short) 0);
        sidekick.setCentreYPreserveSubpixel((short) 0);
        sidekick.setForcedAnimationId(flyAnimId);
        sidekick.setControlLocked(true);
        ObjectControlState.nativeBit7FullControl().applyTo(sidekick);
        controller.returnApproachToSpawningAfterFlyingTimeout();
        return true;
    }

}
