package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
        implements SolidObjectProvider, SolidObjectListener {

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
    private final int targetX;  // Original spawn = target X
    private final int targetY;  // Original spawn = target Y
    private int xVel, yVel;     // Velocity (16-bit signed)
    private final int moveType; // 0 = horizontal, 2 = vertical

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

        this.xSub = 0;
        this.ySub = 0;
        this.xVel = 0;
        this.yVel = 0;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Apply movement based on type
        if (moveType == MOVE_HORIZONTAL) {
            updateHorizontalMovement();
        } else {
            updateVerticalMovement();
        }
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
        // ROM: move.w objoff_30(a0),d0 / cmp.w x_pos(a0),d0 / bge.s + / neg.w d1
        int accel = ACCELERATION;
        if (targetX < x) {
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
        // Accelerate toward target
        int accel = ACCELERATION;
        if (targetY < y) {
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
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Platform carrying is handled by the solid object system
        // This callback is for any special behavior when player contacts the platform
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Calculate offset from spawn position to current position
        int offsetX = x - spawn.x();
        int offsetY = y - spawn.y();
        return new SolidObjectParams(HALF_WIDTH, AIR_HALF_HEIGHT, GROUND_HALF_HEIGHT, offsetX, offsetY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
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
}
