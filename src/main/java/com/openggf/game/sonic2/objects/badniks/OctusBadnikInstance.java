package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Octus (0x4A) - octopus badnik from Oil Ocean Zone.
 * Waits submerged at ground level, rises when the player approaches within
 * 128 pixels, fires a horizontal bullet at its peak altitude, hovers briefly,
 * then descends back to its starting position.
 * Based on disassembly Obj4A (lines 59860-60026).
 */
public class OctusBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {

    private enum State {
        INIT,               // routine 0: Obj4A_Init, falls until it lands, then returns
        WAIT_FOR_PLAYER,    // routine_secondary 0: check player distance
        DELAY_BEFORE_RISE,  // routine_secondary 2: countdown 0x20 frames
        MOVING_UP,          // routine_secondary 4: rise with decel
        HOVERING,           // routine_secondary 6: hover 60 frames, bullet fired
        MOVING_DOWN         // routine_secondary 8: descend back to start
    }

    private static final int COLLISION_SIZE_INDEX = 0x0A; // From disassembly collision_flags $A (s2.asm:59905)
    private static final int DETECT_RANGE = 0x80; // 128 pixels
    private static final int RISE_DELAY = 0x20; // 32 frames
    private static final int INITIAL_Y_VEL = -0x200; // Rise speed
    private static final int Y_ACCEL = 0x10; // Deceleration/acceleration per frame
    // KNOWN FITTED CONSTANT -- deliberately retained, do not "correct" in isolation.
    // The ROM literal is #60 (s2.asm:60457) and Obj4A_Hover is a post-decrement/bmi
    // countdown (s2.asm:60461-60468), so the ROM hovers for 61 dispatches and the
    // faithful seed here would be 60. The engine seeds 59 because its Octus begins
    // its descent one object pass late for a cause that is still unattributed;
    // 59 cancels that lateness. Correcting it to 60 alone turns
    // TestS2OozLevelSelectTraceReplay from green to a missed enemy bounce at frame
    // 6639. Measured and RULED OUT as its partner: the missing routine-0 Init
    // dispatch (added below), the Obj4A_MoveUp transition-frame move, and the
    // Obj4A_MoveDown pre-move comparison -- none of them restores the bounce.
    private static final int HOVER_DURATION = 59;
    // ObjectMoveAndFall applies #$38 gravity after moving with the old y_vel
    // (s2.asm:30164-30177).
    private static final int OBJECT_GRAVITY = 0x38;
    private static final int BULLET_X_VEL = 0x200; // Bullet speed
    private static final int BULLET_DELAY = 0x0F; // 15 frames stationary before moving
    private static final int INIT_FLOOR_Y_RADIUS = 0x0B;

    private static final SpriteAnimationSet ANIMATIONS = createAnimations();

    private int startY;
    private boolean xFlip;
    private State state;
    private int timer;
    private final SubpixelMotion.State motionState;
    private boolean bulletFired;
    private final ObjectAnimationState animationState;

