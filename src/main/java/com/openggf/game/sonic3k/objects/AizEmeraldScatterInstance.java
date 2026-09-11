package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Chaos Emerald scatter object for the AIZ1 intro cinematic.
 *
 * Disassembly reference: sonic3k.asm loc_67900 onward.
 *
 * Seven emeralds are spawned by the intro plane object at routine 0x18
 * (when player X >= 0x13D0). Each emerald is created via CreateChild6_Simple
 * with subtypes 0, 2, 4, 6, 8, 10, 12. The mapping frame is derived from
 * the subtype (subtype >> 1), giving frames 0-6 for the seven emerald colors.
 *
 * Each emerald receives a unique X velocity from Set_IndexedVelocity
 * (Obj_VelocityIndex at 0x40 + subtype * 2), creating a leftward fan scatter.
 * Y velocity is -0x700 for all emeralds.
 *
 * Three phases:
 *
 * 1. FALLING (loc_67938): Gravity applied via MoveSprite. Floor check with
 *    ObjCheckFloorDist. When floor is hit (d1 < 0), snap Y and transition
 *    to GROUNDED.
 *
 * 2. GROUNDED (loc_6795C): Each frame, check Knuckles proximity. If within
 *    8 pixels horizontally and Knuckles is moving in the correct direction
 *    (determined by subtype bit 1), the emerald is collected (deleted).
 *
 *    Direction matching logic:
 *    - Subtype bit 1 = 0: collected when Knuckles moves RIGHT (positive x_vel)
 *    - Subtype bit 1 = 1: collected when Knuckles moves LEFT (negative x_vel)
 */
public class AizEmeraldScatterInstance extends AbstractObjectInstance implements RewindRecreatable {

    // -----------------------------------------------------------------------
    // Phase enum
    // -----------------------------------------------------------------------

    public enum Phase {
        FALLING,
        GROUNDED
    }

    // -----------------------------------------------------------------------
    // ROM constants
    // -----------------------------------------------------------------------

    /** Horizontal proximity in pixels for Knuckles pickup check. */
    public static final int PICKUP_PROXIMITY = 8;

    /** Y collision radius (y_radius = 4 from ROM). */
    public static final int Y_RADIUS = 4;

    /** Standard S3K gravity in subpixels per frame. */
    public static final int GRAVITY = SubpixelMotion.S3K_GRAVITY;

    /**
     * Per-emerald velocity table from Set_IndexedVelocity / Obj_VelocityIndex.
     * ROM indexes by 0x40 + subtype * 2, giving each emerald a different X velocity
     * so they scatter in a leftward fan pattern.
     * Format: {xVel, yVel} indexed by subtype >> 1 (0-6).
     */
    private static final int[][] VELOCITY_TABLE = {
        {-0x0040, -0x0700},  // subtype 0
        {-0x0080, -0x0700},  // subtype 2
        {-0x0180, -0x0700},  // subtype 4
        {-0x0100, -0x0700},  // subtype 6
        {-0x0200, -0x0700},  // subtype 8
        {-0x0280, -0x0700},  // subtype 10
        {-0x0300, -0x0700},  // subtype 12
    };

    // -----------------------------------------------------------------------
    // Mutable state
    // -----------------------------------------------------------------------

    /** Current world X position (pixels). */
    private int currentX;

    /** Current world Y position (pixels). */
    private int currentY;

    /** Fractional X accumulator (subpixels, 0-255). */
    private int xSub;

    /** Fractional Y accumulator (subpixels, 0-255). */
    private int ySub;

    /** X velocity in subpixels (256 = 1 pixel per frame). */
    private int xVel;

    /** Y velocity in subpixels (256 = 1 pixel per frame). */
    private int yVel;

    /** Display frame index derived from subtype (0-6 for seven emerald colors). */
    private int mappingFrame;

    /** Current phase of this emerald's lifecycle. */
    private Phase phase;

    /** Reference to the Knuckles cutscene object for proximity collection. */
    private CutsceneKnucklesAiz1Instance knuckles;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public AizEmeraldScatterInstance(ObjectSpawn spawn) {
        super(spawn, "AIZEmeraldScatter");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xSub = 0;
        this.ySub = 0;
        int velIndex = spawn.subtype() >> 1;  // 0-6
        this.xVel = VELOCITY_TABLE[velIndex][0];
        this.yVel = VELOCITY_TABLE[velIndex][1];
        this.mappingFrame = velIndex;
        this.phase = Phase.FALLING;
    }

