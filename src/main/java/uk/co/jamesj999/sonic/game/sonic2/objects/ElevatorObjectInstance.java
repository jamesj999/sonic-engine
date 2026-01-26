package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Elevator Object (ObjD5).
 * <p>
 * A platform that moves vertically when the player stands on it, returning to
 * its original position when the player leaves.
 * <p>
 * <b>Behavior (State Machine):</b>
 * <ul>
 *   <li>State 0: Wait for contact - platform waits for player to stand on it</li>
 *   <li>State 2: Move up - elevator rises toward initial Y position, plays sound</li>
 *   <li>State 4: Player departs - waits for player to leave the platform</li>
 *   <li>State 6: Return - elevator returns down to spawn position</li>
 * </ul>
 * <p>
 * <b>Subtype Encoding:</b>
 * <ul>
 *   <li>subtype * 4 = Y offset from spawn position</li>
 *   <li>Bit 3 of render flags (x_flip) inverts direction:
 *       Clear = platform starts ABOVE spawn, moves DOWN to spawn, then UP;
 *       Set = platform starts BELOW spawn, moves UP to spawn, then DOWN</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm ObjD5 (CNZ Elevator)
 */
public class ElevatorObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    // State constants (from disassembly routine offsets)
    private static final int STATE_WAIT_FOR_CONTACT = 0;
    private static final int STATE_MOVE_UP = 2;
    private static final int STATE_PLAYER_DEPARTS = 4;
    private static final int STATE_RETURN = 6;

    // Movement velocity: 8 pixels per frame (in 16.8 fixed point = 0x800)
    private static final int VELOCITY_INCREMENT = 0x800;

    // Platform collision dimensions
    // d1 = 0x10 (half-width = 16 pixels)
    // d3 = 9 (platform height for collision)
    private static final int HALF_WIDTH = 16;
    private static final int PLATFORM_HEIGHT = 9;

    // Position tracking
    private int x;              // Current X (constant, same as spawn)
    private int y;              // Current Y position (integer part)
    private int ySub;           // 16.8 fixed-point Y position
    private int yVel;           // Y velocity in 16.8 fixed-point

    // Target positions
    private int baseY;          // Original spawn Y position
    private int targetY;        // Target Y position based on subtype

    // State
    private int state;
    private boolean inverted;   // True if bit 3 of render flags is set (invert direction)

    // Contact tracking
    private int lastContactFrame = -2;

    // Dynamic spawn for moving position
    private ObjectSpawn dynamicSpawn;

    public ElevatorObjectInstance(ObjectSpawn spawn, String name) {
        // Use platform half-width for debug box, cyan color
        super(spawn, name, HALF_WIDTH, PLATFORM_HEIGHT, 0.2f, 0.8f, 0.8f, false);
        init();
    }

    private void init() {
        x = spawn.x();
        baseY = spawn.y();

        // Inverted flag from bit 3 of render flags (status.npc.x_flip)
        inverted = (spawn.renderFlags() & 0x08) != 0;

        // Y offset = subtype * 4
        int yOffset = (spawn.subtype() & 0xFF) * 4;

        if (inverted) {
            // Inverted: platform starts BELOW spawn, moves UP to spawn, then DOWN
            // Initial position is at spawn Y
            // Target is spawn Y + offset (below spawn)
            y = baseY;
            targetY = baseY + yOffset;
        } else {
            // Normal: platform starts ABOVE spawn (at target), moves DOWN to spawn, then UP
            // Initial position is spawn Y - offset (above spawn)
            // Target is spawn Y - offset
            y = baseY - yOffset;
            targetY = baseY - yOffset;
        }

        // Initialize 16.8 fixed-point position (y_sub = 0x8000 equivalent)
        ySub = y << 8;
        yVel = 0;
        state = STATE_WAIT_FOR_CONTACT;

        refreshDynamicSpawn();
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return dynamicSpawn != null ? dynamicSpawn : spawn;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: d1 = 0x10 (half-width), d3 = 9 (platform height)
        return new SolidObjectParams(HALF_WIDTH, PLATFORM_HEIGHT, PLATFORM_HEIGHT + 1);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Elevators are platform objects - only solid from the top
        return true;
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (contact.standing() || contact.touchTop()) {
            lastContactFrame = frameCounter;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        boolean standing = (frameCounter - lastContactFrame) <= 1;

        switch (state) {
            case STATE_WAIT_FOR_CONTACT -> updateWaitForContact(standing);
            case STATE_MOVE_UP -> updateMoveUp();
            case STATE_PLAYER_DEPARTS -> updatePlayerDeparts(standing);
            case STATE_RETURN -> updateReturn();
        }

        refreshDynamicSpawn();
    }

    /**
     * State 0: Wait for player to stand on the platform.
     * When contact detected, transition to State 2 (move up) and play sound.
     */
    private void updateWaitForContact(boolean standing) {
        if (standing) {
            state = STATE_MOVE_UP;
            playElevatorSound();
        }
    }

    /**
     * State 2: Move up toward target position.
     * Velocity accumulates by ±8 per frame (acceleration toward target).
     * When velocity reaches zero (after overshooting and direction change),
     * transition to State 4 (player departs).
     * <p>
     * ROM behavior (s2.asm lines 58438-58448):
     * - add.w d1,y_vel(a0) - velocity += ±8 (accumulates)
     * - bne.s + - if velocity != 0, return (only transition when vel == 0)
     */
    private void updateMoveUp() {
        int target = inverted ? baseY : targetY;

        // Add to velocity based on direction to target (acceleration)
        // ROM: moveq #8,d1 / moveq #-8,d1 then add.w d1,y_vel(a0)
        if (y > target) {
            yVel -= VELOCITY_INCREMENT;  // Accelerate upward
        } else if (y < target) {
            yVel += VELOCITY_INCREMENT;  // Accelerate downward
        }

        // Apply velocity to position
        ySub += yVel;
        y = ySub >> 8;

        // ROM: bne.s + - transition only when velocity == 0
        // This naturally occurs when platform overshoots and direction changes
        if (yVel == 0) {
            y = target;
            ySub = target << 8;
            state = STATE_PLAYER_DEPARTS;
        }
    }

    /**
     * State 4: Wait for player to leave the platform.
     * When player leaves, transition to State 6 (return) and play sound.
     */
    private void updatePlayerDeparts(boolean standing) {
        if (!standing) {
            state = STATE_RETURN;
            playElevatorSound();
        }
    }

    /**
     * State 6: Return to spawn position.
     * Velocity accumulates by ±8 per frame (acceleration toward target).
     * When velocity reaches zero (after overshooting and direction change),
     * transition back to State 0 (wait for contact).
     * <p>
     * ROM behavior (s2.asm lines 58472-58492):
     * - add.w d1,y_vel(a0) - velocity += ±8 (accumulates)
     * - bne.s + - if velocity != 0, return (only transition when vel == 0)
     */
    private void updateReturn() {
        int target = inverted ? targetY : baseY;

        // Add to velocity based on direction to target (acceleration)
        if (y > target) {
            yVel -= VELOCITY_INCREMENT;  // Accelerate upward
        } else if (y < target) {
            yVel += VELOCITY_INCREMENT;  // Accelerate downward
        }

        // Apply velocity to position
        ySub += yVel;
        y = ySub >> 8;

        // Transition only when velocity == 0
        if (yVel == 0) {
            y = target;
            ySub = target << 8;
            state = STATE_WAIT_FOR_CONTACT;
        }
    }

    private void playElevatorSound() {
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(GameSound.CNZ_ELEVATOR);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    private void refreshDynamicSpawn() {
        if (dynamicSpawn == null || dynamicSpawn.y() != y) {
            dynamicSpawn = new ObjectSpawn(
                    x,
                    y,
                    spawn.objectId(),
                    spawn.subtype(),
                    spawn.renderFlags(),
                    spawn.respawnTracked(),
                    spawn.rawYWord());
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_ELEVATOR);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        // Frame 0 only, no flipping
        renderer.drawFrameIndex(0, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
