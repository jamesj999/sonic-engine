package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x14 - Lava Ball (MZ, SLZ).
 * <p>
 * A fireball projectile spawned by the Lava Ball Maker (0x13) or MZ Boss (0x73).
 * Moves according to its subtype and damages Sonic on contact.
 * <p>
 * <b>Subtypes 0-3 (vertical):</b> Fly upward at varying speeds, decelerate by gravity
 * ($18/frame), and fall back down. Destroyed when reaching original Y position on descent.
 * <p>
 * <b>Subtype 4 (ceiling):</b> Fly upward at speed -$200, stop on hitting ceiling,
 * then switch to subtype 8 (idle) with collision animation.
 * <p>
 * <b>Subtype 5 (floor):</b> Fall downward at speed $200, stop on hitting floor,
 * then switch to subtype 8 (idle) with collision animation.
 * <p>
 * <b>Subtypes 6-7 (horizontal):</b> Move sideways at speeds -$200/$200 respectively.
 * Stop on hitting a wall, switching to subtype 8 with horizontal collision animation.
 * <p>
 * <b>Subtype 8 (stopped):</b> No movement, used after hitting terrain.
 * <p>
 * <b>Collision:</b> obColType = $8B (HURT category $80 | size index $0B).
 * <p>
 * <b>Animation (from _anim/Fireballs.asm):</b>
 * <ul>
 *   <li>Anim 0 (.vertical): speed 5, frames {0, $20, 1, $21} - vertical fireball with V-flip</li>
 *   <li>Anim 1 (.vertcollide): speed 5, frame {2}, then afRoutine (advance routine = delete)</li>
 *   <li>Anim 2 (.horizontal): speed 5, frames {3, $43, 4, $44} - horizontal fireball with H-flip</li>
 *   <li>Anim 3 (.horicollide): speed 5, frame {5}, then afRoutine (advance routine = delete)</li>
 * </ul>
 * <p>
 * <b>Mappings (from _maps/Fireballs.asm - Map_Fire_internal):</b>
 * <ul>
 *   <li>Frame 0 (.vertical1): 2x4 tiles at (-8, -$18), startTile 0</li>
 *   <li>Frame 1 (.vertical2): 2x4 tiles at (-8, -$18), startTile 8</li>
 *   <li>Frame 2 (.vertcollide): 2x3 tiles at (-8, -$10), startTile $10</li>
 *   <li>Frame 3 (.horizontal1): 4x2 tiles at (-$18, -8), startTile $16</li>
 *   <li>Frame 4 (.horizontal2): 4x2 tiles at (-$18, -8), startTile $1E</li>
 *   <li>Frame 5 (.horicollide): 3x2 tiles at (-$10, -8), startTile $26</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/14 Lava Ball.asm
 */