    @Override
    public AizEmeraldScatterInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AizEmeraldScatterInstance(ctx.spawn());
    }

    // -----------------------------------------------------------------------
    // ObjectInstance interface
    // -----------------------------------------------------------------------

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return 5; // ROM priority 0x280
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (phase) {
            case FALLING -> updateFalling();
            case GROUNDED -> updateGrounded();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = AizIntroArtLoader.getEmeraldRenderer(services());
        if (renderer == null || !renderer.isReady()) return;
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    // -----------------------------------------------------------------------
    // Phase logic
    // -----------------------------------------------------------------------

    /** Shared state object for SubpixelMotion calls. */
    private final SubpixelMotion.State motionState = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    /**
     * FALLING phase (loc_67938): Apply gravity via MoveSprite equivalent.
     * Check floor with ObjCheckFloorDist. When floor hit (d1 < 0), snap Y
     * and transition to GROUNDED.
     */
    private void updateFalling() {
        // MoveSprite: add velocity to position with subpixel accumulation + gravity.
        // ROM: tst.l d0 / bmi.s — d0 holds old y_vel (pre-gravity); skip floor check
        // if still moving upward.
        int preGravityYVel = yVel;
        motionState.x = currentX; motionState.y = currentY;
        motionState.xSub = xSub;  motionState.ySub = ySub;
        motionState.xVel = xVel;  motionState.yVel = yVel;
        SubpixelMotion.moveSprite(motionState, GRAVITY);
        currentX = motionState.x; currentY = motionState.y;
        xSub = motionState.xSub;  ySub = motionState.ySub;
        xVel = motionState.xVel;  yVel = motionState.yVel;

        // ROM: tst.l d0 / bmi.s — skip floor check while moving upward
        if (preGravityYVel < 0) {
            return;
        }

        // ObjCheckFloorDist terrain collision
        // ROM: tst.w d1 / bpl.s — land when d1 < 0 (strictly negative)
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        if (floor.foundSurface() && floor.distance() < 0) {
            landOnGround(currentY + floor.distance());
        }
    }

    /**
     * GROUNDED phase (loc_6795C): Check Knuckles proximity each frame.
     * Collection logic delegates to canBeCollectedByVelocity().
     */
    private void updateGrounded() {
        if (knuckles == null || knuckles.isDestroyed()) return;
        int dx = Math.abs(knuckles.getX() - currentX);
        if (dx <= PICKUP_PROXIMITY && canBeCollectedByVelocity(knuckles.getXVel())) {
            setDestroyed(true);
        }
    }

    // -----------------------------------------------------------------------
    // Collection logic
    // -----------------------------------------------------------------------

    /**
     * Determines whether this emerald can be collected by Knuckles based on
     * his current X velocity and this emerald's subtype bit 1.
     *
     * ROM logic (loc_6795C):
     * <pre>
     *   move.w  x_vel(a1),d0        ; get Knuckles x_vel
     *   btst    #1,subtype(a0)      ; check subtype bit 1
     *   beq.s   +                   ; if clear, skip negate
     *   neg.w   d0                  ; negate velocity
     * + tst.w   d0
     *   bmi.s   .wrong_direction    ; if negative, wrong direction
     *   ; ... delete self (collected)
     * </pre>
     *
     * @param knuxXVel Knuckles' current x_vel in subpixels
     * @return true if this emerald should be collected (deleted)
     */
    public boolean canBeCollectedByVelocity(int knuxXVel) {
        int adjusted = knuxXVel;
        if ((spawn.subtype() & 2) != 0) {
            adjusted = -adjusted;
        }
        // ROM: tst.w d0 / bmi.s (branch if negative)
        // So collected when adjusted >= 0
        return adjusted >= 0;
    }

    // -----------------------------------------------------------------------
    // Accessors for test and external use
    // -----------------------------------------------------------------------

    public int getMappingFrame() {
        return mappingFrame;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public int getXVel() {
        return xVel;
    }

    public int getYVel() {
        return yVel;
    }

    /**
     * Sets the Knuckles reference for proximity collection checks.
     */
    public void setKnuckles(CutsceneKnucklesAiz1Instance knux) {
        this.knuckles = knux;
    }

    /**
     * Snaps this emerald to the ground at the given Y coordinate
     * and transitions to GROUNDED phase.
     *
     * @param groundY the Y position to snap to
     */
    public void landOnGround(int groundY) {
        this.currentY = groundY;
        this.yVel = 0;
        this.xVel = 0;
        this.phase = Phase.GROUNDED;
    }
}
