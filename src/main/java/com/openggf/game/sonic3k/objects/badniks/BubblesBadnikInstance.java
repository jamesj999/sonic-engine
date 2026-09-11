package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.physics.SwingMotion;

/**
 * S3K Obj $9B — Bubbles Badnik (MGZ).
 *
 * <p>A pogo-stick badnik that oscillates vertically via {@link SwingMotion} while drifting
 * horizontally. On reaching the bottom of its swing, it transitions to a bouncy pogo
 * animation, reversing direction on each swing peak. Returns to the swing state via a
 * scripted animation callback.
 *
 * <p>Based on {@code Obj_BubblesBadnik} (sonic3k.asm, lines 184598–184707).
 *
 * <h3>State machine (ROM routines 0/2/4):</h3>
 * <ul>
 *   <li>INIT (routine 0): Set x_vel by render_flags bit 0, y_vel/threshold=$100, accel=2.</li>
 *   <li>SWING (routine 2): Animate via {@code byte_8844D}, {@code Swing_UpAndDown},
 *       transition to POGO when y_vel crosses zero from a non-negative previous value.</li>
 *   <li>POGO (routine 4): Animate via {@code byte_8843E} (plays {@code sfx_ChainTick} at
 *       anim_frame=4); continue swinging; reverse x_vel and facing on swing direction flip;
 *       return to SWING via $F4 callback ({@code loc_88414}).</li>
 * </ul>
 *
 * <h3>Collision:</h3>
 * {@code $12} (ENEMY, size $12) — defeatable by attack. During pogo mapping_frame 4
 * (extended body), becomes {@code $86} (HURT, size $06) — damages player on contact.
 */
