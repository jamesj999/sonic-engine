package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * S3K Obj $98 — Poindexter (HCZ).
 *
 * <p>A hovering puffer-fish badnik that drifts horizontally while bobbing up and down
 * via {@link SwingMotion}. Periodically extends its spikes (mapping frame 2), switching
 * from ENEMY collision (defeatable) to HURT collision (damages player). Only vulnerable
 * when spikes are retracted (frames 0 and 1).
 *
 * <p>Based on {@code Obj_Poindexter} (sonic3k.asm, lines 184529–184596).
 *
 * <h3>Subtype:</h3>
 * Controls patrol timing. Initial wait = subtype × 4 frames; subsequent waits =
 * subtype × 8 frames. On each wait expiry, X velocity reverses and the sprite flips.
 *
 * <h3>Animation cycle (AniRaw_Poindexter):</h3>
 * Frame 0 (128f retracted) → Frame 1 (5f transition) → Frame 2 (64f spikes out) →
 * Frame 1 (5f transition) → loop.
 *
 * <h3>Collision:</h3>
 * {@code $0A} (ENEMY, size $0A) when retracted — player can defeat by attacking.
 * {@code $86} (HURT, size $06) when spikes extended (frame 2) — damages player on contact.
 */
public final class PoindexterBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: collision_flags values
    // move.b #$A,collision_flags(a0)          ; default: enemy, size $0A
    // move.b #$86,collision_flags(a0)         ; spikes out: hurt, size $06
    private static final int COLLISION_FLAGS_NORMAL = 0x0A;
    private static final int COLLISION_FLAGS_HURT = 0x86;

    // From ObjDat_Poindexter: dc.w $280 (priority)
    private static final int PRIORITY_BUCKET = 5;
    private static final int RENDER_HALF_SIZE = 0x14;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;

    // From disassembly: move.w #-$40,d4 / jsr Set_VelocityXTrackSonic
    private static final int X_SPEED = 0x40;

    // From disassembly:
    // move.w #$20,d0 / move.w d0,y_vel(a0)    → initial Y velocity
    // move.w d0,$3E(a0)                        → swing threshold
    // move.w #1,$40(a0)                        → swing acceleration
    private static final int INITIAL_Y_VEL = 0x20;
    private static final int SWING_THRESHOLD = 0x20;
    private static final int SWING_ACCEL = 1;

    // AniRaw_Poindexter: (frame, delay) pairs terminated by $FC loop.
    // dc.b 0,$7F / 1,4 / 2,$3F / 1,4 / $FC
    private static final int[] ANIM_FRAMES = {0, 1, 2, 1};
    private static final int[] ANIM_DELAYS = {0x7F, 4, 0x3F, 4};

    // Mapping frame that activates HURT collision (spikes extended)
    private static final int SPIKES_FRAME = 2;

    // Patrol state
    private int waitTimer;        // $2E(a0) — Obj_Wait countdown
    private int waitResetValue;   // $3A(a0) — value reloaded into $2E after direction reversal
    private boolean swingDown;    // bit 0 of $38(a0) — Swing_UpAndDown direction flag

    // Animation state
    private int animIndex;        // Current index into ANIM_FRAMES/ANIM_DELAYS
    private int animTimer;        // Countdown for current frame's display duration

    private boolean waitingForOnscreen = true;
    private boolean initialized;
    private boolean collisionFlagsWritten;

    public PoindexterBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Poindexter",
                Sonic3kObjectArtKeys.HCZ_POINTDEXTER, COLLISION_FLAGS_NORMAL, PRIORITY_BUCKET);

        // From disassembly init:
        // moveq #0,d0 / move.b subtype(a0),d0
        // add.w d0,d0 / add.w d0,d0 → subtype * 4
        // move.w d0,$2E(a0)
        // add.w d0,d0              → subtype * 8
        // move.w d0,$3A(a0)
        int subtype = spawn.subtype() & 0xFF;
        this.waitTimer = subtype * 4;
        this.waitResetValue = subtype * 8;

        // Animation: anim_frame_timer ($24) starts at 0 from object setup.
        // On first Animate_RawMultiDelay call, subq makes it negative → immediately
        // advances anim_frame from 0 to 2, reading the SECOND pair (frame 1, delay 4).
        // The first pair (frame 0, $7F) is only used by the $FC loop command.
        this.animIndex = 0;
        this.animTimer = 0;
        this.mappingFrame = ANIM_FRAMES[0];
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) return;

        // Obj_Poindexter starts with Obj_WaitOffscreen. The helper installs a
        // temporary offscreen object and restores the normal operation pointer
        // only after Render_Sprites marks it visible; the restored routine runs
        // on the following object tick.
        if (waitingForOnscreen) {
            if (!isWithinRenderSpriteBounds(WAIT_OFFSCREEN_MARGIN, WAIT_OFFSCREEN_MARGIN)) {
                updateDynamicSpawn(currentX, currentY);
                return;
            }
            waitingForOnscreen = false;
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        if (!initialized) {
            initializeRomSetupState((AbstractPlayableSprite) playerEntity);
            initialized = true;
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        // From disassembly routine 2 execution order:
        // jsr Swing_UpAndDown / jsr MoveSprite2 / jsr Animate_RawMultiDelay / jsr Obj_Wait

        // Swing_UpAndDown: vertical oscillation
        SwingMotion.Result swing = SwingMotion.update(
                SWING_ACCEL, yVelocity, SWING_THRESHOLD, swingDown);
        yVelocity = swing.velocity();
        swingDown = swing.directionDown();

        // MoveSprite2: apply velocity to position (no gravity)
        moveWithVelocity();

        // Animate_RawMultiDelay: advance animation
        updateAnimation();

        // Obj_Wait: patrol timer → direction reversal callback
        updateWaitTimer();

        // loc_882E6 writes collision_flags after movement/animation/wait.
        collisionFlagsWritten = true;
    }

    private void initializeRomSetupState(AbstractPlayableSprite player) {
        initXVelocity(player);
        yVelocity = INITIAL_Y_VEL;
        swingDown = false;
    }

    /**
     * Set_VelocityXTrackSonic (sonic3k.asm:179322). With d4 = -$40, sets x_vel
     * so the object drifts toward the player, and sets render_flags to face them.
     *
     * <pre>
     * Set_VelocityXTrackSonic:
     *     lea    (Player_1).w,a1
     *     bsr.w  Find_OtherObject       ; d0=0 player LEFT, d0=2 player RIGHT
     *     bclr   #0,render_flags(a0)    ; default: face left
     *     tst.w  d0
     *     beq.s  loc_85430             ; player LEFT → skip neg
     *     neg.w  d4                    ; player RIGHT → negate d4
     *     bset   #0,render_flags(a0)   ; face right
     * loc_85430:
     *     move.w d4,x_vel(a0)
     * </pre>
     */
    private void initXVelocity(AbstractPlayableSprite player) {
        // Find_OtherObject: d2 = x_pos(a0) - x_pos(a1); d0 = 0 if >= 0 (player LEFT), 2 if < 0 (player RIGHT)
        int d2 = currentX - player.getCentreX();
        if (d2 >= 0) {
            // Player is LEFT (d0=0): x_vel = d4 = -$40 (drift left toward player)
            // bclr #0,render_flags → face left
            xVelocity = -X_SPEED;
            facingLeft = true;
        } else {
            // Player is RIGHT (d0=2): neg d4 → x_vel = +$40 (drift right toward player)
            // bset #0,render_flags → face right
            xVelocity = X_SPEED;
            facingLeft = false;
        }
    }

    /**
     * Animate_RawMultiDelay: decrement timer each frame; advance to next
     * (frame, delay) pair when expired. Loops on $FC terminator.
     */
    private void updateAnimation() {
        animTimer--;
        if (animTimer < 0) {
            animIndex++;
            if (animIndex >= ANIM_FRAMES.length) {
                animIndex = 0;  // $FC loop back to start
            }
            mappingFrame = ANIM_FRAMES[animIndex];
            animTimer = ANIM_DELAYS[animIndex];
        }
    }

    /**
     * Obj_Wait: decrement $2E(a0); when negative, invoke the direction
     * reversal callback at $34(a0) → loc_8830E.
     *
     * <pre>
     * Obj_Wait:                          ; sonic3k.asm:177944
     *     subq.w #1,$2E(a0)
     *     bmi.s  loc_84892              ; branch if negative → call callback
     *     rts
     * loc_84892:
     *     movea.l $34(a0),a1
     *     jmp    (a1)                   ; → loc_8830E
     *
     * loc_8830E:                ; Movement callback
     *     neg.w  x_vel(a0)
     *     bchg   #0,render_flags(a0)
     *     move.w $3A(a0),$2E(a0)
     *     rts
     * </pre>
     */
    private void updateWaitTimer() {
        waitTimer--;
        if (waitTimer < 0) {
            // loc_8830E: reverse direction and reset timer
            xVelocity = -xVelocity;
            facingLeft = !facingLeft;
            waitTimer = waitResetValue;
        }
    }

    /**
     * Dynamic collision flags based on current animation frame.
     *
     * <pre>
     * move.b #$A,collision_flags(a0)         ; default: ENEMY, size $0A
     * cmpi.b #2,mapping_frame(a0)
     * bne.s  locret
     * move.b #$86,collision_flags(a0)        ; spikes out: HURT, size $06
     * </pre>
     */
    @Override
    public int getCollisionFlags() {
        if (waitingForOnscreen || !initialized || !collisionFlagsWritten) {
            return 0;
        }
        if (mappingFrame == SPIKES_FRAME) {
            return COLLISION_FLAGS_HURT;
        }
        return COLLISION_FLAGS_NORMAL;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_SIZE;
    }

    int getXVelocityForTest() {
        return xVelocity;
    }

    boolean isWaitingForOnscreenForTest() {
        return waitingForOnscreen;
    }

    boolean isInitializedForTest() {
        return initialized;
    }
}
