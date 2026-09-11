package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.BoxObjectInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.*;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Big Block (Object 0xD4) - Large 64x64 oscillating platform.
 * <p>
 * A large platform that oscillates back and forth around its spawn position.
 * The block accelerates toward its target position, causing it to overshoot
 * and create a pendulum-like motion.
 * <p>
 * <b>Behavior:</b>
 * <ul>
 *   <li>Subtype 0x00: Horizontal movement</li>
 *   <li>Subtype 0x02: Vertical movement</li>
 *   <li>Starts 96 pixels away from spawn position (direction based on flip flags)</li>
 *   <li>Accelerates ±4 pixels/frame toward target position</li>
 *   <li>Velocity accumulates causing overshoot and oscillation</li>
 *   <li>Full solid platform collision</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 58279-58366 (ObjD4)
 */
public class CNZBigBlockObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // Constants from disassembly
    private static final int INITIAL_OFFSET = 0x60;      // 96 pixels initial offset from spawn
    private static final int ACCELERATION = 4;           // velocity change per frame

    // Solid collision dimensions from disassembly
    // d1 = half_width + 11 = 0x2B (43)
    // d2 = air_half_height = 0x20 (32)
    // d3 = ground_half_height = 0x21 (33)
    private static final int HALF_WIDTH = 0x2B;          // d1 = 43
    private static final int AIR_HALF_HEIGHT = 0x20;     // d2 = 32
    private static final int GROUND_HALF_HEIGHT = 0x21;  // d3 = 33

    // Movement type from subtype
    private static final int MOVE_HORIZONTAL = 0x00;
    private static final int MOVE_VERTICAL = 0x02;

    // Position tracking (16.16 fixed point)
    // The ROM uses 32-bit positions with 16-bit integer and 16-bit fractional parts
    private int x, y;           // Current position (integer part)
    private int xSub, ySub;     // Sub-pixel (fractional part)
    private int targetX;  // Original spawn = target X
    private int targetY;  // Original spawn = target Y
    private int xVel, yVel;     // Velocity (16-bit signed)
    private int moveType; // 0 = horizontal, 2 = vertical
    private int updateCount;

    public CNZBigBlockObjectInstance(ObjectSpawn spawn, String name) {
        // 64x64 pixel visual size, half dimensions = 32
        super(spawn, name, 32, 32, 0.8f, 0.4f, 0.8f, false);

        // Store target position (spawn position)
        this.targetX = spawn.x();
        this.targetY = spawn.y();

        // Determine move type from subtype
        this.moveType = spawn.subtype() & 0xFF;

        // Calculate initial position based on flip flags
        // ROM: move.w #-$60,d0 / btst #0,status(a0) / beq.s + / neg.w d0
        int offset = INITIAL_OFFSET;

        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        boolean yFlip = (spawn.renderFlags() & 0x02) != 0;

        if (moveType == MOVE_HORIZONTAL) {
            // Horizontal movement: apply offset to X
            if (!xFlip) {
                offset = -offset;
            }
            this.x = targetX + offset;
            this.y = targetY;
        } else {
            // Vertical movement: apply offset to Y
            if (!yFlip) {
                offset = -offset;
            }
            this.x = targetX;
            this.y = targetY + offset;
        }

        // ROM: move.w #$8000,x_sub(a0) / move.w #$8000,y_sub(a0)
        this.xSub = 0x8000;
        this.ySub = 0x8000;
        this.xVel = 0;
        this.yVel = 0;
    }

    @Override
    public CNZBigBlockObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CNZBigBlockObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Apply movement based on type
        if (moveType == MOVE_HORIZONTAL) {
            updateHorizontalMovement();
        } else {
            updateVerticalMovement();
        }
        updateCount++;
    }

    /**
     * Updates horizontal movement.
     * ROM: loc_2B7B8 - Accelerate toward target X, apply velocity to position.
     * <p>
     * ROM uses 16.16 fixed point position and 1/256 pixel/frame velocity:
     * - move.w x_vel(a0),d0    ; get 16-bit velocity (in 1/256 pixels/frame)
     * - ext.l d0               ; sign extend to 32-bit
     * - asl.l #8,d0            ; shift left 8 bits to align with 16.16 format
     * - add.l d0,x_pos(a0)     ; add to 32-bit position
     */
    private void updateHorizontalMovement() {
        // Accelerate toward target
        // ROM: move.w objoff_30(a0),d0 / cmp.w x_pos(a0),d0 / bhi.s + / neg.w d1.
        // Equality accelerates negative; otherwise ObjD4 drifts one phase ahead at each crossing.
        int accel = ACCELERATION;
        if (targetX <= x) {
            accel = -accel;
        }
        xVel += accel;

        // Apply velocity to position (16.16 fixed point)
        // Velocity is in 1/256 pixels/frame, shift left 8 to align with 16.16 position
        // xSub is the low 16 bits (fractional part), x is the high 16 bits (integer part)
        int velocityShifted = xVel << 8;
        int newSub = xSub + velocityShifted;
        x += newSub >> 16;  // Carry integer overflow to x
        xSub = newSub & 0xFFFF;
    }

    /**
     * Updates vertical movement.
     * ROM: loc_2B7E0 - Accelerate toward target Y, apply velocity to position.
     */
    private void updateVerticalMovement() {
        // Same `bhi.s` equality-negative rule as ObjD4_Horizontal (s2.asm:58375-58384).
        int accel = ACCELERATION;
        if (targetY <= y) {
            accel = -accel;
        }
        yVel += accel;

        // Apply velocity to position (16.16 fixed point)
        int velocityShifted = yVel << 8;
        int newSub = ySub + velocityShifted;
        y += newSub >> 16;  // Carry integer overflow to y
        ySub = newSub & 0xFFFF;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Platform carrying is handled by the solid object system
        // This callback is for any special behavior when player contacts the platform
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // No offset needed: getX()/getY() already return the current oscillating position,
        // so the collision anchor is already correct without additional displacement.
        return SolidObjectParams.of(HALF_WIDTH, AIR_HALF_HEIGHT, GROUND_HALF_HEIGHT, 0, 0);
    }

    @Override
    public boolean fullSolidBottomOverlapUsesCurrentYRadiusOnly(PlayableEntity player) {
        // ObjD4 calls the shared S2 SolidObject helper (s2.asm:58348-58356).
        // SolidObject_cont builds the bottom reject bound after adding the
        // live y_radius(a1) to d2 (s2.asm:35135-35166), so rolling players use
        // the smaller rolling radius on both halves.
        return true;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ObjD4_Main passes objoff_30, the saved target/origin X, to MarkObjGone2
        // after SolidObject (s2.asm:58380-58388). The oscillating x_pos can leave
        // the object-load band while the origin is still in range; unloading on the
        // current X incorrectly leaves the spawn dormant until the cursor re-enters.
        return targetX;
    }

    @Override
    public int getOnScreenHalfWidth() {
        // ObjD4_Init sets width_pixels to $20 (s2.asm:58321), and the shared
        // solid-contact render gate uses this footprint as the ROM render flag proxy.
        return 0x20;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x20;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_BIG_BLOCK);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;

        // Render at current computed position (not base spawn position)
        renderer.drawFrameIndex(0, x, y, hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    protected int getHalfWidth() {
        return 32;
    }

    @Override
    protected int getHalfHeight() {
        return 32;
    }

    /**
     * Returns the current X position of the platform.
     * Overrides base to return the oscillating position, not the spawn position.
     * Required for SolidContacts to correctly track platform movement and carry the player.
     */
    @Override
    public int getX() {
        return x;
    }

    /**
     * Returns the current Y position of the platform.
     * Overrides base to return the oscillating position, not the spawn position.
     * Required for SolidContacts to correctly track platform movement and carry the player.
     */
    @Override
    public int getY() {
        return y;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("upd=%d sub=(%04X,%04X) vel=(%04X,%04X) target=(%04X,%04X)",
                updateCount,
                xSub & 0xFFFF,
                ySub & 0xFFFF,
                xVel & 0xFFFF,
                yVel & 0xFFFF,
                targetX & 0xFFFF,
                targetY & 0xFFFF);
    }
}
