package com.openggf.game.sonic1.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Yadrin (0x50) - Spiky hedgehog Badnik from Spring Yard Zone.
 * Walks along terrain, pauses for 1 second, toggles direction, and resumes.
 * Uses wall detection to reverse when hitting a wall.
 * <p>
 * Collision type $CC in ROM. In S1, the $C0 upper bits route to React_Special
 * which implements a spiky-top check: if Sonic hits the spikes from above he
 * gets hurt even while rolling, otherwise standard React_Enemy applies.
 * <p>
 * Based on docs/s1disasm/_incObj/50 Yadrin.asm.
 * <p>
 * Routine index:
 * <ul>
 *   <li>0 (Yad_Main): Initialization - ObjectFall + ObjFloorDist until floor found</li>
 *   <li>2 (Yad_Action): Main behavior with secondary routines:
 *     <ul>
 *       <li>ob2ndRout=0 (Yad_Move): Pause timer, then start walking</li>
 *       <li>ob2ndRout=2 (Yad_FixToFloor): Walk with terrain following + wall checks</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * Animation scripts (Ani_Yad):
 * <ul>
 *   <li>Anim 0 (stand): dc.b 7, 0, afEnd - frame 0 at speed 7</li>
 *   <li>Anim 1 (walk): dc.b 7, 0, 3, 1, 4, 0, 3, 2, 5, afEnd - 8 frames cycling</li>
 * </ul>
 */