    public OctusBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Octus", Sonic2BadnikConfig.DESTRUCTION);
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        // Octus faces left by default; x_flip in spawn means face right
        this.facingLeft = !xFlip;
        // ROM Obj4A_Init falls under gravity across as many dispatches as it
        // takes to reach the floor; it is not an instantaneous snap at spawn.
        this.state = State.INIT;
        this.timer = 0;
        this.currentY = spawn.y();
        this.startY = spawn.y();
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.bulletFired = false;
        this.animationState = new ObjectAnimationState(ANIMATIONS, 0, 1);
    }

    /**
     * Obj4A_Init (docs/s2disasm/s2.asm:60380-60401).
     *
     * <p>A falling init: every dispatch runs ObjectMoveAndFall then
     * ObjCheckFloorDist, and only the dispatch on which the Octus is overlapping
     * the floor (d1 negative) corrects y_pos, zeroes y_vel, advances the routine
     * and performs the one-shot x_flip toggle. That dispatch still ends in
     * {@code rts}, so Obj4A_Main does not run until the following frame.
     * octus_start_position is rewritten every init dispatch, landed or not.
     */
    private void updateInit(AbstractPlayableSprite player) {
        motionState.y = currentY;
        motionState.yVel = yVelocity;
        SubpixelMotion.moveSprite(motionState, OBJECT_GRAVITY);
        currentY = motionState.y;
        yVelocity = motionState.yVel;

        TerrainCheckResult floor = null;
        try {
            floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, INIT_FLOOR_Y_RADIUS);
        } catch (RuntimeException ignored) {
            // Tests without a level never land; they stay in INIT, as the ROM would.
        }
        if (floor != null && floor.foundSurface() && floor.distance() < 0) {
            currentY += floor.distance();
            motionState.y = currentY;
            motionState.ySub = 0;
            yVelocity = 0;
            motionState.yVel = 0;
            state = State.WAIT_FOR_PLAYER;
            // s2.asm:60394-60397: bchg status.x_flip once, on the landing
            // dispatch, if the main character is to the right.
            if (player != null && !player.isDebugMode()
                    && (short) ((currentX - player.getCentreX()) & 0xFFFF) < 0) {
                xFlip = !xFlip;
                facingLeft = !xFlip;
            }
        }
        startY = currentY;
    }

    @Override
    public OctusBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new OctusBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case INIT -> updateInit(player);
            case WAIT_FOR_PLAYER -> updateWaitForPlayer(player);
            case DELAY_BEFORE_RISE -> updateDelayBeforeRise();
            case MOVING_UP -> updateMovingUp();
            case HOVERING -> updateHovering();
            case MOVING_DOWN -> updateMovingDown();
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animationState.update();
        animFrame = animationState.getMappingFrame();
    }

    private void updateWaitForPlayer(AbstractPlayableSprite player) {
        if (player == null || player.isDebugMode()) {
            return;
        }
        int dx = player.getCentreX() - currentX;
        if (Math.abs(dx) < DETECT_RANGE) {
            // ROM Obj4A_WaitForCharacter only advances the routine/timer here;
            // the x-flip set during Obj4A_Init is reused when firing the bullet.
            state = State.DELAY_BEFORE_RISE;
            timer = RISE_DELAY;
            animationState.setAnimId(3); // Pre-rise (antenna visible)
        }
    }

    private void updateDelayBeforeRise() {
        // ROM Obj4A_DelayBeforeMoveUp (s2.asm:59958-59967):
        //   subq.w #1, objoff_2C(a0)
        //   bmi.s +                  ; branch when timer goes NEGATIVE (after the
        //                              ; decrement), not when it hits zero
        //   rts
        // + addq.b #2, routine_secondary(a0)
        //   move.b #4, anim(a0)
        //   move.w #-$200, y_vel(a0)
        //   jmpto JmpTo19_ObjectMove ; apply -$200 y_vel via ObjectMove this frame
        timer--;
        if (timer < 0) {
            state = State.MOVING_UP;
            yVelocity = INITIAL_Y_VEL;
            animationState.setAnimId(4); // Rising animation
            // ROM falls through to ObjectMove on the transition frame, so apply
            // the initial -$200 velocity here. Without this, the Octus starts
            // 2 pixels lower than ROM throughout its rise, delaying badnik-bounce
            // hits by ~1 frame in OOZ trace replay.
            applyYMovement();
        }
    }

    private void updateMovingUp() {
        // ROM Obj4A_MoveUp (s2.asm:60450-60458):
        //   addi.w #$10,y_vel(a0)
        //   bpl.s  +                ; y_vel >= 0 -> transition, WITHOUT moving
        //   jmpto  ObjectMove       ; still rising -> move, and that is the whole
        //                             dispatch
        // + addq.b #2,routine_secondary(a0)
        //   move.w #60,objoff_2C(a0)
        //   bra.w  Obj4A_FireBullet
        // The transition dispatch performs no ObjectMove and leaves y_vel at its
        // just-incremented value; it does not zero it.
        yVelocity += Y_ACCEL;
        if (yVelocity < 0) {
            applyYMovement();
            return;
        }
        state = State.HOVERING;
        timer = HOVER_DURATION;
        fireBullet();
    }

    private void updateHovering() {
        // ROM Obj4A_Hover (s2.asm:59981-59988):
        //   subq.w #1, objoff_2C(a0)
        //   bmi.s +
        //   rts
        // + addq.b #2, routine_secondary(a0)
        //   rts
        // bmi triggers when timer goes negative, not when it reaches zero.
        timer--;
        if (timer < 0) {
            state = State.MOVING_DOWN;
            yVelocity = 0;
        }
    }

    private void updateMovingDown() {
        // ROM Obj4A_MoveDown (s2.asm:60471-60483):
        //   addi.w #$10,y_vel(a0)
        //   move.w y_pos(a0),d0
        //   cmp.w  octus_start_position(a0),d0
        //   bhs.s  +                ; already at/below start -> stop, WITHOUT moving
        //   jmpto  ObjectMove
        // + clr.b routine_secondary(a0) / clr.b anim(a0) / clr.w y_vel(a0)
        //   move.b #1,mapping_frame(a0) / rts
        // The comparison is made before the move, and the stopping dispatch does
        // not write y_pos -- the ROM leaves the Octus wherever it came to rest
        // rather than snapping it back to octus_start_position.
        yVelocity += Y_ACCEL;
        if (currentY < startY) {
            applyYMovement();
            return;
        }
        state = State.WAIT_FOR_PLAYER;
        yVelocity = 0;
        bulletFired = false;
        animationState.setAnimId(0);
        animFrame = 1;
    }

    private void applyYMovement() {
        motionState.y = currentY;
        motionState.yVel = yVelocity;
        SubpixelMotion.moveSprite2(motionState);
        currentY = motionState.y;
    }

    private void fireBullet() {
        if (bulletFired) {
            return;
        }
        bulletFired = true;

        // Bullet fires in the direction the octus is facing
        int bulletXVel = facingLeft ? -BULLET_X_VEL : BULLET_X_VEL;
        boolean bulletHFlip = !facingLeft;

        spawnFreeChild(() -> new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.OCTUS_BULLET,
                currentX,
                currentY,
                bulletXVel,
                0,          // No vertical velocity
                false,      // No gravity
                bulletHFlip,
                BULLET_DELAY));
    }

    /**
     * Test-only: run the routine-0 Init dispatch that Obj4A_Init occupies in the
     * ROM, so a unit test can observe the landed state that used to be produced
     * in the constructor. Setup only -- it runs exactly the production init path.
     */
    void testRunInitDispatch(AbstractPlayableSprite player) {
        updateInit(player);
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.OCTUS);
        if (renderer == null) return;

        // Art faces left by default. When xFlip is set in spawn, face right.
        // facingLeft=true means default orientation (no flip needed).
        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }

    /**
     * Animation scripts from Ani_obj4A (disassembly lines 60030-60045).
     */
    private static SpriteAnimationSet createAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Idle/submerged - dc.b $F, 1, 0, $FF
        set.addScript(0, new SpriteAnimationScript(
                0x0F,
                List.of(1, 0),
                SpriteAnimationEndAction.LOOP,
                0));

        // Anim 1: Alert (unused in final, beta leftover) - dc.b 3, 1, 2, 3, $FF
        set.addScript(1, new SpriteAnimationScript(
                3,
                List.of(1, 2, 3),
                SpriteAnimationEndAction.LOOP,
                0));

        // Anim 2: Bullet projectile - dc.b 2, 5, 6, $FF
        set.addScript(2, new SpriteAnimationScript(
                2,
                List.of(5, 6),
                SpriteAnimationEndAction.LOOP,
                0));

        // Anim 3: Pre-rise (antenna visible) - dc.b $F, 4, $FF
        set.addScript(3, new SpriteAnimationScript(
                0x0F,
                List.of(4),
                SpriteAnimationEndAction.LOOP,
                0));

        // Anim 4: Rising - dc.b 7, 0, 1, $FE, 1
        set.addScript(4, new SpriteAnimationScript(
                7,
                List.of(0, 1),
                SpriteAnimationEndAction.LOOP_BACK,
                1));

        return set;
    }
}
