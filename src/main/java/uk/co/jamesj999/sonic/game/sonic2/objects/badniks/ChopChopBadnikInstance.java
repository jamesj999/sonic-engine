package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ChopChop (0x91) - Piranha/shark Badnik from Aquatic Ruin Zone.
 * A 4-state patrolling badnik that detects the player and charges at them.
 *
 * Behavior from disassembly (obj91.asm lines 73137-73302):
 * 1. PATROLLING: Move at speed 0x40, switch direction every 512 frames,
 *    spawn bubbles every 80 frames
 * 2. WAITING: Stop for 16 frames when player detected (mouth animation)
 * 3. CHARGING: Move toward player at 2 pixels/frame horizontal,
 *    0.5 pixels/frame downward
 *
 * Player detection:
 * - Horizontal range: 32-160 pixels (0x20-0xA0)
 * - Vertical range: -32 to +31 pixels (asymmetric)
 * - Only attacks if already moving toward the player
 */
public class ChopChopBadnikInstance extends AbstractBadnikInstance {
    // Collision size from subObjData in disassembly
    private static final int COLLISION_SIZE_INDEX = 0x02;

    // Movement constants from disassembly
    private static final int PATROL_SPEED = 0x40;           // 64 subpixels/frame (move.w #$40,x_vel(a0))
    private static final int MOVE_TIMER_INIT = 0x200;       // 512 frames (move.w #$200,objoff_36(a0))
    private static final int WAIT_TIME = 0x10;              // 16 frames (move.b #$10,anim_frame_duration(a0))
    private static final int CHARGE_SPEED_X = 2;            // 2 pixels/frame
    private static final int CHARGE_SPEED_Y_SUBPIXEL = 0x80; // 0.5 pixels/frame (addi.w #$80,y_pos(a0))

    // Detection ranges from disassembly
    private static final int DETECTION_RANGE_MIN = 0x20;    // 32 pixels (subi.w #$20,d0)
    private static final int DETECTION_RANGE_MAX = 0xA0;    // 160 pixels (cmpi.w #$A0,d0)
    private static final int DETECTION_RANGE_V = 0x20;      // Vertical offset for asymmetric range check

    // Animation constants
    private static final int ANIM_DELAY = 4;                // 4 ticks per frame

    // States matching disassembly's routine labels
    private enum State {
        PATROLLING,  // Loc_363F2 - normal movement
        WAITING,     // Loc_36468 - pause before attack
        CHARGING     // Loc_3648A - attack run
    }

    private State state;
    private int moveTimer;           // objoff_36 - frames until direction switch
    private int waitTimer;           // anim_frame_duration - frames until charge
    private int ySubpixel;           // Subpixel accumulator for y movement during charge
    private final int startX;        // Initial X position for direction reference