public final class BubblesBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly (sonic3k.asm:184610-184613):
    //   move.b #$12,collision_flags(a0)          ; default: attackable, size $12
    //   cmpi.b #4,mapping_frame(a0) / bne.s
    //   move.b #$86,collision_flags(a0)           ; frame 4: HURT, size $06
    private static final int COLLISION_FLAGS_NORMAL = 0x12;
    private static final int COLLISION_FLAGS_HURT = 0x86;
    private static final int HURT_MAPPING_FRAME = 4;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;

    // ObjSlot_BubblesBadnik: dc.w $280 → priority bucket 5
    private static final int PRIORITY_BUCKET = 5;

    // Init constants (loc_88370):
    //   move.w #-$80,x_vel(a0)                    ; base horizontal drift
    //   move.w #$100,d0 / move.w d0,y_vel(a0)     ; initial y velocity
    //                     move.w d0,$3E(a0)       ; swing threshold
    //   move.w #2,$40(a0)                         ; swing acceleration
    private static final int INITIAL_X_VELOCITY = 0x80;
    private static final int INITIAL_Y_VELOCITY = 0x100;
    private static final int SWING_MAX_VELOCITY = 0x100;
    private static final int SWING_ACCELERATION = 2;

    // Animation scripts — (mapping_frame, delay) pairs from sonic3k.asm:184690-184706.
    // byte_8844D (swing): 0,$7F / 3,3 / 4,$6B / 4,$6B / 3,3 / 0,$7F / $FC (loop to start)
    private static final int[] SWING_FRAMES = {0, 3, 4, 4, 3, 0};
    private static final int[] SWING_DELAYS = {0x7F, 3, 0x6B, 0x6B, 3, 0x7F};
    // byte_8843E (pogo): 0,$F / 0,$77 / 1,3 / 2,3 / 2,3 / 1,3 / 0,$77 / $F4 (callback)
    private static final int[] POGO_FRAMES = {0, 0, 1, 2, 2, 1, 0};
    private static final int[] POGO_DELAYS = {0x0F, 0x77, 3, 3, 3, 3, 0x77};

    // ROM: cmpi.b #4,anim_frame(a0) — SFX triggers when anim_frame byte offset reaches 4
    // (advanced to pair index 2, the first mapping_frame=1 entry of the pogo script).
    private static final int POGO_SFX_ANIM_INDEX = 4;

    private enum State { INIT, SWING, POGO }

    private State state = State.INIT;
    // ROM: bit 0 of $38(a0). false = Swing_UpAndDown applies upward (negative) accel.
    private boolean swingDown;
    // ROM: anim_frame (byte offset into script; increments by 2 per advance).
    private int animIndex;
    // ROM: anim_frame_timer.
    private int animTimer;
    private boolean waitingForOnscreen = true;

    public BubblesBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Bubbles Badnik",
                Sonic3kObjectArtKeys.MGZ_BUBBLES_BADNIK, COLLISION_FLAGS_NORMAL, PRIORITY_BUCKET);
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) return;

        // Obj_BubblesBadnik starts with Obj_WaitOffscreen (sonic3k.asm:184598,
        // helper at 180266-180298). Its own routine does not run, and
        // collision_flags is not written, until Render_Sprites marks it visible.
        if (waitingForOnscreen) {
            if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                updateDynamicSpawn(currentX, currentY);
                return;
            }
            waitingForOnscreen = false;
        }

        switch (state) {
            case INIT -> initialize();
            case SWING -> updateSwing();
            case POGO -> updatePogo();
        }
    }

    /**
     * Routine 0 (loc_88370). Initializes velocities and enters the swing state.
     * <pre>
     * move.w #-$80,x_vel(a0)
     * btst   #0,render_flags(a0)
     * beq.s  loc_8838A
     * neg.w  x_vel(a0)                          ; render_flags bit 0 set → face/move right
     * loc_8838A:
     * move.l #byte_8844D,$30(a0)
     * move.w #$100,d0
     * move.w d0,$3E(a0)
     * move.w d0,y_vel(a0)
     * move.w #2,$40(a0)
     * bclr   #0,$38(a0)                         ; swing direction = up
     * </pre>
     */
    private void initialize() {
        // AbstractS3kBadnikInstance resolves facingLeft from render_flags bit 0.
        // ROM: bit clear (facingLeft=true) → x_vel = -$80 (move left).
        xVelocity = facingLeft ? -INITIAL_X_VELOCITY : INITIAL_X_VELOCITY;
        yVelocity = INITIAL_Y_VELOCITY;
        swingDown = false;
        animIndex = 0;
        animTimer = 0;
        mappingFrame = 0;
        state = State.SWING;
    }

    /**
     * Routine 2 (loc_883AC). Swing up/down while drifting horizontally; transition
     * to pogo when y_vel crosses zero from a non-negative previous value (bottom of swing).
     * <pre>
     * jsr    Animate_RawMultiDelay(pc)
     * move.w y_vel(a0),d4
     * jsr    Swing_UpAndDown(pc)
     * tst.w  y_vel(a0)
     * bne.s  loc_883E0
     * tst.w  d4
     * bmi.s  loc_883E0
     * move.b #4,routine(a0)
     * move.l #byte_8843E,$30(a0)
     * move.l #loc_88414,$34(a0)
     * clr.b  anim_frame(a0)
     * clr.b  anim_frame_timer(a0)
     * loc_883E0:
     * jmp    (MoveSprite2).l
     * </pre>
     */
    private void updateSwing() {
        advanceAnimation(SWING_FRAMES, SWING_DELAYS, false);

        int previousYVelocity = yVelocity;
        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCELERATION, yVelocity, SWING_MAX_VELOCITY, swingDown);
        yVelocity = result.velocity();
        swingDown = result.directionDown();

        if (yVelocity == 0 && previousYVelocity >= 0) {
            state = State.POGO;
            animIndex = 0;
            animTimer = 0;
        }

        moveWithVelocity();
    }

    /**
     * Routine 4 (loc_883E6). Pogo bounce: animate, trigger SFX on mid-extension,
     * continue swinging, and on swing direction flip reverse x_vel and facing.
     * <pre>
     * jsr   Animate_RawMultiDelay(pc)
     * beq.s loc_883FC                          ; d2==0 → skip SFX
     * cmpi.b #4,anim_frame(a0)
     * bne.s loc_883FC
     * moveq #sfx_ChainTick,d0
     * jsr   (Play_SFX).l
     * loc_883FC:
     * jsr   Swing_UpAndDown(pc)
     * tst.w d3                                  ; direction flipped?
     * beq.s loc_8840E
     * neg.w x_vel(a0)
     * bchg  #0,render_flags(a0)
     * loc_8840E:
     * jmp   (MoveSprite2).l
     * </pre>
     */
    private void updatePogo() {
        boolean advanced = advanceAnimation(POGO_FRAMES, POGO_DELAYS, true);
        if (state != State.POGO) {
            // $F4 callback fired during advance; swing routine owns this frame now.
            return;
        }
        if (advanced && animIndex == POGO_SFX_ANIM_INDEX) {
            services().playSfx(Sonic3kSfx.CHAIN_TICK.id);
        }

        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCELERATION, yVelocity, SWING_MAX_VELOCITY, swingDown);
        yVelocity = result.velocity();
        swingDown = result.directionDown();

        if (result.directionChanged()) {
            xVelocity = -xVelocity;
            facingLeft = !facingLeft;
        }

        moveWithVelocity();
    }

    /**
     * Port of Animate_RawMultiDelay (sonic3k.asm:177558). Decrements
     * anim_frame_timer; when it underflows, advances anim_frame by 2 and loads the
     * next (mapping_frame, delay) pair. Script pair 0 is only re-read via the $FC
     * loop command — normal advances start at pair 1. Returns true on a successful
     * frame advance (matches ROM d2=1).
     *
     * @param hasCallback {@code true} for the pogo script ($F4 terminator invokes the
     *     {@code loc_88414} callback to re-enter the swing state); {@code false} for
     *     the swing script ($FC loop back to start).
     */
    private boolean advanceAnimation(int[] frames, int[] delays, boolean hasCallback) {
        animTimer--;
        if (animTimer >= 0) {
            return false;
        }
        animIndex += 2;
        int pairIdx = animIndex >> 1;
        if (pairIdx >= frames.length) {
            if (hasCallback) {
                onPogoAnimationEnd();
                return false;
            }
            // $FC loop: reset anim_frame and explicitly load pair 0 (loc_845F2).
            animIndex = 0;
            mappingFrame = frames[0];
            animTimer = delays[0];
            return true;
        }
        mappingFrame = frames[pairIdx];
        animTimer = delays[pairIdx];
        return true;
    }

    /**
     * Pogo $F4 callback (loc_88414). Returns to the swing state; anim_frame is
     * cleared by Animate_RawMultiDelay's post-callback {@code clr.b anim_frame(a0)}
     * (loc_845CC). anim_frame_timer is left at its post-decrement value ($FF);
     * the next SWING tick will roll it negative and advance immediately.
     */
    private void onPogoAnimationEnd() {
        state = State.SWING;
        animIndex = 0;
    }

    @Override
    public int getCollisionFlags() {
        if (waitingForOnscreen) {
            return 0;
        }
        if (mappingFrame == HURT_MAPPING_FRAME) {
            return COLLISION_FLAGS_HURT;
        }
        return COLLISION_FLAGS_NORMAL;
    }
}
