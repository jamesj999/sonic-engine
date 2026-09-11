package com.openggf.game.sonic1.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Roller (0x43) - Rolling armadillo-like Badnik from Spring Yard Zone.
 * <p>
 * Waits curled up until Sonic is 0x100 pixels to its right, then rolls at high
 * speed ($700) toward him. When the Roller passes Sonic, it stops, waits 2 seconds,
 * then repeats. If it runs off a ledge, it jumps with vertical velocity -$600.
 * While rolling, the Roller is invincible (obColType = $8E; $80 = invincible bit).
 * <p>
 * Based on docs/s1disasm/_incObj/43 Roller.asm.
 * <p>
 * Routine index:
 * <ul>
 *   <li>0 (Roll_Main): Initialization - ObjectFall + ObjFloorDist until floor found</li>
 *   <li>2 (Roll_Action): Main behavior with secondary routines:
 *     <ul>
 *       <li>ob2ndRout=0 (Roll_RollChk): Wait for Sonic to be 0x100px right, then activate</li>
 *       <li>ob2ndRout=2 (Roll_RollNoChk): Paused / waiting to re-roll after stop</li>
 *       <li>ob2ndRout=4 (Roll_ChkJump): Rolling along terrain with stop/jump checks</li>
 *       <li>ob2ndRout=6 (Roll_MatchFloor): Airborne after jump, falling to re-land</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * Animation scripts (Ani_Roll):
 * <ul>
 *   <li>Anim 0 (Unfold): $F speed, frames {2,1,0}, afBack 1 -> loops at frame 1</li>
 *   <li>Anim 1 (Fold): $F speed, frames {1,2}, afChange 2 -> switches to anim 2</li>
 *   <li>Anim 2 (Roll): speed 3, frames {3,4,2}, afEnd -> loops</li>
 * </ul>
 */
