package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.PatrolMovementHelper;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Shellcracker (0x9F) - Crab badnik from Metropolis Zone.
 * Walks back and forth on platforms, detects the player, stops and extends
 * its claw arm to attack, then retracts and resumes walking.
 *
 * Based on disassembly Obj9F (s2.asm:74955).
 *
 * State machine:
 *   Routine 0 (INIT): LoadSubObject, set velocity/radii, transition to WALKING
 *   Routine 2 (WALKING): Walk, check floor, detect player for attack. If player
 *     is facing and within horizontal range, go to ATTACK_WAIT. If floor edge
 *     or timer expires, go to PAUSED.
 *   Routine 4 (PAUSED): Wait timer (0x3B), check player, then resume walking.
 *   Routine 6 (ATTACKING): Sub-state machine:
 *     Sub 0: Wait 8 frames, then spawn claw arm pieces and set frame 3
 *     Sub 2: Wait for claw to signal completion (objoff_2C flag)
 *     Sub 4: Wait 0x20 frames, then reset to walking
 */
public class ShellcrackerBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    // From Obj9F_SubObjData: collision_flags = $A (enemy, size index 10)
    private static final int COLLISION_SIZE_INDEX = 0x0A;

    // From disassembly: move.w #-$40,x_vel(a0)
    private static final int WALK_SPEED = 0x40;

    // From disassembly: move.b #$C,y_radius(a0)
    private static final int Y_RADIUS = 0x0C;

    // From disassembly: move.w #$140,objoff_2A(a0) (walk timer)
    private static final int WALK_TIMER_INIT = 0x140;

    // From disassembly: move.w #$3B,objoff_2A(a0) (pause timer)
    private static final int PAUSE_TIMER = 0x3B;

    // From disassembly: move.w #8,objoff_2A(a0) (attack wait before detect)
    private static final int ATTACK_WAIT_TIMER_SHORT = 8;

    // From disassembly: move.w #$20,objoff_2A(a0) (post-attack delay)
    private static final int POST_ATTACK_DELAY = 0x20;

    // Detection range: addi.w #$60,d2 / cmpi.w #$C0,d2 → horizontal +-$60
    private static final int DETECT_RANGE_HALF = 0x60;

    // Floor snap thresholds from disassembly:
    // cmpi.w #-8,d1 / blt.s / cmpi.w #$C,d1 / bge.s
    private static final int FLOOR_MIN_DIST = -8;
    private static final int FLOOR_MAX_DIST = 0x0C;

    // Animation: Ani_obj9F anim 0 = {$E, 0, 1, 2, $FF, 0}
    // Speed = $E + 1 = 15 frames per transition, frames: 0, 1, 2, loop to 0
    private static final int WALK_ANIM_SPEED = 0x0E + 1;
    private static final int[] WALK_ANIM_FRAMES = {0, 1, 2};

    // Number of claw pieces spawned (8 segments: d6=7 → dbf loops 8 times)
    private static final int CLAW_PIECE_COUNT = 8;

    // Claw piece spawn X offset from disassembly: move.w #-$14,d2
    private static final int CLAW_SPAWN_X_OFFSET = -0x14;
    // Additional offset for non-first piece facing right: subi.w #$C,d2
    private static final int CLAW_SPAWN_X_EXTRA = -0x0C;
    // Claw piece spawn Y offset: subi_.w #8,y_pos(a1)
    private static final int CLAW_SPAWN_Y_OFFSET = -8;

    private enum State {
        WALKING,        // Routine 2: walk + detect player
        PAUSED,         // Routine 4: pause, then reverse direction
        ATTACK_WAIT,    // Routine 6, sub 0: wait before spawning claws
        ATTACK_EXTEND,  // Routine 6, sub 2: wait for claw signal
        ATTACK_RETRACT  // Routine 6, sub 4: post-attack delay
    }

    private State state;
    private int timer;
    private int xSubpixel;
    private int walkAnimIndex;
    private int walkAnimTimer;
    private boolean clawDone; // Set by claw piece 0 when retracted (objoff_2C equivalent)

    public ShellcrackerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Shellcracker", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // ROM: move.w #-$40,x_vel(a0) → always starts moving LEFT
        // ROM: btst x_flip / bset status.npc.x_flip → x_flip only affects rendering
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.xVelocity = -WALK_SPEED; // Always starts moving left

        this.state = State.WALKING;
        this.timer = WALK_TIMER_INIT;
        this.walkAnimIndex = 0;
        this.walkAnimTimer = WALK_ANIM_SPEED;
        this.clawDone = false;
    }

    @Override
    public ShellcrackerBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new ShellcrackerBadnikInstance(ctx.spawn());
    }

    /**
     * Called by the first claw piece (piece index 0) when it finishes retracting.
     * Equivalent to ROM: st.b objoff_2C(a1) where a1 is the parent Shellcracker.
     */
    public void signalClawDone() {
        this.clawDone = true;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case WALKING -> updateWalking(player);
            case PAUSED -> updatePaused(player);
            case ATTACK_WAIT -> updateAttackWait();
            case ATTACK_EXTEND -> updateAttackExtend();
            case ATTACK_RETRACT -> updateAttackRetract();
        }
    }

    /**
     * Routine 2 (loc_3804E): Walk, detect player, check floor.
     *
     * Obj_GetOrientationToPlayer returns:
     *   d0 = 0 if dx >= 0 (player is to the LEFT of crab), d0 = 2 if dx < 0 (player RIGHT)
     *   d2 = obj.x - player.x
     *
     * Detection logic (ROM):
     *   if d0 == 0 (player LEFT): always check range
     *   if d0 != 0 (player RIGHT): check range only if x_flip set (facing RIGHT)
     *   i.e. detect when player is LEFT, or player is RIGHT and crab faces RIGHT
     *
     * Range check: addi.w #$60,d2 / cmpi.w #$C0,d2 → |dx| < $60
     * If in range → go to ATTACK_WAIT (routine 6) with timer 8
     */
    private void updateWalking(AbstractPlayableSprite player) {
        if (player != null) {
            int dx = currentX - player.getCentreX();
            // d0 = 0 if dx >= 0 (player LEFT), d0 = 2 if dx < 0 (player RIGHT)
            boolean playerIsLeft = dx >= 0;

            // ROM: tst.w d0 / beq.s loc_3805E → player LEFT always goes to range check
            // ROM: btst x_flip / beq.s loc_38068 → player RIGHT + x_flip clear → skip
            // facingLeft = true when x_flip is clear (art default = faces left)
            boolean shouldDetect = playerIsLeft || !facingLeft;

            if (shouldDetect) {
                // Range check: (dx + $60) unsigned < $C0, i.e. -$60 <= dx < $60
                int adjusted = dx + DETECT_RANGE_HALF;
                if (adjusted >= 0 && adjusted < DETECT_RANGE_HALF * 2) {
                    // Player detected → transition to attack
                    // ROM: move.b #6,routine(a0) / move.w #8,objoff_2A(a0)
                    state = State.ATTACK_WAIT;
                    animFrame = 0;
                    timer = ATTACK_WAIT_TIMER_SHORT;
                    return;
                }
            }
        }

        // ObjectMove + ObjCheckFloorDist via PatrolMovementHelper
        var patrol = PatrolMovementHelper.updatePatrol(
                currentX, xSubpixel, currentY, xVelocity, Y_RADIUS, FLOOR_MIN_DIST, FLOOR_MAX_DIST);
        currentX = patrol.newX();
        xSubpixel = patrol.newXSub();
        currentY = patrol.newY();

        if (patrol.reversed()) {
            // No valid floor → reverse velocity (no facing change - crab walks sideways)
            // ROM: neg.w x_vel(a0) at loc_38096, then fall through to transitionToPaused
            // Note: ROM does NOT toggle x_flip here, unlike Slicer
            xVelocity = -xVelocity;
            transitionToPaused();
        } else {
            // Decrement walk timer
            timer--;
            if (timer < 0) {
                // Timer expired → pause
                transitionToPaused();
            } else {
                // Animate and continue (Ani_obj9F anim 0)
                updateWalkAnimation();
            }
        }
    }

    /**
     * ROM: loc_3809A - transition to paused state (routine 4).
     */
    private void transitionToPaused() {
        state = State.PAUSED;
        animFrame = 0;
        timer = PAUSE_TIMER;
    }

    /**
     * Routine 4 (loc_380C4): Wait, optionally check player, then resume walking.
     *
     * While paused and on-screen, checks if player is in front and within range.
     * If so, transitions to ATTACK_WAIT (same as walking detection).
     * Otherwise counts down timer, then resumes walking with reversed direction.
     */
    private void updatePaused(AbstractPlayableSprite player) {
        // ROM: _btst #render_flags.on_screen / _beq.s loc_380E4
        if (isOnScreenX() && player != null) {
            int dx = currentX - player.getCentreX();
            boolean playerIsLeft = dx >= 0;

            // Same detection logic as walking
            // ROM: tst.w d0 / beq.s loc_380DA / btst x_flip / beq.s loc_380E4
            boolean shouldDetect = playerIsLeft || !facingLeft;

            if (shouldDetect) {
                int adjusted = dx + DETECT_RANGE_HALF;
                if (adjusted >= 0 && adjusted < DETECT_RANGE_HALF * 2) {
                    // Player in range → attack
                    state = State.ATTACK_WAIT;
                    animFrame = 0;
                    timer = ATTACK_WAIT_TIMER_SHORT;
                    return;
                }
            }
        }

        // Count down timer
        timer--;
        if (timer < 0) {
            // ROM: subq.b #2,routine(a0) → back to routine 2 (WALKING)
            // ROM: move.w #$140,objoff_2A(a0)
            state = State.WALKING;
            timer = WALK_TIMER_INIT;
        }
    }

    /**
     * Routine 6, sub 0 (loc_38114): Wait timer, then spawn claw pieces.
     */
    private void updateAttackWait() {
        timer--;
        if (timer < 0) {
            // ROM: addq.b #2,routine_secondary(a0) → sub 2
            // ROM: move.b #3,mapping_frame(a0) → attack frame
            state = State.ATTACK_EXTEND;
            animFrame = 3;
            clawDone = false;
            spawnClawPieces();
        }
    }

    /**
     * Routine 6, sub 2 (loc_3812A): Wait for claw to signal done.
     */
    private void updateAttackExtend() {
        // ROM: tst.b objoff_2C(a0) / bne.s +
        if (clawDone) {
            // ROM: addq.b #2,routine_secondary(a0) → sub 4
            // ROM: move.w #$20,objoff_2A(a0)
            state = State.ATTACK_RETRACT;
            timer = POST_ATTACK_DELAY;
        }
    }

    /**
     * Routine 6, sub 4 (loc_3813E): Post-attack delay, then resume walking.
     */
    private void updateAttackRetract() {
        timer--;
        if (timer < 0) {
            // ROM: clr.b routine_secondary / clr.b objoff_2C
            // ROM: move.b #2,routine(a0) → back to walking
            // ROM: move.w #$140,objoff_2A(a0)
            state = State.WALKING;
            animFrame = 0;
            clawDone = false;
            timer = WALK_TIMER_INIT;
        }
    }

    /**
     * Spawns 8 claw arm pieces (loc_38292).
     * ROM loops d6=7 (dbf → 8 iterations), d1 starts at 0, increments by 2.
     * Each piece gets objoff_2E = d1 (piece index * 2).
     * Piece 0: frame 5, at body position + x offset
     * Pieces 1-7: frame 5, at body position + x offset (adjusted for facing)
     * All pieces positioned at (x + offset, y - 8).
     */
    private void spawnClawPieces() {
        for (int i = 0; i < CLAW_PIECE_COUNT; i++) {
            final int pieceIndex = i * 2; // d1 = 0, 2, 4, 6, 8, 10, 12, 14

            // Calculate X offset
            // ROM: move.w #-$14,d2
            int xOff = CLAW_SPAWN_X_OFFSET;
            // ROM: btst #render_flags.x_flip / beq.s + / neg.w d2
            if (!facingLeft) {
                xOff = -xOff;
            }
            // ROM: tst.w d1 / beq.s + (skip extra offset for piece 0)
            // ROM: subi.w #$C,d2 (only when x_flip set AND pieceIndex != 0)
            if (!facingLeft && pieceIndex != 0) {
                xOff += CLAW_SPAWN_X_EXTRA;
            }

            // ROM (s2.asm:75284-75296) copies the body's ROM-center x_pos/y_pos to each
            // claw. Badnik currentX/currentY are already ROM-center coords (the same
            // values used by the currentX - player.getCentreX() detection checks above),
            // so they map directly onto the child's spawn position — no center
            // adjustment needed.
            final int pieceX = currentX + xOff;
            final int pieceY = currentY + CLAW_SPAWN_Y_OFFSET;

            // ROM (s2.asm:75271-75301): 8x ObjA0 spawned as children of the body.
            spawnChild(() -> new ShellcrackerClawInstance(
                    spawn, this, pieceX, pieceY, pieceIndex, !facingLeft));
        }
    }

    private void updateWalkAnimation() {
        walkAnimTimer--;
        if (walkAnimTimer <= 0) {
            walkAnimTimer = WALK_ANIM_SPEED;
            walkAnimIndex = (walkAnimIndex + 1) % WALK_ANIM_FRAMES.length;
        }
        animFrame = WALK_ANIM_FRAMES[walkAnimIndex];
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Animation state is set directly in state machine methods
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        // From Obj9F_SubObjData: priority = 5
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.SHELLCRACKER);
        if (renderer == null || !renderer.isReady()) return;

        // Sprite art faces left by default; flip when facing right
        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        super.appendDebugRenderCommands(ctx);
        String stateLabel = "Shellcracker " + state + " f" + animFrame + " t" + timer;
        ctx.drawWorldLabel(currentX, currentY, -12, stateLabel, DebugColor.YELLOW);
    }
}