    public ChopChopBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "ChopChop");
        this.state = State.PATROLLING;
        this.moveTimer = MOVE_TIMER_INIT;
        this.waitTimer = 0;
        this.ySubpixel = 0;
        this.startX = spawn.x();

        // Initial facing based on render_flags (status.npc.x_flip bit)
        // From disassembly: if x_flip bit is SET, velocity stays positive (moving RIGHT)
        // if x_flip bit is CLEAR, velocity is negated (moving LEFT)
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;  // x_flip=1 means facing RIGHT, so facingLeft = false

        // Set initial velocity based on facing direction
        xVelocity = facingLeft ? -PATROL_SPEED : PATROL_SPEED;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case PATROLLING -> updatePatrolling(frameCounter, player);
            case WAITING -> updateWaiting(frameCounter);
            case CHARGING -> updateCharging(player);
        }
    }

    /**
     * Patrolling state (Loc_363F2):
     * - Move horizontally at patrol speed
     * - Switch direction every 512 frames
     * - Check for player detection
     */
    private void updatePatrolling(int frameCounter, AbstractPlayableSprite player) {
        // Apply velocity (convert from subpixels: velocity is in 1/256 pixels)
        currentX += (xVelocity >> 8);

        // Decrement direction switch timer
        moveTimer--;
        if (moveTimer <= 0) {
            // Switch direction
            facingLeft = !facingLeft;
            xVelocity = facingLeft ? -PATROL_SPEED : PATROL_SPEED;
            moveTimer = MOVE_TIMER_INIT;
        }

        // Check for player detection
        if (player != null && detectPlayer(player)) {
            // Player detected - transition to waiting state
            state = State.WAITING;
            waitTimer = WAIT_TIME;
            xVelocity = 0; // Stop moving
        }

        // TODO: Spawn bubbles every 80 frames (requires SmallBubbles object support)
    }

    /**
     * Waiting state (Loc_36468):
     * - Stop movement for 16 frames
     * - Animate mouth open/close
     * - Then transition to charging
     */
    private void updateWaiting(int frameCounter) {
        waitTimer--;
        if (waitTimer <= 0) {
            // Transition to charging
            state = State.CHARGING;
        }
    }

    /**
     * Charging state (Loc_3648A):
     * - Move toward player at high speed
     * - Horizontal: 2 pixels/frame
     * - Vertical: 0.5 pixels/frame downward
     */
    private void updateCharging(AbstractPlayableSprite player) {
        // Move horizontally toward player
        if (facingLeft) {
            currentX -= CHARGE_SPEED_X;
        } else {
            currentX += CHARGE_SPEED_X;
        }

        // Move downward at 0.5 pixels/frame using subpixel accumulator
        ySubpixel += CHARGE_SPEED_Y_SUBPIXEL;
        if (ySubpixel >= 0x100) {
            currentY += (ySubpixel >> 8);
            ySubpixel &= 0xFF;
        }
    }

    /**
     * Player detection logic from disassembly (lines 73247-73280):
     * - Player must be 32-160 pixels away horizontally
     * - Player must be within asymmetric vertical range (-32 to +31 pixels)
     * - ChopChop must be moving toward the player (check actual velocity)
     */
    private boolean detectPlayer(AbstractPlayableSprite player) {
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // Calculate horizontal distance (object - player, matching disassembly order)
        int dx = currentX - playerX;
        int absDx = Math.abs(dx);

        // Check horizontal range: 32-160 pixels
        if (absDx < DETECTION_RANGE_MIN || absDx > DETECTION_RANGE_MAX) {
            return false;
        }

        // Check vertical range using disassembly's asymmetric check:
        // addi.w #$20,d3 ; cmpi.w #$40,d3 ; bhs -> don't charge
        // This means: (object_y - player_y + 0x20) must be < 0x40
        // Equivalent to player being between -32 and +31 pixels vertically
        int dy = currentY - playerY;
        int adjustedDy = dy + DETECTION_RANGE_V;
        if (adjustedDy < 0 || adjustedDy >= 0x40) {
            return false;
        }

        // Check if moving toward player (use actual velocity, not facingLeft flag)
        // dx > 0 means player is to the LEFT, so we need negative velocity (moving left)
        // dx < 0 means player is to the RIGHT, so we need positive velocity (moving right)
        if (dx > 0) {
            // Player to the left - must be moving left (negative velocity)
            if (xVelocity >= 0) {
                return false;
            }
        } else {
            // Player to the right - must be moving right (positive velocity)
            if (xVelocity <= 0) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        // Animation based on state and frame counter
        // Frame 0 = mouth closed, Frame 1 = mouth open
        switch (state) {
            case PATROLLING -> {
                // Slow animation during patrol
                animFrame = ((frameCounter >> 3) & 1); // Toggle every 8 frames
            }
            case WAITING -> {
                // Fast mouth animation during wait
                animFrame = ((frameCounter >> 2) & 1); // Toggle every 4 frames
            }
            case CHARGING -> {
                // Mouth open during charge
                animFrame = 1;
            }
        }
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
        if (destroyed) {
            return;
        }

        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CHOP_CHOP);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Render current animation frame
        // facingLeft = true means sprite needs horizontal flip
        boolean hFlip = facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