public class Sonic1RollerBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: obColType = $0E (normal) or $8E (invincible while rolling)
    // Size index is lower 6 bits = $0E
    private static final int COLLISION_SIZE_INDEX = 0x0E;

    // From disassembly: obHeight = $E, obWidth = 8
    private static final int Y_RADIUS = 0x0E;

    // From disassembly: obActWid = $10
    private static final int ACTIVE_WIDTH = 0x10;

    // ObjectFall gravity: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // Rolling velocity: move.w #$700,obVelX(a0)
    private static final int ROLL_VELOCITY = 0x700;

    // Jump velocity: move.w #-$600,obVelY(a0)
    private static final int JUMP_VELOCITY = -0x600;

    // Activation distance: subi.w #$100,d0 (player must be >= $100 px right of Roller)
    private static final int ACTIVATION_DISTANCE = 0x100;

    // Stop proximity: subi.w #$30,d0 (Roller passed Sonic by $30 px)
    private static final int STOP_PROXIMITY = 0x30;

    // Pause duration after stopping: move.w #120,objoff_30(a0)
    private static final int PAUSE_DURATION = 120;

    // Floor distance thresholds for Roll_ChkJump:
    // cmpi.w #-8,d1 / blt.s Roll_Jump / cmpi.w #$C,d1 / bge.s Roll_Jump
    private static final int FLOOR_MIN_DIST = -8;
    private static final int FLOOR_MAX_DIST = 0x0C;

    // Secondary routine states (ob2ndRout values / 2)
    private static final int STATE_ROLL_CHK = 0;       // ob2ndRout=0: waiting for activation
    private static final int STATE_ROLL_NO_CHK = 1;    // ob2ndRout=2: paused after stop, waiting to re-roll
    private static final int STATE_CHK_JUMP = 2;       // ob2ndRout=4: rolling along terrain
    private static final int STATE_MATCH_FLOOR = 3;    // ob2ndRout=6: airborne after jump

    // Animation IDs
    private static final int ANIM_UNFOLD = 0;
    private static final int ANIM_FOLD = 1;
    private static final int ANIM_ROLL = 2;

    // Unfold animation: $F speed, frames {2,1,0}, afBack to index 1
    private static final int[] UNFOLD_FRAMES = {2, 1, 0};
    private static final int UNFOLD_SPEED = 0x0F;
    private static final int UNFOLD_BACK_INDEX = 1; // loops back to frame index 1

    // Fold animation: $F speed, frames {1,2}, then change to ANIM_ROLL
    private static final int[] FOLD_FRAMES = {1, 2};
    private static final int FOLD_SPEED = 0x0F;

    // Roll animation: speed 3, frames {3,4,2}, loop
    private static final int[] ROLL_FRAMES = {3, 4, 2};
    private static final int ROLL_ANIM_SPEED = 3;

    private int secondaryState;
    private int pauseTimer;         // objoff_30: wait timer
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private boolean initialized;
    private int currentAnim;        // Current animation ID
    private int animIndex;          // Current index within animation frame array
    private boolean invincible;     // Whether currently in invincible rolling state
    private boolean hasStopped;     // objoff_32 bit 7: has stopped once before
    private boolean hasJumped;      // objoff_32 bit 0: has jumped once (controls jump velocity)

    public Sonic1RollerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Roller");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.facingLeft = false;
        this.secondaryState = STATE_ROLL_CHK;
        this.pauseTimer = 0;
        this.initialized = false;
        this.currentAnim = ANIM_UNFOLD;
        this.animIndex = 0;
        this.animTimer = 0;
        this.invincible = false;
        this.hasStopped = false;
        this.hasJumped = false;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            initialize();
            return;
        }

        switch (secondaryState) {
            case STATE_ROLL_CHK -> updateRollChk(player);
            case STATE_ROLL_NO_CHK -> updateRollNoChk();
            case STATE_CHK_JUMP -> updateChkJump(player);
            case STATE_MATCH_FLOOR -> updateMatchFloor();
        }
    }

    /**
     * Routine 0: Roll_Main - ObjectFall + ObjFloorDist until floor is found.
     * ROM: bsr.w ObjectFall / bsr.w ObjFloorDist / tst.w d1 / bpl.s locret_E052
     */
    private void initialize() {
        // ObjectFall: apply velocity then add gravity
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = yVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentY = motion.y;
        yVelocity = motion.yVel;

        // ObjFloorDist
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // ROM: tst.w d1 / bpl.s locret_E052
        if (floorResult.foundSurface() && floorResult.distance() < 0) {
            currentY += floorResult.distance();  // add.w d1,obY(a0)
            yVelocity = 0;                       // move.w #0,obVelY(a0)
            initialized = true;                  // addq.b #2,obRoutine(a0)
        }
    }

    /**
     * ob2ndRout=0 (Roll_RollChk): Check if Sonic is far enough to the right to activate.
     * ROM: move.w (v_player+obX).w,d0 / subi.w #$100,d0 / bcs.s loc_E0D2
     *      sub.w obX(a0),d0 / bcs.s loc_E0D2
     * If triggered: advance to state 4, set anim 2, velocity $700, collision $8E.
     * Also pops the return address (addq.l #4,sp) to skip AnimateSprite/DisplaySprite.
     */
    private void updateRollChk(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        int playerX = player.getCentreX();
        // subi.w #$100,d0 / bcs.s (unsigned subtraction: if playerX < $100, carry set)
        if (playerX < ACTIVATION_DISTANCE) {
            return;
        }
        // sub.w obX(a0),d0 / bcs.s (if playerX - $100 < rollerX, carry set)
        int dist = (playerX - ACTIVATION_DISTANCE) - currentX;
        if (dist < 0) {
            return;
        }

        // Activation: advance to Roll_ChkJump (ob2ndRout += 4 -> state 4 = index 2)
        secondaryState = STATE_CHK_JUMP;
        // move.b #2,obAnim(a0) - set to roll animation
        setAnimation(ANIM_ROLL);
        // move.w #$700,obVelX(a0)
        xVelocity = ROLL_VELOCITY;
        // move.b #$8E,obColType(a0)
        invincible = true;
    }

    /**
     * ob2ndRout=2 (Roll_RollNoChk): Paused after stopping, waiting to re-roll.
     * ROM: cmpi.b #2,obAnim(a0) / beq.s loc_E0F8
     *      subq.w #1,objoff_30(a0) / bpl.s locret_E0F6
     *      Set anim 1, velocity $700, collision $8E.
     * If anim is already 2 (fold completed -> changed to roll), advance to Roll_ChkJump.
     */
    private void updateRollNoChk() {
        // If fold animation has completed and changed to roll anim
        if (currentAnim == ANIM_ROLL) {
            // loc_E0F8: addq.b #2,ob2ndRout(a0) -> advance to Roll_ChkJump
            secondaryState = STATE_CHK_JUMP;
            return;
        }

        // Decrement pause timer
        pauseTimer--;
        if (pauseTimer >= 0) {
            return; // Still waiting
        }

        // Timer expired: start folding to re-roll
        // move.b #1,obAnim(a0)
        setAnimation(ANIM_FOLD);
        // move.w #$700,obVelX(a0)
        xVelocity = ROLL_VELOCITY;
        // move.b #$8E,obColType(a0)
        invincible = true;
    }

    /**
     * ob2ndRout=4 (Roll_ChkJump): Rolling along terrain with terrain following.
     * ROM: bsr.w Roll_Stop / bsr.w SpeedToPos / bsr.w ObjFloorDist
     *      cmpi.w #-8,d1 / blt.s Roll_Jump / cmpi.w #$C,d1 / bge.s Roll_Jump
     *      add.w d1,obY(a0)
     */
    private void updateChkJump(AbstractPlayableSprite player) {
        // Roll_Stop: check if Roller should stop (only while rolling)
        checkStop(player);

        // SpeedToPos: apply X velocity with subpixel precision
        motion.x = currentX;
        motion.xVel = xVelocity;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;

        // ObjFloorDist
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        int floorDist = floorResult.foundSurface() ? floorResult.distance() : 100;

        // cmpi.w #-8,d1 / blt.s Roll_Jump / cmpi.w #$C,d1 / bge.s Roll_Jump
        if (floorDist < FLOOR_MIN_DIST || floorDist >= FLOOR_MAX_DIST) {
            performJump();
            return;
        }

        // add.w d1,obY(a0) - snap to floor
        currentY += floorDist;
    }

    /**
     * Roll_Jump: Transition to airborne state.
     * ROM: addq.b #2,ob2ndRout(a0) / bset #0,objoff_32(a0)
     *      beq.s locret_E12E / move.w #-$600,obVelY(a0)
     * First jump: bit 0 not set -> beq taken -> no vertical velocity (just falls)
     * Second+ jump: bit 0 already set -> beq not taken -> apply jump velocity
     */
    private void performJump() {
        secondaryState = STATE_MATCH_FLOOR;
        // bset #0,objoff_32(a0) / beq.s locret_E12E
        // bset tests the OLD value of the bit; beq branches if it WAS 0
        if (hasJumped) {
            // Bit was already set: apply jump velocity
            yVelocity = JUMP_VELOCITY;
        }
        hasJumped = true;
    }

    /**
     * ob2ndRout=6 (Roll_MatchFloor): Airborne - falling under gravity until landing.
     * ROM: bsr.w ObjectFall / tst.w obVelY(a0) / bmi.s locret_E150
     *      bsr.w ObjFloorDist / tst.w d1 / bpl.s locret_E150
     *      add.w d1,obY(a0) / subq.b #2,ob2ndRout(a0) / move.w #0,obVelY(a0)
     */
    private void updateMatchFloor() {
        // ObjectFall: apply velocity (using current values), then add gravity to yVel.
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = xVelocity;
        motion.yVel = yVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentX = motion.x;
        currentY = motion.y;
        yVelocity = motion.yVel;

        // tst.w obVelY(a0) / bmi.s locret_E150 - only check floor when falling
        if (yVelocity < 0) {
            return;
        }

        // ObjFloorDist
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // tst.w d1 / bpl.s locret_E150 - only land if floor is above feet
        if (!floorResult.foundSurface() || floorResult.distance() >= 0) {
            return;
        }

        // Land: snap to floor and return to Roll_ChkJump
        currentY += floorResult.distance();
        secondaryState = STATE_CHK_JUMP;
        yVelocity = 0;
    }

    /**
     * Roll_Stop: Check if Roller should stop rolling.
     * ROM: tst.b objoff_32(a0) / bmi.s locret_E188
     *      move.w (v_player+obX).w,d0 / subi.w #$30,d0 / sub.w obX(a0),d0
     *      bcc.s locret_E188
     * If hasStopped (bit 7 set), never stop again.
     * Stops when playerX - $30 - rollerX < 0, i.e., roller has passed Sonic.
     */
    private void checkStop(AbstractPlayableSprite player) {
        // tst.b objoff_32(a0) / bmi.s locret_E188
        // In S1, tst.b tests the high byte of objoff_32. Bit 7 = $80 in byte = negative.
        if (hasStopped) {
            return;
        }

        if (player == null) {
            return;
        }

        int playerX = player.getCentreX();
        // subi.w #$30,d0 / sub.w obX(a0),d0 / bcc.s locret_E188
        int dist = (playerX - STOP_PROXIMITY) - currentX;
        if (dist >= 0) {
            return; // Haven't passed Sonic yet
        }

        // Stop rolling
        // move.b #0,obAnim(a0)
        setAnimation(ANIM_UNFOLD);
        // move.b #$E,obColType(a0)
        invincible = false;
        // clr.w obVelX(a0)
        xVelocity = 0;
        // move.w #120,objoff_30(a0) - 2 seconds at 60fps
        pauseTimer = PAUSE_DURATION;
        // move.b #2,ob2ndRout(a0)
        secondaryState = STATE_ROLL_NO_CHK;
        // bset #7,objoff_32(a0)
        hasStopped = true;
    }

    /**
     * Sets the current animation and resets animation state.
     */
    private void setAnimation(int anim) {
        if (currentAnim != anim) {
            currentAnim = anim;
            animIndex = 0;
            animTimer = 0;
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Only animate in routine 2 (Roll_Action) - not during init
        if (!initialized) {
            return;
        }

        // Roll_Action_FromLeft (ob2ndRout=0) pops its own return address
        // (addq.l #4,sp) on every call while dormant, so AnimateSprite is never
        // reached until the Roller activates (docs/s1disasm/_incObj/"43 Badnik -
        // Roller.asm":36-39). Skip animating the curled pose during that window.
        if (secondaryState == STATE_ROLL_CHK) {
            return;
        }

        switch (currentAnim) {
            case ANIM_UNFOLD -> animateUnfold();
            case ANIM_FOLD -> animateFold();
            case ANIM_ROLL -> animateRoll();
        }
    }

    /**
     * Anim 0 (A_Roll_Unfold): dc.b $F, 2, 1, 0, afBack, 1
     * Speed $F (16 ticks per frame), frames {2,1,0}, then back to index 1 (frame 1).
     */
    private void animateUnfold() {
        animTimer++;
        if (animTimer > UNFOLD_SPEED) {
            animTimer = 0;
            animIndex++;
            if (animIndex >= UNFOLD_FRAMES.length) {
                // afBack, 1: go back to index 1
                animIndex = UNFOLD_BACK_INDEX;
            }
        }
    }

    /**
     * Anim 1 (A_Roll_Fold): dc.b $F, 1, 2, afChange, 2
     * Speed $F (16 ticks per frame), frames {1,2}, then change to anim 2.
     */
    private void animateFold() {
        animTimer++;
        if (animTimer > FOLD_SPEED) {
            animTimer = 0;
            animIndex++;
            if (animIndex >= FOLD_FRAMES.length) {
                // afChange, 2: switch to roll animation
                setAnimation(ANIM_ROLL);
            }
        }
    }

    /**
     * Anim 2 (A_Roll_Roll): dc.b 3, 3, 4, 2, afEnd
     * Speed 3 (4 ticks per frame), frames {3,4,2}, loop.
     */
    private void animateRoll() {
        animTimer++;
        if (animTimer > ROLL_ANIM_SPEED) {
            animTimer = 0;
            animIndex++;
            if (animIndex >= ROLL_FRAMES.length) {
                animIndex = 0; // afEnd: loop from start
            }
        }
    }

    /**
     * Returns the current mapping frame index based on animation state.
     */
    private int getMappingFrame() {
        return switch (currentAnim) {
            case ANIM_UNFOLD -> UNFOLD_FRAMES[animIndex];
            case ANIM_FOLD -> FOLD_FRAMES[animIndex];
            case ANIM_ROLL -> ROLL_FRAMES[animIndex];
            default -> 0;
        };
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getCollisionFlags() {
        // ROM parity: Roll_Main (routine 0) never sets obColType, and the initial
        // waiting state Roll_Action_FromLeft (ob2ndRout=0) leaves it untouched too
        // (docs/s1disasm/_incObj/43 Badnik - Roller.asm:19-38,86-100). obColType is
        // first written only when the Roller activates (Roll_Action_FromLeft sets
        // $8E, ...:96) or stops-and-unfolds (Roll_Action_StopAndUnfold sets $0E,
        // ...:177) — both advance ob2ndRout away from 0 and never return to it. So a
        // Roller still in STATE_ROLL_CHK has obColType=0 (col_none) and ReactToItem
        // skips it entirely (`move.b obColType(a1),d0 / bne ...`,
        // docs/s1disasm/_incObj/Sonic ReactToItem.asm:52-53). Without this, a curled
        // waiting Roller wrongly hurt Sonic when he fell onto it (S1 SYZ1 trace
        // f2338: engine hurt + ring loss vs ROM's unscathed fall past the dormant
        // Roller).
        if (secondaryState == STATE_ROLL_CHK) {
            return 0;
        }
        if (invincible) {
            // obColType = $8E: $80 = invincible (BOSS category in engine), $0E = size index
            // Using $80 (BOSS) category makes the Roller invincible to Sonic's attacks
            return 0x80 | (COLLISION_SIZE_INDEX & 0x3F);
        }
        // obColType = $0E: standard enemy + size index
        return 0x00 | (COLLISION_SIZE_INDEX & 0x3F);
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    public boolean isPersistent() {
        // Roll_Action does NOT end at RememberState. Under FixBugs = 0 -- the
        // branch both S1 builds take (docs/s1disasm/sonic.asm:20) -- it carries
        // an inlined copy of RememberState that closes with `bgt.w .offscreen`
        // instead of the macro's `bhi`
        // (docs/s1disasm/_incObj/43 Badnik - Roller.asm:63-70, and the
        // disassembly's own note at :58-62). That signed compare cannot be
        // satisfied by a negative distance, so a Roller that has scrolled off
        // the LEFT edge stays alive and keeps rolling; only the right edge
        // despawns it. The FixBugs branch (:56) would `bra.w RememberState` and
        // despawn on both edges.
        //
        // The difference is load-bearing, not cosmetic: in the SYZ1 complete-run
        // trace the Roller that starts at x=0x710 rolls right while Sonic runs
        // right, leaves the window on the left at x=0x859, and -- because the ROM
        // keeps it -- is still rolling when Sonic comes back left, so it performs
        // Roll_Action_StopAndUnfold 48px to his left at x=0x955 and is destroyed
        // there. With the unsigned window the engine deleted it at 0x859, the
        // respawned copy unfolded 372px too early, and Sonic never got the
        // React_Enemy `addi.w #$100,obVelY` rebound
        // (docs/s1disasm/_incObj/Sonic ReactToItem.asm:299-309).
        return !isDestroyed() && isInRangeAtSigned(getX());
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // obPriority = 4
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        // ROM never calls DisplaySprite for a still-falling (routine 0, Roll_Main
        // is entered via jmp and its own rts never reaches AnimateSprite/
        // DisplaySprite) or still-dormant (ob2ndRout=0, Roll_Action_FromLeft's
        // addq.l #4,sp skip, docs/s1disasm/_incObj/"43 Badnik - Roller.asm":36-39,
        // 99) Roller -- both windows are invisible on real hardware, not just
        // hitbox-less.
        if (!initialized || secondaryState == STATE_ROLL_CHK) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.ROLLER);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // Roller art faces right by default; hFlip when facing left
        // ROM sets obRender = 4 (bit 2 set = use object's X-flip flag for rendering)
        // The Roller always moves right (positive velocity), no flip needed normally
        renderer.drawFrameIndex(frame, currentX, currentY, facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Yellow hitbox rectangle
        ctx.drawRect(currentX, currentY, 16, 16, 1f, 1f, 0f);

        // Cyan velocity arrow if moving
        if (xVelocity != 0 || yVelocity != 0) {
            int endX = currentX + (xVelocity >> 5);
            int endY = currentY + (yVelocity >> 5);
            ctx.drawArrow(currentX, currentY, endX, endY, 0f, 1f, 1f);
        }

        // State label
        String stateStr = switch (secondaryState) {
            case STATE_ROLL_CHK -> "WAIT";
            case STATE_ROLL_NO_CHK -> "PAUSE(" + pauseTimer + ")";
            case STATE_CHK_JUMP -> "ROLL";
            case STATE_MATCH_FLOOR -> "AIR";
            default -> "?";
        };
        String invStr = invincible ? " INV" : "";
        String label = name + " " + stateStr + " f" + getMappingFrame() + invStr;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.YELLOW);
    }
}