public class Sonic1YadrinBadnikInstance extends AbstractBadnikInstance
        implements TouchResponseListener, SpawnRewindRecreatable {

    // From disassembly: obColType = $CC
    // Upper 2 bits ($C0) = collision category, lower 6 bits ($0C) = size index
    private static final int COLLISION_SIZE_INDEX = 0x0C;

    /**
     * Yadrin's ROM {@code $CC} route enters {@code React_Special}, then
     * {@code React_Yadrin}, on every frame of overlap. The engine exposes that
     * object-specific branch through its listener-handled SPECIAL category, so
     * it must opt out of the generic SPECIAL edge latch.
     */
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            true,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    /**
     * Vertical penetration threshold for spiky-top hurt: Sonic's react-hitbox
     * bottom edge must clip no more than this many pixels into Yadrin's top
     * edge (s1disasm: {@code cmpi.w #8,d5}, React_Yadrin).
     */
    private static final int SPIKY_TOP_THRESHOLD = 8;

    /** Sonic's touch-collision half-width is a fixed constant, not obWidth (s1disasm: sonic_react_width = 16/2). */
    private static final int SONIC_REACT_HALF_WIDTH = 8;

    /** React_Yadrin's spiked section is 24px wide (s1disasm: addi.w #24,d0). */
    private static final int SPIKE_REGION_WIDTH = 24;

    /** React_Yadrin's spiked section starts 4px in from Yadrin's leading edge (s1disasm: subq.w #4,d0). */
    private static final int SPIKE_REGION_X_OFFSET = 4;

    /** Facing-right mirrors the spiked section 16px further left (s1disasm: subi.w #16,d0). */
    private static final int SPIKE_REGION_FACING_RIGHT_MIRROR = 16;

    // From disassembly: obHeight = $11, obWidth = 8
    private static final int Y_RADIUS = 0x11;

    // From disassembly: obActWid = $14 (active/display width, also wall sensor offset)
    private static final int ACTIVE_WIDTH = 0x14;

    // Walking velocity: move.w #-$100,obVelX(a0)
    private static final int WALK_VELOCITY = 0x100;

    // Pause duration: move.w #59,yad_timedelay(a0)
    private static final int PAUSE_DURATION = 59;

    // Floor detection thresholds from Yad_FixToFloor:
    // cmpi.w #-8,d1 / blt.s Yad_Pause / cmpi.w #$C,d1 / bge.s Yad_Pause
    private static final int FLOOR_MIN_DIST = -8;
    private static final int FLOOR_MAX_DIST = 0x0C;

    // ObjectFall gravity: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // Wall check frequency: andi.w #3,d0 - check walls every 4th frame
    private static final int WALL_CHECK_MASK = 3;

    // Secondary routine states
    private static final int STATE_MOVE = 0;       // ob2ndRout=0: paused/waiting
    private static final int STATE_FIX_TO_FLOOR = 1; // ob2ndRout=2: walking

    // Walk animation frame sequence: dc.b 7, 0, 3, 1, 4, 0, 3, 2, 5, afEnd
    private static final int[] WALK_ANIM_FRAMES = {0, 3, 1, 4, 0, 3, 2, 5};
    private static final int ANIM_SPEED = 7; // Duration = speed + 1 = 8 ticks per frame

    private int secondaryState;
    private int pauseTimer;        // yad_timedelay (objoff_30)
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int fallVelocity;      // obVelY during initialization
    private boolean initialized;
    private int walkAnimIndex;     // Current index into WALK_ANIM_FRAMES

    public Sonic1YadrinBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Yadrin");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        // S1: obStatus bit 0 set = facing right (xFlip). See isSpikeRegionHit()'s
        // Javadoc for the Yad_Action_Wait bchg/bne/neg derivation proving this
        // polarity -- it is not in conflict with "50 Badnik - Yadrin.asm:75"'s
        // one-shot spawn-time toggle comment.
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.secondaryState = STATE_MOVE;
        this.pauseTimer = 0;
        this.fallVelocity = 0;
        this.initialized = false;
        this.walkAnimIndex = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            initialize();
            return;
        }

        switch (secondaryState) {
            case STATE_MOVE -> updateMove();
            case STATE_FIX_TO_FLOOR -> updateFixToFloor(vIntRunCount);
        }
    }

    /**
     * Routine 0: Yad_Main - ObjectFall + ObjFloorDist until floor is found.
     * Falls under gravity until feet intersect terrain (distance < 0).
     * ROM: bsr.w ObjectFall / bsr.w ObjFloorDist / tst.w d1 / bpl.s locret_F89E
     */
    private void initialize() {
        // ObjectFall: apply velocity to position, then add gravity
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = fallVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentY = motion.y;
        fallVelocity = motion.yVel;

        // ObjFloorDist: check floor from feet
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // ROM: tst.w d1 / bpl.s locret_F89E
        if (floorResult.foundSurface() && floorResult.distance() < 0) {
            currentY += floorResult.distance();  // add.w d1,obY(a0)
            fallVelocity = 0;                     // move.w #0,obVelY(a0)
            initialized = true;                   // addq.b #2,obRoutine(a0)
            // bchg #0,obStatus(a0) - toggle facing direction on init
            facingLeft = !facingLeft;
        }
    }

    /**
     * ob2ndRout=0 (Yad_Move): Decrement pause timer. When expired, start walking.
     * ROM: subq.w #1,yad_timedelay(a0) / bpl.s locret_F8E2
     * Then sets velocity, toggles direction, advances to Yad_FixToFloor.
     */
    private void updateMove() {
        pauseTimer--;
        if (pauseTimer >= 0) {
            return; // bpl.s locret_F8E2
        }

        // Timer expired: start walking
        // addq.b #2,ob2ndRout(a0)
        secondaryState = STATE_FIX_TO_FLOOR;

        // move.w #-$100,obVelX(a0) - default: move left
        xVelocity = -WALK_VELOCITY;

        // move.b #1,obAnim(a0)
        animFrame = 1;
        walkAnimIndex = 0;
        animTimer = 0;

        // bchg #0,obStatus(a0) / bne.s locret_F8E2 / neg.w obVelX(a0)
        // bchg tests OLD bit, toggles it. bne branches if OLD bit was SET (now clear).
        // OLD bit SET -> now CLEAR -> bne taken -> keep negative velocity (walk left)
        // OLD bit CLEAR -> now SET -> bne not taken -> negate velocity (walk right)
        facingLeft = !facingLeft;
        if (!facingLeft) {
            xVelocity = -xVelocity; // neg.w obVelX(a0)
        }
    }

    /**
     * ob2ndRout=2 (Yad_FixToFloor): Walk with terrain following and wall checks.
     * ROM: bsr.w SpeedToPos / bsr.w ObjFloorDist / cmpi.w range checks
     * Also calls Yad_ChkWall which uses (v_framecount + d7) & 3 to throttle checks.
     */
    private void updateFixToFloor(int vIntRunCount) {
        // SpeedToPos: apply X velocity with subpixel precision
        motion.x = currentX;
        motion.xVel = xVelocity;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;

        // ObjFloorDist
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        int floorDist = floorResult.foundSurface() ? floorResult.distance() : 100;

        // cmpi.w #-8,d1 / blt.s Yad_Pause / cmpi.w #$C,d1 / bge.s Yad_Pause
        if (floorDist < FLOOR_MIN_DIST || floorDist >= FLOOR_MAX_DIST) {
            returnToPause();
            return;
        }

        // add.w d1,obY(a0) - snap to floor
        currentY += floorDist;

        // Yad_ChkWall: check walls every 4th frame
        // ROM: move.w (v_framecount).w,d0 / add.w d7,d0 / andi.w #3,d0 / bne.s .skip
        // d7 is 0 for Yadrin (no per-object offset in this object code)
        if ((vIntRunCount & WALL_CHECK_MASK) == 0) {
            if (checkWall()) {
                returnToPause();
            }
        }
    }

    /**
     * Yad_ChkWall: Check for wall collision in the direction of travel.
     * ROM: Uses obActWid as sensor distance, ObjHitWallRight or ObjHitWallLeft.
     * Returns true if a wall was hit (d1 < 0 for right, d1 < 0 for left after not.w d3).
     */
    private boolean checkWall() {
        if (xVelocity > 0) {
            // Moving right: check right wall
            // ROM: moveq #0,d3 / move.b obActWid(a0),d3 / bsr.w ObjHitWallRight
            TerrainCheckResult result = ObjectTerrainUtils.checkRightWallDist(
                    currentX + ACTIVE_WIDTH, currentY);
            // tst.w d1 / bpl.s .nowall - wall found if distance < 0
            return result.foundSurface() && result.distance() < 0;
        } else if (xVelocity < 0) {
            // Moving left: check left wall
            // ROM: not.w d3 / bsr.w ObjHitWallLeft
            TerrainCheckResult result = ObjectTerrainUtils.checkLeftWallDist(
                    currentX - ACTIVE_WIDTH, currentY);
            // tst.w d1 / bmi.s .hitwall - wall found if distance < 0
            return result.foundSurface() && result.distance() < 0;
        }
        return false;
    }

    /**
     * Yad_Pause: Return to pause state.
     * ROM: subq.b #2,ob2ndRout(a0) / move.w #59,yad_timedelay(a0) /
     *      move.w #0,obVelX(a0) / move.b #0,obAnim(a0)
     */
    private void returnToPause() {
        secondaryState = STATE_MOVE;
        pauseTimer = PAUSE_DURATION;
        xVelocity = 0;
        animFrame = 0;
        animTimer = 0;
        walkAnimIndex = 0;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (secondaryState == STATE_FIX_TO_FLOOR) {
            // Walk animation: dc.b 7, 0, 3, 1, 4, 0, 3, 2, 5, afEnd
            // Speed 7 = 8 ticks per frame
            animTimer++;
            if (animTimer > ANIM_SPEED) {
                animTimer = 0;
                walkAnimIndex++;
                if (walkAnimIndex >= WALK_ANIM_FRAMES.length) {
                    walkAnimIndex = 0;
                }
            }
        }
    }

    /**
     * Returns the mapping frame index for the current animation state.
     * Stand animation (anim 0): frame 0 at speed 7.
     * Walk animation (anim 1): frames {0, 3, 1, 4, 0, 3, 2, 5} at speed 7.
     */
    private int getMappingFrame() {
        if (secondaryState == STATE_FIX_TO_FLOOR) {
            return WALK_ANIM_FRAMES[walkAnimIndex];
        }
        // Stand: frame 0
        return 0;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getCollisionFlags() {
        // obColType = $CC: SPECIAL category ($40) + size index $0C.
        // React_Special decides between spiky-top hurt and normal enemy defeat.
        return 0x40 | (getCollisionSizeIndex() & 0x3F);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isSpikeRegionHit(player, result)) {
            // React_Yadrin's .damaging path (s1disasm/_incObj/Sonic ReactToItem.asm:557-558):
            // touching the spiked head region always hurts, even while Sonic is
            // rolling/invincible -- it never reaches the badnik-defeat check.
            applyTouchHurt(player, frameCounter);
            return;
        }

        if (isPlayerAttacking(player)) {
            applyEnemyBounce(player);
            destroyBadnik(player);
        } else {
            applyTouchHurt(player, frameCounter);
        }
    }

    /**
     * React_Yadrin (s1disasm/_incObj/Sonic ReactToItem.asm:532-561): the Yadrin's
     * spiked section is an imaginary, 24px-wide hitbox at its top, offset 4px in
     * from its leading edge (mirrored 16px further when facing right). It only
     * applies when Sonic's react-hitbox bottom edge clips no more than
     * {@link #SPIKY_TOP_THRESHOLD}-1 pixels into Yadrin's top edge -- a shallow
     * graze from directly above, not a deep side/below overlap (which falls
     * through to normal badnik rules, React_Enemy).
     * <p>
     * The previous implementation compared Sonic's and Yadrin's CENTRE Y values
     * against the same threshold. Since a real top-graze touch has a
     * centre-to-centre distance close to the sum of both hitbox half-heights
     * (~30+px, not &lt;8px), that comparison almost never matched on first
     * contact -- so a rolling Sonic landing on the spikes fell through to the
     * attacking-badnik branch and safely destroyed Yadrin instead of getting hurt.
     * <p>
     * Facing-bit polarity (obStatus bit 0): {@code 50 Badnik - Yadrin.asm:101-103}'s
     * {@code bchg #0,obStatus(a0) / bne.s .return / neg.w obVelX(a0)} in
     * Yad_Action_Wait proves the bit's live meaning -- {@code bchg} sets Z from
     * the bit's value BEFORE the toggle, so Z=0 (bne taken, keep the just-set
     * leftward {@code -$100} velocity) only when the OLD bit was 1, and Z=1
     * (fall through to negate into rightward velocity) when the OLD bit was 0.
     * Since the toggle flips old-1-moving-left into new-0, and old-0-moving-right
     * into new-1, the settled (post-toggle) bit is 1 while moving/facing RIGHT
     * and 0 while moving/facing LEFT -- exactly matching this class's
     * {@code facingLeft = !xFlip} convention and independently confirmed by
     * {@code Sonic ReactToItem.asm:541-543}'s own {@code btst #0,obStatus(a1) /
     * beq.s .checkSpikedSection} (bit clear -&gt; facing left -&gt; no mirror).
     * {@code 50 Badnik - Yadrin.asm:75}'s one-shot spawn-time {@code bchg}
     * ("make Yadrin face to the left on spawn") is not a competing polarity
     * claim -- it just describes the empirical outcome of toggling this
     * particular Yadrin's spawn-data-supplied initial bit, not the bit's
     * general meaning.
     */
    private boolean isSpikeRegionHit(AbstractPlayableSprite player, TouchResponseResult result) {
        // ReactToItem.asm:20-21: Sonic's react-hitbox half-height is
        // (obHeight - 3), not the raw obHeight/getYRadius() value. ROM's
        // `subq.b #3,d5` has no floor; real physics profiles never bring
        // getYRadius() near 3, so this is left unclamped to match ROM exactly.
        int baseYRadius = player.getYRadius() - 3;
        int sonicBottom = player.getCentreY() + baseYRadius;
        int yadrinTop = currentY - result.heightRadius();
        int penetration = sonicBottom - yadrinTop;
        if (penetration < 0 || penetration >= SPIKY_TOP_THRESHOLD) {
            return false;
        }

        int sonicLeft = player.getCentreX() - SONIC_REACT_HALF_WIDTH;
        int sonicRight = sonicLeft + SONIC_REACT_HALF_WIDTH * 2;
        // s1disasm: subq.w #4,d0 then, only when facing right (obStatus bit 0
        // set), subi.w #16,d0 mirrors the region horizontally.
        int spikeLeft = currentX - SPIKE_REGION_X_OFFSET
                - (facingLeft ? 0 : SPIKE_REGION_FACING_RIGHT_MIRROR);
        int spikeRight = spikeLeft + SPIKE_REGION_WIDTH;
        // ReactToItem.asm:546-554 is the same carry/borrow "bhs/blo/bhi" AABB
        // idiom as the general React_CheckHitboxOverlap (lines 141-175): both
        // edges are INCLUSIVE. Working through the 68k arithmetic exactly:
        // - .sonicLeft branch (sonicLeft <= spikeLeft): "cmp.w d4,d0 / bhi
        //   .normalBadnik" only excludes strictly-greater-than-16, so
        //   sonicLeft == spikeLeft - 16 (the sonicRight == spikeLeft edge) hits.
        // - Other branch (sonicLeft > spikeLeft): "addi.w #24,d0 / blo
        //   .damaging" carries (unsigned overflow) exactly when
        //   (spikeLeft - sonicLeft) + 24 >= 0, i.e. sonicLeft <= spikeLeft + 24
        //   inclusive, so sonicLeft == spikeRight hits too.
        return sonicRight >= spikeLeft && sonicLeft <= spikeRight;
    }

    private boolean isPlayerAttacking(AbstractPlayableSprite player) {
        return player.isSuperSonic()
                || player.getInvincibleFrames() > 0
                || player.getRolling()
                || player.getSpindash();
    }

    private void applyEnemyBounce(AbstractPlayableSprite player) {
        short ySpeed = player.getYSpeed();
        if (ySpeed < 0) {
            player.setYSpeed((short) (ySpeed + 0x100));
            return;
        }

        int playerY = player.getCentreY();
        if (playerY < currentY) {
            player.setYSpeed((short) -ySpeed);
        } else {
            player.setYSpeed((short) (ySpeed - 0x100));
        }
    }

    private void applyTouchHurt(AbstractPlayableSprite player, int frameCounter) {
        if (player.getInvulnerable()) {
            return;
        }

        boolean hadRings = player.getRingCount() > 0;
        ObjectServices svc = tryServices();
        if (hadRings && !player.hasShield() && svc != null) {
            svc.spawnLostRings(player, frameCounter);
        }
        player.applyHurtOrDeath(currentX, false, hadRings);
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    public boolean isPersistent() {
        // Yad_Action ends at RememberState, whose out_of_range macro deletes the
        // object once its chunk-aligned X leaves the [camera-128, camera-128+0x280]
        // window (docs/s1disasm/_incObj/50 Badnik - Yadrin.asm:95 ->
        // _incObj/sub RememberState.asm:9 -> Macros.asm:278-295).
        return !isDestroyed() && isInRange();
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

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.YADRIN);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // S1 convention: default sprite art faces left, hFlip = true when facing right
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Yellow hitbox rectangle
        ctx.drawRect(currentX, currentY, 16, 16, 1f, 1f, 0f);

        // Cyan velocity arrow if moving
        if (xVelocity != 0) {
            int endX = currentX + (xVelocity >> 5);
            ctx.drawArrow(currentX, currentY, endX, currentY, 0f, 1f, 1f);
        }

        // State label
        String stateStr = switch (secondaryState) {
            case STATE_MOVE -> "PAUSE(" + pauseTimer + ")";
            case STATE_FIX_TO_FLOOR -> "WALK";
            default -> "?";
        };
        String dir = facingLeft ? "L" : "R";
        String label = name + " " + stateStr + " f" + getMappingFrame() + " " + dir;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.YELLOW);
    }
}
