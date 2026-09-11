package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Clucker (Object 0xAE) - Chicken turret badnik from WFZ.
 * Sits on a CluckerBase (0xAD), rises when player is nearby, shoots projectiles.
 *
 * ROM Reference: s2.asm lines 76808-76960 (ObjAE)
 *
 * State machine (7 routines, indexed by routine byte):
 *   Routine 0 ($00): Init - LoadSubObject, set frame $15, copy x_flip to status
 *   Routine 2 ($02): Check distance - detect player within $100 range, advance to 4 if close
 *   Routine 4 ($04): Rise animation - Ani_objAE_a (frames 0-7, duration 1), then set
 *                     frame 8, enable collision_flags=6, advance to routine 6
 *   Routine 6 ($06): Shoot animation - Ani_objAE_b (frames 8,9,$A,$B,$B,$B,$B, duration 1)
 *   Routine 8 ($08): Wait timer - countdown objoff_2A, then fire projectile, advance to $A
 *   Routine A ($0A): Fire animation - Ani_objAE_c (frames $A,$B, duration 3)
 *   Routine C ($0C): Reset - set routine=8, timer=$40, loop back
 *
 * SubObjData (subtype $44): ObjAD_SubObjData2
 *   mappings = ObjAD_Obj98_MapUnc_395B4
 *   art_tile = ArtTile_ArtNem_WfzScratch (palette 0)
 *   render_flags = level_fg
 *   priority = 5
 *   width_pixels = $10
 *   collision_flags = 0 (set to 6 during routine 4 transition)
 *
 * Projectile: Obj98 subtype $46 (ObjAD_SubObjData3)
 *   mapping_frame = $D (frame 13)
 *   x_vel = -$200 (or +$200 if x_flip)
 *   y_pos offset = +$B
 *   x_pos offset = -8 (or +8 if x_flip)
 */
public class CluckerBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {

    // Collision size index from disassembly: collision_flags = 6
    // Set during routine 4->6 transition (move.b #6,collision_flags(a0))
    private static final int COLLISION_SIZE_INDEX = 0x06;

    // Player detection range from disassembly
    // Obj_GetOrientationToPlayer returns d2 = signed distance
    // addi.w #$80,d2 / cmpi.w #$100,d2 / blo.s = branch if (d2+$80) < $100
    // This means: abs(distance) < $80 (128 pixels)
    private static final int DETECT_RANGE = 0x80;

    // Projectile constants from disassembly loc_39526
    private static final int SHOT_X_VEL = 0x200;   // move.w #-$200,d0
    private static final int SHOT_X_OFFSET = 8;     // move.w #-8,d1
    private static final int SHOT_Y_OFFSET = 0x0B;  // addi.w #$B,y_pos(a1)

    // Wait timer from disassembly loc_39516
    private static final int WAIT_TIMER = 0x40;     // move.b #$40,objoff_2A(a0)

    /**
     * State machine matching the 7 ROM routines.
     */
    private enum State {
        /** Routine 0: Init (handled in constructor) */
        INIT,
        /** Routine 2: Check player distance */
        CHECK_DISTANCE,
        /** Routine 4: Rise animation (Ani_objAE_a) */
        RISING,
        /** Routine 6: Shoot animation (Ani_objAE_b) */
        SHOOTING,
        /** Routine 8: Wait timer countdown */
        WAITING,
        /** Routine A: Fire animation (Ani_objAE_c) */
        FIRING,
        /** Routine C: Reset to WAITING */
        RESET
    }

    private State state;
    private boolean collisionEnabled;
    private int waitTimer;

    // Animation state
    private int animationIndex;   // Current position in animation sequence
    private int animDuration;     // Frames remaining for current animation frame

    // Rising animation: Ani_objAE_a = dc.b 1, 0, 1, 2, 3, 4, 5, 6, 7, $FC
    // Duration 1 (2 frames per step), frames 0-7, end $FC = advance routine
    private static final int[] ANIM_RISE = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final int ANIM_RISE_DURATION = 1;

    // Shoot animation: Ani_objAE_b = dc.b 1, 8, 9, $A, $B, $B, $B, $B, $FC
    // Duration 1, frames 8-11 (with $B repeated), end $FC = advance routine
    private static final int[] ANIM_SHOOT = {8, 9, 10, 11, 11, 11, 11};
    private static final int ANIM_SHOOT_DURATION = 1;

    // Fire animation: Ani_objAE_c = dc.b 3, $A, $B, $FC
    // Duration 3, frames 10-11, end $FC = advance routine
    private static final int[] ANIM_FIRE = {10, 11};
    private static final int ANIM_FIRE_DURATION = 3;

    public CluckerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Clucker", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // ObjAE_Init: btst #render_flags.x_flip / bset #status.npc.x_flip
        this.facingLeft = (spawn.renderFlags() & 0x01) == 0;