public class Sonic1LavaBallObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /**
     * LBall_Speeds: Y velocity table indexed by subtype.
     * From disassembly: dc.w -$400, -$500, -$600, -$700, -$200, $200, -$200, $200, 0
     */
    private static final int[] SPEEDS = {
            -0x400, -0x500, -0x600, -0x700,  // subtypes 0-3: vertical upward
            -0x200,                            // subtype 4: ceiling seeker
            0x200,                             // subtype 5: floor seeker
            -0x200,                            // subtype 6: horizontal left
            0x200,                             // subtype 7: horizontal right
            0                                  // subtype 8: stopped
    };

    /** Gravity applied per frame for subtypes 0-3: addi.w #$18,obVelY(a0). */
    private static final int GRAVITY = 0x18;

    /** obColType = $8B: HURT category ($80) | size index $0B. */
    private static final int COLLISION_FLAGS = 0x8B;

    /** obHeight = 8, obWidth = 8 (from LBall_Main). */
    private static final int Y_RADIUS = 8;

    /** obPriority = 3 (from LBall_Main). */
    private static final int PRIORITY_NORMAL = 3;

    /**
     * Animation speed from Ani_Fire: dc.b 5, ... -> frame advances every (5+1) = 6 game frames.
     */
    private static final int ANIM_SPEED = 6;

    /** Threshold for horizontal vs vertical subtypes. Subtypes >= 6 are horizontal. */
    private static final int HORIZONTAL_THRESHOLD = 6;

    /**
     * Vertical animation: frames {0, 0-hflip, 1, 1-hflip}.
     * ROM encodes: 0, $20, 1, $21 where bit 5 ($20) = H-flip via rol.b #3 → bit 0.
     * S1 AnimateSprite uses rol.b #3 to map: bit 5→bit 0 (H-flip), bit 6→bit 1 (V-flip).
     * The vertical flame shimmers horizontally (left-right mirror), NOT directional V-flip.
     */
    private static final int[] VERT_ANIM_FRAMES = {0, 0, 1, 1};
    private static final boolean[] VERT_ANIM_HFLIP = {false, true, false, true};

    /** Vertical collision animation: frame 2, then advance routine (delete). */
    private static final int VERT_COLLIDE_FRAME = 2;

    /**
     * Horizontal animation: frames {3, 3-vflip, 4, 4-vflip}.
     * ROM encodes: 3, $43, 4, $44 where bit 6 ($40) = V-flip via rol.b #3 → bit 1.
     * The horizontal flame shimmers vertically (up-down mirror), NOT directional H-flip.
     */
    private static final int[] HORIZ_ANIM_FRAMES = {3, 3, 4, 4};
    private static final boolean[] HORIZ_ANIM_VFLIP = {false, true, false, true};

    /** Horizontal collision animation: frame 5, then advance routine (delete). */
    private static final int HORIZ_COLLIDE_FRAME = 5;

    /** SFX ID for fireball: sfx_Fireball = $AE (from Constants.asm). */
    private static final int SFX_FIREBALL = Sonic1Sfx.AE_UNUSED.id;

    /** Debug color for lava ball (bright orange). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(255, 140, 0);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Current subtype - can change when ball hits terrain (e.g., type 4 -> 8). */
    private int currentSubtype;

    /** Whether this ball started as a horizontal type (subtypes 6-7). */
    private boolean isHorizontal;

    /**
     * Base V-flip from obStatus bit 1, set when velY < 0 (moving up).
     * XORed with animation V-flip in AnimateSprite: eori.b #2,obRender(a0).
     * From LBall_Type00: bclr/bset #1,obStatus(a0) based on velY sign.
     */
    private boolean statusVFlip;

    /**
     * Base H-flip from obStatus bit 0, set for Type06 (left), cleared for Type07 (right).
     * XORed with animation H-flip in AnimateSprite: eori.b #1,obRender(a0).
     * From LBall_Type06: bset #0,obStatus(a0) / LBall_Type07: bclr #0,obStatus(a0).
     */
    private boolean statusHFlip;

    /** Current X position (updated each frame). */
    private int currentX;

    /** Current Y position (updated each frame). */
    private int currentY;

    /** Y velocity in subpixels (signed 16-bit). */
    private int velY;

    /** X velocity in subpixels (signed 16-bit). */
    private int velX;

    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16.16 SpeedToPos integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    /** Original Y position (objoff_30), used for deletion check in subtypes 0-3. */
    private int originY;

    /** Animation frame index (cycling through animation frames). */
    private int animFrameIndex;

    /** Animation timer (counts up to ANIM_SPEED). */
    private int animTimer;

    /** Whether the ball is in collision animation (afRoutine -> will be deleted). */
    private boolean inCollisionAnim;

    /** Timer for collision animation before deletion. */
    private int collisionAnimTimer;

    /** Art key to use for rendering (zone-dependent: MZ_FIREBALL or SLZ_FIREBALL). */
    private String artKey;
    /** Boss-spawned variant uses subtype word $00FF in ROM. */
    private boolean bossDroppedVariant;
    /** Priority bucket can be elevated for boss-spawned lava. */
    private int priorityBucket;

    public Sonic1LavaBallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LavaBall");

        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.originY = spawn.y();  // objoff_30: move.w obY(a0),objoff_30(a0)

        int subtype = spawn.subtype() & 0xFF;
        // BossMarble_MakeLava writes obSubtype as a WORD ($00FF).
        // In the original object layout this sets obSubtype=0 and objoff_29=$FF.
        // Model this explicitly so boss lava uses vertical type-0 motion.
        this.bossDroppedVariant = (subtype == 0xFF);
        if (bossDroppedVariant) {
            subtype = 0;
        }
        // Clamp to valid speed table range
        int speedIndex = Math.min(subtype, SPEEDS.length - 1);
        this.currentSubtype = subtype;

        // From LBall_Main:
        // cmpi.b #6,obSubtype(a0) / blo.s .sound
        // Subtypes < 6 are vertical; >= 6 are horizontal
        this.isHorizontal = (subtype >= HORIZONTAL_THRESHOLD);

        if (isHorizontal) {
            // move.w obVelY(a0),obVelX(a0) ; set horizontal speed
            // move.w #0,obVelY(a0) ; delete vertical speed
            this.velX = SPEEDS[speedIndex];
            this.velY = 0;
        } else {
            this.velX = 0;
            this.velY = SPEEDS[speedIndex];
        }

        this.animFrameIndex = 0;
        this.animTimer = 0;
        this.inCollisionAnim = false;
        this.collisionAnimTimer = 0;
        this.priorityBucket = bossDroppedVariant
                ? RenderPriority.clamp(PRIORITY_NORMAL + 2)
                : RenderPriority.clamp(PRIORITY_NORMAL);

        // Initialize obStatus flip bits based on velocity direction
        // Vertical: V-flip when moving up (velY < 0)
        this.statusVFlip = (this.velY < 0);
        // Horizontal: H-flip for Type06 (moving left, velX < 0)
        this.statusHFlip = isHorizontal && (this.velX < 0);

    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    private void ensureInitialized() {
        if (artKey == null) {
            // Determine art key based on current zone
            // ROM: cmpi.b #id_SLZ,(v_zone).w / bne.s .notSLZ
            int zoneIndex = services().romZoneId();
            artKey = (zoneIndex == Sonic1Constants.ZONE_SLZ)
                    ? ObjectArtKeys.SLZ_FIREBALL : ObjectArtKeys.MZ_FIREBALL;

            // Play fireball sound: move.w #sfx_Fireball,d0 / jsr (QueueSound2).l
            services().playSfx(SFX_FIREBALL);
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (inCollisionAnim) {
            // Collision animation plays one frame then afRoutine increments obRoutine -> delete.
            // ROM: afRoutine adds 2 to obRoutine, making it 4 = LBall_Delete -> bra.w DeleteObject
            collisionAnimTimer++;
            if (collisionAnimTimer >= ANIM_SPEED) {
                setDestroyed(true);
            }
            return;
        }

        // ROM: LBall_Action (Routine 2)
        // Dispatch to type-specific handler
        updateTypeLogic();
        // bsr.w SpeedToPos
        applyVelocity();
        // AnimateSprite
        updateAnimation();
    }

    /**
     * Dispatches to type-specific movement logic.
     * From LBall_TypeIndex dispatch table.
     */
    private void updateTypeLogic() {
        switch (currentSubtype) {
            case 0, 1, 2, 3 -> updateType00();   // LBall_Type00: vertical with gravity
            case 4 -> updateType04();              // LBall_Type04: fly up until ceiling
            case 5 -> updateType05();              // LBall_Type05: fall until floor
            case 6 -> updateType06();              // LBall_Type06: move left until wall
            case 7 -> updateType07();              // LBall_Type07: move right until wall
            case 8 -> { }                          // LBall_Type08: rts (no-op)
            default -> { }
        }
    }

    /**
     * Subtypes 0-3: Vertical fireballs with gravity.
     * <pre>
     * LBall_Type00:
     *   addi.w #$18,obVelY(a0)           ; increase object's downward speed
     *   move.w objoff_30(a0),d0
     *   cmp.w  obY(a0),d0                ; has object fallen back to its original position?
     *   bhs.s  loc_E41E                  ; if not, branch
     *   addq.b #2,obRoutine(a0)          ; goto "LBall_Delete" routine
     * loc_E41E:
     *   bclr #1,obStatus(a0)
     *   tst.w obVelY(a0) / bpl.s / bset #1,obStatus(a0)
     * </pre>
     */
    private void updateType00() {
        // addi.w #$18,obVelY(a0)
        velY += GRAVITY;

        // move.w objoff_30(a0),d0 / cmp.w obY(a0),d0 / bhs.s loc_E41E
        // Check if fallen back past original position (d0 = originY, compared to currentY)
        // bhs = branch if higher or same (unsigned), so if originY >= currentY, don't delete
        // Delete when currentY > originY (ball has fallen past origin)
        if (currentY > originY && velY > 0) {
            // addq.b #2,obRoutine(a0) ; goto LBall_Delete
            setDestroyed(true);
        }

        // loc_E41E: update obStatus V-flip based on velocity direction
        // bclr #1,obStatus(a0) / tst.w obVelY(a0) / bpl.s locret / bset #1,obStatus(a0)
        statusVFlip = (velY < 0);
    }

    /**
     * Subtype 4: Fly upward until hitting ceiling.
     * <pre>
     * LBall_Type04:
     *   bset #1,obStatus(a0)
     *   bsr.w ObjHitCeiling
     *   tst.w d1 / bpl.s locret_E452
     *   move.b #8,obSubtype(a0)
     *   move.b #1,obAnim(a0)
     *   move.w #0,obVelY(a0)
     * </pre>
     */
    private void updateType04() {
        // ObjHitCeiling: probes at (x, y - obHeight) upward
        TerrainCheckResult result = ObjectTerrainUtils.checkCeilingDist(currentX, currentY, Y_RADIUS);
        // tst.w d1 / bpl.s locret_E452 — if d1 >= 0, no collision
        if (result.foundSurface() && result.distance() < 0) {
            // Hit ceiling: stop and play collision animation
            currentSubtype = 8;
            velY = 0;
            enterCollisionAnimation();
        }
    }

    /**
     * Subtype 5: Fall downward until hitting floor.
     * <pre>
     * LBall_Type05:
     *   bclr #1,obStatus(a0)
     *   bsr.w ObjFloorDist
     *   tst.w d1 / bpl.s locret_E474
     *   move.b #8,obSubtype(a0)
     *   move.b #1,obAnim(a0)
     *   move.w #0,obVelY(a0)
     * </pre>
     */
    private void updateType05() {
        // ObjFloorDist: probes at (x, y + obHeight) downward
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        if (result.foundSurface() && result.distance() < 0) {
            currentSubtype = 8;
            velY = 0;
            enterCollisionAnimation();
        }
    }

    /**
     * Subtype 6: Move left until hitting wall.
     * <pre>
     * LBall_Type06:
     *   bset #0,obStatus(a0)
     *   moveq #-8,d3
     *   bsr.w ObjHitWallLeft
     *   tst.w d1 / bpl.s locret_E498
     *   move.b #8,obSubtype(a0)
     *   move.b #3,obAnim(a0)
     *   move.w #0,obVelX(a0)
     * </pre>
     */
    private void updateType06() {
        // ObjHitWallLeft: probes at (x - d3, y) leftward
        // moveq #-8,d3 → checking 8 pixels left of center
        TerrainCheckResult result = ObjectTerrainUtils.checkLeftWallDist(currentX - 8, currentY);
        if (result.foundSurface() && result.distance() < 0) {
            currentSubtype = 8;
            velX = 0;
            enterCollisionAnimation();
        }
    }

    /**
     * Subtype 7: Move right until hitting wall.
     * <pre>
     * LBall_Type07:
     *   bclr #0,obStatus(a0)
     *   moveq #8,d3
     *   bsr.w ObjHitWallRight
     *   tst.w d1 / bpl.s locret_E4BC
     *   move.b #8,obSubtype(a0)
     *   move.b #3,obAnim(a0)
     *   move.w #0,obVelX(a0)
     * </pre>
     */
    private void updateType07() {
        // ObjHitWallRight: probes at (x + d3, y) rightward
        // moveq #8,d3 → checking 8 pixels right of center
        TerrainCheckResult result = ObjectTerrainUtils.checkRightWallDist(currentX + 8, currentY);
        if (result.foundSurface() && result.distance() < 0) {
            currentSubtype = 8;
            velX = 0;
            enterCollisionAnimation();
        }
    }

    /**
     * Enters the collision animation state (frame plays once then object deletes).
     * ROM: afRoutine in animation script increments obRoutine by 2 -> routine 4 = LBall_Delete.
     */
    private void enterCollisionAnimation() {
        inCollisionAnim = true;
        collisionAnimTimer = 0;
        animFrameIndex = 0;
        animTimer = 0;
    }

    /**
     * Applies velocity to position using ROM-accurate 16.16 fixed-point arithmetic.
     * <p>ROM SpeedToPos: {@code ext.l d0 / asl.l #8,d0 / add.l d0,obY(a0)}
     * <p>The ROM treats position as a 32-bit value: (pixel << 16) | subpixel.
     * Velocity is sign-extended to 32 bits and shifted left 8 before adding.
     * This naturally propagates carries/borrows between the fractional and
     * integer parts, unlike Java integer division which truncates toward zero
     * and produces incorrect results for negative velocities with non-zero
     * fractional parts (e.g., velY=-0x318 gives -3px instead of ROM's -4px).
     */
    private void applyVelocity() {
        // SpeedToPos 16.16 fixed-point integration for both X and Y.
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = velX;
        motion.yVel = velY;
        SubpixelMotion.speedToPos(motion);
        currentX = motion.x;
        currentY = motion.y;
    }

    /**
     * Updates animation frame cycling.
     * From Ani_Fire animations with speed 5 (advances every 6 frames).
     */
    private void updateAnimation() {
        animTimer++;
        if (animTimer >= ANIM_SPEED) {
            animTimer = 0;
            if (!inCollisionAnim) {
                if (isHorizontal) {
                    animFrameIndex = (animFrameIndex + 1) % HORIZ_ANIM_FRAMES.length;
                } else {
                    animFrameIndex = (animFrameIndex + 1) % VERT_ANIM_FRAMES.length;
                }
            }
        }
    }

    // ========================================================================
    // TouchResponseProvider Implementation
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) return;

        int frameIndex;
        boolean hFlip = false;
        boolean vFlip = false;

        if (inCollisionAnim) {
            // Collision animation: single frame (vertcollide or horicollide)
            if (isHorizontal) {
                frameIndex = HORIZ_COLLIDE_FRAME;  // frame 5
            } else {
                frameIndex = VERT_COLLIDE_FRAME;   // frame 2
            }
            // Apply status flips during collision anim
            hFlip = statusHFlip;
            vFlip = statusVFlip;
        } else if (isHorizontal) {
            // Horizontal animation: anim 2 (.horizontal)
            // S1 AnimateSprite rol.b #3 maps: anim bit 6 → obRender bit 1 (V-flip)
            // obRender bit 0 (H-flip) = status bit 0 XOR 0 (no anim H-flip)
            // obRender bit 1 (V-flip) = status bit 1 XOR anim_vflip
            frameIndex = HORIZ_ANIM_FRAMES[animFrameIndex];
            hFlip = statusHFlip;
            vFlip = statusVFlip ^ HORIZ_ANIM_VFLIP[animFrameIndex];
        } else {
            // Vertical animation: anim 0 (.vertical)
            // S1 AnimateSprite rol.b #3 maps: anim bit 5 → obRender bit 0 (H-flip)
            // obRender bit 0 (H-flip) = status bit 0 XOR anim_hflip
            // obRender bit 1 (V-flip) = status bit 1 XOR 0 (no anim V-flip)
            frameIndex = VERT_ANIM_FRAMES[animFrameIndex];
            hFlip = statusHFlip ^ VERT_ANIM_HFLIP[animFrameIndex];
            vFlip = statusVFlip;
        }

        renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, vFlip);
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(currentX, currentY, 0x14, currentSubtype, 0, false, 0);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public boolean requiresSameFrameUpdate() {
        // ROM: LBall_Main falls through to LBall_Action — the ball's first movement
        // happens in the same ExecuteObjects pass as its creation by the maker.
        // Without this, the ball is 1 frame behind the ROM, causing position-dependent
        // collision differences (e.g., spurious hurt at MZ1 frame 1251).
        return true;
    }

    @Override
    public boolean isPersistent() {
        // Dynamically spawned - persist while on screen, destroy when off screen.
        // ROM: out_of_range.w DeleteObject (LBall_ChkDel)
        if (isDestroyed()) {
            return false;
        }
        return isOnScreen(64);
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(currentX, currentY, Y_RADIUS, Y_RADIUS, 1.0f, 0.55f, 0.0f);
        ctx.drawWorldLabel(currentX, currentY, -1,
                String.format("LavaBall[%d] vx=%d vy=%d %s",
                        currentSubtype, velX, velY,
                        inCollisionAnim ? "COLLIDE" : ""),
                DEBUG_COLOR);
    }
}