        // Start at routine 2 (Init handled in constructor, mapping_frame=$15)
        this.state = State.CHECK_DISTANCE;
        this.animFrame = 21; // mapping_frame = $15 (hidden/initial frame)
        this.collisionEnabled = false;
        this.waitTimer = 0;
        this.animationIndex = 0;
        this.animDuration = 0;
    }

    @Override
    public CluckerBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CluckerBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case CHECK_DISTANCE -> updateCheckDistance(player);
            case RISING -> updateRising();
            case SHOOTING -> updateShooting();
            case WAITING -> updateWaiting();
            case FIRING -> updateFiring();
            case RESET -> updateReset();
            default -> { /* INIT handled in constructor */ }
        }
    }

    /**
     * Routine 2: Check if player is within detection range.
     * ROM: Obj_GetOrientationToPlayer, addi.w #$80,d2, cmpi.w #$100,d2
     */
    private void updateCheckDistance(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        int dx = currentX - player.getCentreX();
        // ROM uses signed distance + $80, checks if < $100
        // Equivalent: abs(dx) < $80
        if (Math.abs(dx) < DETECT_RANGE) {
            // Player is close enough - start rising
            // Don't set animFrame immediately; ROM advances routine then AnimateSprite
            // runs on the NEXT frame. Set index=-1 and duration=0 so advanceAnimation
            // loads the first frame on the next update tick.
            state = State.RISING;
            animationIndex = -1;
            animDuration = 0;
        }
    }

    /**
     * Routine 4: Play rise animation (Ani_objAE_a).
     * On completion ($FC): set frame 8, collision_flags=6, advance to routine 6.
     */
    private void updateRising() {
        if (advanceAnimation(ANIM_RISE, ANIM_RISE_DURATION)) {
            // Animation complete ($FC = advance routine)
            // ROM: clr.l mapping_frame area, set mapping_frame=8, collision_flags=6
            // Don't set animFrame immediately - let advanceAnimation load it next tick
            collisionEnabled = true;
            state = State.SHOOTING;
            animationIndex = -1;
            animDuration = 0;
        }
    }

    /**
     * Routine 6: Play shoot animation (Ani_objAE_b).
     * On completion ($FC): advance to routine 8 (WAITING).
     */
    private void updateShooting() {
        if (advanceAnimation(ANIM_SHOOT, ANIM_SHOOT_DURATION)) {
            // Animation complete - transition to waiting
            state = State.WAITING;
            waitTimer = 0; // First time: no wait, fire immediately (timer starts at 0)
        }
    }

    /**
     * Routine 8: Wait timer countdown.
     * When expired: fire projectile, set frame $B, advance to routine $A.
     */
    private void updateWaiting() {
        if (waitTimer > 0) {
            waitTimer--;
            return;
        }

        // Timer expired - advance to firing state
        // ROM: set mapping_frame=$B, then call loc_39526 (fire projectile)
        // Don't set animFrame immediately - let advanceAnimation load it next tick
        state = State.FIRING;
        animationIndex = -1;
        animDuration = 0;
        fireProjectile();
    }

    /**
     * Routine A: Play fire animation (Ani_objAE_c).
     * On completion ($FC): advance to routine $C (RESET).
     */
    private void updateFiring() {
        if (advanceAnimation(ANIM_FIRE, ANIM_FIRE_DURATION)) {
            // Animation complete - reset
            state = State.RESET;
        }
    }

    /**
     * Routine C: Reset to WAITING with timer $40.
     * ROM: move.b #8,routine(a0) / move.b #$40,objoff_2A(a0)
     */
    private void updateReset() {
        state = State.WAITING;
        waitTimer = WAIT_TIMER;
    }

    /**
     * Fire a projectile bullet.
     * ROM: loc_39526 - AllocateObjectAfterCurrent, create Obj98 with subtype $46.
     */
    private void fireProjectile() {
        // Calculate projectile position and velocity based on facing direction
        int xVel;
        int xOffset;
        if (facingLeft) {
            // Default (not x_flipped): shoot left
            xVel = -SHOT_X_VEL;
            xOffset = -SHOT_X_OFFSET;
        } else {
            // x_flipped: shoot right (neg.w d0 / neg.w d1)
            xVel = SHOT_X_VEL;
            xOffset = SHOT_X_OFFSET;
        }

        spawnChild(() -> {
            BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                    spawn,
                    BadnikProjectileInstance.ProjectileType.CLUCKER_SHOT,
                    currentX + xOffset,
                    currentY + SHOT_Y_OFFSET,
                    xVel,
                    0, // y_vel = 0 (Obj98_CluckerShotMove uses ObjectMove, no gravity)
                    false,
                    !facingLeft);
            projectile.deferFirstMovementForLoadSubObjectInit();
            return projectile;
        });
    }

    /**
     * Advance an animation sequence. Returns true when the sequence is complete.
     *
     * @param frames   Array of mapping frame indices
     * @param duration Frames per animation step (dc.b duration value)
     * @return true if animation reached end ($FC marker)
     */
    private boolean advanceAnimation(int[] frames, int duration) {
        animDuration--;
        if (animDuration < 0) {
            animDuration = duration;
            animationIndex++;
            if (animationIndex >= frames.length) {
                return true; // End of sequence
            }
            animFrame = frames[animationIndex];
        }
        return false;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Animation is driven by the state machine in updateMovement
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getCollisionFlags() {
        if (!collisionEnabled) {
            return 0; // No collision until risen (collision_flags initially 0)
        }
        return super.getCollisionFlags();
    }

    @Override
    public int getPriorityBucket() {
        // ObjAD_SubObjData2: priority = 5
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CLUCKER);
        if (renderer == null) return;

        // Sprite art default faces left. Flip when facing right (x_flip set).
        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(currentX, currentY, 16, 16, 1f, 1f, 0f);

        String stateStr = state.name();
        String dir = facingLeft ? "L" : "R";
        String label = "Clucker " + stateStr + " f" + animFrame + " " + dir;
        if (collisionEnabled) {
            label += " [COL]";
        }
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.YELLOW);
    }
}
